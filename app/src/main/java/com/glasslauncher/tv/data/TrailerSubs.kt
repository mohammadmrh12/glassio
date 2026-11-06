package com.glasslauncher.tv.data

/**
 * A trailer's subtitles, loaded and timed by Glassio (not by the player): in the language asked for when YouTube
 * has it or will translate it, otherwise in English (most trailers only have automatic English captions, and
 * YouTube often refuses to translate them), so they show instead of nothing.
 */
object TrailerSubs {
    data class Line(val start: Long, val end: Long, val text: String)
    data class Loaded(val lang: String, val lines: List<Line>)

    private fun get(url: String): String? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5_000; c.readTimeout = 8_000
        c.setRequestProperty("User-Agent", TrailerSource.UA)
        if (c.responseCode != 200) { Glog.w("Trailer subtitles: ${c.responseCode}"); null }
        else c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun code(s: TrailerSource.Sub) = s.lang.lowercase().substringBefore('-')

    /** Blocking. Null when there are none at all. */
    fun load(subs: List<TrailerSource.Sub>, lang: String): Loaded? {
        if (subs.isEmpty()) return null
        val l = lang.lowercase().substringBefore('-')
        val same = subs.filter { code(it) == l }
        (same.firstOrNull { !it.auto } ?: same.firstOrNull())?.let { s -> get(s.url)?.let { parse(it, s.mime) }?.takeIf { it.isNotEmpty() }?.let { return Loaded(l, it) } }
        val src = subs.firstOrNull { !it.auto && code(it) == "en" } ?: subs.firstOrNull { code(it) == "en" } ?: subs.first()
        // YouTube's own translation of them, when it allows it.
        if (l != code(src) && src.url.contains("youtube.com/api/timedtext"))
            get(src.url + "&tlang=$l")?.let { parse(it, src.mime) }?.takeIf { it.isNotEmpty() }?.let { return Loaded(l, it) }
        val orig = get(src.url)?.let { parse(it, src.mime) }?.takeIf { it.isNotEmpty() } ?: return null
        // YouTube wouldn't translate them: Google Translate does (the English ones only when that fails too).
        if (l != code(src)) translate(orig, code(src), l)?.let { return Loaded(l, it) }
        return Loaded(code(src), orig)
    }

    /** The lines in [to], each on its own (Google Translate, in batches), or null when it can't. */
    private fun translate(lines: List<Line>, from: String, to: String): List<Line>? = runCatching {
        val texts = lines.map { it.text.replace('\n', ' ') }
        val out = ArrayList<String>(texts.size)
        texts.chunked(40).forEach { batch ->
            val c = java.net.URL("https://translate.googleapis.com/translate_a/t?client=gtx&sl=$from&tl=$to").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 5_000; c.readTimeout = 8_000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("User-Agent", TrailerSource.UA)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            c.outputStream.use { o -> o.write(batch.joinToString("&") { "q=" + java.net.URLEncoder.encode(it, "UTF-8") }.toByteArray()) }
            check(c.responseCode == 200) { "translate ${c.responseCode}" }
            val a = org.json.JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            check(a.length() == batch.size) { "translate: ${a.length()} for ${batch.size}" }
            for (i in 0 until a.length()) out += when (val v = a.get(i)) { is org.json.JSONArray -> v.optString(0); else -> v.toString() }
        }
        lines.mapIndexed { i, ln -> ln.copy(text = out[i].ifBlank { ln.text }) }
    }.onFailure { Glog.w("Trailer subtitles: no translation (${it.message})") }.getOrNull()

    fun parse(body: String, mime: String): List<Line> =
        if (mime.contains("vtt") || body.startsWith("WEBVTT")) vtt(body) else ttml(body)

    private fun clock(t: String): Long? {
        val s = t.trim()
        if (s.endsWith("ms")) return s.dropLast(2).toDoubleOrNull()?.toLong()
        if (s.endsWith("s")) return s.dropLast(1).toDoubleOrNull()?.let { (it * 1000).toLong() }
        val p = s.replace(',', '.').split(':')
        return runCatching {
            when (p.size) {
                3 -> ((p[0].toLong() * 3600 + p[1].toLong() * 60) * 1000 + (p[2].toDouble() * 1000).toLong())
                2 -> (p[0].toLong() * 60 * 1000 + (p[1].toDouble() * 1000).toLong())
                else -> (p[0].toDouble() * 1000).toLong()
            }
        }.getOrNull()
    }

    private fun clean(t: String) = t.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    private fun ttml(body: String): List<Line> {
        val re = Regex("<p\\b([^>]*)>(.*?)</p>", setOf(RegexOption.DOT_MATCHES_ALL))
        fun attr(a: String, n: String) = Regex("\\b$n=\"([^\"]+)\"").find(a)?.groupValues?.get(1)
        return re.findAll(body).mapNotNull { m ->
            val a = m.groupValues[1]
            val start = attr(a, "begin")?.let(::clock) ?: return@mapNotNull null
            val end = attr(a, "end")?.let(::clock) ?: attr(a, "dur")?.let(::clock)?.let { start + it } ?: return@mapNotNull null
            val text = clean(m.groupValues[2])
            if (text.isBlank()) null else Line(start, end, text)
        }.toList()
    }

    private fun vtt(body: String): List<Line> {
        val out = mutableListOf<Line>()
        body.replace("\r", "").split("\n\n").forEach { block ->
            val lines = block.lines()
            val i = lines.indexOfFirst { "-->" in it }
            if (i < 0) return@forEach
            val (a, b) = lines[i].split("-->").map { it.trim().substringBefore(' ') }
            val s = clock(a) ?: return@forEach; val e = clock(b) ?: return@forEach
            val text = clean(lines.drop(i + 1).joinToString("\n"))
            if (text.isNotBlank()) out += Line(s, e, text)
        }
        return out
    }

    /** What shows at [ms] (lines that overlap are shown together, without repeats). */
    fun at(lines: List<Line>, ms: Long): String =
        lines.filter { ms in it.start until it.end }.map { it.text }.distinct().joinToString("\n")
}
