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
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** True when the key was Glassio's (it is then not passed on). Called for every key, from the service and the activity. */
    fun handle(app: GlassApp, code: Int, down: Boolean): Boolean {
        capture?.let { c ->
            if (!usable(code)) return false
            if (!down) { capture = null; main.post { c(code) } }
            return true
        }
        val shuffle = app.settings.shuffleKey
        if (shuffle != 0 && code == shuffle && homeInFront) {
            if (!down) main.post { app.shuffleRev.intValue++ }
            return true
        }
        return false
    }
}
