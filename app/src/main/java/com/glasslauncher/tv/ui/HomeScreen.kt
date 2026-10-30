package com.glasslauncher.tv.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.async
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.Art
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.NuvioLaunch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Screen { Home, Settings, Library }

/** Test only (am start --ei dbg_hide <bits>): 1 backdrop, 2 carousel text, 4 top bar, 8 dock. Finds what the GPU spends a frame on. */
val DebugHide = androidx.compose.runtime.mutableIntStateOf(0)

const val DOCK_COLUMNS = 6

/** Called when an arrow leads out of a grid edge; true when it did something (no bounce then). */
private val LocalEdge = androidx.compose.runtime.compositionLocalOf<(androidx.compose.ui.focus.FocusDirection) -> Boolean> { { false } }
/** Index of the "Manage from phone" category in the settings list. */
const val PHONE_CATEGORY = 10
/** Index of the "Catalogs" category in the settings list. */
const val CATALOGS_CATEGORY = 5
/** Pseudo package used by move mode for the Apps folder tile. */
private const val FOLDER = "__apps_folder__"

/** Backdrops are decoded at the size they are prepared at (2/3 of the screen in Performance). */
@Volatile private var backdropScale = 1f

private fun imgReq(ctx: android.content.Context, data: Any?, w: Int = 1920, h: Int = 1080) =
    ImageRequest.Builder(ctx).data(data).size(if (w == 1920) (w * backdropScale).toInt() else w, if (w == 1920) (h * backdropScale).toInt() else h)
        // Backdrops are baked into a new bitmap on the CPU (bakeBackdrop): decode them in software, a
        // hardware bitmap would have to be read back from the GPU first, which stalls the render thread.
        .apply { if (w == 1920) allowHardware(false) }
        .build()

private fun Modifier.clipR(r: Dp): Modifier = this.clip(RoundedCornerShape(r))

/** Every dock measurement in dp, from the screen width and the "Dock size" setting (1.0 = full width). */
private class DockMetrics(val s: Float, screenW: Float, dockScale: Float) {
    val padH = 18 * s * dockScale
    val gap = 14 * s * dockScale
    private val fullCellW = (screenW - 2 * 24 * s - 2 * 18 * s - (DOCK_COLUMNS - 1) * 12 * s) / DOCK_COLUMNS
    val cellW = fullCellW * dockScale
    val dockW = DOCK_COLUMNS * cellW + (DOCK_COLUMNS - 1) * gap + 2 * padH
    val sideMargin = (screenW - dockW) / 2f
    val tileH = cellW / 1.6f
    val cellH = tileH
    // The bottom padding is smaller than the row gap so the next row never peeks out of a collapsed dock.
    val padTop = 12 * s * dockScale
    val padBottom = 10 * s * dockScale
    val bottomMargin = 22 * s
    val collapsedH = padTop + cellH + padBottom
}

@Composable
fun HomeScreen(app: GlassApp, screen: Screen, onScreen: (Screen) -> Unit) {
    val settings = app.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var backdrop by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var movingPkg by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var settingsCat by remember { mutableIntStateOf(0) }
    val firstTile = remember { FocusRequester() }
    val heroFocus = remember { FocusRequester() }
    val folderFocus = remember { FocusRequester() }
    var pickerFor by remember { mutableStateOf<AppEntry?>(null) }
    var folderOpen by remember { mutableStateOf(false) }
    var libFromDock by remember { mutableStateOf(false) }
    var libWarm by remember { mutableStateOf(false) }
    // Up from the Library: the home screen is drawn once under it first (its first frame is the slow one),
    // then the page scrolls down, so the scroll itself runs at full speed.
    var homePrep by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val gearReq = remember { FocusRequester() }
    // Just closed: focus can pass over the settings button on its way back, which must not open it again.
    var menuClosedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var folderMoving by remember { mutableStateOf<String?>(null) }
    val tileFocus = remember { HashMap<String, FocusRequester>() }

    // The current backdrop as a small bitmap: every glass surface refracts it. The page backdrop only
    // switches once the glass copy is ready, and both crossfade together, so the glass never lags behind.
    var backImgs by remember { mutableStateOf<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(null) }
    var prevImgs by remember { mutableStateOf<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(null) }
    var shownBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var prevBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    // Which picture is on screen: the title text waits for it, so text and picture change together.
    val shownUrl = remember { mutableStateOf<String?>(null) }
    var fadeMs by remember { mutableIntStateOf(settings.transitionMs) }
    val mix = remember { androidx.compose.animation.core.Animatable(1f) }
    // 0 = crossfade; 1 / -1 = the new picture slides in from the right / left (Slide animation).
    var slideDir by remember { mutableIntStateOf(0) }
    LaunchedEffect(backdrop, settings.blurRadius, settings.glassRes, settings.slideAnim, settings.pageScale, settings.memTier, settings.bgMode, settings.bgImage, settings.bgVideo, CustomBackground.aerial.value, CustomBackground.frameRev.intValue) {
        GlassCache.res = settings.glassRes
        BakeCache.max = when (settings.memTier) { 0 -> 2; 2 -> 4; else -> 3 }
        backdropScale = settings.pageScale
        backdrop565 = settings.memTier == 0
        backdropSharpen = settings.imageQuality == "hd"
        GlassCache.limit(settings.memTier)
        // Your own picture / video: it replaces every title's backdrop (one picture, so nothing changes per slide).
        val custom = CustomBackground.still(ctx, settings)
        val u = custom ?: backdrop ?: return@LaunchedEffect
        if (custom != null && shownUrl.value == custom && backImgs != null) return@LaunchedEffect
        // Wait for both the full picture and its glass copy, then switch them in the same frame.
        // Originals are big downloads: if the original is not cached yet, show the 1280 px copy first
        // (fast) and swap in the original silently once it arrives.
        val loader = SingletonImageLoader.get(ctx)
        val t0 = android.os.SystemClock.uptimeMillis()
        fun bmpOf(r: coil3.request.ImageResult?) = (r as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
        var fullBmp: ImageBitmap? = null
        var upgrade: kotlinx.coroutines.Deferred<coil3.request.ImageResult?>? = null
        // "1280 enhanced" downloads the 1280 px picture too; it is sharpened as it is scaled up (bakeBackdrop).
        val high = settings.imageQuality == "high" || settings.imageQuality == "hd"
        val pinnedBmp = GlassCache.pinnedFull[u]
        val orig = async { if (pinnedBmp != null) null else loader.execute(imgReq(ctx, if (high) sized(u, "w1280") else u)) }
        val next = kotlinx.coroutines.coroutineScope {
            // Glass copies: ready (prepared ahead), or made from the page picture itself once it is here.
            val g = async { GlassCache.peek(u, settings.blurRadius) }
            fullBmp = pinnedBmp ?: kotlinx.coroutines.withTimeoutOrNull(120) { bmpOf(orig.await()) }
            if (fullBmp == null && !high && sized(u, "w1280") != u) {
                fullBmp = bmpOf(loader.execute(imgReq(ctx, sized(u, "w1280"))))
                upgrade = orig
            } else if (fullBmp == null && app.browsing() && sized(u, "w780") != u) {
                // Moving fast through the carousel and this picture isn't here yet: a small copy (a third of the
                // download) goes on screen now, the full one replaces it silently when it arrives.
                fullBmp = kotlinx.coroutines.withTimeoutOrNull(1500) { bmpOf(loader.execute(imgReq(ctx, sized(u, "w780")))) }
                if (fullBmp != null) upgrade = orig else fullBmp = bmpOf(orig.await())
            } else if (fullBmp == null) fullBmp = bmpOf(orig.await())
            (g.await() ?: fullBmp?.let { fb -> withContext(Dispatchers.Default) { runCatching { GlassCache.fromBitmap(u, settings.blurRadius, fb.asAndroidBitmap().let { b ->
                if (b.config == android.graphics.Bitmap.Config.HARDWARE) b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else b }) }.getOrNull() } })
                ?.let { Triple(it.blurred, it.sharp, it.heavy) }
        }
        // A slow or busy TV sometimes loses a download: try again (smaller copy) before giving up on this title.
        var tries = 0
        while (fullBmp == null && tries < 3) {
            delay(700L * (tries + 1))
            val res = loader.execute(imgReq(ctx, sized(u, "w1280")))
            fullBmp = bmpOf(res)
            if (fullBmp == null) com.glasslauncher.tv.data.Glog.w("Backdrop $u failed (try ${tries + 1})", (res as? coil3.request.ErrorResult)?.throwable)
            tries++
        }
        if (fullBmp == null) return@LaunchedEffect
        val first = backImgs == null
        fadeMs = if (app.browsing()) (when (settings.slideAnim) { "apple" -> 560; "slide" -> 420; else -> 220 }) else settings.transitionMs
        val dm = ctx.resources.displayMetrics
        val src = fullBmp
        // Slide: the side shade must stay put while pictures move, so it is drawn on top instead of baked in.
        val slide = settings.slideAnim != "fade"
        val bk = (if (slide) "$u|slide" else u) + "|" + settings.pageScale + "|" + settings.memTier
        val baked = BakeCache.get(bk, src) ?: withContext(Dispatchers.Default) { bakeBackdrop(src, (dm.widthPixels * settings.pageScale).toInt(), (dm.heightPixels * settings.pageScale).toInt(), !slide) }.also { BakeCache.put(bk, src, it) }
        if (GlassHalf.peek(baked) == null) withContext(Dispatchers.Default) { GlassHalf.of(baked) }
        if (com.glasslauncher.tv.data.Glog.timing) com.glasslauncher.tv.data.Glog.i("Slide ready in ${android.os.SystemClock.uptimeMillis() - t0} ms (${u.substringAfterLast('/')})")
        // Page and glass fade in the same frames with the same timing, so the bar never runs ahead.
        prevImgs = backImgs
        prevBackdrop = shownBackdrop
        if (next != null) backImgs = next
        slideDir = if (slide && !first && prevBackdrop != null) SlideDir.dir else 0
        if (!first && next != null) mix.snapTo(0f)
        shownBackdrop = baked
        shownUrl.value = u
        try {
            if (first || next == null) mix.snapTo(1f)
            else mix.animateTo(1f, tween(fadeMs, easing = when {
                slideDir == 0 -> androidx.compose.animation.core.LinearEasing
                // Apple TV: a quick start that settles slowly.
                settings.slideAnim == "apple" -> androidx.compose.animation.core.CubicBezierEasing(0.22f, 0.0f, 0.0f, 1.0f)
                else -> FastOutSlowInEasing
            }))
        } finally {
            // Also when this fade is cut short by the next slide: never leave the glass half faded with the
            // old layer underneath (every glass surface would then be drawn twice, translucent, on every frame).
            prevImgs = null
            prevBackdrop = null
            slideDir = 0
            withContext(kotlinx.coroutines.NonCancellable) { mix.snapTo(1f) }
        }
        upgrade?.let { d -> bmpOf(d.await())?.let { hi ->
            val dm = ctx.resources.displayMetrics
            val b = withContext(Dispatchers.Default) { bakeBackdrop(hi, (dm.widthPixels * settings.pageScale).toInt(), (dm.heightPixels * settings.pageScale).toInt(), !slide).also { GlassHalf.of(it) } }
            fadeMs = 0; shownBackdrop = b
        } }
    }
    // Glass like glass: a short ripple after every move in the dock.
    val wobble = remember { androidx.compose.animation.core.Animatable(0f) }
    val kick: () -> Unit = {
        if (settings.dockStyle == "water" || settings.tileStyle == "water") scope.launch {
            wobble.snapTo(1f)
            wobble.animateTo(0f, tween(700, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
        }
    }
    LaunchedEffect(app.homeRev.intValue) {
        folderOpen = false; folderMoving = null; pickerFor = null; dialog = null; movingPkg = null; expanded = false
        if (app.homeRev.intValue > 0) menuOpen = false
    }
    LaunchedEffect(app.requestSettingsRev.intValue) {
        val c = app.requestSettings.intValue
        when {
            c == 100 -> { onScreen(Screen.Home); menuOpen = true; app.requestSettings.intValue = -1 }
            c == 101 -> { menuOpen = false; onScreen(Screen.Library); app.requestSettings.intValue = -1 }
            c >= 0 -> { settingsCat = c; onScreen(Screen.Settings); app.requestSettings.intValue = -1 }
        }
    }
    LaunchedEffect(app.requestFolder.value) { if (app.requestFolder.value) { folderOpen = true; app.requestFolder.value = false } }

    LaunchedEffect(screen) {
        if (screen == Screen.Home) { delay(400); runCatching { firstTile.requestFocus() } }
    }
    BackHandler(enabled = screen == Screen.Home && movingPkg != null) { movingPkg = null }
    BackHandler(enabled = screen == Screen.Home && movingPkg == null && expanded && !folderOpen) { runCatching { firstTile.requestFocus() } }
    BackHandler(enabled = settings.backSaver && screen == Screen.Home && movingPkg == null && !expanded && !folderOpen && pickerFor == null) {
        runCatching { ctx.startActivity(android.content.Intent(ctx, com.glasslauncher.tv.saver.ScreensaverActivity::class.java)) }
    }

    val onMoveKey: (KeyEvent) -> Boolean = { ev ->
        val p = movingPkg
        if (p == null) false
        else when {
            ev.isSelect -> { if (ev.type == KeyEventType.KeyUp) movingPkg = null; true }
            ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> {
                if (ev.type == KeyEventType.KeyDown) {
                    val delta = when (ev.key) {
                        Key.DirectionLeft -> -1; Key.DirectionRight -> 1
                        Key.DirectionUp -> -DOCK_COLUMNS; else -> DOCK_COLUMNS
                    }
                    if (p == FOLDER) {
                        val n = app.apps.ordered().size
                        val cur = settings.folderPos.let { if (it < 0 || it > n) n else it }
                        settings.folderPos = (cur + delta).coerceIn(0, n)
                    } else app.apps.moveVisible(p, delta)
                }
                true
            }
            else -> false
        }
    }

    val showMenu: (AppEntry, Boolean) -> Unit = { e, inFolder ->
        dialog = appMenu(
            app, e, ctx,
            startMove = if (inFolder) ({ folderMoving = e.pkg }) else ({ movingPkg = e.pkg }),
            pickIcon = { pickerFor = e },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val rootW = with(density) { maxWidth.roundToPx() }
        val rootH = with(density) { maxHeight.roundToPx() }
        val s = maxWidth.value / 960f
        val screenHdp = maxHeight.value
        // The veil (the page blurred and dark) comes once the dock has (almost) opened: animating both at once
        // doubled what the TV GPU drew per frame and the dock opened at about 30 fps.
        // It stays on while the Apps folder is open and as it closes: it went off under the folder, so going in and
        // coming out showed the sharp title picture for a moment before it blurred again.
        var folderLeft by remember { mutableStateOf(0L) }
        LaunchedEffect(folderOpen) { if (!folderOpen && folderLeft != 0L) { delay(500); folderLeft = 0L } }
        if (folderOpen) folderLeft = 1L
        val veilIn = expanded || folderOpen || folderLeft != 0L
        // Comes in just after the dock has grown (both at once made the opening stutter on the TV); straight away
        // when it's already up (the folder).
        val veil = animateFloatAsState(if (veilIn) 1f else 0f, if (veilIn) tween(150, delayMillis = 110) else tween(100), label = "veil")
        val blur = veil
        var dockHasFocus by remember { mutableStateOf(false) }
        val dockShownTarget = !settings.dockHide || dockHasFocus || expanded || folderOpen
        val dockShown = animateFloatAsState(if (dockShownTarget) 1f else 0f, tween(if (dockShownTarget) 200 else 260, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "dockShown")
        // Where the open (and settled) dock covers the page; empty while it moves.
        val dockCover = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        // Clear glass shows the page picture itself (full resolution, scrims included); the small
        // GlassCache copy is only the fallback before the first picture is on screen.
        val pageSharp = if (PageGlass.on.value) GlassHalf.peek(shownBackdrop) else null
        val env = GlassEnv(backImgs?.first, pageSharp ?: backImgs?.second, backImgs?.third, rootW, rootH, settings.glassTint, settings.refraction, 1f, wobble.asState(),
            prevImgs?.first, if (prevImgs != null) (GlassHalf.peek(prevBackdrop) ?: prevImgs?.second) else null, mix.asState(), veil, sharpIsPage = pageSharp != null,
            slide = slideDir, slideKind = if (settings.slideAnim == "apple") SLIDE_APPLE else SLIDE_PUSH,
            pageNoSideShade = settings.slideAnim != "fade", blur = blur)
        val radius = settings.cornerRadius
        val metrics = remember(s, maxWidth, settings.dockScale) { DockMetrics(s, maxWidth.value, settings.dockScale) }

        CompositionLocalProvider(LocalGlassEnv provides env, LocalScale provides s) {
            // Nothing under the folder is drawn: it covers the whole screen (the TV GPU is the bottleneck).
            // Backdrop and scrims are flattened into one cached GPU layer: moving focus in the dock then
            // composites a single texture instead of redrawing the photo and both gradients every frame.
            // The scrims are baked into the picture (bakeBackdrop): a slide change draws two flat pictures and
            // nothing else, which keeps the crossfade smooth on TV graphics chips.
            // Under the fully open veil (an opaque picture) the backdrop isn't drawn at all.
            val veilCovers = backImgs?.third != null
            // Under the Library (once it covers the whole screen) the title picture isn't drawn either: the TV GPU
            // painted every pixel four times there and the Library's scrolling lost frames.
            val libCovers = remember { mutableStateOf(false) }
            if (!folderOpen && DebugHide.intValue and 1 == 0 && !VideoFrames.showing.value) Box(Modifier.fillMaxSize().graphicsLayer { alpha = if ((veilCovers && blur.value > 0.995f) || libCovers.value) 0f else 1f }) {
                if (settings.slideAnim != "fade") SlideBackdrop(prevBackdrop, shownBackdrop, mix, slideDir,
                    if (settings.slideAnim == "apple") SLIDE_APPLE else SLIDE_PUSH)
                else FadeBackdrop(prevBackdrop, shownBackdrop, mix)
            }
            // Screensaver as background: one of its aerial videos (your links and / or Apple's feed).
            val aerialKey = settings.saverSources.joinToString() + settings.saverQuality + settings.saverPick + settings.saverMine
            var lastAerialKey by remember { mutableStateOf(aerialKey) }
            LaunchedEffect(settings.bgMode, aerialKey) {
                // The screensaver's video choice changed: pick again from the new list.
                if (aerialKey != lastAerialKey) { lastAerialKey = aerialKey; CustomBackground.aerial.value = null }
                if (settings.bgMode == "aerial" && CustomBackground.aerial.value == null)
                    CustomBackground.aerial.value = withContext(Dispatchers.IO) {
                        runCatching { com.glasslauncher.tv.saver.AerialSource.playlist(app).also { CustomBackground.aerials = it }.let { CustomBackground.firstLive(it) } }.getOrNull()
                    }
                        .also { com.glasslauncher.tv.data.Glog.i("Screensaver background: ${it ?: "no aerial videos (screensaver links / Apple feed)"}") }
            }
            val videoSrc = when (settings.bgMode) { "video" -> settings.bgVideo.ifBlank { null }; "aerial" -> CustomBackground.aerial.value; else -> null }
            if (videoSrc != null && !folderOpen) {
                val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
                val state by life.currentStateFlow.collectAsState()
                // Back from the system screensaver / another app: a fresh player and surface (the old surface
                // came back black).
                var resumes by remember { mutableIntStateOf(0) }
                androidx.compose.runtime.DisposableEffect(life) {
                    // Only a real return (after ON_STOP): adding the observer replays ON_START too.
                    var stopped = false
                    val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) stopped = true
                        if (e == androidx.lifecycle.Lifecycle.Event.ON_START && stopped) { stopped = false; resumes++ }
                    }
                    life.addObserver(o); onDispose { life.removeObserver(o) }
                }
                listOf(settings.dockStyle, settings.tileStyle, settings.menuStyle).let { st ->
                    VideoFrames.wantSharp = st.any { it.startsWith("water") }
                    VideoFrames.wantSoft = st.any { it == "liquid" || it == "clear" }
                }
                androidx.compose.runtime.key(videoSrc, resumes) {
                    VideoBackground(videoSrc, playing = screen == Screen.Home && state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && DebugHide.intValue and 4096 == 0,
                        onError = { if (settings.bgMode == "aerial") CustomBackground.nextAerial() },
                        onDone = if (settings.bgMode == "aerial" && settings.bgShuffle && CustomBackground.aerials.size > 1) ({ CustomBackground.nextAerial(ended = true) }) else null,
                        busy = { System.currentTimeMillis() - app.interaction.longValue < 600 })
                }
            }
            // Dock open: the whole page blurs and darkens (the glass draws the same veil, see LiquidSurface).
            val veilOn by remember { androidx.compose.runtime.derivedStateOf { veil.value > 0.01f } }
            if (veilOn && !folderOpen && DebugHide.intValue and 32 == 0) {
                // With a video behind, the open dock only darkens it (a still blurred frame over it looked frozen).
                // One pass: the blurred picture comes already darkened (a black layer on top cost the TV GPU a
                // whole extra screen every frame of the opening).
                // Dimming first (a flat colour, cheap), then the darkened blurred picture over it once the dock is open;
                // fully blurred, the dimming and the backdrop under it aren't drawn at all.
                val img = if (!VideoFrames.showing.value && DebugHide.intValue and 32768 == 0) backImgs?.third else null
                if (img == null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }.background(Color.Black.copy(alpha = VEIL_DIM)))
                // Not drawn where the open dock covers it (its glass is opaque): on the TV GPU every pixel drawn twice counts.
                if (img != null) {
                    val brush = remember(img, rootW, rootH) {
                        val bw = img.width.toFloat(); val bh = img.height.toFloat()
                        val sc = maxOf(rootW / bw, rootH / bh)
                        val sh = android.graphics.BitmapShader(img.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                        sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(sc, sc); postTranslate((rootW - bw * sc) / 2f, (rootH - bh * sc) / 2f) })
                        androidx.compose.ui.graphics.ShaderBrush(sh)
                    }
                    val cornerPx = with(density) { (radius * s).dp.toPx() }
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = blur.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }) {
                        val d = dockCover.value
                        fun part(l: Float, t: Float, r: Float, b: Float) { if (r > l && b > t) drawRect(brush, androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), colorFilter = VeilFilter) }
                        if (d.isEmpty || DebugHide.intValue and 524288 != 0) part(0f, 0f, size.width, size.height)
                        else {
                            val t = d.top + cornerPx; val b = d.bottom - cornerPx
                            part(0f, 0f, size.width, t); part(0f, b, size.width, size.height)
                            part(0f, t, d.left, b); part(d.right, t, size.width, b)
                        }
                    }
                }
            }

            // Down from the dock: the page scrolls up into the Library like the Apple TV home page (the home screen
            // moves up and out, the Library comes up from below); Up from its top row scrolls back to the dock.
            val libScroll = animateFloatAsState(if (screen == Screen.Library && libFromDock) 1f else 0f,
                tween(440, easing = androidx.compose.animation.core.CubicBezierEasing(0.35f, 0.0f, 0.15f, 1f)), label = "libScroll")
            LaunchedEffect(screen) {
                if (screen != Screen.Library && libFromDock) { androidx.compose.runtime.snapshotFlow { libScroll.value }.first { it < 0.01f }; libFromDock = false }
            }
            // Fully covered by settings or the folder: stop drawing it at all.
            val homeAlphaState = animateFloatAsState(if ((screen == Screen.Home || (screen == Screen.Library && libFromDock)) && !folderOpen) 1f else 0f, tween(180), label = "homeAlpha")
            val libSliding by remember { androidx.compose.runtime.derivedStateOf { libScroll.value > 0.001f && libScroll.value < 0.999f } }
            // Frozen the whole time the Library is up, not only while it slides: the dock's glass kept the place it had
            // while scrolled out of view, so on the way back it showed the middle of the picture for a moment.
            // While it slides the glass keeps its picture (moving with the page); while the Library is fully up the
            // dock's place is measured as if the page weren't scrolled.
            val libFull by remember { androidx.compose.runtime.derivedStateOf { libScroll.value >= 0.999f } }
            val screenPx = with(LocalDensity.current) { androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp.dp.toPx() }
            androidx.compose.runtime.SideEffect { GlassMotion.set(libSliding, if (libFull) screenPx else 0f) }
            val libOpaque by remember(screen) { androidx.compose.runtime.derivedStateOf { screen == Screen.Library && (!libFromDock || libScroll.value >= 0.999f) } }
            androidx.compose.runtime.SideEffect { libCovers.value = libOpaque }
            // Once the Library covers the page the dock closes behind it, so coming back shows it closed (its dimmed
            // open look flashed dark for a moment on the way back).
            LaunchedEffect(libFull) { if (libFull) expanded = false }
            val homeVisible by remember { androidx.compose.runtime.derivedStateOf { homeAlphaState.value > 0.001f && libScroll.value < 0.999f } }
            // Kept composed under the Library (only not drawn): leaving the composition restarted the carousel, so
            // its backdrop slid in again when you came back. Not under Settings or the Apps folder: hidden, its dock
            // could still take the remote there (Right in Settings went to the dock behind instead of the options).
            if ((screen == Screen.Home && !folderOpen) || homePrep || libFromDock || libScroll.value > 0.001f || (homeVisible && screen != Screen.Settings)) Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = if (homeVisible || homePrep || (screen == Screen.Home && !folderOpen)) homeAlphaState.value else 0f; translationY = -libScroll.value * size.height }
                    .focusProperties { canFocus = screen == Screen.Home && pickerFor == null && !folderOpen && !menuOpen }
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (DebugHide.intValue and 2 != 0) 0f else 1f }) {
                    HeroSection(app, s, hidden = expanded || folderOpen, paused = screen != Screen.Home, metrics = metrics, shownUrl = shownUrl, dockShown = dockShown,
                        heroFocus = heroFocus, firstTile = firstTile, onBackdrop = { backdrop = it },
                        onCustomUp = {
                            if (settings.menuMode == "auto") { menuClosedAt = 0L; menuOpen = true }
                            else scope.launch { delay(16); runCatching { gearReq.requestFocus() } }
                        })
                }
                if (DebugHide.intValue and 4 == 0) TopBar(app, s, gearReq, onSettings = { onScreen(Screen.Settings) }, onMenu = { pressed -> if (!menuOpen && (pressed || android.os.SystemClock.uptimeMillis() - menuClosedAt > 400)) menuOpen = true }, modifier = Modifier.fillMaxWidth().align(Alignment.TopStart))
                // Your own background: the dock may sit in the middle or as a column on the left.
                val customPos = if (settings.bgMode != "carousel") settings.dockPos else "bottom"
                Dock(
                    vertical = customPos == "left", centered = customPos == "center",
                    onLibrary = { libFromDock = true; onScreen(Screen.Library) },
                    app = app, s = s, metrics = metrics, screenH = screenHdp, radius = radius,
                    expanded = expanded, onExpanded = { expanded = it },
                    movingPkg = movingPkg, onMoveKey = onMoveKey, firstTile = firstTile, heroFocus = heroFocus,
                    folderFocus = folderFocus, tileFocus = tileFocus, onMove = kick,
                    onMenu = { showMenu(it, false) },
                    onFolder = { folderOpen = true },
                    onFolderMenu = { dialog = MenuDialog("Apps", listOf(
                        "Move" to { movingPkg = FOLDER },
                        "Hide from the dock" to {
                            settings.showFolder = false
                            scope.launch { delay(80); runCatching { firstTile.requestFocus() } }
                            android.widget.Toast.makeText(ctx, "Apps folder hidden. Show it again: Settings → Dock & icons", android.widget.Toast.LENGTH_LONG).show()
                        },
                    )) },
                    modifier = Modifier.align(when (customPos) { "center" -> Alignment.Center; "left" -> Alignment.CenterStart; else -> Alignment.BottomCenter })
                        .onFocusChanged { dockHasFocus = it.hasFocus }
                        .graphicsLayer {
                            alpha = if (DebugHide.intValue and 8 != 0) 0f else 1f
                            translationY = (1f - dockShown.value) * (metrics.collapsedH + metrics.bottomMargin + 6 * s) * this.density
                            if (customPos == "left") { translationY = 0f; translationX = -(1f - dockShown.value) * (metrics.cellW + 60 * s) * this.density }
                        },
                    onCover = { dockCover.value = it },
                )
            }
            // The folder fades out over the home screen instead of vanishing: the dock's glass is drawn again under it
            // first (it showed black for a couple of frames as the folder closed).
            androidx.compose.animation.AnimatedVisibility(folderOpen, enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.fadeOut(tween(220, delayMillis = 60))) {
                FolderOverlay(
                    app, s, metrics, radius, screenHdp,
                    moving = folderMoving, onMoveDone = { folderMoving = null }, blocked = pickerFor != null,
                    onMenu = { showMenu(it, true) },
                    onClose = {
                        folderMoving = null
                        folderOpen = false
                        scope.launch { delay(150); runCatching { folderFocus.requestFocus() } }
                    },
                )
            }
            if (screen == Screen.Home && !folderOpen) UpdateBanner(app, s, Modifier.align(Alignment.TopEnd).padding(end = (34 * s).dp))
            if (screen == Screen.Home && menuOpen) SideMenu(
                app, s, modifier = Modifier.align(Alignment.TopEnd),
                onClose = {
                    menuClosedAt = android.os.SystemClock.uptimeMillis()
                    menuOpen = false
                    // With your own background there is no carousel to go back to (its stand-in passes focus up to
                    // the settings button, which opened the menu again): back to the dock.
                    val custom = when (settings.bgMode) { "image" -> settings.bgImage.isNotBlank(); "video" -> settings.bgVideo.isNotBlank(); "aerial" -> true; else -> false }
                    scope.launch { delay(30); runCatching { (if (custom) firstTile else heroFocus).requestFocus() } }
                },
                onLibrary = { menuClosedAt = android.os.SystemClock.uptimeMillis(); menuOpen = false; onScreen(Screen.Library) },
                onSettings = { menuClosedAt = android.os.SystemClock.uptimeMillis(); menuOpen = false; onScreen(Screen.Settings) },
            )
            if (screen == Screen.Settings) SettingsScreen(app, settingsCat) { settingsCat = 0; onScreen(Screen.Home) }
            // Ready before you press Down: the Library is built (off screen, not drawn) once the dock is selected,
            // so the page scrolls up at once instead of waiting for it to be laid out.
            // Built a few seconds after the home screen is up, not the moment the dock is selected (that made the
            // first move down into the dock stutter).
            LaunchedEffect(settings.libraryDown) { if (settings.libraryDown && !libWarm) { delay(5_000); libWarm = true } }
            // Read through derivedStateOf: reading the animation itself here recomposed the whole home screen every frame.
            val libMoving by remember { androidx.compose.runtime.derivedStateOf { libScroll.value > 0.001f } }
            if (screen == Screen.Library || libMoving || (libWarm && settings.libraryDown && screen == Screen.Home)) Box(Modifier.fillMaxSize().graphicsLayer {
                val on = screen == Screen.Library || libScroll.value > 0.001f
                if (!on) { alpha = 0f; translationY = size.height }
                // Also while it scrolls back down after Up (libFromDock is cleared at the very end): without this the
                // Library flashed in place over the home screen for a frame.
                else if (libFromDock || screen != Screen.Library) translationY = (1f - libScroll.value) * size.height
            }) {
                val back: () -> Unit = {
                    // Up pressed quickly many times to reach the top of the Library stops at the dock, instead of
                    // carrying on to the carousel and the menu.
                    NavGate.hold(android.view.KeyEvent.KEYCODE_DPAD_UP, 550)
                    if (libFromDock) scope.launch {
                        homePrep = true
                        repeat(3) { androidx.compose.runtime.withFrameNanos { } }
                        onScreen(Screen.Home)
                        delay(40); runCatching { firstTile.requestFocus() }
                        delay(600); homePrep = false
                    } else onScreen(Screen.Home)
                }
                LibraryScreen(app, onTopUp = if (libFromDock) back else null, active = screen == Screen.Library) { back() }
            }
            pickerFor?.let { e ->
                IconPicker(
                    app, e,
                    onPhone = { settingsCat = PHONE_CATEGORY; onScreen(Screen.Settings) },
                    onClose = {
                        val back = tileFocus[e.pkg] ?: firstTile
                        pickerFor = null
                        scope.launch { delay(200); runCatching { back.requestFocus() } }
                    },
                )
            }
            DialogHost(dialog) { dialog = null }
        }
    }
}

private fun appMenu(
    app: GlassApp, e: AppEntry, ctx: android.content.Context,
    startMove: (() -> Unit)?, pickIcon: () -> Unit,
): DialogState {
    val hidden = e.pkg in app.settings.hiddenApps
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    if (hidden) opts += "Open" to { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } }
    opts += "App info" to {
        runCatching {
            ctx.startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(ctx, "App info is not available on this device") }
    }
    if (startMove != null) opts += "Move" to startMove
    opts += "Change icon" to pickIcon
    opts += (if (hidden) "Show in dock" else "Hide") to { app.apps.toggleHidden(e.pkg) }
    return MenuDialog(e.label, opts)
}

/** The picture a title shows: the Settings choice, or the one picked for it with a long press. */
private fun withArtwork(i: HeroItem, choice: String, pick: String?): HeroItem {
    val chosen = pick?.takeIf { it.isNotBlank() } ?: when (choice) {
        "second" -> i.gallery.getOrNull(1)
        "best" -> i.bestArt.takeIf { it.isNotBlank() }
        "addon" -> i.addonArt.takeIf { it.isNotBlank() }
        else -> null
    }
    if (chosen == null || chosen == i.background) return i
    return i.copy(background = chosen, gallery = (listOf(chosen) + i.gallery).distinct())
}

/** Every picture a long press cycles through: TMDB backdrops, the highest-resolution one, the add-on's own. */
private fun artOptions(i: HeroItem): List<String> =
    (listOf(i.background) + i.gallery + listOf(i.bestArt, i.addonArt)).filter { it.isNotBlank() }.distinct()

/** Play triangle drawn as a path: crisp at any size, optically centred. */
@Composable
private fun PlayGlyph(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width; val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.14f, h * 0.06f); lineTo(w * 0.94f, h * 0.5f); lineTo(w * 0.14f, h * 0.94f); close()
        }
        drawPath(path, color)
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.10f,
            join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** The last few baked backdrops (~8 MB each), keyed by picture and source bitmap. */
/**
 * Half-size copy of a baked page picture, for the clear glass: sharp (2x, not 4x, enlargement like the old
 * 480 px copy) and lined up exactly with the page, at a quarter of the texture reading a full-size copy costs.
 */
/** Test switch (adb extra page_glass): clear glass from the page picture, or from the small GlassCache copy. */
object PageGlass { val on = mutableStateOf(true) }

private object GlassHalf {
    private val map = java.util.WeakHashMap<ImageBitmap, ImageBitmap>()
    @Synchronized fun peek(b: ImageBitmap?): ImageBitmap? = b?.let { map[it] }
    fun of(b: ImageBitmap): ImageBitmap {
        peek(b)?.let { return it }
        val a = b.asAndroidBitmap()
        val h = immutable(android.graphics.Bitmap.createScaledBitmap(a, a.width / 2, a.height / 2, true)).asImageBitmap()
        synchronized(this) { map[b] = h }
        return h
    }
}

/** Low memory: drop the prepared backdrops (the one on screen stays referenced by the page). */
fun trimPageCaches() { BakeCache.clear() }

private object BakeCache {
    /** How many prepared backdrops to keep (by memory tier: 2 / 3 / 4). */
    @Volatile var max = 4
    @Synchronized fun clear() = map.clear()
    private val map = object : LinkedHashMap<String, Pair<Int, ImageBitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Int, ImageBitmap>>?) = size > max
    }
    @Synchronized fun get(url: String, src: ImageBitmap): ImageBitmap? =
        map[url]?.takeIf { it.first == src.width * 10000 + src.height }?.second
    @Synchronized fun put(url: String, src: ImageBitmap, baked: ImageBitmap) { map[url] = (src.width * 10000 + src.height) to baked }
}

/**
 * The backdrop scaled to the screen (centre crop) with the two scrims painted in, as one bitmap.
 * Done once per picture on a background thread; drawing it is then a single full-screen copy.
 */
@Volatile private var backdrop565 = false
/** "1280 enhanced": pictures smaller than the screen are sharpened after scaling up. */
@Volatile private var backdropSharpen = false

/**
 * Unsharp mask (amount 0.55, a 3-pixel box blur), on the CPU, once per picture as it is prepared: a 1280 px picture
 * scaled to the screen looks as crisp as a bigger one, with no halos.
 */
private fun sharpen(b: android.graphics.Bitmap) {
    if (b.config != android.graphics.Bitmap.Config.ARGB_8888) return
    val w = b.width; val h = b.height
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    // Horizontal then vertical box blur of radius 1, per channel (packed).
    for (y in 0 until h) { val o = y * w
        for (x in 0 until w) {
            val a = px[o + maxOf(0, x - 1)]; val c = px[o + x]; val d = px[o + minOf(w - 1, x + 1)]
            tmp[o + x] = ((((a shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255)) / 3 shl 16) or
                ((((a shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255)) / 3 shl 8) or (((a and 255) + (c and 255) + (d and 255)) / 3)
        } }
    for (y in 0 until h) { val up = maxOf(0, y - 1) * w; val o = y * w; val dn = minOf(h - 1, y + 1) * w
        for (x in 0 until w) {
            val a = tmp[up + x]; val c = tmp[o + x]; val d = tmp[dn + x]
            val br = (((a shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255)) / 3
            val bg = (((a shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255)) / 3
            val bb = ((a and 255) + (c and 255) + (d and 255)) / 3
            val p = px[o + x]
            val r = (((p shr 16) and 255) * 155 - br * 55) / 100; val g = (((p shr 8) and 255) * 155 - bg * 55) / 100; val bl = ((p and 255) * 155 - bb * 55) / 100
            px[o + x] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
        } }
    b.setPixels(px, 0, w, 0, 0, w, h)
}

private fun bakeBackdrop(src: ImageBitmap, w: Int, h: Int, hScrim: Boolean = true): ImageBitmap {
    var bmp = src.asAndroidBitmap()
    if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    // Performance (small memory tier): 16-bit colour, dithered. Half the memory and half the GPU reads per frame.
    val out = android.graphics.Bitmap.createBitmap(w, h, if (backdrop565) android.graphics.Bitmap.Config.RGB_565 else android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val scale = maxOf(w / bmp.width.toFloat(), h / bmp.height.toFloat())
    val dw = bmp.width * scale; val dh = bmp.height * scale
    val dst = android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    c.drawBitmap(bmp, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG))
    if (backdropSharpen && bmp.width < w * 0.9f) runCatching { sharpen(out) }
    val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.62f, 0f, 0xE0000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
    if (hScrim) c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), p)
    p.shader = android.graphics.LinearGradient(0f, h * 0.58f, 0f, h.toFloat(), 0x00000000, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
    c.drawRect(0f, h * 0.58f, w.toFloat(), h.toFloat(), p)
    // Fully covered: marked opaque, so it is drawn without blending.
    out.setHasAlpha(false)
    return immutable(out).asImageBitmap()
}

/**
 * The GPU keeps an immutable bitmap's texture; a mutable one may be uploaded again whenever a surface that
 * draws it is re-recorded (every glass tile, on every focus move), which doubled the dock's GPU time.
 */
private fun immutable(b: android.graphics.Bitmap): android.graphics.Bitmap {
    val r = if (b.isMutable) b.copy(b.config ?: android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle() } else b
    r.prepareToDraw()
    return r
}

/** Which way the next slide moves (set by the remote / auto-play just before the index changes). */
object SlideDir { @Volatile var dir = 1 }

/**
 * Slide animation: the old picture leaves to one side while the new one comes in from the other, both moved
 * as cached layers (no redraw). The side shade is not baked into these pictures; it is drawn once on top and
 * stays still, so no dark band travels across the screen.
 */
@Composable
private fun SlideBackdrop(prev: ImageBitmap?, cur: ImageBitmap?, mix: androidx.compose.animation.core.Animatable<Float, *>, dir: Int, kind: Int) {
    // One canvas: both pictures drawn straight (no layers), the old one darkened by a colour filter in the
    // same pass instead of a full-screen rectangle on top.
    if (cur == null && prev == null) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF110E26), Color(0xFF3A1B52), Color(0xFF0D1A3A)))))
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        val W = size.width; val H = size.height
        val dst = androidx.compose.ui.unit.IntSize(W.toInt(), H.toInt())
        fun img(b: ImageBitmap, dx: Float, dim: Float = 0f) = drawImage(
            b, dstOffset = androidx.compose.ui.unit.IntOffset(dx.roundToInt(), 0), dstSize = dst,
            filterQuality = if (b.width < W - 8) androidx.compose.ui.graphics.FilterQuality.Low else androidx.compose.ui.graphics.FilterQuality.None,
            colorFilter = if (dim > 0.002f) androidx.compose.ui.graphics.ColorFilter.tint(Color.Black.copy(alpha = dim), androidx.compose.ui.graphics.BlendMode.SrcAtop) else null,
        )
        if (prev == null || dir == 0 || cur == null) { (cur ?: prev)?.let { img(it, 0f) } }
        else {
            val f = slideFrame(kind, dir, mix.value, W)
            // Each picture only where it shows: nothing is drawn twice, the TV GPU reads each pixel once.
            clipRect(left = if (f.curRight) -1e5f else f.edge, right = if (f.curRight) f.edge else 1e5f) { img(prev, f.prevShift, f.dim) }
            clipRect(left = if (f.curRight) f.edge else -1e5f, right = if (f.curRight) 1e5f else f.edge) { img(cur, f.curShift) }
            // A soft shadow on the old picture along the moving edge gives the new one some depth.
            if (kind == SLIDE_APPLE) {
                val sw = W * 0.05f; val a = 0.35f * (1f - mix.value); val e = f.edge
                if (f.curRight) drawRect(Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = a), startX = e - sw, endX = e),
                    topLeft = Offset(e - sw, 0f), size = androidx.compose.ui.geometry.Size(sw, H))
                else drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = a), 1f to Color.Transparent, startX = e, endX = e + sw),
                    topLeft = Offset(e, 0f), size = androidx.compose.ui.geometry.Size(sw, H))
            }
        }
        // The side shade stays still on top (a pre-painted picture: a live gradient this size was costly).
        drawImage(Scrims.side, dstSize = dst, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
    }
}

/**
 * Crossfade driven by the very same [mix] value as the glass: the old picture stays fully drawn and the new
 * one's opacity goes 0 → 1 on top, exactly what every glass surface does. (Compose's Crossfade also fades the
 * old picture out, so mid-fade the page was darker than the glass and the two looked out of step.)
 */
@Composable
private fun FadeBackdrop(prev: ImageBitmap?, cur: ImageBitmap?, mix: androidx.compose.animation.core.Animatable<Float, *>) {
    // Drawn 1:1 normally; prepared at 2/3 size (Performance) it is scaled up, so it needs filtering.
    val fq = if ((cur ?: prev)?.width?.let { it < 1800 } == true) androidx.compose.ui.graphics.FilterQuality.Low else androidx.compose.ui.graphics.FilterQuality.None
    if (prev != null) Image(prev, null, contentScale = ContentScale.FillBounds, filterQuality = fq, modifier = Modifier.fillMaxSize())
    if (cur != null) Image(cur, null, contentScale = ContentScale.FillBounds, filterQuality = fq,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (prev != null) mix.value else 1f; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha })
    else if (prev == null) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF110E26), Color(0xFF3A1B52), Color(0xFF0D1A3A)))))
}


// ───────────────────────────── hero / carousel ─────────────────────────────

@Composable
private fun HeroSection(
    app: GlassApp, s: Float, hidden: Boolean, paused: Boolean, metrics: DockMetrics, dockShown: androidx.compose.runtime.State<Float> = androidx.compose.runtime.mutableFloatStateOf(1f),
    heroFocus: FocusRequester, firstTile: FocusRequester, onBackdrop: (String?) -> Unit,
    shownUrl: androidx.compose.runtime.State<String?> = androidx.compose.runtime.mutableStateOf(null),
    onCustomUp: () -> Unit = {},
) {
    val settings = app.settings
    val ctx = LocalContext.current
    val catalogItems by app.catalogs.items.collectAsState()
    val cwItems by app.cw.items.collectAsState()
    val enrichedRaw by app.catalogs.enrichedItems.collectAsState()
    val status by app.catalogs.status.collectAsState()
    var index by remember { mutableIntStateOf(0) }
    var focused by remember { mutableStateOf(false) }
    val mode = settings.continueMode
    val lead = settings.cwLead
    val items = remember(catalogItems, cwItems, mode, lead) {
        val cw = if (mode == "off") emptyList() else cwItems
        val keys = cw.map { it.key }.toSet()
        val rest = catalogItems.filterNot { it.key in keys }
        when {
            cw.isEmpty() -> catalogItems
            else -> buildList {
                // "At the start": the first [lead] titles open the carousel, the others follow one after
                // every four catalog titles. "Mixed": all of them spread that way.
                val head = if (mode == "mixed") 0 else if (lead <= 0) cw.size else lead.coerceAtMost(cw.size)
                addAll(cw.take(head))
                var c = head
                rest.forEachIndexed { i, it -> if (i % 4 == 3 && c < cw.size) add(cw[c++]); add(it) }
                while (c < cw.size) add(cw[c++])
            }
        }
    }
    // TMDB enrichment is cached per title; keep the progress of Continue Watching items on top of it.
    val enriched = enrichedRaw
    // Back from Nuvio: open the carousel on what was just watched.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifeState by lifecycle.currentStateFlow.collectAsState()
    val resumed = lifeState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val jumpRev by app.cw.jumpRev.collectAsState()
    var jumpedRev by remember { mutableIntStateOf(jumpRev) }
    LaunchedEffect(resumed, jumpRev, items) {
        if (resumed && jumpRev != jumpedRev && settings.continueJump && mode != "off") {
            val k = app.cw.lastWatched.value
            val i = items.indexOfFirst { it.key == k }
            if (i >= 0) index = i
            jumpedRev = jumpRev
        }
    }

    val raw = if (items.isEmpty()) null else items[index.mod(items.size)]
    val target = raw?.let { r -> enriched[r.key]?.copy(progress = r.progress, sub = r.sub) ?: r }
        ?.let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) }

    // The slide changes once its backdrop and logo are decoded (usually instant: prefetched). While you are
    // browsing with the remote it never waits long, so the text keeps up with every press.
    var item by remember { mutableStateOf<HeroItem?>(null) }
    LaunchedEffect(target?.key, target?.background, target?.logo) {
        val t = target
        if (t == null) { item = null; return@LaunchedEffect }
        if (item != null) {
            val loader = SingletonImageLoader.get(ctx)
            // Browsing: the text follows every press at once; the picture follows as soon as it is ready.
            if (!app.browsing()) kotlinx.coroutines.withTimeoutOrNull(2500L) {
                kotlinx.coroutines.coroutineScope {
                    if (t.background.isNotBlank()) launch { loader.execute(imgReq(ctx, t.background)) }
                    if (t.background.isNotBlank()) launch { GlassCache.get(ctx, t.background, settings.blurRadius) }
                    if (t.logo.isNotBlank()) launch { loader.execute(imgReq(ctx, t.logo, 1000, 400)) }
                }
            }
        }
        item = t
    }
    // Apple TV style gallery: the title stays, its pictures change.
    val gallery = item?.let { i -> if (settings.heroGallery) i.gallery.take(settings.galleryShots) else emptyList() }.orEmpty()
    var shot by remember { mutableIntStateOf(0) }
    LaunchedEffect(item?.key) { shot = 0 }
    val shotMs = (settings.heroIntervalSec * 1000L).coerceAtLeast(4000L)
    LaunchedEffect(item?.key, gallery.size, hidden, paused, resumed) {
        if (gallery.size < 2 || hidden || paused || !resumed) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        while (true) {
            val next = gallery[(shot + 1).mod(gallery.size)]
            loader.enqueue(imgReq(ctx, next)); launch { GlassCache.get(ctx, next, settings.blurRadius) }
            delay(shotMs)
            if (shot + 1 >= gallery.size) break
            SlideDir.dir = 1
            shot++
        }
    }
    val shownBg = gallery.getOrNull(shot) ?: item?.background
    LaunchedEffect(shownBg, hidden) { onBackdrop(shownBg?.takeIf { it.isNotBlank() }) }
    // The text of a slide appears together with its picture (browsing with the remote: at once).
    var textItem by remember { mutableStateOf<HeroItem?>(null) }
    LaunchedEffect(item?.key, item?.logo) {
        val it0 = item
        if (it0 == null || textItem == null || app.browsing() || it0.background.isBlank()) { textItem = it0; return@LaunchedEffect }
        if (textItem?.key == it0.key) { textItem = it0; return@LaunchedEffect }
        kotlinx.coroutines.withTimeoutOrNull(1500) {
            androidx.compose.runtime.snapshotFlow { shownUrl.value }.first { u -> u == it0.background || u in it0.gallery }
        }
        textItem = it0
    }
    LaunchedEffect(index, items.size, settings.tmdbToken) {
        if (items.isEmpty()) return@LaunchedEffect
        val d = settings.prefetchDepth
        for (k in listOf(0) + (1..d + 1).flatMap { listOf(it, -it) }.take(d * 2)) app.catalogs.enrich(items[(index + k).mod(items.size)])
    }
    LaunchedEffect(index, items.size, enriched.size, settings.artChoice) {
        if (items.size < 2) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        // Wait until you stop on a slide: while you hold Right, preparing the slides around each one passed only
        // filled the download queue, and the picture you landed on waited behind them. Everything below runs in
        // this effect, so moving on cancels it and frees the downloads for the slide on screen.
        delay(if (app.browsing()) 350 else 60)
        // Both directions, a few slides deep, glass copies included.
        val depth = settings.prefetchDepth
        val ahead = (1..depth).flatMap { if (it <= (depth + 1) / 2) listOf(it, -it) else listOf(it) }
        // Downloads only (no decoding kept in memory), further ahead: fast browsing finds the pictures on disk.
        launch {
            delay(400)
            for (k in depth + 1..depth + 2) {
                val n = items[(index + k).mod(items.size)].let { withArtwork(enriched[it.key] ?: it, settings.artChoice, settings.artPick(it.key)) }
                if (n.background.isNotBlank()) loader.execute(imgReq(ctx, sized(n.background, if (settings.imageQuality == "high") "w1280" else "original"))
                    .newBuilder().memoryCachePolicy(coil3.request.CachePolicy.DISABLED).build())
            }
        }
        for (k in ahead) {
            val n = items[(index + k).mod(items.size)].let { withArtwork(enriched[it.key] ?: it, settings.artChoice, settings.artPick(it.key)) }
            if (n.background.isNotBlank()) {
                launch { loader.execute(imgReq(ctx, sized(n.background, "w1280"))) }
                if (kotlin.math.abs(k) <= 1 && settings.memTier > 0) launch {
                    val u = n.background
                    val r = loader.execute(imgReq(ctx, if (settings.imageQuality == "high") sized(u, "w1280") else u))
                    val b = (r as? SuccessResult)?.image?.toBitmap()?.asImageBitmap() ?: return@launch
                    val slide = settings.slideAnim != "fade"
                    val bk = (if (slide) "$u|slide" else u) + "|" + settings.pageScale + "|" + settings.memTier
                    if (BakeCache.get(bk, b) == null) {
                        val dm = ctx.resources.displayMetrics
                        val baked = withContext(Dispatchers.Default) { bakeBackdrop(b, (dm.widthPixels * settings.pageScale).toInt(), (dm.heightPixels * settings.pageScale).toInt(), !slide) }
                        BakeCache.put(bk, b, baked)
                        withContext(Dispatchers.Default) { GlassHalf.of(baked) }
                    }
                }
                launch { GlassCache.get(ctx, n.background, settings.blurRadius) }
            }
            // Logos decoded into memory ahead, so a title's name appears in the same frame as its picture.
            if (n.logo.isNotBlank() && GlassCache.logos.get(n.logo) == null && GlassCache.pinnedLogo[n.logo] == null) launch {
                (loader.execute(imgReq(ctx, n.logo, 1000, 400)) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()?.let { GlassCache.logos.put(n.logo, it) }
            }
        }
    }
    // Continue Watching stays in memory (picture, logo, glass) for as long as the title is in the list:
    // going back to it — even after browsing far, or with Home — never reloads anything.
    val cwKeys = remember(cwItems) { cwItems.map { it.key } }
    LaunchedEffect(cwKeys) { cwItems.forEach { app.catalogs.enrich(it) } }
    val cwShown = cwItems.map { c -> (enriched[c.key] ?: c).let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) } }
    LaunchedEffect(cwShown.map { it.background + it.logo }, settings.imageQuality, settings.blurRadius) {
        val loader = SingletonImageLoader.get(ctx)
        val high = settings.imageQuality == "high"
        val keep = mutableSetOf<String>()
        // Kept in memory: the first few only on smaller TVs (each full picture is a few MB).
        for (c in cwShown.take(when (settings.memTier) { 0 -> 2; 2 -> 10; else -> 5 })) {
            val bg = c.background
            if (bg.isNotBlank()) {
                keep += bg
                if (GlassCache.pinnedFull[bg] == null) (loader.execute(imgReq(ctx, if (high) sized(bg, "w1280") else bg)) as? SuccessResult)
                    ?.image?.toBitmap()?.asImageBitmap()?.let { GlassCache.pinnedFull[bg] = it }
                GlassCache.pin(ctx, bg, settings.blurRadius)
            }
            if (c.logo.isNotBlank()) {
                keep += c.logo
                if (GlassCache.pinnedLogo[c.logo] == null) (loader.execute(imgReq(ctx, c.logo, 1000, 400)) as? SuccessResult)
                    ?.image?.toBitmap()?.asImageBitmap()?.let { GlassCache.pinnedLogo[c.logo] = it }
            }
        }
        GlassCache.keepOnly(keep)
    }
    // Home pressed while on the home screen: back to the first slide (Continue Watching).
    val homeRev = app.homeRev.intValue
    var seenHome by remember { mutableIntStateOf(homeRev) }
    LaunchedEffect(homeRev) {
        if (homeRev != seenHome) {
            seenHome = homeRev
            index = 0
            delay(120); runCatching { heroFocus.requestFocus() }
        }
    }
    // Keeps playing while the carousel is selected; any Left/Right restarts the timer (index changes).
    LaunchedEffect(index, items.size, settings.heroAutoplay, settings.heroIntervalSec, hidden, paused, resumed) {
        if (settings.heroAutoplay && !hidden && !paused && resumed && items.size > 1) {
            val shots = item?.let { i -> if (settings.heroGallery) i.gallery.take(settings.galleryShots).size else 0 } ?: 0
            delay(settings.heroIntervalSec * 1000L * shots.coerceAtLeast(1))
            SlideDir.dir = 1
            index++
        }
    }

    val alpha by animateFloatAsState(if (hidden) 0f else 1f, tween(if (hidden) 150 else 220), label = "heroAlpha")
    val dotsOn = items.size > 1 && settings.dotsMode != "hidden" && (!settings.dotsAutoHide || focused)
    val dotsAlpha by animateFloatAsState(if (dotsOn) 1f else 0f, tween(300), label = "dotsAlpha")

    // Your own picture / video / the screensaver behind: the page is just that, no title, details or buttons.
    val customBg = when (settings.bgMode) { "image" -> settings.bgImage.isNotBlank(); "video" -> settings.bgVideo.isNotBlank(); "aerial" -> true; else -> false }
    // ModulateAlpha: no full-screen off-screen pass while it fades (the dock opening); the pieces barely overlap.
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }) {
        val shown = textItem
        if (customBg) {
            // Holds the dock's Up: opens the menu (or selects the settings button) straight away.
            Box(Modifier.align(Alignment.TopStart).padding(start = (48 * s).dp, top = (300 * s).dp).size(1.dp)
                .focusRequester(heroFocus)
                .onFocusChanged { if (it.isFocused) onCustomUp() }
                .focusable())
        } else if (shown != null) {
            HeroInfo(
                item = shown, app = app, s = s, focused = focused, heroFocus = heroFocus, firstTile = firstTile,
                onFocus = { focused = it },
                onPrev = { app.navAt = System.currentTimeMillis(); SlideDir.dir = -1; index-- }, onNext = { app.navAt = System.currentTimeMillis(); SlideDir.dir = 1; index++ },
                onOpen = { NuvioLaunch.open(ctx, settings, app.http, shown) },
                onLong = {
                    val base = enriched[shown.key] ?: shown
                    val options = artOptions(base)
                    if (options.size < 2) toast(ctx, "No other pictures for ${shown.name} (add a TMDB token for more)")
                    else {
                        val cur = options.indexOf(shown.background).coerceAtLeast(0)
                        val next = options[(cur + 1) % options.size]
                        settings.setArtPick(shown.key, next)
                        toast(ctx, "Picture ${(cur + 1) % options.size + 1} of ${options.size} · ${shown.name}  (hold OK again for the next)")
                    }
                },
                modifier = Modifier.align(Alignment.TopStart),
            )
        } else {
            EmptyHint(status, s, heroFocus, firstTile, Modifier.align(Alignment.TopStart)) {
                app.requestSettings.intValue = CATALOGS_CATEGORY; app.requestSettingsRev.intValue++
            }
        }
        if (!customBg && items.size > 1 && settings.dotsMode != "hidden") {
            Dots(
                total = items.size, current = index.mod(items.size), mode = settings.dotsMode, max = settings.dotsMax, s = s,
                modifier = Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = (metrics.collapsedH + metrics.bottomMargin + 10 * s).dp)
                    // Hidden dock: the dots go down to the bottom with it.
                    .graphicsLayer { this.alpha = dotsAlpha; translationY = (1f - dockShown.value) * (metrics.collapsedH + metrics.bottomMargin - 14 * s) * density },
            )
        }
    }
}

/**
 * Shown while the carousel has nothing. It must hold [heroFocus]: the dock's first row points Up at it,
 * and without a focus target there Up did nothing, so Settings could not be reached from the dock.
 */
@Composable
private fun EmptyHint(
    status: String, s: Float, heroFocus: FocusRequester, firstTile: FocusRequester,
    modifier: Modifier, onCatalogs: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, spring(0.6f, 450f), label = "hintBtn")
    Column(modifier.padding(start = (56 * s).dp, top = (130 * s).dp).widthIn(max = (480 * s).dp)) {
        Text("Glassio", color = Color.White, fontSize = (30 * s).sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp)
        Spacer(Modifier.height((10 * s).dp))
        Text(if (status.isBlank()) "Loading…" else status, color = Color.White.copy(alpha = 0.7f), fontSize = (14 * s).sp)
        Spacer(Modifier.height((6 * s).dp))
        Text("Open Settings → Catalogs to connect Nuvio and fill the carousel.", color = Color.White.copy(alpha = 0.55f), fontSize = (12 * s).sp)
        Spacer(Modifier.height((14 * s).dp))
        Text(
            "Open Catalogs",
            color = if (focused) Color.Black else Color.White,
            fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .focusRequester(heroFocus)
                .focusProperties { down = firstTile }
                .onFocusChanged { focused = it.isFocused }
                .tvPress(onClick = onCatalogs)
                .clip(RoundedCornerShape(50))
                .background(if (focused) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.14f))
                .padding(horizontal = (14 * s).dp, vertical = (6 * s).dp),
        )
    }
}

@Composable
private fun HeroInfo(
    item: HeroItem, app: GlassApp, s: Float, focused: Boolean, heroFocus: FocusRequester, firstTile: FocusRequester,
    onFocus: (Boolean) -> Unit, onPrev: () -> Unit, onNext: () -> Unit, onOpen: () -> Unit, onLong: () -> Unit,
    modifier: Modifier,
) {
    val settings = app.settings
    val ms = settings.transitionMs
    var heroDown by remember { mutableStateOf(false) }
    var heroLong by remember { mutableStateOf(false) }
    // Apple TV buttons: 0 = Play, 1 = the round Next button. The block stays one focus target; Left/Right
    // move between the two and past them change the title, so holding a direction keeps browsing.
    val nextBtn = settings.heroButtons == "apple"
    // 0 = Play, 1 = + (save to Library), 2 = Next.
    val order = if (settings.heroPlus) listOf(0, 1, 2) else listOf(0, 2)
    var btn by remember { mutableIntStateOf(0) }
    val savedList by app.library.saved.collectAsState()
    val isSaved = savedList.any { it.key == item.key }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // The block itself is the focus target (invisible): Left/Right change slide, OK opens the title.
    Box(
        modifier
            .padding(start = (48 * s).dp, top = ((if (settings.heroButtons == "apple") 78 else 96) * s).dp)
            .width((440 * s).dp)
            .focusRequester(heroFocus)
            .focusProperties { down = firstTile }
            .onKeyEvent { ev ->
                when {
                    ev.key == Key.DirectionRight && ev.type == KeyEventType.KeyDown -> {
                        val i = order.indexOf(btn); if (nextBtn && i in 0 until order.size - 1) btn = order[i + 1] else onNext(); true
                    }
                    ev.key == Key.DirectionLeft && ev.type == KeyEventType.KeyDown -> {
                        val i = order.indexOf(btn); if (nextBtn && i > 0) btn = order[i - 1] else onPrev(); true
                    }
                    ev.isSelect && nextBtn && btn == 2 -> { if (ev.type == KeyEventType.KeyDown) onNext(); true }
                    ev.isSelect && nextBtn && btn == 1 -> {
                        if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) {
                            val now = app.library.toggle(item)
                            toast(ctx, if (now) "Saved to Library · ${item.name}" else "Removed from Library · ${item.name}")
                        }
                        true
                    }
                    ev.isSelect -> {
                        if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { heroDown = true; heroLong = false }
                        // Hold OK: next picture of this title.
                        if (ev.type == KeyEventType.KeyDown && heroDown && !heroLong && ev.nativeKeyEvent.repeatCount >= 1) { heroLong = true; onLong() }
                        if (ev.type == KeyEventType.KeyUp && heroDown) { heroDown = false; if (!heroLong) onOpen(); heroLong = false }
                        true
                    }
                    ev.key == Key.Menu -> { if (ev.type == KeyEventType.KeyDown) onLong(); true }
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) btn = 0; onFocus(it.isFocused) }
            .focusable()
            .padding((8 * s).dp),
    ) {
      Column {
        // With the Apple TV buttons the text sits in a fixed-height area: logo and details stay where they always
        // were (top), the synopsis takes what is left (its own line setting), and the buttons below never move.
        val fixed = nextBtn
        Box(if (fixed) Modifier.height((240 * s).dp).clipToBounds() else Modifier, contentAlignment = Alignment.TopStart) {
        AnimatedContent(
            targetState = item,
            transitionSpec = { fadeIn(tween(ms)) togetherWith fadeOut(tween((ms * 0.4f).toInt())) },
            contentKey = { it.key },
            label = "hero",
        ) { it ->
            val m = settings.metaScale
            var headW by remember { mutableIntStateOf(0) }
            val density = LocalDensity.current
            Column {
              Column(Modifier.onSizeChanged { sz -> headW = sz.width }) {
                Title(it, settings.titleMode, settings.titleScale, s)
                if (settings.showMeta) {
                    Spacer(Modifier.height((16 * s).dp))
                    Row(horizontalArrangement = Arrangement.spacedBy((6 * s * m).dp), verticalAlignment = Alignment.CenterVertically) {
                        if (it.rating.isNotBlank()) Chip("★ ${it.rating}", s * m)
                        if (it.year.isNotBlank()) Chip(it.year, s * m)
                        if (it.platform.isNotBlank()) Chip(it.platform, s * m)
                    }
                }
                if (it.progress >= 0f && settings.cwProgress) {
                    Spacer(Modifier.height((10 * s).dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listOfNotNull("Continue", it.sub.takeIf { x -> x.isNotBlank() }, "${(it.progress * 100).toInt()}%").joinToString("  ·  "),
                            color = Color.White, fontSize = (10.5f * s * m).sp, fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.width((8 * s).dp))
                        Box(Modifier.width((90 * s).dp).height((3 * s).dp).clipR((2 * s).dp).background(Color.White.copy(alpha = 0.25f))) {
                            Box(Modifier.fillMaxWidth(it.progress.coerceIn(0f, 1f)).height((3 * s).dp).background(Color.White))
                        }
                    }
                }
                if (settings.showGenres) {
                    val kind = if (it.type.equals("movie", true)) "Movie" else "Series"
                    Spacer(Modifier.height((8 * s).dp))
                    Text(
                        (listOf(kind) + it.genres).joinToString("  ·  "),
                        color = AppleSecondary, fontWeight = FontWeight.Medium, fontSize = (11 * s * settings.genresScale).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
              }
                if (settings.showSynopsis && it.overview.isNotBlank()) {
                    AnimatedVisibility(visible = !settings.synopsisOnFocus || focused, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                        Column {
                            Spacer(Modifier.height((10 * s).dp))
                            Text(
                                it.overview, color = AppleSecondary, fontWeight = FontWeight.Medium,
                                fontSize = (11.5f * s * settings.synopsisScale).sp, lineHeight = (16 * s * settings.synopsisScale).sp,
                                maxLines = settings.synopsisLines, overflow = TextOverflow.Ellipsis,
                                modifier = if (headW > 0) Modifier.width(with(density) { headW.toDp() }) else Modifier,
                            )
                        }
                    }
                }
                // Simple Play button: only shows while the carousel is selected.
                if (settings.heroButtons == "play") AnimatedVisibility(visible = focused, enter = fadeIn(tween(180)), exit = fadeOut(tween(140))) {
                    Column {
                        Spacer(Modifier.height((14 * s).dp))
                        Row(
                            Modifier.clipR((settings.cornerRadius * 0.7f * s).dp).background(Color.White.copy(alpha = 0.94f))
                                .padding(horizontal = (16 * s).dp, vertical = (7 * s).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayGlyph(Modifier.size((10 * s).dp), Color.Black)
                            Spacer(Modifier.width((7 * s).dp))
                            Text("Play", color = Color.Black, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
        }
        // Apple TV buttons stay put while the title changes; only the text above fades.
        if (nextBtn) HeroButtons(focused, btn, s, settings.cornerRadius, settings.heroPlus, isSaved)
      }
    }
}

/**
 * Logos come in every shape: very wide word marks and almost square stacked ones. Sizing them by a
 * fixed height made wide logos tiny and tall ones huge, so every logo gets the same visual *area*,
 * then is capped in width and height.
 */
@Composable
private fun Title(item: HeroItem, mode: String, scale: Float, s: Float) {
    if (mode == "hidden") return
    @Composable fun asText() = Text(
        item.name, color = Color.White, fontSize = (30 * s * scale).sp, fontWeight = FontWeight.Light,
        letterSpacing = (4 * scale).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (mode == "text" || item.logo.isBlank()) { asText(); return }
    val ctx = LocalContext.current
    val pinnedLogo = GlassCache.pinnedLogo[item.logo] ?: GlassCache.logos.get(item.logo)
    // Logos that fail to download are tried twice more before the title is shown as text.
    var attempt by remember(item.logo) { mutableIntStateOf(0) }
    val req = remember(item.logo, attempt) { imgReq(ctx, item.logo, 1000, 400) }
    var ratio by remember(item.logo) { mutableStateOf<Float?>(null) }
    var failed by remember(item.logo) { mutableStateOf(false) }
    var retryAt by remember(item.logo) { mutableIntStateOf(0) }
    LaunchedEffect(retryAt) { if (retryAt > 0) { delay(900L * retryAt); attempt = retryAt } }
    if (failed) { asText(); return }
    val r = (ratio ?: 3f).coerceIn(0.6f, 8f)
    val baseH = 70f * s * scale
    val area = baseH * baseH * 3.2f
    var h = sqrt(area / r)
    var w = h * r
    val maxW = 420f * s * scale
    val maxH = 118f * s * scale
    if (w > maxW) { w = maxW; h = w / r }
    if (h > maxH) { h = maxH; w = h * r }
    if (pinnedLogo != null) {
        val pr = (pinnedLogo.width.toFloat() / pinnedLogo.height).coerceIn(0.6f, 8f)
        var ph = sqrt(area / pr); var pw = ph * pr
        if (pw > maxW) { pw = maxW; ph = pw / pr }
        if (ph > maxH) { ph = maxH; pw = ph * pr }
        Image(pinnedLogo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, colorFilter = logoTint(), modifier = Modifier.size(pw.dp, ph.dp))
        return
    }
    AsyncImage(
        model = req, contentDescription = item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, colorFilter = logoTint(),
        onSuccess = { st ->
            val sz = st.painter.intrinsicSize
            if (sz.width > 0f && sz.height > 0f) ratio = sz.width / sz.height
        },
        onError = { e ->
            if (retryAt < 2) retryAt++ else failed = true
            com.glasslauncher.tv.data.Glog.w("Logo ${item.logo} failed", e.result.throwable)
        },
        modifier = Modifier.size(w.dp, h.dp).alpha(if (ratio == null) 0f else 1f),
    )
}

/** Apple style: the logo's shape in white (its colours dropped). */
private val WhiteLogo = ColorFilter.tint(Color.White, BlendMode.SrcIn)
@Composable private fun logoTint(): ColorFilter? = if ((LocalContext.current.applicationContext as GlassApp).settings.logoWhite) WhiteLogo else null

@Composable
private fun Chip(text: String, s: Float) {
    Text(
        text, color = Color.White.copy(alpha = 0.92f), fontSize = (10.5f * s).sp,
        modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape((3 * s).dp))
            .padding(horizontal = (6 * s).dp, vertical = (1.5f * s).dp),
    )
}

@Composable
private fun Dots(total: Int, current: Int, mode: String, max: Int, s: Float, modifier: Modifier) {
    if (mode == "liquid") { LiquidDots(total, current, max, s, modifier); return }
    Box(
        modifier.clipR((8 * s).dp).background(Color.Black.copy(alpha = 0.34f))
            .padding(horizontal = (8 * s).dp, vertical = (5 * s).dp),
    ) {
        if (mode == "progress") {
            Box(Modifier.width((120 * s).dp).height((3 * s).dp).clipR((2 * s).dp).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(((current + 1f) / total).coerceIn(0f, 1f)).height((3 * s).dp).clipR((2 * s).dp).background(Color.White))
            }
        } else {
            val page = current / max
            val start = page * max
            val count = minOf(max, total - start)
            Row(horizontalArrangement = Arrangement.spacedBy((4.5f * s).dp), verticalAlignment = Alignment.CenterVertically) {
                for (i in 0 until count) {
                    val active = start + i == current
                    val w by animateDpAsState(if (active) (17 * s).dp else (4.5f * s).dp, spring(Spring.DampingRatioMediumBouncy, 500f), label = "dot")
                    Box(Modifier.width(w).height((4.5f * s).dp).clipR((3 * s).dp).background(Color.White.copy(alpha = if (active) 1f else 0.45f)))
                }
            }
        }
    }
}

// ───────────────────────────── top bar ─────────────────────────────

@Composable
private fun TopBar(app: GlassApp, s: Float, gearReq: FocusRequester, onSettings: () -> Unit, onMenu: (pressed: Boolean) -> Unit, modifier: Modifier) {
    val settings = app.settings
    // The settings button opens the menu (as soon as it is selected, or with OK), or Settings when the menu is off.
    // A key pressed on it (OK, or Up again when it is already selected) always opens it: that is never by accident.
    val gearClick = if (settings.menuMode == "off") onSettings else { { onMenu(true) } }
    val gearUp: () -> Boolean = { if (settings.menuMode == "auto") { onMenu(true); true } else false }
    // Only when you went up to it: focus can also land there by itself when Glassio starts or comes back.
    val since = remember { android.os.SystemClock.uptimeMillis() }
    // Still on the button a moment later (it was just closed, or the TV was busy): that's on purpose, open it.
    var gearOn by remember { mutableStateOf(false) }
    LaunchedEffect(gearOn) { if (gearOn && settings.menuMode == "auto") { delay(450); if (gearOn && android.os.SystemClock.uptimeMillis() - since > 1200) onMenu(true) } }
    val gearFocus: (Boolean) -> Unit = {
        gearOn = it
        if (it && settings.menuMode == "auto" && android.os.SystemClock.uptimeMillis() - since > 1200 &&
            System.currentTimeMillis() - app.interaction.longValue < 2500) onMenu(false)
    }
    val time by produceState(clockText(settings.clock24h), settings.clock24h) {
        while (true) { value = clockText(settings.clock24h); delay(10_000) }
    }
    var barFocused by remember { mutableStateOf(false) }
    // Auto-hide: the clock and the settings button stay hidden while you move around the home screen
    // and only appear when focus goes up to the settings button. (A few seconds at first, so you see where it is.)
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(settings.topAutoHide, settings.topHideSec) { intro = true; delay(settings.topHideSec * 1000L); intro = false }
    val visible = !settings.topAutoHide || barFocused || intro
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 140 else 350), label = "topAlpha")
    val date by produceState(dateText(), Unit) { while (true) { value = dateText(); delay(60_000) } }
    val wx by app.weather.now.collectAsState()
    val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f))
    Row(
        modifier.padding(top = (18 * s).dp, start = (56 * s).dp, end = (34 * s).dp).graphicsLayer { alpha = a },
        verticalAlignment = Alignment.Top,
    ) {
        val right = settings.clockPos == "right"
        // Apple TV: time and date, top left.
        if (settings.showClock && !right) Column {
            Text(time, color = Color.White, fontSize = (19 * s).sp, fontWeight = FontWeight.SemiBold, style = shade)
            if (settings.showDate) Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = (8.5f * s).sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp, style = shade)
        }
        Spacer(Modifier.weight(1f))
        @Composable fun weatherRow() {
            val w = wx
            if (w != null && settings.weatherMode != "off") Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(w.icon, Modifier.size((15 * s).dp))
                Spacer(Modifier.width((5 * s).dp))
                val parts = buildList {
                    add(w.temp(settings.weatherF))
                    if (settings.weatherMode == "condition" || settings.weatherMode == "all") w.condition.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (settings.weatherMode == "city" || settings.weatherMode == "all") add(w.city)
                }
                Text(parts.joinToString("  ·  "), color = Color.White.copy(alpha = 0.9f), fontSize = (10 * s).sp, fontWeight = FontWeight.Medium, style = shade)
            }
        }
        if (right) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            // Clock next to Settings, weather under it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                if (settings.showClock) Column(horizontalAlignment = Alignment.End) {
                    Text(time, color = Color.White, fontSize = (17 * s).sp, fontWeight = FontWeight.SemiBold, style = shade)
                    if (settings.showDate) Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = (8 * s).sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp, style = shade)
                }
                UpdateDot(app, s) { barFocused = it }
                GearButton(s, gearClick, gearReq, gearUp) { barFocused = it; gearFocus(it) }
            }
            weatherRow()
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
            weatherRow()
            UpdateDot(app, s) { barFocused = it }
            GearButton(s, gearClick, gearReq, gearUp) { barFocused = it; gearFocus(it) }
        }
    }
}

/** True while the Update pill in the top bar is selected: the banner below it shows what's new. */
private val updateFocused = mutableStateOf(false)

/**
 * A small pill when a new version is on GitHub, next to the settings button. Select it (Up, then Left)
 * and press OK: it downloads and installs right there, showing its progress, no trip to Settings.
 */
@Composable
private fun UpdateDot(app: GlassApp, s: Float, onFocus: (Boolean) -> Unit = {}) {
    val r by app.updater.available.collectAsState()
    val status by app.updater.status.collectAsState()
    val rel = r ?: return
    var focused by remember { mutableStateOf(false) }
    val busy = status.isNotBlank() && !status.startsWith("Update failed")
    Text(
        if (status.isNotBlank()) status else "Update ${rel.version}",
        color = if (focused) Color.Black else Color.White, fontSize = (9f * s).sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (focused) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.22f))
            .onFocusChanged { focused = it.isFocused; updateFocused.value = it.isFocused; onFocus(it.isFocused) }
            .clickable { if (!busy) app.updater.install() }
            .padding(horizontal = (9 * s).dp, vertical = (3 * s).dp),
    )
}

/** The release notes' bullet points, shortened to their headline ("**Sharp glass**: …" → "Sharp glass"). */
private fun noteLines(notes: String, max: Int = 4): List<String> =
    notes.lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { l ->
            val t = l.drop(2).trim()
            val bold = Regex("""^\*\*(.+?)\*\*""").find(t)?.groupValues?.get(1)
            (bold ?: t.replace("**", "")).trim().trimEnd(':', '.')
        }.filter { it.isNotBlank() }.take(max)

/** One-time card when an update is found: tvOS-style banner, top centre, for a few seconds. */
@Composable
fun UpdateBanner(app: GlassApp, s: Float, modifier: Modifier) {
    val r by app.updater.available.collectAsState()
    var shownFor by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val status by app.updater.status.collectAsState()
    val pill by updateFocused
    LaunchedEffect(r?.version) {
        val v = r?.version ?: return@LaunchedEffect
        if (v == shownFor) return@LaunchedEffect
        shownFor = v; visible = true; delay(9000); visible = false
    }
    // Shown once when the update is found, and again whenever the Update pill is selected.
    AnimatedVisibility((visible || pill || status.isNotBlank()) && r != null, modifier, enter = fadeIn(tween(250)), exit = fadeOut(tween(300))) {
        GlassPanel(Modifier.padding(top = (54 * s).dp).width((300 * s).dp), (16 * s).dp, style = app.settings.panelStyle, darken = 0.55f) {
            Column(Modifier.padding(horizontal = (16 * s).dp, vertical = (11 * s).dp)) {
                Text("Glassio ${r?.version}", color = Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height((5 * s).dp))
                noteLines(r?.notes.orEmpty()).forEach {
                    Text("·  $it", color = AppleSecondary, fontSize = (10 * s).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height((6 * s).dp))
                Text(
                    when { status.isNotBlank() -> status; pill -> "Press OK to update now"; else -> "Go up to Update and press OK" },
                    color = Color.White, fontSize = (10 * s).sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun dateText(): String =
    java.text.SimpleDateFormat("d MMM ''yy", java.util.Locale.US).format(java.util.Date()).uppercase()

@Composable
private fun GearButton(s: Float, onClick: () -> Unit, req: FocusRequester, onUp: () -> Boolean = { false }, onFocus: (Boolean) -> Unit) {
    // tvOS 26 top-right button: a small glass circle with the gearshape glyph; selected it turns white.
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused) 1.16f else 1f, spring(0.8f, 900f), label = "gear")
    val lit = animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "gearLit")
    Box(
        Modifier.size((20 * s).dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .focusRequester(req)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .tvPress(onKey = { ev -> ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp && onUp() }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SlidersGlyph(Modifier.fillMaxSize(), Color.White) { lit.value }
    }
}

/** Settings glyph: three sliders in a rounded square. */
@Composable
private fun SlidersGlyph(modifier: Modifier, outline: Color, litOf: () -> Float = { 0f }) {
    // The highlight is read while drawing: animating it redraws the glyph, it never recomposes.
    androidx.compose.foundation.Canvas(modifier) {
        val lit = litOf()
        val w = size.minDimension
        val sw = w * 0.085f
        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        val tl = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2)
        val sz = androidx.compose.ui.geometry.Size(w - sw, w - sw)
        val cr = androidx.compose.ui.geometry.CornerRadius(w * 0.26f)
        // Selected: the square fills white and the sliders turn dark.
        if (lit > 0.01f) drawRoundRect(Color.White.copy(alpha = lit), topLeft = tl, size = sz, cornerRadius = cr)
        val color = androidx.compose.ui.graphics.lerp(outline, Color(0xFF1C1C1E), lit)
        drawRoundRect(androidx.compose.ui.graphics.lerp(outline.copy(alpha = 0.9f), Color.White, lit), topLeft = tl, size = sz, cornerRadius = cr, style = st)
        val knobs = floatArrayOf(0.40f, 0.62f, 0.40f)
        for (i in 0..2) {
            val y = w * (0.30f + i * 0.20f)
            val kx = w * knobs[i]; val kr = w * 0.075f
            // The track stops at the knob, so the knob reads as a ring.
            drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.24f, y), androidx.compose.ui.geometry.Offset(kx - kr, y), sw * 0.9f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(color, androidx.compose.ui.geometry.Offset(kx + kr, y), androidx.compose.ui.geometry.Offset(w * 0.76f, y), sw * 0.9f, androidx.compose.ui.graphics.StrokeCap.Round)
            drawCircle(color, w * 0.075f, androidx.compose.ui.geometry.Offset(kx, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw * 0.9f))
        }
    }
}

/** SF Symbols "gearshape.fill" style: a toothed wheel with a hole. */
@Composable
private fun GearGlyph(modifier: Modifier, color: Color) {
    androidx.compose.foundation.Canvas(modifier) {
        val c = androidx.compose.ui.geometry.Offset(size.width / 2, size.height / 2)
        val ro = size.minDimension / 2; val ri = ro * 0.74f; val teeth = 8
        val p = androidx.compose.ui.graphics.Path()
        for (i in 0 until teeth * 4) {
            val a = (i / (teeth * 4f)) * 2 * Math.PI - Math.PI / 2
            val r = if ((i % 4) == 1 || (i % 4) == 2) ro else ri
            val x = c.x + (kotlin.math.cos(a) * r).toFloat(); val y = c.y + (kotlin.math.sin(a) * r).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        val hole = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(c, ro * 0.30f))
        }
        drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, p, hole), color)
    }
}

// ───────────────────────────── dock ─────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Dock(
    app: GlassApp, s: Float, metrics: DockMetrics, screenH: Float, radius: Int,
    expanded: Boolean, onExpanded: (Boolean) -> Unit,
    movingPkg: String?, onMoveKey: (KeyEvent) -> Boolean, firstTile: FocusRequester, heroFocus: FocusRequester,
    folderFocus: FocusRequester, tileFocus: HashMap<String, FocusRequester>, onMove: () -> Unit,
    onMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit, modifier: Modifier,
    onCover: (androidx.compose.ui.geometry.Rect) -> Unit = {},
    /** A column on the left (your own background only). */
    vertical: Boolean = false,
    /** In the middle of the screen (your own background only): no margin under it. */
    centered: Boolean = false,
    onLibrary: () -> Unit = {},
) {
    val ctx = LocalContext.current
    val settings = app.settings
    val allApps by app.apps.apps.collectAsState()
    val apps = remember(allApps, settings.dockOrder, settings.hiddenApps, settings.showMobileApps) { app.apps.ordered() }
    // null marks where the Apps folder sits.
    val showFolder = settings.showFolder
    val items: List<AppEntry?> = remember(apps, settings.folderPos, showFolder) {
        val p = settings.folderPos.let { if (it < 0 || it > apps.size) apps.size else it }
        apps.toMutableList<AppEntry?>().apply { if (showFolder) add(p, null) }
    }
    val rows = remember(items) { items.chunked(DOCK_COLUMNS) }
    // Fewer apps than a full row: the dock wraps them (the tiles keep their size).
    val cols = if (settings.dockFit) items.size.coerceIn(1, DOCK_COLUMNS) else DOCK_COLUMNS
    val side = metrics.sideMargin + (DOCK_COLUMNS - cols) * (metrics.cellW + metrics.gap) / 2f
    // Down from the last row opens the Library.
    val edge: (androidx.compose.ui.focus.FocusDirection) -> Boolean = { d ->
        if (d == androidx.compose.ui.focus.FocusDirection.Down && settings.libraryDown) { onLibrary(); true } else false
    }
    if (vertical) {
        VerticalDock(app, s, metrics, screenH, radius, items, movingPkg, onMoveKey, firstTile, heroFocus, folderFocus, tileFocus, onMove,
            onMenu, onFolder, onFolderMenu, modifier, edge, onExpanded)
        return
    }
    // Move mode: a moved tile is laid out again in its new slot and loses focus, which then fell to the
    // carousel. Keep focus on the app being moved after every step.
    LaunchedEffect(items, movingPkg) {
        val p = movingPkg ?: return@LaunchedEffect
        delay(30)
        runCatching { (if (p == FOLDER) folderFocus else tileFocus[p])?.requestFocus() }
    }
    val fullH = metrics.padTop + rows.size * metrics.cellH + (rows.size - 1).coerceAtLeast(0) * metrics.gap + metrics.padBottom + 8 * s
    val expandedH = minOf(fullH, screenH * 0.72f).coerceAtLeast(metrics.collapsedH)
    val targetH = if (expanded) expandedH else metrics.collapsedH
    val hState = animateDpAsState(targetH.dp, tween(170, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "dockH")
    // While the dock grows or shrinks its glass is flat (no off-screen layer resized every frame).
    // The height is applied in the layout phase, so the dock is not recomposed on every animation frame.
    val still by remember(targetH) { androidx.compose.runtime.derivedStateOf { kotlin.math.abs(hState.value.value - targetH) < 0.5f } }
    val scroll = rememberScrollState()
    LaunchedEffect(expanded) { if (!expanded) scroll.animateScrollTo(0) }

    GlassPanel(
        modifier = modifier.padding(horizontal = side.dp).padding(bottom = if (centered) 0.dp else metrics.bottomMargin.dp).fillMaxWidth().layout { m, c ->
            val px = hState.value.roundToPx()
            val p = m.measure(c.copy(minHeight = px, maxHeight = px))
            layout(p.width, px) { p.place(0, 0) }
        }.onGloballyPositioned { c -> onCover(if (still && expanded && settings.dockStyle != "amoled" && settings.dockStyle != "bow") c.boundsInRoot() else androidx.compose.ui.geometry.Rect.Zero) },
        radius = (radius * s).dp,
        style = settings.dockStyle,
        // The rims stay on while the dock opens: switching them off for the move made them blink.
        refract = true,
        cache = still || DebugHide.intValue and 131072 != 0,
    ) {
        // Plain rows instead of a lazy grid: about twenty apps, and focus moves in and out predictably.
        // When every row fits in the open dock nothing scrolls: scrolling to the second row while the dock was
        // still growing pushed the first row out and back (a jump at every open).
        val fits = fullH <= expandedH + 1f
        // Panes stay until the page has cleared again after closing: switching back to glass then happens once
        // everything has stopped moving (it made the first frame of the close slow).
        val genv = LocalGlassEnv.current
        val veilUp by remember(genv.veil) { androidx.compose.runtime.derivedStateOf { (genv.veil?.value ?: 0f) > 0.02f } }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (fits) NoScroll else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current),
            LocalDockOpen provides (expanded || veilUp),
        ) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer { alpha = if (DebugHide.intValue and 65536 != 0) 0f else 1f }.verticalScroll(scroll)
                .padding(start = metrics.padH.dp, end = metrics.padH.dp, top = metrics.padTop.dp, bottom = metrics.padBottom.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gap.dp),
        ) {
            rows.forEachIndexed { r, row ->
                androidx.compose.runtime.CompositionLocalProvider(LocalEdge provides (if (r == rows.lastIndex) edge else { _ -> false })) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp)) {
                    row.forEachIndexed { c, e ->
                        val idx = r * DOCK_COLUMNS + c
                        val edges = gridEdges(c, row.size, r, rows.size, allowUp = true)
                        val base = Modifier.weight(1f)
                            .then(if (idx == 0) Modifier.focusRequester(firstTile) else Modifier)
                            .then(if (r == 0) Modifier.focusProperties { up = heroFocus } else Modifier)
                        if (e == null) {
                            key(FOLDER) {
                                FolderTile(
                                    app, s, radius, moving = movingPkg == FOLDER,
                                    modifier = base.focusRequester(folderFocus),
                                    onFocus = { f -> if (f) { onExpanded(r > 0); onMove() } },
                                    onMoveKey = onMoveKey, onClick = onFolder, onLong = onFolderMenu, edges = edges,
                                )
                            }
                        } else key(e.pkg) {
                            DockTile(
                                entry = e, app = app, s = s, radius = radius, moving = movingPkg == e.pkg,
                                modifier = base.focusRequester(tileFocus.getOrPut(e.pkg) { FocusRequester() }),
                                onFocus = { f -> if (f) { onExpanded(r > 0); onMove() } },
                                onMoveKey = onMoveKey,
                                onClick = { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } },
                                onLong = { onMenu(e) }, edges = edges,
                            )
                        }
                    }
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
                }
            }
        }
        }
    }
}

/** The dock as a column on the left of the screen (your own background only): every app, scrolling. */
@Composable
private fun VerticalDock(
    app: GlassApp, s: Float, metrics: DockMetrics, screenH: Float, radius: Int, items: List<AppEntry?>,
    movingPkg: String?, onMoveKey: (KeyEvent) -> Boolean, firstTile: FocusRequester, heroFocus: FocusRequester,
    folderFocus: FocusRequester, tileFocus: HashMap<String, FocusRequester>, onMove: () -> Unit,
    onMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit, modifier: Modifier,
    edge: (androidx.compose.ui.focus.FocusDirection) -> Boolean,
    onExpanded: (Boolean) -> Unit,
) {
    val ctx = LocalContext.current
    val cellW = metrics.cellW * 0.9f
    val cellH = cellW / 1.6f
    val fullH = metrics.padTop + items.size * cellH + (items.size - 1).coerceAtLeast(0) * metrics.gap + metrics.padBottom + 8 * s
    val h = minOf(fullH, screenH * 0.84f)
    GlassPanel(
        modifier = modifier.padding(start = (28 * s).dp).width((cellW + 2 * metrics.padH).dp).height(h.dp),
        radius = (radius * s).dp, style = app.settings.dockStyle, refract = true, cache = true,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(start = metrics.padH.dp, end = metrics.padH.dp, top = metrics.padTop.dp, bottom = metrics.padBottom.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gap.dp),
        ) {
            items.forEachIndexed { i, e ->
                val last = i == items.lastIndex
                val edges = buildSet {
                    add(androidx.compose.ui.focus.FocusDirection.Left); add(androidx.compose.ui.focus.FocusDirection.Right)
                    if (last) add(androidx.compose.ui.focus.FocusDirection.Down)
                }
                val base = Modifier.fillMaxWidth()
                    .then(if (i == 0) Modifier.focusRequester(firstTile).focusProperties { up = heroFocus } else Modifier)
                androidx.compose.runtime.CompositionLocalProvider(LocalEdge provides (if (last) edge else { _ -> false })) {
                    if (e == null) key(FOLDER) {
                        FolderTile(app, s, radius, moving = movingPkg == FOLDER, modifier = base.focusRequester(folderFocus),
                            onFocus = { f -> if (f) { onExpanded(false); onMove() } }, onMoveKey = onMoveKey, onClick = onFolder, onLong = onFolderMenu, edges = edges)
                    } else key(e.pkg) {
                        DockTile(entry = e, app = app, s = s, radius = radius, moving = movingPkg == e.pkg,
                            modifier = base.focusRequester(tileFocus.getOrPut(e.pkg) { FocusRequester() }),
                            onFocus = { f -> if (f) { onExpanded(false); onMove() } }, onMoveKey = onMoveKey,
                            onClick = { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } },
                            onLong = { onMenu(e) }, edges = edges)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object NoScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Which arrows lead out of a grid cell (left of the first column, right of the last, below the last row …). */
private fun gridEdges(col: Int, rowSize: Int, row: Int, rows: Int, allowUp: Boolean): Set<androidx.compose.ui.focus.FocusDirection> = buildSet {
    if (col == 0) add(androidx.compose.ui.focus.FocusDirection.Left)
    if (col == rowSize - 1) add(androidx.compose.ui.focus.FocusDirection.Right)
    if (row == rows - 1) add(androidx.compose.ui.focus.FocusDirection.Down)
    if (row == 0 && !allowUp) add(androidx.compose.ui.focus.FocusDirection.Up)
}

/** The plate behind an icon, in the chosen style. */
@Composable
private fun TilePlate(style: String, focused: Boolean, s: Float, radius: Dp, flat: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val env = LocalGlassEnv.current
    // Dock opening or open (page blurred and darkened behind it), or inside the folder: the page behind is (about
    // to be) one blurred image, so a plain translucent pane looks the same and costs the GPU nothing extra. It
    // switches the moment the dock starts to open: glass in every icon while the second row slid in made the
    // opening stutter.
    val veiled by remember(env.veil) { androidx.compose.runtime.derivedStateOf { (env.veil?.value ?: 0f) > 0.95f } }
    val pane = (flat || veiled || LocalDockOpen.current) && (style == "liquid" || style == "clear" || style.startsWith("water"))
    val shape = RoundedCornerShape(radius)
    // The icon always sits in the same place: switching the plate under it no longer rebuilds every icon.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fill = Modifier.matchParentSize()
        if (pane) {
            // A light glass pane over the blurred page: it reads as the theme's glass, not a grey card (issue #8).
            val clearGlass = style.startsWith("water")
            Box(fill.background(Color.White.copy(alpha = when { focused -> 0.22f; clearGlass -> 0.07f; else -> 0.11f }), shape)
                .border(if (focused) 1.2.dp else 0.7.dp, Color.White.copy(alpha = if (focused) 0.6f else if (clearGlass) 0.22f else 0.16f), shape))
        } else when (style) {
            "water", "water_still" -> {
                LiquidSurface(
                    fill, radius = radius, refraction = if (focused) (env.refractionDp * 1.2f).dp else 0.dp, bezel = (16 * s).dp,
                    tint = Color.White.copy(alpha = if (focused) 0.05f else 0.02f), sharp = true, wobble = if (focused && style == "water") env.wobble else 0f,
                )
                Box(fill.border(0.6.dp, Color.White.copy(alpha = if (focused) 0.30f else 0.16f), shape))
            }
            "amoled" -> Box(fill.background(Color.Black, shape).border(0.6.dp, Color.White.copy(alpha = if (focused) 0.5f else 0.10f), shape))
            // Like the iOS 26 light icons (Apple Music): a soft off-white that shades a little towards the bottom,
            // with a fine bright rim, not a flat paper white. Selected, it brightens.
            "bow" -> Box(fill.background(if (focused) BowFocused else BowRest, shape)
                .border(0.8.dp, Color.White.copy(alpha = if (focused) 0.9f else 0.55f), shape))
            // Clear (matte): the dock behind is already frosted glass, so the plate is just a light veil over it.
            "clear" -> Box(fill.background(Color.White.copy(alpha = if (focused) 0.18f else 0.10f), shape)
                .border(0.6.dp, Color.White.copy(alpha = if (focused) 0.32f else 0.14f), shape))
            // Only the selected icon bends light: no extra GPU layer per icon, fast dock and folder.
            else -> LiquidSurface(
                fill, radius = radius, refraction = if (focused) (env.refractionDp * 0.75f).dp else 0.dp, bezel = (14 * s).dp,
                tint = Color.White.copy(alpha = if (focused) 0.10f else 0.04f),
                // Frosted icons show a blurred copy: crossfading it during a slide looks the same as sliding it.
                slideInside = false,
            )
        }
        Box(fill.clip(shape), contentAlignment = Alignment.Center, content = content)
    }
}

/** The dock is opening or open: its icons use plain panes (see TilePlate). */
val LocalDockOpen = androidx.compose.runtime.compositionLocalOf { false }


private val BowRest = Brush.verticalGradient(listOf(Color(0xFFE1E1E5), Color(0xFFCECED4)))
private val BowFocused = Brush.verticalGradient(listOf(Color(0xFFEDEDF0), Color(0xFFDADADF)))
private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private val whiteGlyph = ColorFilter.tint(Color.White, BlendMode.SrcIn)
private val blackGlyph = ColorFilter.tint(Color(0xFF111114), BlendMode.SrcIn)

/**
 * tvOS focus colouring: at rest the icon is a white glyph (or a grey banner); selected, it shows its own
 * colours on a light plate. [lit] animates 0..1, both looks are drawn and cross-faded.
 */
@Composable
private fun FocusColorArt(art: Art?, label: String, s: Float, litState: androidx.compose.runtime.State<Float>, plate: String, restOnLight: Boolean = false) {
    if (art == null) {
        Text(label, color = androidx.compose.ui.graphics.lerp(Color.White, if (plate == "light") Color.Black else Color.White, litState.value), fontSize = (11 * s).sp, maxLines = 2, textAlign = TextAlign.Center)
        return
    }
    val bm = art.bmp
    val wide = bm.width > bm.height * 1.3f
    val crop = wide && !art.transparent
    val mod = Modifier.fillMaxSize().padding(if (crop) 0.dp else (8 * s).dp)
    val scale = if (crop) ContentScale.Crop else ContentScale.Fit
    // All three layers stay composed; only their alpha changes, read while drawing (no recomposition).
    Box(Modifier.fillMaxSize()) {
        // White: soft frosted white (not paper white). Dark: near-black glass. Glass: no plate at all, the tile
        // stays as it is and only the icon's colours come in. Black AMOLED: pure black.
        val light = plate == "light"
        // ModulateAlpha: each layer is one draw, so its opacity goes straight into the draw instead of an
        // off-screen pass per icon (eighteen of them every frame while the dock opened).
        if (plate != "glass") Box(Modifier.fillMaxSize().graphicsLayer { alpha = litState.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }
            .background(when (plate) { "light" -> Color(0xE6E4E4EA); "glass" -> Color.Transparent; "amoled" -> Color.Black; else -> Color(0xF0141416) }))
        Image(bm, label, colorFilter = if (art.transparent) (if (restOnLight) blackGlyph else whiteGlyph) else grayscale, contentScale = scale,
            filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            modifier = mod.graphicsLayer { alpha = (1f - litState.value) * if (art.transparent) 0.92f else 0.75f; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha })
        Image(if (light) art.onLight else bm, label, contentScale = scale, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
            modifier = mod.graphicsLayer { alpha = litState.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha })
    }
}

/** The icon itself; in "black on white" glyph icons turn black and full banners turn grey. */
@Composable
private fun IconArt(art: Art?, label: String, style: String, s: Float) {
    if (art == null) {
        Text(label, color = if (style == "bow") Color.Black else Color.White, fontSize = (11 * s).sp, maxLines = 2, textAlign = TextAlign.Center)
        return
    }
    val bm = art.bmp
    val wide = bm.width > bm.height * 1.3f
    val filter = when {
        style == "bow" && art.transparent -> ColorFilter.tint(Color(0xFF0E0E10), BlendMode.SrcIn)
        style == "bow" -> grayscale
        else -> null
    }
    Image(
        bm, label, colorFilter = filter, filterQuality = androidx.compose.ui.graphics.FilterQuality.High,
        contentScale = if (wide && !art.transparent) ContentScale.Crop else ContentScale.Fit,
        modifier = Modifier.fillMaxSize().padding(if (wide && !art.transparent) 0.dp else (8 * s).dp),
    )
}

@Composable
private fun TileFrame(
    s: Float, radius: Int, moving: Boolean, label: String, alwaysLabel: Boolean, modifier: Modifier,
    onFocus: (Boolean) -> Unit, onMoveKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLong: () -> Unit,
    solid: Boolean = false,
    lift: Boolean = true,
    bounce: Boolean = true,
    /** Directions with no neighbour inside the grid: pressing them bounces instead of moving focus. */
    edges: Set<androidx.compose.ui.focus.FocusDirection> = emptySet(),
    zoom: String = "tile",
    glow: String = "off",
    glowColor: Color? = null,
    plate: @Composable (focused: Boolean, radius: Dp) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val glowA = animateFloatAsState(if (focused && glow != "off" && !moving) 1f else 0f, tween(220), label = "glow")
    val gColor = if (glow == "white") Color.White else glowColor ?: Color.White
    // tvOS rubber band: at the edge, pressing further nudges the icon that way and it springs back.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val onEdge = LocalEdge.current
    val nudge = remember { androidx.compose.animation.core.Animatable(androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Offset.VectorConverter) }
    val tileScope = rememberCoroutineScope()
    val backOwner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    val keys: (KeyEvent) -> Boolean = keys@{ ev ->
        if (onMoveKey(ev)) return@keys true
        if (ev.key == Key.Back) {
            if (ev.type == KeyEventType.KeyUp) backOwner?.onBackPressedDispatcher?.onBackPressed()
            return@keys true
        }
        if (!bounce) return@keys false
        val dir = when (ev.key) {
            Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
            Key.DirectionRight -> androidx.compose.ui.focus.FocusDirection.Right
            Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
            Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
            else -> return@keys false
        }
        if (ev.type != KeyEventType.KeyDown) return@keys true
        if (dir in edges && onEdge(dir)) return@keys true
        if (dir in edges || !focusManager.moveFocus(dir)) {
            val d = 9f * s
            val push = when (dir) {
                androidx.compose.ui.focus.FocusDirection.Left -> androidx.compose.ui.geometry.Offset(-d, 0f)
                androidx.compose.ui.focus.FocusDirection.Right -> androidx.compose.ui.geometry.Offset(d, 0f)
                androidx.compose.ui.focus.FocusDirection.Down -> androidx.compose.ui.geometry.Offset(0f, d * 0.7f)
                else -> androidx.compose.ui.geometry.Offset(0f, -d * 0.7f)
            }
            tileScope.launch {
                nudge.animateTo(push, tween(90, easing = androidx.compose.animation.core.LinearOutSlowInEasing))
                nudge.animateTo(androidx.compose.ui.geometry.Offset.Zero, spring(0.45f, 380f))
            }
        }
        true
    }
    // tvOS: the selected icon rises. Done as a layout offset so the glass behind it follows the icon.
    val rise by animateDpAsState(if (focused && lift) (-7 * s).dp else 0.dp, spring(0.7f, 420f), label = "tileRise")
    // Apple TV style: no frame. The icon lifts, grows a little and casts a soft shadow.
    val scale by animateFloatAsState(if (focused) (if (moving) 1.14f else if (zoom == "tile") 1.09f else 1f) else 1f, spring(0.72f, 520f), label = "tileScale")
    val shadow by animateDpAsState(if (focused && solid) (16 * s).dp else 0.dp, tween(180), label = "tileShadow")
    val r = (radius * 0.75f * s).dp
    val shape = RoundedCornerShape(r)
    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (focused) 1f else 0f)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .tvPress(onLong = onLong, onKey = keys, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f)
                .offset { androidx.compose.ui.unit.IntOffset((nudge.value.x * density).toInt(), rise.roundToPx() + (nudge.value.y * density).toInt()) }
                .drawBehind { with(Glow) { glow(gColor, glowA.value, grow = scale) } }
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    shadowElevation = shadow.toPx()
                    this.shape = shape; clip = true
                    ambientShadowColor = Color.Black; spotShadowColor = Color.Black
                },
        ) {
            plate(focused, r)
            if (moving) {
                Text(
                    "◀ ▶ ▲ ▼  ·  OK", color = Color.White, fontSize = (9 * s).sp,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = (4 * s).dp)
                        .clipR((6 * s).dp).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = (6 * s).dp, vertical = (2 * s).dp),
                )
            }
        }
        if (alwaysLabel) {
            Box(Modifier.fillMaxWidth().height((18 * s).dp), contentAlignment = Alignment.Center) {
                Text(
                    label, color = Color.White.copy(alpha = if (focused) 1f else 0.7f), fontSize = (10.5f * s).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DockTile(
    entry: AppEntry, app: GlassApp, s: Float, radius: Int, moving: Boolean, modifier: Modifier,
    onFocus: (Boolean) -> Unit, onMoveKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLong: () -> Unit,
    alwaysLabel: Boolean = false, label: String = entry.label, flat: Boolean = false,
    edges: Set<androidx.compose.ui.focus.FocusDirection> = emptySet(),
) {
    val rev = app.icons.rev.intValue
    val pack = app.settings.iconPack
    val style = app.settings.tileStyle
    val art by produceState(app.apps.cachedArtwork(entry), entry.pkg, pack, rev) { value = app.apps.artwork(entry) }
    val solid = style == "amoled" || style == "bow"
    val colorOnFocus = app.settings.focusColor
    val zoom = app.settings.tileZoom
    val glow = app.settings.glow
    val glowColor = if (glow == "color") art?.let { a -> remember(a) { Glow.colorOf("app:" + entry.pkg + rev, a.bmp.asAndroidBitmap()) } } else null
    TileFrame(s, radius, moving, label, alwaysLabel, modifier, onFocus, onMoveKey, onClick, onLong, solid, app.settings.tileLift, app.settings.edgeBounce, edges,
        zoom = zoom, glow = glow, glowColor = glowColor) { focused, r ->
        // "Inside the glass": the tile stays put and only the icon grows in it.
        val iz by animateFloatAsState(if (focused && zoom == "icon") 1.12f else 1f, spring(0.72f, 520f), label = "iconZoom")
        if (colorOnFocus) {
            val lit = animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "lit")
            TilePlate(style, focused, s, r, flat) { Box(Modifier.fillMaxSize().graphicsLayer { scaleX = iz; scaleY = iz }) { FocusColorArt(art, entry.label, s, lit, app.settings.focusPlate, restOnLight = style == "bow") } }
        } else TilePlate(style, focused, s, r, flat) { Box(Modifier.fillMaxSize().graphicsLayer { scaleX = iz; scaleY = iz }) { IconArt(art, entry.label, style, s) } }
    }
}

/** Apple-style folder: a 3x2 preview of the apps inside. */
@Composable
private fun FolderTile(
    app: GlassApp, s: Float, radius: Int, moving: Boolean, modifier: Modifier,
    onFocus: (Boolean) -> Unit, onMoveKey: (KeyEvent) -> Boolean, onClick: () -> Unit, onLong: () -> Unit,
    edges: Set<androidx.compose.ui.focus.FocusDirection> = emptySet(),
) {
    val style = app.settings.tileStyle
    val all by app.apps.apps.collectAsState()
    val preview = remember(all, app.settings.dockOrder) { app.apps.ordered(includeHidden = true).take(6) }
    TileFrame(s, radius, moving, "Apps", false, modifier, onFocus, onMoveKey, onClick, onLong, style == "amoled" || style == "bow", app.settings.tileLift, app.settings.edgeBounce, edges) { focused, r ->
        TilePlate(style, focused, s, r) {
            Column(Modifier.fillMaxSize().padding((7 * s).dp), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                preview.chunked(3).forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                        row.forEach { e ->
                            val art by produceState(app.apps.cachedArtwork(e), e.pkg, app.settings.iconPack, app.icons.rev.intValue) { value = app.apps.artwork(e) }
                            Box(Modifier.weight(1f).fillMaxHeight().clipR((4 * s).dp)
                                .background(if (style == "bow") Color(0xFFE2E2E6) else Color.White.copy(alpha = 0.06f))) {
                                IconArt(art, "", style, s * 0.35f)
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

/** Every app, hidden ones included, in a glass folder like tvOS. */
@Composable
private fun FolderOverlay(
    app: GlassApp, s: Float, metrics: DockMetrics, radius: Int, screenH: Float,
    moving: String?, onMoveDone: () -> Unit, blocked: Boolean,
    onMenu: (AppEntry) -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val all by app.apps.apps.collectAsState()
    val list = remember(all, app.settings.dockOrder, app.settings.hiddenApps, app.settings.showMobileApps) { app.apps.ordered(includeHidden = true) }
    val hidden = app.settings.hiddenApps.toSet()
    val first = remember { FocusRequester() }
    val focusOf = remember { HashMap<String, FocusRequester>() }
    // While moving, the arrows carry the app through the grid and focus stays on it.
    LaunchedEffect(list, moving) { moving?.let { p -> delay(30); runCatching { focusOf[p]?.requestFocus() } } }
    val onMoveKey: (KeyEvent) -> Boolean = { ev ->
        val p = moving
        if (p == null) false
        else when {
            ev.isSelect -> { if (ev.type == KeyEventType.KeyUp) onMoveDone(); true }
            ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) onMoveDone(); true }
            ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> {
                if (ev.type == KeyEventType.KeyDown) app.apps.move(p, when (ev.key) {
                    Key.DirectionLeft -> -1; Key.DirectionRight -> 1
                    Key.DirectionUp -> -DOCK_COLUMNS; else -> DOCK_COLUMNS
                })
                true
            }
            else -> false
        }
    }
    BackHandler(enabled = moving != null, onBack = onMoveDone)
    BackHandler(enabled = moving == null && !blocked, onBack = onClose)
    LaunchedEffect(Unit) { delay(60); runCatching { first.requestFocus() } }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val outer = LocalGlassEnv.current
    val env = outer.copy(bmp = outer.heavy ?: outer.bmp, sharp = outer.heavy ?: outer.sharp, wobble = 0f, prevBmp = null, prevSharp = null, mix = null, veil = null, sharpIsPage = false)
    CompositionLocalProvider(LocalGlassEnv provides env) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Blurred page and its dimming in one pass.
        val bg = env.bmp
        if (bg != null) Image(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.45f), BlendMode.SrcAtop))
        else Box(Modifier.fillMaxSize().background(Color(0xFF101014)))
        AnimatedVisibility(shown, enter = fadeIn(tween(160))) {
            Column(Modifier.fillMaxWidth(0.9f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Apps", color = Color.White, fontSize = (26 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(bottom = (12 * s).dp))
                GlassPanel(Modifier.fillMaxWidth().heightIn(max = (screenH * 0.74f).dp), radius = (radius * 1.6f * s).dp, style = app.settings.dockStyle, refract = false, cache = true) {
                    Column(
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding((20 * s).dp),
                        verticalArrangement = Arrangement.spacedBy((14 * s).dp),
                    ) {
                        val folderRows = list.chunked(DOCK_COLUMNS)
                        val rowsCount = folderRows.size
                        folderRows.forEachIndexed { r, row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp)) {
                                row.forEachIndexed { c, e ->
                                    key(e.pkg) {
                                        val fr = focusOf.getOrPut(e.pkg) { FocusRequester() }
                                        val edges = gridEdges(c, row.size, r, rowsCount, allowUp = false)
                                        DockTile(
                                            entry = e, app = app, s = s, radius = radius, moving = moving == e.pkg,
                                            modifier = Modifier.weight(1f).focusProperties { canFocus = !blocked }.focusRequester(fr).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                                            onFocus = {}, onMoveKey = onMoveKey,
                                            onClick = { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } },
                                            onLong = { onMenu(e) },
                                            alwaysLabel = true, flat = true,
                                            label = if (e.pkg in hidden) "${e.label} · hidden" else e.label, edges = edges,
                                        )
                                    }
                                }
                                repeat(DOCK_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

/**
 * Liquid glass page dots (tvOS / iOS 26 style): white drops with no pill behind them. The selected drop is a
 * longer capsule whose front and back follow with different springs, so moving stretches it like a drop of
 * water, and the dots it passes swell and merge into it before it settles.
 */
@Composable
private fun LiquidDots(total: Int, current: Int, max: Int, s: Float, modifier: Modifier) {
    val page = current / max
    val start = page * max
    val count = minOf(max, total - start)
    val li = (current - start).toFloat()
    val head = remember { androidx.compose.animation.core.Animatable(li) }
    val tail = remember { androidx.compose.animation.core.Animatable(li) }
    var lastPage by remember { mutableIntStateOf(page) }
    LaunchedEffect(li, page) {
        if (page != lastPage) { lastPage = page; head.snapTo(li); tail.snapTo(li); return@LaunchedEffect }
        // The front races ahead and the back follows slowly: the drop stretches into the next dot, then
        // its back pulls away from the old one until the bridge snaps.
        launch { head.animateTo(li, spring(dampingRatio = 0.72f, stiffness = 1100f)) }
        launch { tail.animateTo(li, spring(dampingRatio = 0.86f, stiffness = 380f)) }
    }
    // On a small liquid-glass capsule, the same glass as the dock.
    val st = LocalGlassEnv.current
    GlassPanel(modifier, radius = (11 * s).dp, style = "water_still", refract = st.refractionDp > 0) {
        GlassDots(count, { head.value }, { tail.value }, s, Modifier.padding(horizontal = (3 * s).dp, vertical = (2 * s).dp))
    }
}


/**
 * Play and Next, drawn like the Apple TV app: grey glass when not selected, white with a dark glyph when
 * selected, the selected one a little larger.
 */
@Composable
private fun HeroButtons(focused: Boolean, btn: Int, s: Float, corner: Int, plus: Boolean = false, saved: Boolean = false) {
    val h = (30 * s).dp
    Column {
        Spacer(Modifier.height((14 * s).dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val playOn = focused && btn == 0
            val nextOn = focused && btn == 2
            val plusOn = focused && btn == 1
            val pScale by animateFloatAsState(if (playOn) 1.06f else 1f, spring(0.7f, 700f), label = "playScale")
            val nScale by animateFloatAsState(if (nextOn) 1.12f else 1f, spring(0.7f, 700f), label = "nextScale")
            // Dark when not selected, white when selected.
            val pBg by androidx.compose.animation.animateColorAsState(if (playOn) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.55f), tween(140), label = "playBg")
            val nBg by androidx.compose.animation.animateColorAsState(if (nextOn) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.55f), tween(140), label = "nextBg")
            val pFg = if (playOn) Color.Black else Color.White
            val nFg = if (nextOn) Color.Black else Color.White
            Row(
                Modifier.graphicsLayer { scaleX = pScale; scaleY = pScale }.height(h)
                    .clipR((corner * 0.7f * s).dp.coerceAtLeast(h / 2)).background(pBg)
                    .padding(horizontal = (16 * s).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlayGlyph(Modifier.size((10 * s).dp), pFg)
                Spacer(Modifier.width((7 * s).dp))
                Text("Play", color = pFg, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width((10 * s).dp))
            if (plus) {
                RoundHeroButton(h, plusOn) { fg ->
                    Canvas(Modifier.size((11 * s).dp)) {
                        val w = size.width; val hh = size.height
                        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.15f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                        val p = androidx.compose.ui.graphics.Path()
                        if (saved) { p.moveTo(w * 0.16f, hh * 0.52f); p.lineTo(w * 0.42f, hh * 0.78f); p.lineTo(w * 0.86f, hh * 0.24f) }
                        else { p.moveTo(w * 0.5f, hh * 0.12f); p.lineTo(w * 0.5f, hh * 0.88f); p.moveTo(w * 0.12f, hh * 0.5f); p.lineTo(w * 0.88f, hh * 0.5f) }
                        drawPath(p, fg, style = st)
                    }
                }
                Spacer(Modifier.width((10 * s).dp))
            }
            Box(
                Modifier.graphicsLayer { scaleX = nScale; scaleY = nScale }.size(h).clip(androidx.compose.foundation.shape.CircleShape).background(nBg),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size((11 * s).dp)) {
                    val w = size.width; val hh = size.height
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.36f, hh * 0.16f); lineTo(w * 0.70f, hh * 0.5f); lineTo(w * 0.36f, hh * 0.84f)
                    }
                    drawPath(path, nFg, style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = w * 0.17f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

/** A round hero button (the + / ✓ one): dark, white with a dark glyph when selected. */
@Composable
private fun RoundHeroButton(h: Dp, on: Boolean, glyph: @Composable (Color) -> Unit) {
    val sc by animateFloatAsState(if (on) 1.12f else 1f, spring(0.7f, 700f), label = "roundScale")
    val bg by androidx.compose.animation.animateColorAsState(if (on) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.55f), tween(140), label = "roundBg")
    Box(
        Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.size(h).clip(androidx.compose.foundation.shape.CircleShape).background(bg),
        contentAlignment = Alignment.Center,
    ) { glyph(if (on) Color.Black else Color.White) }
}

/** Swallows a key for a moment after a page change, so presses meant for the old page don't run on into the next. */
object NavGate {
    @Volatile private var key = 0
    @Volatile private var until = 0L
    fun hold(keyCode: Int, ms: Long) { key = keyCode; until = android.os.SystemClock.uptimeMillis() + ms }
    fun blocks(keyCode: Int): Boolean = keyCode == key && android.os.SystemClock.uptimeMillis() < until
}
