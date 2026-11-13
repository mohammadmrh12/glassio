package com.glasslauncher.tv.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * Glassio's own artwork for a song that comes without one: a deep colour (one of a few, the same for the same song),
 * a soft light from the top corner, a glass disc with its rim and two joined notes. Drawn once per song, kept.
 */
object PlaceholderArt {
    private const val S = 512
    /** Pairs of colours, top-left to bottom-right: petrol, indigo, plum, wine, forest. */
    private val PALETTES = listOf(
        0xFF0E4A55.toInt() to 0xFF06171C.toInt(),
        0xFF2B2F6B.toInt() to 0xFF0B0C1F.toInt(),
        0xFF4A2459.toInt() to 0xFF140A19.toInt(),
        0xFF5A1F2E.toInt() to 0xFF18080D.toInt(),
        0xFF1F4A36.toInt() to 0xFF07150F.toInt(),
    )
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 6
    }

    @Synchronized
    fun of(key: String): Bitmap = cache.getOrPut(key) { draw(PALETTES[(key.hashCode() and 0x7fffffff) % PALETTES.size]) }

    private fun draw(colors: Pair<Int, Int>): Bitmap {
        val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, S.toFloat(), S.toFloat(), colors.first, colors.second, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, S.toFloat(), S.toFloat(), p)
        // Light from the top-left corner.
        p.shader = RadialGradient(S * 0.18f, S * 0.1f, S * 0.75f, Color.argb(70, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, S.toFloat(), S.toFloat(), p)
        p.shader = null
        // The glass disc: a light fill, a bright rim on top, a faint one below.
        val cx = S / 2f; val cy = S / 2f; val r = S * 0.30f
        p.color = Color.argb(30, 255, 255, 255); c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, Color.argb(150, 255, 255, 255), Color.argb(25, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null; p.style = Paint.Style.FILL
        // Two joined notes.
        p.color = Color.argb(235, 255, 255, 255)
        val headW = 46f; val headH = 34f
        fun head(x: Float, y: Float) {
            c.save(); c.rotate(-22f, x, y); c.drawOval(RectF(x - headW / 2, y - headH / 2, x + headW / 2, y + headH / 2), p); c.restore()
        }
        val lx = cx - 46f; val rx = cx + 50f; val ly = cy + 62f; val ry = cy + 44f
        head(lx, ly); head(rx, ry)
        val stem = 9f
        val lsx = lx + headW / 2 - 6f; val rsx = rx + headW / 2 - 6f
        c.drawRect(lsx - stem, cy - 74f, lsx, ly - 4f, p)
        c.drawRect(rsx - stem, cy - 92f, rsx, ry - 4f, p)
        val beam = Path().apply {
            moveTo(lsx - stem, cy - 74f); lineTo(rsx, cy - 92f); lineTo(rsx, cy - 66f); lineTo(lsx - stem, cy - 48f); close()
        }
        c.drawPath(beam, p)
        b.prepareToDraw()
        return b
    }
}
