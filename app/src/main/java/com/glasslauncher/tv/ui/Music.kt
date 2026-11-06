package com.glasslauncher.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.NowPlaying
import kotlinx.coroutines.delay

/** The music player's shared bits: what opens the menu on its music page (the dock's Music icon). */
object MusicUi {
    val openInMenu = mutableStateOf(false)
    const val DOCK_PKG = "music:player"

    /** The artwork's main colour (for the island's bars), or white. */
    fun tint(b: android.graphics.Bitmap?): Color {
        if (b == null) return Color.White
        return runCatching {
            val s = android.graphics.Bitmap.createScaledBitmap(b, 16, 16, true)
            var best = 0; var bestSat = -1f
            val hsv = FloatArray(3)
            for (y in 0 until 16) for (x in 0 until 16) {
                val p = s.getPixel(x, y)
                android.graphics.Color.colorToHSV(p, hsv)
                val score = hsv[1] * hsv[2]
                if (score > bestSat) { bestSat = score; best = p }
            }
            android.graphics.Color.colorToHSV(best, hsv)
            hsv[2] = hsv[2].coerceAtLeast(0.75f); hsv[1] = hsv[1].coerceAtMost(0.85f)
            Color(android.graphics.Color.HSVToColor(hsv))
        }.getOrDefault(Color.White)
    }

    /** A Music icon for the dock (a note, see-through like the apps' glyph icons). */
    fun dockIcon(): android.graphics.Bitmap {
        val w = 640; val h = 360
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        c.drawOval(w * 0.33f, h * 0.56f, w * 0.45f, h * 0.76f, p)
        c.drawOval(w * 0.55f, h * 0.48f, w * 0.67f, h * 0.68f, p)
        p.strokeWidth = 14f; p.style = android.graphics.Paint.Style.STROKE; p.strokeCap = android.graphics.Paint.Cap.ROUND
        c.drawLine(w * 0.445f, h * 0.66f, w * 0.445f, h * 0.24f, p)
        c.drawLine(w * 0.665f, h * 0.58f, w * 0.665f, h * 0.16f, p)
        p.strokeWidth = 26f; c.drawLine(w * 0.445f, h * 0.27f, w * 0.665f, h * 0.19f, p)
        return b
    }
}

/** Song progress, moving on by itself. */
@Composable
private fun progressOf(t: NowPlaying.Track): Float {
    val v by produceState(0f, t) {
        while (true) { value = if (t.duration > 0) (t.positionNow().toFloat() / t.duration).coerceIn(0f, 1f) else 0f; delay(500) }
    }
    return v
}

/** A few bars rising and falling while the music plays (in the artwork's colour or white). */
@Composable
fun MusicBars(playing: Boolean, color: Color, modifier: Modifier) {
    val tr = rememberInfiniteTransition(label = "bars")
    val t by tr.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val on by animateFloatAsState(if (playing) 1f else 0.15f, tween(300), label = "on")
    Canvas(modifier) {
        val n = 4
        val gap = size.width / (n * 2f - 1f)
        for (i in 0 until n) {
            val ph = t + i * 1.7f
            val hgt = size.height * (0.25f + on * 0.75f * (0.5f + 0.5f * kotlin.math.sin(ph * (1f + i * 0.35f)).toFloat()))
            drawRoundRect(color, Offset(i * gap * 2f, (size.height - hgt) / 2f), Size(gap, hgt), CornerRadius(gap / 2f))
        }
    }
}

/**
 * The island at the top of the screensaver while music plays (like the iPhone's): artwork, song, progress and the
 * bars. Black or glass, floating a little below the top or joined to it (like a MacBook's notch).
 */
@Composable
fun MusicIsland(app: GlassApp, s: Float) {
    val st = app.settings
    val t by NowPlaying.track.collectAsState()
    val tr = t ?: return
    val k = s * st.islandScale
    val glass = st.islandStyle == "glass"
    val attached = st.islandAttached
    val shape = if (attached) RoundedCornerShape(bottomStart = (22 * k).dp, bottomEnd = (22 * k).dp) else RoundedCornerShape(50)
    val color = remember(tr.art) { if (st.islandBars == "white") Color.White else MusicUi.tint(tr.art) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(0.7f, 260f)) }
    Box(Modifier.fillMaxWidth().padding(top = if (attached) 0.dp else (14 * s + st.islandOffset * s).dp), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier.graphicsLayer { scaleX = 0.6f + 0.4f * enter.value; scaleY = scaleX; alpha = enter.value; translationY = (attached).let { if (it) (st.islandOffset * s) * density else 0f } }
                .clip(shape)
                .background(if (glass) Color.White.copy(alpha = 0.16f) else Color.Black.copy(alpha = 0.92f))
                .then(if (glass) Modifier.border((0.8f * k).dp, Color.White.copy(alpha = 0.35f), shape) else Modifier)
                .padding(start = (8 * k).dp, end = (14 * k).dp, top = (if (attached) 10 else 7).let { (it * k).dp }, bottom = (7 * k).dp)
                .widthIn(max = (360 * k).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (st.islandArt) {
                val img = remember(tr.art) { tr.art?.asImageBitmap() }
                Box(Modifier.size((30 * k).dp).clip(RoundedCornerShape((7 * k).dp)).background(Color.White.copy(alpha = 0.1f))) {
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Spacer(Modifier.width((9 * k).dp))
            }
            Column(Modifier.weight(1f, fill = false)) {
                if (st.islandTitle) {
                    Text(tr.title, color = Color.White, fontSize = (11 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (tr.artist.isNotBlank()) Text(tr.artist, color = Color.White.copy(alpha = 0.6f), fontSize = (9.5f * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (st.islandProgress) {
                    val p = progressOf(tr)
                    Spacer(Modifier.height((5 * k).dp))
                    Box(Modifier.width((150 * k).dp).height((2.5f * k).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.22f))) {
                        Box(Modifier.fillMaxWidth(p).height((2.5f * k).dp).background(Color.White))
                    }
                }
            }
            Spacer(Modifier.width((12 * k).dp))
            MusicBars(tr.playing, color, Modifier.size((20 * k).dp, (16 * k).dp))
        }
    }
}

/** A round player button: in the theme's look, white or black, the other way round when selected (like Play). */
@Composable
private fun MusicButton(size: Dp, look: String, light: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    var f by remember { mutableStateOf(false) }
    val sc by animateFloatAsState(if (f) 1.12f else 1f, androidx.compose.animation.core.spring(0.7f, 700f), label = "mb")
    val (bg, fg) = when (look) {
        "white" -> if (f) Color.Black to Color.White else Color.White.copy(alpha = 0.92f) to Color.Black
        "black" -> if (f) Color.White to Color.Black else Color.Black.copy(alpha = 0.7f) to Color.White
        else -> if (f) (if (light) Color(0xFF111114) else Color.White.copy(alpha = 0.95f)) to (if (light) Color.White else Color.Black)
                else (if (light) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.14f)) to (if (light) Color(0xFF111114) else Color.White)
    }
    Box(
        modifier.graphicsLayer { scaleX = sc; scaleY = sc }.size(size).clip(CircleShape).background(bg)
            .onFocusChanged { f = it.isFocused }
            .tvPress(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(size * 0.42f)) { glyph(fg) } }
}

private fun DrawScope.playGlyph(c: Color, playing: Boolean) {
    val w = size.width
    if (playing) { drawRoundRect(c, Offset(w * 0.18f, 0f), Size(w * 0.22f, w), CornerRadius(w * 0.06f)); drawRoundRect(c, Offset(w * 0.60f, 0f), Size(w * 0.22f, w), CornerRadius(w * 0.06f)) }
    else drawPath(Path().apply { moveTo(w * 0.2f, 0f); lineTo(w, w / 2f); lineTo(w * 0.2f, w); close() }, c)
}
private fun DrawScope.skipGlyph(c: Color, forward: Boolean) {
    val w = size.width
    val p = Path().apply {
        if (forward) { moveTo(0f, w * 0.1f); lineTo(w * 0.5f, w * 0.5f); lineTo(0f, w * 0.9f); close(); moveTo(w * 0.42f, w * 0.1f); lineTo(w * 0.92f, w * 0.5f); lineTo(w * 0.42f, w * 0.9f); close() }
        else { moveTo(w, w * 0.1f); lineTo(w * 0.5f, w * 0.5f); lineTo(w, w * 0.9f); close(); moveTo(w * 0.58f, w * 0.1f); lineTo(w * 0.08f, w * 0.5f); lineTo(w * 0.58f, w * 0.9f); close() }
    }
    drawPath(p, c)
}
private fun DrawScope.stopGlyph(c: Color) { val w = size.width; drawRoundRect(c, Offset(w * 0.1f, w * 0.1f), Size(w * 0.8f, w * 0.8f), CornerRadius(w * 0.12f)) }
private fun DrawScope.shuffleGlyph(c: Color) {
    val w = size.width
    val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.11f, cap = StrokeCap.Round)
    drawPath(Path().apply { moveTo(0f, w * 0.25f); cubicTo(w * 0.45f, w * 0.25f, w * 0.45f, w * 0.75f, w * 0.85f, w * 0.75f) }, c, style = st)
    drawPath(Path().apply { moveTo(0f, w * 0.75f); cubicTo(w * 0.45f, w * 0.75f, w * 0.45f, w * 0.25f, w * 0.85f, w * 0.25f) }, c, style = st)
    drawPath(Path().apply { moveTo(w * 0.72f, w * 0.12f); lineTo(w * 0.95f, w * 0.25f); lineTo(w * 0.72f, w * 0.38f) }, c, style = st)
    drawPath(Path().apply { moveTo(w * 0.72f, w * 0.62f); lineTo(w * 0.95f, w * 0.75f); lineTo(w * 0.72f, w * 0.88f) }, c, style = st)
}
private fun DrawScope.listGlyph(c: Color) {
    val w = size.width
    for (i in 0..2) drawLine(c, Offset(w * 0.08f, w * (0.2f + i * 0.3f)), Offset(w * 0.92f, w * (0.2f + i * 0.3f)), w * 0.11f, StrokeCap.Round)
}

/** The music apps on the TV (the ones Glassio knows), as package → name. */
fun installedMusicApps(ctx: android.content.Context): List<Pair<String, String>> =
    NowPlaying.MUSIC_APPS.entries.filter { e -> runCatching { ctx.packageManager.getPackageInfo(e.key, 0) }.isSuccess }
        .map { it.key to it.value }.distinctBy { it.second }

/** Opens a music app (the chosen platform), to pick something to play. */
fun openMusicApp(ctx: android.content.Context, pkg: String) {
    val pm = ctx.packageManager
    (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))?.let { runCatching { ctx.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

/**
 * Music as the background (like YouTube Music on TV): the artwork large on its own blurred colours, the song,
 * its progress and the buttons under it (◀◀  ▶ / ❚❚  ▶▶  ■, shuffle and the music apps). Stop ends the music and
 * your own background comes back.
 */
@Composable
fun MusicStage(app: GlassApp, s: Float, heroFocus: FocusRequester, firstTile: FocusRequester, onApps: () -> Unit) {
    val st = app.settings
    val t by NowPlaying.track.collectAsState()
    val tr = t ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val img = remember(tr.art) { tr.art?.asImageBitmap() }
    val blurred = remember(tr.art) { tr.art?.let { runCatching { blurredBackdrop(it, 70) }.getOrNull() } }
    val k = s * st.musicStageScale
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (blurred != null) Image(blurred, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f }, contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        Column(Modifier.align(Alignment.Center).offset(y = (-34 * s).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size((190 * k).dp).clip(RoundedCornerShape((14 * k).dp)).background(Color.White.copy(alpha = 0.08f))) {
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.height((16 * k).dp))
            Text(tr.title, color = Color.White, fontSize = (19 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = (520 * k).dp))
            if (tr.artist.isNotBlank()) Text(tr.artist, color = Color.White.copy(alpha = 0.7f), fontSize = (13 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height((12 * k).dp))
            val p = progressOf(tr)
            Box(Modifier.width((300 * k).dp).height((3 * k).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(p).height((3 * k).dp).background(Color.White))
            }
            Spacer(Modifier.height((16 * k).dp))
            val look = st.musicButtons
            val light = st.dockStyle == "bow" && look == "theme"
            val big = (40 * k).dp; val small = (32 * k).dp
            var shuffled by remember(tr.pkg) { mutableStateOf(NowPlaying.shuffleOn(ctx)) }
            Row(horizontalArrangement = Arrangement.spacedBy((14 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                MusicButton(small, look, light, Modifier.focusProperties { down = firstTile }, onClick = { shuffled = !shuffled; NowPlaying.shuffle(ctx, shuffled) }) { shuffleGlyph(if (shuffled) it else it.copy(alpha = 0.55f)) }
                MusicButton(small, look, light, Modifier.focusProperties { down = firstTile }, onClick = { NowPlaying.previous() }) { skipGlyph(it, false) }
                MusicButton(big, look, light, Modifier.focusRequester(heroFocus).focusProperties { down = firstTile }, onClick = { NowPlaying.playPause() }) { playGlyph(it, tr.playing) }
                MusicButton(small, look, light, Modifier.focusProperties { down = firstTile }, onClick = { NowPlaying.next() }) { skipGlyph(it, true) }
                MusicButton(small, look, light, Modifier.focusProperties { down = firstTile }, onClick = { NowPlaying.stop() }) { stopGlyph(it) }
                MusicButton(small, look, light, Modifier.focusProperties { down = firstTile }, onClick = onApps) { listGlyph(it) }
            }
            Spacer(Modifier.height((8 * k).dp))
            Text(NowPlaying.appLabel(tr.pkg), color = Color.White.copy(alpha = 0.45f), fontSize = (9 * k).sp)
        }
    }
}

/** The menu's music page (▶ from the menu, or the dock's Music icon): a small player like Apple's. */
@Composable
fun MusicMenuPage(app: GlassApp, s: Float, light: Boolean, first: FocusRequester, onBack: () -> Unit, onApps: () -> Unit) {
    val t by NowPlaying.track.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ink = if (light) Color(0xFF111114) else Color.White
    val tr = t
    Column(Modifier.fillMaxWidth().padding(horizontal = (10 * s).dp, vertical = (4 * s).dp)
        .onKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) false else false }) {
        Text("Music", color = ink.copy(alpha = 0.6f), fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
            modifier = Modifier.padding(bottom = (8 * s).dp))
        if (tr == null) {
            Text("Nothing is playing. Pick a music app to start something.", color = ink.copy(alpha = 0.7f), fontSize = (10 * s).sp)
            Spacer(Modifier.height((10 * s).dp))
            val apps = installedMusicApps(ctx)
            if (apps.isEmpty()) Text("No music app on this TV (Spotify, YouTube Music, Apple Music…)", color = ink.copy(alpha = 0.5f), fontSize = (9 * s).sp)
            apps.forEachIndexed { i, (pkg, name) ->
                Box(Modifier.fillMaxWidth().padding(bottom = (6 * s).dp).clip(RoundedCornerShape((10 * s).dp))
                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    .menuLit(light).tvPress(onKey = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onBack(); true } else false },
                        onClick = { openMusicApp(ctx, pkg) })
                    .padding(horizontal = (10 * s).dp, vertical = (8 * s).dp)) {
                    Text(name, color = ink, fontSize = (11 * s).sp, fontWeight = FontWeight.Medium)
                }
            }
            if (apps.isEmpty()) Box(Modifier.size(1.dp).focusRequester(first).focusable())
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val img = remember(tr.art) { tr.art?.asImageBitmap() }
            Box(Modifier.size((58 * s).dp).clip(RoundedCornerShape((8 * s).dp)).background(ink.copy(alpha = 0.08f))) {
                if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Spacer(Modifier.width((10 * s).dp))
            Column(Modifier.weight(1f)) {
                Text(tr.title, color = ink, fontSize = (11.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tr.artist, color = ink.copy(alpha = 0.6f), fontSize = (9.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(NowPlaying.appLabel(tr.pkg), color = ink.copy(alpha = 0.4f), fontSize = (8 * s).sp, maxLines = 1)
            }
        }
        Spacer(Modifier.height((9 * s).dp))
        val p = progressOf(tr)
        Box(Modifier.fillMaxWidth().height((3 * s).dp).clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxWidth(p).height((3 * s).dp).background(ink))
        }
        Spacer(Modifier.height((10 * s).dp))
        var shuffled by remember(tr.pkg) { mutableStateOf(NowPlaying.shuffleOn(ctx)) }
        val look = app.settings.musicButtons
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val left = Modifier.onKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onBack(); true } else false }
            MusicButton((26 * s).dp, look, light, left, onClick = { shuffled = !shuffled; NowPlaying.shuffle(ctx, shuffled) }) { shuffleGlyph(if (shuffled) it else it.copy(alpha = 0.55f)) }
            MusicButton((26 * s).dp, look, light, onClick = { NowPlaying.previous() }) { skipGlyph(it, false) }
            MusicButton((34 * s).dp, look, light, Modifier.focusRequester(first), onClick = { NowPlaying.playPause() }) { playGlyph(it, tr.playing) }
            MusicButton((26 * s).dp, look, light, onClick = { NowPlaying.next() }) { skipGlyph(it, true) }
            MusicButton((26 * s).dp, look, light, onClick = onApps) { listGlyph(it) }
        }
    }
}

/** A light background on the focused row (the menu's own look). */
@Composable
private fun Modifier.menuLit(light: Boolean): Modifier {
    var f by remember { mutableStateOf(false) }
    return this.onFocusChanged { f = it.isFocused }.background((if (light) Color(0xFF111114) else Color.White).copy(alpha = if (f) 0.18f else 0.05f))
}
