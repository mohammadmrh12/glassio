package com.glasslauncher.tv

import com.glasslauncher.tv.data.Bg
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/**
 * Players for the cameras (corner camera, multi-view, doorbell).
 *
 * The TV's hardware video decoders are few (two on many TV chips). A camera taking one could take it from the
 * video you were watching or from the screensaver, which then stopped (seen on a TCL TV with Android 11). With
 * "software" the cameras are decoded by the processor instead, so the TV's own video keeps its decoder; a camera's
 * small sub-stream is light enough for that. Auto: software on small TVs (up to 3 GB) and Android 11 or older.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object CamPlayer {
    fun software(ctx: Context): Boolean {
        val st = (ctx.applicationContext as GlassApp).settings
        return when (st.cameraDecoder) {
            "software" -> true
            "hardware" -> false
            else -> {
                val mi = android.app.ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
                mi.totalMem < 3L * 1024 * 1024 * 1024 || android.os.Build.VERSION.SDK_INT <= 30
            }
        }
    }

    /**
     * How many videos the TV's hardware decoders take at once (the smallest of its H.264 / HEVC ones; the Streamer
     * says 4). Read once from the TV's own codec list.
     */
    val hardwareRoom: Int get() = roomOverride.takeIf { it > 0 } ?: tvRoom
    /** QA (dev key, `--ei cam_room N`): the TV taken to have only N hardware decoders (0: its own number). */
    @Volatile var roomOverride = 0
    private val tvRoom: Int by lazy {
        runCatching {
            android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder && (android.os.Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated) }
                .flatMap { ci -> ci.supportedTypes.filter { it == "video/avc" || it == "video/hevc" }.map { t -> ci.getCapabilitiesForType(t).maxSupportedInstances } }
                .filter { it > 0 }.minOrNull()
        }.getOrNull() ?: 4
    }

    /**
     * A camera's player. [softwareFirst]: the processor decodes it (see [software]); a camera beyond the TV's
     * hardware decoders ([hardwareRoom]) too. Either way a decoder that can't start hands over to the next one in the
     * list (decoder fallback): a fourth camera on a TV whose hardware decoders were taken failed with
     * DECODER_INIT_FAILED and stayed dark (his "one camera doesn't play"); it now plays on the processor.
     */
    fun build(ctx: Context, softwareFirst: Boolean = software(ctx)): ExoPlayer {
        val b = ExoPlayer.Builder(ctx)
        val factory = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        if (softwareFirst) {
            // Software decoders first; a hardware one only if the TV has no software decoder for the format.
            factory.setMediaCodecSelector { mime, secure, tunneling ->
                MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
            }
        }
        b.setRenderersFactory(factory)
        // Its own playback thread, which outlives a reply the RTSP client can't read (#28: a camera answered
        // without a CSeq header, Media3's RtspClient threw on the player's thread and Glassio closed).
        val (looper, _) = safeLooper()
        b.setPlaybackLooper(looper)
        return b.build().apply { volume = 0f; loopers[this] = looper; com.glasslauncher.tv.data.MediaLog.underGlassio(this) }
    }

    private val loopers = java.util.Collections.synchronizedMap(java.util.WeakHashMap<ExoPlayer, android.os.Looper>())

    /** Releases [p] and ends its playback thread. */
    fun release(p: ExoPlayer) {
        stopPictures(p); playNo.remove(p); guards.remove(p)
        runCatching { p.release() }.onFailure { com.glasslauncher.tv.data.Glog.w("Camera player release", it) }
        loopers.remove(p)?.quitSafely()
    }

    /**
     * A looper thread for a camera's player that goes on after a runtime exception in what it runs: that one
     * message is lost (a camera's reply nobody can match), the stream goes on or times out as the player decides.
     * Errors (out of memory) still end the app as before.
     */
    private fun safeLooper(): Pair<android.os.Looper, Thread> {
        val ready = java.util.concurrent.CountDownLatch(1)
        val box = arrayOfNulls<android.os.Looper>(1)
        // (Named here: named from the main thread afterwards, Android renamed the main thread instead, and every
        // measurement showed the main thread's work as a camera's.)
        val t = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            android.os.Looper.prepare(); box[0] = android.os.Looper.myLooper(); ready.countDown()
            var lost = 0
            while (true) {
                try { android.os.Looper.loop(); break }
                catch (e: RuntimeException) {
                    // (Once in the log with its stack, then only counted: a camera can send such replies all along.)
                    if (lost++ == 0) com.glasslauncher.tv.data.Glog.w("Camera stream: a reply the player couldn't read was left out", e)
                }
            }
        }, "glass-cam-play")
        t.start(); ready.await()
        return box[0]!! to t
    }

    /**
     * Starts [url] on [p] (RTSP over TCP, like the corner camera always did). ha://camera.x (a Home Assistant
     * camera) is first turned into a fresh live link, off the main thread.
     */
    fun play(ctx: Context, p: ExoPlayer, url: String, tex: android.view.TextureView? = null, onFail: () -> Unit = {}, onLive: () -> Unit = {}) {
        stopPictures(p)
        guards.remove(p)?.let { p.removeListener(it) }
        // (Each call has its number: a Home Assistant answer that comes after the player moved on to another camera,
        // or was let go, is dropped.)
        val n = playNo.merge(p, 1, Int::plus) ?: 1
        if (!url.startsWith("ha://")) { tex?.let { p.setVideoTextureView(it) }; play(p, url); return }
        haPlay(ctx, p, url.removePrefix("ha://"), tex, onFail, onLive, n, 0)
    }

    /**
     * A Home Assistant camera ([selfHealing]): its stream, asked for again once if it fails or isn't playing within
     * 15 s (HA's stream worker starting, a decoder that fails), then the camera's MJPEG picture or its snapshots,
     * which HA gives for every camera; [onFail] only when none of them comes. (#40: some of a person's Reolink cameras
     * said "Can't reach the camera", at random, while Home Assistant showed them live: the stream's first failure
     * was the end of it.)
     */
    private fun haPlay(ctx: Context, p: ExoPlayer, entity: String, tex: android.view.TextureView?, onFail: () -> Unit, onLive: () -> Unit, n: Int, attempt: Int) {
        val st = (ctx.applicationContext as GlassApp).settings
        val base = haTestUrl ?: st.haUrl
        val token = haTestToken ?: st.haToken
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        fun current() = playNo[p] == n && loopers.containsKey(p)
        fun pictures() {
            if (tex != null) { runCatching { p.stop(); p.clearVideoTextureView(tex) }; pictures[p] = HaPictures(base, token, entity, tex, onLive, onFail).also { it.start() } }
            else { runCatching { p.stop() }; onFail() }
        }
        Bg.io("glass-cam") {
            val live = runCatching { com.glasslauncher.tv.net.HaWatch.streamUrl(base, token, entity) }.getOrNull()
            main.post {
                if (!current()) return@post
                // No stream from Home Assistant (an MJPEG or snapshot-only camera, or its `stream` integration off,
                // #28): the camera's live MJPEG picture, else its snapshot every second, drawn on the same view.
                if (live == null) { pictures(); return@post }
                tex?.let { p.setVideoTextureView(it) }
                var done = false
                fun failed(why: String) {
                    if (done || !current()) return
                    done = true
                    guards.remove(p)?.let { p.removeListener(it) }
                    if (attempt == 0) {
                        com.glasslauncher.tv.data.Glog.w("Home Assistant camera $entity: stream $why, asking for it again")
                        main.postDelayed({ if (current()) haPlay(ctx, p, entity, tex, onFail, onLive, n, 1) }, 2000)
                    } else {
                        com.glasslauncher.tv.data.Glog.w("Home Assistant camera $entity: stream $why again, its pictures instead")
                        pictures()
                    }
                }
                val guard = object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(s: Int) {
                        if (s == androidx.media3.common.Player.STATE_READY && !done) { done = true; guards.remove(p)?.let { p.removeListener(it) }; onLive() }
                    }
                    override fun onPlayerError(e: androidx.media3.common.PlaybackException) { failed(e.errorCodeName) }
                }
                guards[p] = guard; p.addListener(guard)
                runCatching { play(p, live) }.onFailure { failed(it.javaClass.simpleName) }
                main.postDelayed({ failed("not playing after 15 s") }, 15_000)
            }
        }
    }

    /** Home Assistant cameras look after their own failures ([haPlay]): a window shows "Can't reach" only on onFail. */
    fun selfHealing(url: String) = url.startsWith("ha://")

    private val guards = java.util.concurrent.ConcurrentHashMap<ExoPlayer, androidx.media3.common.Player.Listener>()

    /** QA (dev key): a Home Assistant to use instead of the one set (a test server), for `cam_test ha://…`. */
    @Volatile var haTestUrl: String? = null
    @Volatile var haTestToken: String? = null

    private val playNo = java.util.concurrent.ConcurrentHashMap<ExoPlayer, Int>()
    private val pictures = java.util.concurrent.ConcurrentHashMap<ExoPlayer, HaPictures>()
    private fun stopPictures(p: ExoPlayer) { pictures.remove(p)?.stop() }

    /**
     * A Home Assistant camera's pictures drawn straight on a TextureView: its MJPEG stream
     * (`/api/camera_proxy_stream/<entity>`, as the dashboard shows it, connected again if it drops), else its
     * snapshot (`/api/camera_proxy/<entity>`) once a second. Stopped (another camera, closed): its surface is let go
     * at once, so the player can take the view again.
     */
    private class HaPictures(val base: String, val token: String, val entity: String, val tex: android.view.TextureView, val onLive: () -> Unit, val onFail: () -> Unit) {
        @Volatile private var running = true
        @Volatile private var conn: java.net.HttpURLConnection? = null
        @Volatile private var thread: Thread? = null
        private val lock = Any()
        private var surface: android.view.Surface? = null
        private var told = false
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        fun stop() {
            running = false
            runCatching { conn?.disconnect() }
            thread?.interrupt()
            synchronized(lock) { runCatching { surface?.release() }; surface = null }
        }

        fun start() = Bg.io("glass-cam-pictures") {
            thread = Thread.currentThread()
            try {
                // The MJPEG stream while it gives pictures (connected again after a drop, a few times in a row at most).
                var drops = 0
                while (running && drops < 3) {
                    val frames = runCatching { stream() }.onFailure { if (running) com.glasslauncher.tv.data.Glog.w("Home Assistant camera $entity: MJPEG", it) }.getOrDefault(-1)
                    if (frames < 0) break                       // no MJPEG at all: the snapshots
                    drops = if (frames > 0) 0 else drops + 1
                    if (running) runCatching { Thread.sleep(1000L * (drops + 1)) }
                }
                if (running && !told) com.glasslauncher.tv.data.Glog.i("Home Assistant camera $entity: its snapshots instead")
                var fails = 0
                while (running) {
                    fails = if (runCatching { snapshot() }.getOrDefault(false)) 0 else fails + 1
                    if (fails >= 5) {
                        com.glasslauncher.tv.data.Glog.w("Home Assistant camera $entity: no pictures either")
                        if (running) main.post(onFail)
                        break
                    }
                    runCatching { Thread.sleep(1000) }
                }
            } finally { thread = null; synchronized(lock) { runCatching { surface?.release() }; surface = null } }
        }

        private fun open(path: String): java.net.HttpURLConnection = (java.net.URL(base.trimEnd('/') + path).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 6000; readTimeout = 10000
            setRequestProperty("Authorization", "Bearer $token")
            conn = this
        }

        /** The MJPEG stream, frame by frame (JPEGs between their markers): how many were drawn, -1 when there is none. */
        private fun stream(): Int {
            val c = open("/api/camera_proxy_stream/$entity")
            try {
                if (c.responseCode !in 200..299 || c.contentType?.contains("multipart", true) != true) return -1
                val inp = java.io.BufferedInputStream(c.inputStream, 1 shl 16)
                val buf = java.io.ByteArrayOutputStream(1 shl 17)
                var prev = -1; var inJpeg = false; var frames = 0
                while (running) {
                    val b = inp.read(); if (b < 0) break
                    if (!inJpeg) { if (prev == 0xFF && b == 0xD8) { inJpeg = true; buf.reset(); buf.write(0xFF); buf.write(0xD8) } }
                    else {
                        buf.write(b)
                        if (prev == 0xFF && b == 0xD9) {
                            inJpeg = false
                            val bytes = buf.toByteArray()
                            if (android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { draw(it) } == true) {
                                if (frames++ == 0) com.glasslauncher.tv.data.Glog.i("Home Assistant camera $entity: its MJPEG picture (no stream)")
                            }
                        }
                    }
                    prev = b
                }
                return frames
            } finally { c.disconnect() }
        }

        private fun snapshot(): Boolean {
            val c = open("/api/camera_proxy/$entity")
            try {
                if (c.responseCode !in 200..299) return false
                val bmp = c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return false
                return draw(bmp)
            } finally { c.disconnect() }
        }

        /** The picture fitted into the view (black around it), on its own surface. False: nowhere to draw it now. */
        private fun draw(b: android.graphics.Bitmap): Boolean {
            try {
                synchronized(lock) {
                    if (!running) return false
                    val st = tex.surfaceTexture ?: return false
                    val s = surface ?: android.view.Surface(st).also { surface = it }
                    val canvas = runCatching { s.lockCanvas(null) }.getOrNull() ?: return false
                    try {
                        canvas.drawColor(android.graphics.Color.BLACK)
                        val vw = canvas.width.toFloat(); val vh = canvas.height.toFloat()
                        val k = minOf(vw / b.width, vh / b.height)
                        val w = b.width * k; val h = b.height * k
                        canvas.drawBitmap(b, null, android.graphics.RectF((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    } finally { runCatching { s.unlockCanvasAndPost(canvas) } }
                }
            } finally { b.recycle() }
            if (!told) { told = true; main.post(onLive) }
            return true
        }
    }

    fun play(p: ExoPlayer, url: String) {
        p.stop(); p.clearMediaItems()
        val item = MediaItem.fromUri(url)
        if (url.startsWith("rtsp", true)) p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000).createMediaSource(item))
        else p.setMediaItem(item)
        p.prepare(); p.playWhenReady = true
    }

    /**
     * The camera's small second stream when its link shows one (Dahua/Amcrest subtype=0 → 1, Hikvision channel
     * 101 → 102): four cameras at once stay light. Other links are used as they are.
     */
    fun subStream(url: String): String = when {
        Regex("subtype=0", RegexOption.IGNORE_CASE).containsMatchIn(url) -> url.replace(Regex("subtype=0", RegexOption.IGNORE_CASE), "subtype=1")
        Regex("/Streaming/Channels/(\\d+)01\\b", RegexOption.IGNORE_CASE).containsMatchIn(url) ->
            url.replace(Regex("/Streaming/Channels/(\\d+)01\\b", RegexOption.IGNORE_CASE)) { "/Streaming/Channels/${it.groupValues[1]}02" }
        else -> url
    }
}
