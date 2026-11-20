package com.glasslauncher.tv.saver

import com.glasslauncher.tv.data.Bg
import kotlinx.coroutines.delay
import android.content.Context
import com.glasslauncher.tv.data.LocalShell

/**
 * Starts the TV's own screensaver (e.g. Aerial Views) on demand.
 *
 * Android only lets root start a dream directly, so this briefly shortens the screen timeout to its
 * minimum (10 s); the system then starts the chosen screensaver itself, and the original timeout is put
 * back as soon as it is running. The original value is saved first, so it is restored even if Glass is
 * closed half way (see [restoreIfPending]).
 */
object SystemDream {
    private const val PREFS = "glass_dream"
    private const val KEY = "restoreTimeout"

    /**
     * Blocking. Starts the screensaver chosen in the TV settings right away: IDreamManager.dream()
     * (transaction 1), which the shell may call. Fails if USB debugging was not allowed for Glass.
     */
    fun startNow(ctx: Context): Result<Unit> = runCatching {
        LocalShell.run(ctx, "service call dreams 1").getOrThrow()
        Thread.sleep(600)
        check(isDreaming(ctx)) { "screensaver did not start" }
    }

    /** Older route: shorten the screen timeout so the system starts it itself. */
    fun arm(ctx: Context): Result<Unit> = runCatching {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = sp.getLong(KEY, -1L)
        val current = LocalShell.run(ctx, "settings get system screen_off_timeout").getOrThrow().trim().toLongOrNull() ?: 20_000L
        val original = if (pending > 0) pending else current
        sp.edit().putLong(KEY, original).commit()
        LocalShell.run(ctx, "settings put system screen_off_timeout 10000").getOrThrow()
    }

    fun isDreaming(ctx: Context): Boolean =
        LocalShell.run(ctx, "dumpsys dreams").getOrNull()?.lineSequence()
            ?.any { it.trim().startsWith("mCurrentDream=") && !it.trim().endsWith("null") } == true

    /** Waits (up to ~40 s) for the screensaver to start, then restores the timeout. */
    fun awaitAndRestore(ctx: Context, onDreaming: () -> Unit) {
        Bg.io("glass-dream") {
            var started = false
            for (i in 0 until 40) {
                delay(1000)
                if (isDreaming(ctx)) { started = true; break }
            }
            restoreIfPending(ctx)
            if (started) onDreaming()
        }
    }

    fun restoreIfPending(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val v = sp.getLong(KEY, -1L)
        if (v <= 0) return
        if (LocalShell.run(ctx, "settings put system screen_off_timeout $v").isSuccess) sp.edit().remove(KEY).commit()
    }
}
