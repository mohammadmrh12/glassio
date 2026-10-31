package com.glasslauncher.tv.data

import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/**
 * Optional TMDB enrichment (same idea as Nuvio TV Relay): title logo, full-size backdrop, localized
 * synopsis, rating, year, genres and the streaming platform. Only runs when a read token is set.
 */
class Tmdb(private val http: Http) {
    companion object {
        /** TMDB's v3 API Key (32 hex characters): not what Glassio takes. */
        fun looksLikeApiKey(t: String) = Regex("^[0-9a-fA-F]{32}$").matches(t.trim())

        /**
         * Checks the API Read Access Token with TMDB itself: true = works, false = TMDB refused it,
         * null = couldn't ask (offline).
         */
        fun verify(token: String): Boolean? {
            if (token.isBlank()) return false
            if (looksLikeApiKey(token)) return false
            return runCatching {
                val c = java.net.URL("https://api.themoviedb.org/3/authentication").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
                c.setRequestProperty("Accept", "application/json")
                try {
                    when (c.responseCode) {
                        in 200..299 -> JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optBoolean("success", true)
                        401, 403 -> false
                        else -> null
                    }
                } finally { c.disconnect() }
            }.getOrNull()
        }
    }

    private val ttl = 30L * 24 * 3600 * 1000

    private fun get(url: String, token: String) = http.json(url, ttl, token)
    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]"), " ").replace(Regex("\\s+"), " ").trim()

    fun enrich(item: HeroItem, token: String, lang: String): HeroItem? {
        if (token.isBlank()) return null
        val isTv = item.type.equals("series", true) || item.type.equals("tv", true)
        val kind = if (isTv) "tv" else "movie"
        val tmdbLang = TMDB_LANGS[lang] ?: "en-US"
        val imdb = item.id.takeIf { it.matches(Regex("tt\\d{7,10}")) }
        var tmdbId = 0
        runCatching {
            if (imdb != null) {
                val r = get("https://api.themoviedb.org/3/find/$imdb?external_source=imdb_id", token)
                tmdbId = r.optJSONArray(if (isTv) "tv_results" else "movie_results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            }
            if (tmdbId == 0) {
                val q = URLEncoder.encode(item.name, "UTF-8")
                val y = if (item.year.isNotBlank()) (if (isTv) "&first_air_date_year=${item.year}" else "&year=${item.year}") else ""
                val r = get("https://api.themoviedb.org/3/search/$kind?query=$q$y&include_adult=false", token)
                val results = r.optJSONArray("results")
                if (results != null) for (i in 0 until results.length()) {
                    val c = results.optJSONObject(i) ?: continue
                    val titles = listOf("name", "title", "original_name", "original_title").map { norm(c.optString(it)) }
                    if (norm(item.name) in titles) { tmdbId = c.optInt("id", 0); break }
                }
            }
        }
        if (tmdbId == 0) return null
        val d = runCatching {
            get("https://api.themoviedb.org/3/$kind/$tmdbId?language=$tmdbLang&append_to_response=images&include_image_language=en,null", token)
        }.getOrNull() ?: return null

        var overview = d.optString("overview").trim()
        if (overview.isBlank() && lang != "en") {
            overview = runCatching { get("https://api.themoviedb.org/3/$kind/$tmdbId?language=en-US", token).optString("overview").trim() }.getOrDefault("")
        }
        val backdrop = d.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/original$it" }.orEmpty()
        val poster = d.optString("poster_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/original$it" }.orEmpty()

        // English first, then a sensible size: the widest file was often a several-megabyte PNG (thousands of
        // pixels for a logo shown about 800 wide), so the title waited seconds for its logo every time.
        var logoPath: String? = null; var logoW = 0; var rank = Int.MAX_VALUE
        d.optJSONObject("images")?.optJSONArray("logos")?.let { logos ->
            for (i in 0 until logos.length()) {
                val l = logos.optJSONObject(i) ?: continue
                val p = l.optString("file_path"); if (!p.startsWith("/")) continue
                if (p.endsWith(".svg", true)) continue
                val lr = when (l.optString("iso_639_1")) { "en" -> 0; "null", "" -> 1; else -> 2 }
                val w = l.optInt("width", 0)
                // Best between 600 and 1600 wide; smaller ones look soft, bigger ones are slow.
                val fit = when { w in 600..1600 -> 0; w > 1600 -> (w - 1600) / 100; else -> (600 - w) / 50 + 5 }
                val r = lr * 1_000_000 + fit * 1000 - w.coerceAtMost(999) / 10
                if (r < rank) { rank = r; logoPath = p; logoW = w }
            }
        }
        // Textless backdrops first, best rated first: the Apple TV style gallery for this title.
        val gallery = d.optJSONObject("images")?.optJSONArray("backdrops")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("file_path").startsWith("/") && it.optInt("width") >= 1280 }
                .sortedWith(compareBy<JSONObject> { if (it.isNull("iso_639_1")) 0 else 1 }.thenByDescending { it.optDouble("vote_average") })
                .map { "https://image.tmdb.org/t/p/original" + it.optString("file_path") }
                .distinct().take(6)
        }.orEmpty()
        // Highest resolution: textless first, then the widest file.
        val bestArt = d.optJSONObject("images")?.optJSONArray("backdrops")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.filter { it.optString("file_path").startsWith("/") }
                .sortedWith(compareBy<JSONObject> { if (it.isNull("iso_639_1")) 0 else 1 }.thenBy { if (it.optInt("width") > 3840) 1 else 0 }.thenByDescending { it.optInt("width") })
                .firstOrNull()?.let { "https://image.tmdb.org/t/p/original" + it.optString("file_path") }
        }.orEmpty()
        val genresEn = if (lang != "en") runCatching { get("https://api.themoviedb.org/3/$kind/$tmdbId?language=en-US", token) }.getOrDefault(d) else d
        val genres = genresEn.optJSONArray("genres")?.let { g ->
            (0 until minOf(3, g.length())).mapNotNull { g.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty()
        val score = d.optDouble("vote_average", -1.0)
        val rating = if (score > 0.1) String.format(Locale.US, "%.1f", score) else ""
        val date = if (isTv) d.optString("first_air_date") else d.optString("release_date")
        val year = if (date.matches(Regex("(19|20)\\d{2}.*"))) date.take(4) else ""
        val platform = runCatching { provider(kind, tmdbId, token) }.getOrDefault("")

        return item.copy(
            background = backdrop.ifBlank { item.background },
            poster = poster.ifBlank { item.poster },
            logo = logoPath?.let { "https://image.tmdb.org/t/p/${if (logoW > 2000) "w500" else "original"}$it" } ?: item.logo,
            overview = overview.ifBlank { item.overview },
            rating = rating.ifBlank { item.rating },
            year = year.ifBlank { item.year },
            genres = genres.ifEmpty { item.genres },
            platform = platform.ifBlank { item.platform },
            gallery = (listOf(backdrop.ifBlank { item.background }) + gallery).filter { it.isNotBlank() }.distinct(),
            addonArt = item.addonArt.ifBlank { item.background },
            bestArt = bestArt,
        )
    }

    private fun provider(kind: String, id: Int, token: String): String {
        val us = get("https://api.themoviedb.org/3/$kind/$id/watch/providers", token).optJSONObject("results")?.optJSONObject("US") ?: return ""
        for (cat in listOf("flatrate", "free", "ads")) {
            val arr = us.optJSONArray(cat) ?: continue
            for (i in 0 until arr.length()) {
                val name = display(arr.optJSONObject(i)?.optString("provider_name").orEmpty())
                if (name.isNotBlank()) return name
            }
        }
        return ""
    }

    private fun display(raw: String): String {
        val n = raw.lowercase(Locale.ROOT).replace("+", " plus ").replace(Regex("[^a-z0-9]+"), " ").trim()
        return when {
            n.contains("apple tv") -> "Apple TV"
            n.contains("prime video") || n.startsWith("amazon prime") -> "Prime Video"
            n.contains("peacock") -> "Peacock"
            n.contains("paramount") -> "Paramount"
            n == "hbo" || n.contains("hbo max") -> "HBO"
            n == "max" || n.startsWith("max ") -> "Max"
            n.contains("disney") -> "Disney"
            n.contains("netflix") -> "Netflix"
            n.contains("hulu") -> "Hulu"
            n.contains("crunchyroll") -> "Crunchyroll"
            n.contains("youtube") -> "YouTube"
            else -> raw.trim().replace(Regex("(?i)\\s+(premium|plus|channel|channels|subscription)$"), "").trim()
        }
    }
}

/** Synopsis languages (Settings → Carousel → Synopsis language): setting value → TMDB language. */
val TMDB_LANGS = linkedMapOf(
    "en" to "en-US", "ar" to "ar-SA", "fr" to "fr-FR", "es" to "es-ES", "es-MX" to "es-MX", "de" to "de-DE",
    "it" to "it-IT", "pt" to "pt-PT", "pt-BR" to "pt-BR", "nl" to "nl-NL", "tr" to "tr-TR", "ru" to "ru-RU",
    "uk" to "uk-UA", "pl" to "pl-PL", "fa" to "fa-IR", "he" to "he-IL", "hi" to "hi-IN", "id" to "id-ID",
    "ja" to "ja-JP", "ko" to "ko-KR", "zh" to "zh-CN", "zh-TW" to "zh-TW", "sv" to "sv-SE", "el" to "el-GR",
)
val LANG_NAMES = mapOf(
    "en" to "English", "ar" to "Arabic", "fr" to "French", "es" to "Spanish (Spain)", "es-MX" to "Spanish (Latin America)",
    "de" to "German", "it" to "Italian", "pt" to "Portuguese", "pt-BR" to "Portuguese (Brazil)", "nl" to "Dutch",
    "tr" to "Turkish", "ru" to "Russian", "uk" to "Ukrainian", "pl" to "Polish", "fa" to "Persian", "he" to "Hebrew",
    "hi" to "Hindi", "id" to "Indonesian", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese (Simplified)",
    "zh-TW" to "Chinese (Traditional)", "sv" to "Swedish", "el" to "Greek",
)
