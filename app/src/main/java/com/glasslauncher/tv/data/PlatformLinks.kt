package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * A title's own page inside a streaming app (Netflix, Apple TV, Prime Video, Disney+): the app's package and the
 * link it takes. Each app wants its own id for the title, which the catalogs don't carry (they give IMDb ids), so
 * it is looked up by IMDb id: in Wikidata (open data) for the app the title is listed on, then in JustWatch (its
 * site's own, undocumented search: [Settings.linkJustWatch] turns it off). What is found, and what isn't, is remembered.
 */
object PlatformLinks {
    data class Link(val pkg: String, val url: String)

    const val NETFLIX = "com.netflix.ninja"
    const val APPLE = "com.apple.atve.androidtv.appletv"
    const val PRIME = "com.amazon.amazonvideo.livingroom"
    const val DISNEY = "com.disney.disneyplus"

    /** The apps a link can be made for, in the order they are tried. */
    val APPS = listOf(NETFLIX, APPLE, PRIME, DISNEY)

    /** Found links are kept this long; "nothing found" is asked again sooner (a title can arrive on a platform). */
    private const val KEEP_MS = 30L * 24 * 3600 * 1000
    private const val RETRY_MS = 3L * 24 * 3600 * 1000
    /** After JustWatch failed it is left alone this long: Play opens the usual way at once instead of waiting again. */
    private const val REST_MS = 10L * 60 * 1000
    @Volatile private var restUntil = 0L

    // ---- Links from ids (no network) ----

    fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "title" }

    fun netflix(id: String) = Link(NETFLIX, "https://www.netflix.com/title/$id")
    /** [cc]: the Apple TV storefront (a country it has; "us" when not known). */
    fun apple(id: String, movie: Boolean, name: String, cc: String = "us") =
        Link(APPLE, "https://tv.apple.com/${cc.lowercase().takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } } ?: "us"}/${if (movie) "movie" else "show"}/${slug(name)}/$id")
    fun primeAsin(asin: String) = Link(PRIME, "https://watch.amazon.com/detail?asin=$asin")
    fun disney(id: String) = Link(DISNEY, "https://www.disneyplus.com/browse/entity-$id")

    /**
     * The title's page from one JustWatch offer: its web link ([web]) is that page for Netflix, Prime Video, Disney+
     * and Apple TV films. For an Apple TV show the web link is an episode, and the show's id is in the TV link ([tv]).
     * Null for anything else (a shop, a channel inside another app, an unknown site).
     */
    fun fromOffer(web: String, tv: String, name: String): Link? {
        val u = web.substringBefore('#')
        val host = u.substringAfter("://", "").substringBefore('/').lowercase()
        val path = u.substringAfter("://", "").substringAfter('/', "").substringBefore('?')
        return when {
            host == "www.netflix.com" -> Regex("^(?:title|watch)/(\\d+)").find(path)?.let { netflix(it.groupValues[1]) }
            host == "tv.apple.com" -> {
                val show = Regex("[?&]showId=(umc\\.cmc\\.[a-z0-9]+)").find(tv)?.groupValues?.get(1)
                val own = Regex("(umc\\.cmc\\.[a-z0-9]+)").find(path)?.groupValues?.get(1)
                // The storefront JustWatch found the title in (the same title has another page in another country).
                val cc = path.substringBefore('/')
                when {
                    show != null -> apple(show, false, name, cc)
                    own != null && path.contains("movie/") -> apple(own, true, name, cc)
                    own != null && path.contains("show/") -> apple(own, false, name, cc)
                    else -> null
                }
            }
            host == "watch.amazon.com" || host == "app.primevideo.com" ->
                Regex("[?&](gti|asin)=([A-Za-z0-9.\\-]+)").find(u)?.let { Link(PRIME, "https://watch.amazon.com/detail?${it.groupValues[1]}=${it.groupValues[2]}") }
            host == "www.disneyplus.com" -> Regex("entity-([a-z0-9\\-]+)").find(path)?.let { disney(it.groupValues[1]) }
            else -> null
        }
    }

    /** The one to use out of [found]: the app the title is listed on ([first]) if it has a link, else the first of [APPS] that has. */
    fun pick(found: List<Link>, usable: Set<String>, first: String?): Link? {
        val ok = found.filter { it.pkg in usable }
        return ok.firstOrNull { it.pkg == first } ?: APPS.firstNotNullOfOrNull { p -> ok.firstOrNull { it.pkg == p } }
    }

    fun intent(l: Link): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(l.url)).setPackage(l.pkg)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).apply {
            // Netflix on a TV only follows a link that says where it came from (30: another app).
            if (l.pkg == NETFLIX) putExtra("source", "30")
        }

    // ---- What is remembered ----

    private val mem = ConcurrentHashMap<String, String>()
    private val busy = ConcurrentHashMap.newKeySet<String>()
    private fun store(ctx: Context) = ctx.getSharedPreferences("links_cache", Context.MODE_PRIVATE)

    /** The installed apps that take links, as a short mark: a "nothing found" is only good for the same apps. */
    private fun usable(ctx: Context): Set<String> = APPS.filter { NuvioLaunch.installed(ctx, it) }.toSet()
    /** The streaming apps on this TV a title's page can be opened in (for the setting's list of apps). */
    fun installed(ctx: Context): Set<String> = usable(ctx)
    /** A link into an app the person left out (Settings → Play opens the streaming app → Apps) is no link. */
    private fun allowed(s: Settings, l: Link?): Link? = l?.takeIf { it.pkg !in s.appCatalogsPlayOff }
    private fun mark(apps: Set<String>) = APPS.joinToString("") { if (it in apps) "1" else "0" }

    /** Kept as "time|apps|package|link" (package and link empty: nothing found). */
    private fun read(ctx: Context, id: String): Pair<Boolean, Link?> {
        val v = mem[id] ?: store(ctx).getString(id, null)?.also { mem[id] = it } ?: return false to null
        val p = v.split('|', limit = 4)
        if (p.size < 4) return false to null
        val age = System.currentTimeMillis() - (p[0].toLongOrNull() ?: 0L)
        val link = if (p[2].isNotBlank()) Link(p[2], p[3]) else null
        if (link != null) return (age < KEEP_MS && NuvioLaunch.installed(ctx, link.pkg)).let { it to link.takeIf { _ -> it } }
        return (age < RETRY_MS && p[1] == mark(usable(ctx))) to null
    }

    private fun write(ctx: Context, id: String, apps: Set<String>, l: Link?) {
        val v = "${System.currentTimeMillis()}|${mark(apps)}|${l?.pkg.orEmpty()}|${l?.url.orEmpty()}"
        mem[id] = v
        store(ctx).edit().putString(id, v).apply()
    }

    /** What is remembered is per country: the same title is on other services elsewhere. */
    private fun cacheKey(s: Settings, id: String, prefer: String? = null) = Region.of(s) + ":" + id + (prefer?.let { "@$it" } ?: "")

    // ---- Looking up ----

    private fun request(url: String, body: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 5000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Glassio/1.0 (Android TV home screen)")
        try {
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** Wikidata's ids for the title: Netflix (P1874), Apple TV show / film (P9751 / P9586), Prime Video (P8055), Disney+ film / series (P7595 / P7596). */
    private fun wikidata(imdb: String, name: String): List<Link> {
        val q = "SELECT ?n ?as ?am ?p ?dm ?ds WHERE { ?i wdt:P345 \"$imdb\". OPTIONAL{?i wdt:P1874 ?n} OPTIONAL{?i wdt:P9751 ?as} " +
            "OPTIONAL{?i wdt:P9586 ?am} OPTIONAL{?i wdt:P8055 ?p} OPTIONAL{?i wdt:P7595 ?dm} OPTIONAL{?i wdt:P7596 ?ds} } LIMIT 1"
        val rows = JSONObject(request("https://query.wikidata.org/sparql?format=json&query=" + URLEncoder.encode(q, "UTF-8")))
            .optJSONObject("results")?.optJSONArray("bindings")
        val b = rows?.optJSONObject(0) ?: return emptyList()
        fun v(k: String) = b.optJSONObject(k)?.optString("value").orEmpty().trim()
        return listOfNotNull(
            v("n").takeIf { it.isNotBlank() && it.all(Char::isDigit) }?.let { netflix(it) },
            v("as").takeIf { it.startsWith("umc.") }?.let { apple(it, false, name) } ?: v("am").takeIf { it.startsWith("umc.") }?.let { apple(it, true, name) },
            v("p").takeIf { it.isNotBlank() }?.let { primeAsin(it) },
            (v("dm").ifBlank { v("ds") }).takeIf { it.isNotBlank() }?.let { disney(it) },
        )
    }

    /** JustWatch: the title found by name, matched by its IMDb id, and its offers to watch with a subscription or free. */
    private fun justWatch(imdb: String, name: String, movie: Boolean, country: String): List<Link> {
        if (name.isBlank()) return emptyList()
        val query = "query Q(\$q:String!,\$t:[ObjectType!]){ popularTitles(country: $country, first: 6, filter:{searchQuery:\$q, objectTypes:\$t}) { edges { node { " +
            "content(country: $country, language: en){ externalIds{ imdbId } } " +
            "offers(country: $country, platform: WEB){ monetizationType standardWebURL deeplinkURL(platform: ANDROID_TV) } } } } }"
        val body = JSONObject().put("query", query).put("variables", JSONObject().put("q", name)
            .put("t", org.json.JSONArray().put(if (movie) "MOVIE" else "SHOW"))).toString()
        val answer = JSONObject(request("https://apis.justwatch.com/graphql", body))
        // An answer that isn't the one asked for (its search changed, a country it doesn't know): a failure, not "nothing".
        val edges = answer.optJSONObject("data")?.optJSONObject("popularTitles")?.optJSONArray("edges")
            ?: error("unexpected answer: " + answer.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty().take(80))
        for (i in 0 until edges.length()) {
            val node = edges.optJSONObject(i)?.optJSONObject("node") ?: continue
            if (node.optJSONObject("content")?.optJSONObject("externalIds")?.optString("imdbId") != imdb) continue
            val offers = node.optJSONArray("offers") ?: return emptyList()
            val out = ArrayList<Link>()
            for (k in 0 until offers.length()) {
                val o = offers.optJSONObject(k) ?: continue
                if (o.optString("monetizationType") !in setOf("FLATRATE", "FREE", "ADS")) continue
                fromOffer(o.optString("standardWebURL"), o.optString("deeplinkURL"), name)?.let { l -> if (out.none { it.pkg == l.pkg }) out += l }
            }
            return out
        }
        return emptyList()
    }

    /**
     * The title's link in an installed app, asking the network when it isn't known yet. Not for the main thread.
     * Null: no installed app has it (or nothing answered: that is not remembered, so it is asked again).
     */
    fun find(ctx: Context, s: Settings, item: HeroItem, prefer: String? = null): Link? {
        val id = item.id.trim()
        if (!id.startsWith("tt")) return null
        val key = cacheKey(s, id, prefer)
        val (fresh, link) = read(ctx, key)
        if (fresh) return allowed(s, link)
        val apps = usable(ctx) - s.appCatalogsPlayOff.toSet()
        if (apps.isEmpty()) return null
        // ([prefer]: the app whose catalogs the title was picked from comes before the one it is listed on.)
        val first = prefer ?: NuvioLaunch.platformPackage(item.platform)
        val movie = item.type.equals("movie", true)
        var failed = false
        // Wikidata says an app has a page for the title, not that it can be watched there (Netflix keeps the number of
        // a title it no longer has; Apple TV has a page for other services' shows). So its link is only used for the
        // app the title is listed on. JustWatch's offers are what can be watched now, in any installed app.
        var got: Link? = null
        if (first != null && first in apps) got = runCatching { wikidata(id, item.name) }
            .onFailure { failed = true; Glog.w("Title link from Wikidata", it) }.getOrDefault(emptyList()).firstOrNull { it.pkg == first }
        if (got == null && s.linkJustWatch) {
            if (System.currentTimeMillis() < restUntil) failed = true
            else got = pick(runCatching { justWatch(id, item.name, movie, Region.of(s)) }
                .onFailure { failed = true; restUntil = System.currentTimeMillis() + REST_MS; Glog.w("Title link from JustWatch (left alone for 10 minutes)", it) }
                .getOrDefault(emptyList()), apps, first)
        }
        // (Picked from an app's own catalogs: that app or nothing; another app's page would be a surprise.)
        if (prefer != null && got?.pkg != prefer) got = null
        // An app must really take the link (a version that doesn't: its home page is opened as before).
        if (got != null && intent(got).resolveActivity(ctx.packageManager) == null) got = null
        if (got != null || !failed) write(ctx, key, apps, got)
        return got
    }

    private val offersMem = java.util.concurrent.ConcurrentHashMap<String, List<Link>>()

    /**
     * Every installed app that has the title now (JustWatch's offers in the country), one link each: the search's
     * "where to watch" list. Not for the main thread. Empty when nothing has it, JustWatch is off or didn't answer
     * (only a real answer is remembered, for this run of Glassio).
     */
    fun offers(ctx: Context, s: Settings, item: HeroItem): List<Link> {
        val id = item.id.trim()
        if (!id.startsWith("tt") || !s.linkJustWatch) return emptyList()
        val apps = usable(ctx)
        if (apps.isEmpty()) return emptyList()
        val key = Region.of(s) + ":" + id
        val all = offersMem[key] ?: run {
            if (System.currentTimeMillis() < restUntil) return emptyList()
            runCatching { justWatch(id, item.name, item.type.equals("movie", true), Region.of(s)) }
                .onFailure { restUntil = System.currentTimeMillis() + REST_MS; Glog.w("Places to watch from JustWatch (left alone for 10 minutes)", it) }
                .getOrNull()?.also { offersMem[key] = it } ?: return emptyList()
        }
        return all.filter { it.pkg in apps && intent(it).resolveActivity(ctx.packageManager) != null }
    }

    /** Looks a title up ahead of time (the slide on screen), so Play opens it at once. */
    fun warm(ctx: Context, s: Settings, item: HeroItem, prefer: String? = null) {
        val id = item.id.trim()
        if (!id.startsWith("tt") || read(ctx, cacheKey(s, id, prefer)).first || !busy.add(id + prefer)) return
        Bg.io("title-link") { try { find(ctx, s, item, prefer) } finally { busy.remove(id + prefer) } }
    }

    private val main = Handler(Looper.getMainLooper())

    private fun start(ctx: Context, l: Link): Boolean = runCatching { ctx.startActivity(intent(l)) }
        .onFailure { Glog.w("Open ${l.url} in ${l.pkg}", it) }.isSuccess

    /**
     * Opens the title's page in its streaming app, or runs [otherwise] (on the main thread) when there is no link.
     * A link already known opens at once; one that has to be asked for is waited for a short while only.
     */
    fun openOr(ctx: Context, s: Settings, item: HeroItem, prefer: String? = null, otherwise: () -> Unit) {
        val id = item.id.trim()
        val (fresh, found) = read(ctx, cacheKey(s, id, prefer))
        val link = allowed(s, found)
        if (fresh || !id.startsWith("tt")) {
            if (link == null || !start(ctx, link)) otherwise()
            return
        }
        var done = false
        val finish = { l: Link? -> if (!done) { done = true; if (l == null || !start(ctx, l)) otherwise() } }
        main.postDelayed({ finish(null) }, 2500)
        Bg.io("title-link") {
            val l = runCatching { find(ctx, s, item, prefer) }.getOrNull()
            main.post { finish(l) }
        }
    }

    fun forget(ctx: Context) { mem.clear(); restUntil = 0L; store(ctx).edit().clear().apply() }
}
