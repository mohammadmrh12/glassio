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
import androidx.compose.ui.graphics.asImageBitmap
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
    const val ID = 58

    class Page(val title: String, val points: List<String>, val where: String, val qr: String? = null)

    const val GITHUB = "https://github.com/mohammadmrh12/glassio"

    /** Always the last page of the tour, whatever the version: where Glassio lives, and how to support it. */
    private val GITHUB_PAGE = Page("Glassio on GitHub", listOf(
        "Glassio is free and open source. Scan the code to see every version, report a problem or ask for a feature.",
        "Support Glassio: a ⭐ on GitHub and sharing it with friends keeps it going. Thank you!",
    ), "Thank you for using Glassio ♥", qr = GITHUB)

    val PAGES = listOf(
        Page("What's new in Glassio 3.3", listOf(
            "A new screensaver: Trailers. The carousel's trailers one after another, with their logo and details; OK opens the title in Nuvio, hold OK for more.",
            "Posters screensaver: OK opens the title in Nuvio, ◀ ▶ the next or the one before.",
            "A colour for all the glass: black or petrol, still see-through, like a tinted window. And Bold text everywhere.",
            "◀ ▶ to look through · OK for the next page · Back to close.",
        ), "Screensaver: Settings → Screensaver → Type (also in the menu). Glass colour, Bold text: Settings → Menu & themes (and the menu's Themes)."),
        Page("Trailers", listOf(
            "When a title has no trailer listed, Glassio finds one on TMDB, then with a YouTube search.",
            "Subtitles one line at a time, each replacing the last (they no longer roll up), with a size of their own.",
            "The trailer's window always stays inside the screen, and it shows with the first frame (it waited before).",
            "Trailers play in the best quality the source has.",
        ), "Settings → Carousel → Trailers (Subtitle size)."),
        Page("Also in 3.3", listOf(
            "Settings opens where you left it, on the same page and row.",
            "Something is always selected: after the Apps folder, Home, or an app opened from the folder.",
            "Music mode: the music page stays when you go down into the dock.",
            "Music mode no longer opens by itself when Glassio starts with a song left paused.",
            "Holding a button in the Library moves faster. The carousel's work between slides is spread out (less CPU).",
            "Settings: more room between groups; the dock's look is in Dock & icons; the screensaver's info is under On screen.",
        ), "Settings → Dock & icons → Dock look."),
        Page("What's new in Glassio 3.2.2", listOf(
            "Trailers no longer stop by themselves after a few seconds.",
            "Trailer sound fixed: it played twice, slightly apart.",
            "◀ ▶ to look through · OK for the next page · Back to close.",
        ), "Trailers: Settings → Trailers."),
        Page("What's new in Glassio 3.2.1", listOf(
            "Icon packs show every icon they have, also the ones not tied to an app (Projectivy and Blueprint packs gave only some).",
            "Search icons: type part of a name and every installed pack is searched at once.",
            "Recent apps work without USB debugging: Glassio lists the apps you used lately. With USB debugging on, the list is exact as before.",
            "◀ ▶ to look through · OK for the next page · Back to close.",
        ), "Change an icon: hold OK on an app → Change icon → Search icons. Recent apps: Settings → Remote mapping → Home and Back."),
        Page("What's new in Glassio 3.2", listOf(
            "Remote mapping: any button on your remote opens what you choose, in any app.",
            "Then everything that came with 3.1: Hue Sync, the new Settings and more.",
            "Each page says what's new and where to switch it off or change it.",
            "◀ ▶ to look through · OK for the next page · Back to close.",
        ), "You can open this again in Settings → About → What's new."),
        Page("Remote mapping", listOf(
            "Give a remote button an app, the Apps folder, the recent apps or a TV input (HDMI…).",
            "It works everywhere, also inside other apps. Pressed again, the Apps folder or the recent apps close.",
            "Shuffle, multi-view, the corner camera, Hold Home and Back for the screensaver are set here too.",
        ), "Settings → Remote mapping. Buttons in other apps need System → Home button service."),
        Page("Also in 3.2", listOf(
            "A locked app's menu (hold OK) asks for its code first.",
            "Signing out of Nuvio, Stremio, Trakt or Simkl asks first.",
            "A row you change (◀ ▶) takes the Switch colour: burgundy, green or purple.",
            "Music mode: opening the dock keeps the song's picture behind it.",
            "The selection always comes back: after the screensaver, the Apps folder or the recent apps.",
        ), "Settings → Menu & themes → Switch colour."),
        Page("From Glassio 3.1", listOf(
            "Philips Hue lights in the colours of the screen, a new Settings with search, and much more.",
        ), "The next pages."),
        Page("Hue Sync", listOf(
            "Your Philips Hue lights take the colours of what's on screen: the backdrop, the poster or the logo, videos, the music's artwork.",
            "Each light follows its own part of the screen (pictures show which), or its place in a Hue entertainment area.",
            "Philips and other makes (Lidl Livarno, Tuya…) each get their colours the way they show them best.",
            "Optional: the lights take the colour of the app selected in the dock or the poster in the Library, with their own brightness.",
            "Back as they were when you open a film or turn the TV off (or dimmed, or off).",
        ), "Settings → Hue lights: press the bridge's button, then Connect."),
        Page("A new Settings", listOf(
            "Grouped like on an iPhone: rows together in rounded cards with thin lines between them.",
            "Search at the top, with Glassio's own keyboard: a result opens its page at that setting.",
            "Lighter and quicker to open and to move through.",
            "Switch colour: burgundy, green or purple.",
        ), "Settings → Menu & themes → Switch colour."),
        Page("Music", listOf(
            "The dock's Music icon opens the music page, even with Music mode off.",
            "Pause and Play show at once, the song's name cross-fades in place, no flash in the dock.",
            "Leaving a YouTube video no longer takes the music page or the selection away.",
        ), "Settings → Music."),
        Page("Library and cameras", listOf(
            "Holding Down in the Library glides at a steady pace, even with fast key repeat.",
            "Multi-view cameras blur everything behind them, on the home screen and the screensaver.",
            "The screensaver never starts while a trailer plays.",
        ), "Settings → Library, Settings → Cameras."),
        Page("Nuvio and your phone", listOf(
            "A self-hosted Nuvio server can be used (address, key, sign in with email).",
            "Home Assistant can get Nuvio's playback from Glassio itself and control it (pause, play, seek), quick and light.",
            "The phone page looks like iPhone Settings, with a search.",
        ), "Settings → Connections (Nuvio server, Home Assistant), Settings → Manage from phone."),
        GITHUB_PAGE,
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
                        p.qr?.let { url ->
                            val qr = androidx.compose.runtime.remember(url) { com.glasslauncher.tv.net.Net.qr(url, 360) }
                            Spacer(Modifier.height((4 * s).dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.Image(qr.asImageBitmap(), null,
                                    Modifier.size((120 * s).dp).clip(RoundedCornerShape((12 * s).dp)).background(Color.White).padding((6 * s).dp))
                                Spacer(Modifier.width((16 * s).dp))
                                Column {
                                    Text("Scan with your phone", color = ink.copy(alpha = 0.6f), fontSize = (10 * s).sp)
                                    Text(url.removePrefix("https://"), color = ink, fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold)
                                }
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
