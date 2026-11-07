package com.glasslauncher.tv.ui

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import coil3.request.allowHardware
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.HueSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Hue Sync on the home screen: the lights follow what is on screen. The backdrop is what the glass already shows
 * (the carousel picture, your photo, the music page), so it costs nothing extra; the poster or logo is loaded small;
 * a video (your video background, a trailer) is followed live from the glass's copies of it.
 */
/** The lights take [color] while [focused] (an app in the dock, a poster in the Library), if chosen. */
@Composable
fun HueFocus(app: GlassApp, on: Boolean, focused: Boolean, color: androidx.compose.ui.graphics.Color?, poster: Boolean = false) {
    if (!on) return
    val was = androidx.compose.runtime.remember { booleanArrayOf(false) }
    LaunchedEffect(focused, color) {
        if (focused) {
            val c = color ?: return@LaunchedEffect
            // Not for every one passed on the way: only where the selection rests a moment.
            delay(140); was[0] = true
            HueSync.focusColor(app.settings, c.toArgb(), if (poster) app.settings.huePostersBri else app.settings.hueAppsBri)
        } else if (was[0]) { was[0] = false; HueSync.focusLeft() }
    }
}

@Composable
fun HueDriver(app: GlassApp, item: HeroItem?, on: Boolean) {
    val st = app.settings
    if (!HueSync.ready(st)) return
    val env = LocalGlassEnvRef.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // (Any change of the lights' settings sends the colours again at once.)
    LaunchedEffect(on, item?.key, st.hueSource, st.hueVideo, st.hueVideoSec, st.hueZones, st.hueMethods, st.hueIntensity, st.hueBri,
        st.hueSpread, st.hueFollowBri, st.hueGroups, st.hueLights) {
        if (!on) return@LaunchedEffect
        val url = when (st.hueSource) { "poster" -> item?.poster; "logo" -> item?.logo; else -> null }
        val art: Bitmap? = if (url.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val req = coil3.request.ImageRequest.Builder(ctx).data(url).size(96, 96).allowHardware(false).build()
                (coil3.SingletonImageLoader.get(ctx).execute(req).image as? coil3.BitmapImage)?.bitmap
            }.getOrNull()
        }
        // A new picture goes to the lights once it has settled on screen; a video every [hueVideoSec].
        // (While a title changes, the glass first holds its small blurred copy, then the sharp picture: sent both,
        // the lights took the blurred one's colours for a moment, then the right ones. Going through several
        // titles quickly sent each of them too.)
        var last: Any? = null
        var lastVideoAt = 0L
        var seen: Any? = null
        var seenAt = 0L
        while (true) {
            // A selected app or poster has the lights: the screen's colours wait (and are sent again after it).
            if (HueSync.focusOn) { last = null; delay(100); continue }
            val video = st.hueVideo && VideoFrames.showing.value
            val e = env.value
            val img = when {
                video -> VideoFrames.soft.value ?: VideoFrames.sharp.value
                art != null -> art
                else -> e.sharp ?: e.bmp
            }
            val now = android.os.SystemClock.uptimeMillis()
            if (img !== seen) { seen = img; seenAt = now }
            // Settled: the sharp picture for a moment, or the blurred copy for longer (it may be all there is).
            val settled = now - seenAt >= (if (art != null || e.sharp != null) 200L else 900L)
            val due = if (video) now - lastVideoAt >= (if (st.hueVideoSec <= 0) 500L else st.hueVideoSec * 1000L) else img !== last && settled
            if (img != null && due) {
                last = img; lastVideoAt = now
                val b = (img as? Bitmap) ?: (img as androidx.compose.ui.graphics.ImageBitmap).asAndroidBitmap()
                withContext(Dispatchers.Default) { HueSync.showPicture(st, b) }
            }
            delay(100)
        }
    }
}
