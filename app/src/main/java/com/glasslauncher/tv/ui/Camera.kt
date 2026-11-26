package com.glasslauncher.tv.ui

import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/** A saved camera: "Name|url" in the settings. */
data class Cam(val name: String, val url: String) {
    companion object {
        fun parse(line: String): Cam? {
            val name = line.substringBefore('|', "").trim(); val url = line.substringAfter('|').trim()
            return if (url.contains("://")) Cam(name.ifBlank { hostOf(url) }, url) else null
        }
        private fun hostOf(u: String) = runCatching { java.net.URI(u).host }.getOrNull() ?: "Camera"
    }
    fun line() = "$name|$url"
}

/**
 * A live camera (RTSP over TCP, HLS or any video link), muted, in a rounded card with a "LIVE" badge like the
 * Apple TV camera view. A TextureView, so the card's rounded corners clip it.
 */
@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun CameraView(cam: Cam, s: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var state by remember(cam.url) { mutableStateOf("Connecting…") }
    val view = remember(cam.url) { arrayOfNulls<TextureView>(1) }
    val player = remember(cam.url) {
        com.glasslauncher.tv.CamPlayer.build(ctx).apply {
            var tries = 0
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) { state = ""; tries = 0 } }
                // (Tried twice more before "Can't reach", as multi-view does; a Home Assistant camera looks after itself.)
                override fun onPlayerError(error: PlaybackException) {
                    com.glasslauncher.tv.data.MediaLog.error("Menu camera", this@apply, error)
                    if (com.glasslauncher.tv.CamPlayer.selfHealing(cam.url)) return
                    val p = this@apply
                    if (tries++ < 2) { handler.postDelayed({ view[0]?.let { tv -> runCatching { com.glasslauncher.tv.CamPlayer.play(ctx, p, cam.url, tv) } } }, 1500L * tries); return }
                    state = "Can't reach the camera"
                }
            })
        }
    }
    DisposableEffect(player) { onDispose { com.glasslauncher.tv.CamPlayer.release(player) } }
    Box(modifier.clip(RoundedCornerShape((14 * s).dp)).background(Color.Black)) {
        // (Started once the view is there: a Home Assistant camera without a stream draws its pictures on it.)
        AndroidView(factory = { c -> TextureView(c).also { tv -> view[0] = tv; player.setVideoTextureView(tv); com.glasslauncher.tv.CamPlayer.play(ctx, player, cam.url, tv, onFail = { state = "Can't reach the camera" }) { state = "" } } }, modifier = Modifier.fillMaxSize())
        if (state.isNotBlank()) Text(state, color = Color.White.copy(alpha = 0.7f), fontSize = (10 * s).sp, modifier = Modifier.align(Alignment.Center))
        Row(
            Modifier.align(Alignment.TopStart).padding((8 * s).dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = (7 * s).dp, vertical = (3 * s).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size((6 * s).dp).clip(CircleShape).background(if (state.isBlank()) Color(0xFFFF3B30) else Color.Gray))
            Spacer(Modifier.width((5 * s).dp))
            Text("LIVE", color = Color.White, fontSize = (8.5f * s).sp, fontWeight = FontWeight.Bold, letterSpacing = (0.8f * s).sp)
        }
        Plain(cam.name, color = Color.White, fontSize = (10 * s).sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.BottomStart).padding((9 * s).dp))
    }
}
