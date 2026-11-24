package com.glasslauncher.tv.ui

import android.graphics.Bitmap
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What every glass surface needs: the page backdrop, already blurred once for everybody. */
data class GlassEnv(
    /** Blurred backdrop (the "Glass blur" setting). */
    val bmp: ImageBitmap?,
    /** Unblurred backdrop, for the clear "Glass like glass" look. */
    val sharp: ImageBitmap?,
    /** Heavily blurred backdrop, for the Apps folder (like tvOS). */
    val heavy: ImageBitmap?,
    val rootW: Int, val rootH: Int,
    val tint: Int, val refractionDp: Int,
    /** 1 when "Glass like glass" surfaces may ripple; the ripple itself is read from [live]. */
    val wobble: Float = 0f,
    /**
     * 0..1, animated briefly after a move in the dock. Read only while drawing, so a ripple redraws the
     * glass without recomposing the screen.
     */
    val live: androidx.compose.runtime.State<Float>? = null,
    /** The previous backdrop, faded out underneath while the new one fades in (same timing as the page). */
    val prevBmp: ImageBitmap? = null,
    val prevSharp: ImageBitmap? = null,
    /** 0..1 progress of that crossfade; read only while drawing. */
    val mix: androidx.compose.runtime.State<Float>? = null,
    /** 0..1 while the dock is open: the whole page, glass included, turns blurred and dark. */
    val veil: androidx.compose.runtime.State<Float>? = null,
    /**
     * True when [sharp]/[prevSharp] are the full-screen page pictures themselves (scrims already baked in):
     * the clear glass then shows the picture at full screen resolution instead of a small copy blown up
     * (which looked pixelated and broken up behind the dock), and lines up with the page exactly.
     */
    val sharpIsPage: Boolean = false,
    /** Slide animation in progress: 1 / -1 = the new picture comes in from the right / left, 0 = crossfade. */
    val slide: Int = 0,
    /** Which slide: [SLIDE_PUSH] (both pictures move a full screen) or [SLIDE_APPLE] (Apple TV, see [slideFrame]). */
    val slideKind: Int = SLIDE_PUSH,
    /** Page pictures were baked without the side shade (Slide animation): the glass draws it, still, on top. */
    val pageNoSideShade: Boolean = false,
    /** 0..1: the blur of the open-dock veil, which fades in once the dock has finished opening ([veil] is the dimming). */
    val blur: androidx.compose.runtime.State<Float>? = null,
    /** The "Glass blur" setting as a blur radius in dp: frosted glass (and the large clock) blur the sharp picture by this. */
    val blurDp: Float = 0f,
    /** The page is a picture of its own over a video background (the music player): the glass shows that picture,
     *  not the video's live copies (the dock showed the video under the player). */
    val ignoreVideo: Boolean = false,
)

const val SLIDE_PUSH = 1
const val SLIDE_APPLE = 2

/**
 * One frame of a slide change, shared by the page, every glass surface and the dots so they can't drift.
 * The new picture covers the screen on one side of [edge] (right of it when [curRight]).
 * - Push: both pictures move a whole screen.
 * - Apple TV (frame by frame from the Apple TV app): the edge sweeps across the screen while the new picture
 *   itself only drifts in a little (parallax), and the old one drifts the other way and dims a little; the
 *   picture seems to open up from inside rather than being pushed.
 */
class SlideFrame(val curShift: Float, val prevShift: Float, val edge: Float, val curRight: Boolean, val dim: Float)

fun slideFrame(kind: Int, dir: Int, e: Float, w: Float): SlideFrame {
    val edge = if (dir > 0) w * (1f - e) else w * e
    // The new picture's own drift ends well before the edge does (squared): with the same slow ending as the
    // edge it was still creeping the last few pixels after the sweep, a slight shake before the picture settled.
    return if (kind == SLIDE_APPLE) SlideFrame(dir * (1f - e) * (1f - e) * w * 0.12f, -dir * e * w * 0.20f, edge, dir > 0, 0.30f * e)
    else SlideFrame(dir * (1f - e) * w, -dir * e * w, edge, dir > 0, 0f)
}

/** How dark the page gets under the open dock. */
const val VEIL_DIM = 0.62f

/** The open-dock veil in one pass: the blurred picture already darkened (instead of a black layer on top of it). */
val VeilFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
    1f - VEIL_DIM, 0f, 0f, 0f, 0f,  0f, 1f - VEIL_DIM, 0f, 0f, 0f,  0f, 0f, 1f - VEIL_DIM, 0f, 0f,  0f, 0f, 0f, 1f, 0f)))

/**
 * The veil's picture already darkened (what [VeilFilter] does), made once per picture: the colour filter, worked out
 * for the whole screen and every glass surface in each frame of the dock opening, was a good part of the graphics
 * chip's time there. The picture itself comes back if the copy can't be made (then the filter is used).
 */
object VeilPic {
    private var src: ImageBitmap? = null
    private var out: ImageBitmap? = null
    @Synchronized fun of(b: ImageBitmap): ImageBitmap {
        if (src === b) return out ?: b
        val made = runCatching {
            val a = b.asAndroidBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            val o = Bitmap.createBitmap(a.width, a.height, Bitmap.Config.ARGB_8888)
            val k = 1f - VEIL_DIM
            android.graphics.Canvas(o).drawBitmap(a, 0f, 0f, android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(floatArrayOf(k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, k, 0f, 0f, 0f, 0f, 0f, 1f, 0f))
            })
            o.prepareToDraw(); o.asImageBitmap()
        }.getOrNull()
        src = b; out = made
        return made ?: b
    }
}

val LocalGlassEnv = compositionLocalOf { GlassEnv(null, null, null, 0, 0, 6, 14) }
/**
 * The same environment for the glass surfaces, as a state read only while drawing: a new picture or a slide
 * redraws them instead of building them again. Provided next to [LocalGlassEnv] (see [ProvideGlassEnv]).
 */
val LocalGlassEnvRef = staticCompositionLocalOf<androidx.compose.runtime.State<GlassEnv>> { androidx.compose.runtime.mutableStateOf(GlassEnv(null, null, null, 0, 0, 6, 14)) }
/** The parts of the glass environment a surface needs while being built: they change only with the settings. */
data class GlassStatic(val refractionDp: Int, val wobble: Float, val veil: androidx.compose.runtime.State<Float>?)

/** [GlassStatic] for this surface: rebuilt only when one of them changes, not with every new picture or slide. */
@Composable
fun rememberGlassStatic(): GlassStatic {
    val ref = LocalGlassEnvRef.current
    val v by remember(ref) {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) { ref.value.let { GlassStatic(it.refractionDp, it.wobble, it.veil) } }
    }
    return v
}

/** 1.0 at 960dp wide (1080p TV at 2x); every size in the UI is multiplied by this. */
val LocalScale = staticCompositionLocalOf { 1f }

/**
 * Blurs the backdrop once on the CPU: shrink it hard (that alone averages pixels), then three box
 * passes. Every glass surface draws from this one small bitmap, so no surface needs its own GPU blur.
 * [strength] 0..80 (the "Glass blur" setting).
 */
fun blurredBackdrop(src: Bitmap, strength: Int): ImageBitmap {
    // Twice the old size with twice the blur radius: the same softness, but scaled up on screen it no longer
    // shows blocks and steps (the frosted glass looked pixelated next to the clear one).
    val w0 = (320 - strength * 3).coerceIn(48, 320)
    // A light blur needs a finer copy: scaled up ×3 a 560 px copy blurred by a pixel or two showed its pixels as
    // steps. The radius below grows with it, so the softness stays the same.
    val w = (w0 * 2).coerceAtMost(if (strength < 24) src.width.coerceIn(560, 960) else 560)
    val h = max(8, w * src.height / max(1, src.width))
    val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
    val r = if (strength <= 0) 0 else ((1 + strength / 20).coerceAtMost(4) * w / w0)
    if (r > 0) {
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) { boxBlur(px, tmp, w, h, r); boxBlur(tmp, px, w, h, r, vertical = true) }
        small.setPixels(px, 0, w, 0, 0, w, h)
    }
    // Immutable, so the GPU keeps its texture instead of uploading it again for every glass surface.
    val out = small.copy(Bitmap.Config.ARGB_8888, false).also { small.recycle(); it.prepareToDraw() }
    return out.asImageBitmap()
}

private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, vertical: Boolean = false) {
    val len = if (vertical) h else w
    val lines = if (vertical) w else h
    val div = 2 * r + 1
    for (line in 0 until lines) {
        var sa = 0; var sr = 0; var sg = 0; var sb = 0
        fun idx(i: Int) = if (vertical) i.coerceIn(0, len - 1) * w + line else line * w + i.coerceIn(0, len - 1)
        for (i in -r..r) { val c = src[idx(i)]; sa += c ushr 24; sr += (c shr 16) and 255; sg += (c shr 8) and 255; sb += c and 255 }
        for (i in 0 until len) {
            dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
            val o = src[idx(i - r)]; val n = src[idx(i + r + 1)]
            sa += (n ushr 24) - (o ushr 24); sr += ((n shr 16) and 255) - ((o shr 16) and 255)
            sg += ((n shr 8) and 255) - ((o shr 8) and 255); sb += (n and 255) - (o and 255)
        }
    }
}

/**
 * Liquid glass refraction on the GPU (AGSL, Android 13+). A rounded-rect signed distance field gives
 * every pixel its distance to the rim and the rim normal; inside the bezel band the backdrop is read
 * from further inside along that normal with a circular lens profile, so whatever is behind the glass
 * bends and stretches at the edges, with a slight red/blue split like real glass. No glow, no sheen:
 * the glass stays clean.
 */
private const val LIQUID_SKSL = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float wobble;
uniform float phase;

float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fc) {
    float2 c = size * 0.5;
    float2 p = fc - c;
    float d = sdRound(p, c, radius);
    float t = clamp(1.0 + d / bezel, 0.0, 1.0);
    // Liquid: after a move the whole body ripples and settles.
    float2 base = fc;
    if (wobble > 0.001) base += float2(sin(fc.y * 0.045 + phase), cos(fc.x * 0.032 + phase * 1.3)) * wobble * 7.0;
    float prof = 1.0 - sqrt(max(0.0, 1.0 - t * t));
    float disp = strength * prof;
    // Most pixels sit in the flat middle of the glass: one plain sample, no edge maths.
    if (disp < 0.35) return half4(content.eval(base).rgb, 1.0);
    float2 n = float2(sdRound(p + float2(1.0, 0.0), c, radius) - sdRound(p - float2(1.0, 0.0), c, radius),
                      sdRound(p + float2(0.0, 1.0), c, radius) - sdRound(p - float2(0.0, 1.0), c, radius));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, 0.0);
    float4 cg = float4(content.eval(base - n * disp * 1.06));
    float r = float4(content.eval(base - n * disp)).r;
    float b = float4(content.eval(base - n * disp * 1.12)).b;
    return half4(half3(float3(r, cg.g, b)), 1.0);
}
"""

/** One compiled shader per surface; uniforms change, the program is never recompiled. */
/**
 * The program is compiled once for the whole app. Creating a RenderEffect copies the current uniform
 * values, so one shader object safely serves every surface (all effects are built on the main thread).
 */
@androidx.annotation.RequiresApi(33)
private object SharedShader {
    private var shader: Any? = null
    fun get(): Any = shader ?: RuntimeShader(LIQUID_SKSL).also { shader = it }
}

private class Refractor {
    fun effect(w: Float, h: Float, radius: Float, bezel: Float, strength: Float, wobble: Float = 0f): androidx.compose.ui.graphics.RenderEffect? {
        if (Build.VERSION.SDK_INT < 33 || w < 2f || h < 2f || (strength < 0.5f && wobble < 0.01f)) return null
        val s = SharedShader.get() as RuntimeShader
        s.setFloatUniform("size", w, h)
        s.setFloatUniform("radius", min(radius, min(w, h) / 2f))
        s.setFloatUniform("bezel", max(1f, bezel))
        s.setFloatUniform("strength", strength)
        s.setFloatUniform("wobble", wobble)
        s.setFloatUniform("phase", (1f - wobble) * 14f)
        return RenderEffect.createRuntimeShaderEffect(s, "content").asComposeRenderEffect()
    }
}

/** Draws the (blurred) backdrop region that sits behind this element, darkened like the scene. */
private fun DrawScope.drawBackdropRegion(bmp: ImageBitmap, rootW: Int, rootH: Int, pos: Offset, alpha: Float = 1f, scrims: Boolean = true, shift: Float = 0f,
                                        colorFilter: androidx.compose.ui.graphics.ColorFilter? = null) {
    val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
    if (bw < 1 || bh < 1 || rootW <= 0 || rootH <= 0) return
    val scale = max(rootW / bw, rootH / bh)
    val ox = (rootW - bw * scale) / 2f; val oy = (rootH - bh * scale) / 2f
    // [shift]: the picture is moved right by this many screen pixels (Slide animation).
    val sx = (pos.x - shift - ox) / scale; val sy = (pos.y - oy) / scale
    if (sx + size.width / scale <= 0f || sx >= bw) { if (scrims) drawScrims(rootW, rootH, pos, true); return }
    // The whole picture placed exactly where the page has it (a float transform, clipped to this surface). Cutting
    // out the part behind it in whole source pixels rounded its size and place: with small copies scaled up ×3 or
    // more each surface was off by up to a few screen pixels and stretched a little, so the glass of the dock and of
    // each icon didn't line up with the page or with each other. Hidden under a strong blur, it showed as steps and
    // seams with a light one, worse on small icons. Only the visible part is drawn by the GPU either way.
    val k = scale
    clipRect(0f, 0f, size.width, size.height) {
        withTransform({
            this.translate(ox + shift - pos.x, oy - pos.y); this.scale(k, k, Offset.Zero)
        }) {
            drawImage(
                bmp,
                srcOffset = IntOffset.Zero, srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset.Zero, dstSize = IntSize(bmp.width, bmp.height),
                alpha = alpha,
                colorFilter = colorFilter,
                filterQuality = if (k <= 1.01f) FilterQuality.None else FilterQuality.Low,
            )
        }
    }
    if (scrims) drawScrims(rootW, rootH, pos, true)
}

/** The page's two shades in screen space: side (left) and, unless [sideOnly], bottom. */
private fun DrawScope.drawScrims(rootW: Int, rootH: Int, pos: Offset, bottom: Boolean) {
    // Pre-painted shade pictures instead of live gradients: a gradient over a large area cost the TV GPU
    // many times more than drawing a small picture scaled up.
    val full = androidx.compose.ui.unit.IntSize(rootW, rootH)
    translate(-pos.x, -pos.y) {
        drawImage(Scrims.side, dstSize = full, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
        if (bottom) drawImage(Scrims.bottom, dstSize = full, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
    }
}

/** The page's two shades as small dithered pictures (painted once), drawn scaled to the screen. */
object Scrims {
    private fun paint(side: Boolean): ImageBitmap {
        val w = 480; val h = 270
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG)
        val c = android.graphics.Canvas(b)
        if (side) {
            p.shader = android.graphics.LinearGradient(0f, 0f, w * 0.62f, 0f, 0xE0000000.toInt(), 0, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w * 0.62f, h.toFloat(), p)
        } else {
            p.shader = android.graphics.LinearGradient(0f, h * 0.58f, 0f, h.toFloat(), 0, 0xCC000000.toInt(), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, h * 0.58f, w.toFloat(), h.toFloat(), p)
        }
        return b.copy(Bitmap.Config.ARGB_8888, false).also { b.recycle(); it.prepareToDraw() }.asImageBitmap()
    }
    private val sideImg: ImageBitmap by lazy { paint(true) }
    private val none: ImageBitmap by lazy { ImageBitmap(4, 4) }
    /**
     * Settings → Background & glass → Left shade, off (with your own background): no side shade anywhere.
     * Kept as state: what draws the shade (the page, the glass, the big clock) draws again when it changes. As a
     * plain value the switch did nothing on the TV until the next picture came: the page already drawn stayed.
     */
    var sideOff by androidx.compose.runtime.mutableStateOf(false)
    val side: ImageBitmap get() = if (sideOff) none else sideImg
    /** Settings → Carousel → Carousel shade, off: no bottom shade either (state, for the same reason). */
    var bottomOff by androidx.compose.runtime.mutableStateOf(false)
    private val bottomImg: ImageBitmap by lazy { paint(false) }
    val bottom: ImageBitmap get() = if (bottomOff) none else bottomImg
    /** What the pictures made with the shades are kept under: a picture baked with a shade is never shown without it. */
    val key: String get() = "$sideOff$bottomOff"
}

/** A liquid-glass surface: blurred backdrop + rim refraction ([refraction] dp, 0 = flat frosted glass). */
@Composable
fun LiquidSurface(
    modifier: Modifier = Modifier,
    radius: Dp,
    refraction: Dp,
    bezel: Dp,
    tint: Color = Color.Transparent,
    sharp: Boolean = false,
    wobble: Float = 0f,
    /** false (small surfaces, the dock icons): during a slide the glass crossfades instead of sliding inside. */
    slideInside: Boolean = true,
    /** Keep the glass in its own cached texture. Off while the surface is resizing (the open/close of the
     *  dock): a texture re-made at a new size every frame showed one frame late, so the glass flickered. */
    cache: Boolean = true,
    /**
     * Frosted glass made the way the clear glass is: from the same sharp picture (the page's own, or the video's
     * copy), blurred on the GPU by the Glass blur setting. It changes, slides and settles exactly with the page,
     * like the clear glass (the small blurred copies made apart were ready at other moments).
     */
    frosted: Boolean = false,
    content: @Composable BoxScope.() -> Unit = {},
) {
    // Square look (Settings): every glass surface without rounded corners.
    @Suppress("NAME_SHADOWING") val radius = if (GlassShape.square.value) 0.dp else radius
    // The glass environment is read only while drawing (never here): a new picture or a slide then redraws the
    // glass without building every surface again (each slide rebuilt the whole dock twice, ~20 ms each time).
    val ref = LocalGlassEnvRef.current
    val density = LocalDensity.current
    // Video behind: the glass changes every copy, a cached texture would only be drawn twice.
    // (Read through the ref, as a derived value: read straight from LocalGlassEnv, every glass surface on screen —
    // the dock, each icon, the island — was composed again with every new picture, at the start of every slide.)
    val ignoreVideo by remember(ref) { androidx.compose.runtime.derivedStateOf { ref.value.ignoreVideo } }
    val videoBehind = VideoFrames.showing.value && !ignoreVideo
    // *Glass over a video: See-through*: the video itself shows through (nothing drawn of it here), only the glass's
    // colour, the veil and the shuffle's black on top; no bending or blur (they would act on nothing).
    // (Also over the song player's artwork: the page there is a picture drawn over the video, ignoreVideo, and the
    // glass went back to bending it on the Music icon only, then see-through again on the next app: his video.)
    val passThrough = VideoGlass.clear.value && VideoFrames.showing.value
    val pos = remember { mutableStateOf(Offset.Zero) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val refractor = remember { Refractor() }
    val rPx = with(density) { radius.toPx() }
    val bPx = with(density) { bezel.toPx() }
    val fPx = with(density) { refraction.toPx() }
    val blurDp by remember(ref) { androidx.compose.runtime.derivedStateOf { ref.value.blurDp } }
    // Frosted glass shows the ready-blurred copies of the picture (made with the picture, at the Glass blur
    // setting), not the sharp picture blurred live: Android applies a layer's blur again every time the screen is
    // drawn, so a dock and six icons blurred ten layers in every frame of a slide (~50 ms a frame at a strong blur;
    // the carousel stuttered). Only a live video behind still needs the live blur (its frosted copies are small).
    val liveBlur = frosted && videoBehind && !passThrough
    val blurPx = if (liveBlur) blurDp * density.density else 0f
    // The blur first, then the rim's bending (one chain of effects on the same layer).
    fun withBlur(e: androidx.compose.ui.graphics.RenderEffect?): androidx.compose.ui.graphics.RenderEffect? {
        if (blurPx < 0.5f || Build.VERSION.SDK_INT < 31) return e
        val b = RenderEffect.createBlurEffect(blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP)
        val r = e?.asAndroidRenderEffect()
        return (if (r != null) RenderEffect.createChainEffect(r, b) else b).asComposeRenderEffect()
    }
    val still = remember(sz, rPx, bPx, fPx, blurPx) { withBlur(refractor.effect(sz.width.toFloat(), sz.height.toFloat(), rPx, bPx, fPx, 0f)) }
    // Frosted glass while the page slides: the rim's bending only, over the ready-blurred copies of the two
    // pictures. Blurring the sharp pictures on the GPU every frame of the move cost ~70 ms a frame with a strong
    // Glass blur (the slides stuttered); still, the glass is blurred live once and kept.
    val slideFx = remember(sz, rPx, bPx, fPx, frosted) { if (frosted) refractor.effect(sz.width.toFloat(), sz.height.toFloat(), rPx, bPx, fPx, 0f) else null }
    val clearPic = sharp || liveBlur
    // Shaders for the two pictures during a slide (Android 13+), made again only when a picture changes.
    val shaders = remember { PageShaders() }
    // The open-dock veil layer: only there while the veil shows (its own cached layer).
    // Music mode's pause dim stays composed (at no opacity while playing): made only when the dim began, its
    // first frames showed the artwork bright through the glass (a flash in the dock on Pause / Play).
    val veilOn by remember(ref) { androidx.compose.runtime.derivedStateOf {
        val v = ref.value.veil
        (v?.value ?: 0f) > 0.005f || (v != null && v === MusicUi.pauseDim.asState())
    } }
    Box(
        modifier
            .then(if (DebugHide.intValue and 262144 != 0) Modifier else Modifier.clip(RoundedCornerShape(radius)))
            .onGloballyPositioned { pos.value = GlassPage.placeOf(it); if (it.size != sz) sz = it.size },
    ) {
        // Everything the layers below need, worked out from the environment as it is now (only while drawing).
        fun now() = GlassNow(ref.value, clearPic, videoBehind, slideInside)
        fun androidx.compose.ui.graphics.drawscope.DrawScope.tintIn(t: Color) {
            if (DebugHide.intValue and 256 != 0) return
            if (t.alpha > 0f) drawRect(t)
        }
        // Page pictures carry their shades (with Slide all but the side shade); small copies carry none.
        fun androidx.compose.ui.graphics.drawscope.DrawScope.shade(n: GlassNow, b: ImageBitmap) {
            if (!n.isPage(b)) drawScrims(n.env.rootW, n.env.rootH, pos.value, true)
            else if (n.env.pageNoSideShade) drawScrims(n.env.rootW, n.env.rootH, pos.value, false)
        }
        fun androidx.compose.ui.graphics.drawscope.DrawScope.picture(n: GlassNow, b: ImageBitmap, shift: Float, withShade: Boolean = true) {
            if (DebugHide.intValue and 64 == 0) drawBackdropRegion(b, n.env.rootW, n.env.rootH, pos.value, scrims = false, shift = shift)
            if (withShade && DebugHide.intValue and 128 == 0) shade(n, b)
        }
        fun androidx.compose.ui.graphics.drawscope.DrawScope.veil(n: GlassNow) {
            val v = n.env.veil?.value ?: 0f
            if (v > 0.01f && DebugHide.intValue and 16 == 0) {
                val heavy = if (!videoBehind && DebugHide.intValue and 32768 == 0) n.env.heavy else null
                // Like the page veil: the darkened blurred picture over the glass, one pass.
                if (heavy != null) { val dark = VeilPic.of(heavy); drawBackdropRegion(dark, n.env.rootW, n.env.rootH, pos.value, alpha = v, scrims = false, colorFilter = if (dark === heavy) VeilFilter else null) }
                else drawRect(Color.Black.copy(alpha = v * VEIL_DIM))
            }
        }
        // Frosted glass that crossfades while the page slides: the new picture comes in exactly as the page's
        // moving edge passes over this surface (a plain crossfade finished before the slide got there: the glass
        // changed ahead of the picture behind it).
        fun crossAlpha(env: GlassEnv): Float {
            val m = env.mix?.value ?: 1f
            if (env.slide == 0 || slideInside || sz.width <= 0) return m
            val f = slideFrame(env.slideKind, env.slide, m, env.rootW.toFloat())
            val l = pos.value.x; val r = l + sz.width
            return (if (f.curRight) (r - f.edge) / (r - l) else (f.edge - l) / (r - l)).coerceIn(0f, 1f)
        }
        // While the page crossfades, the old and the new glass are two cached layers and only the top one's
        // opacity changes: in step with the page, without redrawing or re-refracting the glass.
        // The old glass is refracted too (rendered once, it doesn't change), so the rims don't thin out.
        // Every layer is always there and switched by its opacity: switching layers in and out rebuilt the
        // surface at the start and the end of every slide.
        Canvas(Modifier.matchParentSize().graphicsLayer {
            renderEffect = still
            val n = now()
            alpha = if (n.bmp != null && n.prev != null && !n.sliding && !n.covered()) 1f else 0f
        }) {
            val n = now(); val p = n.prev
            if (n.bmp == null || p == null || n.sliding) return@Canvas
            picture(n, p, 0f); if (n.veilPic == null) veil(n); tintIn(tint)
        }
        // The glass as it is when still. It stays drawn during a slide too (nearly invisible, under the
        // moving picture), so its cached texture is ready when the slide ends: building it only then made
        // the glass catch for a moment as the picture settled.
        Canvas(Modifier.matchParentSize().graphicsLayer {
            val n = now()
            val live = n.env.live
            val w = if (wobble > 0f && live != null) live.value * wobble else 0f
            // (No effect while the page slides: the layer is then nearly invisible under the moving picture, and its
            // bending was still worked out again for every frame of the move. Its content stays recorded, so it is
            // ready the moment the slide ends.)
            renderEffect = if (passThrough) null else if (n.bmp == null || n.sliding) null else if (videoBehind && DebugHide.intValue and 2048 != 0) null else if (!cache && DebugHide.intValue and 16384 != 0) null else if (w < 0.01f) still else withBlur(refractor.effect(size.width, size.height, rPx, bPx, fPx, w))
            alpha = if (n.bmp == null) 1f else if (n.sliding) 0.002f else if (n.covered()) 0f else if (n.prev != null) crossAlpha(n.env) else 1f
            // Rendered once into its own texture and reused while nothing behind the glass changes:
            // moving focus over the dock then costs one copy instead of the picture and its shades again.
            if (cache && DebugHide.intValue and 1024 == 0) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
        }) {
            val n = now()
            val b = n.bmp
            if (passThrough) {
                val d = VideoFrames.dim.floatValue
                if (d > 0.003f) drawRect(Color.Black.copy(alpha = d))
                if (n.veilPic == null) veil(n)
                tintIn(tint)
                return@Canvas
            }
            if (b == null) { drawRect(Color(0xFF1A1A22)); tintIn(tint); return@Canvas }
            // A video behind: the glass shows its live frames (sharp or frosted), with the page's shades.
            val liveFrame = if (n.env.ignoreVideo) null else if (clearPic) VideoFrames.sharp.value ?: VideoFrames.soft.value else VideoFrames.soft.value ?: VideoFrames.sharp.value
            if (liveFrame != null) {
                // The page's shades (pre-painted pictures) drawn over the live copy, as on the page itself.
                drawBackdropRegion(liveFrame, n.env.rootW, n.env.rootH, pos.value, scrims = DebugHide.intValue and 128 == 0)
            } else picture(n, b, 0f)
            // Shuffle: the background fades through black between videos, the glass with it.
            val d = if (n.env.ignoreVideo) 0f else VideoFrames.dim.floatValue
            if (d > 0.003f) drawRect(Color.Black.copy(alpha = d))
            if (n.veilPic == null) veil(n)
            tintIn(tint)
        }
        // (Frosted glass too: with its ready-blurred copies of the two pictures. Its own path, a whole offscreen
        // layer blurred and bent again every frame of the move, cost ~50 ms a frame on the TV.)
        if (Build.VERSION.SDK_INT >= 33 && (!frosted || blurPx < 0.5f || ref.value.bmp != null)) {
            // Slide, Android 13+: the middle of the glass is drawn like the page (two plain pictures, cheap),
            // and only the rim band, where the refraction is, is drawn with a shader brush that reads both
            // pictures and bends them itself. No offscreen layer is redrawn and re-refracted per frame, and
            // the rims look exactly as when the glass is still, so nothing jumps when the move ends.
            // Small glass (an app tile): the whole shape is drawn with the shader, not just the rim. With the
            // middle left to the page underneath, the edge of the rim band showed as a glass rectangle inside the
            // selected icon for as long as the slide lasted. On a small surface drawing it all costs nothing.
            val ring = remember(sz, rPx, bPx) {
                val w = sz.width.toFloat(); val h = sz.height.toFloat()
                val rr = min(rPx, min(w, h) / 2f); val bb = max(1f, bPx)
                fun path(whole: Boolean) = androidx.compose.ui.graphics.Path().apply {
                    fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, androidx.compose.ui.geometry.CornerRadius(rr)))
                    if (!whole && w > 2 * bb && h > 2 * bb) addRoundRect(androidx.compose.ui.geometry.RoundRect(bb, bb, w - bb, h - bb,
                        androidx.compose.ui.geometry.CornerRadius(max(0f, rr - bb))))
                }
                path(true) to path(false)
            }
            Canvas(Modifier.matchParentSize()) {
                val n = now()
                val pre = frosted && blurPx >= 0.5f
                val b = if (pre) n.env.bmp ?: n.bmp else n.bmp
                val pv = if (pre) n.env.prevBmp ?: n.prev else n.prev
                if (!n.sliding || b == null || pv == null) return@Canvas
                val env = n.env
                val p = pos.value
                val W = env.rootW.toFloat()
                val f = slideFrame(env.slideKind, n.dir, env.mix?.value ?: 1f, W)
                // Clear glass showing the page pictures themselves: its middle is exactly the page that is
                // already drawn underneath, so it is left empty and only the rim is drawn. Small (blurred)
                // copies differ from the page, so they are drawn in full.
                if (!n.isPage(b)) {
                    picture(n, pv, f.prevShift, withShade = false)
                    if (f.dim > 0f) drawRect(Color.Black.copy(alpha = f.dim))
                    val e = f.edge - p.x
                    clipRect(left = if (f.curRight) e else -1e5f, right = if (f.curRight) 1e5f else e) { picture(n, b, f.curShift, withShade = false) }
                    shade(n, b)
                }
                // Frosted glass: no bending while the page slides. Over a blurred picture the rim's bending hardly
                // shows, and it was most of the GPU's time in every frame of a slide (~20 ms of ~50).
                if (fPx < 0.5f || !clearPic) { veil(n); tintIn(tint); return@Canvas }
                val curS = shaders.cur(b); val prevS = shaders.prev(pv)
                curS.place(env.rootW, env.rootH, p); prevS.place(env.rootW, env.rootH, p)
                val whole = sz.width.toLong() * sz.height < env.rootW.toLong() * env.rootH / 12
                val sh = GlassSlideShader.get()
                sh.setInputShader("cur", curS.shader)
                sh.setInputShader("prev", prevS.shader)
                sh.setFloatUniform("size", size.width, size.height)
                sh.setFloatUniform("radius", min(rPx, min(size.width, size.height) / 2f))
                sh.setFloatUniform("bezel", max(1f, bPx))
                sh.setFloatUniform("strength", fPx)
                sh.setFloatUniform("pos", p.x, p.y)
                sh.setFloatUniform("root", W, env.rootH.toFloat())
                sh.setFloatUniform("shc", f.curShift)
                sh.setFloatUniform("shp", f.prevShift)
                sh.setFloatUniform("edge", f.edge)
                sh.setFloatUniform("curRight", if (f.curRight) 1f else 0f)
                sh.setFloatUniform("dim", f.dim)
                val page = n.isPage(b)
                sh.setFloatUniform("side", if ((!page || env.pageNoSideShade) && !Scrims.sideOff) 1f else 0f)
                sh.setFloatUniform("bottom", if (!page && !Scrims.bottomOff) 1f else 0f)
                val brush = androidx.compose.ui.graphics.ShaderBrush(sh)
                val bb = max(1f, bPx); val w = size.width; val h = size.height
                // (Bands only where the ring's inner corners are square anyway: the rim at least as wide as the corner.)
                if (whole || bb < min(rPx, min(w, h) / 2f) || w <= 2 * bb || h <= 2 * bb) drawPath(if (whole) ring.first else ring.second, brush)
                else {
                    // The rim as four plain bands (the surface's own rounded clip shapes the outside): a ring-shaped
                    // path was filled through a mask made again for every frame of the slide (2 ms of each).
                    drawRect(brush, Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, bb))
                    drawRect(brush, Offset(0f, h - bb), androidx.compose.ui.geometry.Size(w, bb))
                    drawRect(brush, Offset(0f, bb), androidx.compose.ui.geometry.Size(bb, h - 2 * bb))
                    drawRect(brush, Offset(w - bb, bb), androidx.compose.ui.geometry.Size(bb, h - 2 * bb))
                }
                veil(n)
                tintIn(tint)
            }
        } else {
            // Slide: both pictures move behind the glass exactly as the page does. The rim refraction stays
            // on: switching it off for the move made the rims jump when it came back (worse the stronger it is).
            Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = if (frosted && blurPx >= 0.5f) slideFx else still; alpha = if (now().sliding) 1f else 0f }) {
                val n = now()
                // (Frosted: the blurred copies, see slideFx.)
                val pre = frosted && blurPx >= 0.5f
                val b = if (pre) n.env.bmp ?: n.bmp else n.bmp
                val pv = if (pre) n.env.prevBmp ?: n.prev else n.prev
                if (!n.sliding || b == null || pv == null) return@Canvas
                val f = slideFrame(n.env.slideKind, n.dir, n.env.mix?.value ?: 1f, n.env.rootW.toFloat())
                picture(n, pv, f.prevShift, withShade = false)
                if (f.dim > 0f) drawRect(Color.Black.copy(alpha = f.dim))
                val e = f.edge - pos.value.x
                clipRect(left = if (f.curRight) e else -1e5f, right = if (f.curRight) 1e5f else e) { picture(n, b, f.curShift, withShade = false) }
                shade(n, b)
                veil(n)
                tintIn(tint)
            }
        }
        if (veilOn && DebugHide.intValue and 16 == 0) Canvas(Modifier.matchParentSize().graphicsLayer {
            val n = now()
            alpha = if (n.bmp != null && !n.sliding && n.veilPic != null) n.env.veil?.value ?: 0f else 0f
            // No bending and no texture of its own: the veil's picture is heavily blurred (bending it shows nothing),
            // and a second bent layer on the dock and every icon, on top of the glass itself, for each frame of the
            // dock opening was most of the graphics chip's time there (frames of 50-60 ms: the opening stuttered).
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha
        }) {
            val n = now()
            val vp = n.veilPic ?: return@Canvas
            if (n.bmp == null || n.sliding) return@Canvas
            val dark = VeilPic.of(vp)
            drawBackdropRegion(dark, n.env.rootW, n.env.rootH, pos.value, scrims = false, colorFilter = if (dark === vp) VeilFilter else null)
            tintIn(tint)
        }
        // The glass colour (black or petrol) on top of every layer and outside their effects: drawn inside them it
        // came out lighter while the glass was still (its bending / frost changed the colour too) than during a
        // slide, so the colour jumped for a moment with every slide.
        // While the page slides, glass that bends nothing (an icon that isn't selected) draws nothing of its own
        // and shows the dock's glass under it, already coloured: coloured again here, the icons went darker for
        // the length of every slide.
        // (Only glass that sits on glass may leave it out: the strip dock itself bends nothing, and so do the carousel's
        // buttons and any dock with refraction at 0. They sit on the page, and lost their colour for the length of
        // every slide: the dock went a quarter brighter and back with each title.)
        val gTint = GlassTint.color.value
        val onGlass = LocalOnGlass.current
        if (gTint.alpha > 0f && DebugHide.intValue and 256 == 0) Canvas(Modifier.matchParentSize()) {
            val n = now()
            val b = if (frosted && blurPx >= 0.5f) n.env.bmp ?: n.bmp else n.bmp
            val seeThrough = onGlass && Build.VERSION.SDK_INT >= 33 && n.sliding && b != null && n.isPage(b) && fPx < 0.5f && clearPic
            if (!seeThrough) drawRect(gTint)
        }
        content()
    }
}

/** One surface's view of the glass environment at the moment it draws (see [LiquidSurface]). */
private class GlassNow(val env: GlassEnv, clearPic: Boolean, videoBehind: Boolean, slideInside: Boolean) {
    val bmp = if (clearPic) env.sharp ?: env.bmp else env.bmp
    // A video behind: the glass shows the video's own frames; a picture changing under it (the next video's
    // still) must not fade or slide through the glass (it showed the title pictures for a moment).
    val prev = if (videoBehind) null else if (clearPic) env.prevSharp ?: env.prevBmp else env.prevBmp
    val dir = if (slideInside) env.slide else 0
    val sliding = bmp != null && prev != null && dir != 0
    val veilPic = if (!videoBehind && DebugHide.intValue and 32768 == 0) env.heavy else null
    fun covered() = veilPic != null && (env.veil?.value ?: 0f) > 0.995f && DebugHide.intValue and 16 == 0
    fun isPage(b: ImageBitmap) = env.sharpIsPage && (b === env.sharp || b === env.prevSharp)
}

/** The two pictures' shaders for a slide, kept while the pictures stay the same (used on Android 13+ only). */
@android.annotation.SuppressLint("NewApi")
private class PageShaders {
    private var c: PageShader? = null
    private var p: PageShader? = null
    fun cur(b: ImageBitmap): PageShader = c?.takeIf { it.bmp === b } ?: PageShader(b).also { c = it }
    fun prev(b: ImageBitmap): PageShader = p?.takeIf { it.bmp === b } ?: PageShader(b).also { p = it }
}

/**
 * The Settings screen is up: what its page looks like, for the glass of anything that opens over it.
 *
 * A pop-up or dialog on glass normally reads the home page (the carousel's picture). Over Settings that picture is
 * not what is under it: clear glass showed the whole poster, sharp, inside the list (the writing couldn't be read).
 * So every glass panel drawn over Settings is frosted, lighter than on the home screen (the page under it is dark
 * already: darkened as on the home screen and with the screen dimmed it read as a black box), and reads the Settings page (its blurred
 * picture), whatever the theme; the two flat looks stay as they are. One rule, in [GlassPanel]: the hosts of what
 * can open over Settings ([DialogHost], [PopMenuHost], [AppLockHost]) only say so with [OverSettings].
 */
object SettingsGlass {
    val env = androidx.compose.runtime.mutableStateOf<GlassEnv?>(null)
    /** How much the rest of the screen dims behind a pop-up over Settings. */
    const val DIM = 0.2f
    /** How much of a panel's own darkening is kept over Settings: the page under it is dark already. */
    const val DARKEN = 0.1f
}

/** The Settings page's glass environment while something is drawn over Settings, else null (see [SettingsGlass]). */
val LocalOverSettings = compositionLocalOf<GlassEnv?> { null }

/** What opens over Settings when Settings is up: wrap a host's content in this. */
@Composable
fun OverSettings(content: @Composable () -> Unit) =
    androidx.compose.runtime.CompositionLocalProvider(LocalOverSettings provides SettingsGlass.env.value, content = content)

/** Large panel (dock, settings, folder) in one of the four looks: liquid | clear | amoled | bow. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    radius: Dp = 28.dp,
    style: String = "liquid",
    darken: Float = 0f,
    refract: Boolean = true,
    /** Keep the glass in a cached texture (off while the panel is resizing). */
    cache: Boolean = refract,
    content: @Composable BoxScope.() -> Unit,
) {
    // Over the Settings screen: frosted, a little darker, reading the Settings page (see [SettingsGlass]).
    val over = LocalOverSettings.current
    if (over != null && style != "amoled" && style != "bow") {
        val ref = androidx.compose.runtime.rememberUpdatedState(over)
        androidx.compose.runtime.CompositionLocalProvider(LocalOverSettings provides null, LocalGlassEnv provides over, LocalGlassEnvRef provides ref) {
            GlassPanel(modifier, radius, "liquid", darken * SettingsGlass.DARKEN, refract, cache, content)
        }
        return
    }
    @Suppress("NAME_SHADOWING") val radius = if (GlassShape.square.value) 0.dp else radius
    val env = rememberGlassStatic()
    val shape = RoundedCornerShape(radius)
    // The content always sits in the same place whatever the style: switching style (Settings background) used to
    // swap the whole panel, which rebuilt what was inside it (Settings jumped to the top with nothing selected).
    // Only the flat styles clip here; the glass clips itself (one rounded clip layer less on the TV GPU).
    Box(if (style == "amoled" || style == "bow") modifier.clip(shape) else modifier) {
        val fill = Modifier.matchParentSize()
        when (style) {
            "amoled" -> Box(fill.background(Color.Black.copy(alpha = 0.94f)))
            // Soft off-white like the iOS 26 light icons, shading a little towards the bottom (not paper white).
            "bow" -> Box(fill.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xF2EEEBE4), Color(0xF2E2DED5)))))
            // Glass like glass: almost no frost or tint, strong rim bending, ripples after a move.
            "water", "water_still" -> LiquidSurface(
                fill, radius, if (refract) (env.refractionDp * 1.5f).dp else 0.dp, (radius * 1.2f).coerceIn(12.dp, 34.dp),
                tint = Color.Black.copy(alpha = darken.coerceAtMost(0.9f)), sharp = true, wobble = if (refract && style == "water") env.wobble else 0f, cache = cache,
            )
            // Frosted panels slide their picture inside like the clear glass does (crossfading it ran ahead of the page).
            "clear" -> {
                LiquidSurface(fill, radius, 0.dp, 10.dp, tint = Color.White.copy(alpha = 0.06f), cache = cache)
                // Clipped to the panel's corners (unclipped it showed as a dark square around them).
                if (darken > 0f) Box(fill.clip(shape).background(Color.Black.copy(alpha = darken)))
            }
            else -> LiquidSurface(
                fill, radius, if (refract) env.refractionDp.dp else 0.dp, (radius * 1.1f).coerceIn(10.dp, 30.dp),
                tint = Color.Black.copy(alpha = (0.14f + darken).coerceAtMost(0.9f)), cache = cache,
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalOnGlass provides (style != "amoled" && style != "bow")) { content() }
        val rim = when (style) { "amoled" -> 0.08f; "bow" -> 0.6f; "water", "water_still" -> 0.16f; else -> 0.10f }
        Box(fill.border(if (style == "bow") 0.8.dp else 0.6.dp, Color.White.copy(alpha = rim), shape))
    }
}

/**
 * True for what sits on a glass panel: the glass under it already carries the glass colour (Settings → Background &
 * glass → Glass colour). See the colour's layer in [LiquidSurface] and the icons' plates.
 */
val LocalOnGlass = compositionLocalOf { false }

/** Kept for the screens that only need the clip. */
fun Modifier.roundClip(r: Dp) = this.clip(RoundedCornerShape(if (GlassShape.square.value) 0.dp else r))

/** Square look (Settings → Dock & icons): menus, dock and icons with sharp corners. Set by the home screen. */
object GlassShape { val square = androidx.compose.runtime.mutableStateOf(false) }

/**
 * The glass during a slide: the rim refraction of [LIQUID_SKSL], reading the two pictures itself (the new
 * one on its side of the moving edge, the old one dimmed on the other) and applying the page's shades.
 */
private const val GLASS_SLIDE_SKSL = """
uniform shader cur;
uniform shader prev;
uniform float2 size;
uniform float radius;
uniform float bezel;
uniform float strength;
uniform float2 pos;
uniform float2 root;
uniform float shc;
uniform float shp;
uniform float edge;
uniform float curRight;
uniform float dim;
uniform float side;
uniform float bottom;

half4 page(float2 q) {
    float2 s = q + pos;
    bool inCur = curRight > 0.5 ? s.x >= edge : s.x <= edge;
    half4 c;
    if (inCur) c = cur.eval(q - float2(shc, 0.0));
    else { c = prev.eval(q - float2(shp, 0.0)); c.rgb *= half(1.0 - dim); }
    if (side > 0.5) c.rgb *= half(1.0 - 0.88 * clamp(1.0 - (s.x / root.x) / 0.62, 0.0, 1.0));
    if (bottom > 0.5) c.rgb *= half(1.0 - 0.80 * clamp((s.y / root.y - 0.58) / 0.42, 0.0, 1.0));
    return c;
}
float sdRound(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}
half4 main(float2 fc) {
    float2 c = size * 0.5;
    float2 p = fc - c;
    float d = sdRound(p, c, radius);
    float t = clamp(1.0 + d / bezel, 0.0, 1.0);
    float prof = 1.0 - sqrt(max(0.0, 1.0 - t * t));
    float disp = strength * prof;
    if (disp < 0.35) return half4(page(fc).rgb, 1.0);
    float2 n = float2(sdRound(p + float2(1.0, 0.0), c, radius) - sdRound(p - float2(1.0, 0.0), c, radius),
                      sdRound(p + float2(0.0, 1.0), c, radius) - sdRound(p - float2(0.0, 1.0), c, radius));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, 0.0);
    half4 cg = page(fc - n * disp * 1.06);
    half r = page(fc - n * disp).r;
    return half4(r, cg.g, cg.b, 1.0);
}
"""

@androidx.annotation.RequiresApi(33)
private object GlassSlideShader {
    private var shader: Any? = null
    fun get(): RuntimeShader = (shader ?: RuntimeShader(GLASS_SLIDE_SKSL).also { shader = it }) as RuntimeShader
}

// ───────────────────────────── liquid glass page dots ─────────────────────────────

/**
 * Page dots as drops of liquid glass. The shapes are one signed-distance field: every dot is a circle and
 * the selected dot a capsule, joined with a smooth minimum, so when the selected drop moves it bridges into
 * the next dot, stretches, and the bridge thins and snaps off behind it, like two water drops touching and
 * parting. Inside, the picture is clear; only a band along the rim bends it (Apple's liquid glass).
 *
 * Drawn as a shader brush that reads the page pictures directly (no offscreen layer, no RenderEffect), so a
 * slide change or a move costs one small draw per frame.
 */
private const val DOTS_SKSL = """
uniform shader cur;
uniform shader prev;
uniform float wc;
uniform float wp;
uniform float shc;
uniform float shp;
uniform float side;
uniform float edgeMode;
uniform float edge;
uniform float curRight;
uniform float dim;
uniform float posX;
uniform float rootW;
uniform float count;
uniform float gap;
uniform float x0;
uniform float cy;
uniform float r;
uniform float head;
uniform float tail;
uniform float ar;
uniform float k;

half4 page(float2 q) {
    half4 c;
    if (edgeMode > 0.5) {
        float sx = q.x + posX;
        bool inCur = curRight > 0.5 ? sx >= edge : sx <= edge;
        if (inCur) c = cur.eval(q - float2(shc, 0.0));
        else { c = prev.eval(q - float2(shp, 0.0)); c.rgb *= half(1.0 - dim); }
    } else {
        c = cur.eval(q - float2(shc, 0.0)) * half(wc) + prev.eval(q - float2(shp, 0.0)) * half(wp);
    }
    if (side > 0.5) {
        float a = 0.88 * clamp(1.0 - ((q.x + posX) / rootW) / 0.62, 0.0, 1.0);
        c.rgb *= half(1.0 - a);
    }
    return c;
}
float smin(float a, float b, float kk) {
    float h = clamp(0.5 + 0.5 * (b - a) / kk, 0.0, 1.0);
    return mix(b, a, h) - kk * h * (1.0 - h);
}
float capsule(float2 p, float2 a, float2 b, float rr) {
    float2 pa = p - a; float2 ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 0.0001), 0.0, 1.0);
    return length(pa - ba * h) - rr;
}
float drop(float2 p) {
    float a = min(head, tail); float b = max(head, tail);
    return capsule(p, float2(x0 + a * gap - ar * 0.35, cy), float2(x0 + b * gap + ar * 0.35, cy), ar);
}
float field(float2 p) {
    float d = drop(p);
    float a = min(head, tail) - 0.45; float b = max(head, tail) + 0.45;
    for (int i = 0; i < 20; i++) {
        float fi = float(i);
        // Dots the drop already covers are left out (they would only make bumps on it).
        if (fi < count && (fi < a || fi > b)) d = smin(d, length(p - float2(x0 + fi * gap, cy)) - r, k);
    }
    return d;
}
half4 main(float2 fc) {
    float d = field(fc);
    if (d > 3.0) return half4(0.0);
    if (d > 0.0) return half4(0.0, 0.0, 0.0, (1.0 - d / 3.0) * 0.10);
    float e = 0.75;
    float2 n = float2(field(fc + float2(e, 0.0)) - field(fc - float2(e, 0.0)),
                      field(fc + float2(0.0, e)) - field(fc - float2(0.0, e)));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, 0.0);
    float t = clamp(1.0 + d / (r * 0.85), 0.0, 1.0);
    float prof = 1.0 - sqrt(max(0.0, 1.0 - t * t));
    float disp = prof * r * 1.6;
    half4 cg = page(fc - n * disp);
    half cr = page(fc - n * disp * 1.08).r;
    half cb = page(fc - n * disp * 0.92).b;
    half3 rgb = half3(cr, cg.g, cb);
    float sel = clamp(0.5 - drop(fc), 0.0, 1.0);
    rgb = mix(rgb, half3(1.0), half(0.06 + 0.16 * sel));
    rgb *= half(1.0 - 0.10 * t);
    float a = clamp(0.5 - d, 0.0, 1.0);
    return half4(min(rgb, half3(1.0)) * half(a), half(a));
}
"""

@androidx.annotation.RequiresApi(33)
private object DotsShader {
    private var shader: Any? = null
    fun get(): RuntimeShader = (shader ?: RuntimeShader(DOTS_SKSL).also { shader = it }) as RuntimeShader
}

/** A page picture as a shader in a glass element's own coordinates (element at [pos] on screen). */
@androidx.annotation.RequiresApi(31)
private class PageShader(val bmp: ImageBitmap) {
    val shader = android.graphics.BitmapShader(bmp.asAndroidBitmap(), android.graphics.Shader.TileMode.DECAL, android.graphics.Shader.TileMode.DECAL)
    private val m = android.graphics.Matrix()
    fun place(rootW: Int, rootH: Int, pos: Offset) {
        val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
        val sc = max(rootW / bw, rootH / bh)
        m.setScale(sc, sc)
        m.postTranslate((rootW - bw * sc) / 2f - pos.x, (rootH - bh * sc) / 2f - pos.y)
        shader.setLocalMatrix(m)
    }
}

/**
 * [head] / [tail]: the two ends of the selected drop, in dot positions (animated with different springs by
 * the caller). Before Android 13: plain translucent drops.
 */
@Composable
fun GlassDots(count: Int, head: () -> Float, tail: () -> Float, s: Float, modifier: Modifier = Modifier) {
    val env = LocalGlassEnv.current
    val density = LocalDensity.current
    val r = with(density) { (2.9f * s).dp.toPx() }
    val gap = with(density) { (14.5f * s).dp.toPx() }
    val ar = r * 1.45f
    val pad = ar * 2.2f
    val wDp = with(density) { ((count - 1) * gap + 2 * pad).toDp() }
    val hDp = with(density) { (ar * 2 + 10f).toDp() }
    val pos = remember { mutableStateOf(Offset.Zero) }
    val shaderOk = Build.VERSION.SDK_INT >= 33
    val cur = env.sharp?.takeIf { env.sharpIsPage }
    val prev = env.prevSharp?.takeIf { env.sharpIsPage }
    // DECAL bitmap shaders need Android 12+; the shader path itself needs 13, so only build them there.
    val curS = remember(cur) { if (Build.VERSION.SDK_INT >= 33) cur?.let { PageShader(it) } else null }
    val prevS = remember(prev) { if (Build.VERSION.SDK_INT >= 33) prev?.let { PageShader(it) } else null }
    Canvas(modifier.width(wDp).height(hDp).onGloballyPositioned { pos.value = GlassPage.placeOf(it) }) {
        val h = head(); val t = tail()
        if (shaderOk && curS != null) {
            val p = pos.value
            curS.place(env.rootW, env.rootH, p)
            prevS?.place(env.rootW, env.rootH, p)
            val m = env.mix?.value ?: 1f
            val W = env.rootW.toFloat()
            val sh = DotsShader.get()
            sh.setInputShader("cur", curS.shader)
            sh.setInputShader("prev", (prevS ?: curS).shader)
            val sliding = prevS != null && env.slide != 0
            val f = if (sliding) slideFrame(env.slideKind, env.slide, m, W) else null
            sh.setFloatUniform("wc", if (prevS != null && !sliding) m else 1f)
            sh.setFloatUniform("wp", if (prevS == null || sliding) 0f else 1f - m)
            sh.setFloatUniform("shc", f?.curShift ?: 0f)
            sh.setFloatUniform("shp", f?.prevShift ?: 0f)
            sh.setFloatUniform("edgeMode", if (f != null) 1f else 0f)
            sh.setFloatUniform("edge", f?.edge ?: 0f)
            sh.setFloatUniform("curRight", if (f?.curRight == true) 1f else 0f)
            sh.setFloatUniform("dim", f?.dim ?: 0f)
            sh.setFloatUniform("side", if (env.pageNoSideShade && !Scrims.sideOff) 1f else 0f)
            sh.setFloatUniform("posX", p.x)
            sh.setFloatUniform("rootW", W)
            sh.setFloatUniform("count", count.toFloat())
            sh.setFloatUniform("gap", gap)
            sh.setFloatUniform("x0", pad)
            sh.setFloatUniform("cy", size.height / 2f)
            sh.setFloatUniform("r", r)
            sh.setFloatUniform("head", h)
            sh.setFloatUniform("tail", t)
            sh.setFloatUniform("ar", ar)
            sh.setFloatUniform("k", r * 1.1f)
            drawRect(androidx.compose.ui.graphics.ShaderBrush(sh))
        } else {
            val cy = size.height / 2f
            for (i in 0 until count) drawCircle(Color.White.copy(alpha = 0.35f), r, Offset(pad + i * gap, cy))
            val a = min(h, t); val b = max(h, t)
            drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(pad + a * gap - ar * 1.35f, cy - ar),
                Size((b - a) * gap + ar * 2.7f, 2 * ar), androidx.compose.ui.geometry.CornerRadius(ar, ar))
        }
    }
}

/**
 * The home page as it is laid out, without the slide it makes to and from the Library: a glass surface on it reads
 * the page's picture by its place *in the page* ([placeOf]), which is the same at rest, in every frame of the slide
 * and while the page is scrolled out of view. (Its place on screen was used at first, frozen while the page slid
 * and shifted by a screen while it was away: a surface made or moved during the slide, the selected icon's glass
 * on the way back from the Library, kept a wrong place until the page settled, and showed another part of the picture.)
 */
object GlassPage {
    /** The page's own coordinates, inside its sliding layer (set by the home screen). */
    @Volatile var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
    fun placeOf(c: androidx.compose.ui.layout.LayoutCoordinates): Offset {
        val page = coords
        if (page != null && page.isAttached) {
            // (Only a surface inside the page: the menus, the Library and the pop-ups keep their place on screen.)
            var p: androidx.compose.ui.layout.LayoutCoordinates? = c; var n = 0
            while (p != null && p !== page && n++ < 400) p = p.parentCoordinates
            // (Whole pixels: worked out through the sliding layer, the place came back a hair different in every
            // frame of the slide, and every glass surface was made again in each of them.)
            if (p === page) runCatching { val o = page.localPositionOf(c, Offset.Zero); return Offset(Math.round(o.x).toFloat(), Math.round(o.y).toFloat()) }
        }
        return c.positionInRoot()
    }
}


/**
 * A colour through all of Glassio's glass, like a tinted window film: none, black or petrol, at a strength.
 * Read while drawing, so changing it repaints the glass at once.
 */
object GlassTint {
    val color = androidx.compose.runtime.mutableStateOf(Color.Transparent)
    val NAMES = linkedMapOf("none" to "None (clear)", "black" to "Black", "petrol" to "Petrol")
    fun of(kind: String, amount: Float): Color = when (kind) {
        "black" -> Color.Black.copy(alpha = amount.coerceIn(0f, 0.8f))
        "petrol" -> Color(0xFF0B4650).copy(alpha = amount.coerceIn(0f, 0.8f))
        else -> Color.Transparent
    }
    fun apply(st: com.glasslauncher.tv.data.Settings) { color.value = of(st.glassColor, st.glassColorAmount) }
}

/**
 * What a pop-up's glass reads when it opens over a page that isn't a picture of its own (the search, with its
 * posters over the background video): one copy of the screen behind it, taken as it opens (the window with the
 * video's latest copy under it, where the video shows through), blurred, as its glass environment. The video's copy
 * alone showed the video through the pop-up, as if the posters weren't there (his photo).
 */
object ScreenGlass {
    /** Blocking-free: asks for the copy and answers on the main thread (null: the pop-up keeps the usual glass). */
    fun capture(activity: android.app.Activity, st: com.glasslauncher.tv.data.Settings, done: (GlassEnv?) -> Unit) {
        val w = 480; val h = 270
        val win = runCatching { activity.window }.getOrNull() ?: return done(null)
        val dm = activity.resources.displayMetrics
        val top = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        runCatching {
            android.view.PixelCopy.request(win, top, { r ->
                if (r != android.view.PixelCopy.SUCCESS) { done(null); return@request }
                val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(out)
                c.drawColor(android.graphics.Color.BLACK)
                val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                // (The video is its own layer under the window: where it shows through, the window copy is clear.)
                if (VideoFrames.showing.value) VideoFrames.sharp.value?.asAndroidBitmap()?.let { v ->
                    runCatching { c.drawBitmap(v, null, android.graphics.Rect(0, 0, w, h), paint) }
                }
                c.drawBitmap(top, 0f, 0f, paint)
                // A soft blur: down to a small copy and up again, twice (cheap, smooth).
                fun scaled(src: android.graphics.Bitmap, sw: Int, sh: Int) = android.graphics.Bitmap.createScaledBitmap(src, sw, sh, true)
                val soft = scaled(scaled(scaled(out, 60, 34), 120, 68), w, h)
                val heavy = scaled(scaled(out, 24, 14), w, h)
                val sharp = out.asImageBitmap(); val blurred = soft.asImageBitmap(); val hv = heavy.asImageBitmap()
                listOf(sharp, blurred, hv).forEach { runCatching { it.prepareToDraw() } }
                // Frosted even for the clear glass styles: sharp posters under the list's words were hard to read.
                done(GlassEnv(blurred, blurred, hv, dm.widthPixels, dm.heightPixels, st.glassTint, st.refraction, blurDp = st.blurRadius * 0.45f, ignoreVideo = true))
            }, main)
        }.onFailure { done(null) }
    }
}

/** *Glass over a video* set to See-through, while the background is a video (set by the home screen). */
object VideoGlass { val clear = androidx.compose.runtime.mutableStateOf(false) }
