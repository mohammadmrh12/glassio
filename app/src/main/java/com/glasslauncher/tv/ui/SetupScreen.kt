package com.glasslauncher.tv.ui

import android.view.KeyEvent as AKeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.R
import com.glasslauncher.tv.data.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A sign-in shown inside the setup: the QR code of the page to open on the phone, and the code to enter. */
private class SignIn(val title: String, val url: String, val code: String, val note: String)

/**
 * First run: a quiet liquid-glass welcome, in the logo's colours. Hello → look → connect → done,
 * with Restore and Later on the first page. Accounts sign in right here with a QR code (no trip to Settings),
 * and each one gets a ✓ once it's connected. Shown once (System → "Run setup again" brings it back).
 */
@Composable
fun SetupScreen(app: GlassApp, onDone: () -> Unit) {
    val st = app.settings
    var page by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf("") }
    var sign by remember { mutableStateOf<SignIn?>(null) }
    var signMsg by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var rev by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    fun stop() { job?.cancel(); job = null; sign = null; signMsg = ""; rev++ }

    /** Starts a sign-in: [begin] returns what to show, [done] is checked every second, [wait] runs the account's own wait. */
    fun start(begin: suspend () -> SignIn?, done: () -> Boolean, wait: (suspend (cancelled: () -> Boolean) -> Unit)? = null) {
        job?.cancel()
        signMsg = ""
        job = scope.launch {
            val s = runCatching { withContext(Dispatchers.IO) { begin() } }.getOrElse { signMsg = it.message ?: "Couldn't start"; null } ?: return@launch
            sign = s
            if (wait != null) launch(Dispatchers.IO) { runCatching { wait { sign !== s } } }
            var n = 0
            while (!done() && n++ < 900) delay(1000)
            if (done()) { signMsg = "✓ Connected"; rev++; delay(1300) }
            if (sign === s) { sign = null; signMsg = ""; rev++ }
        }
    }

    val nuvio = app.library.nuvio
    val trakt = app.cw.trakt
    val stremio = app.cw.stremio
    val simkl = app.cw.simkl
    fun connectNuvio() = start({
        app.library.connectNuvio { }
        var n = 0
        while (app.library.nuvioUrl.value == null && n++ < 80) delay(100)
        app.library.nuvioUrl.value?.let { SignIn("Nuvio", it, "", "Scan with your phone and approve on nuvio.tv") }
    }, { nuvio.connected })
    fun connectTrakt() = start({
        app.cw.connectTrakt { }
        var n = 0
        while (app.cw.traktCode.value == null && n++ < 80) delay(100)
        app.cw.traktCode.value?.let { SignIn("Trakt", app.cw.traktUrl, it, "Scan, then enter the code on trakt.tv") }
    }, { trakt.connected.also { if (it) st.cwTrakt = true } })
    fun connectStremio() {
        var link: com.glasslauncher.tv.data.Stremio.Link? = null
        start({ stremio.startLink().also { link = it }.let { SignIn("Stremio", it.url, it.code, "Scan and approve, signed in to Stremio on your phone") } },
            { stremio.connected.also { if (it) { st.cwStremio = true; app.cw.pollNow() } } },
            wait = { c -> link?.let { stremio.waitLink(it, c) } })
    }
    fun connectSimkl() {
        var pin: com.glasslauncher.tv.data.Simkl.Pin? = null
        if (!simkl.available) {
            // Simkl needs its app id first: pasted on the phone page (Accounts); the sign-in follows here.
            start({ com.glasslauncher.tv.net.PhoneHost.start(app)?.let { SignIn("Simkl", it, "", "Scan and paste the Client ID from simkl.com/settings/developer under Accounts. The sign-in follows here.") } },
                { simkl.available })
            scope.launch { while (!simkl.available) delay(1000); delay(1600); if (!simkl.connected) connectSimkl() }
            return
        }
        start({ simkl.startPin().also { pin = it }.let { SignIn("Simkl", it.qr, it.code, "Scan and approve on simkl.com (the code is filled in)") } },
            { simkl.connected.also { if (it) { st.cwSimkl = true; app.cw.pollNow() } } },
            wait = { c -> pin?.let { simkl.waitPin(it, c) } })
    }
    fun catalogs() = start({ com.glasslauncher.tv.net.PhoneHost.start(app)?.let { SignIn("Catalogs", it, "", "Scan with your phone (same Wi-Fi) and add your add-ons with a real keyboard") } },
        { false })

    Box(
        Modifier.fillMaxSize().background(Color(0xFF05060B))
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp && it.key == Key.Back) {
                    when { sign != null -> stop(); page > 0 -> page--; else -> onDone() }; true
                } else false
            },
    ) {
        Orbs()
        val pane = RoundedCornerShape(40.dp)
        Box(
            Modifier.align(Alignment.Center).widthIn(max = 980.dp).fillMaxWidth(0.72f).heightIn(min = 460.dp)
                .clip(pane)
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.035f))))
                .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f))), pane)
                .padding(horizontal = 64.dp, vertical = 52.dp),
            contentAlignment = Alignment.Center,
        ) {
            val key = if (sign != null) 10 + page else page
            AnimatedContent(key, transitionSpec = { fadeIn(tween(260, delayMillis = 60)) togetherWith fadeOut(tween(140)) }, label = "page") { k ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    val sg = sign
                    if (k >= 10 && sg != null) SignInPane(sg, signMsg) { stop() }
                    else when (k % 10) {
                        0 -> {
                            Image(painterResource(R.drawable.ic_launcher_glass), null, Modifier.size(92.dp).clip(RoundedCornerShape(24.dp)))
                            Spacer(Modifier.height(26.dp))
                            Text(com.glasslauncher.tv.data.I18n.tr("Hello"), color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Thin)
                            Spacer(Modifier.height(6.dp))
                            Text(com.glasslauncher.tv.data.I18n.tr("Let's connect and customise your Glassio"), color = Color.White.copy(alpha = 0.66f), fontSize = 19.sp)
                            Spacer(Modifier.height(40.dp))
                            Buttons(listOf(
                                "Get started" to { page = 1 },
                                "Restore a backup" to {
                                    note = "Looking for a backup…"
                                    scope.launch {
                                        val j = withContext(Dispatchers.IO) { Backup.latestOnTv(app) }
                                        if (j == null) note = "No backup on the TV. Restore from your phone: Settings → Manage from phone"
                                        else {
                                            val n = withContext(Dispatchers.IO) { runCatching { Backup.restore(app, j) }.getOrDefault(0) }
                                            st.setupDone = true
                                            note = "Restored ($n). Glassio restarts…"; delay(1200); Backup.restart(app)
                                        }
                                    }
                                },
                                "Later" to onDone,
                            ))
                            if (note.isNotBlank()) {
                                Spacer(Modifier.height(18.dp))
                                Text(note, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, textAlign = TextAlign.Center)
                            }
                        }
                        1 -> {
                            Title("Choose a look", "You can change it any time from the menu")
                            val themes = listOf("water_still" to "Glass still", "water" to "Glass is glass", "liquid" to "Liquid",
                                "clear" to "Clear", "amoled" to "Black AMOLED", "bow" to "Black on white")
                            var cur by remember { mutableStateOf(st.theme) }
                            Buttons(themes.map { (k2, l) -> (if (cur == k2) "✓ $l" else l) to { st.applyTheme(k2); cur = k2 } }, wrap = true)
                            Spacer(Modifier.height(22.dp))
                            Buttons(listOf("Next" to { page = 2 }), focusFirst = false)
                        }
                        2 -> {
                            Title("Connect", "Your catalogs, Continue Watching and Library. Sign in with your phone")
                            @Suppress("UNUSED_EXPRESSION") rev
                            fun tick(on: Boolean, l: String) = if (on) "✓ $l" else l
                            Buttons(listOf(
                                tick(nuvio.connected, "Nuvio") to { connectNuvio() },
                                tick(stremio.connected, "Stremio") to { connectStremio() },
                                tick(trakt.connected, "Trakt") to { connectTrakt() },
                                tick(simkl.connected, "Simkl") to { connectSimkl() },
                                tick(app.catalogs.addons.isNotEmpty(), "Catalogs") to { catalogs() },
                            ), wrap = true)
                            if (signMsg.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(signMsg, color = Color(0xFFFF8F9B), fontSize = 14.sp) }
                            Spacer(Modifier.height(22.dp))
                            Buttons(listOf("Next" to { page = 3 }), focusFirst = false)
                        }
                        else -> {
                            Title("You're all set", "▲ opens the menu  ·  ▼ from the dock opens the Library  ·  Hold OK on an app for options")
                            Buttons(listOf("Start" to onDone))
                        }
                    }
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(4) { i ->
                val w by animateDpAsState(if (i == page) 26.dp else 8.dp, tween(260), label = "dot")
                Box(Modifier.size(w, 8.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == page) 0.9f else 0.28f)))
            }
        }
    }
}

@Composable
private fun SignInPane(s: SignIn, msg: String, onBack: () -> Unit) {
    val qr = remember(s.url) { com.glasslauncher.tv.net.Net.qr(s.url, 420).asImageBitmap() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(44.dp)) {
        Image(qr, "QR code", Modifier.size(250.dp).clip(RoundedCornerShape(22.dp)))
        Column(Modifier.widthIn(max = 380.dp)) {
            Text(if (s.title == "Catalogs") "Add your catalogs" else "Connect ${s.title}", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Light)
            Spacer(Modifier.height(10.dp))
            Text(s.note, color = Color.White.copy(alpha = 0.62f), fontSize = 15.sp)
            if (s.code.isNotBlank()) {
                Spacer(Modifier.height(18.dp))
                Text(s.code, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Medium, letterSpacing = 6.sp)
            }
            Spacer(Modifier.height(18.dp))
            Text(msg.ifBlank { if (s.title == "Catalogs") "Press Done when you're finished" else "Waiting for your phone…" },
                color = if (msg.startsWith("✓")) Color(0xFF7EE2A8) else Color.White.copy(alpha = 0.5f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(22.dp))
            Buttons(listOf((if (s.title == "Catalogs") "Done" else "Back") to onBack))
        }
    }
}

@Composable
private fun Title(t: String, sub: String) {
    Text(com.glasslauncher.tv.data.I18n.tr(t), color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
    Spacer(Modifier.height(8.dp))
    Text(sub, color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, textAlign = TextAlign.Center)
    Spacer(Modifier.height(34.dp))
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Buttons(items: List<Pair<String, () -> Unit>>, wrap: Boolean = false, focusFirst: Boolean = true) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (focusFirst) for (d in listOf(80L, 300L)) { delay(d); runCatching { first.requestFocus() } } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(14.dp),
        maxItemsInEachRow = if (wrap) 3 else Int.MAX_VALUE) {
        items.forEachIndexed { i, (label, on) -> Pill(label, primary = i == 0 && !wrap, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, onClick = on) }
    }
}

/** A glass pill. Focus grows it on the GPU only (no new layout), so moving between them stays smooth. */
@Composable
private fun Pill(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val t = animateFloatAsState(if (f) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "f")
    val shape = RoundedCornerShape(50)
    Box(
        modifier.graphicsLayer { val sc = 1f + 0.07f * t.value; scaleX = sc; scaleY = sc }
            .onFocusChanged { f = it.isFocused }
            .onKeyEvent {
                if (it.type == KeyEventType.KeyUp && (it.nativeKeyEvent.keyCode == AKeyEvent.KEYCODE_DPAD_CENTER || it.nativeKeyEvent.keyCode == AKeyEvent.KEYCODE_ENTER)) { onClick(); true } else false
            }
            .focusable()
            .clip(shape)
            .background(if (f) Color(0xFFF2F2F5) else Color.White.copy(alpha = if (primary) 0.16f else 0.08f))
            .border(1.dp, Color.White.copy(alpha = if (f) 0f else 0.22f), shape)
            .padding(horizontal = 30.dp, vertical = 14.dp),
    ) {
        val shown = if (label.startsWith("✓ ")) "✓ " + com.glasslauncher.tv.data.I18n.tr(label.removePrefix("✓ ")) else com.glasslauncher.tv.data.I18n.tr(label)
        Text(shown, color = if (f) Color(0xFF0B0B10) else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Three soft coloured lights behind the glass (purple, blue, rose — the logo's colours), painted once into one
 * small picture that drifts slowly: a single layer moves, nothing is redrawn while you go between the buttons.
 */
@Composable
private fun Orbs() {
    val pic = remember {
        val w = 480; val h = 270
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        c.drawColor(0xFF05060B.toInt())
        fun orb(col: Int, x: Float, y: Float, r: Float) {
            val p = android.graphics.Paint(android.graphics.Paint.DITHER_FLAG).apply {
                shader = android.graphics.RadialGradient(x, y, r, intArrayOf(col and 0x00FFFFFF or (0x8C shl 24), col and 0x00FFFFFF), null, android.graphics.Shader.TileMode.CLAMP)
            }
            c.drawCircle(x, y, r, p)
        }
        orb(0xFF7B61FF.toInt(), w * 0.28f, h * 0.34f, w * 0.42f)
        orb(0xFF3FA9F5.toInt(), w * 0.74f, h * 0.62f, w * 0.38f)
        orb(0xFFFF6FA8.toInt(), w * 0.55f, h * 0.18f, w * 0.26f)
        b.asImageBitmap().also { it.prepareToDraw() }
    }
    val tr = rememberInfiniteTransition(label = "orbs")
    val t = tr.animateFloat(0f, 6.2832f, infiniteRepeatable(tween(30000, easing = LinearEasing)), label = "t")
    Image(pic, null, contentScale = androidx.compose.ui.layout.ContentScale.FillBounds, modifier = Modifier.fillMaxSize().graphicsLayer {
        val sc = 1.12f + 0.04f * kotlin.math.sin(t.value)
        scaleX = sc; scaleY = sc
        translationX = 0.03f * size.width * kotlin.math.cos(t.value); translationY = 0.03f * size.height * kotlin.math.sin(t.value)
    })
}
