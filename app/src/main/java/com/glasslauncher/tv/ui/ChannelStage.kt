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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.draw.drawBehind
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
    /** The rows are on screen (the glass then refracts the grey page, not the carousel's picture). */
    val shown = mutableStateOf(false)
    /** The selected card's picture (its link and the picture): the blurred background (Settings → Channels → Background). */
    val art = mutableStateOf<Pair<String, android.graphics.Bitmap>?>(null)
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
    val st = app.settings
    val glow = st.channelsGlow
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
        // Blurred card: nothing drawn here, the page behind (and the glass) show the card's picture blurred.
        if (st.channelsBg != "blur") {
            val bg = remember(bw, bh) { greyPage(bw, bh) }
            androidx.compose.foundation.Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        LaunchedEffect(pkg) { ChannelsUi.art.value = null }
        // Rows that don't all fit: the lowest one shows whole above the dock (the top one goes under the top edge),
        // until the selection moves into them (each selected row is then scrolled into sight).
        val vs = androidx.compose.foundation.rememberScrollState()
        LaunchedEffect(vs.maxValue) { if (!ChannelsUi.focused) vs.scrollTo(vs.maxValue) }
        // Card size: the chosen number of wide cards fits across a row (Settings → Channels → Cards in a row); posters
        // keep the same height. Smaller when the rows wouldn't fit between the top bar and the dock.
        // Rows that don't all fit scroll up and down with the selection (bigger cards keep their size).
        val side = 48 * s; val gap = 10 * s; val padV = 14 * s; val rowGap = 10 * s
        val n = st.channelsCards
        val wideW = (maxWidth.value - 2 * side - gap * (n - 1)) / n
        val room = maxHeight.value - bottom - 56 * s
        val cardH = minOf(wideW * 9f / 16f, room - (20 * s + 2 * padV)).coerceAtLeast(40 * s)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = bottom.dp).heightIn(max = room.dp)
            .onFocusChanged { ChannelsUi.focused = it.hasFocus }
            // Up from the dock: the lowest row, at the card selected there last (or the first one on screen).
            .focusRequester(ChannelsUi.entry)
            // From the dock (Up): the lowest row; from the menu / the top bar: the top row.
            .focusProperties { enter = { dir -> if (rows.isEmpty()) FocusRequester.Default
                else if (dir == androidx.compose.ui.focus.FocusDirection.Up) req(rows.lastIndex, below(rows.lastIndex, null)) else req(0, below(0, null)) } }
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
            .focusGroup()
            .verticalScroll(vs),
            verticalArrangement = Arrangement.spacedBy(rowGap.dp)) {
            rows.forEachIndexed { r, ch ->
                androidx.compose.runtime.key(ch.key) {
                    var sel by remember { mutableIntStateOf(-1) }
                    Column {
                        Row(Modifier.padding(start = (48 * s).dp, end = (48 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (r == 0) "$label  ·  ${ch.name}" else ch.name, color = Color.White.copy(alpha = 0.85f), fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            // The selected card's title, next to the row's name.
                            val t = ch.programs.getOrNull(sel)
                            if (t != null) Text("   " + listOf(t.title, t.sub).filter { it.isNotBlank() }.joinToString("  ·  "), color = Color.White.copy(alpha = 0.6f),
                                fontSize = (10 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        LazyRow(state = states[r], horizontalArrangement = Arrangement.spacedBy(gap.dp),
                            contentPadding = PaddingValues(horizontal = side.dp, vertical = padV.dp)) {
                            items(ch.programs.size, key = { ch.programs[it].id }) { i ->
                                val p = ch.programs[i]
                                ChannelCard(p, s, cardH, glow, lead = r == 0 && i == 0,
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
private fun ChannelCard(p: Channels.Program, s: Float, h: Float, glow: String, lead: Boolean, modifier: Modifier, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var failed by remember(p.poster) { mutableStateOf(p.poster.isBlank()) }
    var gc by remember(p.poster) { mutableStateOf(Glow.cached("ch:" + p.poster)) }
    var bmp by remember(p.poster) { mutableStateOf<android.graphics.Bitmap?>(null) }
    // The blurred background follows the selected card (the first card's picture until one is selected).
    androidx.compose.runtime.LaunchedEffect(focused, bmp) {
        val b = bmp ?: return@LaunchedEffect
        if (focused || (lead && ChannelsUi.art.value == null)) ChannelsUi.art.value = p.poster to b
    }
    // Like the Library: the selected card grows (no frame), with the same soft light around it when it is on.
    val k by animateFloatAsState(if (focused) (if (p.wide) 1.13f else 1.16f) else 1f, tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "chan")
    val glowA by animateFloatAsState(if (focused && glow != "off") 1f else 0f, tween(220), label = "chanGlow")
    val shape = RoundedCornerShape((8 * s).dp)
    val ratio = if (p.wide) 16f / 9f else 2f / 3f
    val req = remember(p.poster) { ImageRequest.Builder(ctx).data(p.poster).size((h * ratio * 2).toInt().coerceAtLeast(64), (h * 2).toInt().coerceAtLeast(64)).build() }
    Box(modifier.height(h.dp).aspectRatio(ratio)
        // The remote's focus sits outside the zoom (see the Library's posters).
        .onFocusChanged { focused = it.isFocused }
        .tvPress(onClick = onOpen)
        .graphicsLayer { scaleX = k; scaleY = k }
        .drawBehind { if (glowA > 0f) with(Glow) { glow(if (glow == "white") Color.White else gc ?: Color.White, glowA, tallShape = !p.wide, corner = (8 * s).dp.toPx()) } }
        .clip(shape).background(Color(0xFF34343A))) {
        if (!failed) AsyncImage(req, p.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { failed = true },
            onSuccess = { r ->
                val b = (r.result.image as? coil3.BitmapImage)?.bitmap
                bmp = b
                if (glow == "color" && gc == null) Thread { gc = Glow.colorOf("ch:" + p.poster, b) }.start()
            })
        // No picture (or one Glassio isn't allowed to read): the title instead.
        else Text(p.title, color = Color.White.copy(alpha = 0.85f), fontSize = (8.5f * s).sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding((6 * s).dp))
        if (p.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding((5 * s).dp).height((2.5f * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(p.progress).height((2.5f * s).dp).background(Color.White))
        }
    }
}
