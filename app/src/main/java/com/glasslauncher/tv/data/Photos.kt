package com.glasslauncher.tv.data

import com.glasslauncher.tv.GlassApp
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Background "Photos": 4K pictures that change by themselves, with where each was taken and who took it.
 *  - Bing: the photo of the day (UHD, 3840 px) from several countries. No account.
 *  - Wikimedia: Wikimedia Commons' featured pictures (chosen by its community) on a subject. No account.
 *  - Unsplash: photographers' pictures on a subject. Needs a free Access Key.
 *  - Mine: the pictures in Glassio's backgrounds folder.
 * Each photo is downloaded before it is shown (the next one is fetched while this one is on screen), and kept on
 * the TV up to a size you choose, so it changes at once and comes back without downloading.
 */
object Photos {
    data class Photo(val url: String, val title: String, val credit: String)

    val SOURCES = listOf("bing" to "Bing photo of the day", "commons" to "Wikimedia (featured, 4K)", "unsplash" to "Unsplash", "mine" to "My pictures")
    val TOPICS = listOf("nature", "mountains", "forest", "waterfalls", "city", "architecture", "space")
    private const val UA = "Glassio/2.0 (Google TV launcher; https://github.com/mohammadmrh12/glassio)"

    /** The photo on screen now: [Photo.url] is the copy on the TV (file://). */
    val current = androidx.compose.runtime.mutableStateOf<Photo?>(null)
    @Volatile var status: String = ""; private set
    @Volatile private var skip = false
    @Volatile private var started = false

    /** ▶: the next photo now. */
    fun next() { skip = true }

    private fun dir(ctx: android.content.Context) = File(ctx.filesDir, "photo-cache").apply { mkdirs() }
    fun cacheUsedMb(ctx: android.content.Context): Long = (dir(ctx).listFiles().orEmpty().sumOf { it.length() } / 1048576)

    fun start(app: GlassApp) {
        if (started) return
        started = true
        // The last photo comes back straight away after a restart (the list is fetched again behind it).
        app.settings.photoLast.split('\t').takeIf { it.size == 3 && it[0].isNotBlank() }?.let { p ->
            if (!p[0].startsWith("file://") || File(p[0].removePrefix("file://")).isFile) current.value = Photo(p[0], p[1], p[2])
        }
        Thread({ loop(app) }, "glass-photos").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun loop(app: GlassApp) {
        var list: List<Photo> = emptyList()
        var order: List<Int> = emptyList()
        var pos = -1
        var listKey = ""
        var listAt = 0L
        var changedAt = System.currentTimeMillis()
        while (true) {
            val st = app.settings
            if (st.bgMode != "photos") { Thread.sleep(2_000); continue }
            val key = listOf(st.photoSource, st.photoTopic, st.unsplashKey.isNotBlank(), st.photoShuffle).joinToString("|")
            val now = System.currentTimeMillis()
            // A setting changed (not just the first list after Glassio started): a photo from the new choice now.
            val changed = key != listKey && listKey.isNotEmpty()
            if (key != listKey || list.isEmpty() || now - listAt > 6 * 3600_000L) {
                status = ""
                val fresh = runCatching { fetch(app) }.onFailure { Glog.w("Photos", it); status = it.message ?: "Couldn't get the photos" }.getOrDefault(emptyList())
                listAt = now
                if (fresh.isEmpty()) { if (status.isBlank()) status = "No photos found"; listKey = key; Thread.sleep(30_000); continue }
                list = fresh
                order = if (st.photoShuffle) list.indices.shuffled() else list.indices.toList()
                pos = -1
                listKey = key
                status = "${list.size} photos"
                Glog.i("Photos: ${list.size} from ${st.photoSource}")
            }
            val due = st.photoEvery > 0 && now - changedAt >= st.photoEvery * 1000L
            if (due || skip || changed || current.value == null) {
                skip = false
                // The next one that is on the TV already, or downloads (a broken link is skipped).
                var shown = false
                for (attempt in 0 until 4) {
                    pos++
                    if (pos >= order.size) { pos = 0; if (st.photoShuffle) order = list.indices.shuffled() }
                    val p = list[order[pos]]
                    val f = runCatching { local(app, p.url) }.onFailure { Glog.w("Photo download ${p.url}", it) }.getOrNull() ?: continue
                    val onTv = p.copy(url = "file://" + f.absolutePath)
                    current.value = onTv
                    st.photoLast = listOf(onTv.url, onTv.title, onTv.credit).joinToString("\t") { it.replace('\t', ' ') }
                    Glog.i("Photos: ${p.title.ifBlank { p.url }}")
                    shown = true
                    break
                }
                changedAt = now
                if (shown) {
                    // The following photo is fetched now, so the next change is instant.
                    val following = list[order[(pos + 1) % order.size]]
                    runCatching { local(app, following.url) }
                    trim(app)
                }
            }
            Thread.sleep(1_000)
        }
    }

    /** The photo as a file on the TV: kept ones at once, others downloaded first (whole, then shown). */
    private fun local(app: GlassApp, url: String): File {
        if (url.startsWith("file://")) return File(url.removePrefix("file://")).also { check(it.isFile) { "missing" } }
        val name = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".jpg"
        val f = File(dir(app), name)
        if (f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f }
        val part = File(f.path + ".part")
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 30_000
            c.setRequestProperty("User-Agent", UA)
            try {
                val code = c.responseCode
                if (code in 300..399) { u = URL(u, c.getHeaderField("Location")); return@repeat }
                check(code == 200) { "HTTP $code" }
                c.inputStream.use { inp -> part.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            } finally { c.disconnect() }
            check(part.length() > 10_000) { "not a picture" }
            check(part.renameTo(f)) { "couldn't save it" }
            return f
        }
        error("too many redirects")
    }

    /** The oldest photos go once the chosen size is passed (the one on screen and the next one stay). */
    private fun trim(app: GlassApp) {
        val max = app.settings.photoCacheMb * 1048576L
        val keep = setOfNotNull(current.value?.url?.removePrefix("file://"))
        val files = dir(app).listFiles().orEmpty().filter { it.isFile }.sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= max) break
            if (f.absolutePath in keep) continue
            total -= f.length(); f.delete()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json")
        try {
            check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun fetch(app: GlassApp): List<Photo> {
        val st = app.settings
        return when (st.photoSource) {
            "mine" -> com.glasslauncher.tv.ui.CustomBackground.files(app, false)
                .map { Photo("file://" + it.absolutePath, it.nameWithoutExtension.replace('_', ' ').replace('-', ' '), "") }
                .also { if (it.isEmpty()) error("No pictures in the backgrounds folder yet") }
            "commons", "wallhaven" -> commons(st.photoTopic)
            "unsplash" -> unsplash(app, st.photoTopic, st.unsplashKey)
            else -> bing(app)
        }
    }

    /** Bing's photo of the day for the last week, from several countries (each has its own), in 4K. */
    private fun bing(app: GlassApp): List<Photo> {
        val out = LinkedHashMap<String, Photo>()
        for (mkt in listOf("en-US", "en-GB", "de-DE", "fr-FR", "ja-JP", "en-AU", "en-CA", "it-IT", "es-ES", "zh-CN")) for (idx in listOf(0, 7)) runCatching {
            val j = app.http.json("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=$mkt", 6 * 3600_000L)
            val arr = j.optJSONArray("images") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val o = arr.optJSONObject(k) ?: continue
                val base = o.optString("urlbase").takeIf { it.isNotBlank() } ?: continue
                // The same photo has a different name per country (OHR.Name_EN-US…): one of each.
                val id = base.substringAfter("OHR.").substringBefore('_')
                if (id in out) continue
                val copy = o.optString("copyright")
                val place = copy.substringBefore(" (©").trim().ifBlank { o.optString("title") }
                val by = copy.substringAfter("(©", "").substringBeforeLast(')').trim()
                out[id] = Photo("https://www.bing.com${base}_UHD.jpg", place, if (by.isNotBlank()) "© $by" else "Bing")
            }
        }
        if (out.isEmpty()) error("Bing didn't answer")
        return out.values.toList()
    }

    /** Wikimedia Commons' featured pictures on [topic], landscape and at least 2400 px wide, at 3840 px. */
    private fun commons(topic: String): List<Photo> {
        val cat = when (topic) {
            "mountains" -> "Featured_pictures_of_mountains"; "forest" -> "Featured_pictures_of_forests"
            "waterfalls" -> "Featured_pictures_of_waterfalls"; "city" -> "Featured_pictures_of_cityscapes"
            "architecture" -> "Featured_pictures_of_architecture"; "space" -> "Featured_pictures_of_astronomy"
            else -> "Featured_pictures_of_landscapes"
        }
        val out = ArrayList<Photo>()
        var cont = ""
        for (page in 0 until 4) {
            val j = JSONObject(get("https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=categorymembers&gcmtitle=Category:$cat" +
                "&gcmtype=file&gcmlimit=50&prop=imageinfo&iiprop=url%7Csize%7Cextmetadata&iiurlwidth=3840&iiextmetadatafilter=ObjectName%7CArtist" + cont))
            val pages = j.optJSONObject("query")?.optJSONObject("pages") ?: break
            for (k in pages.keys()) {
                val ii = pages.optJSONObject(k)?.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val w = ii.optInt("width"); val h = ii.optInt("height")
                if (w < 2400 || w < h * 1.3f) continue
                val url = ii.optString("thumburl").takeIf { it.startsWith("http") && w > 3840 } ?: ii.optString("url").takeIf { it.startsWith("http") } ?: continue
                val meta = ii.optJSONObject("extmetadata")
                fun m(n: String) = meta?.optJSONObject(n)?.optString("value").orEmpty().replace(Regex("<[^>]*>"), "").replace("&amp;", "&").trim()
                val title = m("ObjectName").ifBlank { pages.optJSONObject(k)?.optString("title").orEmpty().removePrefix("File:").substringBeforeLast('.') }
                val who = m("Artist").lines().firstOrNull().orEmpty().take(60)
                out += Photo(url, title.replace('_', ' ').take(90), if (who.isNotBlank()) "© $who · Wikimedia Commons" else "Wikimedia Commons")
            }
            cont = j.optJSONObject("continue")?.optString("gcmcontinue")?.takeIf { it.isNotBlank() }?.let { "&gcmcontinue=" + enc(it) } ?: break
        }
        if (out.isEmpty()) error("Wikimedia found nothing for \"$topic\"")
        return out
    }

    /** Unsplash: 30 landscape photos on [topic] at 4K, with the place and the photographer. */
    private fun unsplash(app: GlassApp, topic: String, key: String): List<Photo> {
        require(key.isNotBlank()) { "Unsplash needs a free Access Key (unsplash.com/developers): add it in Settings" }
        val arr = JSONArray(app.http.text("https://api.unsplash.com/photos/random?count=30&orientation=landscape&content_filter=high&query=${enc(topic)}&client_id=${enc(key)}", 3 * 3600_000L))
        val out = ArrayList<Photo>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            val raw = o.optJSONObject("urls")?.optString("raw")?.takeIf { it.startsWith("http") } ?: continue
            val place = o.optJSONObject("location")?.let { l -> l.optString("name").takeIf { it.isNotBlank() && it != "null" } }
            val what = o.optString("description").takeIf { it.isNotBlank() && it != "null" } ?: o.optString("alt_description").takeIf { it.isNotBlank() && it != "null" }
            val who = o.optJSONObject("user")?.optString("name").orEmpty()
            out += Photo("$raw&w=3840&fm=jpg&q=82", (place ?: what ?: topic).replaceFirstChar { it.uppercase() }.take(80),
                if (who.isNotBlank()) "Photo: $who · Unsplash" else "Unsplash")
        }
        if (out.isEmpty()) error("Unsplash found nothing for \"$topic\"")
        return out
    }
}
