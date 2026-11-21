// The top of the home screen: the settings button and the update notice. (Split out of HomeScreen.kt.)
package com.glasslauncher.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.offset
import kotlinx.coroutines.async
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.request.allowHardware
import coil3.toBitmap
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Composable
internal fun TopBar(app: GlassApp, s: Float, gearReq: FocusRequester, menuOpen: Boolean, onSettings: () -> Unit, onMenu: (pressed: Boolean) -> Unit, modifier: Modifier) {
    val settings = app.settings
    // The settings button opens the menu (as soon as it is selected, or with OK), or Settings when the menu is off.
    // A key pressed on it (OK, or Up again when it is already selected) always opens it: that is never by accident.
    val gearClick = if (settings.menuMode == "off") onSettings else { { onMenu(true) } }
    val gearUp: () -> Boolean = { if (settings.menuMode == "auto") { onMenu(true); true } else false }
    // Only when you went up to it: focus can also land there by itself when Glassio starts or comes back.
    val since = remember { android.os.SystemClock.uptimeMillis() }
    // Still on the button a moment later (it was just closed, or the TV was busy): that's on purpose, open it.
    var gearOn by remember { mutableStateOf(false) }
    LaunchedEffect(gearOn) { if (gearOn && settings.menuMode == "auto") { delay(450); if (gearOn && android.os.SystemClock.uptimeMillis() - since > 1200) onMenu(true) } }
    val gearFocus: (Boolean) -> Unit = {
        gearOn = it
        if (it && settings.menuMode == "auto" && android.os.SystemClock.uptimeMillis() - since > 1200 &&
            System.currentTimeMillis() - app.interaction.longValue < 2500) onMenu(false)
    }
    val time by produceState(clockText(settings.clock24h), settings.clock24h) {
        while (true) { value = clockText(settings.clock24h); delay(10_000) }
    }
    var barFocused by remember { mutableStateOf(false) }
    LaunchedEffect(barFocused) { DockFocus.top = barFocused }
    // Auto-hide: the clock and the settings button stay hidden while you move around the home screen
    // and only appear when focus goes up to the settings button. (A few seconds at first, so you see where it is.)
    var intro by remember { mutableStateOf(true) }
    LaunchedEffect(settings.topAutoHide, settings.topHideSec) { intro = true; delay(settings.topHideSec * 1000L); intro = false }
    // Never under the open menu (it sits in the same corner).
    val visible = (!settings.topAutoHide || barFocused || intro) && !menuOpen
    val a by animateFloatAsState(if (visible) 1f else 0f, tween(if (visible) 140 else 350), label = "topAlpha")
    val date by produceState(dateText(), Unit) { while (true) { value = dateText(); delay(60_000) } }
    val wx by app.weather.now.collectAsState()
    val shade = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f))
    Row(
        modifier.padding(top = (18 * s).dp, start = (56 * s).dp, end = (34 * s).dp).graphicsLayer { alpha = a },
        verticalAlignment = Alignment.Top,
    ) {
        val right = settings.clockPos == "right"
        val gearPic = settingsPicture(app, settings.settingsIcon)
        // Next to a picture (larger than a glyph) the clock grows a little too.
        val ck = if (gearPic != null) 1.15f else 1f
        // Apple TV: time and date, top left.
        if (settings.showClock && !right) Column {
            Text(time, color = Color.White, fontSize = (19 * s * ck).sp, fontWeight = FontWeight.SemiBold, style = shade)
            if (settings.showDate) Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = (8.5f * s * ck).sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp, style = shade)
        }
        Spacer(Modifier.weight(1f))
        @Composable fun weatherRow() {
            val w = wx
            if (w != null && settings.weatherMode != "off") Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherIcon(w.icon, Modifier.size((15 * s).dp))
                Spacer(Modifier.width((5 * s).dp))
                val parts = buildList {
                    add(w.temp(settings.weatherF))
                    if (settings.weatherMode == "condition" || settings.weatherMode == "all") w.condition.takeIf { it.isNotBlank() }?.let { add(it) }
                    if (settings.weatherMode == "city" || settings.weatherMode == "all") add(w.city)
                }
                Text(parts.joinToString("  ·  "), color = Color.White.copy(alpha = 0.9f), fontSize = (10 * s).sp, fontWeight = FontWeight.Medium, style = shade)
            }
        }
        if (right) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            // Clock next to Settings, weather under it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                if (settings.showClock) Column(horizontalAlignment = Alignment.End) {
                    Text(time, color = Color.White, fontSize = (17 * s * ck).sp, fontWeight = FontWeight.SemiBold, style = shade)
                    if (settings.showDate) Text(date, color = Color.White.copy(alpha = 0.75f), fontSize = (8 * s * ck).sp,
                        fontWeight = FontWeight.SemiBold, letterSpacing = (0.6f * s).sp, style = shade)
                }
                UpdateDot(app, s) { barFocused = it }
                GearButton(s, settings.settingsIcon, settings.settingsFrame, gearClick, gearReq, gearUp, gearPic) { barFocused = it; gearFocus(it) }
            }
            weatherRow()
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
            weatherRow()
            UpdateDot(app, s) { barFocused = it }
            GearButton(s, settings.settingsIcon, settings.settingsFrame, gearClick, gearReq, gearUp, gearPic) { barFocused = it; gearFocus(it) }
        }
    }
}

/** True while the Update pill in the top bar is selected: the banner below it shows what's new. */
private val updateFocused = mutableStateOf(false)

/**
 * A small pill when a new version is on GitHub, next to the settings button. Select it (Up, then Left)
 * and press OK: it downloads and installs right there, showing its progress, no trip to Settings.
 */
@Composable
private fun UpdateDot(app: GlassApp, s: Float, onFocus: (Boolean) -> Unit = {}) {
    val r by app.updater.available.collectAsState()
    val status by app.updater.status.collectAsState()
    val rel = r ?: return
    var focused by remember { mutableStateOf(false) }
    val busy = status.isNotBlank() && !status.startsWith("Update failed")
    Text(
        if (status.isNotBlank()) status else "Update ${rel.version}",
        color = if (focused) Color.Black else Color.White, fontSize = (9f * s).sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (focused) Color.White.copy(alpha = 0.95f) else Color.White.copy(alpha = 0.22f))
            .onFocusChanged { focused = it.isFocused; updateFocused.value = it.isFocused; onFocus(it.isFocused) }
            .clickable { if (!busy) app.updater.install() }
            .padding(horizontal = (9 * s).dp, vertical = (3 * s).dp),
    )
}

/** The release notes' bullet points, shortened to their headline ("**Sharp glass**: …" → "Sharp glass"). */
private fun noteLines(notes: String, max: Int = 4): List<String> =
    notes.lines().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("* ") }
        .map { l ->
            val t = l.drop(2).trim()
            val bold = Regex("""^\*\*(.+?)\*\*""").find(t)?.groupValues?.get(1)
            (bold ?: t.replace("**", "")).trim().trimEnd(':', '.')
        }.filter { it.isNotBlank() }.take(max)

/** One-time card when an update is found: tvOS-style banner, top centre, for a few seconds. */
@Composable
fun UpdateBanner(app: GlassApp, s: Float, modifier: Modifier) {
    val r by app.updater.available.collectAsState()
    var shownFor by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val status by app.updater.status.collectAsState()
    val pill by updateFocused
    LaunchedEffect(r?.version) {
        val v = r?.version ?: return@LaunchedEffect
        if (v == shownFor) return@LaunchedEffect
        shownFor = v; visible = true; delay(9000); visible = false
    }
    // Shown once when the update is found, and again whenever the Update pill is selected.
    AnimatedVisibility((visible || pill || status.isNotBlank()) && r != null, modifier, enter = fadeIn(tween(250)), exit = fadeOut(tween(300))) {
        GlassPanel(Modifier.padding(top = (54 * s).dp).width((300 * s).dp), (16 * s).dp, style = app.settings.panelStyle, darken = 0.55f) {
            Column(Modifier.padding(horizontal = (16 * s).dp, vertical = (11 * s).dp)) {
                Text("Glassio ${r?.version}", color = Color.White, fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height((5 * s).dp))
                noteLines(r?.notes.orEmpty()).forEach {
                    Text("·  $it", color = AppleSecondary, fontSize = (10 * s).sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height((6 * s).dp))
                Text(
                    when { status.isNotBlank() -> status; pill -> "Press OK to update now"; else -> "Go up to Update and press OK" },
                    color = Color.White, fontSize = (10 * s).sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

private fun dateText(): String =
    java.text.SimpleDateFormat("d MMM ''yy", java.util.Locale.US).format(java.util.Date()).uppercase()

/**
 * The settings button's own picture (Settings → Menu & themes → Settings button → Your picture), or null.
 * With none chosen in Glassio it is the profile's picture in Nuvio (kept on the TV); the ready-made ones are drawn
 * on Nuvio's colour for them, as Nuvio shows them.
 */
@Composable
private fun settingsPicture(app: GlassApp, icon: String): androidx.compose.ui.graphics.ImageBitmap? {
    if (icon != "picture") return null
    val rev = app.icons.rev.intValue
    val nuvioRev = com.glasslauncher.tv.data.NuvioAvatars.rev.intValue
    return remember(rev, nuvioRev) {
        val P = com.glasslauncher.tv.data.Profiles
        val mine = app.icons.fileFor(com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON).takeIf { it.isFile && !com.glasslauncher.tv.data.NuvioAvatars.testOnly }
        val info = if (mine != null) null else P.known(app).firstOrNull { it.id == P.active(app) }
        val f = mine ?: info?.let { com.glasslauncher.tv.data.NuvioAvatars.ready(app, it) }
        val bg = info?.takeIf { !it.avatar.startsWith("http") }?.avatarBg?.let { c -> runCatching { android.graphics.Color.parseColor(c) }.getOrNull() }
        if (f == null) null else if (bg != null) runCatching {
            // A ready-made picture has no background of its own: painted once onto its colour.
            val src = android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true; android.graphics.BitmapFactory.decodeFile(f.absolutePath, this)
                var sample = 1; while (outHeight / sample > 512) sample *= 2
                inJustDecodeBounds = false; inSampleSize = sample
            }) ?: return@runCatching null
            val out = android.graphics.Bitmap.createBitmap(src.width, src.height, android.graphics.Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).apply { drawColor(bg); drawBitmap(src, 0f, 0f, null) }
            src.recycle()
            out.asImageBitmap()
        }.getOrNull() else runCatching {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            var sample = 1
            while (o.outHeight / sample > 256) sample *= 2
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun GearButton(s: Float, icon: String, frame: String, onClick: () -> Unit, req: FocusRequester, onUp: () -> Boolean = { false },
                       pic: androidx.compose.ui.graphics.ImageBitmap? = null, onFocus: (Boolean) -> Unit) {
    // tvOS 26 top-right button: a small glass button with a glyph; selected it fills white and the glyph turns dark.
    // Its glyph and frame: Settings → Menu & themes → Settings button.
    var focused by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (focused) 1.16f else 1f, spring(0.8f, 900f), label = "gear")
    val lit = animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "gearLit")
    // A word instead of a glyph: "Hello" or "Menu" in Glassio's own bold type (no frame), or Apple's "hello" in a script
    // hand in a pill.
    val word = when (icon) { "hello" -> "Hello"; "menuText" -> "Menu"; else -> null }
    val script = icon == "helloScript"
    val picture = if (icon == "picture") pic else null
    val wide = word != null || script
    Box(
        (if (picture != null) Modifier.size((26 * s).dp) else if (wide) Modifier.height((20 * s).dp) else Modifier.size((20 * s).dp))
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .focusRequester(req)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .tvPress(onKey = { ev -> ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp && onUp() }, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // A soft shadow under it (the same drawing in black, blurred, a little lower), so the white button reads over a
        // bright picture too. It fades as the button fills in white.
        val pad = 6 * s
        val shadow = Modifier.graphicsLayer {
            alpha = 0.6f * (1f - lit.value)
            translationY = 1.2f * s * density
            if (android.os.Build.VERSION.SDK_INT >= 31) renderEffect = androidx.compose.ui.graphics.BlurEffect(2.2f * s * density, 2.2f * s * density)
        }
        when {
            word != null -> {
                // On its own (no frame, whatever Frame says). Selected it grows like the other glyphs, staying white.
                @Composable fun wordText(color: Color, mod: Modifier) = Text(word, fontSize = (14 * s).sp, fontWeight = FontWeight.Bold,
                    color = color, modifier = mod.padding(horizontal = (2 * s).dp))
                Box(Modifier.wrapContentHeight(unbounded = true).requiredHeight((20 * s + 2 * pad).dp).then(shadow).padding(pad.dp), contentAlignment = Alignment.Center) {
                    wordText(Color.Black, Modifier)
                }
                wordText(Color.White, Modifier)
            }
            script -> {
                // Apple's "hello": a script hand in a pill (or on its own with Frame: None); selected the pill fills white.
                val none = frame == "none"
                Box(Modifier.matchParentSize().drawBehind { pillFrame(none, lit.value) })
                Text("hello", fontFamily = androidx.compose.ui.text.font.FontFamily.Cursive, fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold,
                    color = androidx.compose.ui.graphics.lerp(Color.White, Color(0xFF1C1C1E), lit.value),
                    modifier = Modifier.padding(horizontal = ((if (none) 3 else 8) * s).dp))
            }
            picture != null -> {
                // Your own picture: always a circle, a little larger than the glyphs; selected it gets a white ring.
                Box(Modifier.fillMaxSize()
                    .border((1.8f * s).dp, Color.White.copy(alpha = 0.35f + 0.65f * lit.value), androidx.compose.foundation.shape.CircleShape)
                    .padding((1.2f * s).dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)) {
                    androidx.compose.foundation.Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }
            else -> {
                Box(Modifier.requiredSize((20 * s + 2 * pad).dp).then(shadow).padding(pad.dp)) { SettingsGlyph(Modifier.fillMaxSize(), icon, frame, Color.Black) }
                SettingsGlyph(Modifier.fillMaxSize(), icon, frame, Color.White) { lit.value }
            }
        }
    }
}

/** The script "hello"'s pill: an outline (unless Frame is None) that fills white when selected. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.pillFrame(none: Boolean, lit: Float) {
    val sw = size.height * 0.085f
    val tl = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2)
    val sz = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
    val cr = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
    if (lit > 0.01f) drawRoundRect(Color.White.copy(alpha = lit), topLeft = tl, size = sz, cornerRadius = cr)
    if (!none) drawRoundRect(androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.9f), Color.White, lit), topLeft = tl, size = sz,
        cornerRadius = cr, style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw))
}

/** The settings button's frame: a rounded square, a circle, or none (only the white fill when selected). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.buttonFrame(frame: String, outline: Color, lit: Float) {
    val sw = size.height * 0.085f
    val tl = androidx.compose.ui.geometry.Offset(sw / 2, sw / 2)
    val sz = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
    val cr = androidx.compose.ui.geometry.CornerRadius(if (frame == "square") size.height * 0.26f else size.height / 2)
    val round = frame != "square"
    if (lit > 0.01f) {
        if (round) drawCircle(Color.White.copy(alpha = lit)) else drawRoundRect(Color.White.copy(alpha = lit), topLeft = tl, size = sz, cornerRadius = cr)
    }
    if (frame == "none") return
    val c = androidx.compose.ui.graphics.lerp(outline.copy(alpha = 0.9f), Color.White, lit)
    val st = androidx.compose.ui.graphics.drawscope.Stroke(width = sw)
    if (round) drawCircle(c, radius = size.minDimension / 2 - sw / 2, style = st) else drawRoundRect(c, topLeft = tl, size = sz, cornerRadius = cr, style = st)
}

/** The settings button's glyph in its frame: sliders, a gear, three dots, three lines or a person. */
@Composable
private fun SettingsGlyph(modifier: Modifier, icon: String, frame: String, outline: Color, litOf: () -> Float = { 0f }) {
    // The highlight is read while drawing: animating it redraws the glyph, it never recomposes.
    androidx.compose.foundation.Canvas(modifier) {
        val lit = litOf()
        val w = size.minDimension
        val sw = w * 0.085f
        buttonFrame(frame, outline, lit)
        val color = androidx.compose.ui.graphics.lerp(outline, Color(0xFF1C1C1E), lit)
        fun at(x: Float, y: Float) = androidx.compose.ui.geometry.Offset(w * x, w * y)
        when (icon) {
            "gear" -> {
                // SF Symbols "gearshape.fill": a toothed wheel with a hole.
                val c = at(0.5f, 0.5f)
                val ro = w * 0.31f; val ri = ro * 0.74f; val teeth = 8
                val p = androidx.compose.ui.graphics.Path()
                for (i in 0 until teeth * 4) {
                    val a = (i / (teeth * 4f)) * 2 * Math.PI - Math.PI / 2
                    val r = if ((i % 4) == 1 || (i % 4) == 2) ro else ri
                    val x = c.x + (kotlin.math.cos(a) * r).toFloat(); val y = c.y + (kotlin.math.sin(a) * r).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                val hole = androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, ro * 0.32f)) }
                drawPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, p, hole), color)
            }
            "dots" -> for (x in floatArrayOf(0.31f, 0.5f, 0.69f)) drawCircle(color, w * 0.065f, at(x, 0.5f))
            "menu" -> for (y in floatArrayOf(0.34f, 0.5f, 0.66f))
                drawLine(color, at(0.28f, y), at(0.72f, y), sw * 0.95f, androidx.compose.ui.graphics.StrokeCap.Round)
            "person" -> {
                val st = androidx.compose.ui.graphics.drawscope.Stroke(width = sw * 0.95f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawCircle(color, w * 0.115f, at(0.5f, 0.39f), style = st)
                drawArc(color, 180f, 180f, false, at(0.27f, 0.60f), androidx.compose.ui.geometry.Size(w * 0.46f, w * 0.36f), style = st)
            }
            else -> {
                // Sliders: three tracks with a knob each (the track stops at the knob, so it reads as a ring).
                val knobs = floatArrayOf(0.40f, 0.62f, 0.40f)
                for (i in 0..2) {
                    val y = w * (0.30f + i * 0.20f)
                    val kx = w * knobs[i]; val kr = w * 0.075f
                    drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.24f, y), androidx.compose.ui.geometry.Offset(kx - kr, y), sw * 0.9f, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(color, androidx.compose.ui.geometry.Offset(kx + kr, y), androidx.compose.ui.geometry.Offset(w * 0.76f, y), sw * 0.9f, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawCircle(color, kr, androidx.compose.ui.geometry.Offset(kx, y), style = androidx.compose.ui.graphics.drawscope.Stroke(width = sw * 0.9f))
                }
            }
        }
    }
}

// ───────────────────────────── dock ─────────────────────────────
