package com.glasslauncher.tv.data

import android.content.Context
import com.glasslauncher.tv.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Nuvio account (api.nuvio.tv), signed in on the TV with a code like the Nuvio apps do: its Library is the
 * one Nuvio Legacy, the official Nuvio app and the forks that sync with the account all share.
 *
 * Sign-in: an anonymous session asks for a TV code, the person approves it on nuvio.tv with the phone, and the
 * code is exchanged for the account's tokens. Tokens stay on the TV, in their own preferences file.
 */
class NuvioAccount(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_nuvio", Context.MODE_PRIVATE)
    private val key = BuildConfig.NUVIO_ANON_KEY
    private val base = "https://api.nuvio.tv"

    val available: Boolean get() = key.isNotBlank()
    val connected: Boolean get() = sp.getString("refresh", "").orEmpty().isNotBlank()
    val email: String get() = sp.getString("email", "").orEmpty()
    @Volatile var lastError: String = ""

    data class Code(val code: String, val url: String, val nonce: String, val anon: String, val interval: Int)

    private fun call(path: String, body: JSONObject, bearer: String?): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("apikey", key)
        c.setRequestProperty("Authorization", "Bearer ${bearer ?: key}")
        try {
            c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    /** Step 1: a code to approve at nuvio.tv (shown as a QR). */
    fun startLogin(): Code {
        val (sc, st) = call("/auth/v1/signup", JSONObject(), null)
        val anon = if (sc in 200..299) JSONObject(st).optString("access_token") else ""
        check(anon.isNotBlank()) { "Nuvio answered HTTP $sc" }
        val nonce = java.util.UUID.randomUUID().toString()
        fun start(withName: Boolean) = call("/rest/v1/rpc/start_tv_login_session", JSONObject()
            .put("p_device_nonce", nonce).put("p_redirect_base_url", "https://nuvio.tv/tv-login")
            .apply { if (withName) put("p_device_name", "Glassio") }, anon)
        var (code, text) = start(true)
        if (code !in 200..299) { val r = start(false); code = r.first; text = r.second }
        check(code in 200..299) { "Nuvio answered HTTP $code" }
        val o = text.trim().let { if (it.startsWith("[")) JSONArray(it).getJSONObject(0) else JSONObject(it) }
        val c = o.getString("code")
        return Code(c, o.optString("web_url").ifBlank { "https://nuvio.tv/tv-login?code=$c" }, nonce, anon, o.optInt("poll_interval_seconds", 3).coerceIn(1, 30))
    }

    /** Step 2: waits until the code is approved, then keeps the account's tokens. */
    fun waitForApproval(c: Code, cancelled: () -> Boolean): Boolean {
        lastError = "The code expired"
        val end = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < end && !cancelled()) {
            Thread.sleep(c.interval * 1000L)
            val (pc, pt) = runCatching { call("/rest/v1/rpc/poll_tv_login_session", JSONObject().put("p_code", c.code).put("p_device_nonce", c.nonce), c.anon) }
                .getOrNull() ?: continue
            if (pc !in 200..299) continue
            val o = pt.trim().let { if (it.startsWith("[")) JSONArray(it).optJSONObject(0) ?: JSONObject() else JSONObject(it) }
            when (val status = o.optString("status")) {
                "", "pending" -> continue
                "expired", "cancelled" -> { lastError = "The code $status"; return false }
                else -> {
                    val (ec, et) = call("/functions/v1/tv-logins-exchange", JSONObject().put("code", c.code).put("device_nonce", c.nonce), c.anon)
                    if (ec in 200..299 && keep(et)) return true
                    if (ec >= 500) continue
                    Glog.w("Nuvio exchange ($status): HTTP $ec"); lastError = "Nuvio refused the sign-in (HTTP $ec)"; return false
                }
            }
        }
        return false
    }

    private fun keep(text: String): Boolean {
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return false
        val s = o.optJSONObject("session") ?: o
        val access = s.optString("access_token"); val refresh = s.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) return false
        val exp = System.currentTimeMillis() + s.optLong("expires_in", 3600) * 1000
        val mail = (s.optJSONObject("user") ?: o.optJSONObject("user"))?.optString("email").orEmpty()
        sp.edit().putString("access", access).putString("refresh", refresh).putLong("exp", exp)
            .apply { if (mail.isNotBlank()) putString("email", mail) }.apply()
        return true
    }

    private fun access(force: Boolean = false): String? {
        if (!connected) return null
        val a = sp.getString("access", "").orEmpty()
        if (!force && a.isNotBlank() && System.currentTimeMillis() < sp.getLong("exp", 0) - 60_000) return a
        val (c, t) = call("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", sp.getString("refresh", "")), null)
        if (c in 200..299 && keep(t)) return sp.getString("access", null)
        Glog.w("Nuvio refresh: HTTP $c")
        if (c == 400 || c == 401) signOut()
        return null
    }

    fun signOut() { sp.edit().clear().apply() }

    private fun rawPage(profile: Int, offset: Int, tok0: String): Pair<JSONArray, String> {
        var tok = tok0
        val body = JSONObject().put("p_profile_id", profile).put("p_limit", 500).put("p_offset", offset)
        var (c, t) = call("/rest/v1/rpc/sync_pull_library", body, tok)
        if (c == 401) { tok = access(force = true) ?: error("signed out"); val r = call("/rest/v1/rpc/sync_pull_library", body, tok); c = r.first; t = r.second }
        check(c in 200..299) { "Nuvio answered HTTP $c" }
        return JSONArray(t) to tok
    }

    private val clientId: String get() = sp.getString("client", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("client", it).apply() }

    /**
     * Adds a title to (or removes it from) the account's Library. The server only takes the whole list, so
     * this reads it, changes the one title and writes it back; it never writes an empty or half-read list.
     */
    fun setSaved(add: Boolean, id: String, type: String, name: String, poster: String, background: String, profile: Int = 1): Boolean = runCatching {
        var tok = access() ?: return false
        val rows = ArrayList<JSONObject>()
        var offset = 0
        while (offset < 2000) {
            val (a, t) = rawPage(profile, offset, tok); tok = t
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { rows += it }
            if (a.length() < 500) break
            offset += 500
        }
        val has = rows.any { it.optString("content_id") == id }
        if (add == has) return true
        fun ms(o: JSONObject) = o.opt("added_at").let { v ->
            (v as? Number)?.toLong() ?: runCatching { java.time.OffsetDateTime.parse(v.toString()).toInstant().toEpochMilli() }.getOrDefault(System.currentTimeMillis())
        }
        val keys = listOf("content_id", "content_type", "name", "poster", "poster_shape", "background", "description", "release_info", "imdb_rating", "genres", "addon_base_url", "logo")
        val out = JSONArray()
        for (o in rows) {
            if (!add && o.optString("content_id") == id) continue
            if (o.optString("content_id").isBlank()) return false
            val n = JSONObject()
            for (k in keys) if (o.has(k)) n.put(k, o.get(k))
            if (!n.has("name")) n.put("name", o.optString("title", "Untitled"))
            n.put("added_at", ms(o))
            out.put(n)
        }
        if (add) out.put(JSONObject().put("content_id", id).put("content_type", if (type == "movie") "movie" else "series").put("name", name.ifBlank { "Untitled" })
            .put("poster", poster.ifBlank { JSONObject.NULL }).put("poster_shape", "POSTER").put("background", background.ifBlank { JSONObject.NULL })
            .put("description", "").put("release_info", "").put("imdb_rating", JSONObject.NULL).put("genres", JSONArray()).put("addon_base_url", JSONObject.NULL)
            .put("added_at", System.currentTimeMillis()))
        if (out.length() == 0) { lastError = "Nuvio can't empty the whole list"; return false }
        val body = JSONObject().put("p_profile_id", profile).put("p_origin_client_id", clientId).put("p_items", out)
        val (c, _) = call("/rest/v1/rpc/sync_push_library", body, tok)
        if (c !in 200..299) Glog.w("Nuvio library ${if (add) "add" else "remove"}: HTTP $c")
        c in 200..299
    }.getOrElse { Glog.w("Nuvio library change", it); false }

    private fun get(pathQuery: String, bearer: String): Pair<Int, String> {
        val c = URL(base + pathQuery).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("apikey", key)
        c.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            val code = c.responseCode
            val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            return code to text
        } finally { c.disconnect() }
    }

    /** The account id inside the access token (its "sub"). */
    private fun userId(tok: String): String = runCatching {
        val part = tok.split(".")[1]
        JSONObject(String(android.util.Base64.decode(part, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP))).optString("sub")
    }.getOrDefault("")

    /**
     * The add-ons installed on the account (profile 1, in the account's order, only the ones turned on): the same
     * list Nuvio Legacy and the Nuvio web app read. Null when it couldn't be read (signed out, offline).
     */
    fun addons(profile: Int = 1): List<String>? = runCatching {
        var tok = access() ?: return null
        val uid = userId(tok).ifBlank { return null }
        val q = "/rest/v1/addons?user_id=eq.$uid&profile_id=eq.$profile&select=*&order=sort_order.asc"
        var (c, t) = get(q, tok)
        if (c == 401) { tok = access(force = true) ?: return null; val r = get(q, tok); c = r.first; t = r.second }
        if (c !in 200..299) { Glog.w("Nuvio add-ons: HTTP $c"); lastError = "Nuvio answered HTTP $c"; return null }
        val a = JSONArray(t)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
            .filter { !it.has("enabled") || it.opt("enabled").toString() != "false" }
            .map { it.optString("url").trim() }
            .filter { it.startsWith("http") || it.startsWith("stremio") }
    }.getOrElse { Glog.w("Nuvio add-ons", it); null }

    data class Progress(val key: String, val imdb: String, val season: Int, val episode: Int, val percent: Int, val at: Long)

    /** What you're watching on the account (Nuvio Legacy, the official app and forks push it). Null on failure. */
    fun progress(profile: Int = 1): List<Progress>? = runCatching {
        var tok = access() ?: return null
        val body = JSONObject().put("p_profile_id", profile)
        var (c, t) = call("/rest/v1/rpc/sync_pull_watch_progress", body, tok)
        if (c == 401) { tok = access(force = true) ?: return null; val r = call("/rest/v1/rpc/sync_pull_watch_progress", body, tok); c = r.first; t = r.second }
        check(c in 200..299) { "Nuvio answered HTTP $c" }
        val a = JSONArray(t)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val raw = o.optString("content_id")
            val imdb = raw.substringBefore(':')
            if (!imdb.startsWith("tt")) return@mapNotNull null
            fun num(k: String) = o.opt(k).let { (it as? Number)?.toDouble() ?: it?.toString()?.toDoubleOrNull() }
            val pos = num("position_ms") ?: num("position") ?: return@mapNotNull null
            val dur = num("duration_ms") ?: num("duration") ?: return@mapNotNull null
            if (dur <= 1) return@mapNotNull null
            var season = o.optInt("season"); var ep = o.optInt("episode")
            if (ep <= 0) raw.split(':').let { p -> season = p.getOrNull(1)?.toIntOrNull() ?: 0; ep = p.getOrNull(2)?.toIntOrNull() ?: 0 }
            if (ep <= 0) { season = 0; ep = 0 }
            val at = listOf("updated_at", "last_watched", "last_watched_at").firstNotNullOfOrNull { k ->
                o.opt(k)?.let { v -> (v as? Number)?.toLong() ?: runCatching { java.time.OffsetDateTime.parse(v.toString()).toInstant().toEpochMilli() }.getOrNull() }
            } ?: 0L
            Progress(if (ep > 0) "${imdb}_s${season}e$ep" else imdb, imdb, season, ep, (100 * pos / dur).toInt().coerceIn(0, 100), at)
        }
    }.getOrElse { Glog.w("Nuvio progress", it); null }

    /** Takes a title out of the account's Continue Watching. */
    fun deleteProgress(key: String, profile: Int = 1): Boolean = runCatching {
        val tok = access() ?: return false
        call("/rest/v1/rpc/sync_delete_watch_progress", JSONObject().put("p_profile_id", profile).put("p_keys", JSONArray().put(key)), tok).first in 200..299
    }.getOrDefault(false)

    data class Saved(val id: String, val type: String, val name: String, val poster: String, val background: String,
                     val description: String, val year: String, val at: Long)

    /** The account's Library (profile 1, newest first). */
    fun library(profile: Int = 1): List<Saved> {
        var tok = access() ?: return emptyList()
        val out = ArrayList<Saved>()
        var offset = 0
        while (offset < 2000) {
            val body = JSONObject().put("p_profile_id", profile).put("p_limit", 500).put("p_offset", offset)
            var (c, t) = call("/rest/v1/rpc/sync_pull_library", body, tok)
            if (c == 401) { tok = access(force = true) ?: return out; val r = call("/rest/v1/rpc/sync_pull_library", body, tok); c = r.first; t = r.second }
            check(c in 200..299) { "Nuvio answered HTTP $c" }
            val a = JSONArray(t)
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                fun str(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                val at = runCatching { java.time.OffsetDateTime.parse(str("added_at")).toInstant().toEpochMilli() }.getOrDefault(0L)
                out += Saved(str("content_id"), str("content_type").ifBlank { "movie" }, str("name"), str("poster"), str("background"),
                    str("description"), Regex("(19|20)\\d{2}").find(str("release_info"))?.value.orEmpty(), at)
            }
            if (a.length() < 500) break
            offset += 500
        }
        return out.sortedByDescending { it.at }
    }
}
