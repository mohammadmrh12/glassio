package com.glasslauncher.tv.net

import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Glog
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Doorbell (and other) pop-ups from Home Assistant: POST http://<tv>:7979/notify, the same request PiPup takes, so
 * Home Assistant needs no changes beyond the address. Shows the camera in the corner (like holding Home):
 *   {"camera": "Door"}                                  a camera saved in Glassio, by name
 *   {"media": {"video": {"uri": "rtsp://…"}}}          or any camera link (PiPup's field)
 *   "title", "message", "duration" (seconds)          optional
 * Runs while Settings → Cameras → "Doorbell from Home Assistant" is on.
 */
object NotifyServer {
    const val PORT = 7979
    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false

    fun start(app: GlassApp) {
        if (running) return
        running = true
        Thread({
            while (running) {
                if (!app.settings.doorbellOn) { Thread.sleep(5_000); continue }
                // The port can still be taken (PiPup installed): try again every half minute.
                val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PORT), 8) } }
                    .getOrElse { Glog.w("Doorbell: port $PORT is busy (${it.message}); trying again"); Thread.sleep(30_000); null } ?: continue
                server = s
                Glog.i("Doorbell: listening on $PORT")
                while (running && app.settings.doorbellOn) {
                    val c = runCatching { s.accept() }.getOrNull() ?: break
                    Thread { handle(app, c) }.start()
                }
                runCatching { s.close() }
                server = null
            }
        }, "glass-notify").apply { isDaemon = true }.start()
    }

    private fun handle(app: GlassApp, c: Socket) = runCatching {
        c.soTimeout = 8000
        c.use {
            val inp = BufferedInputStream(it.getInputStream())
            val head = StringBuilder()
            while (!head.endsWith("\r\n\r\n") && head.length < 16_384) { val b = inp.read(); if (b < 0) break; head.append(b.toChar()) }
            val first = head.lineSequence().firstOrNull().orEmpty().split(' ')
            val method = first.getOrNull(0).orEmpty(); val path = first.getOrNull(1).orEmpty().substringBefore('?')
            val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val body = ByteArray(len.coerceIn(0, 256 * 1024)).also { b -> var r = 0; while (r < b.size) { val n = inp.read(b, r, b.size - r); if (n < 0) break; r += n } }
            val (code, out) = when {
                method == "POST" && (path == "/notify" || path == "/") -> runCatching {
                    // Glassio already showed this ring itself (watching Home Assistant): don't show it twice.
                    if (System.currentTimeMillis() - lastShown > 8_000) show(app, JSONObject(String(body, Charsets.UTF_8)))
                    200 to """{"ok":true}"""
                }
                    .getOrElse { e -> 400 to JSONObject().put("ok", false).put("error", e.message).toString() }
                method == "POST" && path == "/dismiss" -> { com.glasslauncher.tv.HomeKeyService.instance?.closeDoorbell(); 200 to """{"ok":true}""" }
                method == "GET" -> 200 to JSONObject().put("app", "Glassio").put("doorbell", true).toString()
                else -> 404 to """{"ok":false}"""
            }
            val bytes = out.toByteArray()
            it.getOutputStream().apply {
                write("HTTP/1.1 $code OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes); flush()
            }
        }
    }.onFailure { Glog.w("Doorbell request", it) }

    @Volatile var lastShown = 0L; private set

    /** Works out the camera and the words, then asks the Home-button service to show it. */
    fun show(app: GlassApp, j: JSONObject) {
        lastShown = System.currentTimeMillis()
        val st = app.settings
        val saved = st.cameras.mapNotNull { com.glasslauncher.tv.ui.Cam.parse(it) }
        val byName = j.optString("camera").trim().takeIf { it.isNotBlank() }?.let { n -> saved.firstOrNull { it.name.equals(n, true) } }
        val media = j.optJSONObject("media")
        val uri = j.optString("video_url").ifBlank { media?.optJSONObject("video")?.optString("uri").orEmpty() }.trim()
        // The camera chosen in Settings → Cameras comes first; else the one Home Assistant sent.
        val chosen = st.doorbellCamera.takeIf { it.isNotBlank() }?.let { u -> saved.firstOrNull { it.url == u } }
        val cam = chosen ?: byName
            ?: uri.takeIf { it.contains("://") }?.let { u -> saved.firstOrNull { it.url == u } ?: com.glasslauncher.tv.ui.Cam(j.optString("title").ifBlank { "Camera" }, u) }
            ?: saved.firstOrNull { it.url == st.cornerCamera } ?: saved.firstOrNull()
            ?: error("No camera: add one in Settings → Cameras, or send its link")
        val seconds = j.optInt("duration", 0).takeIf { it > 0 } ?: st.doorbellSec
        val caption = if (st.doorbellText) (j.optString("title").ifBlank { st.doorbellTitle } to j.optString("message").ifBlank { st.doorbellMessage }) else null
        Glog.i("Doorbell: ${cam.name} for ${seconds}s")
        val svc = com.glasslauncher.tv.HomeKeyService.instance ?: error("The Home button service is off (Settings → System)")
        android.os.Handler(android.os.Looper.getMainLooper()).post { svc.showDoorbell(cam, saved, seconds, caption) }
    }
}
