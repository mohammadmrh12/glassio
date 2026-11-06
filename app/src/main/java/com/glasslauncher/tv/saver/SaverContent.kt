package com.glasslauncher.tv.saver

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.SystemInfo
import com.glasslauncher.tv.ui.clockText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Remote keys while the screensaver plays: ◀ ▶ change the video, OK shows / hides the info. */
object SaverControl {
    /** +1 next video, -1 previous one (read and cleared by the player loop). */
    @Volatile var step = 0
    val infoHidden = androidx.compose.runtime.mutableStateOf(false)
    /** The large clock on the screensaver (▼ shows / hides it). */
    val clockShown = androidx.compose.runtime.mutableStateOf(true)
    /** Bumped by OK to show the info again (it then goes after its time, as after a new video). */
    val showRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Whether the info is on screen right now (written while it draws). */
    @Volatile var infoOnScreen = false
    /** The link of the video on screen (for Lock & release). */
    @Volatile var currentUrl: String? = null
    /** Shown after a hold of OK: true = just locked, false = released; [lockRev] restarts the animation. */
    val lockShown = androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    val lockRev = androidx.compose.runtime.mutableIntStateOf(0)

    private var okDown = false
    private var okLong = false

    /** The keys the screensaver uses itself (any other key ends it). */
    fun handles(code: Int, st: com.glasslauncher.tv.data.Settings): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> true
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> st.saverBigClock
        android.view.KeyEvent.KEYCODE_DPAD_UP -> st.saverMultiCam
        else -> false
    }

    /**
     * A key while the screensaver plays (activity or the TV's own screensaver). OK acts when it is let go
     * (a short press shows / hides the info); held, it locks or releases the video. True when used.
     */
    fun onKey(e: android.view.KeyEvent, st: com.glasslauncher.tv.data.Settings): Boolean {
        val code = e.keyCode
        // Back with the cameras up closes just them (the screensaver stays).
        if (code == android.view.KeyEvent.KEYCODE_BACK && com.glasslauncher.tv.HomeKeyService.instance?.multiShowing == true) {
            if (e.action == android.view.KeyEvent.ACTION_UP) com.glasslauncher.tv.HomeKeyService.instance?.closeMulti()
            return true
        }
        if (!handles(code, st)) return false
        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { okDown = true; okLong = false }
                else if (okDown && !okLong && st.saverLock) { okLong = true; toggleLock(st) }
            } else if (e.action == android.view.KeyEvent.ACTION_UP) {
                if (okDown && !okLong) key(code)
                okDown = false
            }
            return true
        }
        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) key(code)
        return true
    }

    private fun toggleLock(st: com.glasslauncher.tv.data.Settings) {
        val id = currentUrl?.let { AerialSource.idOfUrl[it] } ?: return
        val l = st.saverLocked
        val now = id !in l
        st.saverLocked = if (now) l + id else l - id
        lockShown.value = now; lockRev.intValue++
    }

    /** True when the key was used (◀ ▶ OK); any other key ends the screensaver. */
    fun key(code: Int): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { step = 1; true }
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { step = -1; true }
        // OK: hides the info when it is showing; when it isn't (hidden, or gone by itself after its time), it
        // comes back. Toggling a flag alone needed two presses once the info had gone by itself.
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
            if (!infoHidden.value && infoOnScreen) infoHidden.value = true
            else { infoHidden.value = false; showRev.intValue++ }
            true
        }
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { clockShown.value = !clockShown.value; true }
        // ▲: the cameras slide up from the bottom (multi-view).
        android.view.KeyEvent.KEYCODE_DPAD_UP -> { com.glasslauncher.tv.HomeKeyService.instance?.toggleMulti(bottom = true); true }
        else -> false
    }
}

/**
 * The screensaver: aerial videos (cross-faded one into the next, kept on the TV as they play) or the posters,
 * with the clock, date, weather, place and the TV's numbers in Apple's style or one by one like the notes
 * before a Netflix film.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun SaverContent(app: GlassApp, forceSlideshow: Boolean = false) {
    val ctx = LocalContext.current
    val st = app.settings
    val slideshow = forceSlideshow || st.saverMode == "slideshow"
    // null while the list loads: black meanwhile (the posters showed a title's logo for a moment).
    val items by produceState<List<Pair<String, String>>?>(if (slideshow) emptyList() else null) {
        if (!slideshow) value = withContext(Dispatchers.IO) { AerialSource.playlistItems(app) }
    }
    var title by remember { mutableStateOf("") }
    var change by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = items
        if (list == null) { /* loading: black */ }
        else if (list.isNotEmpty() && !failed) {
            val factory = remember { VideoCache.factory(ctx, st.saverCache, st.saverCacheGb) }
            // A frame of the new video is on screen (the fade back in waits for it).
            val rendered = remember { BooleanArray(1) }
            // More buffered before it starts (the first seconds of a new video stuttered while it downloaded).
            val load = remember { androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 4_000, 6_000).build() }
            val players = remember { Array(1) { k -> ExoPlayer.Builder(ctx).setMediaSourceFactory(factory).setLoadControl(load).build().apply {
                volume = 0f
                addListener(object : Player.Listener { override fun onRenderedFirstFrame() { rendered[k] = true } })
            } } }
            DisposableEffect(Unit) { onDispose { players.forEach { it.release() }; SaverControl.step = 0; SaverControl.infoHidden.value = false; SaverControl.clockShown.value = true; com.glasslauncher.tv.HomeKeyService.instance?.closeMulti() } }
            // One SurfaceView: the video goes straight to the TV at its own size and colour, so 4K is really 4K and
            // HDR / Dolby Vision is shown as HDR. Changing video fades through black with a layer above it (the
            // TV ignored a SurfaceView's own transparency, so cross-fading two of them cut instead).
            val view = remember { arrayOfNulls<SurfaceView>(1) }
            val black = remember { Animatable(1f) }
            AndroidView(factory = { c -> SurfaceView(c).also { view[0] = it } }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = black.value }.background(Color.Black))
            LaunchedEffect(list) {
                while (view[0] == null) delay(16)
                // One player: this TV's decoder can't run a second video while one plays (getting the next one
                // ready broke the one on screen), so each video starts when the last has faded out.
                val p = players[0]
                p.setVideoSurfaceView(view[0])
                val fade = st.saverFade
                var idx = 0; var errors = 0
                val single = list.size == 1
                p.repeatMode = if (single) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                fun start(i: Int) {
                    rendered[0] = false
                    SaverControl.currentUrl = list[i.mod(list.size)].second
                    p.setMediaItem(MediaItem.fromUri(list[i.mod(list.size)].second)); p.prepare(); p.play()
                    com.glasslauncher.tv.data.Glog.i("Saver: #${i.mod(list.size)}/${list.size} ${list[i.mod(list.size)].first}")
                }
                suspend fun waitFrame() {
                    var w = 0; var tries = 0
                    while (!rendered[0] && w < 12_000) {
                        if (p.playerError != null) { if (tries++ >= 2) break; p.prepare(); p.play() }
                        delay(50); w += 50
                    }
                }
                var lastBump = android.os.SystemClock.uptimeMillis()
                start(0); title = list[0].first; change++
                waitFrame()
                if (fade) black.animateTo(0f, tween(900)) else black.snapTo(0f)
                if (single) return@LaunchedEffect
                while (true) {
                    var manual = 0
                    val outMs = if (fade) 700 else 0
                    while (true) {
                        delay(100)
                        if (SaverControl.step != 0) { manual = SaverControl.step; SaverControl.step = 0; break }
                        val d = p.duration
                        if (p.playerError != null) { errors++; com.glasslauncher.tv.data.Glog.w("Saver: error ${p.playerError?.errorCodeName}"); break }
                        if (p.playbackState == Player.STATE_ENDED) break
                        if (d > 0 && p.currentPosition >= d - outMs - 200) break
                    }
                    if (errors > list.size.coerceAtMost(8)) { failed = true; return@LaunchedEffect }
                    // ◀ goes straight to the one before, ▶ (or the end) to the next.
                    idx += if (manual < 0) -1 else 1
                    val quick = manual != 0
                    if (fade) black.animateTo(1f, tween(if (quick) 260 else outMs)) else black.snapTo(1f)
                    start(idx)
                    waitFrame()
                    if (p.playerError == null) errors = 0
                    title = list[idx.mod(list.size)].first
                    // Browsing with ◀ ▶ keeps the info as it is (it used to vanish and come back with every press);
                    // after a pause of a few seconds a new video brings it in again.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!quick || now - lastBump > 6_000) { change++ }
                    lastBump = now
                    if (fade) black.animateTo(0f, tween(if (quick) 380 else 1000)) else black.snapTo(0f)
                }
            }
        } else {
            Slideshow(app)
        }
        if (list != null) SaverInfo(app, if (slideshow || list.isEmpty() || failed) "" else title, change)
        LockBadge()
        // Music playing: the island at the top.
        if (st.musicOn && st.musicIsland) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            com.glasslauncher.tv.ui.MusicIsland(app, maxWidth.value / 960f)
        }
        // The large clock, sliding down from the top (▼ shows / hides it).
        if (st.saverBigClock) androidx.compose.animation.AnimatedVisibility(SaverControl.clockShown.value,
            enter = androidx.compose.animation.slideInVertically(tween(520)) { -it } + androidx.compose.animation.fadeIn(tween(420)),
            exit = androidx.compose.animation.slideOutVertically(tween(420)) { -it } + androidx.compose.animation.fadeOut(tween(320))) {
            // Moves down below the music island while a song plays, so the two never overlap.
            val song by com.glasslauncher.tv.data.NowPlaying.track.collectAsState()
            val island = st.musicOn && st.musicIsland && song != null
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val k = maxWidth.value / 960f
                val drop by androidx.compose.animation.core.animateFloatAsState(if (island) (st.islandOffset + 58f * st.islandScale) * k else 0f, tween(420), label = "drop")
                Box(Modifier.fillMaxSize().graphicsLayer { translationY = drop * density }) {
                    com.glasslauncher.tv.ui.BigClock(st, k, inSaver = true)
                }
            }
        }
    }
}

/**
 * After a hold of OK: a small glass padlock in the middle that closes (locked) or opens (released), then fades.
 * Minimal: a frosted round plate, the lock's body and its shackle, drawn as lines.
 */
@Composable
private fun LockBadge() {
    val locked = SaverControl.lockShown.value ?: return
    val rev = SaverControl.lockRev.intValue
    val shackle = remember(rev) { Animatable(if (locked) 0f else 1f) }
    val alpha = remember(rev) { Animatable(0f) }
    LaunchedEffect(rev) {
        alpha.animateTo(1f, tween(180))
        shackle.animateTo(if (locked) 1f else 0f, androidx.compose.animation.core.spring(0.55f, 380f))
        delay(900)
        alpha.animateTo(0f, tween(450))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(132.dp).graphicsLayer { this.alpha = alpha.value; scaleX = 0.9f + 0.1f * alpha.value; scaleY = scaleX }) {
            val w = size.width
            drawCircle(Color.White.copy(alpha = 0.16f))
            drawCircle(Color.White.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.012f))
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.05f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            // Body.
            drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.30f, w * 0.46f), androidx.compose.ui.geometry.Size(w * 0.40f, w * 0.30f),
                androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = stroke)
            drawCircle(Color.White, w * 0.03f, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.60f))
            // Shackle: down into the body when locked, lifted (and its right leg out) when open.
            val lift = (1f - shackle.value) * w * 0.10f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.37f, w * 0.46f - lift)
                lineTo(w * 0.37f, w * 0.36f - lift)
                arcTo(androidx.compose.ui.geometry.Rect(w * 0.37f, w * 0.23f - lift, w * 0.63f, w * 0.49f - lift), 180f, 180f, false)
                lineTo(w * 0.63f, w * 0.40f - lift + (1f - shackle.value) * w * 0.02f)
            }
            drawPath(path, Color.White, style = stroke)
        }
    }
}

/** What the info shows and how: the screensaver's settings, or the background's own (same choices). */
data class InfoLook(
    val style: String, val clock: Boolean, val date: Boolean, val weather: Boolean, val place: Boolean, val sys: Boolean,
    val pos: String, val showSec: Int, val netflixHide: Boolean, val bar: String, val size: Float,
) {
    companion object {
        fun saver(st: com.glasslauncher.tv.data.Settings) = InfoLook(st.saverStyle, st.saverClock, st.saverDate, st.saverWeather, st.saverPlace, st.saverSys,
            st.saverPos, st.saverShowSec, st.saverNetflixHide, st.saverBar, st.saverSize)
        /** Over the home screen's own background (photos, the screensaver's videos). */
        fun background(st: com.glasslauncher.tv.data.Settings) = InfoLook(st.bgInfoStyle, st.bgInfoClock, st.bgInfoDate, st.bgInfoWeather, st.bgInfo, false,
            st.bgInfoPos, st.bgInfoSec, st.saverNetflixHide, st.saverBar, st.saverSize)
    }
}

/** Clock, date, weather, place and the TV's numbers over the screensaver (and, with [look], over the home background). */
@Composable
fun SaverInfo(app: GlassApp, place: String, change: Int, look: InfoLook = InfoLook.saver(app.settings), credit: String = "",
              padTop: Float = 0f, padBottom: Float = 0f) {
    val st = app.settings
    val k = look.size
    val time by produceState(clockText(st.clock24h)) { while (true) { value = clockText(st.clock24h); delay(5_000) } }
    val date by produceState(dateLine()) { while (true) { value = dateLine(); delay(60_000) } }
    val wx by app.weather.now.collectAsState()
    val sys by produceState<SystemInfo.Snap?>(null, look.sys) {
        if (!look.sys) return@produceState
        while (true) { value = withContext(Dispatchers.IO) { SystemInfo.read(app) }; delay(5_000) }
    }
    // Where it shows: a fixed corner, or another one with every video (so nothing burns in).
    val corner = remember(change, look.pos) {
        if (look.pos == "move") listOf("bl", "br", "tl", "tr").random() else look.pos
    }
    // Shown for a while after each video change, or always.
    val shown = remember { Animatable(0f) }
    val showRev = SaverControl.showRev.intValue
    LaunchedEffect(change, look.showSec, showRev) {
        shown.animateTo(1f, tween(900))
        if (look.showSec > 0) { delay(look.showSec * 1000L); shown.animateTo(0f, tween(1200)) }
    }
    val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f))
    val lines = buildList {
        if (look.clock) add("Time" to time)
        if (look.date) add("Date" to date)
        val w = wx
        if (look.weather && w != null) add("Weather" to (w.temp(st.weatherF) + (if (w.condition.isNotBlank()) "  " + w.condition else "")))
        if (look.place && place.isNotBlank()) add("Place" to place)
        if (look.place && credit.isNotBlank()) add("Credit" to credit)
        sys?.let { y ->
            if (st.sysRam && y.ramTotal > 0) add("RAM" to "${(100 * y.ramUsed / y.ramTotal)}%")
            if (st.sysCpu && y.cpu >= 0) add("CPU" to "${y.cpu}%")
            if (st.sysGpu && y.gpu.isNotBlank()) add("GPU" to y.gpu)
            if (st.sysStorage && y.storeTotal > 0) add("Storage" to "${(100 * y.storeUsed / y.storeTotal)}%")
            if (st.sysIp && y.ip.isNotBlank()) add("IP" to y.ip)
        }
    }
    if (lines.isEmpty()) return
    // OK on the remote shows / hides all of it.
    val hidden by SaverControl.infoHidden
    val vis by androidx.compose.animation.core.animateFloatAsState(if (hidden) 0f else 1f, tween(400), label = "info")
    if (vis < 0.01f) { SaverControl.infoOnScreen = false; return }
    if (look.style == "netflix") {
        // Like the notes before a Netflix film: top left, a bar, then each line comes in one after another.
        val netAlphas = remember(change) { HashMap<Int, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>() }
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = vis; SaverControl.infoOnScreen = vis > 0.5f && netAlphas.values.any { it.value > 0.5f } }
            .padding(start = (52 * k).dp, top = (44 * k).dp + padTop.dp)) {
            val n = lines.size
            lines.forEachIndexed { i, (label, value) ->
                val a = remember(change) { Animatable(0f) }
                netAlphas[i] = a
                LaunchedEffect(change, showRev) {
                    delay(500L + i * 650L)
                    a.animateTo(1f, tween(500))
                    // "Hide when complete": all of them go 4 s after the last one came in.
                    if (look.netflixHide) { delay((n - 1 - i) * 650L + 4_000L); a.animateTo(0f, tween(700)) }
                    else if (look.showSec > 0) { delay(look.showSec * 1000L); a.animateTo(0f, tween(700)) }
                }
                Row(Modifier.padding(bottom = (6 * k).dp).graphicsLayer { alpha = a.value; translationX = (1f - a.value) * -24f * density },
                    verticalAlignment = Alignment.CenterVertically) {
                    val bar = when (look.bar) { "white" -> Color.White; "black" -> Color.Black; "off" -> null; else -> Color(0xFFE50914) }
                    if (bar != null) Box(Modifier.width((3 * k).dp).height((22 * k).dp).background(bar))
                    Text(label.uppercase(), color = Color.White, fontSize = (13 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (1.4f * k).sp,
                        style = shade, modifier = Modifier.padding(start = if (bar != null) (10 * k).dp else 0.dp))
                    Box(Modifier.padding(horizontal = (10 * k).dp).width(1.dp).height((16 * k).dp).background(Color.White.copy(alpha = 0.6f)))
                    Text(value, color = Color.White.copy(alpha = 0.92f), fontSize = (13 * k).sp, fontWeight = FontWeight.Medium, style = shade)
                }
            }
        }
        return
    }
    // Apple: the time large and light, the rest small under it.
    val align = when (corner) { "tl" -> Alignment.TopStart; "tr" -> Alignment.TopEnd; "br" -> Alignment.BottomEnd; else -> Alignment.BottomStart }
    val end = corner.endsWith("r")
    Box(Modifier.fillMaxSize().padding(start = (56 * k).dp, end = (56 * k).dp, top = (44 * k).dp + padTop.dp, bottom = (44 * k).dp + padBottom.dp)
        .graphicsLayer { alpha = shown.value * vis; SaverControl.infoOnScreen = shown.value * vis > 0.5f }) {
        Column(Modifier.align(align), horizontalAlignment = if (end) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy((2 * k).dp)) {
            lines.forEach { (label, value) ->
                if (label == "Time") Text(value, color = Color.White, fontSize = (46 * k).sp, fontWeight = FontWeight.Light, style = shade)
                else if (label == "Credit") Text(value, color = Color.White.copy(alpha = 0.6f), fontSize = (11 * k).sp, fontWeight = FontWeight.Medium, style = shade)
                else Text(if (label == "Date" || label == "Weather" || label == "Place") value else "$label  $value",
                    color = Color.White.copy(alpha = 0.88f), fontSize = (14 * k).sp, fontWeight = FontWeight.Medium, style = shade)
            }
        }
    }
}

private fun dateLine(): String = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.ENGLISH).format(java.util.Date())
