package com.glasslauncher.tv.ui

import android.view.KeyEvent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp

/**
 * What's new: a short tour shown once after an update (never after a fresh install, where the setup explains
 * things), page by page: what each new thing does and where to switch it off or change it. Settings → About opens
 * it again. The remote goes to it while it shows (MainActivity hands it the keys), so the focus underneath stays
 * where it was.
 */
object WhatsNew {
    /** Bumped when the tour has new pages: everyone who updates sees it once. */
    const val ID = 52

    class Page(val title: String, val points: List<String>, val where: String)

    val PAGES = listOf(
        Page("What's new in Glassio", listOf(
            "Music, trailers, the screensaver, inputs and much more since 2.0.3.",
            "Each page says what's new and where to switch it off or change it.",
            "◀ ▶ to look through · OK for the next page · Back to close.",
        ), "You can open this again in Settings → About → What's new."),
        Page("Music", listOf(
            "Now Playing from any music app: Spotify, YouTube Music, Apple Music, Anghami, YouTube…",
            "Music mode: the home screen becomes a full-screen player; songs cross-fade, and the page dims while paused.",
            "In the menu press ▶ for a small player, or add the Music tile to the dock.",
            "The dynamic island on the screensaver: Up opens it with its controls, Down closes it. Its look follows your theme.",
            "Play, skip, shuffle and stop from anywhere; shuffle stays the same in every player.",
        ), "Settings → Music (music mode, island, its look and size)."),
        Page("Trailers", listOf(
            "A title's trailer plays in place of its picture, in Glassio's own player: no YouTube buttons, it starts in about a second.",
            "Cinematic (fills the screen, no black bars), Original, or a small Window.",
            "Subtitles in your language, translated when YouTube has none.",
            "Starts by itself after a few seconds on a title, only while the carousel is selected (never from the dock).",
            "Menu → Trailer to play or stop it and change it quickly. In the Library, hold OK on a poster → Trailer.",
        ), "Settings → Carousel (the Trailer rows), or Menu → Trailer. Set “Starts by itself” to Off to stop auto-play."),
        Page("Screensaver", listOf(
            "A large clock in your theme's glass: Up shows or hides it.",
            "Hold OK to lock a video you like; “Locked only” plays just those.",
            "Down shows your cameras, up to 4 side by side.",
            "It closes smoothly through black.",
        ), "Settings → Screensaver, or Menu → Screensaver."),
        Page("Backgrounds and clock", listOf(
            "Photos (Bing, Wikimedia, Unsplash or yours), your own video, or the screensaver's videos as the background.",
            "A large clock on the background that follows your theme: black, white, frosted, clear or still glass.",
            "Depth effect: a mountain or a tower in the photo stands in front of the clock.",
            "Sharp corners for the menus, dock and icons.",
        ), "Settings → Background & glass."),
        Page("Inputs, cameras and apps", listOf(
            "TV inputs and HDMI devices (PlayStation, receiver…) in the menu and as dock icons.",
            "Camera multi-view, Home Assistant cameras and doorbell pop-ups over any app.",
            "Lock any app with a 4-digit code (hold OK on the app).",
            "Recent apps in the menu.",
        ), "Settings → Menu & themes (inputs), Settings → Cameras, hold OK on an app (lock)."),
        Page("Library and accounts", listOf(
            "Hold OK on Play: add to the Library, Like, or change the artwork.",
            "Sync your Library, likes and Continue Watching across Nuvio, Trakt and Simkl.",
            "Continue Watching exactly as in Nuvio, with the next episode of series you finished.",
            "Open a title in the app you choose. A shuffle button for the carousel.",
        ), "Settings → Connections (sync), Settings → Library, Settings → Continue Watching."),
        Page("Smoother", listOf(
            "Frosted glass slides as smoothly as clear glass.",
            "Faster start, smoother carousel and less memory on small TVs.",
        ), "Settings → Performance."),
    )

    val open = mutableStateOf(false)
    val page = mutableIntStateOf(0)

    fun show() { page.intValue = 0; open.value = true }

    /** The remote while the tour shows (true = used). */
    fun onKey(app: GlassApp, code: Int, down: Boolean): Boolean {
        if (!open.value) return false
        if (code == KeyEvent.KEYCODE_HOME) return false
        if (!down) return true
        when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (page.intValue < PAGES.lastIndex) page.intValue++ else close(app)
            KeyEvent.KEYCODE_DPAD_LEFT -> if (page.intValue > 0) page.intValue--
            KeyEvent.KEYCODE_BACK -> close(app)
        }
        return true
    }

    fun close(app: GlassApp) { open.value = false; app.settings.tourSeen = ID }
}

@Composable
fun WhatsNewHost(app: GlassApp, s: Float) {
    if (!WhatsNew.open.value) return
    val st = app.settings
    val style = st.menuStyle
    val light = style == "bow"
    val ink = if (light) Color(0xFF111114) else Color.White
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        GlassPanel(Modifier.width((560 * s).dp), radius = (26 * s).dp, style = style, darken = 0.25f) {
            Column(Modifier.fillMaxWidth().padding(horizontal = (30 * s).dp, vertical = (26 * s).dp)) {
                val i = WhatsNew.page.intValue
                AnimatedContent(i, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "tour") { n ->
                    val p = WhatsNew.PAGES[n]
                    Column {
                        Text(if (n == 0) "Glassio ${com.glasslauncher.tv.BuildConfig.VERSION_NAME}" else "New", color = ink.copy(alpha = 0.55f),
                            fontSize = (9 * s).sp, fontWeight = FontWeight.SemiBold, letterSpacing = (0.8f * s).sp)
                        Spacer(Modifier.height((4 * s).dp))
                        Text(p.title, color = ink, fontSize = (22 * s).sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height((14 * s).dp))
                        p.points.forEach { line ->
                            Row(Modifier.padding(bottom = (7 * s).dp)) {
                                Box(Modifier.padding(top = (6 * s).dp, end = (10 * s).dp).size((5 * s).dp).clip(CircleShape).background(ink.copy(alpha = 0.7f)))
                                Text(line, color = ink.copy(alpha = 0.88f), fontSize = (11.5f * s).sp, lineHeight = (16 * s).sp)
                            }
                        }
                        Spacer(Modifier.height((8 * s).dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape((12 * s).dp)).background(ink.copy(alpha = 0.08f))
                            .padding(horizontal = (12 * s).dp, vertical = (9 * s).dp)) {
                            Text(p.where, color = ink.copy(alpha = 0.75f), fontSize = (10 * s).sp, lineHeight = (14 * s).sp)
                        }
                    }
                }
                Spacer(Modifier.height((18 * s).dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Row(horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                        WhatsNew.PAGES.indices.forEach { d ->
                            Box(Modifier.size(if (d == i) (16 * s).dp else (6 * s).dp, (6 * s).dp).clip(CircleShape)
                                .background(ink.copy(alpha = if (d == i) 0.9f else 0.3f)))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text(if (i == WhatsNew.PAGES.lastIndex) "OK  Done" else "OK  Next  ▶", color = if (light) Color.White else Color.Black,
                        fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(ink.copy(alpha = 0.92f)).padding(horizontal = (14 * s).dp, vertical = (6 * s).dp))
                }
            }
        }
    }
}
