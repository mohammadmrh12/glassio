package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Library: titles saved with the + button on the carousel (kept on the TV), and, when a Trakt account
 * is connected, its watchlist. Saving can also add the title to the Trakt watchlist.
 */
class Library(
    ctx: Context,
    private val settings: Settings,
    private val http: Http,
    private val trakt: Trakt,
    private val scope: CoroutineScope,
    private val simkl: Simkl? = null,
) {
    private val sp = ctx.getSharedPreferences("glass_library", Context.MODE_PRIVATE)
    val saved = MutableStateFlow(load())
    val watchlist = MutableStateFlow<List<HeroItem>>(emptyList())
    /** Simkl's "Plan to watch". */
    val simklList = MutableStateFlow<List<HeroItem>>(emptyList())
    /** The Nuvio account's Library (shared by Nuvio Legacy, the official app and the forks that sync with it). */
    val nuvio = NuvioAccount(ctx)
    val nuvioList = MutableStateFlow<List<HeroItem>>(emptyList())
    val nuvioStatus = MutableStateFlow("")
    /** Nuvio sign-in in progress: the page to open (QR), null when idle. */
    val nuvioUrl = MutableStateFlow<String?>(null)
    @Volatile private var nuvioJob: kotlinx.coroutines.Job? = null

    fun connectNuvio(say: (String) -> Unit) {
        if (nuvioJob?.isActive == true) return
        nuvioJob = scope.launch(Dispatchers.IO) {
            val c = runCatching { nuvio.startLogin() }.getOrElse { say("Nuvio: ${it.message}"); return@launch }
            nuvioUrl.value = c.url
            val ok = runCatching { nuvio.waitForApproval(c) { nuvioJob?.isCancelled == true } }.getOrDefault(false)
            nuvioUrl.value = null
            say(if (ok) "Nuvio account connected" + (nuvio.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") else "Nuvio: ${nuvio.lastError}. Try again")
            if (ok) { settings.libraryNuvio = true; refreshNuvio() }
        }
    }

    /** Reads the Nuvio account's Library. Blocking. */
    fun refreshNuvio() {
        if (!settings.libraryNuvio || !nuvio.connected) { nuvioList.value = emptyList(); return }
        val list = runCatching { nuvio.library() }.getOrElse { Glog.w("Nuvio library", it); nuvioStatus.value = "Nuvio: ${it.message}"; return }
        nuvioStatus.value = ""
        nuvioList.value = list.filter { it.id.isNotBlank() }.take(300).map { n ->
            val type = if (n.type == "movie") "movie" else "series"
            val m = if (n.poster.isBlank() && n.id.startsWith("tt")) meta(type, n.id) else null
            m ?: HeroItem(
                key = "$type:${n.id}", id = n.id, type = type, name = n.name, poster = n.poster,
                background = n.background.ifBlank { n.poster }, logo = "", overview = n.description, rating = "", year = n.year,
                genres = emptyList(), platform = "",
            )
        }
    }
    /** "" or why the watchlist could not be read. */
    val status = MutableStateFlow("")

    fun has(key: String) = saved.value.any { it.key == key }

    /** Saves or unsaves [item]; returns true when it is now in the Library. */
    fun toggle(item: HeroItem): Boolean {
        val now = !has(item.key)
        val l = if (now) listOf(item.copy(progress = -1f, sub = "", gallery = emptyList())) + saved.value.filterNot { it.key == item.key }
        else saved.value.filterNot { it.key == item.key }
        saved.value = l; save(l)
        if (settings.libraryToNuvio && nuvio.connected && item.id.isNotBlank()) scope.launch(Dispatchers.IO) {
            if (nuvio.setSaved(now, item.id, item.type.lowercase(), item.name, item.poster, item.background)) refreshNuvio()
        }
        if (settings.libraryToTrakt && trakt.connected && item.id.startsWith("tt")) scope.launch(Dispatchers.IO) {
            if (trakt.setWatchlist(now, item.type.lowercase(), item.id)) refreshWatchlist()
        }
        val sk = simkl
        if (settings.libraryToSimkl && sk?.connected == true && item.id.startsWith("tt")) scope.launch(Dispatchers.IO) {
            if (sk.setPlanToWatch(now, item.id, item.type.lowercase())) refreshSimkl()
        }
        return now
    }

    fun remove(key: String) { val l = saved.value.filterNot { it.key == key }; saved.value = l; save(l) }

    fun removeFromNuvio(item: HeroItem, done: (Boolean) -> Unit) {
        nuvioList.value = nuvioList.value.filterNot { it.key == item.key }
        scope.launch(Dispatchers.IO) { val ok = nuvio.setSaved(false, item.id, item.type, item.name, item.poster, item.background); if (!ok) refreshNuvio(); done(ok) }
    }

    fun removeFromWatchlist(item: HeroItem) {
        watchlist.value = watchlist.value.filterNot { it.key == item.key }
        scope.launch(Dispatchers.IO) { trakt.setWatchlist(false, item.type, item.id) }
    }

    fun removeFromSimkl(item: HeroItem) {
        simklList.value = simklList.value.filterNot { it.key == item.key }
        scope.launch(Dispatchers.IO) { simkl?.setPlanToWatch(false, item.id, item.type) }
    }

    /** Reads Simkl's "Plan to watch" (posters from Cinemeta). Blocking. */
    fun refreshSimkl() {
        val sk = simkl
        if (!settings.librarySimkl || sk?.connected != true) { simklList.value = emptyList(); return }
        val list = sk.planToWatch() ?: return
        simklList.value = list.take(120).mapNotNull { meta(it.type, it.imdb) }
    }

    /** Reads the Trakt watchlist and turns it into posters (Cinemeta, cached a week). Blocking. */
    fun refreshWatchlist() {
        if (!settings.libraryTrakt || !trakt.connected) { watchlist.value = emptyList(); return }
        val list = runCatching { trakt.watchlist() }.getOrElse { Glog.w("Trakt watchlist", it); status.value = "Trakt: ${it.message}"; return }
        status.value = ""
        watchlist.value = list.take(120).mapNotNull { meta(it.type, it.imdb) }
    }

    private fun meta(type: String, imdb: String): HeroItem? = runCatching {
        val m = http.json("https://v3-cinemeta.strem.io/meta/$type/$imdb.json", 7L * 24 * 3600 * 1000).optJSONObject("meta") ?: return null
        fun str(k: String) = m.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        HeroItem(
            key = "$type:$imdb", id = imdb, type = type, name = str("name"), poster = str("poster"),
            background = str("background").ifBlank { str("poster") }, logo = str("logo"), overview = str("description"),
            rating = "", year = Regex("(19|20)\\d{2}").find(str("releaseInfo"))?.value.orEmpty(), genres = emptyList(), platform = "",
        )
    }.getOrNull()

    private fun load(): List<HeroItem> = runCatching {
        val a = JSONArray(sp.getString("items", null) ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            HeroItem(o.getString("key"), o.getString("id"), o.optString("type"), o.optString("name"), o.optString("poster"),
                o.optString("background"), o.optString("logo"), o.optString("overview"), o.optString("rating"), o.optString("year"),
                emptyList(), "")
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<HeroItem>) {
        val a = JSONArray()
        l.take(500).forEach {
            a.put(JSONObject().put("key", it.key).put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster)
                .put("background", it.background).put("logo", it.logo).put("overview", it.overview).put("rating", it.rating).put("year", it.year))
        }
        sp.edit().putString("items", a.toString()).apply()
    }
}
