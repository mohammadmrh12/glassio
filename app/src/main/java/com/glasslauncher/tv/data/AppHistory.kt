package com.glasslauncher.tv.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * The apps used lately, for Recent apps without ADB (USB debugging can stay off).
 *
 * Glassio's Home-button service already sees which app's window comes to the front; each one Glassio knows is
 * written down here, newest first. Where the TV gives Glassio usage access (Settings → Apps → Special app access →
 * Usage access) Android's own usage history is added too, so apps opened before Glassio started are listed as well.
 */
object AppHistory {
    private const val MAX = 16
    private const val KEEP_MS = 12L * 60 * 60 * 1000
    private val lock = Any()
    @Volatile private var last: String? = null

    private fun sp(ctx: Context) = ctx.getSharedPreferences("glass_recents", Context.MODE_PRIVATE)

    /** [pkg] came to the front (any app; only the ones [known] says are apps are kept). */
    fun onFront(ctx: Context, pkg: String, known: (String) -> Boolean) {
        if (pkg == last) return
        last = pkg
        if (pkg == ctx.packageName || !known(pkg)) return
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val list = read(ctx).filterNot { it.first == pkg || now - it.second > KEEP_MS }
            write(ctx, (listOf(pkg to now) + list).take(MAX))
        }
    }

    /** Closed from Recent apps: not listed again until it is opened again. */
    fun forget(ctx: Context, pkg: String) = synchronized(lock) {
        write(ctx, read(ctx).filterNot { it.first == pkg })
        sp(ctx).edit().putLong("gone:$pkg", System.currentTimeMillis()).apply()
        if (last == pkg) last = null
    }

    /** Newest first: this list and (with usage access) Android's usage history of the last hours. */
    fun recent(ctx: Context): List<String> {
        val now = System.currentTimeMillis()
        val seen = HashMap<String, Long>()
        read(ctx).forEach { (p, t) -> if (now - t <= KEEP_MS) seen[p] = maxOf(seen[p] ?: 0L, t) }
        if (usageAccess(ctx)) runCatching {
            val um = ctx.getSystemService(UsageStatsManager::class.java)
            val ev = um.queryEvents(now - KEEP_MS, now); val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                @Suppress("DEPRECATION")
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) seen[e.packageName] = maxOf(seen[e.packageName] ?: 0L, e.timeStamp)
            }
        }
        val gone = sp(ctx)
        return seen.entries.filter { it.value > gone.getLong("gone:${it.key}", 0L) }
            .sortedByDescending { it.value }.map { it.key }
    }

    fun usageAccess(ctx: Context): Boolean = runCatching {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    private fun read(ctx: Context): List<Pair<String, Long>> =
        sp(ctx).getString("list", "").orEmpty().split('\n').mapNotNull { l ->
            val p = l.substringBefore(' '); val t = l.substringAfter(' ', "").toLongOrNull()
            if (p.isBlank() || t == null) null else p to t
        }

    private fun write(ctx: Context, list: List<Pair<String, Long>>) {
        sp(ctx).edit().putString("list", list.joinToString("\n") { "${it.first} ${it.second}" }).apply()
    }
}
