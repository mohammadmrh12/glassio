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

    /** Glow pictures made for one element size (half size, blurred), so the halo is the same width on every side. */
    private val shaped = LruCache<String, ImageBitmap>(24)

    /**
     * The glow made for exactly this shape: a rounded rectangle of [w] × [h] with [corner], blurred out by [pad].
     * (The one stretched picture was made for one shape: on other sizes its halo came out thicker on some sides than
     * on others, weak along the edges.)
     */
    private fun shapedPic(w: Float, h: Float, pad: Float, corner: Float): ImageBitmap {
        val key = "${(w / 2).toInt()}x${(h / 2).toInt()}x${(pad / 2).toInt()}x${(corner / 2).toInt()}"
        shaped.get(key)?.let { return it }
        val k = 0.5f
        val pw = (pad * k); val iw = w * k; val ih = h * k
        val b = Bitmap.createBitmap((iw + 2 * pw).toInt().coerceAtLeast(2), (ih + 2 * pw).toInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; maskFilter = BlurMaskFilter((pw * 0.7f).coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL) }
        Canvas(b).drawRoundRect(RectF(pw, pw, pw + iw, pw + ih), corner * k, corner * k, p)
        return b.asImageBitmap().also { it.prepareToDraw(); shaped.put(key, it) }
    }

    /**
     * [maxReach]: the most the halo may spread (px), e.g. the room around an icon inside the dock (the dock clips
     * what goes past its edge: the halo was cut above and below and looked uneven).
     */
    fun DrawScope.glow(color: Color, a: Float, tallShape: Boolean = false, grow: Float = 1f, corner: Float = -1f, amt: Float = amount,
                       maxReach: Float = Float.MAX_VALUE) {
        if (a <= 0.01f) return
        val w0 = size.width; val h0 = size.height
        // The picture's blurred edge spreads out from the element; its reach is set by the size, measured from the
        // shorter side so wide cards don't get a huge halo.
        val pad = minOf(minOf(w0, h0) * PAD * 0.8f * amt, maxReach / grow).coerceAtLeast(2f)
        val cr = if (corner >= 0f) corner else w0 * 0.09f
        val img = shapedPic(w0, h0, pad, cr)
        val w = w0 * grow; val h = h0 * grow; val pg = pad * grow
        val left = (w0 - w) / 2 - pg; val top = (h0 - h) / 2 - pg
        // Only around the edges: the inside is left out, so glass tiles aren't tinted from behind. The hole is a
        // little inside the element's edge: right on it, the soft edges of both left a hairline of the page between
        // them (a thin light line at the side, seen when the picture behind changed).
        val inset = 1.5f
        val hole = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect((w0 - w) / 2 + inset, (h0 - h) / 2 + inset, (w0 + w) / 2 - inset, (h0 + h) / 2 - inset,
                androidx.compose.ui.geometry.CornerRadius((cr * grow - inset).coerceAtLeast(0f))))
        }
        clipPath(hole, androidx.compose.ui.graphics.ClipOp.Difference) {
            drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height),
                dstOffset = IntOffset(left.toInt(), top.toInt()), dstSize = IntSize((w + 2 * pg).toInt(), (h + 2 * pg).toInt()),
                alpha = (0.45f + 0.3f * amt).coerceAtMost(0.9f) * a, colorFilter = ColorFilter.tint(color, BlendMode.SrcIn),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
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
