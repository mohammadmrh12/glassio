package com.glasslauncher.tv.data

import android.content.Context

/**
 * While a remote button is being learnt, what the TV itself sees of the remote, through its own adb (TV access): the
 * raw input events (`getevent`, below Android's key handling) and the system log's lines about keys, for 20 s. Written
 * to Glassio's log, so a problem report sent right after shows them.
 *
 * For buttons that never reach Glassio as a key at all (#43, #45: the Netflix button of a Homatics Box R 4K Plus, of
 * which the log showed nothing while learning): the raw events say whether the remote sends anything and what, and
 * the system log may say who takes it. Nothing is changed on the TV; without TV access nothing happens.
 */
object KeyProbe {
    private const val SECONDS = 20
    private const val MAX_LINES = 40
    @Volatile private var runningUntil = 0L

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        val now = android.os.SystemClock.uptimeMillis()
        if (now < runningUntil) return
        runningUntil = now + SECONDS * 1000L
        Bg.io("glass-key-probe") {
            // (Only when TV access already works: no "Allow USB debugging?" window for this.)
            if (LocalShell.run(app, "true").isFailure) { Glog.i("Button probe: no TV access, raw input not read"); return@io }
            // (/proc/bus/input/devices is closed to the shell on Android 14; getevent lists them.)
            LocalShell.run(app, "getevent -p 2>/dev/null | grep -E '^add device|  name:' | sed -e 's/^add device [0-9]*: //' -e 's/^ *name: *//' | paste -sd ' ' -")
                .onSuccess { Glog.i("Button probe: input devices: " + it.trim().take(400)) }
            Glog.i("Button probe: reading the remote for $SECONDS s (press the button now)")
            Bg.io("glass-key-probe-raw") { lines("raw", app, "timeout $SECONDS getevent -lt 2>/dev/null | grep -E 'EV_KEY|EV_MSC'") }
            Bg.io("glass-key-probe-log") {
                lines("log", app, "timeout $SECONDS logcat -T 1 -v brief 2>/dev/null | grep -iE 'keycode|netflix|keyevent|hotkey|globalkey|key_?press|interceptKey' | grep -viE 'Glassio|GlassA11y|GlassFocus'")
            }
        }
    }

    private fun lines(kind: String, ctx: Context, command: String) {
        var n = 0
        runCatching {
            LocalShell.stream(ctx, command) { l ->
                if (l.isNotBlank() && n++ < MAX_LINES) Glog.i("Button probe $kind: " + l.trim().take(200))
            }
        }.onFailure { Glog.w("Button probe $kind", it) }
        Glog.i("Button probe $kind: done, $n lines")
    }
}
