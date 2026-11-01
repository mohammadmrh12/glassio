package com.glasslauncher.tv.net

import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Glog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The doorbell without any Home Assistant automation: Glassio signs in to Home Assistant itself (its link and a
 * long-lived token) and watches the doorbell sensor. When it rings, the camera shows like a /notify request.
 * Stays connected in the background and connects again on its own after Wi-Fi drops or Home Assistant restarts.
 */
object HaWatch {
    @Volatile var status: String = "Not set up"; private set
    @Volatile private var ws: WebSocket? = null
    @Volatile private var started = false

    private val client by lazy {
        OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS).build()
    }

    fun start(app: GlassApp) {
        if (started) return
        started = true
        Thread({ loop(app) }, "glass-ha").apply { isDaemon = true }.start()
    }

    /** Settings changed: drop the connection, the loop connects again with the new ones. */
    fun restart() { ws?.cancel() }

    private fun ready(app: GlassApp): Boolean {
        val st = app.settings
        return st.doorbellOn && st.haUrl.startsWith("http") && st.haToken.isNotBlank() && st.haDoorbellEntity.isNotBlank()
    }

    private fun loop(app: GlassApp) {
        var wait = 5_000L
        while (true) {
            if (!ready(app)) {
                val st = app.settings
                status = when {
                    !st.doorbellOn -> "Off"
                    !st.haUrl.startsWith("http") || st.haToken.isBlank() -> "Not set up"
                    else -> "Pick the doorbell sensor"
                }
                Thread.sleep(5_000); continue
            }
            val res = runCatching { connectOnce(app) }.getOrElse { Glog.w("Home Assistant", it); "Can't reach Home Assistant" }
            if (res == "ok") { wait = 5_000L; status = "Connecting again…" } else status = res
            // Wrong token: no point knocking every few seconds.
            Thread.sleep(if (res == "Wrong token") 60_000L else wait)
            wait = (wait * 2).coerceAtMost(60_000L)
        }
    }

    private fun wsUrl(http: String) = http.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/api/websocket"

    /** One connection, until it closes. "ok" when it was connected for a while, otherwise what went wrong. */
    private fun connectOnce(app: GlassApp): String {
        val st = app.settings
        val entity = st.haDoorbellEntity
        val done = CountDownLatch(1)
        var result = "Can't reach Home Assistant"
        var last: String? = null
        var lastRing = 0L
        val req = Request.Builder().url(wsUrl(st.haUrl)).build()
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val j = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (j.optString("type")) {
                    "auth_required" -> webSocket.send(JSONObject().put("type", "auth").put("access_token", st.haToken).toString())
                    "auth_invalid" -> { result = "Wrong token"; webSocket.close(1000, null) }
                    "auth_ok" -> {
                        result = "ok"; status = "Connected"
                        Glog.i("Home Assistant: watching $entity")
                        webSocket.send(JSONObject().put("id", 1).put("type", "subscribe_entities").put("entity_ids", JSONArray().put(entity)).toString())
                    }
                    "result" -> if (!j.optBoolean("success", true)) { status = "Home Assistant said: " + (j.optJSONObject("error")?.optString("message") ?: "error") }
                    "event" -> {
                        val ev = j.optJSONObject("event") ?: return
                        // First message: the current state ("a"); after that only changes ("c" → "+").
                        val now = ev.optJSONObject("a")?.optJSONObject(entity)?.optString("s")?.takeIf { it.isNotEmpty() }
                        if (now != null) { last = now; return }
                        val ch = ev.optJSONObject("c")?.optJSONObject(entity)?.optJSONObject("+")?.optString("s")?.takeIf { it.isNotEmpty() } ?: return
                        val before = last; last = ch
                        if (rang(entity, before, ch) && System.currentTimeMillis() - lastRing > 5_000) {
                            lastRing = System.currentTimeMillis()
                            // Home Assistant's own automation (rest_command) may have shown it a moment ago.
                            if (System.currentTimeMillis() - NotifyServer.lastShown < 8_000) return
                            Glog.i("Home Assistant: doorbell ($entity $before → $ch)")
                            runCatching { NotifyServer.show(app, JSONObject()) }.onFailure { Glog.w("Doorbell", it) }
                        }
                    }
                }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (result == "ok") Glog.w("Home Assistant: connection dropped (${t.message})")
                else if (response?.code == 404) result = "That isn't a Home Assistant link"
                done.countDown()
            }
        })
        // Settings changed some other way (phone page, a restored backup): connect again with the new ones.
        val key = listOf(st.haUrl, st.haToken, entity)
        while (!done.await(5, TimeUnit.SECONDS)) {
            if (!st.doorbellOn || listOf(st.haUrl, st.haToken, st.haDoorbellEntity) != key) ws?.cancel()
        }
        ws = null
        return result
    }

    /** A binary sensor (or switch) turning on; an event entity's state is the time it last fired. */
    private fun rang(entity: String, before: String?, now: String): Boolean = when {
        now == "unavailable" || now == "unknown" -> false
        entity.startsWith("event.") -> before != null && before != now
        else -> now == "on" && before != "on"
    }

    /** Blocking. The sensors that can be a doorbell, likeliest first: (entity id, friendly name). */
    fun sensors(url: String, token: String): List<Pair<String, String>> {
        val req = Request.Builder().url(url.trimEnd('/') + "/api/states").header("Authorization", "Bearer $token").build()
        val body = client.newBuilder().readTimeout(10, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            check(r.code != 401) { "Wrong token" }
            check(r.isSuccessful) { "Home Assistant answered HTTP ${r.code}" }
            r.body?.string().orEmpty()
        }
        val arr = JSONArray(body)
        val out = ArrayList<Pair<String, String>>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val id = o.optString("entity_id")
            if (id.substringBefore('.') !in setOf("binary_sensor", "event", "input_boolean", "switch")) continue
            out += id to (o.optJSONObject("attributes")?.optString("friendly_name")?.ifBlank { null } ?: id)
        }
        val bell = Regex("(?i)door ?bell|ring|جرس|bell")
        return out.sortedWith(compareBy({ !(bell.containsMatchIn(it.first) || bell.containsMatchIn(it.second)) }, { it.second.lowercase() }))
    }
}
