package com.glasslauncher.tv.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.mutableIntStateOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** The settings button's own picture is kept with the icons (it travels with them: phone page, backups of icons, Sync). */
const val SETTINGS_BUTTON_ICON = "glassio.settingsbutton"

/**
 * Custom dock icons. An icon can come from a link, from a file saved in the app's icon folder,
 * or from an upload through the phone page. All of them end up as PNG files in filesDir/icons.
 */
class IconStore(private val ctx: Context) {
    private val dir = File(ctx.filesDir, "icons").apply { mkdirs() }
    /** Folder a user can fill over USB/ADB/file manager: <pkg>.png|jpg|webp */
    val dropFolder: File get() = (ctx.getExternalFilesDir("icons") ?: File(ctx.filesDir, "drop")).apply { mkdirs() }
    val rev = mutableIntStateOf(0)

    private fun safe(pkg: String) = pkg.replace(Regex("[^A-Za-z0-9._-]"), "_")
    fun fileFor(pkg: String) = File(dir, safe(pkg) + ".png")
    fun has(pkg: String): Boolean { rev.intValue; return fileFor(pkg).exists() }

    fun setFromBytes(pkg: String, bytes: ByteArray) {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        require(opts.outWidth > 0 && opts.outHeight > 0) { "The file is not a valid image" }
        var sample = 1
        while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Could not decode the image")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        fileFor(pkg).writeBytes(out.toByteArray())
        bmp.recycle()
        rev.intValue = rev.intValue + 1
    }

    /** Saves a pack icon (or any drawable) as this app's custom icon. Blocking. */
    fun setFromDrawable(pkg: String, d: android.graphics.drawable.Drawable) {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 512
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 512
        val w = if (iw.toFloat() / ih > 1.3f) 800 else 512
        val h = (ih * (w.toFloat() / iw)).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h); d.draw(android.graphics.Canvas(bmp))
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        fileFor(pkg).writeBytes(out.toByteArray())
        bmp.recycle()
        rev.intValue = rev.intValue + 1
    }

    /** Blocking; call from a background thread. */
    fun setFromUrl(pkg: String, url: String) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "The link must start with http:// or https://" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "GlassLauncher/1.0")
        try {
            check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
            val buf = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    check(buf.size() <= 12 * 1024 * 1024) { "Image is too large" }
                }
            }
            setFromBytes(pkg, buf.toByteArray())
        } finally { c.disconnect() }
    }

    fun reset(pkg: String) { fileFor(pkg).delete(); rev.intValue = rev.intValue + 1 }

    /** Picks up <package>.png|jpg|jpeg|webp placed in [dropFolder]. Returns how many were imported. */
    fun importDropFolder(knownPackages: Collection<String>): Int {
        var n = 0
        val files = dropFolder.listFiles().orEmpty()
        for (pkg in knownPackages) {
            val match = files.firstOrNull { f ->
                val base = f.nameWithoutExtension
                val ext = f.extension.lowercase()
                base == pkg && ext in setOf("png", "jpg", "jpeg", "webp")
            } ?: continue
            if (match.lastModified() <= fileFor(pkg).lastModified()) continue
            runCatching { setFromBytes(pkg, match.readBytes()); n++ }
        }
        return n
    }
}
