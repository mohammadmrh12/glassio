package com.glasslauncher.tv.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.Http

/**
 * Trailers in the carousel, like the Apple TV app: the title's trailer plays in place of its picture (filling the
 * screen, at its own size, or in a small glass window next to the buttons). The trailer is the title's YouTube
 * trailer as Cinemeta lists it, played by YouTube's own embedded player (a WebView that never takes the focus).
 */
object Trailers {
    /** [streams]: played by Glassio's own player; null = YouTube's player (a page) as before. */
    data class Req(val key: String, val ytId: String, val rev: Int, val streams: com.glasslauncher.tv.data.TrailerSource.Streams? = null)

    /** The trailer playing now, or null. */
    val playing = mutableStateOf<Req?>(null)
    /** Its picture is on screen (the dock and the page dots step aside then). */
    val started = mutableStateOf(false)
    /** Bumped when a trailer ends on its own (the carousel then moves on). */
    val endedRev = mutableIntStateOf(0)
    private var rev = 0
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The title's YouTube trailer id (Cinemeta: trailerStreams, then trailers), or null. Blocking. */
    fun youtubeId(http: Http, type: String, imdb: String): String? = youtubeIds(http, type, imdb).firstOrNull()

    /** All of the title's YouTube trailers Cinemeta lists, in its order. Blocking. */
    fun youtubeIds(http: Http, type: String, imdb: String): List<String> {
        if (!imdb.startsWith("tt")) return emptyList()
        cache[imdb]?.let { return it.split(',').filter { s -> s.isNotBlank() } }
        val t = if (type.equals("movie", true)) "movie" else "series"
        val ids = runCatching {
            val meta = http.json("https://v3-cinemeta.strem.io/meta/$t/$imdb.json", 7L * 24 * 3600 * 1000).optJSONObject("meta") ?: return@runCatching emptyList()
            val a = meta.optJSONArray("trailerStreams")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("ytId")?.takeIf { s -> s.isNotBlank() } } }.orEmpty()
            val b = meta.optJSONArray("trailers")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("source")?.takeIf { s -> s.isNotBlank() } } }.orEmpty()
            (a + b).distinct()
        }.getOrDefault(emptyList())
        cache[imdb] = ids.joinToString(",")
        return ids
    }

    /** Versions made for accessibility (a sign-language interpreter in the picture, audio description): skipped. */
    private val accessible = Regex("""\b(ASL|BSL|sign[- ]language|audio[- ]descri\w*|described video|lengua de se[ñn]as|langue des signes)\b""", RegexOption.IGNORE_CASE)

    fun play(key: String, ytId: String, streams: com.glasslauncher.tv.data.TrailerSource.Streams? = null) {
        started.value = false; playing.value = Req(key, ytId, ++rev, streams)
    }
    fun stop() { playing.value = null; started.value = false }
    /** Glassio's player couldn't play it: YouTube's player takes over. */
    fun toWeb(r: Req) { if (playing.value?.rev == r.rev) { started.value = false; playing.value = r.copy(streams = null, rev = ++rev) } }

    /** The tallest trailer picture worth playing here: Automatic 1080p (720p on small TVs); Full the screen's own. */
    fun maxHeight(ctx: android.content.Context, full: Boolean): Int {
        if (full) {
            val d = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
            val modes = d?.supportedModes.orEmpty()
            return (modes.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) } ?: 1080).coerceAtLeast(1080)
        }
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }.totalMem
        return if (mem in 1 until 3_000_000_000L) 720 else 1080
    }

    /**
     * Gets the trailer of a title ready (its id and streams) while you look at it, so it starts at once when asked.
     * Blocking. Returns the id and the streams (null streams = YouTube's player).
     */
    fun prepare(app: GlassApp, type: String, imdb: String): Pair<String, com.glasslauncher.tv.data.TrailerSource.Streams?>? {
        val ids = youtubeIds(app.http, type, imdb)
        if (ids.isEmpty()) return null
        val full = app.settings.trailerQuality == "full"
        // The first ordinary trailer that plays here (one with a sign-language interpreter or audio description,
        // or one YouTube won't show here, is passed over for the next).
        for (yt in ids.take(4)) {
            val st = com.glasslauncher.tv.data.TrailerSource.resolve(yt, maxHeight(app, full), full) ?: continue
            if (accessible.containsMatchIn(st.title)) { Glog.i("Trailer $yt skipped: ${st.title}"); continue }
            return yt to st
        }
        return ids.first() to null
    }
    fun isPlaying(key: String?) = key != null && playing.value?.key == key
}

private class TrailerBridge(val onState: (Int) -> Unit, val onError: (Int) -> Unit) {
    @JavascriptInterface fun state(s: Int) = onState(s)
    @JavascriptInterface fun error(e: Int) = onError(e)
}

/**
 * The trailer layer of the home screen. [mode]: "cinema" fills the screen (cropped), "original" shows the whole
 * picture (black bars), "popup" is a small glass window next to the Play button.
 */
@Composable
fun TrailerLayer(app: GlassApp, s: Float, dockInset: Float = 0f) {
    val req = Trailers.playing.value ?: return
    val st = app.settings
    val mode = st.trailerSize
    if (req.streams != null) { androidx.compose.runtime.key(req.rev) { NativeTrailer(app, s, req, req.streams, mode, dockInset) }; return }
    val alpha = remember(req.rev) { Animatable(0f) }
    // Shown once it really plays (YouTube's own loading screen stays hidden).
    var started by remember(req.rev) { mutableStateOf(false) }
    // A moment after it starts: YouTube's title and pause marks of the first second stay unseen.
    LaunchedEffect(started) { if (started) { kotlinx.coroutines.delay(1300); alpha.animateTo(1f, tween(600)) } }
    // Never stuck on a trailer that doesn't start.
    LaunchedEffect(req.rev) { kotlinx.coroutines.delay(15_000); if (!started && Trailers.playing.value?.rev == req.rev) Trailers.stop() }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val view = @Composable { m: Modifier, fill: Boolean ->
        AndroidView(
            factory = { c -> trailerWebView(c, req.ytId, st.trailerMute, st.trailerSubs, st.trailerSubLang, fill,
                onState = { stt -> main.post {
                    if (Trailers.playing.value?.rev != req.rev) return@post
                    if (stt == 1) { started = true; Trailers.started.value = true }
                    if (stt == 0) { Trailers.stop(); Trailers.endedRev.intValue++ }
                } },
                onError = { e -> main.post { Glog.w("Trailer ${req.ytId}: YouTube error $e"); if (Trailers.playing.value?.rev == req.rev) Trailers.stop() } }) },
            modifier = m,
            onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
        )
    }
    if (mode == "popup") {
        val (pos, size) = popupPlace(s, st.trailerPopupScale, 16f / 9f)
        val (w, h) = size
        val shape = RoundedCornerShape((14 * s).dp)
        Box(Modifier.offset { pos }.size(w, h).graphicsLayer { this.alpha = alpha.value }
            .clip(shape).background(Color.Black).border((1 * s).dp, Color.White.copy(alpha = 0.35f), shape)) {
            androidx.compose.runtime.key(req.rev) { view(Modifier.fillMaxSize(), false) }
        }
    } else {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.Black)) {
            androidx.compose.runtime.key(req.rev) { view(Modifier.fillMaxSize(), mode == "cinema") }
        }
    }
}

/**
 * Where the trailer's own window goes: next to the Play button, or next to the pop-up menu while one is open
 * (beside it, never under it).
 */
@Composable
private fun popupPlace(s: Float, scale: Float, aspect: Float): Pair<IntOffset, Pair<androidx.compose.ui.unit.Dp, androidx.compose.ui.unit.Dp>> {
    val density = LocalDensity.current
    // Shaped like the trailer: as wide as set, as tall as its picture (a very tall one is kept to a sensible height).
    val w = (300 * s * scale).dp
    val h = w / aspect.coerceIn(1.2f, 2.8f)
    val menu = PopAnchors.menu.value
    val anchor = menu ?: PopAnchors.play
    val x = with(density) { ((anchor?.right ?: (48 * s).dp.toPx()) + (18 * s).dp.toPx()).toInt() }
    val y = with(density) {
        val cy = if (menu != null) menu.top + h.toPx() / 2f else anchor?.center?.y ?: (200 * s).dp.toPx()
        (cy - h.toPx() / 2f).toInt().coerceAtLeast((20 * s).dp.roundToPx())
    }
    return IntOffset(x, y) to (w to h)
}

/**
 * Glassio's own player for the trailer: its picture fills the page (or its window) with nothing of YouTube on it,
 * the glass of the dock and menus shows it (live copies, as with a video background), and the subtitles are drawn
 * here. Any problem: YouTube's player takes over ([Trailers.toWeb]).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun NativeTrailer(app: GlassApp, s: Float, req: Trailers.Req, streams: com.glasslauncher.tv.data.TrailerSource.Streams, mode: String, dockInset: Float) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st = app.settings
    val popup = mode == "popup"
    var rendered by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(if (streams.height > 0) streams.width.toFloat() / streams.height else 16f / 9f) }
    var cues by remember { mutableStateOf("") }
    // Black bars inside the trailer's own picture (most trailers are wider than 16:9 and carry them): the share of
    // the height each one takes, found from the picture itself; -1 until known.
    var bar by remember { mutableStateOf(-1f) }
    val bars = remember { BarFinder() }
    val player = remember {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(streams.userAgent)
            // Short waits: a piece that hangs is asked for again at once (a long timeout froze the trailer for 13 s).
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(5_000).setReadTimeoutMs(5_000)
        // YouTube's streams read in pieces (one long download is slowed down after a few seconds).
        val chunked = androidx.media3.datasource.DataSource.Factory { com.glasslauncher.tv.data.YtChunkSource(http) }
        val prog = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(chunked)
        // The picture and the sound, played together (the subtitles are Glassio's own: TrailerSubs).
        val videoSrc = prog.createMediaSource(androidx.media3.common.MediaItem.fromUri(streams.video))
        val src = if (streams.audio != null && !st.trailerMute)
            androidx.media3.exoplayer.source.MergingMediaSource(videoSrc, prog.createMediaSource(androidx.media3.common.MediaItem.fromUri(streams.audio)))
            else videoSrc
        // Starts as soon as a little is in: a trailer is short, and waiting for a big buffer was the delay.
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(3_000, 30_000, 600, 1_500).build()
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
            setMediaSource(src)
            addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                override fun onLoadError(t: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, l: androidx.media3.exoplayer.source.LoadEventInfo,
                                         m: androidx.media3.exoplayer.source.MediaLoadData, e: java.io.IOException, cancelled: Boolean) {
                    Glog.w("Trailer ${req.ytId}: load ${e.javaClass.simpleName} ${e.message?.take(80)} (tried again)")
                }
            })
            volume = if (st.trailerMute || streams.audio == null) 0f else 1f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(Unit) {
        val t0 = android.os.SystemClock.uptimeMillis()
        var waitFrom = 0L
        val l = object : androidx.media3.common.Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!rendered) Glog.i("Trailer ${req.ytId}: on screen in ${android.os.SystemClock.uptimeMillis() - t0} ms (${streams.height}p)")
                rendered = true; Trailers.started.value = true; if (!popup) VideoFrames.showing.value = true }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { if (v.width > 0 && v.height > 0) aspect = v.width * v.pixelWidthHeightRatio / v.height }
            override fun onPlaybackStateChanged(state: Int) {
                val now = android.os.SystemClock.uptimeMillis()
                if (state == androidx.media3.common.Player.STATE_BUFFERING && rendered) waitFrom = now
                if (state == androidx.media3.common.Player.STATE_READY && waitFrom > 0) { Glog.w("Trailer ${req.ytId}: waited ${now - waitFrom} ms for data"); waitFrom = 0 }
                if (state == androidx.media3.common.Player.STATE_ENDED && Trailers.playing.value?.rev == req.rev) { Trailers.stop(); Trailers.endedRev.intValue++ }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                Glog.w("Trailer ${req.ytId}: own player ${e.errorCodeName}, YouTube's player instead")
                Trailers.toWeb(req)
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l); runCatching { player.release() }
            if (!popup) VideoFrames.clear()
        }
    }
    // Subtitles: loaded here and shown by the trailer's time.
    if (st.trailerSubs) LaunchedEffect(Unit) {
        val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.TrailerSubs.load(streams.subs, st.trailerSubLang) }
        if (got == null) { Glog.i("Trailer ${req.ytId}: no subtitles"); return@LaunchedEffect }
        Glog.i("Trailer ${req.ytId}: subtitles ${got.lang} (${got.lines.size} lines)")
        while (true) { cues = com.glasslauncher.tv.data.TrailerSubs.at(got.lines, player.currentPosition); kotlinx.coroutines.delay(120) }
    }
    // Never stuck on one that doesn't start.
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(12_000); if (!rendered) { Glog.w("Trailer ${req.ytId}: own player didn't start (state ${player.playbackState}), YouTube's player instead"); Trailers.toWeb(req) } }
    // The page's picture over the video until its first frame, then it fades into the trailer.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(rendered) { if (rendered) { var w = 0; while (bar < 0f && w < 1200) { kotlinx.coroutines.delay(50); w += 50 }; settled = true } }
    val show = rendered && (settled || mode == "original")
    val cover by androidx.compose.animation.core.animateFloatAsState(if (show) 0f else 1f, tween(if (show) 500 else 0), label = "cover")
    val crop by androidx.compose.animation.core.animateFloatAsState(bar.coerceAtLeast(0f), tween(if (settled) 600 else 0), label = "crop")
    val env = LocalGlassEnv.current
    val pic = env.sharp ?: env.bmp
    val subStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = ((if (popup) 9 else 17) * s).sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.9f), blurRadius = 8f))
    if (popup) {
        // The window takes the shape of the picture itself (its black bars left out).
        val content = aspect / (1f - 2f * crop)
        val (pos, size) = popupPlace(s, st.trailerPopupScale, content)
        val shape = RoundedCornerShape((14 * s).dp)
        val a by androidx.compose.animation.core.animateFloatAsState(if (show) 1f else 0f, tween(350), label = "popA")
        var tv by remember { mutableStateOf<android.view.TextureView?>(null) }
        LaunchedEffect(tv, rendered) {
            val t = tv ?: return@LaunchedEffect
            if (!rendered) return@LaunchedEffect
            findBars(bars, { t.getBitmap(160, 90) }) { bar = it }
        }
        // A TextureView: the window has round corners and fades (a SurfaceView can do neither).
        Box(Modifier.offset { pos }.size(size.first, size.second).graphicsLayer { alpha = a }
            .clip(shape).background(Color.Black).border((1 * s).dp, Color.White.copy(alpha = 0.35f), shape)) {
            AndroidView(factory = { c -> android.view.TextureView(c).also { tv = it; player.setVideoTextureView(it) } },
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center).requiredSize(size.first, size.first / aspect))
            if (cues.isNotBlank()) androidx.compose.material3.Text(cues, style = subStyle,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(horizontal = (8 * s).dp, vertical = (6 * s).dp))
        }
        return
    }
    // Which glass copies are made (as the video background does).
    listOf(st.dockStyle, st.tileStyle, st.menuStyle).let { l ->
        VideoFrames.wantSharp = l.any { it.startsWith("water") } || st.bgClock
        VideoFrames.wantSoft = l.any { it == "liquid" || it == "clear" }
        VideoFrames.softRadius = (st.blurRadius / 13).coerceIn(1, 6)
    }
    var view by remember { mutableStateOf<android.view.SurfaceView?>(null) }
    // Where the picture is on screen (cropped, or with bars), so the glass shows exactly what is behind it.
    val region = remember { arrayOfNulls<FrameRegion>(1) }
    LaunchedEffect(view) { val v = view ?: return@LaunchedEffect; copyVideoFrames(v, { rendered }) { region[0] } }
    if (mode == "cinema") LaunchedEffect(view, rendered) {
        val v = view ?: return@LaunchedEffect
        if (!rendered || android.os.Build.VERSION.SDK_INT < 26) return@LaunchedEffect
        val buf = android.graphics.Bitmap.createBitmap(160, 90, android.graphics.Bitmap.Config.ARGB_8888)
        findBars(bars, { pixelCopy(v, buf) }) { bar = it }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val sw = maxWidth; val sh = maxHeight
        val screenAspect = sw.value / sh.value
        val cinema = mode == "cinema"
        // Cinematic: the picture itself (without the black bars it carries) fills the screen, cropped at the edges;
        // Original: the whole picture with its bars.
        val c = if (cinema) 1f - 2f * crop else 1f
        val content = aspect / c
        val wide = content > screenAspect
        val cw = if (cinema == wide) sh * content else sw
        val ch = if (cinema == wide) sh else sw / content
        val vw = cw
        val vh = ch / c
        // The part of the picture on screen (in the surface's pixels), and where on screen it is.
        val dens = androidx.compose.ui.platform.LocalDensity.current.density
        region[0] = if (cinema) FrameRegion(android.graphics.Rect(
            (((vw - sw) / 2).value.coerceAtLeast(0f) * dens).toInt(), (((vh - sh) / 2).value.coerceAtLeast(0f) * dens).toInt(),
            (((vw + sw) / 2).value.coerceAtMost(vw.value) * dens).toInt(), (((vh + sh) / 2).value.coerceAtMost(vh.value) * dens).toInt()),
            android.graphics.RectF(0f, 0f, 1f, 1f))
        else FrameRegion(null, android.graphics.RectF(((sw - vw) / 2 / sw).coerceAtLeast(0f), ((sh - vh) / 2 / sh).coerceAtLeast(0f),
            ((sw + vw) / 2 / sw).coerceAtMost(1f), ((sh + vh) / 2 / sh).coerceAtMost(1f)))
        if (!cinema && rendered) Box(Modifier.fillMaxSize().background(Color.Black))
        AndroidView(factory = { c -> android.view.SurfaceView(c).also { view = it; player.setVideoSurfaceView(it) } },
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).requiredSize(vw, vh))
        if (pic != null && cover > 0.002f) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sc = maxOf(size.width / pic.width, size.height / pic.height)
            val dw = pic.width * sc; val dh = pic.height * sc
            drawImage(pic, dstOffset = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()), alpha = cover)
        }
        // The page's shades over the video, so the title, its details and the buttons stay easy to read.
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val full = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
            drawImage(Scrims.side, dstSize = full, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
            drawImage(Scrims.bottom, dstSize = full, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        }
        if (cues.isNotBlank()) androidx.compose.material3.Text(cues, style = subStyle,
            // Above the dock while it shows, at the bottom while it is away.
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(start = (120 * s).dp, end = (120 * s).dp, bottom = (44 * s + dockInset).dp))
    }
}

/** A small copy of a SurfaceView's picture, or null. Suspends until it is done. */
private suspend fun pixelCopy(v: android.view.SurfaceView, dst: android.graphics.Bitmap): android.graphics.Bitmap? {
    if (!v.holder.surface.isValid) return null
    return kotlinx.coroutines.suspendCancellableCoroutine { k ->
        runCatching {
            android.view.PixelCopy.request(v, dst, { r -> if (k.isActive) k.resumeWith(Result.success(if (r == android.view.PixelCopy.SUCCESS) dst else null)) },
                android.os.Handler(android.os.Looper.getMainLooper()))
        }.onFailure { if (k.isActive) k.resumeWith(Result.success(null)) }
    }
}

/** Looks at the picture now and then (often at first) for black bars; [onBars] gets the share of the height of each. */
private suspend fun findBars(f: BarFinder, grab: suspend () -> android.graphics.Bitmap?, onBars: (Float) -> Unit) {
    var n = 0
    while (true) {
        kotlinx.coroutines.delay(if (n < 20) 250L else 1500L)
        n++
        val b = grab() ?: continue
        val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { f.feed(b) }
        if (r != null) onBars(r)
    }
}

/**
 * Finds the black bars at the top and bottom of a trailer's picture: rows where nothing is lit. A frame counts only
 * when something in it is bright (a dark scene could look like bars), and the bars only ever get thinner (the
 * thinnest seen is the real one).
 */
private class BarFinder {
    private var best = -1f
    private var px = IntArray(0)
    fun feed(b: android.graphics.Bitmap): Float? {
        val w = b.width; val h = b.height
        if (px.size != w * h) px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        var top = -1; var last = -1; var bright = false
        for (y in 0 until h) {
            var mx = 0
            var x = 0
            while (x < w) {
                val p = px[y * w + x]
                val l = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
                if (l > mx) mx = l
                x += 2
            }
            if (mx > 90) bright = true
            if (mx > 34) { if (top < 0) top = y; last = y }
        }
        if (!bright || top < 0) return null
        var bar = minOf(top, h - 1 - last).toFloat() / h
        if (bar > 0.2f) return null
        if (bar < 0.025f) bar = 0f
        best = if (best < 0f) bar else minOf(best, bar)
        return best
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerWebView(
    c: android.content.Context, id: String, mute: Boolean, subs: Boolean, lang: String, fill: Boolean,
    onState: (Int) -> Unit, onError: (Int) -> Unit,
): WebView = WebView(c).apply {
    isFocusable = false
    isFocusableInTouchMode = false
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.javaScriptEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        // One tap on the page (not on the player) once it has loaded: the page then counts as touched, so the
        // trailer may get its sound (without it, turning the sound on paused the trailer).
        override fun onPageFinished(v: WebView, url: String?) {
            v.postDelayed({
                val t = android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { a ->
                    val e = android.view.MotionEvent.obtain(t, t, a, 2f, 2f, 0)
                    v.dispatchTouchEvent(e); e.recycle()
                }
            }, 300)
        }
    }
    addJavascriptInterface(TrailerBridge(onState, onError), "Glassio")
    // The player is sized by script from the real window size: "cover" crops it to fill like a backdrop,
    // otherwise the whole picture shows (centred, black bars). Started muted (allowed to play by itself), then
    // given its sound once it plays.
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;overflow:hidden;width:100%;height:100%}#p{position:absolute;pointer-events:none;border:0}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var player, cover=${if (fill) "true" else "false"}, wantSound=${if (mute) "false" else "true"}, sounded=false;
        function fit(){ var f=document.getElementById('p'); if(!f) return; var W=window.innerWidth, H=window.innerHeight; if(!W||!H) return;
          var w=W, h=W*9/16; if(cover){ if(h<H){ h=H; w=H*16/9; } w*=1.08; h*=1.08; } else { if(h>H){ h=H; w=H*16/9; } }
          f.style.width=w+'px'; f.style.height=h+'px'; f.style.left=((W-w)/2)+'px'; f.style.top=((H-h)/2)+'px'; }
        window.addEventListener('resize', fit);
        function onYouTubeIframeAPIReady(){
          player = new YT.Player('p',{videoId:'$id',width:'100%',height:'100%',
            playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,rel:0,playsinline:1,iv_load_policy:3,modestbranding:1,mute:1,
              cc_load_policy:${if (subs) 1 else 0},cc_lang_pref:'$lang',hl:'$lang',origin:'https://glassio.app'},
            events:{
              onReady:function(e){ fit(); e.target.mute(); e.target.playVideo(); },
              onStateChange:function(e){
                if(e.data==1 && wantSound && !sounded){ sounded=true; e.target.unMute(); e.target.setVolume(100); }
                // Nobody pauses it here: a pause (turning the sound on can cause one) is undone at once.
                if(e.data==2){ setTimeout(function(){ e.target.playVideo(); }, 50); }
                Glassio.state(e.data); },
              onError:function(e){ Glassio.error(e.data); }
            }});
          fit();
        }
        </script></body></html>
    """.trimIndent()
    loadDataWithBaseURL("https://glassio.app/", html, "text/html", "utf-8", null)
}
