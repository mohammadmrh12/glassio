package com.glasslauncher.tv.saver

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Settings
import com.glasslauncher.tv.ui.CustomBackground
import kotlinx.coroutines.delay

/**
 * The screensaver on the home screen itself (Settings → Background & screensaver → *On the home screen*,
 * `saverInHome`, off: its own screen as before).
 *
 * No screen is opened: the home screen hides its dock, carousel and glass and the screensaver's parts (place,
 * clock, island, the held-OK menu) are drawn over it. With the aerial background and the aerial screensaver it is
 * the background's own video that goes on, in the same player: nothing is loaded, and coming back only fades the
 * home screen in. Any other background: the screensaver's videos, posters or trailers are drawn over the home
 * screen (one player; the home screen's own video waits paused), and the home screen is already there underneath
 * when it ends.
 */
object HomeSaver {
    /**
     * 0: off; 1: showing; 2: closing (black fading off the home screen, see [Closing]); 3: on the background's video,
     * going (the home page fading in under its island, which stays until the home screen's own takes over).
     */
    val state = mutableStateOf(0)
    /** Showing on the background's own video (aerial background and aerial screensaver). */
    @Volatile var onBackground = false
        private set
    /** The home screen shows its own music island now (set by the home screen): the two hand over in one frame. */
    @Volatile var homeIsland = false
    /** One island at a time: the home screen's waits while this one is up (two glass islands one over the other). */
    fun ownsIsland(st: Settings) = onBackground && state.value.let { it == 1 || it == 3 } && st.musicOn && st.musicIsland

    fun wanted(st: Settings) = st.saverInHome && st.saverMode != "system"

    /**
     * The home screen itself is up and nothing covers it (set by MainActivity and the home screen): not the setup,
     * "Who is watching?", the tour, Settings, the Library or the open Apps folder. Elsewhere the screensaver opens
     * its own screen, as before: drawn there, it either wasn't drawn at all and took the remote's keys (the setup)
     * or sat over a page the video wasn't behind.
     */
    @Volatile var homeShown = false
    @Volatile var folderOpen = false
    /** Closes the side menu (set by the home screen): the screensaver starting over the open menu left it on screen
     *  afterwards with nothing selected (his report: menu open, screensaver, ▼ cameras, back: the menu stuck). */
    @Volatile var closeMenu: (() -> Unit)? = null

    /** The key that ended it: its release (and repeats) is eaten, so it can't act on the home screen (Back started it again). */
    @Volatile private var endedBy = 0

    /** True: the event was the ending key's own release or repeat, eaten. */
    fun eatAfterEnd(e: android.view.KeyEvent): Boolean {
        val k = endedBy
        if (k == 0 || e.keyCode != k) return false
        if (e.action == android.view.KeyEvent.ACTION_UP) endedBy = 0
        return true
    }

    /** Starts it on [activity] (the home screen) when wanted there; otherwise the screensaver's own screen. */
    fun begin(activity: android.app.Activity) {
        val app = activity.application as GlassApp
        val st = app.settings
        // The TV's "Allow USB debugging?" window may be up over Glassio (its keys don't reach Glassio, so Glassio is
        // idle): the screensaver's screen would cover it, unanswered (#25).
        val shell = com.glasslauncher.tv.data.LocalShell
        if (shell.promptUp || shell.waitedMs() > 3_000) return
        if (activity is com.glasslauncher.tv.MainActivity && wanted(st) && homeShown && !folderOpen) {
            if (state.value != 0) return
            closeMenu?.invoke()
            onBackground = st.bgMode == "aerial" && st.saverMode == "aerial" && CustomBackground.aerial.value != null
            com.glasslauncher.tv.data.Glog.i(com.glasslauncher.tv.data.Glog.HOME_SAVER + (if (onBackground) "background" else st.saverMode))
            state.value = 1
        } else {
            com.glasslauncher.tv.ui.SaverGate.opening.value = true
            activity.startActivity(android.content.Intent(activity, ScreensaverActivity::class.java))
        }
    }

    /** Ends it: on the background's video the home screen just fades back in; otherwise through black. */
    fun exit() {
        if (state.value != 1) return
        if (onBackground) {
            state.value = 3
            // (The home page's fade in takes 180 ms.)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (state.value == 3) state.value = 0 }, 220)
            return
        }
        SaverControl.out.value = true
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ if (state.value == 1) state.value = 2 }, 230)
    }

    /** Gone at once (another app came in front, the TV's own screensaver started). */
    fun drop() { state.value = 0 }

    /** Its keys (from the home screen's activity while it shows): ◀ ▶ OK ▼ ▲ as on its own screen, anything else ends it. */
    fun onKey(e: android.view.KeyEvent, st: Settings): Boolean {
        if (state.value == 2) return true
        // Going: the keys are the home screen's again.
        if (state.value == 3) return false
        if (SaverControl.popOpen.value) return false
        if (SaverControl.onKey(e, st)) return true
        if (e.action == android.view.KeyEvent.ACTION_DOWN) { endedBy = e.keyCode; exit() }
        return true
    }
}

/** What MainActivity draws over the home screen while [HomeSaver] is on. */
@Composable
internal fun HomeSaverLayer(app: GlassApp) {
    val s = HomeSaver.state.value
    if (s == 1 || (s == 3 && HomeSaver.onBackground)) {
        if (HomeSaver.onBackground) OnBackgroundSaver(app)
        else com.glasslauncher.tv.ui.GlassType { SaverContent(app) }
    }
    if (s == 2) Closing()
}

/**
 * Its video gone (the surface went with it, so nothing punches through the home screen), a black layer fades off
 * the home screen, which was there all along.
 */
@Composable
private fun Closing() {
    val a = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(Unit) { a.animateTo(0f, tween(320)); HomeSaver.state.value = 0 }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().graphicsLayer { alpha = a.value }.background(androidx.compose.ui.graphics.Color.Black))
}

/**
 * On the background's own video: no player here. ◀ ▶ step the background's video, the place is the background's,
 * the clock reads the background's live copies like the home screen's large clock.
 */
@Composable
private fun OnBackgroundSaver(app: GlassApp) {
    val ctx = LocalContext.current
    val st = app.settings
    remember { SaverControl.clockShown.value = true; SaverControl.lockShown.value = null; SaverControl.out.value = false
        SaverControl.ctx = ctx.applicationContext; com.glasslauncher.tv.ui.MusicUi.islandOpen.value = false; SaverControl.step = 0; true }
    val cur = CustomBackground.aerial.value
    val title = cur?.let { CustomBackground.aerialTitles[it] }.orEmpty()
    var change by remember { mutableIntStateOf(0) }
    val stepAt = remember { longArrayOf(0L) }
    // A new video brings the info in again, unless ◀ ▶ are being pressed (it would vanish and come back each time).
    LaunchedEffect(cur) {
        SaverControl.currentUrl = cur
        SaverControl.playingTitle = title
        if (android.os.SystemClock.uptimeMillis() - stepAt[0] > 6_000) change++
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            val k = SaverControl.step
            if (k != 0) {
                SaverControl.step = 0; stepAt[0] = android.os.SystemClock.uptimeMillis()
                if (k > 0) CustomBackground.next.intValue++ else CustomBackground.back.intValue++
            }
        }
    }
    DisposableEffect(Unit) { onDispose { SaverControl.step = 0; SaverControl.infoHidden.value = false; SaverControl.lockShown.value = null } }
    // *Hold OK asks*: keep this video, or never show it again (as on its own screen).
    var pop by remember { mutableStateOf<com.glasslauncher.tv.ui.PopState?>(null) }
    if (st.saverHoldMenu) {
        DisposableEffect(Unit) {
            val mine: () -> Unit = {
                val t = SaverControl.currentUrl?.let { CustomBackground.aerialTitles[it] }.orEmpty()
                pop = com.glasslauncher.tv.ui.PopState(t.ifBlank { "Video" }, listOf(
                    com.glasslauncher.tv.ui.PopOption(if (SaverControl.locked(st)) "Release this video" else "Keep this video", if (SaverControl.locked(st)) "✓" else "") { SaverControl.toggleLock(st) },
                    com.glasslauncher.tv.ui.PopOption("Don't show it again") {
                        if (SaverControl.hideCurrent(st, t)) com.glasslauncher.tv.ui.toast(ctx, "Won't show again · Settings → Background & screensaver → Hidden videos")
                    },
                ), null)
            }
            SaverControl.onLong = mine
            onDispose { if (SaverControl.onLong === mine) SaverControl.onLong = null; SaverControl.popOpen.value = false }
        }
        LaunchedEffect(pop) { SaverControl.popOpen.value = pop != null }
    }
    com.glasslauncher.tv.ui.GlassType {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val sc = maxWidth.value / 960f
            val env = remember(constraints.maxWidth, constraints.maxHeight, st.blurRadius) {
                val none = androidx.compose.ui.graphics.ImageBitmap(1, 1)
                com.glasslauncher.tv.ui.GlassEnv(none, none, none, constraints.maxWidth, constraints.maxHeight, st.glassTint, st.refraction, blurDp = st.blurRadius * 0.45f)
            }
            val envRef = androidx.compose.runtime.rememberUpdatedState(env)
            androidx.compose.runtime.CompositionLocalProvider(com.glasslauncher.tv.ui.LocalGlassEnv provides env, com.glasslauncher.tv.ui.LocalGlassEnvRef provides envRef) {
                // Going: its words fade with the home page coming in; the island stays as it is when the home
                // screen's own takes its place in the same frame, else it fades too.
                val going = HomeSaver.state.value == 3
                val wordsA by androidx.compose.animation.core.animateFloatAsState(if (going) 0f else 1f, tween(180), label = "saverWords")
                val islandA by androidx.compose.animation.core.animateFloatAsState(if (going && !HomeSaver.homeIsland) 0f else 1f, tween(180), label = "saverIsland")
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().graphicsLayer { alpha = wordsA }) {
                    SaverInfo(app, title, change)
                    LockBadge()
                }
                if (st.musicOn && st.musicIsland) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().graphicsLayer { alpha = islandA }) {
                    com.glasslauncher.tv.ui.MusicIsland(app, sc)
                }
                if (st.saverBigClock) androidx.compose.animation.AnimatedVisibility(SaverControl.clockShown.value,
                    enter = androidx.compose.animation.slideInVertically(tween(520)) { -it } + androidx.compose.animation.fadeIn(tween(420)),
                    exit = androidx.compose.animation.slideOutVertically(tween(420)) { -it } + androidx.compose.animation.fadeOut(tween(320))) {
                    BoxWithConstraints(Modifier.fillMaxSize()) { com.glasslauncher.tv.ui.BigClock(st, maxWidth.value / 960f) }
                }
                if (pop != null) com.glasslauncher.tv.ui.PopMenuHost(pop, sc, st.menuStyle) { pop = null }
            }
        }
    }
}
