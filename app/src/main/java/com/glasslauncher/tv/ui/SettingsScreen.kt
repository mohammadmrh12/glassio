package com.glasslauncher.tv.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
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
import com.glasslauncher.tv.MultiCamOverlay
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
private fun styleName(v: String) = when (v) { "water" -> "Dynamic Glass"; "water_still" -> "Still Glass"; "clear" -> "Clear (matte)"; "amoled" -> "AMOLED black"; "bow" -> "Black on white"; else -> "Frosted Glass" }
private fun themeName(v: String) = when (v) { "bow" -> "Black on white"; "amoled" -> "Black AMOLED"; "liquid" -> "Frosted Glass"; "clear" -> "Clear"; "water_still" -> "Still Glass"; "water" -> "Dynamic Glass"; else -> "Custom" }
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
    val syncStatus by app.sync.status.collectAsState()
    val nuvioUrl by app.library.nuvioUrl.collectAsState()
    // The QR dialog closes when this turns null (sign-in finished); the Nuvio code is in the QR itself.
    val nuvioShort = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    LaunchedEffect(nuvioUrl) { nuvioShort.value = nuvioUrl?.let { "" } }
    val tvRev = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // The token is checked once per token while Glassio runs (checked on every opening, Settings was built
    // again twice more as the answer came).
    var tmdbOk by remember { mutableStateOf(SettingsCache.tmdb[st.tmdbToken]) }
    var tmdbChecking by remember { mutableStateOf(false) }
    LaunchedEffect(st.tmdbToken) {
        val t = st.tmdbToken
        if (SettingsCache.tmdb.containsKey(t)) { tmdbOk = SettingsCache.tmdb[t]; return@LaunchedEffect }
        tmdbOk = null
        if (t.isBlank() || com.glasslauncher.tv.data.Tmdb.looksLikeApiKey(t)) return@LaunchedEffect
        tmdbChecking = true
        tmdbOk = kotlinx.coroutines.withContext(Dispatchers.IO) { com.glasslauncher.tv.data.Tmdb.verify(t) }
        if (tmdbOk != null) SettingsCache.tmdb[t] = tmdbOk
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
    // Icon packs are looked up off the main thread (two package queries on opening made Settings catch).
    LaunchedEffect(Unit) {
        if (SettingsCache.packs.value == null) SettingsCache.packs.value = kotlinx.coroutines.withContext(Dispatchers.IO) { runCatching { app.packs.installed() }.getOrDefault(emptyList()) }
    }
    val rightFocusedA = remember { booleanArrayOf(false) }
    val firstCat = remember { FocusRequester() }
    // One requester per category: leaving the right pane goes back to the category it belongs to,
    // instead of whichever category happens to sit next to the row (that switched the page).
    val catFocus = remember { List(24) { FocusRequester() } }

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


    // Made once: the pages read what they show when they are drawn (rebuilt with every change, the whole of
    // Settings was composed again on each one).
    val categories = remember { listOf(
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
            tvRev.intValue
            listOfNotNull(
                Toggle("Auto-play", "Slides change on their own", { st.heroAutoplay }, { st.heroAutoplay = it }),
                Adjust("Slide time", "Seconds before the next title", { "${st.heroIntervalSec} s" }, { st.heroIntervalSec += it }),
                Act("Shuffle button", "Press it on the home screen: the catalog titles come in a new random order (Continue Watching keeps its place)",
                    if (st.shuffleKey == 0) "Set" else "✓ " + keyName(st.shuffleKey), ok = if (st.shuffleKey != 0) true else null) {
                    dialog = KeyCaptureDialog("Shuffle button") { code -> st.shuffleKey = code; tvRev.intValue++; toast(ctx, "Shuffle button: ${keyName(code)}") }
                },
                if (st.shuffleKey != 0) Act("  · Clear the shuffle button", "", "") { st.shuffleKey = 0 } else null,
                Toggle("New titles first", "Titles a catalog has just added open the carousel for two days", { st.newFirst }, { st.newFirst = it }),
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
                Adjust("Dots size", "The dots bar (and the liquid glass drops)", { pct(st.dotsScale) }, { st.dotsScale = ((st.dotsScale * 10).roundToInt() + it) / 10f }),
                Toggle("Trailer button", "Next to Play (Apple TV buttons). Hold OK on Play has Trailer too", { st.trailerButton }, { st.trailerButton = it }),
                Adjust("Trailer size", "Fill the screen (no black bars), its own shape, or a window by the buttons (shaped like the trailer)", {
                    when (st.trailerSize) { "original" -> "Original"; "popup" -> "Window"; else -> "Cinematic (fill)" }
                }, { st.trailerSize = cycle(listOf("cinema", "original", "popup"), st.trailerSize, it) }),
                Adjust("Trailer window size", "", { pct(st.trailerPopupScale) }, { st.trailerPopupScale = ((st.trailerPopupScale * 10).roundToInt() + it) / 10f }),
                Toggle("Hide the dock during trailers", "The dock and the dots step aside while a trailer fills the page, and come back when it stops or ends (never with the window)",
                    { st.trailerHideDock }, { st.trailerHideDock = it }),
                Adjust("Trailer quality", "Automatic: H.264 up to 1080p (720p on small TVs). Full: the best this TV can play, up to 4K", {
                    if (st.trailerQuality == "full") "Full" else "Automatic"
                }, { st.trailerQuality = cycle(listOf("auto", "full"), st.trailerQuality, it) }),
                Toggle("Trailer sound", "", { !st.trailerMute }, { st.trailerMute = !it }),
                Toggle("Trailer subtitles", "", { st.trailerSubs }, { st.trailerSubs = it }),
                Adjust("Subtitle language", "", { java.util.Locale(st.trailerSubLang).getDisplayLanguage(java.util.Locale.ENGLISH) },
                    { st.trailerSubLang = cycle(listOf("en", "ar", "fr", "de", "es", "it", "tr", "fa", "ru", "pt", "hi", "ja", "ko", "zh"), st.trailerSubLang, it) }),
                Adjust("Play trailers by themselves", "After this long on a title its trailer starts; when it ends (or ◀ ▶) the next title comes", {
                    if (st.trailerAutoSec == 0) "Off" else "After ${st.trailerAutoSec} s"
                }, { st.trailerAutoSec = cycle(listOf(0, 3, 5, 8, 10, 15, 20, 30), st.trailerAutoSec, it) }),
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
        Category("Background & glass") {
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
                link("Dock & icons", "Where the dock sits over your background, its apps and icons"),
                Adjust("Dock glass", "The glass of the dock (the large clock follows it)", { styleName(st.dockStyle) },
                    { st.dockStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.dockStyle, it); st.theme = "" }),
                Adjust("Dock position", "With your own picture, photos, video or screensaver", { when (st.dockPos) { "center" -> "Middle"; "left" -> "Left (column)"; else -> "Bottom" } },
                    { st.dockPos = cycle(listOf("bottom", "center", "left"), st.dockPos, it) }),
                Adjust("Background", "Each title's own picture, or yours: a picture or a video, from a link or the folder below",
                    { when (st.bgMode) { "image" -> "My picture"; "video" -> "My video"; "aerial" -> "Screensaver (aerials)"; "photos" -> "Photos (4K)"; else -> "Title backdrops" } },
                    { st.bgMode = cycle(listOf("carousel", "image", "photos", "video", "aerial"), st.bgMode, it) }),
                Adjust("Screensaver videos as background", "1080p is lightest behind the glass; the screensaver's quality also plays the videos it saved on the TV", {
                    if (st.bgAerialQuality == "saver") "Screensaver's quality" else "1080p"
                }, { st.bgAerialQuality = cycle(listOf("1080", "saver"), st.bgAerialQuality, it) }),
                Toggle("Shuffle screensaver videos", "A new one when each ends, fading through black like the screensaver", { st.bgShuffle }, { st.bgShuffle = it }),
                Toggle("Shuffle my videos", "My video: the other videos in the folder too, one after another (else the chosen one loops)", { st.bgVideoShuffle }, { st.bgVideoShuffle = it }),
                Toggle("Shuffle my pictures", "My picture: another picture from the folder every \"Change every\"", { st.bgImageShuffle }, { st.bgImageShuffle = it }),
                Act("Next", "The next video, picture or photo now", "") { CustomBackground.nextNow(st); toast(ctx, "Next") },
                Note("Photos: 4K pictures that change by themselves"),
                Adjust("Photos from", "Bing and Wikimedia need no account; Unsplash needs a free key", {
                    com.glasslauncher.tv.data.Photos.SOURCES.firstOrNull { it.first == st.photoSource }?.second ?: st.photoSource
                }, { st.photoSource = cycle(com.glasslauncher.tv.data.Photos.SOURCES.map { it.first }, st.photoSource, it); st.bgMode = "photos" }),
                Adjust("Subject", "What Wikimedia and Unsplash photos show", { st.photoTopic.replaceFirstChar { c -> c.uppercase() } },
                    { st.photoTopic = cycle(com.glasslauncher.tv.data.Photos.TOPICS, st.photoTopic, it) }),
                Act("Unsplash key", "Free: unsplash.com/developers → New application → Access Key. Easier to paste from your phone (Manage from phone)",
                    if (st.unsplashKey.isBlank()) "Not set" else "Saved") {
                    dialog = TextEntry("Unsplash Access Key", st.unsplashKey, "Access Key") { v -> st.unsplashKey = v }
                },
                Adjust("Change every", "Photos, and your pictures with shuffle", {
                    when (val e = st.photoEvery) { 0 -> "Never"; in 1..59 -> "$e s"; in 60..3599 -> "${e / 60} min"; else -> "${e / 3600} h" }
                }, { st.photoEvery = cycle(listOf(60, 300, 600, 1800, 3600, 21600, 0), st.photoEvery, it) }),
                Toggle("Shuffle photos", "A random order (else the source's own order)", { st.photoShuffle }, { st.photoShuffle = it }),
                Adjust("Photos kept on the TV", "Shown again without downloading; the oldest go first", {
                    "${if (st.photoCacheMb >= 1024) "${st.photoCacheMb / 1024} GB" else "${st.photoCacheMb} MB"} · ${com.glasslauncher.tv.data.Photos.cacheUsedMb(ctx)} MB used"
                }, { st.photoCacheMb = cycle(listOf(256, 512, 1024, 2048, 4096), st.photoCacheMb, it) }),
                Act("Next photo", com.glasslauncher.tv.data.Photos.status.ifBlank { "Shows the next one now" }, "") {
                    if (st.bgMode != "photos") st.bgMode = "photos"
                    com.glasslauncher.tv.data.Photos.next(); toast(ctx, "Next photo")
                },
                Note("Over your background (picture, photos, video, screensaver)"),
                Toggle("Large clock", "In the middle, like a phone's lock screen", { st.bgClock }, { st.bgClock = it }),
                Adjust("Large clock style", "With the theme: the digits are made exactly like the dock in your theme (its glass, tint and colour)", {
                    when (st.bgClockStyle) { "white" -> "White"; "still" -> "Still Glass"; else -> "With the theme" }
                }, { st.bgClockStyle = cycle(listOf("theme", "still", "white"), st.bgClockStyle, it) }),
                Toggle("Large clock: white date", "The date above the time in white, whatever the clock's look", { st.bgClockDateWhite }, { st.bgClockDateWhite = it }),
                Adjust("Large clock size", "", { pct(st.bgClockSize) }, { st.bgClockSize = ((st.bgClockSize * 10).roundToInt() + it) / 10f }),
                Adjust("Large clock position", "◀ higher · lower ▶", {
                    val v = (st.bgClockOffset * 100).roundToInt(); if (v == 0) "Middle" else if (v < 0) "Up ${-v}" else "Down $v"
                }, { st.bgClockOffset = ((st.bgClockOffset * 100).roundToInt() + it * 2) / 100f }),
                Toggle("Depth effect", Depth.status.ifBlank { "On pictures and photos with open sky (yours too): what stands in front (a mountain, a tower, a tree) covers the lower part of the large clock. Worked out on the TV, once per photo (a 33 MB model is downloaded the first time)" },
                    { st.bgDepth }, { st.bgDepth = it }),
                Note("Info over the photos and the screensaver videos, like the screensaver"),
                Toggle("Place and credit", "Where the photo or video was taken and who took it", { st.bgInfo }, { st.bgInfo = it }),
                Toggle("Time", "", { st.bgInfoClock }, { st.bgInfoClock = it }),
                Toggle("Date", "", { st.bgInfoDate }, { st.bgInfoDate = it }),
                Toggle("Weather", "", { st.bgInfoWeather }, { st.bgInfoWeather = it }),
                Adjust("Info style", "", { if (st.bgInfoStyle == "netflix") "Netflix (one by one)" else "Apple" },
                    { st.bgInfoStyle = cycle(listOf("apple", "netflix"), st.bgInfoStyle, it) }),
                Adjust("Info position", "", { when (st.bgInfoPos) { "tl" -> "Top left"; "tr" -> "Top right"; "br" -> "Bottom right"; "move" -> "Moves each time"; else -> "Bottom left" } },
                    { st.bgInfoPos = cycle(listOf("bl", "br", "tl", "tr", "move"), st.bgInfoPos, it) }),
                Adjust("Info shows for", "After each new photo or video", { if (st.bgInfoSec == 0) "Always" else "${st.bgInfoSec} s" },
                    { st.bgInfoSec = cycle(listOf(0, 10, 20, 30, 60), st.bgInfoSec, it) }),
                Note("Your own"),
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
                    { if (st.iconPack.isEmpty()) "None (original icons)" else (SettingsCache.packs.value.orEmpty().firstOrNull { it.pkg == st.iconPack }?.label ?: st.iconPack) },
                    { st.iconPack = cycle(listOf("") + SettingsCache.packs.value.orEmpty().map { p -> p.pkg }, st.iconPack, it) }))
                add(Toggle("Show Apps folder", "The folder with all your apps, in the dock. Hidden apps stay listed below", { st.showFolder }, { st.showFolder = it }))
                add(Adjust("Dock size", "Smaller makes the dock and its icons narrower", { pct(st.dockScale) }, { st.dockScale = ((st.dockScale * 20).roundToInt() + it) / 20f }))
                add(Toggle("Dock fits its apps", "With fewer apps than a full row, the dock shrinks around them", { st.dockFit }, { st.dockFit = it }))
                add(Toggle("Hide the dock", "It stays below the screen until you go down to it (the dots go down too)", { st.dockHide }, { st.dockHide = it }))
                add(Adjust("Dock position", "With your own picture, video or screensaver background",
                    { when (st.dockPos) { "center" -> "Middle"; "left" -> "Left (column)"; else -> "Bottom" } },
                    { st.dockPos = cycle(listOf("bottom", "center", "left"), st.dockPos, it) }))
                add(Toggle("Sharp corners", "The menu, the dock and the icons with square corners", { st.squareCorners }, { st.squareCorners = it }))
                add(Toggle("Down from the dock opens the Library", "From the dock's last row", { st.libraryDown }, { st.libraryDown = it }))
                add(link("Background & glass", "Your picture, photos, video or screensaver behind the dock, the large clock and the glass"))
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
                Act("Switch colour", "The colour of the switches in Settings", SETTINGS_ACCENTS[st.settingsAccent]?.first ?: "Burgundy") {
                    dialog = ThumbDialog("Switch colour", SETTINGS_ACCENTS.map { (k, v) -> ThumbItem(v.first, if (k == "burgundy") "Default" else "", HueArt.swatch(v.second), checked = k == st.settingsAccent) { st.settingsAccent = k } })
                },
                Adjust("Theme", "One look for the dock, the icons and the menu. Black ones keep the dock clear (matte)",
                    { if (st.theme.isBlank()) "Custom" else themeName(st.theme) }, { st.applyTheme(cycle(themes, st.theme.ifBlank { "liquid" }, it)) }),
                Adjust("Menu", "The Apple TV-style menu at the settings button",
                    { when (st.menuMode) { "auto" -> "Opens when you go up to it"; "manual" -> "Opens with OK"; else -> "Off (OK opens Settings)" } },
                    { st.menuMode = cycle(listOf("auto", "manual", "off"), st.menuMode, it) }),
                Adjust("Menu look", "", { styleName(st.menuStyle) },
                    { st.menuStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.menuStyle, it); st.theme = "" }),
                Toggle("Clock in the menu", "", { st.menuClock }, { st.menuClock = it }),
                Toggle("Date in the menu", "", { st.menuDate }, { st.menuDate = it }),
                Toggle("Weather in the menu", "Temperature, conditions, high / low, humidity and wind (city in Background & glass)", { st.menuWeather }, { st.menuWeather = it; app.weather.reload() }),
                Toggle("City in the menu", "", { st.menuCity }, { st.menuCity = it }),
                Toggle("Library in the menu", "", { st.menuLibrary }, { st.menuLibrary = it }),
                Toggle("Android settings in the menu", "", { st.menuAndroid }, { st.menuAndroid = it }),
                Toggle("Wi-Fi in the menu", "", { st.menuWifi }, { st.menuWifi = it }),
                Toggle("Recent apps in the menu", "Opens the app switcher (like holding Home)", { st.menuRecents }, { st.menuRecents = it }),
                Toggle("Inputs in the menu", "The TV's HDMI inputs and the devices plugged into them (found by themselves, by name over HDMI-CEC)",
                    { st.menuInputs }, { st.menuInputs = it; if (it) scope.launch(Dispatchers.IO) { com.glasslauncher.tv.data.Inputs.refresh(ctx) } }),
                Toggle("Inputs in the dock", "Each input as an icon: move, hide and change its icon like an app",
                    { st.inputsDock }, { st.inputsDock = it; scope.launch(Dispatchers.IO) { com.glasslauncher.tv.data.Inputs.refresh(ctx); app.apps.reload() } }),
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
                Toggle("Trailer in the menu", "Play or stop the trailer, and its size, starting by itself and subtitles", { st.menuTrailer }, { st.menuTrailer = it }),
                Toggle("Screensaver in the menu", "Start it, choose its videos and when it starts", { st.menuSaver }, { st.menuSaver = it }),
                Toggle("Camera in the menu", if (st.cameras.isEmpty()) "Add a camera in Settings → Cameras" else "Live view in the menu, like Apple TV", { st.menuCamera }, { st.menuCamera = it }),
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
                add(Toggle("Exactly as in Nuvio", "The titles and their progress from Nuvio only, even with Trakt, Simkl and Stremio syncing too (their progress for the same title could be older or off)",
                    { st.cwNuvioOnly }, { st.cwNuvioOnly = it; app.cw.refreshItems() }))
                if (st.continueMode == "first") add(Adjust("Slides at the start", "How many titles in progress open the carousel", {
                    if (st.cwLead <= 0) "All" else "${st.cwLead}"
                }, { st.cwLead = cycle(listOf(1, 2, 3, 4, 5, 0), st.cwLead, it) }))
                add(Toggle("Open on what I just watched", "Back from Nuvio, the carousel shows that title first", { st.continueJump }, { st.continueJump = it }))
                add(Toggle("Progress bar", "Shows Continue · S1 · E3 · 42% under the title", { st.cwProgress }, { st.cwProgress = it }))
                add(Toggle("Next up", "After you finish an episode, the next one waits here (from the Nuvio account and what Glassio saw)", { st.cwNextUp }, { st.cwNextUp = it; app.cw.pollNow() }))
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
                val nv = app.library.nuvio
                tvRev.intValue
                if (!nv.connected) add(Act("Sign in with email", "With the account's email and password (for a server without a TV sign-in page)", "") {
                    dialog = TextEntry("Nuvio email", "", "you@example.com") { mail ->
                        dialog = TextEntry("Nuvio password", "", "Password") { pw ->
                            io {
                                if (nv.signInWithPassword(mail, pw)) { st.libraryNuvio = true; app.library.refreshNuvio(); app.cw.pollNow(); tvRev.intValue++; "Nuvio account connected" }
                                else "Nuvio: ${nv.lastError}"
                            }
                        }
                    }
                })
                // A self-hosted Nuvio backend (its own Supabase project): address, anon key, TV sign-in page.
                add(Act("Nuvio server", "Your own Nuvio backend, if you host one. Easier to type on the phone page", if (nv.custom) hostOf(nv.server) else "Official") {
                    dialog = TextEntry("Nuvio server address (empty: the official one)", nv.server, "https://your-project.supabase.co") { url ->
                        val u = url.trim()
                        if (u.isBlank()) { io { nv.setServer("", "", ""); app.library.nuvioList.value = emptyList(); tvRev.intValue++; "Back on the official Nuvio server" } }
                        else dialog = TextEntry("Its anon (public) key", "", "Supabase → Project Settings → API → anon key") { k ->
                            dialog = TextEntry("Its TV sign-in page (optional)", nv.loginPage, "https://…/tv-login · empty: sign in with email") { page ->
                                io { nv.setServer(u, k, page); app.library.nuvioList.value = emptyList(); tvRev.intValue++; "Nuvio server set: sign in again" }
                            }
                        }
                    }
                })
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

                add(Note("Sync"))
                add(Toggle("Sync Library, likes and Continue Watching", "Keeps them the same on every connected account: Nuvio, Trakt and Simkl (Stremio is read). Removing a title removes it everywhere",
                    { st.syncAll }, { on -> st.syncAll = on; if (on) app.sync.runSoon { m -> toast(ctx, m) } }))
                if (st.syncAll) add(Act("Sync now", syncStatus.ifBlank { "Runs on its own every 30 minutes" }, "") {
                    toast(ctx, "Syncing…"); app.sync.runSoon { m -> toast(ctx, m); tvRev.intValue++ }
                })

                add(Note("Home Assistant"))
                add(Act("Home Assistant webhook", "Full webhook URL from your Home Assistant", if (st.haWebhook.isBlank()) "Not set" else "Set",
                    ok = if (st.haWebhook.startsWith("http")) true else null) {
                    dialog = TextEntry("Home Assistant webhook", st.haWebhook, "http://homeassistant.local:8123/api/webhook/…") { st.haWebhook = it }
                })
                add(Toggle("Tell Home Assistant when a title opens", "Sends title, type and id as JSON", { st.haOnLaunch }, { st.haOnLaunch = it }))
                add(Toggle("Send Nuvio playback to Home Assistant", if (st.haUrl.isBlank() || st.haToken.isBlank()) "Needs the Home Assistant link and token (Cameras → Doorbell from Home Assistant)"
                    else "Nuvio Legacy's title, play / pause and position, as it happens (event glassio_nuvio): Home Assistant no longer needs to ask the TV over ADB",
                    { st.nuvioPush }, { on -> st.nuvioPush = on; com.glasslauncher.tv.HomeKeyService.instance?.watchAllWindows(on); if (on) com.glasslauncher.tv.data.NuvioWatch.start(ctx) else com.glasslauncher.tv.data.NuvioWatch.stop() }))
                add(Act("Send a test event", "") { io { NuvioLaunch.testWebhook(st, app.http) } })

                add(link("Cameras", "Find, add and remove cameras, the menu camera and the corner camera"))
            }
        },
        Category("Cameras") {
            buildList {
                add(Note("Your cameras"))
                add(Act("Find cameras", "Searches your network (ONVIF: Hikvision, Dahua, Reolink, Tapo, Amcrest, Axis…)", "") {
                    io {
                        val found = com.glasslauncher.tv.data.Onvif.discover()
                        if (found.isEmpty()) return@io "No cameras answered. Add one with its RTSP link below"
                        dialog = MenuDialog("Cameras found", found.map { f -> "${f.name} · ${f.ip}" to {
                            dialog = TextEntry("Login for ${f.name}", "admin:", "user:password (the camera's own login)") { login ->
                                io {
                                    val chans = com.glasslauncher.tv.data.Onvif.streamUris(f, login.substringBefore(':'), login.substringAfter(':', ""))
                                    st.cameras = (st.cameras + chans.map { (n, uri) -> Cam(n, uri).line() }).distinct(); tvRev.intValue++
                                    if (chans.size > 1) "Added ${chans.size} cameras from ${f.name}" else "Added ${f.name}"
                                }
                            }
                        } })
                        "${found.size} found"
                    }
                })
                add(Act("Cameras from Home Assistant", "Aqara (through HomeKit), Frigate, Ring, Tapo… any camera Home Assistant shows. Uses the Home Assistant link and token (Doorbell, below)", "") {
                    io {
                        check(st.haUrl.startsWith("http") && st.haToken.isNotBlank()) { "Add the Home Assistant link and token first (Doorbell from Home Assistant, below)" }
                        val list = com.glasslauncher.tv.net.HaWatch.cameras(st.haUrl, st.haToken)
                        if (list.isEmpty()) return@io "Home Assistant has no cameras"
                        dialog = MenuDialog("Home Assistant cameras", list.map { (id, n) ->
                            (if (st.cameras.any { Cam.parse(it)?.url == "ha://$id" }) "✓ $n" else n) to {
                                st.cameras = (st.cameras + Cam(n, "ha://$id").line()).distinctBy { Cam.parse(it)?.url ?: it }; tvRev.intValue++
                                toast(ctx, "Added $n")
                            }
                        })
                        "${list.size} cameras"
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
                if (st.cameras.isEmpty()) add(Note("No cameras yet: find them on your network or add a link above. From the phone page you can also add, rename and remove them."))
                add(Note("Where they show"))
                add(Toggle("Camera in the menu", "Live view in the menu, like Apple TV (◀ ▶ switches camera)", { st.menuCamera }, { st.menuCamera = it }))
                add(Toggle("Hold Home shows a camera", "In the corner, over any app (needs the Home button service: System)", { st.homeLong == "camera" },
                    { st.homeLong = if (it) "camera" else "off" }))
                val camList = st.cameras.mapNotNull { Cam.parse(it) }
                if (camList.size > 1) add(Adjust("  · Camera", "Which camera opens when you hold Home",
                    { (camList.firstOrNull { it.url == st.cornerCamera } ?: camList.first()).name },
                    { d ->
                        val i = camList.indexOfFirst { it.url == st.cornerCamera }.coerceAtLeast(0)
                        st.cornerCamera = camList[(i + d).mod(camList.size)].url
                    }))
                add(Adjust("  · Size", "", { pct(st.cameraScale) }, { st.cameraScale = ((st.cameraScale * 10).roundToInt() + it) / 10f }))
                if (camList.size > 1) add(Adjust("  · Switch cameras every", "Goes round your cameras on its own",
                    { if (st.cameraCycleSec == 0) "Off" else if (st.cameraCycleSec < 60) "${st.cameraCycleSec} s" else "${st.cameraCycleSec / 60} min" },
                    { st.cameraCycleSec = cycle(listOf(0, 5, 10, 15, 20, 30, 60), st.cameraCycleSec, it) }))
                add(Adjust("  · Corner camera closes after", "It goes away on its own after this (Back or holding Home again closes it sooner)",
                    { if (st.cameraCornerSec == 0) "Never" else if (st.cameraCornerSec < 60) "${st.cameraCornerSec} s" else "${st.cameraCornerSec / 60} min" },
                    { st.cameraCornerSec = cycle(listOf(10, 20, 30, 60, 120, 300, 0), st.cameraCornerSec, it) }))
                add(Note("Multi-view: up to four cameras in a row"))
                add(Toggle("On the screensaver", "▼ on the screensaver brings them up from the bottom (Back or ▼ again closes them)", { st.saverMultiCam }, { st.saverMultiCam = it }))
                if (camList.isNotEmpty()) add(Act("  · Cameras", "Which ones, in this order (up to four). None chosen = the first four",
                    MultiCamOverlay.cams(app).joinToString(" · ") { it.name }) {
                    fun chooser() {
                        val cur = st.multiCams
                        dialog = MenuDialog("Multi-view cameras", camList.map { c ->
                            (if (c.url in cur) "✓ ${c.name}" else c.name) to {
                                st.multiCams = if (c.url in cur) cur - c.url else (cur + c.url).take(4)
                                scope.launch { kotlinx.coroutines.delay(150); chooser() }
                                Unit
                            }
                        } + listOf("Done" to {}))
                    }
                    chooser()
                })
                add(Adjust("  · Size", "", { pct(st.multiScale) }, { st.multiScale = ((st.multiScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Picture", "Sharp: each camera's full stream. Light: its small stream (for small or older TVs). Automatic picks for this TV",
                        { when (st.multiQuality) { "high" -> "Sharp"; "light" -> "Light"; else -> "Automatic" } }, { st.multiQuality = cycle(listOf("auto", "high", "light"), st.multiQuality, it) }))
                add(Adjust("  · Shows for", "Then it goes away by itself", { if (st.multiSec == 0) "Until closed" else if (st.multiSec < 60) "${st.multiSec} s" else "${st.multiSec / 60} min" },
                    { st.multiSec = cycle(listOf(10, 20, 30, 60, 120, 300, 0), st.multiSec, it) }))
                add(Act("  · Show it now", "In the middle of the screen (also: menu → Camera, then OK)", "") {
                    val svc = com.glasslauncher.tv.HomeKeyService.instance
                    if (svc == null) toast(ctx, "Needs the Home button service: System → Turn it on") else svc.toggleMulti(bottom = false)
                })
                add(Note("Remote buttons (in any app)"))
                add(Act("Multi-view button", "Press it anywhere: the cameras show in the middle (again closes them)",
                    if (st.multiKey == 0) "Set" else "✓ " + keyName(st.multiKey), ok = if (st.multiKey != 0) true else null) {
                    dialog = KeyCaptureDialog("Multi-view button") { code -> st.multiKey = code; tvRev.intValue++; toast(ctx, "Multi-view button: ${keyName(code)}") }
                })
                if (st.multiKey != 0) add(Act("  · Clear the multi-view button", "", "") { st.multiKey = 0 })
                add(Act("Corner camera button", "Press it anywhere: the camera in the corner (again closes it)",
                    if (st.cornerKey == 0) "Set" else "✓ " + keyName(st.cornerKey), ok = if (st.cornerKey != 0) true else null) {
                    dialog = KeyCaptureDialog("Corner camera button") { code -> st.cornerKey = code; tvRev.intValue++; toast(ctx, "Corner camera button: ${keyName(code)}") }
                })
                if (st.cornerKey != 0) add(Act("  · Clear the corner camera button", "", "") { st.cornerKey = 0 })
                add(Adjust("Camera video", "Software keeps the TV's own video playing while a camera shows (some TVs stopped it)", {
                    when (st.cameraDecoder) { "software" -> "Software"; "hardware" -> "Hardware"; else -> "Auto (" + (if (com.glasslauncher.tv.CamPlayer.software(ctx)) "software" else "hardware") + ")" }
                }, { st.cameraDecoder = cycle(listOf("auto", "software", "hardware"), st.cameraDecoder, it) }))
                add(Note("Doorbell from Home Assistant"))
                add(Toggle("Doorbell pop-up", "Home Assistant sends to http://<this TV>:7979/notify (the same as PiPup): the camera shows in the corner over any app",
                    { st.doorbellOn }, { st.doorbellOn = it }))
                if (st.doorbellOn) {
                    add(Adjust("  · Size", "", { pct(st.doorbellScale) }, { st.doorbellScale = ((st.doorbellScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Camera", "From Home Assistant: the one its automation sends; or one of your cameras here",
                        { camList.firstOrNull { it.url == st.doorbellCamera }?.name ?: "From Home Assistant" },
                        { d ->
                            val opts = listOf("") + camList.map { it.url }
                            val i = opts.indexOf(st.doorbellCamera).coerceAtLeast(0)
                            st.doorbellCamera = opts[(i + d).mod(opts.size)]
                        }))
                    if (camList.isNotEmpty()) add(Adjust("  · Switch cameras every", "Goes round your cameras on its own, starting with the doorbell's",
                        { if (st.doorbellCycleSec == 0) "Off" else if (st.doorbellCycleSec < 60) "${st.doorbellCycleSec} s" else "${st.doorbellCycleSec / 60} min" },
                        { st.doorbellCycleSec = cycle(listOf(0, 5, 10, 15, 20, 30), st.doorbellCycleSec, it) }))
                    add(Adjust("  · Shows for", "Then it goes away on its own (Back closes it sooner)",
                        { if (st.doorbellSec < 60) "${st.doorbellSec} s" else "${st.doorbellSec / 60} min" + (if (st.doorbellSec % 60 != 0) " ${st.doorbellSec % 60} s" else "") },
                        { st.doorbellSec = cycle(listOf(5, 10, 15, 20, 30, 45, 60, 90, 120), st.doorbellSec, it) }))
                    add(Toggle("  · Words", "Where and what, at the bottom of the camera", { st.doorbellText }, { st.doorbellText = it }))
                    if (st.doorbellText) {
                        // Ready-made words, or your own.
                        val presets = listOf(
                            "العربية" to ("الباب الأمامي" to "يوجد شخص عند الباب"),
                            "English" to ("Front Door" to "Someone is at the door"),
                        )
                        val cur = presets.firstOrNull { it.second.first == st.doorbellTitle && it.second.second == st.doorbellMessage }?.first ?: "Custom"
                        add(Adjust("  · Language", "Ready-made words, or write your own below", { cur }, { d ->
                            val names = presets.map { it.first }
                            val i = names.indexOf(cur)
                            val next = presets[((if (i < 0) -1 else i) + d).mod(presets.size)].second
                            st.doorbellTitle = next.first; st.doorbellMessage = next.second
                        }))
                        add(Act("  · Title", "", st.doorbellTitle) {
                            dialog = TextEntry("Doorbell title", st.doorbellTitle, "Front Door") { t -> st.doorbellTitle = t.trim() }
                        })
                        add(Act("  · Message", "", st.doorbellMessage) {
                            dialog = TextEntry("Doorbell message", st.doorbellMessage, "Someone is at the door") { t -> st.doorbellMessage = t.trim() }
                        })
                    }
                    add(Toggle("  · Chime", "A soft ding-dong when it shows", { st.doorbellChime }, { st.doorbellChime = it }))
                    add(Act("  · Try it", "Shows the pop-up now with your camera", "") {
                        io { runCatching { com.glasslauncher.tv.net.NotifyServer.show(app, org.json.JSONObject()); "Shown" }.getOrElse { it.message ?: "Couldn't show it" } }
                    })
                    // Glassio watches the doorbell sensor itself: works with no automation in Home Assistant.
                    val ha = com.glasslauncher.tv.net.HaWatch
                    add(Note("Straight from Home Assistant (no automation needed)"))
                    add(Act("  · Home Assistant link", "Now: ${ha.status}", st.haUrl.ifBlank { "Not set" }) {
                        dialog = TextEntry("Home Assistant link", st.haUrl.ifBlank { "http://" }, "http://homeassistant.local:8123") { u ->
                            st.haUrl = u; ha.restart(); tvRev.intValue++
                        }
                    })
                    add(Act("  · Token", "Home Assistant → your profile → Security → Long-lived access tokens. Easier to paste from your phone (Manage from phone)",
                        if (st.haToken.isBlank()) "Not set" else "Saved") {
                        dialog = TextEntry("Home Assistant token", "", "Long-lived access token") { t ->
                            if (t.isNotBlank()) { st.haToken = t; ha.restart(); tvRev.intValue++ }
                        }
                    })
                    add(Act("  · Doorbell sensor", "The one that turns on when the doorbell rings", st.haDoorbellEntity.ifBlank { "Pick" }) {
                        io {
                            check(st.haUrl.startsWith("http") && st.haToken.isNotBlank()) { "Add the Home Assistant link and token first" }
                            val list = ha.sensors(st.haUrl, st.haToken)
                            if (list.isEmpty()) return@io "No sensors found"
                            dialog = MenuDialog("Doorbell sensor", list.map { (id, n) -> "$n · $id" to { st.haDoorbellEntity = id; ha.restart(); tvRev.intValue++ } })
                            "${list.size} sensors"
                        }
                    })
                }
                add(link("Manage from phone", "Add, rename and remove cameras from your phone"))
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
                add(Toggle("Large clock", "The large glass clock in the middle of the screensaver", { st.saverBigClock }, { st.saverBigClock = it }))
                add(Toggle("Lock & release", "Hold OK on a video you like to lock it (a glass padlock closes); hold OK again to release it", { st.saverLock }, { st.saverLock = it }))
                if (st.saverLock) {
                    add(Toggle("  · Only locked videos", "Plays just the videos you locked (when there are some). Also in the menu's Screensaver page", { st.saverLockedOnly }, { st.saverLockedOnly = it }))
                    add(Act("  · Clear locked videos", "${st.saverLocked.size} locked", "") { st.saverLocked = emptyList() })
                }
                if (st.saverBigClock) {
                    add(Note("Large clock (the same look as the home screen's large clock)"))
                    add(Adjust("Large clock style", "With the theme: the digits are made exactly like the dock in your theme (its glass, tint and colour)", {
                    when (st.bgClockStyle) { "white" -> "White"; "still" -> "Still Glass"; else -> "With the theme" }
                }, { st.bgClockStyle = cycle(listOf("theme", "still", "white"), st.bgClockStyle, it) }))
                    add(Toggle("Large clock: white date", "The date above the time in white, whatever the clock's look", { st.bgClockDateWhite }, { st.bgClockDateWhite = it }))
                    add(Adjust("Large clock size", "", { pct(st.bgClockSize) }, { st.bgClockSize = ((st.bgClockSize * 10).roundToInt() + it) / 10f }))
                    add(Adjust("Large clock position", "◀ higher · lower ▶", {
                    val v = (st.bgClockOffset * 100).roundToInt(); if (v == 0) "Middle" else if (v < 0) "Up ${-v}" else "Down $v"
                }, { st.bgClockOffset = ((st.bgClockOffset * 100).roundToInt() + it * 2) / 100f }))
                }
                run {
                    add(Note("Cameras on the screensaver"))
                    val camList = st.cameras.mapNotNull { Cam.parse(it) }
                    add(Toggle("Cameras (multi-view)", "▼ on the screensaver brings them up from the bottom (Back or ▼ again closes them)", { st.saverMultiCam }, { st.saverMultiCam = it }))
                    if (camList.isNotEmpty()) add(Act("  · Cameras", "Which ones, in this order (up to four). None chosen = the first four",
                        MultiCamOverlay.cams(app).joinToString(" · ") { it.name }) {
                        fun chooser() {
                            val cur = st.multiCams
                            dialog = MenuDialog("Multi-view cameras", camList.map { c ->
                                (if (c.url in cur) "✓ ${c.name}" else c.name) to {
                                    st.multiCams = if (c.url in cur) cur - c.url else (cur + c.url).take(4)
                                    scope.launch { kotlinx.coroutines.delay(150); chooser() }
                                    Unit
                                }
                            } + listOf("Done" to {}))
                        }
                        chooser()
                    })
                    add(Adjust("  · Size", "", { pct(st.multiScale) }, { st.multiScale = ((st.multiScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Picture", "Sharp: each camera's full stream. Light: its small stream (for small or older TVs). Automatic picks for this TV",
                        { when (st.multiQuality) { "high" -> "Sharp"; "light" -> "Light"; else -> "Automatic" } }, { st.multiQuality = cycle(listOf("auto", "high", "light"), st.multiQuality, it) }))
                    add(Adjust("  · Shows for", "Then it goes away by itself", { if (st.multiSec == 0) "Until closed" else if (st.multiSec < 60) "${st.multiSec} s" else "${st.multiSec / 60} min" },
                        { st.multiSec = cycle(listOf(10, 20, 30, 60, 120, 300, 0), st.multiSec, it) }))
                    add(Act("  · Show it now", "In the middle of the screen (also: menu → Camera, then OK)", "") {
                        val svc = com.glasslauncher.tv.HomeKeyService.instance
                        if (svc == null) toast(ctx, "Needs the Home button service: System → Turn it on") else svc.toggleMulti(bottom = false)
                    })
                }
                if (st.musicOn) {
                    add(Note("Music island"))
                    add(Toggle("Island", "While music plays, a small island at the top of the screensaver (like the iPhone's). ▲ opens it with its buttons, ▼ closes it", { st.musicIsland }, { st.musicIsland = it }))
                    add(Toggle("  · Island on the home screen too", "When Music mode is off", { st.islandHome }, { st.islandHome = it }))
                    if (st.musicIsland) {
                        add(Adjust("  · Look", "", { when (st.islandStyle) { "glass" -> "Glass"; "black" -> "Black"; "none" -> "No background"; else -> "With the theme" } }, { st.islandStyle = cycle(listOf("none", "theme", "glass", "black"), st.islandStyle, it) }))
                        add(Toggle("  · Joined to the top", "Like a MacBook's notch, instead of floating below the top", { st.islandAttached }, { st.islandAttached = it }))
                        add(Adjust("  · Size", "", { pct(st.islandScale) }, { st.islandScale = ((st.islandScale * 10).roundToInt() + it) / 10f }))
                        add(Adjust("  · Lower", "", { "${st.islandOffset.roundToInt()}" }, { st.islandOffset = st.islandOffset + it * 4f }))
                        add(Toggle("  · Artwork", "", { st.islandArt }, { st.islandArt = it }))
                        add(Toggle("  · Song and artist", "", { st.islandTitle }, { st.islandTitle = it }))
                        add(Toggle("  · Progress bar", "", { st.islandProgress }, { st.islandProgress = it }))
                        add(Adjust("  · Bars colour", "", { if (st.islandBars == "white") "White" else "From the artwork" }, { st.islandBars = cycle(listOf("art", "white"), st.islandBars, it) }))
                    }
                }
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
        Category("Music") {
            buildList {
                tvRev.intValue
                val access = com.glasslauncher.tv.data.NowPlaying.hasAccess(ctx)
                add(Toggle("Music", if (!st.musicOn) "Glassio follows what any music app plays (Spotify, YouTube Music, Apple Music, Anghami…)"
                    else if (access) "On: following your music apps" else "Needs TV access (ADB, System) to read the music apps: press to try again",
                    { st.musicOn }, { on ->
                        st.musicOn = on
                        if (on) io {
                            val ok = com.glasslauncher.tv.data.NowPlaying.grant(ctx)
                            com.glasslauncher.tv.data.NowPlaying.start(ctx); app.apps.reload(); tvRev.intValue++
                            if (ok) "Music on" else "Allow TV access (ADB) first: Glassio settings → System"
                        } else app.apps.reload()
                    }))
                if (st.musicOn) {
                    add(Note("Where it shows"))
                    add(Toggle("Player in the menu", "▶ in the menu turns it into a small player", { st.musicMenu }, { st.musicMenu = it }))
                    add(Toggle("Music in the dock", "A Music icon (move, hide and change it like an app): opens the player", { st.musicDock }, { st.musicDock = it; app.apps.reload() }))
                    add(Toggle("Music mode", "While a song plays, the carousel's place becomes the player (artwork, song, buttons). ■ stops the music. With your own picture, photos, video or the screensaver's videos as the background, the island shows instead",
                        { st.musicMode }, { st.musicMode = it }))
                    add(Adjust("  · Player size", "", { pct(st.musicStageScale) }, { st.musicStageScale = ((st.musicStageScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Buttons", "White or black turn the other way round when selected", {
                        when (st.musicButtons) { "white" -> "White"; "black" -> "Black"; else -> "With the theme" }
                    }, { st.musicButtons = cycle(listOf("theme", "white", "black"), st.musicButtons, it) }))
                    add(Note("Island on the screensaver"))
                    add(Toggle("Island", "While music plays, a small island at the top of the screensaver (like the iPhone's). ▲ opens it with its buttons, ▼ closes it", { st.musicIsland }, { st.musicIsland = it }))
                    add(Toggle("  · Island on the home screen too", "When Music mode is off", { st.islandHome }, { st.islandHome = it }))
                    if (st.musicIsland) {
                        add(Adjust("  · Look", "", { when (st.islandStyle) { "glass" -> "Glass"; "black" -> "Black"; "none" -> "No background"; else -> "With the theme" } }, { st.islandStyle = cycle(listOf("none", "theme", "glass", "black"), st.islandStyle, it) }))
                        add(Toggle("  · Joined to the top", "Like a MacBook's notch, instead of floating below the top", { st.islandAttached }, { st.islandAttached = it }))
                        add(Adjust("  · Size", "", { pct(st.islandScale) }, { st.islandScale = ((st.islandScale * 10).roundToInt() + it) / 10f }))
                        add(Adjust("  · Lower", "", { "${st.islandOffset.roundToInt()}" }, { st.islandOffset = st.islandOffset + it * 4f }))
                        add(Toggle("  · Artwork", "", { st.islandArt }, { st.islandArt = it }))
                        add(Toggle("  · Song and artist", "", { st.islandTitle }, { st.islandTitle = it }))
                        add(Toggle("  · Progress bar", "", { st.islandProgress }, { st.islandProgress = it }))
                        add(Adjust("  · Bars colour", "", { if (st.islandBars == "white") "White" else "From the artwork" }, { st.islandBars = cycle(listOf("art", "white"), st.islandBars, it) }))
                    }
                    add(Note("Music apps on this TV: " + installedMusicApps(ctx).joinToString { it.second }.ifBlank { "none yet" } +
                        ". The player follows and controls whatever those apps play. Signing in to Spotify, YouTube Music or Apple Music for their playlists isn't here yet."))
                }
            }
        },
        Category("Hue lights") {
            val hue = com.glasslauncher.tv.data.Hue
            val sync = com.glasslauncher.tv.data.HueSync
            val hueRev = remember { androidx.compose.runtime.mutableIntStateOf(0) }
            // The bridge's rooms, zones and lights (read when this page opens, and after connecting).
            val found by androidx.compose.runtime.produceState<Pair<List<com.glasslauncher.tv.data.Hue.Group>, List<com.glasslauncher.tv.data.Hue.Light>>?>(null, st.hueUser, st.hueIp, hueRev.intValue) {
                value = if (st.hueUser.isBlank() || st.hueIp.isBlank()) null
                    else kotlinx.coroutines.withContext(Dispatchers.IO) { hue.groups(st.hueIp, st.hueUser) to hue.lights(st.hueIp, st.hueUser) }
            }
            buildList {
                add(Toggle("Hue Sync", "Philips Hue lights in the colours of what's on screen: the backdrop, the poster or the logo, videos and trailers, the music's artwork",
                    { st.hueOn }, { on -> st.hueOn = on; if (!on) sync.leave(st, "restore") }))
                if (st.hueOn) {
                    val paired = st.hueUser.isNotBlank()
                    add(Note("Bridge"))
                    if (!paired) {
                        add(Act("Find the bridge", if (st.hueIp.isBlank()) "Looks for the Hue bridge on this network" else "Found: ${st.hueIp}", "Find") {
                            io { val f = hue.discover(ctx); if (f.isEmpty()) "No bridge found: enter its address below" else { st.hueIp = f[0]; "Found ${f[0]}: press the round button on the bridge, then Connect" } }
                        })
                        add(Act("Bridge address", st.hueIp.ifBlank { "Not set (the Hue app shows it: Settings → Bridges)" }, "Change") {
                            dialog = TextEntry("Hue bridge address", st.hueIp, "The address the Hue app shows") { v -> st.hueIp = v.trim() }
                        })
                        if (st.hueIp.isNotBlank()) add(Act("Connect", "Press the round button on top of the bridge, then this (within 30 seconds)", "Connect") {
                            io {
                                repeat(30) {
                                    val r = hue.pair(st.hueIp)
                                    if (r.user != null) { st.hueUser = r.user; sync.refreshGroups(); hueRev.intValue++; return@io "Connected to the Hue bridge: now choose the lights" }
                                    if (!r.waiting) return@io "Hue: ${r.error}"
                                    kotlinx.coroutines.delay(1000)
                                }
                                "The bridge's button wasn't pressed: press it, then Connect again"
                            }
                        })
                    } else {
                        add(Act("Bridge", "Connected · ${st.hueIp}", "", ok = true) { hueRev.intValue++ })
                        val f = found
                        add(Note("Lights that follow the screen"))
                        if (f == null) add(Note("Reading the bridge…"))
                        else {
                            fun plural(n: Int) = "$n light${if (n == 1) "" else "s"}"
                            val areas = f.first.filter { it.type == "Entertainment" }
                            // Entertainment areas first: the Hue app knows where each of their lights stands, and
                            // each light then takes the colour of the part of the screen in front of it.
                            if (areas.isEmpty()) add(Note("No entertainment area on the bridge. Make one in the Hue app (Settings → Entertainment areas) and place the lights around the TV: each then takes the colour of its side of the screen by itself"))
                            areas.forEach { g ->
                                add(Toggle("Entertainment area · ${g.name}", plural(g.lights.size) + if (g.places.isNotEmpty()) " · placed around the TV" else "",
                                    { g.id in st.hueGroups }, { on -> st.hueGroups = if (on) st.hueGroups + g.id else st.hueGroups - g.id; sync.refreshGroups() }))
                            }
                            f.first.filter { it.type != "Entertainment" }.forEach { g ->
                                add(Toggle("${if (g.type == "Zone") "Zone" else "Room"} · ${g.name}", plural(g.lights.size),
                                    { g.id in st.hueGroups }, { on -> st.hueGroups = if (on) st.hueGroups + g.id else st.hueGroups - g.id; sync.refreshGroups() }))
                            }
                            f.second.forEach { l ->
                                add(Toggle("  · ${l.name}", if (l.color) "" else "White only (it follows the brightness)",
                                    { l.id in st.hueLights }, { on -> st.hueLights = if (on) st.hueLights + l.id else st.hueLights - l.id }))
                            }
                            // Each chosen light: which part of the screen it follows (a list to choose from), and
                            // (other makes) how it takes its colour.
                            val chosen = sync.targets(st, f.first)
                            val details = remember(st.hueIp, st.hueUser, hueRev.intValue, f) { f.second.associateBy { it.id } }
                            val placed = f.first.filter { it.id in st.hueGroups }.flatMap { it.places.keys }.toSet()
                            if (chosen.isNotEmpty()) add(Note("Each light · the part of the screen it follows"))
                            chosen.forEach { id ->
                                val l = details[id] ?: return@forEach
                                val zone = sync.zones(st)[id] ?: "auto"
                                val zones = if (l.gradient) hue.ZONES else hue.ZONES - "gradient"
                                val shown = if (zone == "auto" && id in placed) "Its place in the area" else hue.zoneName(zone)
                                add(Act(l.name, (if (l.kind == "ct") "White shades only: the nearest warm or cold white · " else if (l.kind == "dim") "Brightness only · " else "") +
                                    (l.maker.takeIf { !l.philips && it.isNotBlank() }?.let { "$it · " } ?: "") + "OK: choose", shown) {
                                    dialog = ThumbDialog(l.name, listOf(ThumbItem("Blink it", "To see which light this is", HueArt.blink()) { io { hue.identify(st.hueIp, st.hueUser, id); "${l.name} blinks" } }) +
                                        zones.map { z -> ThumbItem(hue.zoneName(z), hue.zoneHint(z), HueArt.zone(z), checked = z == zone) { sync.setZone(st, id, z) } })
                                })
                                if (l.kind == "color" && !l.philips) {
                                    val m = sync.methods(st)[id] ?: "auto"
                                    add(Act("  · Colour method", "Hue and saturation usually match other makes better than xy", if (m == "xy") "xy" else "Hue and saturation") {
                                        dialog = ThumbDialog("${l.name}: colour method", listOf(
                                            ThumbItem("Hue and saturation", "Recommended for this make", HueArt.method("hs"), checked = m != "xy") { sync.setMethod(st, id, "auto") },
                                            ThumbItem("xy", "Like Philips lights", HueArt.method("xy"), checked = m == "xy") { sync.setMethod(st, id, "xy") }))
                                    })
                                }
                            }
                        }
                        add(Note("Colours"))
                        // A list to choose from (OK), with what each choice means.
                        fun pick(title: String, desc: String, cur: String, opts: List<Triple<String, String, String>>, art: ((String) -> Any?)? = null, set: (String) -> Unit) =
                            add(Act(title, desc, opts.firstOrNull { it.first == cur }?.second ?: opts[0].second) {
                                dialog = ThumbDialog(title, opts.map { (k, name, hint) -> ThumbItem(name, hint, art?.invoke(k), checked = k == cur) { set(k) } })
                            })
                        pick("Colours from", "What the lights take their colours from", st.hueSource, listOf(
                            Triple("backdrop", "Backdrop", "What fills the screen: the title's picture, your photo, the music page"),
                            Triple("poster", "Poster", "The title's poster"), Triple("logo", "Logo", "The title's logo")), { HueArt.source(it) }) { st.hueSource = it }
                        add(Toggle("Follow videos", "Your video background and trailers, live", { st.hueVideo }, { st.hueVideo = it }))
                        if (st.hueVideo || st.hueSaver) add(Adjust("  · Every", "How often the lights follow a video", { if (st.hueVideoSec <= 0) "0.5 s" else "${st.hueVideoSec} s" }, { st.hueVideoSec = st.hueVideoSec + it }))
                        add(Adjust("Lights", "Each light a different colour of the picture, or all of them its main colour", {
                            if (st.hueSpread) "A colour each" else "One colour"
                        }, { st.hueSpread = !st.hueSpread }))
                        add(Adjust("Intensity", "How colourful the lights are", {
                            listOf("Subtle", "Natural", "Vivid", "Maximum")[st.hueIntensity]
                        }, { st.hueIntensity = (st.hueIntensity + it).coerceIn(0, 3) }))
                        add(Adjust("Brightness", "", { "${st.hueBri} %" }, { st.hueBri = st.hueBri + it * 5 }))
                        add(Toggle("Follow the picture's brightness", "Darker scenes, dimmer lights", { st.hueFollowBri }, { st.hueFollowBri = it }))
                        pick("Change speed", "How long the lights take to follow a video (a new title's colours come at once)", st.hueSpeedMs.toString(), listOf(
                            Triple("0", "Instant", ""), Triple("400", "Quick", "0.4 s"), Triple("1000", "Smooth", "1 s"),
                            Triple("2000", "Slow", "2 s"), Triple("4000", "Very slow", "4 s"))) { st.hueSpeedMs = it.toInt() }
                        add(Toggle("On the screensaver", "The lights follow its videos", { st.hueSaver }, { st.hueSaver = it }))
                        add(Note("Selection"))
                        add(Toggle("The selected app", "All the lights take the colour of the app selected in the dock (its glow's colour)", { st.hueApps }, { st.hueApps = it }))
                        if (st.hueApps) add(Adjust("  · Brightness", "How bright the lights are with the app's colour", { "${st.hueAppsBri} %" }, { st.hueAppsBri = st.hueAppsBri + it * 5 }))
                        add(Toggle("The selected poster in the Library", "All the lights take the colour of the poster you're on", { st.huePosters }, { st.huePosters = it }))
                        if (st.huePosters) add(Adjust("  · Brightness", "How bright the lights are with the poster's colour", { "${st.huePostersBri} %" }, { st.huePostersBri = st.huePostersBri + it * 5 }))
                        add(Note("Afterwards"))
                        pick("When you open an app or a film", "", st.hueLeave, listOf(
                            Triple("restore", "Back as they were", "As before Hue Sync"), Triple("dim", "Dim them", "Their own colour, at 20 %"),
                            Triple("off", "Turn them off", ""), Triple("keep", "Leave them", "In the last colours"))) { st.hueLeave = it }
                        pick("When the TV turns off", "", st.hueOff, listOf(
                            Triple("restore", "Back as they were", "As before Hue Sync"), Triple("off", "Turn them off", ""),
                            Triple("keep", "Leave them", "In the last colours"))) { st.hueOff = it }
                        add(Act("Put the lights back now", "As they were before Hue Sync (they follow the screen again on the home screen)", "Restore") {
                            sync.leave(st, "restore"); sync.resume(); toast(ctx, "Lights back as they were")
                        })
                        add(Act("Forget the bridge", "", "Forget") { sync.leave(st, "restore"); st.hueUser = ""; st.hueGroups = emptyList(); st.hueLights = emptyList() })
                    }
                }
            }
        },
        Category("Manage from phone", Kind.Phone),
        Category("System") {
            val devOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
            val adbOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
            listOf(
                Note("App lock (only when opened from Glassio)"),
                Act("Change the code", "4 digits, 0000 at first. Asks for the current code, then the new one: OK saves it (the same code again is fine too)",
                    "${st.lockedApps.size} locked") { AppLock.change(app, ctx) },
                Toggle("Show a hint", "Your own words under the digits", { st.lockHintOn }, { st.lockHintOn = it }),
                Act("Hint", "Anything you like (even the code itself)", st.lockHint.ifBlank { "Not set" }) {
                    dialog = TextEntry("Hint under the digits", st.lockHint, "e.g. year we met") { v -> st.lockHint = v.trim(); if (v.isNotBlank()) st.lockHintOn = true }
                },
                Note("Lock an app: hold OK on it in the dock → Lock. It then asks for the code when opened from Glassio: ▲ ▼ change a digit, ◀ ▶ move, and it opens as soon as the code is right."),
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
    ).sortedBy { CATEGORY_ORDER.indexOf(it.title).let { i -> if (i < 0) 99 else i } } }
    catTitles[0] = categories.map { it.title }

    // Catalog choices made here are applied when the screen closes; only when one of them changed (every visit to
    // Settings reloaded every catalog, and the home screen was rebuilt with them just as you came back).
    val catalogSig = remember { app.catalogs.signature() }
    DisposableEffect(Unit) { onDispose { if (app.catalogs.signature() != catalogSig) app.scope.launch(Dispatchers.IO) { runCatching { app.catalogs.refresh() } } } }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    BackHandler { if (rightFocusedA[0]) runCatching { (if (searching) searchFocus else catFocus[cat]).requestFocus() } else onClose() }
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
    // Search: a query typed on Glassio's own keyboard; a result opens its page at that row.
    var jump by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val onAppMenu: (AppEntry) -> Unit = remember { { e -> appMenu(app, e, ctx, scope) { dialog = it } } }
    LaunchedEffect(jump) {
        val (c, r) = jump ?: return@LaunchedEffect
        kotlinx.coroutines.delay(200)
        runCatching { listStates[c]?.scrollToItem(r) }
        kotlinx.coroutines.delay(60)
        runCatching { rowReq(c, r).requestFocus() }
        jump = null
    }
    // One still picture behind Settings (the home screen's blurred backdrop, darkened) instead of the live home
    // screen under a black veil and two refracting glass panels: those cost the TV's GPU ~28 ms a frame (every
    // move in Settings dropped frames) and made opening it heavy. The panels are plain frosted cards on it.
    val env = LocalGlassEnv.current
    val still = env.heavy ?: env.bmp
    if (still != null) DisposableEffect(Unit) { SettingsCovers.value = true; onDispose { SettingsCovers.value = false } }
    val flat = st.panelStyle == "amoled" || st.panelStyle == "bow"
    @Composable fun Pane(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) =
        if (flat) GlassPanel(modifier, (26 * s).dp, style = st.panelStyle, darken = 0.42f, content = content)
        else Box(modifier.clip(RoundedCornerShape((26 * s).dp)).background(Color.White.copy(alpha = 0.07f))
            .border(0.6.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape((26 * s).dp)), content = content)
    Box(Modifier.fillMaxSize().onFocusChanged { anyFocus = it.hasFocus }) {
        if (still != null) {
            Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black.copy(alpha = 0.62f), androidx.compose.ui.graphics.BlendMode.SrcAtop))
        } else Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)))
        Row(Modifier.fillMaxSize().padding((28 * s).dp), horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
            Pane(Modifier.width((230 * s).dp).fillMaxHeight()) {
                // Scrolls when the list is taller than the screen (the last entry, About, was cut off).
                Column(Modifier.padding(horizontal = (14 * s).dp, vertical = (12 * s).dp).verticalScroll(rememberScrollState())) {
                    Text("Settings", color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = (8 * s).dp, bottom = (8 * s).dp))
                    // Search, at the top like on an iPhone.
                    var sf by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().padding(bottom = (8 * s).dp).clip(RoundedCornerShape((12 * s).dp))
                        .background(if (sf) Color.White.copy(alpha = 0.92f) else if (searching) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.08f))
                        .focusRequester(searchFocus)
                        .onFocusChanged { sf = it.isFocused; if (it.isFocused) searching = true }
                        .clickable { searching = true }
                        .padding(horizontal = (12 * s).dp, vertical = (7 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                        val ink = if (sf) Color(0xFF111114) else Color.White
                        Text("⌕", color = ink.copy(alpha = 0.7f), fontSize = (14 * s).sp)
                        Spacer(Modifier.width((8 * s).dp))
                        Text(query.ifBlank { com.glasslauncher.tv.data.I18n.tr("Search") }, color = ink.copy(alpha = if (query.isBlank()) 0.6f else 1f), fontSize = (13.5f * s).sp, maxLines = 1)
                    }
                    categories.forEachIndexed { i, c ->
                        var f by remember { mutableStateOf(false) }
                        // A thin line between the groups of sections (accounts · the look · the rest).
                        if (c.title in CATEGORY_BREAKS) Box(Modifier.padding(horizontal = (10 * s).dp, vertical = (6 * s).dp).fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.14f)))
                        Text(
                            com.glasslauncher.tv.data.I18n.tr(c.title), color = if (f) Color(0xFF111114) else Color.White, fontSize = (13.5f * s).sp,
                            fontWeight = if (cat == i && !searching) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(vertical = (1 * s).dp)
                                .clip(RoundedCornerShape((12 * s).dp))
                                .background(if (f) Color.White.copy(alpha = 0.92f) else if (cat == i && !searching) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                .focusRequester(catFocus[i])
                                .then(if (i == initialCat) Modifier.focusRequester(firstCat) else Modifier)
                                .onFocusChanged { f = it.isFocused; if (it.isFocused) { cat = i; searching = false } }
                                .clickable { cat = i; searching = false }
                                .padding(horizontal = (12 * s).dp, vertical = (5.5f * s).dp),
                        )
                    }
                }
            }
            Pane(
                Modifier.weight(1f).fillMaxHeight().onFocusChanged { rightFocusedA[0] = it.hasFocus }
                    // Left goes back to this page's own category; Up/Down at the ends stay in the page.
                    .focusProperties {
                        exit = { dir ->
                            when (dir) {
                                androidx.compose.ui.focus.FocusDirection.Left -> if (searching) searchFocus else catFocus[cat]
                                androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down -> FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
            ) {
                if (searching) {
                    // Every page's rows, worked out only while searching.
                    val q = query.trim().lowercase()
                    val found = if (q.isEmpty()) emptyList() else categories.mapIndexed { ci, c -> ci to (if (c.kind == Kind.Rows) c.rows() else emptyList()) }
                        .flatMap { (ci, rows) -> rows.mapIndexedNotNull { ri, r ->
                            val (t, d) = when (r) { is Toggle -> r.title to r.desc; is Adjust -> r.title to r.desc; is Act -> r.title to r.desc; is AppItem -> r.entry.label to r.entry.pkg; else -> return@mapIndexedNotNull null }
                            val tt = com.glasslauncher.tv.data.I18n.tr(t)
                            val inTitle = t.lowercase().contains(q) || tt.lowercase().contains(q)
                            if (inTitle || d.lowercase().contains(q)) Pair(inTitle, Triple(ci, ri, tt.removePrefix("→ ").trim().trimStart('·', ' ') to com.glasslauncher.tv.data.I18n.tr(categories[ci].title))) else null
                        } }.sortedByDescending { it.first }.map { it.second }.take(60)
                    SearchPane(s, query, { query = it }, found.map { it.third }) { k ->
                        val (ci, ri, _) = found[k]
                        // The selection waits on the page's name on the left while the page is built (with the
                        // results gone it fell back to the search field, which opened the search again).
                        runCatching { catFocus[ci].requestFocus() }
                        searching = false; cat = ci; shownCat = ci; lastRow[ci] = ri; jump = ci to ri
                    }
                } else {
                SettingsPage(s, categories[shownCat], shownCat, app, listStates.getOrPut(shownCat) { androidx.compose.foundation.lazy.LazyListState() },
                    rowFocus, lastRow, onAppMenu)
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
    opts += "Open app" to { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) }
    show(MenuDialog(e.label, opts))
}

/** What Settings looked up once while Glassio runs: the icon packs, and whether each TMDB token works. */
private object SettingsCache {
    val packs = mutableStateOf<List<com.glasslauncher.tv.data.IconPackInfo>?>(null)
    val tmdb = HashMap<String, Boolean?>()
}

/** Settings covers the whole screen with its own still picture: the home screen's backdrop isn't drawn under it. */
val SettingsCovers = mutableStateOf(false)

/** Settings → Menu & themes → Switch colour. */
val SETTINGS_ACCENTS = linkedMapOf("burgundy" to ("Burgundy" to 0xFFA3243F.toInt()), "green" to ("Green" to 0xFF34C759.toInt()), "purple" to ("Purple" to 0xFF7B61FF.toInt()))
fun accentOf(key: String): Color = Color(SETTINGS_ACCENTS[key]?.second ?: SETTINGS_ACCENTS.getValue("burgundy").second)

/** The text colour on a row: white, or near-black on the selected (white) row, like tvOS. */
private val LocalInk = androidx.compose.runtime.compositionLocalOf { Color.White }

/** Where a row sits in its group: 0 alone, 1 first, 2 in the middle, 3 last. Headings ([Note]) start a new group. */
private fun groupPositions(rows: List<SRow>): IntArray = IntArray(rows.size) { i ->
    if (rows[i] is Note) return@IntArray 0
    val prev = rows.getOrNull(i - 1).let { it != null && it !is Note }
    val next = rows.getOrNull(i + 1).let { it != null && it !is Note }
    when { prev && next -> 2; prev -> 3; next -> 1; else -> 0 }
}

@Composable
private fun RowView(row: SRow, s: Float, app: GlassApp, onApp: (AppEntry) -> Unit, pos: Int = 0, first: Boolean = true) {
    when (row) {
        // A short note is a group's heading; a longer one a small explanation under the heading.
        is Note -> {
            val head = row.text.length <= 44 && !row.text.endsWith(".")
            Text(com.glasslauncher.tv.data.I18n.tr(row.text), color = Color.White.copy(alpha = if (head) 0.62f else 0.45f), fontSize = ((if (head) 12f else 11f) * s).sp,
                fontWeight = if (head) FontWeight.SemiBold else null,
                modifier = Modifier.padding(start = (14 * s).dp, end = (14 * s).dp, top = ((if (first) 0f else if (head) 18f else 6f) * s).dp, bottom = (6 * s).dp))
        }
        is Toggle -> Frame(s, pos = pos, onClick = { row.set(!row.get()) }) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            val on = row.get()
            // An iOS switch: green when on.
            Box(Modifier.width((40 * s).dp).height((24 * s).dp).clip(RoundedCornerShape(50))
                .background(if (on) accentOf(app.settings.settingsAccent) else LocalInk.current.copy(alpha = 0.22f)).padding((2.5f * s).dp)) {
                Box(Modifier.size((19 * s).dp).clip(RoundedCornerShape(50)).background(Color.White).align(if (on) Alignment.CenterEnd else Alignment.CenterStart))
            }
        }
        is Adjust -> {
            var editing by remember { mutableStateOf(false) }
            Frame(s, pos = pos, editing = editing, onKey = { ev ->
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
                val ink = LocalInk.current
                if (editing) Text("‹  ${row.value()}  ›", color = Color.White, fontSize = (13.5f * s).sp, fontWeight = FontWeight.SemiBold)
                else Text(row.value() + "  ›", color = ink.copy(alpha = 0.55f), fontSize = (13 * s).sp)
            }
        }
        is Act -> Frame(s, pos = pos, onClick = row.onClick) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            val ink = LocalInk.current
            if (row.value.isNotEmpty()) Text(if (row.ok == true) "✓  " + row.value else row.value,
                color = when (row.ok) { true -> accentOf(app.settings.settingsAccent).let { a -> if (ink == Color.White) androidx.compose.ui.graphics.lerp(a, Color.White, 0.35f) else a }; false -> Color(0xFFFF5A52); else -> ink.copy(alpha = 0.55f) },
                fontSize = (12.5f * s).sp, fontWeight = if (row.ok != null) FontWeight.SemiBold else null, maxLines = 1)
        }
        is AppItem -> {
            val e = row.entry
            val hidden = e.pkg in app.settings.hiddenApps
            val art by produceState<ImageBitmap?>(null, e.pkg) { value = app.apps.artwork(e)?.bmp }
            var moving by remember { mutableStateOf(false) }
            Frame(s, pos = pos, editing = moving, onKey = { ev ->
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
                if (flags.isNotEmpty()) Text(flags, color = LocalInk.current.copy(alpha = 0.55f), fontSize = (11.5f * s).sp)
                Text(if (moving) "  ◀ ▶ · OK" else "", color = Color.White, fontSize = (12 * s).sp)
            }
        }
    }
}

@Composable
private fun Label(title: String, desc: String, s: Float, modifier: Modifier) {
    val ink = LocalInk.current
    Column(modifier.padding(end = (10 * s).dp)) {
        Text(com.glasslauncher.tv.data.I18n.tr(title), color = ink, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (desc.isNotBlank()) Text(desc, color = ink.copy(alpha = 0.5f), fontSize = (10.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    pos: Int = 0,
    content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val r = (14 * s).dp
    val top = if (pos == 0 || pos == 1) r else 0.dp
    val bottom = if (pos == 0 || pos == 3) r else 0.dp
    // The group's card (rounded at its ends); the selected row lifts out of it in white, its own corners rounded.
    val shape = RoundedCornerShape(top, top, bottom, bottom)
    Box(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = 0.08f))) {
        Row(
            Modifier.fillMaxWidth()
                .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
                .clip(if (focused || editing) RoundedCornerShape(r) else shape)
                .background(when {
                    editing -> Color(0xFF7B61FF).copy(alpha = 0.55f)
                    focused -> Color.White.copy(alpha = 0.94f)
                    else -> Color.Transparent
                })
                .tvPress(onLong = onLong, onKey = onKey, onClick = onClick)
                .padding(horizontal = (14 * s).dp, vertical = (10 * s).dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(LocalInk provides if (focused && !editing) Color(0xFF111114) else Color.White) { content(focused) }
        }
        // A hairline between the rows of a group (not over the selected row).
        if ((pos == 2 || pos == 3) && !focused) Box(Modifier.align(Alignment.TopStart).padding(start = (14 * s).dp).fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.12f)))
    }
}

/** One page of Settings (its own part of the screen: moving in the list on the left doesn't build it again). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsPage(s: Float, c: Category, sc: Int, app: GlassApp, listState: androidx.compose.foundation.lazy.LazyListState,
                         rowFocus: HashMap<Long, FocusRequester>, lastRow: IntArray, onAppMenu: (AppEntry) -> Unit) {
    Column(Modifier.padding((18 * s).dp)) {
        Text(com.glasslauncher.tv.data.I18n.tr(c.title), color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = (6 * s).dp, bottom = (10 * s).dp))
        if (c.kind == Kind.Phone) PhonePane(app, s)
        else if (c.kind == Kind.About) AboutPane(app, s, onApp = onAppMenu)
        else {
            val rows = c.rows()
            // Rows between two headings sit together in one rounded group, with thin lines between them.
            val pos = groupPositions(rows)
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SettingsPivot) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(rows.size, contentType = { rows[it]::class }) { i ->
                        Box(Modifier.focusRequester(rowFocus.getOrPut(sc * 10_000L + i) { FocusRequester() }).onFocusChanged { if (it.hasFocus) lastRow[sc] = i }) {
                            RowView(rows[i], s, app, onApp = onAppMenu, pos = pos[i], first = i == 0)
                        }
                    }
                }
            }
        }
    }
}

/** Search: the query on Glassio's own keyboard (letters, numbers, space, delete), the matching rows beside it. */
@Composable
private fun SearchPane(s: Float, query: String, onQuery: (String) -> Unit, results: List<Pair<String, String>>, onPick: (Int) -> Unit) {
    val keys = remember { ("abcdefghijklmnopqrstuvwxyz1234567890").map { it.toString() } }
    // Right from the keyboard's last column goes to the results from any row (they start at the top: from the
    // lower rows the remote found nothing to the right).
    val resFocus = remember { FocusRequester() }
    val toResults = Modifier.focusProperties { right = if (results.isNotEmpty()) resFocus else FocusRequester.Default }
    Row(Modifier.fillMaxSize().padding((18 * s).dp), horizontalArrangement = Arrangement.spacedBy((18 * s).dp)) {
        Column(Modifier.width((300 * s).dp)) {
            Text(com.glasslauncher.tv.data.I18n.tr("Search"), color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = (6 * s).dp, bottom = (10 * s).dp))
            // The query, like a text field.
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape((12 * s).dp)).background(Color.White.copy(alpha = 0.10f)).padding(horizontal = (14 * s).dp, vertical = (10 * s).dp)) {
                Text(if (query.isEmpty()) com.glasslauncher.tv.data.I18n.tr("Type with the keys below") else "$query|", color = Color.White.copy(alpha = if (query.isEmpty()) 0.45f else 1f), fontSize = (15 * s).sp, maxLines = 1)
            }
            Spacer(Modifier.height((12 * s).dp))
            @Composable fun Key(label: String, w: Float, edge: Boolean = false, onClick: () -> Unit) {
                var f by remember { mutableStateOf(false) }
                Box((if (edge) toResults else Modifier).width((w * s).dp).height((34 * s).dp).clip(RoundedCornerShape((9 * s).dp))
                    .background(if (f) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.08f))
                    .onFocusChanged { f = it.isFocused }.tvPress(onClick = onClick), contentAlignment = Alignment.Center) {
                    Text(label, color = if (f) Color(0xFF111114) else Color.White, fontSize = (14 * s).sp, fontWeight = FontWeight.Medium)
                }
            }
            keys.chunked(6).forEach { line ->
                Row(Modifier.padding(bottom = (5 * s).dp), horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
                    line.forEachIndexed { i, k -> Key(k, 45.8f, edge = i == line.lastIndex) { onQuery(query + k) } }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy((5 * s).dp)) {
                Key(com.glasslauncher.tv.data.I18n.tr("Space"), 147.4f) { onQuery("$query ") }
                Key("⌫", 71f) { onQuery(query.dropLast(1)) }
                Key(com.glasslauncher.tv.data.I18n.tr("Clear"), 71f, edge = true) { onQuery("") }
            }
        }
        Column(Modifier.weight(1f)) {
            Spacer(Modifier.height((44 * s).dp))
            when {
                query.isBlank() -> Text(com.glasslauncher.tv.data.I18n.tr("Find any setting by its name"), color = Color.White.copy(alpha = 0.5f), fontSize = (13 * s).sp, modifier = Modifier.padding(start = (6 * s).dp))
                results.isEmpty() -> Text(com.glasslauncher.tv.data.I18n.tr("No results"), color = Color.White.copy(alpha = 0.5f), fontSize = (13 * s).sp, modifier = Modifier.padding(start = (6 * s).dp))
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(results.size) { i ->
                        val (title, page) = results[i]
                        Box(if (i == 0) Modifier.focusRequester(resFocus) else Modifier) {
                        Frame(s, pos = if (results.size == 1) 0 else if (i == 0) 1 else if (i == results.lastIndex) 3 else 2, onClick = { onPick(i) }) {
                            Label(title, page, s, Modifier.weight(1f))
                            Text("›", color = LocalInk.current.copy(alpha = 0.5f), fontSize = (15 * s).sp)
                        }
                        }
                    }
                }
            }
        }
    }
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
            add(Act("What's new", "A short tour of what's new and where to change it", "Open") { WhatsNew.show() })
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
/** Sections that start a new group in the list on the left (a thin line above them). */
private val CATEGORY_BREAKS = setOf("Library", "Cameras", "Performance")

val CATEGORY_ORDER = listOf("Connections", "Catalogs", "Continue Watching", "Library", "Carousel", "Dock & icons", "Menu & themes",
    "Cameras", "Background & glass", "Screensaver", "Music", "Hue lights", "Performance", "Manage from phone", "System", "About")

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
