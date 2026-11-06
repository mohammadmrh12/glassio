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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.glasslauncher.tv.ui.Cam

/**
 * Multi-view: up to four cameras side by side in one row, each like the corner camera (rounded, LIVE, its name).
 * At the bottom of the screen it slides up (the screensaver's ▲); elsewhere it shows in the middle. Drawn by the
 * Home-button service over any app; it never takes the remote (Back closes it). Each camera uses its small
 * sub-stream when its link has one, decoded as [CamPlayer] says.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultiCamOverlay(private val ctx: Context, private val cams: List<Cam>, private val bottom: Boolean,
                      private val seconds: Int, private val scale: Float) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var row: LinearLayout? = null
    private val players = mutableListOf<ExoPlayer>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    val showing get() = root != null

    fun show() {
        if (cams.isEmpty() || root != null) return
        val dm = ctx.resources.displayMetrics
        val n = cams.size.coerceAtMost(4)
        val gap = (12 * dm.density).toInt()
        // Each card: a share of the screen width (four fill about 88% of it at size 100%).
        val cardW = ((dm.widthPixels * 0.88f - gap * (n - 1)) / 4f * scale.coerceIn(0.6f, 1.4f)).toInt()
        val cardH = cardW * 9 / 16
        val r = dm.density * 12
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        cams.take(n).forEachIndexed { i, c ->
            val tex = TextureView(ctx)
            val status = TextView(ctx).apply { setTextColor(0xB3FFFFFF.toInt()); textSize = 10f; text = "Connecting…" }
            val card = FrameLayout(ctx).apply {
                setBackgroundColor(Color.BLACK)
                outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, r) } }
                clipToOutline = true
                foreground = GradientDrawable().apply { cornerRadius = r; setStroke((1 * dm.density).toInt().coerceAtLeast(1), 0x59FFFFFF) }
                addView(tex, FrameLayout.LayoutParams(-1, -1))
                val badge = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply { cornerRadius = 100f; setColor(0x73000000) }
                    setPadding((6 * dm.density).toInt(), (2 * dm.density).toInt(), (7 * dm.density).toInt(), (2 * dm.density).toInt())
                    addView(View(ctx).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFF3B30.toInt()) } },
                        LinearLayout.LayoutParams((5 * dm.density).toInt(), (5 * dm.density).toInt()).apply { marginEnd = (4 * dm.density).toInt() })
                    addView(TextView(ctx).apply { text = "LIVE"; setTextColor(Color.WHITE); textSize = 8f; typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.08f })
                }
                addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins((7 * dm.density).toInt(), (7 * dm.density).toInt(), 0, 0) })
                addView(TextView(ctx).apply { text = c.name; setTextColor(Color.WHITE); textSize = 10f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setShadowLayer(6f, 0f, 1f, Color.BLACK) },
                    FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins((8 * dm.density).toInt(), 0, 0, (6 * dm.density).toInt()) })
                addView(status, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
            }
            line.addView(card, LinearLayout.LayoutParams(cardW, cardH).apply { if (i > 0) marginStart = gap })
            // Sharp: each camera's main stream (its full picture) where the TV decodes in hardware; the small
            // sub-stream on small / older TVs (decoded by the processor), or when the main one won't play.
            val st = (ctx.applicationContext as GlassApp).settings
            val main = when (st.multiQuality) { "high" -> true; "light" -> false; else -> !CamPlayer.software(ctx) }
            var onMain = main && CamPlayer.subStream(c.url) != c.url
            players += CamPlayer.build(ctx).apply {
                setVideoTextureView(tex)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) status.visibility = View.GONE }
                    override fun onPlayerError(error: PlaybackException) {
                        if (onMain) { onMain = false; com.glasslauncher.tv.data.Glog.w("Multi-view ${c.name}: main stream ${error.errorCodeName}, the small one instead"); CamPlayer.play(ctx, this@apply, CamPlayer.subStream(c.url)); return }
                        status.text = "Can't reach the camera"; status.visibility = View.VISIBLE
                    }
                })
                CamPlayer.play(ctx, this, if (main) c.url else CamPlayer.subStream(c.url))
            }
        }
        val box = FrameLayout(ctx).apply { addView(line, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }
        val lp = WindowManager.LayoutParams(-1, cardH + (if (bottom) (dm.heightPixels * 0.06f).toInt() else 0), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = if (bottom) Gravity.BOTTOM else Gravity.CENTER
        }
        runCatching { wm.addView(box, lp) }.onFailure { players.forEach { it.release() }; players.clear(); return }
        root = box; row = line
        // Comes up from below (or fades in, in the middle).
        if (bottom) { line.translationY = cardH * 1.4f; line.animate().translationY(0f).setDuration(420).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).start() }
        else { line.alpha = 0f; line.scaleX = 0.94f; line.scaleY = 0.94f; line.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).start() }
        handler.removeCallbacks(autoClose)
        if (seconds > 0) handler.postDelayed(autoClose, seconds * 1000L)
    }

    fun close() {
        handler.removeCallbacks(autoClose)
        val box = root ?: return
        val line = row
        root = null; row = null
        val done = Runnable {
            players.forEach { runCatching { it.release() } }; players.clear()
            runCatching { wm.removeView(box) }
        }
        if (line != null && bottom) line.animate().translationY(line.height * 1.4f).setDuration(320).withEndAction(done).start()
        else if (line != null) line.animate().alpha(0f).setDuration(220).withEndAction(done).start()
        else done.run()
    }

    companion object {
        /** The cameras for multi-view: the ones chosen in Settings (in order), else the first four. */
        fun cams(app: GlassApp): List<Cam> {
            val all = app.settings.cameras.mapNotNull { Cam.parse(it) }
            val chosen = app.settings.multiCams
            return (if (chosen.isEmpty()) all else chosen.mapNotNull { u -> all.firstOrNull { it.url == u } }).take(4)
        }
    }
}
