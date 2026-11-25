package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The TV's channels (the rows apps publish for the home screen: Continue watching, New, Recommended…), read from the
 * TV's own list (the TV provider). Shown in the carousel's place while an app that has some is selected in the dock
 * (Settings → Channels). Reading other apps' rows needs the "TV listings" right, given over ADB or asked on the TV.
 */
object Channels {
    class Program(val id: Long, val title: String, val sub: String, val poster: String, val intent: String, val wide: Boolean, val progress: Float)
    class Channel(val id: Long, val pkg: String, val name: String, val programs: List<Program>) {
        /** Kept in the settings (hidden / order): the app and the row's name (the TV's own ids change). */
        val key get() = "$pkg|$name"
    }

    private val CHANNELS: Uri = Uri.parse("content://android.media.tv/channel")
    private val PROGRAMS: Uri = Uri.parse("content://android.media.tv/preview_program")
    private val WATCH_NEXT: Uri = Uri.parse("content://android.media.tv/watch_next_program")
    const val PERMISSION = "android.permission.READ_TV_LISTINGS"
    const val WATCH_NEXT_NAME = "Continue watching"

    /** Nuvio's and Stremio's apps keep the carousel (it is made of their titles) unless they show rows: no music player there. */
    fun keepsCarousel(pkg: String) = pkg.contains("nuvio", ignoreCase = true) || pkg.contains("stremio", ignoreCase = true)

    /** Every app's rows that have something in them (read on the main thread's behalf, see [refresh]). */
    val all = androidx.compose.runtime.mutableStateOf<List<Channel>>(emptyList())

    fun hasAccess(ctx: Context): Boolean = ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Blocking. Gives Glassio the right to read the TV's channels, over the TV's ADB. */
    fun grant(ctx: Context): Boolean {
        if (hasAccess(ctx)) return true
        if (Store.PLAY) return false   // the TV's own question is asked instead
        LocalShell.run(ctx, "pm grant ${ctx.packageName} $PERMISSION; pm grant ${ctx.packageName} com.android.providers.tv.permission.READ_EPG_DATA 2>/dev/null; true")
        return hasAccess(ctx)
    }

    private fun programs(ctx: Context, uri: Uri, keyColumn: String): Map<String, List<Program>> {
        val out = LinkedHashMap<String, ArrayList<Program>>()
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            fun col(n: String) = c.getColumnIndex(n)
            val iId = col("_id"); val iKey = col(keyColumn); val iTitle = col("title"); val iEp = col("episode_title")
            val iPoster = col("poster_art_uri"); val iThumb = col("thumbnail_uri"); val iIntent = col("intent_uri")
            val iAr = col("poster_art_aspect_ratio"); val iPos = col("last_playback_position_millis"); val iDur = col("duration_millis"); val iBr = col("browsable")
            val iSeen = col("last_engagement_time_utc_millis")
            val seen = HashMap<Long, Long>()
            if (iKey < 0 || iIntent < 0) return out
            fun str(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i).orEmpty() else ""
            while (c.moveToNext()) {
                if (iBr >= 0 && !c.isNull(iBr) && c.getInt(iBr) == 0) continue
                val poster = str(iPoster).ifBlank { str(iThumb) }
                val intent = str(iIntent)
                if (intent.isBlank() || (poster.isBlank() && str(iTitle).isBlank())) continue
                val pos = if (iPos >= 0 && !c.isNull(iPos)) c.getLong(iPos) else 0L
                val dur = if (iDur >= 0 && !c.isNull(iDur)) c.getLong(iDur) else 0L
                // Aspect ratios: 0 = 16:9, 1 = 3:2, 2 = 4:3, 3 = 1:1, 4 = 2:3, 5 = movie poster.
                val ar = if (iAr >= 0 && !c.isNull(iAr)) c.getInt(iAr) else 0
                if (iSeen >= 0 && !c.isNull(iSeen)) seen[c.getLong(iId)] = c.getLong(iSeen)
                out.getOrPut(str(iKey)) { ArrayList() } += Program(c.getLong(iId), str(iTitle), str(iEp), poster, intent,
                    wide = ar <= 2, progress = if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f)
            }
            // Continue watching: what was watched last comes first, like the TV's own row (the TV hands them over oldest
            // first, so a title just watched went to the end of the row). Without a time, the newest card first.
            if (keyColumn == "package_name") for ((_, l) in out)
                l.sortWith(compareByDescending<Program> { seen[it.id] ?: 0L }.thenByDescending { it.id })
        }
        return out
    }

    /** Every app's rows that have something in them, in the TV's order ("Continue watching" first). Blocking. */
    fun read(ctx: Context): Result<List<Channel>> = runCatching {
        val chans = ArrayList<Triple<Long, String, String>>()
        ctx.contentResolver.query(CHANNELS, arrayOf("_id", "package_name", "display_name", "type"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(3) != "TYPE_PREVIEW") continue
                chans += Triple(c.getLong(0), c.getString(1).orEmpty(), c.getString(2).orEmpty())
            }
        } ?: error("the TV gave no channel list")
        val byChannel = programs(ctx, PROGRAMS, "channel_id")
        // What each app put in the TV's "Continue watching" row: its own row here.
        val next = runCatching { programs(ctx, WATCH_NEXT, "package_name") }.getOrDefault(emptyMap())
        (next.entries.mapIndexed { i, (pkg, l) -> Channel(-1L - i, pkg, WATCH_NEXT_NAME, l) } +
            chans.mapNotNull { (id, pkg, name) -> byChannel[id.toString()]?.takeIf { it.isNotEmpty() }?.let { Channel(id, pkg, name.ifBlank { "Recommended" }, it) } })
            // (Nuvio's and Stremio's apps and their forks too, like any other app: their rows were left out once, and
            // Nuvio One's "Show its rows" stayed blank, #33. Each app's rows can still be switched off in Settings.)
            .filterNot { it.pkg == ctx.packageName }
    }

    @Volatile private var started = false
    @Volatile private var pending = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Reads the rows again, in the background (a burst of changes is read once). */
    fun refresh(ctx: Context) {
        if (pending) return
        pending = true
        val app = ctx.applicationContext
        Bg.io("glass-channels") {
            Thread.sleep(400)
            pending = false
            if (!hasAccess(app)) return@io
            read(app).onSuccess { l -> main.post { all.value = l } }.onFailure { Glog.w("Channels", it) }
        }
    }

    /** Starts following the TV's channels (once the right is there). Safe to call again. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasAccess(app)) return
        if (!started) runCatching {
            val obs = object : android.database.ContentObserver(main) { override fun onChange(selfChange: Boolean) { refresh(app) } }
            for (u in listOf(CHANNELS, PROGRAMS, WATCH_NEXT)) app.contentResolver.registerContentObserver(u, true, obs)
            started = true
        }.onFailure { Glog.w("Channels: following changes", it) }
        refresh(app)
    }

    /** The apps that have rows (their packages). */
    fun apps(): List<String> = all.value.map { it.pkg }.distinct()

    const val TIZENTUBE = "io.gh.reisxd.tizentube.cobalt"
    /** SmartTube under every package name it has shipped as (the same list TubeRows uses). */
    val SMARTTUBE = listOf("org.smarttube.stable", "org.smarttube.beta", "app.smarttube.fdroid", "app.smarttube",
        "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv")

    /**
     * Apps whose rows come from another app by default, in order of preference. TizenTube Cobalt publishes none (#30):
     * it shows TubeRows' (`tv.tuberows.app`, SmartTube's rows re-published for TizenTube) when that is installed, else
     * SmartTube's own, each card then opening in TizenTube (see [open]). Used only while the app has no rows of its
     * own, and never over a choice made in its menu (*Rows from…*).
     */
    private val BORROW = mapOf(TIZENTUBE to listOf("tv.tuberows.app") + SMARTTUBE)

    /** Whose rows [pkg] shows: its own, the app chosen in its menu (*Rows from…*), or the one it borrows by default. */
    fun sourceOf(st: Settings, pkg: String): String {
        st.channelsFrom.firstOrNull { it.startsWith("$pkg=") }?.substringAfter('=')?.takeIf { it.isNotBlank() }?.let { return it }
        val l = all.value
        BORROW[pkg]?.let { srcs -> if (l.none { it.pkg == pkg }) srcs.firstOrNull { s -> l.any { it.pkg == s } }?.let { return it } }
        return pkg
    }

    /**
     * The apps with rows for Settings: those that publish some, then those that show another app's (TizenTube with
     * SmartTube's, or one chosen in a menu) when [installed].
     */
    fun settingsApps(st: Settings, installed: (String) -> Boolean): List<String> {
        val own = apps()
        val l = all.value
        // (An app chosen in a menu stays listed after "Its own rows" is chosen again: it went from the list with nothing
        // left to choose it back with; one that can borrow rows stays while any of them is there.)
        val chosen = st.channelsFrom.map { it.substringBefore('=') }
        val canBorrow = BORROW.filter { (_, srcs) -> srcs.any { s -> l.any { it.pkg == s } } }.keys
        val others = (chosen + canBorrow).distinct().filter { it !in own && installed(it) }
        return own + others
    }

    /** The key that hides row [ch] on [pkg]: its own key for its own rows; for another app's rows one of [pkg]'s (hiding
     *  SmartTube's History on TizenTube leaves it on SmartTube). */
    fun hideKey(pkg: String, ch: Channel) = if (ch.pkg == pkg) ch.key else "$pkg>${ch.key}"

    /** Sets whose rows [pkg] shows ([src] == [pkg]: its own). */
    fun setSource(st: Settings, pkg: String, src: String) {
        st.channelsFrom = st.channelsFrom.filterNot { it.startsWith("$pkg=") } + "$pkg=$src"
    }

    /** Every row of [pkg], in your order (Settings → Channels), the hidden ones too. */
    fun allFor(st: Settings, pkg: String): List<Channel> {
        val order = st.channelsOrder
        val src = sourceOf(st, pkg)
        return all.value.filter { it.pkg == src }.withIndex()
            .sortedBy { (i, ch) -> order.indexOf(ch.key).let { o -> if (o >= 0) o else 1000 + i } }.map { it.value }
    }

    /** The rows of [pkg] (one or two show at once, the others with Up / Down): none for an app switched off or locked; without the hidden ones. */
    fun rowsFor(st: Settings, pkg: String?): List<Channel> {
        if (pkg == null || !st.channelsOn || pkg in st.channelsOffApps || pkg in st.lockedApps) return emptyList()
        val hidden = st.channelsHidden
        return allFor(st, pkg).filter { hideKey(pkg, it) !in hidden }
    }

    /**
     * Opens what the row's card stands for, in its app. [from]: the app whose row it is, when [pkg] shows another app's
     * rows: the card's link then opens in [pkg] itself when it takes it (SmartTube's cards in TizenTube, like TubeRows
     * does), else as the card says.
     */
    fun open(ctx: Context, pkg: String, p: Program, from: String = pkg) {
        runCatching {
            val i = Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val link = i.data
            if (from != pkg && link != null && (link.scheme == "https" || link.scheme == "http")) {
                val here = Intent(Intent.ACTION_VIEW, link).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (here.resolveActivity(ctx.packageManager) != null) {
                    ctx.startActivity(here)
                    // SmartTube refreshes its rows when asked (so the video lands in its History row), as TubeRows asks it.
                    if (from in SMARTTUBE) refreshSmartTube(ctx, from, delayMs = 2 * 60_000L)
                    return
                }
            }
            // In the row's own app when it takes the link (a web link went to the browser otherwise).
            if (i.component == null && i.`package` == null) {
                val own = Intent(i).setPackage(pkg)
                if (own.resolveActivity(ctx.packageManager) != null) { ctx.startActivity(own); return }
            }
            ctx.startActivity(i)
        }.onFailure {
            Glog.w("Channel card", it)
            runCatching { ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg)?.let { l -> ctx.startActivity(l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }

    @Volatile private var smartTubeAskedAt = 0L

    /**
     * Asks SmartTube to refresh its rows: any broadcast to its exported `UpdateChannelsReceiver` re-runs its channel
     * worker at once (read in TubeRows' source, which does the same). At most once in 10 minutes. Used when another
     * app shows SmartTube's rows: SmartTube is never opened there, and Android lets an app no one opens refresh
     * less and less often.
     */
    fun refreshSmartTube(ctx: Context, pkg: String, delayMs: Long = 0L) {
        val app = ctx.applicationContext
        main.postDelayed({
            val now = System.currentTimeMillis()
            if (now - smartTubeAskedAt < 10 * 60_000L) return@postDelayed
            smartTubeAskedAt = now
            runCatching {
                app.sendBroadcast(Intent("com.glasslauncher.tv.action.REFRESH_ROWS")
                    .setClassName(pkg, "com.liskovsoft.leanbackassistant.channels.UpdateChannelsReceiver")
                    .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
                Glog.i("Rows: asked $pkg to refresh its rows")
            }.onFailure { Glog.w("SmartTube refresh", it) }
        }, delayMs)
    }

    /** When [pkg] shows SmartTube's rows: asks SmartTube for fresh ones if it wasn't asked in the last 30 minutes. */
    fun freshen(ctx: Context, st: Settings, pkg: String) {
        val src = sourceOf(st, pkg)
        if (src != pkg && src in SMARTTUBE && System.currentTimeMillis() - smartTubeAskedAt > 30 * 60_000L) refreshSmartTube(ctx, src)
    }

    /** For testing: what the TV hands over (log "GlassChannels"). */
    fun dump(ctx: Context) {
        // The raw Continue watching rows, with when each was last touched by its app.
        runCatching {
            ctx.contentResolver.query(WATCH_NEXT, null, null, null, null)?.use { c ->
                fun v(n: String) = c.getColumnIndex(n).let { i -> if (i >= 0 && !c.isNull(i)) c.getString(i) else "-" }
                val f = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                while (c.moveToNext()) {
                    val t = v("last_engagement_time_utc_millis").toLongOrNull()?.let { f.format(java.util.Date(it)) } ?: "-"
                    android.util.Log.i("GlassChannels", "WN ${v("package_name")} · id=${v("_id")} · ${v("title").take(30)} · ${v("episode_title").take(20)} · engaged=$t · pos=${v("last_playback_position_millis")} · dur=${v("duration_millis")} · type=${v("watch_next_type")} · browsable=${v("browsable")}")
                }
            }
        }.onFailure { android.util.Log.i("GlassChannels", "WN failed: ${it.message}") }
        read(ctx).onSuccess { l ->
            android.util.Log.i("GlassChannels", "${l.size} channels")
            l.forEach { ch -> android.util.Log.i("GlassChannels", "${ch.pkg} · ${ch.name} · ${ch.programs.size} · ${ch.programs.firstOrNull()?.let { it.title.take(30) + " | wide=" + it.wide + " | " + it.poster.take(40) + " | " + it.intent.take(300) }}") }
        }.onFailure { android.util.Log.i("GlassChannels", "failed: ${it.javaClass.simpleName} ${it.message}") }
    }
}
