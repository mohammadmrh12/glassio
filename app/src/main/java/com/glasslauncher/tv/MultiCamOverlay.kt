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
 * Multi-view: up to four cameras, each like the corner camera (rounded, LIVE, its name), in one row or two by two
 * (`multiLayout`; never wider than the screen: #31's four cameras ran off both sides at the size 140 %).
 * At the bottom of the screen it slides up (the screensaver's ▲); elsewhere it shows in the middle. Drawn by the
 * Home-button service over any app; it never takes the remote (Back closes it). Each camera uses its small
 * sub-stream when its link has one, decoded as [CamPlayer] says.
 *
 * [full]: the cameras page (#30): they fill the screen on black (one whole, two side by side, three or four two by
 * two) and stay until Back. It takes the arrows and OK: the arrows choose a camera (a white rim), OK turns its
 * sound on or off (one camera's at a time; all start muted).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MultiCamOverlay(private val ctx: Context, private val cams: List<Cam>, private val bottom: Boolean,
                      private val seconds: Int, private val scale: Float,
                      private val layout: String = "auto", private val full: Boolean = false,
                      /** Shown inside Glassio's own window (no Home button service: Fire TV, the Play build before consent). */
                      private val inApp: Boolean = false) {
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
        // Up to nine, on the cameras page and in multi-view alike (three by three past four, his ask after #30).
        val n = cams.size.coerceAtMost(9)
        val gap = (12 * dm.density).toInt()
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        // Each card: a share of the screen width (four fill about 88% of it at size 100%).
        val wanted = (w * 0.88f - gap * (n - 1)) / 4f * scale.coerceIn(0.6f, 1.4f)
        val cols = when {
            full -> pageCols(n)
            n < 3 || layout == "row" -> n
            layout == "grid" -> pageCols(n)
            else -> if (wanted * n + gap * (n - 1) <= w * 0.94f) n else pageCols(n)      // auto: a grid when one row won't fit
        }
        colsShown = cols
        val rowsN = (n + cols - 1) / cols
        // Never wider (or taller) than the screen.
        val fitW = (w * (if (full) 0.97f else 0.94f) - gap * (cols - 1)) / cols
        val fitH = ((h * (if (full) 0.95f else 0.9f) - gap * (rowsN - 1)) / rowsN) * 16f / 9f
        // Two by two: as big as the screen allows (his ask: four cameras bigger), smaller only with a smaller Size.
        val cardW = (when {
            full -> minOf(fitW, fitH)
            rowsN > 1 -> minOf(fitW, fitH) * (scale.coerceIn(0.6f, 1.4f) / 1.4f)
            else -> minOf(wanted, fitW, fitH)
        }).toInt()
        val cardH = cardW * 9 / 16
        val r = dm.density * 12
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val lines = List(rowsN) { LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER } }
        lines.forEachIndexed { k, l -> line.addView(l, LinearLayout.LayoutParams(-2, -2).apply { if (k > 0) topMargin = gap }) }
        cams.take(n).forEachIndexed { i, c ->
            // (Opaque: the video covers the card, nothing behind it needs blending.)
            val tex = TextureView(ctx).apply { isOpaque = true }
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
                if (full) {
                    val sound = TextView(ctx).apply {
                        setTextColor(Color.WHITE); textSize = 9f; typeface = android.graphics.Typeface.DEFAULT_BOLD
                        background = GradientDrawable().apply { cornerRadius = 100f; setColor(0x73000000) }
                        setPadding((8 * dm.density).toInt(), (3 * dm.density).toInt(), (8 * dm.density).toInt(), (3 * dm.density).toInt())
                    }
                    addView(sound, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, (8 * dm.density).toInt(), (6 * dm.density).toInt()) })
                    sounds += sound
                    // PTZ (hold OK): what the arrows and OK do now, over the bottom of the camera.
                    val ctl = TextView(ctx).apply {
                        setTextColor(Color.WHITE); textSize = 11f; typeface = android.graphics.Typeface.DEFAULT_BOLD; visibility = View.GONE
                        background = GradientDrawable().apply { cornerRadius = 100f; setColor(0xB3000000.toInt()) }
                        setPadding((12 * dm.density).toInt(), (5 * dm.density).toInt(), (12 * dm.density).toInt(), (5 * dm.density).toInt())
                    }
                    addView(ctl, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (34 * dm.density).toInt() })
                    ctls += ctl
                }
            }
            cards += card
            lines[i / cols].addView(card, LinearLayout.LayoutParams(cardW, cardH).apply { if (i % cols > 0) marginStart = gap })
            // Sharp: each camera's main stream (its full picture) where the TV decodes in hardware; the small
            // sub-stream on small / older TVs (decoded by the processor), or when the main one won't play.
            val st = (ctx.applicationContext as GlassApp).settings
            val main = when (st.multiQuality) { "high" -> true; "light" -> false; else -> !CamPlayer.software(ctx) }
            var onMain = main && CamPlayer.subStream(c.url) != c.url
            var tries = 0; var live = false
            // (Beyond the TV's hardware decoders: this one on the processor, with its small stream.)
            val beyond = i >= CamPlayer.hardwareRoom
            if (beyond) { onMain = false; com.glasslauncher.tv.data.Glog.i("Multi-view ${c.name}: beyond the TV's ${CamPlayer.hardwareRoom} hardware decoders, on the processor") }
            players += CamPlayer.build(ctx, CamPlayer.software(ctx) || beyond).apply {
                val p = this
                setVideoTextureView(tex)
                // Started again a little later (its small stream), three times at most: a camera that failed or never
                // answered while the others came up (the TV's decoders all busy for a moment, the recorder answering one
                // stream late) stayed on "Can't reach" / "Connecting…" while two of three played (his report).
                fun again(why: String) {
                    if (root == null || live) return
                    if (tries++ >= 3) { status.text = "Can't reach the camera"; status.visibility = View.VISIBLE; return }
                    com.glasslauncher.tv.data.Glog.w("Multi-view ${c.name}: $why, trying again ($tries)")
                    status.text = "Connecting…"; status.visibility = View.VISIBLE
                    handler.postDelayed({ if (root != null && !live) CamPlayer.play(ctx, p, CamPlayer.subStream(c.url), tex, onFail = { again("no picture") }) { live = true; status.visibility = View.GONE } }, 1200L * tries)
                }
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(s: Int) { if (s == Player.STATE_READY) { live = true; status.visibility = View.GONE } }
                    // Whether this camera sends sound at all (many send only the picture): the page says so.
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        if (!full || tracks.groups.isEmpty()) return
                        audio[i] = tracks.containsType(androidx.media3.common.C.TRACK_TYPE_AUDIO); if (root != null) mark()
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (onMain) { onMain = false; com.glasslauncher.tv.data.Glog.w("Multi-view ${c.name}: main stream ${error.errorCodeName}, the small one instead"); CamPlayer.play(ctx, this@apply, CamPlayer.subStream(c.url)); return }
                        again(error.errorCodeName)
                    }
                })
                // Started after the cameras have come up, one after the other: starting three or four videos at once
                // (each a decoder set up on the main thread) while the row slid up made its first frames stutter, and
                // all at once was also when one of them failed. Not live after 10 s: started again.
                handler.postDelayed({
                    if (root == null) return@postDelayed
                    CamPlayer.play(ctx, p, if (main && !beyond) c.url else CamPlayer.subStream(c.url), tex, onFail = { again("no picture") }) { live = true; status.visibility = View.GONE }
                    handler.postDelayed({ if (!live) again("no picture after 10 s") }, 10_000)
                }, 260L + 150L * i)
            }
        }
        // The whole screen: everything behind the cameras (the screensaver's video, the home screen, any app) is
        // blurred and dimmed a little, so the cameras stand out. The TV blurs it itself (Android 12+, where the TV
        // allows window blur); elsewhere it is only dimmed.
        // (Not "bottom" inside apply {}: that would be the layout's own bottom edge.)
        val atBottom = bottom
        // The dim behind is a plain view faded with its own opacity: dimming the window itself meant updating the
        // window on every frame of the fade, which made opening and closing stutter.
        // The cameras page stands on dark grey (his choice), the rest on a soft dim.
        // Over Glassio's screensaver (it draws its own veil) the window is only as big as the cameras: a window over
        // the whole screen, see-through with a dim in it, was blended over the screensaver by the TV in every frame
        // the cameras' videos changed.
        val compact = !full && ownVeil > 0
        val dim = View(ctx).apply { setBackgroundColor(if (full) 0xFF1C1C1E.toInt() else 0x73000000); alpha = 0f }
        shade = if (compact) null else dim
        val box = FrameLayout(ctx).apply {
            if (!compact) addView(dim, FrameLayout.LayoutParams(-1, -1))
            addView(line, FrameLayout.LayoutParams(-2, -2, if (atBottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER).apply {
                if (atBottom && !compact) bottomMargin = (dm.heightPixels * 0.06f).toInt()
            })
        }
        // (Not over Glassio's screensaver: it draws its own soft veil, and the TV's blur of the whole screen in
        // every frame, over a playing video, made every frame of both windows slow.)
        val blurOk = android.os.Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled && ownVeil == 0
        val lp = WindowManager.LayoutParams(if (compact) -2 else -1, if (compact) -2 else -1,
            if (inApp) WindowManager.LayoutParams.TYPE_APPLICATION_PANEL else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (blurOk) WindowManager.LayoutParams.FLAG_BLUR_BEHIND else 0),
            PixelFormat.TRANSLUCENT).apply {
            gravity = if (!compact) Gravity.FILL else if (atBottom) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
            if (compact && atBottom) y = (dm.heightPixels * 0.06f).toInt()
            // Set once (not eased frame by frame: each change re-laid out the window).
            if (blurOk) blurBehindRadius = (30 * dm.density).toInt()
        }
        runCatching { wm.addView(box, lp) }.onFailure { players.forEach { CamPlayer.release(it) }; players.clear(); return }
        if (full) mark()
        root = box; row = line; params = lp; this.blurOk = blurOk
        open.value = true
        if (!compact) dim.animate().alpha(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator(1.5f)).start()
        // Comes up from below (or fades in, in the middle), drawn as one layer while it moves.
        if (bottom) { line.translationY = cardH * 0.5f; line.alpha = 0f; line.animate().translationY(0f).alpha(1f).setDuration(240).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).withLayer().start() }
        else { line.alpha = 0f; line.scaleX = 0.94f; line.scaleY = 0.94f; line.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).setInterpolator(android.view.animation.DecelerateInterpolator(2f)).withLayer().start() }
        handler.removeCallbacks(autoClose)
        if (seconds > 0 && !full) handler.postDelayed(autoClose, seconds * 1000L)
    }

    private val cards = mutableListOf<View>()
    private val sounds = mutableListOf<TextView>()
    private val ctls = mutableListOf<TextView>()

    // PTZ on the cameras page: held OK on a camera opens its controls (see Ptz).
    private var ptz: com.glasslauncher.tv.net.Ptz? = null
    private var ptzAt = -1                 // the camera whose controls show (-1: none)
    private var ptzMsg = ""                // "checking…" or why it can't move
    private var ptzZoom = false            // OK switches the arrows between moving and zoom / presets
    private var ptzPreset = -1
    private var okHeld = false
    private val pressed = HashSet<Int>()
    private var again: Runnable = Runnable {}
    private val holdOk = Runnable { okHeld = true; openPtz(sel) }

    private fun openPtz(i: Int) {
        val cam = cams.getOrNull(i) ?: return
        closePtz(); ptzAt = i; ptzMsg = "PTZ · checking the camera…"; ptzZoom = false; ptzPreset = -1; mark()
        com.glasslauncher.tv.data.Bg.io("glass-ptz-open") {
            val r = com.glasslauncher.tv.net.Ptz.open(ctx, cam)
            handler.post {
                if (root == null || ptzAt != i) { r.getOrNull()?.close(); return@post }
                r.onSuccess { ptz = it; ptzMsg = "" }.onFailure { e ->
                    ptzMsg = if (e.message?.startsWith("No PTZ") == true) e.message!!.removePrefix("No PTZ: ").replaceFirstChar { it.uppercase() }
                        else "Can't reach this camera's controls (ONVIF)"
                    // The reason stays a moment, then the controls go.
                    handler.postDelayed({ if (ptz == null && ptzAt == i) { ptzAt = -1; ptzMsg = ""; mark() } }, 3500)
                }
                mark()
            }
        }
    }
    private fun closePtz() { handler.removeCallbacks(again); ptz?.close(); ptz = null; ptzAt = -1; ptzMsg = "" }
    private var sel = 0
    private var heard = -1
    private val audio = HashMap<Int, Boolean>()

    /** The cameras page: the chosen camera's rim, and which one is heard. */
    private fun mark() {
        val d = ctx.resources.displayMetrics.density
        cards.forEachIndexed { i, v ->
            (v.foreground as? GradientDrawable)?.setStroke(((if (i == sel && cards.size > 1) 3 else 1) * d).toInt().coerceAtLeast(1),
                if (i == sel && cards.size > 1) Color.WHITE else 0x59FFFFFF)
        }
        sounds.forEachIndexed { i, t ->
            t.text = when {
                i == ptzAt -> ""
                audio[i] == false -> if (i == sel) "No sound · hold OK: move it" else ""
                i == heard -> "🔊 Sound on · OK"; i == sel -> "🔇 OK: sound · hold OK: move it"; else -> ""
            }
            t.visibility = if (t.text.isEmpty()) View.GONE else View.VISIBLE
        }
        ctls.forEachIndexed { i, t ->
            val p = ptz
            t.text = when {
                i != ptzAt -> ""
                p == null -> ptzMsg
                !ptzZoom -> "◀ ▶ ▲ ▼  move   ·   OK: zoom   ·   Back: done"
                else -> "▲ zoom in  ▼ out" + (if (p.presetNames.isNotEmpty()) "   ·   ◀ ▶ preset" + (p.presetNames.getOrNull(ptzPreset)?.let { ": $it" } ?: "") else "") + "   ·   OK: move   ·   Back: done"
            }
            t.visibility = if (t.text.isEmpty()) View.GONE else View.VISIBLE
        }
        // Sound follows the selection (a setting): the selected camera is heard when it sends sound.
        if (full && (ctx.applicationContext as GlassApp).settings.cameraSoundFollows) heard = if (audio[sel] == false) -1 else sel
        players.forEachIndexed { i, p -> p.volume = if (i == heard) 1f else 0f }
        if (heard != heardLogged) { heardLogged = heard; com.glasslauncher.tv.data.Glog.i("Cameras page: sound on ${cams.getOrNull(heard)?.name ?: "none"} (selected ${cams.getOrNull(sel)?.name})") }
    }
    private var heardLogged = -2
    private var colsShown = 2

    /** Back on the cameras page: leaves the camera's controls first (true), else the page closes. */
    fun back(): Boolean {
        if (ptzAt < 0) return false
        closePtz(); mark(); return true
    }

    /**
     * The cameras page takes the arrows and OK (from the Home button service); false: not its key. OK: the camera's
     * sound; held 0.6 s: its controls (PTZ). With the controls up an arrow held moves the camera until it is let go.
     */
    fun key(e: android.view.KeyEvent): Boolean {
        if (root == null) return false
        val code = e.keyCode; val down = e.action == android.view.KeyEvent.ACTION_DOWN; val first = down && e.repeatCount == 0
        val ok = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER || code == android.view.KeyEvent.KEYCODE_ENTER
        // Only a key pressed while this is up counts: the release of the key that opened it (▼ on the screensaver,
        // OK in the menu) closed it again at once, or opened the cameras page. That release still goes on to where
        // its press went: kept here, the screensaver took ▼ as held for ever (Android repeated it), and the next ▼
        // was only a repeat: it took two presses, or a wait, to bring the cameras up again.
        if (ok || code in ARROWS) {
            if (down) { if (first) pressed += code }
            else if (!pressed.remove(code)) return false
        }
        // Multi-view takes the arrows and OK too (they moved what was behind it, his report): ▼ closes the one that
        // came up from the bottom (the screensaver's way), OK opens the cameras page, the others do nothing.
        if (!full) {
            if (!ok && code !in ARROWS) return false
            if (!down) {
                if (bottom && code == android.view.KeyEvent.KEYCODE_DPAD_DOWN) handler.post { MultiHost.close() }
                else if (ok) handler.post { MultiHost.close(); MultiHost.toggle(bottom = false, full = true) }
            }
            return true
        }
        val p = ptz
        if (ptzAt >= 0) {
            if (p == null) return ok || code in ARROWS        // checking, or the reason: the keys wait
            val (pan, tilt) = when (code) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0; android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
                android.view.KeyEvent.KEYCODE_DPAD_UP -> 0 to 1; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0 to -1
                else -> 0 to 0
            }
            when {
                ok -> { if (first) { ptzZoom = !ptzZoom; mark() } }
                code !in ARROWS -> return false
                ptzZoom && pan != 0 -> if (first && p.presetNames.isNotEmpty()) {
                    ptzPreset = (ptzPreset + pan).coerceIn(0, p.presetNames.lastIndex); p.preset(ptzPreset); mark()
                }
                // Held: the move again every 0.7 s (each lasts a second; the service gets no key repeats), 20 s at
                // most, so a lost release stops the camera by itself.
                first -> {
                    handler.removeCallbacks(again)
                    val z = ptzZoom; val started = android.os.SystemClock.uptimeMillis()
                    again = object : Runnable { override fun run() {
                        if (ptz !== p || android.os.SystemClock.uptimeMillis() - started > 20_000) return
                        if (z) p.move(0, 0, tilt) else p.move(pan, tilt, 0); handler.postDelayed(this, 700)
                    } }
                    again.run()
                }
                !down -> { handler.removeCallbacks(again); p.stop() }
            }
            return true
        }
        if (ok) {
            if (first) { okHeld = false; handler.postDelayed(holdOk, 600) }
            else if (!down) { handler.removeCallbacks(holdOk); if (!okHeld) { heard = if (heard == sel || audio[sel] == false) -1 else sel; mark() } }
            return true
        }
        val n = cards.size; val cols = if (full) pageCols(n) else colsShown
        val step = when (code) {
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> -1; android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            android.view.KeyEvent.KEYCODE_DPAD_UP -> -cols; android.view.KeyEvent.KEYCODE_DPAD_DOWN -> cols
            else -> return false
        }
        if (!down) return true
        (sel + step).takeIf { it in 0 until n }?.let { sel = it }
        mark(); return true
    }

    private var params: WindowManager.LayoutParams? = null
    private var blurOk = false
    private var shade: View? = null

    fun close() {
        handler.removeCallbacks(autoClose); handler.removeCallbacks(holdOk); closePtz()
        val box = root ?: return
        val line = row
        val dim = shade
        root = null; row = null; shade = null
        open.value = false
        // The videos stop first: four live videos fading out with the row was most of the work of each frame.
        players.forEach { runCatching { it.playWhenReady = false } }
        val lp = params
        if (lp != null && blurOk && android.os.Build.VERSION.SDK_INT >= 31) { lp.blurBehindRadius = 0; runCatching { wm.updateViewLayout(box, lp) } }
        val done = Runnable {
            params = null
            runCatching { wm.removeView(box) }
            // Released one at a time after the window is gone (all at once held the screen for a moment).
            val list = players.toList(); players.clear()
            list.forEachIndexed { i, p -> handler.postDelayed({ CamPlayer.release(p) }, 40L * i) }
        }
        dim?.animate()?.alpha(0f)?.setDuration(180)?.setInterpolator(android.view.animation.AccelerateInterpolator(1.2f))?.start()
        val ease = android.view.animation.PathInterpolator(0.4f, 0f, 1f, 1f)
        if (line != null && bottom) line.animate().translationY(line.height * 0.35f).alpha(0f).setDuration(200).setInterpolator(ease).withLayer().withEndAction(done).start()
        else if (line != null) line.animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(180).setInterpolator(ease).withLayer().withEndAction(done).start()
        else done.run()
    }

    companion object {
        private val ARROWS = setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN)

        /**
         * Multi-view is up: Glassio blurs what's behind it itself (the home screen's page, the screensaver's
         * video), since this TV doesn't blur behind windows (the overlay only dims other apps).
         */
        val open = androidx.compose.runtime.mutableStateOf(false)

        /** Glassio's screensavers showing (each draws its own veil behind the cameras). */
        @Volatile var ownVeil = 0

        /** The cameras page's columns: one camera whole, two to four two by two, five to nine three by three. */
        fun pageCols(n: Int) = when { n <= 1 -> 1; n <= 4 -> 2; else -> 3 }

        /** QA (dev key, `--es cams_test "Name|url,Name|url"`, `-` ends it): cameras in memory only, his list untouched. */
        @Volatile var testCams: List<Cam>? = null

        /** The cameras chosen in Settings (in order), else all of them, nine at most (multi-view and the cameras page). */
        fun cams(app: GlassApp): List<Cam> {
            testCams?.let { return it.take(9) }
            val all = app.settings.cameras.mapNotNull { Cam.parse(it) }
            val chosen = app.settings.multiCams
            return (if (chosen.isEmpty()) all else chosen.mapNotNull { u -> all.firstOrNull { it.url == u } }).take(9)
        }
    }
}

/**
 * Opens and closes multi-view and the cameras page from anywhere. With the Home button service they are its windows
 * over any app; without it (Fire TV, where it can only be turned on through ADB; the Play build before its agreement)
 * Glassio's own window shows them and hands them the remote's keys, so the cameras still work from Glassio (#30:
 * "full screen does not work on Fire TV").
 */
object MultiHost {
    private var inApp: MultiCamOverlay? = null

    /** False: nothing could show them (no service and Glassio isn't on screen). */
    fun toggle(bottom: Boolean, full: Boolean = false): Boolean {
        HomeKeyService.instance?.let { it.toggleMulti(bottom, full); return true }
        inApp?.let { if (it.showing) { it.close(); inApp = null; return true } }
        val act = MainActivity.current() ?: return false
        val app = act.application as GlassApp
        val cams = MultiCamOverlay.cams(app)
        if (cams.isEmpty()) { android.widget.Toast.makeText(act, "Add a camera first: Glassio settings → Cameras", android.widget.Toast.LENGTH_LONG).show(); return true }
        inApp = MultiCamOverlay(act, cams, bottom && !full, app.settings.multiSec, app.settings.multiScale, app.settings.multiLayout, full, inApp = true).also { it.show() }
        return true
    }

    fun close() { HomeKeyService.instance?.closeMulti(); inApp?.close(); inApp = null }

    /** Glassio's window passes its keys here first: Back closes (or leaves a camera's controls), the rest as the service does. */
    fun key(e: android.view.KeyEvent): Boolean {
        val m = inApp?.takeIf { it.showing } ?: return false
        if (e.keyCode == android.view.KeyEvent.KEYCODE_BACK) { if (e.action == android.view.KeyEvent.ACTION_UP && !m.back()) { m.close(); inApp = null }; return true }
        return m.key(e)
    }

    /** Glassio's window went away: what it showed goes with it. */
    fun windowGone() { inApp?.close(); inApp = null }
}
