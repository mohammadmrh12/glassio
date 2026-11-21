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
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import com.glasslauncher.tv.data.PlatformRows as PR
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
/** [sub]: the page this row opens (the search and Changed look into it too). */
private class Act(val title: String, val desc: String, val value: String = "", val ok: Boolean? = null, val sub: SubPage? = null, val onClick: () -> Unit) : SRow
private class Note(val text: String) : SRow
private class AppItem(val entry: AppEntry) : SRow

/**
 * A page opened from a row of a Settings page (a long list behind one row: the add-ons, the catalogs …). It takes the
 * page's place on the right, with the section's name before its own; Back goes back to the row that opened it.
 */
private class SubPage(val title: String, val kind: Kind = Kind.Rows, val rows: @Composable () -> List<SRow> = { emptyList() })
/** Where the pages opened from rows keep their selected row (the sections use the slots below it). */
private const val SUB_SLOT = 24

private enum class Kind { Rows, Phone, About }

/** A row somewhere in Settings: its section, the pages opened on the way to it (each with the row that opens it), its place. */
private class Found(val ci: Int, val chain: List<Pair<SubPage, Int>>, val ri: Int, val row: SRow)
/** A row's name as the person reads it (no "· " of a row under another, no "→ " of a link). */
private fun norm(t: String) = t.trim().removePrefix("→").trim().trimStart('·', ' ').trim()

/**
 * Settings → New: the rows each version added (their titles), marked until each is used, and gathered on one page.
 * A new version's list replaces the last one's.
 */
private val NEW_IN = mapOf(
    "4.0.0" to listOf("Profile", "At start", "Name", "Picture", "Greeting", "Catalogs for apps", "Apps and their catalogs",
        "Continue Watching in app carousels", "Play opens the streaming app", "Apps", "Find links with JustWatch", "Country",
        "Platform rows", "Rows per platform", "Carousel shade", "Button look", "Music & Inputs page"),
    "4.1.0" to listOf("Search", "Search icon in the dock", "OK on a title", "Streaming services", "Service apps", "Nuvio and Stremio",
        "Nuvio and Stremio apps", "Nuvio plays at once", "Stremio for a film", "Quality", "Largest file", "HDR and Dolby Vision",
        "Leave out cinema recordings", "Torrents", "Add-ons asked", "Keep the words searched", "Search in the menu", "Page behind the search", "Titles as", "Stay in the search", "Card size", "Rating",
        "Only on my services", "Popular this week", "Start with an empty bar", "YouTube", "YouTube opens in", "Music", "Music apps", "A song plays under the search", "Order of the list"),
)
/** The rows marked New on this screen, and what to do when one is used. */
private class NewMarks(val titles: Set<String>, val used: (String) -> Unit)
private val LocalNew = androidx.compose.runtime.staticCompositionLocalOf { NewMarks(emptySet()) {} }
/** Changed leaves these out: where Settings was left, and what only the setup and the tour keep. */
private val NOT_CHANGES = setOf("settingsLastCat", "settingsLastRow", "settingsLayout", "settingsNewFor", "settingsNewSeen", "setupDone", "tourSeen")
private const val NEW_SLOT = 30
private const val CHANGED_SLOT = 31
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
fun SettingsScreen(app: GlassApp, askedCat: Int, onClose: () -> Unit) {
    // Opened plainly (the menu, the settings button): back where you were last time, the same page and row.
    // Opened for a particular page (a link, Manage from phone): that page.
    // (Not back to About: it has no rows to come back to, and Settings kept opening on it.)
    // (The sections were put in a new order once: the section remembered from the old order becomes the one that
    // holds it now.)
    if (app.settings.settingsLayout < SETTINGS_LAYOUT) {
        app.settings.settingsLastCat = sectionAfterReorder(app.settings.settingsLastCat); app.settings.settingsLastRow = -1
        app.settings.settingsLayout = SETTINGS_LAYOUT
    }
    val newVer = com.glasslauncher.tv.BuildConfig.VERSION_NAME.substringBefore('-')
    if (app.settings.settingsNewFor != newVer) {
        val fresh = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).let { it.firstInstallTime == it.lastUpdateTime } }.getOrDefault(false)
        app.settings.settingsNewSeen = if (fresh) NEW_IN[newVer].orEmpty() else emptyList()
        app.settings.settingsNewFor = newVer
    }
    // Manage from phone is a page of System now: asked for from outside, Settings opens on it.
    val phoneAsked = askedCat == PHONE_CATEGORY
    val asked = if (phoneAsked) CATEGORY_ORDER.indexOf("System") else askedCat.coerceIn(0, CATEGORY_ORDER.lastIndex)
    val resume = asked == 0 && (app.settings.settingsLastCat > 0 || app.settings.settingsLastRow >= 0) &&
        app.settings.settingsLastCat in 1 until CATEGORY_ORDER.indexOf("About")
    val initialCat = if (resume) app.settings.settingsLastCat else asked
    val s = LocalScale.current
    val ctx = LocalContext.current
    val st = app.settings
    val scope = rememberCoroutineScope()
    val traktCode by app.cw.traktCode.collectAsState()
    val syncStatus by app.sync.status.collectAsState()
    val packState by com.glasslauncher.tv.data.IconPackStore.status.collectAsState()
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
    // Pages opened from a row (see SubPage), the last one on top.
    val subs = remember { androidx.compose.runtime.mutableStateListOf<SubPage>().apply { if (phoneAsked) add(SubPage("Manage from phone", Kind.Phone)) } }
    // While the page on the right is swapped the selection waits on an unseen place inside that pane ([hold]): left
    // on a row that goes away it fell to the search field, which opened the search; kept on the section's name
    // (as it was at first) the section lit up white for 0.2-0.3 s at every page opened and every Back.
    val holdFocus = remember { FocusRequester() }
    val holding = remember { booleanArrayOf(false) } // (the place can only be selected while a page is being swapped)
    val holdHas = remember { booleanArrayOf(false) }
    fun hold() {
        holding[0] = true
        if (!runCatching { holdFocus.requestFocus() }.getOrDefault(false)) runCatching { catFocus[cat].requestFocus() }
    }
    fun openSub(title: String, kind: Kind = Kind.Rows, rows: @Composable () -> List<SRow> = { emptyList() }) { hold(); subs.add(SubPage(title, kind, rows)) }
    fun openSub(p: SubPage) { hold(); subs.add(p) }
    /** A row that opens [sp] (its count or state on the right, from [more]). */
    fun pageRow(title: String, desc: String, value: String, ok: Boolean? = null, sp: SubPage): SRow = Act(title, desc, value, ok, sub = sp) { openSub(sp) }
    fun closeSub() { if (subs.isNotEmpty()) { hold(); subs.removeAt(subs.lastIndex) } }
    /** A row that opens a list: its count on the right. */
    fun more(n: Any) = "${com.glasslauncher.tv.data.I18n.tr(n.toString())}  ›"
    val weatherFound by app.weather.found.collectAsState()
    // The Hue pages read the bridge again when this changes (connected, forgotten).
    val hueRev = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // Every setting has one place. Where it is needed elsewhere too, a row leads to it: the section opens with that
    // row selected ([row] is its title there, or a group's heading: the first row under it).
    var seek by remember { mutableStateOf<Pair<Int, String>?>(null) }
    fun link(section: String, row: String, desc: String, title: String = row): SRow = Act("→ " + com.glasslauncher.tv.data.I18n.tr(title.trim().trimStart('·', ' ')), desc, com.glasslauncher.tv.data.I18n.tr(section)) {
        val i = CATEGORY_ORDER.indexOf(section)
        if (i >= 0) { hold(); cat = i; shownCat = i; seek = i to row }
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
    // Signing out asks first (one stray OK on the row signed you out).
    fun signOutAsk(name: String, run: () -> Unit) { dialog = MenuDialog("Sign out of $name?", listOf("Sign out" to run, "Cancel" to { })) }
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

    /**
     * Which of the Nuvio account's profiles Glassio uses (Continue Watching, Library, add-ons), under the account row.
     * The main profile stays the default: it goes straight to it, as before (#16).
     */
    /** Your picture: the settings button's and, with profiles, this profile's (one picture for both). */
    fun pictureDialog() {
        dialog = MenuDialog("Settings button picture", buildList {
            // A QR code: the phone opens a page with one Upload button; this closes by itself when the picture arrives.
            add("From your phone (QR code)" to {
                scope.launch(Dispatchers.IO) {
                    val base = com.glasslauncher.tv.net.PhoneHost.start(app)
                        ?: run { kotlinx.coroutines.withContext(Dispatchers.Main) { toast(ctx, "The TV is not on a network") }; return@launch }
                    val f = app.icons.fileFor(com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON)
                    val before = if (f.isFile) f.lastModified() else 0L
                    val code = kotlinx.coroutines.flow.MutableStateFlow<String?>("")
                    dialog = QrCodeDialog("Settings button picture", base.replace("/?t=", "/button?t="), code,
                        "Scan with your phone and press Upload a picture. This closes by itself when it arrives.")
                    var n = 0
                    while (dialog != null && n++ < 600) {
                        kotlinx.coroutines.delay(1000)
                        if (f.isFile && f.lastModified() != before) break
                    }
                    val got = f.isFile && f.lastModified() != before
                    code.value = null
                    com.glasslauncher.tv.net.PhoneHost.leave(app)
                    if (got) kotlinx.coroutines.withContext(Dispatchers.Main) { st.settingsIcon = "picture"; toast(ctx, "Picture set") }
                }
            })
            add("From a link" to {
                dialog = TextEntry("Picture link", "", "https://… (png, jpg, webp)") { u ->
                    if (u.isNotBlank()) scope.launch(Dispatchers.IO) {
                        val r = runCatching { app.icons.setFromUrl(com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON, u.trim()) }
                        kotlinx.coroutines.withContext(Dispatchers.Main) { toast(ctx, if (r.isSuccess) "Picture set" else "Couldn't use it: ${r.exceptionOrNull()?.message.orEmpty()}") }
                    }
                }
            })
            if (app.icons.has(com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON)) add("Remove the picture" to { app.icons.reset(com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON) })
        })
    }

    // The country whose streaming offers count: the platform under a title, title links and the Library's platform rows.
    fun countryRow(indent: Boolean = false) = Adjust(if (indent) "  · Country" else "Country", "What each service has differs by country: platform names, title links, platform rows",
        { com.glasslauncher.tv.data.Region.label(st) }, {
            val list = com.glasslauncher.tv.data.Region.CHOICES
            st.watchCountry = cycle(list, st.watchCountry.takeIf { c -> c in list } ?: "", it)
            com.glasslauncher.tv.data.PlatformLinks.forget(ctx); app.catalogs.clearEnrichment()
        })

    fun nuvioProfileRows(): List<SRow> {
        val nv = app.library.nuvio
        if (!nv.connected) return emptyList()
        val P = com.glasslauncher.tv.data.Profiles
        val known = P.known(ctx)
        fun nameOf(id: Int) = known.firstOrNull { it.id == id }?.name ?: if (id == 1) "Main profile" else "Profile $id"
        return listOf(
            Act("Profile", "Each profile of your Nuvio account has its own Glassio: its look, dock, catalogs, Library and Continue Watching. Glassio starts again when you change it",
                nv.profileName.ifBlank { nameOf(nv.profile) }) { ProfilesUi.show(app) },
            Act("  · At start", "When the TV starts or wakes: ask who is watching, or open one profile straight away",
                if (P.startMode(ctx) == "ask") "Ask" else "Automatic (${nameOf(P.autoId(ctx))})") {
                dialog = MenuDialog("At start", listOf(((if (P.startMode(ctx) == "ask") "✓  " else "") + "Ask who is watching") to { P.setStartMode(ctx, "ask"); tvRev.intValue++; Unit }) +
                    known.ifEmpty { listOf(com.glasslauncher.tv.data.Profiles.Info(P.active(ctx), nameOf(P.active(ctx)))) }.map { p ->
                        ((if (P.startMode(ctx) == "auto" && P.autoId(ctx) == p.id) "✓  " else "") + "Automatic: ${p.name}") to { P.setStartMode(ctx, "auto"); P.setAutoId(ctx, p.id); tvRev.intValue++; Unit }
                    })
            },
            Act("  · Name", "As Glassio shows this profile. Empty: the name it has in Nuvio", P.nameOverride(ctx, P.active(ctx)).ifBlank { "As in Nuvio" }) {
                dialog = TextEntry("Profile name", P.nameOverride(ctx, P.active(ctx)), "Empty: the name in Nuvio") { n ->
                    P.setName(ctx, P.active(ctx), n.trim()); tvRev.intValue++
                    nv.setProfile(P.active(ctx), P.known(ctx).firstOrNull { it.id == P.active(ctx) }?.name ?: n.trim())
                }
            },
            Act("  · Picture", "Shown for this profile and as the settings button (one picture for both). From your phone (QR code) or a link",
                if (app.icons.has(com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON)) "Change" else "Add") { pictureDialog() },
        )
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
                    } else Act("Nuvio account", "Signed in" + app.library.nuvio.email.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty() + ": Library, add-ons and saving use it", "Sign out") { signOutAsk("Nuvio") { app.library.nuvio.signOut(); app.library.nuvioList.value = emptyList(); st.libraryNuvio = st.libraryNuvio; toast(ctx, "Signed out of Nuvio") } }


    // Made once: the pages read what they show when they are drawn (rebuilt with every change, the whole of
    // Settings was composed again on each one).
    val categories = remember { listOf(
        Category("Accounts & profiles") {
            tvRev.intValue
            val nv = app.library.nuvio
            buildList {
                add(Note("Nuvio"))
                add(nuvioAccountRow())
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
                add(Act("  · Nuvio server", "Your own Nuvio backend, if you host one. Easier to type on the phone page", if (nv.custom) hostOf(nv.server) else "Official") {
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
                add(Adjust("Open titles in", "Auto uses the first installed: Nuvio Legacy, Nuvio (official), Nuvio Arc, Nuvio Re-Shaped", {
                    val n = NuvioLaunch.NAMES[st.nuvioTarget] ?: "Auto"
                    val r = NuvioLaunch.resolve(ctx, st)
                    when {
                        st.nuvioTarget == "auto" -> "Auto · " + (if (NuvioLaunch.TARGETS.values.any { NuvioLaunch.installed(ctx, it) }) NuvioLaunch.NAMES[r] else "none installed")
                        !NuvioLaunch.installed(ctx, NuvioLaunch.TARGETS.getValue(r)) -> "$n (not installed)"
                        else -> n
                    }
                }, { st.nuvioTarget = cycle(listOf("auto") + NuvioLaunch.TARGETS.keys, st.nuvioTarget, it) }))

                add(Note("Other accounts"))
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
                    add(Act("Stremio account", "Signed in" + sm.email.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(), "Sign out", ok = true) { signOutAsk("Stremio") { sm.signOut(); tvRev.intValue++; toast(ctx, "Signed out of Stremio") } })
                }
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
                    else add(Act("Trakt account", "Signed in as ${trakt.user.ifBlank { "your account" }}", "Sign out", ok = true) { signOutAsk("Trakt") { trakt.signOut(); st.cwTrakt = st.cwTrakt; toast(ctx, "Signed out of Trakt") } })
                } else add(Note("This build has no Trakt app id."))
                val sk = app.cw.simkl
                if (!sk.connected) add(Act("Connect Simkl", "Scan a QR code with your phone and approve" + (if (!sk.available) " (first its app id, from the phone)" else ""), "") { connectSimkl() })
                else add(Act("Simkl account", "Signed in" + sk.user.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(), "Sign out", ok = true) { signOutAsk("Simkl") { sk.signOut(); tvRev.intValue++; toast(ctx, "Signed out of Simkl") } })
                add(tmdbRow())

                nuvioProfileRows().let { if (it.isNotEmpty()) { add(Note("Profiles")); addAll(it) } }

                add(Note("Keep accounts the same"))
                add(Toggle("Sync Library, likes and Continue Watching", "Keeps them the same on every connected account: Nuvio, Trakt and Simkl (Stremio is read). Removing a title removes it everywhere",
                    { st.syncAll }, { on -> st.syncAll = on; if (on) app.sync.runSoon { m -> toast(ctx, m) } }))
                if (st.syncAll) add(Act("Sync now", syncStatus.ifBlank { "Runs on its own every 30 minutes" }, "") {
                    toast(ctx, "Syncing…"); app.sync.runSoon { m -> toast(ctx, m); tvRev.intValue++ }
                })
            }
        },
        Category("Catalogs & add-ons") {
            val AC = com.glasslauncher.tv.data.AppCatalogs
            val all = app.catalogs.allAddons()
            fun onOf(u: String) = catalogs.count { it.addon == u && it.key in selected }
            fun ofAddon(u: String) = catalogs.count { it.addon == u }
            // One add-on: its catalogs, each on or off, and removing it.
            fun addonPage(u: String, mine: Boolean) = SubPage(app.catalogs.addonName(u)) {
                buildList {
                    val own = catalogs.filter { it.addon == u }
                    add(Note(if (own.isEmpty()) "This add-on has no catalogs for the carousel" else "Catalogs in the carousel"))
                    own.forEach { c -> add(Toggle(c.name, c.type, { c.key in selected }, { app.catalogs.toggleCatalog(c.key) })) }
                    add(Note("Add-on"))
                    if (mine) add(Act("Remove add-on", hostOf(u), "Remove") {
                        dialog = MenuDialog("Remove ${app.catalogs.addonName(u)}?", listOf("Remove add-on" to { closeSub(); io { app.catalogs.removeAddon(u) }; Unit }))
                    }) else add(Act("From your Nuvio account", "Add or remove it in Nuvio; it updates here on the next refresh", hostOf(u)) {
                        toast(ctx, "Add or remove it in Nuvio; it updates here on the next refresh") })
                }
            }
            // The add-ons: ways to add one, then each add-on on one row.
            fun addonsPage() = SubPage("Add-ons") {
                buildList {
                    add(Note("Add an add-on"))
                    add(Act("Add from my phone (QR)", "Scan the code, paste Nuvio manifest links and your TMDB token", "") { phoneDialog = true })
                    add(Act("Add add-on link", "Paste a manifest link", "") {
                        dialog = TextEntry("Add-on link", "", "https://…/manifest.json or stremio://…") { u -> io { app.catalogs.addAddon(u) } }
                    })
                    add(Act("Connect to Nuvio", "Open Nuvio > Add-ons > Manage from phone first", "") { io { app.catalogs.connectLocal() } })
                    val mine = addons.sortedBy { app.catalogs.addonName(it).lowercase() }
                    val theirs = accountAddons.size.let { _ -> app.catalogs.accountOnly() }.sortedBy { app.catalogs.addonName(it).lowercase() }
                    add(Note(if (mine.isEmpty() && theirs.isEmpty()) "No add-ons yet" else "Your add-ons"))
                    mine.forEach { u -> add(pageRow(app.catalogs.addonName(u), hostOf(u), more("${onOf(u)} / ${ofAddon(u)}"), sp = addonPage(u, true))) }
                    theirs.forEach { u -> add(pageRow(app.catalogs.addonName(u), "From your Nuvio account · " + hostOf(u), more("${onOf(u)} / ${ofAddon(u)}"), sp = addonPage(u, false))) }
                }
            }
            // The carousel's catalogs: the ones that were on when the page opened on top (a row doesn't jump away
            // from under the selection when it is switched), the others under their add-on.
            fun carouselPage(): SubPage {
                val onAtOpen = selected.toSet()
                return SubPage("Catalogs in the carousel") {
                    buildList {
                        add(Act("Select all catalogs", "", "") { app.catalogs.selectAll(true); io { app.catalogs.refresh(); "Done" } })
                        add(Act("Select none", "", "") { app.catalogs.selectAll(false); io { app.catalogs.refresh(); "Done" } })
                        if (catalogs.isEmpty()) add(Note("No catalogs yet. Add an add-on first."))
                        fun row(c: com.glasslauncher.tv.data.CatalogRef, withAddon: Boolean) =
                            Toggle(c.name, if (withAddon) "${c.addonName} · ${c.type}" else c.type, { c.key in selected }, { app.catalogs.toggleCatalog(c.key) })
                        val top = catalogs.filter { it.key in onAtOpen }.sortedBy { it.addonName.lowercase() }
                        if (top.isNotEmpty()) { add(Note("In the carousel")); top.forEach { add(row(it, true)) } }
                        catalogs.filter { it.key !in onAtOpen }.groupBy { it.addonName }.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, list) ->
                            add(Note(name)); list.forEach { add(row(it, false)) }
                        }
                    }
                }
            }
            // The apps a catalog can be linked to, each on one row with what is linked.
            fun appsPage() = SubPage("Apps and their catalogs") {
                buildList {
                    add(Note("Shown in the carousel while the app is selected in the dock"))
                    app.apps.ordered().filterNot { com.glasslauncher.tv.data.Channels.keepsCarousel(it.pkg) || it.pkg.startsWith("glassio.") }
                        .map { it to AC.keysFor(st, catalogs, it.pkg) }.sortedByDescending { it.second.size }.forEach { (e, keys) ->
                            add(Act(e.label, catalogs.filter { it.key in keys }.joinToString(" · ") { it.name }.ifBlank { "None linked" }, more(keys.size)) {
                                dialog = appCatalogsDialog(app, e.pkg, e.label) })
                        }
                }
            }
            buildList {
                add(Note("Add-ons"))
                add(pageRow("Add-ons", all.joinToString(" · ") { app.catalogs.addonName(it) }.ifBlank { "None yet: add one from your phone, a link or Nuvio" }, more(all.size), sp = addonsPage()))
                add(Toggle("  · Add-ons from my Nuvio account", if (app.library.nuvio.connected) "The add-ons on your Nuvio account (${app.library.nuvio.email.ifBlank { "signed in" }}) too. One that's already here isn't added twice"
                    else "Sign in to Nuvio first (Connections)", { st.catalogsNuvio }, { on -> io { app.catalogs.useAccount(on) } }))
                // (Only with a Stremio account signed in: there is nothing to take them from otherwise.)
                if (app.cw.stremio.connected) add(Toggle("  · Catalogs from Stremio's add-ons", "One that's already here isn't added twice", { st.catalogsStremio }, { on -> io { app.catalogs.useAccount(on, "stremio") } }))
                add(Note("Carousel catalogs"))
                add(pageRow("Catalogs in the carousel", if (catalogs.isEmpty()) "No catalogs yet. Add an add-on first" else "Which catalogs the carousel takes its titles from",
                    more("${catalogs.count { it.key in selected }} / ${catalogs.size}"), sp = carouselPage()))
                add(Act("Refresh catalogs", status, "") { io { app.catalogs.refresh(); app.catalogs.status.value } })
                // Catalogs of an app (see AppCatalogs): the carousel shows an app's own catalogs while it is selected in the dock.
                add(Note("Catalogs for apps"))
                add(Toggle("Catalogs for apps", "With a streaming app selected in the dock, the carousel shows the catalogs linked to it. Catalogs named after a service are linked on their own",
                    { st.appCatalogsOn }, { st.appCatalogsOn = it }))
                if (st.appCatalogsOn) {
                    val linked = app.apps.ordered().filter { AC.keysFor(st, catalogs, it.pkg).isNotEmpty() }
                    add(pageRow("  · Apps and their catalogs", linked.joinToString(" · ") { e -> "${e.label} ${AC.keysFor(st, catalogs, e.pkg).size}" }.ifBlank { "None linked yet" }, more(linked.size), sp = appsPage()))
                    add(Adjust("  · Continue Watching in app carousels", "Titles in progress open the carousel of the app whose service has them in your country, when that app is on this TV (a Netflix title in Netflix's, also when you watched it in Nuvio); else the app it was watched in. Off with Continue Watching off",
                        { if (st.appCatalogsCw == "off") "Off" else "At the start" }, { st.appCatalogsCw = if (st.appCatalogsCw == "off") "start" else "off" }))
                }
                // One switch for both: a title of the carousel or the Library, and a title of an app's own carousel.
                add(Note("Play opens the app"))
                add(Toggle("Play opens the streaming app", "A title that is on Netflix, Apple TV, Prime Video or Disney+ opens on its own page in that app, if it is installed; a title from an app's catalogs, in that app. Any other title opens as before",
                    { st.playInApp }, { st.playInApp = it }))
                if (st.playInApp) {
                    // The apps Play may open: the streaming apps on this TV and the apps that have catalogs (all at first).
                    val can = com.glasslauncher.tv.data.PlatformLinks.installed(ctx)
                    val apps = app.apps.ordered(includeHidden = true).filter { it.pkg in can || AC.keysFor(st, catalogs, it.pkg).isNotEmpty() }.distinctBy { it.pkg }
                    val off = st.appCatalogsPlayOff.toSet()
                    val on = apps.filter { it.pkg !in off }
                    add(Act("  · Apps", if (apps.isEmpty()) "No streaming app on this TV yet" else if (on.size == apps.size) "All: " + apps.joinToString(" · ") { it.label } else on.joinToString(" · ") { it.label }.ifBlank { "None" }, more(on.size)) {
                        if (apps.isEmpty()) return@Act
                        dialog = PickDialog("Play opens the streaming app", "The others open a title as usual", apps.map { e ->
                            PickRow(e.label, "", { if (e.pkg in st.appCatalogsPlayOff) "" else "✓" }) {
                                st.appCatalogsPlayOff = if (e.pkg in st.appCatalogsPlayOff) st.appCatalogsPlayOff - e.pkg else st.appCatalogsPlayOff + e.pkg
                                com.glasslauncher.tv.data.PlatformLinks.forget(ctx)
                            }
                        }, st.menuStyle)
                    })
                    add(Toggle("  · Find links with JustWatch", "Each app has its own number for a title. Wikidata has some; JustWatch knows many more and what can be watched now, through its site's own search (not an official service)",
                        { st.linkJustWatch }, { st.linkJustWatch = it; com.glasslauncher.tv.data.PlatformLinks.forget(ctx) }))
                }
                add(countryRow())
            }
        },
        Category("Continue Watching & Library") {
            tvRev.intValue
            val cwList by app.cw.entries.collectAsState()
            val trakt = app.cw.trakt
            val sm = app.cw.stremio
            val sk = app.cw.simkl
            val nvOn = app.library.nuvio.connected
            buildList {
                add(Note("Continue Watching"))
                add(Adjust("Show Continue Watching", "Where titles you are watching appear", {
                    when (st.continueMode) { "off" -> "Off"; "mixed" -> "Mixed with catalogs"; else -> "At the start" }
                }, { st.continueMode = cycle(listOf("first", "mixed", "off"), st.continueMode, it) }))
                if (st.continueMode == "first") add(Adjust("Slides at the start", "How many titles in progress open the carousel", {
                    if (st.cwLead <= 0) "All" else "${st.cwLead}"
                }, { st.cwLead = cycle(listOf(1, 2, 3, 4, 5, 0), st.cwLead, it) }))
                add(Toggle("Exactly as in Nuvio", "The titles and their progress from Nuvio only, even with Trakt, Simkl and Stremio syncing too (their progress for the same title could be older or off)",
                    { st.cwNuvioOnly }, { st.cwNuvioOnly = it; app.cw.refreshItems() }))
                add(Toggle("Open where it was watched", "A title here opens in its own app: Nuvio Legacy, Nuvio or Stremio (Trakt and Simkl titles: Open titles in)",
                    { st.cwOpenSource }, { st.cwOpenSource = it }))
                add(Toggle("Open on what I just watched", "Back from Nuvio, the carousel shows that title first", { st.continueJump }, { st.continueJump = it }))
                add(Toggle("Progress bar", "Shows Continue · S1 · E3 · 42% under the title", { st.cwProgress }, { st.cwProgress = it }))
                add(Toggle("Next up", "After you finish an episode, the next one waits here (from the Nuvio account and what Glassio saw)", { st.cwNextUp }, { st.cwNextUp = it; app.cw.pollNow() }))
                // The titles in the list, one by one, behind one row.
                add(pageRow("Clear the list", "Every title in progress, or one of them", more(cwList.size), sp = SubPage("Clear the list") {
                        val list by app.cw.entries.collectAsState()
                        buildList {
                            add(Act("Clear the whole list", "${list.size} titles", "") { app.cw.clear() })
                            if (list.isNotEmpty()) add(Note("One title"))
                            list.forEach { e ->
                                add(Act(e.imdb + if (e.season > 0) "  S${e.season}E${e.episode}" else "", "${e.app} · ${e.percent}%", "Remove") { app.cw.remove(e.key) })
                            }
                        }
                    }))

                // Where the titles in progress come from: every source in one list (signing in is in Accounts & profiles).
                add(Note("Where it comes from"))
                val srcOn = listOf(st.cwLegacy, nvOn && st.cwNuvioAccount, trakt.connected && st.cwTrakt, sm.connected && st.cwStremio, sk.connected && st.cwSimkl)
                add(pageRow("Sources", "Nuvio Legacy, your Nuvio account, Trakt, Stremio and Simkl: which of them fill Continue Watching", more("${srcOn.count { it }} / 5"), sp = SubPage("Sources") {
                        tvRev.intValue
                        val cwStatusCw by app.cw.status.collectAsState()
                        val tStatus by app.cw.traktStatus.collectAsState()
                        buildList {
                            add(Toggle("Nuvio Legacy", if (app.cw.hasLogAccess()) "Reading what you watch in Nuvio Legacy" else "Allow USB debugging for Glassio when the TV asks", { st.cwLegacy }, { st.cwLegacy = it }))
                            if (nvOn)
                                add(Toggle("Nuvio account", "What you watch in Nuvio Legacy, the official app and forks" + (cwStatusCw["Nuvio"]?.let { " · $it" } ?: ""),
                                    { st.cwNuvioAccount }, { st.cwNuvioAccount = it; app.cw.pollNow() }))
                            else add(link("Accounts & profiles", "Connect Nuvio account", "Sign in first", title = "Nuvio account"))
                            if (trakt.available) {
                                if (trakt.connected)
                                    add(Toggle("Trakt", if (trakt.connected) "Signed in as ${trakt.user.ifBlank { "your account" }}. What you pause in any app that uses Trakt (official Nuvio, Nuvio Arc, Re-Shaped, Stremio…)" + (if (tStatus.isNotBlank()) " · $tStatus" else "")
                                        else "Connect your account below to see what you paused in any app that uses Trakt", { st.cwTrakt }, { st.cwTrakt = it; app.cw.syncTraktNow() }))
                                else add(link("Accounts & profiles", "Connect Trakt", "Sign in first", title = "Trakt"))
                            }
                            if (sm.connected)
                                add(Toggle("Stremio", "What you paused in Stremio" + (cwStatusCw["Stremio"]?.let { " · $it" } ?: ""), { st.cwStremio }, { st.cwStremio = it; app.cw.pollNow() }))
                            else add(link("Accounts & profiles", "Connect Stremio", "Sign in first", title = "Stremio"))
                            if (sk.connected)
                                add(Toggle("Simkl", "What you paused in any app that uses Simkl" + (cwStatusCw["Simkl"]?.let { " · $it" } ?: ""), { st.cwSimkl }, { st.cwSimkl = it; app.cw.pollNow() }))
                            else add(link("Accounts & profiles", "Connect Simkl", "Sign in first", title = "Simkl"))
                            add(Note("How it works: Nuvio Legacy writes what you play to the TV's log (title, episode, how far). Glassio reads that log through the TV's own debugging connection (ADB), so this needs ADB once: System → TV access (ADB). Nothing leaves the TV."))
                            add(Note("The official Nuvio app, Nuvio Arc and Re-Shaped don't write that log: connect Trakt (and turn on Trakt in that Nuvio app) and what you pause there shows up here through Trakt."))
                            add(Note("Other apps (YouTube, Netflix, Apple TV…) keep their Continue Watching private on Google TV; only the system launcher may read it."))
                        }
                    }))

                add(Note("Library look"))
                add(Toggle("Ask before removing", "Hold OK on a title asks \"Remove?\" first", { st.libraryConfirm }, { st.libraryConfirm = it }))
                add(Toggle("Titles under posters", "In the Library", { st.libraryTitles }, { st.libraryTitles = it }))
                add(Adjust("Poster size", "Smaller posters leave more room at the sides", { pct(st.libraryPosterSize) },
                    { st.libraryPosterSize = ((st.libraryPosterSize * 10).roundToInt() + it) / 10f }))
                add(Adjust("Posters in a row", "", { "${st.libraryColumns}" }, { st.libraryColumns = st.libraryColumns + it }))
                add(Adjust("Space between posters", "Between posters and between rows", { pct(st.librarySpacing) },
                    { st.librarySpacing = ((st.librarySpacing * 10).roundToInt() + it) / 10f }))

                add(Note("Lists in the Library"))
                add(Toggle("Nuvio library", if (app.library.nuvio.connected) "From your Nuvio account" + (app.library.nuvio.email.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "") + ": Nuvio Legacy, the official Nuvio app and forks that sync with it"
                    else "Sign in to Nuvio first (Accounts & profiles)", { st.libraryNuvio }, { st.libraryNuvio = it; scope.launch(Dispatchers.IO) { app.library.refreshNuvio() } }))
                if (trakt.connected)
                    add(Toggle("Trakt watchlist", "", { st.libraryTrakt }, { st.libraryTrakt = it; scope.launch(Dispatchers.IO) { app.library.refreshWatchlist() } }))
                if (sk.connected) add(Toggle("Simkl plan to watch", "", { st.librarySimkl }, { st.librarySimkl = it; scope.launch(Dispatchers.IO) { app.library.refreshSimkl() } }))

                // Where the + button saves a title besides Glassio's own Library (the accounts signed in).
                val saves = buildList<SRow> {
                    if (nvOn)
                        add(Toggle("Nuvio library", "The + button also saves the title to your Nuvio account (Legacy, official Nuvio and forks see it)", { st.libraryToNuvio }, { st.libraryToNuvio = it }))
                    if (trakt.connected)
                        add(Toggle("Trakt watchlist", "The + button also adds the title on Trakt", { st.libraryToTrakt }, { st.libraryToTrakt = it }))
                    if (sk.connected) add(Toggle("Simkl", "The + button also adds the title to Simkl's Plan to watch", { st.libraryToSimkl }, { st.libraryToSimkl = it }))
                }
                if (saves.isNotEmpty()) { add(Note("+ saves to")); addAll(saves) }

                add(Note("Library rows"))
                add(pageRow("Platform rows", if (st.tmdbToken.isBlank()) "Needs the TMDB token (Accounts & profiles) · a row per streaming service"
                    else "Popular titles of each streaming service, under your lists",
                    more(when (st.libraryPlatforms) { PR.AUTO -> "Installed apps"; PR.OFF -> "Off"; else -> "${st.libraryPlatforms.split(',').count { it.isNotBlank() }}" }), sp = SubPage("Platform rows") {
                        buildList {
                            add(Adjust("Show", "The services whose app is on this TV, the ones you choose, or none", {
                                when (st.libraryPlatforms) { PR.AUTO -> "Installed apps"; PR.OFF -> "Off"; else -> "Chosen" }
                            }, {
                                val now = when (st.libraryPlatforms) { PR.AUTO -> "auto"; PR.OFF -> "off"; else -> "chosen" }
                                st.libraryPlatforms = when (cycle(listOf("auto", "chosen", "off"), now, it)) {
                                    "auto" -> PR.AUTO; "off" -> PR.OFF
                                    // Chosen starts from what was showing.
                                    else -> PR.wanted(PR.AUTO) { p -> NuvioLaunch.installed(ctx, p) }.joinToString(",") { sv -> sv.key }.ifBlank { "netflix" }
                                }
                            }))
                            if (st.libraryPlatforms != PR.AUTO && st.libraryPlatforms != PR.OFF) {
                                add(Note("Services"))
                                PR.SERVICES.forEach { sv ->
                                    add(Toggle(sv.name, "", { sv.key in st.libraryPlatforms.split(',') }, { on ->
                                        val keys = st.libraryPlatforms.split(',').filter { k -> k.isNotBlank() && k != sv.key } + (if (on) listOf(sv.key) else emptyList())
                                        // None left would read as "Installed apps": the last one stays.
                                        if (keys.isNotEmpty()) st.libraryPlatforms = PR.SERVICES.map { x -> x.key }.filter { k -> k in keys }.joinToString(",")
                                    }))
                                }
                            }
                        }
                    }))
                if (st.libraryPlatforms != PR.OFF)
                    add(Adjust("  · Rows per platform", "", { "${st.libraryPlatformRows}" }, { st.libraryPlatformRows = st.libraryPlatformRows + it }))
                add(link("Catalogs & add-ons", "Country", "What each service has differs by country: it sets these rows too"))
                add(Toggle("Continue Watching in the Library", "At the top, with where each title came from", { st.libraryCw }, { st.libraryCw = it }))
                add(Adjust("  · Card", "", { if (st.libraryCwShape == "poster") "Poster" else "Backdrop" }, { st.libraryCwShape = if (st.libraryCwShape == "poster") "backdrop" else "poster" }))
                add(Adjust("  · Card size", "", { pct(st.libraryCwSize) }, { st.libraryCwSize = ((st.libraryCwSize * 10).roundToInt() + it) / 10f }))
                add(Toggle("  · Where it came from", "Nuvio, Trakt, Simkl or Stremio, with a dot in its colour", { st.libraryCwSource }, { st.libraryCwSource = it }))
                add(Note("In the Library: OK opens the title like the carousel. Hold OK (or Menu) to remove it."))

                add(Note("Search"))
                add(pageRow("Search", "Films and series by name, from an icon in the dock, and where each can be watched. Off: nothing of it is there",
                    more(if (st.searchOn) "On" else "Off"), sp = SubPage("Search") {
                        buildList {
                            val reload = { scope.launch(Dispatchers.IO) { app.apps.reload() }; Unit }
                            add(Toggle("Search", "An icon in the dock: select it and the search bar comes up in the carousel's place; OK types. Glassio plays nothing itself: a title opens in an app",
                                { st.searchOn }, { st.searchOn = it; if (it && st.searchDock) app.apps.placeNearFront(SearchUi.DOCK_PKG); reload() }))
                            if (!st.searchOn) return@buildList
                            add(Toggle("Search icon in the dock", "Move, hide and change it like an app", { st.searchDock }, { st.searchDock = it; if (it) app.apps.placeNearFront(SearchUi.DOCK_PKG); reload() }))
                            // (The same setting as Music & app rows → Music & Inputs page: the three icons share their page.)
                            add(Adjust("Page behind the search", "The grey page, Glassio's soft coloured lights, or the carousel's picture blurred. The Music and Inputs icons' page is the same one",
                                { when (st.stagePageBg) { "glassio" -> "Glassio blur"; "carousel" -> "Carousel blur"; else -> "Grey" } },
                                { st.stagePageBg = cycle(listOf("grey", "glassio", "carousel"), st.stagePageBg, it) }))
                            add(Toggle("Search in the menu", "A Search row in the side menu; a remote button can open it too (System → Remote buttons)", { st.menuSearch }, { st.menuSearch = it }))
                            add(Note("What is searched"))
                            add(Toggle("Films", "", { st.searchMovies }, { if (it || st.searchSeries) st.searchMovies = it }))
                            add(Toggle("Series", "", { st.searchSeries }, { if (it || st.searchMovies) st.searchSeries = it }))
                            add(Adjust("Titles as", "A tall poster or a wide card with the title's backdrop. The name, the year and the rating are inside the picture, like a Continue Watching card of the Library",
                                { if (st.searchShape == "card") "Card" else "Poster" }, { st.searchShape = if (st.searchShape == "card") "poster" else "card" }))
                            add(Toggle("Stay in the search", "On the Search icon ▼ doesn't open the Library, and ▲ from the search bar doesn't go to the menu", { st.searchStay }, { st.searchStay = it }))
                            add(Adjust("Card size", "Large: the titles and the videos as big as fits above the dock with both rows whole. Standard: the smaller cards",
                                { if (st.searchCardSize == "large") "Large" else "Standard" }, { st.searchCardSize = if (st.searchCardSize == "large") "standard" else "large" }))
                            add(Toggle("Popular this week", "Shown before anything is typed. Needs the TMDB token (Accounts & profiles)", { st.searchTrending }, { st.searchTrending = it }))
                            add(Toggle("Rating", "The title's rating, the number alone, faint, beside the year and Film / Series", { st.searchRating }, { st.searchRating = it }))
                            add(Toggle("Only on my services", "Only titles that a service's app on this TV has. Titles that are on none of them are left out, also when Nuvio or Stremio could open them",
                                { st.searchMine }, { st.searchMine = it }))
                            // Videos and songs: found here (the github build), opened in their apps.
                            if (com.glasslauncher.tv.data.MediaSearch.AVAILABLE) {
                                val tube = com.glasslauncher.tv.data.Search.YOUTUBE_APPS.filter { NuvioLaunch.installed(ctx, it) }
                                add(Toggle("YouTube", if (tube.isEmpty()) "No YouTube app or SmartTube on this TV" else "Videos under the titles; one opens in the app below", { st.searchYoutube }, { st.searchYoutube = it }))
                                if (st.searchYoutube && tube.size > 1) add(Adjust("  · YouTube opens in", "", { if (st.searchYoutubeApp == "auto") "Automatic" else com.glasslauncher.tv.data.Search.appLabel(ctx, st.searchYoutubeApp) },
                                    { st.searchYoutubeApp = cycle(listOf("auto") + tube, st.searchYoutubeApp.takeIf { a -> a == "auto" || a in tube } ?: "auto", it) }))
                            }
                            val music = listOfNotNull(("spotify" to "Spotify").takeIf { NuvioLaunch.installed(ctx, com.glasslauncher.tv.data.Search.SPOTIFY) },
                                ("soundcloud" to "SoundCloud").takeIf { com.glasslauncher.tv.data.MediaSearch.AVAILABLE && NuvioLaunch.installed(ctx, com.glasslauncher.tv.data.Search.SOUNDCLOUD) })
                            add(Toggle("Music", if (music.isEmpty()) "Neither Spotify nor SoundCloud is on this TV" else "SoundCloud's songs under the titles, and Spotify's own search with the words in it", { st.searchMusic }, { st.searchMusic = it }))
                            if (st.searchMusic && music.isNotEmpty()) {
                                val on = music.filter { it.first !in st.searchMusicOff }
                                add(Act("  · Music apps", on.joinToString(" · ") { it.second }.ifBlank { "None" }, more(on.size)) {
                                    dialog = PickDialog("Music", "The ones without a tick are left out of the search", music.map { (k, n) ->
                                        PickRow(n, "", { if (k in st.searchMusicOff) "" else "✓" }) { st.searchMusicOff = if (k in st.searchMusicOff) st.searchMusicOff - k else st.searchMusicOff + k }
                                    }, st.menuStyle)
                                })
                            }
                            if (st.searchMusic && music.any { it.first == "soundcloud" && it.first !in st.searchMusicOff })
                                add(Toggle("A song plays under the search", "Glassio comes back as soon as the song starts in SoundCloud. Off: SoundCloud stays open", { st.searchMusicStay }, { st.searchMusicStay = it }))
                            add(Note("Opening a title"))
                            // The list, the first place of it, or one app chosen here (a service's app, a Nuvio app, Stremio).
                            val okApps = (if (st.searchServices) com.glasslauncher.tv.data.PlatformLinks.installed(ctx).filter { it !in st.searchServicesOff }.map { it to com.glasslauncher.tv.data.Search.appLabel(ctx, it) } else emptyList()) +
                                com.glasslauncher.tv.data.Search.players(ctx, st)
                            add(Adjust("OK on a title", "A list of the places it can be watched in, the first of them at once (services first, then Nuvio, then Stremio), or always one app: the list comes up when that app can't open the title",
                                { when (st.searchOk) { "list" -> "Lists where to watch"; "first" -> "Opens the first place"; else -> okApps.firstOrNull { it.first == st.searchOk }?.second ?: "Lists where to watch" } },
                                { st.searchOk = cycle(listOf("list", "first") + okApps.map { a -> a.first }, st.searchOk.takeIf { k -> k == "list" || k == "first" || okApps.any { a -> a.first == k } } ?: "list", it) }))
                            add(Adjust("  · Order of the list", "", { if (st.searchOrder == "players") "Nuvio and Stremio first" else "Services first" }, { st.searchOrder = if (st.searchOrder == "players") "services" else "players" }))
                            add(Toggle("Streaming services", "Netflix, Apple TV, Prime Video, Disney+: the ones on this TV that have the title in your country (JustWatch)",
                                { st.searchServices }, { st.searchServices = it }))
                            if (st.searchServices) {
                                val can = com.glasslauncher.tv.data.PlatformLinks.installed(ctx).toList()
                                val names = can.associateWith { com.glasslauncher.tv.data.Search.appLabel(ctx, it) }
                                val on = can.filter { it !in st.searchServicesOff }
                                add(Act("  · Service apps", if (can.isEmpty()) "No streaming service's app on this TV yet" else on.joinToString(" · ") { names.getValue(it) }.ifBlank { "None" }, more(on.size)) {
                                    if (can.isEmpty()) return@Act
                                    dialog = PickDialog("Streaming services", "The ones without a tick never show for a title", can.map { p ->
                                        PickRow(names.getValue(p), "", { if (p in st.searchServicesOff) "" else "✓" }) {
                                            st.searchServicesOff = if (p in st.searchServicesOff) st.searchServicesOff - p else st.searchServicesOff + p
                                        }
                                    }, st.menuStyle)
                                })
                                if (!st.linkJustWatch) add(link("Catalogs & add-ons", "Find links with JustWatch", "It is off: without it no service is found for a title"))
                                add(link("Catalogs & add-ons", "Country", "What each service has differs by country"))
                            }
                            add(Toggle("Nuvio and Stremio", "The Nuvio apps and Stremio on this TV: they can open any title", { st.searchPlayers }, { st.searchPlayers = it }))
                            if (st.searchPlayers) {
                                val can = NuvioLaunch.installedTargets(ctx)
                                val on = can.filter { it.first !in st.searchPlayersOff }
                                add(Act("  · Nuvio and Stremio apps", if (can.isEmpty()) "None of them is on this TV" else on.joinToString(" · ") { it.second }.ifBlank { "None" }, more(on.size)) {
                                    if (can.isEmpty()) return@Act
                                    dialog = PickDialog("Nuvio and Stremio", "The ones without a tick never show for a title", can.map { (t, n) ->
                                        PickRow(n, "", { if (t in st.searchPlayersOff) "" else "✓" }) {
                                            st.searchPlayersOff = if (t in st.searchPlayersOff) st.searchPlayersOff - t else st.searchPlayersOff + t
                                        }
                                    }, st.menuStyle)
                                })
                                if (on.any { it.first != "stremio" }) add(Toggle("  · Nuvio plays at once", "Instead of the title's page: the Nuvio app starts it and picks the source by its own settings. A series opens its page",
                                    { st.searchNuvioPlay }, { st.searchNuvioPlay = it }))
                                if (on.any { it.first == "stremio" } && !com.glasslauncher.tv.data.Store.PLAY) {
                                    add(Note("Stremio's sources"))
                                    add(Adjust("Stremio for a film", "Stremio takes one exact source from Glassio: your add-ons' sources are listed here by the rules below, or the first that fits plays at once. A series opens its page in Stremio",
                                        { when (st.stremioSources) { "first" -> "Plays the first that fits"; "page" -> "Its page in Stremio"; else -> "Lists the sources" } },
                                        { st.stremioSources = cycle(listOf("list", "first", "page"), st.stremioSources, it) }))
                                    if (st.stremioSources != "page") {
                                        add(Adjust("  · Quality", "That one first, then the nearest below it", { when (st.stremioQuality) { 0 -> "Highest"; 2160 -> "4K"; else -> "${st.stremioQuality}p" } },
                                            { st.stremioQuality = cycle(listOf(0, 2160, 1080, 720), st.stremioQuality, it) }))
                                        val sizes = listOf(0, 2, 4, 6, 8, 10, 15, 20, 30, 50, 80)
                                        add(Adjust("  · Largest file", "Bigger files are left out", { if (st.stremioMaxGb == 0) "No limit" else "${st.stremioMaxGb} GB" },
                                            { st.stremioMaxGb = sizes[(sizes.indexOf(st.stremioMaxGb).coerceAtLeast(0) + it).coerceIn(0, sizes.lastIndex)] }))
                                        add(Adjust("  · HDR and Dolby Vision", "", { when (st.stremioHdr) { "prefer" -> "First"; "avoid" -> "Last"; else -> "Doesn't matter" } },
                                            { st.stremioHdr = cycle(listOf("any", "prefer", "avoid"), st.stremioHdr, it) }))
                                        add(Toggle("  · Leave out cinema recordings", "CAM, telesync and the like", { st.stremioNoCam }, { st.stremioNoCam = it }))
                                        add(Toggle("  · Torrents", "Sources without a direct address (marked P2P). Stremio plays them only where its streaming server runs; on most TVs they don't start",
                                            { st.stremioP2p }, { st.stremioP2p = it }))
                                        val addons = app.catalogs.allAddons()
                                        val used = addons.filter { it !in st.stremioAddonsOff }
                                        add(Act("  · Add-ons asked", "The ones that give sources among your add-ons", more("${used.size} / ${addons.size}")) {
                                            if (addons.isEmpty()) return@Act
                                            dialog = PickDialog("Add-ons asked for sources", "One that gives no sources is simply skipped", addons.map { u ->
                                                PickRow(u.substringAfter("://").substringBefore('/'), "", { if (u in st.stremioAddonsOff) "" else "✓" }) {
                                                    st.stremioAddonsOff = if (u in st.stremioAddonsOff) st.stremioAddonsOff - u else st.stremioAddonsOff + u
                                                }
                                            }, st.menuStyle)
                                        })
                                    }
                                }
                            }
                            add(Note("Searched before"))
                            add(Toggle("Start with an empty bar", "The search opens empty each time. Off: what was typed last is still in the bar", { st.searchFresh }, { st.searchFresh = it }))
                            add(Toggle("Keep the words searched", "A list of your earlier searches under the bar while it is empty: OK on one searches it again", { st.searchRecent }, { st.searchRecent = it; if (!it) st.searchHistory = emptyList() }))
                            if (st.searchRecent && st.searchHistory.isNotEmpty()) add(Act("  · Forget them", st.searchHistory.joinToString(" · "), "") { st.searchHistory = emptyList() })
                        }
                    }))
            }
        },
        Category("Carousel") {
            tvRev.intValue
            buildList {
                add(Note("Slides"))
                add(Toggle("Auto-play", "Slides change on their own", { st.heroAutoplay }, { st.heroAutoplay = it }))
                add(Adjust("  · Slide time", "Seconds before the next title", { "${st.heroIntervalSec} s" }, { st.heroIntervalSec += it }))
                add(Adjust("Slide animation", "Apple TV: the next picture opens up from the right (from the left going back) · Slide: pushed in · Fade",
                    { when (st.slideAnim) { "apple" -> "Apple TV"; "slide" -> "Slide"; else -> "Fade" } },
                    { st.slideAnim = cycle(listOf("apple", "slide", "fade"), st.slideAnim, it) }))
                add(Adjust("Transition speed", "Fade between slides (lower = faster)", { "${st.transitionMs} ms" }, { st.transitionMs += it * 100; st.perfMode = "custom" }))

                add(Note("What it shows"))
                add(Adjust("Titles per catalog", "Reload to apply", { "${st.perCatalog}" }, { st.perCatalog += it * 5 }))
                add(Adjust("Total titles", "0 = every title, endless loop", { if (st.maxItems == 0) "Unlimited" else "${st.maxItems}" }, { st.maxItems = (st.maxItems + it * 10).coerceAtLeast(0) }))
                add(Toggle("Shuffle all catalogs", "Random order across every catalog, a new mix each time the list loads", { st.shuffle }, { st.shuffle = it }))
                add(Toggle("Surprise me", "A random mix from all your catalogs (different titles each time), fetched again on its own", { st.surprise }, { st.surprise = it; io { app.catalogs.refresh(); "New mix" } }))
                add(Adjust("  · New mix every", "", { st.surpriseMin.let { if (it < 60) "$it min" else "${it / 60} h" } }, { st.surpriseMin = cycle(listOf(15, 30, 60, 180, 360, 720), st.surpriseMin, it) }))
                add(Toggle("New titles first", "Titles a catalog has just added open the carousel for two days", { st.newFirst }, { st.newFirst = it }))
                add(Act("Reload carousel now", "") { io { app.catalogs.refresh(); app.catalogs.status.value } })

                add(Note("Title & info"))
                add(Adjust("Title style", "Logo image, plain text, or nothing", {
                    when (st.titleMode) { "logo" -> "Logo"; "text" -> "Text"; else -> "Hidden" }
                }, { st.titleMode = cycle(listOf("logo", "text", "hidden"), st.titleMode, it) }))
                add(Toggle("  · White logo", "Apple style: the logo all in white", { st.logoWhite }, { st.logoWhite = it }))
                add(Adjust("  · Logo size", "Logos are evened out so wide and tall ones look the same size", { pct(st.titleScale) }, { st.titleScale = tenth(st.titleScale, it) }))
                add(Toggle("Rating, year, platform", "The chips under the title", { st.showMeta }, { st.showMeta = it }))
                add(Adjust("  · Size", "", { pct(st.metaScale) }, { st.metaScale = tenth(st.metaScale, it) }))
                add(Toggle("Genres line", "", { st.showGenres }, { st.showGenres = it }))
                add(Adjust("  · Size", "", { pct(st.genresScale) }, { st.genresScale = tenth(st.genresScale, it) }))
                add(Toggle("Synopsis", "", { st.showSynopsis }, { st.showSynopsis = it }))
                add(Toggle("  · Only when selected", "Hidden while you browse the apps", { st.synopsisOnFocus }, { st.synopsisOnFocus = it }))
                add(Adjust("  · Size", "", { pct(st.synopsisScale) }, { st.synopsisScale = tenth(st.synopsisScale, it) }))
                add(Adjust("  · Lines", "", { "${st.synopsisLines}" }, { st.synopsisLines += it }))
                add(Adjust("Synopsis language", "Needs a TMDB token. English is used when a title has no text in this language", { com.glasslauncher.tv.data.LANG_NAMES[st.descLang] ?: "English" }, {
                    st.descLang = cycle(com.glasslauncher.tv.data.TMDB_LANGS.keys.toList(), st.descLang, it); app.catalogs.clearEnrichment()
                }))

                add(Note("Buttons"))
                add(Adjust("Buttons", "Play + Next: like Apple TV, Right or OK on the round button shows the next title, Left on Play the previous one",
                    { when (st.heroButtons) { "apple" -> "Play + Next"; "play" -> "Play only"; else -> "Hidden" } },
                    { st.heroButtons = cycle(listOf("apple", "play", "hidden"), st.heroButtons, it) }))
                add(Adjust("  · Button look", "Dark: as in the Apple TV app · Follow the theme: the plates of your theme (glass, Black AMOLED, Black on white), the selected one as Selected icon plate says",
                    { if (st.heroButtonLook == "theme") "Follow the theme" else "Dark" }, { st.heroButtonLook = if (st.heroButtonLook == "theme") "dark" else "theme" }))
                add(Toggle("  · + button", "Between Play and Next: saves the title to your Library", { st.heroPlus }, { st.heroPlus = it }))

                add(Note("Dots"))
                add(Adjust("Dots style", "", {
                    when (st.dotsMode) { "window" -> "Dots"; "liquid" -> "Liquid glass dots"; "progress" -> "Progress bar"; else -> "Hidden" }
                }, { st.dotsMode = cycle(listOf("window", "liquid", "progress", "hidden"), st.dotsMode, it) }))
                add(Toggle("Auto-hide", "Show them only when the carousel is selected", { st.dotsAutoHide }, { st.dotsAutoHide = it }))
                add(Adjust("Dots per group", "", { "${st.dotsMax}" }, { st.dotsMax += it }))
                add(Adjust("Dots size", "The dots bar (and the liquid glass drops)", { pct(st.dotsScale) }, { st.dotsScale = ((st.dotsScale * 10).roundToInt() + it) / 10f }))

                add(Note("Pictures"))
                add(Adjust("Artwork", "Which picture each title uses", {
                    when (st.artChoice) { "second" -> "Second picture"; "best" -> "Highest resolution"; "addon" -> "Add-on picture"; else -> "Main (TMDB)" }
                }, { st.artChoice = cycle(listOf("main", "second", "best", "addon"), st.artChoice, it) }))
                add(Toggle("Backdrop gallery", "Like Apple TV: each title shows several of its pictures (needs the TMDB token)", { st.heroGallery }, { st.heroGallery = it; st.perfMode = "custom" }))
                add(Adjust("  · Pictures per title", "", { "${st.galleryShots}" }, { st.galleryShots = (st.galleryShots + it).coerceIn(2, 6) }))
                add(Toggle("Carousel shade", "The dark shades over the title's picture: on the left behind the logo, text and buttons, and at the bottom above the dock. Off: the picture as it is (the writing gets a soft shadow of its own)",
                    { st.carouselShade != "off" }, { st.carouselShade = if (it) "on" else "off" }))
                add(Act("Reset pictures picked per title", "", "") { st.clearArtPicks(); toast(ctx, "Every title is back to the Artwork setting") })
                // Hold OK on Play → Hide this title. OK here lists them: picking one brings it back.
                add(st.hiddenTitles().let { hid ->
                    Act("Hidden titles", "Hold OK on Play → Hide this title. Pick one here to show it again", if (hid.isEmpty()) "None" else "${hid.size}") {
                        if (hid.isEmpty()) { toast(ctx, "No hidden titles"); return@Act }
                        dialog = MenuDialog("Hidden titles · pick one to show it again",
                            listOf("Show all again (${hid.size})" to { st.clearHidden(); toast(ctx, "Every hidden title is back") }) +
                            hid.asReversed().map { (k, n) -> (n.ifBlank { k }) to { st.setHidden(k, n, false); toast(ctx, "Back on the carousel · ${n.ifBlank { k }}") } })
                    }
                })
                add(Note("One title: on the carousel hold OK (or press Menu) to switch to its next picture. Glassio remembers it."))

                if (!com.glasslauncher.tv.data.Store.PLAY) {
                add(Note("Trailers"))
                add(Toggle("Trailer button", "Next to Play (Apple TV buttons). Hold OK on Play has Trailer too", { st.trailerButton }, { st.trailerButton = it }))
                add(Adjust("Play trailers by themselves", "After this long on a title its trailer starts; when it ends (or ◀ ▶) the next title comes", {
                    if (st.trailerAutoSec == 0) "Off" else "After ${st.trailerAutoSec} s"
                }, { st.trailerAutoSec = cycle(listOf(0, 3, 5, 8, 10, 15, 20, 30), st.trailerAutoSec, it) }))
                add(Adjust("Size", "Fill the screen (no black bars), its own shape, or a window by the buttons (shaped like the trailer)", {
                    when (st.trailerSize) { "original" -> "Original"; "popup" -> "Window"; else -> "Cinematic (fill)" }
                }, { st.trailerSize = cycle(listOf("cinema", "original", "popup"), st.trailerSize, it) }))
                if (st.trailerSize == "popup")
                    add(Adjust("  · Window size", "", { pct(st.trailerPopupScale) }, { st.trailerPopupScale = ((st.trailerPopupScale * 10).roundToInt() + it) / 10f }))
                add(Toggle("Hide the dock during trailers", "The dock and the dots step aside while a trailer fills the page, and come back when it stops or ends (never with the window)",
                    { st.trailerHideDock }, { st.trailerHideDock = it }))
                add(Toggle("Sound", "", { !st.trailerMute }, { st.trailerMute = !it }))
                add(Adjust("  · Volume", "How loud the trailers play (the TV's own volume stays as it is)", { "${st.trailerVolume} %" }, { st.trailerVolume += it * 10 }))
                add(Toggle("Subtitles", "", { st.trailerSubs }, { st.trailerSubs = it }))
                add(Adjust("  · Language", "", { java.util.Locale(st.trailerSubLang).getDisplayLanguage(java.util.Locale.ENGLISH) },
                    { st.trailerSubLang = cycle(listOf("en", "ar", "fr", "de", "es", "it", "tr", "fa", "ru", "pt", "hi", "ja", "ko", "zh"), st.trailerSubLang, it) }))
                add(Adjust("  · Size", "", { pct(st.trailerSubScale) }, { st.trailerSubScale = ((st.trailerSubScale * 10).roundToInt() + it) / 10f }))
                }
            }
        },
        Category("Dock & icons") {
            tvRev.intValue
            buildList {
                add(Note("Place & size"))
                add(Adjust("Dock position", "With your own picture, photos, video or screensaver as the background",
                    { when (st.dockPos) { "center" -> "Middle"; "left" -> "Left (column)"; else -> "Bottom" } },
                    { st.dockPos = cycle(listOf("bottom", "center", "left"), st.dockPos, it) }))
                add(Adjust("Dock size", "Smaller makes the dock and its icons narrower", { pct(st.dockScale) }, { st.dockScale = ((st.dockScale * 20).roundToInt() + it) / 20f }))
                add(Adjust("Apps in a row", "More apps make the icons smaller", { "${st.dockColumns}" }, { st.dockColumns += it }))
                add(Toggle("Dock fits its apps", "With fewer apps than a full row, the dock shrinks around them", { st.dockFit }, { st.dockFit = it }))
                add(Toggle("Dock from edge to edge", "One strip with every app: the selected app stays in the middle and the strip moves", { st.dockStrip }, { st.dockStrip = it }))
                add(Toggle("Hide the dock", "It stays below the screen until you go down to it (the dots go down too)", { st.dockHide }, { st.dockHide = it }))

                add(Note("Dock glass"))
                add(Toggle("Dock glass", "Off: only the icons, no glass behind them", { !st.dockGlassOff }, { st.dockGlassOff = !it }))
                add(Adjust("  · Glass style", "Behind the apps row and the Apps folder (the large clock follows it)", { styleName(st.dockStyle) },
                    { st.dockStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.dockStyle, it); st.theme = "" }))

                add(Note("Apps"))
                add(Toggle("Show Apps folder", "The folder with all your apps, in the dock. Hidden apps stay listed in Arrange apps", { st.showFolder }, { st.showFolder = it }))
                add(Toggle("Show phone apps too", "Apps without a TV launcher entry", { st.showMobileApps }, { st.showMobileApps = it; app.apps.reload() }))
                add(Toggle("Down from the dock opens the Library", "From the dock's last row", { st.libraryDown }, { st.libraryDown = it }))
                add(pageRow("Arrange apps", "Their order in the dock, the hidden ones, and each app's options", more(allApps.size), sp = SubPage("Arrange apps") {
                        val apps by app.apps.apps.collectAsState()
                        @Suppress("UNUSED_VARIABLE") val redraw = st.dockOrder.size + apps.size // read for recomposition on reorder
                        buildList {
                            add(Note("OK picks an app up, ◀ ▶ moves it, OK puts it down. Hold OK (or Menu) for its options."))
                            app.apps.ordered(includeHidden = true).forEach { add(AppItem(it)) }
                        }
                    }))

                add(Note("Icons"))
                add(Adjust("Icon pack", "Projectivy / Blueprint / Nova packs installed on this TV",
                    { if (st.iconPack.isEmpty()) "None (original icons)" else (SettingsCache.packs.value.orEmpty().firstOrNull { it.pkg == st.iconPack }?.label ?: st.iconPack) },
                    { st.iconPack = cycle(listOf("") + SettingsCache.packs.value.orEmpty().map { p -> p.pkg }, st.iconPack, it) }))
                add(Act("  · Find icon packs on Google Play", "Projectivy / Android TV icon packs", "") { openPlay(ctx, null, "projectivy icon pack") })
                // Packs installed straight from their own pages (GitHub, F-Droid): Install, then pick it under Icon pack.
                if (!com.glasslauncher.tv.data.Store.PLAY) add(pageRow("  · Install an icon pack", "Straight from the packs' own pages (GitHub, F-Droid)", more(com.glasslauncher.tv.data.IconPackStore.PACKS.size), sp = SubPage("Install an icon pack") {
                        tvRev.intValue
                        buildList {
                            com.glasslauncher.tv.data.IconPackStore.PACKS.forEach { p ->
                                val have = com.glasslauncher.tv.data.IconPackStore.installed(ctx, p.pkg)
                                val doing = packState[p.pkg]
                                add(Act(p.name, doing ?: p.about, if (doing != null && !doing.startsWith("Failed")) "…" else if (have) "Update" else "Install", ok = if (have) true else null) {
                                    scope.launch(Dispatchers.IO) {
                                        com.glasslauncher.tv.data.IconPackStore.install(ctx, p) { f -> app.updater.installWithPackageInstaller(f, own = false) }
                                        app.packs.forget()
                                        SettingsCache.packs.value = runCatching { app.packs.installed() }.getOrDefault(SettingsCache.packs.value.orEmpty())
                                        if (com.glasslauncher.tv.data.IconPackStore.installed(ctx, p.pkg)) toast(ctx, "${p.name} installed: choose it under Icon pack")
                                        tvRev.intValue++
                                    }
                                })
                            }
                            add(Note("Icon packs: install any Android TV / Projectivy icon pack from Google Play, then choose it above. Every app takes the pack's icon; you can still change single apps."))
                        }
                    }))
                add(Act("  · Import icons from the folder", "", "") {
                    val n = app.icons.importDropFolder(app.apps.apps.value.map { it.pkg })
                    toast(ctx, if (n > 0) "Imported $n icons" else "No new icons. Folder: ${app.icons.dropFolder.absolutePath}")
                })
                add(Adjust("Icon style", "How the plate behind every icon looks", {
                    styleName(st.tileStyle)
                }, { st.tileStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.tileStyle, it); st.theme = "" }))
                add(Toggle("Colour on focus", "Like tvOS: icons stay white, the selected one shows its colours on a light plate", { st.focusColor }, { st.focusColor = it }))
                add(Adjust("  · Selected icon plate", "With Colour on focus", { PLATE_NAMES[st.focusPlate] ?: "Dark" },
                    { st.focusPlate = cycle(PLATES, st.focusPlate, it) }))
                add(Adjust("Icon zoom on focus", "", { when (st.tileZoom) { "icon" -> "Inside the glass (the icon grows)"; "off" -> "None"; else -> "Whole tile grows" } },
                    { st.tileZoom = cycle(listOf("tile", "icon", "off"), st.tileZoom, it) }))
                add(Toggle("Lift selected icon", "The selected icon rises a little, glass and all", { st.tileLift }, { st.tileLift = it }))
                add(Toggle("Bounce at the edges", "Pressing past the first or last icon nudges it, like tvOS", { st.edgeBounce }, { st.edgeBounce = it }))
                add(Note("Change one icon: on the home screen hold OK on the app → Change icon. Pick from an icon pack, the Apple TV icons, a photo on the TV, a link to a PNG, or send one from your phone (Manage from phone)."))
                add(Note("Many icons at once: copy <package>.png files (e.g. com.netflix.ninja.png) into the icon folder below, then Import."))
                add(Note("Folder for <package>.png files: ${app.icons.dropFolder.absolutePath}"))

                add(Note("Extra icons"))
                if (st.musicOn)
                    add(Toggle("Music icon", "A Music icon (move, hide and change it like an app): opens the player", { st.musicDock }, { st.musicDock = it; app.apps.reload() }))
                add(Toggle("Inputs icon", "One Inputs icon (move, hide and change it like an app): its inputs show above the dock, like the Music icon's music apps. Hold OK on an input there: its icon, order, hide",
                    { st.inputsIcon }, { st.inputsIcon = it; scope.launch(Dispatchers.IO) { com.glasslauncher.tv.data.Inputs.refresh(ctx); app.apps.reload() } }))
                add(Toggle("Inputs in the dock", "Each input as an icon: move, hide and change its icon like an app",
                    { st.inputsDock }, { st.inputsDock = it; scope.launch(Dispatchers.IO) { com.glasslauncher.tv.data.Inputs.refresh(ctx); app.apps.reload() } }))
                if (st.inputsIcon) {
                    val shown = com.glasslauncher.tv.data.Inputs.forPage(st, withHidden = true)
                    add(pageRow("Inputs", "Which inputs show on the Inputs icon's page, and their order", more("${shown.count { com.glasslauncher.tv.data.Inputs.PREFIX + it.id !in st.inputsHidden }} / ${shown.size}"), sp = SubPage("Inputs") {
                            tvRev.intValue
                            buildList {
                                val ins = com.glasslauncher.tv.data.Inputs.forPage(st, withHidden = true)
                                ins.forEach { inp ->
                                    val id = com.glasslauncher.tv.data.Inputs.PREFIX + inp.id
                                    add(Toggle(inp.label, "On the Inputs icon's page (hold OK on it there to change its icon)", { id !in st.inputsHidden }, { on ->
                                        st.inputsHidden = if (on) st.inputsHidden - id else st.inputsHidden + id
                                    }))
                                }
                                if (ins.size > 1) add(Act("Order", ins.joinToString(" · ") { it.label }, "Change") {
                                    dialog = ReorderDialog("Inputs, first on the left", ins.map { (com.glasslauncher.tv.data.Inputs.PREFIX + it.id) to it.label }) { st.inputsOrder = it }
                                })
                            }
                        }))
                }
                add(Adjust("Inputs open in", "Glassio: shows the input itself (for TVs on plain Android, where the TV's app doesn't open it)",
                    { when (st.inputPlayer) { "tv" -> "The TV's app"; "glassio" -> "Glassio"; else -> "Automatic" } },
                    { st.inputPlayer = cycle(listOf("auto", "tv", "glassio"), st.inputPlayer, it) }))
            }
        },
        Category("Look & menu") {
            val themes = listOf("bow", "amoled", "liquid", "clear", "water_still", "water")
            val icon = st.settingsIcon
            val hasPic = app.icons.has(com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON)
            buildList {
                add(Note("Theme"))
                add(Adjust("Theme", "One look for the dock, the icons and the menu. Black ones keep the dock clear (matte)",
                    { if (st.theme.isBlank()) "Custom" else themeName(st.theme) }, { st.applyTheme(cycle(themes, st.theme.ifBlank { "liquid" }, it)) }))
                add(Adjust("Glass colour", "A tint through all the glass (dock, icons, menus, panels), still see-through, like a tinted window",
                    { GlassTint.NAMES[st.glassColor] ?: "None (clear)" }, { st.glassColor = cycle(GlassTint.NAMES.keys.toList(), st.glassColor, it); GlassTint.apply(st) }))
                add(Adjust("  · Strength", "How deep the glass colour is", { pct(st.glassColorAmount) },
                    { st.glassColorAmount = ((st.glassColorAmount * 20).roundToInt() + it) / 20f; GlassTint.apply(st) }))

                add(Note("Glass"))
                add(Adjust("Glass blur", "0 turns blur off", { "${st.blurRadius}" }, { st.blurRadius += it * 4 }))
                add(Adjust("Glass refraction", "How much the rim bends what is behind it (0 = flat)", { "${st.refraction}" }, { st.refraction += it * 2; st.perfMode = "custom" }))
                add(Adjust("Glass brightness", "", { "${st.glassTint}" }, { st.glassTint += it * 2 }))

                add(Note("Shapes & text"))
                add(Adjust("Corner radius", "Dock and app tiles (0 = square)", { "${st.cornerRadius}" }, { st.cornerRadius += it * 2 }))
                add(Toggle("Sharp corners", "The menu, the dock and the icons with square corners", { st.squareCorners }, { st.squareCorners = it }))
                add(Toggle("Bold text", "All of Glassio's writing a step bolder", { st.boldText }, { st.boldText = it; TextLook.bold.value = it }))
                add(Act("Switch colour", "The colour of the switches in Settings, and of a row while you change it", SETTINGS_ACCENTS[st.settingsAccent]?.first ?: "Burgundy") {
                    dialog = ThumbDialog("Switch colour", SETTINGS_ACCENTS.map { (k, v) -> ThumbItem(v.first, if (k == "burgundy") "Default" else "", HueArt.swatch(v.second), checked = k == st.settingsAccent) { st.settingsAccent = k } })
                })

                add(Note("Selection"))
                add(Adjust("Glow", "A very soft light around the selected app or poster (also in the Library)", { when (st.glow) { "color" -> "In its own colour"; "white" -> "White"; else -> "Off" } },
                    { st.glow = cycle(listOf("off", "color", "white"), st.glow, it) }))
                add(Adjust("  · Glow size", "How far and how strong it is", { pct(st.glowSize) }, { st.glowSize = ((st.glowSize * 10).roundToInt() + it) / 10f }))

                add(Note("Behind pages"))
                add(Adjust("Settings background", "Behind settings and the icon picker", { styleName(st.panelStyle) },
                    { st.panelStyle = cycle(listOf("liquid", "clear", "amoled"), st.panelStyle, it) }))

                // ── The top bar: the settings button, and when it and the clock hide.
                add(Note("Top bar"))
                add(Adjust("Settings button", "The button at the top that opens the menu", { SETTINGS_ICONS[icon] ?: "Sliders" },
                    { st.settingsIcon = cycle(SETTINGS_ICONS.keys.toList(), icon, it) }))
                if (icon == "picture") add(Act("  · Your picture", if (hasPic) "Shown as the settings button. Change it from your phone (QR code) or a link"
                    else "Not chosen yet: your Nuvio profile's picture shows (the sliders without one). Add your own from your phone (QR code) or a link", if (hasPic) "Change" else "Add") {
                    pictureDialog()
                })
                // ("Hello" and "Menu" are words on their own, and your picture is always round: no frame.)
                if (icon != "hello" && icon != "menuText" && icon != "picture") add(Adjust("  · Frame", "Around the settings button (it fills in white when selected)",
                    { SETTINGS_FRAMES[st.settingsFrame] ?: "Square" }, { st.settingsFrame = cycle(SETTINGS_FRAMES.keys.toList(), st.settingsFrame, it) }))
                add(Toggle("Auto-hide the top bar", "The clock and the settings button hide while you browse, and come back when you go up to them", { st.topAutoHide }, { st.topAutoHide = it }))
                if (st.topAutoHide) add(Adjust("  · Shown at start for", "Then hidden until you go up to them", { "${st.topHideSec} s" }, { st.topHideSec += it }))

                add(Note("Clock & weather"))
                add(Toggle("Show clock", "", { st.showClock }, { st.showClock = it }))
                if (st.showClock) add(Toggle("  · 24-hour clock", "", { st.clock24h }, { st.clock24h = it }))
                if (st.showClock) add(Toggle("  · Show date", "Under the clock, like Apple TV", { st.showDate }, { st.showDate = it }))
                if (st.showClock) add(Adjust("  · Clock position", "", { if (st.clockPos == "right") "Right, by Settings" else "Left, like Apple TV" },
                    { st.clockPos = cycle(listOf("left", "right"), st.clockPos, it) }))
                add(Adjust("Weather", "Next to the settings button", {
                    when (st.weatherMode) { "off" -> "Off"; "temp" -> "Temperature"; "condition" -> "Temperature + sky"; "city" -> "Temperature + city"; else -> "Everything" }
                }, { st.weatherMode = cycle(listOf("all", "condition", "city", "temp", "off"), st.weatherMode, it); app.weather.reload() }))
                if (st.weatherMode != "off" || st.menuWeather) add(Act("  · City", st.weatherCity + weatherFound.takeIf { it.isNotBlank() }?.let { "  →  $it" }.orEmpty(), "Change") {
                    dialog = TextEntry("Weather city", st.weatherCity, "Baghdad") { st.weatherCity = it; app.weather.reload() }
                })
                if (st.weatherMode != "off" || st.menuWeather) add(Toggle("  · Fahrenheit", "", { st.weatherF }, { st.weatherF = it }))

                // ── The Apple TV-style menu at the settings button.
                add(Note("Menu"))
                add(Adjust("Menu", "The Apple TV-style menu at the settings button",
                    { when (st.menuMode) { "auto" -> "Opens when you go up to it"; "manual" -> "Opens with OK"; else -> "Off (OK opens Settings)" } },
                    { st.menuMode = cycle(listOf("auto", "manual", "off"), st.menuMode, it) }))
                if (st.menuMode != "off") {
                    add(Adjust("  · Look", "", { styleName(st.menuStyle) },
                        { st.menuStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.menuStyle, it); st.theme = "" }))
                    add(Adjust("  · Size", "", { pct(st.menuScale) }, { st.menuScale = ((st.menuScale * 20).roundToInt() + it) / 20f }))
                    add(Act("  · Order", st.menuOrder.filterNot { com.glasslauncher.tv.data.Store.PLAY && it == "trailer" }.joinToString(" · ") { com.glasslauncher.tv.data.MENU_NAMES[it] ?: it }, "Change") {
                        // (The play build has no trailers: their item isn't offered, and stays in the saved order, at its end.)
                        dialog = ReorderDialog("Menu order", st.menuOrder.filterNot { com.glasslauncher.tv.data.Store.PLAY && it == "trailer" }.map { it to (com.glasslauncher.tv.data.MENU_NAMES[it] ?: it) }) { st.menuOrder = if (com.glasslauncher.tv.data.Store.PLAY && "trailer" in st.menuOrder) it + "trailer" else it }
                    })
                    add(Toggle("Greeting", "Hello and the profile's name at the top of the menu, with its picture (the name alone under a Hello button, no picture under a picture button)",
                        { st.menuHello }, { st.menuHello = it }))
                    val items = listOf(st.menuClock, st.menuDate, st.menuWeather, st.menuCity, st.menuLibrary, st.menuThemes, st.menuProfile, st.menuBackground,
                        st.menuTrailer, st.menuSaver, st.menuInputs, st.menuRecents, st.menuWifi, st.menuBluetooth, st.menuAndroid)
                    add(pageRow("Menu items", "What the menu shows: the clock, the weather, the Library, themes, the profile, the background and more", more("${items.count { it }} / ${items.size}"), sp = SubPage("Menu items") {
                            buildList {
                                add(Toggle("Clock", "", { st.menuClock }, { st.menuClock = it }))
                                add(Toggle("Date", "", { st.menuDate }, { st.menuDate = it }))
                                add(Toggle("Weather", "Temperature, conditions, high / low, humidity and wind (the city: Clock & weather)", { st.menuWeather }, { st.menuWeather = it; app.weather.reload() }))
                                add(Toggle("City", "", { st.menuCity }, { st.menuCity = it }))
                                add(Toggle("Library", "", { st.menuLibrary }, { st.menuLibrary = it }))
                                add(Toggle("Themes", "", { st.menuThemes }, { st.menuThemes = it }))
                                add(Toggle("Profile", "Change who is watching (shown when your Nuvio account has more than one profile)", { st.menuProfile }, { st.menuProfile = it }))
                                add(Toggle("Background", "Switch between title backdrops, your picture, your video and the screensaver", { st.menuBackground }, { st.menuBackground = it }))
                                if (!com.glasslauncher.tv.data.Store.PLAY) add(Toggle("Trailer", "Play or stop the trailer, and its size, starting by itself and subtitles", { st.menuTrailer }, { st.menuTrailer = it }))
                                add(Toggle("Screensaver", "Start it, choose its videos and when it starts", { st.menuSaver }, { st.menuSaver = it }))
                                add(link("Smart home", "Camera in the menu", "The live camera in the menu is with the cameras"))
                                add(link("Music & app rows", "Player in the menu", "The small player in the menu is with the music"))
                                add(Toggle("Inputs", "The TV's HDMI inputs and the devices plugged into them (found by themselves, by name over HDMI-CEC)",
                                    { st.menuInputs }, { st.menuInputs = it; if (it) scope.launch(Dispatchers.IO) { com.glasslauncher.tv.data.Inputs.refresh(ctx) } }))
                                add(Toggle("Recent apps", "Opens the app switcher (like holding Home)", { st.menuRecents }, { st.menuRecents = it }))
                                if (st.searchOn) add(Toggle("Search", "Glassio's search: films, series, videos, songs", { st.menuSearch }, { st.menuSearch = it }))
                                add(Toggle("Wi-Fi", "", { st.menuWifi }, { st.menuWifi = it }))
                                add(Toggle("Bluetooth", "The TV's Bluetooth: remotes, headphones, speakers and controllers", { st.menuBluetooth }, { st.menuBluetooth = it }))
                                add(Toggle("Android settings", "", { st.menuAndroid }, { st.menuAndroid = it }))
                            }
                        }))
                    val info = listOf(st.sysIp, st.sysCpu, st.sysGpu, st.sysRam, st.sysStorage)
                    add(pageRow("TV info", "Under the weather in the menu: IP, CPU, GPU, RAM, storage", if (st.menuSys) more("${info.count { it }} / 5") else more("Off"), sp = SubPage("TV info") {
                            buildList {
                                add(Toggle("TV info in the menu", "Under the weather: IP, CPU, GPU, RAM, storage (CPU and GPU need TV access (ADB))", { st.menuSys }, { st.menuSys = it }))
                                if (st.menuSys) {
                                    add(Toggle("IP address", "", { st.sysIp }, { st.sysIp = it }))
                                    add(Toggle("CPU", "Use and temperature", { st.sysCpu }, { st.sysCpu = it }))
                                    add(Toggle("GPU", "Use, or its memory where the TV doesn't tell the use", { st.sysGpu }, { st.sysGpu = it }))
                                    add(Toggle("RAM", "", { st.sysRam }, { st.sysRam = it }))
                                    add(Toggle("Storage", "", { st.sysStorage }, { st.sysStorage = it }))
                                }
                            }
                        }))
                }
            }
        },
        Category("Background & screensaver") {
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
                fun changeEvery(): SRow =
                    Adjust("  · Change every", "Photos, and your pictures with shuffle", {
                        when (val e = st.photoEvery) { 0 -> "Never"; in 1..59 -> "$e s"; in 60..3599 -> "${e / 60} min"; else -> "${e / 3600} h" }
                    }, { st.photoEvery = cycle(listOf(60, 300, 600, 1800, 3600, 21600, 0), st.photoEvery, it) })

                add(Note("Background"))
                add(Adjust("Background", "Each title's own picture, or yours: a picture, photos, a video or the screensaver's videos",
                    { when (st.bgMode) { "image" -> "My picture"; "video" -> "My video"; "aerial" -> "Screensaver (aerials)"; "photos" -> "Photos (4K)"; else -> "Title backdrops" } },
                    { st.bgMode = cycle(listOf("carousel", "image", "photos", "video", "aerial"), st.bgMode, it) }))
                add(Act("Next", "The next video, picture or photo now", "") { CustomBackground.nextNow(st); toast(ctx, "Next") })

                if (st.bgMode == "photos") {
                    add(Note("Photos"))
                    add(Adjust("Photos from", "Bing and Wikimedia need no account; Unsplash needs a free key", {
                        com.glasslauncher.tv.data.Photos.SOURCES.firstOrNull { it.first == st.photoSource }?.second ?: st.photoSource
                    }, { st.photoSource = cycle(com.glasslauncher.tv.data.Photos.SOURCES.map { it.first }, st.photoSource, it); st.bgMode = "photos" }))
                    add(Adjust("  · Subject", "What Wikimedia and Unsplash photos show", { st.photoTopic.replaceFirstChar { c -> c.uppercase() } },
                        { st.photoTopic = cycle(com.glasslauncher.tv.data.Photos.TOPICS, st.photoTopic, it) }))
                    if (st.photoSource == "unsplash")
                        add(Act("  · Unsplash key", "Free: unsplash.com/developers → New application → Access Key. Easier to paste from your phone (Manage from phone)",
                            if (st.unsplashKey.isBlank()) "Not set" else "Saved") {
                            dialog = TextEntry("Unsplash Access Key", st.unsplashKey, "Access Key") { v -> st.unsplashKey = v }
                        })
                    if (st.photoSource == "reddit") {
                        add(Act("  · Subreddits", "Subreddit wallpapers: one or more, comma separated (EarthPorn, wallpapers, SkyPorn…)", st.photoSubreddit.ifBlank { "Not set" }) {
                            dialog = TextEntry("Subreddits", st.photoSubreddit, "EarthPorn, wallpapers") { v -> st.photoSubreddit = v; st.photoSource = "reddit"; st.bgMode = "photos" }
                        })
                        add(Act("  · Reddit app id", "Reddit lets apps in only with an id: reddit.com/prefs/apps → create app → \"installed app\" (any name, redirect http://localhost) → the id under its name",
                            if (st.redditAppId.isBlank()) "Not set" else "Saved") {
                            dialog = TextEntry("Reddit app id", st.redditAppId, "the id under the app's name") { v -> st.redditAppId = v }
                        })
                    }
                    add(changeEvery())
                    add(Toggle("  · Shuffle photos", "A random order (else the source's own order)", { st.photoShuffle }, { st.photoShuffle = it }))
                    add(Adjust("  · Photos kept on the TV", "Shown again without downloading; the oldest go first", {
                        "${if (st.photoCacheMb >= 1024) "${st.photoCacheMb / 1024} GB" else "${st.photoCacheMb} MB"} · ${com.glasslauncher.tv.data.Photos.cacheUsedMb(ctx)} MB used"
                    }, { st.photoCacheMb = cycle(listOf(256, 512, 1024, 2048, 4096), st.photoCacheMb, it) }))
                }

                add(Note("Your pictures & videos"))
                add(pageRow("My pictures & videos", "A picture or a video of your own as the background, from a link or the folder", more(4), sp = SubPage("My pictures & videos") {
                        buildList {
                            add(Act("Picture from a link", st.bgImage.ifBlank { "https://… (jpg, png, webp)" }, "") {
                                dialog = TextEntry("Picture link", st.bgImage.takeIf { it.startsWith("http") }.orEmpty(), "https://…") { v -> if (v.isNotBlank()) { st.bgImage = v.trim(); st.bgMode = "image" } }
                            })
                            add(Act("Picture from the folder", "", "") { pick(false) })
                            add(Act("Video from a link", st.bgVideo.ifBlank { "https://… (mp4, webm, mkv); plays muted, on a loop" }, "") {
                                dialog = TextEntry("Video link", st.bgVideo.takeIf { it.startsWith("http") }.orEmpty(), "https://…") { v -> if (v.isNotBlank()) { st.bgVideo = v.trim(); st.bgMode = "video" } }
                            })
                            add(Act("Video from the folder", "", "") { pick(true) })
                            add(Note("Folder for your pictures and videos: $bgFolder (copy with adb push, a file manager or a USB stick). The glass shows the picture; with a video it shows one frame of it."))
                        }
                    }))
                if (st.bgMode == "image") {
                    add(Toggle("Shuffle my pictures", "My picture: another picture from the folder every \"Change every\"", { st.bgImageShuffle }, { st.bgImageShuffle = it }))
                    if (st.bgImageShuffle) add(changeEvery())
                }
                if (st.bgMode == "video")
                    add(Toggle("Shuffle my videos", "My video: the other videos in the folder too, one after another (else the chosen one loops)", { st.bgVideoShuffle }, { st.bgVideoShuffle = it }))

                if (st.bgMode == "aerial") {
                    add(Note("Screensaver videos as background"))
                    add(Adjust("Quality", "1080p is lightest behind the glass; the screensaver's quality also plays the videos it saved on the TV", {
                        if (st.bgAerialQuality == "saver") "Screensaver's quality" else "1080p"
                    }, { st.bgAerialQuality = cycle(listOf("1080", "saver"), st.bgAerialQuality, it) }))
                    add(Toggle("Shuffle", "A new one when each ends, fading through black like the screensaver", { st.bgShuffle }, { st.bgShuffle = it }))
                }

                if (st.bgMode != "carousel") {
                    add(Note("Over the background"))
                    add(Toggle("Left shade", "The dark shade along the left side (it keeps the clock and text readable on bright pictures)", { !st.sideShadeOff }, { st.sideShadeOff = !it }))
                }

                // One large clock, for the home screen and the screensaver: its look is set once here.
                add(Note("Large clock"))
                add(Toggle("Large clock", "In the middle of your own background, like a phone's lock screen", { st.bgClock }, { st.bgClock = it }))
                if (st.bgClock || st.saverBigClock) {
                    add(Adjust("  · Style", "With the theme: the digits are made exactly like the dock in your theme (its glass, tint and colour)", {
                        when (st.bgClockStyle) { "white" -> "White"; "still" -> "Still Glass"; else -> "With the theme" }
                    }, { st.bgClockStyle = cycle(listOf("theme", "still", "white"), st.bgClockStyle, it) }))
                    add(Adjust("  · Glass", "How much the glass digits show: lower is clearer glass (Still / Dynamic / Frosted / Clear looks)", { "${st.bgClockGlass} %" }, { st.bgClockGlass += it * 10 }))
                    add(Toggle("  · White date", "The date above the time in white, whatever the clock's look", { st.bgClockDateWhite }, { st.bgClockDateWhite = it }))
                    add(Adjust("  · Size", "", { pct(st.bgClockSize) }, { st.bgClockSize = ((st.bgClockSize * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Position", "◀ higher · lower ▶", {
                        val v = (st.bgClockOffset * 100).roundToInt(); if (v == 0) "Middle" else if (v < 0) "Up ${-v}" else "Down $v"
                    }, { st.bgClockOffset = ((st.bgClockOffset * 100).roundToInt() + it * 2) / 100f }))
                    add(Toggle("  · Depth effect", Depth.status.ifBlank { "On pictures and photos with open sky (yours too): what stands in front (a mountain, a tower, a tree) covers the lower part of the large clock. Worked out on the TV, once per photo (a 33 MB model is downloaded the first time)" },
                        { st.bgDepth }, { st.bgDepth = it }))
                }

                if (st.bgMode == "photos" || st.bgMode == "aerial") {
                    add(Note("Info over photos"))
                    add(Toggle("Place and credit", "Where the photo or video was taken and who took it", { st.bgInfo }, { st.bgInfo = it }))
                    add(Toggle("Time", "", { st.bgInfoClock }, { st.bgInfoClock = it }))
                    add(Toggle("Date", "", { st.bgInfoDate }, { st.bgInfoDate = it }))
                    add(Toggle("Weather", "", { st.bgInfoWeather }, { st.bgInfoWeather = it }))
                    add(Adjust("Info style", "", { if (st.bgInfoStyle == "netflix") "Netflix (one by one)" else "Apple" },
                        { st.bgInfoStyle = cycle(listOf("apple", "netflix"), st.bgInfoStyle, it) }))
                    add(Adjust("Info position", "", { when (st.bgInfoPos) { "tl" -> "Top left"; "tr" -> "Top right"; "br" -> "Bottom right"; "move" -> "Moves each time"; else -> "Bottom left" } },
                        { st.bgInfoPos = cycle(listOf("bl", "br", "tl", "tr", "move"), st.bgInfoPos, it) }))
                    add(Adjust("Info shows for", "After each new photo or video", { if (st.bgInfoSec == 0) "Always" else "${st.bgInfoSec} s" },
                        { st.bgInfoSec = cycle(listOf(0, 10, 20, 30, 60), st.bgInfoSec, it) }))
                }

                // ── The screensaver.
                add(Note("Screensaver · When"))
                add(Adjust("Start after (on the home screen)", if (ours) "Not needed while Glassio is the TV screensaver" else "Idle time on the launcher", { st.idleSeconds.let { v -> if (v == 0) "Off" else if (v < 60) "$v s" else "${v / 60} min" } }, {
                    st.idleSeconds = cycle(listOf(0, 10, 30, 60, 120, 180, 300, 600, 900, 1800), st.idleSeconds, it)
                }))
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
                add(Adjust("  · TV screensaver starts after", "The TV's own idle time, in any app (ADB)", {
                    when { tvAfter <= 0 -> "Unknown"; tvAfter < 60_000 -> "${tvAfter / 1000} s"; tvAfter >= 3_600_000 -> "${tvAfter / 3_600_000} h"; else -> "${tvAfter / 60_000} min" + (if (tvAfter % 60_000 != 0) " ${tvAfter % 60_000 / 1000} s" else "") }
                }, { d ->
                    val opts = listOf(20_000, 30_000, 60_000, 120_000, 300_000, 600_000, 900_000)
                    val next = cycle(opts, opts.minByOrNull { kotlin.math.abs(it - tvAfter) } ?: 60_000, if (tvAfter in opts) d else 0)
                    tv("settings put system screen_off_timeout $next && settings put secure screensaver_activate_on_sleep 1; device_config put attention_manager_service keep_screen_on_enabled false 2>/dev/null; true", "The TV screensaver now starts after " + (if (next < 60_000) "${next / 1000} s" else "${next / 60_000} min"))
                }))
                add(Toggle("Back on the home screen starts it", "Like Apple TV", { st.backSaver }, { st.backSaver = it }))

                add(Note("Screensaver · What plays"))
                add(Adjust("Type", "What plays", {
                    when (st.saverMode) {
                        "aerial" -> "Aerial videos"
                        "system" -> "The TV's screensaver app (" + (comp.substringBefore('/').substringAfterLast('.').ifBlank { "none" }) + ")"
                        "trailer" -> "Trailers"
                        else -> "Posters with logos"
                    }
                }, { st.saverMode = cycle(if (com.glasslauncher.tv.data.Store.PLAY) listOf("aerial", "slideshow", "system") else listOf("aerial", "slideshow", "trailer", "system"), st.saverMode, it) }))
                if (st.saverMode == "slideshow") add(Note("OK on a poster opens the title in Nuvio · ◀ ▶ the next or the one before"))
                add(Act("Preview now", "") { ctx.startActivity(Intent(ctx, ScreensaverActivity::class.java)) })

                if (st.saverMode == "trailer") {
                    add(Note("Screensaver · Trailers"))
                    add(Toggle("Sound", "Off: the trailers play muted", { !st.saverTrailerMute }, { st.saverTrailerMute = !it }))
                    add(Adjust("  · Volume", "Also in the menu (hold OK)", { "${st.saverTrailerVolume} %" }, { st.saverTrailerVolume += it * 10 }))
                    add(Adjust("Size", "", { if (st.saverTrailerSize == "original") "Original (whole picture)" else "Cinematic (fills the screen)" },
                        { st.saverTrailerSize = cycle(listOf("cinema", "original"), st.saverTrailerSize, it) }))
                    add(Toggle("Logo", "The title's logo (or its name)", { st.saverTrailerLogo }, { st.saverTrailerLogo = it }))
                    add(Toggle("Details", "Rating, year, where it streams and genres, as on the carousel", { st.saverTrailerMeta }, { st.saverTrailerMeta = it }))
                    add(Toggle("Synopsis", "", { st.saverTrailerSynopsis }, { st.saverTrailerSynopsis = it }))
                    add(Adjust("Details shown for", "After each new trailer", { if (st.saverTrailerInfoSec == 0) "Always" else "${st.saverTrailerInfoSec} s" },
                        { st.saverTrailerInfoSec = cycle(listOf(0, 6, 10, 20, 30), st.saverTrailerInfoSec, it) }))
                    add(link("Carousel", "Subtitles", "Trailer subtitles and their size are set once, with the carousel's trailers"))
                    add(Note("Trailers (the carousel's titles, one after another) · OK opens the title in Nuvio · hold OK for more · ◀ ▶ skip"))
                }

                // The videos: for the screensaver, and for the home screen with the screensaver's videos as the background.
                if (st.saverMode == "aerial" || st.bgMode == "aerial") {
                    add(Note("Screensaver · Videos"))
                    add(pageRow("Collections", "Videos from aerial-videos.netlify.app", more("${AerialSource.SOURCES.count { it.key in st.saverSources }} / ${AerialSource.SOURCES.size}"), sp = SubPage("Collections") {
                            buildList {
                                AerialSource.SOURCES.forEach { src ->
                                    add(Toggle(src.name, "", { src.key in st.saverSources }, { on -> st.saverSources = if (on) st.saverSources + src.key else st.saverSources - src.key; st.saverPick = "" }))
                                }
                            }
                        }))
                    add(pageRow("My videos", "Your links and the videos in the backgrounds folder", more(if (st.saverMine) "On · ${st.aerialUrls.size}" else "Off"), sp = SubPage("My videos") {
                            buildList {
                                add(Toggle("Play my videos", "Your links below and the videos in the backgrounds folder", { st.saverMine }, { st.saverMine = it; st.saverPick = "" }))
                                add(Act("Play only one of them", "Your links and the videos in the backgrounds folder, with a picture of each. OK plays only that one", "") {
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
                                add(Act("Add video link", "A direct .mp4 / .mov link (Manage from phone can also save it to the TV)", "") {
                                    dialog = TextEntry("Video link", "", "https://…/video.mp4") { u ->
                                        if (u.startsWith("http")) st.aerialUrls = (st.aerialUrls + u.trim()).distinct() else toast(ctx, "The link must start with http")
                                    }
                                })
                                if (st.aerialUrls.isNotEmpty()) add(Note("Your links"))
                                st.aerialUrls.forEach { u ->
                                    add(Act(videoName(u), hostOf(u), "Remove") { st.aerialUrls = st.aerialUrls - u })
                                }
                            }
                        }))
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
                    add(Adjust("  · Space for saved videos", "Applies after Glassio restarts", { "${st.saverCacheGb} GB" }, { st.saverCacheGb = cycle(listOf(1, 2, 4, 8, 16, 32), st.saverCacheGb, it) }))
                    add(Act("  · Delete saved videos", "", "") { com.glasslauncher.tv.saver.VideoCache.clear(ctx); toast(ctx, "Deleted") })

                    add(Note("Screensaver · Locked videos"))
                    add(Toggle("Lock & release", "Hold OK on a video you like to lock it (a glass padlock closes); hold OK again to release it", { st.saverLock }, { st.saverLock = it }))
                    if (st.saverLock) {
                        add(Toggle("  · Only locked videos", "Plays just the videos you locked (when there are some). Also in the menu's Screensaver page", { st.saverLockedOnly }, { st.saverLockedOnly = it }))
                        add(Act("  · Clear locked videos", "${st.saverLocked.size} locked", "") { st.saverLocked = emptyList() })
                    }
                }

                if (st.saverMode != "system") {
                    add(Note("Screensaver · On screen"))
                    add(Adjust("Style", "", { if (st.saverStyle == "netflix") "One by one, top left (like Netflix)" else "Apple" }, { st.saverStyle = cycle(listOf("apple", "netflix"), st.saverStyle, it) }))
                    if (st.saverStyle == "netflix") {
                        add(Toggle("  · Hide when complete", "The lines go away 4 s after the last one comes in", { st.saverNetflixHide }, { st.saverNetflixHide = it }))
                        add(Adjust("  · Bar", "The line before each one", { when (st.saverBar) { "white" -> "White"; "black" -> "Black"; "off" -> "None"; else -> "Red" } },
                            { st.saverBar = cycle(listOf("red", "white", "black", "off"), st.saverBar, it) }))
                    }
                    add(Toggle("Clock", "", { st.saverClock }, { st.saverClock = it }))
                    add(Toggle("Date", "", { st.saverDate }, { st.saverDate = it }))
                    add(Toggle("Weather", "", { st.saverWeather }, { st.saverWeather = it; app.weather.reload() }))
                    add(Toggle("Place", "Where the video was filmed", { st.saverPlace }, { st.saverPlace = it }))
                    add(Toggle("TV info", "RAM, CPU, GPU, storage, IP (the ones on in Look & menu → TV info)", { st.saverSys }, { st.saverSys = it }))
                    add(Adjust("Position", "Apple style", { when (st.saverPos) { "tl" -> "Top left"; "tr" -> "Top right"; "br" -> "Bottom right"; "bl" -> "Bottom left"; else -> "Moves with every video" } },
                        { st.saverPos = cycle(listOf("move", "bl", "br", "tl", "tr"), st.saverPos, it) }))
                    add(Adjust("Size", "", { pct(st.saverSize) }, { st.saverSize = ((st.saverSize * 10).roundToInt() + it) / 10f }))
                    add(Adjust("Shows for", "After each new video", { if (st.saverShowSec == 0) "Always" else "${st.saverShowSec} s" }, { st.saverShowSec = cycle(listOf(0, 10, 20, 30, 60), st.saverShowSec, it) }))
                    add(Note("While it plays: ◀ ▶ change the video, OK shows or hides this info, any other key ends it."))
                }

                add(Note("Screensaver · Also on it"))
                add(Toggle("Large clock", "The large glass clock in the middle of the screensaver (its look: Large clock, above)", { st.saverBigClock }, { st.saverBigClock = it }))
                add(link("Smart home", "Multi-view", "Cameras on the screensaver: ▼ brings them up from the bottom", title = "Cameras"))
                if (st.musicOn) add(link("Music & app rows", "Island", "The music island at the top of the screensaver", title = "Music island"))
                if (st.hueOn && st.hueUser.isNotBlank()) add(link("Smart home", "On the screensaver", "Hue lights that follow the screensaver's videos", title = "Hue lights"))
            }
        },
        Category("Music & app rows") {
            tvRev.intValue
            val ch = com.glasslauncher.tv.data.Channels
            val access = ch.hasAccess(ctx)
            fun allow() = scope.launch(Dispatchers.IO) {
                val ok = ch.grant(ctx)
                if (ok) ch.start(ctx)
                // Without ADB: the TV's own question.
                else kotlinx.coroutines.withContext(Dispatchers.Main) { runCatching { (ctx as? android.app.Activity)?.requestPermissions(arrayOf(ch.PERMISSION), 77) } }
                tvRev.intValue++
                toast(ctx, if (ok) "Channels on" else if (com.glasslauncher.tv.data.Store.PLAY) "Allow it on the TV's question" else "Allow it on the TV's question, or allow TV access (ADB) first: Glassio settings → System")
            }
            fun label(pkg: String) = app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == pkg }?.label
                ?: runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            buildList {
                add(Note("Music"))
                val musicAccess = com.glasslauncher.tv.data.NowPlaying.hasAccess(ctx)
                add(Toggle("Music", if (!st.musicOn) "Glassio follows what any music app plays (Spotify, YouTube Music, Apple Music, Anghami…)"
                    else if (musicAccess) "On: following your music apps" else if (com.glasslauncher.tv.data.Store.PLAY) "Needs \"Notification access\" for Glassio in the TV's settings: press to open it"
                    else "Needs TV access (ADB, System) to read the music apps: press to try again",
                    { st.musicOn }, { on ->
                        st.musicOn = on
                        if (on) io {
                            val ok = com.glasslauncher.tv.data.NowPlaying.grant(ctx)
                            com.glasslauncher.tv.data.NowPlaying.start(ctx); app.apps.reload(); tvRev.intValue++
                            if (!ok && com.glasslauncher.tv.data.Store.PLAY) kotlinx.coroutines.withContext(Dispatchers.Main) { openSystem(ctx, AndroidSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                            if (ok) "Music on" else if (com.glasslauncher.tv.data.Store.PLAY) "Turn on Glassio under Notification access, then switch Music on again" else "Allow TV access (ADB) first: Glassio settings → System"
                        } else app.apps.reload()
                    }))
                if (st.musicOn) {
                    add(Toggle("Music mode", "While a song plays, the carousel's place becomes the player (artwork, song, buttons). ■ stops the music. With your own picture, photos, video or the screensaver's videos as the background, the island shows instead",
                        { st.musicMode }, { st.musicMode = it }))
                    add(Adjust("  · Player size", "", { pct(st.musicStageScale) }, { st.musicStageScale = ((st.musicStageScale * 10).roundToInt() + it) / 10f }))
                    add(Adjust("  · Buttons", "White or black turn the other way round when selected", {
                        when (st.musicButtons) { "white" -> "White"; "black" -> "Black"; else -> "With the theme" }
                    }, { st.musicButtons = cycle(listOf("theme", "white", "black"), st.musicButtons, it) }))
                    add(Toggle("Player in the menu", "▶ in the menu turns it into a small player", { st.musicMenu }, { st.musicMenu = it }))
                    add(pageRow("Music apps", "Each music app selected in the dock: its player or its rows, and the player with nothing playing", more(installedMusicApps(ctx).size), sp = SubPage("Music apps") {
                            tvRev.intValue
                            buildList {
                                addAll(musicRowsChoice(ctx, st) { tvRev.intValue++ })
                                addAll(musicIdleChoice(ctx, st) { tvRev.intValue++ })
                                add(Note("Music apps on this TV: " + installedMusicApps(ctx).joinToString { it.second }.ifBlank { "none yet" } +
                                    ". The player follows and controls whatever those apps play. Signing in to Spotify, YouTube Music or Apple Music for their playlists isn't here yet."))
                            }
                        }))

                    // One island, for the screensaver and the home screen.
                    add(Note("Music island"))
                    add(Toggle("Island", "While music plays, a small island at the top of the screensaver (like the iPhone's). ▲ opens it with its buttons, ▼ closes it", { st.musicIsland }, { st.musicIsland = it }))
                    add(Toggle("  · On the home screen too", "When Music mode is off", { st.islandHome }, { st.islandHome = it }))
                    if (st.musicIsland) {
                        add(Adjust("  · Look", "", { when (st.islandStyle) { "glass" -> "Glass"; "black" -> "Black"; "none" -> "No background"; else -> "With the theme" } }, { st.islandStyle = cycle(listOf("none", "theme", "glass", "black"), st.islandStyle, it) }))
                        add(Toggle("  · Joined to the top", "Like a MacBook's notch, instead of floating below the top", { st.islandAttached }, { st.islandAttached = it }))
                        add(Adjust("  · Size", "", { pct(st.islandScale) }, { st.islandScale = ((st.islandScale * 10).roundToInt() + it) / 10f }))
                        add(Adjust("  · Lower", "", { "${st.islandOffset.roundToInt()}" }, { st.islandOffset = st.islandOffset + it * 4f }))
                        add(Toggle("  · Artwork", "", { st.islandArt }, { st.islandArt = it }))
                        add(Toggle("  · Song and artist", "", { st.islandTitle }, { st.islandTitle = it }))
                        if (st.islandTitle) add(Toggle("  · Scroll long names", "A song name too long for the island scrolls by (off: shortened with …)", { st.islandMarquee }, { st.islandMarquee = it }))
                        add(Toggle("  · Progress bar", "", { st.islandProgress }, { st.islandProgress = it }))
                        add(Adjust("  · Bars colour", "", { if (st.islandBars == "white") "White" else "From the artwork" }, { st.islandBars = cycle(listOf("art", "white"), st.islandBars, it) }))
                    }
                }

                add(Note("App rows (Channels)"))
                add(Toggle("Channels", "While an app that has channels is selected in the dock (YouTube, Netflix, Shahid, Prime Video, Apple TV, Spotify…), its rows (Continue watching, New…) take the carousel's place. ▲ goes into them, ▼ back to the app",
                    { st.channelsOn }, { on -> st.channelsOn = on; if (on) allow() }))
                if (st.channelsOn) {
                    if (!access) add(Act("Allow reading the TV's channels", "Needs TV access (ADB, System), or the TV's own question: press to try again", "Allow") { allow() })
                    else ch.start(ctx)
                    add(Adjust("Layout", "Rows one above the other, or one row in the middle that ▲ / ▼ switch", { if (st.channelsLayout == "single") "One row" else "Rows" },
                        { st.channelsLayout = cycle(listOf("stack", "single"), st.channelsLayout, it) }))
                    if (st.channelsLayout != "single") add(Adjust("Rows", "How many rows show at once (1 or 2): an app's other rows come in with ▲ / ▼", { "${st.channelsRows}" }, { st.channelsRows += it }))
                    add(Adjust("Cards in a row", "How many cards fit across a row at first (posters are narrower: more of them show)", { "${st.channelsCards}" }, { st.channelsCards += it }))
                    add(Adjust("Background", "Behind the rows: the Library's grey page, or the selected card's picture blurred (like the music page)",
                        { if (st.channelsBg == "blur") "Blurred card" else "Grey" }, { st.channelsBg = cycle(listOf("grey", "blur"), st.channelsBg, it) }))
                    if (st.channelsBg == "blur") add(Adjust("  · Brightness", "How bright the blurred card is behind the rows", { "${st.channelsBlurBri} %" }, { st.channelsBlurBri += it * 10 }))
                    add(Adjust("Glow", "A very soft light around the selected card, like in the Library", { when (st.channelsGlow) { "color" -> "In its own colour"; "white" -> "White"; else -> "Off" } },
                        { st.channelsGlow = cycle(listOf("off", "color", "white"), st.channelsGlow, it) }))
                    if (st.channelsGlow != "off") add(Adjust("  · Glow size", "How far and how strong it is", { pct(st.channelsGlowSize) }, { st.channelsGlowSize = ((st.channelsGlowSize * 10).roundToInt() + it) / 10f }))
                    add(Adjust("App name", "The app's name over the rows: white, or in its icon's colour", { if (st.channelsLabel == "icon") "Icon colour" else "White" },
                        { st.channelsLabel = cycle(listOf("white", "icon"), st.channelsLabel, it) }))
                    add(pageRow("Apps with channels", "Each app's rows: off, hidden one by one, or in another order", more(ch.apps().size), sp = SubPage("Apps with channels") {
                            tvRev.intValue
                            buildList {
                                val apps = ch.apps()
                                if (access && apps.isEmpty()) add(Note("No app on this TV has published channels yet. Open YouTube, Netflix… once and come back."))
                                apps.forEach { pkg ->
                                    val rows = ch.allFor(st, pkg)
                                    val off = pkg in st.channelsOffApps
                                    val locked = pkg in st.lockedApps
                                    val shown = rows.count { it.key !in st.channelsHidden }
                                    add(Act(label(pkg), if (locked) "Locked app: its channels don't show" else if (off) "Off" else rows.filter { it.key !in st.channelsHidden }.joinToString(" · ") { it.name }.ifBlank { "Every row is hidden" },
                                        if (off || locked) "Off" else "On · $shown", ok = if (off || locked) null else true) {
                                        dialog = MenuDialog(label(pkg), buildList {
                                            add((if (off) "Show this app's channels" else "Don't show this app's channels") to {
                                                st.channelsOffApps = if (off) st.channelsOffApps - pkg else st.channelsOffApps + pkg; tvRev.intValue++
                                            })
                                            rows.forEach { r ->
                                                val hidden = r.key in st.channelsHidden
                                                add((if (hidden) "Show: " else "Hide: ") + r.name + "  (${r.programs.size})" to {
                                                    st.channelsHidden = if (hidden) st.channelsHidden - r.key else st.channelsHidden + r.key; tvRev.intValue++
                                                })
                                            }
                                            if (rows.size > 1) add("Change the order of its rows" to {
                                                dialog = ReorderDialog("${label(pkg)}: rows, first on top", rows.map { it.key to it.name }) { keys ->
                                                    st.channelsOrder = st.channelsOrder.filterNot { it.startsWith("$pkg|") } + keys; tvRev.intValue++
                                                }
                                            })
                                        })
                                    })
                                }
                            }
                        }))
                    add(Note("Nuvio and Stremio keep the carousel (no rows, no music player), and a locked app's channels never show. An app's rows: press OK on it to switch them off, hide one of them or change their order."))
                }

                add(Note("Page background"))
                add(Adjust("Music & Inputs page", "Behind the music apps and the inputs (not with your own background): the grey page, Glassio's soft coloured lights, or the carousel's picture blurred",
                    { when (st.stagePageBg) { "glassio" -> "Glassio blur"; "carousel" -> "Carousel blur"; else -> "Grey" } },
                    { st.stagePageBg = cycle(listOf("grey", "glassio", "carousel"), st.stagePageBg, it) }))
            }
        },
        Category("Smart home") {
            val hue = com.glasslauncher.tv.data.Hue
            val sync = com.glasslauncher.tv.data.HueSync
            tvRev.intValue
            hueRev.intValue
            val camList = st.cameras.mapNotNull { Cam.parse(it) }
            buildList {
                add(Note("Hue lights"))
                add(Toggle("Hue Sync", if (com.glasslauncher.tv.data.Store.PLAY) "Philips Hue lights in the colours of what's on screen: the backdrop, the poster or the logo, videos, the music's artwork"
                    else "Philips Hue lights in the colours of what's on screen: the backdrop, the poster or the logo, videos and trailers, the music's artwork",
                    { st.hueOn }, { on -> st.hueOn = on; if (!on) sync.leave(st, "restore") }))
                val paired = st.hueUser.isNotBlank()
                if (st.hueOn) {
                    add(pageRow("Bridge", if (paired) "Connected · ${st.hueIp}" else "Find the Hue bridge on this network and connect to it", if (paired) more("Connected") else more("Connect"), ok = if (paired) true else null, sp = SubPage("Bridge") {
                            hueRev.intValue
                            buildList {
                                if (st.hueUser.isBlank()) {
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
                                                if (r.user != null) { st.hueUser = r.user; st.huePin = ""; sync.refreshGroups(); hueRev.intValue++; return@io "Connected to the Hue bridge: now choose the lights" }
                                                if (!r.waiting) return@io "Hue: ${r.error}"
                                                kotlinx.coroutines.delay(1000)
                                            }
                                            "The bridge's button wasn't pressed: press it, then Connect again"
                                        }
                                    })
                                } else {
                                    add(Act("Bridge", "Connected · ${st.hueIp}", "", ok = true) { hueRev.intValue++ })
                                    add(Act("Forget the bridge", "", "Forget") { sync.leave(st, "restore"); st.huePin = ""; st.hueUser = ""; st.hueGroups = emptyList(); st.hueLights = emptyList(); hueRev.intValue++ })
                                }
                            }
                        }))
                }
                if (st.hueOn && paired) {
                    add(pageRow("Lights & areas", "The lights that follow the screen, the part of it each one follows and how it takes its colour", more(st.hueGroups.size + st.hueLights.size), sp = SubPage("Lights & areas") {
                            // The bridge's rooms, zones and lights (read when this page opens, and after connecting).
                            val found by androidx.compose.runtime.produceState<Pair<List<com.glasslauncher.tv.data.Hue.Group>, List<com.glasslauncher.tv.data.Hue.Light>>?>(null, st.hueUser, st.hueIp, hueRev.intValue) {
                                value = if (st.hueUser.isBlank() || st.hueIp.isBlank()) null
                                    else kotlinx.coroutines.withContext(Dispatchers.IO) { hue.groups(st.hueIp, st.hueUser) to hue.lights(st.hueIp, st.hueUser) }
                            }
                            buildList {
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
                            }
                        }))

                    add(Note("Hue colours"))
                    // A list to choose from (OK), with what each choice means.
                    fun pick(title: String, desc: String, cur: String, opts: List<Triple<String, String, String>>, art: ((String) -> Any?)? = null, set: (String) -> Unit) =
                        add(Act(title, desc, opts.firstOrNull { it.first == cur }?.second ?: opts[0].second) {
                            dialog = ThumbDialog(title, opts.map { (k, name, hint) -> ThumbItem(name, hint, art?.invoke(k), checked = k == cur) { set(k) } })
                        })
                    pick("Colours from", "What the lights take their colours from", st.hueSource, listOf(
                        Triple("backdrop", "Backdrop", "What fills the screen: the title's picture, your photo, the music page"),
                        Triple("poster", "Poster", "The title's poster"), Triple("logo", "Logo", "The title's logo")), { HueArt.source(it) }) { st.hueSource = it }
                    add(Adjust("Lights", "Each light a different colour of the picture, or all of them its main colour", {
                        if (st.hueSpread) "A colour each" else "One colour"
                    }, { st.hueSpread = !st.hueSpread }))
                    add(Adjust("Intensity", "How colourful the lights are", {
                        listOf("Subtle", "Natural", "Vivid", "Maximum")[st.hueIntensity]
                    }, { st.hueIntensity = (st.hueIntensity + it).coerceIn(0, 3) }))
                    add(Adjust("Brightness", "", { "${st.hueBri} %" }, { st.hueBri = st.hueBri + it * 5 }))
                    add(Toggle("Follow the picture's brightness", "Darker scenes, dimmer lights", { st.hueFollowBri }, { st.hueFollowBri = it }))
                    add(Toggle("Follow videos", if (com.glasslauncher.tv.data.Store.PLAY) "Your video background, live" else "Your video background and trailers, live", { st.hueVideo }, { st.hueVideo = it }))
                    if (st.hueVideo || st.hueSaver) add(Adjust("  · Every", "How often the lights follow a video", { if (st.hueVideoSec <= 0) "0.5 s" else "${st.hueVideoSec} s" }, { st.hueVideoSec = st.hueVideoSec + it }))
                    pick("Change speed", "How long the lights take to follow a video (a new title's colours come at once)", st.hueSpeedMs.toString(), listOf(
                        Triple("0", "Instant", ""), Triple("400", "Quick", "0.4 s"), Triple("1000", "Smooth", "1 s"),
                        Triple("2000", "Slow", "2 s"), Triple("4000", "Very slow", "4 s"))) { st.hueSpeedMs = it.toInt() }
                    add(Toggle("On the screensaver", "The lights follow its videos", { st.hueSaver }, { st.hueSaver = it }))

                    add(Note("Hue follows the selection"))
                    add(Toggle("The selected app", "All the lights take the colour of the app selected in the dock (its glow's colour)", { st.hueApps }, { st.hueApps = it }))
                    if (st.hueApps) add(Adjust("  · Brightness", "How bright the lights are with the app's colour", { "${st.hueAppsBri} %" }, { st.hueAppsBri = st.hueAppsBri + it * 5 }))
                    add(Toggle("The selected poster in the Library", "All the lights take the colour of the poster you're on", { st.huePosters }, { st.huePosters = it }))
                    if (st.huePosters) add(Adjust("  · Brightness", "How bright the lights are with the poster's colour", { "${st.huePostersBri} %" }, { st.huePostersBri = st.huePostersBri + it * 5 }))
                    add(Toggle("The selected card in Channels", "All the lights take the colour of the card you're on in an app's rows (as bright as the Library's posters)", { st.channelsHue }, { st.channelsHue = it }))

                    add(Note("Hue · Afterwards"))
                    pick("When you open an app or a film", "", st.hueLeave, listOf(
                        Triple("restore", "Back as they were", "As before Hue Sync"), Triple("dim", "Dim them", "Their own colour, at 20 %"),
                        Triple("off", "Turn them off", ""), Triple("keep", "Leave them", "In the last colours"))) { st.hueLeave = it }
                    pick("When the TV turns off", "", st.hueOff, listOf(
                        Triple("restore", "Back as they were", "As before Hue Sync"), Triple("off", "Turn them off", ""),
                        Triple("keep", "Leave them", "In the last colours"))) { st.hueOff = it }
                    add(Act("Put the lights back now", "As they were before Hue Sync (they follow the screen again on the home screen)", "Restore") {
                        sync.leave(st, "restore"); sync.resume(); toast(ctx, "Lights back as they were")
                    })
                }

                add(Note("Cameras"))
                add(pageRow("Cameras", "Find them on your network, take them from Home Assistant, or add a link", more(camList.size), sp = SubPage("Cameras") {
                        tvRev.intValue
                        buildList {
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
                            add(Act("Cameras from Home Assistant", "Aqara (through HomeKit), Frigate, Ring, Tapo… any camera Home Assistant shows. Uses the Home Assistant link and token (Doorbell → Home Assistant link)", "") {
                                io {
                                    check(st.haUrl.startsWith("http") && st.haToken.isNotBlank()) { "Add the Home Assistant link and token first (Doorbell → Home Assistant link)" }
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
                        }
                    }))
                add(Toggle("Camera in the menu", "Live view in the menu, like Apple TV (◀ ▶ switches camera)", { st.menuCamera }, { st.menuCamera = it }))
                add(Adjust("Camera video", "Software keeps the TV's own video playing while a camera shows (some TVs stopped it)", {
                    when (st.cameraDecoder) { "software" -> "Software"; "hardware" -> "Hardware"; else -> "Auto (" + (if (com.glasslauncher.tv.CamPlayer.software(ctx)) "software" else "hardware") + ")" }
                }, { st.cameraDecoder = cycle(listOf("auto", "software", "hardware"), st.cameraDecoder, it) }))

                add(Note("Corner camera"))
                add(link("System", "Hold Home", "Holding Home shows a camera in the corner, over any app: chosen with what Hold Home does"))
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
                add(Adjust("  · Closes after", "It goes away on its own after this (Back or holding Home again closes it sooner)",
                    { if (st.cameraCornerSec == 0) "Never" else if (st.cameraCornerSec < 60) "${st.cameraCornerSec} s" else "${st.cameraCornerSec / 60} min" },
                    { st.cameraCornerSec = cycle(listOf(10, 20, 30, 60, 120, 300, 0), st.cameraCornerSec, it) }))

                add(Note("Multi-view"))
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

                add(Note("Doorbell"))
                add(Toggle("Doorbell pop-up", "Home Assistant sends to http://<this TV>:7979/notify (the same as PiPup): the camera shows in the corner over any app",
                    { st.doorbellOn }, { st.doorbellOn = it }))
                if (st.doorbellOn) {
                    add(pageRow("  · Pop-up look", "Its size, camera, how long it shows, its words and chime", more(9), sp = SubPage("Pop-up look") {
                            tvRev.intValue
                            buildList {
                                add(Adjust("Size", "", { pct(st.doorbellScale) }, { st.doorbellScale = ((st.doorbellScale * 10).roundToInt() + it) / 10f }))
                                add(Adjust("Camera", "From Home Assistant: the one its automation sends; or one of your cameras here",
                                    { camList.firstOrNull { it.url == st.doorbellCamera }?.name ?: "From Home Assistant" },
                                    { d ->
                                        val opts = listOf("") + camList.map { it.url }
                                        val i = opts.indexOf(st.doorbellCamera).coerceAtLeast(0)
                                        st.doorbellCamera = opts[(i + d).mod(opts.size)]
                                    }))
                                if (camList.isNotEmpty()) add(Adjust("Switch cameras every", "Goes round your cameras on its own, starting with the doorbell's",
                                    { if (st.doorbellCycleSec == 0) "Off" else if (st.doorbellCycleSec < 60) "${st.doorbellCycleSec} s" else "${st.doorbellCycleSec / 60} min" },
                                    { st.doorbellCycleSec = cycle(listOf(0, 5, 10, 15, 20, 30), st.doorbellCycleSec, it) }))
                                add(Adjust("Shows for", "Then it goes away on its own (Back closes it sooner)",
                                    { if (st.doorbellSec < 60) "${st.doorbellSec} s" else "${st.doorbellSec / 60} min" + (if (st.doorbellSec % 60 != 0) " ${st.doorbellSec % 60} s" else "") },
                                    { st.doorbellSec = cycle(listOf(5, 10, 15, 20, 30, 45, 60, 90, 120), st.doorbellSec, it) }))
                                add(Toggle("Words", "Where and what, at the bottom of the camera", { st.doorbellText }, { st.doorbellText = it }))
                                if (st.doorbellText) {
                                    // Ready-made words, or your own.
                                    val presets = listOf(
                                        "العربية" to ("الباب الأمامي" to "يوجد شخص عند الباب"),
                                        "English" to ("Front Door" to "Someone is at the door"),
                                    )
                                    val cur = presets.firstOrNull { it.second.first == st.doorbellTitle && it.second.second == st.doorbellMessage }?.first ?: "Custom"
                                    add(Adjust("Language", "Ready-made words, or write your own below", { cur }, { d ->
                                        val names = presets.map { it.first }
                                        val i = names.indexOf(cur)
                                        val next = presets[((if (i < 0) -1 else i) + d).mod(presets.size)].second
                                        st.doorbellTitle = next.first; st.doorbellMessage = next.second
                                    }))
                                    add(Act("Title", "", st.doorbellTitle) {
                                        dialog = TextEntry("Doorbell title", st.doorbellTitle, "Front Door") { t -> st.doorbellTitle = t.trim() }
                                    })
                                    add(Act("Message", "", st.doorbellMessage) {
                                        dialog = TextEntry("Doorbell message", st.doorbellMessage, "Someone is at the door") { t -> st.doorbellMessage = t.trim() }
                                    })
                                }
                                add(Toggle("Chime", "A soft ding-dong when it shows", { st.doorbellChime }, { st.doorbellChime = it }))
                                add(Act("Try it", "Shows the pop-up now with your camera", "") {
                                    io { runCatching { com.glasslauncher.tv.net.NotifyServer.show(app, org.json.JSONObject()); "Shown" }.getOrElse { it.message ?: "Couldn't show it" } }
                                })
                            }
                        }))
                    // Glassio watches the doorbell sensor itself: works with no automation in Home Assistant.
                    add(pageRow("  · Home Assistant link", "Now: ${com.glasslauncher.tv.net.HaWatch.status}", more(if (st.haUrl.isBlank()) "Not set" else "Set"), sp = SubPage("Home Assistant link") {
                            tvRev.intValue
                            val ha = com.glasslauncher.tv.net.HaWatch
                            buildList {
                                add(Act("Home Assistant link", "Now: ${ha.status}", st.haUrl.ifBlank { "Not set" }) {
                                    dialog = TextEntry("Home Assistant link", st.haUrl.ifBlank { "http://" }, "http://homeassistant.local:8123") { u ->
                                        st.haUrl = u; ha.restart(); tvRev.intValue++
                                    }
                                })
                                add(Act("Token", "Home Assistant → your profile → Security → Long-lived access tokens. Easier to paste from your phone (Manage from phone)",
                                    if (st.haToken.isBlank()) "Not set" else "Saved") {
                                    dialog = TextEntry("Home Assistant token", "", "Long-lived access token") { t ->
                                        if (t.isNotBlank()) { st.haToken = t; ha.restart(); tvRev.intValue++ }
                                    }
                                })
                                add(Act("Doorbell sensor", "The one that turns on when the doorbell rings", st.haDoorbellEntity.ifBlank { "Pick" }) {
                                    io {
                                        check(st.haUrl.startsWith("http") && st.haToken.isNotBlank()) { "Add the Home Assistant link and token first" }
                                        val list = ha.sensors(st.haUrl, st.haToken)
                                        if (list.isEmpty()) return@io "No sensors found"
                                        dialog = MenuDialog("Doorbell sensor", list.map { (id, n) -> "$n · $id" to { st.haDoorbellEntity = id; ha.restart(); tvRev.intValue++ } })
                                        "${list.size} sensors"
                                    }
                                })
                                add(Act("Pop-up token (optional)", if (st.doorbellToken.isBlank()) "Off: any device at home can show the pop-up. On: Home Assistant adds the header X-Glassio-Token (or ?token= in the link)"
                                    else "On: only requests with X-Glassio-Token: ${st.doorbellToken}", if (st.doorbellToken.isBlank()) "Make one" else "Remove") {
                                    st.doorbellToken = if (st.doorbellToken.isBlank()) com.glasslauncher.tv.net.Net.token() else ""; tvRev.intValue++
                                })
                            }
                        }))
                }

                add(Note("Home Assistant"))
                add(Act("Home Assistant webhook", "Full webhook URL from your Home Assistant", if (st.haWebhook.isBlank()) "Not set" else "Set",
                    ok = if (st.haWebhook.startsWith("http")) true else null) {
                    dialog = TextEntry("Home Assistant webhook", st.haWebhook, "http://homeassistant.local:8123/api/webhook/…") { st.haWebhook = it }
                })
                add(Toggle("  · Tell it when a title opens", "Sends title, type and id as JSON", { st.haOnLaunch }, { st.haOnLaunch = it }))
                add(Toggle("  · Send Nuvio playback", if (st.haUrl.isBlank() || st.haToken.isBlank()) "Needs the Home Assistant link and token (Doorbell → Home Assistant link)"
                    else "Nuvio Legacy's title, play / pause and position, as it happens (event glassio_nuvio): Home Assistant no longer needs to ask the TV over ADB",
                    { st.nuvioPush }, { on -> st.nuvioPush = on; if (on) com.glasslauncher.tv.data.NuvioWatch.start(ctx) else com.glasslauncher.tv.data.NuvioWatch.stop() }))
                add(Act("  · Send a test event", "") { io { NuvioLaunch.testWebhook(st, app.http) } })
            }
        },
        Category("System") {
            tvRev.intValue
            val svcOn = com.glasslauncher.tv.HomeKeyService.instance != null
            // One button does one thing: taking it for something new frees it from what it did before.
            fun free(code: Int) {
                if (st.multiKey == code) st.multiKey = 0
                if (st.cornerKey == code) st.cornerKey = 0
                if (st.shuffleKey == code) st.shuffleKey = 0
                com.glasslauncher.tv.data.RemoteMap.remove(st, code)
            }
            fun mapTo(code: Int, action: String, label: String) {
                free(code); com.glasslauncher.tv.data.RemoteMap.set(st, code, action, label); tvRev.intValue++
                toast(ctx, "${keyName(code)}: " + com.glasslauncher.tv.data.RemoteMap.describe(com.glasslauncher.tv.data.RemoteMap.Map(code, action, label)).lowercase())
            }
            // What a button should open: an app (with its picture), the Apps folder, the recent apps or a TV input.
            fun choose(code: Int) {
                val opts = mutableListOf<ThumbItem>()
                // Glassio itself (its home screen), for a remote whose Home button doesn't work (#13).
                opts += ThumbItem("Glassio", "Glassio's home screen, from any app", HueArt.home()) { mapTo(code, "glassio", "Glassio") }
                opts += ThumbItem("The Apps folder", "Every app · press again to close it", HueArt.folder()) { mapTo(code, "folder", "Apps folder") }
                if (st.hueIp.isNotBlank() && st.hueUser.isNotBlank())
                    opts += ThumbItem("Hue lights off", "A room or a group of lights · press again: on", HueArt.bulbOff()) {
                        scope.launch(Dispatchers.IO) {
                            val gs = com.glasslauncher.tv.data.Hue.groups(st.hueIp, st.hueUser).filter { it.type != "Entertainment" }
                            val items = listOf(ThumbItem("All the lights", "Every Hue light", HueArt.bulbOff()) { mapTo(code, "hue:0", "All") }) +
                                gs.map { g -> ThumbItem(g.name, (if (g.type == "Room") "Room" else "Zone") + " · ${g.lights.size} lights", HueArt.bulbOff()) { mapTo(code, "hue:" + g.id, g.name) } }
                            kotlinx.coroutines.withContext(Dispatchers.Main) { dialog = ThumbDialog("${keyName(code)} turns off…", items) }
                        }
                    }
                opts += ThumbItem("Recent apps", "The open apps, like holding Home", HueArt.recents()) { mapTo(code, "recents", "Recent apps") }
                if (st.searchOn) opts += ThumbItem("Search", "Glassio's search: films, series, videos, songs", null) { mapTo(code, "search", "Search") }
                com.glasslauncher.tv.data.Inputs.list.value.forEach { inp ->
                    val pic = app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == com.glasslauncher.tv.data.Inputs.PREFIX + inp.id }?.let { app.apps.cachedArtwork(it)?.bmp }
                        ?: HueArt.input(inp.label)
                    opts += ThumbItem(inp.label, "TV input", pic) { mapTo(code, com.glasslauncher.tv.data.Inputs.PREFIX + inp.id, inp.label) }
                }
                app.apps.ordered(includeHidden = true).filter { !com.glasslauncher.tv.data.Inputs.isInput(it.pkg) && it.pkg != MusicUi.DOCK_PKG && it.pkg != SearchUi.DOCK_PKG }.forEach { e ->
                    opts += ThumbItem(e.label, "App", app.apps.cachedArtwork(e)?.bmp) { mapTo(code, "app:" + e.pkg, e.label) }
                }
                dialog = ThumbDialog("${keyName(code)} opens…", opts)
            }
            // A set button asks first (a "Clear" row of its own was one OK away from losing it).
            fun keyRow(title: String, desc: String, get: () -> Int, set: (Int) -> Unit): List<SRow> = listOf(
                Act(title, desc, if (get() == 0) "Set" else keyName(get()), ok = if (get() != 0) true else null) {
                    val capture = { dialog = KeyCaptureDialog(title) { code -> free(code); set(code); tvRev.intValue++; toast(ctx, "$title: ${keyName(code)}") } }
                    if (get() == 0) capture()
                    else dialog = MenuDialog("$title · ${keyName(get())}", listOf("Choose another button" to { capture() }, "Clear this button" to { set(0); tvRev.intValue++ }))
                },
            )
            fun custom() { st.perfMode = "custom" }
            com.glasslauncher.tv.data.PeerSync.rev.intValue
            buildList {
                add(Note("Home button & start"))
                // The play build: what the service is for is read and agreed to before the TV's settings open
                // (and the service does nothing until then).
                fun agreeThen(then: () -> Unit) {
                    if (!com.glasslauncher.tv.data.Store.PLAY || st.a11yConsent) then()
                    else dialog = ConsentDialog(A11Y_TITLE, A11Y_TEXT, "Agree and open the TV's Accessibility settings") { st.a11yConsent = true; then() }
                }
                add(Toggle("Home button opens Glassio", "Needs Glassio's accessibility service", { st.interceptHome }, { on -> st.interceptHome = on; if (on && com.glasslauncher.tv.data.Store.PLAY && !homeKeyOn) agreeThen { openSystem(ctx, AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) } }))
                add(Act("  · Turn on the service", "Find Glassio and switch it on", "") { agreeThen { openSystem(ctx, AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) } })
                if (com.glasslauncher.tv.data.Store.PLAY && st.a11yConsent) add(Act("  · Take back my agreement", "The service then does nothing, also while it is on in the TV's settings", "") { st.a11yConsent = false; toast(ctx, "Taken back") })
                if (!com.glasslauncher.tv.data.Store.PLAY) add(Act("  · With ADB", if (homeKeyOn) "The accessibility service is on" else "When the TV doesn't let you (restricted setting / greyed out). Needs TV access (ADB)", "") {
                    scope.launch(Dispatchers.IO) {
                        val svc = "${ctx.packageName}/com.glasslauncher.tv.HomeKeyService"
                        val cmd = "cur=\$(settings get secure enabled_accessibility_services); " +
                            "case \"\$cur\" in *$svc*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; " +
                            "*) settings put secure enabled_accessibility_services \"\$cur:$svc\" ;; esac; " +
                            "settings put secure accessibility_enabled 1; appops set ${ctx.packageName} ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; " +
                            // Lets Glassio switch the service back on by itself after every update.
                            "pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; true"
                        // Without ADB (Fire TV) the right to change the TV's settings, given from a computer, is enough.
                        val direct = com.glasslauncher.tv.data.SystemSetup.secureOk(ctx) && com.glasslauncher.tv.data.SystemSetup.enableHomeKey(ctx)
                        val r = if (direct) Result.success("") else com.glasslauncher.tv.data.LocalShell.run(ctx, cmd)
                        homeKeyOn = isHomeKeyOn(ctx)
                        toast(ctx, if (r.isSuccess && homeKeyOn) "Accessibility service turned on" else if (r.isSuccess) "Done, but the TV didn't turn it on. Try the button above" else "Allow TV access (ADB) first: TV → TV access (ADB)")
                    }
                })
                add(Act("Choose default home app", "", "") { openSystem(ctx, AndroidSettings.ACTION_HOME_SETTINGS) })
                add(Toggle("Open Glassio when the TV starts", if (com.glasslauncher.tv.data.Store.PLAY) "Glassio opens on its own after the TV turns on. Needs \"Display over other apps\" for Glassio, in the TV's settings"
                    else "Glassio opens on its own after the TV turns on. The right to do so is given over ADB (TV → TV access (ADB))", { st.bootStart }, { on ->
                    st.bootStart = on
                    // The play build never gives itself the right: the TV's own page for it opens.
                    if (com.glasslauncher.tv.data.Store.PLAY) {
                        if (on && !AndroidSettings.canDrawOverlays(ctx)) runCatching {
                            ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { toast(ctx, "Allow \"Display over other apps\" for Glassio: TV settings → Apps → Special app access") }
                    } else if (on) scope.launch(Dispatchers.IO) {
                        val r = com.glasslauncher.tv.data.LocalShell.run(ctx, "appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow; " +
                            "cmd package set-home-activity ${ctx.packageName}/com.glasslauncher.tv.MainActivity >/dev/null 2>&1; true")
                        toast(ctx, if (r.isSuccess) "Glassio opens when the TV starts" else "Saved. Allow TV access (ADB) so the TV lets Glassio open")
                    }
                }))

                add(Note("Remote buttons"))
                val mapped = com.glasslauncher.tv.data.RemoteMap.all(st).size + listOf(st.shuffleKey, st.multiKey, st.cornerKey).count { it != 0 }
                add(pageRow("Remote buttons", "A button of your remote (Netflix, YouTube, a colour button…) opens what you choose; Glassio's own: shuffle, multi-view, corner camera", more(mapped), sp = SubPage("Remote buttons") {
                        tvRev.intValue
                        buildList {
                            if (!svcOn) add(Note("Buttons in other apps need the Home button service: System → Home button opens Glassio."))
                            add(Note("Buttons that open something"))
                            com.glasslauncher.tv.data.RemoteMap.all(st).forEach { m ->
                                add(Act(keyName(m.code), com.glasslauncher.tv.data.RemoteMap.describe(m), "Change") {
                                    dialog = MenuDialog(keyName(m.code), listOf(
                                        "Open something else" to { choose(m.code) },
                                        "Remove this button" to { com.glasslauncher.tv.data.RemoteMap.remove(st, m.code); tvRev.intValue++ },
                                    ))
                                })
                            }
                            add(Act("Add a button", "Press a remote button (Netflix, YouTube, a colour button…), then choose Glassio, an app, the Apps folder, the recent apps, an input or Hue lights to turn off", "Add") {
                                dialog = KeyCaptureDialog("Press the button you want") { code -> choose(code) }
                            })
                            add(Note("Glassio's buttons"))
                            addAll(keyRow("Shuffle button", "On the home screen: the catalog titles in a new random order", { st.shuffleKey }, { st.shuffleKey = it }))
                            addAll(keyRow("Multi-view button", "Anywhere: the cameras in the middle (again closes them)", { st.multiKey }, { st.multiKey = it }))
                            addAll(keyRow("Corner camera button", "Anywhere: the camera in the corner (again closes it)", { st.cornerKey }, { st.cornerKey = it }))
                        }
                    }))
                add(Act("Hold Home", "What holding the Home button does, in any app", when (st.homeLong) {
                    "recents" -> "Recent apps"; "camera" -> "Corner camera"; "library" -> "The Library"; "menu" -> "The menu"; else -> "Nothing" }) {
                    dialog = ThumbDialog("Hold Home", listOf("recents" to ("Recent apps" to "The open apps, like the Apple TV app switcher"),
                        "camera" to ("Corner camera" to "A live camera in the corner, over any app"), "library" to ("The Library" to ""),
                        "menu" to ("The menu" to ""), "off" to ("Nothing" to "")).map { (k, v) -> ThumbItem(v.first, v.second, null, checked = st.homeLong == k) { st.homeLong = k; tvRev.intValue++ } })
                })
                add(Toggle("Recent apps use TV access (ADB)", "On: the exact open apps, while USB debugging is on. Off, or with USB debugging off: the apps used lately, no ADB needed",
                    { st.recentsAdb }, { st.recentsAdb = it }))
                add(Act("  · Usage access for Recent apps", "Optional: also lists apps opened before Glassio started",
                    if (com.glasslauncher.tv.data.AppHistory.usageAccess(ctx)) "On" else "Off", ok = if (com.glasslauncher.tv.data.AppHistory.usageAccess(ctx)) true else null) {
                    val i = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { ctx.startActivity(i) }.isFailure)
                        android.widget.Toast.makeText(ctx, "This TV has no Usage access screen: Recent apps still works without it", android.widget.Toast.LENGTH_LONG).show()
                })

                add(Note("Performance"))
                add(Adjust("Mode", when (st.perfMode) {
                    "auto" -> "Picked for this TV from its memory and graphics chip"
                    "performance" -> "Smoothest: lighter pictures, quick fades, flat glass"
                    "quality" -> "Sharpest: original pictures, slow fades, full glass"
                    "balanced" -> "Sharp pictures, smooth navigation"
                    else -> "Your own mix of the settings below"
                }, {
                    fun n(m: String) = when (m) { "performance" -> "Performance"; "quality" -> "Quality"; "balanced" -> "Balanced"; else -> "Custom" }
                    if (st.perfMode == "auto") "Auto (${n(st.autoTier)})" else n(st.perfMode)
                }, { st.applyMode(cycle(listOf("auto", "performance", "balanced", "quality"), st.perfMode.takeIf { m -> m != "custom" } ?: "auto", it)) }))
                add(Adjust("  · Glass detail", "Resolution of the picture the glass bends (higher = crisper glass, more memory)", {
                    when (st.glassRes) { 720 -> "Sharp (720 px)"; else -> "Standard (480 px)" }
                }, { st.glassRes = cycle(listOf(480, 720), st.glassRes, it); custom() }))
                add(Adjust("  · Picture quality", "Carousel backdrops. 1280 enhanced: the light 1280 px picture, sharpened as it is scaled up",
                    { when (st.imageQuality) { "high" -> "High (1280 px)"; "hd" -> "1280 enhanced"; else -> "Maximum (original)" } },
                    { st.imageQuality = cycle(listOf("high", "hd", "max"), st.imageQuality, it); custom() }))
                add(Adjust("  · Picture cache", "Posters, backdrops and logos kept on the TV, so browsing back is instant. Applies after a restart", { "${st.cacheMb / 1024.0}".removeSuffix(".0") + " GB" },
                    { st.cacheMb = cycle(listOf(512, 1024, 1536, 2048, 3072, 4096), st.cacheMb, it) }))
                add(Adjust("  · Prepare ahead", "Slides loaded before you get to them", { "${st.prefetchDepth}" }, { st.prefetchDepth += it; custom() }))

                add(Note("Other TVs & backups"))
                add(pageRow("Sync between TVs", when (com.glasslauncher.tv.data.PeerSync.role) {
                    "main" -> "The main TV: the TVs that follow it take its setup"
                    "follow" -> "Follows ${com.glasslauncher.tv.data.PeerSync.peerName.ifBlank { "another TV" }}"
                    else -> "Keep Glassio the same on all your TVs"
                }, more(when (com.glasslauncher.tv.data.PeerSync.role) { "main" -> "Main TV"; "follow" -> "Follows"; else -> "Off" }), sp = SubPage("Sync between TVs") {
                        tvRev.intValue
                        val ps = com.glasslauncher.tv.data.PeerSync
                        ps.rev.intValue
                        // Follow a main TV: find it on the network, then the code it shows.
                        fun follow() {
                            toast(ctx, "Looking for Glassio on your network…")
                            scope.launch(Dispatchers.IO) {
                                val found = ps.discover()
                                kotlinx.coroutines.withContext(Dispatchers.Main) {
                                    if (found.isEmpty()) { toast(ctx, "No main TV found. On the other TV: Settings → System → Sync between TVs → This TV → Main TV (both on the same network)"); return@withContext }
                                    dialog = MenuDialog("Follow which TV?", found.map { f -> "${f.name}  ·  ${f.host}" to {
                                        AppLock.ask.value = AppLock.Ask("The code shown on ${f.name}", newCode = true) { code ->
                                            io { ps.pair(f, code) ?: "Following ${f.name}: its setup comes over now" }
                                        }
                                    } })
                                }
                            }
                        }
                        fun ago(t: Long): String {
                            val m = (System.currentTimeMillis() - t) / 60_000
                            return when { t == 0L -> "never"; m < 1 -> "just now"; m < 60 -> "$m min ago"; m < 1440 -> "${m / 60} h ago"; else -> "${m / 1440} days ago" }
                        }
                        buildList {
                            add(Note("Keep Glassio the same on all your TVs: one is the main TV, the others follow it over your home network, live or when you press Sync now. " +
                                "Sign-ins and HDMI inputs never move (sign in on each TV). Performance, keys, Hue, cameras, remote buttons and the lock move only if you turn them on."))
                            add(Act("This TV", when (ps.role) {
                                "main" -> "The main TV: the TVs that follow it take its setup"
                                "follow" -> "Follows ${ps.peerName.ifBlank { "another TV" }}"
                                else -> "Not synced with another TV"
                            }, when (ps.role) { "main" -> "Main TV"; "follow" -> "Follows"; else -> "Off" }) {
                                dialog = MenuDialog("This TV", listOf(
                                    "Main TV (the others follow it)" to { if (ps.role != "main") { ps.role = "main"; toast(ctx, "This is the main TV now. Add a TV shows the code for the other one") } },
                                    "Follows another TV" to { follow() },
                                    "Off" to { if (ps.role == "follow") ps.unfollow() else ps.role = "off" },
                                ))
                            })
                            add(Act("This TV's name", "How your other TVs see it", ps.name) {
                                dialog = MenuDialog("This TV's name", (listOf("Living room", "Bedroom", "Kids' room", "Office", "Kitchen", "Guest room", android.os.Build.MODEL))
                                    .map { n -> ((if (n == ps.name) "✓  " else "") + n) to { ps.name = n } })
                            })
                            when (ps.role) {
                                "main" -> {
                                    add(Act("Add a TV", "Shows a code: on the other TV choose Settings → System → Sync between TVs → This TV → Follows another TV, then type it", "Show code") {
                                        val c = ps.openPairing()
                                        dialog = MenuDialog("Code for the other TV:   ${c.toCharArray().joinToString("  ")}", listOf("Done (the code works for 5 minutes)" to { }))
                                    })
                                    val fl = ps.followers.entries.sortedByDescending { it.value.second }
                                    add(Note(if (fl.isEmpty()) "No TV follows this one yet." else "Following this TV"))
                                    fl.forEach { (n, v) -> add(Act(n, "Last synced ${ago(v.second)} · ${v.first}", if (System.currentTimeMillis() - v.second < 120_000) "Online" else "", ok = System.currentTimeMillis() - v.second < 120_000) { }) }
                                    add(Toggle("Pause sharing", "To try things out: the TVs that follow keep how this TV was when you paused, and get everything changed meanwhile when you turn it off",
                                        { ps.paused }, { ps.paused = it }))
                                    if (fl.isNotEmpty()) add(Act("Stop every TV following this one", "They need a new code to follow it again", "Stop") {
                                        dialog = MenuDialog("Stop every TV following this one?", listOf("Stop" to { ps.dropFollowers(); toast(ctx, "No TV follows this one now") }, "Cancel" to { }))
                                    })
                                }
                                "follow" -> {
                                    add(Act("Main TV", ps.status.value.ifBlank { "Connecting…" }, ps.peerName, ok = if (ps.status.value.startsWith("Up to date")) true else null) {
                                        dialog = MenuDialog(ps.peerName, listOf("Follow another TV" to { follow() }, "Stop following" to { ps.unfollow(); toast(ctx, "This TV no longer follows ${ps.peerName}") }))
                                    })
                                    add(Adjust("Sync", "Automatic: what you change on the main TV shows here a moment later. Manual: only when you press Sync now",
                                        { if (ps.mode == "manual") "Manual" else "Automatic" }, { ps.mode = if (ps.mode == "manual") "auto" else "manual" }))
                                    add(Act("Sync now", "Last synced ${ago(ps.lastAt)}", if (ps.waiting.value) "Changes waiting" else "Sync", ok = if (ps.waiting.value) null else true) {
                                        io { ps.syncNow()?.let { "Couldn't sync: $it" } ?: "Synced from ${ps.peerName}" }
                                    })
                                    add(Note("What this TV takes from ${ps.peerName.ifBlank { "the main TV" }}"))
                                    val g = ps.groups
                                    val desc = mapOf(
                                        "look" to "Theme, glass, colours, glow, corners, icon pack, the settings button",
                                        "dock" to "The dock's order, hidden apps, its size and place, and icons you made",
                                        "home" to "Carousel, titles, clock and weather, menu, Continue Watching, Library layout, background",
                                        "channels" to "Each app's rows, the music player and the island",
                                        "saver" to "The screensaver: what it shows and how",
                                        "catalogs" to "Your add-ons and the catalogs chosen from them",
                                        "lists" to "Titles saved in Glassio's Library, and likes",
                                    )
                                    com.glasslauncher.tv.data.PeerSync.GROUPS.forEach { (k, n) ->
                                        add(Toggle(n, desc[k].orEmpty(), { k in g }, { on -> ps.groups = if (on) g + k else g - k }))
                                    }
                                    add(Note("Also, if your TVs are in the same home (off unless you turn them on)"))
                                    val extra = mapOf(
                                        "keys" to "Your TMDB key, Unsplash key and Home Assistant (address, token, webhook). They go only to TVs you paired, over your home network",
                                        "hue" to "The Hue bridge Glassio is paired with (each TV keeps its own lights and room)",
                                        "cameras" to "Your cameras, the corner camera and multi-view, and the doorbell",
                                        "remote" to "Glassio's remote buttons and Hold Home (for TVs with the same kind of remote)",
                                        "lock" to "Which apps are locked, the code and its reminder",
                                        "perf" to "The performance mode and what it sets: picture quality, glass detail and refraction, slide animation, the backdrop gallery, caches and screensaver quality. Off: this TV keeps its own, so a weaker TV stays smooth",
                                    )
                                    com.glasslauncher.tv.data.PeerSync.EXTRAS.forEach { (k, n) ->
                                        add(Toggle(n, extra[k].orEmpty(), { k in g }, { on -> ps.groups = if (on) g + k else g - k }))
                                    }
                                    add(Note("Never synced: Nuvio, Stremio, Trakt and Simkl sign-ins. Each of them hands one TV a sign-in that the next use replaces: shared, the two TVs would sign each other out. Sign in on each TV once."))
                                    add(Toggle("Show a note when synced", "A short note on screen when a change from ${ps.peerName.ifBlank { "the main TV" }} arrives", { ps.notify }, { ps.notify = it }))
                                    add(Note("One way only: what you change here in a synced part is replaced by the main TV's next change. Apps not installed on this TV are left out of its dock."))
                                }
                            }
                        }
                    }))
                add(pageRow("Manage from phone", "Add-ons, tokens, icons, cameras, videos and backups from your phone's browser", more(""), sp = SubPage("Manage from phone", Kind.Phone)))
                add(Act("Back up now", if (st.backupSignIns) "Every setting, catalog, Library, Continue Watching, account and token, saved to Download/Glassio on the TV (also from the phone page). Keep it private"
                    else "Every setting, catalog, Library and Continue Watching (no sign-ins), saved to Download/Glassio on the TV (also from the phone page)", "Back up") {
                    scope.launch(Dispatchers.IO) { toast(ctx, runCatching { "Saved: " + com.glasslauncher.tv.data.Backup.saveToTv(ctx) }.getOrElse { "Backup failed: ${it.message}" }) }
                })
                add(Act("Restore backup", "The newest backup in Download/Glassio on the TV. Glassio restarts after", "Restore") {
                    // Asked first: it replaces everything, and from another TV some things don't come along.
                    dialog = MenuDialog("Replace every setting on this TV?", listOf("Restore and restart" to { scope.launch(Dispatchers.IO) {
                        val j = com.glasslauncher.tv.data.Backup.latestOnTv(ctx)
                        if (j == null) toast(ctx, "No backup on the TV yet (Download/Glassio)")
                        else runCatching { val n = com.glasslauncher.tv.data.Backup.restore(ctx, j); toast(ctx, "Restored ($n parts). Restarting…"); kotlinx.coroutines.delay(1200); com.glasslauncher.tv.data.Backup.restart(ctx) }
                            .onFailure { toast(ctx, "Restore failed: ${it.message}") }
                    }; Unit }, "Cancel" to { }))
                })
                add(Toggle("Sign-ins in backups", "On: accounts and keys (Nuvio, Trakt, Simkl, Stremio, TMDB, Home Assistant, Hue, the lock code) go into the backup too, to move them to another TV: keep the file private. Off: they stay on each TV",
                    { st.backupSignIns }, { st.backupSignIns = it }))
                add(Note("Moving your setup to another TV: back up here (or from the phone page), then restore on the other TV. " + com.glasslauncher.tv.data.Backup.MOVES))
                add(Note(com.glasslauncher.tv.data.Backup.STAYS))
                add(Note(com.glasslauncher.tv.data.Backup.WARN))

                add(Note("App lock"))
                add(Act("Change the code", "4 digits, 0000 at first. Asks for the current code, then the new one: OK saves it (the same code again is fine too)",
                    "${st.lockedApps.size} locked") { AppLock.change(app, ctx) })
                add(Toggle("Show a hint", "Your own words under the digits", { st.lockHintOn }, { st.lockHintOn = it }))
                add(Act("  · Hint", "Anything you like (even the code itself)", st.lockHint.ifBlank { "Not set" }) {
                    dialog = TextEntry("Hint under the digits", st.lockHint, "e.g. year we met") { v -> st.lockHint = v.trim(); if (v.isNotBlank()) st.lockHintOn = true }
                })
                add(Note("Lock an app: hold OK on it in the dock → Lock. It then asks for the code when opened from Glassio: ▲ ▼ change a digit, ◀ ▶ move, and it opens as soon as the code is right."))

                add(Note("TV"))
                add(Adjust("Language", "Glassio's screens in this language, at once. Made by machine: tell us where the wording is wrong",
                    { com.glasslauncher.tv.data.I18n.LANGS[st.lang] ?: "English" }, { st.lang = cycle(com.glasslauncher.tv.data.I18n.LANGS.keys.toList(), st.lang, it) }))
                add(pageRow("TV access (ADB)", "Continue Watching from Nuvio Legacy, Glassio as the TV screensaver, TV info, open apps, starting with the TV and silent updates use it. Three steps, once",
                    if (adbAccess == true) more("Allowed") else more("3 steps"), ok = if (adbAccess == true) true else null, sp = SubPage("TV access (ADB)") {
                        val devOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }.getOrDefault(false)
                        val adbOn = runCatching { AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
                        buildList {
                            add(Note(if (com.glasslauncher.tv.data.Store.PLAY) "TV access (ADB): Continue Watching from Nuvio Legacy, Glassio as the TV screensaver, TV info, inputs and open apps use the TV's own debugging connection. Optional. Turn it on once:"
                                else "TV access (ADB): Continue Watching from Nuvio Legacy, Glassio as the TV screensaver, TV info, open apps, starting with the TV and silent updates use the TV's own debugging connection. Turn it on once:"))
                            add(Note("1. Developer options: open About below and press OK on \"Android TV OS build\" (or \"Build\") 7 times, until it says you are a developer."))
                            add(Note("2. Open Developer options below and turn on USB debugging (on some TVs also \"Network debugging\" / \"ADB over network\")."))
                            if (!com.glasslauncher.tv.data.Store.PLAY && com.glasslauncher.tv.data.SystemSetup.isFireTv()) add(Note("Fire TV doesn't let apps use its own ADB any more (Fire OS 7.6.6.9 / 8.1.0.3 and later), so \"Allow Glassio\" can't work there. " +
                                "From a computer or phone with ADB, connected to this Fire TV, type once: " + com.glasslauncher.tv.data.SystemSetup.FIRE_TV_COMMANDS +
                                ". Glassio then turns on the Home button service (remote buttons, cameras) and the music player by itself."))
                            add(Note("3. Press \"Allow Glassio\" below. The TV asks \"Allow USB debugging?\": tick Always allow from this computer, then OK. It is asked only once."))
                            add(Act("1 · Open About (enable Developer options)", if (devOn) "Developer options are on" else "Press OK on the build number 7 times", "") { openSystem(ctx, AndroidSettings.ACTION_DEVICE_INFO_SETTINGS) })
                            add(Act("2 · Open Developer options", if (adbOn) "USB debugging is on" else "Turn on USB debugging", "") { openSystem(ctx, AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
                            add(Act("3 · Allow Glassio", when (adbAccess) { true -> "Allowed: Glassio can use ADB"; false -> "Not allowed yet: press OK, then Always allow on the TV"; else -> "Press OK to check" }, "") {
                                scope.launch(Dispatchers.IO) {
                                    val r = com.glasslauncher.tv.data.LocalShell.run(ctx, "echo ok")
                                    val ok = r.isSuccess
                                    adbAccess = ok
                                    // Why it failed, in a few words (a TV that never asks is told apart from one that refused).
                                    val why = r.exceptionOrNull()?.let { e -> (e.cause ?: e).let { c -> c.message?.take(60) ?: c.javaClass.simpleName } }.orEmpty()
                                    toast(ctx, if (ok) "Glassio can use ADB" else if (!adbOn) "Turn on USB debugging first (step 2)"
                                        else if (com.glasslauncher.tv.data.SystemSetup.isFireTv()) "Fire TV doesn't let apps use its own ADB: see the note above (from a computer, once)"
                                        else "Choose Always allow on the TV, then press OK here again" + if (why.isNotBlank()) " ($why)" else "")
                                }
                            })
                        }
                    }))
                add(Act("Run setup again", "The welcome screen: restore, catalogs, accounts and theme", "") { st.setupDone = false; app.requestSetup.intValue++ })
            }
        },
        Category("About", Kind.About),
    ) }

    // Catalog choices made here are applied when the screen closes; only when one of them changed (every visit to
    // Settings reloaded every catalog, and the home screen was rebuilt with them just as you came back).
    val catalogSig = remember { app.catalogs.signature() }
    DisposableEffect(Unit) { onDispose { if (app.catalogs.signature() != catalogSig) app.scope.launch(Dispatchers.IO) { runCatching { app.catalogs.refresh() } } } }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    // The New and Changed pages (their own places under the search), instead of a section.
    var special by remember { mutableStateOf<String?>(null) }
    val newFocus = remember { FocusRequester() }
    val changedFocus = remember { FocusRequester() }
    val newMarks = (NEW_IN[newVer].orEmpty() - st.settingsNewSeen.toSet()).toSet()
    val marks = remember(newMarks) { NewMarks(newMarks) { t -> if (t in st.settingsNewSeen.toSet()) Unit else st.settingsNewSeen = st.settingsNewSeen + t } }
    /** Where Left (and Back) from the page on the right lead: the entry it belongs to. */
    fun leftOf() = when { searching -> searchFocus; special == "new" -> newFocus; special == "changed" -> changedFocus; else -> catFocus[cat] }
    // A search asked from outside (QA: --es settings_search "words").
    // (Watched, not a key of the effect: clearing it would have cancelled the effect clearing it.)
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { SettingsAskSearch.value }.collect { q ->
            if (q == null) return@collect
            kotlinx.coroutines.delay(700) // (after Settings' own first selection)
            query = q; searching = true; special = null; runCatching { searchFocus.requestFocus() }
            SettingsAskSearch.value = null
        }
    }
    // Back: out of a page opened from a row first (to that row), then to the sections, then out of Settings.
    BackHandler { if (subs.isNotEmpty() && !searching && special == null) closeSub() else if (rightFocusedA[0]) runCatching { leftOf().requestFocus() } else onClose() }
    LaunchedEffect(Unit) { delay150(); runCatching { firstCat.requestFocus() } }

    // Each page keeps its scroll and the row you were on, outside the glass panel: changing "Settings background"
    // swaps the panel, which rebuilt the page at the top with nothing selected (the remote then moved whatever was
    // behind Settings).
    val listStates = remember { HashMap<Int, androidx.compose.foundation.lazy.LazyListState>() }
    val rowFocus = remember { HashMap<Long, FocusRequester>() }
    val lastRow = remember { IntArray(32) { -1 } }
    fun rowReq(c: Int, i: Int) = rowFocus.requester(c * 10_000L + i)
    // The page on the right: the section's, or the last page opened from a row; each with its own list and row.
    val subLists = remember { HashMap<SubPage, androidx.compose.foundation.lazy.LazyListState>() }
    fun pageSlot() = when { special == "new" -> NEW_SLOT; special == "changed" -> CHANGED_SLOT; subs.isEmpty() -> shownCat; else -> SUB_SLOT + subs.size - 1 }
    fun pageList(slot: Int) = if (slot >= NEW_SLOT) listStates[slot] else if (slot >= SUB_SLOT) subs.getOrNull(slot - SUB_SLOT)?.let { subLists[it] } else listStates[slot]
    // Another section: its pages opened from rows are gone.
    val subsOf = remember { intArrayOf(shownCat) }
    LaunchedEffect(shownCat) { if (subsOf[0] != shownCat) { subsOf[0] = shownCat; subs.clear(); subLists.clear() } }
    var anyFocus by remember { mutableStateOf(true) }
    // Safety net: if nothing in Settings is selected (a page was rebuilt, a row went away), select the row you were
    // on, or this page's category.
    LaunchedEffect(anyFocus, dialog, phoneDialog) {
        if (anyFocus || dialog != null || phoneDialog) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        val slot = pageSlot()
        val r = lastRow[slot]
        val ok = r >= 0 && runCatching {
            pageList(slot)?.let { st0 -> if (r !in st0.layoutInfo.visibleItemsInfo.map { it.index }) st0.scrollToItem(r) }
            kotlinx.coroutines.delay(16); rowReq(slot, r).requestFocus()
        }.isSuccess
        if (!ok || !anyFocus) runCatching { catFocus[cat].requestFocus() }
    }
    // Search: a query typed on Glassio's own keyboard; a result opens its page at that row.
    var jump by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    // The row you were on last time (Settings opened plainly).
    LaunchedEffect(Unit) {
        val r = app.settings.settingsLastRow
        if (resume && r >= 0) { delay150(); lastRow[initialCat] = r; jump = initialCat to r }
    }
    // Remembered when Settings closes (also after Glassio starts again).
    DisposableEffect(Unit) { onDispose { app.settings.settingsLastCat = shownCat; app.settings.settingsLastRow = lastRow.getOrElse(shownCat) { -1 } } }
    val onAppMenu: (AppEntry) -> Unit = remember { { e -> appMenu(app, e, ctx, scope) { dialog = it } } }
    LaunchedEffect(jump) {
        val (c, r) = jump ?: return@LaunchedEffect
        // The row is asked for in every frame until it takes the selection (its page is composed by now, its rows
        // with the next layout): no fixed waits, which left the page with nothing selected for a quarter of a second.
        var done = false; var n = 0
        while (!done && n++ < 45) {
            val list = pageList(c)
            // (Only scrolled to when it isn't on screen: coming back to a page, the row is where it was left.)
            if (list != null && list.layoutInfo.totalItemsCount > r && list.layoutInfo.visibleItemsInfo.none { it.index == r }) {
                runCatching { list.scrollToItem(r) }; androidx.compose.runtime.withFrameNanos { }
            }
            // (The row asked for, or the first one after it that can be selected: a page may begin with a heading.)
            for (k in r..r + 3) if (runCatching { rowReq(c, k).requestFocus() }.getOrDefault(false)) { done = true; break }
            if (!done) androidx.compose.runtime.withFrameNanos { }
        }
        holding[0] = false
        if (!done && holdHas[0]) runCatching { leftOf().requestFocus() }
        jump = null
    }
    // A page opened from a row: its first row is selected. Closed: the row that opened it again.
    val subCount = remember { intArrayOf(subs.size) }
    LaunchedEffect(subs.size) {
        val n = subs.size; val was = subCount[0]; subCount[0] = n
        if (n > was) { lastRow[SUB_SLOT + n - 1] = -1; jump = (SUB_SLOT + n - 1) to 0 }
        else if (n < was) { subLists.keys.retainAll(subs.toSet()); val slot = pageSlot(); jump = slot to lastRow[slot].coerceAtLeast(0) }
    }
    // Every row of Settings, in the sections and in the pages their rows open (for the search, New and Changed).
    @Composable fun walkInto(ci: Int, chain: List<Pair<SubPage, Int>>, rows: List<SRow>, out: MutableList<Found>) {
        rows.forEachIndexed { ri, r ->
            out += Found(ci, chain, ri, r)
            val sp = (r as? Act)?.sub
            if (sp != null && sp.kind == Kind.Rows && chain.size < 3) androidx.compose.runtime.key(ci, chain.size, ri, sp.title) {
                walkInto(ci, chain + (sp to ri), sp.rows(), out)
            }
        }
    }
    @Composable fun walkAll(): List<Found> {
        val out = ArrayList<Found>()
        categories.forEachIndexed { ci, c -> if (c.kind == Kind.Rows) androidx.compose.runtime.key(ci) { walkInto(ci, emptyList(), c.rows(), out) } }
        return out
    }
    // The walk is kept until a setting (or a list rows are made from) changes: it was done again at every
    // recomposition of this screen (the selection reaching New or Changed, OK, Back: three or four walks each,
    // ~280 ms of the main thread a frame).
    val walked = remember { arrayOfNulls<Pair<Any, List<Found>>>(1) }
    @Composable fun allRows(): List<Found> {
        val key = listOf(st.changes, catalogs.size, selected.size, addons.size, accountAddons.size, allApps.size, hueRev.intValue, status)
        walked[0]?.let { if (it.first == key) return it.second }
        val all = walkAll()
        walked[0] = key to all
        return all
    }
    fun whereOf(f: Found) = (listOf(categories[f.ci].title) + f.chain.map { it.first.title }).joinToString("  ›  ") { com.glasslauncher.tv.data.I18n.tr(it) }
    /** Opens the row [f]: its section, the pages on the way (Back goes up through them), the row selected. */
    fun goTo(f: Found) {
        hold()
        searching = false; special = null
        cat = f.ci; shownCat = f.ci; subsOf[0] = f.ci
        subs.clear(); subLists.clear()
        if (f.chain.isEmpty()) { subCount[0] = 0; lastRow[f.ci] = f.ri; jump = f.ci to f.ri; return }
        lastRow[f.ci] = f.chain[0].second
        f.chain.indices.forEach { k -> lastRow[SUB_SLOT + k] = f.chain.getOrNull(k + 1)?.second ?: f.ri }
        subs.addAll(f.chain.map { it.first }); subCount[0] = subs.size
        jump = (SUB_SLOT + subs.size - 1) to f.ri
    }
    // One still picture behind Settings (the home screen's blurred backdrop, darkened) instead of the live home
    // screen under a black veil and two refracting glass panels: those cost the TV's GPU ~28 ms a frame (every
    // move in Settings dropped frames) and made opening it heavy. The panels are plain frosted cards on it.
    val env = LocalGlassEnv.current
    val still = env.heavy ?: env.bmp
    if (still != null) DisposableEffect(Unit) { SettingsCovers.value = true; onDispose { SettingsCovers.value = false } }
    // What a pop-up's glass reads while it is open over this screen: this page's own blurred picture (see SettingsGlass).
    val overEnv = remember(still, env.rootW, env.rootH, env.tint, env.refractionDp) { GlassEnv(still, still, still, env.rootW, env.rootH, env.tint, env.refractionDp, ignoreVideo = true) }
    DisposableEffect(overEnv) { SettingsGlass.env.value = overEnv; onDispose { if (SettingsGlass.env.value === overEnv) SettingsGlass.env.value = null } }
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
            // (Wide enough for the longest section name, "Continue Watching & Library".)
            Pane(Modifier.width((262 * s).dp).fillMaxHeight()) {
                // Every section shows, with no scrolling: they share the height under the search evenly and fill it to
                // the bottom (a row is the room left divided by their number, the lines between the groups counted),
                // at any interface size and in any language.
                Column(Modifier.fillMaxSize().padding(horizontal = (14 * s).dp, vertical = (12 * s).dp)) {
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
                    // New (while the version has new settings not used yet) and Changed, side by side.
                    Row(Modifier.fillMaxWidth().padding(bottom = (6 * s).dp), horizontalArrangement = Arrangement.spacedBy((6 * s).dp)) {
                        @Composable fun Chip(key: String, label: String, req: FocusRequester) {
                            var cf by remember { mutableStateOf(false) }
                            Box(Modifier.weight(1f).clip(RoundedCornerShape((12 * s).dp))
                                .background(if (cf) Color.White.copy(alpha = 0.92f) else if (special == key) Color.White.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.06f))
                                .focusRequester(req)
                                .onFocusChanged { cf = it.isFocused; if (it.isFocused) { special = key; searching = false } }
                                // OK goes into its page (Right from New reaches Changed, beside it; from Changed the page).
                                .clickable { special = key; searching = false; val sl = if (key == "new") NEW_SLOT else CHANGED_SLOT; jump = sl to lastRow[sl].coerceAtLeast(0) }
                                .padding(horizontal = (10 * s).dp, vertical = (6 * s).dp), contentAlignment = Alignment.Center) {
                                Text(label, color = if (cf) Color(0xFF111114) else Color.White, fontSize = (12 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = if (special == key) FontWeight.SemiBold else FontWeight.Normal)
                            }
                        }
                        if (newMarks.isNotEmpty()) Chip("new", "✦ " + com.glasslauncher.tv.data.I18n.tr("New") + "  ${newMarks.size}", newFocus)
                        Chip("changed", com.glasslauncher.tv.data.I18n.tr("Changed"), changedFocus)
                    }
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val line = (9 * s).dp
                        val rowH = (maxHeight - line * categories.count { it.title in CATEGORY_BREAKS }) / categories.size
                        // (The writing never outgrows its row.)
                        val font = minOf(13.5f * s, rowH.value * 0.44f).sp
                        Column {
                            categories.forEachIndexed { i, c ->
                                var f by remember { mutableStateOf(false) }
                                // A thin line between the groups of sections (accounts and titles · the look · the home and the TV).
                                if (c.title in CATEGORY_BREAKS) Box(Modifier.fillMaxWidth().height(line), contentAlignment = Alignment.Center) {
                                    Box(Modifier.padding(horizontal = (10 * s).dp).fillMaxWidth().height(0.6.dp).background(Color.White.copy(alpha = 0.14f)))
                                }
                                Box(
                                    Modifier.fillMaxWidth().height(rowH).padding(vertical = minOf(1.5f * s, rowH.value * 0.05f).dp)
                                        .clip(RoundedCornerShape((12 * s).dp))
                                        .background(if (f) Color.White.copy(alpha = 0.92f) else if (cat == i && !searching && special == null) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                        .focusRequester(catFocus[i])
                                        .then(if (i == initialCat) Modifier.focusRequester(firstCat) else Modifier)
                                        .onFocusChanged { f = it.isFocused; if (it.isFocused) { cat = i; searching = false; special = null } }
                                        .clickable { cat = i; searching = false; special = null }
                                        .padding(horizontal = (12 * s).dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Text(com.glasslauncher.tv.data.I18n.tr(c.title), color = if (f) Color(0xFF111114) else Color.White, fontSize = font,
                                        fontWeight = if (cat == i && !searching && special == null) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
            Pane(
                Modifier.weight(1f).fillMaxHeight().onFocusChanged { rightFocusedA[0] = it.hasFocus }
                    // Left goes back to this page's own category; Up/Down at the ends stay in the page.
                    // (From a page a row opened, Left goes up one page, to that row, like Back: it went to the
                    // sections with the page still up, and the next Down changed section from inside a sub-page.)
                    .focusProperties {
                        exit = { dir ->
                            when (dir) {
                                androidx.compose.ui.focus.FocusDirection.Left ->
                                    if (subs.isNotEmpty() && !searching && special == null) { scope.launch { closeSub() }; FocusRequester.Cancel } else leftOf()
                                androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down -> FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
            ) {
                // (Where the selection waits while this pane's page is swapped, see [hold].)
                Box(Modifier.size(1.dp).onFocusChanged { holdHas[0] = it.isFocused }.focusRequester(holdFocus)
                    .focusProperties { canFocus = holding[0] }.focusable())
                if (searching) {
                    // Every row, worked out only while searching: the sections' and the ones in the pages their rows open.
                    val q = query.trim().lowercase()
                    val all = if (q.isEmpty()) emptyList() else allRows()
                    val found = all.mapNotNull { f ->
                        val (t, d) = when (val r = f.row) { is Toggle -> r.title to r.desc; is Adjust -> r.title to r.desc; is Act -> r.title to r.desc; is AppItem -> r.entry.label to r.entry.pkg; else -> return@mapNotNull null }
                        if (t.trim().startsWith("→")) return@mapNotNull null // (a link: its row itself is found)
                        val tt = com.glasslauncher.tv.data.I18n.tr(t)
                        val inTitle = t.lowercase().contains(q) || tt.lowercase().contains(q)
                        if (inTitle || d.lowercase().contains(q)) Pair(inTitle, f) else null
                    }.sortedByDescending { it.first }.map { it.second }.distinctBy { Triple(it.ci, it.chain.map { c -> c.first.title }, it.ri) }.take(60)
                    // The New and Changed pages, found by their names too.
                    val pages = listOfNotNull(
                        ("changed" to "Changed").takeIf { q.isNotEmpty() && listOf("changed", "default", com.glasslauncher.tv.data.I18n.tr("Changed").lowercase()).any { w -> w.contains(q) || q.contains(w) } },
                        ("new" to "New").takeIf { q.isNotEmpty() && newMarks.isNotEmpty() && listOf("new", com.glasslauncher.tv.data.I18n.tr("New").lowercase()).any { w -> w.contains(q) } },
                    )
                    SearchPane(s, query, { query = it }, pages.map { (_, n) -> com.glasslauncher.tv.data.I18n.tr(n) to com.glasslauncher.tv.data.I18n.tr("Settings") } +
                        found.map { norm(com.glasslauncher.tv.data.I18n.tr(rowTitle(it.row))) to whereOf(it) }) { k ->
                        if (k < pages.size) {
                            val key = pages[k].first
                            runCatching { (if (key == "new") newFocus else changedFocus).requestFocus() }
                            searching = false; special = key
                        } else goTo(found[k - pages.size])
                    }
                } else if (special != null) {
                    // New / Changed: rows found all over Settings, each leading to its place.
                    val all = allRows()
                    val page = special!!
                    // (Worked out once for a walk: every Toggle and Adjust is evaluated twice for Changed.)
                    val rows: List<SRow> = remember(all, page, newMarks, st.lang) { if (page == "new") buildList {
                        val seen = HashSet<String>()
                        val hits = all.filter { f -> f.row !is Note && !rowTitle(f.row).trim().startsWith("→") && norm(rowTitle(f.row)) in newMarks && seen.add(norm(rowTitle(f.row))) }
                        add(Note("New in Glassio $newVer: used once, a setting is no longer marked"))
                        hits.groupBy { it.ci }.forEach { (ci, l) ->
                            add(Note(categories[ci].title))
                            l.forEach { f -> add(Act("→ " + norm(rowTitle(f.row)), if (f.chain.isEmpty()) "" else whereOf(f), "") { goTo(f) }) }
                        }
                        add(Act("Mark them all as seen", "", "") { st.settingsNewSeen = (st.settingsNewSeen + newMarks).distinct(); runCatching { catFocus[cat].requestFocus() }; special = null })
                    } else buildList {
                        // A setting differs from its default when what its row shows differs; the accounts, lists of your
                        // own (catalogs, lights, inputs …) and rows that read no setting of Glassio's are left out.
                        fun shown(r: SRow) = when (r) { is Toggle -> if (r.get()) "On" else "Off"; is Adjust -> r.value(); else -> "" }
                        val done = HashSet<Set<String>>()
                        val diffs = all.mapNotNull { f ->
                            if (f.ci == 0 || (f.row !is Toggle && f.row !is Adjust)) return@mapNotNull null
                            val (cur, reads, lists) = runCatching { st.probe(false) { shown(f.row) } }.getOrNull() ?: return@mapNotNull null
                            if (lists.isNotEmpty()) return@mapNotNull null
                            val keys = st.setKeys(reads - NOT_CHANGES)
                            if (keys.isEmpty()) return@mapNotNull null
                            val def = runCatching { st.probe(true) { shown(f.row) }.first }.getOrNull() ?: return@mapNotNull null
                            if (def == cur || !done.add(keys.toSet())) null else Triple(f, cur to def, keys)
                        }
                        if (diffs.isEmpty()) add(Note("Every setting is as it comes"))
                        else add(Note("${diffs.size} settings differ from how Glassio comes. OK on one: back to its default, or go to it"))
                        diffs.groupBy { it.first.ci }.forEach { (ci, l) ->
                            add(Note(categories[ci].title))
                            l.forEach { (f, v, keys) ->
                                val name = norm(com.glasslauncher.tv.data.I18n.tr(rowTitle(f.row)))
                                add(Act(name, (if (f.chain.isEmpty()) "" else whereOf(f) + "  ·  ") + com.glasslauncher.tv.data.I18n.tr("Default") + ": " + com.glasslauncher.tv.data.I18n.tr(v.second),
                                    com.glasslauncher.tv.data.I18n.tr(v.first)) {
                                    dialog = MenuDialog("$name: " + com.glasslauncher.tv.data.I18n.tr("back to") + " " + com.glasslauncher.tv.data.I18n.tr(v.second) + "?", listOf(
                                        com.glasslauncher.tv.data.I18n.tr("Back to default") to {
                                            // Through the row's own control, as pressing it would (its other effects too): a
                                            // row may read more than its own setting, so forgetting what it reads could reset others.
                                            when (val r = f.row) {
                                                is Toggle -> r.set(v.second == "On")
                                                is Adjust -> {
                                                    var n = 0
                                                    while (r.value() != v.second && n++ < 40) r.step(1)
                                                    n = 0
                                                    while (r.value() != v.second && n++ < 80) r.step(-1)
                                                    if (r.value() != v.second) toast(ctx, "Couldn't put it back from here: change it on its page")
                                                }
                                                else -> {}
                                            }
                                            Unit
                                        },
                                        com.glasslauncher.tv.data.I18n.tr("Go to it") to { goTo(f) },
                                        com.glasslauncher.tv.data.I18n.tr("Cancel") to { },
                                    ))
                                })
                            }
                        }
                    } }
                    val slot = if (page == "new") NEW_SLOT else CHANGED_SLOT
                    androidx.compose.runtime.key(page) {
                        SettingsPage(s, Category(if (page == "new") "New settings" else "Changed settings", rows = { rows }), slot, app,
                            listStates.getOrPut(slot) { androidx.compose.foundation.lazy.LazyListState() }, rowFocus, lastRow, onAppMenu)
                    }
                } else {
                val top = subs.lastOrNull()
                androidx.compose.runtime.CompositionLocalProvider(LocalNew provides marks) {
                if (top != null) androidx.compose.runtime.key(top) {
                    // (The section's name, then each page's on the way: where Back leads.)
                    val path = (listOf(categories[shownCat].title) + subs.map { it.title }).joinToString("  ›  ") { com.glasslauncher.tv.data.I18n.tr(it) }
                    SettingsPage(s, Category(top.title, top.kind, top.rows), SUB_SLOT + subs.size - 1, app, subLists.getOrPut(top) { androidx.compose.foundation.lazy.LazyListState() },
                        rowFocus, lastRow, onAppMenu, title = path)
                } else {
                    SettingsPage(s, categories[shownCat], shownCat, app, listStates.getOrPut(shownCat) { androidx.compose.foundation.lazy.LazyListState() },
                        rowFocus, lastRow, onAppMenu, seek = seek?.takeIf { it.first == shownCat }?.second,
                        // (Not found, a row its section hides just now: the section's first row.)
                        onSeek = { i -> seek = null; lastRow[shownCat] = i.coerceAtLeast(0); jump = shownCat to i.coerceAtLeast(0) })
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
    opts += "Icon from my phone (Manage from phone)" to { toast(ctx, "Open Settings → System → Manage from phone, scan the QR code, then pick ${e.label}") }
    if (app.icons.has(e.pkg)) opts += "Reset to the original icon" to { app.icons.reset(e.pkg) }
    opts += "Open app" to { com.glasslauncher.tv.ui.AppLock.open(app, ctx, e) }
    // A locked app's menu asks for the code first (its icon and place could be changed without it).
    if (com.glasslauncher.tv.ui.AppLock.locked(app, e.pkg)) com.glasslauncher.tv.ui.AppLock.ask.value = com.glasslauncher.tv.ui.AppLock.Ask(e.label) { show(MenuDialog(e.label, opts)) }
    else show(MenuDialog(e.label, opts))
}

/** What Settings looked up once while Glassio runs: the icon packs, and whether each TMDB token works. */
private object SettingsCache {
    val packs = mutableStateOf<List<com.glasslauncher.tv.data.IconPackInfo>?>(null)
    val tmdb = HashMap<String, Boolean?>()
}

/** Settings covers the whole screen with its own still picture: the home screen's backdrop isn't drawn under it. */
val SettingsCovers = mutableStateOf(false)
/** Words Settings opens searching for (a QA hook). */
val SettingsAskSearch = mutableStateOf<String?>(null)

/** Settings → Menu & themes → Switch colour. */
val SETTINGS_ACCENTS = linkedMapOf("burgundy" to ("Burgundy" to 0xFFA3243F.toInt()), "green" to ("Green" to 0xFF34C759.toInt()), "purple" to ("Purple" to 0xFF7B61FF.toInt()))
/** A row being changed (◀ ▶) or moved: the Switch colour, see-through. */
@Composable
fun editAccent(): Color = accentOf((androidx.compose.ui.platform.LocalContext.current.applicationContext as com.glasslauncher.tv.GlassApp).settings.settingsAccent).copy(alpha = 0.55f)
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
private fun RowView(row: SRow, s: Float, app: GlassApp, onApp: (AppEntry) -> Unit, pos: Int = 0, first: Boolean = true, afterNote: Boolean = false) {
    when (row) {
        // A short note is a group's heading; a longer one a small explanation under the heading.
        is Note -> {
            val head = row.text.length <= 44 && !row.text.endsWith(".")
            Text(com.glasslauncher.tv.data.I18n.tr(row.text), color = Color.White.copy(alpha = if (head) 0.62f else 0.45f), fontSize = ((if (head) 12f else 11f) * s).sp,
                fontWeight = if (head) FontWeight.SemiBold else null,
                // A clear gap between groups: a note that starts a new group (the one before it was a card, not its heading)
                // gets the same room as a heading; one right under its heading stays close to it.
                modifier = Modifier.padding(start = (14 * s).dp, end = (14 * s).dp, top = ((if (first) 0f else if (head || !afterNote) 30f else 6f) * s).dp, bottom = (8 * s).dp))
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
                if (editing) Text("‹  ${com.glasslauncher.tv.data.I18n.tr(row.value())}  ›", color = Color.White, fontSize = (13.5f * s).sp, fontWeight = FontWeight.SemiBold)
                else Text(com.glasslauncher.tv.data.I18n.tr(row.value()) + "  ›", color = ink.copy(alpha = 0.55f), fontSize = (13 * s).sp)
            }
        }
        is Act -> Frame(s, pos = pos, onClick = row.onClick) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            val ink = LocalInk.current
            if (row.value.isNotEmpty()) Text(if (row.ok == true) "✓  " + com.glasslauncher.tv.data.I18n.tr(row.value) else row.value,
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
        val isNew = !title.trim().startsWith("→") && norm(title) in LocalNew.current.titles
        if (!isNew) Text(com.glasslauncher.tv.data.I18n.tr(title), color = ink, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        else Row(verticalAlignment = Alignment.CenterVertically) {
            Text(com.glasslauncher.tv.data.I18n.tr(title), color = ink, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width((7 * s).dp))
            // A small mark: a setting this version added, not used yet.
            Text(com.glasslauncher.tv.data.I18n.tr("New"), color = Color.White, fontSize = (9.5f * s).sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF0A84FF)).padding(horizontal = (6 * s).dp, vertical = (1 * s).dp))
        }
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
                    editing -> editAccent()
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

private fun rowTitle(r: SRow): String = when (r) { is Toggle -> r.title; is Adjust -> r.title; is Act -> r.title; is Note -> r.text; is AppItem -> r.entry.label }

/** One page of Settings (its own part of the screen: moving in the list on the left doesn't build it again). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SettingsPage(s: Float, c: Category, sc: Int, app: GlassApp, listState: androidx.compose.foundation.lazy.LazyListState,
                         rowFocus: HashMap<Long, FocusRequester>, lastRow: IntArray, onAppMenu: (AppEntry) -> Unit, title: String? = null,
                         seek: String? = null, onSeek: (Int) -> Unit = {}) {
    Column(Modifier.padding((18 * s).dp)) {
        Text(title ?: com.glasslauncher.tv.data.I18n.tr(c.title), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = (6 * s).dp, bottom = (10 * s).dp))
        if (c.kind == Kind.Phone) PhonePane(app, s)
        else if (c.kind == Kind.About) AboutPane(app, s, onApp = onAppMenu)
        else {
            val rows = c.rows()
            // A row asked for by its title (a link from another section): where it is on this page.
            if (seek != null) LaunchedEffect(seek) { onSeek(rows.indexOfFirst { r -> rowTitle(r) == seek }) }
            // (For the QA count of each page's rows.)
            LaunchedEffect(c.title, rows.size) { android.util.Log.d("GlassSettings", "${c.title}|${rows.count { it !is Note }}") }
            // Rows between two headings sit together in one rounded group, with thin lines between them.
            val pos = groupPositions(rows)
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides SettingsPivot) {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    items(rows.size, contentType = { rows[it]::class }) { i ->
                        val nm = LocalNew.current
                        Box(Modifier.focusRequester(rowFocus.requester(sc * 10_000L + i)).onFocusChanged { if (it.hasFocus) lastRow[sc] = i }
                            .onPreviewKeyEvent { ev ->
                                if (ev.type == KeyEventType.KeyDown && (ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight)) {
                                    val t0 = rowTitle(rows[i]); if (!t0.trim().startsWith("→") && norm(t0) in nm.titles) nm.used(norm(t0))
                                }
                                false
                            }) {
                            RowView(rows[i], s, app, onApp = onAppMenu, pos = pos[i], first = i == 0, afterNote = rows.getOrNull(i - 1) is Note)
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
    var dlg by remember { mutableStateOf<DialogState?>(null) }
    DialogHost(dlg) { dlg = null }
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
            if (com.glasslauncher.tv.data.Store.PLAY) add(Note("Updates come from Google Play."))
            else {
            add(Adjust("Updates", if (app.settings.updateChannel == "beta") "Beta: new things first, they may still have bugs" else "Stable: only finished releases",
                { if (app.settings.updateChannel == "beta") "Beta" else "Stable" },
                { app.settings.updateChannel = if (app.settings.updateChannel == "beta") "stable" else "beta"
                  scope.launch(Dispatchers.IO) { toast(ctx, app.updater.check()) } }))
            if (rel != null) {
                add(Act((if (rel!!.beta) "Install beta " else "Install update ") + rel!!.version, if (status.isNotBlank()) status else "Downloads from GitHub and restarts Glass", "Install") { app.updater.install() })
                rel!!.notes.lineSequence().map { it.trim().removePrefix("-").removePrefix("*").trim() }.filter { it.isNotBlank() && !it.startsWith("#") }
                    .take(6).forEach { add(Note("• $it")) }
            } else add(Act("Check for updates", if (status.isNotBlank()) status else "Looks at the latest release on GitHub", "Check") {
                scope.launch(Dispatchers.IO) { toast(ctx, app.updater.check()) }
            })
            add(Note("A pill appears next to the clock when a new version is out."))
            }
            // A problem, with Glassio's last log lines, straight to GitHub Issues (see Report): described here, shown
            // in full before it goes.
            add(Act("Report a problem", "Goes to GitHub with Glassio's log. You see it all before it is sent",
                "Write") {
                dlg = TextEntry("What went wrong?", "", "What happened, and what you did just before") { said ->
                    scope.launch {
                        // (The text box closes itself after this: the next page opens once it has.)
                        kotlinx.coroutines.delay(120)
                        if (said.trim().length < 10) { toast(ctx, "Write a few more words, so the problem can be found"); return@launch }
                        val R = com.glasslauncher.tv.data.Report
                        val data = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            R.build(ctx, app.settings, app.updater.current, said, mapOf("Nuvio" to app.library.nuvio.connected, "Trakt" to app.cw.trakt.connected,
                                "Simkl" to app.cw.simkl.connected, "Stremio" to app.cw.stremio.connected))
                        }
                        dlg = ReportDialog(data) { d -> R.send(ctx, d) }
                    }
                }
            })
            // Support: a PSN gift card code, typed here and sent straight to the developer (see Support).
            if (!com.glasslauncher.tv.data.Store.PLAY) add(Note("Support me"))
            if (!com.glasslauncher.tv.data.Store.PLAY) add(Act("PlayStation gift card (PSN, USA)", if (com.glasslauncher.tv.data.Support.ready) "Type the code and press Send: it comes straight to me. Thank you!"
                else "Not available in this build", "Send") {
                if (!com.glasslauncher.tv.data.Support.ready) { toast(ctx, "Not available in this build"); return@Act }
                dlg = SupportDialog({ com.glasslauncher.tv.data.Support.clean(it) }) { c, n -> com.glasslauncher.tv.data.Support.send(c, n, app.updater.current) }
            })
            if (com.glasslauncher.tv.data.Store.PLAY) add(Note("Privacy: github.com/" + com.glasslauncher.tv.data.Updater.REPO + "/blob/main/docs/PRIVACY.md"))
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
private val CATEGORY_BREAKS = setOf("Carousel", "Smart home")

val CATEGORY_ORDER = listOf("Accounts & profiles", "Catalogs & add-ons", "Continue Watching & Library", "Carousel", "Dock & icons", "Look & menu",
    "Background & screensaver", "Music & app rows", "Smart home", "System", "About")

/** Asked for instead of a section: Settings opens on System's Manage from phone page. */
const val PHONE_CATEGORY = 1000

/** The sections' order as of this number (see [sectionAfterReorder]). */
const val SETTINGS_LAYOUT = 2
private val OLD_SECTIONS = listOf("Connections" to "Accounts & profiles", "Catalogs" to "Catalogs & add-ons", "Continue Watching" to "Continue Watching & Library",
    "Library" to "Continue Watching & Library", "Carousel" to "Carousel", "Dock & icons" to "Dock & icons", "Channels" to "Music & app rows",
    "Menu & themes" to "Look & menu", "Cameras" to "Smart home", "Background & glass" to "Background & screensaver", "Screensaver" to "Background & screensaver",
    "Music" to "Music & app rows", "Hue lights" to "Smart home", "Remote mapping" to "System", "Performance" to "System", "Manage from phone" to "System",
    "Sync" to "System", "System" to "System", "About" to "About")
/** The section that holds what section [old] of the 19 earlier ones held (the one Settings was left on, kept across the update). */
fun sectionAfterReorder(old: Int): Int = OLD_SECTIONS.getOrNull(old)?.let { CATEGORY_ORDER.indexOf(it.second) }?.coerceAtLeast(0) ?: 0

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

/**
 * Settings → Music and Settings → Channels: for each music app, its player (selected in the dock: its song, or an
 * idle player) or its rows like other apps.
 */
private fun musicRowsChoice(ctx: android.content.Context, st: com.glasslauncher.tv.data.Settings, changed: () -> Unit): List<SRow> = buildList {
    val apps = installedMusicApps(ctx).filter { MusicUi.isMusicApp(ctx, it.first) }
    if (apps.isEmpty()) return@buildList
    add(Note("Music apps selected in the dock: their player, or their rows (Channels) like other apps"))
    apps.forEach { (pkg, name) ->
        add(Toggle("$name: rows", "On: its rows. Off: its player (also with nothing playing)", { pkg in st.channelsMusicRowApps }, { on ->
            st.channelsMusicRowApps = if (on) (st.channelsMusicRowApps - pkg) + pkg else st.channelsMusicRowApps - pkg; changed()
        }))
    }
}

/** Settings → Channels: a music app selected with nothing playing shows its idle player (its icon and Play), or not. */
private fun musicIdleChoice(ctx: android.content.Context, st: com.glasslauncher.tv.data.Settings, changed: () -> Unit): List<SRow> = buildList {
    val apps = installedMusicApps(ctx).filter { MusicUi.isMusicApp(ctx, it.first) || it.first in com.glasslauncher.tv.data.NowPlaying.YOUTUBE_CLIENTS }
    if (apps.isEmpty()) return@buildList
    add(Toggle("Nothing playing: the app's player", "A music app selected in the dock with nothing playing: its icon and Play. Off: its rows, or the carousel (its player still comes while its song is on)",
        { st.musicIdleOn }, { st.musicIdleOn = it; changed() }))
    if (st.musicIdleOn) apps.forEach { (pkg, name) ->
        add(Toggle("  · $name", "Its player with nothing playing", { pkg !in st.musicIdleOffApps }, { on ->
            st.musicIdleOffApps = if (on) st.musicIdleOffApps - pkg else st.musicIdleOffApps + pkg; changed()
        }))
    }
}

/** Settings → Menu & themes → Settings button: the glyphs and frames (drawn on the home screen, see GearButton). */
private val SETTINGS_ICONS = linkedMapOf("sliders" to "Sliders", "gear" to "Gear", "dots" to "Three dots", "menu" to "Three lines", "person" to "Person",
    "hello" to "Hello", "helloScript" to "hello (handwriting)", "menuText" to "Menu", "picture" to "Your picture")
private val SETTINGS_FRAMES = linkedMapOf("square" to "Square", "circle" to "Circle", "none" to "None")
