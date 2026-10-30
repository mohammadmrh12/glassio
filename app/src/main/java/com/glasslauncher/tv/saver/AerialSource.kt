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

    /** Your own videos: links you added and the videos in the backgrounds folder. */
    fun mine(app: GlassApp): List<Video> =
        app.settings.aerialUrls.filter { it.startsWith("http") }.map { Video(it, "", "mine", mapOf("1080-H264" to it), "") } +
            com.glasslauncher.tv.ui.CustomBackground.files(app, true).map { Video(it.absolutePath, it.nameWithoutExtension, "mine", mapOf("1080-H264" to "file://" + it.absolutePath), it.absolutePath) }

    /** Blocking. Every video of the collections that are on (for choosing one). */
    fun all(app: GlassApp): List<Video> = buildList {
        val st = app.settings
        if (st.saverMine) addAll(mine(app))
        SOURCES.filter { it.key in st.saverSources }.forEach { addAll(videos(app, it)) }
    }

    /** Blocking. What the screensaver plays, as (title, link), in play order. */
    fun playlistItems(app: GlassApp, quality: String = app.settings.saverQuality): List<Pair<String, String>> {
        val st = app.settings
        val all = all(app)
        val pick = st.saverPick
        val chosen = if (pick.isNotBlank()) all.filter { it.id == pick }.ifEmpty { all } else all
        val list = chosen.mapNotNull { v -> v.url(quality)?.let { v.title to it } }.distinctBy { it.second }
        return if (st.saverShuffle && pick.isBlank()) list.shuffled() else list
    }

    /** Blocking; call off the main thread. The links only (background video). */
    /** For the home screen's background: always 1080 H.264, the lightest to play (4K or HDR behind the glass slowed the whole home screen). */
    fun playlist(app: GlassApp): List<String> = playlistItems(app, "1080-H264").map { it.second }
}
