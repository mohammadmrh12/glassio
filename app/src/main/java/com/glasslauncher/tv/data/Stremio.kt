package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Stremio account: its add-ons (as catalogs) and what you're watching (Continue Watching), from api.strem.io.
 * Sign in with a code approved on the phone (link.stremio.com, like the Stremio TV apps) or with email and
 * password. The key stays on the TV, in its own preferences file.
 */
class Stremio(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_stremio", Context.MODE_PRIVATE)
    val connected: Boolean get() = key.isNotBlank()
    val email: String get() = sp.getString("email", "").orEmpty()
    private val key: String get() = sp.getString("authKey", "").orEmpty()
    @Volatile var lastError = ""

    private fun req(url: String, body: JSONObject? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("Content-Type", "application/json")
        try {
            if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            return code to runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        } finally { c.disconnect() }
    }

    private fun api(method: String, body: JSONObject): JSONObject {
        val (c, t) = req("https://api.strem.io/api/$method", body.put("authKey", key))
        check(c in 200..299) { "Stremio answered HTTP $c" }
        val o = JSONObject(t)
        o.optJSONObject("error")?.let { e -> if (e.optInt("code") == 1) signOut(); error(e.optString("message", "Stremio error")) }
        return o
    }

    private fun keep(auth: String, mail: String) { sp.edit().putString("authKey", auth).putString("email", mail).apply() }

    fun signOut() { sp.edit().clear().apply() }

    /** Email and password sign-in (the password isn't kept). */
    fun login(mail: String, password: String): Boolean = runCatching {
        val (c, t) = req("https://api.strem.io/api/login", JSONObject().put("type", "Login").put("email", mail.trim()).put("password", password).put("facebook", false))
        check(c in 200..299) { "Stremio answered HTTP $c" }
        val o = JSONObject(t)
        o.optJSONObject("error")?.let { error(it.optString("message", "Wrong email or password")) }
        val r = o.getJSONObject("result")
        keep(r.getString("authKey"), r.optJSONObject("user")?.optString("email").orEmpty().ifBlank { mail.trim() })
        true
    }.getOrElse { lastError = it.message ?: "Sign-in failed"; false }

    data class Link(val code: String, val url: String)

    /** Phone sign-in step 1: a code to approve at link.stremio.com. */
    fun startLink(): Link {
        val (c, t) = req("https://link.stremio.com/api/v2/create?type=Create")
        check(c in 200..299) { "Stremio answered HTTP $c" }
        val r = JSONObject(t).getJSONObject("result")
        val code = r.getString("code")
        return Link(code, r.optString("link").ifBlank { "https://link.stremio.com/?code=$code" })
    }

    /** Phone sign-in step 2: waits (up to 10 min) for the code to be approved. */
    fun waitLink(l: Link, cancelled: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + 10 * 60_000L
        while (System.currentTimeMillis() < end && !cancelled()) {
            Thread.sleep(3000)
            val (c, t) = runCatching { req("https://link.stremio.com/api/v2/read?type=Read&code=${l.code}") }.getOrNull() ?: continue
            if (c !in 200..299) continue
            val auth = runCatching { JSONObject(t).optJSONObject("result")?.optString("authKey") }.getOrNull().orEmpty()
            if (auth.isNotBlank()) {
                keep(auth, "")
                runCatching { api("getUser", JSONObject().put("type", "GetUser")).optJSONObject("result")?.optString("email") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { sp.edit().putString("email", it).apply() }
                return true
            }
        }
        lastError = "The code expired"
        return false
    }

    /** The account's add-ons (manifest links). Null when it couldn't be read. */
    fun addons(): List<String>? = runCatching {
        val a = api("addonCollectionGet", JSONObject().put("type", "AddonCollectionGet").put("update", true)).getJSONObject("result").optJSONArray("addons") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("transportUrl")?.takeIf { u -> u.startsWith("http") } }
            // Stremio's own built-in add-ons give no catalogs worth showing twice.
            .filterNot { it.contains("v3-cinemeta") || it.contains("opensubtitles") || it.contains("local.strem.io") }
    }.getOrElse { Glog.w("Stremio add-ons", it); null }

    data class Progress(val id: String, val imdb: String, val season: Int, val episode: Int, val percent: Int, val at: Long, val raw: JSONObject)

    /** What you're watching in Stremio (library items with a position). */
    fun watching(): List<Progress>? = runCatching {
        val a = api("datastoreGet", JSONObject().put("collection", "libraryItem").put("ids", JSONArray()).put("all", true)).optJSONArray("result") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optJSONObject("state") ?: return@mapNotNull null
            val off = s.optLong("timeOffset"); val dur = s.optLong("duration")
            if (off <= 0 || dur <= 0 || (o.optBoolean("removed") && !o.optBoolean("temp"))) return@mapNotNull null
            val id = o.optString("_id")
            if (!id.startsWith("tt")) return@mapNotNull null
            val vid = s.optString("video_id").split(':')
            val season = vid.getOrNull(1)?.toIntOrNull() ?: s.optInt("season"); val ep = vid.getOrNull(2)?.toIntOrNull() ?: s.optInt("episode")
            val at = runCatching { java.time.Instant.parse(s.optString("lastWatched")).toEpochMilli() }.getOrDefault(0L)
            Progress(id, id.substringBefore(':'), season, ep, (100 * off / dur).toInt(), at, o)
        }
    }.getOrElse { Glog.w("Stremio watching", it); lastError = it.message ?: ""; null }

    /** Takes a title out of Stremio's Continue Watching (its position goes back to the start). */
    fun dismiss(p: Progress): Boolean = runCatching {
        val o = JSONObject(p.raw.toString())
        o.getJSONObject("state").put("timeOffset", 0)
        o.put("_mtime", java.time.Instant.now().toString())
        api("datastorePut", JSONObject().put("collection", "libraryItem").put("changes", JSONArray().put(o)))
        true
    }.getOrElse { Glog.w("Stremio dismiss", it); false }
}
