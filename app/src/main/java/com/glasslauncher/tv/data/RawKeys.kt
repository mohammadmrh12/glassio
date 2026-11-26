package com.glasslauncher.tv.data

import android.content.Context
import com.glasslauncher.tv.GlassApp

/**
 * Remote buttons the TV's own system takes before any app sees them (#43, #45, #48: a Homatics Box R 4K Plus opens
 * Netflix from `PhoneWindowManager.launchNetflix`; the button sends HID usage 0x0C0078, KEY_UNKNOWN to Linux, and no
 * key event ever reaches an app or an accessibility service). Glassio reads them below Android, through the TV's own
 * adb (TV access): `getevent` while such a button is mapped.
 *
 * - **Known by** their HID usage (`MSC_SCAN`), as an id below [BASE] (Android scan codes are small negative ids, key
 *   codes positive: see `KeyHub.idOf`). Learnt by the learning window when a raw press comes with no key behind it
 *   ([KeyProbe]).
 * - **A press** goes through `KeyHub.handle` like any button (press, double press, hold).
 * - **The app the system opens for it** (learnt from the log: started by the system, uid 1000, right after the press;
 *   `rawLaunches`) is stopped when it comes up within a few seconds of a press, so the job set for the button stays.
 * - **Asleep:** the press also wakes the TV, before the watcher is back. On the screen coming on, the log of the last
 *   seconds says whether the system just opened that app: then the button's job runs and that app is stopped too.
 */
object RawKeys {
    const val BASE = 100_000_000
    fun isRaw(id: Int) = id <= -BASE
    fun idOf(usage: Int) = -(BASE + usage)
    fun usageOf(id: Int) = -id - BASE

    /** The last press of a mapped raw button: its app (from rawLaunches) is stopped if it comes up soon after. */
    @Volatile var pressedAt = 0L
    @Volatile private var pressedId = 0
    @Volatile private var running = false
    @Volatile private var kick = 0L
    /** What the watcher is doing, for problem reports: not started, waiting (nothing mapped), watching, no TV access, dropped. */
    @Volatile private var state = "not started"
    private fun setState(s: String, log: String? = null) { if (state != s) { state = s; log?.let { Glog.i("Raw buttons: $it") } } }
    /** For problem reports (#52): how many raw buttons are set, the apps the TV opens for them, and the watcher's state. */
    fun status(st: Settings): String = "${mapped(st).size} set · ${st.rawLaunches.joinToString(",") { it.substringAfter('=') }.ifBlank { "no app" }} · $state"
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private class StopWatching : RuntimeException("no raw button mapped")

    // (Glassio's own Shuffle / Multi-view / Corner camera buttons too: they learn through the same window.)
    private fun mapped(st: Settings) = (RemoteMap.all(st).map { it.code } + listOf(st.shuffleKey, st.multiKey, st.cornerKey)).filter { isRaw(it) }.distinct()
    fun launchOf(st: Settings, id: Int): String? = st.rawLaunches.firstOrNull { it.substringBefore('=') == id.toString() }?.substringAfter('=')?.takeIf { it.isNotBlank() }
    fun setLaunch(st: Settings, id: Int, pkg: String) { st.rawLaunches = st.rawLaunches.filterNot { it.substringBefore('=') == id.toString() } + "$id=$pkg" }

    /** Starts the watcher (once; it waits while nothing is mapped). */
    fun start(app: GlassApp) {
        if (running) return
        running = true
        Bg.io("glass-raw-keys") {
            var wait = 5_000L
            while (true) {
                if (mapped(app.settings).isEmpty()) { setState("waiting"); pause(15_000); continue }
                val began = android.os.SystemClock.uptimeMillis()
                runCatching { watch(app) }.onFailure { if (it !is StopWatching) Glog.w("Raw buttons", it) }
                if (state == "watching") setState("dropped", "the connection to the remote ended, trying again")
                // (A connection that lasted resets the wait; one that drops at once waits longer each time.)
                wait = if (android.os.SystemClock.uptimeMillis() - began > 60_000) 5_000L else (wait * 2).coerceAtMost(60_000L)
                pause(wait)
            }
        }
    }

    /** The screen came on: the watcher tries again at once, and a press that woke the TV is looked for. */
    fun screenOn(app: GlassApp) {
        kick = android.os.SystemClock.uptimeMillis()
        if (mapped(app.settings).isEmpty()) return
        Bg.io("glass-raw-wake") { wokeByButton(app) }
    }

    private suspend fun pause(ms: Long) {
        val from = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - from < ms) {
            if (kick > from) return
            kotlinx.coroutines.delay(1000)
        }
    }

    private fun watch(app: GlassApp) {
        // (Said once, not at every try: before, a TV access that didn't answer left nothing in the log, #52.)
        if (LocalShell.run(app, "true").isFailure) { setState("no TV access", "TV access didn't answer, trying again later"); return }
        state = "watching"
        Glog.i("Raw buttons: watching the remote (${mapped(app.settings).size} mapped)")
        val scan = HashMap<String, Int>()
        LocalShell.stream(app, "getevent -l 2>/dev/null | grep -E 'EV_KEY|EV_MSC'") { line ->
            val dev = line.substringBefore(':').trim()
            when {
                "MSC_SCAN" in line -> line.trim().split(Regex("\\s+")).lastOrNull()?.toIntOrNull(16)?.let { scan[dev] = it }
                "EV_KEY" in line && (line.trimEnd().endsWith("DOWN") || line.trimEnd().endsWith("UP")) -> {
                    val usage = scan[dev] ?: return@stream
                    val id = idOf(usage)
                    // (The last raw button removed: the stream ends here, its adb connection with it.)
                    val ids = mapped(app.settings)
                    if (ids.isEmpty()) throw StopWatching()
                    // (While a button is being learnt the learning window reads the remote itself.)
                    if (com.glasslauncher.tv.KeyHub.capture != null || id !in ids) return@stream
                    val down = line.trimEnd().endsWith("DOWN")
                    if (down) { pressedAt = android.os.SystemClock.uptimeMillis(); pressedId = id }
                    // (On the main thread, as every other button: KeyHub's press timers are main-thread state.)
                    main.post { com.glasslauncher.tv.KeyHub.handle(app, id, down) }
                }
            }
        }
    }

    /**
     * A window came to the front (from the Home button service): the app the system opens for a raw button, right
     * after its press, is stopped (the button's own job stays in front, or what was there before comes back).
     */
    fun onFront(ctx: Context, st: Settings, pkg: String) {
        if (android.os.SystemClock.uptimeMillis() - pressedAt > 4_000) return
        if (pkg != launchOf(st, pressedId)) return
        // (Unless the button's own job opens that very app: then it stays.)
        if (RemoteMap.all(st).any { it.code == pressedId && it.action == "app:$pkg" }) return
        pressedAt = 0L
        Glog.i("Raw buttons: the TV opened $pkg for the button: closed")
        Bg.io("glass-raw-close") { LocalShell.run(ctx, "am force-stop $pkg") }
    }

    /** After the screen comes on: did the system open a mapped button's app in the last seconds (the press woke it)? */
    private suspend fun wokeByButton(app: GlassApp) {
        val st = app.settings
        val apps = mapped(st).mapNotNull { id -> launchOf(st, id)?.let { it to id } }.toMap()
        if (apps.isEmpty()) return
        // (The watcher already saw the press (its connection lived through the sleep): not twice.)
        if (android.os.SystemClock.uptimeMillis() - pressedAt < 30_000) return
        // (TV access comes back a moment after the screen: tried for 20 s.)
        // (`|| true`: grep finding nothing is no failure, which made the shell reconnect ten times at each wake.)
        var log: String? = null
        for (i in 0 until 10) {
            log = LocalShell.run(app, "logcat -d -v epoch -t 3000 2>/dev/null | grep 'START u0' | grep 'from uid 1000' || true").getOrNull()
            if (log != null) break
            kotlinx.coroutines.delay(2000)
        }
        val now = System.currentTimeMillis() / 1000.0
        val hit = log.orEmpty().lines().lastOrNull { l ->
            val t = l.trim().substringBefore(' ').toDoubleOrNull() ?: return@lastOrNull false
            now - t < 25 && apps.keys.any { "pkg=$it " in l || "cmp=$it/" in l }
        } ?: return
        val pkg = apps.keys.first { "pkg=$it " in hit || "cmp=$it/" in hit }
        val id = apps.getValue(pkg)
        Glog.i("Raw buttons: the TV woke with $pkg (the button's press): its job instead")
        LocalShell.run(app, "am force-stop $pkg")
        RemoteMap.find(st, id)?.let { m -> android.os.Handler(android.os.Looper.getMainLooper()).post { RemoteMap.run(app, m) } }
    }
}
