package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A song's words in time with it, beside the player and on the video screensaver (his ask: like the lyrics view of
 * Apple Music).
 *
 * - **Where from** ([SOURCES], each can be switched off in Settings → Music & app rows → Lyrics → Sources). All are
 *   asked with the song's name, its artist and its length, which is all that leaves the TV; only while *Lyrics* is
 *   on (off by default, turned on by the player's or the island's lyrics button, or in Settings):
 *   - **LRCLIB** (lrclib.net): a free, open lyrics database with no key. The lines with the moment each starts.
 *   - **NetEase Cloud Music** (music.163.com, its public web calls, no key): for well-known songs the moment of
 *     every *word* (the words light exactly as they are sung), else of each line.
 *   - **KuGou** (kugou.com, its public calls, no key): word by word or line by line; its words for songs not in
 *     Latin letters are often written in Latin letters, so it comes after the others.
 *   - **lyrics.ovh**: plain words with no times, the last resort (spread evenly over the song).
 * - **Which answer wins** ([rank]): all sources are asked at once and the best is kept: NetEase's word by word,
 *   LRCLIB's lines, NetEase's lines, KuGou's, then plain words. Words in the song's own writing (Arabic, Korean…)
 *   win over the same song written in Latin letters ([pick]).
 * - A line with no words is a pause in the singing (the three dots). Lines that are credits ("Lyrics by: …",
 *   the Chinese ones NetEase and KuGou put first) are left out ([clean]).
 * - Kept on the TV (`files/lyrics`, a small text file a song, 30 days; "none" is kept a day) and the last few in
 *   memory, so a song played again shows its words at once.
 * - YouTube apps give a video's title for a song ("Artist - Song (Official Video)"): [candidates] tries the name as it
 *   is, then without what is in brackets, then split at the dash.
 */
object Lyrics {
    /** One word of a line: when it starts (ms from the song's start) and its letters as they stand in the line (its space too). */
    data class Word(val at: Long, val text: String)
    /** One line: when it starts and its words ("" = a pause); [words] when the source times each word. */
    data class Line(val at: Long, val text: String, val words: List<Word>? = null)
    /**
     * A song's words. [synced]: each line has its own moment (else they are spread evenly over the song);
     * [byWord]: each word too. [source]: the key of where they came from.
     */
    class Song(val lines: List<Line>, val synced: Boolean, val source: String = "", val byWord: Boolean = false)

    /** The sources, in the order they are listed: key, name, what it gives. */
    val SOURCES = listOf(
        Triple("lrclib", "LRCLIB", "An open lyrics database. Line by line"),
        Triple("netease", "NetEase Cloud Music", "Word by word for well-known songs, else line by line"),
        Triple("kugou", "KuGou", "Word by word or line by line; other writings often in Latin letters"),
        Triple("ovh", "lyrics.ovh", "Plain words with no times: the last resort"),
    )
    fun sourceName(key: String) = SOURCES.firstOrNull { it.first == key }?.second ?: key

    private const val UA = "Glassio (Google TV launcher; https://github.com/mohammadmrh12/glassio)"
    private const val BROWSER = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private val memory = object : LinkedHashMap<String, Song?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song?>?) = size > 24
    }
    /** The song the words were last asked for, and what came: null while asking, [NONE] when there are none. */
    val NONE = Song(emptyList(), false)
    val shown = androidx.compose.runtime.mutableStateOf<Pair<String, Song?>?>(null)
    @Volatile private var asking = ""

    fun keyOf(title: String, artist: String) = title.trim().lowercase() + "|" + artist.trim().lowercase()

    /** The sources in use ([Settings.lyricsSourcesOff] lists the ones switched off). */
    fun sourcesOn(st: Settings): List<String> { val off = st.lyricsSourcesOff.split(',').map { it.trim() }.toSet(); return SOURCES.map { it.first }.filter { it !in off } }

    /** The words of this song, asked for off the main thread; [shown] follows. Called when the song changes with Lyrics on. */
    fun want(ctx: Context, title: String, artist: String, durationMs: Long) {
        val app = ctx.applicationContext
        val sources = (app as? com.glasslauncher.tv.GlassApp)?.settings?.let { sourcesOn(it) } ?: SOURCES.map { it.first }
        // (A change of the sources asks again: they are part of what a song's words are remembered by.)
        val key = keyOf(title, artist); val memKey = key + "|" + sources.joinToString(",")
        if (title.isBlank()) { shown.value = key to NONE; return }
        synchronized(memory) { if (memory.containsKey(memKey)) { shown.value = key to (memory[memKey] ?: NONE); return } }
        if (asking == memKey) return
        asking = memKey
        shown.value = key to null
        Bg.io("glass-lyrics") {
            val song = runCatching { load(app, title, artist, durationMs, sources) }.onFailure { Glog.w("Lyrics", it) }.getOrNull()
            synchronized(memory) { memory[memKey] = song }
            if (asking == memKey) { asking = ""; shown.value = key to (song ?: NONE) }
            Glog.i("Lyrics: " + if (song == null) "none found" else "${song.lines.count { it.text.isNotBlank() }} lines from ${song.source}" +
                if (song.byWord) " (word by word)" else if (song.synced) "" else " (not timed)")
        }
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, "lyrics").apply { mkdirs() }
    private fun fileOf(ctx: Context, key: String) =
        File(dir(ctx), java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".txt")

    private fun load(ctx: Context, title: String, artist: String, durationMs: Long, sources: List<String>): Song? {
        // ("|2": NetEase's word-by-word files are kept with its lines since then, see [respace]; older files are left to age out.)
        val f = fileOf(ctx, keyOf(title, artist) + "|" + sources.joinToString(",") + "|2")
        if (f.isFile) {
            val age = System.currentTimeMillis() - f.lastModified()
            val text = f.readText()
            if (text == "-" && age < 24 * 3600_000L) return null
            if (text != "-" && age < 30L * 24 * 3600_000L) decode(text, durationMs)?.let { return it }
        }
        val got = fetch(title, artist, durationMs, sources)
        val song = got?.let { decode(it.kept(), durationMs) }
        runCatching { f.writeText(if (song != null) got.kept() else "-") }
        // (A few hundred songs at most: the oldest go.)
        runCatching { dir(ctx).listFiles().orEmpty().sortedByDescending { it.lastModified() }.drop(300).forEach { it.delete() } }
        return song
    }

    /**
     * One source's answer. [kind]: 'Y' NetEase's word-by-word lines, 'K' KuGou's, 'S' lines with times (LRC),
     * 'P' plain words. Kept in a song's file as "<kind> <source>\n<text>".
     */
    class Found(val source: String, val kind: Char, val text: String) { fun kept() = "$kind $source\n$text" }

    /** What is kept for a song, read back (kept apart for tests). An older file has no source: LRCLIB's. */
    fun decode(text: String, durationMs: Long): Song? {
        val head = text.substringBefore('\n'); val body = text.substringAfter('\n', "")
        val source = head.drop(1).trim().ifEmpty { "lrclib" }
        return when (head.firstOrNull()) {
            'S' -> clean(parse(body)).takeIf { l -> l.any { it.text.isNotBlank() } }?.let { Song(it, true, source) }
            'Y' -> clean(respace(parseYrc(body), parse(body))).takeIf { l -> l.any { it.text.isNotBlank() } }?.let { Song(it, true, source, byWord = true) }
            'K' -> clean(parseKrc(body)).takeIf { l -> l.any { it.text.isNotBlank() } }?.let { Song(it, true, source, byWord = true) }
            'P' -> spread(body, durationMs).takeIf { it.isNotEmpty() }?.let { Song(it, false, source) }
            else -> null
        }
    }

    private fun get(url: String, browser: Boolean = false, referer: String? = null): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6_000; c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", if (browser) BROWSER else UA); c.setRequestProperty("Accept", "application/json")
        referer?.let { c.setRequestProperty("Referer", it) }
        try {
            val code = c.responseCode
            return code to (if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else "")
        } finally { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * Every source in use asked at once, each on its own, and the best answer ([pick]). Not all are waited for
     * (the slowest took 8 s for a song the first had in one): it ends as soon as nothing better can still come:
     * NetEase's word by word once LRCLIB has answered too (its words may be in the song's own writing), LRCLIB's
     * lines once NetEase has answered (or 4 s in), anything timed once those two have; 9 s at most.
     */
    private fun fetch(title: String, artist: String, durationMs: Long, sources: List<String>): Found? {
        val secs = durationMs / 1000
        val names = candidates(title, artist)
        val found = java.util.Collections.synchronizedList(ArrayList<Found>())
        val finished = java.util.Collections.synchronizedSet(HashSet<String>())
        val done = java.util.concurrent.CountDownLatch(sources.size)
        for (s in sources) Bg.io("glass-lyrics-$s") {
            try {
                when (s) {
                    "lrclib" -> lrclib(names, secs)
                    "netease" -> netease(names, secs)
                    "kugou" -> kugou(names, secs)
                    "ovh" -> ovh(names)
                    else -> emptyList()
                }.let { found.addAll(it) }
            } catch (e: Exception) { Glog.i("Lyrics: $s didn't answer (${e.javaClass.simpleName})") }
            finally { finished += s; done.countDown() }
        }
        val t0 = System.currentTimeMillis()
        fun over(s: String) = s in finished || s !in sources
        while (!done.await(120, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            val waited = System.currentTimeMillis() - t0
            val best = synchronized(found) { found.maxOfOrNull { rank(it) } } ?: 0
            if (waited > 9_000) break
            if (best >= 60 && (over("lrclib") || waited > 2_500)) break
            if (best >= 50 && (over("netease") || waited > 4_000)) break
            if (best >= 25 && over("lrclib") && over("netease")) break
        }
        return pick(synchronized(found) { found.toList() })
    }

    /** How good an answer is: the higher the better (kept apart for tests). */
    fun rank(f: Found): Int = when {
        f.source == "netease" && f.kind == 'Y' -> 60
        f.source == "lrclib" && f.kind == 'S' -> 50
        f.source == "netease" && f.kind == 'S' -> 40
        f.source == "kugou" && f.kind == 'K' -> 30
        f.source == "kugou" && f.kind == 'S' -> 25
        f.source == "lrclib" -> 15
        else -> 10
    }

    /** How much of [text]'s letters are not Latin ones (0..1): Arabic, Cyrillic, Korean… */
    fun otherWriting(text: String): Float {
        var letters = 0; var other = 0
        for (ch in text) if (Character.isLetter(ch)) { letters++; if (ch.code > 0x24F) other++ }
        return if (letters == 0) 0f else other.toFloat() / letters
    }
    /** An answer's words alone (no stamps), enough of them to tell their writing. (Android's regex refuses a bare closing brace that Java's lets through: escaped.) */
    private fun wordsOf(f: Found) = f.text.replace(Regex("""\[[^\]]*\]|\(\d+,\d+,\d+\)|<\d+,\d+,\d+>|\{.*\}"""), " ").take(1500)

    /**
     * The best of the answers (kept apart for tests): by [rank]; but when the best is in Latin letters and another
     * timed answer is in another writing, that one (the song's own writing beats its spelling in Latin letters).
     */
    fun pick(all: List<Found>): Found? {
        val sorted = all.sortedByDescending { rank(it) }
        val best = sorted.firstOrNull() ?: return null
        if (best.kind != 'P' && otherWriting(wordsOf(best)) < 0.3f)
            sorted.firstOrNull { it.kind != 'P' && otherWriting(wordsOf(it)) > 0.5f }?.let { return it }
        return best
    }

    // ---- LRCLIB
    private fun lrclib(names: List<Pair<String, String>>, secs: Long): List<Found> {
        fun pick(o: JSONObject): Found? {
            val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
            if (synced.isNotBlank()) return Found("lrclib", 'S', synced)
            val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics")
            return if (plain.isNotBlank()) Found("lrclib", 'P', plain) else null
        }
        for ((t, a) in names) {
            // The exact song first (name, artist and length together), then the closest of a search.
            if (a.isNotBlank() && secs > 0) {
                val (code, body) = get("https://lrclib.net/api/get?track_name=${enc(t)}&artist_name=${enc(a)}&duration=$secs")
                if (code == 200) pick(JSONObject(body))?.let { return listOf(it) }
            }
            val (code, body) = get("https://lrclib.net/api/search?track_name=${enc(t)}" + if (a.isNotBlank()) "&artist_name=${enc(a)}" else "")
            if (code != 200) continue
            val arr = JSONArray(body)
            val all = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            // (Timed words before plain ones; among them the nearest in length, and never one far off: another
            // recording of the song would run ahead or behind.)
            fun near(o: JSONObject) = if (secs > 0) kotlin.math.abs(o.optDouble("duration", 0.0) - secs) else 0.0
            val timed = all.filter { !it.isNull("syncedLyrics") && it.optString("syncedLyrics").isNotBlank() }.sortedBy { near(it) }
            timed.firstOrNull { secs <= 0 || near(it) <= 4.0 }?.let { o -> pick(o)?.let { return listOf(it) } }
            all.firstOrNull { !it.isNull("plainLyrics") && it.optString("plainLyrics").isNotBlank() && (secs <= 0 || near(it) <= 8.0) }?.let { o -> pick(o)?.let { return listOf(it) } }
        }
        return emptyList()
    }

    /** Letters and digits only, lower case: how two spellings of a name are compared. */
    fun nameKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    /** [got] (a source's song) is the song asked for: the same name (one inside the other), and the artist when both are known. */
    fun sameSong(title: String, artist: String, gotTitle: String, gotArtists: List<String>): Boolean {
        val a = nameKey(title); val b = nameKey(gotTitle)
        if (a.isEmpty() || b.isEmpty()) return false
        // (One name inside the other only when it is most of it: "Time" is not "Summertime Sadness".)
        val names = a == b || (a.length >= 4 && b.length >= 4 && (a in b || b in a) && minOf(a.length, b.length) * 2 >= maxOf(a.length, b.length))
        if (!names) return false
        val want = nameKey(artist)
        if (want.isEmpty() || gotArtists.isEmpty()) return true
        return gotArtists.any { g -> nameKey(g).let { it.isNotEmpty() && (it in want || want in it) } }
    }
    /** The names worth asking the slower sources with: without the bare video title when a tidier one exists; two at most. */
    private fun tidy(names: List<Pair<String, String>>) = (if (names.size > 1) names.drop(1) else names).take(2)

    // ---- NetEase Cloud Music: a search, then the song's lyrics (yrc = each word's moment, lrc = each line's)
    private fun netease(names: List<Pair<String, String>>, secs: Long): List<Found> {
        val ref = "https://music.163.com/"
        for ((t, a) in tidy(names)) {
            val (code, body) = get("https://music.163.com/api/search/get?s=${enc("$t $a".trim())}&type=1&limit=6&offset=0", browser = true, referer = ref)
            if (code != 200) continue
            val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: continue
            val list = (0 until songs.length()).mapNotNull { songs.optJSONObject(it) }.filter { s ->
                val artists = s.optJSONArray("artists")?.let { ar -> (0 until ar.length()).mapNotNull { ar.optJSONObject(it)?.optString("name") } }.orEmpty()
                sameSong(t, a, s.optString("name"), artists) && (secs <= 0 || kotlin.math.abs(s.optLong("duration") / 1000 - secs) <= 3)
            }
            // (The nearest in length first; two tries: a song's entry may have no words.)
            for (s in list.sortedBy { kotlin.math.abs(it.optLong("duration") / 1000 - secs) }.take(2)) {
                val (c2, b2) = get("https://music.163.com/api/song/lyric/v1?id=${s.optLong("id")}&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0&ytv=0&yrv=0", browser = true, referer = ref)
                if (c2 != 200) continue
                val j = JSONObject(b2)
                val out = ArrayList<Found>()
                val lrc = j.optJSONObject("lrc")?.optString("lyric").orEmpty()
                // (Its lines go with its word-by-word file: they have the spaces that file leaves out, see respace.)
                j.optJSONObject("yrc")?.optString("lyric").orEmpty().takeIf { parseYrc(it).size >= 3 }?.let { out += Found("netease", 'Y', it.trimEnd() + "\n" + lrc) }
                lrc.takeIf { clean(parse(it)).count { l -> l.text.isNotBlank() } >= 3 }?.let { out += Found("netease", 'S', it) }
                if (out.isNotEmpty()) return out
            }
        }
        return emptyList()
    }

    // ---- KuGou: a search for the song, its lyrics by the song's hash, then the file (krc = each word's moment)
    private fun kugou(names: List<Pair<String, String>>, secs: Long): List<Found> {
        for ((t, a) in tidy(names)) {
            val (code, body) = get("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=6&showtype=0&keyword=${enc("$t $a".trim())}", browser = true)
            if (code != 200) continue
            val info = JSONObject(body).optJSONObject("data")?.optJSONArray("info") ?: continue
            val list = (0 until info.length()).mapNotNull { info.optJSONObject(it) }.filter { s ->
                sameSong(t, a, s.optString("songname"), s.optString("singername").split('、', ',', '&').map { it.trim() }) &&
                    (secs <= 0 || kotlin.math.abs(s.optLong("duration") - secs) <= 3)
            }
            for (s in list.sortedBy { kotlin.math.abs(it.optLong("duration") - secs) }.take(2)) {
                val (c2, b2) = get("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=${enc(s.optString("hash"))}", browser = true)
                if (c2 != 200) continue
                val cand = JSONObject(b2).optJSONArray("candidates")?.optJSONObject(0) ?: continue
                val base = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${enc(cand.optString("id"))}&accesskey=${enc(cand.optString("accesskey"))}&charset=utf8"
                val out = ArrayList<Found>()
                runCatching {
                    val (c3, b3) = get("$base&fmt=krc", browser = true)
                    if (c3 == 200) unKrc(java.util.Base64.getMimeDecoder().decode(JSONObject(b3).optString("content")))
                        ?.takeIf { parseKrc(it).size >= 3 }?.let { out += Found("kugou", 'K', it) }
                }
                if (out.isEmpty()) {
                    val (c3, b3) = get("$base&fmt=lrc", browser = true)
                    if (c3 == 200) String(java.util.Base64.getMimeDecoder().decode(JSONObject(b3).optString("content")), Charsets.UTF_8)
                        .takeIf { clean(parse(it)).count { l -> l.text.isNotBlank() } >= 3 }?.let { out += Found("kugou", 'S', it) }
                }
                if (out.isNotEmpty()) return out
            }
        }
        return emptyList()
    }
    private val KRC_KEY = intArrayOf(64, 71, 97, 119, 94, 50, 116, 71, 81, 54, 49, 45, 206, 210, 110, 105)
    /** KuGou's krc file opened: four letters ("krc1"), then the text packed (zlib) under a fixed 16-byte mask (kept apart for tests). */
    fun unKrc(raw: ByteArray): String? {
        if (raw.size < 8) return null
        val packed = ByteArray(raw.size - 4) { i -> (raw[i + 4].toInt() xor KRC_KEY[i % 16]).toByte() }
        return runCatching { java.util.zip.InflaterInputStream(packed.inputStream()).use { it.readBytes() }.toString(Charsets.UTF_8) }.getOrNull()
    }

    // ---- lyrics.ovh: plain words by artist and name
    private fun ovh(names: List<Pair<String, String>>): List<Found> {
        for ((t, a) in tidy(names)) {
            if (a.isBlank()) continue
            val (code, body) = get("https://api.lyrics.ovh/v1/${enc(a).replace("+", "%20")}/${enc(t).replace("+", "%20")}")
            if (code != 200) continue
            JSONObject(body).optString("lyrics").takeIf { it.lines().count { l -> l.isNotBlank() } >= 4 }?.let { return listOf(Found("ovh", 'P', it)) }
        }
        return emptyList()
    }

    /**
     * The names to ask with, the likeliest first (kept apart for tests): as they are; without what stands in
     * brackets ("(Official Video)", "[4K]", "(feat. X)"); and, for a video's title, split at its dash
     * ("Artist - Song").
     */
    fun candidates(title: String, artist: String): List<Pair<String, String>> {
        fun strip(s: String) = s.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "").replace(Regex("""\s+(feat\.?|ft\.?)\s+.*$""", RegexOption.IGNORE_CASE), "").trim()
        val out = LinkedHashSet<Pair<String, String>>()
        val t = title.trim(); val a = artist.trim().removeSuffix(" - Topic").trim()
        out += t to a
        out += strip(t) to a
        val dash = Regex("""\s+[-–—|]\s+""").split(strip(t), 2)
        if (dash.size == 2 && dash[0].isNotBlank() && dash[1].isNotBlank()) {
            out += dash[1] to dash[0]
            out += dash[0] to dash[1]
        }
        return out.filter { it.first.isNotBlank() }
    }

    /** LRC ("[mm:ss.xx] words", several stamps on a line allowed) into lines in order (kept apart for tests). */
    fun parse(lrc: String): List<Line> {
        val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]""")
        val out = ArrayList<Line>()
        for (raw in lrc.lineSequence()) {
            val stamps = stamp.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            // (Only stamps at the start count: "[00:12.00][00:45.10] words".)
            var end = 0
            val times = ArrayList<Long>()
            for (m in stamps) {
                if (raw.substring(end, m.range.first).isNotBlank()) break
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                times += m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + ms
                end = m.range.last + 1
            }
            val text = raw.substring(end).trim()
            times.forEach { out += Line(it, text) }
        }
        return out.sortedBy { it.at }
    }

    /** A line of words each with its moment, from its parts; a pause is added after it when the singing stops for 4 s or more. */
    private fun wordLine(out: ArrayList<Line>, at: Long, len: Long, words: List<Word>, prevEnd: LongArray) {
        val text = words.joinToString("") { it.text }.trim()
        if (text.isEmpty()) return
        // (The pause the line before left: these files have no empty lines for it.)
        out.lastOrNull()?.let { prev -> val end = prevEnd[0]; if (prev.text.isNotBlank() && end > 0 && at - end >= 4_000) out += Line(end, "") }
        out += Line(at, text, words)
        prevEnd[0] = at + len
    }

    /**
     * NetEase's word-by-word file leaves out the space between some words ("how" "to " "I'm" "going ": the player showed
     * "howto", "I'mgoing"), while the same song's plain lines have them. Each word line takes its spaces from the plain
     * line of the same letters near its time ([lines]: the song's LRC lines); a line with no such twin stays as it is.
     */
    fun respace(byWord: List<Line>, lines: List<Line>): List<Line> {
        if (lines.isEmpty()) return byWord
        fun letters(t: String) = t.filterNot { it.isWhitespace() }
        return byWord.map { l ->
            val words = l.words ?: return@map l
            val key = letters(l.text)
            val twin = lines.filter { kotlin.math.abs(it.at - l.at) <= 2_500 && letters(it.text) == key }.minByOrNull { kotlin.math.abs(it.at - l.at) }?.text?.trim()
            if (twin == null || twin == l.text) return@map l
            // Where each letter of the twin stands, so a word's end can be looked up by the letters counted so far.
            val place = twin.indices.filter { !twin[it].isWhitespace() }
            var n = 0
            // (A "word" that is only a space is left out: the word before it carries the space.)
            val fixed = words.mapNotNull { w ->
                val own = w.text.count { !it.isWhitespace() }
                if (own == 0) return@mapNotNull null
                n += own
                val after = place.getOrNull(n - 1)?.plus(1)
                Word(w.at, w.text.trim() + if (after != null && after < twin.length && twin[after].isWhitespace()) " " else "")
            }
            Line(l.at, twin, fixed)
        }
    }

    /** NetEase's word-by-word lines: "[start,length](start,length,0)word (start,length,0)word", times in ms from the song's start (kept apart for tests). */
    fun parseYrc(yrc: String): List<Line> {
        val head = Regex("""^\[(\d+),(\d+)\]""")
        val word = Regex("""\((\d+),(\d+),\d+\)([^(]*)""")
        val out = ArrayList<Line>(); val prevEnd = longArrayOf(0L)
        for (raw in yrc.lineSequence()) {
            val h = head.find(raw) ?: continue
            val words = word.findAll(raw, h.range.last + 1).map { Word(it.groupValues[1].toLong(), it.groupValues[3]) }.filter { it.text.isNotEmpty() }.toList()
            wordLine(out, h.groupValues[1].toLong(), h.groupValues[2].toLong(), words, prevEnd)
        }
        return out.sortedBy { it.at }
    }

    /** KuGou's word-by-word lines: "[start,length]<offset,length,0>word<offset,length,0>word", a word's offset from its line's start (kept apart for tests). */
    fun parseKrc(krc: String): List<Line> {
        val head = Regex("""^\[(\d+),(\d+)\]""")
        val word = Regex("""<(\d+),(\d+),\d+>([^<]*)""")
        val out = ArrayList<Line>(); val prevEnd = longArrayOf(0L)
        for (raw in krc.lineSequence()) {
            val h = head.find(raw.trim()) ?: continue
            val at = h.groupValues[1].toLong()
            val words = word.findAll(raw.trim(), h.range.last + 1).map { Word(at + it.groupValues[1].toLong(), it.groupValues[3]) }.filter { it.text.isNotEmpty() }.toList()
            wordLine(out, at, h.groupValues[2].toLong(), words, prevEnd)
        }
        return out.sortedBy { it.at }
    }

    private val CREDIT = Regex("""^\s*(作词|作曲|编曲|制作人?|监制|混音|母带|出品|发行|录音|和声|吉他|贝斯|鼓|键盘|词|曲|lyrics by|lyricist|composer|composed by|written by|produced by|producer|arranged by|arranger)\s*[:：]""", RegexOption.IGNORE_CASE)
    private fun cjk(s: String) = s.any { it.code in 0x4E00..0x9FFF }
    /**
     * The lines without what isn't the song's words (kept apart for tests): credits ("Lyrics by: …", "作词 : …"),
     * Chinese lines in a song that is otherwise not Chinese (the same credits, and a title's translation), and a
     * first line, at the song's very start, that is its "Name - Artist".
     */
    fun clean(lines: List<Line>): List<Line> {
        val worded = lines.filter { it.text.isNotBlank() }
        val mostlyOther = worded.isNotEmpty() && worded.count { cjk(it.text) } * 10 < worded.size * 3
        val firstWorded = worded.firstOrNull()
        return lines.filter { l ->
            l.text.isBlank() || !(CREDIT.containsMatchIn(l.text) || '：' in l.text || (mostlyOther && cjk(l.text)) ||
                (l === firstWorded && l.at <= 1_500 && worded.size > 3 && Regex("""\S\s+[-–—]\s+\S""").containsMatchIn(l.text)))
        }
    }

    /** Plain words spread evenly over the song (from 8 % to 96 % of it): no line has its own moment. */
    fun spread(plain: String, durationMs: Long): List<Line> {
        val lines = plain.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val d = if (durationMs > 20_000) durationMs else 200_000L
        val from = d * 8 / 100; val span = d * 88 / 100
        return lines.mapIndexed { i, s -> Line(from + span * i / lines.size, s) }
    }

    /**
     * Where the song is among [lines] at [posMs] (kept apart for tests): the line being sung (-1 before the first),
     * and how far through it, 0..1. A line lasts until the next one starts, 9 s at most (a long note, then a
     * silence the words don't mark).
     */
    fun at(lines: List<Line>, posMs: Long): Pair<Int, Float> {
        if (lines.isEmpty() || posMs < lines[0].at) return -1 to 0f
        var lo = 0; var hi = lines.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (lines[mid].at <= posMs) lo = mid else hi = mid - 1 }
        val start = lines[lo].at
        val next = lines.getOrNull(lo + 1)?.at ?: (start + 6_000)
        val len = (next - start).coerceIn(600, 9_000)
        return lo to ((posMs - start).toFloat() / len).coerceIn(0f, 1f)
    }

    /**
     * How many of [words] are sung at [frac] of their line, each taking time by its length (kept apart for tests);
     * the last word is reached a little before the line ends. For a line whose words have no moments of their own.
     */
    fun wordsSung(words: List<String>, frac: Float): Int {
        if (words.isEmpty()) return 0
        val total = words.sumOf { it.length + 1 }.toFloat()
        var acc = 0f
        val f = (frac / 0.88f).coerceIn(0f, 1f)
        words.forEachIndexed { i, w -> if (f * total < acc + 0.01f) return i; acc += w.length + 1 }
        return words.size
    }
}
