package com.glasslauncher.tv.saver

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import java.security.MessageDigest

/**
 * Small pictures of your screensaver video links (shown next to their names). Made once in the background
 * from a frame a few seconds in, then kept as a JPEG. Videos in the backgrounds folder are pictured directly.
 */
object VideoThumbs {
    private val working = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun dir(ctx: Context) = File(ctx.cacheDir, "video-thumbs").apply { mkdirs() }
    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    fun file(ctx: Context, url: String): File = File(dir(ctx), key(url) + ".jpg")

    /** The picture's path if it's made; otherwise starts making it and returns null. */
    fun pathOrMake(ctx: Context, url: String): String? {
        val f = file(ctx, url)
        if (f.isFile && f.length() > 0) return f.absolutePath
        if (working.add(url)) Thread({ try { make(ctx, url, f) } finally { working.remove(url) } }, "glass-thumb").apply { isDaemon = true }.start()
        return null
    }

    /** Blocking: makes the picture now (used when a list with these videos is opened). */
    fun get(ctx: Context, url: String): String? {
        val f = file(ctx, url)
        if (!(f.isFile && f.length() > 0)) make(ctx, url, f)
        return f.takeIf { it.isFile && it.length() > 0 }?.absolutePath
    }

    private fun make(ctx: Context, url: String, out: File) = runCatching {
        val r = MediaMetadataRetriever()
        try {
            if (url.startsWith("http")) r.setDataSource(url, HashMap()) else r.setDataSource(url.removePrefix("file://"))
            val b = (if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(4_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180) else null)
                ?: r.getFrameAtTime(4_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return@runCatching
            val small = if (b.width > 360) Bitmap.createScaledBitmap(b, 320, (320f * b.height / b.width).toInt().coerceAtLeast(1), true) else b
            val tmp = File(out.parentFile, out.name + ".part")
            tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            tmp.renameTo(out)
        } finally { runCatching { r.release() } }
    }
}
