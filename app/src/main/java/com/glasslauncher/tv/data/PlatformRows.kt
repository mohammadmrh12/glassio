package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * The Library's rows per streaming service ("On Netflix", "On Apple TV" …): what is popular on each one in the
 * chosen country, from TMDB's discover with its watch-provider filter. Needs the TMDB token (Settings → Carousel).
 * Settings → Library → Platform rows chooses the services; "Installed apps" shows the ones whose app is on the TV.
 */
class PlatformRows(private val ctx: Context, private val settings: Settings, private val http: Http) {
    /**
     * [ids]: TMDB's usual watch-provider numbers for the service, used when its list for the country can't be read.
     * [words]: what the service is called in that list (a service has other numbers in some countries, and one per
     * kind of subscription: "Netflix", "Netflix Standard with Ads").
     */
    data class Service(val key: String, val name: String, val ids: List<Int>, val pkg: String, val words: List<String> = listOf(name.lowercase()))
    data class Row(val service: Service, val items: List<HeroItem>)

    companion object {
        val SERVICES = listOf(
            Service("netflix", "Netflix", listOf(8), PlatformLinks.NETFLIX),
            Service("apple", "Apple TV", listOf(350), PlatformLinks.APPLE, listOf("apple tv")),
            Service("prime", "Prime Video", listOf(9, 119), PlatformLinks.PRIME, listOf("amazon prime video", "prime video")),
            Service("disney", "Disney+", listOf(337), PlatformLinks.DISNEY, listOf("disney plus", "disney+")),
            Service("max", "Max", listOf(1899), "com.wbd.stream", listOf("max", "hbo max")),
            Service("hulu", "Hulu", listOf(15), "com.hulu.livingroomplus"),
            Service("paramount", "Paramount+", listOf(531), "com.cbs.ott", listOf("paramount plus", "paramount+")),
            Service("peacock", "Peacock", listOf(386), "com.peacocktv.peacockandroid"),
            Service("crunchyroll", "Crunchyroll", listOf(283), "com.crunchyroll.crunchyroid"),
            Service("shahid", "Shahid", listOf(1715), "net.mbc.shahidTV"),
            Service("osn", "OSN+", listOf(629), "com.osn.go", listOf("osn")),
        )
        const val AUTO = ""
        const val OFF = "off"

        /** The services to show: [chosen] is "" (the installed apps'), "off", or their keys with commas. */
        fun wanted(chosen: String, installed: (String) -> Boolean): List<Service> = when (chosen.trim()) {
            OFF -> emptyList()
            AUTO -> SERVICES.filter { installed(it.pkg) }
            else -> chosen.split(',').map { it.trim() }.let { keys -> SERVICES.filter { it.key in keys } }
        }

        /**
         * The service's numbers out of a country's provider list ([all]: number to name). A name must start with one of
         * the service's words, and a service sold inside another one ("Paramount+ Amazon Channel", "… Apple TV
         * Channel") is that other service's. None found: its usual numbers.
         */
        fun idsIn(sv: Service, all: Map<Int, String>): List<Int> {
            val found = all.filter { (_, raw) ->
                val n = raw.lowercase().trim()
                !n.contains("channel") && !n.contains("roku") && !n.endsWith(" store") && sv.words.any { w -> n == w || n.startsWith("$w ") }
            }.keys.sorted()
            return found.ifEmpty { sv.ids }
        }

        /** Films and series one after the other, each title once, [n] at most. */
        fun mix(movies: List<HeroItem>, series: List<HeroItem>, n: Int): List<HeroItem> {
            val out = ArrayList<HeroItem>()
            for (i in 0 until maxOf(movies.size, series.size)) {
                series.getOrNull(i)?.let { out += it }
                movies.getOrNull(i)?.let { out += it }
            }
            return out.distinctBy { it.key }.take(n)
        }
    }

    val rows = MutableStateFlow<List<Row>>(emptyList())
    @Volatile private var loadedFor = ""
    @Volatile private var loadedAt = 0L

    fun wanted(): List<Service> = wanted(settings.libraryPlatforms) { NuvioLaunch.installed(ctx, it) }

    private val day = 24L * 3600 * 1000

    /** TMDB's providers in [region] for [kind], number to name (kept a week). */
    private fun providers(kind: String, region: String, token: String): Map<Int, String> = runCatching {
        val a = http.json("https://api.themoviedb.org/3/watch/providers/$kind?watch_region=$region", 7 * day, token).optJSONArray("results")
        (0 until (a?.length() ?: 0)).mapNotNull { a?.optJSONObject(it) }.associate { it.optInt("provider_id") to it.optString("provider_name") }
    }.onFailure { Glog.w("Streaming services in $region", it) }.getOrDefault(emptyMap())

    /** One page of what is popular on [sv] in [region] ([kind]: movie / tv), with each title's IMDb id. */
    private fun list(sv: Service, kind: String, region: String, token: String, n: Int): List<HeroItem> {
        val ids = idsIn(sv, providers(kind, region, token))
        val url = "https://api.themoviedb.org/3/discover/$kind?include_adult=false&sort_by=popularity.desc&watch_region=$region" +
            "&with_watch_providers=${ids.joinToString("%7C")}&with_watch_monetization_types=flatrate%7Cfree%7Cads"
        val res = http.json(url, day, token).optJSONArray("results") ?: return emptyList()
        val out = ArrayList<HeroItem>()
        for (i in 0 until res.length()) {
            if (out.size >= n) break
            val o = res.optJSONObject(i) ?: continue
            val id = o.optInt("id", 0)
            val poster = o.optString("poster_path").takeIf { it.startsWith("/") } ?: continue
            if (id == 0) continue
            // The apps and Nuvio know a title by its IMDb id; TMDB gives it in a second, long-kept answer.
            val imdb = runCatching { http.json("https://api.themoviedb.org/3/$kind/$id/external_ids", 90 * day, token).optString("imdb_id") }
                .getOrDefault("").takeIf { it.startsWith("tt") } ?: continue
            val type = if (kind == "tv") "series" else "movie"
            val date = o.optString(if (kind == "tv") "first_air_date" else "release_date")
            val score = o.optDouble("vote_average", 0.0)
            out += HeroItem(
                key = "$type:$imdb", id = imdb, type = type, name = o.optString(if (kind == "tv") "name" else "title"),
                poster = "https://image.tmdb.org/t/p/w500$poster",
                background = o.optString("backdrop_path").takeIf { it.startsWith("/") }?.let { "https://image.tmdb.org/t/p/original$it" }.orEmpty(),
                logo = "", overview = o.optString("overview"), rating = if (score > 0.1) String.format(java.util.Locale.US, "%.1f", score) else "",
                year = date.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(), genres = emptyList(), platform = sv.name,
            )
        }
        return out
    }

    /** Reads the rows again when what they depend on changed, or after six hours. Blocking; failures leave the rows as they are. */
    fun refresh() {
        val token = settings.tmdbToken
        val services = if (token.isBlank()) emptyList() else wanted()
        if (services.isEmpty()) { rows.value = emptyList(); loadedFor = ""; return }
        val region = Region.of(settings)
        val n = settings.libraryColumns * settings.libraryPlatformRows
        val want = listOf(services.map { it.key }, region, n, token.length).toString()
        if (want == loadedFor && System.currentTimeMillis() - loadedAt < 6 * 3600 * 1000) return
        val out = ArrayList<Row>()
        var failed = false
        for (sv in services) {
            val half = n / 2 + 2
            val movies = runCatching { list(sv, "movie", region, token, half) }.onFailure { failed = true; Glog.w("Platform row ${sv.name} (films)", it) }.getOrDefault(emptyList())
            val series = runCatching { list(sv, "tv", region, token, half) }.onFailure { failed = true; Glog.w("Platform row ${sv.name} (series)", it) }.getOrDefault(emptyList())
            // Whole rows only: a last row with two posters in it looks unfinished.
            val items = mix(movies, series, n).let { l -> l.take(l.size - l.size % settings.libraryColumns) }
            if (items.isNotEmpty()) out += Row(sv, items)
            Glog.i("Platform row ${sv.name} in $region: ${items.size} titles (${movies.size} films, ${series.size} series read)")
            // Shown as they come: the first service doesn't wait for the last.
            if (out.isNotEmpty()) rows.value = out.toList()
        }
        if (out.isNotEmpty() || !failed) rows.value = out
        if (!failed) { loadedFor = want; loadedAt = System.currentTimeMillis() }
    }
}
