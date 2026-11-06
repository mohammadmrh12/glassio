package com.glasslauncher.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * A small glass menu next to what it's about (hold OK on Play, or on an app in the dock), like the Apple TV
 * context menu: a few rows, the selected one white. A row can open a page of pictures to pick from.
 */
class PopOption(
    val label: String,
    /** Shown on the right, e.g. "✓" when it is on. */
    val trailing: String = "",
    /** Opens a page of pictures in the menu instead of closing it. */
    val thumbs: PopThumbs? = null,
    val onClick: () -> Unit = {},
)

class PopThumbs(val title: String, val urls: List<String>, val current: Int, val onPick: (String) -> Unit)

class PopState(val title: String, val options: List<PopOption>, val anchor: Rect?, val side: Side = Side.Above) {
    enum class Side { Above, Right }
}

/** Where the menu opens from: the Play button and the dock's apps report their place on screen here. */
object PopAnchors {
    @Volatile var play: Rect? = null
    /** Where the open pop-up menu is (a trailer in its window then sits next to it), or null. */
    val menu = androidx.compose.runtime.mutableStateOf<Rect?>(null)
    val tiles = java.util.concurrent.ConcurrentHashMap<String, Rect>()
}

@Composable
fun PopMenuHost(state: PopState?, s: Float, style: String, onDismiss: () -> Unit) {
    if (state == null) return
    androidx.compose.runtime.key(state) { PopMenu(state, s, style, onDismiss) }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun PopMenu(state: PopState, s: Float, style: String, onDismiss: () -> Unit) {
    val light = style == "bow"
    val ink = if (light) Color(0xFF111114) else Color.White
    var page by remember { mutableStateOf<PopThumbs?>(null) }
    // The menu opens while OK is still held (long press): that OK is ignored until it is released and pressed again,
    // or its release would choose the first row.
    var armed by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    var rowAt by remember { mutableStateOf(0) }
    var thumbAt by remember { mutableStateOf(0) }
    val arrows = remember { setOf(androidx.compose.ui.input.key.Key.DirectionUp, androidx.compose.ui.input.key.Key.DirectionDown,
        androidx.compose.ui.input.key.Key.DirectionLeft, androidx.compose.ui.input.key.Key.DirectionRight) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(180)) }
    LaunchedEffect(page) { repeat(8) { kotlinx.coroutines.delay(30); if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect } }
    // Back is read from the key itself: the home screen's own Back actions (screensaver, dock) could take it first,
    // and the menu stayed open with the remote somewhere else.
    val back = { if (page != null) page = null else onDismiss() }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { PopAnchors.menu.value = null } }

    val width = ((if (page != null) 470 else 236) * s).dp
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val rootW = constraints.maxWidth; val rootH = constraints.maxHeight
        val gap = with(androidx.compose.ui.platform.LocalDensity.current) { (12 * s).dp.roundToPx() }
        Box(
            Modifier
                .layout { m, c ->
                    val p = m.measure(Constraints(maxWidth = c.maxWidth, maxHeight = c.maxHeight))
                    val a = state.anchor
                    val (x, y) = when {
                        a == null -> ((rootW - p.width) / 2) to ((rootH - p.height) / 2)
                        state.side == PopState.Side.Right -> (a.right.toInt() + gap) to (a.center.y.toInt() - p.height / 2)
                        else -> (a.center.x.toInt() - p.width / 2) to (a.top.toInt() - gap - p.height)
                    }
                    val px = x.coerceIn(gap, (rootW - p.width - gap).coerceAtLeast(gap)); val py = y.coerceIn(gap, (rootH - p.height - gap).coerceAtLeast(gap))
                    val r = Rect(px.toFloat(), py.toFloat(), (px + p.width).toFloat(), (py + p.height).toFloat())
                    if (PopAnchors.menu.value != r) PopAnchors.menu.value = r
                    layout(c.maxWidth, c.maxHeight) { p.place(px, py) }
                }
                .width(width)
                .graphicsLayer {
                    alpha = enter.value
                    val k = 0.94f + 0.06f * enter.value; scaleX = k; scaleY = k
                }
                // The remote stays in the menu until it closes: past its last row Down did nothing in the menu and went
                // on to the dock. The menu is a focus group that never lets the focus out.
                .focusProperties { @Suppress("DEPRECATION") exit = { FocusRequester.Cancel } }
                .focusGroup()
                .onPreviewKeyEvent { ev ->
                    if (ev.key == androidx.compose.ui.input.key.Key.Back || ev.key == androidx.compose.ui.input.key.Key.Escape) {
                        if (ev.type == KeyEventType.KeyUp) back()
                        return@onPreviewKeyEvent true
                    }
                    // Belt and braces: an arrow that would leave the menu is kept in it.
                    if (ev.type == KeyEventType.KeyDown && ev.key in arrows) {
                        val dir = when (ev.key) {
                            androidx.compose.ui.input.key.Key.DirectionUp -> androidx.compose.ui.focus.FocusDirection.Up
                            androidx.compose.ui.input.key.Key.DirectionDown -> androidx.compose.ui.focus.FocusDirection.Down
                            androidx.compose.ui.input.key.Key.DirectionLeft -> androidx.compose.ui.focus.FocusDirection.Left
                            else -> androidx.compose.ui.focus.FocusDirection.Right
                        }
                        // Rows: only Up / Down; pictures: only Left / Right.
                        val inList = if (page == null) dir == androidx.compose.ui.focus.FocusDirection.Up || dir == androidx.compose.ui.focus.FocusDirection.Down
                            else dir == androidx.compose.ui.focus.FocusDirection.Left || dir == androidx.compose.ui.focus.FocusDirection.Right
                        if (!inList) return@onPreviewKeyEvent true
                        val idx = if (page == null) rowAt else thumbAt
                        val last = if (page == null) state.options.lastIndex else (page?.urls?.lastIndex ?: 0)
                        val back = dir == androidx.compose.ui.focus.FocusDirection.Up || dir == androidx.compose.ui.focus.FocusDirection.Left
                        if ((back && idx <= 0) || (!back && idx >= last)) return@onPreviewKeyEvent true
                        return@onPreviewKeyEvent false
                    }
                    if (ev.type == KeyEventType.KeyUp && ev.key in arrows) return@onPreviewKeyEvent false
                    if (!ev.isSelect || armed) false
                    else if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { armed = true; false }
                    else true
                },
        ) {
            GlassPanel(Modifier.fillMaxWidth(), radius = (20 * s).dp, style = style, darken = 0.3f) {
                Column(Modifier.fillMaxWidth().padding((8 * s).dp)) {
                    val p = page
                    Text(p?.title ?: state.title, color = ink.copy(alpha = 0.6f), fontSize = (10.5f * s).sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = (10 * s).dp, top = (4 * s).dp, bottom = (6 * s).dp))
                    if (p == null) state.options.forEachIndexed { i, o ->
                        PopRow(o, s, ink, light, (if (i == 0) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) rowAt = i }) {
                            if (o.thumbs != null) page = o.thumbs else { onDismiss(); o.onClick() }
                        }
                    } else ThumbStrip(p, s, first, { thumbAt = it }) { url -> onDismiss(); p.onPick(url) }
                }
            }
        }
    }
}

@Composable
private fun PopRow(o: PopOption, s: Float, ink: Color, light: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val bg by animateFloatAsState(if (f) 1f else 0f, tween(120), label = "row")
    Row(
        modifier.fillMaxWidth().padding(vertical = (1 * s).dp)
            .clip(RoundedCornerShape((12 * s).dp))
            .background((if (light) Color(0xFF111114) else Color.White).copy(alpha = 0.94f * bg))
            .onFocusChanged { f = it.isFocused }
            .tvPress(onClick = onClick)
            .padding(horizontal = (12 * s).dp, vertical = (7.5f * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = if (f) (if (light) Color.White else Color(0xFF111114)) else ink
        Text(o.label, color = c, fontSize = (12.5f * s).sp, fontWeight = if (f) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (o.trailing.isNotBlank() || o.thumbs != null)
            Text(if (o.thumbs != null) "›" else o.trailing, color = c.copy(alpha = 0.7f), fontSize = (12.5f * s).sp, fontWeight = FontWeight.Medium)
    }
}

/** The title's pictures, small: the one in use has a tick; OK picks one. */
@Composable
private fun ThumbStrip(p: PopThumbs, s: Float, first: FocusRequester, onAt: (Int) -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val start = p.current.coerceIn(0, (p.urls.size - 1).coerceAtLeast(0))
    val list = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = (start - 1).coerceAtLeast(0))
    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy((8 * s).dp),
        contentPadding = PaddingValues(horizontal = (6 * s).dp, vertical = (6 * s).dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(p.urls) { i, url ->
            var f by remember { mutableStateOf(false) }
            val k by animateFloatAsState(if (f) 1.06f else 1f, tween(140), label = "thumb")
            val req = remember(url) { ImageRequest.Builder(ctx).data(url).size(320, 180).build() }
            Box(
                Modifier.width((132 * s).dp).aspectRatio(16f / 9f)
                    .then(if (i == start) Modifier.focusRequester(first) else Modifier)
                    .onFocusChanged { f = it.isFocused; if (it.isFocused) onAt(i) }
                    .tvPress(onClick = { onPick(url) })
                    .graphicsLayer { scaleX = k; scaleY = k }
                    .clip(RoundedCornerShape((10 * s).dp))
                    .background(Color(0xFF3A3A40))
                    .border(if (f) (2 * s).dp else 0.dp, Color.White.copy(alpha = if (f) 0.95f else 0f), RoundedCornerShape((10 * s).dp)),
            ) {
                AsyncImage(req, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (i == p.current) Box(
                    Modifier.align(Alignment.TopEnd).padding((5 * s).dp).clip(RoundedCornerShape(50)).background(Color.White)
                        .padding(horizontal = (6 * s).dp, vertical = (1 * s).dp),
                ) { Text("✓", color = Color(0xFF111114), fontSize = (9 * s).sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
    Spacer(Modifier.height((2 * s).dp))
}
