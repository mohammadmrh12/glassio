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
    const val ID = 72

    /** [support]: the page ends with "Send a gift card" and "Done" instead of "Next". */
    class Page(val title: String, val points: List<String>, val where: String, val qr: String? = null, val support: Boolean = false)

    const val GITHUB = "https://github.com/mohammadmrh12/glassio"

    /** Where Glassio lives (a QR code to its GitHub page). */
    private val GITHUB_PAGE = Page("Glassio on GitHub", listOf(
        "Glassio is free and open source. Scan the code to see every version, report a problem or ask for a feature.",
        "A ⭐ on GitHub and sharing Glassio with friends keeps it going.",
    ), "github.com/mohammadmrh12/glassio", qr = GITHUB)

    /** Always the last page when this build can send support: a PSN gift card, typed and sent from here. */
    private val SUPPORT_PAGE get() = if (com.glasslauncher.tv.data.Store.PLAY) null else Page("Support Glassio", listOf(
        "Glassio is free, with no ads, made in my spare time.",
        "If you enjoy it, a PlayStation gift card (PSN, USA) helps a lot. Maybe for GTA VI 😉",
        "Press OK, type the code and press Send: it comes straight to me. Thank you!",
    ), "Also in Settings → About → PlayStation gift card", support = true)

    // A welcome first, then everything new since 4.0.0, newest last, then GitHub and support.
    val PAGES = listOfNotNull(
        Page("Welcome to Glassio 4.7", listOf(
            "Everything new since Glassio 4.0, page by page, 4.7's first. Your setup stays as it is: each new thing is a choice.",
            "Something wrong? Settings → About → Report a problem.",
            "Press ▶ to see what's new.",
        ), "Settings → About → What's new"),
        // (New in 4.7.)
        Page("Continue Watching cards", listOf(
            "Over your own background, what you're watching as small glass cards right above the dock: the picture stays whole.",
            "An app with its own Continue watching row (Netflix, Stremio…) shows it as cards too while it is selected.",
            "Up from the dock goes to them, OK opens the title. Off by default.",
        ), "Settings → Background & screensaver → Continue Watching cards"),
        Page("Screensaver and clock", listOf(
            "One player starts on the home screen just before the TV's own screensaver, which stays for other apps; the TV still sleeps on time.",
            "Home over the TV's screensaver now takes you straight to Glassio.",
            "The large clock's Still Glass style looks like the lock screen: tall digits of thick glass under the date.",
            "A paused song leaves the music island and stays gone (Island → A paused song goes).",
        ), "Settings → Background & screensaver · Music & app rows → Island"),
        // (New in 4.5.)
        Page("A tidier menu", listOf(
            "Themes, Background and Screensaver show their choices across one row, each theme as a small sample of itself.",
            "Glass over the video and the video quality are in the menu too.",
            "Page behind the player: your own video or picture can stay behind the music player.",
        ), "Menu → Themes, Background, Screensaver · Settings → Music & app rows"),
        // (New in 4.4.)
        Page("Cameras", listOf(
            "Multi-view never goes past the screen: four cameras two by two, as big as the screen allows.",
            "The cameras page: your cameras over the whole screen; the arrows choose one, OK turns its sound on.",
            "Hold OK on a camera that turns (PTZ): move and zoom it with the remote.",
            "Every camera plays, also with a video behind it: the video waits and gives its decoder back.",
        ), "Settings → Smart home → Multi-view"),
        Page("Lighter", listOfNotNull(
            "Performance → Lite: the same look, lighter on memory and on the processor.",
            "Away in another app for a while, Glassio lets its pictures go and brings them back when you return.",
            if (com.glasslauncher.tv.data.Store.PLAY) null else "Unneeded apps: turn off the TV's apps that only send reports, show ads or install other apps (Settings → System).",
        ), "Settings → System → Performance"),
        Page("Profiles", listOf(
            "One Glassio per profile of your Nuvio account: its own look, dock, catalogs, Library and Continue Watching.",
            "Who is watching? at start or from the menu, with Nuvio's profile pictures and a greeting at the top of the menu.",
        ), "Settings → Accounts & profiles"),
        Page("Your streaming apps", listOf(
            "Catalogs for apps: with Netflix, Apple TV or Prime Video selected in the dock, the carousel shows that app's titles.",
            "Play opens the streaming app: the title's own page in Netflix, Apple TV, Prime Video or Disney+.",
            "Rows per service in the Library (On Netflix, On Apple TV…), for your country.",
        ), "Settings → Catalogs & add-ons"),
        Page("New Settings", listOf(
            "11 sections in groups, every setting in one place, long lists on their own pages.",
            "New: what this version added. Changed: every setting you changed, with Back to default.",
            "The search finds any setting, also inside those pages.",
        ), "Glassio settings"),
        Page("Search", listOfNotNull(
            "Find a film or a series and open it where it can be watched: your streaming apps, Nuvio or Stremio.",
            if (com.glasslauncher.tv.data.Store.PLAY) null else "YouTube videos and songs under the titles; a song plays while you stay in Glassio.",
            "Its icon is in the dock. Turn Search off in Settings if you don't want it.",
        ), "Settings → Continue Watching & Library → Search"),
        Page("Nine languages", listOf(
            "Every screen of Glassio in your language, changed at once.",
            "The translations were made by machine: tell us where the wording is wrong.",
        ), "Settings → System → Language"),
        Page("Jellyfin", listOf(
            "Connect your Jellyfin server with a code (Quick Connect), nothing to type.",
            "Its libraries in the carousel, what you are watching, your favourites in the Library, its titles first in the search.",
            "A title on your server opens on its page in the Jellyfin app.",
        ), "Settings → Accounts & profiles → Connect Jellyfin"),
        Page("Screensaver", listOfNotNull(
            "One player: the screensaver on the home screen itself. With the screensaver's videos as background it goes on with the same video, and you are back at once.",
            "Hold OK on a video: keep it, or never show it again.",
            if (com.glasslauncher.tv.data.Store.PLAY) "Pexels videos for the words you choose, with your own free key." else "Pexels videos, and YouTube videos and playlists among My videos, each one on or off.",
            "In the menu: Screensaver → Collections and One player.",
        ), "Settings → Background & screensaver"),
        Page("Remote buttons", listOf(
            "Each button can do three things: a press, a double press and a hold.",
            "New job: close the app in front and come back to Glassio.",
            "Buttons that send no key code (some remotes' Netflix button) can be given a job too.",
            "A TV inputs button: your inputs over any app.",
        ), "Settings → System → Remote buttons"),
        Page("Manage from phone", listOf(
            "Its QR code is in the menu and in Settings' search, ready to scan.",
            "The page stays on with one code for this TV: save it to your phone's home screen and it keeps working.",
            "Change the theme, language, screensaver and more from your phone.",
        ), "Menu → Manage from phone"),
        Page("And more", listOf(
            "The Library scrolls in one smooth movement, also with a button held down.",
            "Rows above the dock: an app's row sits right above the dock, so the clock stays in view.",
            "Report a problem from the TV or the phone, cleaned of anything private before it goes.",
            "Recommended for this TV: the right settings for a weaker TV in one press (Settings → System → Performance).",
            "With Nuvio connected, the settings button becomes your Nuvio profile's picture.",
        ), "Settings → About → Report a problem"),
        GITHUB_PAGE,
        SUPPORT_PAGE?.takeIf { com.glasslauncher.tv.data.Support.ready },
    )

    val open = mutableStateOf(false)
    val page = mutableIntStateOf(0)
    /** On a support page: 0 = Send a gift card, 1 = Done. */
    val choice = mutableIntStateOf(0)
    /** Set when "Send a gift card" is picked: the home screen opens the support window. */
    val support = mutableStateOf(false)

    fun show() { page.intValue = 0; choice.intValue = 0; open.value = true }

    /** The remote while the tour shows (true = used). */
    fun onKey(app: GlassApp, code: Int, down: Boolean): Boolean {
        if (!open.value) return false
        if (code == KeyEvent.KEYCODE_HOME) return false
        if (!down) return true
        val p = PAGES[page.intValue]
        when (code) {
            // ▶ only turns the page: on the last one it does nothing (or moves to Done on the support page), so the
            // tour isn't closed by accident; OK closes it.
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                p.support && choice.intValue == 0 -> choice.intValue = 1
                page.intValue < PAGES.lastIndex -> { page.intValue++; choice.intValue = 0 }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                p.support && choice.intValue == 0 -> { close(app); support.value = true }
                page.intValue < PAGES.lastIndex -> { page.intValue++; choice.intValue = 0 }
                else -> close(app)
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                p.support && choice.intValue == 1 -> choice.intValue = 0
                page.intValue > 0 -> { page.intValue--; choice.intValue = 0 }
            }
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
    // Its own look whatever the theme (his ask): frosted glass on Glassio's lights, white writing.
    val light = false
    val ink = if (light) Color(0xFF111114) else Color.White
    // The home screen as it is behind it (no dimming: his ask); the window itself is Glassio's own lights (the setup's and the
    // profile chooser's): its glass reads those lights, not the page or the video behind, so it looks the same
    // over any background.
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    val w = constraints.maxWidth; val h = constraints.maxHeight
    val env = androidx.compose.runtime.remember(w, h, st.blurRadius, st.glassTint, st.refraction) {
        val pic = orbsPicture(480, 270, bright = true).asImageBitmap()
        GlassEnv(pic, pic, pic, w, h, st.glassTint, st.refraction, blurDp = st.blurRadius * 0.45f, ignoreVideo = true)
    }
    val envRef = androidx.compose.runtime.rememberUpdatedState(env)
    androidx.compose.runtime.CompositionLocalProvider(LocalGlassEnv provides env, LocalGlassEnvRef provides envRef) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        // Frosted glass over its own lights, the same with every theme (it reads nothing of the page behind).
        GlassPanel(Modifier.width((560 * s).dp), radius = (26 * s).dp, style = "liquid", darken = 0.08f) {
            Column(Modifier.fillMaxWidth().padding(horizontal = (30 * s).dp, vertical = (26 * s).dp)) {
                val i = WhatsNew.page.intValue
                AnimatedContent(i, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "tour") { n ->
                    val p = WhatsNew.PAGES[n]
                    Column {
                        Text(if (n == 0) "Glassio ${com.glasslauncher.tv.BuildConfig.VERSION_NAME}" else "${n + 1} / ${WhatsNew.PAGES.size}", color = ink.copy(alpha = 0.55f),
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
                    if (!com.glasslauncher.tv.data.Store.PLAY && WhatsNew.PAGES[i].support) {
                        val c = WhatsNew.choice.intValue
                        Row(horizontalArrangement = Arrangement.spacedBy((8 * s).dp)) {
                            TourPill("🎁  Send a gift card", c == 0, ink, light, s)
                            TourPill("Done", c == 1, ink, light, s)
                        }
                    } else TourPill(if (i == WhatsNew.PAGES.lastIndex) "OK  Done" else "OK  Next  ▶", true, ink, light, s)
                }
            }
        }
    }
    }
    }
}

@Composable
private fun TourPill(text: String, on: Boolean, ink: Color, light: Boolean, s: Float) {
    Text(text, color = if (on) (if (light) Color.White else Color.Black) else ink.copy(alpha = 0.8f),
        fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(ink.copy(alpha = if (on) 0.92f else 0.12f))
            .padding(horizontal = (14 * s).dp, vertical = (6 * s).dp))
}
