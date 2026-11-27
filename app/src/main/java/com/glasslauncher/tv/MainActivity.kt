package com.glasslauncher.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    /** The screen on show (QA: dump_focus). */
    val screenName get() = screen.name
    private var resumed by mutableStateOf(false)
    @Volatile private var lastInput = System.currentTimeMillis()
    /** The last real key or touch: not counted again while the screensaver shows (for the TV's own sleep time). */
    @Volatile private var userAt = System.currentTimeMillis()
    /** One player stopped keeping the screen on: the TV's sleep time came (see the idle loop). */
    private val sleepLetGo = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only one Glassio: Android keeps the one opened as the home screen and one opened as an app (from another
        // launcher's list, or a test from a computer) apart, and both then ran the home screen at once (two
        // trailers playing, one of them unseen). The older one goes.
        val old = live?.get()
        live = java.lang.ref.WeakReference(this)
        if (old != null && old !== this && !old.isFinishing && !old.isDestroyed) runCatching { old.finishAndRemoveTask() }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        // Android 17: the home network (the phone page, Hue, cameras, other TVs) is behind a permission, asked once.
        if (android.os.Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            runCatching { requestPermissions(arrayOf(LOCAL_NETWORK), 37) }
        val app = application as GlassApp
        com.glasslauncher.tv.data.Backup.restarted(this)
        DevHooks.atStart(this)
        handleExtras(intent)
        // The TV has just started: who is watching (or straight to the automatic profile), once the setup is done.
        // (Also when Glassio starts again with a PIN still being asked: closing Glassio is no way round it.)
        if (savedInstanceState == null && app.settings.setupDone &&
            (com.glasslauncher.tv.data.Profiles.newBoot(this) || com.glasslauncher.tv.data.ProfilePin.gate(this))) com.glasslauncher.tv.ui.ProfilesUi.wake(app)
        setContent {
            // A launcher never exits on Back; it just returns to its home view.
            BackHandler(enabled = true) { screen = Screen.Home }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(2_000)
                    val secs = homeIdleSeconds()
                    // A trailer playing counts as watching: the screensaver waits until it ends (and the full idle
                    // time after that).
                    if (com.glasslauncher.tv.ui.Trailers.playing.value != null) lastInput = System.currentTimeMillis()
                    // (Keys in Glassio's pop-up windows reach their own window, not this one: they count through
                    // GlassApp.touch. Without them the screensaver started under a pop-up in use, his video.)
                    val tapped = app.interaction.longValue
                    if (tapped > userAt) {
                        userAt = tapped; if (tapped > lastInput) lastInput = tapped
                        if (sleepLetGo.value) { sleepLetGo.value = false; com.glasslauncher.tv.ui.CustomBackground.resting.value = false }
                    }
                    val idle = System.currentTimeMillis() - lastInput
                    if (com.glasslauncher.tv.saver.HomeSaver.state.value != 0) lastInput = System.currentTimeMillis()
                    if (secs > 0 && idle > secs * 1000L && !sleepLetGo.value && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !systemSaverFirst(secs)) {
                        lastInput = System.currentTimeMillis()
                        com.glasslauncher.tv.saver.HomeSaver.begin(this@MainActivity)
                    }
                    // One player keeps the screen on, so the TV never slept under it (measured: 5.6 min with a 4 min
                    // sleep time). At the TV's own sleep time, counted from the last real key, it lets go of the screen
                    // and the TV goes on as it would without Glassio.
                    // (The home screen's video stands still too: playing, it kept the TV from its screensaver and its
                    // sleep, also before One player existed. Then the TV goes on as it would: its screensaver, its sleep.)
                    if (!sleepLetGo.value && resumed) {
                        val sleep = tvSleepMs()
                        if (sleep > 0 && System.currentTimeMillis() - userAt > sleep) {
                            sleepLetGo.value = true
                            com.glasslauncher.tv.ui.CustomBackground.resting.value = true
                            com.glasslauncher.tv.saver.HomeSaver.exit()
                            com.glasslauncher.tv.data.Glog.i("The TV's sleep time came: the screensaver and the background video stop, the TV may sleep")
                        }
                    }
                }
            }
            com.glasslauncher.tv.ui.GlassType {
                var setup by androidx.compose.runtime.remember { mutableStateOf(!app.settings.setupDone) }
                LaunchedEffect(app.requestSetup.intValue) { if (app.requestSetup.intValue > 0) setup = true }
                // Glassio's own accessibility service (the Home key) counts as "accessibility on", so Compose kept
                // checking the whole screen's semantics after every change (~7 ms on the main thread, many times a
                // second, also while the carousel slid). Without a screen reader there's nobody to read them: the
                // tree is left empty. With TalkBack on, everything is there as before.
                val reader by androidx.compose.runtime.produceState(screenReaderOn()) {
                    val am = getSystemService(android.view.accessibility.AccessibilityManager::class.java)
                    val l = android.view.accessibility.AccessibilityManager.TouchExplorationStateChangeListener { value = it }
                    am?.addTouchExplorationStateChangeListener(l)
                    awaitDispose { am?.removeTouchExplorationStateChangeListener(l) }
                }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                    .then(if (reader) androidx.compose.ui.Modifier else androidx.compose.ui.Modifier.clearAndSetSemantics { })) {
                    // The home screen is left out while the setup shows, so it can't take the remote's focus.
                    // A new install has nothing to catch up on (the setup explains it): the tour is for updates.
                    if (setup) com.glasslauncher.tv.ui.SetupScreen(app) { setup = false; app.settings.setupDone = true; app.settings.tourSeen = com.glasslauncher.tv.ui.WhatsNew.ID }
                    else {
                        // Multi-view cameras up: the whole home screen behind them blurs and darkens (logo, details,
                        // buttons and dock too), quickly.
                        val multi = com.glasslauncher.tv.MultiCamOverlay.open.value
                        val mv by androidx.compose.animation.core.animateFloatAsState(if (multi) 1f else 0f,
                            androidx.compose.animation.core.tween(if (multi) 180 else 150), label = "multiBlur")
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().graphicsLayer {
                            renderEffect = if (mv > 0.01f && android.os.Build.VERSION.SDK_INT >= 31)
                                androidx.compose.ui.graphics.BlurEffect(36f * mv, 36f * mv, androidx.compose.ui.graphics.TileMode.Clamp) else null
                        }) { HomeScreen(app, screen) { screen = it } }
                        if (mv > 0.01f) androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                            .graphicsLayer { alpha = mv }.background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f)))
                    }
                    // The screensaver on the home screen (*On the home screen*): over it, under the profile chooser.
                    if (!setup) com.glasslauncher.tv.saver.HomeSaverLayer(app)
                    // Kept awake while it shows, as its own screen keeps the TV awake.
                    val saverOn = com.glasslauncher.tv.saver.HomeSaver.state.value != 0 && !sleepLetGo.value
                    androidx.compose.runtime.LaunchedEffect(saverOn) {
                        if (saverOn) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    // "Who is watching?" over everything (the home screen, Settings, the setup).
                    com.glasslauncher.tv.ui.ProfileChooserHost(app)
                    // A profile's PIN, over the chooser and over Settings.
                    com.glasslauncher.tv.ui.PinHost(app)
                    // A see-through TV page in front (Default home app, …): Settings and the setup go dark behind
                    // it, their rows showed through and mixed with its own (his video). The home screen stays: the
                    // TV's side panels are made to sit beside an app.
                    if (underSeeThrough.value && (setup || screen == Screen.Settings))
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0E0E12)))
                    // Back from the screensaver: out of black (it closes faded to black, so nothing snaps).
                    if (com.glasslauncher.tv.ui.SaverGate.cover.floatValue > 0f) {
                        // Runs once the home screen draws again (a stopped activity doesn't compose).
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            val cover = com.glasslauncher.tv.ui.SaverGate.cover
                            kotlinx.coroutines.delay(80)
                            // A video background: the black waits for the video's first frame (at most 1.5 s), so
                            // the home screen comes out of black once, with its video, not with a stand-in picture.
                            val vf = com.glasslauncher.tv.ui.VideoFrames
                            if (vf.homeVideo) { val r0 = vf.drawnRev.intValue; var w = 0; while (vf.drawnRev.intValue == r0 && w < 1500) { kotlinx.coroutines.delay(30); w += 30 } }
                            androidx.compose.animation.core.animate(cover.floatValue, 0f, animationSpec = androidx.compose.animation.core.tween(420)) { v, _ -> cover.floatValue = v }
                        }
                        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()
                            .then(androidx.compose.ui.Modifier.graphicsLayer { alpha = com.glasslauncher.tv.ui.SaverGate.cover.floatValue })
                            .then(androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color.Black)))
                    }
                    // (Where the screensaver may be drawn on the home screen itself: see HomeSaver.homeShown. Read here, in
                    // the composition, so a change of any of them is seen: read inside the SideEffect it wasn't.)
                    val homeShown = screen == Screen.Home && !setup && !com.glasslauncher.tv.ui.ProfilesUi.open.value && com.glasslauncher.tv.ui.PinUi.ask.value == null && !com.glasslauncher.tv.ui.WhatsNew.open.value
                    androidx.compose.runtime.SideEffect {
                        KeyHub.homeInFront = resumed && screen == Screen.Home && !setup
                        com.glasslauncher.tv.saver.HomeSaver.homeShown = homeShown
                    }
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
    /**
     * The idle time before the screensaver on the home screen. With One player and Glassio as the TV's screensaver, One
     * player comes a little before the TV's own time even when *Start after* is off or longer: the TV's would start on
     * the home screen as a separate screen with its own player (not the background's video, no quick way back), and in
     * other apps the TV's still does (EB's #40).
     */
    private fun homeIdleSeconds(): Int {
        val st = (application as GlassApp).settings
        val secs = st.idleSeconds
        if (!com.glasslauncher.tv.saver.HomeSaver.wanted(st)) return secs
        val cr = contentResolver
        val on = runCatching { android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 }.getOrDefault(false)
        val comp = runCatching { android.provider.Settings.Secure.getString(cr, "screensaver_components") }.getOrNull().orEmpty()
        if (!on || !comp.startsWith(packageName)) return secs
        val tv = runCatching { android.provider.Settings.System.getInt(cr, "screen_off_timeout") }.getOrDefault(0)
        if (tv <= 0) return secs
        val before = ((tv - 8_000) / 1000).coerceAtLeast(10)
        return if (secs <= 0) before else minOf(secs, before)
    }

    /** The TV's own sleep time (Settings → Energy), 0 when it never sleeps or it can't be read. */
    private fun tvSleepMs(): Long {
        val cr = contentResolver
        val sleep = runCatching { android.provider.Settings.Secure.getLong(cr, "sleep_timeout") }.getOrDefault(-1L)
        val attentive = runCatching { android.provider.Settings.Secure.getLong(cr, "attentive_timeout") }.getOrDefault(-1L)
        return listOf(sleep, attentive).filter { it > 0 }.minOrNull() ?: 0L
    }

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

    private fun screenReaderOn(): Boolean =
        getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    /** True while another app's window is in front and Glassio is still visible under it (paused, not stopped). */
    private val underSeeThrough = androidx.compose.runtime.mutableStateOf(false)
    private val seeThroughCheck = Runnable {
        if (!resumed && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) underSeeThrough.value = true
    }

    override fun onResume() {
        super.onResume()
        window.decorView.removeCallbacks(seeThroughCheck)
        underSeeThrough.value = false
        // Pictures that failed while Glassio was up are asked again now (the network may be back meanwhile).
        com.glasslauncher.tv.data.NetState.retryIfFailed("back on the home screen")
        lastInput = System.currentTimeMillis(); userAt = lastInput; sleepLetGo.value = false; com.glasslauncher.tv.ui.CustomBackground.resting.value = false
        hideBars()
        (application as GlassApp).cw.pollNow()
        com.glasslauncher.tv.data.PeerSync.resumed()
        // An app's rows read again on the way back (what was just watched shows at once).
        if ((application as GlassApp).settings.channelsOn) com.glasslauncher.tv.data.Channels.refresh(this)
        com.glasslauncher.tv.data.NuvioWatch.start(this)
        com.glasslauncher.tv.ui.MusicUi.resumedRev.intValue++
        resumed = true
        // Set here too: a pause and resume in the same frame (another start of Glassio while it's in front) left
        // the screen unchanged, nothing was composed again, and Glassio counted as "not in front" until the next
        // change (a remote button meant for the home screen started Glassio again instead).
        KeyHub.homeInFront = screen == Screen.Home && (application as GlassApp).settings.setupDone
        com.glasslauncher.tv.ui.SaverGate.opening.value = false
    }

    override fun onStop() {
        super.onStop()
        window.decorView.removeCallbacks(seeThroughCheck)
        underSeeThrough.value = false
        MultiHost.windowGone()
        // Another app or the TV's own screensaver in front: the screensaver on the home screen ends (as its own
        // screen does).
        com.glasslauncher.tv.saver.HomeSaver.drop()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // (Still started a moment later: something see-through is in front, see underSeeThrough.)
        // (700 ms: an ordinary page that is slow to draw stops Glassio by then; Settings went dark for a moment first.)
        window.decorView.postDelayed(seeThroughCheck, 700)
        KeyHub.homeInFront = false
    }

    override fun onDestroy() {
        if (live?.get() === this) live = null
        super.onDestroy()
    }

    companion object {
        private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        /** The Glassio on screen (there is only ever one: see onCreate). */
        @Volatile private var live: java.lang.ref.WeakReference<MainActivity>? = null
        /** The Glassio on screen now, if any. */
        fun current(): MainActivity? = live?.get()
    }

    /** Testing from a computer: see [DevHooks]. */
    private fun handleExtras(i: Intent?) = DevHooks.handle(application as GlassApp, this, i)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home (or anything that brings Glassio forward) ends the screensaver on the home screen.
        if (!intent.getBooleanExtra("start_saver", false)) com.glasslauncher.tv.saver.HomeSaver.exit()
        handleExtras(intent)
        // Home pressed while we are already in front: go back to the plain home view.
        // (Not when a remote button or Hold Home asked for something: the Library, the menu, the Apps folder, an app.)
        // "keep_place": Glassio bringing itself back as it was (a song started from the search plays in its app).
        val asked = listOf("open_library", "open_menu", "open_folder", "open_app", "open_search", "keep_place").any { intent.hasExtra(it) }
        if (intent.hasCategory(Intent.CATEGORY_HOME) && !asked) {
            screen = Screen.Home
            (application as GlassApp).homeRev.intValue++
        }
        lastInput = System.currentTimeMillis(); userAt = lastInput; sleepLetGo.value = false; com.glasslauncher.tv.ui.CustomBackground.resting.value = false
    }

    override fun dispatchGenericMotionEvent(ev: android.view.MotionEvent): Boolean {
        if (com.glasslauncher.tv.saver.HomeSaver.state.value == 1 && ev.actionMasked != android.view.MotionEvent.ACTION_HOVER_EXIT) { com.glasslauncher.tv.saver.HomeSaver.exit(); return true }
        return super.dispatchGenericMotionEvent(ev)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (com.glasslauncher.tv.saver.HomeSaver.state.value == 1) { com.glasslauncher.tv.saver.HomeSaver.exit(); return true }
        return super.dispatchTouchEvent(ev)
    }

    override fun onUserInteraction() {
        lastInput = System.currentTimeMillis()
        userAt = lastInput; sleepLetGo.value = false; com.glasslauncher.tv.ui.CustomBackground.resting.value = false
        (application as GlassApp).touch()
    }

    @android.annotation.SuppressLint("RestrictedApi") // normal Activity override; lint misreads the androidx stub
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Every key here is someone at the remote. The What's new tour, the PIN pad, "Who is watching?" and the cameras
        // shown in this window take their keys below, before the Activity counts them (onUserInteraction): without the
        // Home button service (which counts every key on the TV) the screensaver started while the tour was being read
        // or a PIN typed. Seen with keys sent past the service: it started 1.4 s after a ▶ in the tour.
        if (event.action == KeyEvent.ACTION_DOWN) (application as GlassApp).touch()
        // The key that ended the screensaver on the home screen: its release doesn't reach the home screen.
        if (com.glasslauncher.tv.saver.HomeSaver.eatAfterEnd(event)) return true
        // The screensaver on the home screen takes the remote while it shows (◀ ▶ OK ▲ ▼; anything else ends it).
        if (com.glasslauncher.tv.saver.HomeSaver.state.value != 0) {
            if (com.glasslauncher.tv.saver.HomeSaver.onKey(event, (application as GlassApp).settings)) return true
        }
        if (com.glasslauncher.tv.ui.NavGate.blocks(event.keyCode)) return true
        // Multi-view or the cameras page shown in Glassio's own window (no Home button service) takes the remote.
        if (MultiHost.key(event)) return true
        // The shuffle button and "press the button you want" (also caught by the Home button service first).
        if (KeyHub.handle(application as GlassApp, KeyHub.idOf(event), event.action == KeyEvent.ACTION_DOWN)) return true
        // The What's new tour takes the remote while it shows.
        if (com.glasslauncher.tv.ui.WhatsNew.onKey(application as GlassApp, event.keyCode, event.action == KeyEvent.ACTION_DOWN)) return true
        // "Who is watching?" takes it too.
        if (com.glasslauncher.tv.ui.PinUi.onKey(event)) return true
        if (com.glasslauncher.tv.ui.ProfilesUi.onKey(application as GlassApp, event)) return true
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
