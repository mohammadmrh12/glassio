package com.glasslauncher.tv.data

import android.content.Context

/**
 * After Glassio closed by itself, its next start offers the problem report with the crash in it (the person sees all of
 * it and presses Send, or not), once per crash. With *Send crash reports by themselves* on, it goes without asking. Only
 * a crash of the version installed now (an older one's report would say nothing new).
 */
object CrashReport {
    private fun file(ctx: Context) = java.io.File(ctx.filesDir, "last-crash.txt")
    private fun sp(ctx: Context) = ctx.getSharedPreferences("report", Context.MODE_PRIVATE)

    /** A crash of this version not offered yet. */
    fun pending(ctx: Context): Boolean {
        val f = file(ctx)
        if (!f.isFile) return false
        val updated = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        return f.lastModified() >= updated && f.lastModified() > sp(ctx).getLong("crashOffered", 0L)
    }

    /** Offered (shown or sent): not again for this crash. */
    fun offered(ctx: Context) { sp(ctx).edit().putLong("crashOffered", file(ctx).lastModified()).apply() }

    const val DESCRIPTION = "Glassio closed by itself (report sent after the crash)"
}
