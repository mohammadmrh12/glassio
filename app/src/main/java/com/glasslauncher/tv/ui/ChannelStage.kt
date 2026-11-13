package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.onPreviewKeyEvent
import kotlinx.coroutines.launch
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
    /** The page and the glass have switched to the rows' background (the music page waits for this to give way). */
    val pageOn = mutableStateOf(false)
    /** The selected card's picture (its link and the picture): the blurred background (Settings → Channels → Background). */
    val art = mutableStateOf<Pair<String, android.graphics.Bitmap?>?>(null)
    /** Makes a card's blurred background ahead, in the background (set by the home screen). */
    @Volatile var warm: ((String, android.graphics.Bitmap) -> Unit)? = null
}

/**
 * An app's channels in the carousel's place, each row with its name and its cards: one or two rows at a time (one
 * in the middle, or two one above the other), and every row of the app reached with Up / Down: past the lowest
 * row shown the next one comes in (the 3rd, the 4th…), only after the last row Down goes back to the dock.
 * ▲ from the dock goes into the lowest row shown, ▲ from the first row to the menu. Every move is decided here:
 * left to the system, Up from a row could land on the carousel's hidden buttons behind, and Right past the last
 * card went on to the menu.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ChannelStage(app: com.glasslauncher.tv.GlassApp, s: Float, pkg: String, rows: List<Channels.Channel>, bottom: Float, onDown: () -> Unit, onUp: () -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val label = remember(pkg) { app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == pkg }?.label ?: pkg.substringAfterLast('.') }
    val st = app.settings
    val glow = st.channelsGlow
    val glowAmt = st.channelsGlowSize
    val hueApp = if (st.channelsHue && com.glasslauncher.tv.data.HueSync.ready(st)) app else null
    val blurBg = st.channelsBg == "blur"
    // Rows shown at once, and the first of them (the window moves with the selection).
    val vis = (if (st.channelsLayout == "single") 1 else st.channelsRows.coerceIn(1, 2)).coerceAtMost(rows.size.coerceAtLeast(1))
    var win by remember(pkg) { mutableIntStateOf(0) }
    if (win > (rows.size - vis).coerceAtLeast(0)) win = (rows.size - vis).coerceAtLeast(0)
    val shown = (win until (win + vis).coerceAtMost(rows.size)).toList()
    // One focus handle per card ("row key#index"), each row's scroll and the card last selected in it.
    val reqs = remember(pkg) { HashMap<String, FocusRequester>() }
    fun req(r: Int, i: Int) = reqs.getOrPut("${rows[r].key}#$i") { FocusRequester() }
    val lastSel = remember(pkg) { HashMap<String, Int>() }
    val states = rows.map { ch -> androidx.compose.runtime.key(ch.key) { rememberLazyListState() } }
    // Where the selection is now (row, card).
    val at = remember(pkg) { intArrayOf(-1, -1) }
    // The app's name in the icon's own colour (Settings → Channels → App name).
    var appColor by remember(pkg) { mutableStateOf(Glow.cached("lbl:$pkg")) }
    LaunchedEffect(pkg, st.channelsLabel) {
        if (st.channelsLabel == "icon" && appColor == null) appColor = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val pm = ctx.packageManager
                val d = pm.getApplicationBanner(pkg) ?: pm.getApplicationIcon(pkg)
                val b = android.graphics.Bitmap.createBitmap(96, 54, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 96, 54); d.draw(android.graphics.Canvas(b))
                Glow.colorOf("lbl:$pkg", b)
            }.getOrNull()
        }
    }
    val nameColor = (if (st.channelsLabel == "icon") appColor else null) ?: Color.White
    // The blurred background's first picture: the first row's first card, known before its picture is loaded (a
    // blur made ahead for it shows at once).
    LaunchedEffect(pkg) { ChannelsUi.art.value = rows.firstOrNull()?.programs?.firstOrNull()?.poster?.takeIf { it.isNotBlank() }?.let { it to null } }

    /** The card of row [r] under the selected card (its centre nearest), fully on screen if one is. */
    fun below(r: Int, fromCentre: Float?): Int {
        val info = states[r].layoutInfo
        val vs = info.visibleItemsInfo
        if (vs.isEmpty()) return lastSel[rows[r].key] ?: 0
        val full = vs.filter { it.offset >= info.viewportStartOffset && it.offset + it.size <= info.viewportEndOffset }.ifEmpty { vs }
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
    /** To row [t]: the window moves to show it (when it is outside), then the card under the selected one. */
    fun goRow(t: Int) {
        val c = centreOf(at[0], at[1])
        val nw = when { t < win -> t; t >= win + vis -> t - vis + 1; else -> win }
        if (nw == win) { focusCard(t, below(t, c)); return }
        win = nw; at[0] = t
        scope.launch {
            repeat(2) { androidx.compose.runtime.withFrameNanos { } }
            focusCard(t, below(t, c))
        }
    }

    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // (No background here: the page behind and the glass show it, crossfaded together.)
        // Sizes. The rows fill the room between the top edge and the dock (none cut off); the cards are as big as the
        // chosen number across a row allows (Settings → Channels → Cards in a row), smaller when the rows wouldn't fit.
        // Spacing leaves room for the selected card's zoom (neighbours aren't covered) and its glow (not cut off).
        val zoomW = 1.13f; val zoomP = 1.16f
        val side = 48 * s; val labelH = 18 * s; val rowGap = 8 * s
        val n = st.channelsCards
        val wideFit = (maxWidth.value - 2 * side - (n - 1) * 4 * s) / (n + (n - 1) * (zoomW - 1f) / 2f)
        // Under the clock when it always shows, and under the music island while a song plays.
        val song by com.glasslauncher.tv.data.NowPlaying.track.collectAsState()
        val top = (if ((!st.topAutoHide && st.showClock) || (st.musicOn && song != null)) 64 else 30) * s
        val room = maxHeight.value - bottom - top
        val hFit = (room - (vis - 1) * rowGap - vis * (labelH + 6 * s)) / (vis * zoomP)
        val cardH = minOf(wideFit * 9f / 16f, hFit).coerceAtLeast(40 * s)
        val grow = cardH * (zoomP - 1f) / 2f + 3 * s
        val glowPad = cardH * zoomP * 0.2f * glowAmt + 4 * s
        val padV = grow + glowPad
        val content = vis * (labelH + cardH + 2 * grow) + (vis - 1) * rowGap
        // One row: in the middle of the room; two: from the dock up.
        val lift = if (vis == 1) bottom + (room - content) / 2f else bottom
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = lift.coerceAtLeast(bottom).dp)
            .onFocusChanged { ChannelsUi.focused = it.hasFocus }
            .focusRequester(ChannelsUi.entry)
            // From the dock (Up): the lowest row shown; from the menu / the top bar: the first row shown.
            .focusProperties { enter = { dir -> when {
                rows.isEmpty() -> FocusRequester.Default
                dir == androidx.compose.ui.focus.FocusDirection.Up -> shown.last().let { req(it, below(it, null)) }
                else -> shown.first().let { req(it, below(it, null)) }
            } } }
            .onPreviewKeyEvent { ev ->
                val r = at[0]; val i = at[1]
                if (r !in rows.indices) return@onPreviewKeyEvent false
                val cards = rows[r].programs.size
                val down = ev.type == KeyEventType.KeyDown
                when (ev.key) {
                    Key.DirectionUp -> { if (down) { if (r == 0) onUp() else goRow(r - 1) }; true }
                    Key.DirectionDown -> { if (down) { if (r == rows.lastIndex) onDown() else goRow(r + 1) }; true }
                    // Back: to the app in the dock (the selection was lost).
                    Key.Back -> { if (ev.type == KeyEventType.KeyUp) onDown(); true }
                    // The ends of a row: the selection stays.
                    Key.DirectionRight -> i >= cards - 1
                    Key.DirectionLeft -> i <= 0
                    else -> false
                }
            }
            .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(rowGap.dp)) {
            shown.forEach { r ->
                val ch = rows[r]
                androidx.compose.runtime.key(ch.key) {
                    var sel by remember { mutableIntStateOf(-1) }
                    Column {
                        Row(Modifier.height(labelH.dp).padding(start = side.dp, end = side.dp), verticalAlignment = Alignment.CenterVertically) {
                            val name = androidx.compose.ui.text.buildAnnotatedString {
                                if (r == shown.first()) {
                                    withStyle(androidx.compose.ui.text.SpanStyle(color = nameColor)) { append(label) }
                                    append("  ·  ")
                                }
                                append(ch.name)
                                if (rows.size > vis) withStyle(androidx.compose.ui.text.SpanStyle(color = Color.White.copy(alpha = 0.45f))) { append("   ${r + 1} / ${rows.size}") }
                            }
                            Text(name, color = Color.White.copy(alpha = 0.85f), fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            // The selected card's title, next to the row's name.
                            val t = ch.programs.getOrNull(sel)
                            if (t != null) Text("   " + listOf(t.title, t.sub).filter { it.isNotBlank() }.joinToString("  ·  "), color = Color.White.copy(alpha = 0.6f),
                                fontSize = (10 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        val poster = ch.programs.firstOrNull()?.wide == false
                        val cardW = if (poster) cardH * 2f / 3f else cardH * 16f / 9f
                        val gap = maxOf(10 * s, cardW * ((if (poster) zoomP else zoomW) - 1f) / 2f + 4 * s)
                        // Laid out with the glow's room above and below, but taking only the zoom's room: the glow
                        // spreads over the space around the row instead of being cut off at its edge.
                        val extra = with(density) { (padV - grow).dp.roundToPx() }
                        // TV scrolling: the selected card always stands in the first place, the row moves under it.
                        val sidePx = with(density) { side.dp.toPx() }
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(sidePx) { FirstPlace(sidePx) }) {
                        LazyRow(state = states[r], horizontalArrangement = Arrangement.spacedBy(gap.dp),
                            contentPadding = PaddingValues(horizontal = side.dp, vertical = padV.dp),
                            modifier = Modifier.layout { m, c ->
                                val p = m.measure(c.copy(maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
                                layout(p.width, (p.height - 2 * extra).coerceAtLeast(0)) { p.place(0, -extra) }
                            }) {
                            items(ch.programs.size, key = { ch.programs[it].id }) { i ->
                                val p = ch.programs[i]
                                ChannelCard(p, s, cardH, glow, glowAmt, blurBg, hueApp,
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
}

/** A row's scrolling: the selected card goes to the row's first place (its left margin), with a soft glide. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private class FirstPlace(private val start: Float) : androidx.compose.foundation.gestures.BringIntoViewSpec {
    private val soft = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 420f, visibilityThreshold = 0.5f)
    @Deprecated("still read by Compose 1.9 for bring-into-view scrolling")
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> get() = soft
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val d = offset - start
        return if (kotlin.math.abs(d) < 1f) 0f else d
    }
}

@Composable
private fun ChannelCard(p: Channels.Program, s: Float, h: Float, glow: String, glowAmt: Float, blurBg: Boolean, hueApp: com.glasslauncher.tv.GlassApp?, modifier: Modifier, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    var failed by remember(p.poster) { mutableStateOf(p.poster.isBlank()) }
    var gc by remember(p.poster) { mutableStateOf(Glow.cached("ch:" + p.poster)) }
    var bmp by remember(p.poster) { mutableStateOf<android.graphics.Bitmap?>(null) }
    // The blurred background follows the selected card; its picture, once loaded, also goes to the first card's
    // entry (set before the picture was there).
    LaunchedEffect(focused, bmp) {
        val b = bmp
        val cur = ChannelsUi.art.value
        if (focused || (cur?.first == p.poster && cur.second == null && b != null)) ChannelsUi.art.value = p.poster to b
    }
    // Hue (Settings → Channels / Hue lights): the lights take the selected card's colour, like the Library's posters.
    if (hueApp != null) com.glasslauncher.tv.ui.HueFocus(hueApp, true, focused, gc, poster = true)
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
        .drawBehind { if (glowA > 0f) with(Glow) { glow(if (glow == "white") Color.White else gc ?: Color.White, glowA, tallShape = !p.wide, corner = (8 * s).dp.toPx(), amt = glowAmt) } }
        .clip(shape).background(Color(0xFF34343A))) {
        if (!failed) AsyncImage(req, p.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onError = { failed = true },
            onSuccess = { r ->
                val b = (r.result.image as? coil3.BitmapImage)?.bitmap
                bmp = b
                // The blurred background made ahead for every card on screen: moving to one shows it at once.
                if (blurBg && b != null) ChannelsUi.warm?.invoke(p.poster, b)
                if ((glow == "color" || hueApp != null) && gc == null) Thread { gc = Glow.colorOf("ch:" + p.poster, b) }.start()
            })
        // No picture (or one Glassio isn't allowed to read): the title instead.
        else Text(p.title, color = Color.White.copy(alpha = 0.85f), fontSize = (8.5f * s).sp, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding((6 * s).dp))
        if (p.progress > 0f) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding((5 * s).dp).height((2.5f * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f))) {
            Box(Modifier.fillMaxWidth(p.progress).height((2.5f * s).dp).background(Color.White))
        }
    }
}
