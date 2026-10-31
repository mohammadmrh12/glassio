package com.glasslauncher.tv.data

import android.content.Context
import com.glasslauncher.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Trakt account (device sign-in, no password on the TV): what you paused in any app that scrobbles to Trakt
 * (the official Nuvio app, Nuvio Arc, Re-Shaped, Stremio, Kodi…) shows up in Continue Watching.
 *
 * The app is a PKCE app, so it has no client secret: the device code itself proves the sign-in. Tokens stay
 * on the TV, in their own preferences file (not in the settings backup or the settings dump).
 */
class Trakt(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_trakt", Context.MODE_PRIVATE)
    private val clientId = BuildConfig.TRAKT_CLIENT_ID

    val available: Boolean get() = clientId.isNotBlank()
    val connected: Boolean get() = sp.getString("refresh", "").orEmpty().isNotBlank()
    val user: String get() = sp.getString("user", "").orEmpty()

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val expiresIn: Int, val interval: Int)

    private fun call(method: String, path: String, body: JSONObject? = null, auth: Boolean = false): Pair<Int, String> {
        val c = URL("https://api.trakt.tv$path").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.requestMethod = method
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("trakt-api-version", "2")
        c.setRequestProperty("trakt-api-key", clientId)
        c.setRequestProperty("User-Agent", "Glassio/1.0")
        if (auth) c.setRequestProperty("Authorization", "Bearer ${accessToken() ?: ""}")
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    /** Step 1: a short code for the person to enter at trakt.tv/activate. */
    fun deviceCode(): DeviceCode {
        val (code, text) = call("POST", "/oauth/device/code", JSONObject().put("client_id", clientId))
        if (code !in 200..299) Glog.w("Trakt device code: HTTP $code $text")
        check(code in 200..299) { "Trakt answered HTTP $code" + (if (code == 401 || code == 403) " (this Glassio build's Trakt app is not allowed)" else "") }
        val o = JSONObject(text)
        return DeviceCode(o.getString("device_code"), o.getString("user_code"),
            o.optString("verification_url", "https://trakt.tv/activate"), o.optInt("expires_in", 600), o.optInt("interval", 5))
    }

    /** Why the last [waitForApproval] failed, for the message on screen. */
    @Volatile var lastError: String = ""

    /** Step 2: poll until the code is approved. Returns true when signed in, false when expired / denied. */
    fun waitForApproval(dc: DeviceCode, cancelled: () -> Boolean): Boolean {
        lastError = "The code expired"
        val end = System.currentTimeMillis() + dc.expiresIn * 1000L
        var wait = dc.interval.coerceAtLeast(1) * 1000L
        while (System.currentTimeMillis() < end && !cancelled()) {
            Thread.sleep(wait)
            val (code, text) = runCatching {
                call("POST", "/oauth/device/token", JSONObject().put("code", dc.deviceCode).put("client_id", clientId))
            }.getOrElse { 0 to "" }
            when (code) {
                200 -> { saveTokens(JSONObject(text)); fetchUser(); return true }
                400 -> {} // still waiting for approval
                429 -> wait += 1000 // slow down
                else -> {
                    lastError = when (code) {
                        404 -> "Invalid code"; 409 -> "Code already used"; 410 -> "The code expired"
                        418 -> "Sign-in was denied"
                        401, 403 -> "Trakt refused this account (HTTP $code)"
                        else -> "Trakt answered HTTP $code"
                    }
                    Glog.w("Trakt device token: HTTP $code $text")
                    if (code in setOf(404, 409, 410, 418, 401, 403)) return false
                }
            }
        }
        return false
    }

    private fun saveTokens(o: JSONObject) {
        val expires = (o.optLong("created_at", System.currentTimeMillis() / 1000) + o.optLong("expires_in", 7 * 86400)) * 1000
        sp.edit().putString("access", o.getString("access_token")).putString("refresh", o.getString("refresh_token"))
            .putLong("expires", expires).apply()
    }

    private fun fetchUser() {
        runCatching {
            val (code, text) = call("GET", "/users/settings", auth = true)
            if (code == 200) sp.edit().putString("user", JSONObject(text).optJSONObject("user")?.optString("username").orEmpty()).apply()
        }
    }

    @Synchronized
    private fun accessToken(): String? {
        val access = sp.getString("access", null) ?: return null
        if (System.currentTimeMillis() < sp.getLong("expires", 0) - 3600_000) return access
        val refresh = sp.getString("refresh", null) ?: return null
        // Refresh tokens are single use: every refresh returns a new pair.
        val body = JSONObject().put("refresh_token", refresh).put("client_id", clientId).put("grant_type", "refresh_token")
        var (code, text) = runCatching { call("POST", "/oauth/token", body) }.getOrElse { return access }
        if (code == 400 || code == 401) {
            val r = runCatching { call("POST", "/oauth/token", body.put("redirect_uri", "http://localhost")) }.getOrNull()
            if (r != null) { code = r.first; text = r.second }
        }
        if (code == 200) { saveTokens(JSONObject(text)); return sp.getString("access", null) }
        if (code == 400 || code == 401) signOut()
        return null
    }

    fun signOut() { sp.edit().clear().apply() }

    data class Paused(val id: Long, val imdb: String, val season: Int, val episode: Int, val percent: Int, val at: Long)

    /** Movies and episodes you paused, newest first (GET /sync/playback). */
    fun playback(): List<Paused> {
        if (!connected) return emptyList()
        val (code, text) = call("GET", "/sync/playback?limit=30", auth = true)
        check(code == 200) { "Trakt answered HTTP $code" }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val pct = o.optDouble("progress", 0.0).toInt()
            val at = runCatching { java.time.Instant.parse(o.optString("paused_at")).toEpochMilli() }.getOrDefault(0L)
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }
                    ?.let { Paused(o.optLong("id"), it, 0, 0, pct, at) }
                "episode" -> {
                    val show = o.optJSONObject("show")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }
                    val ep = o.optJSONObject("episode")
                    if (show == null || ep == null) null
                    else Paused(o.optLong("id"), show, ep.optInt("season"), ep.optInt("number"), pct, at)
                }
                else -> null
            }
        }
    }

    data class Listed(val imdb: String, val type: String, val at: Long)

    /** Your Trakt watchlist (movies and shows with an IMDb id), newest first. */
    fun watchlist(): List<Listed> {
        if (!connected) return emptyList()
        val (code, text) = call("GET", "/sync/watchlist?sort_by=added&sort_how=desc", auth = true)
        check(code == 200) { "Trakt answered HTTP $code" }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val at = runCatching { java.time.Instant.parse(o.optString("listed_at")).toEpochMilli() }.getOrDefault(0L)
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.let { Listed(it, "movie", at) }
                "show" -> o.optJSONObject("show")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.let { Listed(it, "series", at) }
                else -> null
            }
        }.sortedByDescending { it.at }
    }

    /** Adds a title to (or removes it from) the Trakt watchlist. */
    fun setWatchlist(add: Boolean, type: String, imdb: String): Boolean = runCatching {
        val key = if (type == "movie") "movies" else "shows"
        val body = JSONObject().put(key, JSONArray().put(JSONObject().put("ids", JSONObject().put("imdb", imdb))))
        val (code, text) = call("POST", if (add) "/sync/watchlist" else "/sync/watchlist/remove", body, auth = true)
        if (code !in 200..299) Glog.w("Trakt watchlist ${if (add) "add" else "remove"}: HTTP $code $text")
        code in 200..299
    }.getOrDefault(false)

    /** Your Trakt favorites (what Glassio calls likes). */
    fun favorites(): List<Listed> {
        if (!connected) return emptyList()
        val (code, text) = call("GET", "/sync/favorites", auth = true)
        check(code == 200) { "Trakt answered HTTP $code" }
        val a = JSONArray(text)
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.let { Listed(it, "movie", 0L) }
                "show" -> o.optJSONObject("show")?.optJSONObject("ids")?.optString("imdb")?.takeIf { it.startsWith("tt") }?.let { Listed(it, "series", 0L) }
                else -> null
            }
        }
    }

    /** Puts a paused position on Trakt (so it shows in Continue Watching of every app that reads Trakt). */
    fun pausedAt(imdb: String, season: Int, episode: Int, percent: Int): Boolean = runCatching {
        val body = scrobbleBody(imdb, season, episode).put("progress", percent.coerceIn(1, 79))
        val (code, text) = call("POST", "/scrobble/pause", body, auth = true)
        if (code !in 200..299 && code != 409) Glog.w("Trakt pause: HTTP $code $text")
        code in 200..299 || code == 409
    }.getOrDefault(false)

    private fun scrobbleBody(imdb: String, season: Int, episode: Int): JSONObject =
        if (episode > 0) JSONObject().put("show", JSONObject().put("ids", JSONObject().put("imdb", imdb)))
            .put("episode", JSONObject().put("season", season).put("number", episode))
        else JSONObject().put("movie", JSONObject().put("ids", JSONObject().put("imdb", imdb)))

    /** Adds a title to (or removes it from) your Trakt favorites (what "Like" does). */
    fun setFavorite(add: Boolean, type: String, imdb: String): Boolean = runCatching {
        val key = if (type == "movie") "movies" else "shows"
        val body = JSONObject().put(key, JSONArray().put(JSONObject().put("ids", JSONObject().put("imdb", imdb))))
        val (code, text) = call("POST", if (add) "/sync/favorites" else "/sync/favorites/remove", body, auth = true)
        if (code !in 200..299) Glog.w("Trakt favorites ${if (add) "add" else "remove"}: HTTP $code $text")
        code in 200..299
    }.getOrDefault(false)

    /** Removes a paused item on Trakt too, so it doesn't come back with the next sync. */
    fun removePlayback(id: Long) { runCatching { call("DELETE", "/sync/playback/$id", auth = true) } }
}
