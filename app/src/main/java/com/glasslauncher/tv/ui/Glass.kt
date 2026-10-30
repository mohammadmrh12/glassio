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
    return if (kind == SLIDE_APPLE) SlideFrame(dir * (1f - e) * w * 0.12f, -dir * e * w * 0.20f, edge, dir > 0, 0.30f * e)
    else SlideFrame(dir * (1f - e) * w, -dir * e * w, edge, dir > 0, 0f)
}

/** How dark the page gets under the open dock. */
const val VEIL_DIM = 0.62f

/** The open-dock veil in one pass: the blurred picture already darkened (instead of a black layer on top of it). */
val VeilFilter = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix(floatArrayOf(
    1f - VEIL_DIM, 0f, 0f, 0f, 0f,  0f, 1f - VEIL_DIM, 0f, 0f, 0f,  0f, 0f, 1f - VEIL_DIM, 0f, 0f,  0f, 0f, 0f, 1f, 0f)))

val LocalGlassEnv = compositionLocalOf { GlassEnv(null, null, null, 0, 0, 6, 14) }
/** 1.0 at 960dp wide (1080p TV at 2x); every size in the UI is multiplied by this. */
val LocalScale = staticCompositionLocalOf { 1f }

/**
 * Blurs the backdrop once on the CPU: shrink it hard (that alone averages pixels), then three box
 * passes. Every glass surface draws from this one small bitmap, so no surface needs its own GPU blur.
 * [strength] 0..80 (the "Glass blur" setting).
 */
fun blurredBackdrop(src: Bitmap, strength: Int): ImageBitmap {
    val w = (320 - strength * 3).coerceIn(48, 320)
    val h = max(8, w * src.height / max(1, src.width))
    val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
    val r = if (strength <= 0) 0 else (1 + strength / 20).coerceAtMost(4)
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
    val sw = size.width / scale; val sh = size.height / scale
    val l = sx.coerceIn(0f, bw - 1f); val t = sy.coerceIn(0f, bh - 1f)
    val r = (sx + sw).coerceIn(l + 1f, bw); val b = (sy + sh).coerceIn(t + 1f, bh)
    drawImage(
        bmp,
        srcOffset = IntOffset(l.toInt(), t.toInt()),
        srcSize = IntSize((r - l).roundToInt().coerceAtLeast(1), (b - t).roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset(((l - sx) * scale).roundToInt(), ((t - sy) * scale).roundToInt()),
        dstSize = IntSize(((r - l) * scale).roundToInt().coerceAtLeast(1), ((b - t) * scale).roundToInt().coerceAtLeast(1)),
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = if (scale <= 1.01f) FilterQuality.None else FilterQuality.Low,
    )
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
    val side: ImageBitmap by lazy { paint(true) }
    val bottom: ImageBitmap by lazy { paint(false) }
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
    content: @Composable BoxScope.() -> Unit = {},
) {
    val env = LocalGlassEnv.current
    val density = LocalDensity.current
    // Video behind: the glass changes every copy, a cached texture would only be drawn twice.
    val videoBehind = VideoFrames.showing.value
    val pos = remember { mutableStateOf(Offset.Zero) }
    // The last measured place, kept even while frozen, so the glass catches up when the page settles.
    val raw = remember { arrayOf(Offset.Zero) }
    val motion = GlassMotion.rev.intValue
    androidx.compose.runtime.LaunchedEffect(motion) { if (!GlassMotion.frozen) pos.value = Offset(raw[0].x, raw[0].y + GlassMotion.shiftY) }
    var sz by remember { mutableStateOf(IntSize.Zero) }
    val refractor = remember { Refractor() }
    val rPx = with(density) { radius.toPx() }
    val bPx = with(density) { bezel.toPx() }
    val fPx = with(density) { refraction.toPx() }
    val still = remember(sz, rPx, bPx, fPx) { refractor.effect(sz.width.toFloat(), sz.height.toFloat(), rPx, bPx, fPx, 0f) }
    val live = env.live
    Box(
        modifier
            .then(if (DebugHide.intValue and 262144 != 0) Modifier else Modifier.clip(RoundedCornerShape(radius)))
            .onGloballyPositioned { raw[0] = it.positionInRoot(); if (!GlassMotion.frozen) pos.value = Offset(raw[0].x, raw[0].y + GlassMotion.shiftY); if (it.size != sz) sz = it.size },
    ) {
        val bmp = if (sharp) env.sharp ?: env.bmp else env.bmp
        // The tint is drawn inside the cached glass layers when they're used (one less pass every frame).
        var tintDone = false
        fun androidx.compose.ui.graphics.drawscope.DrawScope.tintIn(t: Color) { if (t.alpha > 0f && DebugHide.intValue and 256 == 0) drawRect(t) }
        if (bmp != null) {
            fun isPage(b: ImageBitmap) = env.sharpIsPage && (b === env.sharp || b === env.prevSharp)
            // Page pictures carry their shades (with Slide all but the side shade); small copies carry none.
            fun androidx.compose.ui.graphics.drawscope.DrawScope.shade(b: ImageBitmap) {
                if (!isPage(b)) drawScrims(env.rootW, env.rootH, pos.value, true)
                else if (env.pageNoSideShade) drawScrims(env.rootW, env.rootH, pos.value, false)
            }
            fun androidx.compose.ui.graphics.drawscope.DrawScope.picture(b: ImageBitmap, shift: Float, withShade: Boolean = true) {
                if (DebugHide.intValue and 64 == 0) drawBackdropRegion(b, env.rootW, env.rootH, pos.value, scrims = false, shift = shift)
                if (withShade && DebugHide.intValue and 128 == 0) shade(b)
            }
            fun androidx.compose.ui.graphics.drawscope.DrawScope.veil() {
                val v = env.veil?.value ?: 0f
                if (v > 0.01f && DebugHide.intValue and 16 == 0) {
                    val heavy = if (!videoBehind && DebugHide.intValue and 32768 == 0) env.heavy else null
                    // Same as the page veil: dimmed while the dock opens, then the darkened blurred picture fades in.
                    // Like the page veil: the darkened blurred picture over the glass, one pass.
                    if (heavy != null) drawBackdropRegion(heavy, env.rootW, env.rootH, pos.value, alpha = v, scrims = false, colorFilter = VeilFilter)
                    else drawRect(Color.Black.copy(alpha = v * VEIL_DIM))
                }
            }
            val prev = if (sharp) env.prevSharp ?: env.prevBmp else env.prevBmp
            val dir = if (slideInside) env.slide else 0
            if (prev != null && dir != 0 && Build.VERSION.SDK_INT >= 33) {
                // Slide, Android 13+: the middle of the glass is drawn like the page (two plain pictures, cheap),
                // and only the rim band, where the refraction is, is drawn with a shader brush that reads both
                // pictures and bends them itself. No offscreen layer is redrawn and re-refracted per frame, and
                // the rims look exactly as when the glass is still, so nothing jumps when the move ends.
                val curS = remember(bmp) { PageShader(bmp) }
                val prevS = remember(prev) { PageShader(prev) }
                // Small glass (an app tile): the whole shape is drawn with the shader, not just the rim. With the
                // middle left to the page underneath, the edge of the rim band showed as a glass rectangle inside the
                // selected icon for as long as the slide lasted. On a small surface drawing it all costs nothing.
                val whole = sz.width.toLong() * sz.height < env.rootW.toLong() * env.rootH / 12
                val ring = remember(sz, rPx, bPx, whole) {
                    val w = sz.width.toFloat(); val h = sz.height.toFloat()
                    val rr = min(rPx, min(w, h) / 2f); val b = max(1f, bPx)
                    androidx.compose.ui.graphics.Path().apply {
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, w, h, androidx.compose.ui.geometry.CornerRadius(rr)))
                        if (!whole && w > 2 * b && h > 2 * b) addRoundRect(androidx.compose.ui.geometry.RoundRect(b, b, w - b, h - b,
                            androidx.compose.ui.geometry.CornerRadius(max(0f, rr - b))))
                    }
                }
                Canvas(Modifier.matchParentSize()) {
                    val p = pos.value
                    val W = env.rootW.toFloat()
                    val f = slideFrame(env.slideKind, dir, env.mix?.value ?: 1f, W)
                    // Clear glass showing the page pictures themselves: its middle is exactly the page that is
                    // already drawn underneath, so it is left empty and only the rim is drawn. Small (blurred)
                    // copies differ from the page, so they are drawn in full.
                    if (!isPage(bmp)) {
                        picture(prev, f.prevShift, withShade = false)
                        if (f.dim > 0f) drawRect(Color.Black.copy(alpha = f.dim))
                        val e = f.edge - p.x
                        clipRect(left = if (f.curRight) e else -1e5f, right = if (f.curRight) 1e5f else e) { picture(bmp, f.curShift, withShade = false) }
                        shade(bmp)
                    }
                    if (fPx < 0.5f) { veil(); return@Canvas }
                    curS.place(env.rootW, env.rootH, p); prevS.place(env.rootW, env.rootH, p)
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
                    val page = isPage(bmp)
                    sh.setFloatUniform("side", if (!page || env.pageNoSideShade) 1f else 0f)
                    sh.setFloatUniform("bottom", if (!page) 1f else 0f)
                    drawPath(ring, androidx.compose.ui.graphics.ShaderBrush(sh))
                    veil()
                }
            } else if (prev != null && dir != 0) {
                // Slide: both pictures move behind the glass exactly as the page does. The rim refraction stays
                // on: switching it off for the move made the rims jump when it came back (worse the stronger it is).
                Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = still }) {
                    val f = slideFrame(env.slideKind, dir, env.mix?.value ?: 1f, env.rootW.toFloat())
                    picture(prev, f.prevShift, withShade = false)
                    if (f.dim > 0f) drawRect(Color.Black.copy(alpha = f.dim))
                    val e = f.edge - pos.value.x
                    clipRect(
                        left = if (f.curRight) e else -1e5f, right = if (f.curRight) 1e5f else e,
                    ) { picture(bmp, f.curShift, withShade = false) }
                    shade(bmp)
                    veil()
                }
            } else {
                // While the page crossfades, the old and the new glass are two cached layers and only the
                // top one's opacity changes: in step with the page, without redrawing or re-refracting the glass.
                // The old glass is refracted too (rendered once, it doesn't change), so the rims don't thin out
                // and snap back while the new one fades in on top.
                // The open-dock veil is its own layer above (see below): only its opacity changes while it fades
                // in, so these stay cached instead of being redrawn (picture, shades, refraction) every frame.
                val veilPic = if (!videoBehind && DebugHide.intValue and 32768 == 0) env.heavy else null
                val covered = { veilPic != null && (env.veil?.value ?: 0f) > 0.995f && DebugHide.intValue and 16 == 0 }
                if (prev != null) Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = still; alpha = if (covered()) 0f else 1f }) { picture(prev, 0f); if (veilPic == null) veil(); tintIn(tint) }
                Canvas(Modifier.matchParentSize().graphicsLayer {
                    val w = if (wobble > 0f && live != null) live.value * wobble else 0f
                    renderEffect = if (videoBehind && DebugHide.intValue and 2048 != 0) null else if (!cache && DebugHide.intValue and 16384 != 0) null else if (w < 0.01f) still else refractor.effect(size.width, size.height, rPx, bPx, fPx, w)
                    alpha = if (covered()) 0f else if (prev != null) (env.mix?.value ?: 1f) else 1f
                    // Rendered once into its own texture and reused while nothing behind the glass changes:
                    // moving focus over the dock then costs one copy instead of the picture and its shades again.
                    if (cache && DebugHide.intValue and 1024 == 0) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                }) {
                    // A video behind: the glass shows its live frames (sharp or frosted), with the page's shades.
                    val live = if (sharp) VideoFrames.sharp.value ?: VideoFrames.soft.value else VideoFrames.soft.value ?: VideoFrames.sharp.value
                    if (live != null) {
                        // The page's shades (pre-painted pictures) drawn over the live copy, as on the page itself.
                        drawBackdropRegion(live, env.rootW, env.rootH, pos.value, scrims = DebugHide.intValue and 128 == 0)
                    } else picture(bmp, 0f)
                    if (veilPic == null) veil()
                    tintIn(tint)
                }
                if (veilPic != null && DebugHide.intValue and 16 == 0) {
                    val veilOn by remember(env.veil) { androidx.compose.runtime.derivedStateOf { (env.veil?.value ?: 0f) > 0.005f } }
                    if (veilOn) Canvas(Modifier.matchParentSize().graphicsLayer {
                        alpha = env.veil?.value ?: 0f
                        renderEffect = still
                        if (cache && DebugHide.intValue and 1024 == 0) compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                    }) {
                        drawBackdropRegion(veilPic, env.rootW, env.rootH, pos.value, scrims = false, colorFilter = VeilFilter)
                        tintIn(tint)
                    }
                }
                tintDone = true
            }
        } else {
            Box(Modifier.matchParentSize().background(Color(0xFF1A1A22)))
        }
        if (!tintDone && tint.alpha > 0f && DebugHide.intValue and 256 == 0) Box(Modifier.matchParentSize().background(tint))
        content()
    }
}

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
    val env = LocalGlassEnv.current
    val shape = RoundedCornerShape(radius)
    // The content always sits in the same place whatever the style: switching style (Settings background) used to
    // swap the whole panel, which rebuilt what was inside it (Settings jumped to the top with nothing selected).
    // Only the flat styles clip here; the glass clips itself (one rounded clip layer less on the TV GPU).
    Box(if (style == "amoled" || style == "bow") modifier.clip(shape) else modifier) {
        val fill = Modifier.matchParentSize()
        when (style) {
            "amoled" -> Box(fill.background(Color.Black.copy(alpha = 0.94f)))
            // Soft off-white like the iOS 26 light icons, shading a little towards the bottom (not paper white).
            "bow" -> Box(fill.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xF2E3E3E7), Color(0xF2D2D2D8)))))
            // Glass like glass: almost no frost or tint, strong rim bending, ripples after a move.
            "water", "water_still" -> LiquidSurface(
                fill, radius, if (refract) (env.refractionDp * 1.5f).dp else 0.dp, (radius * 1.2f).coerceIn(12.dp, 34.dp),
                tint = Color.Black.copy(alpha = darken.coerceAtMost(0.9f)), sharp = true, wobble = if (refract && style == "water") env.wobble else 0f, cache = cache,
            )
            // Frosted panels show a blurred copy: during a slide they crossfade (looks the same, half the GPU work).
            "clear" -> {
                LiquidSurface(fill, radius, 0.dp, 10.dp, tint = Color.White.copy(alpha = 0.06f), slideInside = false, cache = cache)
                if (darken > 0f) Box(fill.background(Color.Black.copy(alpha = darken)))
            }
            else -> LiquidSurface(
                fill, radius, if (refract) env.refractionDp.dp else 0.dp, (radius * 1.1f).coerceIn(10.dp, 30.dp),
                tint = Color.Black.copy(alpha = (0.14f + darken).coerceAtMost(0.9f)), slideInside = false, cache = cache,
            )
        }
        content()
        val rim = when (style) { "amoled" -> 0.08f; "bow" -> 0.6f; "water", "water_still" -> 0.16f; else -> 0.10f }
        Box(fill.border(if (style == "bow") 0.8.dp else 0.6.dp, Color.White.copy(alpha = rim), shape))
    }
}

/** Kept for the screens that only need the clip. */
fun Modifier.roundClip(r: Dp) = this.clip(RoundedCornerShape(r))

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
    // The last measured place, kept even while frozen, so the glass catches up when the page settles.
    val raw = remember { arrayOf(Offset.Zero) }
    val motion = GlassMotion.rev.intValue
    androidx.compose.runtime.LaunchedEffect(motion) { if (!GlassMotion.frozen) pos.value = Offset(raw[0].x, raw[0].y + GlassMotion.shiftY) }
    val shaderOk = Build.VERSION.SDK_INT >= 33
    val cur = env.sharp?.takeIf { env.sharpIsPage }
    val prev = env.prevSharp?.takeIf { env.sharpIsPage }
    // DECAL bitmap shaders need Android 12+; the shader path itself needs 13, so only build them there.
    val curS = remember(cur) { if (Build.VERSION.SDK_INT >= 33) cur?.let { PageShader(it) } else null }
    val prevS = remember(prev) { if (Build.VERSION.SDK_INT >= 33) prev?.let { PageShader(it) } else null }
    Canvas(modifier.width(wDp).height(hDp).onGloballyPositioned { raw[0] = it.positionInRoot(); if (!GlassMotion.frozen) pos.value = Offset(raw[0].x, raw[0].y + GlassMotion.shiftY) }) {
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
            sh.setFloatUniform("side", if (env.pageNoSideShade) 1f else 0f)
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
 * While the whole page slides (Down from the dock into the Library), the glass keeps the picture it showed at
 * rest and just moves with the page: following the slide re-made every glass surface every frame (half the frame time).
 */
object GlassMotion {
    @Volatile var frozen = false
    /** While the home screen is scrolled up out of view (the Library is up), how far: the glass still samples
     *  the picture where the dock really is, so it is right the moment it comes back. */
    @Volatile var shiftY = 0f
    /** Bumped when [frozen] or [shiftY] change: every glass surface then re-reads its place. */
    val rev = androidx.compose.runtime.mutableIntStateOf(0)
    fun set(frozen: Boolean, shift: Float) {
        if (frozen == this.frozen && shift == shiftY) return
        this.frozen = frozen; shiftY = shift; rev.intValue++
    }
}
