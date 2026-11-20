// The dock and everything in it: the grid, the strip and the column, its glass, the icons' plates and frames, the
// Apps folder. (Split out of HomeScreen.kt, which puts the home screen together.)
package com.glasslauncher.tv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.ui.graphics.ColorMatrix
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.request.allowHardware
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.Art
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

const val DOCK_COLUMNS = 6

/** Called when an arrow leads out of a grid edge; true when it did something (no bounce then). */
private val LocalEdge = androidx.compose.runtime.compositionLocalOf<(androidx.compose.ui.focus.FocusDirection) -> Boolean> { { false } }

/** Pseudo package used by move mode for the Apps folder tile. */
internal const val FOLDER = "__apps_folder__"

/** Every dock measurement in dp, from the screen width and the "Dock size" setting (1.0 = full width). */
internal class DockMetrics(val s: Float, val screenW: Float, dockScale: Float, val cols: Int = DOCK_COLUMNS) {
    val padH = 18 * s * dockScale
    val gap = 14 * s * dockScale
    private val fullCellW = (screenW - 2 * 24 * s - 2 * 18 * s - (cols - 1) * 12 * s) / cols
    val cellW = fullCellW * dockScale
    val dockW = cols * cellW + (cols - 1) * gap + 2 * padH
    val sideMargin = (screenW - dockW) / 2f
    val tileH = cellW / 1.6f
    val cellH = tileH
    // The bottom padding is smaller than the row gap so the next row never peeks out of a collapsed dock.
    val padTop = 12 * s * dockScale
    val padBottom = 10 * s * dockScale
    val bottomMargin = 22 * s
    val collapsedH = padTop + cellH + padBottom
}

/** Which tile of the (bottom) dock is selected: one in a lower row is out of sight while the dock is folded. */
internal object DockFocus {
    @Volatile var idx = -1
    /** The carousel (or what stands in for it) holds the selection. */
    @Volatile var hero = false
    /** The settings button at the top holds it. */
    @Volatile var top = false
    /** Something in the dock holds it. */
    @Volatile var dock = false
    fun on(i: Int, f: Boolean) { if (f) idx = i else if (idx == i) idx = -1 }
    /** Apps in one dock row (Settings → Dock & icons); the strip dock has one row. */
    @Volatile var cols = DOCK_COLUMNS
    val lowerRow get() = cols > 0 && idx >= cols
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Dock(
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
    /** The column on the left: Right opens the menu. */
    onRight: () -> Unit = {},
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
    val perRow = metrics.cols
    val rows = remember(items, perRow) { items.chunked(perRow) }
    // Fewer apps than a full row: the dock wraps them (the tiles keep their size).
    val cols = if (settings.dockFit) items.size.coerceIn(1, perRow) else perRow
    val side = metrics.sideMargin + (perRow - cols) * (metrics.cellW + metrics.gap) / 2f
    // Down from the last row opens the Library.
    val edge: (androidx.compose.ui.focus.FocusDirection) -> Boolean = { d ->
        if (d == androidx.compose.ui.focus.FocusDirection.Down && settings.libraryDown) { onLibrary(); true } else false
    }
    val strip = settings.dockStrip && !vertical
    androidx.compose.runtime.SideEffect { DockFocus.cols = if (strip || vertical) 0 else perRow }
    if (vertical) {
        VerticalDock(app, s, metrics, screenH, radius, items, movingPkg, onMoveKey, firstTile, heroFocus, folderFocus, tileFocus, onMove,
            onMenu, onFolder, onFolderMenu, modifier.onPreviewKeyEvent { ev ->
                // Right from the column: the menu (nothing else is to its right).
                if (ev.key == Key.DirectionRight && movingPkg == null) { if (ev.type == KeyEventType.KeyDown) onRight(); true } else false
            }, edge, onExpanded)
        return
    }
    if (strip) {
        StripDock(app, s, metrics, radius, items, movingPkg, onMoveKey, firstTile, heroFocus, folderFocus, tileFocus, onMove,
            onMenu, onFolder, onFolderMenu, modifier.padding(bottom = if (centered) 0.dp else metrics.bottomMargin.dp), edge, onExpanded, onCover)
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

    DockGlass(
        off = settings.dockGlassOff,
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
        val genv = rememberGlassStatic()
        val veilUp by remember(genv.veil) { androidx.compose.runtime.derivedStateOf { (genv.veil?.value ?: 0f) > 0.02f } }
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides (if (fits) NoScroll else androidx.compose.foundation.gestures.LocalBringIntoViewSpec.current),
            LocalDockOpen provides (expanded || veilUp),
        ) {
        // With no icon selected nothing in the row changes: it is kept as one ready picture. Drawn piece by piece in
        // every frame while the page behind moved (six icons, their plates and rims), the row cost the graphics chip
        // 4 ms of a slide's 21 ms a frame; the slide ran at 30 frames a second because of it.
        var rowFocused by remember { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().onFocusChanged { rowFocused = it.hasFocus }.graphicsLayer {
                alpha = if (DebugHide.intValue and 65536 != 0) 0f else 1f
                if (!rowFocused && still && movingPkg == null) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            }.verticalScroll(scroll)
                .padding(start = metrics.padH.dp, end = metrics.padH.dp, top = metrics.padTop.dp, bottom = metrics.padBottom.dp),
            verticalArrangement = Arrangement.spacedBy(metrics.gap.dp),
        ) {
            rows.forEachIndexed { r, row ->
                androidx.compose.runtime.CompositionLocalProvider(LocalEdge provides (if (r == rows.lastIndex) edge else { _ -> false })) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp)) {
                    row.forEachIndexed { c, e ->
                        val idx = r * perRow + c
                        val edges = gridEdges(c, row.size, r, rows.size, allowUp = true)
                        val base = Modifier.weight(1f)
                            .then(if (idx == 0) Modifier.focusRequester(firstTile) else Modifier)
                            .then(if (r == 0) Modifier.focusProperties { up = if (ChannelsUi.showing) ChannelsUi.entry else if (MusicUi.appsVisible.value && e?.pkg == MusicUi.DOCK_PKG) MusicUi.appsEntry else if (InputsUi.page.value && e?.pkg == com.glasslauncher.tv.data.Inputs.DOCK_PKG) InputsUi.entry else heroFocus } else Modifier)
                        if (e == null) {
                            key(FOLDER) {
                                FolderTile(
                                    app, s, radius, moving = movingPkg == FOLDER,
                                    modifier = base.focusRequester(folderFocus).onGloballyPositioned { PopAnchors.tiles[FOLDER] = it.boundsInRoot() },
                                    onFocus = { f -> DockFocus.on(idx, f); if (f) { onExpanded(r > 0); onMove() } },
                                    onMoveKey = onMoveKey, onClick = onFolder, onLong = onFolderMenu, edges = edges,
                                )
                            }
                        } else key(e.pkg) {
                            // A selected app taken off the dock (uninstalled): the selection leaves it (its player stayed).
                            androidx.compose.runtime.DisposableEffect(e.pkg) { onDispose { if (ChannelsUi.dockPkg.value == e.pkg) ChannelsUi.dockPkg.value = null } }
                            DockTile(
                                entry = e, app = app, s = s, radius = radius, moving = movingPkg == e.pkg,
                                modifier = base.focusRequester(tileFocus.requester(e.pkg))
                                    .onGloballyPositioned { PopAnchors.tiles[e.pkg] = it.boundsInRoot() },
                                onFocus = { f ->
                                    DockFocus.on(idx, f); if (f) { onExpanded(r > 0); onMove(); ChannelsUi.back = null }
                                    // Channels: the app selected in the first row (its rows take the carousel's place).
                                    if (f && r == 0) ChannelsUi.dockPkg.value = e.pkg else if (ChannelsUi.dockPkg.value == e.pkg) ChannelsUi.dockPkg.value = null
                                },
                                onMoveKey = onMoveKey,
                                onClick = { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) },
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
    DockGlass(
        off = app.settings.dockGlassOff,
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
                        FolderTile(app, s, radius, moving = movingPkg == FOLDER, modifier = base.focusRequester(folderFocus).onGloballyPositioned { PopAnchors.tiles[FOLDER] = it.boundsInRoot() },
                            onFocus = { f -> if (f) { onExpanded(false); onMove() } }, onMoveKey = onMoveKey, onClick = onFolder, onLong = onFolderMenu, edges = edges)
                    } else key(e.pkg) {
                        DockTile(entry = e, app = app, s = s, radius = radius, moving = movingPkg == e.pkg,
                            modifier = base.focusRequester(tileFocus.requester(e.pkg))
                                    .onGloballyPositioned { PopAnchors.tiles[e.pkg] = it.boundsInRoot() },
                            onFocus = { f -> if (f) { onExpanded(false); onMove() } }, onMoveKey = onMoveKey,
                            onClick = { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) },
                            onLong = { onMenu(e) }, edges = edges)
                    }
                }
            }
        }
    }
}

/** The dock's glass, or nothing behind the icons (Settings → Dock & icons → Dock glass off). */
@Composable
private fun DockGlass(off: Boolean, modifier: Modifier, radius: Dp, style: String, refract: Boolean, cache: Boolean, content: @Composable BoxScope.() -> Unit) {
    // What the icons sit on: clear glass (or the page itself) shows the same sharp picture a clear icon would.
    androidx.compose.runtime.CompositionLocalProvider(LocalClearBehind provides (off || style.startsWith("water"))) {
        if (off) Box(modifier, content = content)
        else GlassPanel(modifier = modifier, radius = radius, style = style, refract = refract, cache = cache, content = content)
    }
}

/**
 * The dock as one strip from edge to edge: every app in one row, the selected one stays in the middle and the strip
 * moves under it.
 */
@Composable
private fun StripDock(
    app: GlassApp, s: Float, metrics: DockMetrics, radius: Int, items: List<AppEntry?>,
    movingPkg: String?, onMoveKey: (KeyEvent) -> Boolean, firstTile: FocusRequester, heroFocus: FocusRequester,
    folderFocus: FocusRequester, tileFocus: HashMap<String, FocusRequester>, onMove: () -> Unit,
    onMenu: (AppEntry) -> Unit, onFolder: () -> Unit, onFolderMenu: () -> Unit, modifier: Modifier,
    edge: (androidx.compose.ui.focus.FocusDirection) -> Boolean,
    onExpanded: (Boolean) -> Unit,
    onCover: (androidx.compose.ui.geometry.Rect) -> Unit,
) {
    val ctx = LocalContext.current
    val settings = app.settings
    val n = items.size.coerceAtLeast(1)
    // The selected app always in the middle, the others on both sides, all the way round: past the last app comes
    // the first again (with the first selected the left side was empty).
    // [pos] counts every step (it doesn't wrap), so the strip always slides the short way.
    // The selection is kept as the app itself (its key), never as its place in the list: when the list changes
    // (an app hidden, shown, installed, removed, moved) its place is looked up again, so it stays in the middle. Kept
    // as a place, the strip went on centring whatever app now stood there, and its count of steps, taken round a
    // list of another length, pointed somewhere else again: the selected app ended up off to one side until the
    // dock was built again (the Apps folder opened and closed).
    val keys = remember(items) { items.map { it?.pkg ?: FOLDER } }
    var selKey by remember { mutableStateOf(keys.firstOrNull()) }
    val lastSel = remember { intArrayOf(0) }
    // (Its app gone from the list: the one that took its place.)
    val sel = keys.indexOf(selKey).let { if (it >= 0) it else lastSel[0].coerceIn(0, n - 1) }
    lastSel[0] = sel
    androidx.compose.runtime.SideEffect { if (selKey !in keys) selKey = keys.getOrNull(sel) }
    // [pos] counts every step and [shift] follows it: what is left between them is how far the strip still has to
    // slide. Places are counted from the selected app, so only that difference matters.
    var pos by remember { mutableIntStateOf(0) }
    val shift by animateFloatAsState(pos.toFloat(), spring(0.9f, 420f), label = "stripShift")
    val step = metrics.cellW + metrics.gap
    // Only whole apps on screen, as many on the left as on the right (an odd number that fits): the ones further
    // out fade as they slide past the edge (a sliver of an app showed at one side, and one side had one more).
    val fit = run {
        var k = ((metrics.screenW - 2 * 24 * s + metrics.gap) / step).toInt().coerceAtLeast(1)
        if (k % 2 == 0) k--
        k.coerceAtLeast(1)
    }
    val halfOn = if (n > fit) (fit - 1) / 2f else Float.MAX_VALUE
    val focus = remember(items) { items.map { e -> if (e == null) folderFocus else tileFocus.requester(e.pkg) } }
    val onSel: (Int) -> Unit = { i ->
        if (i != sel || keys.getOrNull(i) != selKey) {
            // The shortest way round from the last one.
            var d = (i - sel) % n; if (d > n / 2) d -= n; if (d < -(n - 1) / 2) d += n
            pos += d; selKey = keys.getOrNull(i)
        }
    }
    LaunchedEffect(items, movingPkg) {
        val p = movingPkg ?: return@LaunchedEffect
        delay(30)
        runCatching { (if (p == FOLDER) folderFocus else tileFocus[p])?.requestFocus() }
    }
    LaunchedEffect(Unit) { onCover(androidx.compose.ui.geometry.Rect.Zero) }
    DockGlass(
        off = settings.dockGlassOff,
        modifier = modifier.fillMaxWidth().height(metrics.collapsedH.dp),
        // (The rims bend the picture like the other docks' do. It was off from the day the strip was made, with no
        // cost measured; a slide costs the same with it on.)
        radius = 0.dp, style = settings.dockStyle, refract = true, cache = true,
    ) {
        Box(Modifier.fillMaxSize().padding(top = metrics.padTop.dp, bottom = metrics.padBottom.dp)) {
            items.forEachIndexed { i, e ->
                // Where this app sits from the middle, in steps (between -n/2 and n/2: the far ones go round).
                fun place(): Float {
                    var d = (i - sel + (pos - shift)) % n
                    if (d < -n / 2f) d += n
                    if (d >= n / 2f) d -= n
                    return d
                }
                val edges = setOf(androidx.compose.ui.focus.FocusDirection.Down)
                val base = Modifier.width(metrics.cellW.dp).fillMaxHeight()
                    .offset { androidx.compose.ui.unit.IntOffset(((metrics.screenW / 2f - metrics.cellW / 2f + place() * step) * density).toInt(), 0) }
                    .graphicsLayer { alpha = (halfOn + 1f - kotlin.math.abs(place())).coerceIn(0f, 1f) }
                    .then(if (i == 0) Modifier.focusRequester(firstTile) else Modifier)
                    .focusProperties {
                        left = focus[(i - 1 + n) % n]; right = focus[(i + 1) % n]
                        up = if (ChannelsUi.showing) ChannelsUi.entry else if (MusicUi.appsVisible.value && e?.pkg == MusicUi.DOCK_PKG) MusicUi.appsEntry else if (InputsUi.page.value && e?.pkg == com.glasslauncher.tv.data.Inputs.DOCK_PKG) InputsUi.entry else heroFocus
                    }
                androidx.compose.runtime.CompositionLocalProvider(LocalEdge provides edge) {
                    if (e == null) key(FOLDER) {
                        FolderTile(app, s, radius, moving = movingPkg == FOLDER, modifier = base.focusRequester(folderFocus).onGloballyPositioned { PopAnchors.tiles[FOLDER] = it.boundsInRoot() },
                            onFocus = { f -> DockFocus.on(i, f); if (f) { onSel(i); onExpanded(false); onMove() } }, onMoveKey = onMoveKey, onClick = onFolder, onLong = onFolderMenu, edges = edges)
                    } else key(e.pkg) {
                        androidx.compose.runtime.DisposableEffect(e.pkg) { onDispose { if (ChannelsUi.dockPkg.value == e.pkg) ChannelsUi.dockPkg.value = null } }
                        DockTile(entry = e, app = app, s = s, radius = radius, moving = movingPkg == e.pkg,
                            modifier = base.focusRequester(focus[i])
                                .onGloballyPositioned { PopAnchors.tiles[e.pkg] = it.boundsInRoot() },
                            onFocus = { f ->
                                DockFocus.on(i, f); if (f) { onSel(i); onExpanded(false); onMove(); ChannelsUi.back = null }
                                if (f) ChannelsUi.dockPkg.value = e.pkg else if (ChannelsUi.dockPkg.value == e.pkg) ChannelsUi.dockPkg.value = null
                            },
                            onMoveKey = onMoveKey,
                            onClick = { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) },
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
    val env = rememberGlassStatic()
    // Dock opening or open (page blurred and darkened behind it), or inside the folder: the page behind is (about
    // to be) one blurred image, so a plain translucent pane looks the same and costs the GPU nothing extra. It
    // switches the moment the dock starts to open: glass in every icon while the second row slid in made the
    // opening stutter.
    val veiled by remember(env.veil) { androidx.compose.runtime.derivedStateOf { (env.veil?.value ?: 0f) > 0.95f } }
    val pane = (flat || veiled || LocalDockOpen.current) && (style == "liquid" || style == "clear" || style.startsWith("water"))
    val shape = RoundedCornerShape(if (GlassShape.square.value) 0.dp else radius)
    // The icon always sits in the same place: switching the plate under it no longer rebuilds every icon.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fill = Modifier.matchParentSize()
        if (pane) {
            // A light glass pane over the blurred page: it reads as the theme's glass, not a grey card (issue #8).
            val clearGlass = style.startsWith("water")
            Box(fill.background(Color.White.copy(alpha = when { focused -> 0.22f; clearGlass -> 0.07f; else -> 0.11f }), shape)
                .border(if (focused) 1.2.dp else 0.7.dp, Color.White.copy(alpha = if (focused) 0.6f else if (clearGlass) 0.22f else 0.16f), shape))
        } else when (style) {
            // Clear glass that bends nothing (an icon that isn't selected) on a clear dock or on the page itself shows
            // exactly the picture already drawn under it: only its light tint is drawn. (It also lets the whole row of
            // icons be kept as one ready picture while the page moves: see the dock.)
            "water", "water_still" -> if (!focused && LocalClearBehind.current) {
                // (The glass colour only where no dock glass is under the icon (Dock glass off): on the dock's glass it
                // is there already, and coloured twice the plates were darker than the dock and than the selected icon.)
                val gt = if (LocalOnGlass.current) Color.Transparent else GlassTint.color.value
                Box(fill.clip(shape).background(Color.White.copy(alpha = 0.02f)).then(if (gt.alpha > 0f) Modifier.background(gt) else Modifier)
                    .border(0.6.dp, Color.White.copy(alpha = 0.16f), shape))
            } else {
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
                // Slides inside like the clear glass, in step with the page.
            )
        }
        Box(fill.clip(shape), contentAlignment = Alignment.Center, content = content)
    }
}

/** The dock is opening or open: its icons use plain panes (see TilePlate). */
val LocalDockOpen = androidx.compose.runtime.compositionLocalOf { false }

/** The surface under the dock's icons is clear glass or the page itself (see [TilePlate]). */
val LocalClearBehind = androidx.compose.runtime.compositionLocalOf { false }

private val BowRest = Brush.verticalGradient(listOf(Color(0xFFE9E5DD), Color(0xFFD9D4CA)))

private val BowFocused = Brush.verticalGradient(listOf(Color(0xFFF3F0EA), Color(0xFFE5E1D8)))

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
    val r = if (GlassShape.square.value) 0.dp else (radius * 0.75f * s).dp
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
                // (The glow's hole has the tile's own corners: with smaller tiles (more apps in a row) its corners were
                // sharper than the tile's, and a dark gap showed at each corner of the selected icon.)
                .drawBehind { with(Glow) { glow(gColor, glowA.value, grow = scale, corner = r.toPx()) } }
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
internal fun DockTile(
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
    val hueApps = app.settings.hueApps && com.glasslauncher.tv.data.HueSync.ready(app.settings)
    val artColor = if (glow == "color" || hueApps) art?.let { a -> remember(a) { Glow.colorOf("app:" + entry.pkg + rev, a.bmp.asAndroidBitmap()) } } else null
    val glowColor = if (glow == "color") artColor else null
    TileFrame(s, radius, moving, label, alwaysLabel, modifier, onFocus, onMoveKey, onClick, onLong, solid, app.settings.tileLift, app.settings.edgeBounce, edges,
        zoom = zoom, glow = glow, glowColor = glowColor) { focused, r ->
        HueFocus(app, hueApps, focused, artColor)
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
                                .background(if (style == "bow") Color(0xFFE8E4DC) else Color.White.copy(alpha = 0.06f))) {
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
internal fun FolderOverlay(
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
    val envRef = androidx.compose.runtime.rememberUpdatedState(env)
    CompositionLocalProvider(LocalGlassEnv provides env, LocalGlassEnvRef provides envRef) {
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
                                        val fr = focusOf.requester(e.pkg)
                                        val edges = gridEdges(c, row.size, r, rowsCount, allowUp = false)
                                        DockTile(
                                            entry = e, app = app, s = s, radius = radius, moving = moving == e.pkg,
                                            modifier = Modifier.weight(1f).focusProperties { canFocus = !blocked }.focusRequester(fr).then(if (r == 0 && c == 0) Modifier.focusRequester(first) else Modifier),
                                            onFocus = {}, onMoveKey = onMoveKey,
                                            onClick = { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) },
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
