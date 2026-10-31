package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the Library, likes and Continue Watching the same on every connected account (Connections → Sync).
 *
 * Library: each account's list is read; a title saved anywhere is added to the others. What each list held at the
 * last sync is remembered, so a title removed in one place (in Glassio, or in Nuvio / on Trakt / on Simkl
 * themselves) is removed from the others too, instead of being brought back. An account whose list can't be read
 * this time (offline, Simkl's daily limit) is left alone.
 * Likes: Trakt favorites come back as likes; every like goes to Trakt favorites and Simkl (a 10/10 rating).
 * Continue Watching: what you paused in any app is put on Trakt and Simkl.
 */
class Sync(
    ctx: Context,
    private val settings: Settings,
    private val library: Library,
    private val cw: ContinueWatching,
    private val scope: CoroutineScope,
) {
    private val sp = ctx.getSharedPreferences("glass_sync", Context.MODE_PRIVATE)
    val status = MutableStateFlow("")
    @Volatile private var running = false

    /** Every 30 minutes while it's on. */
    fun start() {
        scope.launch(Dispatchers.IO) {
            delay(20_000)
            while (true) {
                if (settings.syncAll) runCatching { now() }.onFailure { Glog.w("Sync", it) }
                delay(30 * 60_000L)
            }
        }
    }

    fun runSoon(done: (String) -> Unit = {}) = scope.launch(Dispatchers.IO) {
        val r = runCatching { now() }.getOrElse { Glog.w("Sync", it); "Sync failed: ${it.message}" }
        done(r)
    }

    private fun snap(acc: String): Set<String>? = sp.getString("snap_$acc", null)?.split('\n')?.filter { it.isNotBlank() }?.toSet()
    private fun keepSnap(acc: String, keys: Set<String>) { sp.edit().putString("snap_$acc", keys.joinToString("\n")).apply() }
    private fun cwSent(what: String) = sp.getBoolean("cw|$what", false)
    private fun markCw(what: String) { sp.edit().putBoolean("cw|$what", true).apply() }

    private fun keyOf(type: String, id: String) = "${if (type == "movie") "movie" else "series"}:$id"

    /** One account's Library as read now (keys null: couldn't be read, left alone this time). */
    private class Acc(val name: String, val keys: MutableSet<String>?, val add: (HeroItem) -> Boolean, val remove: (HeroItem) -> Boolean)

    @Synchronized fun now(): String {
        if (running) return status.value
        running = true
        try {
            status.value = "Syncing…"
            val trakt = cw.trakt; val simkl = cw.simkl; val nuvio = library.nuvio
            val notes = ArrayList<String>()
            // Titles with their names and pictures (needed to save them on Nuvio).
            val known = HashMap<String, HeroItem>()
            (library.saved.value + library.nuvioList.value + library.watchlist.value + library.simklList.value).forEach { known.putIfAbsent(it.key, it) }
            fun item(key: String): HeroItem = known[key] ?: HeroItem(key, key.substringAfter(':'), key.substringBefore(':'), "", "", "", "", "", "", "", emptyList(), "")

            val accounts = ArrayList<Acc>()
            accounts += Acc("Glassio", library.saved.value.map { it.key }.toMutableSet(),
                add = { i -> library.toggleLocal(i); true },
                remove = { i -> library.remove(i.key); true })
            if (nuvio.connected) {
                val l = runCatching { nuvio.library() }.getOrElse { notes += "Nuvio: couldn't read (${it.message})"; null }
                l?.forEach { s ->
                    val k = keyOf(s.type, s.id)
                    known.putIfAbsent(k, HeroItem(k, s.id, if (s.type == "movie") "movie" else "series", s.name, s.poster, s.background.ifBlank { s.poster }, "", s.description, "", s.year, emptyList(), ""))
                }
                accounts += Acc("Nuvio", l?.map { keyOf(it.type, it.id) }?.toMutableSet(),
                    add = { i -> nuvio.setSaved(true, i.id, i.type, i.name, i.poster, i.background) },
                    remove = { i -> nuvio.setSaved(false, i.id, i.type, i.name, i.poster, i.background) })
            }
            if (trakt.connected) {
                val l = runCatching { trakt.watchlist() }.getOrElse { notes += "Trakt: couldn't read (${it.message})"; null }
                accounts += Acc("Trakt", l?.map { keyOf(it.type, it.imdb) }?.toMutableSet(),
                    add = { i -> trakt.setWatchlist(true, i.type, i.id) },
                    remove = { i -> trakt.setWatchlist(false, i.type, i.id) })
            } else notes += "Trakt isn't signed in"
            if (simkl?.connected == true) {
                val l = if (simkl.limited) null else simkl.planToWatch()
                if (l == null) notes += "Simkl: " + (if (simkl.limited) "its daily request limit is used up, tries again tomorrow" else simkl.lastError.ifBlank { "couldn't read" })
                l?.forEach { s -> val k = keyOf(s.type, s.imdb); known.putIfAbsent(k, HeroItem(k, s.imdb, s.type, s.name, s.poster, s.poster, "", "", "", "", emptyList(), "")) }
                accounts += Acc("Simkl", l?.map { keyOf(it.type, it.imdb) }?.toMutableSet(),
                    add = { i -> simkl.setPlanToWatch(true, i.id, i.type) },
                    remove = { i -> simkl.setPlanToWatch(false, i.id, i.type) })
            }
            val read = accounts.filter { it.keys != null }

            // What was taken out somewhere since the last sync, and what was put in again (which cancels an old removal).
            val gone = sp.getString("gone", "").orEmpty().split('\n').filter { it.isNotBlank() }.toMutableSet()
            val removedNow = HashSet<String>()
            for (a in read) {
                val before = snap(a.name) ?: continue
                removedNow += before - a.keys!!
                gone -= (a.keys - before)
            }
            gone += removedNow
            var added = 0; var removed = 0; var failed = 0
            for (k in removedNow) for (a in read) if (k in a.keys!!) {
                if (a.name == "Simkl" && simkl?.limited == true) continue
                if (runCatching { a.remove(item(k)) }.getOrDefault(false)) { a.keys.remove(k); removed++ } else failed++
            }
            // Everything saved anywhere goes to every account that was read.
            val all = read.flatMap { it.keys!! }.toSet() - gone
            for (k in all) {
                if (!k.substringAfter(':').startsWith("tt")) continue
                for (a in read) if (k !in a.keys!!) {
                    // Glassio's own "Saved" isn't filled with every account's titles: the Library already shows
                    // each account's list in its own row.
                    if (a.name == "Glassio") continue
                    if (a.name == "Simkl" && simkl?.limited == true) continue
                    if (runCatching { a.add(item(k)) }.getOrDefault(false)) { a.keys.add(k); added++ } else failed++
                }
            }
            read.forEach { keepSnap(it.name, it.keys!!) }
            sp.edit().putString("gone", gone.toList().takeLast(2000).joinToString("\n")).apply()

            // Likes.
            var likes = 0
            if (trakt.connected) runCatching { trakt.favorites() }.getOrNull()?.forEach { f ->
                val key = keyOf(f.type, f.imdb)
                if (!settings.liked(key)) settings.setLiked(key, true)
                settings.setLikeSent("trakt", key, true)
            }
            for (key in settings.likedKeys()) {
                val imdb = key.substringAfter(':'); if (!imdb.startsWith("tt")) continue
                val type = key.substringBefore(':')
                if (trakt.connected && !settings.likeSent("trakt", key)) {
                    if (trakt.setFavorite(true, type, imdb)) { likes++; settings.setLikeSent("trakt", key, true) } else failed++
                }
                if (simkl?.connected == true && !simkl.limited && !settings.likeSent("simkl", key)) {
                    if (simkl.setLiked(true, imdb, type)) { likes++; settings.setLikeSent("simkl", key, true) } else failed++
                }
            }

            // Continue Watching: what was paused in another app goes to Trakt and Simkl.
            var paused = 0
            for (e in cw.entries.value) {
                if (e.percent !in 2..90) continue
                val what = "${e.imdb}:${e.season}:${e.episode}:${e.percent / 5}"
                if (trakt.connected && e.app != "Trakt" && !cwSent("trakt|$what")) {
                    if (trakt.pausedAt(e.imdb, e.season, e.episode, e.percent)) { paused++; markCw("trakt|$what") } else failed++
                }
                if (simkl?.connected == true && !simkl.limited && e.app != "Simkl" && !cwSent("simkl|$what")) {
                    if (simkl.pausedAt(e.imdb, e.season, e.episode, e.percent)) { paused++; markCw("simkl|$what") } else failed++
                }
            }

            if (added + removed > 0) { runCatching { library.refreshNuvio() }; runCatching { library.refreshWatchlist() }; runCatching { library.refreshSimkl() } }
            val with = read.filter { it.name != "Glassio" }.joinToString(", ") { it.name }
            val counts = if (added + removed + likes + paused == 0) "already the same everywhere"
                else listOfNotNull("$added added".takeIf { added > 0 }, "$removed removed".takeIf { removed > 0 },
                    "$likes likes sent".takeIf { likes > 0 }, "$paused in progress sent".takeIf { paused > 0 }).joinToString(", ")
            val msg = (if (with.isBlank()) "No account could be synced" else "Synced with $with: $counts") +
                (if (failed > 0) " · $failed couldn't be sent (tried again next time)" else "") +
                notes.joinToString("") { " · $it" }
            Glog.i("Sync: $msg")
            status.value = msg
            return msg
        } finally { running = false }
    }
}
