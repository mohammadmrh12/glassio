package com.glasslauncher.tv.ui

import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.layout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.NuvioLaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The Library, like the Apple TV app's: a calm grey page, posters five to a row with a soft shadow.
 * First what you saved with +, then your Trakt watchlist. OK opens the title, hold OK (or Menu) removes it.
 */
/** Asks before a title is removed (Settings: "Ask before removing"). */
private val LocalAsk = androidx.compose.runtime.staticCompositionLocalOf<(String, () -> Unit) -> Unit> { { _, f -> f() } }
private val LocalGlow = androidx.compose.runtime.staticCompositionLocalOf { "off" }

@Composable
fun LibraryScreen(app: GlassApp, onTopUp: (() -> Unit)? = null, active: Boolean = true, onClose: () -> Unit) {
    var ask by remember { mutableStateOf<DialogState?>(null) }
    val askFn: (String, () -> Unit) -> Unit = { name, f ->
        if (!app.settings.libraryConfirm) f()
        else ask = MenuDialog("Remove “$name”?", listOf("Remove" to f, "Cancel" to {}))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalAsk provides askFn, LocalGlow provides app.settings.glow) {
        LibraryPage(app, onTopUp, active, onClose)
    }
    DialogHost(ask) { ask = null }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun LibraryPage(app: GlassApp, onTopUp: (() -> Unit)?, active: Boolean, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val st = app.settings
    val saved by app.library.saved.collectAsState()
    val watch by app.library.watchlist.collectAsState()
    val status by app.library.status.collectAsState()
    val first = remember { FocusRequester() }
    BackHandler(enabled = active) { onClose() }
    val nuvioAll by app.library.nuvioList.collectAsState()
    val nuvioStatus by app.library.nuvioStatus.collectAsState()
    val simklAll by app.library.simklList.collectAsState()
    val cwItems by app.cw.items.collectAsState()
    val cwEntries by app.cw.entries.collectAsState()
    LaunchedEffect(active) { if (active) withContext(Dispatchers.IO) { app.library.refreshWatchlist(); app.library.refreshNuvio(); app.library.refreshSimkl() } }
    val savedKeys = remember(saved) { saved.map { it.key }.toSet() }
    // The same title in several lists shows once: saved here, then Nuvio, then Trakt.
    val nuvio = if (st.libraryNuvio) nuvioAll.filterNot { it.key in savedKeys } else emptyList()
    val nuvioKeys = remember(nuvio) { nuvio.map { it.key }.toSet() }
    val trakt = if (st.libraryTrakt) watch.filterNot { it.key in savedKeys || it.key in nuvioKeys } else emptyList()
    val traktKeys = remember(trakt) { trakt.map { it.key }.toSet() }
    val simkl = if (st.librarySimkl) simklAll.filterNot { it.key in savedKeys || it.key in nuvioKeys || it.key in traktKeys } else emptyList()
    val cw = if (st.libraryCw && st.continueMode != "off") cwItems else emptyList()
    val hasAny = saved.isNotEmpty() || trakt.isNotEmpty() || nuvio.isNotEmpty() || simkl.isNotEmpty() || cw.isNotEmpty()
    // Opened with Down from the dock: Up from the top row goes back to the dock (like the Apple TV home page).
    val firstList = when { cw.isNotEmpty() -> "c"; saved.isNotEmpty() -> "s"; nuvio.isNotEmpty() -> "n"; trakt.isNotEmpty() -> "t"; else -> "k" }
    fun up(list: String, i: Int): Modifier = if (onTopUp != null && list == firstList && (list == "c" || i < 5))
        Modifier.onPreviewKeyEvent { e ->
            if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.DirectionUp) { onTopUp(); true } else false
        } else Modifier
    // The page stays built between visits (ready for Down from the dock): each visit starts at the top again,
    // with the first title selected (selecting one that was scrolled away left the remote doing nothing).
    val grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // The Continue Watching row too: scrolled sideways, its first card wasn't built any more, so selecting it on
    // the next visit failed and the remote did nothing (the cards looked stuck).
    val cwRow = androidx.compose.foundation.lazy.rememberLazyListState()
    var libFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(active) { if (!active) runCatching { grid.scrollToItem(0); cwRow.scrollToItem(0) } }
    LaunchedEffect(hasAny, active) { if (hasAny && active) { runCatching { grid.scrollToItem(0); cwRow.scrollToItem(0) }; delay(30); runCatching { first.requestFocus() } } }
    // Back from the screensaver (or another app) nothing had the focus here and the remote did nothing.
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(life, active) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            // Only when nothing here is selected any more (a title you were on stays selected).
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && active && hasAny && !libFocused) scope.launch {
                runCatching { grid.scrollToItem(0); cwRow.scrollToItem(0) }; delay(30); runCatching { first.requestFocus() }
            }
        }
        life.addObserver(obs); onDispose { life.removeObserver(obs) }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()
        // Hidden (built ahead for Down from the dock) the remote must never move into it: moving around the dock
        // landed on its invisible cards, and nothing on screen was selected any more.
        .focusProperties { enter = { if (active) androidx.compose.ui.focus.FocusRequester.Default else androidx.compose.ui.focus.FocusRequester.Cancel } }
        .focusGroup()
        .onFocusChanged { libFocused = it.hasFocus }
        .background(Color(0xFF26262B))) {
        // The grey page is painted once into a picture with dithering: a gradient drawn live on the TV showed bands.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val bw = with(density) { maxWidth.roundToPx() }; val bh = with(density) { maxHeight.roundToPx() }
        val bg = remember(bw, bh) { greyPage(bw, bh) }
        androidx.compose.foundation.Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        // TV scrolling: the selected row always sits at the same height. Moving left / right inside a row never
        // scrolls (the default "just bring it into view" nudged the page up and down with every poster).
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides PivotScroll) {
        LazyVerticalGrid(
            state = grid,
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(start = (64 * s).dp, end = (64 * s).dp, top = (34 * s).dp, bottom = (40 * s).dp),
            horizontalArrangement = Arrangement.spacedBy((22 * s).dp),
            verticalArrangement = Arrangement.spacedBy((20 * s).dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Library", color = Color.White, fontSize = (26 * s).sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height((4 * s).dp))
                    Text(
                        if (hasAny) "OK opens · hold OK to remove" else "Nothing here yet. On the home screen, press + next to Play to save a title.",
                        color = Color.White.copy(alpha = 0.55f), fontSize = (10 * s).sp,
                    )
                }
            }
            // Continue Watching: one row of cards with everything inside them (where it came from, episode, progress).
            if (cw.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Section("Continue Watching", s) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "cw-row") {
                    val wide = st.libraryCwShape != "poster"
                    val cardW = ((if (wide) 236f else 132f) * st.libraryCwSize * s).dp
                    // Edge to edge: the first card isn't cut at the page's margin when it grows on focus.
                    val bleed = (64 * s).dp
                    androidx.compose.foundation.lazy.LazyRow(state = cwRow, horizontalArrangement = Arrangement.spacedBy((18 * s).dp),
                        contentPadding = PaddingValues(horizontal = bleed, vertical = (10 * s).dp),
                        modifier = up("c", 0).layout { m, c ->
                            val extra = bleed.roundToPx() * 2
                            val p = m.measure(c.copy(minWidth = c.minWidth + extra, maxWidth = c.maxWidth + extra))
                            layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
                        }) {
                        items(cw.size, key = { "c:" + cw[it].key }) { i ->
                            val it = cw[i]
                            val src = cwEntries.firstOrNull { e -> e.key == it.key }?.app.orEmpty()
                            CwCard(it, src, s, wide, st.libraryCwSource, Modifier.width(cardW).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                                onOpen = { NuvioLaunch.open(ctx, st, app.http, it) },
                                onRemove = { app.cw.remove(it.key); toast(ctx, "Removed from Continue Watching" + (if (src.isNotBlank() && src != "Nuvio Legacy") " and $src" else "") + " · ${it.name}") })
                        }
                    }
                }
            }
            if (saved.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Section("Saved", s) }
                itemsIndexed(saved, key = { _, it -> "s:" + it.key }) { i, it ->
                    Poster(it, s, st.libraryTitles, up("s", i).then(if (i == 0 && cw.isEmpty()) Modifier.focusRequester(first) else Modifier),
                        onOpen = { NuvioLaunch.open(ctx, st, app.http, it) },
                        onRemove = { app.library.remove(it.key); toast(ctx, "Removed · ${it.name}") })
                }
            }
            if (st.libraryNuvio && app.library.nuvio.connected) {
                item(span = { GridItemSpan(maxLineSpan) }) { Section(if (nuvioStatus.isNotBlank()) "Nuvio library · $nuvioStatus" else "Nuvio library", s) }
                itemsIndexed(nuvio, key = { _, it -> "n:" + it.key }) { i, it ->
                    Poster(it, s, st.libraryTitles, up("n", i).then(if (i == 0 && saved.isEmpty() && cw.isEmpty()) Modifier.focusRequester(first) else Modifier),
                        onOpen = { NuvioLaunch.open(ctx, st, app.http, it) },
                        onRemove = { app.library.removeFromNuvio(it) { ok -> toast(ctx.applicationContext, if (ok) "Removed from your Nuvio library · ${it.name}" else "Nuvio: couldn't remove ${it.name}") } })
                }
            }
            if (st.libraryTrakt && app.cw.trakt.connected) {
                item(span = { GridItemSpan(maxLineSpan) }) { Section(if (status.isNotBlank()) "Trakt watchlist · $status" else "Trakt watchlist", s) }
                itemsIndexed(trakt, key = { _, it -> "t:" + it.key }) { i, it ->
                    Poster(it, s, st.libraryTitles, up("t", i).then(if (i == 0 && saved.isEmpty() && nuvio.isEmpty() && cw.isEmpty()) Modifier.focusRequester(first) else Modifier),
                        onOpen = { NuvioLaunch.open(ctx, st, app.http, it) },
                        onRemove = { app.library.removeFromWatchlist(it); toast(ctx, "Removed from the Trakt watchlist · ${it.name}") })
                }
            }
            if (st.librarySimkl && app.cw.simkl.connected && simkl.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Section("Simkl · Plan to watch", s) }
                itemsIndexed(simkl, key = { _, it -> "k:" + it.key }) { i, it ->
                    Poster(it, s, st.libraryTitles, up("k", i).then(if (i == 0 && saved.isEmpty() && nuvio.isEmpty() && trakt.isEmpty() && cw.isEmpty()) Modifier.focusRequester(first) else Modifier),
                        onOpen = { NuvioLaunch.open(ctx, st, app.http, it) },
                        onRemove = { app.library.removeFromSimkl(it); toast(ctx, "Removed from Simkl · ${it.name}") })
                }
            }
        }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object PivotScroll : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Top of the selected row at 30% of the page (the first rows simply stay where they are).
        val target = containerSize * 0.30f
        val d = if (size > containerSize) offset else offset - target
        // Same row (left / right): never a pixel of scroll.
        return if (kotlin.math.abs(d) < 24f) 0f else d
    }
}

@Composable
private fun Section(title: String, s: Float) {
    Text(title, color = Color.White.copy(alpha = 0.8f), fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = (14 * s).dp))
}

@Composable
private fun Poster(item: HeroItem, s: Float, titles: Boolean, modifier: Modifier, onOpen: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val askRemove = LocalAsk.current
    val removeNow = onRemove
    @Suppress("NAME_SHADOWING") val onRemove = { askRemove(item.name, removeNow) }
    var focused by remember { mutableStateOf(false) }
    val glow = LocalGlow.current
    var gc by remember(item.key) { mutableStateOf(Glow.cached("p:" + item.key)) }
    val glowA by animateFloatAsState(if (focused && glow != "off") 1f else 0f, androidx.compose.animation.core.tween(220), label = "glow")
    // No overshoot: a bouncy grow made the posters wobble while you moved along a row.
    val k by animateFloatAsState(if (focused) 1.07f else 1f, androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "poster")
    val shape = RoundedCornerShape((10 * s).dp)
    val req = remember(item.poster) {
        ImageRequest.Builder(ctx).data(sharpPoster(item.poster).ifBlank { item.background }).size((180 * s * 2f).toInt(), (270 * s * 2f).toInt()).build()
    }
    Column(modifier.graphicsLayer { scaleX = k; scaleY = k }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f)
                .drawBehind {
                    // Soft shadow from one pre-blurred picture (smooth on every TV; elevation shadows banded).
                    val sh = PosterShadow.get()
                    val pad = size.width * PosterShadow.PAD
                    drawImage(sh, dstOffset = androidx.compose.ui.unit.IntOffset((-pad).toInt(), (-pad + size.height * (if (focused) 0.05f else 0.03f)).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize((size.width + 2 * pad).toInt(), (size.height + 2 * pad).toInt()),
                        alpha = if (focused) 0.75f else 0.45f, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
                    with(Glow) { glow(if (glow == "white") Color.White else gc ?: Color.White, glowA, tallShape = true) }
                }
                .clip(shape)
                .background(Color(0xFF4A4A50))
                .onFocusChanged { focused = it.isFocused }
                .tvPress(onLong = onRemove, onKey = { ev ->
                    if (ev.key == Key.Menu) { if (ev.type == KeyEventType.KeyDown) onRemove(); true } else false
                }, onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.name, color = Color.White.copy(alpha = 0.6f), fontSize = (10 * s).sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding((8 * s).dp))
            AsyncImage(req, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onSuccess = { r ->
                if (glow == "color" && gc == null) { val b = r.result.image; Thread { gc = Glow.colorOf("p:" + item.key, (b as? coil3.BitmapImage)?.bitmap) }.start() }
            })
        }
        if (titles) Text(item.name, color = Color.White.copy(alpha = if (focused) 0.95f else 0.6f), fontSize = (9.5f * s).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = (7 * s).dp))
    }
}

/** The colour of each source's dot, from its icon. */
fun sourceColor(app: String): Color = when (app) {
    "Trakt" -> Color(0xFFED2224)
    "Simkl" -> Color(0xFF1E90FF)
    "Stremio" -> Color(0xFF8A5AAB)
    "Nuvio", "Nuvio Legacy" -> Color(0xFF5B7CFF)
    else -> Color.White
}

/** A Continue Watching card: the picture with, inside it, where it came from, the title, the episode and the progress. */
@Composable
private fun CwCard(item: HeroItem, source: String, s: Float, wide: Boolean, showSource: Boolean, modifier: Modifier, onOpen: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    val askRemove = LocalAsk.current
    val removeNow = onRemove
    @Suppress("NAME_SHADOWING") val onRemove = { askRemove(item.name, removeNow) }
    var focused by remember { mutableStateOf(false) }
    val glow = LocalGlow.current
    var gc by remember(item.key) { mutableStateOf(Glow.cached("c:" + item.key)) }
    val glowA by animateFloatAsState(if (focused && glow != "off") 1f else 0f, androidx.compose.animation.core.tween(220), label = "glow")
    val k by animateFloatAsState(if (focused) 1.06f else 1f, androidx.compose.animation.core.tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "cw")
    val shape = RoundedCornerShape((12 * s).dp)
    val pic = if (wide) item.background.ifBlank { item.poster } else sharpPoster(item.poster).ifBlank { item.background }
    val req = remember(pic) { ImageRequest.Builder(ctx).data(pic).size((if (wide) 640 else 360) , (if (wide) 360 else 540)).build() }
    Box(
        modifier.graphicsLayer { scaleX = k; scaleY = k }.aspectRatio(if (wide) 16f / 9f else 2f / 3f)
            .drawBehind { with(Glow) { glow(if (glow == "white") Color.White else gc ?: Color.White, glowA, tallShape = !wide) } }
            .clip(shape).background(Color(0xFF4A4A50))
            .border(if (focused) 1.5.dp else 0.dp, Color.White.copy(alpha = if (focused) 0.85f else 0f), shape)
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onLong = onRemove, onKey = { ev -> if (ev.key == Key.Menu) { if (ev.type == KeyEventType.KeyDown) onRemove(); true } else false }, onClick = onOpen),
    ) {
        AsyncImage(req, item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onSuccess = { r ->
            if (glow == "color" && gc == null) { val b = r.result.image; Thread { gc = Glow.colorOf("c:" + item.key, (b as? coil3.BitmapImage)?.bitmap) }.start() }
        })
        // Soft shade at the bottom for the text (a small picture, painted once).
        androidx.compose.foundation.Image(CwShade.get(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        if (showSource && source.isNotBlank()) Row(
            Modifier.align(Alignment.TopStart).padding((7 * s).dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = (7 * s).dp, vertical = (3 * s).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size((6 * s).dp).clip(androidx.compose.foundation.shape.CircleShape).background(sourceColor(source)))
            Spacer(Modifier.width((5 * s).dp))
            Text(source, color = Color.White, fontSize = (8 * s).sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = (9 * s).dp, vertical = (8 * s).dp)) {
            Text(item.name, color = Color.White, fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val pct = (item.progress * 100).toInt().coerceIn(0, 100)
            Text(listOf(item.sub, "$pct%").filter { it.isNotBlank() }.joinToString("  ·  "), color = Color.White.copy(alpha = 0.75f), fontSize = (8.5f * s).sp)
            Spacer(Modifier.height((5 * s).dp))
            Box(Modifier.fillMaxWidth().height((3 * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(item.progress.coerceIn(0f, 1f)).height((3 * s).dp).clip(RoundedCornerShape(50)).background(Color.White))
            }
        }
    }
}

private object CwShade {
    private var img: ImageBitmap? = null
    fun get(): ImageBitmap = img ?: run {
        val b = android.graphics.Bitmap.createBitmap(8, 128, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 40f, 0f, 128f, 0, 0xE0000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        android.graphics.Canvas(b).drawRect(0f, 0f, 8f, 128f, p)
        b.asImageBitmap().also { img = it }
    }
}

/** The Library's grey page: a soft vertical gradient, dithered so the TV shows no steps. */
private fun greyPage(w: Int, h: Int): ImageBitmap {
    val b = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
    val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.25f, h.toFloat(),
        intArrayOf(0xFF3B3B41.toInt(), 0xFF2B2B30.toInt(), 0xFF1E1E22.toInt()), floatArrayOf(0f, 0.55f, 1f), android.graphics.Shader.TileMode.CLAMP)
    android.graphics.Canvas(b).drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    b.setHasAlpha(false)
    return b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap()
}

/** One blurred rounded shape, drawn behind every poster (scaled), for a light, soft shadow. */
private object PosterShadow {
    /** Blur margin on each side, as a fraction of the poster width. */
    const val PAD = 0.16f
    private var img: ImageBitmap? = null
    fun get(): ImageBitmap = img ?: run {
        val w = 160; val h = 240; val pad = (w * PAD).toInt()
        val b = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG).apply {
            color = android.graphics.Color.argb(170, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(pad * 0.8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        android.graphics.Canvas(b).drawRoundRect(pad + 4f, pad + 8f, pad + w - 4f, pad + h.toFloat(), 14f, 14f, p)
        b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap().also { img = it }
    }
}

/** A bigger copy of the poster when the site has one (catalog posters are often thumbnails: w185 / w342, "small"). */
private fun sharpPoster(u: String): String = when {
    u.isBlank() -> u
    "image.tmdb.org/t/p/" in u -> u.replace(Regex("/t/p/(w\\d+|original)/"), "/t/p/w780/")
    "images.metahub.space/poster/" in u -> u.replace(Regex("/poster/(small|medium)/"), "/poster/large/")
    else -> u
}
