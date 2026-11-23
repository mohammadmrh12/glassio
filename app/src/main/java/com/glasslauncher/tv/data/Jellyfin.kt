package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A Jellyfin server (#27): its titles in the carousel, Continue Watching, the Library and the search, and a title
 * opened on its own page in the Jellyfin app (`org.jellyfin.androidtv`, extra `ItemId`: read in its
 * StartupActivity).
 *
 * Sign in with Quick Connect (a code approved in another Jellyfin app: nothing typed on the TV) or with a user
 * name and password. The token stays on the TV, in its own preferences file (`glass_jellyfin`, the TV's, like the
 * other accounts). Never logged, never in a report.
 *
 * The API (10.9 and later, 12.x checked against demo.jellyfin.org): `Authorization: MediaBrowser Client=…,
 * Device=…, DeviceId=…, Version=…, Token=…`; titles from `/Items`, in progress from `/UserItems/Resume`, the next
 * episodes from `/Shows/NextUp`, pictures from `/Items/{id}/Images/{type}`.
 */
class Jellyfin(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_jellyfin", Context.MODE_PRIVATE)
    val server: String get() = sp.getString("server", "").orEmpty()
    val userName: String get() = sp.getString("user", "").orEmpty()
    val serverName: String get() = sp.getString("serverName", "").orEmpty()
    private val userId: String get() = sp.getString("userId", "").orEmpty()
    private val token: String get() = sp.getString("token", "").orEmpty()
    val connected: Boolean get() = server.isNotBlank() && token.isNotBlank() && userId.isNotBlank()

    // (From the TV itself, never kept in a file: a backup restored on another TV carried it, and Jellyfin signs out
    // the earlier sign-in of the same user and device when one signs in.)
    private val deviceId: String by lazy {
        val a = runCatching { android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID) }.getOrNull().orEmpty()
        "glassio-" + (a.ifBlank { java.util.UUID.randomUUID().toString() })
    }
    private fun auth(withToken: Boolean = true): String {
        val v = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1"
        val dev = (android.os.Build.MODEL ?: "TV").replace('"', ' ')
        return "MediaBrowser Client=\"Glassio\", Device=\"$dev\", DeviceId=\"$deviceId\", Version=\"$v\"" +
            (if (withToken && token.isNotBlank()) ", Token=\"$token\"" else "")
    }

    /** An address as typed: "jellyfin.local" → http://jellyfin.local:8096, no slash at the end. */
    fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (s.isBlank()) return s
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        val hostPart = s.substringAfter("://").substringBefore('/')
        if (!hostPart.contains(':') && s.startsWith("http://", true)) s = s.replaceFirst(hostPart, "$hostPart:8096")
        return s
    }

    private fun req(base: String, path: String, body: JSONObject? = null, withToken: Boolean = true, timeout: Int = 12000): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = timeout
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("Authorization", auth(withToken))
        try {
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            return code to runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        } finally { c.disconnect() }
    }

    private fun get(path: String): JSONObject {
        val (c, t) = req(server, path)
        // The server took the sign-in back (signed out there, the user removed): signed out here too, once.
        if (c == 401) { Glog.w("Jellyfin: the server no longer accepts this sign-in: signed out"); forget() }
        check(c in 200..299) { "Jellyfin answered HTTP $c" }
        return JSONObject(t)
    }

    /** The server's own name and version, or an error in words. Blocking. */
    fun check(base: String): Result<String> = runCatching {
        val (c, t) = req(base, "/System/Info/Public", withToken = false, timeout = 6000)
        check(c in 200..299) { "No Jellyfin server answered there (HTTP $c)" }
        val o = JSONObject(t)
        "${o.optString("ServerName")} · ${o.optString("Version")}"
    }

    private fun keep(base: String, o: JSONObject, name: String) {
        val u = o.getJSONObject("User")
        sp.edit().putString("server", base).putString("token", o.getString("AccessToken")).putString("userId", u.getString("Id"))
            .putString("user", u.optString("Name")).putString("serverName", name).apply()
        index = null
        Glog.i("Jellyfin: signed in")
    }

    /** User name and password (an empty password is allowed: some servers have users without one). Blocking. */
    fun signIn(rawServer: String, user: String, password: String): Result<Unit> = runCatching {
        val base = normalize(rawServer)
        val (c, t) = req(base, "/Users/AuthenticateByName", JSONObject().put("Username", user).put("Pw", password), withToken = false)
        check(c in 200..299) { if (c == 401) "Wrong user name or password" else "The server answered HTTP $c" }
        keep(base, JSONObject(t), serverName(base))
    }

    /** Quick Connect: the code to show and the secret to wait with, or an error (Quick Connect off on the server). */
    fun quickStart(rawServer: String): Result<Pair<String, String>> = runCatching {
        val base = normalize(rawServer)
        val (c, t) = req(base, "/QuickConnect/Initiate", JSONObject(), withToken = false)
        check(c in 200..299) { if (c == 401 || c == 403) "Quick Connect is off on this server: sign in with a password" else "The server answered HTTP $c" }
        val o = JSONObject(t)
        o.getString("Code") to o.getString("Secret")
    }

    /** Waits (up to [ms]) for the code to be approved, then signs in. Blocking. */
    fun quickWait(rawServer: String, secret: String, ms: Long = 300_000, cancelled: () -> Boolean = { false }): Result<Unit> = runCatching {
        val base = normalize(rawServer)
        val until = System.currentTimeMillis() + ms
        val s = URLEncoder.encode(secret, "UTF-8")
        while (System.currentTimeMillis() < until) {
            if (cancelled()) error("Cancelled")
            val (c, t) = req(base, "/QuickConnect/Connect?secret=$s", withToken = false)
            if (c in 200..299 && JSONObject(t).optBoolean("Authenticated")) {
                val (c2, t2) = req(base, "/Users/AuthenticateWithQuickConnect", JSONObject().put("Secret", secret), withToken = false)
                check(c2 in 200..299) { "The server answered HTTP $c2" }
                keep(base, JSONObject(t2), serverName(base)); return@runCatching
            }
            if (c !in 200..299) error("The code ran out: try again")
            Thread.sleep(2500)
        }
        error("Nobody approved the code: try again")
    }

    private fun serverName(base: String) = runCatching { JSONObject(req(base, "/System/Info/Public", withToken = false).second).optString("ServerName") }.getOrDefault("")

    fun signOut() {
        if (connected) runCatching { req(server, "/Sessions/Logout", JSONObject()) }
        forget()
        Glog.i("Jellyfin: signed out")
    }

    private fun forget() {
        sp.edit().remove("server").remove("token").remove("userId").remove("user").remove("serverName").remove("device").apply()
        index = null; indexFailedAt = 0L; cache.clear()
    }

    /** The server's catalogs were ticked once (kept here: in the catalogs' file it travelled with sync). */
    var seeded: Boolean get() = sp.getBoolean("seeded", false); set(v) { sp.edit().putBoolean("seeded", v).apply() }

    // ---- Titles

    /** A title on the server (a film, a series, or an episode in progress / next up). */
    class Item(
        val id: String, val type: String, val name: String, val year: Int, val overview: String,
        val imdb: String, val tmdb: String, val rating: Double, val genres: List<String>,
        val hasBackdrop: Boolean, val hasLogo: Boolean, val hasPrimary: Boolean, val hasThumb: Boolean,
        /** For an episode: its series and place. */
        val seriesId: String = "", val seriesName: String = "", val season: Int = 0, val episode: Int = 0,
        /** 0..1 when in progress. */
        val progress: Float = 0f, val runtimeMin: Int = 0, val lastPlayed: Long = 0L,
        val seriesImdb: String = "",
    ) {
        val isEpisode get() = type == "Episode"
        /** The id the rest of Glassio knows a title by: IMDb when the server has it, else jf:<id>. */
        val key: String get() = (if (isEpisode) seriesImdb else imdb).ifBlank { "jf:" + (if (isEpisode) seriesId else id) }
        val kind: String get() = if (type == "Movie") "movie" else "series"
    }

    private fun item(o: JSONObject): Item {
        val p = o.optJSONObject("ProviderIds")
        val tags = o.optJSONObject("ImageTags")
        val ud = o.optJSONObject("UserData")
        val ticks = o.optLong("RunTimeTicks")
        val genres = o.optJSONArray("Genres")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        return Item(
            id = o.optString("Id"), type = o.optString("Type"), name = o.optString("Name"), year = o.optInt("ProductionYear"),
            overview = o.optString("Overview"), imdb = p?.optString("Imdb").orEmpty(), tmdb = p?.optString("Tmdb").orEmpty(),
            rating = o.optDouble("CommunityRating", 0.0).let { if (it.isNaN()) 0.0 else it }, genres = genres,
            hasBackdrop = (o.optJSONArray("BackdropImageTags")?.length() ?: 0) > 0 || (o.optJSONArray("ParentBackdropImageTags")?.length() ?: 0) > 0,
            hasLogo = tags?.has("Logo") == true || o.has("ParentLogoImageTag"), hasPrimary = tags?.has("Primary") == true, hasThumb = tags?.has("Thumb") == true,
            seriesId = o.optString("SeriesId"), seriesName = o.optString("SeriesName"),
            season = o.optInt("ParentIndexNumber"), episode = o.optInt("IndexNumber"),
            progress = ((ud?.optDouble("PlayedPercentage", 0.0) ?: 0.0).let { if (it.isNaN()) 0.0 else it } / 100.0).toFloat(),
            runtimeMin = (ticks / 600_000_000L).toInt(),
            lastPlayed = ud?.optString("LastPlayedDate")?.let { parseDate(it) } ?: 0L,
        )
    }

    private fun parseDate(s: String): Long = runCatching {
        java.time.OffsetDateTime.parse(s.replace(Regex("(\\.\\d{3})\\d+"), "$1")).toInstant().toEpochMilli()
    }.getOrDefault(0L)

    private val fields = "Fields=ProviderIds,Overview,Genres,ProductionYear,CommunityRating&EnableImageTypes=Primary,Backdrop,Logo,Thumb"

    private fun items(path: String): List<Item> {
        val a = get(path).optJSONArray("Items") ?: JSONArray()
        return List(a.length()) { item(a.getJSONObject(it)) }
    }

    /** A short cache so the carousel, the Library and the search don't ask the same thing at once. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Item>>>()
    private fun cached(k: String, ttl: Long, load: () -> List<Item>): List<Item> {
        cache[k]?.takeIf { System.currentTimeMillis() - it.first < ttl }?.let { return it.second }
        return load().also { cache[k] = System.currentTimeMillis() to it }
    }

    /** The libraries (films and series only), as (id, name, kind). Blocking. */
    fun libraries(): List<Triple<String, String, String>> {
        if (!connected) return emptyList()
        val a = get("/UserViews?userId=$userId").optJSONArray("Items") ?: return emptyList()
        // (A library of films and series together has no type, or "mixed".)
        return List(a.length()) { a.getJSONObject(it) }.filter { it.optString("CollectionType") in setOf("movies", "tvshows", "mixed", "") }
            .map { Triple(it.optString("Id"), it.optString("Name"), it.optString("CollectionType")) }
    }

    /** The newest films and series of a library (or of all of them, with [library] blank). Blocking. */
    fun latest(library: String = "", limit: Int = 40): List<Item> = if (!connected) emptyList() else cached("latest:$library:$limit", 10 * 60_000L) {
        items("/Items?userId=$userId&Recursive=true&IncludeItemTypes=Movie,Series&SortBy=DateCreated&SortOrder=Descending&Limit=$limit" +
            (if (library.isNotBlank()) "&ParentId=$library" else "") + "&$fields")
    }

    /** Favourites (films and series). Blocking. */
    fun favourites(limit: Int = 60): List<Item> = if (!connected) emptyList() else cached("fav", 5 * 60_000L) {
        items("/Items?userId=$userId&Recursive=true&IncludeItemTypes=Movie,Series&Filters=IsFavorite&SortBy=SortName&Limit=$limit&$fields")
    }

    /** In progress (films and episodes), most recent first. Blocking. (Glassio's own Next up follows the episodes.) */
    fun continueWatching(limit: Int = 30): List<Item> = if (!connected) emptyList() else cached("cw", 60_000L) {
        val resume = runCatching { items("/UserItems/Resume?userId=$userId&Limit=$limit&MediaTypes=Video&$fields") }
            .recoverCatching { items("/Users/$userId/Items/Resume?Limit=$limit&MediaTypes=Video&$fields") }.getOrDefault(emptyList())
        withSeriesIds(resume)
    }

    /** Episodes know their series by Jellyfin's id: its IMDb id is read from the series (one request for all). */
    private fun withSeriesIds(list: List<Item>): List<Item> {
        val ids = list.filter { it.isEpisode && it.seriesId.isNotBlank() }.map { it.seriesId }.distinct()
        if (ids.isEmpty()) return list
        val series = runCatching { items("/Items?userId=$userId&Ids=${ids.joinToString(",")}&Fields=ProviderIds") }.getOrDefault(emptyList()).associateBy { it.id }
        return list.map { i -> if (!i.isEpisode) i else series[i.seriesId]?.let { s -> copyWithSeries(i, s.imdb) } ?: i }
    }
    private fun copyWithSeries(i: Item, imdb: String) = Item(i.id, i.type, i.name, i.year, i.overview, i.imdb, i.tmdb, i.rating, i.genres,
        i.hasBackdrop, i.hasLogo, i.hasPrimary, i.hasThumb, i.seriesId, i.seriesName, i.season, i.episode, i.progress, i.runtimeMin, i.lastPlayed, imdb)

    /** Films and series whose name matches [q]. Blocking. */
    fun search(q: String, limit: Int = 30): List<Item> {
        if (!connected || q.isBlank()) return emptyList()
        return items("/Items?userId=$userId&Recursive=true&IncludeItemTypes=Movie,Series&searchTerm=${URLEncoder.encode(q, "UTF-8")}&Limit=$limit&$fields")
    }

    /**
     * IMDb id → the server's title (films and series), to say "it is on your Jellyfin" for any title Glassio shows.
     * The whole library with only its ids, kept 30 minutes. Blocking.
     */
    @Volatile private var index: Pair<Long, Map<String, Item>>? = null
    @Volatile private var indexFailedAt = 0L
    fun byImdb(): Map<String, Item> {
        if (!connected) return emptyMap()
        index?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000L }?.let { return it.second }
        // (A failure is tried again after a minute, not kept for half an hour as "nothing there".)
        if (System.currentTimeMillis() - indexFailedAt < 60_000L) return index?.second.orEmpty()
        val all = runCatching { items("/Items?userId=$userId&Recursive=true&IncludeItemTypes=Movie,Series&Fields=ProviderIds&EnableImages=false&Limit=20000") }
            .onFailure { indexFailedAt = System.currentTimeMillis(); Glog.w("Jellyfin: the library's ids", it) }.getOrNull() ?: return index?.second.orEmpty()
        val m = all.filter { it.imdb.isNotBlank() }.associateBy { it.imdb }
        index = System.currentTimeMillis() to m
        return m
    }
    fun find(imdb: String): Item? = if (imdb.isBlank()) null else byImdb()[imdb]
    /** What is known now, without asking the server (Play must not wait): the index is renewed in the background. */
    fun peek(imdb: String): Item? {
        val i = index
        if (i == null || System.currentTimeMillis() - i.first > 30 * 60_000L) Bg.io("glass-jf-index") { runCatching { byImdb() } }
        return i?.second?.get(imdb)
    }

    /** A title on or off the user's favourites. Blocking. */
    fun setFavourite(id: String, on: Boolean) {
        if (!connected) return
        runCatching {
            val c = URL("$server/UserFavoriteItems/$id?userId=$userId").openConnection() as HttpURLConnection
            c.requestMethod = if (on) "POST" else "DELETE"; c.setRequestProperty("Authorization", auth())
            c.connectTimeout = 6000; c.readTimeout = 8000
            try { check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" } } finally { c.disconnect() }
            cache.remove("fav")
        }.onFailure { Glog.w("Jellyfin: favourite", it) }
    }

    /** Takes a title out of the server's "Continue watching" (it comes back if it is played again). Blocking. */
    fun hideFromResume(id: String) {
        if (!connected) return
        runCatching {
            val (c, _) = req(server, "/Users/$userId/Items/$id/HideFromResume?hide=true", JSONObject())
            check(c in 200..299) { "HTTP $c" }
            cache.remove("cw")
        }.onFailure { Glog.w("Jellyfin: hide from resume", it) }
    }

    // ---- Pictures (no token in the link: Jellyfin serves pictures without one, and the links are saved in the
    // Library and sent to Nuvio with a saved title)

    private fun img(id: String, kind: String, w: Int) = "$server/Items/$id/Images/$kind?maxWidth=$w&quality=85"
    /** For an episode the series' pictures (an episode's own still is small and often missing). */
    fun backdrop(i: Item) = if (i.isEpisode && i.seriesId.isNotBlank()) img(i.seriesId, "Backdrop/0", 1920) else if (i.hasBackdrop) img(i.id, "Backdrop/0", 1920) else ""
    fun logo(i: Item) = if (i.isEpisode && i.seriesId.isNotBlank()) img(i.seriesId, "Logo", 800) else if (i.hasLogo) img(i.id, "Logo", 800) else ""
    fun poster(i: Item) = if (i.isEpisode && i.seriesId.isNotBlank()) img(i.seriesId, "Primary", 500) else if (i.hasPrimary) img(i.id, "Primary", 500) else ""

    // ---- The Jellyfin app

    companion object {
        const val PKG = "org.jellyfin.androidtv"
        const val STARTUP = "org.jellyfin.androidtv.ui.startup.StartupActivity"
        /** When Glassio last opened the Jellyfin app (Back out of it comes to Glassio, see HomeKeyService). */
        @Volatile var openedAt = 0L
        fun installed(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

        /** The title's page in the Jellyfin app (it reads the extra ItemId); false when the app isn't there. */
        fun open(ctx: Context, itemId: String): Boolean = runCatching {
            if (!installed(ctx)) return false
            // On a fresh task: with the app still open behind another one, its main screen got the new request
            // and the Jellyfin app crashed (0.20.0, a NullPointerException in it; seen from Glassio and from the
            // shell alike). A new task starts its screens anew.
            openedAt = System.currentTimeMillis()
            ctx.startActivity(Intent().setClassName(PKG, STARTUP).putExtra("ItemId", itemId).putExtra("HideSplash", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            true
        }.onFailure { Glog.w("Jellyfin: opening a title", it) }.getOrDefault(false)

    }
}
