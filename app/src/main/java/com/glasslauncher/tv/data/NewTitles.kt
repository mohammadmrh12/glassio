package com.glasslauncher.tv.data

import android.content.Context

/**
 * When each carousel title was first seen, so titles a catalog has just added can open the carousel for a while
 * ("New titles first"). The very first time everything already there counts as old, not new.
 */
object NewTitles {
    /** How long a title stays "new". */
    const val NEW_FOR_MS = 48L * 60 * 60 * 1000
    private const val MAX = 4000
    private var sp: android.content.SharedPreferences? = null
    private var seen: LinkedHashMap<String, Long>? = null

    @Synchronized private fun map(ctx: Context): LinkedHashMap<String, Long> = seen ?: run {
        val p = ctx.applicationContext.getSharedPreferences("titles_seen", Context.MODE_PRIVATE).also { sp = it }
        val m = LinkedHashMap<String, Long>()
        p.getString("seen", "").orEmpty().lineSequence().forEach { l ->
            val k = l.substringBefore('\t'); val t = l.substringAfter('\t', "").toLongOrNull()
            if (k.isNotBlank() && t != null) m[k] = t
        }
        m.also { seen = it }
    }

    /** Records the titles in [keys] not seen before; returns the keys that are new right now, newest first. */
    @Synchronized fun update(ctx: Context, keys: List<String>): List<String> {
        val m = map(ctx)
        val now = System.currentTimeMillis()
        // The first quarter of an hour counts as "already there": catalogs load a bit at a time, and titles that
        // arrived a few seconds after the first look were taken for new ones.
        val start = sp?.getLong("since", 0L)?.takeIf { it > 0 } ?: now.also { sp?.edit()?.putLong("since", it)?.apply() }
        val firstRun = m.isEmpty() || now - start < 15 * 60_000L
        var changed = false
        for (k in keys) if (k !in m || (firstRun && (m[k] ?: 0L) > 0L)) { m[k] = if (firstRun) 0L else now; changed = true }
        while (m.size > MAX) { m.remove(m.keys.first()); changed = true }
        if (changed) sp?.edit()?.putString("seen", m.entries.joinToString("\n") { "${it.key}\t${it.value}" })?.apply()
        return keys.filter { (m[it] ?: 0L) > now - NEW_FOR_MS }.sortedByDescending { m[it] ?: 0L }
    }
}
