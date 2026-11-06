package com.glasslauncher.tv

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
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

    fun build(ctx: Context): ExoPlayer {
        val b = ExoPlayer.Builder(ctx)
        if (software(ctx)) {
            // Software decoders first; a hardware one only if the TV has no software decoder for the format.
            val selector = MediaCodecSelector { mime, secure, tunneling ->
                MediaCodecUtil.getDecoderInfos(mime, secure, tunneling).sortedBy { if (it.softwareOnly) 0 else 1 }
            }
            b.setRenderersFactory(DefaultRenderersFactory(ctx).setMediaCodecSelector(selector).setEnableDecoderFallback(true))
        }
        return b.build().apply { volume = 0f }
    }

    /**
     * Starts [url] on [p] (RTSP over TCP, like the corner camera always did). ha://camera.x (a Home Assistant
     * camera) is first turned into a fresh live link, off the main thread.
     */
    fun play(ctx: Context, p: ExoPlayer, url: String) {
        if (!url.startsWith("ha://")) { play(p, url); return }
        val st = (ctx.applicationContext as GlassApp).settings
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread {
            val live = runCatching { com.glasslauncher.tv.net.HaWatch.streamUrl(st.haUrl, st.haToken, url.removePrefix("ha://")) }.getOrNull()
            main.post { if (live != null) runCatching { play(p, live) } else runCatching { p.stop() } }
        }.apply { isDaemon = true }.start()
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
