package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Nuvio Legacy's playback, sent to Home Assistant as it happens (Settings → Connections → "Send Nuvio playback to
 * Home Assistant"). Home Assistant used to ask the TV every few seconds over ADB (each time the TV's system worked
 * hard: its CPU jumped); here Glassio follows Nuvio's log as one open stream and the window on screen from its own
 * accessibility service, and sends an event ("glassio_nuvio") only when something changes, plus a heartbeat.
 *
 * The event carries the same text the Home Assistant package read before (the window line, the last line of each
 * kind Nuvio logs, and the newest line), so the package's own logic stays as it is.
 */
object NuvioWatch {
    private const val PKG = "space.nuvio.nativelegacy"
    /** The window on screen ("package/class"), from the accessibility service. */
    @Volatile private var focus = ""
    @Volatile private var running = false
    private val lock = Any()
    // The last line of each kind (as the package's awk kept them), for the running Nuvio process.
    private var pid = ""
    private val slots = arrayOfNulls<String>(6)
    private var lastLine = ""
    private var parado = ""
    @Volatile private var dirty = false
    @Volatile private var lastSent = 0L
    @Volatile private var lastPidCheck = 0L
    @Volatile private var nuvioAlive = false
    @Volatile private var store: java.io.File? = null
    private fun keep() { runCatching { store?.writeText("$pid\n${slots[0].orEmpty()}\n${slots[2].orEmpty()}\n") } }

    /** The title Glassio last opened in Nuvio Legacy (Nuvio doesn't log the title when you press Resume). */
    @Volatile private var openedId = ""
    @Volatile private var openedType = ""
    @Volatile private var openedAt = 0L
    fun opened(type: String, imdb: String) { openedId = imdb; openedType = type; openedAt = System.currentTimeMillis() }
    /** Season and episode of a series opened from Glassio (its Continue Watching knows them), or 0/0. */
    @Volatile var episodeOf: (String) -> Pair<Int, Int> = { 0 to 0 }

    // ---- Nuvio's playback now, for the controls (NuvioControl): from the same log lines.
    /** Playing (true) or paused (false), and since when (uptime ms). */
    @Volatile var playing = false; private set
    @Volatile var stateAt = 0L; private set
    /** The position (s) at [posAt] (uptime ms), and the length. */
    @Volatile private var pos = 0.0
    @Volatile private var posAt = 0L
    @Volatile var duration = 0.0; private set
    /** Nuvio's UI layers over the player (FPS lines, every 3 s): 0 nothing, 1 the seek bar, 8+ the control bar. */
    @Volatile var layers = 0; private set
    @Volatile var layersAt = 0L; private set
    /** Re-buffering (after a seek): Nuvio ignores keys then. Only "stopped" lines tell it; they stop once it plays. */
    @Volatile private var buffering = false
    @Volatile private var bufferingAt = 0L
    val busy get() = buffering && android.os.SystemClock.uptimeMillis() - bufferingAt < 1500
    /** When Nuvio last committed a seek (uptime ms). */
    @Volatile var seekAt = 0L; private set
    /** The player is open (a film or episode). */
    val inPlayer get() = slots[0] != null && slots[5].let { it == null || (slots[0] ?: "") > it } && focus.startsWith(PKG)
    fun position(): Double = if (playing) pos + (android.os.SystemClock.uptimeMillis() - posAt) / 1000.0 else pos

    private fun setPlaying(p: Boolean) { if (p != playing) { if (playing) pos = position(); playing = p; posAt = android.os.SystemClock.uptimeMillis(); stateAt = posAt } }
    private fun setPos(v: Double) { pos = v; posAt = android.os.SystemClock.uptimeMillis() }

    private fun live(line: String) {
        when {
            line.contains("FPS=") -> Regex("""tela=(\d+)""").find(line)?.let { layers = it.groupValues[1].toInt(); layersAt = android.os.SystemClock.uptimeMillis() }
            line.contains("[visto] ponto gravado") -> Regex("""pos=([\d.]+) dur=([\d.]+)""").find(line)?.let {
                setPos(it.groupValues[1].toDouble()); duration = it.groupValues[2].toDouble()
                if (line.contains("(pausa)")) setPlaying(false)
            }
            line.contains("[player] VOD parado") -> Regex("""pos=([\d.]+)[^\n]*?buffering=(\d)[^\n]*?tocando=(\d)""").find(line)?.let {
                setPos(it.groupValues[1].toDouble())
                buffering = it.groupValues[2] == "1"; bufferingAt = android.os.SystemClock.uptimeMillis()
                if (it.groupValues[2] == "0") setPlaying(it.groupValues[3] == "1")
            }
            line.contains("playWhenReady=true") || line.contains("[player] tocando") || line.contains("AudioTrack avancando") -> setPlaying(true)
            line.contains("playWhenReady=false") -> setPlaying(false)
            line.contains("seek alvoMs=") -> Regex("""alvoMs=(\d+)""").find(line)?.let { setPos(it.groupValues[1].toLong() / 1000.0); seekAt = android.os.SystemClock.uptimeMillis() }
        }
    }

    fun onWindow(pkg: String, cls: String) {
        val f = "$pkg/$cls"
        if (f != focus) { focus = f; dirty = true }
    }

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val st = (app as com.glasslauncher.tv.GlassApp).settings
        if (running || !st.nuvioPush || st.haUrl.isBlank() || st.haToken.isBlank()) return
        running = true
        com.glasslauncher.tv.HomeKeyService.instance?.watchAllWindows(true)
        store = java.io.File(app.filesDir, "nuvio-watch.txt")
        // What was known before Glassio restarted (the title's start line is gone from the TV's log after a while).
        runCatching {
            val l = store!!.readLines()
            if (l.size >= 3) synchronized(lock) { pid = l[0]; slots[0] = l[1].ifBlank { null }; slots[2] = l[2].ifBlank { null } }
        }
        // The window on screen now (the accessibility service only tells of the next change).
        Thread {
            LocalShell.run(app, "dumpsys window | grep -m1 mCurrentFocus; true").getOrNull()?.let { out ->
                Regex("""u0 ([^ }]+)""").find(out)?.groupValues?.get(1)?.let { if (focus.isBlank()) { focus = it; dirty = true } }
            }
        }.start()
        // The log, as one stream (reconnects if it drops).
        Thread({
            while (running && st.nuvioPush) {
                runCatching { LocalShell.stream(app, "logcat -v threadtime -s nuvio:I") { onLine(it) } }
                    .onFailure { Glog.w("Nuvio watch: log stream", it) }
                if (running) Thread.sleep(5000)
            }
            running = false
        }, "glass-nuvio-log").apply { isDaemon = true }.start()
        // Sends: a change at once (at most one a second), otherwise a heartbeat (15 s while Nuvio is open, 60 s otherwise).
        Thread({
            while (running && st.nuvioPush) {
                Thread.sleep(1000)
                val now = System.currentTimeMillis()
                val front = focus.startsWith(PKG)
                if (now - lastPidCheck > (if (front) 15_000 else 60_000)) {
                    lastPidCheck = now
                    val p = LocalShell.run(app, "pidof $PKG; true").getOrNull()?.trim().orEmpty().substringBefore(' ')
                    synchronized(lock) {
                        nuvioAlive = p.isNotBlank()
                        // Nuvio closed: its lines are no longer the running app's (as --pid did before).
                        if (!nuvioAlive && pid.isNotBlank()) { pid = ""; slots.fill(null); lastLine = ""; dirty = true; keep() }
                    }
                }
                val beat = if (front || nuvioAlive) 15_000 else 60_000
                if ((dirty && now - lastSent >= 1000) || now - lastSent >= beat) { dirty = false; send(st) }
            }
        }, "glass-nuvio-send").apply { isDaemon = true }.start()
        Glog.i("Nuvio watch on")
    }

    fun stop() { running = false }

    private val rx = Regex("""^\d\d-\d\d \d\d:\d\d:\d\d\.\d+\s+(\d+)\s+\d+\s+\w\s""")

    private fun onLine(line: String) {
        val p = rx.find(line)?.groupValues?.get(1) ?: return
        synchronized(lock) {
            if (p != pid) { pid = p; slots.fill(null); keep() }
            lastLine = line
            live(line)
            // Nuvio says itself when its window comes back or goes (the accessibility event can come in the wrong
            // order when Glassio opens a title in it).
            if (line.contains("foco-voltou") || line.contains("[atividade] inicio") || line.contains("[player] tocando")) {
                if (!focus.startsWith(PKG)) { focus = "$PKG/.Icone_original"; dirty = true }
            } else if (line.contains("vai-ocultar") && focus.startsWith(PKG)) { focus = "?/?"; dirty = true }
            var changed = true
            when {
                line.contains("[atividade] inicio") -> { slots[0] = line; keep() }
                line.contains("[visto] ponto gravado") -> slots[1] = line
                Regex("""\[trakt\] (start|pause|stop|scrobble)""").containsMatchIn(line) -> { slots[2] = line; keep() }
                line.contains("playWhenReady=") -> slots[3] = line
                line.contains("[player] VOD parado") -> {
                    slots[4] = line
                    // While paused Nuvio repeats this line many times a second: only a change of its flags counts.
                    val flags = Regex("""buffering=\d[^\n]*?tocando=\d""").find(line)?.value.orEmpty()
                    changed = flags != parado; parado = flags
                }
                Regex("""\[ciclo\].*player=0|\[player\] saida""").containsMatchIn(line) -> slots[5] = line
                else -> changed = false
            }
            if (changed) dirty = true
        }
    }

    private fun send(st: Settings) {
        val text = synchronized(lock) {
            buildString {
                append("mCurrentFocus=Window{0 u0 ").append(focus).append("}\n")
                slots.forEach { append(it.orEmpty()).append('\n') }
                append("NUVIO_NOW ").append(lastLine)
            }
        }
        lastSent = System.currentTimeMillis()
        runCatching {
            val c = URL("${st.haUrl}/api/events/glassio_nuvio").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 4000; c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer ${st.haToken}")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(JSONObject().put("resp", text).toString().toByteArray()) }
            val code = c.responseCode; c.disconnect()
            if (code !in 200..299) Glog.w("Nuvio watch: Home Assistant answered $code")
        }.onFailure { Glog.w("Nuvio watch: ${it.message}") }
    }
}
