package com.glasslauncher.tv

import android.os.Bundle
import android.view.KeyEvent as AKeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.LocalShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hold Home: the apps open in the background, like the Apple TV app switcher — big cards side by side,
 * the app's icon and name above each one. OK opens it, Up throws it away (the app is closed for real,
 * force-stopped, so it isn't there next time), Back returns. Only apps with a running process are listed
 * (no PiPup, launchers or system screens). Reading and closing them uses the TV's ADB access.
 */
class RecentsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as GlassApp
        setContent { Recents(app) { finish(); overridePendingTransition(0, android.R.anim.fade_out) } }
    }

    companion object {
        private val SKIP = setOf(
            "com.glasslauncher.tv", "nl.rogro82.pipup", "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher",
            "com.android.tv.settings", "com.google.android.tv.settings", "com.android.systemui", "com.google.android.apps.tv.dreamx",
            "com.google.android.katniss", "com.google.android.tvrecommendations", "com.android.vending", "com.google.android.permissioncontroller",
        )

        /** Apps with a task and a running process, newest first. */
        fun open(app: GlassApp): List<AppEntry>? {
            val out = LocalShell.run(app, "dumpsys activity recents | grep -E 'Recent #|realActivity='; echo '=====PS'; ps -A -o NAME; true").getOrNull() ?: return null
            val recents = out.substringBefore("=====PS"); val ps = out.substringAfter("=====PS")
            val running = ps.lineSequence().map { it.trim().substringBefore(':') }.filter { it.isNotBlank() }.toHashSet()
            val known = app.apps.apps.value.associateBy { it.pkg }
            val pkgs = (Regex("type=standard A=\\d+:([A-Za-z0-9_.]+)").findAll(recents).map { it.groupValues[1] } +
                Regex("realActivity=\\{?([A-Za-z0-9_.]+)/").findAll(recents).map { it.groupValues[1] }).distinct()
            val pm = app.packageManager
            fun pkgOf(a: String): String? {
                var x = a
                while (x.contains('.')) {
                    if (known.containsKey(x) || runCatching { pm.getApplicationInfo(x, 0) }.isSuccess) return x
                    x = x.substringBeforeLast('.')
                }
                return null
            }
            return pkgs.mapNotNull { pkgOf(it) }.distinct()
                .filter { it !in SKIP && !it.startsWith("com.google.android.apps.tv.launcher") && it in running }
                .mapNotNull { p ->
                    known[p] ?: runCatching {
                        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: return@runCatching null
                        AppEntry(p, pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString(), i.component ?: return@runCatching null, pm.getLeanbackLaunchIntentForPackage(p) != null)
                    }.getOrNull()
                }.toList()
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Recents(app: GlassApp, close: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var list by remember { mutableStateOf<List<AppEntry>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val gone = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val l = withContext(Dispatchers.IO) { RecentsActivity.open(app) }
        if (l == null) failed = true
        list = l.orEmpty()
    }
    val first = remember { FocusRequester() }
    val shown = list.orEmpty().filter { it.pkg !in gone }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260)) }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.fillMaxSize().graphicsLayer { alpha = enter.value }.background(Color.Black.copy(alpha = 0.78f))
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyUp && (it.key == Key.Back || it.key == Key.Escape)) { close(); true } else false },
    ) {
        // Cards about two thirds of the screen wide, the selected one in the middle, the next ones peeking.
        val cardW = maxWidth * 0.62f; val cardH = cardW * 9f / 16f
        val side = (maxWidth - cardW) / 2
        val state = rememberLazyListState()
        Column(Modifier.align(Alignment.Center).fillMaxWidth()) {
            if (shown.isEmpty()) {
                Text(when { failed -> "Needs TV access (ADB): Glassio settings → System"; list == null -> " "; else -> "No apps are open" },
                    color = Color.White.copy(alpha = 0.65f), fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CenterSpec) {
                LazyRow(state = state, contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    itemsIndexed(shown, key = { _, e -> e.pkg }) { idx, e ->
                        RecentCard(app, e, cardW, cardH, if (idx == 0) Modifier.focusRequester(first) else Modifier,
                            onOpen = { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } }; close() },
                            onClose = {
                                gone.add(e.pkg)
                                // Closed for real (it stayed in the list and came back otherwise).
                                scope.launch(Dispatchers.IO) { LocalShell.run(ctx, "am force-stop ${e.pkg}; true") }
                                if (shown.size <= 1) scope.launch { delay(400); close() }
                            })
                    }
                }
            }
            Spacer(Modifier.height(30.dp))
            if (shown.isNotEmpty()) Text("Swipe up (▲) to close an app", color = Color.White.copy(alpha = 0.45f), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally))
        }
        LaunchedEffect(shown.size) { if (shown.isNotEmpty()) { delay(40); runCatching { first.requestFocus() } } }
        if (list != null && shown.isEmpty()) Box(Modifier.fillMaxSize().focusable().onKeyEvent { if (it.type == KeyEventType.KeyUp) { close(); true } else false })
    }
}

/** The selected card sits in the middle of the screen. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object CenterSpec : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - (containerSize - size) / 2f
}

@Composable
private fun RecentCard(app: GlassApp, e: AppEntry, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp, modifier: Modifier, onOpen: () -> Unit, onClose: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var art by remember { mutableStateOf(app.apps.cachedArtwork(e)) }
    LaunchedEffect(e.pkg) { if (art == null) art = withContext(Dispatchers.IO) { app.apps.artwork(e) } }
    val scale by animateFloatAsState(if (focused) 1f else 0.9f, tween(220), label = "s")
    val dim by animateFloatAsState(if (focused) 1f else 0.55f, tween(220), label = "d")
    val lift by animateFloatAsState(if (leaving) -1f else 0f, tween(320), label = "y")
    LaunchedEffect(leaving) { if (leaving) { delay(300); onClose() } }
    Column(
        modifier.width(w)
            .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift * size.height * 1.4f; alpha = (1f + lift) * dim }
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (it.nativeKeyEvent.keyCode) {
                    AKeyEvent.KEYCODE_DPAD_UP -> { leaving = true; true }
                    AKeyEvent.KEYCODE_DPAD_CENTER, AKeyEvent.KEYCODE_ENTER -> { onOpen(); true }
                    else -> false
                }
            }.focusable(),
    ) {
        // Above the card: the app's icon and name (Apple TV).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, bottom = 12.dp)) {
            Box(Modifier.size(46.dp, 28.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                art?.let { a -> Image(a.bmp, null, contentScale = if (a.transparent) ContentScale.Fit else ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(if (a.transparent) 4.dp else 0.dp)) }
            }
            Spacer(Modifier.width(10.dp))
            Text(e.label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
        val shape = RoundedCornerShape(26.dp)
        Box(Modifier.size(w, h).clip(shape).background(Color(0xFF1C1C21)), contentAlignment = Alignment.Center) {
            // The app's own picture, large and blurred behind (Apple shows the app's last screen; Android keeps
            // that private, so its artwork stands in), and the icon in the middle.
            art?.let { a ->
                Image(a.bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = BlurEffect(60f, 60f, TileMode.Clamp); scaleX = 1.3f; scaleY = 1.3f; alpha = 0.85f
                })
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.06f), Color.Black.copy(alpha = 0.28f)))))
                Image(a.bmp, e.label, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth(0.34f).aspectRatio(1.6f).clip(RoundedCornerShape(16.dp)))
            } ?: Text(e.label, color = Color.White, fontSize = 26.sp)
        }
    }
}
