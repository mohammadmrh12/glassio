package com.glasslauncher.tv

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Home button → Glassio.
 *
 * Google TV sends Home to its own launcher no matter which app is the default home. This service
 * (switched on by the user under Settings > Accessibility) receives the Home key *before* the system
 * does and consumes it, so the stock launcher never appears and there is no flash.
 * The old window-change reaction is kept only as a fallback for devices that don't deliver key events.
 */
class HomeKeyService : AccessibilityService() {
    private var lastLaunch = 0L
    private var homeDownAt = 0L
    private var longDone = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var camera: CameraOverlay? = null
    /** Hold Home: what Settings → System → "Hold Home" says. */
    private val longPress = Runnable {
        longDone = true
        when ((application as? GlassApp)?.settings?.homeLong) {
            "recents" -> runCatching { startActivity(Intent(this, RecentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            "library" -> openGlass("open_library")
            "menu" -> openGlass("open_menu")
            "camera" -> toggleCamera()
        }
    }

    private fun openGlass(extra: String) = runCatching {
        startActivity(Intent(this, MainActivity::class.java).putExtra(extra, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun toggleCamera() {
        camera?.let { if (it.showing) { it.close(); camera = null; return } }
        val cams = (application as GlassApp).settings.cameras.mapNotNull { com.glasslauncher.tv.ui.Cam.parse(it) }
        if (cams.isEmpty()) { android.widget.Toast.makeText(this, "Add a camera first: Glassio settings → Cameras", android.widget.Toast.LENGTH_LONG).show(); return }
        val st = (application as GlassApp).settings
        // The camera chosen in Settings → Cameras (the first one if none is chosen or it was removed).
        val start = cams.indexOfFirst { it.url == st.cornerCamera }.coerceAtLeast(0)
        camera = CameraOverlay(this, cams, st.cameraCornerSec, start).also { it.show() }
    }

    private fun enabled(): Boolean = (application as? GlassApp)?.settings?.interceptHome == true

    private fun showGlass() {
        val now = System.currentTimeMillis()
        if (now - lastLaunch < 1000) return
        lastLaunch = now
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    /** Home key events reach this service on this TV: the window-change fallback is then never needed. */
    private val sp by lazy { getSharedPreferences("glass_home_key", MODE_PRIVATE) }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The camera in the corner: Back closes it (holding Home again too); every other key goes to the app as usual.
        camera?.takeIf { it.showing }?.let { c ->
            if (event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) { c.close(); camera = null }; return true }
        }
        if (event.keyCode != KeyEvent.KEYCODE_HOME || !enabled()) return false
        if (!sp.getBoolean("keys", false)) sp.edit().putBoolean("keys", true).apply()
        val recents = (application as? GlassApp)?.settings?.homeLong.let { it != null && it != "off" }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                homeDownAt = System.currentTimeMillis(); longDone = false
                if (recents) handler.postDelayed(longPress, 600)
            } else if (recents && !longDone && (event.flags and KeyEvent.FLAG_LONG_PRESS != 0 || System.currentTimeMillis() - homeDownAt > 500)) {
                handler.removeCallbacks(longPress); longPress.run()
            }
            KeyEvent.ACTION_UP -> { handler.removeCallbacks(longPress); if (!longDone) showGlass(); longDone = false }
        }
        return true
    }

    /**
     * The TV coming back from standby. After a short standby Google TV shows the app that was on screen, after a
     * longer one (minutes) it opens its own launcher instead, and nothing brought Glassio back until Home was
     * pressed. For a short while after the screen comes on, the Google TV launcher's home screen is replaced by
     * Glassio (System → "Open Glassio when the TV starts"); at any other time it is left alone (it also shows up
     * for a moment when Wi-Fi comes back, and Glassio must not jump in front of what you're watching then).
     */
    @Volatile private var screenOnAt = 0L
    @Volatile private var launcherHomeAt = 0L
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) {
            if (i.action != Intent.ACTION_SCREEN_ON) return
            screenOnAt = android.os.SystemClock.uptimeMillis()
            // The launcher may have come up just before this broadcast arrived.
            if (screenOnAt - launcherHomeAt < 3_000) wakeToGlass("screen on after the Google TV home")
        }
    }

    private fun wakeOn(): Boolean = enabled() && (application as? GlassApp)?.settings?.bootStart == true

    private fun wakeToGlass(why: String) {
        if (!wakeOn()) return
        com.glasslauncher.tv.data.Glog.i("Back from standby on the Google TV home: opening Glassio ($why)")
        lastLaunch = 0L
        showGlass()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !enabled()) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg != "com.google.android.apps.tv.launcherx" && pkg != "com.google.android.tvlauncher") return
        val launcherHome = event.className?.toString().orEmpty().let { it.contains("HomeActivity") || it.contains("MainActivity") || it.contains("LauncherActivity") }
        if (launcherHome) {
            val now = android.os.SystemClock.uptimeMillis()
            launcherHomeAt = now
            if (now - screenOnAt < 20_000) { wakeToGlass("launcher home after screen on"); return }
        }
        // The fallback brought Glassio to the front whenever the Google TV launcher showed any window of its own
        // (for example when Wi-Fi dropped and came back), even while you were watching. Where the Home key
        // reaches this service it is never needed; elsewhere only the launcher's main screen counts.
        if (sp.getBoolean("keys", false)) return
        val cls = event.className?.toString().orEmpty()
        if (!cls.contains("MainActivity") && !cls.contains("HomeActivity") && !cls.contains("LauncherActivity")) return
        // Only reached when the Home key was not filtered above (e.g. Home from the Google TV remote app).
        if (System.currentTimeMillis() - homeDownAt < 1500) return
        showGlass()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { registerReceiver(screenReceiver, android.content.IntentFilter(Intent.ACTION_SCREEN_ON)) }
        // Ask for key events explicitly; some TV builds ignore the flag from the XML alone.
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }
}
