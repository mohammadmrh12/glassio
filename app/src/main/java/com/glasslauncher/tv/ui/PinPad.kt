// A profile's PIN, typed with the remote: over everything, like "Who is watching?" (see data/ProfilePin.kt).
package com.glasslauncher.tv.ui

import android.view.KeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Bg
import com.glasslauncher.tv.data.I18n
import com.glasslauncher.tv.data.ProfilePin
import com.glasslauncher.tv.data.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The PIN pad's state and the remote while it shows. Like the chooser it takes the keys itself and never the
 * selection, so what was selected under it (a Settings row, a profile) is still selected when it closes.
 * The digits come from the pad on screen (arrows and OK) or from a remote's own number keys.
 */
object PinUi {
    /**
     * One question. [check] says whether the four digits are right (blocking, called off the main thread); null: any
     * four digits are taken (a new PIN being typed). [onOk] gets them once accepted, after the pad has closed, so it
     * can open the next question (a new PIN typed twice).
     */
    class Ask(val title: String, val sub: String = "", val who: Profiles.Info? = null, val check: ((String) -> ProfilePin.Result)? = null,
              val onCancel: () -> Unit = {}, val onOk: (String) -> Unit)

    val ask = mutableStateOf<Ask?>(null)
    val typed = mutableStateOf("")
    val note = mutableStateOf("")
    val busy = mutableStateOf(false)
    /** The key selected on the pad: 0-8 are 1-9, 9 erases, 10 is 0, 11 cancels. */
    val sel = mutableIntStateOf(0)
    /** Bumped at a wrong PIN (the dots shake). */
    val wrong = mutableIntStateOf(0)

    fun show(a: Ask) { typed.value = ""; note.value = ""; busy.value = false; sel.intValue = 0; ask.value = a }
    private fun close() { ask.value = null; typed.value = ""; busy.value = false }

    private fun digit(d: Char) {
        val a = ask.value ?: return
        if (busy.value || typed.value.length >= ProfilePin.LENGTH) return
        note.value = ""
        typed.value += d
        if (typed.value.length < ProfilePin.LENGTH) return
        val pin = typed.value
        val check = a.check
        if (check == null) { close(); a.onOk(pin); return }
        busy.value = true
        Bg.io("glass-pin") {
            val r = runCatching { check(pin) }.getOrDefault(ProfilePin.Result.Wrong)
            withContext(Dispatchers.Main) {
                if (ask.value !== a) return@withContext
                when (r) {
                    ProfilePin.Result.Ok -> { close(); a.onOk(pin) }
                    ProfilePin.Result.Wrong -> { busy.value = false; typed.value = ""; wrong.intValue++; note.value = I18n.tr("Wrong PIN") }
                    is ProfilePin.Result.Wait -> {
                        busy.value = false; typed.value = ""; wrong.intValue++
                        note.value = I18n.tr("Too many wrong tries. Try again in") + " " + (if (r.seconds >= 120) "${(r.seconds + 59) / 60} min" else "${r.seconds} s")
                    }
                    ProfilePin.Result.Offline -> { busy.value = false; typed.value = ""; note.value = I18n.tr("Nuvio can't be reached to check this PIN") }
                }
            }
        }
    }

    private fun cancel() { val a = ask.value ?: return; close(); a.onCancel() }

    /** The remote while the pad shows (true = used). */
    fun onKey(e: KeyEvent): Boolean {
        if (ask.value == null) return false
        if (e.keyCode == KeyEvent.KEYCODE_HOME) return false
        val down = e.action == KeyEvent.ACTION_DOWN
        val first = down && e.repeatCount == 0
        val s = sel.intValue
        when (e.keyCode) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> if (first) digit('0' + (e.keyCode - KeyEvent.KEYCODE_0))
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> if (first) digit('0' + (e.keyCode - KeyEvent.KEYCODE_NUMPAD_0))
            KeyEvent.KEYCODE_DEL -> if (first && !busy.value) typed.value = typed.value.dropLast(1)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down && s % 3 > 0) sel.intValue = s - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && s % 3 < 2) sel.intValue = s + 1
            KeyEvent.KEYCODE_DPAD_UP -> if (down && s >= 3) sel.intValue = s - 3
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && s < 9) sel.intValue = s + 3
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> if (first) when (s) {
                9 -> if (!busy.value) typed.value = typed.value.dropLast(1)
                10 -> digit('0')
                11 -> cancel()
                else -> digit('1' + s)
            }
            // (On release, like every Back here: its press belonged to whatever opened the pad.)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) cancel()
        }
        return true
    }
}

private val PAD = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")

@Composable
fun PinHost(app: GlassApp) {
    val a = PinUi.ask.value
    val shown by animateFloatAsState(if (a != null) 1f else 0f, tween(if (a != null) 200 else 140), label = "pin")
    // (The last question stays drawn while the pad fades out.)
    val last = remember { mutableStateOf<PinUi.Ask?>(null) }
    if (a != null) last.value = a
    val q = a ?: last.value
    if (q == null || (shown < 0.01f && a == null)) return
    val typed = PinUi.typed.value
    val sel = PinUi.sel.intValue
    val shake = remember { Animatable(0f) }
    LaunchedEffect(PinUi.wrong.intValue) {
        if (PinUi.wrong.intValue == 0) return@LaunchedEffect
        shake.snapTo(1f); shake.animateTo(0f, spring(0.28f, 600f))
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown }.background(Color(0xFF05060B)), contentAlignment = Alignment.Center) {
        // The same soft lights as "Who is watching?", a little darker: the digits are what this page is about.
        Orbs()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            q.who?.let { p ->
                Face(app, p, Modifier.size(70.dp), letter = 30.sp)
                Spacer(Modifier.height(8.dp))
                Plain(p.name, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
            }
            Text(q.title, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            if (q.sub.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(q.sub, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.graphicsLayer { translationX = shake.value * 26f }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(ProfilePin.LENGTH) { i ->
                    val on = i < typed.length
                    Box(Modifier.size(15.dp).clip(CircleShape).background(if (on) Color.White else Color.Transparent)
                        .border(1.4.dp, Color.White.copy(alpha = if (on) 1f else 0.5f), CircleShape))
                }
            }
            Spacer(Modifier.height(8.dp))
            // (Always a line high: the pad doesn't move when a note comes.)
            Text(if (PinUi.busy.value) I18n.tr("Checking…") else PinUi.note.value.ifBlank { " " }, color = Color(0xFFFF8A80).copy(alpha = if (PinUi.busy.value) 0.6f else 1f),
                fontSize = 12.sp, maxLines = 1)
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (c in 0 until 3) {
                        val i = r * 3 + c
                        val on = sel == i
                        val t by animateFloatAsState(if (on) 1f else 0f, spring(0.75f, 500f), label = "key")
                        Box(Modifier.size(54.dp).graphicsLayer { val sc = 1f + 0.08f * t; scaleX = sc; scaleY = sc }.clip(CircleShape)
                            .background(if (on) Color(0xFFF2F2F5) else Color.White.copy(alpha = 0.09f))
                            .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                            Plain(PAD[i], color = if (on) Color(0xFF0B0B10) else Color.White.copy(alpha = if (i == 9 || i == 11) 0.7f else 1f),
                                fontSize = if (i == 9 || i == 11) 17.sp else 22.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(I18n.tr("The remote's number keys work too · Back cancels"), color = Color.White.copy(alpha = 0.38f), fontSize = 11.sp, fontWeight = FontWeight.Light)
        }
    }
}
