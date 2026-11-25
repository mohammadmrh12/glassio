package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp

/** One card: a title in progress (Glassio's Continue Watching, or an app's own "Continue watching" row). */
internal class CwCard(val key: String, val title: String, val sub: String, val picture: String, val progress: Float, val app: String?, val open: () -> Unit)

/** The cards to show now: the selected app's own Continue watching ([ChannelsUi.cardsPkg]), else Glassio's. */
@Composable
internal fun continueCards(app: GlassApp): List<CwCard> {
    val ctx = LocalContext.current
    val st = app.settings
    val all by app.cw.items.collectAsState()
    val pkg = ChannelsUi.cardsPkg.value
    if (pkg != null) {
        val ch = com.glasslauncher.tv.data.Channels.continueOf(pkg)
        if (ch != null) return ch.programs.map { p ->
            CwCard("$pkg#${p.id}", p.title, p.sub, p.poster, p.progress, pkg) { com.glasslauncher.tv.data.Channels.open(ctx, pkg, p) }
        }
    }
    return all.map { h -> CwCard(h.key, h.name, h.sub.ifBlank { h.year }, h.poster.ifBlank { h.background }, h.progress, null) { com.glasslauncher.tv.data.NuvioLaunch.open(ctx, st, app.http, h) } }
}

/**
 * Continue Watching as small glass cards right above the dock, over your own background (*Continue Watching cards*,
 * off by default; SER's idea: the picture stays whole and the page isn't empty). Every title in progress, in a row
 * shown as many as fit whole between the dock's edges (never cut by the screen's): the row moves a card at a
 * time when the selection reaches its end. From the left, like the dock.
 * Up from the dock comes here ([entry] is the hero's focus), ▲ from a card goes on to the menu ([onUp]), ▼ back to the
 * dock, OK opens the title.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun ContinueCards(app: GlassApp, s: Float, bottom: Float, side: Float, cards: List<CwCard>, entry: FocusRequester, firstTile: FocusRequester, onUp: () -> Unit) {
    if (cards.isEmpty()) return
    val ctx = LocalContext.current
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = reqs.getOrPut(k) { FocusRequester() }
    var last by remember { mutableIntStateOf(0) }
    var start by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<String?>(null) }
    val gap = 9 * s
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().padding(bottom = (bottom + 4 * s).dp), contentAlignment = Alignment.BottomStart) {
        // (Only whole cards, as many as fit between the dock's own edges, widened to fill that width exactly.)
        val avail = maxWidth.value - 2 * side
        val fit = (((avail + gap) / (165 * s + gap)).toInt()).coerceAtLeast(1)
        val cardW = (avail - gap * (fit - 1)) / fit
        start = start.coerceIn(0, (cards.size - fit).coerceAtLeast(0))
        val shown = cards.subList(start, minOf(cards.size, start + fit))
        // (The window moved: the card the selection went to, once it is laid out.)
        LaunchedEffect(pending) { val k = pending ?: return@LaunchedEffect; androidx.compose.runtime.withFrameNanos { }; runCatching { req(k).requestFocus() }; pending = null }
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = side.dp, vertical = (6 * s).dp)
                .focusRequester(entry)
                .focusProperties { enter = { (shown.getOrNull(last - start) ?: shown.lastOrNull())?.let { req(it.key) } ?: FocusRequester.Default } }
                .focusGroup(),
        ) {
            shown.forEachIndexed { j, c ->
                val i = start + j
                androidx.compose.runtime.key(c.key) {
                var focused by remember { mutableStateOf(false) }
                val k by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "cwCard")
                val shape = RoundedCornerShape((12 * s).dp)
                GlassPanel(
                    Modifier.width(cardW.dp).height((48 * s).dp).graphicsLayer { scaleX = k; scaleY = k }
                        .then(if (focused) Modifier.border((1.4f * s).dp, Color.White.copy(alpha = 0.9f), shape) else Modifier)
                        .focusRequester(req(c.key))
                        .onFocusChanged { f -> focused = f.isFocused; if (f.isFocused) last = i }
                        .onPreviewKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (ev.key) {
                                Key.DirectionUp -> { onUp(); true }
                                // (requestFocus answers false for a tile that's gone: then the first one.)
                                Key.DirectionDown -> { if (!runCatching { ChannelsUi.dockTile(firstTile).requestFocus() }.getOrDefault(false)) runCatching { firstTile.requestFocus() }; true }
                                Key.DirectionLeft -> when {
                                    i == 0 -> true
                                    j == 0 -> { start -= 1; pending = cards[i - 1].key; true }
                                    else -> false
                                }
                                Key.DirectionRight -> when {
                                    i == cards.lastIndex -> true
                                    j == shown.lastIndex -> { start += 1; pending = cards[i + 1].key; true }
                                    else -> false
                                }
                                else -> false
                            }
                        }
                        .tvPress { c.open() },
                    radius = (12 * s).dp, style = app.settings.dockStyle,
                ) {
                    Row(Modifier.fillMaxSize().padding((6 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                        coil3.compose.AsyncImage(
                            model = imgReq(ctx, c.picture, 160, 240), contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size((36 * s).dp).clip(RoundedCornerShape((7 * s).dp)).background(Color.White.copy(alpha = 0.08f)),
                        )
                        Spacer(Modifier.width((8 * s).dp))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            Plain(c.title, color = Color.White, fontSize = (9.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.sub.isNotBlank()) Plain(c.sub, color = Color.White.copy(alpha = 0.65f), fontSize = (8 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.progress > 0f) {
                                Spacer(Modifier.height((4 * s).dp))
                                Box(Modifier.fillMaxWidth().height((2 * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))) {
                                    Box(Modifier.fillMaxWidth(c.progress.coerceIn(0f, 1f)).fillMaxHeight().background(Color.White.copy(alpha = 0.9f)))
                                }
                            }
                        }
                    }
                }
                }
            }
        }
    }
}
