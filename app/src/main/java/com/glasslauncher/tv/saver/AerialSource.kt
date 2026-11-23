package com.glasslauncher.tv.saver

import com.glasslauncher.tv.GlassApp
import org.json.JSONObject

/**
 * The screensaver's videos, in the collections of aerial-videos.netlify.app (the Aerial Views project's lists):
 * Apple tvOS 26, Amazon Fire TV ambient, Jetson Creative and Robin Fourcade, plus your own links and the videos
 * in Glassio's backgrounds folder. Each collection can be turned on or off; the quality is chosen once.
 */
object AerialSource {
    data class Source(val key: String, val name: String, val file: String)
    val SOURCES = listOf(
        Source("apple", "Apple tvOS", "tvos26"),
        Source("amazon", "Amazon Fire TV", "fireos8"),
        Source("jetson", "Jetson Creative", "comm1"),
        Source("robin", "Robin Fourcade", "comm2"),
    )
    /** Qualities; a video without the chosen one falls back to the nearest. */
    val QUALITIES = listOf(
        "1080-H264" to "1080p H.264 (plays everywhere)",
        "1080-SDR" to "1080p HEVC",
        "4K-SDR" to "4K HEVC",
        "1080-HDR" to "1080p HDR",
        "4K-HDR" to "4K HDR / Dolby Vision",
    )
    private const val BASE = "https://raw.githubusercontent.com/theothernt/AerialViews/master/app/src/main/res/raw/"
    private const val APPLE_FEED = "https://sylvan.apple.com/Aerials/2x/entries.json"

    data class Video(val id: String, val title: String, val source: String, val urls: Map<String, String>, val preview: String) {
        fun url(quality: String): String? {
            val order = listOf(quality) + when {
                quality.startsWith("4K") -> listOf("4K-SDR", "1080-SDR", "1080-H264")
                else -> listOf("1080-H264", "1080-SDR", "4K-SDR")
            }
            return order.firstNotNullOfOrNull { urls[it] } ?: urls.values.firstOrNull()
        }
    }

    /** Blocking. Every video of one collection (cached for a week). */
    fun videos(app: GlassApp, src: Source): List<Video> = runCatching {
        val d = app.http.json(BASE + src.file + ".json", 7L * 24 * 3600 * 1000)
        val names = runCatching { app.http.json(BASE + src.file + "_strings.json", 7L * 24 * 3600 * 1000) }.getOrNull()
        val a = d.optJSONArray("assets") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val urls = o.keys().asSequence().filter { it.startsWith("url-") }.associate { it.removePrefix("url-") to o.optString(it) }
                .filterValues { it.startsWith("http") }
            if (urls.isEmpty()) return@mapNotNull null
            Video(o.optString("id"), title(o, names), src.key, urls, o.optString("previewImage").takeIf { it.startsWith("http") }.orEmpty())
        }
    }.getOrElse {
        com.glasslauncher.tv.data.Glog.w("Aerial list ${src.name}", it)
        if (src.key == "apple") oldApple(app) else emptyList()
    }

    private fun title(o: JSONObject, names: JSONObject?): String {
        val key = o.optString("localizedNameKey")
        val label = o.optString("accessibilityLabel"); val t = o.optString("title")
        val poi = o.optJSONObject("pointsOfInterest")?.optString("0").orEmpty()
        return listOf(names?.optString(key).orEmpty(), t, label, names?.optString(poi).orEmpty()).firstOrNull { it.isNotBlank() } ?: "Aerial"
    }

    private fun oldApple(app: GlassApp): List<Video> = runCatching {
        val a = app.http.json(APPLE_FEED, 7L * 24 * 3600 * 1000).optJSONArray("assets") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val urls = o.keys().asSequence().filter { it.startsWith("url-") }.associate { it.removePrefix("url-") to o.optString(it) }.filterValues { it.startsWith("http") }
            if (urls.isEmpty()) null else Video(o.optString("id"), o.optString("accessibilityLabel").ifBlank { "Apple aerial" }, "apple", urls, "")
        }
    }.getOrElse { emptyList() }

    /** The names of My videos' YouTube links, as they are read (a playlist's name, a video's title). */
    val ytTitles = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** A YouTube video or playlist link (My videos). */
    fun isYoutube(u: String): Boolean = isPlaylist(u) || youtubeId(u) != null
    /** A YouTube playlist's link (playlist?list=…, or a video's link with its list); not a mix (RD…). */
    fun isPlaylist(u: String): Boolean = Regex("""(youtube\.com|youtu\.be)/.*[?&]list=[A-Za-z0-9_-]+""").containsMatchIn(u) && !u.contains("list=RD")
    /** A YouTube video's id in a link (watch, youtu.be, shorts, live), or null. */
    fun youtubeId(u: String): String? = Regex("""(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|live/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})""").find(u)?.groupValues?.get(1)

    /**
     * Your own videos: links you added and the videos in the backgrounds folder. A YouTube link is played from the
     * video's own picture stream, found when the list is made (the github build: NewPipe Extractor, as for the
     * trailers; in the play build it is left out). Blocking.
     */
    fun mine(app: GlassApp): List<Video> =
        app.settings.aerialUrls.filter { it.startsWith("http") && it !in app.settings.saverYtOff }.flatMap { u ->
            // A YouTube playlist (github build): each of its videos, in order (Shuffle mixes them like the rest).
            if (isPlaylist(u)) return@flatMap if (com.glasslauncher.tv.data.Store.PLAY) emptyList() else
                runCatching { com.glasslauncher.tv.data.MediaSearch.playlist(u) }.onFailure { com.glasslauncher.tv.data.Glog.w("YouTube playlist", it) }.getOrDefault(emptyList())
                    .map { c -> Video(c.url, c.title, "mine", mapOf("1080-H264" to c.url), "") }
            val yt = youtubeId(u) ?: return@flatMap listOf(Video(u, "", "mine", mapOf("1080-H264" to u), ""))
            if (com.glasslauncher.tv.data.Store.PLAY) return@flatMap emptyList()
            // (Its page address is what plays: the stream is looked up as it is about to play (VideoCache), so it
            // never runs out, and the copy kept on the TV is found again by that address.)
            val title = runCatching { com.glasslauncher.tv.data.TrailerSource.resolve(yt, 1080)?.title }.getOrNull().orEmpty()
            if (title.isNotBlank()) ytTitles[u] = title
            listOf(Video(u, title, "mine", mapOf("1080-H264" to u), ""))
        } +
            com.glasslauncher.tv.ui.CustomBackground.files(app, true).map { Video(it.absolutePath, it.nameWithoutExtension, "mine", mapOf("1080-H264" to "file://" + it.absolutePath), it.absolutePath) }

    /** Blocking. Every video of the collections that are on (for choosing one). */
    fun all(app: GlassApp): List<Video> = buildList {
        val st = app.settings
        if (st.saverMine) addAll(mine(app))
        SOURCES.filter { it.key in st.saverSources }.forEach { addAll(videos(app, it)) }
        if (PEXELS in st.saverSources) addAll(pexels(app))
    }

    /** Pexels' free videos (pexels.com), with the person's own API key; off until a key is given. */
    const val PEXELS = "pexels"

    /**
     * Blocking. Pexels' videos for the search words ("aerial" at first), landscape, the 40 most relevant; kept a day.
     * Each one's title carries its maker and Pexels, as Pexels asks of apps that show its videos.
     */
    fun pexels(app: GlassApp): List<Video> {
        val st = app.settings
        if (st.pexelsKey.isBlank()) return emptyList()
        return runCatching {
            val q = java.net.URLEncoder.encode(st.pexelsQuery.ifBlank { "aerial" }, "UTF-8")
            val d = app.http.json("https://api.pexels.com/videos/search?query=$q&orientation=landscape&size=large&per_page=40", 24L * 3600 * 1000, key = st.pexelsKey)
            val a = d.optJSONArray("videos") ?: return emptyList()
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val files = o.optJSONArray("video_files") ?: return@mapNotNull null
                val mp4 = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                    .filter { it.optString("file_type") == "video/mp4" && it.optString("link").startsWith("http") && it.optInt("width") >= it.optInt("height") }
                val hd = mp4.filter { it.optInt("height") in 700..1080 }.maxByOrNull { it.optInt("height") }
                val uhd = mp4.filter { it.optInt("height") >= 2000 }.minByOrNull { it.optInt("height") }
                val urls = buildMap {
                    hd?.let { put("1080-H264", it.optString("link")) }
                    uhd?.let { put("4K-SDR", it.optString("link")) }
                }
                if (urls.isEmpty()) return@mapNotNull null
                // The name is in the page's address: https://www.pexels.com/video/aerial-view-of-a-beach-1234/
                val slug = o.optString("url").trimEnd('/').substringAfterLast('/').substringBeforeLast('-')
                val name = slug.replace('-', ' ').trim().replaceFirstChar { it.uppercase() }.ifBlank { "Pexels video" }
                val by = o.optJSONObject("user")?.optString("name").orEmpty()
                Video("pexels:" + o.optLong("id"), if (by.isNotBlank()) "$name · $by, Pexels" else "$name · Pexels", PEXELS, urls,
                    o.optString("image").takeIf { it.startsWith("http") }.orEmpty())
            }
        }.getOrElse { com.glasslauncher.tv.data.Glog.w("Pexels videos", it); emptyList() }
    }

    /** Link → video id, of every list made (the screensaver's Lock & release and Hidden videos name videos by id). */
    @Volatile var idOfUrl: Map<String, String> = emptyMap()

    /** Blocking. What the screensaver plays, as (title, link), in play order. */
    fun playlistItems(app: GlassApp, quality: String = app.settings.saverQuality, forSaver: Boolean = true): List<Pair<String, String>> {
        val st = app.settings
        val all = all(app)
        val pick = st.saverPick
        var chosen = if (pick.isNotBlank()) all.filter { it.id == pick }.ifEmpty { all } else all
        // The videos hidden from the screensaver's menu (*Don't show it again*): never, unless that is all there is.
        val hidden = st.saverHidden.map { it.substringBefore('\t') }.toSet()
        if (hidden.isNotEmpty() && pick.isBlank()) chosen.filter { it.id !in hidden }.takeIf { it.isNotEmpty() }?.let { chosen = it }
        // Only the locked videos (Lock & release), when that is on and there are some.
        if (forSaver && st.saverLock && st.saverLockedOnly && pick.isBlank()) {
            val locked = st.saverLocked.toSet()
            chosen.filter { it.id in locked }.takeIf { it.isNotEmpty() }?.let { chosen = it }
        }
        val pairs = chosen.mapNotNull { v -> v.url(quality)?.let { Triple(v.title, it, v.id) } }.distinctBy { it.second }
        // (Every list adds to it: the background's list and the screensaver's are of different qualities, and one
        // replacing the other left the video on screen unknown: Keep and Don't show it again did nothing.)
        idOfUrl = idOfUrl + pairs.associate { it.second to it.third }
        val list = pairs.map { it.first to it.second }
        return if (st.saverShuffle && pick.isBlank()) list.shuffled() else list
    }

    /** Blocking. My videos only (the background's *Plays: My videos*), without the hidden ones, in [quality]. */
    fun mineItems(app: GlassApp, quality: String): List<Pair<String, String>> {
        val hidden = app.settings.saverHidden.map { it.substringBefore('\t') }.toSet()
        // *Play only one of them* (My videos) counts here too: the background on My videos (and the screensaver on
        // it, One player) played them all, shuffled, whatever was chosen (#22).
        val pick = app.settings.saverPick
        val vids = mine(app).let { all -> all.filter { it.id == pick }.ifEmpty { all.filter { it.id !in hidden } } }
        idOfUrl = idOfUrl + vids.mapNotNull { v -> v.url(quality)?.let { it to v.id } }
        val list = vids.mapNotNull { v -> v.url(quality)?.let { v.title.ifBlank { "My video" } to it } }.distinctBy { it.second }
        return if (app.settings.bgShuffle) list.shuffled() else list
    }

    /** Blocking; call off the main thread. The links only (background video). */
    /** For the home screen's background: always 1080 H.264, the lightest to play (4K or HDR behind the glass slowed the whole home screen). */
    fun playlist(app: GlassApp): List<String> = playlistItems(app, "1080-H264", forSaver = false).map { it.second }
}
