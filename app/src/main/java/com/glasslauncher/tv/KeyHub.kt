package com.glasslauncher.tv

/**
 * Remote buttons for Glassio's own actions (the shuffle button). Many buttons on TV remotes never reach an app
 * (the system keeps them), but the Home button service sees every one first, so it catches them there.
 */
object KeyHub {
    /** Glassio's home screen is the screen in front. */
    @Volatile var homeInFront = false
    /** Settings is waiting for "press the button you want". */
    @Volatile var capture: ((Int) -> Unit)? = null
    private val nav = setOf(
        android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT,
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER,
        android.view.KeyEvent.KEYCODE_BACK, android.view.KeyEvent.KEYCODE_HOME, android.view.KeyEvent.KEYCODE_POWER,
    )
    fun usable(code: Int) = code !in nav && code != 0
    /**
     * The number a remote button is known by: its key code, or, for a button the TV gives no key code (0: many
     * remotes' Netflix and other app buttons), its scan code as a negative number. Such a button could not be
     * learnt at all before: it read as "Not set".
     */
    fun idOf(e: android.view.KeyEvent): Int = if (e.keyCode != 0) e.keyCode else -e.scanCode
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** True when the key was Glassio's (it is then not passed on). Called for every key, from the service and the activity. */
    fun handle(app: GlassApp, code: Int, down: Boolean): Boolean {
        capture?.let { c ->
            // (In the log, so a problem report shows what a button that won't be learnt really sends.)
            if (down) com.glasslauncher.tv.data.Glog.i("Remote button while learning: $code" + if (usable(code)) "" else " (not usable)")
            if (!usable(code)) return false
            if (!down) { capture = null; main.post { c(code) } }
            return true
        }
        val st = app.settings
        // Remote mapping: a button that opens an app, the Apps folder, the recent apps or an input, in any app.
        // With a double press or a hold set for it too (#25), its presses are read first: a hold acts after 550 ms
        // held, a second press within 350 ms is a double press, else the single press acts (350 ms later).
        if (com.glasslauncher.tv.data.RemoteMap.hasMore(st, code)) { presses(app, code, down); return true }
        com.glasslauncher.tv.data.RemoteMap.find(st, code)?.let { m ->
            if (!down) main.post { com.glasslauncher.tv.data.RemoteMap.run(app, m) }
            return true
        }
        // Camera buttons work in any app: multi-view and the corner camera.
        if (st.multiKey != 0 && code == st.multiKey) { if (!down) main.post { MultiHost.toggle(bottom = false) }; return true }
        if (st.cornerKey != 0 && code == st.cornerKey) { if (!down) main.post { HomeKeyService.instance?.toggleCorner() }; return true }
        val shuffle = app.settings.shuffleKey
        if (shuffle != 0 && code == shuffle && homeInFront) {
            if (!down) main.post { app.shuffleRev.intValue++ }
            return true
        }
        return false
    }

    @Volatile private var heldCode = 0
    @Volatile private var holdDone = false
    /** The button released once, waiting to see whether a second press comes (350 ms). */
    @Volatile private var waitCode = 0
    /** The second press is down: its release is the double press (whenever it comes). */
    @Volatile private var secondCode = 0
    private var holdTask: Runnable? = null
    private var singleTask: Runnable? = null

    /**
     * A button with a double press or a hold (#25). The window for the second press runs from the first release to
     * the second press going *down* (counted to its release, a slower second press became two single presses), and a
     * press followed by a press-and-hold is a double press, not a single and then a hold.
     */
    private fun presses(app: GlassApp, code: Int, down: Boolean) {
        val R = com.glasslauncher.tv.data.RemoteMap
        val st = app.settings
        if (down) {
            if (heldCode == code) return  // the button's own repeats while held
            heldCode = code; holdDone = false
            if (waitCode == code) {
                // The second press: the single is off, this one is the double press.
                singleTask?.let(main::removeCallbacks); waitCode = 0; secondCode = code
                return
            }
            R.find(st, code, "hold")?.let { m ->
                holdTask = Runnable { if (heldCode == code) { holdDone = true; R.run(app, m) } }.also { main.postDelayed(it, 550) }
            }
            return
        }
        heldCode = 0
        holdTask?.let(main::removeCallbacks)
        if (holdDone) { holdDone = false; return }
        if (secondCode == code) { secondCode = 0; R.find(st, code, "double")?.let { m -> main.post { R.run(app, m) } }; return }
        val double = R.find(st, code, "double")
        val single = R.find(st, code)
        if (double == null) { single?.let { m -> main.post { R.run(app, m) } }; return }
        waitCode = code
        singleTask = Runnable { if (waitCode == code) { waitCode = 0; single?.let { R.run(app, it) } } }.also { main.postDelayed(it, 350) }
    }
}
