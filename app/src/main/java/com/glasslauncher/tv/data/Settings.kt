package com.glasslauncher.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * Every launcher option lives here. Reads touch a Compose state counter, so any composable that
 * reads a property recomposes automatically when a setter bumps it.
 */
val MENU_KEYS = listOf("library", "sliders", "gear", "wifi", "theme", "camera", "saver", "background")
/** The menu's order on a new TV. */
val MENU_DEFAULT = listOf("library", "camera", "sliders", "gear", "wifi", "saver", "theme", "background")
val MENU_NAMES = mapOf("library" to "Library", "sliders" to "Glassio settings", "gear" to "Android settings", "wifi" to "Wi-Fi",
    "theme" to "Themes", "camera" to "Camera", "saver" to "Screensaver", "background" to "Background")

class Settings(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
    private val rev = mutableIntStateOf(0)

    private fun touch() { rev.intValue = rev.intValue + 1 }
    /** Test only: every stored value (am start --ez dump_prefs true → logcat GlassPrefs). */
    fun dumpAll(): Map<String, *> = sp.all

    private fun b(k: String, d: Boolean): Boolean { rev.intValue; return sp.getBoolean(k, d) }
    private fun i(k: String, d: Int): Int { rev.intValue; return sp.getInt(k, d) }
    private fun f(k: String, d: Float): Float { rev.intValue; return sp.getFloat(k, d) }
    private fun s(k: String, d: String): String { rev.intValue; return sp.getString(k, d) ?: d }
    private fun putB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply(); touch() }
    private fun putI(k: String, v: Int) { sp.edit().putInt(k, v).apply(); touch() }
    private fun putF(k: String, v: Float) { sp.edit().putFloat(k, v).apply(); touch() }
    private fun putS(k: String, v: String) { sp.edit().putString(k, v).apply(); touch() }
    private fun list(k: String): List<String> =
        s(k, "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    private fun putList(k: String, v: List<String>) = putS(k, v.joinToString("\n"))

    // ---- Hero / carousel
    var heroAutoplay: Boolean get() = b("heroAutoplay", true); set(v) = putB("heroAutoplay", v)
    /** auto | performance | balanced | quality | custom */
    var perfMode: String get() = s("perfMode", "auto"); set(v) = putS("perfMode", v)

    /**
     * What this TV can carry, from its memory and GPU: 2 GB / GLES 2 boxes get "performance",
     * ~4 GB boxes (Google TV Streamer, Shield) "balanced", bigger ones "quality".
     */
    fun deviceTier(): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        val gles = am.deviceConfigurationInfo.reqGlEsVersion
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            am.isLowRamDevice || gb < 2.6 || gles < 0x30000 || android.os.Build.VERSION.SDK_INT < 33 -> "performance"
            gb < 5.0 || cores < 6 -> "balanced"
            else -> "quality"
        }
    }
    /** The tier Auto picked, for display. */
    val autoTier: String by lazy { deviceTier() }

    /** The page picture is prepared at this fraction of the screen size (2/3 in Performance: 2.25× less memory and drawing). */
    var pageScale: Float get() = f("pageScale", 1f); set(v) = putF("pageScale", if (v < 0.9f) 0.667f else 1f)
    /** 0 small, 1 medium, 2 large memory caches (pictures kept ready); set by the mode. */
    var memTier: Int get() = i("memTier", 2); set(v) = putI("memTier", v.coerceIn(0, 2))

    /** Glass copies of the backdrop: 480 px (light, default) or 720 px (crisper glass, more memory). */
    var glassRes: Int get() = i("glassRes", 720); set(v) = putI("glassRes", if (v >= 720) 720 else 480)

    /** Which picture a title uses: main | second | best (highest resolution) | addon (the add-on's own). */
    var artChoice: String get() = s("artChoice", "main"); set(v) = putS("artChoice", v)
    /** Per-title picture picked with a long press on the carousel: "key\turl" lines. */
    fun artPick(key: String): String? = list("artPicks").firstOrNull { it.startsWith("$key\t") }?.substringAfter('\t')
    fun setArtPick(key: String, url: String?) {
        val rest = list("artPicks").filterNot { it.startsWith("$key\t") }
        putList("artPicks", if (url == null) rest else (rest + "$key\t$url").takeLast(300))
    }
    fun clearArtPicks() = putList("artPicks", emptyList())
    /** max = original pictures (4K source, sharpest) | high = 1280 px pictures (faster, lighter) */
    var imageQuality: String get() = s("imageQuality", "max"); set(v) = putS("imageQuality", v)
    /** How many slides ahead are prepared (pictures, logos, glass). */
    var prefetchDepth: Int get() = i("prefetchDepth", 8); set(v) = putI("prefetchDepth", v.coerceIn(1, 8))

    fun applyMode(m: String) {
        // Auto: a weak TV gets the light preset; any other keeps the defaults (made on a 4 GB Google TV Streamer).
        val tier = if (m == "auto") deviceTier().takeIf { it == "performance" } else m
        when (tier) {
            // Performance: pictures at 1280 px and drawn at 2/3 resolution, no rim refraction (no GPU effects at
            // all), a plain crossfade and small caches: for 2 GB TVs and older graphics chips.
            "performance" -> { imageQuality = "high"; prefetchDepth = 2; transitionMs = 300; refraction = 0; heroGallery = false; glassRes = 480
                pageScale = 0.667f; slideAnim = "fade"; memTier = 0; saverQuality = "1080-H264"; cacheMb = 1024 }
            "balanced" -> { imageQuality = "high"; prefetchDepth = 3; transitionMs = 450; refraction = 6; heroGallery = true; galleryShots = 2; glassRes = 480
                pageScale = 1f; memTier = 1 }
            "quality" -> { imageQuality = "max"; prefetchDepth = 6; transitionMs = 700; refraction = 14; heroGallery = true; galleryShots = 3; glassRes = 720
                pageScale = 1f; memTier = 2 }
        }
        perfMode = m
    }

    /** Apple TV style: each title shows a few of its backdrops before the carousel moves on. */
    var heroGallery: Boolean get() = b("heroGallery", true); set(v) = putB("heroGallery", v)
    var galleryShots: Int get() = i("galleryShots", 2); set(v) = putI("galleryShots", v.coerceIn(2, 6))
    var heroIntervalSec: Int get() = i("heroIntervalSec", 10); set(v) = putI("heroIntervalSec", v.coerceIn(3, 60))
    /** logo | text | hidden */
    var titleMode: String get() = s("titleMode", "logo"); set(v) = putS("titleMode", v)
    var titleScale: Float get() = f("titleScale", 0.6f); set(v) = putF("titleScale", v.coerceIn(0.5f, 2.2f))
    var showMeta: Boolean get() = b("showMeta", true); set(v) = putB("showMeta", v)
    var showGenres: Boolean get() = b("showGenres", true); set(v) = putB("showGenres", v)
    var showSynopsis: Boolean get() = b("showSynopsis", true); set(v) = putB("showSynopsis", v)
    /** Synopsis appears only while the carousel is selected. */
    var synopsisOnFocus: Boolean get() = b("synopsisOnFocus", true); set(v) = putB("synopsisOnFocus", v)
    /** Back on the home screen starts the screensaver (like Apple TV). */
    var backSaver: Boolean get() = b("backSaver", true); set(v) = putB("backSaver", v)
    var synopsisScale: Float get() = f("synopsisScale", 1.0f); set(v) = putF("synopsisScale", v.coerceIn(0.6f, 2.0f))
    var synopsisLines: Int get() = i("synopsisLines", 5); set(v) = putI("synopsisLines", v.coerceIn(1, 8))
    /** window (dots on a pill) | liquid (liquid-glass drops, no pill) | progress | hidden */
    var dotsMode: String get() = s("dotsMode", "window"); set(v) = putS("dotsMode", v)
    /** apple (Apple TV: the new picture opens up from the side with parallax) | slide (push) | fade */
    var slideAnim: String get() = s("slideAnim", "apple"); set(v) = putS("slideAnim", v)
    // ---- Your own background
    /** carousel (each title's backdrop) | image | video */
    var bgMode: String get() = s("bgMode", "carousel"); set(v) = putS("bgMode", v)
    /** A link or a file path. */
    var bgImage: String get() = s("bgImage", ""); set(v) = putS("bgImage", v)
    var bgVideo: String get() = s("bgVideo", ""); set(v) = putS("bgVideo", v)

    // ---- Menu (from the settings button) and Library
    /** auto (opens when you go up to the settings button) | manual (OK on it) | off (it opens Settings) */
    var menuMode: String get() = s("menuMode", "auto"); set(v) = putS("menuMode", v)
    var menuClock: Boolean get() = b("menuClock", true); set(v) = putB("menuClock", v)
    var menuDate: Boolean get() = b("menuDate", true); set(v) = putB("menuDate", v)
    var menuWeather: Boolean get() = b("menuWeather", true); set(v) = putB("menuWeather", v)
    var menuCity: Boolean get() = b("menuCity", true); set(v) = putB("menuCity", v)
    var menuLibrary: Boolean get() = b("menuLibrary", false); set(v) = putB("menuLibrary", v)
    /** The menu's rows in your order (keys: library, sliders, gear, wifi, theme, camera, saver, background). */
    var menuOrder: List<String>
        get() = (if (sp.contains("menuOrder")) list("menuOrder") else MENU_DEFAULT).filter { it in MENU_KEYS }.let { it + MENU_KEYS.filter { k -> k !in it } }
        set(v) = putList("menuOrder", v)
    var menuAndroid: Boolean get() = b("menuAndroid", true); set(v) = putB("menuAndroid", v)
    var menuWifi: Boolean get() = b("menuWifi", false); set(v) = putB("menuWifi", v)
    var menuThemes: Boolean get() = b("menuThemes", true); set(v) = putB("menuThemes", v)
    var menuBackground: Boolean get() = b("menuBackground", true); set(v) = putB("menuBackground", v)
    /** Size of the menu (0.8 – 1.3). */
    var menuScale: Float get() = f("menuScale", 0.95f); set(v) = putF("menuScale", v.coerceIn(0.8f, 1.3f))
    /** The TV's numbers under the weather in the menu. */
    var menuSys: Boolean get() = b("menuSys", true); set(v) = putB("menuSys", v)
    var sysIp: Boolean get() = b("sysIp", false); set(v) = putB("sysIp", v)
    var sysCpu: Boolean get() = b("sysCpu", false); set(v) = putB("sysCpu", v)
    var sysGpu: Boolean get() = b("sysGpu", false); set(v) = putB("sysGpu", v)
    var sysRam: Boolean get() = b("sysRam", true); set(v) = putB("sysRam", v)
    var sysStorage: Boolean get() = b("sysStorage", true); set(v) = putB("sysStorage", v)
    /** Cameras, one per line: "Name|rtsp://…" (RTSP, HLS or a plain video link). */
    var cameras: List<String> get() = list("cameras"); set(v) = putList("cameras", v)
    var menuCamera: Boolean get() = b("menuCamera", true); set(v) = putB("menuCamera", v)
    var menuSaver: Boolean get() = b("menuSaver", true); set(v) = putB("menuSaver", v)
    /** Look of the menu: same choices as the dock (set by Themes). */
    var menuStyle: String get() = s("menuStyle", "water_still"); set(v) = putS("menuStyle", v)
    /** The + button next to Play (save to Library). */
    var heroPlus: Boolean get() = b("heroPlus", true); set(v) = putB("heroPlus", v)
    var libraryTrakt: Boolean get() = b("libraryTrakt", true); set(v) = putB("libraryTrakt", v)
    /** The Nuvio account's Library (Nuvio Legacy, official Nuvio and forks that sync with the account). */
    var libraryNuvio: Boolean get() = b("libraryNuvio", true); set(v) = putB("libraryNuvio", v)
    /** Catalogs also come from the add-ons installed on the Nuvio account (merged with the TV's own, never twice). */
    var catalogsNuvio: Boolean get() = b("catalogsNuvio", false); set(v) = putB("catalogsNuvio", v)
    /** The + button also saves to the Nuvio account's Library. */
    var libraryToNuvio: Boolean get() = b("libraryToNuvio", true); set(v) = putB("libraryToNuvio", v)
    var libraryToTrakt: Boolean get() = b("libraryToTrakt", true); set(v) = putB("libraryToTrakt", v)
    var libraryTitles: Boolean get() = b("libraryTitles", false); set(v) = putB("libraryTitles", v)
    /** bow (black on white) | amoled | liquid | clear | water_still | water */
    var theme: String get() = s("theme", "water_still"); set(v) = putS("theme", v)

    /** One choice for the dock, the icons and the menu (Settings keep their own look). Black ones keep the dock clear (matte). */
    fun applyTheme(t: String) {
        when (t) {
            "bow" -> { dockStyle = "clear"; tileStyle = "bow"; menuStyle = "bow" }
            "amoled" -> { dockStyle = "clear"; tileStyle = "amoled"; menuStyle = "amoled" }
            else -> { dockStyle = t; tileStyle = t; menuStyle = t }
        }
        theme = t
    }

    /** Buttons under the title: apple (Play + round Next, like Apple TV) | play (Play only, when selected) | hidden */
    var heroButtons: String get() = s("heroButtons", "play"); set(v) = putS("heroButtons", v)
    var dotsMax: Int get() = i("dotsMax", 10); set(v) = putI("dotsMax", v.coerceIn(3, 20))
    var perCatalog: Int get() = i("perCatalog", 50); set(v) = putI("perCatalog", v.coerceIn(5, 500))
    /** 0 = unlimited */
    var maxItems: Int get() = i("maxItems", 70); set(v) = putI("maxItems", v.coerceAtLeast(0))
    var shuffle: Boolean get() = b("shuffle", true); set(v) = putB("shuffle", v)
    /** Surprise me: a random mix from all the catalogs you chose (different pages each time), fetched again every [surpriseMin]. */
    var surprise: Boolean get() = b("surprise", true); set(v) = putB("surprise", v)
    var surpriseMin: Int get() = i("surpriseMin", 60); set(v) = putI("surpriseMin", v.coerceIn(10, 1440))
    /** Apple style: the title's logo all in white. */
    var logoWhite: Boolean get() = b("logoWhite", true); set(v) = putB("logoWhite", v)
    /** Picture cache on the TV, in MB (applies after Glassio restarts). */
    var cacheMb: Int get() = i("cacheMb", 3072); set(v) = putI("cacheMb", v.coerceIn(256, 8192))
    /** Language of synopses and titles from TMDB: en | ar | … */
    var descLang: String get() = s("descLang", "en"); set(v) = putS("descLang", v)
    var tmdbToken: String get() = s("tmdbToken", ""); set(v) = putS("tmdbToken", v.trim())

    var transitionMs: Int get() = i("transitionMs", 700); set(v) = putI("transitionMs", v.coerceIn(100, 2000))
    var dotsAutoHide: Boolean get() = b("dotsAutoHide", true); set(v) = putB("dotsAutoHide", v)

    // ---- Clock / dock look
    var topAutoHide: Boolean get() = b("topAutoHide", true); set(v) = putB("topAutoHide", v)
    var topHideSec: Int get() = i("topHideSec", 2); set(v) = putI("topHideSec", v.coerceIn(2, 30))
    var cornerRadius: Int get() = i("cornerRadius", 18); set(v) = putI("cornerRadius", v.coerceIn(0, 40))
    /** Index of the Apps folder among the dock apps; -1 = after the last app. */
    var folderPos: Int get() = i("folderPos", -1); set(v) = putI("folderPos", v)
    /** Continue Watching: off | first (at the start of the carousel) | mixed (among the catalogs) */
    var continueMode: String get() = s("continueMode", "first"); set(v) = putS("continueMode", v)
    /** Continue Watching also from the Trakt account (when one is connected). */
    var cwTrakt: Boolean get() = b("cwTrakt", true); set(v) = putB("cwTrakt", v)
    var cwStremio: Boolean get() = b("cwStremio", true); set(v) = putB("cwStremio", v)
    var cwSimkl: Boolean get() = b("cwSimkl", true); set(v) = putB("cwSimkl", v)
    /** What you watch on the Nuvio account (Legacy, the official app and forks push it there). */
    var cwNuvioAccount: Boolean get() = b("cwNuvioAccount", true); set(v) = putB("cwNuvioAccount", v)
    /** Catalogs from the add-ons on the Stremio account. */
    var catalogsStremio: Boolean get() = b("catalogsStremio", false); set(v) = putB("catalogsStremio", v)
    /** Continue Watching at the top of the Library: on, card shape (backdrop | poster), size, where it came from. */
    var libraryCw: Boolean get() = b("libraryCw", true); set(v) = putB("libraryCw", v)
    var libraryCwShape: String get() = s("libraryCwShape", "backdrop"); set(v) = putS("libraryCwShape", v)
    var libraryCwSize: Float get() = f("libraryCwSize", 1.0f); set(v) = putF("libraryCwSize", v.coerceIn(0.7f, 1.5f))
    var libraryCwSource: Boolean get() = b("libraryCwSource", true); set(v) = putB("libraryCwSource", v)
    /** The Simkl "Plan to watch" list in the Library, and + saves there too. */
    var librarySimkl: Boolean get() = b("librarySimkl", true); set(v) = putB("librarySimkl", v)
    var libraryToSimkl: Boolean get() = b("libraryToSimkl", true); set(v) = putB("libraryToSimkl", v)
    /** How many titles in progress open the carousel (0 = all); the rest mix in with the catalogs. */
    var cwLead: Int get() = i("cwLead", 3); set(v) = putI("cwLead", v)
    /** After you leave the player, the carousel opens on what you just watched. */
    var continueJump: Boolean get() = b("continueJump", true); set(v) = putB("continueJump", v)
    var cwLegacy: Boolean get() = b("cwLegacy", true); set(v) = putB("cwLegacy", v)
    var cwProgress: Boolean get() = b("cwProgress", true); set(v) = putB("cwProgress", v)
    /** 0.6 .. 1.0 (1.0 = full width) */
    var dockScale: Float get() = f("dockScale", 1.0f); set(v) = putF("dockScale", v.coerceIn(0.6f, 1.0f))
    /** Background of the dock and the Apps folder: liquid | clear | amoled | bow */
    var dockStyle: String get() = s("dockStyle", "water_still"); set(v) = putS("dockStyle", v)
    /** Background of settings and the icon picker: liquid | clear | amoled */
    var panelStyle: String get() = s("panelStyle", "amoled"); set(v) = putS("panelStyle", v)
    /** liquid | clear | amoled | bow (black on white) */
    var tileStyle: String get() = s("tileStyle", "water_still"); set(v) = putS("tileStyle", v)
    /** How far the glass rim bends the background, dp. */
    var refraction: Int get() = i("refraction", 2); set(v) = putI("refraction", v.coerceIn(0, 40))
    var metaScale: Float get() = f("metaScale", 0.9f); set(v) = putF("metaScale", v.coerceIn(0.6f, 2.0f))
    var genresScale: Float get() = f("genresScale", 0.8f); set(v) = putF("genresScale", v.coerceIn(0.6f, 2.0f))
    /** Opaque dark plate behind every icon, so transparent pack icons don't show the poster through. */
    var tileBacking: Boolean get() = b("tileBacking", true); set(v) = putB("tileBacking", v)
    /** package name of the selected icon pack, "" = none */
    var iconPack: String get() = s("iconPack", ""); set(v) = putS("iconPack", v)
    /** Weather in the top bar: off | temp | condition | city | all */
    var weatherMode: String get() = s("weatherMode", "off"); set(v) = putS("weatherMode", v)
    var weatherCity: String get() = s("weatherCity", "Baghdad"); set(v) = putS("weatherCity", v.trim())
    var weatherF: Boolean get() = b("weatherF", false); set(v) = putB("weatherF", v)
    var showDate: Boolean get() = b("showDate", false); set(v) = putB("showDate", v)
    /** tvOS look: icons are white until selected, then show their colours on a light plate. */
    var focusColor: Boolean get() = b("focusColor", true); set(v) = putB("focusColor", v)
    /** Plate behind the selected icon in "Colour on focus": light | dark | glass | amoled (pure black) */
    var focusPlate: String get() = s("focusPlate", "glass"); set(v) = putS("focusPlate", v)
    /** Clock: left (Apple TV) | right (next to the settings button, weather under it) */
    var clockPos: String get() = s("clockPos", "right"); set(v) = putS("clockPos", v)
    /** Pressing further at the edge of the dock nudges the icon, like tvOS. */
    var edgeBounce: Boolean get() = b("edgeBounce", true); set(v) = putB("edgeBounce", v)
    /** The selected icon rises a little, like tvOS. */
    var tileLift: Boolean get() = b("tileLift", false); set(v) = putB("tileLift", v)
    var showClock: Boolean get() = b("showClock", false); set(v) = putB("showClock", v)
    var clock24h: Boolean get() = b("clock24h", false); set(v) = putB("clock24h", v)
    var blurRadius: Int get() = i("blurRadius", 4); set(v) = putI("blurRadius", v.coerceIn(0, 80))
    var glassTint: Int get() = i("glassTint", 0); set(v) = putI("glassTint", v.coerceIn(0, 40))
    var showMobileApps: Boolean get() = b("showMobileApps", true); set(v) = putB("showMobileApps", v)
    var dockOrder: List<String> get() = list("dockOrder"); set(v) = putList("dockOrder", v)
    var hiddenApps: List<String> get() = list("hiddenApps"); set(v) = putList("hiddenApps", v)
    /** The Apps folder tile in the dock. */
    var showFolder: Boolean get() = b("showFolder", true); set(v) = putB("showFolder", v)
    /** With fewer apps than a full row, the dock shrinks around them. */
    var dockFit: Boolean get() = b("dockFit", true); set(v) = putB("dockFit", v)
    /** The dock hides below the screen and comes up when you go down to it. */
    var dockHide: Boolean get() = b("dockHide", false); set(v) = putB("dockHide", v)
    /** With your own background (picture, video, screensaver): bottom | center | left (a column on the left). */
    var dockPos: String get() = s("dockPos", "bottom"); set(v) = putS("dockPos", v)
    /** Down from the dock's last row opens the Library. */
    var libraryDown: Boolean get() = b("libraryDown", true); set(v) = putB("libraryDown", v)

    // ---- Nuvio / Home Assistant
    /** auto (first installed: Legacy, official, Arc, Re-Shaped) | legacy | official | arc | reshaped */
    var nuvioTarget: String get() = s("nuvioTarget", "auto"); set(v) = putS("nuvioTarget", v)
    var haWebhook: String get() = s("haWebhook", ""); set(v) = putS("haWebhook", v.trim())
    var haOnLaunch: Boolean get() = b("haOnLaunch", false); set(v) = putB("haOnLaunch", v)

    // ---- Screensaver
    var idleMinutes: Int get() = i("idleMinutes", 0); set(v) = putI("idleMinutes", v.coerceAtLeast(0))
    /** Idle time before the screensaver, in seconds (0 = off). Older versions kept whole minutes in idleMinutes. */
    var idleSeconds: Int get() = if (sp.contains("idleSeconds")) i("idleSeconds", 30) else if (sp.contains("idleMinutes")) i("idleMinutes", 0) * 60 else 30
        set(v) = putI("idleSeconds", v.coerceAtLeast(0))
    /** aerial (Apple videos) | slideshow (posters with logos) | system (the TV's own screensaver, e.g. Aerial Views) */
    var saverMode: String get() = s("saverMode", "aerial"); set(v) = putS("saverMode", v)
    var useAppleAerials: Boolean get() = b("useAppleAerials", true); set(v) = putB("useAppleAerials", v)
    var aerialUrls: List<String> get() = list("aerialUrls"); set(v) = putList("aerialUrls", v)
    var saverClock: Boolean get() = b("saverClock", true); set(v) = putB("saverClock", v)
    /** Collections of aerial videos that are on (apple, amazon, jetson, robin); see AerialSource. */
    var saverSources: List<String> get() = if (sp.contains("saverSources")) list("saverSources").filter { it.isNotBlank() } else if (sp.contains("useAppleAerials")) (if (useAppleAerials) listOf("apple") else emptyList()) else listOf("amazon")
        set(v) = putList("saverSources", v)
    /** Your own videos (links and the backgrounds folder) in the screensaver. */
    var saverMine: Boolean get() = b("saverMine", false); set(v) = putB("saverMine", v)
    var saverQuality: String get() = s("saverQuality", "4K-SDR"); set(v) = putS("saverQuality", v)
    var saverShuffle: Boolean get() = b("saverShuffle", true); set(v) = putB("saverShuffle", v)
    /** One video only (its id), or "" for all of them. */
    var saverPick: String get() = s("saverPick", ""); set(v) = putS("saverPick", v)
    /** Videos are kept on the TV as they play (any link), up to [saverCacheGb]. */
    var saverCache: Boolean get() = b("saverCache", true); set(v) = putB("saverCache", v)
    var saverCacheGb: Int get() = i("saverCacheGb", 4); set(v) = putI("saverCacheGb", v.coerceIn(1, 32))
    /** Cross-fade from one video to the next. */
    var saverFade: Boolean get() = b("saverFade", true); set(v) = putB("saverFade", v)
    var saverDate: Boolean get() = b("saverDate", true); set(v) = putB("saverDate", v)
    var saverWeather: Boolean get() = b("saverWeather", true); set(v) = putB("saverWeather", v)
    /** The place the video shows (Apple, Amazon and community videos have one). */
    var saverPlace: Boolean get() = b("saverPlace", true); set(v) = putB("saverPlace", v)
    var saverSys: Boolean get() = b("saverSys", false); set(v) = putB("saverSys", v)
    /** apple (clock in a corner, small text) | netflix (lines appear one by one at the top left, like the notes before a film) */
    var saverStyle: String get() = s("saverStyle", "netflix"); set(v) = putS("saverStyle", v)
    /** bl | br | tl | tr | move (another corner with every video) */
    var saverPos: String get() = s("saverPos", "bl"); set(v) = putS("saverPos", v)
    var saverSize: Float get() = f("saverSize", 0.8f); set(v) = putF("saverSize", v.coerceIn(0.6f, 1.8f))
    /** Seconds the info stays after each new video (0 = always). */
    var saverShowSec: Int get() = i("saverShowSec", 10); set(v) = putI("saverShowSec", v.coerceAtLeast(0))
    /** Netflix style: the lines go away a few seconds after the last one came in. */
    var saverNetflixHide: Boolean get() = b("saverNetflixHide", true); set(v) = putB("saverNetflixHide", v)
    /** Netflix style: the bar before each line — "red", "white", "black" or "off". */
    var saverBar: String get() = s("saverBar", "white"); set(v) = putS("saverBar", v)
    /** How far and how strong the glow is (0.4 … 1.6). */
    var glowSize: Float get() = f("glowSize", 0.6f); set(v) { putF("glowSize", v.coerceIn(0.3f, 1.6f)); com.glasslauncher.tv.ui.Glow.amount = v.coerceIn(0.3f, 1.6f) }
    /** Dock: on focus the whole tile grows ("tile"), only the icon inside the glass ("icon"), or nothing ("off"). */
    var tileZoom: String get() = s("tileZoom", "icon"); set(v) = putS("tileZoom", v)
    /** A very soft glow around the selected icon / poster: "off", "color" (its own colour) or "white". */
    var glow: String get() = s("glow", "white"); set(v) = putS("glow", v)
    /** Hold Home: "recents", "camera" (live camera over any app), "library", "menu" or "off". */
    var homeLong: String get() = s("homeLong", if (b("homeRecents", false)) "recents" else "off"); set(v) = putS("homeLong", v)
    /** Ask before removing a title from the Library. */
    var libraryConfirm: Boolean get() = b("libraryConfirm", true); set(v) = putB("libraryConfirm", v)
    /** Background video: a new one when each ends (else the same one loops). */
    var bgShuffle: Boolean get() = b("bgShuffle", true); set(v) = putB("bgShuffle", v)

    /** Existing installs skip the first-run setup (it is for new TVs). */
    fun firstRun() { if (!sp.contains("setupDone")) putB("setupDone", sp.all.size > 8) }

    /** Testing over adb (see DevHooks): key=value — true/false, a number, or text; lists with \n. */
    fun debugSet(kv: String) {
        val k = kv.substringBefore('='); val v = kv.substringAfter('=')
        when {
            v == "true" || v == "false" -> putB(k, v == "true")
            v.toIntOrNull() != null && !k.endsWith("Pick") -> putI(k, v.toInt())
            v.toFloatOrNull() != null && v.contains('.') -> putF(k, v.toFloat())
            else -> putS(k, v.replace("\\n", "\n"))
        }
    }

    // ---- System
    var interceptHome: Boolean get() = b("interceptHome", false); set(v) = putB("interceptHome", v)
    /** Opens Glassio when the TV starts (needs the "display over other apps" right, given over ADB). */
    var bootStart: Boolean get() = b("bootStart", false); set(v) = putB("bootStart", v)
    /** Manage from phone: minutes the page stays on after you leave that screen (0 = stops at once). */
    var phoneMinutes: Int get() = i("phoneMinutes", 30); set(v) = putI("phoneMinutes", v.coerceIn(0, 720))
    /** App language, a key of I18n.LANGS. */
    var lang: String get() = s("lang", "en"); set(v) { putS("lang", v); I18n.lang = v }
    /** The first-run setup was finished (or skipped with Later). */
    var setupDone: Boolean get() = b("setupDone", false); set(v) = putB("setupDone", v)
}
