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
            // (Never the first use of TV access: it would put "Allow USB debugging?" over the learning window.)
            if (!LocalShell.worked(app) || LocalShell.run(app, "true").isFailure) { Glog.i("Button probe: no TV access, raw input not read"); return@io }
            // (/proc/bus/input/devices is closed to the shell on Android 14; getevent lists them.)
            LocalShell.run(app, "getevent -p 2>/dev/null | grep -E '^add device|  name:' | sed -e 's/^add device [0-9]*: //' -e 's/^ *name: *//' | paste -sd ' ' -")
                .onSuccess { Glog.i("Button probe: input devices: " + it.trim().take(400)) }
            Glog.i("Button probe: reading the remote for $SECONDS s (press the button now)")
            val scan = HashMap<String, Int>()
            Bg.io("glass-key-probe-raw") { lines("raw", app, "timeout $SECONDS getevent -lt 2>/dev/null | grep -E 'EV_KEY|EV_MSC'") { l -> raw(app, l, scan) } }
            Bg.io("glass-key-probe-log") {
                lines("log", app, "timeout $SECONDS logcat -T 1 -v brief 2>/dev/null | grep -iE 'keycode|netflix|keyevent|hotkey|globalkey|key_?press|interceptKey|START u0' | grep -viE 'Glassio|GlassA11y|GlassFocus'") { l -> launched(app, l) }
            }
        }
    }

    /** A raw button just given to the learning window, and when: the app the system opens for it is noted. */
    @Volatile private var rawId = 0
    @Volatile private var rawAt = 0L
    /** When the raw press itself came (the system starts its app within milliseconds, before the press is learnt). */
    @Volatile private var pressAt = 0L
    /** The system's app starts seen lately (time, package): matched once a raw button is learnt. */
    private val starts = java.util.concurrent.ConcurrentLinkedDeque<Pair<Long, String>>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * A raw key press while learning: when no key reached Glassio around it (the system kept it), it is learnt as a raw
     * button ([RawKeys]), known by its HID usage.
     */
    private fun raw(app: Context, l: String, scan: HashMap<String, Int>) {
        val dev = l.substringAfter(']').substringBefore(':').trim()
        if ("MSC_SCAN" in l) { l.trim().split(Regex("\\s+")).lastOrNull()?.toIntOrNull(16)?.let { scan[dev] = it }; return }
        if (!("EV_KEY" in l && l.trimEnd().endsWith("DOWN"))) return
        val usage = scan[dev] ?: return
        val t = android.os.SystemClock.uptimeMillis()
        main.postDelayed({
            val c = com.glasslauncher.tv.KeyHub.capture ?: return@postDelayed
            if (com.glasslauncher.tv.KeyHub.captureKeyAt > t - 1500) return@postDelayed
            val id = RawKeys.idOf(usage)
            Glog.i("Button probe: no key behind the raw press ${"%06X".format(usage)}: learnt as a raw button")
            rawId = id; rawAt = android.os.SystemClock.uptimeMillis(); pressAt = t
            com.glasslauncher.tv.KeyHub.capture = null
            c(id)
            // (The system's start for it usually came already, while the press was waited on.)
            starts.firstOrNull { it.first >= t - 500 }?.let { learnt(app, it.second) }
            RawKeys.start(app.applicationContext as com.glasslauncher.tv.GlassApp)
        }, 700)
    }

    /** The app the system started (uid 1000) right after a raw button was learnt: closed when that button is pressed. */
    private fun launched(app: Context, l: String) {
        if (!("START u0" in l && "from uid 1000" in l)) return
        val pkg = Regex("(?:pkg|cmp)=([A-Za-z0-9_.]+)").find(l)?.groupValues?.get(1) ?: return
        if (pkg == app.packageName) return
        val now = android.os.SystemClock.uptimeMillis()
        starts.addLast(now to pkg)
        while (starts.size > 10) starts.pollFirst()
        if (rawId != 0 && now - rawAt < 5_000) learnt(app, pkg)
    }

    private fun learnt(app: Context, pkg: String) {
        val id = rawId.takeIf { it != 0 } ?: return
        RawKeys.setLaunch((app.applicationContext as com.glasslauncher.tv.GlassApp).settings, id, pkg)
        Glog.i("Button probe: the TV opens $pkg for it")
        rawId = 0
    }

    private fun lines(kind: String, ctx: Context, command: String, each: (String) -> Unit = {}) {
        var n = 0
        runCatching {
            LocalShell.stream(ctx, command) { l ->
                runCatching { each(l) }
                // (Another app's start line carries its link (dat=…): only which app and who started it is kept.)
                val shown = if ("START u0" in l) "START " + (Regex("(?:pkg|cmp)=([A-Za-z0-9_.]+)").find(l)?.groupValues?.get(1) ?: "?") + (Regex("from uid \\d+").find(l)?.value?.let { " $it" } ?: "") else l.trim()
                if (l.isNotBlank() && n++ < MAX_LINES) Glog.i("Button probe $kind: " + shown.take(200))
            }
        }.onFailure { Glog.w("Button probe $kind", it) }
        Glog.i("Button probe $kind: done, $n lines")
    }
}
