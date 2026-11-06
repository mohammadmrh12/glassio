package com.glasslauncher.tv.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidColorFilter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.Settings
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Where the large clock sits, as parts of the screen (the depth effect checks what covers this). Its bottom stays
 * put when you make it smaller (it gets lower, not higher), so what stands up in the photo still reaches it.
 */
private const val CLOCK_BASE = 0.355f
/** [off]: the Large clock position setting (up is negative), as a part of the screen's height. */
private fun clockTop(scale: Float, off: Float = 0f) = (CLOCK_BASE - 0.27f * scale + off).coerceIn(0.02f, 0.75f)
private fun clockBottom(scale: Float, off: Float = 0f) = clockTop(scale, off) + 0.27f * scale
private fun clockSide(scale: Float) = (0.5f - 0.2f * scale).coerceAtLeast(0.05f)

/**
 * Depth effect, like a phone's lock screen: what stands in front in the photo (a mountain, a tower, a bridge, a
 * branch) is cut out and drawn again over the large clock, so the clock sits behind it.
 *
 * Worked out on the TV, once per photo, with a small depth model (MiDaS small, MIT licence, 33 MB, downloaded the
 * first time the effect is used): it tells near from far. What is clearly nearer than the far part at the top of
 * the picture (the sky) is the foreground; its outline is then sharpened against the sky's colours. A photo with no
 * open sky at the top (a close-up, a forest) leaves the clock in front.
 */
object Depth {
    /** The cut-out over the clock: the picture's pixels in front, and where they go (parts of the screen). */
    class Cut(val img: ImageBitmap, val left: Float, val right: Float, val bottom: Float)

    /** The cut-out for the picture on screen, or null (none yet, nothing in front of the clock, or turned off). */
    val fg = mutableStateOf<Cut?>(null)
    // The last few pictures only (each cut-out is a few MB).
    private val made = object : LinkedHashMap<String, Cut?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cut?>?) = size > 3
    }
    /** What the depth effect is doing (Settings shows it). */
    @Volatile var status = ""; private set

    private const val MODEL_URL = "https://huggingface.co/litert-community/MiDaS-small/resolve/main/midas_small_256_fp16.tflite"
    private const val MODEL_SIZE = 33_507_904L
    private fun modelFile(ctx: android.content.Context) = java.io.File(ctx.filesDir, "models/midas_small_256_fp16.tflite")

    /** Turned off or another background: nothing kept in memory. */
    fun clear() { synchronized(made) { made.clear() }; fg.value = null }

    /** Blocking (a few seconds; the first time also the model download). [page] is the picture as the page shows it. */
    fun cutout(ctx: android.content.Context, page: ImageBitmap, clockScale: Float, off: Float = 0f): Cut? {
        val key = "${System.identityHashCode(page)}|$clockScale|$off"
        synchronized(made) { if (made.containsKey(key)) return made[key] }
        val model = runCatching { model(ctx) }.onFailure { Glog.w("Depth model", it); status = "Couldn't download the depth model (${it.message})" }.getOrNull()
            ?: return null
        val out = runCatching { make(model, page, clockScale, off) }.onFailure { Glog.w("Depth effect", it); status = "Depth effect failed: ${it.message}" }.getOrNull()
        synchronized(made) { made[key] = out }
        return out
    }

    @Synchronized private fun model(ctx: android.content.Context): java.io.File {
        val f = modelFile(ctx)
        if (f.length() == MODEL_SIZE) return f
        f.parentFile?.mkdirs()
        status = "Downloading the depth model (33 MB)…"
        Glog.i("Depth: downloading the model")
        val part = java.io.File(f.path + ".part")
        var url = java.net.URL(MODEL_URL)
        repeat(5) {
            val c = url.openConnection() as java.net.HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 30_000
            try {
                val code = c.responseCode
                if (code in 300..399) { url = java.net.URL(url, c.getHeaderField("Location")); return@repeat }
                check(code == 200) { "HTTP $code" }
                c.inputStream.use { inp -> part.outputStream().use { inp.copyTo(it, 1 shl 16) } }
            } finally { c.disconnect() }
            check(part.length() == MODEL_SIZE) { "incomplete download (${part.length()} bytes)" }
            check(part.renameTo(f)) { "couldn't save it" }
            status = ""
            Glog.i("Depth: model ready")
            return f
        }
        error("too many redirects")
    }

    private fun make(model: java.io.File, page: ImageBitmap, clockScale: Float, off: Float): Cut? {
        val t0 = android.os.SystemClock.uptimeMillis()
        var src = page.asAndroidBitmap()
        if (src.config != Bitmap.Config.ARGB_8888) src = src.copy(Bitmap.Config.ARGB_8888, false)
        // 1. Depth: the picture squeezed to 256 × 256 for the model (near = large values).
        val n = 256
        val sq = Bitmap.createScaledBitmap(src, n, n, true)
        val sp = IntArray(n * n); sq.getPixels(sp, 0, n, 0, 0, n, n); sq.recycle()
        val input = java.nio.ByteBuffer.allocateDirect(n * n * 3 * 4).order(java.nio.ByteOrder.nativeOrder())
        for (c in sp) {
            input.putFloat((((c shr 16) and 255) / 255f - 0.485f) / 0.229f)
            input.putFloat((((c shr 8) and 255) / 255f - 0.456f) / 0.224f)
            input.putFloat(((c and 255) / 255f - 0.406f) / 0.225f)
        }
        input.rewind()
        val raw = Array(1) { Array(n) { FloatArray(n) } }
        // Loaded for this one picture and closed again straight after (it takes ~70 MB while it runs).
        org.tensorflow.lite.Interpreter(model, org.tensorflow.lite.Interpreter.Options().setNumThreads(2)).use { it.run(input, raw) }
        // 2. To the working size (the photo's shape), 0..1 between the 2nd and 98th percentile.
        val w = 384; val h = (w * src.height / src.width.coerceAtLeast(1)).coerceAtLeast(8)
        val d = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val fx = (x + 0.5f) * n / w - 0.5f; val fy = (y + 0.5f) * n / h - 0.5f
            val x0 = fx.toInt().coerceIn(0, n - 1); val y0 = fy.toInt().coerceIn(0, n - 1)
            val x1 = (x0 + 1).coerceAtMost(n - 1); val y1 = (y0 + 1).coerceAtMost(n - 1)
            val ax = (fx - x0).coerceIn(0f, 1f); val ay = (fy - y0).coerceIn(0f, 1f)
            val r0 = raw[0][y0]; val r1 = raw[0][y1]
            d[y * w + x] = (r0[x0] * (1 - ax) + r0[x1] * ax) * (1 - ay) + (r1[x0] * (1 - ax) + r1[x1] * ax) * ay
        }
        val sorted = d.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.02f).toInt()]; val hi = sorted[(sorted.size * 0.98f).toInt()]
        for (i in d.indices) d[i] = ((d[i] - lo) / (hi - lo).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
        // 3. The far part at the top: the depth that the top of the picture mostly has.
        val topRows = (h / 20).coerceAtLeast(2)
        val top = FloatArray(topRows * w) { d[it] }.also { it.sort() }
        val far = top[(top.size * 0.6f).toInt()]
        val near = BooleanArray(w * h) { d[it] > far + 0.1f }
        // 4. The outline sharpened: the sky grown from the top over smooth colour, as far as depth allows.
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h); small.recycle()
        val r = FloatArray(w * h); val g = FloatArray(w * h); val b = FloatArray(w * h)
        for (i in px.indices) { val c = px[i]; r[i] = ((c shr 16) and 255).toFloat(); g[i] = ((c shr 8) and 255).toFloat(); b[i] = (c and 255).toFloat() }
        repeat(2) { box3(r, w, h); box3(g, w, h); box3(b, w, h) }
        fun diff(i: Int, j: Int) = maxOf(kotlin.math.abs(r[i] - r[j]), kotlin.math.abs(g[i] - g[j]), kotlin.math.abs(b[i] - b[j]))
        val sky = BooleanArray(w * h)
        val queue = IntArray(w * h); var head = 0; var tail = 0
        for (x in 0 until w) if (!near[x]) { sky[x] = true; queue[tail++] = x }
        while (head < tail) {
            val i = queue[head++]; val x = i % w; val y = i / w
            fun visit(j: Int) { if (!sky[j] && d[j] < far + 0.15f && diff(i, j) < 4.5f) { sky[j] = true; queue[tail++] = j } }
            if (x > 0) visit(i - 1); if (x < w - 1) visit(i + 1); if (y > 0) visit(i - w); if (y < h - 1) visit(i + w)
        }
        val fgm = BooleanArray(w * h) { near[it] && !sky[it] }
        // 5. Worth it? Open sky at the top, and the foreground covering the clock's lower part but not all of it.
        val x0 = (clockSide(clockScale) * w).toInt(); val x1 = w - x0
        val y0 = (clockTop(clockScale, off) * h).toInt(); val y1 = (clockBottom(clockScale, off) * h).toInt().coerceAtMost(h)
        val band = y0 + (y1 - y0) * 35 / 100
        var inClock = 0; var clockPx = 0; var inBand = 0; var bandPx = 0; var topNear = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            clockPx++; if (fgm[y * w + x]) inClock++
            if (y < band) { bandPx++; if (fgm[y * w + x]) inBand++ }
        }
        for (i in 0 until topRows * w) if (fgm[i]) topNear++
        // Open sky: far and smooth (a sky changes colour slowly; leaves, rock and fabric don't).
        var skyTop = 0
        for (x in 1 until w - 1) if (sky[x] && diff(x, x - 1) < 4.5f && diff(x, x + 1) < 4.5f && diff(x, x + w) < 4.5f) skyTop++
        val share = if (clockPx == 0) 0f else inClock.toFloat() / clockPx
        val bandShare = if (bandPx == 0) 0f else inBand.toFloat() / bandPx
        val topShare = topNear.toFloat() / (topRows * w)
        val open = skyTop.toFloat() / w
        Glog.i("Depth effect: far ${"%.2f".format(far)}, open sky ${"%.0f".format(open * 100)}%, top ${"%.0f".format(topShare * 100)}% near, " +
            "${"%.0f".format(share * 100)}% of the clock behind (${android.os.SystemClock.uptimeMillis() - t0} ms)")
        if (far > 0.12f || open < 0.5f || topShare > 0.4f || share < 0.015f || share > 0.45f || bandShare > 0.35f) return null
        // 6. The cut-out, only the part around the clock (not a whole screen kept in memory), with a soft edge.
        val a = FloatArray(w * h) { if (fgm[it]) 1f else 0f }
        box3(a, w, h)
        val m = IntArray(w * h) { (((a[it] * a[it] * (3 - 2 * a[it])) * 255).toInt() shl 24) or 0xFFFFFF }
        val mask = Bitmap.createBitmap(m, w, h, Bitmap.Config.ARGB_8888)
        val left = clockSide(clockScale) * 0.8f; val right = 1f - left; val bottom = (clockBottom(clockScale, off) + 0.04f).coerceAtMost(1f)
        val l = (left * src.width).toInt(); val rr = (right * src.width).toInt(); val bt = (bottom * src.height).toInt()
        val out = Bitmap.createBitmap(rr - l, bt, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawBitmap(src, android.graphics.Rect(l, 0, rr, bt), android.graphics.Rect(0, 0, rr - l, bt), null)
        val ml = left * w; val mr = right * w; val mb = bottom * h
        cv.drawBitmap(mask, android.graphics.Rect(ml.toInt(), 0, mr.roundToInt().coerceAtMost(w), mb.roundToInt().coerceAtMost(h)), android.graphics.Rect(0, 0, rr - l, bt),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN) })
        mask.recycle()
        if (src !== page.asAndroidBitmap()) src.recycle()
        val img = out.copy(Bitmap.Config.ARGB_8888, false).also { out.recycle(); it.prepareToDraw() }.asImageBitmap()
        return Cut(img, left, right, bottom)
    }

    private fun box3(a: FloatArray, w: Int, h: Int) {
        val t = FloatArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            t[i] = (a[i] + a[if (x > 0) i - 1 else i] + a[if (x < w - 1) i + 1 else i]) / 3f
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            a[i] = (t[i] + t[if (y > 0) i - w else i] + t[if (y < h - 1) i + w else i]) / 3f
        }
    }
}

/** How the large clock is drawn: plain white, or exactly as the dock's own glass / plate is drawn. */
internal fun clockLook(st: Settings): String =
    // Black on white and Black AMOLED: white digits (their flat plates disappeared on photos).
    if (st.bgClockStyle == "white" || st.dockStyle == "bow" || st.dockStyle == "amoled") "white"
    // Still Glass whatever the theme (and no blur).
    else if (st.bgClockStyle == "still") "water_still" else st.dockStyle

/** The large clock over your own background (date above, time below), with the depth cut-out drawn over it. */
@Composable
fun BigClock(st: Settings, s: Float, inSaver: Boolean = false) {
    val k = st.bgClockSize
    val off = st.bgClockOffset
    // Like a phone's lock screen: no AM / PM (it made the clock much wider).
    fun now() = clockText(st.clock24h).replace(Regex("\\s*[AaPp]\\.?[Mm]\\.?$"), "")
    val time by produceState(now(), st.clock24h) { while (true) { value = now(); delay(5_000) } }
    val date by produceState(bigDate(), Unit) { while (true) { value = bigDate(); delay(60_000) } }
    val look = clockLook(st)
    val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.28f), blurRadius = 18f))
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = maxHeight
        // Its own layer: drawn again only when it changes (the time, or the picture in glass digits), not with
        // every frame of the page around it. White digits are kept as a ready texture (their soft shadow is costly).
        Column(Modifier.fillMaxWidth().padding(top = h * clockTop(k, off))
            .graphicsLayer { if (look == "white") compositingStrategy = CompositingStrategy.Offscreen },
            horizontalAlignment = Alignment.CenterHorizontally) {
            val small = TextStyle(fontSize = (12 * s * k).sp, fontWeight = FontWeight.SemiBold)
            val big = TextStyle(fontSize = (118 * s * k).sp, lineHeight = (118 * s * k).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-2 * s * k).sp)
            // The date in the same style as the time.
            val blurOn = st.blurRadius > 0 && st.bgClockStyle != "still"
            // The date can be white on its own (Settings), whatever the time's look.
            if (look == "white" || st.bgClockDateWhite) Text(date, color = Color.White.copy(alpha = 0.9f), style = small.merge(shade))
            else ThemedNumerals(date, small, look, blurOn = blurOn, rim = false, saver = inSaver)
            if (look == "white") Text(time, color = Color.White.copy(alpha = 0.94f), style = big.merge(shade))
            else ThemedNumerals(time, big, look, blurOn = blurOn, saver = inSaver)
        }
        // Depth: the subject drawn again over the clock, only where the clock is.
        val cut = if (inSaver) null else Depth.fg.value
        val a by animateFloatAsState(if (cut != null) 1f else 0f, tween(450), label = "depth")
        val last = remember { arrayOfNulls<Depth.Cut>(1) }
        if (cut != null) last[0] = cut
        val c = last[0]
        // With the Slide animation the page's side shade is drawn over the picture (not baked in): the cut-out gets
        // the same shade, only on itself (its own layer), so it matches the photo under it exactly.
        val sideOnTop = st.slideAnim != "fade"
        if (c != null && a > 0.01f) Canvas(Modifier.fillMaxSize().graphicsLayer {
            alpha = a; if (sideOnTop) compositingStrategy = CompositingStrategy.Offscreen
        }) {
            val l = c.left * size.width; val r = c.right * size.width; val b = c.bottom * size.height
            drawImage(c.img, dstOffset = IntOffset(l.roundToInt(), 0), dstSize = IntSize((r - l).roundToInt(), b.roundToInt()), filterQuality = FilterQuality.Low)
            if (sideOnTop) drawImage(Scrims.side, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low, blendMode = BlendMode.SrcAtop)
        }
    }
}

/**
 * The digits made the way the dock is drawn in each theme:
 *  - Frosted Glass: the blurred picture behind, darkened by the dock's own tint;
 *  - Clear: the blurred picture with the clear theme's light veil;
 *  - Still / Dynamic Glass: the sharp picture, slightly magnified like the dock's glass bends it, no tint;
 *  - Black AMOLED: the dock's black; Black on white: the dock's soft off-white.
 * A bright edge along the top of every stroke and a faint dark one underneath give the glass its rim (from the
 * filled digits themselves, so no inner outlines show where the font's strokes overlap).
 */
@Composable
private fun ThemedNumerals(text: String, style: TextStyle, look: String, blurOn: Boolean, rim: Boolean = true, saver: Boolean = false) {
    val env = LocalGlassEnv.current
    // The screen's size (the screensaver has no home page around it to give it).
    val rootView = androidx.compose.ui.platform.LocalView.current.rootView
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The digits' shape and their rim are painted once (when the time changes), not on every frame. Each frame
    // then draws the picture through that shape in one pass (a shader, no extra layer, no blur pass): cheap enough
    // to follow every new copy of a video, in step with the dock's glass.
    val art = remember(text, style, rim) {
        val layout = tm.measure(text, style)
        val w = layout.size.width.coerceAtLeast(1); val h = layout.size.height.coerceAtLeast(1)
        val edge = 1.6f * density.density
        fun paint(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): ImageBitmap =
            ImageBitmap(w, h).also { img ->
                androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr,
                    androidx.compose.ui.graphics.Canvas(img), androidx.compose.ui.geometry.Size(w.toFloat(), h.toFloat()), block)
                img.prepareToDraw()
            }
        val mask = paint { drawText(layout, color = Color.White) }
        // The rim: light along the top of each stroke, a little shadow along the bottom (like the Still Glass rim).
        val rimImg = if (rim) paint {
            drawText(layout, color = Color.White.copy(alpha = 0.7f))
            drawText(layout, color = Color.Black, topLeft = Offset(0f, edge), blendMode = BlendMode.DstOut)
        } else null
        val shadeImg = if (rim) paint {
            drawText(layout, color = Color.Black.copy(alpha = 0.22f))
            drawText(layout, color = Color.Black, topLeft = Offset(0f, -edge), blendMode = BlendMode.DstOut)
        } else null
        Triple(mask, rimImg, shadeImg)
    }
    val (mask, rimImg, shadeImg) = art
    // A state, so the picture in the digits follows them when the page moves (down to the Library and back):
    // it stayed where it was until the next picture came.
    val posS = remember { androidx.compose.runtime.mutableStateOf(Offset.Zero) }
    val size = with(density) { androidx.compose.ui.unit.DpSize(mask.width.toDp(), mask.height.toDp()) }
    val glass = look == "liquid" || look == "clear" || look.startsWith("water")
    // The dock's own tint for this theme, as a colour filter on the same pass.
    val tint = remember(look) {
        when (look) {
            "liquid" -> android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(0.86f, 0.86f, 0.86f, 1f) })
            "clear" -> android.graphics.ColorMatrixColorFilter(floatArrayOf(0.94f, 0f, 0f, 0f, 15.3f, 0f, 0.94f, 0f, 0f, 15.3f, 0f, 0f, 0.94f, 0f, 15.3f, 0f, 0f, 0f, 1f, 0f))
            else -> null
        }
    }
    val m = remember { android.graphics.Matrix() }
    val paint = remember { android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.DITHER_FLAG) }
    // Glass blur (Settings): the same blur as the glass, made on the GPU from the sharp picture (the small blurred
    // copies, scaled up this much, came out in blocks).
    val blurPx = if (blurOn && glass) env.blurDp * density.density else 0f
    val blur = remember(blurPx) {
        if (blurPx < 0.5f || android.os.Build.VERSION.SDK_INT < 31) null
        else android.graphics.RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
    }
    val fill = if (look == "bow") Color(0xF2DCDCE0) else Color.Black.copy(alpha = 0.94f)
    // Drawn like the Still Glass: the full-size sharp picture behind (the page's own, or the video's copy), a little
    // magnified like the glass bends it, then cut to the digits; the theme's tint on top of it.
    Box(Modifier.size(size).onGloballyPositioned { posS.value = it.positionInRoot() }
        .graphicsLayer { if (glass) compositingStrategy = CompositingStrategy.Offscreen }) {
        if (glass) Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = blur }) {
            val pos = arrayOf(posS.value)
            val video = VideoFrames.showing.value
            // On the screensaver: live copies of its own video (com.glasslauncher.tv.saver.SaverFrames).
            val pic = if (saver) com.glasslauncher.tv.saver.SaverFrames.sharp.value
                else (if (video) VideoFrames.sharp.value ?: VideoFrames.soft.value else null) ?: env.sharp ?: env.bmp
            val rootW = if (env.rootW > 0) env.rootW else rootView.width
            val rootH = if (env.rootH > 0) env.rootH else rootView.height
            // No picture to show through (the screensaver's own videos): see-through glass, the video behind it
            // seen through a light tint of the theme.
            if (pic == null || rootW <= 0) drawRect(Color.White.copy(alpha = when (look) { "liquid" -> 0.55f; "clear" -> 0.45f; else -> 0.36f }))
            if (pic != null && rootW > 0) {
                val mag = 1.06f
                val sc0 = max(rootW / pic.width.toFloat(), rootH / pic.height.toFloat())
                val cx = pos[0].x + this.size.width / 2f; val cy = pos[0].y + this.size.height / 2f
                val ox = cx + ((rootW - pic.width * sc0) / 2f - cx) * mag - pos[0].x
                val oy = cy + ((rootH - pic.height * sc0) / 2f - cy) * mag - pos[0].y
                m.reset(); m.setScale(sc0 * mag, sc0 * mag); m.postTranslate(ox, oy)
                paint.colorFilter = tint
                drawIntoCanvas { it.nativeCanvas.drawBitmap(pic.asAndroidBitmap(), m, paint) }
                // Between two videos the background fades through black: the digits darken with it.
                val d = if (saver) com.glasslauncher.tv.saver.SaverFrames.dim.floatValue else VideoFrames.dim.floatValue
                if (d > 0.003f) drawRect(Color.Black.copy(alpha = d))
            }
        }
        Canvas(Modifier.matchParentSize()) {
            if (glass) drawImage(mask, blendMode = BlendMode.DstIn)
            else drawImage(mask, colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fill, BlendMode.SrcIn))
            rimImg?.let { drawImage(it) }
            shadeImg?.let { drawImage(it) }
        }
    }
}

private fun bigDate(): String = java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.ENGLISH).format(java.util.Date())
