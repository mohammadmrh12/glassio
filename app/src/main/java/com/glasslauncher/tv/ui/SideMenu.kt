package com.glasslauncher.tv.ui

import com.glasslauncher.tv.data.Bg
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val EASE = CubicBezierEasing(0.22f, 0f, 0f, 1f)

private val THEMES = listOf(
    "bow" to "Black on white", "amoled" to "Black AMOLED", "liquid" to "Frosted Glass",
    "clear" to "Clear", "water_still" to "Still Glass", "water" to "Dynamic Glass",
)

/**
 * The menu at the settings button, like the Apple TV Control Center: time, date and weather on top,
 * then Library, Glassio settings, Android settings, Wi-Fi and Themes. ◀ or Back closes it.
 */
@Composable
fun SideMenu(app: GlassApp, s0: Float, onClose: () -> Unit, onLibrary: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val st = app.settings
    val s = s0 * st.menuScale
    val ctx = LocalContext.current
    val style = st.menuStyle
    val light = style == "bow"
    val ink = if (light) Color(0xFF111114) else Color.White
    val soft = ink.copy(alpha = if (light) 0.55f else 0.62f)
    var themes by remember { mutableStateOf(false) }
    var bgPage by remember { mutableStateOf(false) }
    var camPage by remember { mutableStateOf(false) }
    var saverPage by remember { mutableStateOf(false) }
    var trailerPage by remember { mutableStateOf(false) }
    var trailerRow by remember { mutableIntStateOf(0) }
    var inputsPage by remember { mutableStateOf(false) }
    var btPage by remember { mutableStateOf(false) }
    // Manage from phone: its QR code right in the menu, with how long the page stays on.
    var phonePage by remember { mutableStateOf(false) }
    var phoneRow by remember { mutableIntStateOf(0) }
    var btRow by remember { mutableIntStateOf(0) }
    // The music player (▶ in the menu, or the dock's Music icon).
    var musicPage by remember { mutableStateOf(MusicUi.openInMenu.value.also { MusicUi.openInMenu.value = false } && st.musicOn) }
    var inputsRow by remember { mutableIntStateOf(0) }
    var saverRow by remember { mutableIntStateOf(0) }
    // Inside the screensaver page: "" its rows, "col" the collections, "yt" the YouTube links (as in Settings).
    var saverSub by remember { mutableStateOf("") }
    var saverSubRow by remember { mutableIntStateOf(0) }
    var camRow by remember { mutableIntStateOf(0) }
    val cams = remember(st.cameras) { st.cameras.mapNotNull { Cam.parse(it) } }
    var camIndex by remember { mutableIntStateOf(0) }
    // One requester per row: after a choice (the menu redraws in the new theme) focus comes back to the same row.
    val reqs = remember { List(16) { FocusRequester() } }
    var target by remember { mutableIntStateOf(0) }
    var current by remember { mutableIntStateOf(0) }
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(260, easing = EASE)) }
    var inside by remember { mutableStateOf(false) }
    // Take focus from the settings button (or wherever it is): retry until a row has it.
    LaunchedEffect(themes, bgPage, camPage, saverPage, saverSub, trailerPage, inputsPage, btPage, musicPage, phonePage, style) { inside = false; repeat(16) { delay(25); if (inside) return@LaunchedEffect; runCatching { reqs[target.coerceIn(0, reqs.lastIndex)].requestFocus() } } }
    var themesRow by remember { mutableIntStateOf(0) }
    var bgRow by remember { mutableIntStateOf(0) }
    BackHandler { if (themes) { target = themesRow; themes = false } else if (bgPage) { target = bgRow; bgPage = false } else if (camPage) { target = camRow; camPage = false } else if (saverPage && saverSub != "") { target = saverSubRow; saverSub = "" } else if (saverPage) { target = saverRow; saverPage = false } else if (trailerPage) { target = trailerRow; trailerPage = false } else if (inputsPage) { target = inputsRow; inputsPage = false } else if (btPage) { target = btRow; btPage = false } else if (phonePage) { target = phoneRow; phonePage = false } else if (musicPage) { musicPage = false } else onClose() }

    val time by produceState(clockText(st.clock24h), st.clock24h) { while (true) { value = clockText(st.clock24h); delay(5_000) } }
    val date by produceState(longDate(), Unit) { while (true) { value = longDate(); delay(60_000) } }
    val wx by app.weather.now.collectAsState()
    // The TV's numbers, every few seconds while the menu is open.
    // The last numbers show with the menu at once; the quick ones (RAM, storage, IP) are read straight away,
    // CPU and GPU (through ADB, about a second) follow.
    val sys by produceState(if (st.menuSys) com.glasslauncher.tv.data.SystemInfo.latest else null, st.menuSys) {
        if (!st.menuSys) { value = null; return@produceState }
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.SystemInfo.read(app, false) }
        while (true) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.glasslauncher.tv.data.SystemInfo.read(app, st.sysCpu || st.sysGpu)
            }
            delay(3_000)
        }
    }

    Box(
        modifier
            .onFocusChanged { inside = it.hasFocus }
            .padding(top = (14 * s).dp, end = (22 * s).dp)
            .width((272 * s).dp)
            .graphicsLayer {
                alpha = enter.value
                translationX = (1f - enter.value) * 40 * s * density
                val k = 0.96f + 0.04f * enter.value; scaleX = k; scaleY = k
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0f)
            },
    ) {
        GlassPanel(Modifier.fillMaxWidth(), radius = (24 * s).dp, style = style, darken = 0.25f) {
            Column(Modifier.fillMaxWidth().padding((14 * s).dp)) {
                // A greeting for the profile in use, with its picture (Settings → Menu & themes → Greeting). It goes with
                // the settings button just above and never says the same thing twice: a button that already reads
                // "Hello" leaves the name only, and a button that is the profile's picture leaves the words only.
                val who = remember { com.glasslauncher.tv.data.Profiles.let { P -> P.known(ctx).firstOrNull { it.id == P.active(ctx) } } }
                val greet = if (st.menuHello && who != null && who.name.isNotBlank()) menuGreeting(st.settingsIcon, com.glasslauncher.tv.data.I18n.tr("Hello"), who.name) else null
                if (greet != null && who != null) {
                    Row(Modifier.fillMaxWidth().padding(start = (6 * s).dp, end = (6 * s).dp, top = (2 * s).dp, bottom = (10 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                        if (greet.second) { Face(app, who, Modifier.size((26 * s).dp), letter = (12 * s).sp); Spacer(Modifier.width((9 * s).dp)) }
                        Text(greet.first, color = ink, fontSize = (15 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                // Header: time and date, weather on the right.
                if (st.menuClock || st.menuDate || st.menuWeather) Row(Modifier.fillMaxWidth().padding(start = (6 * s).dp, end = (6 * s).dp, top = (2 * s).dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        if (st.menuClock) Text(time, color = ink, fontSize = (30 * s).sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5f * s).sp)
                        if (st.menuDate) Text(date, color = soft, fontSize = (10 * s).sp, fontWeight = FontWeight.Medium)
                    }
                    val w = wx
                    if (st.menuWeather && w != null) Column(horizontalAlignment = Alignment.End) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            WeatherIcon(w.icon, Modifier.size((18 * s).dp), ink)
                            Spacer(Modifier.width((5 * s).dp))
                            Text(w.temp(st.weatherF), color = ink, fontSize = (22 * s).sp, fontWeight = FontWeight.Light)
                        }
                        if (w.condition.isNotBlank()) Text(w.condition, color = soft, fontSize = (9.5f * s).sp, fontWeight = FontWeight.Medium)
                    }
                }
                val w = wx
                if (st.menuWeather && w != null) {
                    fun t(c: Double) = if (c.isNaN()) "–" else ((if (st.weatherF) c * 9 / 5 + 32 else c).roundToInt().toString() + "°")
                    val bits = buildList {
                        if (st.menuCity) add(w.city)
                        if (!w.hiC.isNaN()) add("H ${t(w.hiC)}  L ${t(w.loC)}")
                        if (!w.feelsC.isNaN()) add("Feels ${t(w.feelsC)}")
                        if (w.humidity >= 0) add("${w.humidity}%")
                        if (!w.windKmh.isNaN()) add("${w.windKmh.roundToInt()} km/h")
                    }
                    if (bits.isNotEmpty()) Text(bits.joinToString("  ·  "), color = soft, fontSize = (8.5f * s).sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = (6 * s).dp, end = (6 * s).dp, top = (6 * s).dp), maxLines = 1)
                } else if (st.menuCity && st.menuWeather) Text(st.weatherPlace, color = soft, fontSize = (8.5f * s).sp, modifier = Modifier.padding(start = (6 * s).dp, top = (6 * s).dp))
                sys?.let { y ->
                    val bits = buildList {
                        if (st.sysIp && y.ip.isNotBlank()) add("IP" to y.ip)
                        if (st.sysCpu && y.cpu >= 0) add("CPU" to ("${y.cpu}%" + if (y.tempC > 0) " · ${y.tempC}°" else ""))
                        if (st.sysGpu && y.gpu.isNotBlank()) add("GPU" to y.gpu)
                        if (st.sysRam && y.ramTotal > 0) add("RAM" to "${com.glasslauncher.tv.data.SystemInfo.gb(y.ramUsed)} / ${com.glasslauncher.tv.data.SystemInfo.gb(y.ramTotal)}")
                        if (st.sysStorage && y.storeTotal > 0) add("Storage" to "${com.glasslauncher.tv.data.SystemInfo.gb(y.storeUsed)} / ${com.glasslauncher.tv.data.SystemInfo.gb(y.storeTotal)}")
                    }
                    if (bits.isNotEmpty()) Column(Modifier.padding(start = (6 * s).dp, end = (6 * s).dp, top = (8 * s).dp)) {
                        bits.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth().padding(vertical = (1.5f * s).dp)) {
                                pair.forEach { (k, v) ->
                                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                        Text(k, color = soft, fontSize = (8 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp)
                                        Box(Modifier.padding(horizontal = (5 * s).dp).width(0.8.dp).height((9 * s).dp).background(ink.copy(alpha = 0.3f)))
                                        Text(v, color = ink, fontSize = (8.5f * s).sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (st.menuClock || st.menuDate || st.menuWeather || sys != null) {
                    Spacer(Modifier.height((12 * s).dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = (6 * s).dp).height(0.6.dp).background(ink.copy(alpha = 0.14f)))
                    Spacer(Modifier.height((8 * s).dp))
                }

                if (musicPage) {
                    MusicMenuPage(app, s, light, reqs[0], onBack = { musicPage = false }, onApps = {
                        val apps = installedMusicApps(ctx)
                        if (apps.isEmpty()) toast(ctx, "No music app on this TV") else { onClose(); openMusicApp(ctx, apps.first().first) }
                    })
                } else if (btPage) {
                    // The paired devices: OK connects or disconnects one (through the TV's own shell).
                    val devs by com.glasslauncher.tv.data.Bluetooth.list.collectAsState()
                    val problem by com.glasslauncher.tv.data.Bluetooth.problem.collectAsState()
                    val busy by com.glasslauncher.tv.data.Bluetooth.busy.collectAsState()
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    // Disconnecting asks for a second OK: one press by mistake on the speakers the sound goes through
                    // left the TV without sound and stuck (Reddit).
                    var confirm by remember { mutableStateOf<String?>(null) }
                    LaunchedEffect(confirm) { if (confirm != null) { delay(4000); confirm = null } }
                    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.Bluetooth.refresh(app) }; delay(4000) } }
                    Text("Bluetooth", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (4 * s).dp))
                    val back = { target = btRow; btPage = false }
                    val opts = buildList<Triple<String, String, () -> Unit>> {
                        devs.forEach { d ->
                            val state = when {
                                busy == d.address -> if (d.connected) "Disconnecting…" else "Connecting…"
                                confirm == d.address -> "OK again to disconnect"
                                d.connected -> "Connected" + (if (d.battery in 0..100) " · ${d.battery} %" else "")
                                else -> "Off"
                            }
                            add(Triple(d.name, state) {
                                if (busy != null) Unit
                                else if (d.connected && d.remote) toast(ctx, "The remote stays connected")
                                else if (d.connected && confirm != d.address) confirm = d.address
                                else { confirm = null; scope.launch(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.Bluetooth.toggle(app, d) } }
                            })
                        }
                        add(Triple("Pair a new device", "") {
                            onClose()
                            // The TV's own pairing page (only the TV's shell may open it), else its settings.
                            Bg.io("glass-bt-pair") {
                                if (com.glasslauncher.tv.data.LocalShell.run(app, "am start -a com.google.android.intent.action.CONNECT_INPUT").isFailure)
                                    android.os.Handler(android.os.Looper.getMainLooper()).post { open(ctx, android.provider.Settings.ACTION_BLUETOOTH_SETTINGS) }
                            }
                        })
                    }
                    problem?.let { Text(it, color = soft, fontSize = (8.5f * s).sp, modifier = Modifier.padding(horizontal = (10 * s).dp, vertical = (4 * s).dp)) }
                    opts.forEachIndexed { i, (label, value, act) ->
                        MenuRow(s, light, label, value, modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = i == opts.lastIndex, onLeft = back,
                            glyph = { c -> glyph(if (i == opts.lastIndex) "bluetooth" else if (value.startsWith("Connected")) "dotOn" else "dotOff", c) },
                            onClick = { target = i; act() })
                    }
                } else if (inputsPage) {
                    // The TV's inputs as tiles like the dock's icons (their dock icon when one was chosen).
                    val inputs by com.glasslauncher.tv.data.Inputs.list.collectAsState()
                    LaunchedEffect(Unit) { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.glasslauncher.tv.data.Inputs.refresh(app) } }
                    Text("Inputs", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (6 * s).dp))
                    if (inputs.isEmpty()) {
                        Text("No inputs found. On a TV they come from its own list; on a box like the Google TV Streamer from HDMI-CEC (allow TV access: Glassio settings → System).",
                            color = soft, fontSize = (9 * s).sp, modifier = Modifier.padding(horizontal = (10 * s).dp, vertical = (6 * s).dp)
                                .focusRequester(reqs[0]).tvPress(onKey = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft) { target = inputsRow; inputsPage = false; true } else false }, onClick = {}))
                    }
                    inputs.take(16).chunked(2).forEachIndexed { r, pair ->
                        Row(Modifier.fillMaxWidth().padding(bottom = (8 * s).dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy((8 * s).dp)) {
                            pair.forEachIndexed { c, input ->
                                val idx = r * 2 + c
                                InputTile(app, input, s, light, Modifier.weight(1f).focusRequester(reqs[idx]),
                                    onLeft = if (c == 0) ({ target = inputsRow; inputsPage = false }) else null) {
                                    onClose(); com.glasslauncher.tv.data.Inputs.open(ctx, input.id)
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                } else if (phonePage) {
                    Text("Manage from phone", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (6 * s).dp))
                    val back = { target = phoneRow; phonePage = false }
                    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                    var failed by remember { mutableStateOf(false) }
                    var keep by remember { mutableIntStateOf(com.glasslauncher.tv.net.PhoneHost.keepNow(app)) }
                    fun show(u: String?) { qr = u?.let { com.glasslauncher.tv.net.Net.qr(it, 360).asImageBitmap() }; failed = u == null }
                    DisposableEffect(Unit) {
                        val job = app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { val u = com.glasslauncher.tv.net.PhoneHost.start(app); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { show(u) } }
                        onDispose { job.cancel(); com.glasslauncher.tv.net.PhoneHost.leave(app) }
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = (4 * s).dp), contentAlignment = Alignment.Center) {
                        val code = qr
                        if (code != null) Image(code, "QR code", Modifier.size((132 * s).dp).clip(RoundedCornerShape((10 * s).dp)))
                        else if (failed) Text("The TV is not on a network, so the phone page can't start.", color = soft, fontSize = (9 * s).sp)
                        else Spacer(Modifier.size((132 * s).dp))
                    }
                    Text("Scan with your phone (same Wi-Fi) and change any setting there", color = soft, fontSize = (8.5f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, end = (10 * s).dp, bottom = (6 * s).dp))
                    // How long the page stays on after the menu closes; OK goes to the next choice (Always last).
                    MenuRow(s, light, "Keep the page on", com.glasslauncher.tv.net.PhoneHost.keepLabel(keep),
                        modifier = Modifier.focusRequester(reqs[0]), onFocused = { current = 0 },
                        first = true, last = true, onLeft = back,
                        glyph = { c -> glyph(if (keep < 0) "dotOn" else "dotOff", c) },
                        onClick = {
                            target = 0
                            val K = com.glasslauncher.tv.net.PhoneHost.KEEP
                            val next = K[(K.indexOf(keep).coerceAtLeast(0) + 1).mod(K.size)]
                            keep = next
                            app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { val u = com.glasslauncher.tv.net.PhoneHost.setKeep(app, next); kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { show(u) } }
                        })
                } else if (camPage) {
                    val cam = cams.getOrNull(camIndex.mod(cams.size.coerceAtLeast(1)))
                    Text("Camera" + (if (cams.size > 1) "  ·  ◀ ▶" else "") + "  ·  OK: multi-view", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (6 * s).dp))
                    // The card itself takes focus: ◀ ▶ switch cameras, Back returns.
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRequester(reqs[0]).onFocusChanged { }
                        .tvPress(onKey = { ev ->
                            when {
                                ev.type != KeyEventType.KeyDown -> false
                                ev.key == Key.DirectionRight -> { camIndex++; true }
                                ev.key == Key.DirectionLeft -> { if (cams.size > 1) camIndex-- else { target = camRow; camPage = false }; true }
                                ev.key == Key.DirectionDown -> { runCatching { reqs[1].requestFocus() }; true }
                                ev.key == Key.DirectionUp -> true
                                else -> false
                            }
                        }, onClick = {
                            // OK again: multi-view in the middle of the screen (up to four cameras).
                            onClose(); com.glasslauncher.tv.MultiHost.toggle(bottom = false)
                        })) {
                        if (cam != null) androidx.compose.runtime.key(cam.url) { CameraView(cam, s, Modifier.fillMaxSize()) }
                    }
                    Spacer(Modifier.height((6 * s).dp))
                    // The cameras page (#30): the multi-view cameras filling the screen, until Back.
                    // (Not "first": Up goes back to the camera's card above it.)
                    MenuRow(s, light, "Whole screen", "", modifier = Modifier.focusRequester(reqs[1]),
                        first = false, last = true, onLeft = { target = camRow; camPage = false },
                        glyph = { c -> glyph("camera", c) }, onClick = {
                            onClose(); com.glasslauncher.tv.MultiHost.toggle(bottom = false, full = true)
                        })
                } else if (bgPage) {
                    Text("Background", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (4 * s).dp))
                    val back = { target = bgRow; bgPage = false }
                    BG_MODES.forEachIndexed { i, (key, label) ->
                        MenuRow(s, light, label, if (bgKey(st.bgMode) == key) "✓" else "",
                            modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = i == BG_MODES.lastIndex && st.bgMode == "carousel", onLeft = back,
                            glyph = { c -> glyph(if (bgKey(st.bgMode) == key) "dotOn" else "dotOff", c) },
                            onClick = {
                                target = i
                                when {
                                    key == "image" && st.bgImage.isBlank() -> toast(ctx, "Choose a picture first: Glassio settings → Background & screensaver → My pictures & videos")
                                    key == "aerial" && st.bgMode == "video" -> {}
                                    else -> st.bgMode = key
                                }
                            })
                    }
                    // Your background: shuffle (a new one by itself) and the next one now, for whichever kind it is.
                    val extra: Pair<Pair<String, Boolean>, Pair<String, () -> Unit>>? = when (st.bgMode) {
                        // Videos: what it plays (all, mine, one) in turn, then the next one now.
                        "aerial", "video" -> ("Plays" to true) to ("Next video" to {
                            when (if (st.bgMode == "video") "one" else st.bgVideos) {
                                "all" -> st.bgVideos = "mine"
                                "mine" -> if (st.bgVideo.isNotBlank()) { st.bgVideoShuffle = false; st.bgMode = "video" } else st.bgVideos = "all"
                                else -> { st.bgVideos = "all"; st.bgMode = "aerial" }
                            }
                        })
                        "image" -> ("Shuffle my pictures" to st.bgImageShuffle) to ("Next picture" to { st.bgImageShuffle = !st.bgImageShuffle })
                        "photos" -> ("Shuffle photos" to st.photoShuffle) to ("Next photo" to { st.photoShuffle = !st.photoShuffle })
                        else -> null
                    }
                    if (extra != null) {
                        val n = BG_MODES.size
                        val (shuffle, next) = extra
                        // (Videos: what they play, in turn: All the videos, My videos, One video.)
                        val plays = st.bgMode == "aerial" || st.bgMode == "video"
                        val playsText = if (st.bgMode == "video") "One video" else if (st.bgVideos == "mine") "My videos" else "All the videos"
                        MenuRow(s, light, shuffle.first, if (plays) playsText else if (shuffle.second) "On" else "Off",
                            modifier = Modifier.focusRequester(reqs[n]), onFocused = { current = n },
                            first = false, last = false, onLeft = back,
                            glyph = { c -> glyph(if (plays) "more" else if (shuffle.second) "dotOn" else "dotOff", c) }, onClick = { target = n; next.second() })
                        MenuRow(s, light, next.first, "",
                            modifier = Modifier.focusRequester(reqs[n + 1]), onFocused = { current = n + 1 },
                            first = false, last = false, onLeft = back,
                            glyph = { c -> glyph("dotOff", c) }, onClick = { target = n + 1; CustomBackground.nextNow(st) })
                        // The large clock over your own background (as in Settings → Background & screensaver).
                        MenuRow(s, light, "Large clock", if (st.bgClock) "On" else "Off",
                            modifier = Modifier.focusRequester(reqs[n + 2]), onFocused = { current = n + 2 },
                            first = false, last = true, onLeft = back,
                            glyph = { c -> glyph(if (st.bgClock) "dotOn" else "dotOff", c) }, onClick = { target = n + 2; st.bgClock = !st.bgClock })
                    }
                } else if (trailerPage) {
                    Text("Trailer", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (4 * s).dp))
                    val back = { target = trailerRow; trailerPage = false }
                    val on = Trailers.playing.value != null
                    val opts = buildList<Triple<String, String, () -> Unit>> {
                        // Play: the menu closes and the home screen starts the trailer of the title on screen.
                        add(Triple(if (on) "Stop trailer" else "Play trailer", "") {
                            if (on) Trailers.stop() else { onClose(); Trailers.request.intValue++ }
                        })
                        add(Triple("Starts by itself", if (st.trailerAutoSec == 0) "Off" else "On · ${st.trailerAutoSec} s") {
                            val o = listOf(0, 3, 5, 8, 10, 15, 20, 30)
                            st.trailerAutoSec = o[(o.indexOf(st.trailerAutoSec).coerceAtLeast(0) + 1).mod(o.size)]
                        })
                        add(Triple("Size", when (st.trailerSize) { "original" -> "Original"; "popup" -> "Window"; else -> "Cinematic" }) {
                            val o = listOf("cinema", "original", "popup")
                            st.trailerSize = o[(o.indexOf(st.trailerSize).coerceAtLeast(0) + 1).mod(o.size)]
                        })
                        add(Triple("Hide the dock", if (st.trailerHideDock) "On" else "Off") { st.trailerHideDock = !st.trailerHideDock })
                        add(Triple("Subtitles", if (st.trailerSubs) "On" else "Off") { st.trailerSubs = !st.trailerSubs })
                        if (st.trailerSubs) add(Triple("Language", java.util.Locale(st.trailerSubLang).getDisplayLanguage(java.util.Locale.ENGLISH)) {
                            val o = listOf("en", "ar", "fr", "de", "es", "it", "tr", "fa", "ru", "pt", "hi", "ja", "ko", "zh")
                            st.trailerSubLang = o[(o.indexOf(st.trailerSubLang).coerceAtLeast(0) + 1).mod(o.size)]
                        })
                        add(Triple("Sound", if (st.trailerMute) "Off" else "On") { st.trailerMute = !st.trailerMute })
                        if (!st.trailerMute) add(Triple("Volume", "${st.trailerVolume} %") { st.trailerVolume = if (st.trailerVolume >= 100) 10 else st.trailerVolume + 10 })
                    }
                    opts.forEachIndexed { i, (label, value, act) ->
                        MenuRow(s, light, label, value, modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = i == opts.lastIndex, onLeft = back,
                            glyph = { c -> glyph(if (i == 0) (if (on) "stop" else "play") else if (value.startsWith("On")) "dotOn" else "dotOff", c) },
                            onClick = { target = i; act() })
                    }
                } else if (saverPage) {
                    Text(when (saverSub) { "col" -> "Collections"; "yt" -> "YouTube lists"; else -> "Screensaver" }, color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (4 * s).dp))
                    val back = { if (saverSub != "") { target = saverSubRow; saverSub = "" } else { target = saverRow; saverPage = false } }
                    val AS = com.glasslauncher.tv.saver.AerialSource
                    val yts = st.aerialUrls.filter { AS.isYoutube(it) }
                    val opts = if (saverSub == "col") buildList<Triple<String, String, () -> Unit>> {
                        // The collections, as in Settings (Pexels only with its key).
                        AS.SOURCES.forEach { src ->
                            add(Triple(src.name, if (src.key in st.saverSources) "✓" else "") {
                                st.saverMode = "aerial"; st.saverPick = ""
                                st.saverSources = if (src.key in st.saverSources) st.saverSources - src.key else st.saverSources + src.key
                            })
                        }
                        if (st.pexelsKey.isNotBlank()) add(Triple("Pexels", if (AS.PEXELS in st.saverSources) "✓" else "") {
                            st.saverMode = "aerial"; st.saverPick = ""
                            st.saverSources = if (AS.PEXELS in st.saverSources) st.saverSources - AS.PEXELS else st.saverSources + AS.PEXELS
                        })
                    } else if (saverSub == "yt") buildList<Triple<String, String, () -> Unit>> {
                        yts.take(reqs.size).forEach { u ->
                            add(Triple(com.glasslauncher.tv.ui.videoName(u), if (u !in st.saverYtOff) "✓" else "") {
                                st.saverMode = "aerial"; st.saverPick = ""
                                st.saverYtOff = if (u in st.saverYtOff) st.saverYtOff - u else (st.saverYtOff + u).distinct()
                            })
                        }
                    } else buildList<Triple<String, String, () -> Unit>> {
                        add(Triple("Collections", "${AS.SOURCES.count { it.key in st.saverSources } + (if (AS.PEXELS in st.saverSources && st.pexelsKey.isNotBlank()) 1 else 0)} ›") { saverSubRow = current; target = 0; saverSub = "col" })
                        if (!com.glasslauncher.tv.data.Store.PLAY && yts.isNotEmpty())
                            add(Triple("YouTube lists", "${yts.count { it !in st.saverYtOff }} / ${yts.size} ›") { saverSubRow = current; target = 0; saverSub = "yt" })
                        add(Triple("My videos", if (st.saverMode == "aerial" && st.saverMine) "✓" else "") { st.saverMode = "aerial"; st.saverMine = !st.saverMine; st.saverPick = "" })
                        add(Triple("Posters", if (st.saverMode == "slideshow") "✓" else "") { st.saverMode = if (st.saverMode == "slideshow") "aerial" else "slideshow" })
                        if (!com.glasslauncher.tv.data.Store.PLAY) add(Triple("Trailers", if (st.saverMode == "trailer") "✓" else "") { st.saverMode = if (st.saverMode == "trailer") "aerial" else "trailer" })
                        // The screensaver on the home screen itself (one player, back at once).
                        if (st.saverMode != "system") add(Triple("One player", if (st.saverInHome) "On" else "Off") { st.saverInHome = !st.saverInHome })
                        add(Triple("Shuffle", if (st.saverShuffle) "On" else "Off") { st.saverShuffle = !st.saverShuffle })
                        if (st.saverLock) add(Triple("Locked only", if (st.saverLockedOnly) "On" else "Off · ${st.saverLocked.size}") { st.saverLockedOnly = !st.saverLockedOnly })
                        add(Triple("Large clock", if (st.saverBigClock) "On" else "Off") { st.saverBigClock = !st.saverBigClock })
                        add(Triple("Starts after", st.idleSeconds.let { v -> if (v == 0) "Off" else if (v < 60) "$v s" else "${v / 60} min" }) {
                            val o = listOf(0, 10, 30, 60, 120, 300, 600)
                            st.idleSeconds = o[(o.indexOf(st.idleSeconds).coerceAtLeast(0) + 1).mod(o.size)]
                        })
                    }
                    opts.forEachIndexed { i, (label, value, act) ->
                        MenuRow(s, light, label, value, modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = i == opts.lastIndex, onLeft = back,
                            glyph = { c -> glyph(if (value == "✓" || value.startsWith("On")) "dotOn" else if (value.endsWith("›")) "more" else "dotOff", c) },
                            onClick = { target = i; act() })
                    }
                } else if (!themes) {
                    val rows = buildList<Triple<String, String, () -> Unit>> {
                        if (st.menuProfile && com.glasslauncher.tv.data.Profiles.known(ctx).size > 1) add(Triple("profile", "Profile") { onClose(); ProfilesUi.show(app) })
                        if (st.menuLibrary) add(Triple("library", "Library") { onLibrary() })
                        if (st.searchOn && st.menuSearch) add(Triple("search", "Search") { SearchUi.open(st); onClose() })
                        add(Triple("sliders", "Glassio settings") { onSettings() })
                        if (st.menuPhone) add(Triple("phone", "Manage from phone") { target = 0; phonePage = true })
                        if (st.menuAndroid) add(Triple("gear", "Android settings") { open(ctx, android.provider.Settings.ACTION_SETTINGS) })
                        if (st.menuWifi) add(Triple("wifi", "Wi-Fi") { open(ctx, android.provider.Settings.ACTION_WIFI_SETTINGS) })
                        if (st.menuBluetooth) add(Triple("bluetooth", "Bluetooth") { target = 0; btPage = true })
                        if (st.menuInputs) add(Triple("inputs", "Inputs") { target = 0; inputsPage = true })
                        if (st.menuRecents) add(Triple("recents", "Recent apps") { onClose(); runCatching { ctx.startActivity(Intent(ctx, com.glasslauncher.tv.RecentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
                        if (st.menuThemes) add(Triple("theme", "Themes") { target = 0; themes = true })
                        if (st.menuCamera && cams.isNotEmpty()) add(Triple("camera", "Camera") {
                            // (Settings → Smart home: the menu's Camera can open the cameras page straight away.)
                            if (st.cameraMenuFull) { onClose(); com.glasslauncher.tv.MultiHost.toggle(bottom = false, full = true) } else { target = 0; camPage = true }
                        })
                        if (st.menuSaver) add(Triple("saver", "Screensaver") { target = 0; saverPage = true })
                        if (st.menuTrailer) add(Triple("trailer", "Trailer") { target = 0; trailerPage = true })
                        if (st.menuBackground) add(Triple("background", "Background") { target = BG_MODES.indexOfFirst { it.first == bgKey(st.bgMode) }.coerceAtLeast(0); bgPage = true })
                    }.sortedBy { r -> st.menuOrder.indexOf(r.first).let { if (it < 0) 99 else it } }
                    bgRow = rows.indexOfFirst { it.first == "background" }.coerceAtLeast(0)
                    phoneRow = rows.indexOfFirst { it.first == "phone" }.coerceAtLeast(0)
                    camRow = rows.indexOfFirst { it.first == "camera" }.coerceAtLeast(0)
                    saverRow = rows.indexOfFirst { it.first == "saver" }.coerceAtLeast(0)
                    btRow = rows.indexOfFirst { it.first == "bluetooth" }.coerceAtLeast(0)
                    trailerRow = rows.indexOfFirst { it.first == "trailer" }.coerceAtLeast(0)
                    themesRow = rows.indexOfFirst { it.first == "theme" }.coerceAtLeast(0)
                    rows.forEachIndexed { i, (icon, label, act) ->
                        MenuRow(s, light, label, when (icon) { "theme" -> themeLabel(st.theme); "background" -> BG_MODES.firstOrNull { it.first == bgKey(st.bgMode) }?.second.orEmpty()
                            // What the screensaver plays, like the theme and the background beside theirs.
                            "saver" -> when (st.saverMode) { "aerial" -> "Videos"; "slideshow" -> "Posters"; "trailer" -> "Trailers"; "system" -> "TV screensaver"; else -> "" }
                            else -> "" },
                            chevron = icon == "theme" || icon == "background" || icon == "saver",
                            modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = i == rows.lastIndex, onLeft = onClose, glyph = { c -> glyph(icon, c) }, onClick = act,
                            // ▶: the menu turns into the music player.
                            onRight = if (st.musicOn && st.musicMenu) ({ musicPage = true }) else null)
                    }
                } else {
                    Text("Themes", color = soft, fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp,
                        modifier = Modifier.padding(start = (10 * s).dp, bottom = (4 * s).dp))
                    val back = { target = themesRow; themes = false }
                    THEMES.forEachIndexed { i, (key, label) ->
                        MenuRow(s, light, label, if (st.theme == key) "✓" else "",
                            modifier = Modifier.focusRequester(reqs[i]), onFocused = { current = i },
                            first = i == 0, last = false, onLeft = back,
                            glyph = { _ -> swatch(key, light) }, onClick = { target = i; st.applyTheme(key) })
                    }
                    Spacer(Modifier.height((6 * s).dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = (6 * s).dp).height(0.6.dp).background(ink.copy(alpha = 0.14f)))
                    Spacer(Modifier.height((6 * s).dp))
                    val n = THEMES.size
                    // The glass's colour (none, black, petrol): OK goes to the next one.
                    MenuRow(s, light, "Glass colour", GlassTint.NAMES[st.glassColor]?.substringBefore(" (") ?: "None",
                        modifier = Modifier.focusRequester(reqs[n]), onFocused = { current = n },
                        first = false, last = false, onLeft = back,
                        glyph = { c -> drawCircle(if (st.glassColor == "petrol") Color(0xFF1E7A86) else if (st.glassColor == "black") Color.Black else c.copy(alpha = 0.25f), radius = size.minDimension * 0.32f) },
                        onClick = { target = n; val k = GlassTint.NAMES.keys.toList(); st.glassColor = k[(k.indexOf(st.glassColor) + 1).mod(k.size)]; GlassTint.apply(st) })
                    MenuRow(s, light, "Colour on focus", if (st.focusColor) "On" else "Off",
                        modifier = Modifier.focusRequester(reqs[n + 1]), onFocused = { current = n + 1 },
                        first = false, last = !st.focusColor, onLeft = back,
                        glyph = { c -> glyph(if (st.focusColor) "dotOn" else "dotOff", c) }, onClick = { target = n + 1; st.focusColor = !st.focusColor })
                    if (st.focusColor) MenuRow(s, light, "Focus plate", PLATE_NAMES[st.focusPlate] ?: "Dark",
                        modifier = Modifier.focusRequester(reqs[n + 2]), onFocused = { current = n + 2 },
                        first = false, last = true, onLeft = back,
                        glyph = { c -> plateGlyph(st.focusPlate, c) },
                        onClick = { target = n + 2; st.focusPlate = PLATES[(PLATES.indexOf(st.focusPlate) + 1).mod(PLATES.size)] })
                }
            }
        }
    }
}

/**
 * What the menu's greeting says under a settings button showing [icon], and whether the profile's picture goes with
 * it: "Hello, name" with the picture; the name alone under a button that already reads Hello; no picture under a
 * button that is the picture.
 */
internal fun menuGreeting(icon: String, hello: String, name: String): Pair<String, Boolean> =
    (if (icon == "hello" || icon == "helloScript") name.trim() else "$hello, ${name.trim()}") to (icon != "picture")

@Composable
private fun MenuRow(
    s: Float, light: Boolean, label: String, value: String, chevron: Boolean = false, modifier: Modifier = Modifier,
    first: Boolean, last: Boolean, onLeft: () -> Unit, glyph: DrawScope.(Color) -> Unit, onClick: () -> Unit,
    onFocused: () -> Unit = {}, onRight: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val lit by animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "rowLit")
    val ink = if (light) Color(0xFF111114) else Color.White
    val hi = if (light) Color(0xFF111114) else Color.White
    val fg = androidx.compose.ui.graphics.lerp(ink, if (light) Color.White else Color(0xFF111114), lit)
    Row(
        modifier
            .fillMaxWidth()
            .height((34 * s).dp)
            .clip(RoundedCornerShape((12 * s).dp))
            .background(hi.copy(alpha = 0.95f * lit))
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() }
            .tvPress(onKey = { ev ->
                when {
                    ev.type != KeyEventType.KeyDown -> false
                    ev.key == Key.DirectionLeft -> { onLeft(); true }
                    ev.key == Key.DirectionRight -> { onRight?.invoke(); true }
                    ev.key == Key.DirectionUp && first -> true
                    ev.key == Key.DirectionDown && last -> true
                    else -> false
                }
            }, onClick = onClick)
            .padding(horizontal = (10 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size((15 * s).dp)) { glyph(fg) }
        Spacer(Modifier.width((11 * s).dp))
        Text(com.glasslauncher.tv.data.I18n.tr(label), color = fg, fontSize = (12 * s).sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (value.isNotBlank()) Text(value, color = fg.copy(alpha = 0.6f), fontSize = (10 * s).sp, fontWeight = FontWeight.Medium)
        if (chevron) {
            Spacer(Modifier.width((6 * s).dp))
            Canvas(Modifier.size((8 * s).dp)) {
                val p = Path().apply { moveTo(size.width * 0.32f, size.height * 0.15f); lineTo(size.width * 0.70f, size.height * 0.5f); lineTo(size.width * 0.32f, size.height * 0.85f) }
                drawPath(p, fg.copy(alpha = 0.6f), style = Stroke(size.width * 0.16f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Plates behind the selected icon with "Colour on focus". */
val PLATES = listOf("light", "dark", "glass", "amoled")
val PLATE_NAMES = mapOf("light" to "White", "dark" to "Dark", "glass" to "Glass", "amoled" to "Black AMOLED")

private fun DrawScope.plateGlyph(p: String, c: Color) {
    val r = androidx.compose.ui.geometry.CornerRadius(size.width * 0.26f)
    val fill = when (p) { "light" -> Color(0xFFE4E4EA); "dark" -> Color(0xFF2A2A2E); "amoled" -> Color.Black; else -> Color.White.copy(alpha = 0.28f) }
    drawRoundRect(fill, cornerRadius = r)
    drawRoundRect(c, cornerRadius = r, style = Stroke(size.width * 0.1f))
}

private val BG_MODES = listOf("carousel" to "Title backdrops", "image" to "My picture", "photos" to "Photos", "aerial" to "Videos")
/** The menu's background choice for a mode: "One video" (mode "video") is one of Videos' choices. */
private fun bgKey(mode: String) = if (mode == "video") "aerial" else mode

private fun themeLabel(t: String) = THEMES.firstOrNull { it.first == t }?.second ?: "Custom"

private fun open(ctx: android.content.Context, action: String) {
    for (a in listOf(action, android.provider.Settings.ACTION_SETTINGS)) {
        if (com.glasslauncher.tv.data.SystemSetup.openTvSettings(ctx, a)) return
    }
    toast(ctx, "This screen is not available on this device")
}

private fun longDate(): String =
    java.text.SimpleDateFormat("EEEE, d MMMM", java.util.Locale.ENGLISH).format(java.util.Date())

/** Thin line glyphs, drawn in the row's colour. */
private fun DrawScope.glyph(kind: String, c: Color) {
    val w = size.width
    val st = Stroke(w * 0.09f, cap = StrokeCap.Round)
    when (kind) {
        "library" -> {
            drawRoundRect(c, Offset(w * 0.10f, w * 0.18f), Size(w * 0.16f, w * 0.68f), CornerRadius(w * 0.04f), style = st)
            drawRoundRect(c, Offset(w * 0.36f, w * 0.10f), Size(w * 0.16f, w * 0.76f), CornerRadius(w * 0.04f), style = st)
            val p = Path().apply { moveTo(w * 0.62f, w * 0.24f); lineTo(w * 0.76f, w * 0.19f); lineTo(w * 0.94f, w * 0.80f); lineTo(w * 0.80f, w * 0.85f); close() }
            drawPath(p, c, style = st)
        }
        "sliders" -> for ((i, x) in listOf(0.3f, 0.7f, 0.45f).withIndex()) {
            val y = w * (0.22f + i * 0.28f)
            drawLine(c, Offset(w * 0.08f, y), Offset(w * 0.92f, y), w * 0.08f, StrokeCap.Round)
            drawCircle(c, w * 0.10f, Offset(w * x, y))
        }
        "gear" -> {
            val o = Offset(w / 2, w / 2)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(c, Offset(o.x + (w * 0.30f * Math.cos(a)).toFloat(), o.y + (w * 0.30f * Math.sin(a)).toFloat()),
                    Offset(o.x + (w * 0.46f * Math.cos(a)).toFloat(), o.y + (w * 0.46f * Math.sin(a)).toFloat()), w * 0.13f, StrokeCap.Round)
            }
            drawCircle(c, w * 0.30f, o, style = st)
            drawCircle(c, w * 0.10f, o, style = st)
        }
        "wifi" -> {
            val o = Offset(w / 2, w * 0.84f)
            for (r in listOf(0.30f, 0.56f, 0.82f)) drawArc(c, -135f, 90f, false, Offset(o.x - w * r, o.y - w * r), Size(w * r * 2, w * r * 2), style = st)
            drawCircle(c, w * 0.07f, o)
        }
        "bluetooth" -> {
            // The Bluetooth rune.
            val p = Path().apply { moveTo(w * 0.28f, w * 0.32f); lineTo(w * 0.70f, w * 0.68f); lineTo(w * 0.50f, w * 0.88f); lineTo(w * 0.50f, w * 0.12f)
                lineTo(w * 0.70f, w * 0.32f); lineTo(w * 0.28f, w * 0.68f) }
            drawPath(p, c, style = st)
        }
        "background" -> {
            drawRoundRect(c, Offset(w * 0.06f, w * 0.16f), Size(w * 0.88f, w * 0.68f), CornerRadius(w * 0.12f), style = st)
            val p = Path().apply { moveTo(w * 0.18f, w * 0.72f); lineTo(w * 0.42f, w * 0.44f); lineTo(w * 0.58f, w * 0.60f); lineTo(w * 0.68f, w * 0.50f); lineTo(w * 0.84f, w * 0.72f) }
            drawPath(p, c, style = st)
            drawCircle(c, w * 0.07f, Offset(w * 0.70f, w * 0.32f))
        }
        "saver" -> {
            drawRoundRect(c, Offset(w * 0.06f, w * 0.18f), Size(w * 0.88f, w * 0.60f), CornerRadius(w * 0.10f), style = st)
            drawLine(c, Offset(w * 0.32f, w * 0.90f), Offset(w * 0.68f, w * 0.90f), w * 0.09f, StrokeCap.Round)
            val p = Path().apply { moveTo(w * 0.18f, w * 0.64f); quadraticTo(w * 0.40f, w * 0.36f, w * 0.56f, w * 0.52f); quadraticTo(w * 0.70f, w * 0.62f, w * 0.82f, w * 0.40f) }
            drawPath(p, c, style = st)
        }
        "trailer" -> {
            // A film strip with a play mark.
            drawRoundRect(c, Offset(w * 0.06f, w * 0.18f), Size(w * 0.88f, w * 0.64f), CornerRadius(w * 0.10f), style = st)
            for (x in listOf(0.18f, 0.82f)) for (y in listOf(0.32f, 0.50f, 0.68f)) drawCircle(c, w * 0.035f, Offset(w * x, w * y))
            val p = Path().apply { moveTo(w * 0.42f, w * 0.36f); lineTo(w * 0.64f, w * 0.50f); lineTo(w * 0.42f, w * 0.64f); close() }
            drawPath(p, c)
        }
        "stop" -> drawRoundRect(c, Offset(w * 0.22f, w * 0.22f), Size(w * 0.56f, w * 0.56f), CornerRadius(w * 0.08f), style = st)
        "play" -> {
            val p = Path().apply { moveTo(w * 0.28f, w * 0.16f); lineTo(w * 0.84f, w * 0.50f); lineTo(w * 0.28f, w * 0.84f); close() }
            drawPath(p, c, style = st)
        }
        "camera" -> {
            drawRoundRect(c, Offset(w * 0.06f, w * 0.26f), Size(w * 0.62f, w * 0.50f), CornerRadius(w * 0.12f), style = st)
            val p = Path().apply { moveTo(w * 0.70f, w * 0.44f); lineTo(w * 0.94f, w * 0.30f); lineTo(w * 0.94f, w * 0.72f); lineTo(w * 0.70f, w * 0.58f) }
            drawPath(p, c, style = st)
        }
        "recents" -> {
            // Two cards one behind the other, like an app switcher.
            drawRoundRect(c, Offset(w * 0.24f, w * 0.10f), Size(w * 0.62f, w * 0.52f), CornerRadius(w * 0.10f), style = st)
            drawRoundRect(c, Offset(w * 0.10f, w * 0.34f), Size(w * 0.62f, w * 0.52f), CornerRadius(w * 0.10f), style = st)
        }
        "inputs" -> {
            // A screen with an arrow going into it.
            drawRoundRect(c, Offset(w * 0.06f, w * 0.14f), Size(w * 0.88f, w * 0.58f), CornerRadius(w * 0.10f), style = st)
            drawLine(c, Offset(w * 0.30f, w * 0.88f), Offset(w * 0.70f, w * 0.88f), w * 0.09f, StrokeCap.Round)
            drawLine(c, Offset(w * 0.22f, w * 0.43f), Offset(w * 0.62f, w * 0.43f), w * 0.09f, StrokeCap.Round)
            val p = Path().apply { moveTo(w * 0.50f, w * 0.31f); lineTo(w * 0.64f, w * 0.43f); lineTo(w * 0.50f, w * 0.55f) }
            drawPath(p, c, style = st)
        }
        "phone" -> {
            // A phone with its home bar.
            drawRoundRect(c, Offset(w * 0.28f, w * 0.06f), Size(w * 0.44f, w * 0.88f), CornerRadius(w * 0.10f), style = st)
            drawLine(c, Offset(w * 0.42f, w * 0.80f), Offset(w * 0.58f, w * 0.80f), w * 0.08f, StrokeCap.Round)
        }
        "search" -> {
            // A magnifying glass.
            drawCircle(c, w * 0.27f, Offset(w * 0.42f, w * 0.42f), style = st)
            drawLine(c, Offset(w * 0.62f, w * 0.62f), Offset(w * 0.88f, w * 0.88f), w * 0.09f, StrokeCap.Round)
        }
        "profile" -> {
            // A head and shoulders.
            drawCircle(c, w * 0.17f, Offset(w / 2, w * 0.32f), style = st)
            drawArc(c, 180f, 180f, false, Offset(w * 0.16f, w * 0.58f), Size(w * 0.68f, w * 0.60f), style = st)
        }
        "dotOn" -> { drawCircle(c, w * 0.42f, Offset(w / 2, w / 2), style = st); drawCircle(c, w * 0.24f, Offset(w / 2, w / 2)) }
        "dotOff" -> drawCircle(c, w * 0.42f, Offset(w / 2, w / 2), style = st)
        // A page inside (›).
        "more" -> { drawLine(c, Offset(w * 0.38f, w * 0.22f), Offset(w * 0.66f, w * 0.5f), w * 0.09f, StrokeCap.Round); drawLine(c, Offset(w * 0.66f, w * 0.5f), Offset(w * 0.38f, w * 0.78f), w * 0.09f, StrokeCap.Round) }
        "theme" -> {
            drawCircle(c, w * 0.42f, Offset(w / 2, w / 2), style = st)
            drawArc(c, 90f, 180f, true, Offset(w * 0.08f, w * 0.08f), Size(w * 0.84f, w * 0.84f))
        }
    }
}

/** Small preview of a theme: the plate colour it gives the icons. */
private fun DrawScope.swatch(key: String, light: Boolean) {
    val w = size.width
    val (fill, edge) = when (key) {
        "bow" -> Color(0xFFF2EFE8) to Color(0xFF111114)
        "amoled" -> Color.Black to Color.White.copy(alpha = 0.5f)
        "clear" -> Color.White.copy(alpha = 0.14f) to Color.White.copy(alpha = 0.35f)
        "liquid" -> Color.White.copy(alpha = 0.32f) to Color.White.copy(alpha = 0.7f)
        else -> Color.White.copy(alpha = 0.06f) to Color.White.copy(alpha = 0.9f)
    }.let { (f, e) -> if (light && key != "bow" && key != "amoled") Color(0x22000000) to Color(0x66000000) else f to e }
    drawRoundRect(fill, cornerRadius = CornerRadius(w * 0.28f))
    drawRoundRect(edge, cornerRadius = CornerRadius(w * 0.28f), style = Stroke(w * 0.07f))
    if (key == "bow") drawCircle(Color(0xFF111114), w * 0.16f, Offset(w / 2, w / 2))
}


/** One input in the menu, drawn like a dock icon: its picture on a glass plate that grows when selected. */
@Composable
private fun InputTile(app: GlassApp, input: com.glasslauncher.tv.data.Inputs.Input, s: Float, light: Boolean, modifier: Modifier,
                      onLeft: (() -> Unit)?, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val k by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "inputTile")
    val entry = remember(input.id, input.label) {
        com.glasslauncher.tv.data.AppEntry(com.glasslauncher.tv.data.Inputs.PREFIX + input.id, input.label, android.content.ComponentName(app.packageName, "input"), true)
    }
    val art by produceState(app.apps.cachedArtwork(entry), entry, app.icons.rev.intValue) { value = app.apps.artwork(entry) }
    val shape = RoundedCornerShape((10 * s).dp)
    Box(
        modifier.aspectRatio(16f / 9f).graphicsLayer { scaleX = k; scaleY = k }
            .clip(shape).background(if (light) Color(0x14000000) else Color.White.copy(alpha = 0.06f))
            .then(androidx.compose.ui.Modifier.border(if (focused) (1.4f * s).dp else (0.6f * s).dp, (if (light) Color.Black else Color.White).copy(alpha = if (focused) 0.8f else 0.2f), shape))
            .onFocusChanged { focused = it.isFocused }
            .tvPress(onKey = { ev -> if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionLeft && onLeft != null) { onLeft(); true } else false }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        art?.let { androidx.compose.foundation.Image(it.bmp, input.label, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
    }
}
