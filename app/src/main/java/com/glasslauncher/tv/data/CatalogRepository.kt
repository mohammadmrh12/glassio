package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale

data class CatalogRef(val addon: String, val addonName: String, val type: String, val id: String, val name: String) {
    val key: String get() = "$addon|$type|$id"
}

data class HeroItem(
    val key: String, val id: String, val type: String, val name: String,
    val poster: String, val background: String, val logo: String,
    val overview: String, val rating: String, val year: String,
    val genres: List<String>, val platform: String,
    /** 0..1 for Continue Watching items, -1 otherwise. */
    val progress: Float = -1f,
    /** e.g. "S1 · E3" for an episode in progress. */
    val sub: String = "",
    /** Extra backdrops of this title (TMDB), shown one after another like the Apple TV app. */
    val gallery: List<String> = emptyList(),
    /** The add-on's own picture (before TMDB) and TMDB's highest-resolution backdrop. */
    val addonArt: String = "",
    val bestArt: String = "",
)

/**
 * Nuvio add-on catalogs, the same way Nuvio TV Relay reads them: manifests -> selected catalogs ->
 * metas, merged round-robin into one endless hero list. The shared pref keys ("addons", "selected",
 * "manual_addons") deliberately match the relay so a relay export imports one-to-one.
 */
class CatalogRepository(private val ctx: Context, private val settings: Settings, private val http: Http) {
    private val sp = ctx.getSharedPreferences("glass_catalogs", Context.MODE_PRIVATE)
    private val tmdb = Tmdb(http)
    private val tmdbLock = Mutex()
    private val loadLock = Mutex()

    val catalogs = MutableStateFlow<List<CatalogRef>>(emptyList())
    val selectedKeys = MutableStateFlow(sp.getStringSet("selected", emptySet()) ?: emptySet())
    val items = MutableStateFlow<List<HeroItem>>(emptyList())
    val addonList = MutableStateFlow(sp.getStringSet("addons", emptySet()) ?: emptySet())
    val status = MutableStateFlow("")
    private val enriched = MutableStateFlow<Map<String, HeroItem>>(emptyMap())
    val enrichedItems: StateFlow<Map<String, HeroItem>> = enriched

    val addons: Set<String> get() = sp.getStringSet("addons", emptySet()) ?: emptySet()
    private val manual: Set<String> get() = sp.getStringSet("manual_addons", emptySet()) ?: emptySet()

    private fun saveAddons(set: Set<String>) {
        sp.edit().putStringSet("addons", set).apply(); addonList.value = set
    }
    /** Add-ons read from the Nuvio account (kept apart from the TV's own list, so turning it off removes only them). */
    val accountAddons = MutableStateFlow(sp.getStringSet("account_addons", emptySet()) ?: emptySet())
    /** Reads the account's add-ons; set once the Library (which holds the account) exists. */
    @Volatile var account: (() -> List<String>?)? = null

    /** One add-on, however its link is written: host without case, path without the trailing slash. */
    fun addonKey(u: String): String = normalize(u).let { n ->
        runCatching { val x = java.net.URI(n); x.host.orEmpty().lowercase() + (if (x.port > 0) ":${x.port}" else "") + x.rawPath.orEmpty().trimEnd('/') + (x.rawQuery?.let { "?$it" } ?: "") }
            .getOrDefault(n.lowercase())
    }
    /** The TV's add-ons, then the account's ones it doesn't already have (each add-on once). */
    fun allAddons(): List<String> {
        val out = LinkedHashMap<String, String>()
        for (u in addons.sorted()) out.putIfAbsent(addonKey(u), u)
        if ((settings.catalogsNuvio || settings.catalogsStremio)) for (u in accountAddons.value) out.putIfAbsent(addonKey(u), u)
        return out.values.toList()
    }
    /** Account add-ons that aren't also on the TV's own list. */
    fun accountOnly(): List<String> {
        if (!(settings.catalogsNuvio || settings.catalogsStremio)) return emptyList()
        val mine = addons.map { addonKey(it) }.toSet()
        return accountAddons.value.filter { addonKey(it) !in mine }.distinctBy { addonKey(it) }
    }
    /** The link already in use for this add-on (so its selected catalogs keep their keys), else [u] itself. */
    private fun canonical(u: String): String { val k = addonKey(u); return (addons + accountAddons.value).firstOrNull { addonKey(it) == k } ?: normalize(u) }
    private fun saveAccount(list: List<String>) {
        val set = list.map { normalize(it) }.toCollection(LinkedHashSet())
        sp.edit().putStringSet("account_addons", set).apply(); accountAddons.value = set
    }

    private fun saveSelected(set: Set<String>) {
        sp.edit().putStringSet("selected", set).apply(); selectedKeys.value = set
    }

    fun normalize(raw: String): String {
        var u = raw.trim().replace("stremio://", "https://")
        if (!u.endsWith("manifest.json")) u = u.trimEnd('/') + "/manifest.json"
        return u
    }

    fun start(scope: CoroutineScope) {
        importSeed()
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { refresh() }
                delay((if (settings.surprise) settings.surpriseMin else 30) * 60 * 1000L)
            }
        }
    }

    /** One-shot import file dropped by the user or by tooling: {"addons":[],"selected":[],"tmdb":""}. */
    private fun importSeed() {
        val f = File(ctx.getExternalFilesDir(null), "seed.json")
        if (!f.exists()) return
        runCatching {
            val j = JSONObject(f.readText())
            j.optJSONArray("addons")?.let { a ->
                val set = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
                if (set.isNotEmpty()) saveAddons(set + addons)
            }
            j.optJSONArray("selected")?.let { a ->
                val set = (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet()
                if (set.isNotEmpty()) saveSelected(set)
            }
            j.optString("tmdb").takeIf { it.isNotBlank() }?.let { settings.tmdbToken = it }
        }
        f.delete()
    }

    private fun parseCatalogs(url: String, fallbackName: String, manifest: JSONObject): List<CatalogRef> {
        val arr = manifest.optJSONArray("catalogs") ?: JSONArray()
        val name = manifest.optString("name").ifBlank { fallbackName }
        return (0 until arr.length()).mapNotNull { i ->
            val c = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = c.optString("type"); val id = c.optString("id")
            if (type.isBlank() || id.isBlank()) null
            else CatalogRef(url, name, type, id, c.optString("name").ifBlank { id })
        }
    }

    suspend fun refresh() = loadLock.withLock {
        withContext(Dispatchers.IO) {
            status.value = "Loading catalogs…"
            if ((settings.catalogsNuvio || settings.catalogsStremio)) account?.invoke()?.let { saveAccount(it) }
            val urls = allAddons()
            val refs = java.util.Collections.synchronizedList(mutableListOf<CatalogRef>())
            val gate = Semaphore(4)
            coroutineScope {
                urls.map { u ->
                    async {
                        gate.withPermit {
                            runCatching {
                                val m = http.json(u, 24L * 3600 * 1000)
                                refs += parseCatalogs(u, java.net.URI(u).host ?: "Add-on", m)
                            }.onFailure { android.util.Log.w("GlassCatalogs", "manifest failed for ${java.net.URI(u).host}: ${it.javaClass.simpleName} ${it.message}") }
                        }
                    }
                }.awaitAll()
            }
            val all = refs.sortedWith(compareBy({ it.addonName.lowercase() }, { it.name.lowercase() }))
            catalogs.value = all
            var sel = selectedKeys.value
            if (sel.isEmpty() && all.isNotEmpty()) {
                sel = all.filter { it.type == "movie" || it.type == "series" }.take(8).map { it.key }.toSet()
                saveSelected(sel)
            }
            val chosen = all.filter { it.key in sel }
            if (chosen.isEmpty()) {
                items.value = emptyList()
                status.value = if (urls.isEmpty()) "No add-ons yet" else "No catalogs selected"
                return@withContext
            }
            val per = settings.perCatalog
            val results = java.util.concurrent.ConcurrentHashMap<String, List<HeroItem>>()
            fun merge(shuffle: Boolean): List<HeroItem> {
                val merged = mutableListOf<HeroItem>()
                val seen = HashSet<String>()
                val iters = chosen.mapNotNull { results[it.key] }.map { it.iterator() }
                val cap = settings.maxItems
                loop@ while (iters.any { it.hasNext() }) {
                    for (it in iters) {
                        if (!it.hasNext()) continue
                        val item = it.next()
                        if (seen.add(item.key)) merged += item
                        if (cap > 0 && merged.size >= cap) break@loop
                    }
                }
                return if (shuffle) merged.shuffled() else merged
            }
            coroutineScope {
                chosen.map { c ->
                    async {
                        gate.withPermit {
                            results[c.key] = runCatching { fetchCatalog(c, per) }.getOrDefault(emptyList())
                            // Show the first results early instead of waiting for every catalog.
                            if (results.size <= 3 || results.size % 8 == 0) items.value = merge(false)
                            status.value = "Loading ${results.size}/${chosen.size} catalogs…"
                        }
                    }
                }.awaitAll()
            }
            val merged = merge(settings.shuffle || settings.surprise)
            items.value = merged
            status.value = "${merged.size} titles from ${chosen.size} catalogs"
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun fetchCatalog(c: CatalogRef, limit: Int): List<HeroItem> {
        val base = c.addon.removeSuffix("/manifest.json").trimEnd('/')
        val out = mutableListOf<HeroItem>()
        // Surprise me: start further down the catalog now and then (a different mix each time).
        val start = if (settings.surprise) listOf(0, 0, 100, 200).random() else 0
        var skip = start
        while (out.size < limit && skip < 1000) {
            val url = "$base/catalog/${enc(c.type)}/${enc(c.id)}.json?skip=$skip&limit=100"
            val metas = runCatching { http.json(url, if (settings.surprise) 5 * 60 * 1000L else 15 * 60 * 1000L).optJSONArray("metas") }.getOrNull()
            if ((metas == null || metas.length() == 0) && skip == start && start > 0) { skip = 0; continue }
            metas ?: break
            if (metas.length() == 0) break
            for (i in 0 until metas.length()) parseMeta(metas.optJSONObject(i) ?: continue, c.type)?.let(out::add)
            skip += metas.length()
            if (metas.length() < 20) break
        }
        return out.take(limit)
    }

    private fun first(m: JSONObject, vararg keys: String) =
        keys.map { m.optString(it).trim() }.firstOrNull { it.isNotBlank() && !it.equals("null", true) }.orEmpty()
    private fun httpUrl(m: JSONObject, vararg keys: String) =
        keys.map { m.optString(it).trim() }.firstOrNull { it.startsWith("http") }.orEmpty()
    private fun big(u: String) = Regex("(/t/p/)(w45|w92|w154|w185|w300|w342|w500|w780|w1280)(/)")
        .replace(u) { it.groupValues[1] + "original" + it.groupValues[3] }
        .replace(Regex("(metahub\\.space/(?:background|logo|poster))/(?:small|medium)/"), "$1/large/")

    private fun parseMeta(m: JSONObject, catType: String): HeroItem? {
        val id = first(m, "id", "_id", "imdb_id", "imdbId")
        val name = first(m, "name", "title", "original_name", "original_title")
        if (id.isBlank() || name.isBlank()) return null
        val type = m.optString("type", catType).ifBlank { catType }
        val poster = httpUrl(m, "poster", "poster_url", "posterUrl")
        val bg = httpUrl(m, "background", "backdrop", "backdropUrl").ifBlank { poster }
        val logo = httpUrl(m, "logo", "logo_url", "logoUrl")
        val rating = listOf("imdbRating", "imdb_rating", "vote_average", "rating", "score").firstNotNullOfOrNull { k ->
            m.opt(k)?.toString()?.toDoubleOrNull()?.takeIf { it in 0.1..10.0 }?.let { String.format(Locale.US, "%.1f", it) }
        }.orEmpty()
        val year = listOf("year", "releaseInfo", "released", "release_date", "first_air_date")
            .firstNotNullOfOrNull { k -> Regex("(19|20)\\d{2}").find(m.optString(k))?.value }.orEmpty()
        val genres = mutableListOf<String>()
        (m.optJSONArray("genres") ?: m.optJSONArray("genre"))?.let { a ->
            for (i in 0 until a.length()) {
                val g = a.opt(i); val n = (if (g is JSONObject) g.optString("name") else g?.toString()).orEmpty().trim()
                if (n.isNotBlank() && genres.size < 3) genres += n
            }
        }
        return HeroItem(
            key = "$type:$id", id = id, type = type, name = name,
            poster = big(poster), background = big(bg), logo = big(logo),
            overview = first(m, "overview", "description", "plot", "synopsis", "summary"),
            rating = rating, year = year, genres = genres, platform = "",
        )
    }

    fun resolve(item: HeroItem): HeroItem = enriched.value[item.key] ?: item

    /** Fetches TMDB details for [item] in the background; no-op without a token. */
    suspend fun enrich(item: HeroItem) {
        val token = settings.tmdbToken
        if (token.isBlank() || enriched.value.containsKey(item.key)) return
        withContext(Dispatchers.IO) {
            tmdbLock.withLock {
                if (enriched.value.containsKey(item.key)) return@withLock
                val r = runCatching { tmdb.enrich(item, token, settings.descLang) }.getOrNull()
                enriched.value = enriched.value + (item.key to (r ?: item))
            }
        }
    }

    fun clearEnrichment() { enriched.value = emptyMap() }

    // ---- Management
    fun toggleCatalog(key: String) {
        val s = selectedKeys.value.toMutableSet()
        if (!s.remove(key)) s.add(key)
        saveSelected(s)
    }
    fun selectAll(on: Boolean) = saveSelected(if (on) catalogs.value.map { it.key }.toSet() else emptySet())

    /** Reads the add-ons installed in Nuvio through its local "Manage from phone" server. */
    suspend fun connectLocal(): String = withContext(Dispatchers.IO) {
        var arr: JSONArray? = null
        for (port in 8080..8089) {
            try { arr = http.liveArray("http://127.0.0.1:$port/api/addons"); break } catch (_: Exception) { }
        }
        val a = arr ?: return@withContext "Nuvio add-on server not found. Open Nuvio > Add-ons > Manage from phone (QR) and keep it open."
        val urls = linkedSetOf<String>()
        for (i in 0 until a.length()) {
            val u = a.optJSONObject(i)?.optString("url")?.trim().orEmpty()
            if (u.isNotBlank()) urls += canonical(u)
        }
        if (urls.isEmpty()) return@withContext "Nuvio returned no add-ons."
        saveAddons(urls + manual)
        saveSelected(emptySet())
        refresh()
        "Imported ${urls.size} add-ons, ${catalogs.value.size} catalogs."
    }

    suspend fun addAddon(raw: String): String = withContext(Dispatchers.IO) {
        val url = canonical(raw)
        val m = try { http.json(url, 0) } catch (e: Exception) { throw IllegalStateException("Could not read that add-on (${e.message})") }
        val refs = parseCatalogs(url, java.net.URI(url).host ?: "Add-on", m)
        sp.edit().putStringSet("manual_addons", manual + url).apply()
        saveAddons(addons + url)
        saveSelected(selectedKeys.value + refs.filter { it.type == "movie" || it.type == "series" }.map { it.key })
        refresh()
        "Added ${m.optString("name").ifBlank { url }} (${refs.size} catalogs)"
    }

    suspend fun removeAddon(url: String): String = withContext(Dispatchers.IO) {
        sp.edit().putStringSet("manual_addons", manual - url).apply()
        saveAddons(addons - url)
        saveSelected(selectedKeys.value.filterNot { it.startsWith("$url|") }.toSet())
        refresh()
        "Removed"
    }

    /**
     * Turns the account's add-ons on or off. Their catalogs are listed but not put in the carousel by themselves
     * (you pick them, like any other catalog); turning it off also takes theirs out of the carousel.
     */
    suspend fun useAccount(on: Boolean, which: String = "nuvio"): String = withContext(Dispatchers.IO) {
        val name = if (which == "stremio") "Stremio" else "Nuvio"
        fun set(v: Boolean) { if (which == "stremio") settings.catalogsStremio = v else settings.catalogsNuvio = v }
        if (!on) {
            val before = accountOnly().toSet()
            set(false)
            // The add-ons that only came from that account (and their catalogs) leave the carousel.
            val now = runCatching { account?.invoke() }.getOrNull()
            if (now != null) saveAccount(now)
            val gone = before - accountOnly().toSet()
            if (gone.isNotEmpty()) saveSelected(selectedKeys.value.filterNot { k -> gone.any { k.startsWith("$it|") } }.toSet())
            refresh(); return@withContext "$name account add-ons off"
        }
        set(true)
        val list = account?.invoke() ?: run { set(false); return@withContext "Couldn't read add-ons from your $name account. Connect it first (Connections)." }
        saveAccount(list)
        refresh()
        val only = accountOnly().size
        val same = list.size - only
        "${list.size} add-ons from your accounts: $only new" + (if (same > 0) ", $same already here (not added twice)" else "") + ". Pick their catalogs below."
    }

    fun addonName(url: String) = catalogs.value.firstOrNull { it.addon == url }?.addonName ?: (runCatching { java.net.URI(url).host }.getOrNull() ?: url)
}
