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
    const val ID = 63

    class Page(val title: String, val points: List<String>, val where: String, val qr: String? = null)

    const val GITHUB = "https://github.com/mohammadmrh12/glassio"

    /** Always the last page of the tour, whatever the version: where Glassio lives, and how to support it. */
    private val GITHUB_PAGE = Page("Glassio on GitHub", listOf(
        "Glassio is free and open source. Scan the code to see every version, report a problem or ask for a feature.",
        "Support Glassio: a ⭐ on GitHub and sharing it with friends keeps it going. Thank you!",
    ), "Thank you for using Glassio ♥", qr = GITHUB)

    // Only the latest version's pages (every version's notes: CHANGELOG on GitHub), then the GitHub page.
    val PAGES = listOf(
        Page("What's new in Glassio 3.7", listOf(
            "Sync between your TVs: one main TV, the others follow it live over your home network. Performance can sync too, or stay on each TV.",
            "The settings button: Hello, the handwriting hello, Menu, or your own picture in a circle, sent from your phone with a QR code.",
            "Hold OK on an app with channels: Hide its rows or show them again.",
            "Menu & themes in clear groups: the top bar (button, clock, weather) together, then the look, then the menu.",
        ), "Settings → Sync · Settings → Menu & themes"),
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
            // ▶ only turns the page: on the last one (the GitHub QR code) it does nothing, so the page isn't closed by
            // accident; OK closes it.
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (page.intValue < PAGES.lastIndex) page.intValue++
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
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
