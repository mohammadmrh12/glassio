// Pages that take the carousel's place from the dock: the music apps and the inputs. (Split out of HomeScreen.kt.)
package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.async
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.request.allowHardware
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The dock's Music icon selected: the music apps in the carousel's place, one row, drawn like the dock's tiles (the
 * theme, the icons you picked). OK plays where the app left off (its player if it is the one playing), holding OK
 * opens the app. ▼ / Back: the Music icon; ▲: the menu.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MusicAppsStage(app: GlassApp, s: Float, metrics: DockMetrics, radius: Int, bottom: Float, onDown: () -> Unit, onUp: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st = app.settings
    // (The same list as Settings → Music / Channels.)
    val apps = remember { installedMusicApps(ctx).filter { MusicUi.isMusicApp(ctx, it.first) || it.first in com.glasslauncher.tv.data.NowPlaying.YOUTUBE_CLIENTS || it.first == st.musicLastPkg } }
    val entries = remember(apps) { apps.map { (pkg, name) -> app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == pkg }
        ?: AppEntry(pkg, name, android.content.ComponentName(pkg, ""), true) } }
    val song by com.glasslauncher.tv.data.NowPlaying.track.collectAsState()
    val reqs = remember(entries) { entries.map { FocusRequester() } }
    val start = entries.indexOfFirst { it.pkg == song?.pkg }.let { if (it >= 0) it else entries.indexOfFirst { e -> e.pkg == st.musicLastPkg }.coerceAtLeast(0) }
    var sel by remember { mutableIntStateOf(-1) }
    fun play(pkg: String) {
        val t = com.glasslauncher.tv.data.NowPlaying.track.value
        if (t?.pkg == pkg) {
            // Its song is the one here: its player (playing on).
            if (!t.playing) com.glasslauncher.tv.data.NowPlaying.play()
            MusicUi.forced.value = true; MusicUi.focusRev.intValue++
        } else {
            // (The selection goes to the player once its song is on: it fell out of these tiles when they went, and
            // landed on the home screen.)
            MusicUi.startIn(ctx, pkg); MusicUi.focusRev.intValue++
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = (if (!st.topAutoHide && st.showClock) 64 else 30) * s
        val room = maxHeight.value - bottom - top
        Box(Modifier.padding(top = top.dp).fillMaxWidth().height(room.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.onFocusChanged { MusicUi.appsFocused = it.hasFocus }) {
                Text("Music", color = Color.White.copy(alpha = 0.85f), fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height((14 * s).dp))
                if (entries.isEmpty()) {
                    Text("No music app on this TV (Spotify, SoundCloud, Apple Music…)", color = Color.White.copy(alpha = 0.6f), fontSize = (10 * s).sp)
                    Box(Modifier.size(1.dp).focusRequester(MusicUi.appsEntry).focusable())
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp),
                    modifier = Modifier.focusRequester(MusicUi.appsEntry)
                        .focusProperties { enter = { reqs[start.coerceIn(0, reqs.lastIndex)] } }
                        .onPreviewKeyEvent { ev ->
                            val down = ev.type == KeyEventType.KeyDown
                            when (ev.key) {
                                Key.DirectionDown -> { if (down) onDown(); true }
                                Key.DirectionUp -> { if (down) onUp(); true }
                                Key.Back -> { if (ev.type == KeyEventType.KeyUp) onDown(); true }
                                Key.DirectionLeft -> sel <= 0
                                Key.DirectionRight -> sel >= entries.lastIndex
                                else -> false
                            }
                        }
                        .focusGroup()) {
                    entries.forEachIndexed { i, e ->
                        DockTile(e, app, s, radius, moving = false,
                            modifier = Modifier.width(metrics.cellW.dp).height(metrics.tileH.dp).focusRequester(reqs[i]),
                            onFocus = { f -> if (f) sel = i else if (sel == i) sel = -1 },
                            onMoveKey = { false },
                            onClick = { play(e.pkg) },
                            onLong = { openMusicApp(ctx, e.pkg) })
                    }
                }
                Spacer(Modifier.height((14 * s).dp))
                // The selected app: its song if it is the one here, and what OK does.
                val e = entries.getOrNull(sel)
                val t = song
                val line = when {
                    e == null -> ""
                    t != null && t.pkg == e.pkg -> (if (t.playing) "Playing  ·  " else "Paused  ·  ") + listOf(t.title, t.artist).filter { it.isNotBlank() }.joinToString("  ·  ")
                    else -> "OK plays where ${e.label} left off  ·  hold OK opens it"
                }
                Text(line, color = Color.White.copy(alpha = 0.6f), fontSize = (10 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = (700 * s).dp))
            }
        }
    }
}

/** The dock's Inputs icon: its page of the TV's inputs above the dock (like the Music icon's music apps). */
object InputsUi {
    val on = androidx.compose.runtime.mutableStateOf(false)
    /** Its page is up (not behind the open dock, the folder or another screen). */
    val page = androidx.compose.runtime.mutableStateOf(false)
    @Volatile var focused = false
    val entry = FocusRequester()
    /** Each input's tile (the icon picker gives the selection back to it). */
    val tiles = java.util.concurrent.ConcurrentHashMap<String, FocusRequester>()
    /** Until then the page stays though nothing in it is selected (the icon picker opening and closing over it). */
    @Volatile var holdUntil = 0L
    fun held() = android.os.SystemClock.uptimeMillis() < holdUntil
}

/**
 * The Inputs icon's page: the TV's inputs drawn like the dock's tiles, in a row above it. OK switches to one; hold OK
 * changes its icon (which ones show, and their order: Settings → Dock & icons → Inputs). ▼ / Back: the Inputs icon; ▲: the menu.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun InputsStage(app: GlassApp, s: Float, metrics: DockMetrics, radius: Int, bottom: Float, onDown: () -> Unit, onUp: () -> Unit,
    onPickIcon: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val st = app.settings
    val all by com.glasslauncher.tv.data.Inputs.list.collectAsState()
    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(Dispatchers.IO) { com.glasslauncher.tv.data.Inputs.refresh(ctx) } }
    val shown = remember(all, st.inputsHidden, st.inputsOrder) { com.glasslauncher.tv.data.Inputs.forPage(st) }
    val entries = remember(shown) { shown.map { AppEntry(com.glasslauncher.tv.data.Inputs.PREFIX + it.id, it.label, android.content.ComponentName(ctx.packageName, "input"), true) } }
    val reqs = remember(entries.size) { List(entries.size) { FocusRequester() } }
    entries.forEachIndexed { i, e -> InputsUi.tiles[e.pkg] = reqs[i] }
    var sel by remember { mutableIntStateOf(-1) }
    val start = remember { intArrayOf(0) }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val top = (if (!st.topAutoHide && st.showClock) 64 else 30) * s
        val room = maxHeight.value - bottom - top
        Box(Modifier.padding(top = top.dp).fillMaxWidth().height(room.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.onFocusChanged { InputsUi.focused = it.hasFocus }) {
                Text("Inputs", color = Color.White.copy(alpha = 0.85f), fontSize = (12 * s).sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height((14 * s).dp))
                if (entries.isEmpty()) {
                    Text(if (all.isEmpty()) "No inputs found. On a TV they come from its own list; on a box like the Google TV Streamer from HDMI-CEC (TV access: Glassio settings → System)."
                        else "Every input is hidden: show them in Settings → Dock & icons → Inputs → TV inputs",
                        color = Color.White.copy(alpha = 0.6f), fontSize = (10 * s).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.widthIn(max = (520 * s).dp).focusRequester(InputsUi.entry)
                            .onPreviewKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionDown || ev.key == Key.Back)) { onDown(); true } else false }
                            .focusable())
                    return@Column
                }
                Row(horizontalArrangement = Arrangement.spacedBy(metrics.gap.dp),
                    modifier = Modifier.focusRequester(InputsUi.entry)
                        .focusProperties { enter = { reqs[start[0].coerceIn(0, reqs.lastIndex)] } }
                        .onPreviewKeyEvent { ev ->
                            val down = ev.type == KeyEventType.KeyDown
                            when (ev.key) {
                                Key.DirectionDown -> { if (down) onDown(); true }
                                Key.DirectionUp -> { if (down) onUp(); true }
                                Key.Back -> { if (ev.type == KeyEventType.KeyUp) onDown(); true }
                                Key.DirectionLeft -> sel <= 0
                                Key.DirectionRight -> sel >= entries.lastIndex
                                else -> false
                            }
                        }
                        .focusGroup()) {
                    entries.forEachIndexed { i, e ->
                        DockTile(e, app, s, radius, moving = false,
                            modifier = Modifier.width(metrics.cellW.dp).height(metrics.tileH.dp).focusRequester(reqs[i]),
                            onFocus = { f -> if (f) { sel = i; start[0] = i } else if (sel == i) sel = -1 },
                            onMoveKey = { false },
                            onClick = { com.glasslauncher.tv.data.Inputs.open(ctx, e.pkg) },
                            onLong = { InputsUi.holdUntil = android.os.SystemClock.uptimeMillis() + 3000; onPickIcon(e) })
                    }
                }
                // The room of the music apps' line under their tiles (left empty: no hint here), so both pages sit at
                // the same height (without it the inputs sat lower than the music apps).
                Spacer(Modifier.height((14 * s).dp))
                Text(" ", fontSize = (10 * s).sp, maxLines = 1)
            }
        }
    }
}
