package com.glasslauncher.tv.data

import android.content.Context
import com.glasslauncher.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Simkl account (PIN sign-in on the phone): what you paused (Continue Watching) and your "Plan to watch" list
 * (Library). Needs a Simkl app id: the build's, or one you create at simkl.com/settings/developer and paste.
 */
class Simkl(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_simkl", Context.MODE_PRIVATE)
    var clientId: String
        get() = sp.getString("client", "").orEmpty().ifBlank { BuildConfig.SIMKL_CLIENT_ID }
        set(v) { sp.edit().putString("client", v.trim()).apply() }
    val available: Boolean get() = clientId.isNotBlank()
    val connected: Boolean get() = token.isNotBlank()
    val user: String get() = sp.getString("user", "").orEmpty()
    private val token: String get() = sp.getString("token", "").orEmpty()
    @Volatile var lastError = ""

    private fun call(method: String, path: String, body: JSONObject? = null, retried: Boolean = false): Pair<Int, String> {
        refreshIfDue()
        val c = URL("https://api.simkl.com$path" + (if (path.contains('?')) "&" else "?") + "client_id=$clientId").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000; c.requestMethod = method
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("simkl-api-key", clientId)
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            if (code == 401 && token.isNotBlank()) {
                // Expired: try the refresh token once before signing out.
                if (!retried && refresh()) return call(method, path, body, true)
                signOut()
            }
            return code to runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        } finally { c.disconnect() }
    }

    /** A form POST to Simkl's OAuth 2.0 endpoints. */
    private fun form(path: String, fields: Map<String, String>): Pair<Int, String> {
        val c = URL("https://api.simkl.com$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("User-Agent", "Glassio/" + BuildConfig.VERSION_NAME)
        val body = fields.entries.joinToString("&") { (k, v) -> k + "=" + java.net.URLEncoder.encode(v, "UTF-8") }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            return code to runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        } finally { c.disconnect() }
    }

    private fun keepTokens(o: JSONObject) {
        sp.edit().putString("token", o.getString("access_token"))
            .putString("refresh", o.optString("refresh_token", sp.getString("refresh", "").orEmpty()))
            .putLong("expires", if (o.has("expires_in")) System.currentTimeMillis() + o.optLong("expires_in") * 1000L else 0L).apply()
    }

    /** OAuth 2.0 tokens last 7 days: renewed a day before they run out (the refresh token lasts 180 days). */
    private fun refreshIfDue() {
        val exp = sp.getLong("expires", 0L)
        if (exp > 0 && System.currentTimeMillis() > exp - 24 * 3600_000L) refresh()
    }

    @Synchronized private fun refresh(): Boolean = runCatching {
        val r = sp.getString("refresh", "").orEmpty()
        if (r.isBlank()) return false
        val (c, t) = form("/oauth2/token", mapOf("grant_type" to "refresh_token", "refresh_token" to r, "client_id" to clientId))
        if (c !in 200..299) return false
        keepTokens(JSONObject(t)); true
    }.getOrDefault(false)

    fun signOut() { val id = sp.getString("client", ""); sp.edit().clear().putString("client", id).apply() }

    /** [qr] is the page to open on the phone (with the code filled in); [device] is set for the OAuth 2.0 flow. */
    data class Pin(val code: String, val url: String, val interval: Int, val expiresIn: Int, val device: String? = null, val qr: String = url)

    /**
     * Sign-in with a code: Simkl's OAuth 2.0 device flow (apps made now are OAuth 2.0 and refuse the old
     * /oauth/pin, "HTTP 400"), or the old PIN flow for older app ids.
     */
    fun startPin(): Pin {
        check(available) { "Add a Simkl app id first" }
        val (dc, dt) = form("/oauth2/device", mapOf("client_id" to clientId, "scope" to "media:read media:write"))
        if (dc in 200..299) {
            val o = JSONObject(dt)
            val uri = o.optString("verification_uri", "https://simkl.com/pin")
            return Pin(o.getString("user_code"), uri, o.optInt("interval", 5), o.optInt("expires_in", 900),
                device = o.getString("device_code"), qr = o.optString("verification_uri_complete").ifBlank { uri })
        }
        val (c, t) = call("GET", "/oauth/pin")
        check(c in 200..299) {
            val why = runCatching { JSONObject(dt).let { it.optString("error_description").ifBlank { it.optString("message") } } }.getOrNull().orEmpty()
                .ifBlank { runCatching { JSONObject(t).optString("message") }.getOrNull().orEmpty() }
            if (why.isNotBlank()) "Simkl: $why" else "Simkl answered HTTP $dc / $c"
        }
        val o = JSONObject(t)
        val u = o.optString("verification_url", "https://simkl.com/pin/")
        val code = o.getString("user_code")
        return Pin(code, u, o.optInt("interval", 5), o.optInt("expires_in", 900), qr = u.trimEnd('/') + "/" + code)
    }

    fun waitPin(p: Pin, cancelled: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + p.expiresIn * 1000L
        var every = p.interval.coerceAtLeast(2)
        while (System.currentTimeMillis() < end && !cancelled()) {
            Thread.sleep(every * 1000L)
            if (p.device != null) {
                val (c, t) = runCatching { form("/oauth2/token", mapOf("grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "client_id" to clientId, "device_code" to p.device)) }.getOrNull() ?: continue
                val o = runCatching { JSONObject(t) }.getOrNull() ?: continue
                if (c in 200..299 && o.optString("access_token").isNotBlank()) { keepTokens(o); fetchUser(); return true }
                when (o.optString("error")) {
                    "slow_down" -> every += 5
                    "expired_token" -> { lastError = "The code expired"; return false }
                    "invalid_client" -> { lastError = "Simkl doesn't accept this app id"; return false }
                }
                continue
            }
            val (c, t) = runCatching { call("GET", "/oauth/pin/${p.code}") }.getOrNull() ?: continue
            if (c !in 200..299) continue
            val o = runCatching { JSONObject(t) }.getOrNull() ?: continue
            if (o.optString("result") == "OK" && o.optString("access_token").isNotBlank()) {
                sp.edit().putString("token", o.getString("access_token")).apply()
                fetchUser()
                return true
            }
        }
        lastError = "The code expired"
        return false
    }

    private fun fetchUser() {
        runCatching { JSONObject(call("POST", "/users/settings").second).optJSONObject("user")?.optString("name") }.getOrNull()
            ?.takeIf { it.isNotBlank() }?.let { sp.edit().putString("user", it).apply() }
    }

    data class Paused(val id: Long, val imdb: String, val season: Int, val episode: Int, val percent: Int, val at: Long)

    /** What you paused (any app that scrobbles to Simkl). */
    fun playback(): List<Paused>? = runCatching {
        val (c, t) = call("GET", "/sync/playback")
        check(c in 200..299) { "Simkl answered HTTP $c" }
        val a = if (t.isBlank() || t == "null") JSONArray() else JSONArray(t)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val show = o.optJSONObject("show"); val movie = o.optJSONObject("movie"); val ep = o.optJSONObject("episode")
            val imdb = ((show ?: movie)?.optJSONObject("ids")?.optString("imdb")).orEmpty()
            if (!imdb.startsWith("tt")) return@mapNotNull null
            val at = runCatching { java.time.Instant.parse(o.optString("paused_at")).toEpochMilli() }.getOrDefault(0L)
            Paused(o.optLong("id"), imdb, ep?.optInt("season") ?: 0, ep?.optInt("episode", ep.optInt("number")) ?: 0, o.optDouble("progress", 0.0).toInt(), at)
        }
    }.getOrElse { Glog.w("Simkl playback", it); lastError = it.message ?: ""; null }

    fun removePlayback(id: Long): Boolean = runCatching { call("DELETE", "/sync/playback/$id").first in 200..299 }.getOrDefault(false)

    data class Item(val imdb: String, val type: String, val name: String, val poster: String)

    /** "Plan to watch" (movies and shows). */
    fun planToWatch(): List<Item>? = runCatching {
        val (c, t) = call("GET", "/sync/all-items/?extended=full")
        check(c in 200..299) { "Simkl answered HTTP $c" }
        val o = JSONObject(t)
        val out = ArrayList<Item>()
        for ((k, type) in listOf("movies" to "movie", "shows" to "series", "anime" to "series")) {
            val a = o.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                if (e.optString("status") != "plantowatch") continue
                val m = e.optJSONObject(if (type == "movie") "movie" else "show") ?: continue
                val imdb = m.optJSONObject("ids")?.optString("imdb").orEmpty()
                if (!imdb.startsWith("tt")) continue
                val poster = m.optString("poster").takeIf { it.isNotBlank() }?.let { "https://wsrv.nl/?url=https://simkl.in/posters/${it}_m.jpg" }.orEmpty()
                out += Item(imdb, type, m.optString("title"), poster)
            }
        }
        out
    }.getOrElse { Glog.w("Simkl list", it); null }

    fun setPlanToWatch(add: Boolean, imdb: String, type: String): Boolean = runCatching {
        val key = if (type == "movie") "movies" else "shows"
        val item = JSONObject().put("ids", JSONObject().put("imdb", imdb)).apply { if (add) put("to", "plantowatch") }
        val body = JSONObject().put(key, JSONArray().put(item))
        call("POST", if (add) "/sync/add-to-list" else "/sync/history/remove", body).first in 200..299
    }.getOrDefault(false)
}
