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

    /** Nuvio's and Stremio's apps keep the carousel (it is made of their titles): no rows, no music player there. */
    fun keepsCarousel(pkg: String) = pkg.contains("nuvio", ignoreCase = true) || pkg.contains("stremio", ignoreCase = true)

    /** Every app's rows that have something in them (read on the main thread's behalf, see [refresh]). */
    val all = androidx.compose.runtime.mutableStateOf<List<Channel>>(emptyList())

    fun hasAccess(ctx: Context): Boolean = ctx.checkSelfPermission(PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Blocking. Gives Glassio the right to read the TV's channels, over the TV's ADB. */
    fun grant(ctx: Context): Boolean {
        if (hasAccess(ctx)) return true
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
                out.getOrPut(str(iKey)) { ArrayList() } += Program(c.getLong(iId), str(iTitle), str(iEp), poster, intent,
                    wide = ar <= 2, progress = if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f)
            }
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
            .filterNot { keepsCarousel(it.pkg) || it.pkg == ctx.packageName }
    }

    @Volatile private var started = false
    @Volatile private var pending = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Reads the rows again, in the background (a burst of changes is read once). */
    fun refresh(ctx: Context) {
        if (pending) return
        pending = true
        val app = ctx.applicationContext
        Thread({
            Thread.sleep(400)
            pending = false
            if (!hasAccess(app)) return@Thread
            read(app).onSuccess { l -> main.post { all.value = l } }.onFailure { Glog.w("Channels", it) }
        }, "glass-channels").apply { isDaemon = true }.start()
    }

    /** Starts following the TV's channels (once the right is there). Safe to call again. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (!hasAccess(app)) return
        if (!started) runCatching {
            val obs = object : android.database.ContentObserver(main) { override fun onChange(selfChange: Boolean) { refresh(app) } }
            for (u in listOf(CHANNELS, PROGRAMS, WATCH_NEXT)) app.contentResolver.registerContentObserver(u, true, obs)
            started = true
        }
        refresh(app)
    }

    /** The apps that have rows (their packages). */
    fun apps(): List<String> = all.value.map { it.pkg }.distinct()

    /** Every row of [pkg], in your order (Settings → Channels), the hidden ones too. */
    fun allFor(st: Settings, pkg: String): List<Channel> {
        val order = st.channelsOrder
        return all.value.filter { it.pkg == pkg }.withIndex()
            .sortedBy { (i, ch) -> order.indexOf(ch.key).let { o -> if (o >= 0) o else 1000 + i } }.map { it.value }
    }

    /** The rows of [pkg] (one or two show at once, the others with Up / Down): none for an app switched off or locked; without the hidden ones. */
    fun rowsFor(st: Settings, pkg: String?): List<Channel> {
        if (pkg == null || !st.channelsOn || pkg in st.channelsOffApps || pkg in st.lockedApps) return emptyList()
        val hidden = st.channelsHidden
        return allFor(st, pkg).filter { it.key !in hidden }
    }

    /** Opens what the row's card stands for, in its app. */
    fun open(ctx: Context, pkg: String, p: Program) {
        runCatching {
            val i = Intent.parseUri(p.intent, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    /** For testing: what the TV hands over (log "GlassChannels"). */
    fun dump(ctx: Context) {
        read(ctx).onSuccess { l ->
            android.util.Log.i("GlassChannels", "${l.size} channels")
            l.forEach { ch -> android.util.Log.i("GlassChannels", "${ch.pkg} · ${ch.name} · ${ch.programs.size} · ${ch.programs.firstOrNull()?.let { it.title.take(30) + " | wide=" + it.wide + " | " + it.poster.take(40) + " | " + it.intent.take(50) }}") }
        }.onFailure { android.util.Log.i("GlassChannels", "failed: ${it.javaClass.simpleName} ${it.message}") }
    }
}
