package com.glasslauncher.tv.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.foundation.focusable
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

fun toast(ctx: Context, msg: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }
}

sealed interface DialogState
class TextEntry(val title: String, val initial: String, val hint: String, val onDone: (String) -> Unit) : DialogState
class MenuDialog(val title: String, val options: List<Pair<String, () -> Unit>>) : DialogState
/** A list with a small picture next to each choice (pictures, videos, screensaver videos). */
class ThumbDialog(val title: String, val items: List<ThumbItem>) : DialogState
/** "Press the button you want": the next key (not the arrows, OK or Back) goes to [onKey]. */
class KeyCaptureDialog(val title: String, val onKey: (Int) -> Unit) : DialogState
class ThumbItem(val label: String, val sub: String, val thumb: Any?, val checked: Boolean = false, val onClick: () -> Unit)
/** A sign-in code with a QR code of the page to enter it on; closes by itself when [code] turns null. */
/** Put a list in order: OK picks a row up, ▲ ▼ carry it, OK puts it down. [onDone] gets the keys in the new order. */
class ReorderDialog(val title: String, val items: List<Pair<String, String>>, val onDone: (List<String>) -> Unit) : DialogState
class QrCodeDialog(val title: String, val url: String, val code: kotlinx.coroutines.flow.StateFlow<String?>, val note: String) : DialogState
/**
 * "Support me": a gift card code and an optional note in one window, sent from there. [clean] gives the code in its
 * proper form or null; [send] (blocking, called off the main thread) says whether it arrived.
 */
/** A list that stays open while its rows are pressed: each shows its value now ([value]) and changes it on OK. */
class PickRow(val label: String, val sub: String = "", val value: () -> String, val onClick: () -> Unit)
/** [style]: the menus' look (Settings → Menu & themes → Look), so it is the same glass as the dock's menus and the side menu. */
class PickDialog(val title: String, val note: String, val rows: List<PickRow>, val style: String = "liquid") : DialogState

/** One app's catalogs and what stands in the carousel's place for it (the dock's menu → Catalogs, and Settings → Catalogs). */
fun appCatalogsDialog(app: com.glasslauncher.tv.GlassApp, pkg: String, label: String): DialogState {
    val A = com.glasslauncher.tv.data.AppCatalogs
    val st = app.settings
    val all = app.catalogs.catalogs.value
    val hasRows = st.channelsOn && com.glasslauncher.tv.data.Channels.allFor(st, pkg).isNotEmpty()
    val modes = listOfNotNull("", if (hasRows) A.ROWS else null, A.CATALOGS, A.NONE)
    fun name(m: String) = when (m) { A.ROWS -> "Its rows"; A.CATALOGS -> "Its catalogs"; A.NONE -> "Nothing"
        else -> "Automatic · " + when (A.effective("", hasRows, A.keysFor(st, all, pkg).isNotEmpty())) { A.ROWS -> "rows"; A.CATALOGS -> "catalogs"; else -> "nothing" } }
    val rows = ArrayList<PickRow>()
    rows += PickRow("In the carousel's place", "", { name(A.stage(st, pkg)) }) {
        A.setStage(st, pkg, modes[(modes.indexOf(A.stage(st, pkg)).coerceAtLeast(0) + 1) % modes.size])
    }
    // The ticked ones on top, then the ones named after this app, then the rest as Settings → Catalogs lists them.
    // (The order is the one at opening: a row doesn't jump away from under the selection when it is ticked.)
    val ticked = A.keysFor(st, all, pkg).toSet()
    val mine = A.suggested(all, pkg).toSet()
    all.sortedBy { c -> if (c.key in ticked) 0 else if (c.key in mine) 1 else 2 }.forEach { c ->
        rows += PickRow(c.name, "${c.addonName} · ${c.type}", { if (c.key in A.keysFor(st, all, pkg)) "✓" else "" }) { A.toggle(st, all, pkg, c.key) }
    }
    return PickDialog("$label · Catalogs", if (all.isEmpty()) "No catalogs yet: add an add-on in Settings → Catalogs" else "Shown while $label is selected in the dock",
        rows, st.menuStyle)
}

/** A problem report before it goes: what will be sent, Send, and a QR code for the phone when it can't be sent from here. */
class ReportDialog(val data: com.glasslauncher.tv.data.Report.Data, val send: (com.glasslauncher.tv.data.Report.Data) -> com.glasslauncher.tv.data.Report.Sent) : DialogState
class SupportDialog(val clean: (String) -> String?, val send: (String, String) -> Boolean) : DialogState

@Composable
fun DialogHost(state: DialogState?, onDismiss: () -> Unit) {
    if (state == null) return
    androidx.compose.runtime.key(state) { OverSettings {
        when (state) {
            is TextEntry -> TextEntryDialog(state, onDismiss)
            is MenuDialog -> MenuDialogView(state, onDismiss)
            is QrCodeDialog -> QrCodeDialogView(state, onDismiss)
            is ThumbDialog -> ThumbDialogView(state, onDismiss)
            is ReorderDialog -> ReorderDialogView(state, onDismiss)
            is KeyCaptureDialog -> KeyCaptureView(state, onDismiss)
            is SupportDialog -> SupportDialogView(state, onDismiss)
            is ReportDialog -> ReportDialogView(state, onDismiss)
            is PickDialog -> PickDialogView(state, onDismiss)
        }
    } }
}

@Composable
private fun DialogSurface(width: Int = 420, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier.width(width.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xF0181820))
            .border(0.8.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp)).padding(14.dp),
    ) { content() }
}

@Composable
private fun DialogButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text, color = if (focused) Color.Black else Color.White, fontSize = 13.sp,
        fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
        modifier = modifier.clip(RoundedCornerShape(9.dp))
            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.06f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun TextEntryDialog(d: TextEntry, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(d.initial) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface {
            Text(d.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(4.dp))
            Text(d.hint, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp), cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { d.onDone(text); onDismiss() }),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.10f)).padding(10.dp).focusRequester(fr),
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogButton("Save") { d.onDone(text); onDismiss() }
                DialogButton("Cancel", onClick = onDismiss)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun MenuDialogView(d: MenuDialog, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    // The menu opens while OK is still held (long press). Ignore that OK until it is released
    // and pressed again, otherwise the release would trigger the first option.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(
            width = if (d.options.any { it.first.length > 30 }) 400 else 230,
            modifier = Modifier.onPreviewKeyEvent { ev ->
                if (!ev.isSelect || armed) false
                else if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { armed = true; false }
                else true
            },
        ) {
            Text(d.title, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            // Long lists (Home Assistant sensors, cameras…) scroll with the focus instead of running off the screen.
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                d.options.forEachIndexed { i, (label, action) ->
                    DialogButton(label, Modifier.fillMaxWidth().padding(bottom = 3.dp).let { if (i == 0) it.focusRequester(fr) else it }) { onDismiss(); action() }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun KeyCaptureView(d: KeyCaptureDialog, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    var hint by remember { mutableStateOf("Press the button on the remote you want to use (Back cancels)") }
    // The OK that opened this is still being released: ignored.
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(width = 330, modifier = Modifier.focusRequester(fr).onPreviewKeyEvent { ev ->
            val code = ev.nativeKeyEvent.keyCode
            if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
            when (code) {
                android.view.KeyEvent.KEYCODE_BACK -> onDismiss()
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                    if (armed) hint = "The arrows and OK move around Glassio: pick another button" else armed = true
                else -> { onDismiss(); d.onKey(code) }
            }
            true
        }.focusable()) {
            Text(d.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            Text(hint, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp))
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(400); armed = true; runCatching { fr.requestFocus() } }
    // Buttons the system keeps from apps (YouTube, Netflix, the star…) are caught by the Home button service.
    androidx.compose.runtime.DisposableEffect(Unit) {
        com.glasslauncher.tv.KeyHub.capture = { code -> onDismiss(); d.onKey(code) }
        onDispose { com.glasslauncher.tv.KeyHub.capture = null }
    }
}

/** A remote button's name for Settings ("Red", "Play/Pause"…). */
fun keyName(code: Int): String = when (code) {
    0 -> "Not set"
    else -> android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").split('_')
        .joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }
}

@Composable
private fun ReorderDialogView(d: ReorderDialog, onDismiss: () -> Unit) {
    val order = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(d.items.map { it.first }) } }
    val names = remember { d.items.toMap() }
    var held by remember { mutableStateOf<String?>(null) }
    val frs = remember { HashMap<String, FocusRequester>() }
    var armed by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { d.onDone(order.toList()); onDismiss() }) {
        DialogSurface(width = 300) {
            Text(d.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
            Text(if (held == null) "OK picks a row up · Back saves" else "▲ ▼ move it · OK puts it down", color = Color.White.copy(alpha = 0.55f), fontSize = 10.5f.sp,
                modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 10.dp))
            order.forEachIndexed { i, k ->
                val fr = frs.requester(k)
                var f by remember { mutableStateOf(false) }
                val lifted = held == k
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 3.dp).focusRequester(fr).onFocusChanged { f = it.isFocused }
                        .clip(RoundedCornerShape(9.dp))
                        .background(when { lifted -> editAccent(); f -> Color.White.copy(alpha = 0.92f); else -> Color.White.copy(alpha = 0.06f) })
                        .onPreviewKeyEvent { ev ->
                            if (ev.isSelect) {
                                if (ev.type == KeyEventType.KeyDown) armed = true
                                else if (armed) { held = if (held == k) null else k }
                                return@onPreviewKeyEvent true
                            }
                            if (held == k && ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionUp || ev.key == Key.DirectionDown)) {
                                val j = (i + if (ev.key == Key.DirectionUp) -1 else 1).coerceIn(0, order.lastIndex)
                                if (j != i) { order.removeAt(i); order.add(j, k) }
                                true
                            } else false
                        }
                        .focusable()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", color = (if (f && !lifted) Color.Black else Color.White).copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.width(22.dp))
                    Text(names[k] ?: k, color = if (f && !lifted) Color.Black else Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    if (lifted) Text("↕", color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
    // The row that's carried keeps the focus as it moves.
    LaunchedEffect(order.toList(), held) { held?.let { runCatching { frs[it]?.requestFocus() } } }
    LaunchedEffect(Unit) { runCatching { frs[order.first()]?.requestFocus() } }
}

@Composable
private fun QrCodeDialogView(d: QrCodeDialog, onDismiss: () -> Unit) {
    val code by d.code.collectAsState()
    var seen by remember { mutableStateOf(false) }
    LaunchedEffect(code) { if (code != null) seen = true else if (seen) onDismiss() }
    val qr = remember(d.url) { com.glasslauncher.tv.net.Net.qr(d.url, 360).asImageBitmap() }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(width = 460) {
            Text(d.title, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.foundation.Image(qr, null, Modifier.width(150.dp).height(150.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Scan with your phone, or open", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    // (long links wrap)
                    Text(d.url.removePrefix("https://"), color = Color.White, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    // An empty code: the link itself signs in (Nuvio), nothing to type.
                    if (code != "") {
                        Text("and enter", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                        Text(code ?: "…", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(d.note, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            DialogButton("Close", Modifier.focusRequester(fr), onClick = onDismiss)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}


@Composable
private fun ThumbDialogView(d: ThumbDialog, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        DialogSurface(width = 520) {
            Text(d.title, color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.height(380.dp)) {
                items(d.items.size) { i ->
                    val it = d.items[i]
                    var focused by remember { mutableStateOf(false) }
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 4.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.05f))
                            .let { m -> if (i == 0) m.focusRequester(fr) else m }
                            .onFocusChanged { f -> focused = f.isFocused }
                            .clickable { onDismiss(); it.onClick() }
                            .padding(6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.08f))) {
                            if (it.thumb is androidx.compose.ui.graphics.ImageBitmap) androidx.compose.foundation.Image(it.thumb, null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(54.dp))
                            else if (it.thumb != null) coil3.compose.AsyncImage(model = it.thumb, contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(54.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.label, color = if (focused) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            if (it.sub.isNotBlank()) Text(it.sub, color = (if (focused) Color.Black else Color.White).copy(alpha = 0.55f), fontSize = 10.sp, maxLines = 1)
                        }
                        if (it.checked) Text("✓", color = if (focused) Color.Black else Color.White, fontSize = 15.sp, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay100(); runCatching { fr.requestFocus() } }
}

private suspend fun delay100() = kotlinx.coroutines.delay(100)

/**
 * One window for a gift card: what it is for, the code, an optional note, Send. The window sits at the top of the
 * screen so the TV's keyboard (bottom half) never covers it, and the keyboard opens only when OK is pressed on a
 * field (opening by itself, it jumped over the window the moment it appeared). It stays open while sending and says
 * how it went.
 */
@Composable
private fun PickDialogView(d: PickDialog, onDismiss: () -> Unit) {
    val s = LocalScale.current
    val light = d.style == "bow"
    val ink = if (light) Color(0xFF111114) else Color.White
    val fr = remember { FocusRequester() }
    // (Opened while OK is still held on a menu: that OK is not a press here.)
    var armed by remember { mutableStateOf(false) }
    var rev by remember { mutableStateOf(0) }
    // A light pop-up list on the menus' own glass (the menu's look, so also Black AMOLED and Black on white). Its
    // window covers the screen and dims nothing: the glass then sits over the page where it is drawn, as a menu's does.
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        val dv = androidx.compose.ui.platform.LocalView.current
        val overSettings = LocalOverSettings.current != null
        LaunchedEffect(overSettings) { (dv.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(if (overSettings) SettingsGlass.DIM else 0.18f) }
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.width((300 * s).dp).onPreviewKeyEvent { ev ->
                if (!ev.isSelect || armed) false
                else if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) { armed = true; false }
                else true
            }, radius = (20 * s).dp, style = d.style, darken = 0.3f) {
                Column(Modifier.fillMaxWidth().padding((8 * s).dp)) {
                    Text(d.title, color = ink.copy(alpha = 0.75f), fontSize = (10.5f * s).sp, fontWeight = FontWeight.Medium, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = (10 * s).dp, end = (10 * s).dp, top = (4 * s).dp))
                    if (d.note.isNotBlank()) Text(d.note, color = ink.copy(alpha = 0.45f), fontSize = (8 * s).sp, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = (10 * s).dp, end = (10 * s).dp, top = (1 * s).dp))
                    Spacer(Modifier.height((6 * s).dp))
                    Column(Modifier.heightIn(max = (236 * s).dp).verticalScroll(rememberScrollState())) {
                        d.rows.forEachIndexed { i, r ->
                            var focused by remember { mutableStateOf(false) }
                            val value = remember(rev) { r.value() }
                            val on = if (focused) (if (light) Color.White else Color(0xFF111114)) else ink
                            Row(Modifier.fillMaxWidth().padding(vertical = (1 * s).dp).clip(RoundedCornerShape((11 * s).dp))
                                .background(if (focused) (if (light) Color(0xFF111114) else Color.White).copy(alpha = 0.94f) else Color.Transparent)
                                .let { if (i == 0) it.focusRequester(fr) else it }
                                .onFocusChanged { focused = it.isFocused }
                                .clickable { r.onClick(); rev++ }
                                .padding(horizontal = (11 * s).dp, vertical = (6 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                                // The name, and what it is from in small faint letters beside it: one short line a row.
                                Text(r.label, color = on, fontSize = (11.5f * s).sp, fontWeight = if (value == "✓" || focused) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = (if (r.sub.isBlank()) 190 else 170 * 1).let { (it * s).dp }))
                                if (r.sub.isNotBlank()) Text(r.sub, color = on.copy(alpha = 0.45f), fontSize = (8 * s).sp, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(start = (7 * s).dp).weight(1f))
                                else Spacer(Modifier.weight(1f))
                                if (value.isNotBlank()) Text(value, color = on.copy(alpha = if (value == "✓") 1f else 0.6f), fontSize = ((if (value == "✓") 13f else 9.5f) * s).sp,
                                    fontWeight = if (value == "✓") FontWeight.Bold else FontWeight.Normal, maxLines = 1, modifier = Modifier.padding(start = (8 * s).dp))
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { repeat(6) { kotlinx.coroutines.delay(40); if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect } }
}

@Composable
private fun ReportDialogView(d: ReportDialog, onDismiss: () -> Unit) {
    val R = com.glasslauncher.tv.data.Report
    val text = remember { R.preview(d.data) }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf<com.glasslauncher.tv.data.Report.Sent?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val scroll = androidx.compose.foundation.rememberScrollState()
    val sendBtn = remember { FocusRequester() }
    var boxFocused by remember { mutableStateOf(false) }
    // No relay in this build, or it didn't take the report: the phone opens a new issue with the short summary.
    val phone = !R.relay || sent?.ok == false
    val link = remember { R.issueLink(d.data) }
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        DialogSurface(width = 640) {
            Text(if (sent?.ok == true) "Report sent" else "Report a problem", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(when {
                sent?.ok == true -> (sent?.note?.takeIf { it.isNotBlank() }?.plus(" · ") ?: "") + "Thank you!" + (if ((sent?.number ?: 0) > 0) " It is issue #${sent?.number} on GitHub" else "")
                phone && R.relay -> "It couldn't be sent from the TV (${sent?.note}). Scan the code: it opens a new issue on GitHub with a short summary, no log"
                phone -> "Scan the code with your phone: it opens a new issue on GitHub with a short summary (this build can't send from the TV)"
                else -> "This is everything that will be sent. It becomes a public issue on GitHub: addresses, keys, emails and names were removed"
            }, color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            if (phone) {
                val qr = remember(link) { com.glasslauncher.tv.net.Net.qr(link, 420).asImageBitmap() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.Image(qr, null, Modifier.width(170.dp).height(170.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(16.dp))
                    Text(d.data.description + "\n\nGlassio " + d.data.version + "\n" + d.data.device, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                }
            } else if (sent?.ok != true) {
                Text(text, color = Color.White.copy(alpha = 0.86f), fontSize = 10.sp, lineHeight = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.35f))
                        .border(1.dp, Color.White.copy(alpha = if (boxFocused) 0.7f else 0.12f), RoundedCornerShape(10.dp))
                        .onFocusChanged { boxFocused = it.isFocused }
                        // (Down from it is Send, not whichever button is nearest.)
                        .focusProperties { down = sendBtn }
                        // Up and Down read through it; at its end Down goes on to the buttons.
                        .onPreviewKeyEvent { e ->
                            val down = e.key == androidx.compose.ui.input.key.Key.DirectionDown; val up = e.key == androidx.compose.ui.input.key.Key.DirectionUp
                            if (!(down && scroll.value < scroll.maxValue) && !(up && scroll.value > 0)) false
                            else { if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) scope.launch { scroll.animateScrollTo((scroll.value + if (down) 170 else -170).coerceIn(0, scroll.maxValue)) }; true }
                        }
                        .focusable().verticalScroll(scroll).padding(10.dp))
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!phone && sent?.ok != true) DialogButton(if (busy) "Sending…" else "Send", Modifier.focusRequester(sendBtn)) {
                    if (busy) return@DialogButton
                    busy = true
                    scope.launch {
                        sent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { d.send(d.data) }
                        busy = false
                        kotlinx.coroutines.delay(80); runCatching { sendBtn.requestFocus() }
                    }
                }
                DialogButton(if (phone || sent?.ok == true) "Close" else "Cancel", if (phone || sent?.ok == true) Modifier.focusRequester(sendBtn) else Modifier, onClick = onDismiss)
                if (!phone && sent?.ok != true) Text("Up: read it all", color = Color.White.copy(alpha = 0.4f), fontSize = 10.sp)
            }
        }
    }
    LaunchedEffect(phone) { kotlinx.coroutines.delay(80); runCatching { sendBtn.requestFocus() } }
}

@Composable
private fun SupportDialogView(d: SupportDialog, onDismiss: () -> Unit) {
    var code by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(0) }  // 0 none, 1 code, 2 note
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val codeRow = remember { FocusRequester() }
    val noteRow = remember { FocusRequester() }
    val sendBtn = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val ok = d.clean(code)
    Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        // At the top of the screen: the TV's keyboard takes the bottom half.
        val dv = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(Unit) { (dv.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setGravity(android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL) }
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 24.dp),
            contentAlignment = Alignment.TopCenter) {
            DialogSurface(width = 460) {
                Text("Support Glassio", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text("With a PlayStation gift card (PSN, US store). Type its 12-character code: it goes straight to me, nobody else sees it. Thank you!",
                    color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                @Composable fun FieldRow(label: String, value: String, placeholder: String, which: Int, req: FocusRequester, upper: Boolean, onValue: (String) -> Unit) {
                    var focused by remember { mutableStateOf(false) }
                    Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, bottom = 4.dp))
                    if (editing == which) {
                        BasicTextField(
                            value = value, onValueChange = { onValue(if (upper) it.uppercase().take(16) else it.take(140)) }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(Color.White),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done,
                                capitalization = if (upper) androidx.compose.ui.text.input.KeyboardCapitalization.Characters else androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                            keyboardActions = KeyboardActions(onDone = {
                                editing = 0
                                scope.launch { kotlinx.coroutines.delay(60); runCatching { (if (which == 1 && d.clean(code) != null) noteRow else if (which == 2) sendBtn else req).requestFocus() } }
                            }),
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.16f))
                                .border(1.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(11.dp).focusRequester(field),
                        )
                        LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                    } else {
                        Text(value.ifBlank { placeholder }, color = if (focused) Color(0xFF111114) else Color.White.copy(alpha = if (value.isBlank()) 0.45f else 1f), fontSize = 15.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .background(if (focused) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.10f))
                                .focusRequester(req).onFocusChanged { focused = it.isFocused }
                                .clickable { if (!busy && !sent) editing = which }
                                .padding(11.dp))
                    }
                    Spacer(Modifier.height(10.dp))
                }
                FieldRow("Gift card code", code, "Press OK to type it (XXXX-XXXX-XXXX)", 1, codeRow, true) { code = it; status = "" }
                FieldRow("Your name or a message (optional)", note, "Press OK to type", 2, noteRow, false) { note = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DialogButton(if (sent) "Close" else if (busy) "Sending…" else "Send", Modifier.focusRequester(sendBtn)) {
                        if (sent) { onDismiss(); return@DialogButton }
                        if (busy) return@DialogButton
                        val c = d.clean(code)
                        if (c == null) { status = if (code.isBlank()) "Type the code first" else "That isn't a PSN code: 12 letters and digits"; return@DialogButton }
                        busy = true; status = "Sending…"
                        scope.launch {
                            val done = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { d.send(c, note) }
                            busy = false
                            if (done) { sent = true; status = "Sent. Thank you so much for your support!" }
                            else status = "Couldn't send it. Check the internet and try again"
                        }
                    }
                    if (!sent) DialogButton("Cancel", onClick = onDismiss)
                }
                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, color = if (sent) Color(0xFF7BE495) else Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                } else if (code.isNotBlank() && ok == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("${code.count { it.isLetterOrDigit() }} of 12 characters", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp)
                }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(80); runCatching { codeRow.requestFocus() } }
}

