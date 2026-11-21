package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Search (Settings → Continue Watching & Library → Search, off by default): films and series by name, and for the
 * one chosen the places it can be opened in: the streaming services' apps that have it (JustWatch), the Nuvio apps
 * and Stremio.
 *
 * Glassio plays nothing itself. A Nuvio app opens the title (or starts it, where the app can) and picks its source
 * by its own settings. Stremio is the one app that takes one exact source from outside, so for it Glassio can read
 * the add-ons' sources, sort them by the rules set here and hand the chosen one over.
 */
object Search {
    private const val CINEMETA = "https://v3-cinemeta.strem.io"
    const val CINEMETA_MANIFEST = "$CINEMETA/manifest.json"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // ---- Titles ----

    /**
     * Films and series called something like [query]. Blocking.
     * With a TMDB token: TMDB's search (the better one: it finds a title by part of its name, in any language, the
     * best known first); its titles carry TMDB's number until one is opened ([resolve]). Without a token, or when
     * TMDB doesn't answer: Cinemeta (no key, IMDb ids at once), films and series in turn.
     */
    fun titles(http: Http, s: Settings, query: String): List<HeroItem> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        if (s.tmdbToken.isNotBlank()) runCatching { tmdb(http, s, q) }.onFailure { Glog.w("Search: TMDB", it) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return withPictures(it) }
        fun of(type: String): List<HeroItem> {
            val metas = http.json("$CINEMETA/catalog/$type/top/search=${enc(q)}.json", 6L * 3600 * 1000).optJSONArray("metas") ?: return emptyList()
            return (0 until metas.length()).mapNotNull { i ->
                val m = metas.optJSONObject(i) ?: return@mapNotNull null
                val id = m.optString("imdb_id").ifBlank { m.optString("id") }
                if (!id.startsWith("tt") || m.optString("name").isBlank()) return@mapNotNull null
                HeroItem("search:$type:$id", id, type, m.optString("name"), m.optString("poster"), m.optString("background"), m.optString("logo"),
                    "", m.optString("imdbRating").takeIf { r -> r.toDoubleOrNull()?.let { it > 0 } == true }.orEmpty(), m.optString("releaseInfo").ifBlank { m.optString("year") }, emptyList(), "")
            }
        }
        val movies = if (s.searchMovies) runCatching { of("movie") }.onFailure { Glog.w("Search: films", it) }.getOrDefault(emptyList()) else emptyList()
        val series = if (s.searchSeries) runCatching { of("series") }.onFailure { Glog.w("Search: series", it) }.getOrDefault(emptyList()) else emptyList()
        return withPictures(mix(movies, series))
    }

    /**
     * Only titles that have a poster, as long as some do: one without is nearly always a title nobody can open
     * anywhere (a short, a title only announced), and it showed as a grey card with its name. When none has one, all stay.
     */
    fun withPictures(all: List<HeroItem>): List<HeroItem> = all.filter { it.poster.isNotBlank() }.ifEmpty { all }

    /** What is popular this week (TMDB, so only with a token), for the page before anything is typed. Blocking. */
    fun trending(http: Http, s: Settings): List<HeroItem> = if (s.tmdbToken.isBlank()) emptyList() else
        withPictures(tmdb(http, s, null))

    private fun tmdb(http: Http, s: Settings, q: String?): List<HeroItem> {
        val url = if (q == null) "https://api.themoviedb.org/3/trending/all/week" else "https://api.themoviedb.org/3/search/multi?query=${enc(q)}&include_adult=false&page=1"
        val res = http.json(url, 6L * 3600 * 1000, s.tmdbToken).optJSONArray("results")
            ?: return emptyList()
        return (0 until res.length()).mapNotNull { i ->
            val m = res.optJSONObject(i) ?: return@mapNotNull null
            val type = when (m.optString("media_type")) { "movie" -> "movie"; "tv" -> "series"; else -> return@mapNotNull null }
            if ((type == "movie" && !s.searchMovies) || (type == "series" && !s.searchSeries)) return@mapNotNull null
            val name = m.optString("title").ifBlank { m.optString("name") }
            val id = m.optInt("id", 0)
            if (name.isBlank() || id == 0) return@mapNotNull null
            fun pic(k: String, size: String) = m.optString(k).takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/$size$it" }.orEmpty()
            HeroItem("search:$type:tmdb:$id", "tmdb:$id", type, name, pic("poster_path", "w342"), pic("backdrop_path", "w1280"), "",
                "", m.optDouble("vote_average", 0.0).takeIf { it > 0 && m.optInt("vote_count", 0) >= 5 }?.let { "%.1f".format(java.util.Locale.US, it) }.orEmpty(), m.optString("release_date").ifBlank { m.optString("first_air_date") }.take(4), emptyList(), "")
        }
    }

    /**
     * The title with its IMDb id (the apps and the add-ons go by it): a title found by TMDB carries TMDB's number
     * until now. Blocking. Null: TMDB has no IMDb id for it.
     */
    fun resolve(http: Http, s: Settings, item: HeroItem): HeroItem? {
        if (!item.id.startsWith("tmdb:")) return item
        val kind = if (item.type == "movie") "movie" else "tv"
        val imdb = runCatching { http.json("https://api.themoviedb.org/3/$kind/${item.id.removePrefix("tmdb:")}/external_ids", 90L * 24 * 3600 * 1000, s.tmdbToken).optString("imdb_id") }
            .onFailure { Glog.w("Search: the title's IMDb id", it) }.getOrDefault("")
        return if (imdb.startsWith("tt")) item.copy(key = "search:${item.type}:$imdb", id = imdb) else null
    }

    /** One from each list in turn: the best match of either kind comes first. */
    fun <T> mix(a: List<T>, b: List<T>): List<T> {
        val out = ArrayList<T>(a.size + b.size)
        for (i in 0 until maxOf(a.size, b.size)) { a.getOrNull(i)?.let(out::add); b.getOrNull(i)?.let(out::add) }
        return out
    }

    // ---- The words searched before ----

    fun remember(s: Settings, query: String) {
        val q = query.trim()
        if (!s.searchRecent || q.length < 2) return
        s.searchHistory = (listOf(q) + s.searchHistory.filterNot { it.equals(q, true) }).take(8)
    }

    // ---- Where a title can be opened ----

    /** [key]: a service's package, or NuvioLaunch's key of a Nuvio app / Stremio. [sources]: Stremio with its sources listed here. */
    class Place(val key: String, val label: String, val service: Boolean, val sources: Boolean = false, val open: () -> Unit)

    /** The Nuvio apps and Stremio that are installed and not left out, in NuvioLaunch's order. */
    fun players(ctx: Context, s: Settings): List<Pair<String, String>> =
        if (!s.searchPlayers) emptyList() else NuvioLaunch.installedTargets(ctx).filter { it.first !in s.searchPlayersOff }

    /** The services' apps a title can be asked for: installed and not left out. */
    fun services(ctx: Context, s: Settings): Set<String> =
        if (!s.searchServices) emptySet() else PlatformLinks.installed(ctx) - s.searchServicesOff.toSet()

    /**
     * The places for [item], services first (they are where it is really on), then the Nuvio apps, then Stremio.
     * Blocking: the services are asked for (JustWatch), a second or two at most.
     */
    fun places(ctx: Context, s: Settings, item: HeroItem, onSources: (HeroItem) -> Unit): List<Place> {
        val out = ArrayList<Place>()
        val apps = services(ctx, s)
        if (apps.isNotEmpty()) for (l in PlatformLinks.offers(ctx, s, item)) {
            if (l.pkg !in apps) continue
            out += Place(l.pkg, appLabel(ctx, l.pkg), service = true) {
                runCatching { ctx.startActivity(PlatformLinks.intent(l).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Glog.w("Search: open ${l.pkg}", it) }
            }
        }
        val served = out.size
        for ((t, name) in players(ctx, s)) {
            val stremio = t == "stremio"
            // (Not in the Play build: there Stremio only opens its own page for the title.)
            val listed = stremio && !Store.PLAY && s.stremioSources != "page" && item.type.equals("movie", true)
            out += Place(t, name, service = false, sources = listed) {
                if (listed) onSources(item) else if (!stremio && s.searchNuvioPlay) NuvioLaunch.play(ctx, t, item) else NuvioLaunch.openIn(ctx, t, item)
            }
        }
        // (Settings → Search → Order: the Nuvio apps and Stremio before the services.)
        return if (s.searchOrder == "players") out.drop(served) + out.take(served) else out
    }

    fun appLabel(ctx: Context, pkg: String): String = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    // ---- Which of the services' apps on this TV have a title (the small icons on its poster) ----

    private val onMem = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** What is already known for [item] (null: not asked yet). */
    fun watchOnKnown(s: Settings, item: HeroItem): List<String>? = onMem[Region.of(s) + ":" + item.key]

    /**
     * The services' apps on this TV that have [item] now, in the country: TMDB's own list for a title found by
     * TMDB (one request, kept a month), else JustWatch's offers. Blocking. Empty: none, or nothing answered.
     */
    fun watchOn(ctx: Context, http: Http, s: Settings, item: HeroItem): List<String> {
        val apps = services(ctx, s)
        if (apps.isEmpty()) return emptyList()
        val key = Region.of(s) + ":" + item.key
        onMem[key]?.let { return it }
        val got: List<String>? = if (item.id.startsWith("tmdb:") && s.tmdbToken.isNotBlank()) runCatching {
            val kind = if (item.type == "movie") "movie" else "tv"
            val r = http.json("https://api.themoviedb.org/3/$kind/${item.id.removePrefix("tmdb:")}/watch/providers", 30L * 24 * 3600 * 1000, s.tmdbToken)
                .optJSONObject("results")?.optJSONObject(Region.of(s).uppercase())
            val names = ArrayList<String>()
            for (k in listOf("flatrate", "free", "ads")) r?.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("provider_name")?.let(names::add) }
            names.mapNotNull { NuvioLaunch.platformPackage(it) }.distinct().filter { it in apps }
        }.onFailure { Glog.w("Search: where a title is", it) }.getOrNull()
        else if (item.id.startsWith("tt")) PlatformLinks.offers(ctx, s, item).map { it.pkg }.distinct().filter { it in apps }
        else null
        return (got ?: emptyList()).also { if (got != null) onMem[key] = it }
    }

    /** A title's key for telling two lists' titles apart by name: its kind and its name without signs or case. */
    fun nameKey(type: String, name: String) = (if (type.equals("movie", true)) "m:" else "s:") + name.lowercase().filter { it.isLetterOrDigit() }

    // ---- Videos and songs ----

    /** SmartTube's builds and YouTube's own TV app, in the order "auto" tries them. */
    val YOUTUBE_APPS = listOf("org.smarttube.stable", "org.smarttube.beta", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.google.android.youtube.tv")
    const val SPOTIFY = "com.spotify.tv.android"
    const val SOUNDCLOUD = "com.soundcloud.android"

    /** The app a YouTube video opens in: the one chosen when it is installed, else the first of [YOUTUBE_APPS] that is. */
    fun youtubeApp(ctx: Context, s: Settings): String? =
        s.searchYoutubeApp.takeIf { it != "auto" && NuvioLaunch.installed(ctx, it) } ?: YOUTUBE_APPS.firstOrNull { NuvioLaunch.installed(ctx, it) }

    fun musicApp(ctx: Context, s: Settings, key: String, pkg: String) = s.searchMusic && key !in s.searchMusicOff && NuvioLaunch.installed(ctx, pkg)

    /**
     * Opens a video's or a song's own page in [pkg] (YouTube's apps and SoundCloud start it at once).
     * Each press is its own request (the identifier): a link equal to the one the app's task was first opened
     * with only brought that task to the front, the app never got the link and the song did not start (seen with
     * SoundCloud: the second press on a song showed its player on another, paused). Android 10 and later.
     */
    fun openClip(ctx: Context, pkg: String, url: String): Boolean = runCatching {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (android.os.Build.VERSION.SDK_INT >= 29) i.identifier = "glassio-" + android.os.SystemClock.uptimeMillis()
        ctx.startActivity(i)
    }.onFailure { Glog.w("Search: open in $pkg", it) }.isSuccess

    /**
     * A song: its link starts it in [pkg], and with [stay] Glassio comes back to the front as soon as the app
     * reports it playing, so the song plays under the search. Seen on the Streamer with SoundCloud:
     * - Glassio can't return any sooner: brought back at once, the app never got to its own screen and nothing played.
     * - A link to the song the app already holds paused opens its player and leaves it paused: it is told to play.
     * - Over a song that is playing the app shows the new song's page and goes on playing the old one: that one is
     *   paused before the link is sent.
     * - A song already playing there must not count as the new one: the title must be the one asked for, or have
     *   changed since the press. (Told to play after a wait whatever its title, it played the old song over a
     *   link the app was still working out: that takes it from one to more than six seconds.)
     * When the app never plays within twelve seconds (a sign-in page, an offer) it is left in front.
     */
    fun playSong(ctx: Context, pkg: String, url: String, name: String, stay: Boolean): Boolean {
        val app = ctx.applicationContext
        val access = NowPlaying.hasAccess(app)
        fun title(c: android.media.session.MediaController?) = c?.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val was = if (access) NowPlaying.sessionOf(app, pkg) else null
        val before = title(was)
        // Another song playing there is paused first: over a playing song the app only showed the new song's page.
        if (was?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING && !before.equals(name.trim(), ignoreCase = true))
            runCatching { was.transportControls.pause() }
        if (!openClip(ctx, pkg, url)) return false
        if (!access) return true
        Bg.io("glass-song") {
            val t0 = android.os.SystemClock.uptimeMillis()
            var told = false
            kotlinx.coroutines.delay(1200)
            while (android.os.SystemClock.uptimeMillis() - t0 < 12_000) {
                val c = NowPlaying.sessionOf(app, pkg)
                val now = title(c)
                val same = now.isNotEmpty() && now.equals(name.trim(), ignoreCase = true)
                val playing = c?.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
                if (playing && (same || now != before)) {
                    if (!stay) return@io
                    kotlinx.coroutines.delay(350)
                    // The home screen's own task is moved to the front as it is. Started with an intent from here,
                    // Android built the home screen anew (the selection went to the first app and the search closed).
                    runCatching {
                        val main = com.glasslauncher.tv.MainActivity::class.java.name
                        val task = app.getSystemService(android.app.ActivityManager::class.java)?.appTasks
                            ?.firstOrNull { it.taskInfo?.baseIntent?.component?.className == main }
                        if (task != null) task.moveToFront()
                        else app.startActivity(Intent(app, com.glasslauncher.tv.MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .putExtra("keep_place", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }.onFailure { Glog.w("Search: back from $pkg", it) }
                    return@io
                }
                if (!playing && !told && c != null && same) {
                    told = true
                    runCatching { c.transportControls.play() }.onFailure { Glog.w("Search: play in $pkg", it) }
                }
                kotlinx.coroutines.delay(250)
            }
        }
        return true
    }

    /** Spotify's own search with the words already in it (spotify:search:…: seen on the Streamer). */
    fun openSpotify(ctx: Context, query: String): Boolean = runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + enc(query.trim()))).setPackage(SPOTIFY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { Glog.w("Search: Spotify", it) }.isSuccess

    /** "3:07", "1:02:45"; "" when the length isn't known. */
    fun clock(seconds: Long): String = if (seconds <= 0) "" else if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60) else "%d:%02d".format(seconds / 60, seconds % 60)

    // ---- Stremio's sources ----

    /**
     * One source as an add-on gives it. [json] is the add-on's own object, handed to Stremio untouched; the rest is
     * read from it for sorting and for the line shown.
     */
    class Source(val addon: String, val addonName: String, val json: JSONObject, val text: String, val quality: Int, val bytes: Long, val hdr: Boolean, val cam: Boolean,
        /** A torrent (an info hash, no address): Stremio plays it through its own streaming server, where it has one. */
        val p2p: Boolean = false) {
        /** "1080p · 2.1 GB · HDR · Torrentio". */
        val line: String get() = listOfNotNull(
            when { quality >= 2160 -> "4K"; quality > 0 -> "${quality}p"; else -> null },
            bytes.takeIf { it > 0 }?.let { sizeText(it) }, "HDR".takeIf { hdr }, "CAM".takeIf { cam }, "P2P".takeIf { p2p }, addonName.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")
        /** The release's name, as far as the add-on gives one (its first line that isn't only numbers and signs). */
        val name: String get() = text.lines().map { it.trim() }.firstOrNull { l -> l.count(Char::isLetter) >= 6 }?.take(70).orEmpty()
    }

    fun sizeText(bytes: Long): String = if (bytes >= 1L shl 30) "%.1f GB".format(java.util.Locale.US, bytes / 1073741824.0) else "${bytes / 1048576} MB"

    private val Q4K = Regex("(?i)(?<![a-z0-9])(2160p|4k|uhd)(?![a-z0-9])")
    private val QP = Regex("(?i)(?<![a-z0-9])(1080|720|480|360)p(?![a-z0-9])")
    private val HDR = Regex("(?i)(?<![a-z0-9])(hdr10\\+?|hdr|dv|dovi|dolby\\s*vision)(?![a-z0-9])")
    private val CAM = Regex("(?i)(?<![a-z0-9])(cam|camrip|hdcam|telesync|hdts|telecine|hdtc|screener|scr)(?![a-z0-9])")
    private val SIZE = Regex("(?i)(\\d+(?:[.,]\\d+)?)\\s*(gb|gib|mb|mib)(?![a-z])")

    fun qualityOf(text: String): Int = if (Q4K.containsMatchIn(text)) 2160 else QP.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** The file's size from the words of a source ("💾 2.1 GB"); 0 when none is written. */
    fun sizeOf(text: String): Long {
        val m = SIZE.find(text) ?: return 0
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
        return (n * if (m.groupValues[2].lowercase().startsWith("g")) 1073741824.0 else 1048576.0).toLong()
    }

    /** Reads one add-on's answer to /stream/…json. Sources Stremio can't play from here (nothing to play) are left out. */
    fun parseSources(addon: String, addonName: String, body: JSONObject): List<Source> {
        val arr = body.optJSONArray("streams") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (listOf("url", "infoHash", "ytId", "externalUrl").none { o.optString(it).isNotBlank() }) return@mapNotNull null
            val hints = o.optJSONObject("behaviorHints")
            val text = listOf(o.optString("name"), o.optString("title"), o.optString("description"), hints?.optString("filename").orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
            Source(addon, addonName, o, text, qualityOf(text), hints?.optLong("videoSize", 0L)?.takeIf { it > 0 } ?: sizeOf(text), HDR.containsMatchIn(text), CAM.containsMatchIn(text),
                p2p = o.optString("url").isBlank() && o.optString("infoHash").isNotBlank())
        }
    }

    /**
     * The sources that pass the rules, best first: the quality asked for, then the nearest below it, then above;
     * HDR as wished; an add-on's own order kept between equals (add-ons already sort theirs).
     * [quality] 0 = any (highest first), [maxGb] 0 = no limit, [hdr] "any" / "prefer" / "avoid", [p2p] false = no torrents.
     */
    fun sortSources(all: List<Source>, quality: Int, maxGb: Int, hdr: String, noCam: Boolean, p2p: Boolean = true): List<Source> {
        val max = maxGb.toLong() shl 30
        fun rank(q: Int) = when {
            quality == 0 -> -q                      // any: the highest first
            q == quality -> Int.MIN_VALUE           // the one asked for
            q in 1 until quality -> quality - q     // below it: the nearest first
            q > quality -> 100_000 + q - quality    // above it: only after everything below
            else -> 1_000_000                       // not written
        }
        return all.withIndex().filter { (_, x) -> (!noCam || !x.cam) && (p2p || !x.p2p) && (max == 0L || x.bytes == 0L || x.bytes <= max) }
            .sortedWith(compareBy<IndexedValue<Source>>({ rank(it.value.quality) },
                { when (hdr) { "prefer" -> if (it.value.hdr) 0 else 1; "avoid" -> if (it.value.hdr) 1 else 0; else -> 0 } }, { it.index }))
            .map { it.value }
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "GlassLauncher/1.0")
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** The add-ons that give sources for [type] (their manifest says so), by manifest address → name. Blocking. */
    fun streamAddons(http: Http, s: Settings, urls: List<String>, type: String): List<Pair<String, String>> = urls.filter { it !in s.stremioAddonsOff }.mapNotNull { u ->
        val m = runCatching { http.json(u, 24L * 3600 * 1000) }.getOrNull() ?: return@mapNotNull null
        val res = m.optJSONArray("resources") ?: return@mapNotNull null
        val gives = (0 until res.length()).any { i ->
            val r = res.opt(i)
            if (r is JSONObject) r.optString("name") == "stream" && (r.optJSONArray("types")?.let { t -> (0 until t.length()).any { t.optString(it) == type } } ?: true)
            else r == "stream"
        }
        if (gives) u to m.optString("name").ifBlank { u.substringAfter("://").substringBefore('/') } else null
    }

    /**
     * Every add-on's sources for a film, sorted by the rules. Asked together, each given 12 s at most. Never kept
     * on the TV: a source's address can carry the person's own key of a service.
     */
    fun sources(http: Http, s: Settings, urls: List<String>, item: HeroItem): List<Source> {
        if (Store.PLAY) return emptyList()
        val addons = streamAddons(http, s, urls, item.type)
        val found = java.util.Collections.synchronizedList(ArrayList<Pair<Int, List<Source>>>())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(addons.size.coerceIn(1, 6))
        try {
            addons.mapIndexed { i, (u, name) ->
                pool.submit {
                    runCatching {
                        val base = u.removeSuffix("/manifest.json").trimEnd('/')
                        found += i to parseSources(u, name, JSONObject(get("$base/stream/${item.type}/${enc(item.id)}.json")))
                    }.onFailure { Glog.w("Search: sources of $name: ${it.javaClass.simpleName}") }
                }
            }.forEach { f -> runCatching { f.get(13, java.util.concurrent.TimeUnit.SECONDS) } }
        } finally { pool.shutdownNow() }
        val all = found.sortedBy { it.first }.flatMap { it.second }
        return sortSources(all, s.stremioQuality, s.stremioMaxGb, s.stremioHdr, s.stremioNoCam, s.stremioP2p)
    }

    /**
     * Stremio's own link for playing one exact source (its app registers stremio:///player/…): the add-on's stream
     * object as JSON, zlib-compressed, in standard base64 (Stream::encode in Stremio/stremio-core), then the add-on
     * it came from, the add-on its page comes from, and the title.
     */
    fun stremioPlayer(src: Source, item: HeroItem): Intent {
        val raw = src.json.toString().toByteArray()
        val z = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(z).use { it.write(raw) }
        val packed = android.util.Base64.encodeToString(z.toByteArray(), android.util.Base64.NO_WRAP)
        val uri = "stremio:///player/${enc(packed)}/${enc(src.addon)}/${enc(CINEMETA_MANIFEST)}/${item.type}/${enc(item.id)}/${enc(item.id)}"
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(NuvioLaunch.STREMIO_PKG)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }

    /**
     * Hands [src] to Stremio. Stremio is opened first and the source follows a moment later: started from nothing
     * with a player link, Stremio stops ("RUNTIME not initialized": its core isn't up yet when the link arrives;
     * seen on the Streamer). Running already, it only comes to the front first.
     */
    fun playInStremio(ctx: Context, src: Source, item: HeroItem) {
        if (Store.PLAY) { NuvioLaunch.openIn(ctx, "stremio", item); return }
        val player = stremioPlayer(src, item)
        val first = ctx.packageManager.getLeanbackLaunchIntentForPackage(NuvioLaunch.STREMIO_PKG) ?: ctx.packageManager.getLaunchIntentForPackage(NuvioLaunch.STREMIO_PKG)
        val opened = first != null && runCatching { ctx.startActivity(first.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { ctx.startActivity(player) }.onFailure { Glog.w("Search: the source to Stremio", it); NuvioLaunch.openIn(ctx, "stremio", item) }
        }, if (opened) 1600L else 0L)
    }
}
