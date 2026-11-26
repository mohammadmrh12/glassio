package com.glasslauncher.tv.data

/**
 * One line in Glassio's log for a video that failed (background, screensaver, trailer, camera): what failed, the
 * error, the video's format (codec, size, frame rate) and the decoder's own words. A report with only
 * "DECODING_FAILED" (#39, a Fire TV's green screensaver) couldn't say which video or which decoder.
 */
object MediaLog {
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun error(where: String, player: androidx.media3.common.Player?, e: androidx.media3.common.PlaybackException) {
        val f = (player as? androidx.media3.exoplayer.ExoPlayer)?.videoFormat
        val fmt = f?.let { listOfNotNull(it.sampleMimeType, it.codecs, "${it.width}x${it.height}".takeIf { _ -> it.width > 0 },
            if (it.frameRate > 0) "${it.frameRate.toInt()} fps" else null).joinToString(" ") } ?: "no video format yet"
        val why = generateSequence(e.cause) { it.cause }.take(3).joinToString(" ← ") { c -> c.javaClass.simpleName + (c.message?.let { ": " + it.take(160) } ?: "") }
        Glog.w("$where: video error ${e.errorCodeName} · $fmt" + (if (why.isNotBlank()) " · $why" else ""))
    }
}
