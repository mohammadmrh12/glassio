package com.glasslauncher.tv.net

import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.LocalShell
import com.glasslauncher.tv.data.NuvioWatch
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Nuvio Legacy's playback controlled from Home Assistant, quickly: POST http://<tv>:9091/pause | /resume |
 * /seek {"seconds": n} | /seek_to {"position": s}, with the header X-Relay-Token (the same requests Nuvio TV Relay
 * took, so Home Assistant's scripts use it as they did Relay). The keys go through Glassio's open connection to the
 * TV (no new connection each time) and Nuvio's state comes from its log as it happens (no dumpsys), so a press is
 * checked in a fraction of a second instead of polling the audio service.
 *
 * How Nuvio takes keys (measured): a media key toggles play / pause; Right / Left are +/-10 s only while it plays
 * with the control bar hidden; presses closer than 0.1 s are merged.
 */
object NuvioControl {
    const val PORT = 9091
    @Volatile private var running = false
    private val busy = Any()

    fun start(app: GlassApp) {
        if (running) return
        running = true
        Thread({
            while (running) {
                val st = app.settings
                if (!st.nuvioPush || st.nuvioToken.isBlank()) { Thread.sleep(5_000); continue }
                val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 8) } }
                    .getOrElse { Glog.w("Nuvio control: port $PORT busy (${it.message})"); Thread.sleep(30_000); null } ?: continue
                Glog.i("Nuvio control: listening on $PORT")
                while (running && st.nuvioPush) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    Thread { handle(app, c) }.start()
                }
                runCatching { s.close() }
            }
        }, "glass-nuvio-control").apply { isDaemon = true }.start()
    }

    private fun handle(app: GlassApp, c: Socket) = runCatching {
        c.soTimeout = 10_000
        c.use {
            val inp = BufferedInputStream(it.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n") && head.length < 16_384) { val b = inp.read(); if (b < 0) break; head.append(b.toChar()) }
            val first = head.lineSequence().firstOrNull().orEmpty().split(' ')
            val method = first.getOrNull(0).orEmpty(); val path = first.getOrNull(1).orEmpty().substringBefore('?')
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val token = Regex("(?i)x-relay-token:\\s*(\\S+)").find(head)?.groupValues?.get(1).orEmpty()
            val body = ByteArray(len.coerceIn(0, 16_384)).also { b -> var r = 0; while (r < b.size) { val n = inp.read(b, r, b.size - r); if (n < 0) break; r += n } }
            val j = runCatching { JSONObject(String(body, Charsets.UTF_8).ifBlank { "{}" }) }.getOrDefault(JSONObject())
            val (code, out) = when {
                method == "GET" && path == "/state" -> 200 to state()
                token != app.settings.nuvioToken -> 403 to """{"ok":false,"error":"token"}"""
                method != "POST" -> 404 to """{"ok":false}"""
                else -> synchronized(busy) {
                    val t0 = System.currentTimeMillis()
                    val ok = when (path) {
                        "/pause" -> setPlaying(app, false)
                        "/resume", "/play" -> setPlaying(app, true)
                        "/toggle" -> setPlaying(app, !NuvioWatch.playing)
                        "/seek" -> seek(app, j.optDouble("seconds", 0.0))
                        "/seek_to" -> seek(app, j.optDouble("position", 0.0) - NuvioWatch.position())
                        else -> null
                    }
                    Glog.i("Nuvio control: $path ${if (ok == true) "done" else "failed"} in ${System.currentTimeMillis() - t0} ms")
                    if (ok == null) 404 to """{"ok":false}""" else 200 to JSONObject(state()).put("ok", ok).toString()
                }
            }
            val bytes = out.toByteArray()
            it.getOutputStream().apply {
                write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }
    }.onFailure { Glog.w("Nuvio control request", it) }

    private fun state(): String = JSONObject().put("state", if (NuvioWatch.playing) "playing" else "paused")
        .put("position", (NuvioWatch.position() * 10).roundToInt() / 10.0).put("duration", NuvioWatch.duration).toString()

    private fun keys(app: GlassApp, vararg k: Int, gapMs: Int = 150) =
        LocalShell.quickNow(app, k.joinToString("; ") { "input keyevent $it" }.let { if (k.size > 1) k.joinToString("; sleep ${gapMs / 1000.0}; ") { c -> "input keyevent $c" } else it })

    /** Waits up to [ms] for [cond] (Nuvio's log answers within a few hundred ms). */
    private fun waitFor(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(60) }
        return cond()
    }

    private fun setPlaying(app: GlassApp, want: Boolean): Boolean {
        repeat(4) {
            // Right after a seek Nuvio re-buffers, and while its seek bar shows (layers 1) Play/Pause is ignored
            // (measured): wait for both to end first (the seek bar goes after a few seconds).
            waitFor(7000) { !NuvioWatch.busy && NuvioWatch.layers != 1 }
            if (NuvioWatch.playing == want) return true
            val since = NuvioWatch.stateAt
            keys(app, 85)
            // A first key can only dismiss Nuvio's "You're watching" overlay: then once more.
            if (waitFor(1500) { NuvioWatch.stateAt != since && NuvioWatch.playing == want }) return true
        }
        return NuvioWatch.playing == want
    }

    private fun seek(app: GlassApp, seconds: Double): Boolean {
        if (abs(seconds) < 5) return true
        val wasPaused = !NuvioWatch.playing
        val t0 = System.currentTimeMillis()
        if (wasPaused && !setPlaying(app, true)) return false
        // The control bar must be hidden: Nuvio ignores Right / Left while any of its bars shows (measured).
        // (Back would hide a bar sooner, but with the seek bar up it leaves the player: measured, so never.)
        val since = android.os.SystemClock.uptimeMillis()
        waitFor(15_000) { NuvioWatch.layers == 0 && (!wasPaused || NuvioWatch.layersAt > since) }
        // While it played to get there, the film moved on: that part is taken off.
        val drift = if (wasPaused) (System.currentTimeMillis() - t0) / 1000.0 + 1.5 else 0.0
        val eff = seconds - drift
        val n = (abs(eff) / 10).roundToInt().coerceAtMost(360)
        val key = if (eff > 0) 22 else 21
        val pressed = android.os.SystemClock.uptimeMillis()
        var left = n
        while (left > 0) { val c = minOf(left, 20); keys(app, *IntArray(c) { key }); left -= c }
        if (wasPaused) {
            // Nuvio commits the seek about a second after the last press, re-buffers (its log says "stopped"
            // meanwhile), then plays: pause only once it really plays again.
            waitFor(4000) { NuvioWatch.seekAt > pressed }
            waitFor(6000) { NuvioWatch.playing && NuvioWatch.stateAt > NuvioWatch.seekAt }
            Thread.sleep(300)
            setPlaying(app, false)
        }
        return true
    }
}
