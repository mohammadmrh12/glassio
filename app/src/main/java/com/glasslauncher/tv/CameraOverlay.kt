package com.glasslauncher.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.glasslauncher.tv.ui.Cam

/**
 * Hold Home → camera: a live camera in the corner over whatever is playing (picture in picture, like the
 * Apple TV camera view). Drawn by the Home-button service, so it needs no extra permission and never takes
 * the remote: ◀ ▶ change camera, Back (or holding Home again) closes it. It closes by itself after 3 minutes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CameraOverlay(private val ctx: Context, private val cams: List<Cam>) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var player: ExoPlayer? = null
    private var name: TextView? = null
    private var status: TextView? = null
    private var index = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    val showing get() = root != null

    fun show() {
        if (cams.isEmpty() || root != null) return
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * 0.34f).toInt(); val h = w * 9 / 16
        val r = dm.density * 14
        val tex = TextureView(ctx)
        val box = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
            clipToOutline = true
            addView(tex, FrameLayout.LayoutParams(-1, -1))
            // LIVE badge.
            val badge = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply { cornerRadius = 100f; setColor(0x73000000) }
                setPadding((7 * dm.density).toInt(), (3 * dm.density).toInt(), (8 * dm.density).toInt(), (3 * dm.density).toInt())
                addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) } },
                    LinearLayout.LayoutParams((6 * dm.density).toInt(), (6 * dm.density).toInt()).apply { marginEnd = (5 * dm.density).toInt() })
                addView(TextView(ctx).apply { text = "LIVE"; setTextColor(Color.WHITE); textSize = 9f; typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.08f })
            }
            addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins((9 * dm.density).toInt(), (9 * dm.density).toInt(), 0, 0) })
            name = TextView(ctx).apply { setTextColor(Color.WHITE); textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(6f, 0f, 1f, Color.BLACK) }
            addView(name, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins((10 * dm.density).toInt(), 0, 0, (8 * dm.density).toInt()) })
            status = TextView(ctx).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 11f; text = "Connecting…" }
            addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        val lp = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (dm.widthPixels * 0.03f).toInt(); y = (dm.heightPixels * 0.05f).toInt()
            windowAnimations = android.R.style.Animation_Toast
        }
        runCatching { wm.addView(box, lp) }.onFailure { return }
        root = box
        player = ExoPlayer.Builder(ctx).build().apply {
            volume = 0f; setVideoTextureView(tex)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) status?.visibility = View.GONE }
                override fun onPlayerError(error: PlaybackException) { status?.text = "Can't reach the camera"; status?.visibility = View.VISIBLE }
            })
        }
        play()
        handler.postDelayed(autoClose, 3 * 60_000L)
    }

    private fun play() {
        val p = player ?: return
        val c = cams[index.mod(cams.size)]
        name?.text = c.name
        status?.text = "Connecting…"; status?.visibility = View.VISIBLE
        p.stop(); p.clearMediaItems()
        val item = MediaItem.fromUri(c.url)
        if (c.url.startsWith("rtsp", true)) p.setMediaSource(RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000).createMediaSource(item))
        else p.setMediaItem(item)
        p.prepare(); p.playWhenReady = true
    }

    fun next(d: Int) { if (cams.size > 1) { index += d; play() } }

    fun close() {
        handler.removeCallbacks(autoClose)
        runCatching { player?.release() }; player = null
        root?.let { runCatching { wm.removeView(it) } }; root = null
    }
}
