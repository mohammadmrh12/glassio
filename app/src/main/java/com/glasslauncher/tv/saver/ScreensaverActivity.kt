package com.glasslauncher.tv.saver

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.ui.clockText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text

/** Full-screen aerial video (or backdrop slideshow). Any key press or touch dismisses it. */
class ScreensaverActivity : ComponentActivity() {
    private fun app() = application as GlassApp
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val app = application as GlassApp
        if (app.settings.saverMode == "system") {
            // Hand over to the TV's own screensaver (e.g. Aerial Views): the posters play for the few seconds
            // until the system starts it, then this screen steps aside. If the local shell is not allowed,
            // the posters simply stay.
            setContent { Box(Modifier.fillMaxSize().background(Color.Black)) }
            Thread {
                val now = SystemDream.startNow(applicationContext)
                runOnUiThread {
                    if (now.isSuccess) { finish(); overridePendingTransition(0, 0); return@runOnUiThread }
                    // Fallback: let the system start it after its shortest timeout, posters meanwhile.
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    setContent { com.glasslauncher.tv.ui.GlassType { SaverContent(app, forceSlideshow = true) } }
                    Thread {
                        if (SystemDream.arm(applicationContext).isSuccess)
                            SystemDream.awaitAndRestore(applicationContext) { runOnUiThread { if (!isFinishing) finish() } }
                        else runOnUiThread {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            android.widget.Toast.makeText(this, "Allow USB debugging for Glassio to use the TV screensaver", android.widget.Toast.LENGTH_LONG).show()
                        }
                    }.apply { isDaemon = true }.start()
                }
            }.apply { isDaemon = true }.start()
            return
        }
        setContent { com.glasslauncher.tv.ui.GlassType { SaverContent(app) } }
    }

    // The TV started its own screensaver on top (another app's): step aside, so one Back gets you home (issue #9).
    private val dreamReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) { if (!isFinishing) finish() }
    }
    override fun onStart() {
        super.onStart()
        runCatching {
            val f = android.content.IntentFilter(android.content.Intent.ACTION_DREAMING_STARTED)
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(dreamReceiver, f, android.content.Context.RECEIVER_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(dreamReceiver, f)
        }
    }
    override fun onStop() { runCatching { unregisterReceiver(dreamReceiver) }; super.onStop() }

    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ◀ ▶ change the video, OK shows / hides the info; any other key ends it.
        val k = event.keyCode
        if (app().settings.saverMode != "system" && k in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) SaverControl.key(k)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { finish(); return true }
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean { if (ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT) finish(); return true }
}

/** Slow Ken Burns over the catalog backdrops, with each title's logo. */
@Composable
internal fun Slideshow(app: GlassApp) {
    val raw by app.catalogs.items.collectAsState()
    val enriched by app.catalogs.enrichedItems.collectAsState()
    val items = remember(raw) { raw.filter { it.background.isNotBlank() } }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) { while (items.size > 1) { delay(12_000); index++ } }
    val item = items.takeIf { it.isNotEmpty() }?.let { val r = it[index.mod(it.size)]; enriched[r.key] ?: r }
    val url = item?.background
    val t = rememberInfiniteTransition(label = "kb")
    val scale by t.animateFloat(1.0f, 1.14f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    Crossfade(targetState = url, animationSpec = tween(1600), label = "show") { u ->
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (u != null) AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.alpha(0.95f),
            )
        }
    }
    // Soft shade so the logo reads on bright frames.
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
    Crossfade(targetState = item, animationSpec = tween(1600), label = "logo") { it ->
        Box(Modifier.fillMaxSize().padding(start = 64.dp, bottom = 52.dp), contentAlignment = Alignment.BottomStart) {
            if (it != null) {
                if (it.logo.isNotBlank()) AsyncImage(
                    model = it.logo, contentDescription = it.name, contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.size(380.dp, 150.dp),
                ) else Text(it.name, color = Color.White, fontSize = 40.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}
