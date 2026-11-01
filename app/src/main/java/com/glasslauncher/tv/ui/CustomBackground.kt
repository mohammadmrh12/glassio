package com.glasslauncher.tv.ui

import android.content.Context
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.viewinterop.AndroidView
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.Settings
import java.io.File
import kotlinx.coroutines.sync.withLock

/**
 * Your own picture or video behind the home screen instead of the titles' backdrops. A link (http…) or a file
 * in Glassio's backgrounds folder. The glass uses the picture (for a video: one frame of it).
 */
object CustomBackground {
    val exts = setOf("jpg", "jpeg", "png", "webp")
    /** Screensaver as background: the aerial video playing now (one per start of Glassio). */
    val aerial = androidx.compose.runtime.mutableStateOf<String?>(null)
    @Volatile var aerials: List<String> = emptyList()
    /** "Next" from the menu or Settings: bumped for the next video / picture (photos have their own). */
    val next = androidx.compose.runtime.mutableIntStateOf(0)
    fun nextNow(s: Settings) { if (s.bgMode == "photos") com.glasslauncher.tv.data.Photos.next() else next.intValue++ }

    /** My video: the chosen one first, then the other videos in the folder (in a random order with shuffle). */
    fun videoList(ctx: Context, s: Settings): List<String> {
        val chosen = s.bgVideo
        val others = files(ctx, true).map { it.absolutePath }.filter { it != chosen }
        return listOf(chosen) + if (s.bgVideoShuffle) others.shuffled() else others
    }

    /** My picture: the next one in the folder (a random other one with shuffle). */
    fun nextPicture(ctx: Context, s: Settings) {
        val files = files(ctx, false).map { it.absolutePath }
        if (files.isEmpty()) return
        val cur = files.indexOf(s.bgImage)
        s.bgImage = if (s.bgImageShuffle && files.size > 1) files.filterIndexed { i, _ -> i != cur }.random() else files[(cur + 1).mod(files.size)]
        s.bgMode = "image"
    }

    /** Each aerial video's place (shown over the background like on the screensaver). */
    @Volatile var aerialTitles: Map<String, String> = emptyMap()
    /** The place / title and the credit of what's behind the home screen now, or null. */
    fun info(s: Settings): Pair<String, String>? = when (s.bgMode) {
        "aerial" -> aerial.value?.let { aerialTitles[it] }?.takeIf { it.isNotBlank() }?.let { it to "" }
        "photos" -> com.glasslauncher.tv.data.Photos.current.value?.let { it.title to it.credit }
        else -> null
    }
    private var tries = 0
    /** First video of [list] that answers (Apple's feed still lists videos it has taken down). Blocking. */
    fun firstLive(list: List<String>): String? = list.take(12).firstOrNull { u ->
        if (u.startsWith("file://")) return@firstOrNull java.io.File(u.removePrefix("file://")).isFile
        runCatching {
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            c.requestMethod = "HEAD"; c.connectTimeout = 4000; c.readTimeout = 4000
            try { c.responseCode in 200..399 } finally { c.disconnect() }
        }.getOrDefault(false)
    }

    /** That video didn't play (network, codec): the next one, a few times. */
    fun nextAerial(ended: Boolean = false) {
        if (ended) tries = 0
        if (aerials.size < 2 || ++tries > 4) { Glog.w("Screensaver background: no aerial video plays here"); return }
        val rest = aerials.drop(aerials.indexOf(aerial.value) + 1) + aerials.take(aerials.indexOf(aerial.value).coerceAtLeast(0))
        Thread { firstLive(rest)?.let { aerial.value = it } }.apply { isDaemon = true }.start()
    }
    val videoExts = setOf("mp4", "mkv", "webm", "mov", "m4v")

    fun folder(ctx: Context): File = (ctx.getExternalFilesDir("backgrounds") ?: File(ctx.filesDir, "backgrounds")).apply { mkdirs() }

    fun files(ctx: Context, video: Boolean): List<File> =
        folder(ctx).listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in (if (video) videoExts else exts) }.sortedBy { it.name.lowercase() }

    /** What the player / image loader gets: links unchanged, files as file:// URIs. */
    fun uri(src: String): String = if (src.startsWith("http") || src.startsWith("file://")) src else "file://" + src

    /** Bumped when a video frame becomes ready (taking it from a link can take a while). */
    val frameRev = androidx.compose.runtime.mutableIntStateOf(0)
    private val working = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * The picture to use as the backdrop (and for the glass), or null for the carousel's own. A video's frame
     * that isn't ready yet is made in the background (the titles' backdrops show meanwhile).
     */
    fun still(ctx: Context, s: Settings): String? = when (s.bgMode) {
        "image" -> s.bgImage.takeIf { it.isNotBlank() }?.let { uri(it) }
        "photos" -> com.glasslauncher.tv.data.Photos.current.value?.url
        "video" -> s.bgVideo.takeIf { it.isNotBlank() }?.let { cachedFrame(ctx, it) }
        "aerial" -> aerial.value?.let { cachedFrame(ctx, it) }
        else -> null
    }

    private fun frameFile(ctx: Context, src: String) = File(ctx.cacheDir, "bgframe-" + Integer.toHexString(src.hashCode()) + ".jpg")

    private fun cachedFrame(ctx: Context, src: String): String? {
        val f = frameFile(ctx, src)
        if (f.length() > 0) return "file://" + f.absolutePath
        if (working.add(src)) Thread {
            try { if (frameOf(ctx, src) != null) frameRev.intValue++ } finally { working.remove(src) }
        }.apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        return null
    }

    /** One frame of the video (cached), so the glass has something to show. */
    private fun frameOf(ctx: Context, src: String): String? {
        val out = frameFile(ctx, src)
        if (out.length() > 0) return "file://" + out.absolutePath
        return runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                if (src.startsWith("http")) r.setDataSource(src, HashMap()) else r.setDataSource(src.removePrefix("file://"))
                val b = r.getFrameAtTime(1_500_000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: r.frameAtTime ?: return null
                out.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                b.recycle()
            } finally { r.release() }
            "file://" + out.absolutePath
        }.getOrElse { Glog.w("Background video frame", it); null }
    }
}

/**
 * Live copies of the background video for the glass: a small sharp one (clear glass) and a tiny one that,
 * scaled up, reads as frosted. Updated about 12 times a second while the video plays; read only while drawing.
 */
object VideoFrames {
    val sharp = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    val soft = mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    /** A video is on screen (the page's own picture under it isn't drawn then). */
    val showing = mutableStateOf(false)
    /** Bumped by [clear]: a copy finishing late (on its own thread) from a video that's gone is dropped. */
    @Volatile var gen = 0; private set
    @Synchronized fun clear() { gen++; sharp.value = null; soft.value = null; showing.value = false }
    @Synchronized fun publish(g: Int, s: androidx.compose.ui.graphics.ImageBitmap?, f: androidx.compose.ui.graphics.ImageBitmap?) {
        if (g != gen) return
        sharp.value = s; soft.value = f
    }
    /** A frosted-glass surface (Liquid, Clear) shows the frosted copy. */
    @Volatile var wantSoft = true
    /** A clear-glass theme shows the sharp copy (set from the settings); otherwise only the frosted one is made. */
    @Volatile var wantSharp = true
    @Volatile var interval = 66L
    /** Blur of the frosted copy (box radius at 160 px), from the Glass blur setting. */
    @Volatile var softRadius = 3
    /**
     * Shuffle: the background fades through black into the next video, like the screensaver. The glass darkens
     * with it (its copies of the video don't see the black layer on top of it). Survives the change of video.
     */
    val dim = androidx.compose.runtime.mutableFloatStateOf(0f)
}

/**
 * Makes the frosted copy of a video frame: blurred at 160 px (cheap: it runs ~10 times a second), then scaled up
 * to [w] × [h] with filtering, so on screen it is smooth with no blocks or steps (~2 ms on the TV).
 */
private class Softener(val w: Int, val h: Int) {
    private val sw = 160; private val sh = 90
    private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val small = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888)
    private val mid = android.graphics.Bitmap.createBitmap(240, 135, android.graphics.Bitmap.Config.ARGB_8888)
    private val px = IntArray(sw * sh)
    private val tmp = IntArray(sw * sh)
    private fun scale(src: android.graphics.Bitmap, dst: android.graphics.Bitmap) =
        android.graphics.Canvas(dst).drawBitmap(src, null, android.graphics.Rect(0, 0, dst.width, dst.height), paint)

    fun make(src: android.graphics.Bitmap, out: android.graphics.Bitmap, fromLarge: Boolean = false) {
        // Down in two filtered steps (one big step skips pixels and flickers), blurred, then up in two steps.
        if (fromLarge) { scale(src, mid); scale(mid, small) } else { scale(src, mid); scale(mid, small) }
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        repeat(3) { blurH(px, tmp); blurV(tmp, px) }
        small.setPixels(px, 0, sw, 0, 0, sw, sh)
        scale(small, mid); scale(mid, out)
    }

    private val r get() = VideoFrames.softRadius
    private fun blurH(src: IntArray, dst: IntArray) {
        val div = 2 * r + 1
        for (y in 0 until sh) {
            val row = y * sw
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = src[row + i.coerceIn(0, sw - 1)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (x in 0 until sw) {
                dst[row + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val o = src[row + (x - r).coerceAtLeast(0)]; val n = src[row + (x + r + 1).coerceAtMost(sw - 1)]
                sr += ((n shr 16) and 255) - ((o shr 16) and 255); sg += ((n shr 8) and 255) - ((o shr 8) and 255); sb += (n and 255) - (o and 255)
            }
        }
    }
    private fun blurV(src: IntArray, dst: IntArray) {
        val div = 2 * r + 1
        for (x in 0 until sw) {
            var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) { val c = src[i.coerceIn(0, sh - 1) * sw + x]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
            for (y in 0 until sh) {
                dst[y * sw + x] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val o = src[(y - r).coerceAtLeast(0) * sw + x]; val n = src[(y + r + 1).coerceAtMost(sh - 1) * sw + x]
                sr += ((n shr 16) and 255) - ((o shr 16) and 255); sg += ((n shr 8) and 255) - ((o shr 8) and 255); sb += (n and 255) - (o and 255)
            }
        }
    }
}

/**
 * The video(s), muted, with the page's shades on top. Paused while [playing] is false.
 *
 * One ExoPlayer with the whole [list] as its playlist, reading through the screensaver's video cache: the videos
 * the screensaver kept on the TV play from there at once, and the next one is fetched before this one ends.
 * With [advance] it goes to the next video near the end of each, fading through black like the screensaver
 * (the glass darkens with it); otherwise it loops the one it started on. [onItem] gets the video now playing.
 *
 * A SurfaceView: the video goes straight to the screen as its own layer (the display hardware puts it
 * together), so the GPU doesn't redraw the home screen for every video frame. The glass gets live copies
 * (PixelCopy, scaled on the GPU) about 15 times a second.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoBackground(list: List<String>, start: Int, playing: Boolean, advance: Boolean, onItem: (String) -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st = (ctx.applicationContext as com.glasslauncher.tv.GlassApp).settings
    var ready by remember { mutableStateOf(false) }
    var rendered by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(16f / 9f) }
    var view by remember { mutableStateOf<android.view.SurfaceView?>(null) }
    val dim = VideoFrames.dim
    val player = remember {
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build()
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(com.glasslauncher.tv.saver.VideoCache.factory(ctx, st.saverCache, st.saverCacheGb))
            .setLoadControl(load).build().apply {
                volume = 0f
                setMediaItems(list.map { androidx.media3.common.MediaItem.fromUri(CustomBackground.uri(it)) }, start.coerceIn(0, (list.size - 1).coerceAtLeast(0)), 0L)
                repeatMode = if (advance && list.size > 1) androidx.media3.common.Player.REPEAT_MODE_ALL else androidx.media3.common.Player.REPEAT_MODE_ONE
                prepare()
            }
    }
    // Back from another app the video's surface is new and black until the player draws again: the last frame
    // stands in for it (the glass shows that same frame), then fades out.
    var still by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var cover by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(cover, rendered, playing) { if (cover && rendered && playing) { kotlinx.coroutines.delay(300); cover = false } }
    val coverAlpha by androidx.compose.animation.core.animateFloatAsState(if (cover) 1f else 0f, androidx.compose.animation.core.tween(if (cover) 0 else 380), label = "cover")
    DisposableEffect(Unit) {
        Glog.i("Background video on: ${list.getOrNull(start)} (${list.size} in the list)")
        var errors = 0
        val l = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) aspect = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onRenderedFirstFrame() { rendered = true; ready = true; errors = 0; VideoFrames.showing.value = true }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                rendered = false
                val u = item?.localConfiguration?.uri?.toString() ?: return
                list.firstOrNull { CustomBackground.uri(it) == u }?.let { Glog.i("Background video: $it"); onItem(it) }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                Glog.w("Background video error ${e.errorCodeName}")
                // That one doesn't play (gone from the feed, network): the next one, a few times.
                if (++errors <= list.size.coerceAtMost(6) && list.size > 1) { player.seekToNextMediaItem(); player.prepare() }
            }
        }
        player.addListener(l)
        onDispose {
            Glog.i("Background video off")
            player.removeListener(l); runCatching { player.release() }
            VideoFrames.clear(); dim.floatValue = 0f
        }
    }
    androidx.compose.runtime.LaunchedEffect(playing) { player.playWhenReady = playing }
    // The fade in, once the first frame of a video is on screen.
    androidx.compose.runtime.LaunchedEffect(rendered) {
        if (rendered && dim.floatValue > 0f)
            androidx.compose.animation.core.animate(dim.floatValue, 0f, animationSpec = androidx.compose.animation.core.tween(1000)) { v, _ -> dim.floatValue = v }
    }
    // To the next video, like the screensaver: fade to black, the next one starts under it, then it fades in.
    val switching = remember { kotlinx.coroutines.sync.Mutex() }
    suspend fun toNext(fadeMs: Int) = switching.withLock {
        if (player.mediaItemCount < 2) return@withLock
        androidx.compose.animation.core.animate(dim.floatValue, 1f, animationSpec = androidx.compose.animation.core.tween(fadeMs)) { v, _ -> dim.floatValue = v }
        player.seekTo((player.currentMediaItemIndex + 1) % player.mediaItemCount, 0L)
        // The next one's first frame (from the cache: at once); not for ever, if it never comes.
        var w = 0
        while (!rendered && w < 10_000) { kotlinx.coroutines.delay(50); w += 50 }
        if (!rendered) androidx.compose.animation.core.animate(dim.floatValue, 0f, animationSpec = androidx.compose.animation.core.tween(600)) { v, _ -> dim.floatValue = v }
    }
    // Shuffle: near the end of each video.
    androidx.compose.runtime.LaunchedEffect(ready, playing) {
        if (!advance || list.size < 2 || !ready || !playing) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(100)
            val d = player.duration; val p = player.currentPosition
            if (d <= 2000 || p < d - 900) continue
            toNext(700)
        }
    }
    // "Next video" from the menu or Settings.
    val nextRev = CustomBackground.next.intValue
    val seenNext = remember { intArrayOf(nextRev) }
    androidx.compose.runtime.LaunchedEffect(nextRev) { if (nextRev != seenNext[0]) { seenNext[0] = nextRev; toNext(300) } }
    androidx.compose.runtime.LaunchedEffect(ready, playing, view) {
        val v = view ?: return@LaunchedEffect
        if (!ready || !playing || android.os.Build.VERSION.SDK_INT < 24) return@LaunchedEffect
        val myGen = VideoFrames.gen
        // One copy at a time, into buffers used in turn: a new copy never lands in the picture on screen
        // (that showed as flashes in the glass).
        val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
        // The frosted glass needs only a small copy: the GPU scales the video down in the copy itself (scaling
        // a full-size copy down on the CPU took ~15 ms every time). The sharp copy is made only when a clear
        // glass theme (Still Glass / Dynamic Glass) shows it.
        val sharpBufs = Array(4) { android.graphics.Bitmap.createBitmap(720, 405, android.graphics.Bitmap.Config.ARGB_8888) }
        val smallBufs = Array(2) { android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888) }
        val softBufs = Array(4) { android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888) }
        val soften = Softener(320, 180)
        // The copies finish on their own thread: on the main thread they held up the home screen while you moved.
        val worker = android.os.HandlerThread("glass-video-copy").apply { start() }
        val h = android.os.Handler(worker.looper)
        var k = 0
        try {
            while (true) {
                // Frosted glass only: 10 a second is plenty for a blurred picture (the clear glass needs 15).
                kotlinx.coroutines.delay(if (VideoFrames.wantSharp) VideoFrames.interval else 100L)
                if (!v.holder.surface.isValid || !rendered) continue
                if (!inFlight.compareAndSet(false, true)) continue
                val i = k++ % 4
                val wantSharp = VideoFrames.wantSharp; val wantSoft = VideoFrames.wantSoft
                val dst = if (wantSharp) sharpBufs[i] else smallBufs[i % 2]
                runCatching {
                    android.view.PixelCopy.request(v, dst, { r ->
                        try {
                            if (r != android.view.PixelCopy.SUCCESS || myGen != VideoFrames.gen) return@request
                            val soft = if (wantSoft || !wantSharp) softBufs[i].also { soften.make(dst, it, fromLarge = wantSharp) } else null
                            VideoFrames.publish(myGen, if (wantSharp) dst.asImageBitmap() else null, soft?.asImageBitmap())
                        } finally { inFlight.set(false) }
                    }, h)
                }.onFailure { inFlight.set(false) }
            }
        } finally {
            worker.quitSafely(); VideoFrames.clear()
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Centre crop: the surface is made as big as needed to fill the screen with the video's shape.
        val sw = maxWidth; val sh = maxHeight
        val screenAspect = sw.value / sh.value
        val vw = if (aspect > screenAspect) sh * aspect else sw
        val vh = if (aspect > screenAspect) sh else sw / aspect
        AndroidView(
            factory = { c ->
                android.view.SurfaceView(c).also { view = it }.apply {
                    player.setVideoSurfaceView(this)
                    holder.addCallback(object : android.view.SurfaceHolder.Callback {
                        override fun surfaceCreated(hd: android.view.SurfaceHolder) {}
                        override fun surfaceChanged(hd: android.view.SurfaceHolder, f: Int, w: Int, h: Int) {}
                        override fun surfaceDestroyed(hd: android.view.SurfaceHolder) {
                            // No copies for the glass until the video draws on the new surface (a copy of an empty
                            // surface is black: the glass went black after coming back).
                            rendered = false
                            (VideoFrames.sharp.value ?: VideoFrames.soft.value)?.let { f ->
                                runCatching { still = f.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false).asImageBitmap(); cover = true }
                            }
                        }
                    })
                }
            },
            modifier = Modifier.align(androidx.compose.ui.Alignment.Center).wrapContentSize(unbounded = true).requiredSize(vw, vh),
        )
        still?.let { b -> if (coverAlpha > 0.01f) androidx.compose.foundation.Image(b, null, Modifier.fillMaxSize().graphicsLayer { alpha = coverAlpha },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
        // The page's shades as one small picture painted once (dithered) and scaled up: drawn live as two
        // full-screen gradients over the video they cost the TV GPU 30 ms a frame, this costs about 2.
        if (DebugHide.intValue and 8192 == 0) androidx.compose.foundation.Image(Shades.get(), null, Modifier.fillMaxSize(),
            contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        // Shuffle: the fade through black between two videos.
        val dark by remember { androidx.compose.runtime.derivedStateOf { dim.floatValue > 0.003f } }
        if (dark) Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim.floatValue }.background(Color.Black))
    }
}

private object Shades {
    private var img: androidx.compose.ui.graphics.ImageBitmap? = null
    fun get(): androidx.compose.ui.graphics.ImageBitmap = img ?: run {
        val w = 480; val h = 270
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.62f, 0f, 0xB0000000.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), p)
        p.shader = android.graphics.LinearGradient(0f, h * 0.58f, 0f, h.toFloat(), 0, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.58f, w.toFloat(), h.toFloat(), p)
        b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap().also { img = it }
    }
}
