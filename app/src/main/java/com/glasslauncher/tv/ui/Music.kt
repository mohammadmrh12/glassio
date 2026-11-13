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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.asAndroidBitmap
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
import kotlinx.coroutines.launch

/** The music player's shared bits: what opens the menu on its music page (the dock's Music icon). */
object MusicUi {
    /**
     * Music mode takes the page only over the carousel: with your own picture, photos, video or the screensaver's
     * videos as the background, that background stays and the island shows the music instead.
     */
    fun stageAllowed(st: com.glasslauncher.tv.data.Settings): Boolean =
        when (st.bgMode) { "image" -> st.bgImage.isBlank(); "video" -> st.bgVideo.isBlank(); "aerial", "photos" -> false; else -> true }

    /** The music page's background for one song: the artwork blurred (the page) and its small copy for the glass. */
    class StagePics(val back: androidx.compose.ui.graphics.ImageBitmap, val glass: androidx.compose.ui.graphics.ImageBitmap?, val page: Boolean = false)
    /** The song's pictures now, the last song's while the new one fades in over it, and that fade (0..1). */
    val stageCur = mutableStateOf<StagePics?>(null)
    val stagePrev = mutableStateOf<StagePics?>(null)
    val stageMix = androidx.compose.animation.core.Animatable(1f)
    private var stageKey: String? = null
    /** Bumped each time Glassio comes back on screen (YouTube's playing counts again only after 3 s from then). */
    val resumedRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Paused: the music page (and the glass over it) dims; 0..0.6, the same scale as the open-dock veil. */
    val pauseDim = androidx.compose.animation.core.Animatable(0f)
    /** Whether the selection was in the music player (it goes back to the carousel or the dock when the player goes). */
    var stageFocused = false

    /**
     * The same artwork comes as a new picture with every update from the music app (each position change): a few
     * of its pixels tell whether it is really another one.
     */
    fun artKey(b: android.graphics.Bitmap?): String? = b?.let {
        runCatching {
            var h = it.width * 31 + it.height
            for (y in 1..5) for (x in 1..5) h = h * 31 + it.getPixel(it.width * x / 6, it.height * y / 6)
            "${it.width}x${it.height}:$h"
        }.getOrNull()
    }

    /**
     * A new song's background: worked out off the main thread (it stuttered when blurred while drawing), then faded
     * in over the last one (both stay fully drawn under it, so the page never dips to black), the glass with it.
     */
    /**
     * The song for the music page: the playing one, or the last one for a moment after it went (between two songs,
     * or while an app changes its session, there is often a moment with no song: the page and the dock's glass
     * flashed the carousel picture). Stop from Glassio takes it away at once.
     */
    @Composable
    fun heldTrack(): NowPlaying.Track? {
        val t by NowPlaying.track.collectAsState()
        var last by remember { androidx.compose.runtime.mutableStateOf<NowPlaying.Track?>(null) }
        // YouTube plays videos too: leaving it with a video on, its session stayed "playing" for a second or two
        // before it stopped, and the music page flashed up and went (taking the selection with it). It counts as
        // music only once it has played on for 3 seconds.
        val yt = t?.pkg?.let { NowPlaying.sometimesMusic(it) } == true
        var ytOk by remember { androidx.compose.runtime.mutableStateOf(false) }
        LaunchedEffect(t?.key, t?.playing, yt, resumedRev.intValue) {
            ytOk = false
            if (yt && t?.playing == true) { delay(3000); ytOk = true }
        }
        // Until then the music app under it (Spotify, paused by the video) stays the page.
        val alt by NowPlaying.alt.collectAsState()
        val usable = if (yt && !ytOk) alt else t
        LaunchedEffect(usable) {
            if (usable != null) last = usable
            else if (android.os.SystemClock.uptimeMillis() - NowPlaying.stoppedAt < 3000 || yt) last = null
            else { delay(1500); last = null }
        }
        return usable ?: last?.takeIf { android.os.SystemClock.uptimeMillis() - NowPlaying.stoppedAt > 3000 }
    }

    suspend fun stageTo(art: android.graphics.Bitmap?) {
        if (art == null) { stageKey = null; stageCur.value = null; stagePrev.value = null; stageMix.snapTo(1f); return }
        val key = artKey(art)
        if (key == stageKey && stageCur.value != null) return
        stageKey = key
        val pics = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runCatching { val b = blurredBackdrop(art, 70); StagePics(b, stagePicture(art, b)) }.getOrNull()
        } ?: return
        val old = stageCur.value
        if (old == null) { stageCur.value = pics; return }
        stageMix.snapTo(0f)
        stagePrev.value = old; stageCur.value = pics
        try { stageMix.animateTo(1f, androidx.compose.animation.core.tween(900)) }
        finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { stageMix.snapTo(1f); stagePrev.value = null } }
    }

    /** The music page coming back over another page (the channels' background): faded in, the glass with it. */
    suspend fun fadeFrom(page: androidx.compose.ui.graphics.ImageBitmap) {
        if (stageCur.value == null) return
        stageMix.snapTo(0f)
        stagePrev.value = StagePics(page, page, page = true)
        try { stageMix.animateTo(1f, androidx.compose.animation.core.tween(240)) }
        finally { kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { stageMix.snapTo(1f); stagePrev.value = null } }
    }

    val openInMenu = mutableStateOf(false)
    const val DOCK_PKG = "music:player"
    /** The dock's Music icon: the music page shows (even with Music mode off) until Back or the music stops. */
    val forced = mutableStateOf(false)
    /** Bumped to put the selection on the music page's Play button. */
    val focusRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** A music app selected in the dock (its player shows in the carousel's place, also with nothing playing). */
    val dockApp = mutableStateOf<String?>(null)
    /** The dock's Music icon is selected: the music apps stand in the carousel's place. */
    val appsOn = mutableStateOf(false)
    @Volatile var appsFocused = false
    val appsEntry = FocusRequester()
    val appsShowing get() = appsOn.value && !forced.value
    /** Nuvio / Stremio selected in the dock: the carousel stays, also while music plays (the island shows it). */
    val carouselApp = mutableStateOf(false)
    /** The music apps are on screen now (not while a song plays: the player then). */
    val appsVisible = mutableStateOf(false)
    /** The idle player's app kept while its page fades out. */
    @Volatile var idleKept: String? = null
    /** The song for the player now: a music app selected in the dock shows only its own (another app's: its idle player). */
    fun songFor(t: NowPlaying.Track?): NowPlaying.Track? { val d = dockApp.value ?: idleKept; return t?.takeIf { forced.value || d == null || it.pkg == d } }
    /** Their grey page is on (also behind the open dock). */
    val appsPage = mutableStateOf(false)
    /** A music app (its player, not rows, unless chosen in Settings → Music / Channels). YouTube isn't one here. */
    fun isMusicApp(ctx: android.content.Context, pkg: String) = pkg in NowPlaying.MUSIC_APPS || (!NowPlaying.sometimesMusic(pkg) && NowPlaying.audioApp(ctx, pkg))
    fun playerFor(ctx: android.content.Context, st: com.glasslauncher.tv.data.Settings, pkg: String) =
        st.musicOn && isMusicApp(ctx, pkg) && pkg !in st.channelsMusicRowApps
    /** The dock's tile for an app (set by the home screen). */
    @Volatile var tileFor: (String) -> FocusRequester? = { null }
    /** Down from the player: back to the tile it came from (the Music icon, or the music app), else the first app. */
    fun downTo(first: FocusRequester): FocusRequester = when {
        forced.value -> tileFor(DOCK_PKG)
        dockApp.value != null -> tileFor(dockApp.value!!)
        else -> null
    } ?: first
    /** Its player with nothing playing is on screen (the page under it is the grey one). */
    val idleOn = mutableStateOf(false)
    /**
     * Starts [pkg] playing from where it was, without opening it: the app that played last through the TV's own Play
     * (the system brings it back, paused at first: Play once more when its player is there), another one through
     * its own Play. Opened if it doesn't answer.
     */
    fun startIn(ctx: android.content.Context, pkg: String, lastPkg: String, scope: kotlinx.coroutines.CoroutineScope) {
        val app = ctx.applicationContext
        val am = app.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        for (a in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) runCatching {
            val ev = android.view.KeyEvent(a, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)
            if (pkg == lastPkg) am.dispatchMediaKeyEvent(ev)
            else app.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_BUTTON).setPackage(pkg).putExtra(android.content.Intent.EXTRA_KEY_EVENT, ev))
        }
        scope.launch {
            val t0 = android.os.SystemClock.uptimeMillis()
            while (android.os.SystemClock.uptimeMillis() - t0 < 4_500) {
                delay(250)
                val t = NowPlaying.track.value
                if (t?.pkg == pkg) {
                    // Back, but paused: just woken, it turns Play down for a few seconds (Spotify), so Play again
                    // until it plays (a plain Play, never a toggle that could pause it).
                    repeat(14) {
                        if (NowPlaying.reallyPlaying()) return@launch
                        NowPlaying.play()
                        delay(800)
                    }
                    return@launch
                }
            }
            openMusicApp(app, pkg)
        }
    }
    /** Whether the music page takes the home screen now. */
    fun stageOn(st: com.glasslauncher.tv.data.Settings, song: NowPlaying.Track?): Boolean {
        if (song == null) return false
        if (forced.value || dockApp.value != null) return true
        // Only a song that played while Glassio runs: a music app left paused from before (Spotify keeps its paused
        // session) put the music page up the moment Glassio started, after an update or a restart.
        val key = song.pkg + "|" + song.title + "|" + song.artist
        if (song.playing) playedHere.add(key)
        return st.musicOn && st.musicMode && stageAllowed(st) && key in playedHere
    }
    /** Songs that played since Glassio started (paused afterwards they keep the music page, as before). */
    private val playedHere = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    /** The dock's Music icon: the music page if something plays, else the menu's music page (the apps). */
    fun openFromDock(app: com.glasslauncher.tv.GlassApp) {
        if (NowPlaying.track.value != null) { forced.value = true; focusRev.intValue++ }
        else { openInMenu.value = true; app.requestSettings.intValue = 100; app.requestSettingsRev.intValue++ }
    }

    /** The island opened up (▲ on the screensaver) with its buttons; [islandSel] is the button selected. */
    val islandOpen = mutableStateOf(false)
    val islandSel = androidx.compose.runtime.mutableIntStateOf(2)
    /** Shuffle as the playing app last reported it (the island's shuffle button). */
    val shuffled = NowPlaying.shuffled

    /**
     * A key while the island is open (the screensaver passes them here): ◀ ▶ choose a button, OK presses it,
     * ▼ or Back close it. True when used.
     */
    fun islandKey(ctx: android.content.Context, code: Int): Boolean {
        when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> islandSel.intValue = (islandSel.intValue - 1).coerceAtLeast(0)
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> islandSel.intValue = (islandSel.intValue + 1).coerceAtMost(5)
            android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> when (islandSel.intValue) {
                0 -> NowPlaying.shuffle(ctx, !shuffled.value)
                1 -> NowPlaying.previous()
                2 -> NowPlaying.playPause()
                3 -> NowPlaying.next()
                4 -> { islandOpen.value = false; NowPlaying.stop() }
                // The list: the playing app itself (its queue and playlists).
                5 -> NowPlaying.track.value?.pkg?.let { islandOpen.value = false; openMusicApp(ctx, it) }
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_BACK -> islandOpen.value = false
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {}
            else -> return false
        }
        return true
    }

    /**
     * The music mode's background as a small picture (the artwork blurred and darkened, as the stage draws it), so
     * the glass of the dock and menus shows the music page instead of the carousel picture under it.
     */
    fun stagePicture(art: android.graphics.Bitmap, blurred: androidx.compose.ui.graphics.ImageBitmap? = null): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
        val w = 480; val h = 270
        val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.BLACK)
        val blur = (blurred ?: blurredBackdrop(art, 70)).asAndroidBitmap()
        val sc = maxOf(w.toFloat() / blur.width, h.toFloat() / blur.height)
        val dw = blur.width * sc; val dh = blur.height * sc
        c.drawBitmap(blur, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { alpha = (0.85f * 255).toInt() })
        c.drawColor(android.graphics.Color.argb((0.35f * 255).toInt(), 0, 0, 0))
        out.asImageBitmap()
    }.getOrNull()

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
    // About 12 steps a second, and none while paused: at the screen's 60 a second these few bars made the whole
    // home screen draw again all the time (the TV's GPU was always busy, the Library scrolled slower).
    val t by produceState(0f, playing) {
        if (!playing) return@produceState
        while (true) { value = (android.os.SystemClock.uptimeMillis() % 1400L) / 1400f * 6.2832f; delay(80) }
    }
    val on by animateFloatAsState(if (playing) 1f else 0.15f, tween(300), label = "on")
    Canvas(modifier) {
        val n = 4
        val gap = size.width / (n * 2f - 1f)
        for (i in 0 until n) {
            val ph = t + i * 1.7f
            val hgt = size.height * (0.25f + on * 0.75f * (0.5f + 0.5f * kotlin.math.sin(ph * (1f + i * 0.35f))))
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
    if (t == null) { androidx.compose.runtime.SideEffect { if (MusicUi.islandOpen.value) MusicUi.islandOpen.value = false }; return }
    val tr = t!!
    val k = s * st.islandScale
    // Its look, as the dock's panel is drawn: with the theme (Black AMOLED black, Black on white white, Frosted
    // frosted, Clear clear, Still Glass still glass), or chosen.
    val look = when (st.islandStyle) { "none" -> "none"; "black" -> "amoled"; "glass" -> "water_still"; else -> st.dockStyle }
    val ink = if (look == "bow") Color(0xFF111114) else Color.White
    val inkOn = if (look == "bow") Color.White else Color.Black
    // No background at all: the song and buttons float over the picture (with a soft shadow to stay readable).
    val bare = look == "none"
    val attached = st.islandAttached
    val open = MusicUi.islandOpen.value
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(open) { if (open) { MusicUi.islandSel.intValue = 2 } }
    // Paused for a while: the island goes away (back as soon as it plays, or when opened).
    var away by remember { mutableStateOf(false) }
    LaunchedEffect(tr.playing, open) { if (tr.playing || open) away = false else { kotlinx.coroutines.delay(20_000); away = true } }
    val shown by animateFloatAsState(if (away) 0f else 1f, tween(600), label = "islandShown")
    // (It fades out but stays composed: leaving the composition from here while its animations were still
    // being drawn crashed the home screen once — "a state created after the snapshot was taken".)
    val color = remember(tr.art) { if (st.islandBars == "white") ink else MusicUi.tint(tr.art) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, androidx.compose.animation.core.spring(0.7f, 260f)) }
    val img = remember(tr.art) { tr.art?.asImageBitmap() }
    // Like the iPhone's Dynamic Island: one shape that grows from its middle (springy, settling softly), its
    // corners rounding out a little, the new content fading in once there is room for it.
    val morph = androidx.compose.animation.core.spring<androidx.compose.ui.unit.IntSize>(0.82f, 320f)
    val grow by animateFloatAsState(if (open) 1f else 0f, androidx.compose.animation.core.spring(0.82f, 320f), label = "island")
    val r = (22 * k + 6 * k * grow).dp
    val shape = if (attached) RoundedCornerShape(bottomStart = r, bottomEnd = r) else RoundedCornerShape(r)
    Box(Modifier.fillMaxWidth().padding(top = if (attached) 0.dp else (14 * s + st.islandOffset * s).dp), contentAlignment = Alignment.TopCenter) {
      // The panel is its own layer under the content and takes its size every frame (as the background of the
      // morphing content itself it vanished while the island opened and closed).
      Box(Modifier.graphicsLayer { scaleX = 0.6f + 0.4f * enter.value; scaleY = scaleX; alpha = enter.value * shown; translationY = (attached).let { if (it) (st.islandOffset * s) * density else 0f } }) {
        if (!bare) GlassPanel(Modifier.matchParentSize(), radius = r, style = look, refract = look.startsWith("water") || look == "liquid", cache = false) {}
        androidx.compose.animation.AnimatedContent(
            targetState = open,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(220, delayMillis = 110)) togetherWith androidx.compose.animation.fadeOut(tween(110)))
                    .using(androidx.compose.animation.SizeTransform(clip = true) { _, _ -> morph })
            },
            contentAlignment = Alignment.TopCenter,
            label = "islandMorph",
            modifier = Modifier.clip(shape),
        ) { isOpen ->
          androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalTextStyle provides androidx.compose.ui.text.TextStyle(
              shadow = if (bare) androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.7f), blurRadius = 10f) else null)) {
            if (!isOpen) Row(Modifier.padding(start = (8 * k).dp, end = (14 * k).dp, top = ((if (attached) 10 else 7) * k).dp, bottom = (7 * k).dp).widthIn(max = (360 * k).dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (st.islandArt) {
                    Box(Modifier.size((30 * k).dp).clip(RoundedCornerShape((7 * k).dp)).background(Color.White.copy(alpha = 0.1f))) {
                        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.width((9 * k).dp))
                }
                Column(Modifier.weight(1f, fill = false)) {
                    if (st.islandTitle) {
                        Text(tr.title, color = ink, fontSize = (11 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tr.artist.isNotBlank()) Text(tr.artist, color = ink.copy(alpha = 0.6f), fontSize = (9.5f * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (st.islandProgress) {
                        val p = progressOf(tr)
                        Spacer(Modifier.height((5 * k).dp))
                        Box(Modifier.width((150 * k).dp).height((2.5f * k).dp).clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.22f))) {
                            Box(Modifier.fillMaxWidth(p).height((2.5f * k).dp).background(ink))
                        }
                    }
                }
                Spacer(Modifier.width((12 * k).dp))
                MusicBars(tr.playing, color, Modifier.size((20 * k).dp, (16 * k).dp))
            } else Column(Modifier.padding(start = (18 * k).dp, end = (18 * k).dp, top = ((if (attached) 18 else 15) * k).dp, bottom = (16 * k).dp)) {
                // Open: the artwork, the song, its progress and the buttons (shuffle, ◀◀, play / pause, ▶▶, stop, the app's list).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size((52 * k).dp).clip(RoundedCornerShape((10 * k).dp)).background(Color.White.copy(alpha = 0.1f))) {
                        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.width((12 * k).dp))
                    Column(Modifier.width((262 * k).dp)) {
                        Text(tr.title, color = ink, fontSize = (13 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (tr.artist.isNotBlank()) Text(tr.artist, color = ink.copy(alpha = 0.65f), fontSize = (11 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width((10 * k).dp))
                    MusicBars(tr.playing, color, Modifier.size((22 * k).dp, (18 * k).dp))
                }
                Spacer(Modifier.height((10 * k).dp))
                val p = progressOf(tr)
                Box(Modifier.width((358 * k).dp).height((3 * k).dp).clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.22f))) {
                    Box(Modifier.fillMaxWidth(p).height((3 * k).dp).background(ink))
                }
                Spacer(Modifier.height((10 * k).dp))
                val sel = MusicUi.islandSel.intValue
                Row(Modifier.width((358 * k).dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    for (b in 0..5) {
                        val on = sel == b
                        val size = (if (b == 2) 36 else 30) * k
                        val sc by animateFloatAsState(if (on) 1.12f else 1f, androidx.compose.animation.core.spring(0.7f, 700f), label = "ib$b")
                        Box(Modifier.graphicsLayer { scaleX = sc; scaleY = sc }.size(size.dp).clip(CircleShape)
                            .background(if (on) ink else ink.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                            val fg = if (on) inkOn else ink
                            val shuffleOn = MusicUi.shuffled.value
                            Canvas(Modifier.size((size * 0.42f).dp)) {
                                when (b) {
                                    0 -> shuffleGlyph(if (shuffleOn) fg else fg.copy(alpha = 0.4f))
                                    1 -> skipGlyph(fg, false)
                                    2 -> playGlyph(fg, tr.playing)
                                    3 -> skipGlyph(fg, true)
                                    4 -> stopGlyph(fg)
                                    else -> listGlyph(fg)
                                }
                            }
                            if (b == 0 && shuffleOn) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = (size * 0.1f).dp).size((size * 0.09f).dp).clip(CircleShape).background(fg))
                        }
                    }
                }
            }
          }
        }
      }
    }
}

/** A round player button: in the theme's look, white or black, the other way round when selected (like Play). */
@Composable
private fun MusicButton(size: Dp, look: String, light: Boolean, modifier: Modifier = Modifier, active: Boolean = false, onOff: Boolean = false, onClick: () -> Unit, glyph: DrawScope.(Color) -> Unit) {
    val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
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
            // Back: always to the home screen's own Back (unhandled here, the first press only moved the selection
            // off the button and nothing happened).
            .tvPress(onKey = { ev -> if (ev.key == Key.Back) { if (ev.type == KeyEventType.KeyUp) back?.onBackPressedDispatcher?.onBackPressed(); true } else false }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) { glyph(if (active || !onOff) fg else fg.copy(alpha = 0.4f)) }
        // On (shuffle): a dot under the mark, so it shows clearly in every button look.
        if (active) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = size * 0.1f).size(size * 0.09f).clip(CircleShape).background(fg))
    }
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
private var musicAppsCache: Pair<Long, List<Pair<String, String>>>? = null
fun installedMusicApps(ctx: android.content.Context): List<Pair<String, String>> {
    musicAppsCache?.let { (at, l) -> if (android.os.SystemClock.uptimeMillis() - at < 30_000) return l }
    return findMusicApps(ctx).also { musicAppsCache = android.os.SystemClock.uptimeMillis() to it }
}
private fun findMusicApps(ctx: android.content.Context): List<Pair<String, String>> {
    val pm = ctx.packageManager
    val known = (NowPlaying.MUSIC_APPS.entries.map { it.key to it.value } + listOf("com.google.android.youtube.tv" to "YouTube", "com.google.android.youtube.googletv" to "YouTube"))
        .filter { (pkg, _) -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
    // Any other app that calls itself an audio app (and can be opened from the TV's home).
    val others = runCatching {
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.applicationInfo }
            .filter { it.category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO && it.packageName != ctx.packageName }
            .map { it.packageName to pm.getApplicationLabel(it).toString() }
    }.getOrDefault(emptyList())
    return (known + others).distinctBy { it.first }.distinctBy { it.second }
}

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
    val held = MusicUi.songFor(MusicUi.heldTrack())
    if (held == null) { (MusicUi.dockApp.value ?: MusicUi.idleKept)?.let { MusicIdle(app, s, it, heroFocus, firstTile, onApps) }; return }
    val tr = held
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val k = s * st.musicStageScale
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // A new song fades in over the last one (its background, artwork and names), like Apple Music on TV. The
    // background is the one the glass shows (MusicUi.stageTo): the new picture fades in over the old one, still
    // fully drawn, so nothing dips to black in between.
    val cur = MusicUi.stageCur.value
    val prev = MusicUi.stagePrev.value
    // The artwork by its look, not by the picture object (the app sends a new one with every update).
    val artKey = remember(tr.art) { MusicUi.artKey(tr.art) }
    val artImg = remember(artKey) { tr.art?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The page faded from (another page as it is, or the last song's darkened like this one), and this song's
        // picture darkened, fading in over it as one layer (darkening both together dimmed the page faded from).
        if (prev != null) { Image(prev.back, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (!prev.page) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f))) }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = if (prev != null) MusicUi.stageMix.value else 1f }) {
            if (cur != null) Image(cur.back, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        // More dimmed while paused (the glass over it dims the same way: its veil).
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = MusicUi.pauseDim.value * VEIL_DIM }.background(Color.Black))
        Column(Modifier.align(Alignment.Center).offset(y = (-34 * s).dp).onFocusChanged { MusicUi.stageFocused = it.hasFocus },
            horizontalAlignment = Alignment.CenterHorizontally) {
            // A soft shadow under the artwork, as if it floats over the page.
            Box(Modifier.size((190 * k).dp).shadow((22 * k).dp, RoundedCornerShape((14 * k).dp), ambientColor = Color.Black, spotColor = Color.Black)
                .clip(RoundedCornerShape((14 * k).dp)).background(Color.White.copy(alpha = 0.08f))) {
                androidx.compose.animation.Crossfade(artImg, animationSpec = tween(700), label = "art") { img ->
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
          androidx.compose.animation.Crossfade(tr.title to tr.artist, animationSpec = tween(600), label = "song") { (title, artist) ->
           // One fixed width for both names while they cross-fade (each was laid out from the left: the new name
           // jumped to the left of the old one, then back to the middle).
           Column(Modifier.width((560 * k).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height((16 * k).dp))
            Text(title, color = Color.White, fontSize = (19 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = (520 * k).dp))
            if (artist.isNotBlank()) Text(artist, color = Color.White.copy(alpha = 0.7f), fontSize = (13 * k).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
           }
          }
            Spacer(Modifier.height((12 * k).dp))
            val p = progressOf(tr)
            Box(Modifier.width((300 * k).dp).height((3 * k).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(p).height((3 * k).dp).background(Color.White))
            }
            Spacer(Modifier.height((16 * k).dp))
            val look = st.musicButtons
            val light = st.dockStyle == "bow" && look == "theme"
            val big = (40 * k).dp; val small = (32 * k).dp
            val shuffled = NowPlaying.shuffled.value
            Row(horizontalArrangement = Arrangement.spacedBy((14 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                MusicButton(small, look, light, Modifier.focusProperties { down = MusicUi.downTo(firstTile) }, active = shuffled, onOff = true, onClick = { NowPlaying.shuffle(ctx, !shuffled) }) { shuffleGlyph(it) }
                MusicButton(small, look, light, Modifier.focusProperties { down = MusicUi.downTo(firstTile) }, onClick = { NowPlaying.previous() }) { skipGlyph(it, false) }
                MusicButton(big, look, light, Modifier.focusRequester(heroFocus).focusProperties { down = MusicUi.downTo(firstTile) }, onClick = { NowPlaying.playPause() }) { playGlyph(it, tr.playing) }
                MusicButton(small, look, light, Modifier.focusProperties { down = MusicUi.downTo(firstTile) }, onClick = { NowPlaying.next() }) { skipGlyph(it, true) }
                // Stop: the player goes away, so the selection moves to the dock (it was left nowhere).
                MusicButton(small, look, light, Modifier.focusProperties { down = MusicUi.downTo(firstTile) }, onClick = {
                    runCatching { firstTile.requestFocus() }
                    NowPlaying.stop()
                    scope.launch { delay(120); runCatching { firstTile.requestFocus() } }
                }) { stopGlyph(it) }
                MusicButton(small, look, light, Modifier.focusProperties { down = MusicUi.downTo(firstTile) }, onClick = onApps) { listGlyph(it) }
            }
            Spacer(Modifier.height((8 * k).dp))
            Text(NowPlaying.appLabel(tr.pkg), color = Color.White.copy(alpha = 0.45f), fontSize = (9 * k).sp)
        }
    }
}

/**
 * A music app's player with nothing playing (the app selected in the dock): its logo where the artwork goes, its
 * name, and Play (it carries on from where it was) and Open. On the grey page (drawn behind, the glass shows it).
 */
@Composable
private fun MusicIdle(app: GlassApp, s: Float, pkg: String, heroFocus: FocusRequester, firstTile: FocusRequester, onApps: () -> Unit) {
    val st = app.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val k = s * st.musicStageScale
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val label = NowPlaying.appLabel(pkg)
    // The app's TV banner (its own frame, 16:9), or its icon in the artwork's square.
    val logo = remember(pkg) {
        runCatching {
            val pm = ctx.packageManager
            val banner = pm.getApplicationBanner(pkg)
            val d = banner ?: pm.getApplicationIcon(pkg)
            val w = if (banner != null) 640 else 192; val h = if (banner != null) 360 else 192
            val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, w, h); d.draw(android.graphics.Canvas(b))
            (banner != null) to b.asImageBitmap()
        }.getOrNull()
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.Center).offset(y = (-34 * s).dp).onFocusChanged { MusicUi.stageFocused = it.hasFocus },
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (logo?.first == true) Box(Modifier.width((300 * k).dp).height((169 * k).dp).padding(top = (10 * k).dp, bottom = (11 * k).dp)
                .clip(RoundedCornerShape((14 * k).dp))) {
                Image(logo.second, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else Box(Modifier.size((190 * k).dp).clip(RoundedCornerShape((14 * k).dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                if (logo != null) Image(logo.second, null, Modifier.fillMaxSize().padding((30 * k).dp), contentScale = ContentScale.Fit)
            }
            Column(Modifier.width((560 * k).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height((16 * k).dp))
                Text(label, color = Color.White, fontSize = (19 * k).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("Nothing playing", color = Color.White.copy(alpha = 0.7f), fontSize = (13 * k).sp, maxLines = 1)
            }
            // (Where the progress bar is on a song's page: the buttons stay in the same place.)
            Spacer(Modifier.height((12 * k + 3 * k + 16 * k).dp))
            val look = st.musicButtons
            val light = st.dockStyle == "bow" && look == "theme"
            Row(horizontalArrangement = Arrangement.spacedBy((14 * k).dp), verticalAlignment = Alignment.CenterVertically) {
                MusicButton((40 * k).dp, look, light, Modifier.focusRequester(heroFocus).focusProperties { down = MusicUi.downTo(firstTile) },
                    onClick = { MusicUi.startIn(ctx, pkg, st.musicLastPkg, scope) }) { playGlyph(it, false) }
                MusicButton((32 * k).dp, look, light, Modifier.focusProperties { down = MusicUi.downTo(firstTile) }, onClick = onApps) { listGlyph(it) }
            }
            Spacer(Modifier.height((8 * k).dp))
            Text("Play carries on where $label left off  ·  ≡ music apps", color = Color.White.copy(alpha = 0.45f), fontSize = (9 * k).sp)
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
        val shuffled = NowPlaying.shuffled.value
        val look = app.settings.musicButtons
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            val left = Modifier.onKeyEvent { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { onBack(); true } else false }
            MusicButton((26 * s).dp, look, light, left, active = shuffled, onOff = true, onClick = { NowPlaying.shuffle(ctx, !shuffled) }) { shuffleGlyph(it) }
            MusicButton((26 * s).dp, look, light, onClick = { NowPlaying.previous() }) { skipGlyph(it, false) }
            MusicButton((34 * s).dp, look, light, Modifier.focusRequester(first), onClick = { NowPlaying.playPause() }) { playGlyph(it, tr.playing) }
            MusicButton((26 * s).dp, look, light, onClick = { NowPlaying.next() }) { skipGlyph(it, true) }
            MusicButton((26 * s).dp, look, light, onClick = { NowPlaying.stop() }) { stopGlyph(it) }
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
