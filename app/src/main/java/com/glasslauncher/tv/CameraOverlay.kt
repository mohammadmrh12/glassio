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
/** [seconds]: how long it stays on screen (Settings → System → Corner camera closes after; 0 = until closed). */
/** [cycle]: seconds between automatic switches to the next camera (0 = none). */
/**
 * [scale]: size (1 = a third of the screen wide). [caption]: two lines at the bottom (the doorbell: where and what),
 * shown instead of the camera's name.
 */
class CameraOverlay(private val ctx: Context, private val cams: List<Cam>, private val seconds: Int = 20, start: Int = 0, private val cycle: Int = 0,
                    private val scale: Float = 1f, private val caption: Pair<String, String>? = null) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var player: ExoPlayer? = null
    private var name: TextView? = null
    private var status: TextView? = null
    private var index = start.coerceAtLeast(0)
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    // Goes round the cameras on its own; it doesn't keep the corner open longer (only a press does).
    private val rotate = object : Runnable {
        override fun run() { if (root == null) return; index++; play(); handler.postDelayed(this, cycle * 1000L) }
    }
    private fun restartRotation() {
        handler.removeCallbacks(rotate)
        if (cycle > 0 && cams.size > 1) handler.postDelayed(rotate, cycle * 1000L)
    }
    val showing get() = root != null

    fun show() {
        if (cams.isEmpty() || root != null) return
        val dm = ctx.resources.displayMetrics
        val w = (dm.widthPixels * 0.34f * scale.coerceIn(0.5f, 1.8f)).toInt(); val h = w * 9 / 16
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
            if (caption == null) addView(name, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins((10 * dm.density).toInt(), 0, 0, (8 * dm.density).toInt()) })
            else {
                // The doorbell's two lines on a soft shade at the bottom: the place in white, what happened under it.
                val k = scale.coerceIn(0.7f, 1.6f)
                val shade = View(ctx).apply {
                    background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xB3000000.toInt()))
                }
                addView(shade, FrameLayout.LayoutParams(-1, (h * 0.42f).toInt(), Gravity.BOTTOM))
                val lines = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    if (caption.first.isNotBlank()) addView(TextView(ctx).apply {
                        text = caption.first; setTextColor(Color.WHITE); textSize = 12.5f * k
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt()); maxLines = 1
                    })
                    if (caption.second.isNotBlank()) addView(TextView(ctx).apply {
                        text = caption.second; setTextColor(0xD9FFFFFF.toInt()); textSize = 10.5f * k
                        textDirection = View.TEXT_DIRECTION_FIRST_STRONG; textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                        setShadowLayer(6f, 0f, 1f, 0x99000000.toInt()); maxLines = 1
                    })
                }
                val m = (12 * dm.density * k).toInt()
                addView(lines, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(m, 0, m, (10 * dm.density * k).toInt()) })
            }
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
        restartTimer()
        restartRotation()
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

    /** The countdown starts again (also when you switch camera). */
    private fun restartTimer() {
        handler.removeCallbacks(autoClose)
        if (seconds > 0) handler.postDelayed(autoClose, seconds * 1000L)
    }

    fun next(d: Int) { if (cams.size > 1) { index += d; play(); restartTimer(); restartRotation() } }

    fun close() {
        handler.removeCallbacks(autoClose)
        handler.removeCallbacks(rotate)
        runCatching { player?.release() }; player = null
        root?.let { runCatching { wm.removeView(it) } }; root = null
    }
}
