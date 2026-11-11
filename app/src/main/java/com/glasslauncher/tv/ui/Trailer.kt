package com.glasslauncher.tv.ui

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
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.asCoroutineDispatcher
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.Http

/**
 * Trailers in the carousel, like the Apple TV app: the title's trailer plays in place of its picture (filling the
 * screen, at its own size, or in a small glass window next to the buttons). The trailer is the title's YouTube
 * trailer as Cinemeta lists it, played by YouTube's own embedded player (a WebView that never takes the focus).
 */
object Trailers {
    /** [streams]: played by Glassio's own player; null = YouTube's player (a page) as before. */
    data class Req(val key: String, val ytId: String, val rev: Int, val streams: com.glasslauncher.tv.data.TrailerSource.Streams? = null,
                   /** Played over the Library (hold OK on a poster → Trailer): the whole screen, its own, never paused there. */
                   val overlay: Boolean = false)

    /** The trailer playing now, or null. */
    val playing = mutableStateOf<Req?>(null)
    /** Its picture is on screen (the dock and the page dots step aside then). */
    val started = mutableStateOf(false)
    /** ▼ pressed while the trailer hides the dock: the dock comes back (until the next trailer). */
    val dockAsked = mutableStateOf(false)
    /** Settings, the Library or another page is open: the trailer waits, and goes on when you are back home. */
    val held = mutableStateOf(false)
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

    /**
     * When Cinemeta lists no trailer: the title's videos on TMDB (with your TMDB token), trailers before teasers,
     * the official ones first. Blocking.
     */
    fun tmdbIds(app: GlassApp, type: String, imdb: String): List<String> {
        val token = app.settings.tmdbToken
        if (token.isBlank() || !imdb.startsWith("tt")) return emptyList()
        cache["tmdb:$imdb"]?.let { return it.split(',').filter { s -> s.isNotBlank() } }
        val tv = !type.equals("movie", true)
        val ids = runCatching {
            val f = app.http.json("https://api.themoviedb.org/3/find/$imdb?external_source=imdb_id", 7L * 24 * 3600 * 1000, token)
            val tvId = f.optJSONArray("tv_results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            val movieId = f.optJSONArray("movie_results")?.optJSONObject(0)?.optInt("id", 0) ?: 0
            val (kind, id) = when { tv && tvId > 0 -> "tv" to tvId; movieId > 0 -> "movie" to movieId; else -> "tv" to tvId }
            if (id == 0) return@runCatching emptyList()
            val v = app.http.json("https://api.themoviedb.org/3/$kind/$id/videos?include_video_language=en,ar,null", 24L * 3600 * 1000, token)
            val a = v.optJSONArray("results") ?: return@runCatching emptyList()
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
                .filter { it.optString("site").equals("YouTube", true) && it.optString("key").length == 11 && it.optString("type") in setOf("Trailer", "Teaser") }
                .sortedWith(compareBy({ if (it.optString("type") == "Trailer") 0 else 1 }, { if (it.optBoolean("official")) 0 else 1 }, { -it.optInt("size") }))
                .map { it.optString("key") }.distinct()
        }.onFailure { Glog.w("Trailer TMDB $imdb", it) }.getOrDefault(emptyList())
        cache["tmdb:$imdb"] = ids.joinToString(",")
        return ids
    }

    /** Last resort: YouTube's own search for "<title> <year> official trailer". Blocking. */
    fun searchIds(name: String, year: String, type: String): List<String> {
        if (name.isBlank()) return emptyList()
        val q = "$name ${year.take(4)} ${if (type.equals("movie", true)) "" else "series "}official trailer".replace(Regex("\\s+"), " ").trim()
        cache["yt:$q"]?.let { return it.split(',').filter { s -> s.isNotBlank() } }
        val ids = runCatching {
            val c = java.net.URL("https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8") + "&sp=EgIQAQ%253D%253D")
                .openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.8")
            c.setRequestProperty("Cookie", "CONSENT=YES+1")
            val html = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            Regex(""""videoRenderer":\{"videoId":"([A-Za-z0-9_-]{11})"""").findAll(html).map { it.groupValues[1] }.distinct().take(4).toList()
        }.onFailure { Glog.w("Trailer search '$q'", it) }.getOrDefault(emptyList())
        if (ids.isNotEmpty()) cache["yt:$q"] = ids.joinToString(",")
        return ids
    }

    /** Versions made for accessibility (a sign-language interpreter in the picture, audio description): skipped. */
    private val accessible = Regex("""\b(ASL|BSL|sign[- ]language|audio[- ]descri\w*|described video|lengua de se[ñn]as|langue des signes)\b""", RegexOption.IGNORE_CASE)

    fun play(key: String, ytId: String, streams: com.glasslauncher.tv.data.TrailerSource.Streams? = null, overlay: Boolean = false) {
        started.value = false; dockAsked.value = false; playing.value = Req(key, ytId, ++rev, streams, overlay)
    }
    fun stop() { playing.value = null; started.value = false; dockAsked.value = false }
    /**
     * One low-priority thread for finding trailers ahead: reading YouTube's page and its player script takes the
     * processor for a second or more, and on the normal threads it made the carousel and the start-up stutter.
     */
    val background: kotlinx.coroutines.CoroutineDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glass-trailers").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    /** Asks the home screen to start the trailer of the title on screen (the Play menu does the same; tests use it). */
    val request = mutableIntStateOf(0)

    /** Glassio's player couldn't play it: it stops (YouTube's own player, with its buttons and logo, is never used). */
    fun failed(r: Req) { if (playing.value?.rev == r.rev) { Glog.w("Trailer ${r.ytId}: couldn't play"); bad += r.ytId; stop() } }
    /** Trailers that wouldn't play this time: passed over for the title's next one. */
    private val bad = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

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
    fun prepare(app: GlassApp, type: String, imdb: String, name: String = "", year: String = "", fullOverride: Boolean? = null): Pair<String, com.glasslauncher.tv.data.TrailerSource.Streams?>? {
        // Always the best the source has that this TV can show (up to its screen, 4K on a 4K TV).
        val full = fullOverride ?: true
        val tried = HashSet<String>()
        // The first ordinary trailer that plays here (one with a sign-language interpreter or audio description,
        // or one YouTube won't show here, is passed over for the next).
        fun firstOf(ids: List<String>): Pair<String, com.glasslauncher.tv.data.TrailerSource.Streams>? {
            for (yt in ids.filterNot { it in bad || it in tried }.take(4)) {
                tried += yt
                val st = com.glasslauncher.tv.data.TrailerSource.resolve(yt, maxHeight(app, full), full) ?: continue
                if (accessible.containsMatchIn(st.title)) { Glog.i("Trailer $yt skipped: ${st.title}"); continue }
                return yt to st
            }
            return null
        }
        // Cinemeta first, then TMDB's videos, then a YouTube search by the title's name.
        firstOf(youtubeIds(app.http, type, imdb))?.let { return it }
        firstOf(tmdbIds(app, type, imdb))?.let { Glog.i("Trailer ${it.first}: from TMDB"); return it }
        firstOf(searchIds(name, year, type))?.let { Glog.i("Trailer ${it.first}: from a YouTube search for $name"); return it }
        return null
    }
    fun isPlaying(key: String?) = key != null && playing.value?.key == key
}

/**
 * The trailer layer of the home screen. [mode]: "cinema" fills the screen (cropped), "original" shows the whole
 * picture (black bars), "popup" is a small glass window next to the Play button.
 */
@Composable
fun TrailerLayer(app: GlassApp, s: Float, dockInset: Float = 0f) {
    val req = Trailers.playing.value ?: return
    if (req.overlay) return
    if (!onScreen()) return
    val st = app.settings
    val mode = st.trailerSize
    val streams = req.streams ?: return
    androidx.compose.runtime.key(req.rev) { NativeTrailer(app, s, req, streams, mode, dockInset) }
}

/** This home screen is the one on screen: a Glassio left behind another never plays the trailer too (double sound). */
@Composable
private fun onScreen(): Boolean {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var on by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(owner) {
        val o = androidx.lifecycle.LifecycleEventObserver { _, _ -> on = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        owner.lifecycle.addObserver(o)
        onDispose { owner.lifecycle.removeObserver(o) }
    }
    return on
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
    // Never past the screen's edges: at the largest size it went off the bottom.
    val screen = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val x = with(density) {
        val want = ((anchor?.right ?: (48 * s).dp.toPx()) + (18 * s).dp.toPx()).toInt()
        val max = screen.width - w.roundToPx() - (16 * s).dp.roundToPx()
        if (screen.width > 0) want.coerceAtMost(max).coerceAtLeast(0) else want
    }
    val y = with(density) {
        val cy = if (menu != null) menu.top + h.toPx() / 2f else anchor?.center?.y ?: (200 * s).dp.toPx()
        val top = (20 * s).dp.roundToPx()
        val max = screen.height - h.roundToPx() - (16 * s).dp.roundToPx()
        (cy - h.toPx() / 2f).toInt().let { if (screen.height > 0) it.coerceAtMost(max) else it }.coerceAtLeast(top)
    }
    return IntOffset(x, y) to (w to h)
}

/**
 * Glassio's own player for the trailer: its picture fills the page (or its window) with nothing of YouTube on it,
 * the glass of the dock and menus shows it (live copies, as with a video background), and the subtitles are drawn
 * here. Any problem: it stops ([Trailers.failed]); YouTube's own player is never shown.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun NativeTrailer(app: GlassApp, s: Float, req: Trailers.Req, streams: com.glasslauncher.tv.data.TrailerSource.Streams, mode: String, dockInset: Float,
                          /** The screensaver's own: its sound setting, no home-screen glass, and it is told when the trailer ends or fails. */
                          muteOverride: Boolean? = null, glass: Boolean = true, onEnded: (() -> Unit)? = null, onFailed: (() -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st = app.settings
    val mute = muteOverride ?: st.trailerMute
    val popup = mode == "popup"
    var rendered by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(if (streams.height > 0) streams.width.toFloat() / streams.height else 16f / 9f) }
    var cues by remember { mutableStateOf("") }
    // The video's own size: the surface's pictures are in it (not in the size it has on screen).
    var vid by remember { mutableStateOf(streams.width to streams.height) }
    // Black bars inside the trailer's own picture (most trailers are wider than 16:9 and carry them): the share of
    // the height each one takes, found from the picture itself; -1 until known.
    var bar by remember { mutableStateOf(-1f) }
    val bars = remember { BarFinder() }
    val player = remember {
        val http = androidx.media3.datasource.DefaultHttpDataSource.Factory().setUserAgent(streams.userAgent)
            // Short waits: a piece that hangs is asked for again at once (a long timeout froze the trailer for 13 s).
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(4_000).setReadTimeoutMs(3_500)
        // YouTube's streams read in pieces (one long download is slowed down after a few seconds).
        val chunked = androidx.media3.datasource.DataSource.Factory { com.glasslauncher.tv.data.YtChunkSource(http) }
        val prog = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(chunked)
        // The picture and the sound, played together (the subtitles are Glassio's own: TrailerSubs).
        val videoSrc = prog.createMediaSource(androidx.media3.common.MediaItem.fromUri(streams.video))
        val src = if (streams.audio != null && !mute)
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
            volume = if (mute || streams.audio == null) 0f else 1f
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
                rendered = true; if (glass) { Trailers.started.value = true; if (!popup) VideoFrames.showing.value = true } }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) { if (v.width > 0 && v.height > 0) { aspect = v.width * v.pixelWidthHeightRatio / v.height; vid = v.width to v.height } }
            override fun onPlaybackStateChanged(state: Int) {
                val now = android.os.SystemClock.uptimeMillis()
                if (state == androidx.media3.common.Player.STATE_BUFFERING && rendered) waitFrom = now
                if (state == androidx.media3.common.Player.STATE_READY && waitFrom > 0) { Glog.w("Trailer ${req.ytId}: waited ${now - waitFrom} ms for data"); waitFrom = 0 }
                if (state == androidx.media3.common.Player.STATE_ENDED && onEnded != null) onEnded()
                else if (state == androidx.media3.common.Player.STATE_ENDED && Trailers.playing.value?.rev == req.rev) { Trailers.stop(); if (!req.overlay) Trailers.endedRev.intValue++ }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                Glog.w("Trailer ${req.ytId}: own player ${e.errorCodeName}")
                if (onFailed != null) onFailed() else Trailers.failed(req)
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l); runCatching { player.release() }
            if (!popup && glass) VideoFrames.clear()
        }
    }
    LaunchedEffect(Trailers.held.value) { player.playWhenReady = req.overlay || !Trailers.held.value }
    // Subtitles: loaded here and shown by the trailer's time.
    if (st.trailerSubs) LaunchedEffect(Unit) {
        val got = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.TrailerSubs.load(streams.subs, st.trailerSubLang) }
        if (got == null) { Glog.i("Trailer ${req.ytId}: no subtitles"); return@LaunchedEffect }
        Glog.i("Trailer ${req.ytId}: subtitles ${got.lang} (${got.lines.size} lines)")
        while (true) { cues = com.glasslauncher.tv.data.TrailerSubs.at(got.lines, player.currentPosition); kotlinx.coroutines.delay(120) }
    }
    // Never stuck on one that doesn't start.
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(12_000); if (!rendered) { Glog.w("Trailer ${req.ytId}: own player didn't start (state ${player.playbackState})"); if (onFailed != null) onFailed() else Trailers.failed(req) } }
    // The page's picture over the video until its first frame, then it fades into the trailer.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(rendered) { if (rendered) { var w = 0; while (bar < 0f && w < 500) { kotlinx.coroutines.delay(50); w += 50 }; settled = true } }
    // Its window shows with the first frame (it waited up to 1.2 s for the black bars to be measured first).
    val show = rendered && (settled || mode == "original" || popup)
    val cover by androidx.compose.animation.core.animateFloatAsState(if (show) 0f else 1f, tween(if (show) 500 else 0), label = "cover")
    val crop by androidx.compose.animation.core.animateFloatAsState(bar.coerceAtLeast(0f), tween(if (settled) 600 else 0), label = "crop")
    val env = LocalGlassEnv.current
    val pic = env.sharp ?: env.bmp
    val subStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = ((if (popup) 9 else 17) * s * st.trailerSubScale).sp,
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
        // Never fully see-through: a layer at 0 isn't drawn, so its TextureView got no surface and the trailer never
        // started (the first one after opening the home screen stayed invisible).
        Box(Modifier.offset { pos }.size(size.first, size.second).graphicsLayer { alpha = maxOf(a, 0.004f) }
            .clip(shape).background(Color.Black).border((1 * s).dp, Color.White.copy(alpha = 0.35f), shape)) {
            AndroidView(factory = { c -> android.view.TextureView(c).also { tv = it; it.keepScreenOn = true; player.setVideoTextureView(it) } },
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center).requiredSize(size.first, size.first / aspect))
            if (cues.isNotBlank()) androidx.compose.material3.Text(cues, style = subStyle,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(horizontal = (8 * s).dp, vertical = (6 * s).dp))
        }
        return
    }
    // The glass follows the trailer about 30 times a second (the video background's 15 looked jerky behind the
    // menus); small TVs keep 15.
    if (glass) DisposableEffect(Unit) {
        VideoFrames.interval = if (Trailers.maxHeight(ctx, false) >= 1080) 33L else 66L
        // Back to the video background's rate (a fixed value: two trailers handing over could leave it at 30).
        onDispose { VideoFrames.interval = 66L }
    }
    // Which glass copies are made (as the video background does).
    if (glass) listOf(st.dockStyle, st.tileStyle, st.menuStyle).let { l ->
        VideoFrames.wantSharp = l.any { it.startsWith("water") } || st.bgClock
        VideoFrames.wantSoft = l.any { it == "liquid" || it == "clear" }
        VideoFrames.softRadius = (st.blurRadius / 13).coerceIn(1, 6)
    }
    var view by remember { mutableStateOf<android.view.SurfaceView?>(null) }
    // Where the picture is on screen (cropped, or with bars), so the glass shows exactly what is behind it.
    val region = remember { arrayOfNulls<FrameRegion>(1) }
    LaunchedEffect(view) { if (!glass) return@LaunchedEffect; val v = view ?: return@LaunchedEffect; copyVideoFrames(v, { rendered }) { region[0] } }
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
        // The part of the picture on screen, and where on screen it is. The copy is asked for in the video's own
        // pixels (the surface holds the decoded frames at their size): asked in screen pixels, the glass showed
        // another part of the trailer than the one behind it.
        val (fw, fh) = vid
        region[0] = if (cinema && fw > 0 && fh > 0) {
            val x0 = ((vw - sw) / 2 / vw).coerceIn(0f, 1f); val y0 = ((vh - sh) / 2 / vh).coerceIn(0f, 1f)
            FrameRegion(android.graphics.Rect((x0 * fw).toInt(), (y0 * fh).toInt(), ((1f - x0) * fw).toInt(), ((1f - y0) * fh).toInt()),
                android.graphics.RectF(0f, 0f, 1f, 1f))
        }
        else FrameRegion(null, android.graphics.RectF(((sw - vw) / 2 / sw).coerceAtLeast(0f), ((sh - vh) / 2 / sh).coerceAtLeast(0f),
            ((sw + vw) / 2 / sw).coerceAtMost(1f), ((sh + vh) / 2 / sh).coerceAtMost(1f)))
        if (!cinema && rendered) Box(Modifier.fillMaxSize().background(Color.Black))
        AndroidView(factory = { c -> android.view.SurfaceView(c).also { view = it; it.keepScreenOn = true; player.setVideoSurfaceView(it) } },
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).requiredSize(vw, vh))
        if (!req.overlay && pic != null && cover > 0.002f) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val sc = maxOf(size.width / pic.width, size.height / pic.height)
            val dw = pic.width * sc; val dh = pic.height * sc
            drawImage(pic, dstOffset = IntOffset(((size.width - dw) / 2).toInt(), ((size.height - dh) / 2).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize(dw.toInt(), dh.toInt()), alpha = cover)
        }
        // The page's shades over the video, so the title, its details and the buttons stay easy to read.
        if (!req.overlay) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val full = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
            drawImage(Scrims.side, dstSize = full, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
            drawImage(Scrims.bottom, dstSize = full, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        }
        if (cues.isNotBlank()) androidx.compose.material3.Text(cues, style = subStyle,
            // Above the dock while it shows, at the bottom while it is away.
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(start = (120 * s).dp, end = (120 * s).dp, bottom = (44 * s + dockInset).dp))
    }
}

/**
 * A trailer over the Library (hold OK on a poster → Trailer): the whole picture on black, over everything. The
 * Library keeps the focus where it was; Back (or the end) stops it.
 */
@Composable
fun LibraryTrailer(app: GlassApp, s: Float) {
    val req = Trailers.playing.value?.takeIf { it.overlay } ?: return
    if (!onScreen()) return
    val streams = req.streams ?: return
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.runtime.key(req.rev) { NativeTrailer(app, s, req, streams, "original", 0f) }
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
        if (bar < 0.006f) bar = 0f
        best = if (best < 0f) bar else minOf(best, bar)
        return best
    }
}


/**
 * Screensaver "Trailers": the carousel's titles play their trailers one after another (shuffled), each with its
 * logo and details like the carousel for a few seconds. OK opens the title in Nuvio, held OK a menu like Play's,
 * ◀ ▶ the next or the one before.
 */
@Composable
fun SaverTrailers(app: GlassApp) {
    val st = app.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val raw by app.catalogs.items.collectAsState()
    val enriched by app.catalogs.enrichedItems.collectAsState()
    val pool = remember(raw.size) { raw.filter { it.id.startsWith("tt") }.shuffled() }
    val full = true
    // Where we are in the list, and what plays.
    var at by remember { mutableIntStateOf(0) }
    var want by remember { mutableIntStateOf(0) }
    var dir by remember { mutableIntStateOf(1) }
    var tick by remember { mutableIntStateOf(0) }
    var cur by remember { mutableStateOf<Pair<com.glasslauncher.tv.data.HeroItem, Trailers.Req>?>(null) }
    var none by remember { mutableStateOf(false) }
    var rev by remember { mutableIntStateOf(0) }
    var pop by remember { mutableStateOf<PopState?>(null) }
    var muted by remember { mutableStateOf(st.saverTrailerMute) }
    fun item(i: Int) = pool[i.mod(pool.size)].let { enriched[it.key] ?: it }
    fun go(d: Int) { dir = if (d < 0) -1 else 1; want = at + dir; tick++ }
    LaunchedEffect(tick, pool.size) {
        if (pool.isEmpty()) return@LaunchedEffect
        var i = want
        var found: Pair<Int, Pair<com.glasslauncher.tv.data.HeroItem, Pair<String, com.glasslauncher.tv.data.TrailerSource.Streams>>>? = null
        for (n in 0 until minOf(pool.size, 12)) {
            val it = item(i)
            val got = kotlinx.coroutines.withContext(Trailers.background) { runCatching { Trailers.prepare(app, it.type, it.id, it.name, it.year, full) }.getOrNull() }
            val sts = got?.second
            if (got != null && sts != null) { found = i to (it to (got.first to sts)); break }
            i += dir
        }
        // Nothing playable (the internet gone, or no trailers): the last trailer isn't left frozen on screen.
        val f = found ?: run { none = true; cur = null; return@LaunchedEffect }
        none = false
        at = f.first
        val (it, yt) = f.second
        cur = it to Trailers.Req("saver:" + it.key, yt.first, ++rev, yt.second, overlay = true)
        com.glasslauncher.tv.saver.TrailerSaver.rev.intValue++
        // The next one gets ready while this one plays.
        val n = item(at + dir)
        kotlinx.coroutines.withContext(Trailers.background) { runCatching { Trailers.prepare(app, n.type, n.id, n.name, n.year, full) } }
    }
    // ◀ ▶ from the remote (the screensaver reads the keys and leaves the step here).
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(100)
            val k = com.glasslauncher.tv.saver.SaverControl.step
            if (k != 0) { com.glasslauncher.tv.saver.SaverControl.step = 0; go(k) }
        }
    }
    // Online or not, looked at every few seconds: without the internet the screensaver says so (not "no trailers"),
    // and starts again by itself when it is back.
    var online by remember { mutableStateOf(isOnline(ctx)) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(3000); online = isOnline(ctx) } }
    LaunchedEffect(online) { if (online && cur == null && pool.isNotEmpty()) { want = at; tick++ } }
    fun openIt(it: com.glasslauncher.tv.data.HeroItem) {
        com.glasslauncher.tv.data.NuvioLaunch.open(ctx, st, app.http, it)
        com.glasslauncher.tv.saver.SaverControl.afterOpen?.invoke()
    }
    fun menuFor(it: com.glasslauncher.tv.data.HeroItem) {
        val inLib = app.library.has(it.key)
        val liked = st.liked(it.key)
        pop = PopState(it.name, listOf(
            PopOption("Open in Nuvio") { openIt(it) },
            PopOption("Next trailer") { go(1) },
            PopOption(if (inLib) "Remove from Library" else "Add to Library", if (inLib) "✓" else "") {
                val now = app.library.toggle(it)
                toast(ctx, if (now) "Saved to Library · ${it.name}" else "Removed from Library · ${it.name}")
            },
            PopOption(if (liked) "Liked" else "Like", if (liked) "♥" else "") {
                val now = app.library.toggleLike(it)
                toast(ctx, if (now) "Liked · ${it.name}" else "Like removed · ${it.name}")
            },
            PopOption(if (muted) "Sound on" else "Sound off") { muted = !muted; st.saverTrailerMute = muted; cur = cur?.let { c -> c.first to c.second.copy(rev = ++rev) } },
        ), null)
    }
    // OK and held OK, read by the screensaver's key handling.
    DisposableEffect(Unit) {
        com.glasslauncher.tv.saver.SaverControl.onOk = { cur?.first?.let { openIt(it) } }
        com.glasslauncher.tv.saver.SaverControl.onLong = { cur?.first?.let { menuFor(it) } }
        onDispose {
            com.glasslauncher.tv.saver.SaverControl.onOk = null; com.glasslauncher.tv.saver.SaverControl.onLong = null
            com.glasslauncher.tv.saver.SaverControl.popOpen.value = false
        }
    }
    LaunchedEffect(pop) { com.glasslauncher.tv.saver.SaverControl.popOpen.value = pop != null }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val s = maxWidth.value / 960f
        val c = cur
        val infoOn = st.saverTrailerLogo || st.saverTrailerMeta || st.saverTrailerSynopsis
        // The details show for a while after each trailer starts (or always).
        var shown by remember { mutableStateOf(true) }
        LaunchedEffect(c?.second?.rev, st.saverTrailerInfoSec) {
            shown = true
            if (st.saverTrailerInfoSec > 0) { kotlinx.coroutines.delay(st.saverTrailerInfoSec * 1000L); shown = false }
        }
        // The subtitles sit above the details while they show (both were at the bottom and covered each other); at
        // the bottom of the picture otherwise, also in Cinematic where the picture is cropped.
        val subsUp by androidx.compose.animation.core.animateFloatAsState(
            if (infoOn && shown) (if (st.saverTrailerSynopsis) 150f else 105f) * s else 0f, tween(500), label = "saverSubs")
        if (c != null) androidx.compose.runtime.key(c.second.rev) {
            val streams = c.second.streams
            if (streams != null) NativeTrailer(app, s, c.second, streams, if (st.saverTrailerSize == "original") "original" else "cinema", subsUp,
                muteOverride = muted, glass = false, onEnded = { go(1) }, onFailed = { go(dir) })
        }
        // The first trailer is still being found (a few seconds the first time): the title's picture meanwhile,
        // instead of a black screen.
        if (c == null && !none && pool.isNotEmpty()) {
            val bg = item(want).background
            if (bg.isNotBlank()) coil3.compose.AsyncImage(bg, null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.85f },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        }
        if (c == null && (none || pool.isEmpty() || !online)) DriftingNote(
            when { !online -> "No internet connection\nThe trailers come back when it's back"; pool.isEmpty() -> "No titles in the carousel yet"; else -> "No trailers found" }, s)
        // The title's logo and details, like the carousel, for a few seconds after each trailer starts.
        if (c != null && infoOn) {
            val a by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 500 else 900), label = "saverInfo")
            if (a > 0.01f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = a }) {
                // A soft shade at the bottom left, so the words read over any picture.
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)))
                    drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.45f), 0.55f to Color.Transparent))
                }
                val it = c.first
                androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(start = (48 * s).dp, bottom = (44 * s).dp, end = (48 * s).dp).width((440 * s).dp)) {
                    if (st.saverTrailerLogo) Title(it, if (st.titleMode == "hidden") "logo" else st.titleMode, st.titleScale, s)
                    if (st.saverTrailerMeta) {
                        androidx.compose.foundation.layout.Spacer(Modifier.size((12 * s).dp))
                        androidx.compose.foundation.layout.Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((6 * s).dp)) {
                            if (it.rating.isNotBlank()) Chip("★ ${it.rating}", s)
                            if (it.year.isNotBlank()) Chip(it.year, s)
                            if (it.platform.isNotBlank()) Chip(it.platform, s)
                        }
                        val kind = if (it.type.equals("movie", true)) "Movie" else "Series"
                        androidx.compose.foundation.layout.Spacer(Modifier.size((8 * s).dp))
                        androidx.compose.material3.Text((listOf(kind) + it.genres).joinToString("  ·  "), color = Color.White.copy(alpha = 0.7f),
                            fontSize = (11 * s).sp, maxLines = 1)
                    }
                    if (st.saverTrailerSynopsis && it.overview.isNotBlank()) {
                        androidx.compose.foundation.layout.Spacer(Modifier.size((8 * s).dp))
                        androidx.compose.material3.Text(it.overview, color = Color.White.copy(alpha = 0.75f), fontSize = (11.5f * s).sp,
                            lineHeight = (16 * s).sp, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // The menu (held OK) in the screensaver's own glass.
        val env = remember(constraints.maxWidth, constraints.maxHeight, st.blurRadius) {
            val blank = androidx.compose.ui.graphics.ImageBitmap(1, 1)
            GlassEnv(blank, blank, blank, constraints.maxWidth, constraints.maxHeight, st.glassTint, st.refraction, blurDp = st.blurRadius * 0.45f)
        }
        val envRef = androidx.compose.runtime.rememberUpdatedState(env)
        androidx.compose.runtime.CompositionLocalProvider(LocalGlassEnv provides env, LocalGlassEnvRef provides envRef) {
            PopMenuHost(pop, s, st.menuStyle) { pop = null }
        }
    }
}


/** Connected to the internet (checked, not only a network). */
private fun isOnline(ctx: android.content.Context): Boolean = runCatching {
    val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
    caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}.getOrDefault(true)

/**
 * A note on the screensaver that never stays still (on OLED screens still text burns in): every few seconds it fades
 * out and comes back somewhere else.
 */
@Composable
private fun DriftingNote(text: String, s: Float) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        var spot by remember { mutableStateOf(0.5f to 0.5f) }
        val a = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                spot = (0.18f + kotlin.random.Random.nextFloat() * 0.64f) to (0.15f + kotlin.random.Random.nextFloat() * 0.7f)
                a.animateTo(1f, tween(900))
                kotlinx.coroutines.delay(8000)
                a.animateTo(0f, tween(900))
            }
        }
        var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        androidx.compose.material3.Text(text, color = Color.White.copy(alpha = 0.6f), fontSize = (14 * s).sp, lineHeight = (20 * s).sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.onSizeChanged { size = it }
                .offset { IntOffset((spot.first * w - size.width / 2f).toInt().coerceIn(0, (w - size.width).toInt().coerceAtLeast(0)),
                    (spot.second * h - size.height / 2f).toInt().coerceIn(0, (h - size.height).toInt().coerceAtLeast(0))) }
                .graphicsLayer { alpha = a.value })
    }
}
