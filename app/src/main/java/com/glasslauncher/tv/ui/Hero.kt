// The carousel: its picture (slide and fade), the title block, the buttons, the page dots.
// (Split out of HomeScreen.kt.)
package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.async
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.NuvioLaunch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

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

/** Which way the next slide moves (set by the remote / auto-play just before the index changes). */
object SlideDir { @Volatile var dir = 1 }

/**
 * Slide animation: the old picture leaves to one side while the new one comes in from the other, both moved
 * as cached layers (no redraw). The side shade is not baked into these pictures; it is drawn once on top and
 * stays still, so no dark band travels across the screen.
 */
@Composable
internal fun SlideBackdrop(prev: ImageBitmap?, cur: ImageBitmap?, mix: androidx.compose.animation.core.Animatable<Float, *>, dir: Int, kind: Int) {
    // One canvas: both pictures drawn straight (no layers), the old one darkened by a colour filter in the
    // same pass instead of a full-screen rectangle on top.
    if (cur == null && prev == null) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF110E26), Color(0xFF3A1B52), Color(0xFF0D1A3A)))))
        return
    }
    Canvas(Modifier.fillMaxSize()) {
        val W = size.width; val H = size.height
        val dst = androidx.compose.ui.unit.IntSize(W.toInt(), H.toInt())
        // Moved by fractions of a pixel (filtered while moving): whole-pixel steps made the slow end of the slide judder.
        fun img(b: ImageBitmap, dx: Float, dim: Float = 0f, a: Float = 1f) = translate(left = dx) { drawImage(
            b, dstSize = dst, alpha = a,
            filterQuality = if (b.width < W - 8 || dx != 0f) androidx.compose.ui.graphics.FilterQuality.Low else androidx.compose.ui.graphics.FilterQuality.None,
            colorFilter = if (dim > 0.002f) androidx.compose.ui.graphics.ColorFilter.tint(Color.Black.copy(alpha = dim), androidx.compose.ui.graphics.BlendMode.SrcAtop) else null,
        ) }
        // No slide (the channels' backgrounds): a crossfade on the same mix as the glass, so the page never
        // changes ahead of the dock's glass.
        if (prev != null && cur != null && dir == 0 && mix.value < 0.999f) { img(prev, 0f); img(cur, 0f, a = mix.value) }
        else if (prev == null || dir == 0 || cur == null) { (cur ?: prev)?.let { img(it, 0f) } }
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
internal fun FadeBackdrop(prev: ImageBitmap?, cur: ImageBitmap?, mix: androidx.compose.animation.core.Animatable<Float, *>) {
    // Drawn 1:1 normally; prepared at 2/3 size (Performance) it is scaled up, so it needs filtering.
    val fq = if ((cur ?: prev)?.width?.let { it < 1800 } == true) androidx.compose.ui.graphics.FilterQuality.Low else androidx.compose.ui.graphics.FilterQuality.None
    if (prev != null) Image(prev, null, contentScale = ContentScale.FillBounds, filterQuality = fq, modifier = Modifier.fillMaxSize())
    if (cur != null) Image(cur, null, contentScale = ContentScale.FillBounds, filterQuality = fq,
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (prev != null) mix.value else 1f; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha })
    else if (prev == null) Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF110E26), Color(0xFF3A1B52), Color(0xFF0D1A3A)))))
}


// ───────────────────────────── hero / carousel ─────────────────────────────

@Composable
internal fun HeroSection(
    app: GlassApp, s: Float, hidden: Boolean, keepHidden: Boolean = hidden, quickHide: Boolean = false, paused: Boolean, metrics: DockMetrics, dockShown: androidx.compose.runtime.State<Float> = androidx.compose.runtime.mutableFloatStateOf(1f),
    heroFocus: FocusRequester, firstTile: FocusRequester, onBackdrop: (String?) -> Unit,
    shownUrl: androidx.compose.runtime.State<String?> = androidx.compose.runtime.mutableStateOf(null),
    onCustomUp: () -> Unit = {},
    onPop: (PopState) -> Unit = {},
) {
    val settings = app.settings
    val ctx = LocalContext.current
    val catalogItems by app.catalogs.items.collectAsState()
    val cwItems by app.cw.items.collectAsState()
    val enrichedRaw by app.catalogs.enrichedItems.collectAsState()
    val status by app.catalogs.status.collectAsState()
    // Catalogs of an app (see AppCatalogs): with that app selected in the dock, its titles are the carousel's.
    // They are loaded ahead (CatalogRepository.appItems), so the change is one slide to a list already there.
    val appAll by app.catalogs.appItems.collectAsState()
    val appPkg = com.glasslauncher.tv.data.AppCatalogs.pkg.value?.takeIf { settings.appCatalogsOn && !appAll[it].isNullOrEmpty() }
    // Each list keeps its own place: back on the general carousel (or on an app's) the slide is the one left there.
    val places = remember { HashMap<String, Int>() }
    val placeNow = remember { arrayOf("") }
    val place = appPkg ?: ""
    var index by remember { mutableIntStateOf(0) }
    // (Changed here, before anything below reads it: the first frame of the other list is already at its own slide.)
    if (placeNow[0] != place) { places[placeNow[0]] = index; index = places[place] ?: 0; placeNow[0] = place }
    var focused by remember { mutableStateOf(false) }
    val mode = settings.continueMode
    val lead = settings.cwLead
    val shuffleRev = app.shuffleRev.intValue
    val newFirst = settings.newFirst
    // New titles are worked out off the main thread (a few thousand remembered titles were read and written in
    // the middle of drawing the home screen).
    var freshKeys by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(catalogItems, newFirst) {
        freshKeys = if (!newFirst || catalogItems.isEmpty()) emptyList()
            else withContext(Dispatchers.Default) { com.glasslauncher.tv.data.NewTitles.update(ctx, catalogItems.map { it.key }) }
    }
    // Titles hidden with a long press on Play (Settings → Carousel → Hidden titles brings them back).
    val hiddenKeys = settings.hiddenKeys()
    // The shuffle button's order, kept while titles are hidden (hiding one doesn't mix the rest again).
    val mixed = remember(catalogItems, shuffleRev) {
        if (shuffleRev > 0) catalogItems.shuffled(kotlin.random.Random(shuffleRev * 7919L + System.nanoTime() % 1000)) else catalogItems
    }
    val appList = appPkg?.let { appAll[it] }
    // Its titles in progress first (Settings → Catalogs → Continue Watching in app carousels), with their progress:
    // the ones on its service, or (no service known) watched in this app. From what is already known: Continue Watching's list and the
    // platform TMDB gave each title when it was looked up, so changing app in the dock asks the network nothing.
    val appCw = if (appList == null || appPkg == null || mode == "off" || settings.appCatalogsCw == "off") emptyList() else remember(appPkg, cwItems, enrichedRaw, hiddenKeys, com.glasslauncher.tv.data.AppCatalogs.testOwner.size) {
        com.glasslauncher.tv.data.AppCatalogs.continueFor(appPkg, cwItems.filterNot { it.key in hiddenKeys },
            platformOf = { (enrichedRaw[it.key] ?: it).platform },
            watchedIn = { c -> com.glasslauncher.tv.data.AppCatalogs.testOwner[c.name] ?: NuvioLaunch.sourceTarget(ctx, NuvioLaunch.cwSource(c.key))?.let { t -> NuvioLaunch.TARGETS[t] } },
            installed = { p -> NuvioLaunch.installed(ctx, p) })
    }
    val items = if (appList != null) remember(appList, hiddenKeys, appCw) {
        val keys = appCw.map { it.key }.toSet()
        val rest = appList.filterNot { it.key in keys }
        appCw + (if (hiddenKeys.isEmpty()) rest else rest.filterNot { it.key in hiddenKeys }.ifEmpty { rest })
    }
    else remember(mixed, cwItems, mode, lead, newFirst, freshKeys, hiddenKeys) {
        val cw = (if (mode == "off") emptyList() else cwItems).let { l -> if (hiddenKeys.isEmpty()) l else l.filterNot { it.key in hiddenKeys } }
        val keys = cw.map { it.key }.toSet()
        // The catalog titles: shuffled by the shuffle button, then the ones a catalog has just added in front
        // (newest first). Continue Watching keeps its own place below.
        var cat = if (hiddenKeys.isEmpty()) mixed else mixed.filterNot { it.key in hiddenKeys }
        if (newFirst) {
            val fresh = freshKeys
            if (fresh.isNotEmpty()) {
                val rank = fresh.withIndex().associate { it.value to it.index }
                cat = cat.filter { it.key in rank }.sortedBy { rank[it.key] } + cat.filterNot { it.key in rank }
            }
        }
        val rest = cat.filterNot { it.key in keys }
        when {
            cw.isEmpty() -> cat
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
    val inFrontNow by inFront()
    // The screensaver is opening: the carousel holds still (a slide that started under its fade-in showed as a flash).
    val resumed = inFrontNow && !SaverGate.opening.value
    // Never stuck: if the screensaver didn't open after all, the carousel goes on.
    LaunchedEffect(SaverGate.opening.value) {
        if (SaverGate.opening.value) { delay(4000); if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) SaverGate.opening.value = false }
    }
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

    // Shuffled: back to the first slide (Continue Watching, the new titles, then the new order).
    LaunchedEffect(shuffleRev) {
        if (shuffleRev > 0) { index = 0; app.navAt = System.currentTimeMillis(); com.glasslauncher.tv.data.Glog.i("Carousel shuffled: starts with ${items.firstOrNull()?.name}") }
    }
    val raw = if (items.isEmpty()) null else items[index.mod(items.size)]
    com.glasslauncher.tv.data.AppCatalogs.slide = raw?.name.orEmpty()
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
                    if (t.logo.isNotBlank()) launch { loader.execute(imgReq(ctx, t.logo, 1000, 400)) }
                    // (The copy the page itself uses: the original, unused with "1280 enhanced", was a full-size download and
                    // decode for nothing, a heavy one on a weak TV.)
                    if (t.background.isNotBlank()) launch { loader.execute(imgReq(ctx, if (pageCopy(settings)) sized(t.background, "w1280") else t.background)) }
                    if (t.background.isNotBlank()) launch { GlassCache.get(ctx, t.background, settings.blurRadius) }
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
    // Your own background: the titles aren't shown, so none of their pictures are prepared or kept in memory.
    val ownBg = settings.bgMode != "carousel"
    // The music page really showing (as the slides' own timer sees it): a song left paused in a music app no longer
    // stops the title's pictures from changing, while the carousel still waited for them (it looked stuck).
    val stageNow = MusicUi.stageOn(settings, com.glasslauncher.tv.data.NowPlaying.track.collectAsState().value)
    LaunchedEffect(item?.key, gallery.size, hidden, paused, resumed, ownBg, Trailers.playing.value != null, stageNow) {
        if (ownBg || gallery.size < 2 || hidden || paused || !resumed || stageNow || Trailers.playing.value != null) return@LaunchedEffect
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
    LaunchedEffect(index, items.size, enriched.size, settings.artChoice, ownBg) {
        if (ownBg || items.size < 2) return@LaunchedEffect
        val loader = SingletonImageLoader.get(ctx)
        // Wait until you stop on a slide: while you hold Right, preparing the slides around each one passed only
        // filled the download queue, and the picture you landed on waited behind them. Everything below runs in
        // this effect, so moving on cancels it and frees the downloads for the slide on screen.
        // Playing by itself: after the slide's animation (the work around it made the animation and the CPU jump).
        delay(if (app.browsing()) 350 else (settings.transitionMs + 300L).coerceAtLeast(60L))
        // Both directions, a few slides deep, glass copies included.
        val depth = settings.prefetchDepth
        val ahead = (1..depth).flatMap { if (it <= (depth + 1) / 2) listOf(it, -it) else listOf(it) }
        // Logos first: they are small, but queued behind the big backdrops of the slides around (the picture site
        // takes a few downloads at a time) a title waited seconds for its logo.
        for (k in listOf(0) + ahead) {
            val n = items[(index + k).mod(items.size)].let { withArtwork(enriched[it.key] ?: it, settings.artChoice, settings.artPick(it.key)) }
            if (n.logo.isNotBlank() && GlassCache.logos.get(n.logo) == null && GlassCache.pinnedLogo[n.logo] == null) launch {
                (loader.execute(imgReq(ctx, n.logo, 1000, 400).prep()) as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()?.let { GlassCache.logos.put(n.logo, it) }
            }
        }
        // Downloads only (no decoding kept in memory), further ahead: fast browsing finds the pictures on disk.
        launch {
            delay(400)
            for (k in depth + 1..depth + 2) {
                val n = items[(index + k).mod(items.size)].let { withArtwork(enriched[it.key] ?: it, settings.artChoice, settings.artPick(it.key)) }
                // Only the download matters (it lands in the disk cache): decoded tiny, not as a full 4K picture
                // that was thrown away again (two of them on every slide change).
                if (n.background.isNotBlank()) loader.execute(imgReq(ctx, sized(n.background, if (pageCopy(settings)) "w1280" else "original"), 240, 135)
                    .newBuilder().memoryCachePolicy(coil3.request.CachePolicy.DISABLED).decoderCoroutineContext(prepDispatcher).build())
            }
        }
        for (k in ahead) {
            val n = items[(index + k).mod(items.size)].let { withArtwork(enriched[it.key] ?: it, settings.artChoice, settings.artPick(it.key)) }
            if (n.background.isNotBlank()) {
                launch { loader.execute(imgReq(ctx, sized(n.background, "w1280")).prep()) }
                if (kotlin.math.abs(k) <= 1 && settings.memTier > 0) launch {
                    val u = n.background
                    val r = loader.execute(imgReq(ctx, if (pageCopy(settings)) sized(u, "w1280") else u).prep())
                    val b = (r as? SuccessResult)?.image?.toBitmap()?.asImageBitmap() ?: return@launch
                    val slide = settings.slideAnim != "fade"
                    val bk = (if (slide) "$u|slide" else u) + "|" + settings.pageScale + "|" + settings.memTier + "|" + Scrims.key
                    if (BakeCache.get(bk, b) == null) {
                        val dm = ctx.resources.displayMetrics
                        val baked = withContext(prepDispatcher) { bakeBackdrop(b, (dm.widthPixels * settings.pageScale).toInt(), (dm.heightPixels * settings.pageScale).toInt(), !slide) }
                        BakeCache.put(bk, b, baked)
                        withContext(prepDispatcher) { GlassHalf.of(baked) }
                    }
                }
                launch { GlassCache.get(ctx, n.background, settings.blurRadius) }
            }
        }
    }
    // Continue Watching stays in memory (picture, logo, glass) for as long as the title is in the list:
    // going back to it — even after browsing far, or with Home — never reloads anything.
    val cwKeys = remember(cwItems) { cwItems.map { it.key } }
    LaunchedEffect(cwKeys) { cwItems.forEach { app.catalogs.enrich(it) } }
    val cwShown = cwItems.map { c -> (enriched[c.key] ?: c).let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) } }
    // The slide each app's own list opens on is kept ready too (not on small TVs): going to an app with catalogs,
    // from the search or any other icon, shows its picture at once instead of after a download (it took over a
    // second, the page waiting on the last one meanwhile).
    val appFirst = if (settings.memTier == 0 || !settings.appCatalogsOn) emptyList() else appAll.entries.mapNotNull { (pkg, list) ->
        list.filterNot { it.key in hiddenKeys }.getOrNull(places[pkg] ?: 0)
    }
    LaunchedEffect(appFirst.map { it.key }) { appFirst.forEach { app.catalogs.enrich(it) } }
    val appFirstShown = appFirst.map { c -> (enriched[c.key] ?: c).let { withArtwork(it, settings.artChoice, settings.artPick(it.key)) } }
    androidx.compose.runtime.SideEffect { appFirstShown.map { it.background }.filter { it.isNotBlank() }.take(3).let { if (HeroAhead.urls.value != it) HeroAhead.urls.value = it } }
    LaunchedEffect((cwShown.take(10) + appFirstShown).map { it.background + it.logo }, settings.imageQuality, settings.blurRadius, ownBg, GlassCache.released.intValue) {
        // Not shown with your own background: what was kept for them is let go (tens of MB).
        if (ownBg) { GlassCache.keepOnly(emptySet()); return@LaunchedEffect }
        val loader = SingletonImageLoader.get(ctx)
        val high = pageCopy(settings)
        val keep = mutableSetOf<String>()
        // Kept in memory: the first few only on smaller TVs (each full picture is a few MB).
        for (c in cwShown.take(when (settings.memTier) { 0 -> 2; 2 -> 10; else -> 5 }) + appFirstShown) {
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
            // Your own background has no carousel: Home goes to the dock (the stand-in above it opened the menu).
            delay(120); runCatching { if (settings.bgMode != "carousel") firstTile.requestFocus() else heroFocus.requestFocus() }
        }
    }
    // Keeps playing while the carousel is selected; any Left/Right restarts the timer (index changes).
    val trailerOn = Trailers.playing.value != null
    // Music mode on screen: the carousel stands still under it (it went on moving, unseen).
    val songOn = MusicUi.heldTrack()
    val stageOn = MusicUi.stageOn(settings, songOn)
    LaunchedEffect(stageOn) { if (stageOn && Trailers.playing.value?.overlay != true) Trailers.stop() }
    // The screensaver on the home screen: the carousel stands still under it.
    val saverUp = com.glasslauncher.tv.saver.HomeSaver.state.value != 0
    // (*Where* → Only on the carousel: while the selection is elsewhere, the dock, nothing changes.)
    val hereOk = settings.heroAutoplayWhere != "carousel" || focused
    LaunchedEffect(index, items.size, settings.heroAutoplay, settings.heroIntervalSec, hidden, paused, resumed, trailerOn, stageOn, saverUp, hereOk) {
        if (settings.heroAutoplay && hereOk && !hidden && !paused && resumed && !trailerOn && !stageOn && !saverUp && items.size > 1) {
            val shots = item?.let { i -> if (settings.heroGallery) i.gallery.take(settings.galleryShots).size else 0 } ?: 0
            delay(settings.heroIntervalSec * 1000L * shots.coerceAtLeast(1))
            SlideDir.dir = 1
            index++
        }
    }

    // Trailers: one starts with the Trailer button, from the Play menu, or by itself after a while on a title.
    val trailerScope = androidx.compose.runtime.rememberCoroutineScope()
    // The title whose trailer started by itself: it stops when the selection leaves the carousel (for the dock).
    val autoKey = remember { arrayOfNulls<String>(1) }
    fun startTrailer(it: HeroItem, quiet: Boolean = false) {
        if (Trailers.isPlaying(it.key)) { Trailers.stop(); return }
        autoKey[0] = if (quiet) it.key else null
        trailerScope.launch {
            // Usually ready already (prepared while the title was on screen): starts at once.
            val got = withContext(Dispatchers.IO) { Trailers.prepare(app, it.type, it.id, it.name, it.year) }
            if (got == null) { if (!quiet) toast(ctx, "No trailer for ${it.name}") }
            // Only for the title on screen (the carousel may have moved on meanwhile).
            else if (item?.key == it.key) Trailers.play(it.key, got.first, got.second)
        }
    }
    // Asked from outside (tests): the trailer of the title on screen, as the Play menu starts it.
    val asked = Trailers.request.intValue
    val seenAsk = remember { intArrayOf(asked) }
    LaunchedEffect(asked) { if (asked != seenAsk[0]) { seenAsk[0] = asked; item?.let { startTrailer(it) } } }
    // The trailer of the title on screen is found ahead (a second after it shows), so it starts without a wait.
    // Only while the carousel is selected (a trailer can only start then): with the selection in the dock the
    // carousel goes by on its own, and working each title's trailer out took a busy TV's processor (3 s each on the
    // TCL, every slide).
    LaunchedEffect(item?.key, hidden, resumed, focused) {
        val it = item ?: return@LaunchedEffect
        if (hidden || !resumed || !focused || settings.bgMode != "carousel") return@LaunchedEffect
        // (A weak TV, memory tier 0: only when trailers start by themselves. Worked out for each title rested on it took
        // the processor for seconds; pressed, the trailer is worked out then.)
        if (settings.lean && settings.trailerAutoSec <= 0) return@LaunchedEffect
        // Only for a title you stay on (not each one passed while browsing), and never in the first seconds after
        // the home screen comes up; on its own low-priority thread.
        val auto = settings.trailerAutoSec > 0
        // (Sooner than before, 2.5 s: pressed in the meantime, the trailer had to be worked out from scratch.)
        delay(if (auto) 600 else 900)
        if (android.os.SystemClock.uptimeMillis() - appStartedAt < 8_000) delay(8_000 - (android.os.SystemClock.uptimeMillis() - appStartedAt))
        withContext(Trailers.background) { runCatching { Trailers.prepare(app, it.type, it.id, it.name, it.year) } }
        // And the next one in the carousel (trailers starting by themselves), so it is ready before it comes
        // (not on small TVs: one at a time there).
        if (auto && items.size > 1 && settings.memTier > 0) {
            val next = items[(index + 1).mod(items.size)]
            delay(2000)
            withContext(Trailers.background) { runCatching { Trailers.prepare(app, next.type, next.id, next.name, next.year) } }
        }
    }
    // A new title, or leaving the home screen: the trailer stops.
    LaunchedEffect(item?.key, hidden, resumed) { if (Trailers.playing.value?.overlay != true && (Trailers.playing.value?.key != item?.key || hidden || !resumed)) Trailers.stop() }
    // Hue Sync: the lights follow the screen while the home screen shows.
    HueDriver(app, item, on = !hidden && resumed && !saverUp)
    // When a trailer ends, the next title comes (like the Apple TV app).
    val endedRev = Trailers.endedRev.intValue
    val seenEnded = remember { intArrayOf(endedRev) }
    LaunchedEffect(endedRev) { if (endedRev != seenEnded[0]) { seenEnded[0] = endedRev; if (items.size > 1) { SlideDir.dir = 1; index++ } } }
    // By itself only while the carousel is selected (never with the selection in the dock).
    LaunchedEffect(item?.key, settings.trailerAutoSec, hidden, paused, resumed, stageOn, focused, saverUp) {
        val sec = settings.trailerAutoSec
        val it = item ?: return@LaunchedEffect
        if (sec <= 0 || !focused || hidden || paused || !resumed || stageOn || saverUp || settings.bgMode != "carousel") return@LaunchedEffect
        delay(sec * 1000L)
        if (Trailers.playing.value == null) startTrailer(it, quiet = true)
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (Trailers.playing.value?.overlay != true) Trailers.stop() } }
    LaunchedEffect(focused) {
        if (focused) return@LaunchedEffect
        delay(250)
        val p = Trailers.playing.value
        if (p != null && !p.overlay && p.key == autoKey[0]) Trailers.stop()
    }

    // (Hidden at once: another page's background is already up then, and the title fading out over it showed the
    // carousel for a moment on the way to an app's rows or the music page.)
    // Back on the carousel from another page (an app's rows, the grey page): the title waits for its picture (it
    // showed over the rows' background for a moment, then the picture came in under it). Not longer than 1.5 s.
    val heroSong = MusicUi.songFor(MusicUi.heldTrack())
    val heroMusic = (MusicUi.stageOn(settings, heroSong) && !MusicUi.carouselApp.value) || (heroSong == null && settings.musicOn && MusicUi.dockApp.value != null)
    val waitRaw = !hidden && !heroMusic && settings.bgMode == "carousel" && shownUrl.value?.startsWith("glassio:") == true
    var waitOver by remember { mutableStateOf(false) }
    LaunchedEffect(waitRaw) { waitOver = false; if (waitRaw) { delay(1500); waitOver = true } }
    val waitPic = waitRaw && !waitOver
    val alphaA by animateFloatAsState(if (hidden || waitPic) 0f else 1f, if (hidden && quickHide) androidx.compose.animation.core.snap() else tween(if (hidden) 120 else 150), label = "heroAlpha")
    // (Hidden for another page in the same frame its background comes: animated, the title stayed one frame more.)
    val alpha = if (hidden && quickHide) 0f else alphaA
    // A trailer playing: the dots step aside with the dock (back when it stops or ends).
    val dotsOn = items.size > 1 && settings.dotsMode != "hidden" && (!settings.dotsAutoHide || focused) && !(Trailers.started.value && settings.trailerHideDock && settings.trailerSize != "popup" && !Trailers.dockAsked.value)
    val dotsAlpha by animateFloatAsState(if (dotsOn) 1f else 0f, tween(300), label = "dotsAlpha")

    // Your own picture / video / the screensaver behind: the page is just that, no title, details or buttons.
    val customBg = when (settings.bgMode) { "image" -> settings.bgImage.isNotBlank(); "video" -> settings.bgVideo.isNotBlank(); "aerial", "photos" -> true; else -> false }
    // ModulateAlpha: no full-screen off-screen pass while it fades (the dock opening); the pieces barely overlap.
    // When the selection last left this page (the music player and the carousel come and go under it).
    val pageFocus = remember { booleanArrayOf(false) }
    val pageLostAt = remember { longArrayOf(0L) }
    // An app's rows right above the dock (*Rows above the dock*) over your own background: the large clock stays,
    // drawn outside this page's fade (it went with the carousel, #21).
    val clockThrough = customBg && settings.bgClock && settings.channelsLow && ChannelsUi.shown.value
    // While the music page fades (to or from the channels' rows): one off-screen pass, so its artwork's shadow fades
    // with it (faded piece by piece, the shadow isn't faded: a dark square stayed over the rows and came early).
    if (clockThrough) BigClock(settings, s)
    Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha
        compositingStrategy = if (alpha in 0.002f..0.998f && (MusicUi.stageCur.value != null || MusicUi.dockApp.value != null))
            androidx.compose.ui.graphics.CompositingStrategy.Offscreen else androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }
        .onFocusChanged { if (it.hasFocus) pageFocus[0] = true else if (pageFocus[0]) { pageFocus[0] = false; pageLostAt[0] = android.os.SystemClock.uptimeMillis() } }) {
        val shown = textItem
        // Music mode: while a song plays, the player is the background (whatever was chosen).
        val song = MusicUi.songFor(MusicUi.heldTrack())
        // (A music app selected in the dock: its player, also with nothing playing.)
        val musicStageNow = (MusicUi.stageOn(settings, song) && !MusicUi.carouselApp.value) || (song == null && settings.musicOn && MusicUi.dockApp.value != null)
        // While this page fades out (the rows, the music apps coming): the player stays as it was under the fade (it
        // went at once, and the carousel showed for a moment).
        val musicKept = remember { booleanArrayOf(false) }
        val idleKept = remember { mutableStateOf<String?>(null) }
        // (Only while hidden under the dock, a folder, the rows or the music apps. Hidden because another page is up,
        // nothing is kept: kept, the app picked the song shown, the page above went by it, and the player and an
        // app's idle player never came back.)
        if (!keepHidden) { musicKept[0] = musicStageNow; idleKept.value = MusicUi.dockApp.value }
        val musicStage = if (keepHidden) musicKept[0] else musicStageNow
        // (Set before the player below is drawn: set after, another app's paused song showed for a frame or two.)
        MusicUi.idleKept = if (keepHidden) idleKept.value else null
        // Opened from the dock's Music icon: it stays until Back or the music stops.
        LaunchedEffect(song == null) { if (song == null) MusicUi.forced.value = false }
        BackHandler(enabled = MusicUi.forced.value && musicStage) { MusicUi.forced.value = false }
        val focusDone = remember { intArrayOf(MusicUi.focusRev.intValue) }
        LaunchedEffect(MusicUi.focusRev.intValue, musicStage) {
            val rev = MusicUi.focusRev.intValue
            if (rev != focusDone[0] && musicStage) {
                focusDone[0] = rev
                // (Tried again until the player has it: with your own background the player came while the music
                // apps were still going, the first try was lost and the selection went nowhere, then to the first app.)
                repeat(25) {
                    delay(if (it == 0) 150 else 100)
                    if (MusicUi.stageFocused || DockFocus.top) return@LaunchedEffect
                    runCatching { heroFocus.requestFocus() }
                }
            }
        }
        // The player went (the app stopped, e.g. YouTube when you leave it) with the selection in it: the selection
        // comes back to the carousel (or the dock), not left nowhere.
        // The same when the player comes (the carousel's button it had goes): the selection goes to the player.
        LaunchedEffect(musicStage) {
            val changedAt = android.os.SystemClock.uptimeMillis()
            val fromStage = !musicStage && MusicUi.stageFocused
            if (fromStage) MusicUi.stageFocused = false
            delay(120)
            // (Not when the selection went down to the dock itself: the player closing then is that move's doing.)
            // (Nor with the search up: the selection is in the search's page, not lost. A song started from the search
            // changed the player's state just as Glassio came back, and the selection was sent to the first app.)
            val lostWithIt = !pageFocus[0] && pageLostAt[0] >= changedAt - 100 && !DockFocus.dock && !SearchUi.on.value && !SearchUi.full.value
            // (On the Music icon (paused, or stopped): back to the icon, not the first app.)
            val onMusicIcon = MusicUi.appsOn.value || MusicUi.forced.value
            if ((fromStage || lostWithIt) && onMusicIcon) runCatching { MusicUi.tileFor(MusicUi.DOCK_PKG)?.requestFocus() }
            else if (fromStage || lostWithIt) if (runCatching { heroFocus.requestFocus() }.isFailure) runCatching { firstTile.requestFocus() }
        }
        // The trailer in place of the picture (under the text), or in its window.
        // The subtitles sit above the dock while it shows.
        val dockUp by animateFloatAsState(if (dockShown.value > 0.5f) metrics.collapsedH + metrics.bottomMargin else 0f, tween(300), label = "subsUp")
        if (!customBg && !musicStage && settings.trailerSize != "popup") TrailerLayer(app, s, dockUp)
        // (While it fades out over your own background the page keeps a requester of its own: with the carousel's,
        // Up from the dock in those 220 ms could select its fading button instead of the box that opens the menu.)
        val fadingFocus = remember { FocusRequester() }
        val stage: @Composable () -> Unit = {
            MusicStage(app, s, if (musicStage) heroFocus else fadingFocus, firstTile, onApps = {
                val apps = installedMusicApps(ctx)
                if (apps.isEmpty()) toast(ctx, "No music app on this TV")
                else onPop(PopState("Music apps", apps.map { (pkg, name) -> PopOption(name) { openMusicApp(ctx, pkg) } }, null))
            })
        }
        // Over your own background (a picture, a video) the player's page fades in and out in the 220 ms the glass
        // takes to go from the background to the song's artwork and back (artIn in HomeScreen). It came and went in
        // one frame: the page was already the player's while the dock's glass still showed the video for a fifth of
        // a second (his report: "the dock draws its glass late"; read from a recording).
        if (customBg) androidx.compose.animation.AnimatedVisibility(musicStage, enter = androidx.compose.animation.fadeIn(tween(220)),
            exit = androidx.compose.animation.fadeOut(tween(220))) { stage() }
        if (musicStage) {
            if (!customBg) stage()
        } else if (customBg) {
            // A large clock (with the depth effect on photos), and the place / credit of what's behind.
            if (settings.bgClock && !clockThrough) BigClock(settings, s)
            // The place and credit (and, if chosen, time, date, weather) like the screensaver's info.
            val info = CustomBackground.info(settings)
            val infoChange = remember(info) { (info?.first ?: "").hashCode() }
            if (info != null) com.glasslauncher.tv.saver.SaverInfo(app, info.first, infoChange, com.glasslauncher.tv.saver.InfoLook.background(settings), credit = info.second,
                padTop = 30 * s, padBottom = if (settings.dockPos == "bottom") metrics.collapsedH + metrics.bottomMargin - 30 * s else 0f)
            // Holds the dock's Up: opens the menu (or selects the settings button) straight away.
            Box(Modifier.align(Alignment.TopStart).padding(start = (48 * s).dp, top = (300 * s).dp).size(1.dp)
                .focusRequester(heroFocus)
                .onFocusChanged { if (it.isFocused) onCustomUp() }
                .focusable())
        } else if (shown != null) {
            // Play opens the streaming app: the slide's link is looked up while it is on screen, so Play opens at once.
            if (appPkg != null && com.glasslauncher.tv.data.AppCatalogs.playsIn(settings, appPkg)) LaunchedEffect(shown.id, appPkg) { com.glasslauncher.tv.data.PlatformLinks.warm(ctx, settings, shown, appPkg) }
            else if (settings.playInApp) LaunchedEffect(shown.id, shown.platform) { com.glasslauncher.tv.data.PlatformLinks.warm(ctx, settings, shown) }
            HeroInfo(
                item = shown, app = app, s = s, focused = focused, heroFocus = heroFocus, firstTile = firstTile,
                onFocus = { focused = it },
                onPrev = { app.navAt = System.currentTimeMillis(); SlideDir.dir = -1; index-- }, onNext = { app.navAt = System.currentTimeMillis(); SlideDir.dir = 1; index++ },
                // (From an app's catalogs: the title's page in that app, when its link is known or found; else as usual.)
                onOpen = {
                    if (appPkg != null && com.glasslauncher.tv.data.AppCatalogs.playsIn(settings, appPkg)) com.glasslauncher.tv.data.PlatformLinks.openOr(ctx, settings, shown, appPkg) { NuvioLaunch.open(ctx, settings, app.http, shown) }
                    else NuvioLaunch.open(ctx, settings, app.http, shown)
                },
                // Hold OK on Play: a small glass menu next to it (Library, Like, artwork).
                onLong = {
                    val base = enriched[shown.key] ?: shown
                    val pictures = artOptions(base)
                    val inLib = app.library.has(shown.key)
                    val liked = settings.liked(shown.key)
                    onPop(PopState(shown.name, listOfNotNull(
                        PopOption(if (inLib) "Remove from Library" else "Add to Library", if (inLib) "✓" else "") {
                            val now = app.library.toggle(shown)
                            toast(ctx, if (now) "Saved to Library · ${shown.name}" else "Removed from Library · ${shown.name}")
                        },
                        if (com.glasslauncher.tv.data.Store.PLAY) null else PopOption(if (Trailers.isPlaying(shown.key)) "Stop trailer" else "Trailer") { startTrailer(shown) },
                        PopOption(if (liked) "Liked" else "Like", if (liked) "♥" else "") {
                            val now = app.library.toggleLike(shown)
                            toast(ctx, if (now) "Liked · ${shown.name}" else "Like removed · ${shown.name}")
                        },
                        if (pictures.size > 1) PopOption("Change artwork", thumbs = PopThumbs("Artwork · ${shown.name}", pictures,
                            pictures.indexOf(shown.background).coerceAtLeast(0)) { url -> settings.setArtPick(shown.key, url) })
                        else PopOption("Change artwork", "—") { toast(ctx, "No other pictures for ${shown.name} (add a TMDB token for more)") },
                        // Not for you: off the carousel for good (Settings → Carousel → Hidden titles brings it back).
                        if (items.size > 1) PopOption("Hide this title") {
                            app.navAt = System.currentTimeMillis()
                            settings.setHidden(shown.key, shown.name, true)
                            toast(ctx, "Hidden · ${shown.name}  (Settings → Carousel → Hidden titles)")
                        } else null,
                    ), PopAnchors.play, PopState.Side.Right))
                },
                modifier = Modifier.align(Alignment.TopStart),
                onTrailer = { startTrailer(shown) },
            )
        } else {
            EmptyHint(status, s, heroFocus, firstTile, Modifier.align(Alignment.TopStart)) {
                app.requestSettings.intValue = CATALOGS_CATEGORY; app.requestSettingsRev.intValue++
            }
        }
        if (!customBg && !musicStage && items.size > 1 && settings.dotsMode != "hidden") {
            Dots(
                total = items.size, current = index.mod(items.size), mode = settings.dotsMode, max = settings.dotsMax, s = s * settings.dotsScale,
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
        Text("Open Settings → Catalogs & add-ons to connect Nuvio and fill the carousel.", color = Color.White.copy(alpha = 0.55f), fontSize = (12 * s).sp)
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
    modifier: Modifier, onTrailer: () -> Unit = {},
) {
    val settings = app.settings
    val ms = settings.transitionMs
    var heroDown by remember { mutableStateOf(false) }
    var heroLong by remember { mutableStateOf(false) }
    // Apple TV buttons: 0 = Play, 1 = the round Next button. The block stays one focus target; Left/Right
    // move between the two and past them change the title, so holding a direction keeps browsing.
    val nextBtn = settings.heroButtons == "apple"
    // 0 = Play, 1 = + (save to Library), 2 = Next.
    // 3 = Trailer (before Next).
    val order = (if (settings.heroPlus) listOf(0, 1) else listOf(0)) + (if (settings.trailerButton) listOf(3) else emptyList()) + listOf(2)
    var btn by remember { mutableIntStateOf(0) }
    val savedList by app.library.saved.collectAsState()
    val isSaved = savedList.any { it.key == item.key }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val backOwner = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
    // The block itself is the focus target (invisible): Left/Right change slide, OK opens the title.
    Box(
        modifier
            .padding(start = (48 * s).dp, top = ((if (settings.heroButtons == "apple") 78 else 96) * s).dp)
            .width((440 * s).dp)
            .focusRequester(heroFocus)
            // Down: back to the app whose channels Up came from (the first app otherwise).
            .focusProperties { down = ChannelsUi.back ?: firstTile }
            .onKeyEvent { ev ->
                when {
                    ev.key == Key.DirectionRight && ev.type == KeyEventType.KeyDown -> {
                        val i = order.indexOf(btn); if (nextBtn && i in 0 until order.size - 1) btn = order[i + 1] else onNext(); true
                    }
                    ev.key == Key.DirectionLeft && ev.type == KeyEventType.KeyDown -> {
                        val i = order.indexOf(btn); if (nextBtn && i > 0) btn = order[i - 1] else onPrev(); true
                    }
                    ev.isSelect && nextBtn && btn == 2 -> { if (ev.type == KeyEventType.KeyDown) onNext(); true }
                    ev.isSelect && nextBtn && btn == 3 -> { if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) onTrailer(); true }
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
                    // Back is handled here like on the dock's tiles: left to Compose, it cleared the focus and the
                    // remote did nothing until the page was opened again.
                    // A trailer playing: Back stops it (and stays on this title).
                    ev.key == Key.Back && Trailers.playing.value != null -> { if (ev.type == KeyEventType.KeyUp) Trailers.stop(); true }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) backOwner?.onBackPressedDispatcher?.onBackPressed(); true }
                    else -> false
                }
            }
            .onFocusChanged { if (it.isFocused) btn = 0; onFocus(it.isFocused) }
            .focusable()
            .padding((8 * s).dp),
    ) {
      // No left shade (Carousel shade off, or Left shade off over your own background): the writing carries a soft
      // shadow of its own so it reads over a bright picture. Not the buttons: they have plates.
      val plainText = androidx.compose.material3.LocalTextStyle.current
      val shadowPx = with(LocalDensity.current) { (3.2f * s).dp.toPx() }
      val textStyle = if (Scrims.sideOff) plainText.copy(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, androidx.compose.ui.geometry.Offset(0f, shadowPx * 0.25f), shadowPx)) else plainText
      androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides textStyle, LocalPlainText provides plainText) {
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
                if (it.nextUp && settings.cwProgress) {
                    Spacer(Modifier.height((10 * s).dp))
                    Text(listOf("Next up", it.sub).filter { x -> x.isNotBlank() }.joinToString("  ·  "),
                        color = Color.White, fontSize = (10.5f * s * m).sp, fontWeight = FontWeight.Medium)
                } else if (it.progress >= 0f && settings.cwProgress) {
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
                        color = if (Scrims.sideOff) Color(0xFFF4F4F6) else AppleSecondary, fontWeight = FontWeight.Medium, fontSize = (11 * s * settings.genresScale).sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
              }
                if (settings.showSynopsis && it.overview.isNotBlank()) {
                    AnimatedVisibility(visible = !settings.synopsisOnFocus || focused, enter = fadeIn(tween(200)), exit = fadeOut(tween(150))) {
                        Column {
                            Spacer(Modifier.height((10 * s).dp))
                            Text(
                                it.overview, color = if (Scrims.sideOff) Color(0xFFF4F4F6) else AppleSecondary, fontWeight = FontWeight.Medium,
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
                        val look = HeroLook.of(settings)
                        if (look == null) Row(
                            Modifier.onGloballyPositioned { PopAnchors.play = it.boundsInRoot() }
                                .clipR((settings.cornerRadius * 0.7f * s).dp).background(Color.White.copy(alpha = 0.94f))
                                .padding(horizontal = (16 * s).dp, vertical = (7 * s).dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayGlyph(Modifier.size((10 * s).dp), Color.Black)
                            Spacer(Modifier.width((7 * s).dp))
                            Text("Play", color = Color.Black, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold, style = plainText())
                        } else HeroPlate(true, 1f, Modifier.onGloballyPositioned { PopAnchors.play = it.boundsInRoot() }, (settings.cornerRadius * 0.7f * s).dp, s, look) { fg ->
                            Row(Modifier.padding(horizontal = (16 * s).dp, vertical = (7 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                PlayGlyph(Modifier.size((10 * s).dp), fg)
                                Spacer(Modifier.width((7 * s).dp))
                                Text("Play", color = fg, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold, style = plainText())
                            }
                        }
                    }
                }
            }
        }
        }
        // Apple TV buttons stay put while the title changes; only the text above fades.
        if (nextBtn) Box(Modifier.onGloballyPositioned { PopAnchors.play = it.boundsInRoot() }) {
            HeroButtons(focused, btn, s, settings.cornerRadius, settings.heroPlus, isSaved, trailer = settings.trailerButton, trailerOn = Trailers.isPlaying(item.key), look = HeroLook.of(settings))
        }
      }
      }
    }
}

/** The carousel's text style without the shadow the writing gets when the left shade is off (the buttons' own). */
private val LocalPlainText = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.text.TextStyle?> { null }

@Composable private fun plainText() = LocalPlainText.current ?: androidx.compose.material3.LocalTextStyle.current

/** The pictures of the slides the apps' own carousels open on, for the home screen to prepare ahead. */
internal object HeroAhead { val urls = androidx.compose.runtime.mutableStateOf<List<String>>(emptyList()) }

/**
 * Logos come in every shape: very wide word marks and almost square stacked ones. Sizing them by a
 * fixed height made wide logos tiny and tall ones huge, so every logo gets the same visual *area*,
 * then is capped in width and height.
 */
@Composable
internal fun Title(item: HeroItem, mode: String, scale: Float, s: Float) {
    if (mode == "hidden") return
    @Composable fun asText() = Plain(
        item.name, color = Color.White, fontSize = (30 * s * scale).sp, fontWeight = FontWeight.Light,
        letterSpacing = (4 * scale).sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    if (mode == "text" || item.logo.isBlank()) { asText(); return }
    val ctx = LocalContext.current
    val pinnedLogo = GlassCache.pinnedLogo[item.logo] ?: GlassCache.logos.get(item.logo)
    // Logos that fail to download are tried twice more before the title is shown as text.
    // (After the three tries, asked again when the network is back or the home screen shows again: NetState.)
    val netRev = com.glasslauncher.tv.data.NetState.rev.intValue
    var attempt by remember(item.logo, netRev) { mutableIntStateOf(0) }
    val req = remember(item.logo, attempt, netRev) { imgReq(ctx, item.logo, 1000, 400) }
    var ratio by remember(item.logo) { mutableStateOf<Float?>(null) }
    var failed by remember(item.logo, netRev) { mutableStateOf(false) }
    var retryAt by remember(item.logo, netRev) { mutableIntStateOf(0) }
    LaunchedEffect(retryAt) { if (retryAt > 0) { delay(900L * retryAt); attempt = retryAt } }
    // Still loading after a moment (a slow site): the name shows meanwhile, the logo replaces it when it's there.
    var slow by remember(item.logo) { mutableStateOf(false) }
    val since = remember(item.logo) { android.os.SystemClock.uptimeMillis() }
    LaunchedEffect(item.logo) { delay(700); slow = true }
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
        Box {
            if (Scrims.sideOff) Image(pinnedLogo, null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, colorFilter = LogoShadow,
                modifier = Modifier.size(pw.dp, ph.dp).offset((1.2f * s).dp, (1.6f * s).dp).alpha(0.55f))
            Image(pinnedLogo, item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, colorFilter = logoTint(), modifier = Modifier.size(pw.dp, ph.dp))
        }
        return
    }
    // The name and the (still invisible) logo in the same place: one replaces the other, nothing moves.
    Box {
    if (ratio == null && slow) asText()
    // (No left shade: the logo's own shape in black just under it, so a white logo reads over a bright picture.)
    if (Scrims.sideOff && ratio != null) AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, colorFilter = LogoShadow,
        modifier = Modifier.size(w.dp, h.dp).offset((1.2f * s).dp, (1.6f * s).dp).alpha(0.55f))
    AsyncImage(
        model = req, contentDescription = item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart, colorFilter = logoTint(),
        onSuccess = { st ->
            val sz = st.painter.intrinsicSize
            // An empty or broken picture (1×1, a blank PNG some sites send for "no logo"): the name instead.
            if (sz.width < 8f || sz.height < 4f) { failed = true; return@AsyncImage }
            ratio = sz.width / sz.height
            val took = android.os.SystemClock.uptimeMillis() - since
            if (took > 800) com.glasslauncher.tv.data.Glog.i("Logo slow (${took} ms): ${item.name} ${item.logo}")
        },
        onError = { e ->
            if (retryAt < 2) retryAt++ else { failed = true; com.glasslauncher.tv.data.NetState.failed() }
            com.glasslauncher.tv.data.Glog.w("Logo ${item.logo} failed", e.result.throwable)
        },
        modifier = Modifier.size(w.dp, h.dp).alpha(if (ratio == null) 0f else 1f),
    )
    }
}

/** Apple style: the logo's shape in white (its colours dropped). */
private val WhiteLogo = ColorFilter.tint(Color.White, BlendMode.SrcIn)
private val LogoShadow = ColorFilter.tint(Color.Black, BlendMode.SrcIn)

@Composable private fun logoTint(): ColorFilter? = if ((LocalContext.current.applicationContext as GlassApp).settings.logoWhite) WhiteLogo else null

@Composable
internal fun Chip(text: String, s: Float) {
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
    val st = rememberGlassStatic()
    GlassPanel(modifier, radius = (11 * s).dp, style = "water_still", refract = st.refractionDp > 0) {
        GlassDots(count, { head.value }, { tail.value }, s, Modifier.padding(horizontal = (3 * s).dp, vertical = (2 * s).dp))
    }
}

/**
 * Play and Next, drawn like the Apple TV app: grey glass when not selected, white with a dark glyph when
 * selected, the selected one a little larger. With [look] (Settings → Carousel → Button look: Follow the theme)
 * the plates are the theme's own, see [HeroPlate].
 */
@Composable
private fun HeroButtons(focused: Boolean, btn: Int, s: Float, corner: Int, plus: Boolean = false, saved: Boolean = false,
                        trailer: Boolean = false, trailerOn: Boolean = false, look: HeroLook? = null) {
    val h = (30 * s).dp
    Column {
        Spacer(Modifier.height((14 * s).dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HeroPlate(focused && btn == 0, 1.06f, Modifier.height(h), (corner * 0.7f * s).dp.coerceAtLeast(h / 2), s, look) { fg ->
                Row(Modifier.padding(horizontal = (16 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayGlyph(Modifier.size((10 * s).dp), fg)
                    Spacer(Modifier.width((7 * s).dp))
                    Text("Play", color = fg, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold, style = plainText())
                }
            }
            Spacer(Modifier.width((10 * s).dp))
            if (plus) {
                HeroPlate(focused && btn == 1, 1.12f, Modifier.size(h), h / 2, s, look) { fg ->
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
            if (trailer) {
                // Trailer: a small screen with a play mark (a stop square while it plays).
                HeroPlate(focused && btn == 3, 1.12f, Modifier.size(h), h / 2, s, look) { fg ->
                    Canvas(Modifier.size((12 * s).dp)) {
                        val w = size.width; val hh = size.height
                        val st = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.11f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                        drawRoundRect(fg, androidx.compose.ui.geometry.Offset(w * 0.04f, hh * 0.16f), androidx.compose.ui.geometry.Size(w * 0.92f, hh * 0.68f),
                            androidx.compose.ui.geometry.CornerRadius(w * 0.14f), style = st)
                        if (trailerOn) drawRect(fg, androidx.compose.ui.geometry.Offset(w * 0.38f, hh * 0.38f), androidx.compose.ui.geometry.Size(w * 0.24f, hh * 0.24f))
                        else drawPath(androidx.compose.ui.graphics.Path().apply { moveTo(w * 0.40f, hh * 0.34f); lineTo(w * 0.64f, hh * 0.5f); lineTo(w * 0.40f, hh * 0.66f); close() }, fg)
                    }
                }
                Spacer(Modifier.width((10 * s).dp))
            }
            HeroPlate(focused && btn == 2, 1.12f, Modifier.size(h), h / 2, s, look) { fg ->
                Canvas(Modifier.size((11 * s).dp)) {
                    val w = size.width; val hh = size.height
                    val path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(w * 0.36f, hh * 0.16f); lineTo(w * 0.70f, hh * 0.5f); lineTo(w * 0.36f, hh * 0.84f)
                    }
                    drawPath(path, fg, style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = w * 0.17f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                }
            }
        }
    }
}

/**
 * The carousel buttons' plates when they follow the theme: [style] is the icons' plate style (what a theme sets:
 * the four glasses, Black AMOLED, Black on white), [selected] what the selected button turns into: "light" (white,
 * as the dark buttons do), "dark", "amoled", or "glass" = the theme's own plate, lit. It follows *Selected icon
 * plate* when *Colour on focus* is on, else it is white.
 */
internal class HeroLook(val style: String, val selected: String) {
    companion object {
        fun of(st: com.glasslauncher.tv.data.Settings): HeroLook? =
            if (st.heroButtonLook != "theme") null else HeroLook(st.tileStyle, if (st.focusColor) st.focusPlate else "light")
    }
}

/**
 * One carousel button: its plate, a little larger when selected ([zoom]), with what is on it drawn in the colour
 * the plate asks for. [look] null: dark, white when selected (the Apple TV app's).
 *
 * With a look, the plate at rest is the theme's: glass like the dock icons' (the same ready pictures of the page,
 * nothing blurred live; clear glass that bends nothing draws only its tint while the page slides), black, or
 * off-white. Glass is tinted dark a little so white writing reads over a bright picture.
 */
@Composable
private fun HeroPlate(on: Boolean, zoom: Float, modifier: Modifier, radius: Dp, s: Float, look: HeroLook?, content: @Composable (Color) -> Unit) {
    val sc by animateFloatAsState(if (on) zoom else 1f, spring(0.7f, 700f), label = "heroBtnScale")
    if (look == null) {
        // Dark when not selected, white when selected.
        val bg by androidx.compose.animation.animateColorAsState(if (on) Color.White.copy(alpha = 0.95f) else Color.Black.copy(alpha = 0.55f), tween(140), label = "heroBtnBg")
        Box(modifier.graphicsLayer { scaleX = sc; scaleY = sc }.clipR(radius).background(bg), contentAlignment = Alignment.Center) {
            content(if (on) Color.Black else Color.White)
        }
        return
    }
    val env = rememberGlassStatic()
    val style = look.style
    val bow = style == "bow"
    // What the selected button turns into. The theme's own plate lit, for the two flat themes: the other way round
    // on Black on white (a dark plate), a bright rim on black.
    val sel = when {
        look.selected == "glass" && bow -> "dark"
        look.selected == "glass" && style == "amoled" -> "amoled"
        else -> look.selected
    }
    val lit = animateFloatAsState(if (on) 1f else 0f, tween(140), label = "heroBtnLit")
    val shape = RoundedCornerShape(if (GlassShape.square.value) 0.dp else radius)
    val fg = when {
        on -> if (sel == "light") Color.Black else Color.White
        bow -> Color(0xFF0E0E10)
        else -> Color.White
    }
    Box(modifier.graphicsLayer { scaleX = sc; scaleY = sc }, contentAlignment = Alignment.Center) {
        val fill = Modifier.matchParentSize()
        when (style) {
            "amoled" -> Box(fill.background(Color.Black, shape))
            "bow" -> Box(fill.background(HeroBow, shape))
            else -> {
                val clear = style.startsWith("water")
                val bend = on && sel == "glass" && style != "clear"
                // Frosted glass draws both pictures itself in every frame of a slide (measured on the Streamer: about
                // 1 ms a frame for the three buttons): a plain dark pane stands in while the page slides, the glass
                // is back when it stops. Clear glass costs nothing then (it shows the page under it).
                val ref = LocalGlassEnvRef.current
                fun sliding() = !clear && ref.value.let { it.slide != 0 && it.prevBmp != null }
                LiquidSurface(
                    fill.graphicsLayer { alpha = if (sliding()) 0f else 1f }, radius = radius, refraction = if (bend) (env.refractionDp * 1.2f).dp else 0.dp, bezel = (12 * s).dp,
                    tint = Color.Black.copy(alpha = if (clear) 0.26f else 0.22f), sharp = clear,
                )
                if (!clear) Box(fill.graphicsLayer { alpha = if (sliding()) 1f else 0f }.background(Color(0xFF2A2A2E).copy(alpha = 0.62f), shape))
            }
        }
        // The selected plate over it, faded in (one flat layer; glass stays glass and only gains its rim).
        if (sel != "glass") Box(fill.graphicsLayer { alpha = lit.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }
            .background(when (sel) { "light" -> Color.White.copy(alpha = 0.95f); "amoled" -> Color.Black; else -> Color(0xF0141416) }, shape))
        val rest = if (bow) 0.55f else if (style == "amoled") 0.14f else 0.20f
        Box(fill.drawBehind {
            val l = lit.value
            val a = rest + ((if (sel == "light") 0f else 0.92f) - rest) * l
            if (a > 0.01f) {
                val w = (0.7f + (if (sel == "light") 0f else 0.9f) * l) * density
                val r = if (GlassShape.square.value) 0f else kotlin.math.min(radius.toPx(), kotlin.math.min(size.width, size.height) / 2f)
                drawRoundRect(Color.White.copy(alpha = a), androidx.compose.ui.geometry.Offset(w / 2, w / 2), androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
                    androidx.compose.ui.geometry.CornerRadius((r - w / 2).coerceAtLeast(0f)), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            }
        })
        content(fg)
    }
}

private val HeroBow = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFFE9E5DD), Color(0xFFD9D4CA)))
