package com.glasslauncher.tv.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Small pictures for the Hue lights' choices (Settings → Hue lights): a TV with the part of the screen a light
 * follows lit up and its glow on the wall, the poster or the logo, a colour wheel or triangle. Drawn once, kept.
 */
object HueArt {
    private const val W = 192
    private const val H = 108
    private val cache = HashMap<String, ImageBitmap>()
    private val warm = Color.rgb(255, 150, 60)
    private val cool = Color.rgb(80, 170, 255)
    private val pink = Color.rgb(240, 80, 160)

    private fun draw(key: String, block: Canvas.(Paint) -> Unit): ImageBitmap = synchronized(cache) {
        cache.getOrPut(key) {
            val b = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.drawColor(Color.rgb(28, 28, 32))
            c.block(Paint(Paint.ANTI_ALIAS_FLAG))
            b.asImageBitmap()
        }
    }

    /** The TV's screen inside the picture. */
    private val screen = RectF(46f, 22f, 146f, 78f)

    private fun Canvas.tv(p: Paint, fill: Int = Color.rgb(58, 58, 66)) {
        p.reset(); p.isAntiAlias = true
        p.color = Color.rgb(12, 12, 14); drawRoundRect(RectF(screen.left - 3, screen.top - 3, screen.right + 3, screen.bottom + 3), 5f, 5f, p)
        p.color = fill; drawRoundRect(screen, 3f, 3f, p)
        p.color = Color.rgb(12, 12, 14); drawRect(RectF(90f, 81f, 102f, 86f), p)
    }

    /** A soft light on the wall around [r] (in screen fractions pushed outwards). */
    private fun Canvas.glow(p: Paint, x: Float, y: Float, color: Int, radius: Float = 22f) {
        p.reset(); p.isAntiAlias = true
        p.color = color; p.alpha = 170; p.maskFilter = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
        drawCircle(x, y, radius * 0.9f, p)
        p.maskFilter = null
    }

    private fun Canvas.part(p: Paint, l: Float, t: Float, r: Float, b: Float, color: Int) {
        p.reset(); p.isAntiAlias = true; p.color = color
        val sw = screen.width(); val sh = screen.height()
        drawRect(RectF(screen.left + l * sw, screen.top + t * sh, screen.left + r * sw, screen.top + b * sh), p)
    }

    /** A part of the screen a light follows ([com.glasslauncher.tv.data.Hue.ZONES]). */
    fun zone(z: String): ImageBitmap = draw("zone:$z") { p ->
        val cx = screen.centerX(); val cy = screen.centerY()
        when (z) {
            "whole" -> { glow(p, cx, cy, warm, 46f); tv(p, warm) }
            "left" -> { glow(p, 26f, cy, warm); tv(p); part(p, 0f, 0f, 0.33f, 1f, warm) }
            "right" -> { glow(p, 166f, cy, warm); tv(p); part(p, 0.67f, 0f, 1f, 1f, warm) }
            "top" -> { glow(p, cx, 8f, warm); tv(p); part(p, 0f, 0f, 1f, 0.33f, warm) }
            "bottom" -> { glow(p, cx, 98f, warm); tv(p); part(p, 0f, 0.67f, 1f, 1f, warm) }
            "center" -> { tv(p); part(p, 0.25f, 0.25f, 0.75f, 0.75f, warm) }
            "topleft" -> { glow(p, 30f, 12f, warm); tv(p); part(p, 0f, 0f, 0.5f, 0.5f, warm) }
            "topright" -> { glow(p, 162f, 12f, warm); tv(p); part(p, 0.5f, 0f, 1f, 0.5f, warm) }
            "bottomleft" -> { glow(p, 30f, 96f, warm); tv(p); part(p, 0f, 0.5f, 0.5f, 1f, warm) }
            "bottomright" -> { glow(p, 162f, 96f, warm); tv(p); part(p, 0.5f, 0.5f, 1f, 1f, warm) }
            "gradient" -> {
                glow(p, 30f, cy, warm, 18f); glow(p, cx, 10f, pink, 18f); glow(p, 162f, cy, cool, 18f)
                tv(p)
                p.reset(); p.isAntiAlias = true; p.strokeWidth = 4f; p.style = Paint.Style.STROKE
                p.shader = LinearGradient(screen.left, 0f, screen.right, 0f, intArrayOf(warm, pink, cool), null, Shader.TileMode.CLAMP)
                val path = android.graphics.Path().apply {
                    moveTo(screen.left - 7, screen.bottom); lineTo(screen.left - 7, screen.top - 7)
                    lineTo(screen.right + 7, screen.top - 7); lineTo(screen.right + 7, screen.bottom)
                }
                drawPath(path, p); p.shader = null
            }
            else -> { // automatic: the picture's own colours, one per light
                glow(p, 26f, cy, warm, 16f); glow(p, cx, 8f, pink, 16f); glow(p, 166f, cy, cool, 16f)
                tv(p)
                part(p, 0f, 0f, 0.4f, 1f, warm); part(p, 0.4f, 0f, 0.7f, 1f, pink); part(p, 0.7f, 0f, 1f, 1f, cool)
            }
        }
    }

    /** What the colours come from: the whole picture, the poster or the logo. */
    fun source(s: String): ImageBitmap = draw("src:$s") { p ->
        when (s) {
            "poster" -> {
                tv(p)
                p.color = warm; drawRoundRect(RectF(84f, 26f, 108f, 74f), 3f, 3f, p)
                p.color = Color.argb(150, 0, 0, 0); drawRect(RectF(84f, 62f, 108f, 74f), p)
            }
            "logo" -> {
                tv(p)
                p.color = Color.WHITE; p.textSize = 15f; p.isFakeBoldText = true; p.textAlign = Paint.Align.CENTER
                drawText("LOGO", screen.centerX(), screen.centerY() + 5f, p)
                p.color = warm; drawRect(RectF(70f, 58f, 122f, 61f), p)
            }
            else -> {
                p.shader = LinearGradient(0f, screen.top, 0f, screen.bottom, intArrayOf(cool, warm), null, Shader.TileMode.CLAMP)
                tv(p, Color.TRANSPARENT); drawRoundRect(screen, 3f, 3f, p); p.shader = null
                p.color = Color.rgb(30, 40, 30); drawRect(RectF(screen.left, 62f, screen.right, screen.bottom), p)
            }
        }
    }

    /** How a light of another make takes its colour: hue and saturation (a wheel) or xy (the colour triangle). */
    fun method(m: String): ImageBitmap = draw("m:$m") { p ->
        if (m == "xy") {
            val path = android.graphics.Path().apply { moveTo(96f, 14f); lineTo(146f, 92f); lineTo(46f, 92f); close() }
            p.shader = LinearGradient(46f, 92f, 146f, 14f, intArrayOf(cool, Color.rgb(80, 220, 120), Color.rgb(255, 70, 60)), null, Shader.TileMode.CLAMP)
            drawPath(path, p); p.shader = null
        } else {
            p.shader = android.graphics.SweepGradient(96f, 54f, intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED), null)
            drawCircle(96f, 54f, 40f, p); p.shader = null
            p.color = Color.rgb(28, 28, 32); drawCircle(96f, 54f, 18f, p)
        }
    }

    /** A light blinking (to tell which one it is). */
    fun blink(): ImageBitmap = draw("blink") { p ->
        glow(p, 96f, 46f, Color.WHITE, 30f)
        p.color = Color.rgb(255, 245, 220); drawCircle(96f, 46f, 16f, p)
        p.color = Color.rgb(150, 150, 160); drawRoundRect(RectF(88f, 64f, 104f, 76f), 3f, 3f, p)
    }

    /** A colour swatch (the Settings switch colours). */
    fun swatch(argb: Int): ImageBitmap = draw("sw:$argb") { p ->
        p.color = argb; drawRoundRect(RectF(66f, 36f, 126f, 72f), 18f, 18f, p)
        p.color = Color.WHITE; drawCircle(108f, 54f, 14f, p)
    }
}
