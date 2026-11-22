package com.glasslauncher.tv.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.async
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt
enum class Screen { Home, Settings, Library }

/** Test only (am start --ei dbg_hide <bits>): 1 backdrop, 2 carousel text, 4 top bar, 8 dock. Finds what the GPU spends a frame on. */
val DebugHide = androidx.compose.runtime.mutableIntStateOf(0)

/** Index of the "Catalogs" category in the settings list. */
val CATALOGS_CATEGORY: Int get() = CATEGORY_ORDER.indexOf("Catalogs & add-ons")

/** When this run of the app started (some work waits until the home screen has settled). */
internal val appStartedAt = android.os.SystemClock.uptimeMillis()

internal fun Modifier.clipR(r: Dp): Modifier = this.clip(RoundedCornerShape(r))

/** The depth effect works on its own low-priority thread (the model's threads inherit it): the home screen first. */
private val DepthWorker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glass-depth").apply { isDaemon = true }
}.asCoroutineDispatcher()

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    val homeHasFocus = remember { booleanArrayOf(false) }
    // The carousel, the top buttons or the dock hold the selection (not something hidden: the Library kept ready
    // under the page, a folder fading out).
    val homeGroupHas = remember { booleanArrayOf(false) }
    val heroFocus = remember { FocusRequester() }
    val folderFocus = remember { FocusRequester() }
    var pickerFor by remember { mutableStateOf<AppEntry?>(null) }
    var folderOpen by remember { mutableStateOf(false) }
    var libFromDock by remember { mutableStateOf(false) }
    /** When the dock's selection last moved (see the Search icon's wait while it is only passed over). */
    val dockMovedAt = remember { longArrayOf(0L) }
    var libWarm by remember { mutableStateOf(false) }
    // Up from the Library: the home screen is drawn once under it first (its first frame is the slow one),
    // then the page scrolls down, so the scroll itself runs at full speed.
    var homePrep by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    // The home screen stops taking the focus only once the menu holds it (and takes it back before the menu goes):
    // switching it off the moment the menu opened dropped the focus onto the dock for a frame or two.
    var menuHasFocus by remember { mutableStateOf(false) }
    // The small glass menu (hold OK on Play or on an app) and where the focus goes back when it closes.
    var pop by remember { mutableStateOf<PopState?>(null) }
    var popReturn by remember { mutableStateOf<FocusRequester?>(null) }
    val gearReq = remember { FocusRequester() }
    // Just closed: focus can pass over the settings button on its way back, which must not open it again.
    var menuClosedAt by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    var folderMoving by remember { mutableStateOf<String?>(null) }
    val tileFocus = remember { HashMap<String, FocusRequester>() }
    androidx.compose.runtime.SideEffect { MusicUi.tileFor = { tileFocus[it] } }

    // The current backdrop as a small bitmap: every glass surface refracts it. The page backdrop only
    // switches once the glass copy is ready, and both crossfade together, so the glass never lags behind.
    var backImgs by remember { mutableStateOf<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(null) }
    var prevImgs by remember { mutableStateOf<Triple<ImageBitmap, ImageBitmap, ImageBitmap>?>(null) }
    var shownBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    var prevBackdrop by remember { mutableStateOf<ImageBitmap?>(null) }
    // The carousel's picture as it showed when a page of the dock's icons (search, music apps, inputs, an app's rows)
    // took its place: back on the same slide it is put back as it was, with nothing loaded or made again (that
    // took about half a second of an empty blurred page).
    class KeptPage(val url: String, val sig: String, val list: String?, val page: ImageBitmap, val imgs: Triple<ImageBitmap, ImageBitmap, ImageBitmap>)
    val keptPage = remember { arrayOfNulls<KeptPage>(1) }
    // The search's page comes and goes in one frame, page and glass together (its cards fade by themselves): a
    // crossfade of two full pages with every glass surface drawn twice cost the TV GPU 45-55 ms a frame, some
    // fifteen frames a second for the length of the fade, which is what looked like a stutter.
    val searchCut = remember { booleanArrayOf(false) }
    fun pageSig() = listOf(Scrims.key, settings.blurRadius, settings.glassRes, settings.slideAnim, settings.pageScale, settings.memTier, settings.imageQuality).joinToString("|")
    // Which picture is on screen: the title text waits for it, so text and picture change together.
    val shownUrl = remember { mutableStateOf<String?>(null) }
    var fadeMs by remember { mutableIntStateOf(settings.transitionMs) }
    val mix = remember { androidx.compose.animation.core.Animatable(1f) }
    // 0 = crossfade; 1 / -1 = the new picture slides in from the right / left (Slide animation).
    var slideDir by remember { mutableIntStateOf(0) }
    // The channels' grey background is made ahead (it was made the moment the rows appeared, and the page waited).
    LaunchedEffect(settings.channelsOn, settings.channelsBg, settings.blurRadius, settings.glassRes, settings.pageScale, settings.channelsBlurBri) {
        if (!settings.channelsOn) return@LaunchedEffect
        delay(4_000)
        val (w, h, tag) = ChannelPages.size(ctx, settings)
        withContext(Dispatchers.Default) { runCatching { ChannelPages.grey(tag, w, h, settings.blurRadius) } }
    }
    // The music apps' and inputs' own background (Glassio's lights, or the carousel's picture blurred) made ahead on
    // the quiet thread, the carousel's again after each new picture has settled: the page then comes up at once.
    LaunchedEffect(settings.stagePageBg, settings.bgMode, settings.musicOn, settings.inputsIcon, settings.searchOn, if (settings.stagePageBg == "carousel") shownUrl.value else null,
        settings.blurRadius, settings.glassRes, settings.pageScale, settings.channelsBlurBri) {
        val mode = settings.stagePageBg
        if (mode == "grey" || settings.bgMode != "carousel" || !(settings.musicOn || settings.inputsIcon || settings.searchOn)) return@LaunchedEffect
        val u = shownUrl.value?.takeIf { !it.startsWith("glassio:") }
        if (mode == "carousel" && u == null) return@LaunchedEffect
        delay(if (mode == "carousel") 900 else 3_000)
        val (w, h, tag) = ChannelPages.size(ctx, settings)
        val blur = settings.blurRadius
        ChannelPages.warmer.execute { runCatching { StagePages.make(mode, u, tag, w, h, blur) }.onFailure { com.glasslauncher.tv.data.Glog.w("Music & Inputs page background", it) } }
    }
    // Blurred cards made ahead, one at a time on a quiet thread: every card on screen as its picture loads, and each
    // app's first card a few seconds after start (selecting the app, or moving to a card, then shows it at once).
    LaunchedEffect(settings.blurRadius, settings.glassRes, settings.pageScale, settings.channelsBlurBri) {
        val (w, h, tag) = ChannelPages.size(ctx, settings)
        val blur = settings.blurRadius
        ChannelsUi.warm = { link, b -> ChannelPages.warmer.execute { if (ChannelPages.peekArt(link, tag) == null) runCatching { ChannelPages.art(link, b, tag, w, h, blur) } } }
    }
    LaunchedEffect(com.glasslauncher.tv.data.Channels.all.value, settings.channelsOn, settings.channelsBg, settings.channelsBlurBri) {
        if (!settings.channelsOn || settings.channelsBg != "blur") return@LaunchedEffect
        delay(3_000)
        val loader = SingletonImageLoader.get(ctx)
        for (pkg in com.glasslauncher.tv.data.Channels.apps()) {
            val p = com.glasslauncher.tv.data.Channels.rowsFor(settings, pkg).firstOrNull()?.programs?.firstOrNull() ?: continue
            if (p.poster.isBlank()) continue
            val (w, h, tag) = ChannelPages.size(ctx, settings)
            ChannelPages.peekArt(p.poster, tag)?.let { ChannelPages.keepFirst(p.poster, tag, it); continue }
            val b = withContext(Dispatchers.IO) { runCatching {
                (loader.execute(ImageRequest.Builder(ctx).data(p.poster).size(640, 360).allowHardware(false).build()) as? SuccessResult)?.image?.toBitmap()
            }.getOrNull() } ?: continue
            val blur = settings.blurRadius
            ChannelPages.warmer.execute { runCatching { ChannelPages.keepFirst(p.poster, tag, ChannelPages.peekArt(p.poster, tag) ?: ChannelPages.art(p.poster, b, tag, w, h, blur)) } }
        }
    }
    // A new home screen (back after it was rebuilt): the rows' page state starts over with it.
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ChannelsUi.pageOn.value = false } }
    // Whether the music page showed just before the rows came (the rows fade from it then).
    val musicWas = remember { booleanArrayOf(false) }
    // The app whose rows' background shows.
    val rowsPkg = remember { arrayOfNulls<String>(1) }
    // The rows' page with its side shade drawn in (the music page fades in over it as it looked).
    val rowsShaded = remember { arrayOfNulls<Pair<ImageBitmap, ImageBitmap>>(1) }
    // (Read here, before anything is drawn with it.)
    // (The carousel's own switch, Settings → Carousel → Carousel shade: both shades; your own background keeps its
    // Left shade switch and its bottom shade.)
    (if (settings.bgMode != "carousel") settings.sideShadeOff else settings.carouselShade == "off").let { if (Scrims.sideOff != it) Scrims.sideOff = it }
    (settings.bgMode == "carousel" && settings.carouselShade == "off").let { if (Scrims.bottomOff != it) Scrims.bottomOff = it }
    LaunchedEffect(backdrop, Scrims.key, settings.blurRadius, settings.glassRes, settings.slideAnim, settings.pageScale, settings.memTier, settings.bgMode, settings.bgImage, settings.bgVideo, CustomBackground.aerial.value, CustomBackground.frameRev.intValue, com.glasslauncher.tv.data.Photos.current.value, ChannelsUi.shown.value || MusicUi.idleOn.value || InputsUi.page.value || SearchUi.page.value, settings.stagePageBg, if (ChannelsUi.shown.value && settings.channelsBg == "blur") ChannelsUi.art.value?.let { it.first + (if (it.second != null) "#" else "") } else null) {
        GlassCache.res = settings.glassRes
        // The channels' rows on screen: the page and the glass show their background (the Library's grey, or the
        // selected card's picture blurred), crossfaded together. Coming from the music page it fades from that page
        // (the carousel's picture under it used to show for a second while the new background was being made).
        // (Also the idle music player, nothing playing: on the grey page.)
        // (Only over the carousel: with your own picture, like a video or the screensaver, the page stays see-through.)
        if ((ChannelsUi.shown.value || MusicUi.idleOn.value || InputsUi.page.value || SearchUi.page.value) && settings.bgMode == "carousel") {
            val (w, h, tag) = ChannelPages.size(ctx, settings)
            val blurMode = settings.channelsBg == "blur" && ChannelsUi.shown.value
            val art = ChannelsUi.art.value?.takeIf { blurMode }
            // (Also when this page is new, e.g. back from the screensaver: it has no picture yet.)
            val first = !ChannelsUi.pageOn.value || shownBackdrop == null
            // Blurs are made ahead (every card on screen, and each app's first card): usually here at once.
            // The music apps, an idle player, the inputs: their own background (Settings → Dock & icons).
            val stageMode = if (ChannelsUi.shown.value) "grey" else settings.stagePageBg
            val stageWas = if (stageMode != "grey") StagePages.peek(stageMode, backdrop, tag) else null
            var pair = if (art != null) ChannelPages.peekArt(art.first, tag) else if (!blurMode) ChannelPages.peekGrey(tag) else null
            if (stageMode != "grey") {
                val u = backdrop; val blur = settings.blurRadius
                val made = withContext(Dispatchers.Default) { runCatching { StagePages.make(stageMode, u, tag, w, h, blur) }.getOrNull() }
                // (The carousel's picture has no glass copy yet: the page stays as it is; a new page takes the last
                // blurred picture, or the grey one.)
                if (made == null && !first) return@LaunchedEffect
                pair = made ?: stageWas ?: pair
            }
            if (pair == null && blurMode) {
                val b = art?.second
                if (b != null) pair = withContext(Dispatchers.Default) { runCatching { ChannelPages.art(art.first, b, tag, w, h, settings.blurRadius) }.getOrNull() }
                else {
                    // The card's picture isn't loaded yet (it comes in a moment, and this starts again): the page
                    // stays as it is meanwhile, no grey flash before the blur. A page with nothing on it yet (back
                    // from the screensaver: black for a moment) takes the last background shown at once.
                    // (Another app's rows: the grey meanwhile, not the last app's colour.)
                    if (!first && ChannelsUi.stagePkg.value == rowsPkg[0]) return@LaunchedEffect
                    if (first) {
                    val last = ChannelPages.peekLast(tag)
                    if (shownBackdrop == null && last != null) pair = last else delay(700)
                    }
                }
            }
            if (pair == null) pair = withContext(Dispatchers.Default) { ChannelPages.grey(tag, w, h, settings.blurRadius) }
            val (page, glass) = pair
            // (What showed meanwhile, see "bridge" below: the new background fades in from it, not the carousel.)
            val bridged = if (first) ((if (blurMode) art?.let { ChannelPages.peekArt(it.first, tag) } else null) ?: stageWas ?: ChannelPages.peekGrey(tag)) else null
            ChannelPages.remember(tag, pair)
            rowsPkg[0] = ChannelsUi.stagePkg.value
            if (!first && shownBackdrop === page) return@LaunchedEffect
            // (Faded from the music page only when it was what showed: from Nuvio's carousel the song's blurred
            // artwork flashed for a moment on the way to the rows.)
            val music = MusicUi.stageCur.value?.takeIf { first && musicWas[0] }
            mix.snapTo(0f)
            // (The carousel's own picture is what this page replaces: kept for the way back.)
            val wasUrl = shownUrl.value; val wasPage = shownBackdrop; val wasImgs = backImgs
            if (wasUrl != null && !wasUrl.startsWith("glassio:") && wasPage != null && wasImgs != null) keptPage[0] = KeptPage(wasUrl, pageSig(), com.glasslauncher.tv.data.AppCatalogs.pkg.value, wasPage, wasImgs)
            if (music != null) { val m = music.glass ?: music.back; prevBackdrop = m; prevImgs = Triple(m, m, m) }
            else if (bridged != null) { prevImgs = Triple(bridged.second.blurred, bridged.second.sharp, bridged.second.heavy); prevBackdrop = bridged.first }
            else { prevImgs = backImgs; prevBackdrop = shownBackdrop }
            backImgs = Triple(glass.blurred, glass.sharp, glass.heavy)
            slideDir = 0
            shownBackdrop = page
            shownUrl.value = "glassio:channels"
            ChannelsUi.pageOn.value = true
            if (settings.slideAnim != "fade" && rowsShaded[0]?.first !== page) scope.launch(Dispatchers.Default) {
                runCatching { withSideShade(page) }.getOrNull()?.let { rowsShaded[0] = page to it }
            }
            searchCut[0] = SearchUi.page.value
            try { if (searchCut[0]) mix.snapTo(1f) else mix.animateTo(1f, tween(if (first) 150 else 200)) } finally {
                prevImgs = null; prevBackdrop = null
                withContext(kotlinx.coroutines.NonCancellable) { mix.snapTo(1f) }
            }
            return@LaunchedEffect
        }
        // Leaving the rows: the music page (if a song plays) fades in over their background, page and glass together.
        if (ChannelsUi.pageOn.value) {
            ChannelsUi.pageOn.value = false
            val page = shownBackdrop
            // (With its side shade, as it showed: drawn without it, the page brightened for a moment before the music
            // page came in over it.)
            val shaded = rowsShaded[0]?.takeIf { it.first === page }?.second ?: page
            val rowsBlur = backImgs?.first
            if (page != null && MusicUi.stageCur.value != null) scope.launch { MusicUi.fadeFrom(shaded!!, rowsBlur) }
        }
        BakeCache.max = when (settings.memTier) { 0 -> 2; 2 -> 4; else -> 3 }
        backdropScale = settings.pageScale
        backdrop565 = settings.memTier == 0
        backdropSharpen = settings.imageQuality == "hd"
        GlassCache.limit(settings.memTier)
        // Your own picture / video: it replaces every title's backdrop (one picture, so nothing changes per slide).
        val custom = CustomBackground.still(ctx, settings)
        // Your own background whose picture isn't ready yet (the next video's still is being made): keep what is
        // there. Falling back to the titles' pictures flashed them through the glass between two videos.
        val own = when (settings.bgMode) { "image" -> settings.bgImage.isNotBlank(); "video" -> settings.bgVideo.isNotBlank(); "aerial", "photos" -> true; else -> false }
        if (own && custom == null) return@LaunchedEffect
        val u = custom ?: backdrop ?: return@LaunchedEffect
        if (custom != null && shownUrl.value == custom && backImgs != null) return@LaunchedEffect
        // Back from a page of the dock's icons to the slide that was showing: its picture is still here.
        // (Only to the same list: on the way to an app with its own carousel the picture reported for a moment is
        // still the old list's, and it showed for half a second before sliding to the app's.)
        keptPage[0]?.takeIf { it.url == u && it.sig == pageSig() && it.list == com.glasslauncher.tv.data.AppCatalogs.pkg.value && shownUrl.value?.startsWith("glassio:") == true }?.let { k ->
            prevImgs = backImgs; prevBackdrop = shownBackdrop
            backImgs = k.imgs; slideDir = 0
            mix.snapTo(0f)
            shownBackdrop = k.page; shownUrl.value = u; fadeMs = 150
            try { if (searchCut[0]) mix.snapTo(1f) else mix.animateTo(1f, tween(150, easing = androidx.compose.animation.core.LinearEasing)) } finally {
                prevImgs = null; prevBackdrop = null
                withContext(kotlinx.coroutines.NonCancellable) { mix.snapTo(1f) }
            }
            return@LaunchedEffect
        }
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
        // (Back from a page of the dock's own icons or an app's rows: a short crossfade, not a slide's length. It took
        // the slides' time, 560 ms and more, and the search felt slow to leave.)
        val fromStage = shownUrl.value?.startsWith("glassio:") == true
        fadeMs = if (fromStage && searchCut[0]) 0 else if (fromStage) 150 else if (app.browsing()) (when (settings.slideAnim) { "apple" -> 560; "slide" -> 420; else -> 220 }) else settings.transitionMs
        val dm = ctx.resources.displayMetrics
        val src = fullBmp
        // Slide: the side shade must stay put while pictures move, so it is drawn on top instead of baked in.
        val slide = settings.slideAnim != "fade"
        val bk = (if (slide) "$u|slide" else u) + "|" + settings.pageScale + "|" + settings.memTier + "|" + Scrims.key
        val baked = BakeCache.get(bk, src) ?: withContext(Dispatchers.Default) { bakeBackdrop(src, (dm.widthPixels * settings.pageScale).toInt(), (dm.heightPixels * settings.pageScale).toInt(), !slide) }.also { BakeCache.put(bk, src, it) }
        if (GlassHalf.peek(baked) == null) withContext(Dispatchers.Default) { GlassHalf.of(baked) }
        if (com.glasslauncher.tv.data.Glog.timing) com.glasslauncher.tv.data.Glog.i("Slide ready in ${android.os.SystemClock.uptimeMillis() - t0} ms (${u.substringAfterLast('/')})")
        // The new pictures sent to the graphics chip ahead (in the background), a frame or two before they show:
        // uploaded only when first drawn, the switch frame took 30-100 ms (a catch with every new photo / video /
        // slide, worse with the large clock's glass reading the new picture too).
        listOfNotNull(baked, GlassHalf.peek(baked), next?.first, next?.second, next?.third).forEach { b -> runCatching { b.prepareToDraw() } }
        repeat(2) { androidx.compose.runtime.withFrameNanos { } }
        // Page and glass fade in the same frames with the same timing, so the bar never runs ahead.
        prevImgs = backImgs
        prevBackdrop = shownBackdrop
        if (next != null) backImgs = next
        // (Back from the rows or the grey page: a crossfade; it slid in from the side of the last carousel move.)
        slideDir = if (slide && !first && prevBackdrop != null && shownUrl.value?.startsWith("glassio:") != true) SlideDir.dir else 0
        if (!first && next != null) mix.snapTo(0f)
        shownBackdrop = baked
        shownUrl.value = u
        try {
            if (first || next == null || fadeMs == 0) mix.snapTo(1f)
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
    // The opening slide of each app's own carousel made ready ahead, on the quiet thread (the picture scaled to the
    // screen with its shades, and its small copy for the glass): going to that app then only fades. Made when
    // needed, it left the page waiting about half a second.
    val aheadUrls = HeroAhead.urls.value
    LaunchedEffect(aheadUrls, pageSig(), settings.bgMode) {
        val slide = settings.slideAnim != "fade"
        fun key(u: String) = (if (slide) "$u|slide" else u) + "|" + settings.pageScale + "|" + settings.memTier + "|" + Scrims.key
        BakeCache.keepOnly(if (settings.bgMode == "carousel") aheadUrls.map { key(it) }.toSet() else emptySet())
        if (settings.bgMode != "carousel") return@LaunchedEffect
        delay(2500)
        val dm = ctx.resources.displayMetrics
        for (u in aheadUrls) {
            // (Its picture is kept by the carousel a moment after the list is known: waited for a little.)
            var src: ImageBitmap? = null
            repeat(20) { if (src == null) { src = GlassCache.pinnedFull[u]; if (src == null) delay(500) } }
            val full = src ?: continue
            if (BakeCache.get(key(u), full) != null) continue
            withContext(Dispatchers.Default) { runCatching {
                val b = bakeBackdrop(full, (dm.widthPixels * settings.pageScale).toInt(), (dm.heightPixels * settings.pageScale).toInt(), !slide)
                GlassHalf.of(b); BakeCache.keep(key(u), full, b)
            }.onFailure { com.glasslauncher.tv.data.Glog.w("Preparing an app's first slide", it) } }
        }
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
        folderOpen = false; folderMoving = null; pickerFor = null; dialog = null; movingPkg = null; expanded = false; pop = null
        if (app.homeRev.intValue > 0) menuOpen = false
    }
    LaunchedEffect(app.requestSettingsRev.intValue) {
        val c = app.requestSettings.intValue
        when {
            c == 100 -> { onScreen(Screen.Home); menuOpen = true; app.requestSettings.intValue = -1 }
            c == 101 -> { menuOpen = false; onScreen(Screen.Library); app.requestSettings.intValue = -1 }
            c == 102 -> { menuOpen = false; folderOpen = false; pop = null; onScreen(Screen.Home); SearchUi.open(settings); app.requestSettings.intValue = -1 }
            c >= 0 -> { settingsCat = c; onScreen(Screen.Settings); app.requestSettings.intValue = -1 }
        }
    }
    // Settings left any way (the Home button too, not only Back): next time it opens plainly, not on that page again.
    LaunchedEffect(screen) { if (screen != Screen.Settings) settingsCat = 0 }
    // The Apps folder from a remote button or another screen: the menu, a pop-up or another screen (Settings, the
    // Library) are closed first. Left open under it, the menu kept the remote (the next OK picked a theme there),
    // and from Settings the folder opened where it couldn't be seen (nothing was selected any more).
    fun openFolder() {
        menuOpen = false; pop = null; dialog = null; expanded = false; pickerFor = null; movingPkg = null
        if (screen != Screen.Home) onScreen(Screen.Home)
        folderOpen = true
    }
    // Opened by a remote button (not from its tile): Back goes to the first app, the tile may be out of sight.
    var folderByKey by remember { mutableStateOf(false) }
    LaunchedEffect(app.requestFolder.value) { if (app.requestFolder.value) { openFolder(); folderByKey = true; app.requestFolder.value = false } }
    // Nothing selected on the home screen (back from the screensaver, or the Apps folder closed by its remote
    // button): the remote did nothing until Home. The Play button (or the first app) is selected again.
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val lifeNow by life.currentStateFlow.collectAsState()
    val isResumed = lifeNow.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    LaunchedEffect(isResumed, folderOpen, screen, menuOpen) {
        if (!isResumed || folderOpen || screen != Screen.Home || menuOpen || SearchUi.full.value) return@LaunchedEffect
        repeat(3) {
            delay(250)
            if (homeHasFocus[0] || pop != null || dialog != null || pickerFor != null) return@LaunchedEffect
            // (With your own background the hidden spot above the dock opens the menu: the first app then.)
            // (The search page up: its bar. The carousel's button isn't there then, and the page went away with the selection.)
            val first = if (SearchUi.page.value) SearchUi.entry else if (settings.bgMode != "carousel") firstTile else heroFocus
            if (runCatching { first.requestFocus() }.isFailure) runCatching { firstTile.requestFocus() }
        }
    }
    // A watch for the selection, everywhere: whenever Glassio is on screen with the remote and nothing at all is
    // selected for a moment (the Apps folder closed by Home, back from an app opened from the folder, a page or a
    // pop-up that went away with the selection in it), something is selected again. The fixes for each case missed
    // others; this catches them all.
    val winInfo = androidx.compose.ui.platform.LocalWindowInfo.current
    LaunchedEffect(isResumed) {
        if (!isResumed) return@LaunchedEffect
        var lost = 0; var tries = 0
        while (true) {
            delay(250)
            // On the plain home screen the selection must be on the page itself: held by something hidden counts as lost.
            val plainHome = screen == Screen.Home && !folderOpen && !menuOpen && pop == null && dialog == null && pickerFor == null && !SearchUi.full.value
            // Only on the plain home screen: the Library and Settings keep their own selection (moving "down" for them
            // here made those pages scroll down by themselves).
            if (!plainHome) { lost = 0; tries = 0; continue }
            // It must be on the carousel, the top buttons or the dock. Back from an app (opened from the Apps folder)
            // Android gave it to the page's own group, which shows nothing; a tile in the dock's lower rows while the
            // dock is folded is out of sight too.
            val ok = if (plainHome) homeGroupHas[0] && (DockFocus.hero || DockFocus.top || DockFocus.dock) && !(DockFocus.lowerRow && !expanded)
                else homeHasFocus[0]
            if (ok || !winInfo.isWindowFocused || movingPkg != null) { lost = 0; tries = 0; continue }
            if (++lost < 2) continue
            lost = 0
            if (tries == 0) com.glasslauncher.tv.data.Glog.i("Nothing selected ($screen, folder $folderOpen, menu $menuOpen): selecting again")
            // Each try something else, in case one of them can't take it (the Play button hidden, the dock away…).
            if (tries % 2 == 0) runCatching { (if (SearchUi.page.value) SearchUi.entry else if (settings.bgMode != "carousel") firstTile else heroFocus).requestFocus() }
            else runCatching { firstTile.requestFocus() }
            tries++
        }
    }
    val folderToggleSeen = remember { intArrayOf(app.folderToggle.intValue) }
    LaunchedEffect(app.folderToggle.intValue) {
        if (app.folderToggle.intValue == folderToggleSeen[0]) return@LaunchedEffect
        folderToggleSeen[0] = app.folderToggle.intValue
        if (folderOpen) {
            // Back to the first app, not the Apps tile: that one sits in the dock's lower row, hidden while the dock
            // is folded, and the selection went there out of sight.
            folderMoving = null; folderOpen = false; folderByKey = false; delay(150)
            runCatching { firstTile.requestFocus() }
            // The folder fades out for 280 ms and can hold on to the selection until it's gone: tried again after.
            repeat(4) {
                delay(150)
                if (!homeHasFocus[0] && !folderOpen) runCatching { firstTile.requestFocus() }
            }
        }
        else { openFolder(); folderByKey = true }
    }

    LaunchedEffect(screen) {
        if (screen == Screen.Home) { delay(400); runCatching { firstTile.requestFocus() } }
    }
    BackHandler(enabled = screen == Screen.Home && movingPkg != null) { movingPkg = null }
    BackHandler(enabled = screen == Screen.Home && movingPkg == null && expanded && !folderOpen) { runCatching { firstTile.requestFocus() } }
    BackHandler(enabled = settings.backSaver && screen == Screen.Home && movingPkg == null && !expanded && !folderOpen && pickerFor == null) {
        SaverGate.opening.value = true
        runCatching { ctx.startActivity(android.content.Intent(ctx, com.glasslauncher.tv.saver.ScreensaverActivity::class.java)) }
    }
    // A trailer playing (also with the dock selected while it is hidden): Back stops it first.
    BackHandler(enabled = screen == Screen.Home && Trailers.playing.value != null && movingPkg == null && !folderOpen && pickerFor == null) { Trailers.stop() }

    // Moving an app to another row builds its tile again, and the focus went with the old one (nothing was
    // selected until Down brought it back to the dock): the moved app is selected again once it has its new place.
    val keepOn: (String) -> Unit = { p ->
        scope.launch {
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            runCatching { (if (p == FOLDER) folderFocus else tileFocus[p])?.requestFocus() }
        }
    }
    val onMoveKey: (KeyEvent) -> Boolean = { ev ->
        val p = movingPkg
        if (p == null) false
        else when {
            ev.isSelect -> { if (ev.type == KeyEventType.KeyUp) { movingPkg = null; keepOn(p) }; true }
            ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight || ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> {
                if (ev.type == KeyEventType.KeyDown) {
                    val delta = when (ev.key) {
                        Key.DirectionLeft -> -1; Key.DirectionRight -> 1
                        Key.DirectionUp -> -DockFocus.cols; else -> DockFocus.cols
                    }
                    if (p == FOLDER) {
                        val n = app.apps.ordered().size
                        val cur = settings.folderPos.let { if (it < 0 || it > n) n else it }
                        settings.folderPos = (cur + delta).coerceIn(0, n)
                    } else app.apps.moveVisible(p, delta)
                    keepOn(p)
                }
                true
            }
            else -> false
        }
    }

    // A locked app's menu (move, hide, icon, unlock…) asks for the code first, like opening it.
    val dialogReturn = remember { arrayOfNulls<FocusRequester>(1) }
    lateinit var openMenu: (AppEntry, Boolean, Boolean) -> Unit
    val showMenu: (AppEntry, Boolean) -> Unit = { e, inFolder ->
        if (AppLock.locked(app, e.pkg)) AppLock.ask.value = AppLock.Ask(e.label) { openMenu(e, inFolder, true) }
        else openMenu(e, inFolder, false)
    }
    openMenu = { e, inFolder, verified ->
        val m = appMenu(
            app, e, ctx,
            startMove = if (inFolder) ({ folderMoving = e.pkg }) else ({ movingPkg = e.pkg }),
            pickIcon = { pickerFor = e },
            verified = verified,
            pickCatalogs = { dialogReturn[0] = if (inFolder) null else tileFocus[e.pkg]; dialog = appCatalogsDialog(app, e.pkg, e.label) },
        ) as MenuDialog
        // The glass menu right above the app (in the Apps folder: in the middle of the screen).
        popReturn = if (inFolder) null else tileFocus[e.pkg]
        pop = PopState(m.title, m.options.map { (label, action) ->
            // Hidden from the dock: its tile goes away with the focus (nothing was selected until Home). The app
            // next to it is selected instead (the one after it, else the one before, else the first tile).
            if (!inFolder && label == "Hide") PopOption(label, onClick = {
                val list = app.apps.ordered()
                val i = list.indexOfFirst { it.pkg == e.pkg }
                val near = (list.getOrNull(i + 1) ?: list.getOrNull(i - 1))?.pkg
                action()
                // After the menu's own return to the (now gone) tile, once the dock has been laid out again.
                scope.launch { delay(120); runCatching { (near?.let { tileFocus[it] } ?: firstTile).requestFocus() } }
            }) else PopOption(label, onClick = action)
        }, if (inFolder) null else PopAnchors.tiles[e.pkg])
    }
    // The dock gets the same two functions for as long as the home screen lives: showMenu is made again every time
    // this screen is composed (it reaches openMenu through a new holder each time), and handed to the dock as it was,
    // the dock and every icon in it were composed again, twice, at the start of each slide.
    val showMenuNow = androidx.compose.runtime.rememberUpdatedState(showMenu)
    val dockMenu = remember { { e: AppEntry -> showMenuNow.value(e, false) } }
    val folderMenu = remember { { e: AppEntry -> showMenuNow.value(e, true) } }

    // Square look: read by every glass surface.
    androidx.compose.runtime.SideEffect { GlassShape.square.value = settings.squareCorners }
    // Your own background chosen: the carousel's decoded pictures (titles, glass copies) aren't needed any more.
    LaunchedEffect(settings.bgMode) {
        if (settings.bgMode == "carousel") return@LaunchedEffect
        delay(4000)
        runCatching { SingletonImageLoader.get(ctx).memoryCache?.clear() }
        GlassCache.trim(); trimPageCaches()
        com.glasslauncher.tv.data.Glog.i("Own background: carousel pictures let go")
    }

    // My picture: the next one from the menu, and by itself (shuffle) every "Change every".
    val nextRev = CustomBackground.next.intValue
    val seenNext = remember { intArrayOf(nextRev) }
    LaunchedEffect(nextRev) { if (nextRev != seenNext[0]) { seenNext[0] = nextRev; if (settings.bgMode == "image") CustomBackground.nextPicture(ctx, settings) } }
    LaunchedEffect(settings.bgMode, settings.bgImageShuffle, settings.photoEvery, settings.bgImage) {
        if (settings.bgMode != "image" || !settings.bgImageShuffle || settings.photoEvery <= 0) return@LaunchedEffect
        delay(settings.photoEvery * 1000L)
        CustomBackground.nextPicture(ctx, settings)
    }

    // Depth effect: the subject of your photo cut out (once per photo, in the background) to cover the large clock.
    LaunchedEffect(shownBackdrop, settings.bgClock, settings.bgDepth, settings.bgMode, settings.bgClockSize, settings.bgClockOffset) {
        Depth.fg.value = null
        // Off, or a background it isn't for: nothing of it stays in memory.
        if (!settings.bgClock || !settings.bgDepth || settings.bgMode !in setOf("image", "photos")) { Depth.clear(); return@LaunchedEffect }
        val b = shownBackdrop ?: return@LaunchedEffect
        delay(settings.transitionMs.toLong() + 200)
        val fg = withContext(DepthWorker) { Depth.cutout(ctx, b, settings.bgClockSize, settings.bgClockOffset) }
        if (shownBackdrop === b) Depth.fg.value = fg
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black).onFocusChanged { homeHasFocus[0] = it.hasFocus }) {
        val density = LocalDensity.current
        val rootW = with(density) { maxWidth.roundToPx() }
        val rootH = with(density) { maxHeight.roundToPx() }
        val s = maxWidth.value / 960f
        val screenHdp = maxHeight.value
        // The veil (the page blurred and dark) comes once the dock has (almost) opened: animating both at once
        // doubled what the TV GPU drew per frame and the dock opened at about 30 fps.
        // It stays on while the Apps folder is open and as it closes: it went off under the folder, so going in and
        // coming out showed the sharp title picture for a moment before it blurred again.
        var folderLeft by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
        LaunchedEffect(folderOpen) { if (!folderOpen && folderLeft != 0L) { delay(500); folderLeft = 0L } }
        if (folderOpen) folderLeft = 1L
        // (Multi-view cameras over the home screen: the page behind them blurs too.)
        // (Multi-view over a video background: the blurred copy covers the video, which the screen's blur can't reach.)
        // Music mode: the music page stays as it is when you go down into the dock (it faded and blurred away, and
        // its artwork showed for a moment as a see-through blurred square).
        // (Only when the music page is really what shows: Nuvio / Stremio keep the carousel, and it blurs as usual.)
        val stageKeeps = MusicUi.stageOn(settings, MusicUi.heldTrack()) && !MusicUi.carouselApp.value && !ChannelsUi.pageOn.value &&
            MusicUi.songFor(MusicUi.heldTrack()) != null
        val dockVeils = expanded && !stageKeeps
        val veilIn = dockVeils || folderOpen || folderLeft != 0L || (com.glasslauncher.tv.MultiCamOverlay.open.value && (VideoFrames.showing.value || android.os.Build.VERSION.SDK_INT < 31))
        // Comes in just after the dock has grown (both at once made the opening stutter on the TV); straight away
        // when it's already up (the folder).
        val veil = animateFloatAsState(if (veilIn) 1f else 0f, if (veilIn) tween(150, delayMillis = 110) else tween(100), label = "veil")
        val blur = veil
        var dockHasFocus by remember { mutableStateOf(false) }
        // A trailer playing in the carousel: the dock steps aside (back when it stops or ends, or when you go to it).
        // (Hidden even with the dock selected: the trailer has the whole page; Back stops it and the dock comes back.)
        val trailerHides = Trailers.started.value && screen == Screen.Home && settings.trailerHideDock && settings.trailerSize != "popup" && !Trailers.dockAsked.value
        val dockShownTarget = if (trailerHides) expanded || folderOpen else !settings.dockHide || dockHasFocus || expanded || folderOpen
        // Away from the home screen (Settings, the Library): the trailer waits there and goes on when you come back.
        androidx.compose.runtime.SideEffect { Trailers.held.value = screen != Screen.Home }
        val dockShown = animateFloatAsState(if (dockShownTarget) 1f else 0f, tween(if (dockShownTarget) 200 else 260, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0f, 0f, 1f)), label = "dockShown")
        // Where the open (and settled) dock covers the page; empty while it moves.
        val dockCover = remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
        // Clear glass shows the page picture itself (full resolution, scrims included); the small
        // GlassCache copy is only the fallback before the first picture is on screen.
        // Clear glass reads the page picture itself, at its full size. (It used to read a half-size copy enlarged
        // again: the picture behind the glass was softer than the page around it. Measured on both TVs, a slide costs
        // the same either way. The smallest memory tier keeps the copy.)
        val fullGlass = settings.memTier > 0
        val pageSharp = if (PageGlass.on.value) (if (fullGlass) shownBackdrop else GlassHalf.peek(shownBackdrop)) else null
        // What stands in the carousel's place and how the page behind the dock is drawn for it: one answer, worked out
        // in HomePage.kt from the dock's selection, the song and the settings.
        val page = homePage(settings, musicWas)
        val songNow = page.song; val songShown = page.songShown
        val idleNow = page.idle; val rowsNow = page.rows; val dockMid = page.dockMid
        val stageWanted = page.stage; val heroOwn = page.heroOwn; val otherPageNow = page.other
        // The app that played last (the idle player's Play brings it back through the TV's own Play).
        val playingPkg = songNow?.takeIf { it.playing }?.pkg
        LaunchedEffect(playingPkg) { if (playingPkg != null && playingPkg != settings.musicLastPkg) settings.musicLastPkg = playingPkg }
        androidx.compose.runtime.SideEffect { MusicUi.idleOn.value = idleNow }
        // (Made ready whenever a song is on, shown or not: thrown away when its page went (another app selected, the
        // Music icon) and made again on the way back, the carousel showed meanwhile.)
        // (The song shown, when it's another app's than the last one heard: the page took the other app's artwork.)
        val stageArt = (songShown ?: songNow)?.art
        // Worked out off the main thread, and faded from song to song with the page (MusicUi.stageTo).
        // (Keyed by how the artwork looks: the app sends a new picture object with every update.) A song whose
        // artwork hasn't come yet keeps the last picture (the dock's glass flashed the carousel poster).
        val stageArtKey = remember(stageArt) { MusicUi.artKey(stageArt) }
        LaunchedEffect(stageArtKey, songNow == null) { if (songNow == null) { delay(8000); MusicUi.stageTo(null) } else if (stageArt != null) MusicUi.stageTo(stageArt) }
        // Paused: the page and its glass dim.
        val songPlaying = songNow?.playing == true
        LaunchedEffect(songPlaying, stageWanted) { MusicUi.pauseDim.animateTo(if (stageWanted && !songPlaying) 0.6f else 0f, tween(if (songPlaying) 140 else 180, easing = androidx.compose.animation.core.LinearOutSlowInEasing)) }
        val ownBg = page.ownBg; val songPage = page.songPage; val musicToRows = page.musicToRows
        // (Over your own background: once the player is drawn, so the glass changes with the page, not ahead of it.)
        val stagePicNow = MusicUi.stageCur.value?.glass?.takeIf { (songPage && (!ownBg || MusicUi.songPageUp.value)) || musicToRows }
        // Over a video: the glass goes from the video to the artwork (and back) with the page, not at once ahead of it.
        val artIn by animateFloatAsState(if (ownBg && stagePicNow != null) 1f else 0f, tween(220), label = "artGlass")
        val lastArt = remember { arrayOfNulls<ImageBitmap>(1) }
        if (stagePicNow != null) lastArt[0] = stagePicNow
        // (Also on the first frame of each way, before the fade has moved: the glass jumped to the artwork at once,
        // ahead of the page, and back to the video at once on the way out.)
        val artFade = ownBg && VideoFrames.showing.value && (if (stagePicNow != null) artIn < 0.999f else artIn > 0.001f)
        val stagePic = stagePicNow ?: lastArt[0]?.takeIf { artFade }
        val stagePrevPic = MusicUi.stagePrev.value?.glass?.takeIf { stagePic != null }
        // Another page coming while its own background is still being made (the music apps' grey, an app's rows):
        // the page and the glass show the grey one (or the rows' last background) meanwhile, never the carousel's
        // picture (it flashed in the icons and the dock for a moment).
        val bridge = if (stagePic == null && otherPageNow && !ChannelsUi.pageOn.value && settings.bgMode == "carousel") {
            val tag = ChannelPages.size(ctx, settings).third
            // (The rows' own first card when its blur is made already; else the grey. Another app's last blur showed
            // its colour for a moment.)
            // (The music apps, an idle player, the inputs: their chosen background when it is made already.)
            (if (rowsNow && settings.channelsBg == "blur") ChannelsUi.art.value?.let { ChannelPages.peekArt(it.first, tag) } else null)
                ?: (if (!rowsNow && settings.stagePageBg != "grey") StagePages.peek(settings.stagePageBg, backdrop, tag) else null) ?: ChannelPages.peekGrey(tag)
        } else null
        // (Counted as the page itself: the carousel page's dark shades aren't drawn over it, the music page has none.)
        // (Drawn as the page will be once it is up, page picture and shades alike: drawn otherwise, the dock's glass
        // was darker for a moment and then jumped lighter.)
        val bridgeSharp = bridge?.let { if (PageGlass.on.value) (if (fullGlass) it.first else GlassHalf.peek(it.first)) else null }
        val stagePrevBlur = MusicUi.stagePrev.value?.blur?.takeIf { stagePic != null }
        val videoPrev = if (artFade) VideoFrames.soft.value ?: VideoFrames.sharp.value else null
        val artMix = androidx.compose.runtime.rememberUpdatedState(artIn)
        val env = if (bridge != null) GlassEnv(bridge.second.blurred, bridgeSharp ?: bridge.second.sharp, bridge.second.heavy, rootW, rootH, settings.glassTint, settings.refraction, 1f, wobble.asState(),
            null, null, null, veil, sharpIsPage = bridgeSharp != null, pageNoSideShade = settings.slideAnim != "fade", blur = blur, blurDp = settings.blurRadius * 0.45f)
        else if (stagePic != null) GlassEnv(stagePic, stagePic, stagePic, rootW, rootH, settings.glassTint, settings.refraction, 1f, wobble.asState(),
            // (The video as the old picture: not counted as the page, so it keeps the page's shades like the live glass
            // does: counted as the page it was lighter, and the glass jumped brighter as the fade began.)
            stagePrevBlur ?: stagePrevPic ?: videoPrev, stagePrevPic, if (stagePrevPic != null) MusicUi.stageMix.asState() else if (videoPrev != null) artMix else null, MusicUi.pauseDim.asState(),
            sharpIsPage = true, blur = blur, blurDp = settings.blurRadius * 0.45f, ignoreVideo = true)
        else GlassEnv(backImgs?.first, pageSharp ?: backImgs?.second, backImgs?.third, rootW, rootH, settings.glassTint, settings.refraction, 1f, wobble.asState(),
            prevImgs?.first, if (prevImgs != null) ((if (fullGlass) prevBackdrop else GlassHalf.peek(prevBackdrop)) ?: prevImgs?.second) else null, mix.asState(), veil, sharpIsPage = pageSharp != null,
            slide = slideDir, slideKind = if (settings.slideAnim == "apple") SLIDE_APPLE else SLIDE_PUSH,
            pageNoSideShade = settings.slideAnim != "fade", blur = blur, blurDp = settings.blurRadius * 0.45f)
        val radius = settings.cornerRadius
        val metrics = remember(s, maxWidth, settings.dockScale, settings.dockColumns) { DockMetrics(s, maxWidth.value, settings.dockScale, settings.dockColumns) }

        val envRef = androidx.compose.runtime.rememberUpdatedState(env)
        CompositionLocalProvider(LocalGlassEnv provides env, LocalGlassEnvRef provides envRef, LocalScale provides s) {
            // Nothing under the folder is drawn: it covers the whole screen (the TV GPU is the bottleneck).
            // Backdrop and scrims are flattened into one cached GPU layer: moving focus in the dock then
            // composites a single texture instead of redrawing the photo and both gradients every frame.
            // The scrims are baked into the picture (bakeBackdrop): a slide change draws two flat pictures and
            // nothing else, which keeps the crossfade smooth on TV graphics chips.
            // Under the fully open veil (an opaque picture) the backdrop isn't drawn at all.
            // Music mode: the open dock (Down from the dock) showed the carousel's title picture behind it, the music
            // page fades out with the rest of the page then. The song's picture is behind it instead.
            // Switching between two pages that aren't the carousel (the player, an app's rows, the music apps, an idle
            // player): the carousel never shows in between — one went at once while the next faded in over it. The
            // last music page stays under the one coming in, or a dark page when there was none.
            val otherPage = otherPageNow
            // (The music apps and an idle player have their own grey page: the dark one under them, never the song's
            // picture.)
            val greyPage = page.grey
            // (An app's rows have their own background: the song's page only bridges until they are up.)
            val stageBack = MusicUi.stageCur.value?.takeIf { songPage || (otherPage && settings.bgMode == "carousel" && !greyPage && !ChannelsUi.pageOn.value && (musicToRows || (!ChannelsUi.shown.value && !rowsNow))) }
            val veilCovers = (stageBack?.let { it.glass ?: it.back } ?: backImgs?.third) != null
            // Under the Library (once it covers the whole screen) the title picture isn't drawn either: the TV GPU
            // painted every pixel four times there and the Library's scrolling lost frames.
            val libCovers = remember { mutableStateOf(false) }
            if (!folderOpen && DebugHide.intValue and 1 == 0 && !VideoFrames.showing.value) Box(Modifier.fillMaxSize().graphicsLayer { alpha = if ((veilCovers && blur.value > 0.995f) || libCovers.value || SettingsCovers.value) 0f else 1f }) {
                if (stageBack != null) {
                    // (The page it fades in over: a song's last picture, or the channels' background.)
                    // (Drawn as the music page looks, darkened: drawn plain it was brighter, and when the music page
                    // over it had faded in, the screen jumped darker.)
                    val sp = MusicUi.stagePrev.value
                    if (sp != null) Image(if (sp.page) sp.back else (sp.glass ?: sp.back), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Image(stageBack.glass ?: stageBack.back, null, Modifier.fillMaxSize().graphicsLayer { alpha = if (sp != null) MusicUi.stageMix.value else 1f }, contentScale = ContentScale.Crop)
                }
                // (Once the page's own background is up (the rows' blurred card, the grey page) it shows below.)
                else if (otherPage && !ChannelsUi.pageOn.value && settings.bgMode == "carousel") {
                    // (Drawn as the page is once it is up, its shade included: drawn plain it was lighter, then
                    // darkened when the rows came.)
                    if (bridge != null) { if (settings.slideAnim != "fade") SlideBackdrop(null, bridge.first, mix, 0, SLIDE_PUSH) else FadeBackdrop(null, bridge.first, mix) }
                    else Box(Modifier.fillMaxSize().background(Color(0xFF141416)))
                }
                else if (settings.slideAnim != "fade") SlideBackdrop(prevBackdrop, shownBackdrop, mix, slideDir,
                    if (settings.slideAnim == "apple") SLIDE_APPLE else SLIDE_PUSH)
                else FadeBackdrop(prevBackdrop, shownBackdrop, mix)
            }
            // Screensaver as background: one of its aerial videos (your links and / or Apple's feed).
            val aerialKey = settings.saverSources.joinToString() + settings.saverQuality + settings.saverPick + settings.saverMine + settings.bgAerialQuality
            var lastAerialKey by remember { mutableStateOf(aerialKey) }
            LaunchedEffect(settings.bgMode, aerialKey) {
                // The screensaver's video choice changed: pick again from the new list.
                if (aerialKey != lastAerialKey) { lastAerialKey = aerialKey; CustomBackground.aerial.value = null }
                if (settings.bgMode == "aerial" && CustomBackground.aerial.value == null)
                    CustomBackground.aerial.value = withContext(Dispatchers.IO) {
                        runCatching {
                            // 1080 H.264 (light behind the glass), or the screensaver's own quality (its saved videos).
                            val items = if (settings.bgAerialQuality == "saver") com.glasslauncher.tv.saver.AerialSource.playlistItems(app)
                                else com.glasslauncher.tv.saver.AerialSource.playlistItems(app, "1080-H264")
                            CustomBackground.aerialTitles = items.associate { (t, u) -> u to t }
                            // The videos already on the TV first: they start at once.
                            val (kept, rest) = items.map { it.second }.partition { settings.saverCache && com.glasslauncher.tv.saver.VideoCache.fullyCached(app, settings.saverCacheGb, it) }
                            com.glasslauncher.tv.data.Glog.i("Screensaver background: ${kept.size} of ${items.size} videos already on the TV")
                            (kept + rest).also { CustomBackground.aerials = it }.let { CustomBackground.firstLive(it) }
                        }.getOrNull()
                    }
                        .also { com.glasslauncher.tv.data.Glog.i("Screensaver background: ${it ?: "no aerial videos (screensaver links / Apple feed)"}") }
            }
            val videoSrc = when (settings.bgMode) { "video" -> settings.bgVideo.ifBlank { null }; "aerial" -> CustomBackground.aerial.value; else -> null }
            // No video any more (another background chosen mid-fade): nothing stays dark.
            LaunchedEffect(videoSrc == null) { if (videoSrc == null) VideoFrames.dim.floatValue = 0f }
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
                    // The large clock's glass digits are drawn from the sharp copy (blurred on the GPU), like the Still Glass.
                    VideoFrames.wantSharp = st.any { it.startsWith("water") } || settings.bgClock
                    VideoFrames.wantSoft = st.any { it == "liquid" || it == "clear" }
                    // The frosted copy of the video follows the Glass blur setting, like the pictures' glass copies.
                    VideoFrames.softRadius = (settings.blurRadius / 13).coerceIn(1, 6)
                }
                // The screensaver's videos: one player for the whole list (it isn't rebuilt for every video, so the
                // next one comes from the screensaver's cache straight away); your video: that one, looping.
                val aerialMode = settings.bgMode == "aerial"
                val mine = remember(settings.bgVideo, settings.bgVideoShuffle) { CustomBackground.videoList(ctx, settings) }
                val list = if (aerialMode) CustomBackground.aerials.ifEmpty { listOf(videoSrc) } else mine
                // Not rebuilt when you come back from another app or the screensaver: the player keeps going on the new
                // surface (a new player showed the last frame frozen in the glass, then black, until it started).
                androidx.compose.runtime.key(if (aerialMode) list else videoSrc) {
                    VideoBackground(list, start = list.indexOf(videoSrc).coerceAtLeast(0),
                        playing = screen == Screen.Home && state.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) && DebugHide.intValue and 4096 == 0,
                        advance = if (aerialMode) settings.bgShuffle else settings.bgVideoShuffle,
                        onItem = { if (aerialMode) CustomBackground.aerial.value = it })
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
                val img = if (!VideoFrames.showing.value && DebugHide.intValue and 32768 == 0) (stageBack?.let { it.glass ?: it.back } ?: backImgs?.third) else null
                if (img == null) Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }.background(Color.Black.copy(alpha = VEIL_DIM)))
                // Not drawn where the open dock covers it (its glass is opaque): on the TV GPU every pixel drawn twice counts.
                if (img != null) {
                    // The picture darkened once (a small copy), not by a colour filter in every frame of the fade.
                    val dark = remember(img) { VeilPic.of(img) }
                    val veilFilter = if (dark === img) VeilFilter else null
                    val brush = remember(dark, rootW, rootH) {
                        @Suppress("NAME_SHADOWING") val img = dark
                        val bw = img.width.toFloat(); val bh = img.height.toFloat()
                        val sc = maxOf(rootW / bw, rootH / bh)
                        val sh = android.graphics.BitmapShader(img.asAndroidBitmap(), android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                        sh.setLocalMatrix(android.graphics.Matrix().apply { setScale(sc, sc); postTranslate((rootW - bw * sc) / 2f, (rootH - bh * sc) / 2f) })
                        androidx.compose.ui.graphics.ShaderBrush(sh)
                    }
                    val cornerPx = with(density) { (radius * s).dp.toPx() }
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = blur.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }) {
                        val d = dockCover.value
                        fun part(l: Float, t: Float, r: Float, b: Float) { if (r > l && b > t) drawRect(brush, androidx.compose.ui.geometry.Offset(l, t), androidx.compose.ui.geometry.Size(r - l, b - t), colorFilter = veilFilter) }
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
                // (Moving from the first frame: the old curve started slowly and the page seemed to wait before it went.)
                tween(400, easing = androidx.compose.animation.core.CubicBezierEasing(0.2f, 0.0f, 0.0f, 1f)), label = "libScroll")
            LaunchedEffect(screen) {
                if (screen != Screen.Library && libFromDock) { androidx.compose.runtime.snapshotFlow { libScroll.value }.first { it < 0.01f }; libFromDock = false }
            }
            // Fully covered by settings or the folder: stop drawing it at all.
            val homeAlphaState = animateFloatAsState(if ((screen == Screen.Home || (screen == Screen.Library && libFromDock)) && !folderOpen) 1f else 0f, tween(180), label = "homeAlpha")
            // (The glass on the page reads the picture by its place in the page, not on screen: nothing to tell it
            // while the page slides or is away, see GlassPage.)
            val libFull by remember { androidx.compose.runtime.derivedStateOf { libScroll.value >= 0.999f } }
            val libOpaque by remember(screen) { androidx.compose.runtime.derivedStateOf { screen == Screen.Library && (!libFromDock || libScroll.value >= 0.999f) } }
            androidx.compose.runtime.SideEffect { libCovers.value = libOpaque }
            // Once the Library covers the page the dock closes behind it, so coming back shows it closed (its dimmed
            // open look flashed dark for a moment on the way back).
            LaunchedEffect(libFull) { if (libFull) expanded = false }
            val homeVisible by remember { androidx.compose.runtime.derivedStateOf { homeAlphaState.value > 0.001f && libScroll.value < 0.999f } }
            // Kept composed under the Library and Settings (only not drawn): leaving the composition meant the whole
            // home screen (carousel, glass, dock, backdrop) was built again on the way back, and the first second
            // there stuttered. The remote can't go into it while it's hidden: the group lets no focus in from outside
            // (Right in Settings once went to the hidden dock instead of the options).
            val homeTakesFocus = screen == Screen.Home && pickerFor == null && !folderOpen && !(menuOpen && menuHasFocus) && pop == null
            if ((screen == Screen.Home && !folderOpen) || homePrep || libFromDock || libScroll.value > 0.001f || homeVisible || screen == Screen.Settings) Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = if (homeVisible || homePrep || (screen == Screen.Home && !folderOpen)) homeAlphaState.value else 0f; translationY = -libScroll.value * size.height }
                    .onGloballyPositioned { GlassPage.coords = it }
                    .focusProperties {
                        canFocus = homeTakesFocus
                        enter = { if (homeTakesFocus) androidx.compose.ui.focus.FocusRequester.Default else androidx.compose.ui.focus.FocusRequester.Cancel }
                    }
                    .onFocusChanged { homeGroupHas[0] = it.hasFocus }
                    // ▼ while a trailer has the dock hidden: the dock comes back first (with the dock already selected,
                    // that press only shows it; it used to go on down to the Library).
                    .onPreviewKeyEvent { ev ->
                        // Up held from the Library back to the dock: it stops there until the button is let go.
                        if (ev.key == Key.DirectionUp && LibPace.swallowUp) {
                            if (ev.type == KeyEventType.KeyUp || ev.nativeKeyEvent.repeatCount == 0) LibPace.swallowUp = false
                            return@onPreviewKeyEvent ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount > 0 || ev.type == KeyEventType.KeyUp
                        }
                        if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown && Trailers.started.value && settings.trailerHideDock &&
                            settings.trailerSize != "popup" && !Trailers.dockAsked.value) { Trailers.dockAsked.value = true; dockHasFocus } else false
                    }
                    .focusGroup()
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (DebugHide.intValue and 2 != 0) 0f else 1f }.onFocusChanged { DockFocus.hero = it.hasFocus }) {
                    // Channels (Settings → Channels): the selected app's rows stand in the carousel's place. They come a
                    // moment after the app is selected (moving along the dock doesn't flash them), and stay while the
                    // selection is inside them.
                    val dockPkg = ChannelsUi.dockPkg.value
                    val appTitles by app.catalogs.appItems.collectAsState()
                    // (A music app's own song starting or stopping: its player or its rows. Only with the selection in the dock:
                    // Play on an idle player (the selection in it) started the song, the idle player went, and the app's
                    // player was dropped as if nothing were selected — the carousel came and the selection with it.)
                    val trackPkgNow = MusicUi.heldTrack()?.pkg
                    // What stands in the carousel's place for the app selected in the dock: its rows, a music app's player
                    // (also with nothing playing), the music apps (the Music icon), or the carousel (Nuvio / Stremio and
                    // apps without rows). Worked out once the selection rests a moment, and all changed together: cleared
                    // at once, the carousel (or the music page) flashed between two of them.
                    LaunchedEffect(dockPkg, settings.musicOn, settings.channelsOn, settings.channelsMusicRowApps, settings.channelsOffApps, settings.musicIdleOn, settings.musicIdleOffApps, dockMid, trackPkgNow.takeIf { dockPkg != null }, com.glasslauncher.tv.data.Channels.all.value, expanded, pop != null, pickerFor != null,
                        settings.appCatalogsOn, settings.appStage, appTitles) {
                        if (dockPkg == null) {
                            // (Down into the open dock's second row: what shows above stays, its background too.)
                            delay(150)
                            // (An app no longer installed goes in any case.)
                            MusicUi.dockApp.value?.let { p -> if (runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isFailure) MusicUi.dockApp.value = null }
                            // (Also with an app's menu open (hold OK), or its icon picker: what shows stays behind it.)
                            if (!expanded && pop == null && pickerFor == null) {
                                if (!ChannelsUi.focused) ChannelsUi.stagePkg.value = null
                                // (Kept while the selection is in the player, or nowhere for a moment: Play on an idle player swaps
                                // it for the app's player, the selection goes with the idle one, and the app's player was dropped
                                // as if another place had been chosen — the carousel came, and the selection with it.)
                                if (!MusicUi.stageFocused && !DockFocus.hero && (DockFocus.top || DockFocus.dock)) MusicUi.dockApp.value = null
                                // (Up into the Music icon's player: still the Music icon's, Stop / pause come back to it.)
                                if (!MusicUi.appsFocused && !MusicUi.stageFocused) MusicUi.appsOn.value = false
                                if (!InputsUi.focused && !InputsUi.held()) InputsUi.on.value = false
                                if (!SearchUi.focused && !SearchUi.held()) SearchUi.on.value = false
                            }
                            return@LaunchedEffect
                        }
                        val inputsIcon = !dockMid && settings.inputsIcon && dockPkg == com.glasslauncher.tv.data.Inputs.DOCK_PKG
                        val searchIcon = SearchUi.inPlace(settings) && settings.searchOn && dockPkg == SearchUi.DOCK_PKG
                        val musicIcon = !dockMid && settings.musicOn && dockPkg == MusicUi.DOCK_PKG
                        val musicApp = !dockMid && !musicIcon && MusicUi.playerFor(ctx, settings, dockPkg)
                        val hasRows = !dockMid && settings.channelsOn && !musicIcon && !musicApp && com.glasslauncher.tv.data.Channels.rowsFor(settings, dockPkg).isNotEmpty()
                        // Catalogs of this app (see AppCatalogs): its rows, its catalogs in the carousel, or nothing.
                        val AC = com.glasslauncher.tv.data.AppCatalogs
                        val hasCats = settings.appCatalogsOn && !musicIcon && !musicApp && !inputsIcon && !searchIcon && !app.catalogs.appItems.value[dockPkg].isNullOrEmpty()
                        val stand = if (settings.appCatalogsOn) AC.effective(AC.stage(settings, dockPkg), hasRows, hasCats) else if (hasRows) AC.ROWS else AC.NONE
                        val rows = hasRows && stand == AC.ROWS
                        val cats = hasCats && stand == AC.CATALOGS
                        // (To the search, and away from it to whatever is next to it: at once, both ways. The longer rest is for
                        // pages that would flash while the selection only passes over their icon.)
                        val fromSearch = SearchUi.on.value && !searchIcon
                        // (Unless the selection is only passing over the Search icon: walking along the dock across it,
                        // its page came up and went again at every crossing, two page changes of 50-77 ms frames on the
                        // TV's GPU (17 ms between other icons). Arrived within half a second of the move before, it waits
                        // 0.42 s (a person's steps along the dock come every 0.3-0.4 s); one press onto it, after a rest,
                        // still opens it at once.)
                        val nowMs = android.os.SystemClock.uptimeMillis()
                        val passing = nowMs - dockMovedAt[0] < 500L
                        dockMovedAt[0] = nowMs
                        SearchUi.passing.value = searchIcon && passing
                        // (The same for every icon with a page of its own: on his dock the Search icon sits between the Music icon and
                        // SmartTube's rows, and walking past the three brought up three pages in turn.)
                        val ownPage = musicIcon || musicApp || rows || inputsIcon || cats || AC.pkg.value != null
                        delay(if (searchIcon) (if (passing) 420 else 50) else if (fromSearch) 30 else if (ownPage) (if (passing) 420 else 240) else 90)
                        // (Another app's titles come in like the next slide. With nothing selected in the dock the list
                        // stays: Up to the carousel is how one of its titles is opened.)
                        if (AC.pkg.value != dockPkg.takeIf { cats }) { SlideDir.dir = 1; app.navAt = System.currentTimeMillis() }
                        AC.pkg.value = dockPkg.takeIf { cats }
                        // Another app selected: the player the Music icon opened gives way (its rows stayed away).
                        if (dockPkg != MusicUi.DOCK_PKG) MusicUi.forced.value = false
                        MusicUi.carouselApp.value = com.glasslauncher.tv.data.Channels.keepsCarousel(dockPkg)
                        // Another app's rows: their background starts from their first card (the card selected on an
                        // earlier visit showed its blur for a moment first).
                        if (rows && ChannelsUi.stagePkg.value != dockPkg) ChannelsUi.art.value =
                            com.glasslauncher.tv.data.Channels.rowsFor(settings, dockPkg).firstOrNull()?.programs?.firstOrNull()?.poster?.takeIf { it.isNotBlank() }?.let { it to null }
                        ChannelsUi.stagePkg.value = dockPkg.takeIf { rows }
                        MusicUi.dockApp.value = dockPkg.takeIf { musicApp }
                        MusicUi.appsOn.value = musicIcon
                        InputsUi.on.value = inputsIcon
                        SearchUi.on.value = searchIcon
                    }
                    LaunchedEffect(settings.channelsOn) { if (settings.channelsOn) com.glasslauncher.tv.data.Channels.start(ctx) }
                    // Each dock app's catalogs are loaded ahead, and again every half hour, off the main thread: the
                    // carousel changes to a list that is already there.
                    val catalogRefs by app.catalogs.catalogs.collectAsState()
                    LaunchedEffect(settings.appCatalogsOn, settings.appCatalogs, catalogRefs, settings.perCatalog, settings.maxItems) {
                        if (!settings.appCatalogsOn) { com.glasslauncher.tv.data.AppCatalogs.pkg.value = null; app.catalogs.appItems.value = emptyMap(); return@LaunchedEffect }
                        while (true) {
                            runCatching { app.catalogs.loadApps(com.glasslauncher.tv.data.AppCatalogs.links(settings, catalogRefs, app.apps.ordered().map { it.pkg })) }
                                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it; com.glasslauncher.tv.data.Glog.w("Catalogs of apps", it) }
                            delay(30 * 60 * 1000L)
                        }
                    }
                    val chanPkg = ChannelsUi.stagePkg.value
                    val chanRows = if (chanPkg != null && !folderOpen && screen == Screen.Home) com.glasslauncher.tv.data.Channels.rowsFor(settings, chanPkg) else emptyList()
                    // The dock's Music icon puts the player in the carousel's place: no rows then. With the dock open the
                    // rows go, their background stays behind it (chanPage).
                    // (Nor under the search opened from the menu or a remote button: the search has the place then.)
                    val chanPage = chanPkg != null && chanRows.isNotEmpty() && !MusicUi.forced.value && !SearchUi.on.value
                    val chanOn = chanPage && !expanded
                    // The music apps (the Music icon), on the grey page; kept behind the open dock. Not while a song plays:
                    // its player then.
                    val songNowHere by com.glasslauncher.tv.data.NowPlaying.track.collectAsState()
                    val heldHere = MusicUi.heldTrack()
                    val appsPage = MusicUi.appsShowing && settings.musicOn && !folderOpen && screen == Screen.Home &&
                        // (Paused too: the player stays, paused; the music apps once the song has stopped.)
                        // (The song held over a short gap: between two videos SmartTube's song goes for a second or two, and the
                        // music apps came up in between.)
                        (heldHere ?: songNowHere).let { t -> !(t != null && MusicUi.stageOn(settings, t)) }
                    val appsShow = appsPage && !expanded
                    // The Inputs icon: its inputs, on the same grey page.
                    val inputsPage = InputsUi.on.value && settings.inputsIcon && !folderOpen && screen == Screen.Home
                    val inputsShow = inputsPage && !expanded
                    androidx.compose.runtime.SideEffect { InputsUi.page.value = inputsPage }
                    // The Search icon: its bar and the titles found, on the same page.
                    val searchPage = SearchUi.on.value && settings.searchOn && !folderOpen && screen == Screen.Home && SearchUi.inPlace(settings)
                    val searchShow = searchPage && !expanded
                    androidx.compose.runtime.SideEffect { SearchUi.page.value = searchPage }
                    androidx.compose.runtime.SideEffect { MusicUi.appsVisible.value = appsShow; MusicUi.appsPage.value = appsPage }
                    // Paused or stopped on the Music icon: the player gives way to the music apps, the selection goes back
                    // to the icon (it was left in the player going away, then went to the first app).
                    LaunchedEffect(appsShow) {
                        if (appsShow && MusicUi.stageFocused) { delay(60); runCatching { tileFocus[MusicUi.DOCK_PKG]?.requestFocus() } }
                    }
                    HeroSection(app, s, hidden = dockVeils || folderOpen || chanPage || appsPage || inputsPage || searchPage || (otherPageNow && !heroOwn), keepHidden = dockVeils || folderOpen || chanPage || appsPage || inputsPage || searchPage, quickHide = (chanPage || appsPage || inputsPage || searchPage || (otherPageNow && !heroOwn)) && !musicWas[0] && settings.bgMode == "carousel", paused = screen != Screen.Home || pop != null || WhatsNew.open.value || chanPage || appsPage || inputsPage, metrics = metrics, shownUrl = shownUrl, dockShown = dockShown,
                        // (Showing an app's catalogs: Down from the carousel goes back to that app, so its titles stay.)
                        heroFocus = heroFocus, firstTile = com.glasslauncher.tv.data.AppCatalogs.pkg.value?.let { tileFocus[it] } ?: firstTile, onBackdrop = { backdrop = it },
                        onPop = { popReturn = heroFocus; pop = it },
                        onCustomUp = {
                            if (settings.menuMode == "auto") { menuClosedAt = 0L; menuOpen = true }
                            else scope.launch { delay(16); runCatching { gearReq.requestFocus() } }
                        })
                    androidx.compose.runtime.SideEffect { ChannelsUi.shown.value = chanPage }
                    // ▲ from the top of the rows or the music apps: the menu (or the settings button), they stay under it.
                    val toMenu: () -> Unit = {
                        if (settings.menuMode == "auto") { menuClosedAt = 0L; menuOpen = true }
                        else runCatching { gearReq.requestFocus() }
                    }
                    // The rows fade in and out (they went in one frame, the page under them bare for a moment), in
                    // step with the music page or the carousel coming back.
                    val chanA by animateFloatAsState(if (chanOn) 1f else 0f, tween(if (chanOn) 220 else 240), label = "chanStage")
                    val lastChan = remember { arrayOfNulls<Pair<String, List<com.glasslauncher.tv.data.Channels.Channel>>>(1) }
                    if (chanOn) lastChan[0] = chanPkg to chanRows
                    val stageNow = lastChan[0]
                    if (stageNow != null && (chanOn || chanA > 0.01f)) Box(Modifier.fillMaxSize().graphicsLayer { alpha = chanA }) { ChannelStage(app, s, stageNow.first, stageNow.second, bottom = metrics.collapsedH + metrics.bottomMargin + 18 * s,
                        onDown = { if (runCatching { tileFocus[stageNow.first]?.requestFocus() ?: error("") }.isFailure) runCatching { firstTile.requestFocus() } },
                        onUp = toMenu) }
                    // The Music icon selected: the music apps, faded in and out like the rows.
                    val appsA by animateFloatAsState(if (appsShow) 1f else 0f, tween(if (appsShow) 140 else 240), label = "musicApps")
                    if (appsShow || appsA > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = appsA }) {
                        MusicAppsStage(app, s, metrics, radius, bottom = metrics.collapsedH + metrics.bottomMargin + 18 * s,
                            onDown = { if (runCatching { tileFocus[MusicUi.DOCK_PKG]?.requestFocus() ?: error("") }.isFailure) runCatching { firstTile.requestFocus() } },
                            onUp = toMenu)
                    }
                    val inputsA by animateFloatAsState(if (inputsShow) 1f else 0f, tween(if (inputsShow) 140 else 240), label = "inputsPage")
                    if (inputsShow || inputsA > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = inputsA }) {
                        InputsStage(app, s, metrics, radius, bottom = metrics.collapsedH + metrics.bottomMargin + 18 * s,
                            onDown = { if (runCatching { tileFocus[com.glasslauncher.tv.data.Inputs.DOCK_PKG]?.requestFocus() ?: error("") }.isFailure) runCatching { firstTile.requestFocus() } },
                            onUp = toMenu,
                            onPickIcon = { e -> pickerFor = e })
                    }
                    val searchA by animateFloatAsState(if (searchShow) 1f else 0f, tween(if (searchShow) 110 else 100), label = "searchPage")
                    // (Each thing's own alpha, no layer of the whole screen for the fade: one full-screen buffer less on the TV GPU.)
                    if (searchShow || searchA > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = searchA; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }) {
                        SearchStage(app, s, bottom = metrics.collapsedH + metrics.bottomMargin + 18 * s,
                            leave = { if (runCatching { tileFocus[SearchUi.DOCK_PKG]?.requestFocus() ?: error("") }.isFailure) runCatching { firstTile.requestFocus() } },
                            // (Stay in the search: ▲ from the bar goes nowhere.)
                            toMenu = { if (!settings.searchStay) toMenu() })
                    }
                }
                if (DebugHide.intValue and 4 == 0) TopBar(app, s, gearReq, menuOpen = menuOpen, onSettings = { onScreen(Screen.Settings) },
                    // Opens at once, also right after closing: the focus now goes back to Play (not to this button) when
                    // the menu closes, so the old 400 ms wait only showed the settings button for a moment first.
                    onMenu = { _ -> if (!menuOpen) menuOpen = true }, modifier = Modifier.fillMaxWidth().align(Alignment.TopStart))
                // Your own background: the dock may sit in the middle or as a column on the left.
                val customPos = if (settings.bgMode != "carousel") settings.dockPos else "bottom"
                Dock(
                    vertical = customPos == "left", centered = customPos == "center",
                    // (Stay in the search: ▼ on the Search icon doesn't open the Library.)
                    onLibrary = { if (!(settings.searchStay && SearchUi.on.value)) { libFromDock = true; onScreen(Screen.Library) } },
                    onRight = { if (settings.menuMode == "auto") { menuClosedAt = 0L; menuOpen = true } else runCatching { gearReq.requestFocus() } },
                    app = app, s = s, metrics = metrics, screenH = screenHdp, radius = radius,
                    expanded = expanded, onExpanded = { expanded = it },
                    movingPkg = movingPkg, onMoveKey = onMoveKey, firstTile = firstTile, heroFocus = heroFocus,
                    folderFocus = folderFocus, tileFocus = tileFocus, onMove = kick,
                    onMenu = dockMenu,
                    onFolder = { folderOpen = true },
                    onFolderMenu = {
                        popReturn = folderFocus
                        pop = PopState("Apps", listOf(
                            PopOption("Move") { movingPkg = FOLDER },
                            PopOption("Hide from the dock") {
                                settings.showFolder = false
                                scope.launch { delay(80); runCatching { firstTile.requestFocus() } }
                                android.widget.Toast.makeText(ctx, "Apps folder hidden. Show it again: Settings → Dock & icons", android.widget.Toast.LENGTH_LONG).show()
                            },
                        ), PopAnchors.tiles[FOLDER])
                    },
                    modifier = Modifier.align(when (customPos) { "center" -> Alignment.Center; "left" -> Alignment.CenterStart; else -> Alignment.BottomCenter })
                        .onFocusChanged { dockHasFocus = it.hasFocus; DockFocus.dock = it.hasFocus }
                        .graphicsLayer {
                            alpha = if (DebugHide.intValue and 8 != 0) 0f else 1f
                            translationY = (1f - dockShown.value) * (metrics.collapsedH + metrics.bottomMargin + 6 * s) * this.density
                            if (customPos == "left") { translationY = 0f; translationX = -(1f - dockShown.value) * (metrics.cellW + 60 * s) * this.density }
                        },
                    onCover = { dockCover.value = it },
                )
                // A trailer in its own window: over the dock and the dots too (only the pop-up menu is above it).
                if (settings.trailerSize == "popup") TrailerLayer(app, s)
                // The music island at the top of the home screen (Settings → Music), when Music mode isn't showing.
                // Over the channels' rows too (the music page gives way to them): kept built and faded in and out with
                // the rows (built each time they came, it appeared late and went at once).
                val homeIsland = settings.islandHome && (!settings.musicMode || !MusicUi.stageAllowed(settings)) && MusicUi.dockApp.value == null && !(stageWanted && !ChannelsUi.shown.value)
                // (Never with the search: its bar has the top of the screen.)
// (Gone as soon as its icon is selected, before its page is up: it lingered over the bar for a moment.)
                // (Not while the selection is only passing over its icon: the island went and came back at every crossing.)
                val searchUp = SearchUi.page.value || SearchUi.full.value || (settings.searchOn && SearchUi.inPlace(settings) && ChannelsUi.dockPkg.value == SearchUi.DOCK_PKG && !SearchUi.passing.value)
                val chanIsland0 = !searchUp && (ChannelsUi.shown.value || MusicUi.appsPage.value || InputsUi.page.value || (MusicUi.carouselApp.value && settings.musicMode))
                // (Never over the player itself: the Music icon's or a music app's own player showed it twice.)
                val playerUp = stageWanted && !ChannelsUi.shown.value
                val chanIsland = chanIsland0 && !playerUp
                val islandA by animateFloatAsState(if ((homeIsland && !searchUp) || chanIsland) 1f else 0f, tween(if (searchUp) 70 else if (chanIsland) 260 else 200), label = "chanIsland")
                LaunchedEffect(chanIsland, homeIsland) { if (!chanIsland && !homeIsland) MusicUi.islandOpen.value = false }
                if (settings.musicOn && !MusicUi.forced.value && (homeIsland || settings.musicMode))
                    Box(Modifier.fillMaxSize().graphicsLayer { alpha = islandA }) { if (islandA > 0f || (homeIsland && !searchUp)) MusicIsland(app, s) }
            }
            // The folder fades out over the home screen instead of vanishing: the dock's glass is drawn again under it
            // first (it showed black for a couple of frames as the folder closed).
            androidx.compose.animation.AnimatedVisibility(folderOpen, enter = androidx.compose.animation.EnterTransition.None,
                exit = androidx.compose.animation.fadeOut(tween(220, delayMillis = 60))) {
                FolderOverlay(
                    app, s, metrics, radius, screenHdp,
                    moving = folderMoving, onMoveDone = { folderMoving = null }, blocked = pickerFor != null,
                    onMenu = folderMenu,
                    onClose = {
                        folderMoving = null
                        folderOpen = false
                        val byKey = folderByKey; folderByKey = false
                        scope.launch { delay(150); runCatching { (if (byKey) firstTile else folderFocus).requestFocus() } }
                    },
                )
            }
            if (screen == Screen.Home && !folderOpen) UpdateBanner(app, s, Modifier.align(Alignment.TopEnd).padding(end = (34 * s).dp))
            // The search over the whole screen: where its page can't stand above the dock (the dock in the middle or as
            // a column), from OK on its icon, the menu's row or a remote button. Back, or ▼ past its last row, closes it.
            if (SearchUi.full.value && screen == Screen.Home && settings.searchOn && !SearchUi.inPlace(settings)) {
                val closeSearch = {
                    SearchUi.full.value = false
                    scope.launch { repeat(6) { if (runCatching { tileFocus[SearchUi.DOCK_PKG]?.requestFocus() ?: firstTile.requestFocus() }.getOrDefault(false)) return@launch; delay(40) } }
                    Unit
                }
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)).focusGroup()) {
                    SearchStage(app, s, bottom = 20 * s, leave = closeSearch, toMenu = { })
                }
            } else if (SearchUi.full.value) androidx.compose.runtime.SideEffect { SearchUi.full.value = false }
            if (screen == Screen.Home && menuOpen) SideMenu(
                app, s, modifier = Modifier.align(Alignment.TopEnd).onFocusChanged { menuHasFocus = it.hasFocus },
                onClose = {
                    menuClosedAt = android.os.SystemClock.uptimeMillis()
                    // With your own background there is no carousel to go back to (its stand-in passes focus up to
                    // the settings button, which opened the menu again): back to the dock.
                    val custom = when (settings.bgMode) { "image" -> settings.bgImage.isNotBlank(); "video" -> settings.bgVideo.isNotBlank(); "aerial", "photos" -> true; else -> false }
                    // The channels' rows on screen: back into their top row.
                    // (Into the music apps too: the selection was lost there.)
                    // (Into the search bar and the inputs too: their pages stay under the menu, the carousel's button doesn't exist then.)
                    // (The search first: asked for from the menu over an app's rows, the selection went into the rows.)
                    val back = if (SearchUi.page.value || SearchUi.pending || SearchUi.full.value) SearchUi.entry else if (ChannelsUi.showing) ChannelsUi.entry else if (MusicUi.appsVisible.value) MusicUi.appsEntry
                        else if (InputsUi.page.value) InputsUi.entry else if (custom) firstTile else heroFocus
                    // The focus goes back first, straight from the menu, then the menu closes.
                    menuHasFocus = false
                    runCatching { back.requestFocus() }
                    menuOpen = false
                    scope.launch { delay(30); runCatching { back.requestFocus() } }
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
            // Also kept under Settings: closing Settings built the whole Library page again, and the first frame back
            // on the home screen took a fifth of a second.
            if (screen == Screen.Library || libMoving || (libWarm && settings.libraryDown && (screen == Screen.Home || screen == Screen.Settings))) Box(Modifier.fillMaxSize().graphicsLayer {
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
                        // (An input on the Inputs icon's page: back to it there.)
                        val back = (if (InputsUi.page.value) InputsUi.tiles[e.pkg] else null) ?: tileFocus[e.pkg] ?: firstTile
                        if (InputsUi.page.value) InputsUi.holdUntil = android.os.SystemClock.uptimeMillis() + 1500
                        pickerFor = null
                        scope.launch { delay(200); runCatching { back.requestFocus() } }
                    },
                )
            }
            DialogHost(dialog) {
                dialog = null
                // (An app's Catalogs: back on its icon, where its menu was opened.)
                dialogReturn[0]?.let { r -> dialogReturn[0] = null; scope.launch { delay(80); runCatching { r.requestFocus() } } }
            }
            // The 4-digit code for a locked app (or to change the code).
            AppLockHost(app, s, settings.menuStyle)
            // What's new: once after an update (Settings → About opens it again).
            LaunchedEffect(Unit) {
                if (settings.setupDone && settings.tourSeen < WhatsNew.ID) { delay(1500); WhatsNew.show() }
            }
            WhatsNewHost(app, s)
            // The tour's last page: "Send a gift card" opens the support window straight away.
            LaunchedEffect(WhatsNew.support.value) {
                if (WhatsNew.support.value) {
                    delay(150)
                    dialog = SupportDialog({ com.glasslauncher.tv.data.Support.clean(it) }) { c, n -> com.glasslauncher.tv.data.Support.send(c, n, app.updater.current) }
                    WhatsNew.support.value = false // last: it restarts this effect
                }
            }
            PopMenuHost(pop, s, settings.menuStyle) {
                pop = null
                // Back to what it was opened on (unless a choice moved the focus on, e.g. Move or another dialog).
                val back = popReturn; popReturn = null
                if (back != null) scope.launch { delay(30); if (dialog == null && pickerFor == null) runCatching { back.requestFocus() } }
            }
        }
    }
}

private fun appMenu(
    app: GlassApp, e: AppEntry, ctx: android.content.Context,
    startMove: (() -> Unit)?, pickIcon: () -> Unit, verified: Boolean = false, pickCatalogs: (() -> Unit)? = null,
): DialogState {
    val hidden = e.pkg in app.settings.hiddenApps
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    if (hidden) opts += "Open" to { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) }
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
    // Its rows in the carousel's place (Settings → Channels): hidden or shown again from here too.
    val st = app.settings
    if (st.channelsOn && com.glasslauncher.tv.data.Channels.allFor(st, e.pkg).isNotEmpty()) {
        val off = e.pkg in st.channelsOffApps
        opts += (if (off) "Show its rows" else "Hide its rows") to {
            st.channelsOffApps = if (off) st.channelsOffApps - e.pkg else st.channelsOffApps + e.pkg
            toast(ctx, if (off) "${e.label}: its rows show again" else "${e.label}: the carousel stays in place of its rows")
        }
    }
    // Its catalogs in the carousel while it is selected (see AppCatalogs); not for the apps that keep the carousel anyway.
    if (pickCatalogs != null && app.catalogs.catalogs.value.isNotEmpty() && !com.glasslauncher.tv.data.Channels.keepsCarousel(e.pkg)) opts += "Catalogs" to pickCatalogs
    opts += (if (hidden) "Show in dock" else "Hide") to { app.apps.toggleHidden(e.pkg) }
    // Asks for the 4-digit code when opened from Glassio (Settings → System sets the code).
    opts += (if (AppLock.locked(app, e.pkg)) "Unlock" else "Lock") to { AppLock.toggle(app, ctx, e, verified) }
    return MenuDialog(e.label, opts)
}

/** Set just before the screensaver is started from the home screen; cleared when the home screen is back in front. */
object SaverGate {
    val opening = androidx.compose.runtime.mutableStateOf(false)
    /** Black over the home screen when the screensaver has just closed (faded to black): it fades out on resume. */
    val cover = androidx.compose.runtime.mutableFloatStateOf(0f)
}

/** Swallows a key for a moment after a page change, so presses meant for the old page don't run on into the next. */
object NavGate {
    @Volatile private var key = 0
    @Volatile private var until = 0L
    fun hold(keyCode: Int, ms: Long) { key = keyCode; until = android.os.SystemClock.uptimeMillis() + ms }
    fun blocks(keyCode: Int): Boolean = keyCode == key && android.os.SystemClock.uptimeMillis() < until
}
