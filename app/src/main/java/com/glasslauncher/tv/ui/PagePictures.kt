// Page pictures as the home screen and its glass draw them: decoding, baking (scaled, with the page's shades), the
// small caches, and the backgrounds of an app's rows. (Split out of HomeScreen.kt.)
package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.async
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Backdrops are decoded at the size they are prepared at (2/3 of the screen in Performance). */
@Volatile internal var backdropScale = 1f
/**
 * The 1280 px copy is the page picture with "High" and "1280 enhanced" (hd); the original otherwise. The pictures
 * prepared ahead used to load the original with "1280 enhanced" (a 4K JPEG decoded and scaled down for every
 * title around: seconds of processor at start-up and while browsing), then the slide made its own copy again.
 */
internal fun pageCopy(st: com.glasslauncher.tv.data.Settings) = st.imageQuality == "high" || st.imageQuality == "hd"

internal fun imgReq(ctx: android.content.Context, data: Any?, w: Int = 1920, h: Int = 1080) =
    ImageRequest.Builder(ctx).data(data).size(if (w == 1920) (w * backdropScale).toInt() else w, if (w == 1920) (h * backdropScale).toInt() else h)
        // Backdrops are baked into a new bitmap on the CPU (bakeBackdrop): decode them in software, a
        // hardware bitmap would have to be read back from the GPU first, which stalls the render thread.
        .apply { if (w == 1920) allowHardware(false) }
        .build()

/**
 * Getting the slides around the one on screen ready (decoding, baking): one low-priority thread. On the default
 * threads every slide change took two or three cores at once for a second (CPU jumping up and down while the
 * carousel plays by itself); here the same work is spread out and never competes with the slide's animation.
 */
internal val prepDispatcher: kotlinx.coroutines.CoroutineDispatcher = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
    Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glass-prep").apply { isDaemon = true }
}.asCoroutineDispatcher()

internal fun ImageRequest.prep(): ImageRequest = newBuilder().decoderCoroutineContext(prepDispatcher).build()

/** The last few baked backdrops (~8 MB each), keyed by picture and source bitmap. */
/**
 * Half-size copy of a baked page picture, for the clear glass: sharp (2x, not 4x, enlargement like the old
 * 480 px copy) and lined up exactly with the page, at a quarter of the texture reading a full-size copy costs.
 */
/** Test switch (adb extra page_glass): clear glass from the page picture, or from the small GlassCache copy. */
object PageGlass { val on = mutableStateOf(true) }

internal object GlassHalf {
    private val map = java.util.WeakHashMap<ImageBitmap, ImageBitmap>()
    @Synchronized fun peek(b: ImageBitmap?): ImageBitmap? = b?.let { map[it] }
    fun of(b: ImageBitmap): ImageBitmap {
        peek(b)?.let { return it }
        val a = b.asAndroidBitmap()
        val h = immutable(android.graphics.Bitmap.createScaledBitmap(a, a.width / 2, a.height / 2, true)).asImageBitmap()
        synchronized(this) { map[b] = h }
        return h
    }
}

/** Low memory: drop the prepared backdrops (the one on screen stays referenced by the page). */
fun trimPageCaches() { BakeCache.clear() }

internal object BakeCache {
    /** How many prepared backdrops to keep (by memory tier: 2 / 3 / 4). */
    @Volatile var max = 4
    @Synchronized fun clear() = map.clear()
    private val map = object : LinkedHashMap<String, Pair<Int, ImageBitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Int, ImageBitmap>>?) = size > max
    }
    /** Made ahead and never pushed out by browsing: the opening slide of each app's own carousel (three at most). */
    private val ahead = HashMap<String, Pair<Int, ImageBitmap>>()
    @Synchronized fun get(url: String, src: ImageBitmap): ImageBitmap? =
        (map[url] ?: ahead[url])?.takeIf { it.first == src.width * 10000 + src.height }?.second
    @Synchronized fun keep(url: String, src: ImageBitmap, baked: ImageBitmap) { ahead[url] = (src.width * 10000 + src.height) to baked }
    @Synchronized fun keepOnly(urls: Set<String>) { ahead.keys.retainAll(urls) }
    @Synchronized fun put(url: String, src: ImageBitmap, baked: ImageBitmap) { map[url] = (src.width * 10000 + src.height) to baked }
}

/**
 * The backdrop scaled to the screen (centre crop) with the two scrims painted in, as one bitmap.
 * Done once per picture on a background thread; drawing it is then a single full-screen copy.
 */
@Volatile internal var backdrop565 = false
/** "1280 enhanced": pictures smaller than the screen are sharpened after scaling up. */
@Volatile internal var backdropSharpen = false

/**
 * Unsharp mask (amount 0.55, a 3-pixel box blur), on the CPU, once per picture as it is prepared: a 1280 px picture
 * scaled to the screen looks as crisp as a bigger one, with no halos.
 */
private fun sharpen(b: android.graphics.Bitmap) {
    if (b.config != android.graphics.Bitmap.Config.ARGB_8888) return
    val w = b.width; val h = b.height
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    // Horizontal then vertical box blur of radius 1, per channel (packed).
    for (y in 0 until h) { val o = y * w
        for (x in 0 until w) {
            val a = px[o + maxOf(0, x - 1)]; val c = px[o + x]; val d = px[o + minOf(w - 1, x + 1)]
            tmp[o + x] = ((((a shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255)) / 3 shl 16) or
                ((((a shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255)) / 3 shl 8) or (((a and 255) + (c and 255) + (d and 255)) / 3)
        } }
    for (y in 0 until h) { val up = maxOf(0, y - 1) * w; val o = y * w; val dn = minOf(h - 1, y + 1) * w
        for (x in 0 until w) {
            val a = tmp[up + x]; val c = tmp[o + x]; val d = tmp[dn + x]
            val br = (((a shr 16) and 255) + ((c shr 16) and 255) + ((d shr 16) and 255)) / 3
            val bg = (((a shr 8) and 255) + ((c shr 8) and 255) + ((d shr 8) and 255)) / 3
            val bb = ((a and 255) + (c and 255) + (d and 255)) / 3
            val p = px[o + x]
            val r = (((p shr 16) and 255) * 155 - br * 55) / 100; val g = (((p shr 8) and 255) * 155 - bg * 55) / 100; val bl = ((p and 255) * 155 - bb * 55) / 100
            px[o + x] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
        } }
    b.setPixels(px, 0, w, 0, 0, w, h)
}

internal fun bakeBackdrop(src: ImageBitmap, w: Int, h: Int, hScrim: Boolean = true): ImageBitmap {
    var bmp = src.asAndroidBitmap()
    if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) bmp = bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    // Performance (small memory tier): 16-bit colour, dithered. Half the memory and half the GPU reads per frame.
    val out = android.graphics.Bitmap.createBitmap(w, h, if (backdrop565) android.graphics.Bitmap.Config.RGB_565 else android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    val scale = maxOf(w / bmp.width.toFloat(), h / bmp.height.toFloat())
    val dw = bmp.width * scale; val dh = bmp.height * scale
    val dst = android.graphics.RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
    c.drawBitmap(bmp, null, dst, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG))
    if (backdropSharpen && bmp.width < w * 0.9f) runCatching { sharpen(out) }
    val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
    p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.62f, 0f, 0xE0000000.toInt(), 0x00000000, android.graphics.Shader.TileMode.CLAMP)
    if (hScrim && !Scrims.sideOff) c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), p)
    p.shader = android.graphics.LinearGradient(0f, h * 0.58f, 0f, h.toFloat(), 0x00000000, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
    if (!Scrims.bottomOff) c.drawRect(0f, h * 0.58f, w.toFloat(), h.toFloat(), p)
    // Fully covered: marked opaque, so it is drawn without blending.
    out.setHasAlpha(false)
    return immutable(out).asImageBitmap()
}

/**
 * The GPU keeps an immutable bitmap's texture; a mutable one may be uploaded again whenever a surface that
 * draws it is re-recorded (every glass tile, on every focus move), which doubled the dock's GPU time.
 */
private fun immutable(b: android.graphics.Bitmap): android.graphics.Bitmap {
    val r = if (b.isMutable) b.copy(b.config ?: android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle() } else b
    r.prepareToDraw()
    return r
}

/** The channels' blurred-card background: the picture blurred, large, on black and darkened (like the music page). */
/** A page with the side shade drawn in (the Slide animation draws it over the page instead). */
internal fun withSideShade(page: ImageBitmap): ImageBitmap {
    val src = page.asAndroidBitmap()
    val out = src.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawBitmap(Scrims.side.asAndroidBitmap(), null, android.graphics.RectF(0f, 0f, out.width.toFloat(), out.height.toFloat()),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG))
    return out.asImageBitmap().also { GlassHalf.of(it) }
}

private fun channelArtPage(src: android.graphics.Bitmap, w: Int, h: Int): ImageBitmap {
    val art = if (src.config == android.graphics.Bitmap.Config.HARDWARE) src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else src
    val blur = blurredBackdrop(art, 70).asAndroidBitmap()
    val out = android.graphics.Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    c.drawColor(android.graphics.Color.BLACK)
    val sc = maxOf(w.toFloat() / blur.width, h.toFloat() / blur.height)
    val dw = blur.width * sc; val dh = blur.height * sc
    c.drawBitmap(blur, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2),
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG).apply { alpha = (0.85f * 255).toInt() })
    c.drawColor(android.graphics.Color.argb((ChannelPages.dim * 255).toInt(), 0, 0, 0))
    out.setHasAlpha(false)
    return out.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { out.recycle(); it.prepareToDraw() }.asImageBitmap()
}

/**
 * The channels' backgrounds, made once and kept: the grey page (made ahead), and the last few cards' blurred
 * pictures (a card come back to is shown at once). Each with its copies for the glass.
 */
internal object ChannelPages {
    private var grey: Triple<String, ImageBitmap, GlassImages>? = null
    private val arts = android.util.LruCache<String, Pair<ImageBitmap, GlassImages>>(16)
    /** The quiet thread that makes the blurs ahead. */
    val warmer: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "glass-chan-blur").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    /** The page's size and the key its pictures are kept under (they are made again when any of it changes). */
    fun size(ctx: android.content.Context, st: com.glasslauncher.tv.data.Settings): Triple<Int, Int, String> {
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * st.pageScale).toInt(); val h = (dm.heightPixels * st.pageScale).toInt()
        dim = (0.45f - (st.channelsBlurBri - 100) * 0.006f).coerceIn(0.1f, 0.8f)
        return Triple(w, h, "$w|$h|${st.blurRadius}|${st.glassRes}|${st.channelsBlurBri}")
    }
    /** How dark the blurred card is made (Settings → Channels → Background brightness). */
    @Volatile var dim = 0.45f
    fun peekGrey(tag: String): Pair<ImageBitmap, GlassImages>? = grey?.takeIf { it.first == tag }?.let { it.second to it.third }
    private var last: Pair<String, Pair<ImageBitmap, GlassImages>>? = null
    /** The background shown last (a new page starts with it while the current card's is made). */
    fun remember(tag: String, p: Pair<ImageBitmap, GlassImages>) { last = tag to p }
    fun peekLast(tag: String): Pair<ImageBitmap, GlassImages>? = last?.takeIf { it.first == tag }?.second
    @Synchronized fun grey(tag: String, w: Int, h: Int, blur: Int): Pair<ImageBitmap, GlassImages> = peekGrey(tag) ?: run {
        val g = greyPage(w, h)
        GlassHalf.of(g)
        val gl = GlassCache.fromBitmap("glassio:channels:$tag", blur, g.asAndroidBitmap())
        grey = Triple(tag, g, gl)
        g to gl
    }
    // Each app's first card, kept apart: the cards on screen made ahead pushed them out, and selecting an app then
    // showed the grey page first and its blur a moment later (the page brightened once its rows were up).
    private val firsts = android.util.LruCache<String, Pair<ImageBitmap, GlassImages>>(14)
    fun keepFirst(link: String, tag: String, p: Pair<ImageBitmap, GlassImages>) { firsts.put("$tag|$link", p) }
    fun peekArt(link: String, tag: String): Pair<ImageBitmap, GlassImages>? = arts.get("$tag|$link") ?: firsts.get("$tag|$link")
    /** Made at a quarter of the screen (it is a blur: drawn larger it looks the same, and it is ready much sooner). */
    fun art(link: String, b: android.graphics.Bitmap, tag: String, w: Int, h: Int, blur: Int): Pair<ImageBitmap, GlassImages> = peekArt(link, tag) ?: run {
        val pg = channelArtPage(b, w / 4, h / 4)
        GlassHalf.of(pg)
        val gl = GlassCache.fromBitmap("glassio:channels-art:$tag|$link", blur, pg.asAndroidBitmap())
        (pg to gl).also { arts.put("$tag|$link", it) }
    }
}

/**
 * The page behind the music apps, an idle player and the inputs (Settings → Dock & icons → Music & Inputs page):
 * the grey page, Glassio's soft lights (the setup's and the profiles' background), or the carousel's picture
 * blurred (made from its ready glass copy, like the rows' blurred card). Made ahead and kept, each with its copies
 * for the glass, so the page and the glass change together and nothing is blurred while drawing.
 */
internal object StagePages {
    private var lights: Triple<String, ImageBitmap, GlassImages>? = null
    private val heroes = android.util.LruCache<String, Pair<ImageBitmap, GlassImages>>(3)
    /** The last blurred carousel picture made (shown while the next one isn't there yet). */
    @Volatile private var lastHero: Pair<String, Pair<ImageBitmap, GlassImages>>? = null

    fun peek(mode: String, url: String?, tag: String): Pair<ImageBitmap, GlassImages>? = when (mode) {
        "glassio" -> lights?.takeIf { it.first == tag }?.let { it.second to it.third }
        "carousel" -> url?.let { heroes.get("$tag|$it") } ?: lastHero?.takeIf { it.first == tag }?.second
        else -> ChannelPages.peekGrey(tag)
    }

    /** Null: nothing to make it from yet (the carousel's picture has no glass copy). Off the main thread. */
    @Synchronized fun make(mode: String, url: String?, tag: String, w: Int, h: Int, blur: Int): Pair<ImageBitmap, GlassImages>? = when (mode) {
        "glassio" -> peek(mode, url, tag) ?: run {
            // (A quarter of the screen: soft lights look the same drawn larger.)
            val b = orbsPicture((w / 4).coerceAtLeast(1), (h / 4).coerceAtLeast(1), 0.28f)
            b.setHasAlpha(false)
            val pg = b.copy(android.graphics.Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap()
            GlassHalf.of(pg)
            val gl = GlassCache.fromBitmap("glassio:lights:$tag", blur, pg.asAndroidBitmap())
            lights = Triple(tag, pg, gl)
            pg to gl
        }
        "carousel" -> if (url == null) null else heroes.get("$tag|$url") ?: GlassCache.peek(url, blur)?.sharp?.let { src ->
            val pg = channelArtPage(src.asAndroidBitmap(), w / 4, h / 4)
            GlassHalf.of(pg)
            val gl = GlassCache.fromBitmap("glassio:stage-hero:$tag|$url", blur, pg.asAndroidBitmap())
            (pg to gl).also { heroes.put("$tag|$url", it); lastHero = tag to it }
        }
        else -> ChannelPages.grey(tag, w, h, blur)
    }
}
