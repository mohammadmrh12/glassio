// "Who is watching?": the profiles of the Nuvio account, each with its own Glassio (see data/Profiles.kt).
package com.glasslauncher.tv.ui

import android.view.KeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Backup
import com.glasslauncher.tv.data.Bg
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.I18n
import com.glasslauncher.tv.data.Profiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The chooser's state and the remote while it shows. It takes the keys itself (like the What's new tour) and never
 * the selection, so whatever was selected under it is still selected when it closes.
 */
object ProfilesUi {
    val open = mutableStateOf(false)
    val list = mutableStateOf<List<Profiles.Info>>(emptyList())
    /** The profile selected in the row; [list].size is the "At start" button under it. */
    val sel = mutableIntStateOf(0)
    private var row = 0
    /** "Opening …" while Glassio starts again as another profile. */
    val busy = mutableStateOf("")
    /** A short line under the profiles (what a long press did, why a profile can't be opened). */
    val note = mutableStateOf("")
    /** Bumped when the start mode changes (the button's label). */
    val rev = mutableIntStateOf(0)
    private var held = false
    /** Test: the list is kept as it is (pretend profiles), not read again from Nuvio. */
    @Volatile var keepList = false

    fun show(app: GlassApp) {
        val active = Profiles.active(app)
        val known = Profiles.known(app).ifEmpty { listOf(Profiles.Info(active, app.library.nuvio.profileName.ifBlank { if (active == 1) "Main profile" else "Profile $active" })) }
        list.value = known
        row = known.indexOfFirst { it.id == active }.coerceAtLeast(0); sel.intValue = row
        busy.value = ""; note.value = ""; open.value = true
        // The names and pictures as they are in Nuvio now (the list shown meanwhile is the one last seen).
        if (!keepList) Bg.io("glass-profiles") {
            val fresh = app.library.nuvio.profileInfos() ?: return@io
            if (fresh.isEmpty()) return@io
            Profiles.remember(app, fresh)
            withContext(Dispatchers.Main) {
                if (!open.value || busy.value.isNotEmpty()) return@withContext
                val cur = list.value.getOrNull(row)?.id
                list.value = fresh
                row = fresh.indexOfFirst { it.id == cur }.coerceAtLeast(0)
                if (sel.intValue < list.value.size || sel.intValue > fresh.size) sel.intValue = row
            }
        }
    }

    /** The TV started, or woke after a long sleep: ask, or go to the automatic profile (see Profiles.onWake). */
    fun wake(app: GlassApp) {
        val known = Profiles.known(app)
        when (val w = Profiles.onWake(Profiles.startMode(app), Profiles.active(app), Profiles.autoId(app), known.map { it.id }, app.library.nuvio.connected)) {
            0 -> {}
            -1 -> show(app)
            else -> go(app, known.first { it.id == w }, quiet = true)
        }
    }

    private fun go(app: GlassApp, p: Profiles.Info, quiet: Boolean = false) {
        if (!Profiles.choose(app, p.id)) { open.value = false; return }
        app.library.nuvio.setProfile(p.id, p.name)
        if (!quiet) busy.value = p.name
        Glog.i("Profile: starting again as ${p.id}")
        Bg.io("glass-restart") { delay(if (quiet) 100 else 500); Backup.restart(app) }
    }

    /** The remote while the chooser shows (true = used). */
    fun onKey(app: GlassApp, e: KeyEvent): Boolean {
        if (!open.value) return false
        if (e.keyCode == KeyEvent.KEYCODE_HOME) return false
        if (busy.value.isNotEmpty()) return true
        val n = list.value.size
        val down = e.action == KeyEvent.ACTION_DOWN
        val onButton = sel.intValue >= n
        when (e.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down && !onButton && row > 0) { row--; sel.intValue = row; note.value = "" }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down && !onButton && row < n - 1) { row++; sel.intValue = row; note.value = "" }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && !onButton) sel.intValue = n
            KeyEvent.KEYCODE_DPAD_UP -> if (down && onButton) sel.intValue = row
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val p = list.value.getOrNull(row)
                when {
                    down && e.repeatCount == 0 -> held = false
                    // Held on a profile: Glassio always starts as that one.
                    down && !held && !onButton && p != null && (e.isLongPress || e.eventTime - e.downTime > 650) -> {
                        held = true
                        Profiles.setStartMode(app, "auto"); Profiles.setAutoId(app, p.id); rev.intValue++
                        note.value = I18n.tr("Glassio will start as") + " " + p.name
                    }
                    !down && !held -> when {
                        onButton -> {
                            // Ask ↔ Automatic (the profile selected in the row).
                            if (Profiles.startMode(app) == "ask") { Profiles.setStartMode(app, "auto"); list.value.getOrNull(row)?.let { Profiles.setAutoId(app, it.id) } }
                            else Profiles.setStartMode(app, "ask")
                            rev.intValue++
                        }
                        p == null -> open.value = false
                        p.locked && p.id != Profiles.active(app) -> note.value = I18n.tr("This profile has a PIN in Nuvio: it can't be opened from here yet")
                        else -> go(app, p)
                    }
                }
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> if (!down) open.value = false
        }
        return true
    }
}

/**
 * A profile's picture: its own (the one its settings button shows), else Nuvio's (the one it uploaded, or Nuvio's
 * ready-made one on its colour, both kept on the TV), else its colour and first letter.
 */
@Composable
internal fun Face(app: GlassApp, p: Profiles.Info, modifier: Modifier, letter: androidx.compose.ui.unit.TextUnit = 58.sp) {
    val rev = app.icons.rev.intValue
    val nuvioRev = com.glasslauncher.tv.data.NuvioAvatars.rev.intValue
    // (true: the picture is Nuvio's ready-made one, drawn on Nuvio's colour for it.)
    var readyMade by remember(p.id) { mutableStateOf(false) }
    val own by produceState<ImageBitmap?>(null, p.id, rev, nuvioRev, p.avatar, p.avatarId) {
        value = withContext(Dispatchers.IO) {
            val mine = Profiles.picture(app, p.id).takeIf { it.isFile && !com.glasslauncher.tv.data.NuvioAvatars.testOnly }
            val f = mine ?: com.glasslauncher.tv.data.NuvioAvatars.ready(app, p)
            readyMade = mine == null && f != null && !p.avatar.startsWith("http")
            if (f == null) null else runCatching {
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
                var sample = 1
                while (minOf(o.outWidth, o.outHeight) / sample > 640) sample *= 2
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    val tint = (if (readyMade && own != null) runCatching { Color(android.graphics.Color.parseColor(p.avatarBg)) }.getOrNull() else null)
        ?: runCatching { Color(android.graphics.Color.parseColor(p.color)) }.getOrNull() ?: FACE_COLORS[p.id.mod(FACE_COLORS.size)]
    Box(modifier.clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
        val pic = own
        if (pic != null) Image(pic, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(p.name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontSize = letter, fontWeight = FontWeight.SemiBold)
    }
}

private val FACE_COLORS = listOf(Color(0xFF7B61FF), Color(0xFF3FA9F5), Color(0xFFFF6FA8), Color(0xFF34C759), Color(0xFFFF9F0A))

@Composable
fun ProfileChooserHost(app: GlassApp) {
    val shown by animateFloatAsState(if (ProfilesUi.open.value) 1f else 0f, tween(if (ProfilesUi.open.value) 220 else 160), label = "profiles")
    if (shown < 0.01f && !ProfilesUi.open.value) return
    val list = ProfilesUi.list.value
    val sel = ProfilesUi.sel.intValue
    val active = Profiles.active(app)
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown }.background(Color(0xFF05060B)), contentAlignment = Alignment.Center) {
        // The same soft lights as the first-run setup, under a light shade.
        Orbs()
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        val busy = ProfilesUi.busy.value
        if (busy.isNotEmpty()) {
            Text(I18n.tr("Opening Glassio as") + " " + busy, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            return@Box
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(I18n.tr("Who is watching?"), color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(46.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(40.dp), verticalAlignment = Alignment.Top) {
                list.forEachIndexed { i, p ->
                    val on = sel == i
                    val t by animateFloatAsState(if (on) 1f else 0f, spring(0.72f, 420f), label = "face")
                    Column(Modifier.width(168.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(168.dp), contentAlignment = Alignment.Center) {
                            Face(app, p, Modifier.size(140.dp).graphicsLayer { val sc = 1f + 0.16f * t; scaleX = sc; scaleY = sc; alpha = 0.72f + 0.28f * t }
                                .border((3.2f * t).dp, Color.White.copy(alpha = t), CircleShape))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(p.name, color = Color.White.copy(alpha = if (on) 1f else 0.62f), fontSize = 18.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                        Text(when { p.id == active -> I18n.tr("In use"); p.locked -> I18n.tr("PIN in Nuvio"); else -> " " },
                            color = Color.White.copy(alpha = 0.45f), fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(34.dp))
            ProfilesUi.rev.intValue
            val ask = Profiles.startMode(app) == "ask"
            val autoName = list.firstOrNull { it.id == Profiles.autoId(app) }?.name ?: I18n.tr("Main profile")
            val onButton = sel >= list.size
            val shape = RoundedCornerShape(50)
            // A small pill as wide as its words: the profiles are what this page is about.
            Box(Modifier.wrapContentWidth().clip(shape).background(if (onButton) Color(0xFFF2F2F5) else Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color.White.copy(alpha = if (onButton) 0f else 0.22f), shape).padding(horizontal = 16.dp, vertical = 7.dp)) {
                Text(I18n.tr("At start") + ": " + (if (ask) I18n.tr("Ask") else I18n.tr("Automatic") + " ($autoName)"),
                    color = if (onButton) Color(0xFF0B0B10) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            Spacer(Modifier.height(14.dp))
            Text(ProfilesUi.note.value.ifBlank { I18n.tr("OK opens a profile · hold OK to always start as it") },
                color = Color.White.copy(alpha = 0.38f), fontSize = 11.sp, fontWeight = FontWeight.Light)
        }
    }
}
