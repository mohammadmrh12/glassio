package com.glasslauncher.tv.saver

import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import com.glasslauncher.tv.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.SystemInfo
import com.glasslauncher.tv.ui.clockText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Remote keys while the screensaver plays: ◀ ▶ change the video, OK shows / hides the info. */
/** The trailer screensaver: bumped with every new trailer (the clock and date move to another corner then). */
object TrailerSaver { val rev = androidx.compose.runtime.mutableIntStateOf(0) }

object SaverControl {
    /** +1 next video, -1 previous one (read and cleared by the player loop). */
    @Volatile var step = 0
    val infoHidden = androidx.compose.runtime.mutableStateOf(false)
    /** The large clock on the screensaver (shown whenever it is on in Settings). */
    val clockShown = androidx.compose.runtime.mutableStateOf(true)
    /** Bumped by OK to show the info again (it then goes after its time, as after a new video). */
    val showRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Whether the info is on screen right now (written while it draws). */
    @Volatile var infoOnScreen = false
    /** What the screensaver was playing when it ended, and where in it (the background goes on with it: Same video). */
    @Volatile var endedTitle: String? = null
    @Volatile var endedPos = 0L
    @Volatile var endedAt = 0L
    /** Where the screensaver's video is now, read on the main thread (null: no video playing). */
    @Volatile var positionNow: (() -> Long)? = null
    @Volatile var playingTitle: String? = null
    @Volatile var playingSince = 0L
    @Volatile var playingFrom = 0L
    /** The link of the video on screen (for Lock & release). */
    @Volatile var currentUrl: String? = null
    /** Shown after a hold of OK: true = just locked, false = released; [lockRev] restarts the animation. */
    val lockShown = androidx.compose.runtime.mutableStateOf<Boolean?>(null)
    val lockRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Closing: everything fades to black before the screen goes. */
    val out = androidx.compose.runtime.mutableStateOf(false)
    /** Posters and Trailers: OK opens the title on screen; held OK opens its menu (Trailers). */
    @Volatile var onOk: (() -> Unit)? = null
    @Volatile var onLong: (() -> Unit)? = null
    /** A menu is open on the screensaver: the keys go to it. */
    val popOpen = androidx.compose.runtime.mutableStateOf(false)
    /** A title was opened from it: the TV's own screensaver (dream) ends then (the activity ends by itself). */
    @Volatile var afterOpen: (() -> Unit)? = null

    private var okDown = false
    private var okLong = false

    /** The keys the screensaver uses itself (any other key ends it). */
    fun handles(code: Int, st: com.glasslauncher.tv.data.Settings): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> true
        // ▲ opens the music island (while a song plays), ▼ closes it, or brings up the cameras.
        android.view.KeyEvent.KEYCODE_DPAD_UP -> islandHere(st)
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> st.saverMultiCam || com.glasslauncher.tv.ui.MusicUi.islandOpen.value
        else -> false
    }

    /**
     * A key while the screensaver plays (activity or the TV's own screensaver). OK acts when it is let go
     * (a short press shows / hides the info); held, it locks or releases the video. True when used.
     */
    private fun islandHere(st: com.glasslauncher.tv.data.Settings) =
        st.musicOn && st.musicIsland && com.glasslauncher.tv.data.NowPlaying.track.value != null

    /** The app (for the island's buttons). */
    @Volatile var ctx: android.content.Context? = null

    fun onKey(e: android.view.KeyEvent, st: com.glasslauncher.tv.data.Settings): Boolean {
        val code = e.keyCode
        // The island open: its keys (◀ ▶ choose, OK presses, ▼ / Back close it).
        if (com.glasslauncher.tv.ui.MusicUi.islandOpen.value) {
            val c = ctx ?: return false
            if (e.action == android.view.KeyEvent.ACTION_DOWN) return com.glasslauncher.tv.ui.MusicUi.islandKey(c, code)
            return code in setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_BACK)
        }
        // Back with the cameras up closes just them (the screensaver stays).
        if (code == android.view.KeyEvent.KEYCODE_BACK && com.glasslauncher.tv.HomeKeyService.instance?.multiShowing == true) {
            if (e.action == android.view.KeyEvent.ACTION_UP) com.glasslauncher.tv.HomeKeyService.instance?.closeMulti()
            return true
        }
        if (!handles(code, st)) return false
        if (code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER) {
            if (e.action == android.view.KeyEvent.ACTION_DOWN) {
                if (e.repeatCount == 0) { okDown = true; okLong = false }
                else if (okDown && !okLong && onLong != null) { okLong = true; onLong?.invoke() }
                else if (okDown && !okLong && st.saverLock && onOk == null) { okLong = true; toggleLock(st) }
            } else if (e.action == android.view.KeyEvent.ACTION_UP) {
                if (okDown && !okLong) { val ok = onOk; if (ok != null) ok() else key(code) }
                okDown = false
            }
            return true
        }
        if (e.action == android.view.KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            if (code == android.view.KeyEvent.KEYCODE_DPAD_UP) com.glasslauncher.tv.ui.MusicUi.islandOpen.value = true
            else key(code)
        }
        return true
    }

    /** The video on screen: its id and title (for the held-OK menu). */
    fun currentId(): String? = currentUrl?.let { AerialSource.idOfUrl[it] }
    fun locked(st: com.glasslauncher.tv.data.Settings) = currentId()?.let { it in st.saverLocked } == true
    /** Never shows the video on screen again and goes on to the next one. */
    /** False: the video on screen isn't known (nothing was hidden). */
    fun hideCurrent(st: com.glasslauncher.tv.data.Settings, title: String): Boolean {
        val id = currentId() ?: return false
        st.saverHidden = (st.saverHidden.filter { it.substringBefore('\t') != id } + "$id\t${title.replace('\t', ' ')}")
        st.saverLocked = st.saverLocked - id
        step = 1
        return true
    }
    internal fun toggleLock(st: com.glasslauncher.tv.data.Settings) {
        val id = currentUrl?.let { AerialSource.idOfUrl[it] } ?: return
        val l = st.saverLocked
        val now = id !in l
        st.saverLocked = if (now) l + id else l - id
        lockShown.value = now; lockRev.intValue++
    }

    /** True when the key was used (◀ ▶ OK); any other key ends the screensaver. */
    fun key(code: Int): Boolean = when (code) {
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { step = 1; true }
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { step = -1; true }
        // OK: hides the info when it is showing; when it isn't (hidden, or gone by itself after its time), it
        // comes back. Toggling a flag alone needed two presses once the info had gone by itself.
        android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
            if (!infoHidden.value && infoOnScreen) infoHidden.value = true
            else { infoHidden.value = false; showRev.intValue++ }
            true
        }
        // ▼: the cameras slide up from the bottom (multi-view).
        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { com.glasslauncher.tv.HomeKeyService.instance?.toggleMulti(bottom = true); true }
        else -> false
    }
}

/**
 * The screensaver: aerial videos (cross-faded one into the next, kept on the TV as they play) or the posters,
 * with the clock, date, weather, place and the TV's numbers in Apple's style or one by one like the notes
 * before a Netflix film.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun SaverContent(app: GlassApp, forceSlideshow: Boolean = false) {
    val ctx = LocalContext.current
    val st = app.settings
    val trailers = !forceSlideshow && st.saverMode == "trailer"
    val slideshow = forceSlideshow || st.saverMode == "slideshow" || trailers
    // null while the list loads: black meanwhile (the posters showed a title's logo for a moment).
    // Where the first video starts (Same video: where the background was in it).
    // (Remembered: a plain variable is a new one at every recomposition, and the player read 0.)
    val sameFromBox = remember { longArrayOf(0L) }
    // (Made again when HEVC is found not to play here: the rest of the list was HEVC too, #53.)
    val items by produceState<List<Pair<String, String>>?>(if (slideshow) emptyList() else null, st.hevcFailed) {
        if (!slideshow) value = withContext(Dispatchers.IO) { AerialSource.playlistItems(app) }.let { l ->
            // *Same video*: with the aerial background, the screensaver starts with the video the background was
            // showing, where it was, instead of loading a second one (on a TV with little memory that was felt, #22).
            val bg = com.glasslauncher.tv.ui.CustomBackground.leftVideo?.let { com.glasslauncher.tv.ui.CustomBackground.aerialTitles[it] }
            val i = if (st.saverSameVideo && st.bgMode == "aerial" && bg != null) l.indexOfFirst { it.first == bg } else -1
            if (i > 0) listOf(l[i]) + l.filterIndexed { k, _ -> k != i } else l
        }.also { l -> sameFromBox[0] = if (st.saverSameVideo && st.bgMode == "aerial" && l.firstOrNull()?.first != null &&
            l.first().first == com.glasslauncher.tv.ui.CustomBackground.leftVideo?.let { com.glasslauncher.tv.ui.CustomBackground.aerialTitles[it] }) com.glasslauncher.tv.ui.CustomBackground.leftPos else 0L }
    }
    var title by remember { mutableStateOf("") }
    var change by remember { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    // The clock as it was left last time (shown or hidden); no padlock left over from last time.
    remember { SaverControl.clockShown.value = true; SaverControl.lockShown.value = null; SaverControl.out.value = false; SaverControl.ctx = ctx.applicationContext; com.glasslauncher.tv.ui.MusicUi.islandOpen.value = false; 0 }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val list = items
        if (list == null) { /* loading: black */ }
        else if (list.isNotEmpty() && !failed) {
            // *Hold OK asks* (off: hold OK locks, as always): keep the video (lock it), or never show it again.
            var pop by remember { mutableStateOf<com.glasslauncher.tv.ui.PopState?>(null) }
            if (st.saverHoldMenu) {
                DisposableEffect(Unit) {
                    val mine: () -> Unit = {
                        // (The name of the video really on screen: [title] follows a moment later, after the fade.)
                        val t = list.firstOrNull { it.second == SaverControl.currentUrl }?.first ?: title
                        pop = com.glasslauncher.tv.ui.PopState(t.ifBlank { "Video" }, listOf(
                            com.glasslauncher.tv.ui.PopOption(if (SaverControl.locked(st)) "Release this video" else "Keep this video", if (SaverControl.locked(st)) "✓" else "") { SaverControl.toggleLock(st) },
                            com.glasslauncher.tv.ui.PopOption("Don't show it again") { if (SaverControl.hideCurrent(st, t)) com.glasslauncher.tv.ui.toast(ctx, "Won't show again · Settings → Background & screensaver → Hidden videos") },
                        ), null)
                    }
                    SaverControl.onLong = mine
                    onDispose { if (SaverControl.onLong === mine) SaverControl.onLong = null; SaverControl.popOpen.value = false }
                }
                LaunchedEffect(pop) { SaverControl.popOpen.value = pop != null }
            }
            val factory = remember { VideoCache.factory(ctx, st.saverCache, st.saverCacheGb) }
            // A frame of the new video is on screen (the fade back in waits for it).
            val rendered = remember { BooleanArray(1) }
            // More buffered before it starts (the first seconds of a new video stuttered while it downloaded).
            // (The same for every TV: a smaller buffer for Lite made the screensaver stop to load on a weak TV, #34.)
            val load = remember { androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(20_000, 60_000, 4_000, 6_000).build() }
            // The video let go under the cameras and not drawn again yet: the blurred copy stays over it till then.
            val underCams = remember { mutableStateOf(false) }
            val stillFrame = remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            val resumedAt = remember { longArrayOf(0L) }
            val saverErrors = remember { IntArray(1) }
            val players = remember { Array(1) { k -> ExoPlayer.Builder(ctx).setMediaSourceFactory(factory).setLoadControl(load).build().apply {
                volume = 0f; com.glasslauncher.tv.data.MediaLog.underGlassio(this)
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        rendered[k] = true; saverErrors[0] = 0
                        if (underCams.value) com.glasslauncher.tv.data.Glog.i("Saver: back after the cameras in ${android.os.SystemClock.uptimeMillis() - resumedAt[0]} ms")
                        underCams.value = false
                    }
                    // (A video that fails was left on screen as it was (a Fire TV's green picture, #40) and nothing was
                    // written: now it's in the log and the next one comes, as the background does; six in a row at most.)
                    override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                        com.glasslauncher.tv.data.MediaLog.error("Screensaver", this@apply, e, hevcOf = st)
                        if (++saverErrors[0] <= 6) SaverControl.step = 1
                    }
                })
            } } }
            // The cameras up (▼, multi-view): the video waits under them and goes on from there when they close (his
            // idea). Behind the cameras it is blurred and dimmed anyway, and playing on it cost the TV a decoder's
            // work and a fresh blurred copy of it for every frame, with three or four cameras' videos on top. Paused
            // it still held its decoder, and the fourth camera found none (DECODER_INIT_FAILED, his "one camera
            // doesn't play"): once the veil has its copy it is let go, and taken up again where it was.
            val view = remember { arrayOfNulls<SurfaceView>(1) }
            val camsUp = com.glasslauncher.tv.MultiCamOverlay.open.value
            val heldAt = remember { longArrayOf(-1L) }
            LaunchedEffect(camsUp) {
                val p = players[0]
                if (camsUp) {
                    p.playWhenReady = false
                    delay(300)
                    if (p.currentMediaItem != null && p.playbackState != Player.STATE_IDLE) {
                        // A sharp copy of where it stopped stands in for it after the cameras close, until it draws
                        // again (~1 s): the blur goes with the cameras (it stayed that second, his report).
                        view[0]?.takeIf { it.holder.surface.isValid }?.let { v ->
                            val b = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888)
                            val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { c ->
                                runCatching { android.view.PixelCopy.request(v, b, { r -> if (c.isActive) c.resumeWith(Result.success(r == android.view.PixelCopy.SUCCESS)) }, android.os.Handler(android.os.Looper.getMainLooper())) }
                                    .onFailure { if (c.isActive) c.resumeWith(Result.success(false)) }
                            }
                            stillFrame.value = if (ok && !com.glasslauncher.tv.ui.looksEmpty(b)) b.asImageBitmap() else null
                        }
                        heldAt[0] = p.currentPosition; underCams.value = true; rendered[0] = false; p.stop()
                    }
                } else if (heldAt[0] >= 0) {
                    val at = heldAt[0]; heldAt[0] = -1L
                    // (To the nearest key frame before preparing: the exact place took 1.3 s to decode its way to, the
                    // veil's wait he saw after the cameras closed.)
                    resumedAt[0] = android.os.SystemClock.uptimeMillis(); p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC); p.seekTo(at); p.prepare(); p.playWhenReady = true
                    p.setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
                    // (Not for ever, if it never draws: the veil goes after 4 s.)
                    delay(4000); underCams.value = false
                } else p.playWhenReady = true
            }
            // Glassio left (another app in front): the video stops now. Drawn on the home screen, the screensaver is only
            // taken down when Glassio draws again, and its player went on streaming under the other app until then.
            val life = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
            DisposableEffect(life) {
                val o = androidx.lifecycle.LifecycleEventObserver { _, ev -> if (ev == androidx.lifecycle.Lifecycle.Event.ON_STOP) players.forEach { runCatching { it.stop() } } }
                life.addObserver(o); onDispose { life.removeObserver(o) }
            }
            // (What "Same video" reads is cleared with the players: left over, the next screensaver of another kind
            // handed the background an old title and a place past its end.)
            DisposableEffect(Unit) { onDispose { players.forEach { it.release() }; SaverControl.positionNow = null; SaverControl.playingTitle = null; SaverControl.playingSince = 0L; SaverControl.playingFrom = 0L; SaverControl.step = 0; SaverControl.infoHidden.value = false; SaverControl.lockShown.value = null; SaverFrames.sharp.value = null; com.glasslauncher.tv.ui.MusicUi.islandOpen.value = false; com.glasslauncher.tv.HomeKeyService.instance?.closeMulti() } }
            // One SurfaceView: the video goes straight to the TV at its own size and colour, so 4K is really 4K and
            // HDR / Dolby Vision is shown as HDR. Changing video fades through black with a layer above it (the
            // TV ignored a SurfaceView's own transparency, so cross-fading two of them cut instead).
            val black = remember { Animatable(1f) }
            AndroidView(factory = { c -> SurfaceView(c).also { view[0] = it } }, modifier = Modifier.fillMaxSize())
            // (The cameras over it don't ask the TV to blur behind their window: this draws its own veil.)
            DisposableEffect(Unit) { com.glasslauncher.tv.MultiCamOverlay.ownVeil++; onDispose { com.glasslauncher.tv.MultiCamOverlay.ownVeil-- } }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = black.value }.background(Color.Black))
            // The large clock in a glass look shows this video through its digits, like the dock's glass: live
            // copies of the video (PixelCopy, scaled on the GPU), ~15 a second, only while the clock is up.
            // Multi-view cameras over the screensaver: its video blurs behind them (a small live copy of it, blurred
            // and stretched over it: the TV can't blur behind a window, nor a video surface directly).
            val multiOpen = com.glasslauncher.tv.MultiCamOverlay.open.value
            // (The video let go and not back yet: its sharp still frame, without the blur; the blur only while the
            // cameras show, or when there is no still frame.)
            if (underCams.value) stillFrame.value?.let { f -> androidx.compose.foundation.Image(f, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            val multiVeil by androidx.compose.animation.core.animateFloatAsState(if (multiOpen || (underCams.value && stillFrame.value == null)) 1f else 0f, androidx.compose.animation.core.tween(if (multiOpen) 180 else 150), label = "multiVeil")
            if (multiVeil > 0.01f) {
                // A tiny copy, softened on the processor and stretched over the screen (stretching it that much is
                // itself a soft blur), on every TV. The GPU's blur of a screen-size picture in every frame, with the
                // cameras' videos over it, took the frames from 14 to ~50 ms on the Streamer (his "lag").
                val gpuBlur = false
                val frame = remember { androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                LaunchedEffect(Unit) {
                    val bufs = Array(2) { if (gpuBlur) android.graphics.Bitmap.createBitmap(320, 180, android.graphics.Bitmap.Config.ARGB_8888)
                        else android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888) }
                    var k = 0
                    val h = android.os.Handler(android.os.Looper.getMainLooper())
                    while (true) {
                        val v = view[0]
                        if (v != null && v.holder.surface.isValid) {
                            val b = bufs[k++ % 2]
                            val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { c ->
                                runCatching { android.view.PixelCopy.request(v, b, { r -> if (c.isActive) c.resumeWith(Result.success(r == android.view.PixelCopy.SUCCESS)) }, h) }
                                    .onFailure { if (c.isActive) c.resumeWith(Result.success(false)) }
                            }
                            if (ok && !gpuBlur) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { softBlur(b) }
                            if (ok) frame.value = b.asImageBitmap()
                            // (The video waits while the cameras are up: one copy is all it takes.)
                            if (ok && multiOpen) break
                        }
                        delay(70)
                    }
                }
                frame.value?.let { f ->
                    androidx.compose.foundation.Image(f, null, Modifier.fillMaxSize().graphicsLayer { alpha = multiVeil }.then(if (gpuBlur) Modifier.blur(28.dp) else Modifier),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
                }
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = multiVeil }.background(Color.Black.copy(alpha = 0.2f)))
            }
            val glassClock = st.saverBigClock && com.glasslauncher.tv.ui.clockLook(st) != "white" && st.dockStyle != "bow" && st.dockStyle != "amoled"
            LaunchedEffect(Unit) { snapshotFlow { black.value }.collect { SaverFrames.dim.floatValue = it; com.glasslauncher.tv.ui.VideoFrames.dim.floatValue = it } }
            // Left before its video faded in (in and out quickly): the darkness it set stayed, and the home screen's
            // glass was drawn black until the screensaver had run once more. It goes with the screensaver.
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { SaverFrames.dim.floatValue = 0f; com.glasslauncher.tv.ui.VideoFrames.dim.floatValue = 0f }
            }
            // Hue Sync on the screensaver: a small copy of its video every few seconds.
            if (com.glasslauncher.tv.data.HueSync.ready(st) && st.hueSaver) LaunchedEffect(Unit) {
                val buf = android.graphics.Bitmap.createBitmap(64, 36, android.graphics.Bitmap.Config.ARGB_8888)
                val h = android.os.Handler(android.os.Looper.getMainLooper())
                while (true) {
                    delay(if (st.hueVideoSec <= 0) (if (st.lean) 2000L else 500L) else st.hueVideoSec * 1000L)
                    val v = view[0] ?: continue
                    // (Not while the cameras are up: the video waits, its colours don't change, and a copy of a 4K
                    // picture is costly for the drawing thread while the cameras play.)
                    if (!v.holder.surface.isValid || black.value > 0.5f || com.glasslauncher.tv.MultiCamOverlay.open.value) continue
                    val ok = kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { k ->
                        runCatching { android.view.PixelCopy.request(v, buf, { r -> if (k.isActive) k.resumeWith(Result.success(r == android.view.PixelCopy.SUCCESS)) }, h) }
                            .onFailure { if (k.isActive) k.resumeWith(Result.success(false)) }
                    }
                    if (ok) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { com.glasslauncher.tv.data.HueSync.showPicture(st, buf) }
                }
            }
            // The music island in a glass look: live copies of this video for its glass (see MusicIsland).
            val islandGlass = saverIslandGlass(st)
            if (islandGlass) {
                // (The home screen's choice of copies back afterwards: left at the island's, the home screen's clock and
                // glass waited for copies that were no longer made.)
                DisposableEffect(Unit) {
                    val iv = com.glasslauncher.tv.ui.VideoFrames
                    val was = Triple(iv.wantSharp, iv.wantSoft, iv.softRadius)
                    onDispose { iv.wantSharp = was.first; iv.wantSoft = was.second; iv.softRadius = was.third }
                }
                LaunchedEffect(Unit) {
                    while (view[0] == null) delay(50)
                    val iv = com.glasslauncher.tv.ui.VideoFrames
                    val look = if (st.islandStyle == "glass") "water_still" else st.dockStyle
                    iv.wantSharp = look.startsWith("water"); iv.wantSoft = !look.startsWith("water")
                    iv.softRadius = (st.blurRadius / 13).coerceIn(1, 6)
                    snapshotFlow { rendered[0] }.collect { }
                }
                LaunchedEffect(Unit) {
                    while (view[0] == null) delay(50)
                    while (!rendered[0]) delay(100)
                    com.glasslauncher.tv.ui.VideoFrames.showing.value = true
                    com.glasslauncher.tv.ui.copyVideoFrames(view[0]!!, { rendered[0] })
                }
                DisposableEffect(Unit) { onDispose { com.glasslauncher.tv.ui.VideoFrames.clearForHome() } }
            }
            if (glassClock) LaunchedEffect(Unit) {
                while (view[0] == null) delay(50)
                val v = view[0]!!
                val bufs = Array(3) { android.graphics.Bitmap.createBitmap(720, 405, android.graphics.Bitmap.Config.ARGB_8888) }
                val busy = java.util.concurrent.atomic.AtomicBoolean(false)
                val worker = android.os.HandlerThread("saver-clock-copy").apply { start() }
                val h = android.os.Handler(worker.looper)
                var k = 0
                try {
                    while (true) {
                        delay(66)
                        if (!SaverControl.clockShown.value || !rendered[0] || !v.holder.surface.isValid || com.glasslauncher.tv.MultiCamOverlay.open.value) continue
                        if (!busy.compareAndSet(false, true)) continue
                        val dst = bufs[k++ % 3]
                        runCatching {
                            android.view.PixelCopy.request(v, dst, { r ->
                                try { if (r == android.view.PixelCopy.SUCCESS) SaverFrames.sharp.value = dst.asImageBitmap() } finally { busy.set(false) }
                            }, h)
                        }.onFailure { busy.set(false) }
                    }
                } finally { worker.quitSafely() }
            }
            LaunchedEffect(list) {
                while (view[0] == null) delay(16)
                // One player: this TV's decoder can't run a second video while one plays (getting the next one
                // ready broke the one on screen), so each video starts when the last has faded out.
                val p = players[0]
                p.setVideoSurfaceView(view[0])
                SaverControl.positionNow = { runCatching { p.currentPosition }.getOrDefault(0L) }
                val fade = st.saverFade
                var idx = 0; var errors = 0
                val single = list.size == 1
                p.repeatMode = if (single) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                fun start(i: Int) {
                    rendered[0] = false
                    SaverControl.currentUrl = list[i.mod(list.size)].second
                    val from = if (i == 0 && sameFromBox[0] > 0) sameFromBox[0].also { sameFromBox[0] = 0L } else 0L
                    com.glasslauncher.tv.ui.DecodeGate.starting(); p.setMediaItem(MediaItem.fromUri(list[i.mod(list.size)].second), from); p.prepare(); p.play()
                    SaverControl.playingTitle = list[i.mod(list.size)].first; SaverControl.playingSince = android.os.SystemClock.uptimeMillis(); SaverControl.playingFrom = from
                    com.glasslauncher.tv.data.Glog.i("Saver: #${i.mod(list.size)}/${list.size} ${list[i.mod(list.size)].first}")
                }
                suspend fun waitFrame() {
                    var w = 0; var tries = 0
                    while (!rendered[0] && w < 12_000) {
                        if (p.playerError != null) { if (tries++ >= 2) break; p.prepare(); p.play() }
                        delay(50); w += 50
                    }
                }
                var lastBump = android.os.SystemClock.uptimeMillis()
                start(0); title = list[0].first; change++
                waitFrame()
                if (fade) black.animateTo(0f, tween(900)) else black.snapTo(0f)
                if (single) return@LaunchedEffect
                while (true) {
                    var manual = 0
                    val outMs = if (fade) 700 else 0
                    while (true) {
                        delay(100)
                        if (SaverControl.step != 0) { manual = SaverControl.step; SaverControl.step = 0; break }
                        val d = p.duration
                        if (p.playerError != null) { errors++; com.glasslauncher.tv.data.Glog.w("Saver: error ${p.playerError?.errorCodeName}"); break }
                        if (p.playbackState == Player.STATE_ENDED) break
                        if (d > 0 && p.currentPosition >= d - outMs - 200) break
                    }
                    if (errors > list.size.coerceAtMost(8)) { failed = true; return@LaunchedEffect }
                    // ◀ goes straight to the one before, ▶ (or the end) to the next; a video hidden meanwhile
                    // (*Don't show it again*) is passed over: the list is the one the screensaver started with, and ◀
                    // brought the hidden one back.
                    val dir = if (manual < 0) -1 else 1
                    idx += dir
                    var passed = 0
                    while (passed++ < list.size && AerialSource.idOfUrl[list[idx.mod(list.size)].second]?.let { id -> st.saverHidden.any { it.substringBefore('\t') == id } } == true) idx += dir
                    val quick = manual != 0
                    if (fade) black.animateTo(1f, tween(if (quick) 260 else outMs)) else black.snapTo(1f)
                    start(idx)
                    waitFrame()
                    if (p.playerError == null) errors = 0
                    title = list[idx.mod(list.size)].first
                    // Browsing with ◀ ▶ keeps the info as it is (it used to vanish and come back with every press);
                    // after a pause of a few seconds a new video brings it in again.
                    val now = android.os.SystemClock.uptimeMillis()
                    if (!quick || now - lastBump > 6_000) { change++ }
                    lastBump = now
                    if (fade) black.animateTo(0f, tween(if (quick) 380 else 1000)) else black.snapTo(0f)
                }
            }
            // The held-OK menu, on the screensaver's own glass (as the trailers' one).
            if (pop != null) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val s = maxWidth.value / 960f
                val env = remember(constraints.maxWidth, constraints.maxHeight, st.blurRadius) {
                    val blank = androidx.compose.ui.graphics.ImageBitmap(1, 1)
                    com.glasslauncher.tv.ui.GlassEnv(blank, blank, blank, constraints.maxWidth, constraints.maxHeight, st.glassTint, st.refraction, blurDp = st.blurRadius * 0.45f)
                }
                val envRef = androidx.compose.runtime.rememberUpdatedState(env)
                androidx.compose.runtime.CompositionLocalProvider(com.glasslauncher.tv.ui.LocalGlassEnv provides env, com.glasslauncher.tv.ui.LocalGlassEnvRef provides envRef) {
                    com.glasslauncher.tv.ui.PopMenuHost(pop, s, st.menuStyle) { pop = null }
                }
            }
        } else if (trailers) {
            com.glasslauncher.tv.ui.SaverTrailers(app)
        } else {
            Slideshow(app)
        }
        if (list != null) SaverInfo(app, if (slideshow || list.isEmpty() || failed) "" else title, change + TrailerSaver.rev.intValue,
            avoid = if (trailers) "bl" else if (!slideshow && com.glasslauncher.tv.ui.saverLyricsShown(app)) "br" else null)
        // The song's words, over videos only (never the posters or a trailer).
        if (!slideshow) com.glasslauncher.tv.ui.SaverLyrics(app)
        LockBadge()
        // Music playing: the island at the top.
        if (st.musicOn && st.musicIsland) androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
            // The island's glass shows the video behind it (live copies, as the home screen's glass does over a
            // video): the screensaver has no page picture of its own to give it.
            val env = remember(constraints.maxWidth, constraints.maxHeight, st.blurRadius) {
                val none = androidx.compose.ui.graphics.ImageBitmap(1, 1)
                com.glasslauncher.tv.ui.GlassEnv(none, none, none, constraints.maxWidth, constraints.maxHeight, st.glassTint, st.refraction,
                    blurDp = st.blurRadius * 0.45f)
            }
            val envRef = androidx.compose.runtime.rememberUpdatedState(env)
            androidx.compose.runtime.CompositionLocalProvider(com.glasslauncher.tv.ui.LocalGlassEnv provides env, com.glasslauncher.tv.ui.LocalGlassEnvRef provides envRef) {
                com.glasslauncher.tv.ui.MusicIsland(app, maxWidth.value / 960f)
            }
        }
        // The large clock, sliding down from the top (▼ shows / hides it).
        if (st.saverBigClock) androidx.compose.animation.AnimatedVisibility(SaverControl.clockShown.value,
            enter = androidx.compose.animation.slideInVertically(tween(520)) { -it } + androidx.compose.animation.fadeIn(tween(420)),
            exit = androidx.compose.animation.slideOutVertically(tween(420)) { -it } + androidx.compose.animation.fadeOut(tween(320))) {
            // (Moves down below the music island while a song plays: see BigClock.)
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                com.glasslauncher.tv.ui.BigClock(st, maxWidth.value / 960f, inSaver = true)
            }
        }
        // Closing: to black (ScreensaverActivity.exit).
        val outA by androidx.compose.animation.core.animateFloatAsState(if (SaverControl.out.value) 1f else 0f, tween(210), label = "out")
        if (outA > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = outA }.background(Color.Black))
    }
}

/**
 * After a hold of OK: a small glass padlock in the middle that closes (locked) or opens (released), then fades.
 * Minimal: a frosted round plate, the lock's body and its shackle, drawn as lines.
 */
@Composable
internal fun LockBadge() {
    val locked = SaverControl.lockShown.value ?: return
    val rev = SaverControl.lockRev.intValue
    // 1 = closed; 0 = open, the shackle swung out to the side on its left leg.
    val shackle = remember(rev) { Animatable(if (locked) 0f else 1f) }
    val alpha = remember(rev) { Animatable(0f) }
    LaunchedEffect(rev) {
        alpha.animateTo(1f, tween(160))
        shackle.animateTo(if (locked) 1f else 0f, androidx.compose.animation.core.spring(0.6f, 320f))
        delay(800)
        alpha.animateTo(0f, tween(400))
        // Played once: it doesn't come back with the next screensaver.
        if (SaverControl.lockRev.intValue == rev) SaverControl.lockShown.value = null
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(78.dp).graphicsLayer { this.alpha = alpha.value; scaleX = 0.9f + 0.1f * alpha.value; scaleY = scaleX }) {
            val w = size.width
            drawCircle(Color.White.copy(alpha = 0.16f))
            drawCircle(Color.White.copy(alpha = 0.55f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.014f))
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.055f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            // Body.
            drawRoundRect(Color.White, androidx.compose.ui.geometry.Offset(w * 0.30f, w * 0.47f), androidx.compose.ui.geometry.Size(w * 0.40f, w * 0.29f),
                androidx.compose.ui.geometry.CornerRadius(w * 0.06f), style = stroke)
            drawCircle(Color.White, w * 0.032f, androidx.compose.ui.geometry.Offset(w * 0.5f, w * 0.61f))
            // Shackle: lifts a little out of the body, then swings sideways on its left leg (like a real padlock
            // seen from the front, its free leg turns out to the left).
            val open = 1f - shackle.value
            val lift = (open * 2f).coerceAtMost(1f) * w * 0.05f
            val swing = ((open - 0.3f) / 0.7f).coerceIn(0f, 1f)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(w * 0.37f, w * 0.47f - lift)
                lineTo(w * 0.37f, w * 0.36f - lift)
                arcTo(androidx.compose.ui.geometry.Rect(w * 0.37f, w * 0.23f - lift, w * 0.63f, w * 0.49f - lift), 180f, 180f, false)
                lineTo(w * 0.63f, w * 0.43f - lift)
            }
            // The swing: the shackle mirrored about its left leg, squeezed as it turns (a 3-D turn seen flat).
            withTransform({ scale(1f - 2f * swing, 1f, androidx.compose.ui.geometry.Offset(w * 0.37f, w * 0.4f)) }) {
                drawPath(path, Color.White, style = stroke)
            }
        }
    }
}

/** What the info shows and how: the screensaver's settings, or the background's own (same choices). */
data class InfoLook(
    val style: String, val clock: Boolean, val date: Boolean, val weather: Boolean, val place: Boolean, val sys: Boolean,
    val pos: String, val showSec: Int, val netflixHide: Boolean, val bar: String, val size: Float,
) {
    companion object {
        fun saver(st: com.glasslauncher.tv.data.Settings) = InfoLook(st.saverStyle, st.saverClock, st.saverDate, st.saverWeather, st.saverPlace, st.saverSys,
            st.saverPos, st.saverShowSec, st.saverNetflixHide, st.saverBar, st.saverSize)
        /** Over the home screen's own background (photos, the screensaver's videos). */
        fun background(st: com.glasslauncher.tv.data.Settings) = InfoLook(st.bgInfoStyle, st.bgInfoClock, st.bgInfoDate, st.bgInfoWeather, st.bgInfo, false,
            st.bgInfoPos, st.bgInfoSec, st.saverNetflixHide, st.saverBar, st.saverSize)
    }
}

/** Clock, date, weather, place and the TV's numbers over the screensaver (and, with [look], over the home background). */
@Composable
fun SaverInfo(app: GlassApp, place: String, change: Int, look: InfoLook = InfoLook.saver(app.settings), credit: String = "",
              padTop: Float = 0f, padBottom: Float = 0f,
              /** A corner something else uses (the trailer's logo and details, bottom left): never there. */
              avoid: String? = null) {
    val st = app.settings
    val k = look.size
    val time by produceState(clockText(st.clock24h)) { while (true) { value = clockText(st.clock24h); delay(5_000) } }
    val date by produceState(dateLine()) { while (true) { value = dateLine(); delay(60_000) } }
    val wx by app.weather.now.collectAsState()
    val sys by produceState<SystemInfo.Snap?>(null, look.sys) {
        if (!look.sys) return@produceState
        while (true) { value = withContext(Dispatchers.IO) { SystemInfo.read(app) }; delay(5_000) }
    }
    // Where it shows: a fixed corner, or another one with every video (so nothing burns in).
    val corner = remember(change, look.pos, avoid) {
        if (look.pos == "move") (listOf("bl", "br", "tl", "tr") - setOfNotNull(avoid)).random()
        else if (look.pos == avoid) "tr" else look.pos
    }
    // Shown for a while after each video change, or always.
    val shown = remember { Animatable(0f) }
    val showRev = SaverControl.showRev.intValue
    LaunchedEffect(change, look.showSec, showRev) {
        shown.animateTo(1f, tween(900))
        if (look.showSec > 0) { delay(look.showSec * 1000L); shown.animateTo(0f, tween(1200)) }
    }
    val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 10f))
    val lines = buildList {
        if (look.clock) add("Time" to time)
        if (look.date) add("Date" to date)
        val w = wx
        if (look.weather && w != null) add("Weather" to (w.temp(st.weatherF) + (if (w.condition.isNotBlank()) "  " + w.condition else "")))
        if (look.place && place.isNotBlank()) add("Place" to place)
        if (look.place && credit.isNotBlank()) add("Credit" to credit)
        sys?.let { y ->
            if (st.sysRam && y.ramTotal > 0) add("RAM" to "${(100 * y.ramUsed / y.ramTotal)}%")
            if (st.sysCpu && y.cpu >= 0) add("CPU" to "${y.cpu}%")
            if (st.sysGpu && y.gpu.isNotBlank()) add("GPU" to y.gpu)
            if (st.sysStorage && y.storeTotal > 0) add("Storage" to "${(100 * y.storeUsed / y.storeTotal)}%")
            if (st.sysIp && y.ip.isNotBlank()) add("IP" to y.ip)
        }
    }
    if (lines.isEmpty()) return
    // OK on the remote shows / hides all of it.
    val hidden by SaverControl.infoHidden
    val vis by androidx.compose.animation.core.animateFloatAsState(if (hidden) 0f else 1f, tween(400), label = "info")
    if (vis < 0.01f) { SaverControl.infoOnScreen = false; return }
    if (look.style == "netflix") {
        // Like the notes before a Netflix film: top left, a bar, then each line comes in one after another.
        val netAlphas = remember(change) { HashMap<Int, Animatable<Float, androidx.compose.animation.core.AnimationVector1D>>() }
        Column(Modifier.fillMaxSize().graphicsLayer { alpha = vis; SaverControl.infoOnScreen = vis > 0.5f && netAlphas.values.any { it.value > 0.5f } }
            .padding(start = (52 * k).dp, top = (44 * k).dp + padTop.dp)) {
            val n = lines.size
            lines.forEachIndexed { i, (label, value) ->
                val a = remember(change) { Animatable(0f) }
                netAlphas[i] = a
                LaunchedEffect(change, showRev) {
                    delay(500L + i * 650L)
                    a.animateTo(1f, tween(500))
                    // "Hide when complete": all of them go 4 s after the last one came in.
                    if (look.netflixHide) { delay((n - 1 - i) * 650L + 4_000L); a.animateTo(0f, tween(700)) }
                    else if (look.showSec > 0) { delay(look.showSec * 1000L); a.animateTo(0f, tween(700)) }
                }
                Row(Modifier.padding(bottom = (6 * k).dp).graphicsLayer { alpha = a.value; translationX = (1f - a.value) * -24f * density },
                    verticalAlignment = Alignment.CenterVertically) {
                    val bar = when (look.bar) { "white" -> Color.White; "black" -> Color.Black; "off" -> null; else -> Color(0xFFE50914) }
                    if (bar != null) Box(Modifier.width((3 * k).dp).height((22 * k).dp).background(bar))
                    Text(label.uppercase(), color = Color.White, fontSize = (13 * k).sp, fontWeight = FontWeight.Bold, letterSpacing = (1.4f * k).sp,
                        style = shade, modifier = Modifier.padding(start = if (bar != null) (10 * k).dp else 0.dp))
                    Box(Modifier.padding(horizontal = (10 * k).dp).width(1.dp).height((16 * k).dp).background(Color.White.copy(alpha = 0.6f)))
                    Text(value, color = Color.White.copy(alpha = 0.92f), fontSize = (13 * k).sp, fontWeight = FontWeight.Medium, style = shade)
                }
            }
        }
        return
    }
    // Apple: the time large and light, the rest small under it.
    val align = when (corner) { "tl" -> Alignment.TopStart; "tr" -> Alignment.TopEnd; "br" -> Alignment.BottomEnd; else -> Alignment.BottomStart }
    val end = corner.endsWith("r")
    Box(Modifier.fillMaxSize().padding(start = (56 * k).dp, end = (56 * k).dp, top = (44 * k).dp + padTop.dp, bottom = (44 * k).dp + padBottom.dp)
        .graphicsLayer { alpha = shown.value * vis; SaverControl.infoOnScreen = shown.value * vis > 0.5f }) {
        Column(Modifier.align(align), horizontalAlignment = if (end) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy((2 * k).dp)) {
            lines.forEach { (label, value) ->
                if (label == "Time") Text(value, color = Color.White, fontSize = (46 * k).sp, fontWeight = FontWeight.Light, style = shade)
                else if (label == "Credit") Text(value, color = Color.White.copy(alpha = 0.6f), fontSize = (11 * k).sp, fontWeight = FontWeight.Medium, style = shade)
                else Text(if (label == "Date" || label == "Weather" || label == "Place") value else "$label  $value",
                    color = Color.White.copy(alpha = 0.88f), fontSize = (14 * k).sp, fontWeight = FontWeight.Medium, style = shade)
            }
        }
    }
}

private fun dateLine(): String = java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.ENGLISH).format(java.util.Date())

/** Live copies of the screensaver's video for the glass clock (the home screen's own copies are in VideoFrames). */
object SaverFrames {
    val sharp = androidx.compose.runtime.mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
    /** The fade through black between two videos (the digits darken with it). */
    val dim = androidx.compose.runtime.mutableFloatStateOf(0f)
}

/** A soft blur of a small picture, in place (three box passes each way): for TVs without the GPU's blur. */
private fun softBlur(b: android.graphics.Bitmap, r: Int = 4) {
    val w = b.width; val h = b.height
    val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
    val tmp = IntArray(w * h)
    fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
        val n = if (horizontal) w else h; val lines = if (horizontal) h else w
        for (l in 0 until lines) for (i in 0 until n) {
            var rs = 0; var gs = 0; var bs = 0; var c = 0
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, n - 1)
                val p = if (horizontal) src[l * w + j] else src[j * w + l]
                rs += (p shr 16) and 0xFF; gs += (p shr 8) and 0xFF; bs += p and 0xFF; c++
            }
            val o = if (horizontal) l * w + i else i * w + l
            dst[o] = (0xFF shl 24) or ((rs / c) shl 16) or ((gs / c) shl 8) or (bs / c)
        }
    }
    repeat(3) { pass(px, tmp, true); pass(tmp, px, false) }
    b.setPixels(px, 0, w, 0, 0, w, h)
}

/** The music island over the screensaver in a glass look: it needs live copies of the video. */
internal fun saverIslandGlass(st: com.glasslauncher.tv.data.Settings): Boolean = st.musicOn && st.musicIsland && st.islandStyle != "none" &&
    (if (st.islandStyle == "theme") com.glasslauncher.tv.ui.themeLook(st) !in setOf("amoled", "bow") else st.islandStyle == "glass")
