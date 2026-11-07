package com.glasslauncher.tv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry

/**
 * Apps locked with a 4-digit code, only inside Glassio (opening them from Glassio's dock, folder or menus asks for
 * it; the rest of Google TV is untouched). The code is entered in a small glass window with the arrows: ▲ ▼ change
 * the digit, ◀ ▶ move between them, number keys work too. The app opens the moment the code is right, no OK.
 */
object AppLock {
    /** What the code window is for: open a locked app, unlock one, or set a new code (OK saves it). */
    class Ask(val title: String, val newCode: Boolean = false, val onOk: (String) -> Unit)
    val ask = mutableStateOf<Ask?>(null)

    fun locked(app: GlassApp, pkg: String) = pkg in app.settings.lockedApps

    /** Opens [e]: straight away, or after the code when it is locked. */
    fun open(app: GlassApp, ctx: android.content.Context, e: AppEntry) {
        val go = {
            if (e.pkg == MusicUi.DOCK_PKG) MusicUi.openFromDock(app)
            else if (com.glasslauncher.tv.data.Inputs.isInput(e.pkg)) com.glasslauncher.tv.data.Inputs.open(ctx, e.pkg)
            else app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } }
            Unit
        }
        if (!locked(app, e.pkg)) { go(); return }
        ask.value = Ask(e.label) { go() }
    }

    /** Lock [e] (no code needed), or unlock it (needs the code). */
    fun toggle(app: GlassApp, ctx: android.content.Context, e: AppEntry) {
        val st = app.settings
        if (!locked(app, e.pkg)) {
            st.lockedApps = (st.lockedApps + e.pkg).distinct()
            toast(ctx, "${e.label} is locked: Glassio asks for the code to open it (code: Settings → System)")
        } else ask.value = Ask("Unlock ${e.label}") {
            st.lockedApps = st.lockedApps - e.pkg
            toast(ctx, "${e.label} unlocked")
        }
    }

    /** Settings → System: a new code (any 4 digits, the same one again too). */
    fun change(app: GlassApp, ctx: android.content.Context) {
        ask.value = Ask("Current code", newCode = false) {
            ask.value = Ask("New code · OK saves it", newCode = true) { code ->
                app.settings.lockCode = code
                toast(ctx, "Code saved")
            }
        }
    }
}

/** The glass code window (shown by the home screen over everything else while [AppLock.ask] is set). */
@Composable
fun AppLockHost(app: GlassApp, s: Float, style: String) {
    val a = AppLock.ask.value ?: return
    // Its own full-screen window: the focus goes back where it was when it closes, and the glass still lines up
    // with the picture behind (the window covers the whole screen, like the page).
    androidx.compose.ui.window.Dialog(onDismissRequest = { AppLock.ask.value = null },
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        LaunchedEffect(view) { (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.setDimAmount(0f) }
        androidx.compose.runtime.key(a) { CodePop(app, a, s, style) }
    }
}

@Composable
private fun CodePop(app: GlassApp, a: AppLock.Ask, s: Float, style: String) {
    val light = style == "bow"
    val ink = if (light) Color(0xFF111114) else Color.White
    val digits = remember { mutableStateOf(IntArray(4)) }
    var at by remember { mutableIntStateOf(0) }
    var wrong by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val enter = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(180)) }
    LaunchedEffect(Unit) { repeat(10) { kotlinx.coroutines.delay(30); if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect } }
    LaunchedEffect(wrong) { if (wrong) { repeat(3) { shake.animateTo(1f, tween(40)); shake.animateTo(-1f, tween(40)) }; shake.animateTo(0f, tween(40)); wrong = false } }
    fun code() = digits.value.joinToString("")
    fun close() { AppLock.ask.value = null }
    // Opens the moment the four digits are right (no OK). A new code is saved with OK instead.
    fun check() {
        if (!a.newCode && code() == app.settings.lockCode) { close(); a.onOk(code()) }
    }
    fun set(i: Int, v: Int) { digits.value = digits.value.copyOf().also { it[i] = (v + 10) % 10 }; check() }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        Box(
            Modifier.width((300 * s).dp)
                .graphicsLayer { alpha = enter.value; val k = 0.94f + 0.06f * enter.value; scaleX = k; scaleY = k; translationX = shake.value * 10 * s * density }
                .focusRequester(focus)
                .onPreviewKeyEvent { ev ->
                    val down = ev.type == KeyEventType.KeyDown
                    val code = ev.nativeKeyEvent.keyCode
                    when {
                        ev.key == Key.Back || ev.key == Key.Escape -> { if (!down) close(); true }
                        !down -> true
                        ev.key == Key.DirectionUp -> { set(at, digits.value[at] + 1); true }
                        ev.key == Key.DirectionDown -> { set(at, digits.value[at] - 1); true }
                        ev.key == Key.DirectionLeft -> { at = (at - 1).coerceAtLeast(0); true }
                        ev.key == Key.DirectionRight -> { at = (at + 1).coerceAtMost(3); true }
                        code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                            set(at, code - android.view.KeyEvent.KEYCODE_0); at = (at + 1).coerceAtMost(3); true
                        }
                        ev.isSelect -> {
                            if (ev.nativeKeyEvent.repeatCount == 0) {
                                if (a.newCode) { close(); a.onOk(code()) }
                                // The code is already right as it stands (0000 at first): OK opens it.
                                else if (code() == app.settings.lockCode) { close(); a.onOk(code()) }
                                else wrong = true
                            }
                            true
                        }
                        else -> true
                    }
                }
                .focusable(),
        ) {
            GlassPanel(Modifier.fillMaxWidth(), radius = (22 * s).dp, style = style, darken = 0.3f) {
                Column(Modifier.fillMaxWidth().padding(horizontal = (16 * s).dp, vertical = (14 * s).dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(a.title, color = ink.copy(alpha = 0.85f), fontSize = (12.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    androidx.compose.foundation.layout.Spacer(Modifier.height((12 * s).dp))
                    Row(horizontalArrangement = Arrangement.spacedBy((10 * s).dp)) {
                        for (i in 0 until 4) {
                            val sel = i == at
                            Box(
                                Modifier.size((46 * s).dp, (54 * s).dp).clip(RoundedCornerShape((12 * s).dp))
                                    .background((if (sel) (if (light) Color(0xFF111114) else Color.White) else ink).copy(alpha = if (sel) 0.94f else 0.08f))
                                    .border(1.dp, ink.copy(alpha = if (sel) 0f else 0.18f), RoundedCornerShape((12 * s).dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${digits.value[i]}", color = if (sel) (if (light) Color.White else Color(0xFF111114)) else ink,
                                    fontSize = (24 * s).sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    // Your own reminder (Settings → System), if you turned it on; setting a code shows nothing.
                    val hint = app.settings.lockHint.trim()
                    if (!a.newCode && app.settings.lockHintOn && hint.isNotEmpty()) {
                        androidx.compose.foundation.layout.Spacer(Modifier.height((10 * s).dp))
                        Text(hint, color = ink.copy(alpha = 0.6f), fontSize = (10 * s).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
