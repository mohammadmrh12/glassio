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
 *  - Windows Spotlight: Windows' lock-screen pictures (4K). No account.
 *  - Wikimedia: Wikimedia Commons' featured pictures (chosen by its community) on a subject. No account.
 *  - Paintings: the Cleveland Museum of Art's public-domain paintings. No account.
 *  - Unsplash, Pexels: photographers' pictures on a subject. Each needs its free key.
 *  (Subreddit wallpapers were here until 4.7.3: Reddit lets no app in without an id of its own any more.)
 *  - Mine: the pictures in Glassio's backgrounds folder.
 * Each photo is downloaded before it is shown (the next one is fetched while this one is on screen), and kept on
 * the TV up to a size you choose, so it changes at once and comes back without downloading.
 */
object Photos {
    data class Photo(val url: String, val title: String, val credit: String)

    val SOURCES = listOf("bing" to "Bing photo of the day", "spotlight" to "Windows Spotlight (4K)", "commons" to "Wikimedia (featured, 4K)",
        "art" to "Paintings (museum)", "unsplash" to "Unsplash", "pexels" to "Pexels", "mine" to "My pictures")
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
        Bg.io("glass-photos") { loop(app) }
    }

    private fun loop(app: GlassApp) {
        var list: List<Photo> = emptyList()
        var order: List<Int> = emptyList()
        var pos = -1
        var listKey = ""
        var listAt = 0L
        var changedAt = System.currentTimeMillis()
        // A source that keeps refusing (HTTP 403 every half minute for hours, #34) is asked less and less often:
        // 30 s, 1, 2, 4 … 30 min. A changed choice asks at once.
        var failWait = 30_000L
        while (true) {
            val st = app.settings
            if (st.bgMode != "photos") { Thread.sleep(2_000); continue }
            val key = listOf(st.photoSource, st.photoTopic, st.unsplashKey.isNotBlank(), st.photoShuffle, st.pexelsKey.isNotBlank()).joinToString("|")
            val now = System.currentTimeMillis()
            // A setting changed (not just the first list after Glassio started): a photo from the new choice now.
            val changed = key != listKey && listKey.isNotEmpty()
            if (key != listKey || list.isEmpty() || now - listAt > 6 * 3600_000L) {
                status = ""
                val fresh = runCatching { fetch(app) }.onFailure { Glog.w("Photos", it); status = it.message ?: "Couldn't get the photos" }.getOrDefault(emptyList())
                listAt = now
                if (fresh.isEmpty()) {
                    if (status.isBlank()) status = "No photos found"; listKey = key
                    val until = System.currentTimeMillis() + failWait; failWait = (failWait * 2).coerceAtMost(30 * 60_000L)
                    while (System.currentTimeMillis() < until) {
                        Thread.sleep(2_000)
                        val s2 = app.settings
                        if (listOf(s2.photoSource, s2.photoTopic, s2.unsplashKey.isNotBlank(), s2.photoShuffle, s2.pexelsKey.isNotBlank()).joinToString("|") != key) { failWait = 30_000L; break }
                    }
                    continue
                }
                failWait = 30_000L
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
                    runCatching { local(app, following.url) }.onFailure { Glog.w("Photos: saving the next photo", it) }
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
            "spotlight" -> spotlight()
            "art" -> paintings(app)
            "pexels" -> pexels(app, st.photoTopic, st.pexelsKey)
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

    /**
     * Windows Spotlight: the lock-screen pictures of Windows (landscapes and wildlife from around the world, 3840×2160),
     * a few from each of several countries. No account. Each comes with its title and who took it.
     */
    private fun spotlight(): List<Photo> {
        val out = LinkedHashMap<String, Photo>()
        // (Each call gives four at random: twice per country, the same picture once.)
        for ((country, locale) in listOf("US" to "en-US", "GB" to "en-GB", "DE" to "de-DE", "FR" to "fr-FR", "JP" to "ja-JP", "AU" to "en-AU", "CA" to "en-CA", "IN" to "en-IN")) repeat(2) {
            runCatching {
                val items = JSONObject(get("https://fd.api.iris.microsoft.com/v4/api/selection?placement=88000820&bcnt=4&country=$country&locale=$locale&fmt=json"))
                    .optJSONObject("batchrsp")?.optJSONArray("items") ?: JSONArray()
                for (k in 0 until items.length()) {
                    val ad = JSONObject(items.optJSONObject(k)?.optString("item").orEmpty()).optJSONObject("ad") ?: continue
                    val url = ad.optJSONObject("landscapeImage")?.optString("asset")?.takeIf { it.startsWith("https://") } ?: continue
                    if (url in out) continue
                    out[url] = Photo(url, ad.optString("title").take(90), ad.optString("copyright").trim().take(80).ifBlank { "Windows Spotlight" })
                }
            }
        }
        if (out.isEmpty()) error("Windows Spotlight didn't answer")
        return out.values.toList()
    }

    /**
     * Paintings: the Cleveland Museum of Art's open-access collection (public domain, CC0), the landscape-shaped ones,
     * at 3400 px. No account. Each with its title, its painter and its year.
     */
    private fun paintings(app: GlassApp): List<Photo> {
        val out = ArrayList<Photo>()
        for (skip in listOf(0, 200, 400, 600, 800)) runCatching {
            val data = app.http.json("https://openaccess-api.clevelandart.org/api/artworks/?cc0=1&has_image=1&type=Painting&limit=200&skip=$skip" +
                "&fields=title,creators,creation_date,images", 7L * 24 * 3600_000L).optJSONArray("data") ?: JSONArray()
            for (k in 0 until data.length()) {
                val o = data.optJSONObject(k) ?: continue
                val pic = o.optJSONObject("images")?.optJSONObject("print") ?: continue
                val w = pic.optString("width").toIntOrNull() ?: pic.optInt("width"); val h = pic.optString("height").toIntOrNull() ?: pic.optInt("height")
                if (w < 2400 || w < h * 1.25f) continue
                val url = pic.optString("url").takeIf { it.startsWith("https://") } ?: continue
                // "Robert S. Duncanson (American, 1821–1872)" → the name.
                val who = o.optJSONArray("creators")?.optJSONObject(0)?.optString("description").orEmpty().substringBefore(" (").trim()
                val year = o.optString("creation_date").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                out += Photo(url, o.optString("title").take(90), listOf(who, year).filter { it.isNotBlank() }.joinToString(", ").ifBlank { "Painting" }.take(70) + " · Cleveland Museum of Art")
            }
        }.onFailure { Glog.w("Paintings (from $skip)", it) }
        if (out.isEmpty()) error("The museum didn't answer")
        return out
    }

    /** Pexels: 80 landscape photos on [topic] at 4K, with your own Pexels key (the same one as its videos). */
    private fun pexels(app: GlassApp, topic: String, key: String): List<Photo> {
        require(key.isNotBlank()) { "Pexels needs a free key (pexels.com/api): add it in Settings" }
        val arr = app.http.json("https://api.pexels.com/v1/search?query=${enc(topic)}&orientation=landscape&size=large&per_page=80", 6 * 3600_000L, key = key).optJSONArray("photos") ?: JSONArray()
        val out = ArrayList<Photo>()
        for (k in 0 until arr.length()) {
            val o = arr.optJSONObject(k) ?: continue
            if (o.optInt("width") < 2400 || o.optInt("width") < o.optInt("height") * 1.25f) continue
            val src = o.optJSONObject("src")?.optString("original")?.takeIf { it.startsWith("https://") } ?: continue
            val who = o.optString("photographer")
            out += Photo("$src?auto=compress&cs=tinysrgb&w=3840", o.optString("alt").ifBlank { topic }.replaceFirstChar { it.uppercase() }.take(80),
                if (who.isNotBlank()) "Photo: $who · Pexels" else "Pexels")
        }
        if (out.isEmpty()) error("Pexels found nothing for \"$topic\"")
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
