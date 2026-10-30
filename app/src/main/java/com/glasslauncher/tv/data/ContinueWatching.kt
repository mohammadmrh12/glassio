package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** One title being watched. season/episode are 0 for movies. */
data class CwEntry(val imdb: String, val season: Int, val episode: Int, val percent: Int, val at: Long, val app: String) {
    val type: String get() = if (season > 0 || episode > 0) "series" else "movie"
    val key: String get() = "$type:$imdb"
}

/**
 * Continue Watching for Nuvio Legacy.
 *
 * Google TV only lets privileged apps read other apps' Watch Next rows, so instead this reads what Nuvio
 * Legacy writes to its own log while you watch ("[atividade] inicio tt… T1E3 42%", "[visto] ponto gravado
 * … (63%)", "[player] saida"). The log is read through the TV's local adb shell (LocalShell), so Android's
 * "Allow access to all device logs?" prompt never appears; only the one-time "Allow USB debugging" does.
 * The list is kept on the TV, newest first; titles past 92% count as finished and leave the list.
 */
class ContinueWatching(
    private val ctx: Context,
    private val settings: Settings,
    private val http: Http,
    private val scope: CoroutineScope,
) {
    private val sp = ctx.getSharedPreferences("glass_cw", Context.MODE_PRIVATE)
    val entries = MutableStateFlow(load())
    val items = MutableStateFlow<List<HeroItem>>(emptyList())
    /** Key of the title you were watching last; bumped when you leave the player (carousel jumps to it). */
    val lastWatched = MutableStateFlow<String?>(null)
    val jumpRev = MutableStateFlow(0)

    private var current: Triple<String, Int, Int>? = null
    @Volatile private var started = false

    @Volatile private var access = sp.getBoolean("access", false)
    val trakt = Trakt(ctx)
    val stremio = Stremio(ctx)
    val simkl = Simkl(ctx)
    /** The Nuvio account (kept by the Library); set once it exists. */
    @Volatile var nuvio: NuvioAccount? = null
    private val syncedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Last result per account for Settings ("12 titles", or the error). */
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    @Volatile private var traktAt = 0L
    @Volatile private var woke = false
    /** Last Trakt sync result for Settings ("12 titles", or the error). */
    val traktStatus = MutableStateFlow("")
    /** Released to wake the poll loop early (Glass came back to the front). */
    private val wake = java.util.concurrent.Semaphore(0)

    /** True once the local adb shell has answered (USB debugging allowed for Glassio). */
    fun hasLogAccess(): Boolean = access

    fun start() {
        if (started) return
        started = true
        scope.launch(Dispatchers.IO) { rebuildItems() }
        thread(name = "glass-cw-log", isDaemon = true) {
            while (true) {
                if (settings.cwLegacy && settings.continueMode != "off") poll()
                // Trakt: every 3 minutes, or right away (at most every 45 s) when Glass comes back to the front.
                val now = System.currentTimeMillis()
                if (settings.cwTrakt && settings.continueMode != "off" && trakt.connected &&
                    now - traktAt > (if (woke) 45_000L else 180_000L)) { traktAt = now; syncTrakt() }
                // The other accounts, the same way. Each one's list replaces its own titles here, so a title
                // removed (or finished) in that app leaves Glassio too.
                fun due(k: String) = now - (syncedAt[k] ?: 0L) > (if (woke) 45_000L else 180_000L)
                if (settings.continueMode != "off") {
                    if (settings.cwStremio && stremio.connected && due("Stremio")) { syncedAt["Stremio"] = now; syncStremio() }
                    if (settings.cwSimkl && simkl.connected && due("Simkl")) { syncedAt["Simkl"] = now; syncSimkl() }
                    val n = nuvio
                    if (settings.cwNuvioAccount && n?.connected == true && due("Nuvio")) { syncedAt["Nuvio"] = now; syncNuvio(n) }
                }
                woke = false
                // Every 20 s, or right away when Glass comes back to the front (pollNow).
                runCatching { wake.tryAcquire(20, java.util.concurrent.TimeUnit.SECONDS) }
                wake.drainPermits()
            }
        }
    }

    /** Called when the home screen resumes, so leaving Nuvio shows up immediately. */
    fun pollNow() { woke = true; wake.release() }

    /** Trakt sign-in in progress: the line to show ("Go to … and enter CODE"), null when idle. */
    val traktCode = MutableStateFlow<String?>(null)
    @Volatile var traktUrl: String = "https://trakt.tv/activate"
    @Volatile private var signIn: kotlinx.coroutines.Job? = null

    /** Starts the device sign-in; it keeps going when you leave Settings. [say] shows a short message. */
    fun connectTrakt(say: (String) -> Unit) {
        if (signIn?.isActive == true) return
        signIn = scope.launch(Dispatchers.IO) {
            val dc = runCatching { trakt.deviceCode() }.getOrElse { say("Trakt: ${it.message}"); return@launch }
            traktUrl = dc.url
            traktCode.value = dc.userCode
            val ok = trakt.waitForApproval(dc) { signIn?.isCancelled == true }
            traktCode.value = null
            say(if (ok) "Trakt connected" + (trakt.user.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "") else "Trakt: ${trakt.lastError}. Try again")
            if (ok) { settings.cwTrakt = true; syncTraktNow() }
        }
    }

    /** Forces a Trakt sync on the next round (after signing in). */
    fun syncTraktNow() { traktAt = 0L; pollNow() }

    private fun traktIds(): JSONObject = runCatching { JSONObject(sp.getString("traktIds", null) ?: "{}") }.getOrDefault(JSONObject())

    /**
     * What you paused on Trakt joins the list. A title Nuvio Legacy saw more recently keeps Legacy's
     * progress; Trakt titles that are no longer paused there (finished or removed) leave the list.
     */
    private fun syncTrakt() {
        val paused = runCatching { trakt.playback() }.getOrElse { Glog.w("Trakt sync", it); traktStatus.value = "Sync failed: ${it.message}"; return }
        synchronized(this) {
            val ids = JSONObject()
            val list = entries.value.filterNot { it.app == "Trakt" }.toMutableList()
            for (p in paused) {
                if (p.percent >= 92) continue
                val e = CwEntry(p.imdb, p.season, p.episode, p.percent, p.at, "Trakt")
                val other = list.firstOrNull { it.imdb == p.imdb }
                if (other != null && other.at >= p.at) continue
                list.removeAll { it.imdb == p.imdb }
                list.add(e)
                ids.put(e.key, p.id)
            }
            val trimmed = list.sortedByDescending { it.at }.take(30)
            entries.value = trimmed
            save(trimmed)
            sp.edit().putString("traktIds", ids.toString()).apply()
        }
        traktStatus.value = "${paused.size} paused on Trakt"
        rebuildItems()
    }

    private class Remote(val e: CwEntry, val ref: String)

    private fun refs(app: String): JSONObject = runCatching { JSONObject(sp.getString("refs_$app", null) ?: "{}") }.getOrDefault(JSONObject())

    /** [app]'s list replaces its titles here; a title another source saw more recently keeps that one's place. */
    private fun mirror(app: String, remote: List<Remote>) {
        synchronized(this) {
            val ids = JSONObject()
            val list = entries.value.filterNot { it.app == app }.toMutableList()
            for (r in remote) {
                if (r.e.percent >= 92 || r.e.percent < 1) continue
                val other = list.firstOrNull { it.imdb == r.e.imdb }
                if (other != null && other.at >= r.e.at) continue
                list.removeAll { it.imdb == r.e.imdb }
                list.add(r.e)
                ids.put(r.e.key, r.ref)
            }
            val trimmed = list.sortedByDescending { it.at }.take(40)
            entries.value = trimmed; save(trimmed)
            sp.edit().putString("refs_$app", ids.toString()).apply()
        }
        status.value = status.value + (app to "${remote.size} titles")
        rebuildItems()
    }

    fun syncStremio() {
        val l = stremio.watching() ?: run { status.value = status.value + ("Stremio" to "Sync failed: ${stremio.lastError}"); return }
        mirror("Stremio", l.map { Remote(CwEntry(it.imdb, it.season, it.episode, it.percent, it.at, "Stremio"), it.raw.toString()) })
    }

    private fun syncSimkl() {
        val l = simkl.playback() ?: run { status.value = status.value + ("Simkl" to "Sync failed: ${simkl.lastError}"); return }
        mirror("Simkl", l.map { Remote(CwEntry(it.imdb, it.season, it.episode, it.percent, it.at, "Simkl"), it.id.toString()) })
    }

    private fun syncNuvio(n: NuvioAccount) {
        val l = n.progress() ?: run { status.value = status.value + ("Nuvio" to "Sync failed: ${n.lastError}"); return }
        mirror("Nuvio", l.map { Remote(CwEntry(it.imdb, it.season, it.episode, it.percent, it.at, "Nuvio"), it.key) })
        // Nuvio Legacy pushes its progress to the account: a title it shows in its log but that is no longer on the
        // account was removed (or finished) in Nuvio, so it leaves here too (a few minutes' grace for the push).
        val onAccount = l.map { it.imdb }.toSet()
        synchronized(this) {
            val now = System.currentTimeMillis()
            val kept = entries.value.filterNot { it.app == "Nuvio Legacy" && it.imdb !in onAccount && now - it.at > 5 * 60_000L }
            if (kept.size != entries.value.size) { entries.value = kept; save(kept) }
        }
        rebuildItems()
    }

    private val epochLine = Regex("""^(\d+)\.(\d+)\s+\d+\s+\d+\s+[A-Z]\s+nuvio\s*:\s(.*)$""")
    private val startRe = Regex("""\[atividade] inicio (tt\d+) T(\d+)E(\d+) (\d+)%""")
    private val pointRe = Regex("""\[visto] ponto gravado .*?\((\d+)%\)""")
    private val exitRe = Regex("""\[player] saida""")

    /** Reads Nuvio's log lines from the buffer; lines already handled (by time) are skipped. */
    private fun poll() {
        val out = LocalShell.run(ctx, "logcat -d -v epoch -s nuvio:I").getOrNull()
        if ((out != null) != access) { access = out != null; sp.edit().putBoolean("access", access).apply() }
        out ?: return
        for (line in out.lineSequence()) {
            val m = epochLine.find(line.trim()) ?: continue
            val at = m.groupValues[1].toLong() * 1000L + m.groupValues[2].padEnd(3, '0').take(3).toLong()
            if (at <= sp.getLong("lastAt", 0L)) continue
            sp.edit().putLong("lastAt", at).apply()
            handle(at, m.groupValues[3])
        }
    }

    private fun handle(at: Long, msg: String) {
        startRe.find(msg)?.let {
            val id = it.groupValues[1]; val s = it.groupValues[2].toInt(); val e = it.groupValues[3].toInt()
            current = Triple(id, s, e)
            upsert(CwEntry(id, s, e, it.groupValues[4].toInt(), at, "Nuvio Legacy"))
            return
        }
        pointRe.find(msg)?.let {
            val c = current ?: return
            upsert(CwEntry(c.first, c.second, c.third, it.groupValues[1].toInt(), at, "Nuvio Legacy"))
            return
        }
        if (exitRe.containsMatchIn(msg)) {
            current?.let { c ->
                val key = CwEntry(c.first, c.second, c.third, 0, at, "").key
                if (entries.value.any { it.key == key }) { lastWatched.value = key; jumpRev.value = jumpRev.value + 1 }
            }
        }
    }

    @Synchronized
    private fun upsert(e: CwEntry) {
        // One entry per title: a newer episode of the same series replaces the older one.
        val list = entries.value.filterNot { it.imdb == e.imdb }.toMutableList()
        if (e.percent < 92) list.add(0, e)
        val trimmed = list.sortedByDescending { it.at }.take(30)
        entries.value = trimmed
        save(trimmed)
        lastWatched.value = e.key
        scope.launch(Dispatchers.IO) { rebuildItems() }
    }

    fun clear() { entries.value = emptyList(); save(emptyList()); items.value = emptyList() }

    /** Removes a title here and in the app it came from (Trakt, Simkl, Stremio, the Nuvio account). */
    fun remove(key: String) {
        val e = entries.value.firstOrNull { it.key == key }
        if (e?.app == "Trakt") {
            val id = traktIds().optLong(key, 0L)
            if (id > 0) scope.launch(Dispatchers.IO) { trakt.removePlayback(id) }
        }
        if (e != null) scope.launch(Dispatchers.IO) {
            val ref = refs(e.app).optString(key)
            when (e.app) {
                "Simkl" -> ref.toLongOrNull()?.let { simkl.removePlayback(it) }
                "Stremio" -> runCatching { val o = JSONObject(ref); stremio.dismiss(Stremio.Progress(o.optString("_id"), e.imdb, e.season, e.episode, e.percent, e.at, o)) }
                "Nuvio" -> nuvio?.deleteProgress(ref.ifBlank { if (e.episode > 0) "${e.imdb}_s${e.season}e${e.episode}" else e.imdb })
                "Nuvio Legacy" -> nuvio?.takeIf { it.connected }?.deleteProgress(if (e.episode > 0) "${e.imdb}_s${e.season}e${e.episode}" else e.imdb)
            }
        }
        val l = entries.value.filterNot { it.key == key }
        entries.value = l; save(l)
        scope.launch(Dispatchers.IO) { rebuildItems() }
    }

    /** Turns entries into carousel items using Cinemeta (name, art, logo, synopsis, rating). */
    private fun rebuildItems() {
        val out = entries.value.filter { it.percent >= 2 }.mapNotNull { e ->
            runCatching {
                val meta = http.json("https://v3-cinemeta.strem.io/meta/${e.type}/${e.imdb}.json", 7L * 24 * 3600 * 1000).optJSONObject("meta")
                    ?: return@runCatching null
                fun str(k: String) = meta.optString(k).takeIf { it.isNotBlank() && it != "null" }.orEmpty()
                val rating = meta.optString("imdbRating").toDoubleOrNull()?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()
                val year = Regex("(19|20)\\d{2}").find(str("releaseInfo").ifBlank { str("year") })?.value.orEmpty()
                val genres = meta.optJSONArray("genres")?.let { a -> (0 until minOf(3, a.length())).map { a.optString(it) } }.orEmpty()
                val sub = if (e.type == "series") "S${e.season} · E${e.episode}" else ""
                HeroItem(
                    key = e.key, id = e.imdb, type = e.type, name = str("name"),
                    poster = str("poster"), background = str("background").ifBlank { str("poster") }, logo = str("logo"),
                    overview = str("description"), rating = rating, year = year, genres = genres, platform = "",
                    progress = e.percent / 100f, sub = sub,
                )
            }.getOrNull()
        }
        items.value = out
    }

    private fun load(): List<CwEntry> = runCatching {
        val a = JSONArray(sp.getString("entries", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CwEntry(o.getString("imdb"), o.optInt("s"), o.optInt("e"), o.optInt("p"), o.optLong("at"), o.optString("app"))
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<CwEntry>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("imdb", it.imdb).put("s", it.season).put("e", it.episode).put("p", it.percent).put("at", it.at).put("app", it.app)) }
        sp.edit().putString("entries", a.toString()).apply()
    }
}
