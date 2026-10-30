package com.glasslauncher.tv.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * A very soft glow around the selected icon or poster (Settings → Dock & icons → Glow): in the picture's own
 * colour or white. The glow is one small pre-blurred picture, tinted and stretched, so it costs a single copy.
 */
object Glow {
    private const val PAD = 0.22f
    private var wide: ImageBitmap? = null
    private var tall: ImageBitmap? = null

    private fun make(w: Int, h: Int): ImageBitmap {
        val pad = (w * PAD).toInt()
        val b = Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; maskFilter = BlurMaskFilter(pad * 0.7f, BlurMaskFilter.Blur.NORMAL) }
        Canvas(b).drawRoundRect(RectF(pad.toFloat(), pad.toFloat(), (pad + w).toFloat(), (pad + h).toFloat()), w * 0.09f, w * 0.09f, p)
        return b.asImageBitmap().also { it.prepareToDraw() }
    }

    private fun pic(tallShape: Boolean): ImageBitmap =
        if (tallShape) tall ?: make(120, 180).also { tall = it } else wide ?: make(192, 120).also { wide = it }

    /** Draws the glow around this element (call from drawBehind). [a] is 0…1 (fades with focus). */
    /** Settings → Glow size (0.3 … 1.6). */
    @Volatile var amount = 0.7f

    fun DrawScope.glow(color: Color, a: Float, tallShape: Boolean = false, grow: Float = 1f) {
        if (a <= 0.01f) return
        val img = pic(tallShape)
        val w = size.width * grow; val h = size.height * grow
        // The picture's blurred edge spreads out from the element; its reach is set by the size, measured from the
        // shorter side so wide cards don't get a huge halo.
        val padX = minOf(w, h) * PAD * 0.8f * amount; val padY = padX
        val left = (size.width - w) / 2 - padX; val top = (size.height - h) / 2 - padY
        // Only around the edges: the inside is left out, so glass tiles aren't tinted from behind.
        val hole = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect((size.width - w) / 2, (size.height - h) / 2, (size.width + w) / 2, (size.height + h) / 2,
                androidx.compose.ui.geometry.CornerRadius(w * 0.09f)))
        }
        clipPath(hole, androidx.compose.ui.graphics.ClipOp.Difference) {
            drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize((w + 2 * padX).toInt(), (h + 2 * padY).toInt()),
                alpha = (0.35f + 0.25f * amount).coerceAtMost(0.8f) * a, colorFilter = ColorFilter.tint(color, BlendMode.SrcIn))
        }
    }

    private val colors = LruCache<String, Int>(600)

    fun cached(key: String): Color? = colors.get(key)?.let { Color(it) }

    /** The picture's main colour, made bright and a little saturated so it reads as light. Blocking but quick. */
    fun colorOf(key: String, src: Bitmap?): Color? {
        colors.get(key)?.let { return Color(it) }
        src ?: return null
        val c = runCatching {
            val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
            val small = Bitmap.createScaledBitmap(soft, 8, 8, true)
            if (soft !== src) soft.recycle()
            var r = 0f; var g = 0f; var bl = 0f; var wsum = 0f
            val hsv = FloatArray(3)
            for (y in 0 until 8) for (x in 0 until 8) {
                val px = small.getPixel(x, y)
                if (android.graphics.Color.alpha(px) < 40) continue
                android.graphics.Color.colorToHSV(px, hsv)
                // Colourful pixels count most; black, white and grey barely.
                val wt = 0.08f + hsv[1] * hsv[2]
                r += android.graphics.Color.red(px) * wt; g += android.graphics.Color.green(px) * wt; bl += android.graphics.Color.blue(px) * wt; wsum += wt
            }
            small.recycle()
            if (wsum <= 0f) return@runCatching Color.White
            android.graphics.Color.RGBToHSV((r / wsum).toInt(), (g / wsum).toInt(), (bl / wsum).toInt(), hsv)
            hsv[1] = (hsv[1] * 1.15f).coerceIn(0f, 0.75f); hsv[2] = hsv[2].coerceAtLeast(0.9f)
            Color(android.graphics.Color.HSVToColor(hsv))
        }.getOrNull() ?: return null
        colors.put(key, c.toArgb())
        return c
    }
}
