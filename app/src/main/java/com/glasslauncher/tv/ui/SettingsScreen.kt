package com.glasslauncher.tv.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.NuvioLaunch
import com.glasslauncher.tv.net.Net
import com.glasslauncher.tv.net.PhoneServer
import com.glasslauncher.tv.saver.ScreensaverActivity
import com.glasslauncher.tv.saver.AerialSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private sealed interface SRow
private class Toggle(val title: String, val desc: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : SRow
private class Adjust(val title: String, val desc: String, val value: () -> String, val step: (Int) -> Unit) : SRow
/** [ok]: true shows the value in green with a check mark (set and working), false in red. */
private class Act(val title: String, val desc: String, val value: String = "", val ok: Boolean? = null, val onClick: () -> Unit) : SRow
private class Note(val text: String) : SRow
private class AppItem(val entry: AppEntry) : SRow

private enum class Kind { Rows, Phone, About }
private class Category(val title: String, val kind: Kind = Kind.Rows, val rows: @Composable () -> List<SRow> = { emptyList() })

private fun <T> cycle(list: List<T>, cur: T, d: Int): T {
    val i = list.indexOf(cur).let { if (it < 0) 0 else it }
    return list[(i + d).mod(list.size)]
}
private fun styleName(v: String) = when (v) { "water" -> "Glass like glass"; "water_still" -> "Glass like glass (still)"; "clear" -> "Clear (matte)"; "amoled" -> "AMOLED black"; "bow" -> "Black on white"; else -> "Liquid glass" }
private fun themeName(v: String) = when (v) { "bow" -> "Black on white"; "amoled" -> "Black AMOLED"; "liquid" -> "Liquid"; "clear" -> "Clear"; "water_still" -> "Glass still"; "water" -> "Glass is glass"; else -> "Custom" }
private fun hostOf(u: String) = runCatching { java.net.URI(u).host }.getOrNull() ?: "add-on"
private fun pct(f: Float) = "${(f * 100).roundToInt()}%"
private fun tenth(f: Float, d: Int) = ((f * 10).roundToInt() + d) / 10f

/** Google Play page of [pkg], or a Play search for [query]; falls back to the web page. */
private fun openPlay(ctx: Context, pkg: String?, query: String?) {
    val q = android.net.Uri.encode(query.orEmpty())
    val market = if (pkg != null) "market://details?id=$pkg" else "market://search?q=$q&c=apps"
    val web = if (pkg != null) "https://play.google.com/store/apps/details?id=$pkg" else "https://play.google.com/store/search?q=$q&c=apps"
    for (u in listOf(market, web)) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (_: Exception) {}
    }
    toast(ctx, "Google Play is not available on this device")
}

/** Is Glassio's Home-button accessibility service switched on? */
private fun isHomeKeyOn(ctx: Context): Boolean = runCatching {
    AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        .contains("${ctx.packageName}/")
}.getOrDefault(false)

private fun openSystem(ctx: Context, action: String) {
    try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { toast(ctx, "This screen is not available on this device") }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(app: GlassApp, initialCat: Int, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val st = app.settings
    val scope = rememberCoroutineScope()
    val traktCode by app.cw.traktCode.collectAsState()
    val nuvioUrl by app.library.nuvioUrl.collectAsState()
    // The QR dialog closes when this turns null (sign-in finished); the Nuvio code is in the QR itself.
    val nuvioShort = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    LaunchedEffect(nuvioUrl) { nuvioShort.value = nuvioUrl?.let { "" } }
    val tvRev = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    var tmdbOk by remember { mutableStateOf<Boolean?>(null) }
    var tmdbChecking by remember { mutableStateOf(false) }
    LaunchedEffect(st.tmdbToken) {
        tmdbOk = null
        val t = st.tmdbToken
        if (t.isBlank() || com.glasslauncher.tv.data.Tmdb.looksLikeApiKey(t)) return@LaunchedEffect
        tmdbChecking = true
        tmdbOk = kotlinx.coroutines.withContext(Dispatchers.IO) { com.glasslauncher.tv.data.Tmdb.verify(t) }
        tmdbChecking = false
    }
    var homeKeyOn by remember { mutableStateOf(isHomeKeyOn(ctx)) }
    var adbAccess by remember { mutableStateOf<Boolean?>(if (app.cw.hasLogAccess()) true else null) }
    val catalogs by app.catalogs.catalogs.collectAsState()
    val selected by app.catalogs.selectedKeys.collectAsState()
    val addons by app.catalogs.addonList.collectAsState()
    val accountAddons by app.catalogs.accountAddons.collectAsState()
    val status by app.catalogs.status.collectAsState()
    val allApps by app.apps.apps.collectAsState()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var phoneDialog by remember { mutableStateOf(false) }
    var cat by remember { mutableIntStateOf(initialCat) }
    var shownCat by remember { mutableIntStateOf(initialCat) }
    LaunchedEffect(cat) { if (cat != shownCat) { kotlinx.coroutines.delay(120); shownCat = cat } }
    val packList = remember { listOf("") + app.packs.installed().map { it.pkg } }
    val packLabels = remember { app.packs.installed().associate { it.pkg to it.label } }
    var rightFocused by remember { mutableStateOf(false) }
    val firstCat = remember { FocusRequester() }
    // One requester per category: leaving the right pane goes back to the category it belongs to,
    // instead of whichever category happens to sit next to the row (that switched the page).
    val catFocus = remember { List(16) { FocusRequester() } }

    fun io(block: suspend () -> String) = scope.launch(Dispatchers.IO) {
        toast(ctx, runCatching { block() }.getOrElse { it.message ?: "Failed" })
    }
    /** A video link's picture if it's made already (no waiting). */
    fun thumbOf(id: String): java.io.File? = if (!id.startsWith("http")) null
        else com.glasslauncher.tv.saver.VideoThumbs.file(ctx, id).takeIf { it.isFile && it.length() > 0 }
    /** Makes the pictures still missing, then shows the same list again with them (if it's still open). */
    fun fillThumbs(ids: List<String>, title: String, items: () -> List<ThumbItem>) = scope.launch(Dispatchers.IO) {
        val missing = ids.filter { it.startsWith("http") && thumbOf(it) == null }
        if (missing.isEmpty()) return@launch
        missing.forEach { com.glasslauncher.tv.saver.VideoThumbs.get(ctx, it) }
        if ((dialog as? ThumbDialog)?.title == title) dialog = ThumbDialog(title, items())
    }
    // A row that opens another section (things that belong together are reachable from both).
    val weatherFound by app.weather.found.collectAsState()
    val catTitles = remember { arrayOf<List<String>>(emptyList()) }
    fun link(title: String, desc: String): SRow = Act("→ " + com.glasslauncher.tv.data.I18n.tr(title), desc, "") {
        val i = catTitles[0].indexOf(title)
        if (i >= 0) { cat = i; shownCat = i; scope.launch { delay150(); runCatching { catFocus[i].requestFocus() } } }
    }
    // TMDB: the token is checked with TMDB itself whenever it changes, so "Set" means it really works.
    fun tmdbRow(): SRow {
        val t = st.tmdbToken
        val (value, ok) = when {
            t.isBlank() -> "Not set" to null
            com.glasslauncher.tv.data.Tmdb.looksLikeApiKey(t) -> "API Key, not the token" to false
            tmdbOk == true -> "Set" to true
            tmdbOk == false -> "Not working" to false
            tmdbChecking -> "Checking…" to null
            else -> "Set (couldn't check, offline?)" to null
        }
        return Act("TMDB token", "Use the API Read Access Token (long, starts eyJ…), not the short API Key",
            value, ok) {
            dialog = TextEntry("TMDB API Read Access Token", "", "The long token starting eyJ… (not the 32-character API Key). Easier from the phone page") {
                st.tmdbToken = it; app.catalogs.clearEnrichment()
            }
        }
    }
    // Connect / sign out of the Nuvio account: the same row in Menu & Library, Catalogs and Nuvio & Home Assistant.
    fun connectStremio() {
        val sm = app.cw.stremio
        scope.launch(Dispatchers.IO) {
            val l = runCatching { sm.startLink() }.getOrElse { toast(ctx, "Stremio: ${it.message}"); return@launch }
            val code = kotlinx.coroutines.flow.MutableStateFlow<String?>(l.code)
            dialog = QrCodeDialog("Connect Stremio", l.url, code, "Approve on your phone. This closes by itself.")
            val ok = sm.waitLink(l) { dialog == null }
            code.value = null
            toast(ctx, if (ok) "Stremio connected" else "Stremio: ${sm.lastError}")
            if (ok) { st.cwStremio = true; app.cw.pollNow(); tvRev.intValue++ }
        }
    }
    fun connectSimkl() {
        val sk = app.cw.simkl
        scope.launch(Dispatchers.IO) {
            if (!sk.available) {
                // Simkl needs an app id: pasted on the phone page (Accounts), then the sign-in code follows by itself.
                val u = com.glasslauncher.tv.net.PhoneHost.start(app) ?: run { toast(ctx, "The TV is not on a network"); return@launch }
                val code = kotlinx.coroutines.flow.MutableStateFlow<String?>("Simkl Client ID")
                dialog = QrCodeDialog("Simkl: add its app id", u, code, "Scan, then paste the Client ID from simkl.com/settings/developer under Accounts. Sign-in follows here.")
                var n = 0
                while (!sk.available && dialog != null && n++ < 600) kotlinx.coroutines.delay(1000)
                code.value = null
                if (!sk.available) return@launch
                kotlinx.coroutines.delay(400)
            }
            val pin = runCatching { sk.startPin() }.getOrElse { toast(ctx, "Simkl: ${it.message}"); return@launch }
            val code = kotlinx.coroutines.flow.MutableStateFlow<String?>(pin.code)
            dialog = QrCodeDialog("Connect Simkl", pin.qr, code, "Scan and approve on simkl.com (the code is filled in). This closes by itself.")
            val ok = sk.waitPin(pin) { dialog == null }
            code.value = null
            toast(ctx, if (ok) "Simkl connected" else "Simkl: ${sk.lastError}")
            if (ok) { st.cwSimkl = true; app.cw.pollNow(); tvRev.intValue++ }
        }
    }

    fun nuvioAccountRow(): SRow =
        if (!app.library.nuvio.connected) Act("Connect Nuvio account", "Scan a QR code with your phone and approve, like signing in to Nuvio on a TV", if (nuvioUrl != null) "Waiting…" else "") {
                        val appCtx = ctx.applicationContext
                        app.library.connectNuvio { msg -> toast(appCtx, msg) }
                        scope.launch {
                            var n = 0
                            while (app.library.nuvioUrl.value == null && n++ < 60) kotlinx.coroutines.delay(100)
                            app.library.nuvioUrl.value?.let { u -> dialog = QrCodeDialog("Connect Nuvio", u, nuvioShort,
                                "Scan with your phone and approve on nuvio.tv. This closes by itself once you approve.") }
                        }
                    } else Act("Nuvio account", "Signed in" + app.library.nuvio.email.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty() + ": Library, add-ons and saving use it", "Sign out") { app.library.nuvio.signOut(); app.library.nuvioList.value = emptyList(); st.libraryNuvio = st.libraryNuvio; toast(ctx, "Signed out of Nuvio") }


    val categories = listOf(
        Category("Performance") {
            fun custom() { st.perfMode = "custom" }
            listOf(
                Adjust("Mode", when (st.perfMode) {
                    "auto" -> "Picked for this TV from its memory and graphics chip"
                    "performance" -> "Smoothest: lighter pictures, quick fades, flat glass"
                    "quality" -> "Sharpest: original pictures, slow fades, full glass"
                    "balanced" -> "Sharp pictures, smooth navigation"
                    else -> "Your own mix of the settings below"
                }, {
                    fun n(m: String) = when (m) { "performance" -> "Performance"; "quality" -> "Quality"; "balanced" -> "Balanced"; else -> "Custom" }
                    if (st.perfMode == "auto") "Auto (${n(st.autoTier)})" else n(st.perfMode)
                }, { st.applyMode(cycle(listOf("auto", "performance", "balanced", "quality"), st.perfMode.takeIf { m -> m != "custom" } ?: "auto", it)) }),
                Adjust("Glass detail", "Resolution of the picture the glass bends (higher = crisper glass, more memory)", {
                    when (st.glassRes) { 720 -> "Sharp (720 px)"; else -> "Standard (480 px)" }
                }, { st.glassRes = cycle(listOf(480, 720), st.glassRes, it); custom() }),
                Adjust("Picture quality", "Carousel backdrops. 1280 enhanced: the light 1280 px picture, sharpened as it is scaled up",
                    { when (st.imageQuality) { "high" -> "High (1280 px)"; "hd" -> "1280 enhanced"; else -> "Maximum (original)" } },
                    { st.imageQuality = cycle(listOf("high", "hd", "max"), st.imageQuality, it); custom() }),
                Adjust("Picture cache", "Posters, backdrops and logos kept on the TV, so browsing back is instant. Applies after a restart", { "${st.cacheMb / 1024.0}".removeSuffix(".0") + " GB" },
                    { st.cacheMb = cycle(listOf(512, 1024, 1536, 2048, 3072, 4096), st.cacheMb, it) }),
                Adjust("Prepare ahead", "Slides loaded before you get to them", { "${st.prefetchDepth}" }, { st.prefetchDepth += it; custom() }),
                Adjust("Slide transition", "", { "${st.transitionMs} ms" }, { st.transitionMs += it * 50; custom() }),
                Adjust("Glass refraction", "0 = flat glass (lightest)", { "${st.refraction}" }, { st.refraction += it * 2; custom() }),
                Toggle("Backdrop gallery", "Several pictures per title", { st.heroGallery }, { st.heroGallery = it; custom() }),
            )
        },
        Category("Carousel") {
            listOf(
                Toggle("Auto-play", "Slides change on their own", { st.heroAutoplay }, { st.heroAutoplay = it }),
                Adjust("Slide time", "Seconds before the next title", { "${st.heroIntervalSec} s" }, { st.heroIntervalSec += it }),
                Adjust("Transition speed", "Fade between slides (lower = faster)", { "${st.transitionMs} ms" }, { st.transitionMs += it * 100 }),
                Adjust("Slide animation", "Apple TV: the next picture opens up from the right (from the left going back) · Slide: pushed in · Fade",
                    { when (st.slideAnim) { "apple" -> "Apple TV"; "slide" -> "Slide"; else -> "Fade" } },
                    { st.slideAnim = cycle(listOf("apple", "slide", "fade"), st.slideAnim, it) }),
                Adjust("Buttons", "Play + Next: like Apple TV, Right or OK on the round button shows the next title, Left on Play the previous one",
                    { when (st.heroButtons) { "apple" -> "Play + Next"; "play" -> "Play only"; else -> "Hidden" } },
                    { st.heroButtons = cycle(listOf("apple", "play", "hidden"), st.heroButtons, it) }),
                Toggle("  · + button", "Between Play and Next: saves the title to your Library", { st.heroPlus }, { st.heroPlus = it }),
                Toggle("Dots auto-hide", "Show them only when the carousel is selected", { st.dotsAutoHide }, { st.dotsAutoHide = it }),
                Adjust("Title style", "Logo image, plain text, or nothing", {
                    when (st.titleMode) { "logo" -> "Logo"; "text" -> "Text"; else -> "Hidden" }
                }, { st.titleMode = cycle(listOf("logo", "text", "hidden"), st.titleMode, it) }),
                Toggle("White logo", "Apple style: the logo all in white", { st.logoWhite }, { st.logoWhite = it }),
                Adjust("Logo / title size", "Logos are evened out so wide and tall ones look the same size", { pct(st.titleScale) }, { st.titleScale = tenth(st.titleScale, it) }),
                Adjust("Rating, year, platform size", "", { pct(st.metaScale) }, { st.metaScale = tenth(st.metaScale, it) }),
                Adjust("Genres size", "", { pct(st.genresScale) }, { st.genresScale = tenth(st.genresScale, it) }),
                Toggle("Rating, year, platform", "The chips under the title", { st.showMeta }, { st.showMeta = it }),
                Toggle("Genres line", "", { st.showGenres }, { st.showGenres = it }),
                Toggle("Synopsis", "", { st.showSynopsis }, { st.showSynopsis = it }),
                Toggle("Synopsis only when the carousel is selected", "Hidden while you browse the apps", { st.synopsisOnFocus }, { st.synopsisOnFocus = it }),
                Adjust("Synopsis size", "", { pct(st.synopsisScale) }, { st.synopsisScale = tenth(st.synopsisScale, it) }),
                Adjust("Synopsis lines", "", { "${st.synopsisLines}" }, { st.synopsisLines += it }),
                Adjust("Dots style", "", {
                    when (st.dotsMode) { "window" -> "Dots"; "liquid" -> "Liquid glass dots"; "progress" -> "Progress bar"; else -> "Hidden" }
                }, { st.dotsMode = cycle(listOf("window", "liquid", "progress", "hidden"), st.dotsMode, it) }),
                Adjust("Dots per group", "", { "${st.dotsMax}" }, { st.dotsMax += it }),
                Adjust("Titles per catalog", "Reload to apply", { "${st.perCatalog}" }, { st.perCatalog += it * 5 }),
                Adjust("Total titles", "0 = every title, endless loop", { if (st.maxItems == 0) "Unlimited" else "${st.maxItems}" }, { st.maxItems = (st.maxItems + it * 10).coerceAtLeast(0) }),
                Toggle("Backdrop gallery", "Like Apple TV: each title shows several of its pictures (needs the TMDB token)", { st.heroGallery }, { st.heroGallery = it }),
                Adjust("Pictures per title", "", { "${st.galleryShots}" }, { st.galleryShots = (st.galleryShots + it).coerceIn(2, 6) }),
                Toggle("Shuffle all catalogs", "Random order across every catalog, a new mix each time the list loads", { st.shuffle }, { st.shuffle = it }),
                Toggle("Surprise me", "A random mix from all your catalogs (different titles each time), fetched again on its own", { st.surprise }, { st.surprise = it; io { app.catalogs.refresh(); "New mix" } }),
                Adjust("  · New mix every", "", { st.surpriseMin.let { if (it < 60) "$it min" else "${it / 60} h" } }, { st.surpriseMin = cycle(listOf(15, 30, 60, 180, 360, 720), st.surpriseMin, it) }),
                Adjust("Synopsis language", "Needs a TMDB token. English is used when a title has no text in this language", { com.glasslauncher.tv.data.LANG_NAMES[st.descLang] ?: "English" }, {
                    st.descLang = cycle(com.glasslauncher.tv.data.TMDB_LANGS.keys.toList(), st.descLang, it); app.catalogs.clearEnrichment()
                }),
                Adjust("Artwork", "Which picture each title uses", {
                    when (st.artChoice) { "second" -> "Second picture"; "best" -> "Highest resolution"; "addon" -> "Add-on picture"; else -> "Main (TMDB)" }
                }, { st.artChoice = cycle(listOf("main", "second", "best", "addon"), st.artChoice, it) }),
                Note("One title: on the carousel hold OK (or press Menu) to switch to its next picture. Glassio remembers it."),
                Act("Reset pictures picked per title", "", "") { st.clearArtPicks(); toast(ctx, "Every title is back to the Artwork setting") },
                Act("Reload carousel now", "") { io { app.catalogs.refresh(); app.catalogs.status.value } },
            )
        },
        Category("Clock & glass") {
            val bgFolder = CustomBackground.folder(ctx).absolutePath
            fun pick(video: Boolean) {
                val files = CustomBackground.files(ctx, video)
                if (files.isEmpty()) { toast(ctx, "No ${if (video) "videos" else "pictures"} yet. Copy them to: $bgFolder"); return }
                dialog = ThumbDialog(if (video) "Videos in the folder" else "Pictures in the folder", files.map { f ->
                    ThumbItem(f.name, "${f.length() / 1048576} MB", f, checked = f.absolutePath == (if (video) st.bgVideo else st.bgImage)) {
                        if (video) { st.bgVideo = f.absolutePath; st.bgMode = "video" } else { st.bgImage = f.absolutePath; st.bgMode = "image" }
                    }
                })
            }
            listOf(
                Adjust("Background", "Each title's own picture, or yours: a picture or a video, from a link or the folder below",
                    { when (st.bgMode) { "image" -> "My picture"; "video" -> "My video"; "aerial" -> "Screensaver (aerials)"; else -> "Title backdrops" } },
                    { st.bgMode = cycle(listOf("carousel", "image", "video", "aerial"), st.bgMode, it) }),
                Act("Picture from a link", st.bgImage.ifBlank { "https://… (jpg, png, webp)" }, "") {
                    dialog = TextEntry("Picture link", st.bgImage.takeIf { it.startsWith("http") }.orEmpty(), "https://…") { v -> if (v.isNotBlank()) { st.bgImage = v.trim(); st.bgMode = "image" } }
                },
                Act("Picture from the folder", "", "") { pick(false) },
                Act("Video from a link", st.bgVideo.ifBlank { "https://… (mp4, webm, mkv); plays muted, on a loop" }, "") {
                    dialog = TextEntry("Video link", st.bgVideo.takeIf { it.startsWith("http") }.orEmpty(), "https://…") { v -> if (v.isNotBlank()) { st.bgVideo = v.trim(); st.bgMode = "video" } }
                },
                Act("Video from the folder", "", "") { pick(true) },
                Note("Folder for your pictures and videos: $bgFolder (copy with adb push, a file manager or a USB stick). The glass shows the picture; with a video it shows one frame of it."),
                Toggle("Show clock", "", { st.showClock }, { st.showClock = it }),
                Toggle("24-hour clock", "", { st.clock24h }, { st.clock24h = it }),
                Toggle("Show date", "Under the clock, like Apple TV", { st.showDate }, { st.showDate = it }),
                Adjust("Clock position", "", { if (st.clockPos == "right") "Right, by Settings" else "Left, like Apple TV" },
                    { st.clockPos = cycle(listOf("left", "right"), st.clockPos, it) }),
                Adjust("Weather", "Top bar, next to the settings button", {
                    when (st.weatherMode) { "off" -> "Off"; "temp" -> "Temperature"; "condition" -> "Temperature + sky"; "city" -> "Temperature + city"; else -> "Everything" }
                }, { st.weatherMode = cycle(listOf("all", "condition", "city", "temp", "off"), st.weatherMode, it); app.weather.reload() }),
                Act("Weather city", st.weatherCity + weatherFound.takeIf { it.isNotBlank() }?.let { "  →  $it" }.orEmpty(), "Change") {
                    dialog = TextEntry("Weather city", st.weatherCity, "Baghdad") { st.weatherCity = it; app.weather.reload() }
                },
                Toggle("Fahrenheit", "", { st.weatherF }, { st.weatherF = it }),
                Toggle("Auto-hide clock and settings button", "Hidden while you browse; they appear when you go up to the settings button", { st.topAutoHide }, { st.topAutoHide = it }),
                Adjust("Shown at start for", "Then hidden until you go up to them", { "${st.topHideSec} s" }, { st.topHideSec += it }),
                Adjust("Corner radius", "Dock and app tiles (0 = square)", { "${st.cornerRadius}" }, { st.cornerRadius += it * 2 }),
                Adjust("Glass blur", "0 turns blur off", { "${st.blurRadius}" }, { st.blurRadius += it * 4 }),
                Adjust("Glass refraction", "How much the rim bends what is behind it (0 = flat)", { "${st.refraction}" }, { st.refraction += it * 2 }),
                Adjust("Glass brightness", "", { "${st.glassTint}" }, { st.glassTint += it * 2 }),
                Toggle("Colour on focus", "Like tvOS: icons stay white, the selected one shows its colours on a light plate", { st.focusColor }, { st.focusColor = it }),
                Adjust("Selected icon plate", "With Colour on focus", { PLATE_NAMES[st.focusPlate] ?: "Dark" },
                    { st.focusPlate = cycle(PLATES, st.focusPlate, it) }),
                Toggle("Bounce at the edges", "Pressing past the first or last icon nudges it, like tvOS", { st.edgeBounce }, { st.edgeBounce = it }),
                Toggle("Lift selected icon", "The selected icon rises a little, glass and all", { st.tileLift }, { st.tileLift = it }),
                Adjust("Icon zoom on focus", "", { when (st.tileZoom) { "icon" -> "Inside the glass (the icon grows)"; "off" -> "None"; else -> "Whole tile grows" } },
                    { st.tileZoom = cycle(listOf("tile", "icon", "off"), st.tileZoom, it) }),
                Adjust("Glow", "A very soft light around the selected app or poster (also in the Library)", { when (st.glow) { "color" -> "In its own colour"; "white" -> "White"; else -> "Off" } },
                    { st.glow = cycle(listOf("off", "color", "white"), st.glow, it) }),
                Adjust("  · Glow size", "How far and how strong it is", { pct(st.glowSize) }, { st.glowSize = ((st.glowSize * 10).roundToInt() + it) / 10f }),
                Toggle("Show phone apps too", "Apps without a TV launcher entry", { st.showMobileApps }, { st.showMobileApps = it; app.apps.reload() }),
            )
        },
        Category("Dock & icons") {
            val imported = st.dockOrder.size // read for recomposition on reorder
            @Suppress("UNUSED_VARIABLE") val unused = imported + allApps.size
            buildList {
                add(Adjust("Icon pack", "Projectivy / Blueprint / Nova packs installed on this TV",
                    { if (st.iconPack.isEmpty()) "None (original icons)" else (packLabels[st.iconPack] ?: st.iconPack) },
                    { st.iconPack = cycle(packList, st.iconPack, it) }))
                add(Toggle("Show Apps folder", "The folder with all your apps, in the dock. Hidden apps stay listed below", { st.showFolder }, { st.showFolder = it }))
                add(Adjust("Dock size", "Smaller makes the dock and its icons narrower", { pct(st.dockScale) }, { st.dockScale = ((st.dockScale * 20).roundToInt() + it) / 20f }))
                add(Toggle("Dock fits its apps", "With fewer apps than a full row, the dock shrinks around them", { st.dockFit }, { st.dockFit = it }))
                add(Toggle("Hide the dock", "It stays below the screen until you go down to it (the dots go down too)", { st.dockHide }, { st.dockHide = it }))
                add(Adjust("Dock position", "With your own picture, video or screensaver background",
                    { when (st.dockPos) { "center" -> "Middle"; "left" -> "Left (column)"; else -> "Bottom" } },
                    { st.dockPos = cycle(listOf("bottom", "center", "left"), st.dockPos, it) }))
                add(Toggle("Down from the dock opens the Library", "From the dock's last row", { st.libraryDown }, { st.libraryDown = it }))
                add(Adjust("Dock background", "Behind the apps row and the Apps folder", { styleName(st.dockStyle) },
                    { st.dockStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.dockStyle, it); st.theme = "" }))
                add(Adjust("Settings background", "Behind settings and the icon picker", { styleName(st.panelStyle) },
                    { st.panelStyle = cycle(listOf("liquid", "clear", "amoled"), st.panelStyle, it) }))
                add(Adjust("Icon style", "How the plate behind every icon looks", {
                    styleName(st.tileStyle)
                }, { st.tileStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.tileStyle, it); st.theme = "" }))
                add(Note("Change one icon: on the home screen hold OK on the app → Change icon. Pick from an icon pack, the Apple TV icons, a photo on the TV, a link to a PNG, or send one from your phone (Manage from phone)."))
                add(Note("Icon packs: install any Android TV / Projectivy icon pack from Google Play, then choose it above. Every app takes the pack's icon; you can still change single apps."))
                add(Act("Find icon packs on Google Play", "Projectivy / Android TV icon packs", "") { openPlay(ctx, null, "projectivy icon pack") })
                add(Note("Many icons at once: copy <package>.png files (e.g. com.netflix.ninja.png) into the icon folder below, then Import."))
                add(Note("On the home screen, hold OK on an app for App info, Move, Change icon and Hide."))
                add(Note("Here: OK picks an app up, ◀ ▶ moves it, OK puts it down. Hold OK (or Menu) for its options."))
                add(Act("Import icons from the icon folder", "", "") {
                    val n = app.icons.importDropFolder(app.apps.apps.value.map { it.pkg })
                    toast(ctx, if (n > 0) "Imported $n icons" else "No new icons. Folder: ${app.icons.dropFolder.absolutePath}")
                })
                add(Note("Folder for <package>.png files: ${app.icons.dropFolder.absolutePath}"))
                app.apps.ordered(includeHidden = true).forEach { add(AppItem(it)) }
            }
        },
        Category("Library") {
            listOf(
                Toggle("Ask before removing", "Hold OK on a title asks \"Remove?\" first", { st.libraryConfirm }, { st.libraryConfirm = it }),
                Toggle("Down from the dock opens the Library", "The page scrolls up into it, like Apple TV; Up from its top row goes back", { st.libraryDown }, { st.libraryDown = it }),
                Toggle("+ button on the carousel", "Between Play and Next: saves the title to your Library", { st.heroPlus }, { st.heroPlus = it }),
                Toggle("Trakt watchlist in the Library", "Needs Trakt (Continue Watching)", { st.libraryTrakt }, { st.libraryTrakt = it; scope.launch(Dispatchers.IO) { app.library.refreshWatchlist() } }),
                Toggle("Nuvio library", if (app.library.nuvio.connected) "From your Nuvio account" + (app.library.nuvio.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": Nuvio Legacy, the official Nuvio app and forks that sync with it"
                    else "Connect your Nuvio account below", { st.libraryNuvio }, { st.libraryNuvio = it; scope.launch(Dispatchers.IO) { app.library.refreshNuvio() } }),
                nuvioAccountRow(),
                Toggle("Save to the Nuvio library too", "The + button also saves the title to your Nuvio account (Legacy, official Nuvio and forks see it)", { st.libraryToNuvio }, { st.libraryToNuvio = it }),
                Toggle("Save to the Trakt watchlist too", "The + button also adds the title on Trakt", { st.libraryToTrakt }, { st.libraryToTrakt = it }),
                Toggle("Titles under posters", "In the Library", { st.libraryTitles }, { st.libraryTitles = it }),
                Adjust("Poster size", "Smaller posters leave more room at the sides", { pct(st.libraryPosterSize) },
                    { st.libraryPosterSize = ((st.libraryPosterSize * 10).roundToInt() + it) / 10f }),
                Adjust("Posters in a row", "", { "${st.libraryColumns}" }, { st.libraryColumns = st.libraryColumns + it }),
                Adjust("Space between posters", "Between posters and between rows", { pct(st.librarySpacing) },
                    { st.librarySpacing = ((st.librarySpacing * 10).roundToInt() + it) / 10f }),
                Toggle("Continue Watching in the Library", "At the top, with where each title came from", { st.libraryCw }, { st.libraryCw = it }),
                Adjust("  · Card", "", { if (st.libraryCwShape == "poster") "Poster" else "Backdrop" }, { st.libraryCwShape = if (st.libraryCwShape == "poster") "backdrop" else "poster" }),
                Adjust("  · Card size", "", { pct(st.libraryCwSize) }, { st.libraryCwSize = ((st.libraryCwSize * 10).roundToInt() + it) / 10f }),
                Toggle("  · Where it came from", "Nuvio, Trakt, Simkl or Stremio, with a dot in its colour", { st.libraryCwSource }, { st.libraryCwSource = it }),
                Note("In the Library: OK opens the title like the carousel. Hold OK (or Menu) to remove it."),
                link("Connections", "Sign in to Nuvio, Trakt, Simkl or Stremio for their lists here"),
                link("Continue Watching", "Where the Continue Watching row gets its titles"),
            )
        },
        Category("Menu & themes") {
            val themes = listOf("bow", "amoled", "liquid", "clear", "water_still", "water")
            listOf(
                Adjust("Theme", "One look for the dock, the icons and the menu. Black ones keep the dock clear (matte)",
                    { if (st.theme.isBlank()) "Custom" else themeName(st.theme) }, { st.applyTheme(cycle(themes, st.theme.ifBlank { "liquid" }, it)) }),
                Adjust("Menu", "The Apple TV-style menu at the settings button",
                    { when (st.menuMode) { "auto" -> "Opens when you go up to it"; "manual" -> "Opens with OK"; else -> "Off (OK opens Settings)" } },
                    { st.menuMode = cycle(listOf("auto", "manual", "off"), st.menuMode, it) }),
                Adjust("Menu look", "", { styleName(st.menuStyle) },
                    { st.menuStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.menuStyle, it); st.theme = "" }),
                Toggle("Clock in the menu", "", { st.menuClock }, { st.menuClock = it }),
                Toggle("Date in the menu", "", { st.menuDate }, { st.menuDate = it }),
                Toggle("Weather in the menu", "Temperature, conditions, high / low, humidity and wind (city in Clock & glass)", { st.menuWeather }, { st.menuWeather = it; app.weather.reload() }),
                Toggle("City in the menu", "", { st.menuCity }, { st.menuCity = it }),
                Toggle("Library in the menu", "", { st.menuLibrary }, { st.menuLibrary = it }),
                Toggle("Android settings in the menu", "", { st.menuAndroid }, { st.menuAndroid = it }),
                Toggle("Wi-Fi in the menu", "", { st.menuWifi }, { st.menuWifi = it }),
                Toggle("Themes in the menu", "", { st.menuThemes }, { st.menuThemes = it }),
                Toggle("Background in the menu", "Switch between title backdrops, your picture, your video and the screensaver", { st.menuBackground }, { st.menuBackground = it }),
                Adjust("Menu size", "", { pct(st.menuScale) }, { st.menuScale = ((st.menuScale * 20).roundToInt() + it) / 20f }),
                Act("Menu order", st.menuOrder.joinToString(" · ") { com.glasslauncher.tv.data.MENU_NAMES[it] ?: it }, "Change") {
                    dialog = ReorderDialog("Menu order", st.menuOrder.map { it to (com.glasslauncher.tv.data.MENU_NAMES[it] ?: it) }) { st.menuOrder = it }
                },
                Toggle("TV info in the menu", "Under the weather: IP, CPU, GPU, RAM, storage (CPU and GPU need TV access (ADB))", { st.menuSys }, { st.menuSys = it }),
                Toggle("  · IP address", "", { st.sysIp }, { st.sysIp = it }),
                Toggle("  · CPU", "Use and temperature", { st.sysCpu }, { st.sysCpu = it }),
                Toggle("  · GPU", "Use, or its memory where the TV doesn't tell the use", { st.sysGpu }, { st.sysGpu = it }),
                Toggle("  · RAM", "", { st.sysRam }, { st.sysRam = it }),
                Toggle("  · Storage", "", { st.sysStorage }, { st.sysStorage = it }),
                Toggle("Screensaver in the menu", "Start it, choose its videos and when it starts", { st.menuSaver }, { st.menuSaver = it }),
                Toggle("Camera in the menu", if (st.cameras.isEmpty()) "Add a camera in Connections → Cameras" else "Live view in the menu, like Apple TV", { st.menuCamera }, { st.menuCamera = it }),
                link("Library", "Its rows, accounts and Continue Watching there"),
            )
        },
        Category("Catalogs") {
            buildList {
                add(Act("Add add-ons from my phone (QR)", "Scan the code, paste Nuvio manifest links and your TMDB token", "") { phoneDialog = true })
                add(tmdbRow())
                add(Act("Connect to Nuvio", "Open Nuvio > Add-ons > Manage from phone first", "") { io { app.catalogs.connectLocal() } })
                add(Toggle("Add-ons from my Nuvio account", if (app.library.nuvio.connected) "The add-ons on your Nuvio account (${app.library.nuvio.email.ifBlank { "signed in" }}) too. One that's already here isn't added twice"
                    else "Connect your Nuvio account first (the row below)", { st.catalogsNuvio }, { on -> io { app.catalogs.useAccount(on) } }))
                add(nuvioAccountRow())
                add(Act("Add add-on link", "Paste a manifest link", "") {
                    dialog = TextEntry("Add-on link", "", "https://…/manifest.json or stremio://…") { u -> io { app.catalogs.addAddon(u) } }
                })
                add(Act("Refresh catalogs", status, "") { io { app.catalogs.refresh(); app.catalogs.status.value } })
                add(Act("Select all catalogs", "", "") { app.catalogs.selectAll(true); io { app.catalogs.refresh(); "Done" } })
                add(Act("Select none", "", "") { app.catalogs.selectAll(false); io { app.catalogs.refresh(); "Done" } })
                addons.sorted().forEach { u ->
                    add(Act("Add-on · ${app.catalogs.addonName(u)}", hostOf(u), "Remove") {
                        dialog = MenuDialog("Remove ${app.catalogs.addonName(u)}?", listOf("Remove add-on" to { io { app.catalogs.removeAddon(u) } }))
                    })
                }
                accountAddons.size.let { _ -> app.catalogs.accountOnly() }.sorted().forEach { u ->
                    add(Act("Add-on · ${app.catalogs.addonName(u)}", "From your Nuvio account · " + hostOf(u), "") { toast(ctx, "Add or remove it in Nuvio; it updates here on the next refresh") })
                }
                if (catalogs.isEmpty()) add(Note("No catalogs yet. Connect to Nuvio or add an add-on link."))
                catalogs.forEach { c ->
                    add(Toggle(c.name, "${c.addonName} · ${c.type}", { c.key in selected }, {
                        app.catalogs.toggleCatalog(c.key)
                    }))
                }
            }
        },
        Category("Continue Watching") {
            val cwList by app.cw.entries.collectAsState()
            val cwStatusCw by app.cw.status.collectAsState()
            buildList {
                add(Adjust("Show Continue Watching", "Where titles you are watching appear", {
                    when (st.continueMode) { "off" -> "Off"; "mixed" -> "Mixed with catalogs"; else -> "At the start" }
                }, { st.continueMode = cycle(listOf("first", "mixed", "off"), st.continueMode, it) }))
                if (st.continueMode == "first") add(Adjust("Slides at the start", "How many titles in progress open the carousel", {
                    if (st.cwLead <= 0) "All" else "${st.cwLead}"
                }, { st.cwLead = cycle(listOf(1, 2, 3, 4, 5, 0), st.cwLead, it) }))
                add(Toggle("Open on what I just watched", "Back from Nuvio, the carousel shows that title first", { st.continueJump }, { st.continueJump = it }))
                add(Toggle("Progress bar", "Shows Continue · S1 · E3 · 42% under the title", { st.cwProgress }, { st.cwProgress = it }))
                add(Note("Apps"))
                add(Toggle("Nuvio Legacy", if (app.cw.hasLogAccess()) "Reading what you watch in Nuvio Legacy" else "Allow USB debugging for Glassio when the TV asks", { st.cwLegacy }, { st.cwLegacy = it }))
                val trakt = app.cw.trakt
                val tStatus by app.cw.traktStatus.collectAsState()
                if (trakt.available) {
                    add(Toggle("Trakt", if (trakt.connected) "Signed in as ${trakt.user.ifBlank { "your account" }}. What you pause in any app that uses Trakt (official Nuvio, Nuvio Arc, Re-Shaped, Stremio…)" + (if (tStatus.isNotBlank()) " · $tStatus" else "")
                        else "Connect your account below to see what you paused in any app that uses Trakt", { st.cwTrakt }, { st.cwTrakt = it; app.cw.syncTraktNow() }))
                    if (!trakt.connected) add(Act("Connect Trakt account", traktCode?.let { "Enter $it at ${app.cw.traktUrl.removePrefix("https://")}" } ?: "Scan a QR code with your phone and enter a short code, no password on the TV", if (traktCode != null) "Waiting…" else "") {
                        val appCtx = ctx.applicationContext
                        app.cw.connectTrakt { msg -> toast(appCtx, msg) }
                        scope.launch {
                            // Wait for the code, then show it with a QR code of the sign-in page.
                            var n = 0
                            while (app.cw.traktCode.value == null && n++ < 50) kotlinx.coroutines.delay(100)
                            if (app.cw.traktCode.value != null) dialog = QrCodeDialog("Connect Trakt", app.cw.traktUrl, app.cw.traktCode,
                                "This closes by itself once you approve. The code works for about 10 minutes.")
                        }
                    })
                    else add(Act("Sign out of Trakt", trakt.user, "") { trakt.signOut(); st.cwTrakt = st.cwTrakt; toast(ctx, "Signed out of Trakt") })
                }
                val smC = app.cw.stremio
                if (smC.connected) add(Toggle("Stremio", "What you paused in Stremio" + (cwStatusCw["Stremio"]?.let { " · $it" } ?: ""), { st.cwStremio }, { st.cwStremio = it; app.cw.pollNow() }))
                else add(Act("Connect Stremio", "Scan a QR code with your phone and approve", "") { connectStremio() })
                val skC = app.cw.simkl
                if (skC.connected) add(Toggle("Simkl", "What you paused in any app that uses Simkl" + (cwStatusCw["Simkl"]?.let { " · $it" } ?: ""), { st.cwSimkl }, { st.cwSimkl = it; app.cw.pollNow() }))
                else add(Act("Connect Simkl", "Scan a QR code with your phone and approve", "") { connectSimkl() })
                if (app.library.nuvio.connected) add(Toggle("Nuvio account", "What you watch in Nuvio Legacy, the official app and forks" + (cwStatusCw["Nuvio"]?.let { " · $it" } ?: ""),
                    { st.cwNuvioAccount }, { st.cwNuvioAccount = it; app.cw.pollNow() }))
                add(Note("How it works: Nuvio Legacy writes what you play to the TV's log (title, episode, how far). Glassio reads that log through the TV's own debugging connection (ADB), so this needs ADB once: Settings → System → TV access (ADB). Nothing leaves the TV."))
                add(Note("The official Nuvio app, Nuvio Arc and Re-Shaped don't write that log: connect Trakt (and turn on Trakt in that Nuvio app) and what you pause there shows up here through Trakt."))
                add(Note("Other apps (YouTube, Netflix, Apple TV…) keep their Continue Watching private on Google TV; only the system launcher may read it."))
                add(Act("Clear the list", "${cwList.size} titles", "") { app.cw.clear() })
                cwList.forEach { e ->
                    add(Act(e.imdb + if (e.season > 0) "  S${e.season}E${e.episode}" else "", "${e.app} · ${e.percent}%", "Remove") { app.cw.remove(e.key) })
                }
            }
        },
        Category("Connections") {
            val cwStatus by app.cw.status.collectAsState()
            buildList {
                add(Note("Nuvio"))
                add(nuvioAccountRow())
                if (app.library.nuvio.connected) {
                    add(Toggle("Continue Watching from the Nuvio account", "What you watch in Nuvio Legacy, the official app and forks" + (cwStatus["Nuvio"]?.let { " · $it" } ?: ""),
                        { st.cwNuvioAccount }, { st.cwNuvioAccount = it; app.cw.pollNow() }))
                    add(Toggle("Catalogs from the Nuvio account's add-ons", "One that's already here isn't added twice", { st.catalogsNuvio }, { on -> io { app.catalogs.useAccount(on, "nuvio") } }))
                }
                add(Adjust("Open titles in", "Auto uses the first installed: Nuvio Legacy, Nuvio (official), Nuvio Arc, Nuvio Re-Shaped", {
                    val n = NuvioLaunch.NAMES[st.nuvioTarget] ?: "Auto"
                    val r = NuvioLaunch.resolve(ctx, st)
                    when {
                        st.nuvioTarget == "auto" -> "Auto · " + (if (NuvioLaunch.TARGETS.values.any { NuvioLaunch.installed(ctx, it) }) NuvioLaunch.NAMES[r] else "none installed")
                        !NuvioLaunch.installed(ctx, NuvioLaunch.TARGETS.getValue(r)) -> "$n (not installed)"
                        else -> n
                    }
                }, { st.nuvioTarget = cycle(listOf("auto") + NuvioLaunch.TARGETS.keys, st.nuvioTarget, it) }))

                add(Note("Stremio"))
                val sm = app.cw.stremio
                if (!sm.connected) {
                    add(Act("Connect Stremio", "Scan a QR code with your phone and approve (signed in to Stremio there)", "") { connectStremio() })
                    add(Act("Sign in with email", "Your Stremio email and password (the password isn't kept)", "") {
                        dialog = TextEntry("Stremio email", "", "you@example.com") { mail ->
                            dialog = TextEntry("Stremio password", "", "Password") { pw ->
                                io { if (sm.login(mail, pw)) { st.cwStremio = true; app.cw.pollNow(); tvRev.intValue++; "Stremio connected" } else "Stremio: ${sm.lastError}" }
                            }
                        }
                    })
                } else {
                    add(Act("Stremio account", "Signed in" + sm.email.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(), "Sign out", ok = true) { sm.signOut(); tvRev.intValue++; toast(ctx, "Signed out of Stremio") })
                    add(Toggle("Continue Watching from Stremio", cwStatus["Stremio"] ?: "", { st.cwStremio }, { st.cwStremio = it; app.cw.pollNow() }))
                    add(Toggle("Catalogs from Stremio's add-ons", "One that's already here isn't added twice", { st.catalogsStremio }, { on -> io { app.catalogs.useAccount(on, "stremio") } }))
                }

                add(Note("Trakt"))
                val trakt = app.cw.trakt
                if (trakt.available) {
                    if (!trakt.connected) add(Act("Connect Trakt", "Scan a QR code with your phone and enter a short code", if (traktCode != null) "Waiting…" else "") {
                        val appCtx = ctx.applicationContext
                        app.cw.connectTrakt { msg -> toast(appCtx, msg) }
                        scope.launch {
                            var n = 0
                            while (app.cw.traktCode.value == null && n++ < 50) kotlinx.coroutines.delay(100)
                            if (app.cw.traktCode.value != null) dialog = QrCodeDialog("Connect Trakt", app.cw.traktUrl, app.cw.traktCode, "This closes by itself once you approve.")
                        }
                    })
                    else {
                        add(Act("Trakt account", "Signed in as ${trakt.user.ifBlank { "your account" }}", "Sign out", ok = true) { trakt.signOut(); st.cwTrakt = st.cwTrakt; toast(ctx, "Signed out of Trakt") })
                        add(Toggle("Continue Watching from Trakt", "", { st.cwTrakt }, { st.cwTrakt = it; app.cw.syncTraktNow() }))
                        add(Toggle("Trakt watchlist in the Library", "", { st.libraryTrakt }, { st.libraryTrakt = it; scope.launch(Dispatchers.IO) { app.library.refreshWatchlist() } }))
                    }
                } else add(Note("This build has no Trakt app id."))

                add(Note("Simkl"))
                val sk = app.cw.simkl
                if (!sk.connected) add(Act("Connect Simkl", "Scan a QR code with your phone and approve" + (if (!sk.available) " (first its app id, from the phone)" else ""), "") { connectSimkl() })
                else {
                    add(Act("Simkl account", "Signed in" + sk.user.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(), "Sign out", ok = true) { sk.signOut(); tvRev.intValue++; toast(ctx, "Signed out of Simkl") })
                    add(Toggle("Continue Watching from Simkl", cwStatus["Simkl"] ?: "", { st.cwSimkl }, { st.cwSimkl = it; app.cw.pollNow() }))
                    add(Toggle("Simkl \"Plan to watch\" in the Library", "", { st.librarySimkl }, { st.librarySimkl = it; scope.launch(Dispatchers.IO) { app.library.refreshSimkl() } }))
                    add(Toggle("+ saves to Simkl too", "", { st.libraryToSimkl }, { st.libraryToSimkl = it }))
                }

                add(Note("TMDB"))
                add(tmdbRow())

                add(Note("Home Assistant"))
                add(Act("Home Assistant webhook", "Full webhook URL from your Home Assistant", if (st.haWebhook.isBlank()) "Not set" else "Set",
                    ok = if (st.haWebhook.startsWith("http")) true else null) {
                    dialog = TextEntry("Home Assistant webhook", st.haWebhook, "http://homeassistant.local:8123/api/webhook/…") { st.haWebhook = it }
                })
                add(Toggle("Tell Home Assistant when a title opens", "Sends title, type and id as JSON", { st.haOnLaunch }, { st.haOnLaunch = it }))
                add(Act("Send a test event", "") { io { NuvioLaunch.testWebhook(st, app.http) } })

                add(Note("Cameras"))
                add(Act("Find cameras", "Searches your network (ONVIF: Hikvision, Dahua, Reolink, Tapo, Amcrest, Axis…)", "") {
                    io {
                        val found = com.glasslauncher.tv.data.Onvif.discover()
                        if (found.isEmpty()) return@io "No cameras answered. Add one with its RTSP link below"
                        dialog = MenuDialog("Cameras found", found.map { f -> "${f.name} · ${f.ip}" to {
                            dialog = TextEntry("Login for ${f.name}", "admin:", "user:password (the camera's own login)") { login ->
                                io {
                                    val uri = com.glasslauncher.tv.data.Onvif.streamUri(f, login.substringBefore(':'), login.substringAfter(':', ""))
                                    st.cameras = (st.cameras + Cam(f.name, uri).line()).distinct(); tvRev.intValue++
                                    "Added ${f.name}"
                                }
                            }
                        } })
                        "${found.size} found"
                    }
                })
                add(Act("Add camera link", "rtsp://user:password@ip:554/… , an HLS (.m3u8) or video link", "") {
                    dialog = TextEntry("Camera link", "rtsp://", "rtsp://user:password@camera-ip:554/stream1") { u ->
                        if (!u.contains("://")) { toast(ctx, "That isn't a link"); return@TextEntry }
                        dialog = TextEntry("Camera name", "", "Door, Garden…") { n -> st.cameras = (st.cameras + Cam(n.ifBlank { "Camera" }, u.trim()).line()).distinct(); tvRev.intValue++ }
                    }
                })
                st.cameras.mapNotNull { Cam.parse(it) }.forEach { c ->
                    add(Act(c.name, runCatching { java.net.URI(c.url).host }.getOrNull() ?: "", "Remove") { st.cameras = st.cameras.filterNot { it == c.line() || Cam.parse(it)?.url == c.url } })
                }
                add(Toggle("Camera in the menu", "", { st.menuCamera }, { st.menuCamera = it }))
            }
        },
        Category("Screensaver") {
            buildList {
                // The TV's own screensaver, switched through the TV's debugging connection (ADB).
                tvRev.intValue
                val cr = ctx.contentResolver
                val comp = runCatching { AndroidSettings.Secure.getString(cr, "screensaver_components") }.getOrNull().orEmpty()
                val tvEnabled = runCatching { AndroidSettings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 }.getOrDefault(true)
                val glassDream = "${ctx.packageName}/com.glasslauncher.tv.saver.GlassDreamService"
                val ours = tvEnabled && comp.startsWith(ctx.packageName)
                val tvAfter = runCatching { AndroidSettings.System.getInt(cr, "screen_off_timeout") }.getOrDefault(0)
                fun tv(cmd: String, done: String) = io {
                    val r = com.glasslauncher.tv.data.LocalShell.run(ctx, cmd)
                    tvRev.intValue++
                    if (r.isSuccess) done else "Allow USB debugging for Glassio when the TV asks (Always allow), then try again"
                }
                add(Toggle("Glassio is the TV screensaver (ADB)", if (ours) "On: it starts in every app when the TV is idle, not only on the home screen"
                    else if (tvEnabled && comp.isNotBlank()) "Now the TV uses ${comp.substringBefore('/')}" else "Off",
                    { ours }, { on ->
                        // The TV's "undim detector" kept the screen on for 10 minutes after a couple of quick key presses,
                        // so the screensaver often didn't start at its time: switched off here.
                        // With the screensaver time at (or past) the TV's sleep time the TV just went to sleep: give it 5 minutes then.
                        if (on) tv("settings put secure screensaver_components $glassDream && settings put secure screensaver_enabled 1 && settings put secure screensaver_activate_on_sleep 1; " +
                            "t=\$(settings get system screen_off_timeout); sl=\$(settings get secure sleep_timeout); case \$sl in ''|null|-1|0) sl=1800000;; esac; " +
                            "if [ \"\$t\" -ge \"\$sl\" ] 2>/dev/null || [ \"\$t\" -ge 1800000 ] 2>/dev/null; then settings put system screen_off_timeout 300000; fi; " +
                            "device_config put attention_manager_service keep_screen_on_enabled false 2>/dev/null; true",
                            "Glassio is now the TV screensaver")
                        // Off: with no screensaver the TV goes to sleep at that idle time, so the screen then stays on
                        // until the TV's own sleep time (Energy settings, sleep_timeout) instead.
                        else tv("settings put secure screensaver_enabled 0 && s=\$(settings get secure sleep_timeout) && case \$s in ''|null|-1|0) s=1800000;; esac && settings put system screen_off_timeout \$s",
                            "TV screensaver off (the TV now sleeps at its own sleep time)")
                    }))
                add(Adjust("TV screensaver starts after (ADB)", "The TV's own idle time, in any app", {
                    when { tvAfter <= 0 -> "Unknown"; tvAfter < 60_000 -> "${tvAfter / 1000} s"; tvAfter >= 3_600_000 -> "${tvAfter / 3_600_000} h"; else -> "${tvAfter / 60_000} min" + (if (tvAfter % 60_000 != 0) " ${tvAfter % 60_000 / 1000} s" else "") }
                }, { d ->
                    val opts = listOf(20_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000)
                    val next = cycle(opts, opts.minByOrNull { kotlin.math.abs(it - tvAfter) } ?: 60_000, if (tvAfter in opts) d else 0)
                    tv("settings put system screen_off_timeout $next && settings put secure screensaver_activate_on_sleep 1; device_config put attention_manager_service keep_screen_on_enabled false 2>/dev/null; true", "The TV screensaver now starts after " + (if (next < 60_000) "${next / 1000} s" else "${next / 60_000} min"))
                }))
                add(Adjust("Start after (on the home screen)", if (ours) "Not needed while Glassio is the TV screensaver" else "Idle time on the launcher", { st.idleSeconds.let { v -> if (v == 0) "Off" else if (v < 60) "$v s" else "${v / 60} min" } }, {
                    st.idleSeconds = cycle(listOf(0, 10, 30, 60, 120, 180, 300, 600, 900, 1800), st.idleSeconds, it)
                }))
                add(Adjust("Type", "What plays", {
                    when (st.saverMode) {
                        "aerial" -> "Aerial videos"
                        "system" -> "The TV's screensaver app (" + (comp.substringBefore('/').substringAfterLast('.').ifBlank { "none" }) + ")"
                        else -> "Posters with logos"
                    }
                }, { st.saverMode = cycle(listOf("aerial", "slideshow", "system"), st.saverMode, it) }))
                add(Note("Videos (aerial-videos.netlify.app)"))
                AerialSource.SOURCES.forEach { src ->
                    add(Toggle(src.name, "", { src.key in st.saverSources }, { on -> st.saverSources = if (on) st.saverSources + src.key else st.saverSources - src.key; st.saverPick = "" }))
                }
                add(Toggle("My videos", "Your links below and the videos in the backgrounds folder", { st.saverMine }, { st.saverMine = it; st.saverPick = "" }))
                add(Adjust("Quality", "Videos without it play the nearest one", { AerialSource.QUALITIES.firstOrNull { it.first == st.saverQuality }?.second ?: st.saverQuality },
                    { st.saverQuality = cycle(AerialSource.QUALITIES.map { it.first }, st.saverQuality, it) }))
                add(Toggle("Shuffle", "Off: in the collections' order", { st.saverShuffle }, { st.saverShuffle = it }))
                add(Act("Play one video only", if (st.saverPick.isBlank()) "All of them" else "One video", if (st.saverPick.isBlank()) "" else "Change") {
                    io {
                        val vids = AerialSource.all(app)
                        if (vids.isEmpty()) return@io "Turn on a collection first"
                        fun items() = listOf(ThumbItem("All of them", "Every video that's on", null, checked = st.saverPick.isBlank()) { st.saverPick = "" }) +
                            vids.map { v -> ThumbItem(v.title.ifBlank { videoName(v.id) }, AerialSource.SOURCES.firstOrNull { it.key == v.source }?.name ?: "My videos",
                                v.preview.ifBlank { null }?.let { p -> if (p.startsWith("/")) java.io.File(p) else p } ?: thumbOf(v.id),
                                checked = v.id == st.saverPick) { st.saverPick = v.id } }
                        dialog = ThumbDialog("One video", items())
                        fillThumbs(vids.map { it.id }, "One video") { items() }
                        "${vids.size} videos"
                    }
                })
                add(Toggle("Fade between videos", "", { st.saverFade }, { st.saverFade = it }))
                add(Toggle("Keep videos on the TV", "Every video (Apple, Amazon or your link) is saved as it plays and starts at once next time" +
                    " · ${com.glasslauncher.tv.data.SystemInfo.gb(com.glasslauncher.tv.saver.VideoCache.used(ctx))} used", { st.saverCache }, { st.saverCache = it }))
                add(Adjust("Space for saved videos", "Applies after Glassio restarts", { "${st.saverCacheGb} GB" }, { st.saverCacheGb = cycle(listOf(1, 2, 4, 8, 16, 32), st.saverCacheGb, it) }))
                add(Act("Delete saved videos", "", "") { com.glasslauncher.tv.saver.VideoCache.clear(ctx); toast(ctx, "Deleted") })
                add(Note("On screen"))
                add(Adjust("Style", "", { if (st.saverStyle == "netflix") "One by one, top left (like Netflix)" else "Apple" }, { st.saverStyle = cycle(listOf("apple", "netflix"), st.saverStyle, it) }))
                if (st.saverStyle == "netflix") {
                    add(Toggle("  · Hide when complete", "The lines go away 4 s after the last one comes in", { st.saverNetflixHide }, { st.saverNetflixHide = it }))
                    add(Adjust("  · Bar", "The line before each one", { when (st.saverBar) { "white" -> "White"; "black" -> "Black"; "off" -> "None"; else -> "Red" } },
                        { st.saverBar = cycle(listOf("red", "white", "black", "off"), st.saverBar, it) }))
                }
                add(Note("While it plays: ◀ ▶ change the video, OK shows or hides this info, any other key ends it."))
                add(Toggle("Clock", "", { st.saverClock }, { st.saverClock = it }))
                add(Toggle("Date", "", { st.saverDate }, { st.saverDate = it }))
                add(Toggle("Weather", "", { st.saverWeather }, { st.saverWeather = it; app.weather.reload() }))
                add(Toggle("Place", "Where the video was filmed", { st.saverPlace }, { st.saverPlace = it }))
                add(Toggle("TV info", "RAM, CPU, GPU, storage, IP (the ones on in Menu, Library & themes)", { st.saverSys }, { st.saverSys = it }))
                add(Adjust("Position", "Apple style", { when (st.saverPos) { "tl" -> "Top left"; "tr" -> "Top right"; "br" -> "Bottom right"; "bl" -> "Bottom left"; else -> "Moves with every video" } },
                    { st.saverPos = cycle(listOf("move", "bl", "br", "tl", "tr"), st.saverPos, it) }))
                add(Adjust("Size", "", { pct(st.saverSize) }, { st.saverSize = ((st.saverSize * 10).roundToInt() + it) / 10f }))
                add(Adjust("Shows for", "After each new video", { if (st.saverShowSec == 0) "Always" else "${st.saverShowSec} s" }, { st.saverShowSec = cycle(listOf(0, 10, 20, 30, 60), st.saverShowSec, it) }))
                add(Note("More"))
                add(Toggle("Back on the home screen starts it", "Like Apple TV: press Back on the home screen", { st.backSaver }, { st.backSaver = it }))
                add(Act("Add video link", "A direct .mp4 / .mov link (Manage from phone can also save it to the TV)", "") {
                    dialog = TextEntry("Video link", "", "https://…/video.mp4") { u ->
                        if (u.startsWith("http")) st.aerialUrls = (st.aerialUrls + u.trim()).distinct() else toast(ctx, "The link must start with http")
                    }
                })
                add(Act("My videos", "Your links and the videos in the backgrounds folder, with a picture of each. OK plays only that one", "") {
                    io {
                        val mine = AerialSource.mine(app)
                        if (mine.isEmpty()) return@io "No videos yet: add a link below or upload one from your phone"
                        fun items() = mine.map { v ->
                            val thumb = if (v.preview.isNotBlank()) java.io.File(v.preview) else thumbOf(v.id)
                            ThumbItem(v.title.ifBlank { videoName(v.id) }, if (v.id.startsWith("http")) hostOf(v.id) else "On the TV", thumb, checked = v.id == st.saverPick) {
                                st.saverMine = true; st.saverPick = v.id
                            }
                        }
                        dialog = ThumbDialog("My videos", items())
                        fillThumbs(mine.map { it.id }, "My videos") { items() }
                        "${mine.size} videos"
                    }
                })
                st.aerialUrls.forEach { u ->
                    add(Act(videoName(u), hostOf(u), "Remove") { st.aerialUrls = st.aerialUrls - u })
                }
                add(Act("Preview now", "") { ctx.startActivity(Intent(ctx, ScreensaverActivity::class.java)) })
            }
        },
        Category("Manage from phone", Kind.Phone),
        Category("System") {
            val devOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
            val adbOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
            listOf(
                Toggle("Home button opens Glassio", "Needs the accessibility service below", { st.interceptHome }, { st.interceptHome = it }),
                Act("Turn on the accessibility service", "Find Glassio and switch it on", "") { openSystem(ctx, AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) },
                Act("Turn it on with ADB", if (homeKeyOn) "The accessibility service is on" else "When the TV doesn't let you (restricted setting / greyed out). Needs TV access (ADB), below", "") {
                    scope.launch(Dispatchers.IO) {
                        val svc = "${ctx.packageName}/com.glasslauncher.tv.HomeKeyService"
                        val cmd = "cur=\$(settings get secure enabled_accessibility_services); " +
                            "case \"\$cur\" in *$svc*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; " +
                            "*) settings put secure enabled_accessibility_services \"\$cur:$svc\" ;; esac; " +
                            "settings put secure accessibility_enabled 1; appops set ${ctx.packageName} ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; " +
                            // Lets Glassio switch the service back on by itself after every update.
                            "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; true"
                        val r = com.glasslauncher.tv.data.LocalShell.run(ctx, cmd)
                        homeKeyOn = isHomeKeyOn(ctx)
                        toast(ctx, if (r.isSuccess && homeKeyOn) "Accessibility service turned on" else if (r.isSuccess) "Done, but the TV didn't turn it on. Try the button above" else "Allow TV access (ADB) first: steps below")
                    }
                },
                Act("Choose default home app", "", "") { openSystem(ctx, AndroidSettings.ACTION_HOME_SETTINGS) },
                Toggle("Open Glassio when the TV starts", "Glassio opens on its own after the TV turns on. The right to do so is given over ADB (TV access, below)", { st.bootStart }, { on ->
                    st.bootStart = on
                    if (on) scope.launch(Dispatchers.IO) {
                        val r = com.glasslauncher.tv.data.LocalShell.run(ctx, "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow; " +
                            "cmd package set-home-activity ${ctx.packageName}/com.glasslauncher.tv.MainActivity >/dev/null 2>&1; true")
                        toast(ctx, if (r.isSuccess) "Glassio opens when the TV starts" else "Saved. Allow TV access (ADB) below so the TV lets Glassio open")
                    }
                }),
                Adjust("Hold Home", "What holding the Home button does, in any app (needs the Home button service above)", {
                    when (st.homeLong) { "recents" -> "Open apps (like Apple TV)"; "camera" -> "Camera in the corner, over any app"; "library" -> "Open the Library"; "menu" -> "Open the menu"; else -> "Nothing" }
                }, { st.homeLong = cycle(listOf("off", "recents", "camera", "library", "menu"), st.homeLong, it) }),
                Adjust("Language", "The menu, the settings sections, the setup and the main rows (the rest stays English for now). Reopen the screen to see it",
                    { com.glasslauncher.tv.data.I18n.LANGS[st.lang] ?: "English" }, { st.lang = cycle(com.glasslauncher.tv.data.I18n.LANGS.keys.toList(), st.lang, it) }),
                Act("Back up now", "Every setting, catalog, Library, Continue Watching, account and token, saved to Download/Glassio on the TV (also from the phone page). Keep it private", "Back up") {
                    scope.launch(Dispatchers.IO) { toast(ctx, runCatching { "Saved: " + com.glasslauncher.tv.data.Backup.saveToTv(ctx) }.getOrElse { "Backup failed: ${it.message}" }) }
                },
                Act("Restore backup", "The newest backup in Download/Glassio on the TV. Glassio restarts after", "Restore") {
                    scope.launch(Dispatchers.IO) {
                        val j = com.glasslauncher.tv.data.Backup.latestOnTv(ctx)
                        if (j == null) toast(ctx, "No backup on the TV yet (Download/Glassio)")
                        else runCatching { val n = com.glasslauncher.tv.data.Backup.restore(ctx, j); toast(ctx, "Restored ($n parts). Restarting…"); kotlinx.coroutines.delay(1200); com.glasslauncher.tv.data.Backup.restart(ctx) }
                            .onFailure { toast(ctx, "Restore failed: ${it.message}") }
                    }
                },
                Act("Run setup again", "The welcome screen: restore, catalogs, accounts and theme", "") { st.setupDone = false; app.requestSetup.intValue++ },
                Note("TV access (ADB): Continue Watching from Nuvio Legacy, Glassio as the TV screensaver, TV info, open apps, starting with the TV and silent updates use the TV's own debugging connection. Turn it on once:"),
                Note("1. Developer options: open About below and press OK on \"Android TV OS build\" (or \"Build\") 7 times, until it says you are a developer."),
                Note("2. Open Developer options below and turn on USB debugging (on some TVs also \"Network debugging\" / \"ADB over network\")."),
                Note("3. Press \"Allow Glassio\" below. The TV asks \"Allow USB debugging?\": tick Always allow from this computer, then OK. It is asked only once."),
                Act("1 · Open About (enable Developer options)", if (devOn) "Developer options are on" else "Press OK on the build number 7 times", "") { openSystem(ctx, AndroidSettings.ACTION_DEVICE_INFO_SETTINGS) },
                Act("2 · Open Developer options", if (adbOn) "USB debugging is on" else "Turn on USB debugging", "") { openSystem(ctx, AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                Act("3 · Allow Glassio", when (adbAccess) { true -> "Allowed: Glassio can use ADB"; false -> "Not allowed yet: press OK, then Always allow on the TV"; else -> "Press OK to check" }, "") {
                    scope.launch(Dispatchers.IO) {
                        val ok = com.glasslauncher.tv.data.LocalShell.run(ctx, "echo ok").isSuccess
                        adbAccess = ok
                        toast(ctx, if (ok) "Glassio can use ADB" else if (!adbOn) "Turn on USB debugging first (step 2)" else "Choose Always allow on the TV, then press OK here again")
                    }
                },
            )
        },
        Category("About", Kind.About),
    ).sortedBy { CATEGORY_ORDER.indexOf(it.title).let { i -> if (i < 0) 99 else i } }
    catTitles[0] = categories.map { it.title }

    // Catalog choices made here are applied when the screen closes.
    DisposableEffect(Unit) { onDispose { app.scope.launch(Dispatchers.IO) { runCatching { app.catalogs.refresh() } } } }
    BackHandler { if (rightFocused) runCatching { catFocus[cat].requestFocus() } else onClose() }
    LaunchedEffect(Unit) { delay150(); runCatching { firstCat.requestFocus() } }

    // Each page keeps its scroll and the row you were on, outside the glass panel: changing "Settings background"
    // swaps the panel, which rebuilt the page at the top with nothing selected (the remote then moved whatever was
    // behind Settings).
    val listStates = remember { HashMap<Int, androidx.compose.foundation.lazy.LazyListState>() }
    val rowFocus = remember { HashMap<Long, FocusRequester>() }
    val lastRow = remember { IntArray(32) { -1 } }
    fun rowReq(c: Int, i: Int) = rowFocus.getOrPut(c * 10_000L + i) { FocusRequester() }
    var anyFocus by remember { mutableStateOf(true) }
    // Safety net: if nothing in Settings is selected (a page was rebuilt, a row went away), select the row you were
    // on, or this page's category.
    LaunchedEffect(anyFocus, dialog, phoneDialog) {
        if (anyFocus || dialog != null || phoneDialog) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val r = lastRow[shownCat]
        val ok = r >= 0 && runCatching {
            listStates[shownCat]?.let { st0 -> if (r !in st0.layoutInfo.visibleItemsInfo.map { it.index }) st0.scrollToItem(r) }
            kotlinx.coroutines.delay(16); rowReq(shownCat, r).requestFocus()
        }.isSuccess
        if (!ok || !anyFocus) runCatching { catFocus[cat].requestFocus() }
    }
    Box(Modifier.fillMaxSize().onFocusChanged { anyFocus = it.hasFocus }.background(Color.Black.copy(alpha = 0.55f)).padding((28 * s).dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
            GlassPanel(Modifier.width((230 * s).dp).fillMaxHeight(), (30 * s).dp, style = st.panelStyle, darken = 0.42f) {
                // Scrolls when the list is taller than the screen (the last entry, About, was cut off).
                Column(Modifier.padding(horizontal = (14 * s).dp, vertical = (12 * s).dp).verticalScroll(rememberScrollState())) {
                    Text("Settings", color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = (8 * s).dp, bottom = (4 * s).dp))
                    categories.forEachIndexed { i, c ->
                        var f by remember { mutableStateOf(false) }
                        Text(
                            com.glasslauncher.tv.data.I18n.tr(c.title), color = Color.White, fontSize = (13.5f * s).sp,
                            fontWeight = if (cat == i) FontWeight.SemiBold else FontWeight.Normal,
                            // Every section fits without scrolling: the list used to jump a little at a time as you went
                            // down past Dock & icons.
                            modifier = Modifier.fillMaxWidth().padding(vertical = (1 * s).dp)
                                .clip(RoundedCornerShape((14 * s).dp))
                                .background(if (f) Color.White.copy(alpha = 0.30f) else if (cat == i) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                .focusRequester(catFocus[i])
                                .then(if (i == initialCat) Modifier.focusRequester(firstCat) else Modifier)
                                .onFocusChanged { f = it.isFocused; if (it.isFocused) cat = i }
                                .clickable { cat = i }
                                .padding(horizontal = (12 * s).dp, vertical = (5.5f * s).dp),
                        )
                    }
                }
            }
            GlassPanel(
                Modifier.weight(1f).fillMaxHeight().onFocusChanged { rightFocused = it.hasFocus }
                    // Left goes back to this page's own category; Up/Down at the ends stay in the page.
                    .focusProperties {
                        exit = { dir ->
                            when (dir) {
                                androidx.compose.ui.focus.FocusDirection.Left -> catFocus[cat]
                                androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down -> FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
                (30 * s).dp, style = st.panelStyle, darken = 0.42f,
            ) {
                val c = categories[shownCat]
                Column(Modifier.padding((18 * s).dp)) {
                    Text(com.glasslauncher.tv.data.I18n.tr(c.title), color = Color.White, fontSize = (20 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = (6 * s).dp, bottom = (8 * s).dp))
                    if (c.kind == Kind.Phone) PhonePane(app, s)
                    else if (c.kind == Kind.About) AboutPane(app, s, onApp = { e -> appMenu(app, e, ctx, scope) { dialog = it } })
                    else {
                        val rows = c.rows()
                        val sc = shownCat
                        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SettingsPivot) {
                        LazyColumn(Modifier.fillMaxSize(), state = listStates.getOrPut(sc) { androidx.compose.foundation.lazy.LazyListState() },
                            verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                            items(rows.size) { i ->
                                Box(Modifier.focusRequester(rowReq(sc, i)).onFocusChanged { if (it.hasFocus) lastRow[sc] = i }) {
                                    RowView(rows[i], s, app, onApp = { e -> appMenu(app, e, ctx, scope) { dialog = it } })
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
    DialogHost(dialog) { dialog = null }
    if (phoneDialog) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { phoneDialog = false }) {
            Box(
                Modifier.width((520 * s).dp).height((380 * s).dp).clip(RoundedCornerShape((18 * s).dp))
                    .background(Color(0xF0181820)).padding((18 * s).dp)
                    .focusable(),
            ) { PhonePane(app, s) }
        }
    }
}

private suspend fun delay150() = kotlinx.coroutines.delay(150)

private fun appMenu(app: GlassApp, e: AppEntry, ctx: Context, scope: kotlinx.coroutines.CoroutineScope, show: (DialogState) -> Unit) {
    val hidden = e.pkg in app.settings.hiddenApps
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    opts += (if (hidden) "Show in dock" else "Hide from dock") to { app.apps.toggleHidden(e.pkg) }
    opts += "Icon from a link" to {
        show(TextEntry("Icon link for ${e.label}", "", "https://…/icon.png") { u ->
            scope.launch(Dispatchers.IO) { toast(ctx, runCatching { app.icons.setFromUrl(e.pkg, u.trim()); "Icon updated" }.getOrElse { it.message ?: "Failed" }) }
        })
    }
    opts += "Icon from my phone (Manage from phone)" to { toast(ctx, "Open Settings > Manage from phone, scan the QR code, then pick ${e.label}") }
    if (app.icons.has(e.pkg)) opts += "Reset to the original icon" to { app.icons.reset(e.pkg) }
    opts += "Open app" to { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } }
    show(MenuDialog(e.label, opts))
}

@Composable
private fun RowView(row: SRow, s: Float, app: GlassApp, onApp: (AppEntry) -> Unit) {
    when (row) {
        is Note -> Text(row.text, color = Color.White.copy(alpha = 0.55f), fontSize = (11.5f * s).sp, modifier = Modifier.padding(horizontal = (8 * s).dp, vertical = (6 * s).dp))
        is Toggle -> Frame(s, onClick = { row.set(!row.get()) }) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            val on = row.get()
            Box(Modifier.width((38 * s).dp).height((22 * s).dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFF7B61FF) else Color.White.copy(alpha = 0.22f)).padding((3 * s).dp)) {
                Box(Modifier.size((16 * s).dp).clip(RoundedCornerShape(50)).background(Color.White).align(if (on) Alignment.CenterEnd else Alignment.CenterStart))
            }
        }
        is Adjust -> {
            var editing by remember { mutableStateOf(false) }
            Frame(s, editing = editing, onKey = { ev ->
                when {
                    !editing -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) row.step(if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) editing = false; true }
                    // Up/Down while editing: stay on the row
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) editing = false }, onClick = { editing = !editing }) { f ->
                Label(row.title, if (editing) "◀ ▶ to change · OK when done" else row.desc, s, Modifier.weight(1f))
                if (editing) Text("‹  ${row.value()}  ›", color = Color(0xFFB9ABFF), fontSize = (13.5f * s).sp, fontWeight = FontWeight.SemiBold)
                else Text(row.value(), color = Color.White.copy(alpha = if (f) 1f else 0.75f), fontSize = (13 * s).sp)
            }
        }
        is Act -> Frame(s, onClick = row.onClick) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            if (row.value.isNotEmpty()) Text(if (row.ok == true) "✓  " + row.value else row.value,
                color = when (row.ok) { true -> Color(0xFF34C759); false -> Color(0xFFFF6961); else -> Color.White.copy(alpha = 0.7f) },
                fontSize = (12.5f * s).sp, fontWeight = if (row.ok != null) FontWeight.SemiBold else null)
        }
        is AppItem -> {
            val e = row.entry
            val hidden = e.pkg in app.settings.hiddenApps
            val art by produceState<ImageBitmap?>(null, e.pkg) { value = app.apps.artwork(e)?.bmp }
            var moving by remember { mutableStateOf(false) }
            Frame(s, editing = moving, onKey = { ev ->
                when {
                    ev.key == Key.Menu -> { if (ev.type == KeyEventType.KeyDown) onApp(e); true }
                    !moving -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) app.apps.move(e.pkg, if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) moving = false; true }
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) moving = false }, onClick = { moving = !moving }, onLong = { onApp(e) }) { f ->
                Box(Modifier.size((34 * s).dp).clip(RoundedCornerShape((8 * s).dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                    art?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((3 * s).dp)) }
                }
                Spacer(Modifier.width((10 * s).dp))
                Label(e.label, e.pkg, s, Modifier.weight(1f))
                val flags = listOfNotNull(if (hidden) "Hidden" else null, if (app.icons.has(e.pkg)) "Custom icon" else null).joinToString(" · ")
                if (flags.isNotEmpty()) Text(flags, color = Color(0xFFB9ABFF), fontSize = (11.5f * s).sp)
                Text(if (moving) "  ◀ ▶ · OK" else "", color = Color(0xFFB9ABFF), fontSize = (12 * s).sp)
            }
        }
    }
}

@Composable
private fun Label(title: String, desc: String, s: Float, modifier: Modifier) {
    Column(modifier) {
        Text(com.glasslauncher.tv.data.I18n.tr(title), color = Color.White, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (desc.isNotBlank()) Text(desc, color = Color.White.copy(alpha = 0.5f), fontSize = (10.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Frame(
    s: Float,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    editing: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clip(RoundedCornerShape((14 * s).dp))
            .background(when {
                editing -> Color(0xFF7B61FF).copy(alpha = 0.34f)
                focused -> Color.White.copy(alpha = 0.26f)
                else -> Color.White.copy(alpha = 0.06f)
            })
            .tvPress(onLong = onLong, onKey = onKey, onClick = onClick)
            .padding(horizontal = (14 * s).dp, vertical = (9 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}

@Composable
private fun PhonePane(app: GlassApp, s: Float) {
    var url by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var qr by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    DisposableEffect(Unit) {
        val job = app.scope.launch(Dispatchers.IO) {
            val u = com.glasslauncher.tv.net.PhoneHost.start(app)
            if (u != null) {
                val bmp = Net.qr(u, 480).asImageBitmap()
                kotlinx.coroutines.withContext(Dispatchers.Main) { url = u; qr = bmp }
            } else kotlinx.coroutines.withContext(Dispatchers.Main) { failed = true }
        }
        onDispose { job.cancel(); com.glasslauncher.tv.net.PhoneHost.leave(app) }
    }
    Column(Modifier.fillMaxSize().padding(start = (6 * s).dp)) {
        Text(
            "Scan the code with your phone (same Wi-Fi) to add add-ons, set app icons, set or upload a background picture or video, and paste tokens with a real keyboard.",
            color = Color.White.copy(alpha = 0.7f), fontSize = (12.5f * s).sp,
        )
        Spacer(Modifier.height((14 * s).dp))
        val code = qr
        if (code != null) {
            Image(code, "QR code", Modifier.size((190 * s).dp).clip(RoundedCornerShape((14 * s).dp)))
            Spacer(Modifier.height((8 * s).dp))
            Text(url ?: "", color = Color.White.copy(alpha = 0.6f), fontSize = (11 * s).sp)
        } else if (failed) {
            Text("The TV is not on a network, so the phone page can't start.", color = Color(0xFFFF8F9B), fontSize = (13 * s).sp)
        }
        Spacer(Modifier.height((10 * s).dp))
        val st = app.settings
        RowView(Adjust("Keep the page on", "After you leave this screen. The code changes each time it starts again", {
            st.phoneMinutes.let { if (it == 0) "Stops when you leave" else if (it < 60) "$it min" else "${it / 60} h" }
        }, { st.phoneMinutes = cycle(listOf(0, 10, 30, 60, 180), st.phoneMinutes, it) }), s, app, onApp = {})
        Spacer(Modifier.height((6 * s).dp))
        Text("Everything is there: add-ons, backgrounds, icons, tokens, Stremio and Simkl, cameras (RTSP), backup and restore, and every setting.", color = Color.White.copy(alpha = 0.45f), fontSize = (11 * s).sp)
    }
}


/** Version, updates from GitHub, the project page as a QR code, credits and the last crash (if any). */
@Composable
private fun AboutPane(app: GlassApp, s: Float, onApp: (com.glasslauncher.tv.data.AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rel by app.updater.available.collectAsState()
    val status by app.updater.status.collectAsState()
    val qr = remember { com.glasslauncher.tv.net.Net.qr(com.glasslauncher.tv.data.Updater.REPO_URL, 420).asImageBitmap() }
    val crash = remember { java.io.File(ctx.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.lineSequence()?.take(2)?.joinToString(" · ").orEmpty() }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((18 * s).dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(qr, "GitHub", Modifier.size((150 * s).dp).clip(RoundedCornerShape((12 * s).dp)))
            Spacer(Modifier.height((6 * s).dp))
            Text("github.com/" + com.glasslauncher.tv.data.Updater.REPO, color = Color.White.copy(alpha = 0.75f), fontSize = (9.5f * s).sp)
            Text("Scan with your phone", color = Color.White.copy(alpha = 0.5f), fontSize = (9 * s).sp)
        }
        val rows = buildList<SRow> {
            add(Note("Glassio ${app.updater.current} · The most clean, comfortable and customisable launcher."))
            add(Act("Give Glassio a star on GitHub", "Scan the code with your phone and tap ★ Star. It helps others find it", "★") { toast(ctx, "Thank you! Scan the code on the left") })
            if (rel != null) {
                add(Act("Install update ${rel!!.version}", if (status.isNotBlank()) status else "Downloads from GitHub and restarts Glass", "Install") { app.updater.install() })
                rel!!.notes.lineSequence().map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                    .take(6).forEach { add(Note("• $it")) }
            } else add(Act("Check for updates", if (status.isNotBlank()) status else "Looks at the latest release on GitHub", "Check") {
                scope.launch(Dispatchers.IO) { toast(ctx, app.updater.check()) }
            })
            add(Note("A pill appears next to the clock when a new version is out."))
            add(Note("Made by Mohammad (mrh12) · Fonts: Inter (SIL OFL) · Weather: Open-Meteo · This product uses the TMDB API but is not endorsed or certified by TMDB."))
            if (crash.isNotBlank()) add(Act("Last crash", crash.take(120), "Clear") { java.io.File(ctx.filesDir, "last-crash.txt").delete(); toast(ctx, "Cleared") })
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            items(rows.size) { i -> RowView(rows[i], s, app, onApp = onApp) }
        }
    }
}

/** A video link's file name, readable ("Lofoten_4K.mp4" → "Lofoten 4K"). */
private fun videoName(u: String): String = runCatching {
    java.net.URLDecoder.decode(u.substringBefore('?').trimEnd('/').substringAfterLast('/'), "UTF-8").substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim()
}.getOrNull().orEmpty().ifBlank { "Video link" }

/** The settings sections in order: accounts and what they feed, then the look, then the screensaver and the TV. */
val CATEGORY_ORDER = listOf("Connections", "Catalogs", "Continue Watching", "Library", "Carousel", "Dock & icons", "Menu & themes",
    "Clock & glass", "Screensaver", "Performance", "Manage from phone", "System", "About")

/**
 * Settings pages scroll like a TV list: the selected row settles at the same height (a third of the way down)
 * and stays there. The default "just bring it into view" nudged the list up and down at its ends, so fast
 * presses near the bottom of Dock & icons made the page shake.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private object SettingsPivot : androidx.compose.foundation.gestures.BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = containerSize * 0.33f
        val d = if (size > containerSize) offset else offset - target
        return if (kotlin.math.abs(d) < 12f) 0f else d
    }
}
