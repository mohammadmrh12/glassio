package com.glasslauncher.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.glasslauncher.tv.saver.ScreensaverActivity
import com.glasslauncher.tv.ui.HomeScreen
import com.glasslauncher.tv.ui.Screen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)
    @Volatile private var lastInput = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        val app = application as GlassApp
        handleExtras(intent)
        setContent {
            // A launcher never exits on Back; it just returns to its home view.
            BackHandler(enabled = true) { screen = Screen.Home }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2_000)
                    val secs = app.settings.idleSeconds
                    val idle = System.currentTimeMillis() - lastInput
                    if (secs > 0 && idle > secs * 1000L && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !systemSaverFirst(secs)) {
                        lastInput = System.currentTimeMillis()
                        startActivity(Intent(this@MainActivity, ScreensaverActivity::class.java))
                    }
                }
            }
            com.glasslauncher.tv.ui.GlassType {
                var setup by androidx.compose.runtime.remember { mutableStateOf(!app.settings.setupDone) }
                LaunchedEffect(app.requestSetup.intValue) { if (app.requestSetup.intValue > 0) setup = true }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                    // The home screen is left out while the setup shows, so it can't take the remote's focus.
                    if (setup) com.glasslauncher.tv.ui.SetupScreen(app) { setup = false; app.settings.setupDone = true }
                    else HomeScreen(app, screen) { screen = it }
                }
            }
        }
        // The pages paint their own black: the window's black under them was one more full screen for the TV GPU
        // every frame.
        window.setBackgroundDrawable(null)
    }

    /**
     * The TV's own screensaver (another app, e.g. Aerial Views) is on and starts at about the same time or
     * earlier: leave it to the TV. Starting Glassio's as well put one on top of the other, and Back then had
     * to be pressed twice (issue #9). With Type "TV screensaver" Glassio only asks the TV to start its own.
     */
    private fun systemSaverFirst(secs: Int): Boolean {
        val app = application as GlassApp
        if (app.settings.saverMode == "system") return false
        val cr = contentResolver
        val on = runCatching { android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 }.getOrDefault(false)
        val comp = runCatching { android.provider.Settings.Secure.getString(cr, "screensaver_components") }.getOrNull().orEmpty()
        // (Glassio itself as the TV screensaver counts too: the TV then starts it, in every app.)
        if (!on || comp.isBlank()) return false
        // Glassio's own dream: on the home screen Glassio starts it itself (a playing background video kept the
        // TV from starting it, so it never came on here).
        if (comp.startsWith(packageName)) return false
        val tv = runCatching { android.provider.Settings.System.getInt(cr, "screen_off_timeout") }.getOrDefault(Int.MAX_VALUE)
        return tv <= secs * 1000L + 5_000
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        lastInput = System.currentTimeMillis()
        hideBars()
        (application as GlassApp).cw.pollNow()
    }

    /** Testing from a computer: see [DevHooks]. */
    private fun handleExtras(i: Intent?) = DevHooks.handle(application as GlassApp, this, i)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleExtras(intent)
        // Home pressed while we are already in front: go back to the plain home view.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            screen = Screen.Home
            (application as GlassApp).homeRev.intValue++
        }
        lastInput = System.currentTimeMillis()
    }

    override fun onUserInteraction() {
        lastInput = System.currentTimeMillis()
        (application as GlassApp).touch()
    }

    @android.annotation.SuppressLint("RestrictedApi") // normal Activity override; lint misreads the androidx stub
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (com.glasslauncher.tv.ui.NavGate.blocks(event.keyCode)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            screen = if (screen == Screen.Settings) Screen.Home else Screen.Settings
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
