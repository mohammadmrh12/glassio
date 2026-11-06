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
        return get(src.url)?.let { parse(it, src.mime) }?.takeIf { it.isNotEmpty() }?.let { Loaded(code(src), it) }
    }

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
