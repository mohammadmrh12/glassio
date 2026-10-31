package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Keeps the Library, likes and Continue Watching the same on every connected account (Connections → Sync):
 *  - Library: a title saved anywhere (Glassio, Nuvio, Trakt watchlist, Simkl Plan to watch) is added to the others.
 *  - Likes: Glassio's likes go to Trakt favorites and Simkl (a 10/10 rating); Trakt favorites come back as likes.
 *  - Continue Watching: what you paused in any app is put on Trakt and Simkl, so every app that reads them has it.
 * Each title is sent to an account once (remembered), so nothing is sent again and again.
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

    /** Every 30 minutes while it's on (and at once when it's switched on). */
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
        val r = runCatching { now() }.getOrElse { "Sync failed: ${it.message}" }
        done(r)
    }

    private fun sent(account: String, what: String): Boolean = sp.getBoolean("$account|$what", false)
    private fun markSent(account: String, what: String) { sp.edit().putBoolean("$account|$what", true).apply() }

    @Synchronized fun now(): String {
        if (running) return status.value
        running = true
        try {
            status.value = "Syncing…"
            val trakt = cw.trakt; val simkl = cw.simkl; val nuvio = library.nuvio
            // Fresh lists from every account first.
            runCatching { library.refreshNuvio() }; runCatching { library.refreshWatchlist() }; runCatching { library.refreshSimkl() }
            var added = 0; var likes = 0; var paused = 0

            // Library: everything saved anywhere, by title.
            val all = (library.saved.value + library.nuvioList.value + library.watchlist.value + library.simklList.value)
                .filter { it.id.startsWith("tt") }.distinctBy { it.key }
            val inNuvio = library.nuvioList.value.map { it.key }.toSet()
            val inTrakt = library.watchlist.value.map { it.key }.toSet()
            val inSimkl = library.simklList.value.map { it.key }.toSet()
            for (it in all) {
                val type = it.type.lowercase()
                if (nuvio.connected && it.key !in inNuvio && !sent("nuvio", it.key)) {
                    if (nuvio.setSaved(true, it.id, type, it.name, it.poster, it.background)) { added++; markSent("nuvio", it.key) }
                }
                if (trakt.connected && it.key !in inTrakt && !sent("trakt", it.key)) {
                    if (trakt.setWatchlist(true, type, it.id)) { added++; markSent("trakt", it.key) }
                }
                if (simkl?.connected == true && it.key !in inSimkl && !sent("simkl", it.key)) {
                    if (simkl.setPlanToWatch(true, it.id, type)) { added++; markSent("simkl", it.key) }
                }
            }

            // Likes: Trakt favorites come back as likes, then every like goes to Trakt and Simkl.
            if (trakt.connected) runCatching { trakt.favorites() }.getOrNull()?.forEach { f ->
                val key = "${if (f.type == "movie") "movie" else "series"}:${f.imdb}"
                if (!settings.liked(key)) settings.setLiked(key, true)
                markSent("trakt-like", key)
            }
            for (key in settings.likedKeys()) {
                val imdb = key.substringAfter(':'); if (!imdb.startsWith("tt")) continue
                val type = key.substringBefore(':')
                if (trakt.connected && !sent("trakt-like", key) && trakt.setFavorite(true, type, imdb)) { likes++; markSent("trakt-like", key) }
                if (simkl?.connected == true && !sent("simkl-like", key) && simkl.setLiked(true, imdb, type)) { likes++; markSent("simkl-like", key) }
            }

            // Continue Watching: what was paused in another app goes to Trakt and Simkl (at its own position).
            for (e in cw.entries.value) {
                if (e.percent !in 2..90) continue
                val what = "${e.imdb}:${e.season}:${e.episode}:${e.percent / 5}"
                if (trakt.connected && e.app != "Trakt" && !sent("trakt-cw", what) && trakt.pausedAt(e.imdb, e.season, e.episode, e.percent)) { paused++; markSent("trakt-cw", what) }
                if (simkl?.connected == true && e.app != "Simkl" && !sent("simkl-cw", what) && simkl.pausedAt(e.imdb, e.season, e.episode, e.percent)) { paused++; markSent("simkl-cw", what) }
            }
            if (added > 0) { runCatching { library.refreshNuvio() }; runCatching { library.refreshWatchlist() }; runCatching { library.refreshSimkl() } }
            val accounts = listOfNotNull("Nuvio".takeIf { nuvio.connected }, "Trakt".takeIf { trakt.connected }, "Simkl".takeIf { simkl?.connected == true })
            val msg = if (accounts.isEmpty()) "No accounts to sync: sign in under Connections"
                else "Synced with ${accounts.joinToString(", ")} · $added saved, $likes likes, $paused in progress sent"
            Glog.i("Sync: $msg")
            status.value = msg
            return msg
        } finally { running = false }
    }
}
