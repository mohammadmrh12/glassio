package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
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
import com.glasslauncher.tv.data.Channels

/** Which app's channels stand in the carousel's place (Settings → Channels). */
object ChannelsUi {
    /** The app selected in the dock's first row (nothing when the selection is elsewhere). */
    val dockPkg = mutableStateOf<String?>(null)
    /** The app whose rows show now. */
    val stagePkg = mutableStateOf<String?>(null)
    /** The selection is inside the rows. */
    @Volatile var focused = false
    /** Where Up from the dock goes while the rows show: their lowest row. */
    val entry = FocusRequester()
    val showing get() = stagePkg.value != null
    /** Up from the rows went to the carousel: Down from it goes back to this app (not the first one). */
    @Volatile var back: FocusRequester? = null
}

/**
 * An app's channels in the carousel's place: up to three rows, one above the other, each with its name and its cards,
 * on the Library's grey page. ▲ from the dock goes into the lowest row, ▼ from it back to the app, ▲ from the top row
 * back to the carousel. Every move is decided here: left to the system, Up from a row could land on the carousel's
 * hidden buttons behind (nothing on screen was selected), and Right past the last card went on to the menu.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ChannelStage(app: com.glasslauncher.tv.GlassApp, s: Float, pkg: String, rows: List<Channels.Channel>, bottom: Float, onDown: () -> Unit, onUp: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val label = remember(pkg) { app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == pkg }?.label ?: pkg.substringAfterLast('.') }
    // Fewer rows: bigger cards.
    val cardH = when (rows.size) { 1 -> 150f; 2 -> 118f; else -> 88f } * s
    // One focus handle per card ("row key#index"), each row's scroll and the card last selected in it.
    val reqs = remember(pkg) { HashMap<String, FocusRequester>() }
    fun req(r: Int, i: Int) = reqs.getOrPut("${rows[r].key}#$i") { FocusRequester() }
    val lastSel = remember(pkg) { HashMap<String, Int>() }
    val states = rows.map { ch -> androidx.compose.runtime.key(ch.key) { rememberLazyListState() } }
    // Where the selection is now (row, card).
    val at = remember(pkg) { intArrayOf(-1, -1) }

    /** The card of row [r] under the selected card (its centre nearest), fully on screen if one is. */
    fun below(r: Int, fromCentre: Float?): Int {
        val info = states[r].layoutInfo
        val vis = info.visibleItemsInfo
        if (vis.isEmpty()) return 0
        val full = vis.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }.ifEmpty { vis }
        val c = fromCentre ?: return (lastSel[rows[r].key]?.takeIf { i -> full.any { it.index == i } } ?: full.first().index)
        return full.minBy { kotlin.math.abs(it.offset + it.size / 2f - c) }.index
    }
    fun focusCard(r: Int, i: Int) {
        val n = rows[r].programs.size
        if (n == 0) return
        val idx = i.coerceIn(0, n - 1)
        if (runCatching { req(r, idx).requestFocus() }.isFailure) scope.launch {
            // Not built yet (scrolled away): bring it in, then select it.
            runCatching { states[r].scrollToItem(idx) }
            androidx.compose.runtime.withFrameNanos { }
            runCatching { req(r, idx).requestFocus() }
        }
    }
    fun centreOf(r: Int, i: Int): Float? = states[r].layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }?.let { it.offset + it.size / 2f }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // The Library's grey page (painted once, dithered) covers the carousel's picture.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val bw = with(density) { maxWidth.roundToPx() }; val bh = with(density) { maxHeight.roundToPx() }
        val bg = remember(bw, bh) { greyPage(bw, bh) }
        androidx.compose.foundation.Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = bottom.dp)
            .onFocusChanged { ChannelsUi.focused = it.hasFocus }
            // Up from the dock: the lowest row, at the card selected there last (or the first one on screen).
            .focusRequester(ChannelsUi.entry)
            .focusProperties { enter = { if (rows.isEmpty()) FocusRequester.Default else req(rows.lastIndex, below(rows.lastIndex, null)) } }
            .onPreviewKeyEvent { ev ->
                val r = at[0]; val i = at[1]
                if (r !in rows.indices) return@onPreviewKeyEvent false
                val n = rows[r].programs.size
                val down = ev.type == KeyEventType.KeyDown
                when (ev.key) {
                    Key.DirectionUp -> { if (down) { if (r == 0) onUp() else focusCard(r - 1, below(r - 1, centreOf(r, i))) }; true }
                    Key.DirectionDown -> { if (down) { if (r == rows.lastIndex) onDown() else focusCard(r + 1, below(r + 1, centreOf(r, i))) }; true }
                    // The ends of a row: the selection stays.
                    Key.DirectionRight -> i >= n - 1
                    Key.DirectionLeft -> i <= 0
                    else -> false
                }
            }
            .focusGroup(),
            verticalArrangement = Arrangement.spacedBy((10 * s).dp)) {
            rows.forEachIndexed { r, ch ->
                androidx.compose.runtime.key(ch.key) {
                    var sel by remember { mutableIntStateOf(-1) }
                    Column {
                        Row(Modifier.padding(start = (48 * s).dp, end = (48 * s).dp, bottom = (5 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (r == 0) "$label  ·  ${ch.name}" else ch.name, color = Color.White.copy(alpha = 0.85f), fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            // The selected card's title, next to the row's name.
                            val t = ch.programs.getOrNull(sel)
                            if (t != null) Text("   " + listOf(t.title, t.sub).filter { it.isNotBlank() }.joinToString("  ·  "), color = Color.White.copy(alpha = 0.6f),
                                fontSize = (10 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        LazyRow(state = states[r], horizontalArrangement = Arrangement.spacedBy((10 * s).dp),
                            contentPadding = PaddingValues(horizontal = (48 * s).dp, vertical = (5 * s).dp)) {
                            items(ch.programs.size, key = { ch.programs[it].id }) { i ->
                                val p = ch.programs[i]
                                ChannelCard(p, s, cardH,
                                    Modifier.focusRequester(req(r, i))
                                        .onFocusChanged { f ->
                                            if (f.isFocused) { sel = i; at[0] = r; at[1] = i; lastSel[ch.key] = i }
                                            else if (sel == i) sel = -1
                                        },
                                    onOpen = { Channels.open(ctx, pkg, p) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(p: Channels.Program, s: Float, h: Float, modifier: Modifier, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var failed by remember(p.poster) { mutableStateOf(p.poster.isBlank()) }
    val k by animateFloatAsState(if (focused) 1.07f else 1f, tween(140), label = "chan")
    val shape = RoundedCornerShape((8 * s).dp)
    val ratio = if (p.wide) 16f / 9f else 2f / 3f
    val req = remember(p.poster) { ImageRequest.Builder(ctx).data(p.poster).size((h * ratio * 2).toInt().coerceAtLeast(64), (h * 2).toInt().coerceAtLeast(64)).build() }
    Box(modifier.height(h.dp).aspectRatio(ratio)
        .onFocusChanged { focused = it.isFocused }
        .tvPress(onClick = onOpen)
        .graphicsLayer { scaleX = k; scaleY = k }
        .clip(shape).background(Color(0xFF34343A))
        .border(if (focused) 1.5.dp else 0.dp, Color.White.copy(alpha = if (focused) 0.9f else 0f), shape)) {
        if (!failed) AsyncImage(req, p.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { failed = true })
        // No picture (or one Glassio isn't allowed to read): the title instead.
        else Text(p.title, color = Color.White.copy(alpha = 0.85f), fontSize = (8.5f * s).sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding((6 * s).dp))
        if (p.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding((5 * s).dp).height((2.5f * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(p.progress).height((2.5f * s).dp).background(Color.White))
        }
    }
}
