package com.glasslauncher.tv.ui

import com.glasslauncher.tv.data.Bg
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
    /**
     * The TV's sleep time came with nobody at the remote: the video stands still. A playing video kept Google TV from
     * starting its screensaver and from sleeping (seen: the TV's own Settings started it at 120 s, the home screen with
     * a video never). Any key or touch plays it again.
     */
    val resting = androidx.compose.runtime.mutableStateOf(false)
    /** The background video Glassio left (another app or the screensaver came in front) and where it was in it. */
    @Volatile var leftVideo: String? = null
    @Volatile var leftPos = 0L
    /** Screensaver as background: the aerial video playing now (one per start of Glassio). */
    val aerial = androidx.compose.runtime.mutableStateOf<String?>(null)
    @Volatile var aerials: List<String> = emptyList()
    /** "Next" from the menu or Settings: bumped for the next video / picture (photos have their own). */
    val next = androidx.compose.runtime.mutableIntStateOf(0)
    /** The video before (◀ on the screensaver on the home screen). */
    val back = androidx.compose.runtime.mutableIntStateOf(0)
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
        Bg.io("glass-aerial") { firstLive(rest)?.let { aerial.value = it } }
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

    private fun frameFile(ctx: Context, src: String) = File(ctx.cacheDir, "bgframe2-" + Integer.toHexString(src.hashCode()) + ".jpg")

    private fun cachedFrame(ctx: Context, src: String): String? {
        val f = frameFile(ctx, src)
        if (f.length() > 0) return "file://" + f.absolutePath
        // A video's frame is taken from the player's own picture once it plays ([saveStill]), never decoded again
        // here: reading it with MediaMetadataRetriever opened a second hardware decoder for the same video while the
        // player opened its own, and on an Amlogic box (UGOOS AM9 Pro, #31) the switch to an HEVC video with both at
        // once was the last thing in its log before a kernel panic. Only YouTube's still (a download) is fetched.
        if (com.glasslauncher.tv.saver.VideoThumbs.youtube(src) == null) return null
        if (working.add(src)) Bg.io("glass-video-frame") {
            try { if (frameOf(ctx, src) != null) frameRev.intValue++ } finally { working.remove(src) }
        }
        return null
    }

    /** Keeps [bmp] (a copy of the playing video's picture) as [src]'s still, once. */
    fun saveStill(ctx: Context, src: String, bmp: android.graphics.Bitmap) {
        val out = frameFile(ctx, src)
        if (out.length() > 0) return
        if (looksEmpty(bmp)) { Glog.w("Background video still: the copy was empty (one colour), not kept"); return }
        Bg.io("glass-video-frame") {
            runCatching {
                val tmp = File(out.parentFile, out.name + ".part")
                tmp.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it) }
                tmp.renameTo(out); frameRev.intValue++
                Glog.i("Background video still: from its own picture")
            }.onFailure { Glog.w("Background video frame", it) }
        }
    }

    /** One frame of the video (cached), so the glass has something to show. */
    private fun frameOf(ctx: Context, src: String): String? {
        val out = frameFile(ctx, src)
        if (out.length() > 0) return "file://" + out.absolutePath
        // A YouTube link is its page, not a video file: YouTube's own still (no frame can be read from a page, and it
        // was tried again every few seconds).
        com.glasslauncher.tv.saver.VideoThumbs.youtube(src)?.let { yt ->
            val bytes = listOf(yt.replace("mqdefault", "maxresdefault"), yt).firstNotNullOfOrNull { u ->
                runCatching { java.net.URL(u).openStream().use { it.readBytes() } }.getOrNull()?.takeIf { it.size > 2_000 }
            } ?: return null.also { Glog.w("Background video frame: no YouTube still for $src") }
            return runCatching { out.writeBytes(bytes); "file://" + out.absolutePath }.getOrNull()
        }
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
    /**
     * The screensaver has closed: its copies are let go at once and none of its loops may publish again for a
     * moment. Its own clean-up only runs when its screen is destroyed, which is after the home screen is back: until
     * then the home screen took a video to be behind it, drew no carousel picture (black for ~0.4 s) and its glass
     * showed the screensaver's last frame. A time, not a switch: nothing can leave the glass frozen for good.
     */
    @Volatile var quietUntil = 0L
    fun saverClosed() { quietUntil = android.os.SystemClock.uptimeMillis() + 3000; clearForHome() }
    /** The home screen has a video background of its own (set while its player exists). */
    @Volatile var homeVideo = false
    /** Bumped each time the home screen's video draws its first frame (on a new surface too). */
    val drawnRev = androidx.compose.runtime.mutableIntStateOf(0)
    /**
     * The screensaver's copies are no longer wanted. With a video background of the home screen's own, the copies
     * stay until its own next one replaces them (the same video, with *Same video*): cleared, the whole home
     * screen changed to no video behind and back within a moment, twice on every return from the screensaver
     * (once as it closed, once when its screen was destroyed): two full rebuilds of every glass surface, the heavy
     * return he reported.
     */
    @Synchronized fun clearForHome() { if (homeVideo) gen++ else clear() }
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
    // A strong blur works at 160 px (cheap); a light one at 240 px with twice the radius (a little crisper): blurred
    // by a pixel at 160 px and scaled up twelve times, its pixels showed as steps in the glass.
    private val coarse = Pass(160, 90, 1)
    private val fine = Pass(240, 136, 2)
    fun make(src: android.graphics.Bitmap, out: android.graphics.Bitmap, fromLarge: Boolean = false) =
        (if (VideoFrames.softRadius <= 2) fine else coarse).make(src, out)

    private class Pass(val sw: Int, val sh: Int, val k: Int) {
        private val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        private val small = android.graphics.Bitmap.createBitmap(sw, sh, android.graphics.Bitmap.Config.ARGB_8888)
        private val mid = android.graphics.Bitmap.createBitmap(sw * 3 / 2, sh * 3 / 2, android.graphics.Bitmap.Config.ARGB_8888)
        private val px = IntArray(sw * sh)
        private val tmp = IntArray(sw * sh)
        private fun scale(src: android.graphics.Bitmap, dst: android.graphics.Bitmap) =
            android.graphics.Canvas(dst).drawBitmap(src, null, android.graphics.Rect(0, 0, dst.width, dst.height), paint)

        fun make(src: android.graphics.Bitmap, out: android.graphics.Bitmap) {
            // Down in two filtered steps (one big step skips pixels and flickers), blurred, then up in two steps.
            if (src.width >= mid.width * 3 / 2) { scale(src, mid); scale(mid, small) } else scale(src, small)
            small.getPixels(px, 0, sw, 0, 0, sw, sh)
            repeat(3) { blurH(px, tmp); blurV(tmp, px) }
            small.setPixels(px, 0, sw, 0, 0, sw, sh)
            if (out.width > mid.width) { scale(small, mid); scale(mid, out) } else scale(small, out)
        }

        private val r get() = VideoFrames.softRadius * k
        // (Fixed-point multiply instead of a division per pixel, and the edges handled outside the inner loop: this
        // runs ~10 times a second on every video background and screensaver; the plain version took a third of a core.)
        private fun blurH(src: IntArray, dst: IntArray) {
            val r = r; val mul = (1 shl 16) / (2 * r + 1); val last = sw - 1
            for (y in 0 until sh) {
                val row = y * sw
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) { val c = src[row + (if (i < 0) 0 else if (i > last) last else i)]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
                for (x in 0 until sw) {
                    dst[row + x] = -0x1000000 or (((sr * mul) ushr 16) shl 16) or (((sg * mul) ushr 16) shl 8) or ((sb * mul) ushr 16)
                    val oi = x - r; val ni = x + r + 1
                    val o = src[row + (if (oi < 0) 0 else oi)]; val n = src[row + (if (ni > last) last else ni)]
                    sr += ((n shr 16) and 255) - ((o shr 16) and 255); sg += ((n shr 8) and 255) - ((o shr 8) and 255); sb += (n and 255) - (o and 255)
                }
            }
        }
        private fun blurV(src: IntArray, dst: IntArray) {
            val r = r; val mul = (1 shl 16) / (2 * r + 1); val last = sh - 1
            for (x in 0 until sw) {
                var sr = 0; var sg = 0; var sb = 0
                for (i in -r..r) { val c = src[(if (i < 0) 0 else if (i > last) last else i) * sw + x]; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
                for (y in 0 until sh) {
                    dst[y * sw + x] = -0x1000000 or (((sr * mul) ushr 16) shl 16) or (((sg * mul) ushr 16) shl 8) or ((sb * mul) ushr 16)
                    val oi = y - r; val ni = y + r + 1
                    val o = src[(if (oi < 0) 0 else oi) * sw + x]; val n = src[(if (ni > last) last else ni) * sw + x]
                    sr += ((n shr 16) and 255) - ((o shr 16) and 255); sg += ((n shr 8) and 255) - ((o shr 8) and 255); sb += (n and 255) - (o and 255)
                }
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
    var aspect by remember { androidx.compose.runtime.mutableFloatStateOf(16f / 9f) }
    var view by remember { mutableStateOf<android.view.SurfaceView?>(null) }
    val dim = VideoFrames.dim
    val player = remember {
        // (The same for every TV: capped smaller for Lite in 4.4.0, the video stopped to load on a weak TV's slower
        // network (#34), for a gain in memory too small to measure.)
        val load = androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 50_000, 2_500, 5_000).build()
        androidx.media3.exoplayer.ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(com.glasslauncher.tv.saver.VideoCache.factory(ctx, st.saverCache, st.saverCacheGb))
            .setLoadControl(load).build().apply {
                volume = 0f
                // (No frame rate told to the TV: it held all of Glassio at the video's 30 frames a second at rest.)
                com.glasslauncher.tv.data.MediaLog.underGlassio(this)
                setMediaItems(list.map { androidx.media3.common.MediaItem.fromUri(CustomBackground.uri(it)) }, start.coerceIn(0, (list.size - 1).coerceAtLeast(0)), 0L)
                repeatMode = if (advance && list.size > 1) androidx.media3.common.Player.REPEAT_MODE_ALL else androidx.media3.common.Player.REPEAT_MODE_ONE
                prepare()
            }
    }
    // Back from another app the video's surface is new and black until the player draws again: the last frame
    // stands in for it (the glass shows that same frame), then fades out.
    var still by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    // (Under the cameras: where the video was when it let go, and when it was taken up again; see below.)
    val camsHeld = remember { longArrayOf(-1L, 0L) }
    var cover by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(cover, rendered, playing) { if (cover && rendered && playing) { kotlinx.coroutines.delay(300); cover = false } }
    val coverAlpha by androidx.compose.animation.core.animateFloatAsState(if (cover) 1f else 0f, androidx.compose.animation.core.tween(if (cover) 0 else 380), label = "cover")
    DisposableEffect(Unit) {
        Glog.i("Background video on: ${list.getOrNull(start)} (${list.size} in the list)")
        VideoFrames.homeVideo = true
        // (The home screen draws its own veil behind the cameras over a video: no blur of the TV's on top.)
        com.glasslauncher.tv.MultiCamOverlay.ownVeil++
        var errors = 0
        val l = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) aspect = v.width * v.pixelWidthHeightRatio / v.height
            }
            override fun onRenderedFirstFrame() {
                rendered = true; ready = true; errors = 0; VideoFrames.showing.value = true
                if (camsHeld[1] > 0) { Glog.i("Background video: back after the cameras in ${android.os.SystemClock.uptimeMillis() - camsHeld[1]} ms"); camsHeld[1] = 0L }
            }
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                // The same video starting again (one video, or Shuffle off) plays on without a new "first frame":
                // marking it as not drawn stopped the glass copies for good (dock, menu and clock froze).
                if (reason == androidx.media3.common.Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return
                rendered = false
                DecodeGate.starting()
                val u = item?.localConfiguration?.uri?.toString() ?: return
                list.firstOrNull { CustomBackground.uri(it) == u }?.let { Glog.i("Background video: $it"); onItem(it) }
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                com.glasslauncher.tv.data.MediaLog.error("Background video", player, e, hevcOf = st)
                // That one doesn't play (gone from the feed, network): the next one, a few times.
                if (++errors <= list.size.coerceAtMost(6) && list.size > 1) { player.seekToNextMediaItem(); player.prepare() }
            }
        }
        player.addListener(l)
        onDispose {
            Glog.i("Background video off")
            player.removeListener(l); runCatching { player.release() }
            VideoFrames.homeVideo = false
            com.glasslauncher.tv.MultiCamOverlay.ownVeil--
            VideoFrames.clear(); dim.floatValue = 0f
        }
    }
    // Away from Glassio (another app or the screensaver in front) for a few seconds: the video's decoder and its
    // buffer (up to 50 s of video) are let go, and taken again on the way back. Kept, they stayed in memory under
    // whatever was in front: on a TV with little memory the screensaver's own video then had two to carry (#22).
    // Only while Glassio is really away (stopped), never for its own screens (the Library, Settings).
    val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val let = remember { BooleanArray(1) }
    // The cameras up over it (multi-view, its whole-screen page; One player's screensaver too): the video waits under
    // them, as the screensaver's own does, and lets its decoder go once its last frame stands in for it (paused it
    // still held one, and the fourth camera found none). Taken up again where it was when they close; the frame
    // stays over it until it draws.
    val camsUp = com.glasslauncher.tv.MultiCamOverlay.open.value
    androidx.compose.runtime.LaunchedEffect(camsUp) {
        if (camsUp) {
            if (playing) player.playWhenReady = false
            kotlinx.coroutines.delay(300)
            val f = VideoFrames.sharp.value
            if (f != null && player.currentMediaItem != null && player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                runCatching { val c = f.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false); if (!looksEmpty(c)) { still = c.asImageBitmap(); cover = true } }
                camsHeld[0] = player.currentPosition; rendered = false; player.stop()
                Glog.i("Background video: let go under the cameras")
            }
        } else {
            if (camsHeld[0] >= 0) { val at = camsHeld[0]; camsHeld[0] = -1L; camsHeld[1] = android.os.SystemClock.uptimeMillis()
                // (The nearest key frame, before preparing: the exact place took 1.3 s, the veil's wait after the cameras.)
                player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC); player.seekTo(at); player.prepare(); player.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT) }
            if (playing) player.playWhenReady = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(playing) {
        player.playWhenReady = playing && !camsUp
        if (!playing) {
            CustomBackground.leftVideo = player.currentMediaItem?.localConfiguration?.uri?.toString()
            CustomBackground.leftPos = player.currentPosition
            // Checked every 5 s while it stands still: Glassio may be left later (from Settings or the Library, where
            // the video already stood still, it was never let go). Also while the screensaver on the home screen plays
            // its own video: two decoders at once on a small TV (#22).
            while (player.playbackState != androidx.media3.common.Player.STATE_IDLE) {
                kotlinx.coroutines.delay(5000)
                val away = !life.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
                val saverOwn = com.glasslauncher.tv.saver.HomeSaver.state.value != 0 && !com.glasslauncher.tv.saver.HomeSaver.onBackground
                if ((st.bgLetGo && away) || saverOwn) {
                    CustomBackground.leftPos = player.currentPosition
                    player.stop(); let[0] = true
                    Glog.i("Background video: let go while away")
                }
            }
        } else {
            // Back from the screensaver with *Same video*: on with the one it ended on, where it was. Also when the
            // video was not let go: it stood paused where the screensaver began, and jumped back by the
            // screensaver's whole length on the way home (his report: "the sync isn't right").
            val sv = com.glasslauncher.tv.saver.SaverControl
            val title = sv.endedTitle.takeIf { st.saverSameVideo && st.bgMode == "aerial" && android.os.SystemClock.uptimeMillis() - sv.endedAt < 60_000L }
            val at = title?.let { t -> list.indexOfFirst { CustomBackground.aerialTitles[it] == t } }?.takeIf { it >= 0 }
            sv.endedTitle = null
            if (at != null) {
                // (The same video a moment further on is usually in the player's buffer already: no loading.)
                // (The stand-in frame is from when the screensaver began: another moment, or another video. The
                // screen stays black, under the screensaver's own black, until the video draws.)
                cover = false
                if (at != player.currentMediaItemIndex) player.seekTo(at, sv.endedPos)
                else if (kotlin.math.abs(player.currentPosition - sv.endedPos) > 1500 || let[0]) player.seekTo(sv.endedPos)
                Glog.i("Background video: on from the screensaver's ($title, ${sv.endedPos / 1000} s)")
            } else if (let[0]) player.seekTo(CustomBackground.leftPos)
            if (let[0]) { let[0] = false; player.prepare() }
        }
    }
    // Never left "not drawn" while the video plays on (a video following another without a new first-frame
    // report): the glass copies would stop and everything glass froze on the last picture.
    androidx.compose.runtime.LaunchedEffect(rendered, ready, view) {
        if (rendered || !ready) return@LaunchedEffect
        kotlinx.coroutines.delay(1500)
        if (player.isPlaying && view?.holder?.surface?.isValid == true) { Glog.i("Background video: drawing again"); rendered = true }
    }
    androidx.compose.runtime.LaunchedEffect(rendered) { if (rendered) VideoFrames.drawnRev.intValue++ }
    // The video's still (the page's backdrop, the glass behind it while it loads): one copy of its own picture,
    // 1.5 s in, once per video. No second decoder (see CustomBackground.cachedFrame).
    androidx.compose.runtime.LaunchedEffect(rendered, view) {
        val v = view ?: return@LaunchedEffect
        if (!rendered) return@LaunchedEffect
        val u = player.currentMediaItem?.localConfiguration?.uri?.toString() ?: return@LaunchedEffect
        val src = list.firstOrNull { CustomBackground.uri(it) == u } ?: return@LaunchedEffect
        kotlinx.coroutines.delay(1_500)
        if (!rendered || player.currentMediaItem?.localConfiguration?.uri?.toString() != u || !v.holder.surface.isValid) return@LaunchedEffect
        val bmp = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(v, bmp, { r -> if (r == android.view.PixelCopy.SUCCESS) CustomBackground.saveStill(v.context, src, bmp) },
            android.os.Handler(android.os.Looper.getMainLooper()))
    }
    // The fade in, once the first frame of a video is on screen.
    androidx.compose.runtime.LaunchedEffect(rendered) {
        if (rendered && dim.floatValue > 0f)
            androidx.compose.animation.core.animate(dim.floatValue, 0f, animationSpec = androidx.compose.animation.core.tween(1000)) { v, _ -> dim.floatValue = v }
    }
    // To the next video, like the screensaver: fade to black, the next one starts under it, then it fades in.
    val switching = remember { kotlinx.coroutines.sync.Mutex() }
    suspend fun toNext(fadeMs: Int, by: Int = 1) = switching.withLock {
        if (player.mediaItemCount < 2) return@withLock
        androidx.compose.animation.core.animate(dim.floatValue, 1f, animationSpec = androidx.compose.animation.core.tween(fadeMs)) { v, _ -> dim.floatValue = v }
        player.seekTo((player.currentMediaItemIndex + by).mod(player.mediaItemCount), 0L)
        // The next one's first frame (from the cache: at once); not for ever, if it never comes.
        var w = 0
        while (!rendered && w < 10_000) { kotlinx.coroutines.delay(50); w += 50 }
        if (!rendered) androidx.compose.animation.core.animate(dim.floatValue, 0f, animationSpec = androidx.compose.animation.core.tween(600)) { v, _ -> dim.floatValue = v }
    }
    // Shuffle (and the screensaver on the home screen, which always goes on to the next one unless the video is
    // kept): near the end of each video.
    val homeSaver = com.glasslauncher.tv.saver.HomeSaver.state.value == 1 && com.glasslauncher.tv.saver.HomeSaver.onBackground
    androidx.compose.runtime.LaunchedEffect(ready, playing, homeSaver) {
        if (!(advance || homeSaver) || list.size < 2 || !ready || !playing) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(100)
            if (homeSaver && com.glasslauncher.tv.saver.SaverControl.locked(st)) continue
            val d = player.duration; val p = player.currentPosition
            if (d <= 2000 || p < d - 900) continue
            toNext(700)
        }
    }
    // "Next video" from the menu or Settings.
    val nextRev = CustomBackground.next.intValue
    val seenNext = remember { intArrayOf(nextRev) }
    androidx.compose.runtime.LaunchedEffect(nextRev) { if (nextRev != seenNext[0]) { seenNext[0] = nextRev; toNext(300) } }
    val backRev = CustomBackground.back.intValue
    val seenBack = remember { intArrayOf(backRev) }
    androidx.compose.runtime.LaunchedEffect(backRev) { if (backRev != seenBack[0]) { seenBack[0] = backRev; toNext(300, -1) } }
    androidx.compose.runtime.LaunchedEffect(ready, playing, view) {
        val v = view ?: return@LaunchedEffect
        if (!ready || !playing) return@LaunchedEffect
        if (rendered) VideoFrames.showing.value = true
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
        val lastSoft = longArrayOf(0L)
        // The copies finish on their own thread: on the main thread they held up the home screen while you moved.
        val worker = android.os.HandlerThread("glass-video-copy").apply { start() }
        val h = android.os.Handler(worker.looper)
        var k = 0
        try {
            while (true) {
                // Frosted glass only: 10 a second is plenty for a blurred picture (the clear glass needs 15).
                kotlinx.coroutines.delay(if (VideoFrames.wantSharp) VideoFrames.interval else 100L)
                if (!v.holder.surface.isValid || !rendered || com.glasslauncher.tv.MultiCamOverlay.open.value) continue
                // (Read each time: the screensaver's own copies, ended, cleared the frames and moved on their number,
                // and this background's copies were then dropped for good: the glass stood still after the
                // screensaver until the next video. This video is still the background while this runs.)
                val myGen = VideoFrames.gen
                if (!VideoFrames.showing.value) VideoFrames.showing.value = true
                if (!inFlight.compareAndSet(false, true)) continue
                val i = k++ % 4
                val wantSharp = VideoFrames.wantSharp; val wantSoft = VideoFrames.wantSoft
                val dst = if (wantSharp) sharpBufs[i] else smallBufs[i % 2]
                runCatching {
                    android.view.PixelCopy.request(v, dst, { r ->
                        try {
                            if (r != android.view.PixelCopy.SUCCESS || myGen != VideoFrames.gen) return@request
                            val soft = softCopy(soften, softBufs[i], dst, wantSharp, wantSoft, lastSoft)
                            VideoFrames.publish(myGen, if (wantSharp) dst.asImageBitmap() else null, soft)
                        } finally { inFlight.set(false) }
                    }, h)
                }.onFailure { inFlight.set(false) }
            }
        } finally {
            // Paused (the Library, another screen): the last copies stay, and the video is still the background.
            // Clearing them here left the glass and the clock on the still picture after coming back, until the
            // next video started. They are cleared only when the video goes away (onDispose).
            worker.quitSafely()
        }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        // Centre crop: the surface is made as big as needed to fill the screen with the video's shape.
        val sw = maxWidth; val sh = maxHeight
        val screenAspect = sw.value / sh.value
        val vw = if (aspect > screenAspect) sh * aspect else sw
        val vh = if (aspect > screenAspect) sh else sw / aspect
        val saverCovers = com.glasslauncher.tv.saver.HomeSaver.state.value == 1 && !com.glasslauncher.tv.saver.HomeSaver.onBackground
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
                            // (Only the sharp copy: the small frosted one, enlarged, showed as a blurred and lighter
                            // picture until the video came back, then the screen jumped.)
                            VideoFrames.sharp.value?.let { f ->
                                runCatching { val c = f.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, false); if (!looksEmpty(c)) { still = c.asImageBitmap(); cover = true } }
                            }
                        }
                    })
                }
            },
            // While the screensaver plays its own things over the home screen (trailers, posters, another video), this
            // surface steps aside: stopped, it still held its last frame, and between two trailers (a moment with no
            // picture of the screensaver's own) the background's frozen frame showed through (his report).
            update = { v -> v.visibility = if (saverCovers) android.view.View.INVISIBLE else android.view.View.VISIBLE },
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
    private var imgNoSide: androidx.compose.ui.graphics.ImageBitmap? = null
    fun get(): androidx.compose.ui.graphics.ImageBitmap = if (Scrims.sideOff) (imgNoSide ?: paint(false).also { imgNoSide = it }) else (img ?: paint(true).also { img = it })
    private fun paint(side: Boolean): androidx.compose.ui.graphics.ImageBitmap = run {
        val w = 480; val h = 270
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.62f, 0f, 0xB0000000.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
        if (side) c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), p)
        p.shader = android.graphics.LinearGradient(0f, h * 0.58f, 0f, h.toFloat(), 0, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.58f, w.toFloat(), h.toFloat(), p)
        b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap()
    }
}

/**
 * Live copies of a video for the glass (the dock, menus and pop-up menus follow the picture), ~15 a second:
 * the same copies as the video background's, for any other video on a SurfaceView (the carousel's trailers).
 * Runs until cancelled; [rendered] says a frame of the video is on screen.
 */
/** The part of a video's surface on screen ([src], null = all of it) and where it sits on screen (fractions). */
class FrameRegion(val src: android.graphics.Rect?, val dst: android.graphics.RectF)

suspend fun copyVideoFrames(v: android.view.SurfaceView, rendered: () -> Boolean, region: () -> FrameRegion? = { null }) {
    val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    val sharpBufs = Array(4) { android.graphics.Bitmap.createBitmap(720, 405, android.graphics.Bitmap.Config.ARGB_8888) }
    val smallBufs = Array(2) { android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888) }
    val softBufs = Array(4) { android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888) }
    val soften = Softener(320, 180)
    val lastSoft = longArrayOf(0L)
    val worker = android.os.HandlerThread("glass-trailer-copy").apply { start() }
    val h = android.os.Handler(worker.looper)
    var k = 0
    try {
        while (true) {
            kotlinx.coroutines.delay(if (VideoFrames.wantSharp) VideoFrames.interval else 100L)
            if (!v.holder.surface.isValid || !rendered()) continue
            // (The video waits under the cameras: its picture doesn't change, and each copy cost the drawing thread
            // a fifth of its time while the cameras played.)
            if (com.glasslauncher.tv.MultiCamOverlay.open.value) continue
            // (Read each time, like the video background: frames cleared by something else (a video background
            // going, the screensaver ending) moved the number on, and every copy after it was dropped: the glass froze.)
            if (android.os.SystemClock.uptimeMillis() < VideoFrames.quietUntil) continue
            val myGen = VideoFrames.gen
            if (!VideoFrames.showing.value) VideoFrames.showing.value = true
            if (!inFlight.compareAndSet(false, true)) continue
            val i = k++ % 4
            val wantSharp = VideoFrames.wantSharp; val wantSoft = VideoFrames.wantSoft
            val dst = if (wantSharp) sharpBufs[i] else smallBufs[i % 2]
            val reg = region()
            val whole = reg == null || (reg.dst.left <= 0f && reg.dst.top <= 0f && reg.dst.right >= 1f && reg.dst.bottom >= 1f)
            // Only part of the screen (bars around it): copied small, then placed where it is, black around.
            val into = if (whole) dst else tmpFor(dst, reg.dst)
            val done = { r: Int ->
                try {
                    if (r == android.view.PixelCopy.SUCCESS && myGen == VideoFrames.gen) {
                        if (!whole) android.graphics.Canvas(dst).apply {
                            drawColor(android.graphics.Color.BLACK)
                            drawBitmap(into, null, android.graphics.RectF(reg.dst.left * dst.width, reg.dst.top * dst.height, reg.dst.right * dst.width, reg.dst.bottom * dst.height), placePaint)
                        }
                        val soft = softCopy(soften, softBufs[i], dst, wantSharp, wantSoft, lastSoft)
                        VideoFrames.publish(myGen, if (wantSharp) dst.asImageBitmap() else null, soft)
                    }
                } finally { inFlight.set(false) }
            }
            runCatching {
                val src = reg?.src
                if (src != null) android.view.PixelCopy.request(v, src, into, { r -> done(r) }, h)
                else android.view.PixelCopy.request(v, into, { r -> done(r) }, h)
            }.onFailure { inFlight.set(false) }
        }
    } finally { worker.quitSafely() }
}

/**
 * The frosted copy of [dst], at most ~10 a second: with the sharp copies coming 15-30 a second (the clock, clear
 * glass, trailers) it was made for every one of them, and a blurred picture needs no more. In between, the last one
 * stays. Null when no frosted glass shows.
 */
private fun softCopy(soften: Softener, buf: android.graphics.Bitmap, dst: android.graphics.Bitmap, wantSharp: Boolean, wantSoft: Boolean,
                     last: LongArray): androidx.compose.ui.graphics.ImageBitmap? {
    if (!wantSoft && wantSharp) return null
    val now = android.os.SystemClock.uptimeMillis()
    if (wantSharp && now - last[0] < 95) return VideoFrames.soft.value
    last[0] = now
    soften.make(dst, buf, fromLarge = wantSharp)
    return buf.asImageBitmap()
}

private val placePaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
private val tmpBufs = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

/** A buffer the size of the [part] of [full] (kept for the next copies). */
private fun tmpFor(full: android.graphics.Bitmap, part: android.graphics.RectF): android.graphics.Bitmap {
    val w = (full.width * (part.right - part.left)).toInt().coerceAtLeast(16)
    val h = (full.height * (part.bottom - part.top)).toInt().coerceAtLeast(16)
    return tmpBufs.getOrPut("${w}x$h") { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888) }
}

/**
 * One video decoder starting at a time: a player opening its decoder (the next video) and a still or thumbnail being
 * decoded from a video at the same moment were two hardware decoders at once, and on an Amlogic box (#31) the
 * switch to HEVC with both open was the last thing before a kernel panic. Pictures made from videos wait until a
 * player's start is over.
 */
object DecodeGate {
    @Volatile private var busyUntil = 0L
    /** A player is opening a decoder now. */
    fun starting(ms: Long = 4_000) { busyUntil = android.os.SystemClock.uptimeMillis() + ms }
    /** Blocking: waits until no player is starting (20 s at most). */
    fun waitClear() { var n = 0; while (android.os.SystemClock.uptimeMillis() < busyUntil && n++ < 200) Thread.sleep(100) }
}

/**
 * A copy of a video's picture that is one flat colour (an empty frame: on some TVs a copy of a hardware video comes
 * back empty, and an empty video frame shows as solid green or black) is never kept as a still. #40: a Fire TV showed
 * a solid green page behind an app's rows. Samples 16 x 9 points; flat = every channel within a few steps.
 */
fun looksEmpty(b: android.graphics.Bitmap): Boolean = runCatching {
    var minR = 255; var maxR = 0; var minG = 255; var maxG = 0; var minB = 255; var maxB = 0
    for (y in 0 until 9) for (x in 0 until 16) {
        val p = b.getPixel((b.width - 1) * x / 15, (b.height - 1) * y / 8)
        val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
        if (r < minR) minR = r; if (r > maxR) maxR = r; if (g < minG) minG = g; if (g > maxG) maxG = g; if (bl < minB) minB = bl; if (bl > maxB) maxB = bl
    }
    maxR - minR < 8 && maxG - minG < 8 && maxB - minB < 8
}.getOrDefault(false)
