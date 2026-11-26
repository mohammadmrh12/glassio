package com.glasslauncher.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * Every launcher option lives here. Reads touch a Compose state counter, so any composable that
 * reads a property recomposes automatically when a setter bumps it.
 */
val MENU_KEYS = listOf("library", "search", "sliders", "phone", "gear", "wifi", "bluetooth", "theme", "camera", "saver", "background", "recents", "inputs", "trailer", "profile")
/** The menu's order on a new TV. */
val MENU_DEFAULT = listOf("profile", "inputs", "library", "search", "camera", "sliders", "phone", "recents", "gear", "wifi", "bluetooth", "saver", "trailer", "theme", "background")
val MENU_NAMES = mapOf("library" to "Library", "sliders" to "Glassio settings", "gear" to "Android settings", "wifi" to "Wi-Fi", "bluetooth" to "Bluetooth",
    "theme" to "Themes", "camera" to "Camera", "saver" to "Screensaver", "background" to "Background", "recents" to "Recent apps", "inputs" to "Inputs", "trailer" to "Trailer", "search" to "Search", "profile" to "Profile", "phone" to "Manage from phone")

class Settings(private val ctx: Context) {
    // The TV's own settings are kept once, a profile's in that profile's file (see Profiles). For the profile that
    // was in use before profiles existed both are the same file, as it always was.
    private val dev = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
    private val own = Profiles.prefs(ctx, "glass")
    private val split = own !== dev
    private fun sp(k: String): android.content.SharedPreferences = if (split && !Profiles.isDevice(k)) own else dev
    private val rev = mutableIntStateOf(0)

    /** Goes up with every setting written (read in composition: what depends on "any setting" is worked out again). */
    val changes: Int get() = rev.intValue
    private fun touch() { rev.intValue = rev.intValue + 1 }
    /** Test only: every stored value (am start --ez dump_prefs true → logcat GlassPrefs). */
    fun dumpAll(): Map<String, *> = if (!split) dev.all else dev.all.filterKeys { Profiles.isDevice(it) } + own.all.filterKeys { !Profiles.isDevice(it) }

    // Settings → Changed: what a row reads, and what it would show at its defaults. Only on the thread that asks
    // (the probe), so nothing else ever reads a default for a value that was set.
    @Volatile private var probe: Thread? = null
    private var probeDefaults = false
    private var probeReads: MutableSet<String>? = null
    private var probeLists: MutableSet<String>? = null
    private fun probed(k: String): Boolean {
        val p = probe ?: return false
        if (p !== Thread.currentThread()) return false
        probeReads?.add(k)
        return probeDefaults
    }
    /** [block]'s result and the keys it read; with [defaults], as if no setting had ever been changed. */
    fun <T> probe(defaults: Boolean, block: () -> T): Triple<T, Set<String>, Set<String>> {
        val reads = HashSet<String>(); val lists = HashSet<String>()
        probe = Thread.currentThread(); probeDefaults = defaults; probeReads = reads; probeLists = lists
        try { return Triple(block(), reads, lists) } finally { probe = null; probeDefaults = false; probeReads = null; probeLists = null }
    }
    /** Of [keys], the ones set on this TV (or profile): what "back to default" removes. */
    fun setKeys(keys: Collection<String>): List<String> = keys.filter { sp(it).contains(it) }

    private fun b(k: String, d: Boolean): Boolean { rev.intValue; if (probed(k)) return d; return sp(k).getBoolean(k, d) }
    private fun i(k: String, d: Int): Int { rev.intValue; if (probed(k)) return d; return sp(k).getInt(k, d) }
    private fun f(k: String, d: Float): Float { rev.intValue; if (probed(k)) return d; return sp(k).getFloat(k, d) }
    // A value saved as another type (a list of one number set from a computer was stored as a number) is read as
    // text instead of crashing.
    private fun s(k: String, d: String): String { rev.intValue; if (probed(k)) return d; val sp = sp(k); return runCatching { sp.getString(k, d) ?: d }.getOrElse { sp.all[k]?.toString() ?: d } }
    // A value written again unchanged changes nothing: every write redraws whatever read any setting (it bumps [rev]),
    // and some were written over and over with the same value.
    private fun putB(k: String, v: Boolean) { val sp = sp(k); if (sp.contains(k) && sp.getBoolean(k, !v) == v) return; sp.edit().putBoolean(k, v).apply(); wrote(k) }
    private fun putI(k: String, v: Int) { val sp = sp(k); if (sp.contains(k) && runCatching { sp.getInt(k, v - 1) }.getOrNull() == v) return; sp.edit().putInt(k, v).apply(); wrote(k) }
    private fun putF(k: String, v: Float) { val sp = sp(k); if (sp.contains(k) && runCatching { sp.getFloat(k, v - 1f) }.getOrNull() == v) return; sp.edit().putFloat(k, v).apply(); wrote(k) }
    private fun putS(k: String, v: String) { val sp = sp(k); if (sp.contains(k) && runCatching { sp.getString(k, null) }.getOrNull() == v) return; sp.edit().putString(k, v).apply(); wrote(k) }
    /** The last settings written (QA: dump_focus shows them, to find writes that redraw the screen). */
    val recentWrites = java.util.concurrent.ConcurrentLinkedDeque<String>()
    private fun wrote(k: String) {
        recentWrites.addFirst("$k@${android.os.SystemClock.uptimeMillis() % 100000}")
        while (recentWrites.size > 12) recentWrites.pollLast()
        touch()
    }
    // Lists parsed once per stored value (read again for every slide and every title on screen, the long ones —
    // artwork picks, likes, the dock — were split and trimmed on the main thread each time: ~10% of its time while
    // the carousel moved).
    private val parsed = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<String>>>()
    private fun list(k: String): List<String> {
        if (probe === Thread.currentThread()) probeLists?.add(k)
        val raw = s(k, "")
        parsed[k]?.let { if (it.first == raw) return it.second }
        val l = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        parsed[k] = raw to l
        return l
    }
    private val picks = java.util.concurrent.atomic.AtomicReference<Pair<List<String>, Map<String, String>>?>(null)
    private fun putList(k: String, v: List<String>) = putS(k, v.joinToString("\n"))

    /**
     * Settings → Sync: the main TV's values written in one go (and the ones it doesn't have back to their default), then
     * every screen redraws with them. Main thread.
     */
    fun applyRemote(put: Map<String, Any>, remove: Set<String>) {
        // (Each value into the file it belongs to: the TV's, or the profile's.)
        val eds = HashMap<android.content.SharedPreferences, android.content.SharedPreferences.Editor>()
        fun ed(k: String) = sp(k).let { p -> eds.getOrPut(p) { p.edit() } }
        remove.forEach { ed(it).remove(it) }
        @Suppress("UNCHECKED_CAST")
        put.forEach { (k, v) -> val ed = ed(k); when (v) { is Boolean -> ed.putBoolean(k, v); is Int -> ed.putInt(k, v); is Long -> ed.putLong(k, v)
            is Float -> ed.putFloat(k, v); is String -> ed.putString(k, v); is Set<*> -> ed.putStringSet(k, v as Set<String>) } }
        eds.values.forEach { it.apply() }
        com.glasslauncher.tv.ui.Glow.amount = glowSize
        I18n.lang = lang
        touch()
    }

    // ---- Hero / carousel
    var heroAutoplay: Boolean get() = b("heroAutoplay", true); set(v) = putB("heroAutoplay", v)
    /** Where slides change on their own: "always", or "carousel" (only while the carousel itself is selected: a weak TV
     *  then draws nothing while you sit on the dock). */
    var heroAutoplayWhere: String get() = s("heroAutoplayWhere", "always"); set(v) = putS("heroAutoplayWhere", v)
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
    /**
     * The light ways of doing things (slower island bars, the music page's blur and a title's trailer only when needed,
     * pictures let go a minute after Glassio is left, Hue every 2 s): the smallest memory tier (Performance), and Lite,
     * which keeps the look as it is (resolution, glass, pictures, animations) and only keeps less ready and works less.
     */
    val lean: Boolean get() = memTier == 0 || perfMode == "lite"
    /** The tier Auto picked, for display. */
    val autoTier: String by lazy { deviceTier() }
    /** What this TV is, in a few words (memory, Android, cores, graphics). */
    val tvKind: String by lazy {
        runCatching {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            val gb = Math.round(mi.totalMem / (1024.0 * 1024 * 1024) * 2) / 2.0
            val gles = am.deviceConfigurationInfo.reqGlEsVersion
            val g = gb.toString().removeSuffix(".0")
            listOf(android.os.Build.MODEL, "$g GB", "Android ${android.os.Build.VERSION.RELEASE}",
                "${Runtime.getRuntime().availableProcessors()} cores", "OpenGL ES ${gles shr 16}.${gles and 0xffff}").joinToString(" · ")
        }.getOrDefault(android.os.Build.MODEL)
    }
    /**
     * The performance settings changed by hand while a mode is in use: the mode leaves them as they are (only that
     * setting is your own; the mode goes on choosing the rest). Was: any change made the whole mode Custom.
     */
    var perfOwn: Set<String> get() = list("perfOwn").toSet(); set(v) = putList("perfOwn", v.sorted())
    /** [key] set by hand (see [perfOwn]). */
    fun ownPerf(key: String) { if (perfMode != "custom" && key !in perfOwn) perfOwn = perfOwn + key }
    /** The keys a mode sets. */
    val PERF_KEYS = listOf("imageQuality", "prefetchDepth", "transitionMs", "refraction", "heroGallery", "galleryShots", "glassRes", "pageScale",
        "slideAnim", "memTier", "saverQuality", "cacheMb", "heroAutoplayWhere")
    /** Recommended for this TV: Auto, every performance setting back to what Auto picks here (your own ones too). */
    fun recommendedMode() {
        perfOwn = emptySet()
        PERF_KEYS.forEach { k -> sp(k).edit().remove(k).apply() }
        PERF_KEYS.forEach { wrote(it) }
        applyMode("auto")
    }

    /** The page picture is prepared at this fraction of the screen size (2/3 in Performance: 2.25× less memory and drawing). */
    var pageScale: Float get() = f("pageScale", 1f); set(v) = putF("pageScale", if (v < 0.9f) 0.667f else 1f)
    /** 0 small, 1 medium, 2 large memory caches (pictures kept ready); set by the mode. */
    var memTier: Int get() = i("memTier", 2); set(v) = putI("memTier", v.coerceIn(0, 2))

    /** Glass copies of the backdrop: 480 px (light, default) or 720 px (crisper glass, more memory). */
    var glassRes: Int get() = i("glassRes", 720); set(v) = putI("glassRes", if (v >= 720) 720 else 480)

    /** Which picture a title uses: main | second | best (highest resolution) | addon (the add-on's own). */
    var artChoice: String get() = s("artChoice", "best"); set(v) = putS("artChoice", v)
    /** Per-title picture picked with a long press on the carousel: "key\turl" lines. */
    fun artPick(key: String): String? {
        val l = list("artPicks")
        val c = picks.get()?.takeIf { it.first === l } ?: (l to l.associate { it.substringBefore('\t') to it.substringAfter('\t') }).also { picks.set(it) }
        return c.second[key]
    }
    fun setArtPick(key: String, url: String?) {
        val rest = list("artPicks").filterNot { it.startsWith("$key\t") }
        putList("artPicks", if (url == null) rest else (rest + "$key\t$url").takeLast(300))
    }
    fun clearArtPicks() = putList("artPicks", emptyList())
    /** Titles hidden from the carousel (hold OK on Play → Hide): "key\tname" lines, newest last. */
    fun hiddenTitles(): List<Pair<String, String>> = list("hiddenTitles").map { it.substringBefore('\t') to it.substringAfter('\t', "") }
    private val hiddenSet = java.util.concurrent.atomic.AtomicReference<Pair<List<String>, Set<String>>?>(null)
    /** The hidden keys, parsed once per stored value. */
    fun hiddenKeys(): Set<String> {
        val l = list("hiddenTitles")
        return (hiddenSet.get()?.takeIf { it.first === l } ?: (l to l.map { it.substringBefore('\t') }.toSet()).also { hiddenSet.set(it) }).second
    }
    fun setHidden(key: String, name: String, on: Boolean) {
        val rest = list("hiddenTitles").filterNot { it.substringBefore('\t') == key }
        putList("hiddenTitles", if (on) (rest + "$key\t${name.replace('\t', ' ').replace('\n', ' ')}").takeLast(1000) else rest)
    }
    fun clearHidden() = putList("hiddenTitles", emptyList())
    /** Library, likes and Continue Watching kept the same on every connected account (Connections → Sync). */
    var syncAll: Boolean get() = b("syncAll", false); set(v) = putB("syncAll", v)
    /** Likes already on an account ("trakt", "simkl"), so they're sent once. */
    fun likeSent(acc: String, key: String): Boolean = list("likeSent_$acc").contains(key)
    fun setLikeSent(acc: String, key: String, on: Boolean) {
        val rest = list("likeSent_$acc").filterNot { it == key }
        putList("likeSent_$acc", if (on) (rest + key).takeLast(3000) else rest)
    }
    /** All liked keys. */
    fun likedKeys(): List<String> = list("likes")
    /** Titles you liked (hold OK on Play → Like): their keys. */
    fun liked(key: String): Boolean = list("likes").contains(key)
    fun setLiked(key: String, on: Boolean) {
        val rest = list("likes").filterNot { it == key }
        putList("likes", if (on) (rest + key).takeLast(2000) else rest)
    }
    /** max = original pictures (4K source, sharpest) | high = 1280 px pictures (faster, lighter) */
    var imageQuality: String get() = s("imageQuality", "max"); set(v) = putS("imageQuality", v)
    /** How many slides ahead are prepared (pictures, logos, glass). */
    var prefetchDepth: Int get() = i("prefetchDepth", 8); set(v) = putI("prefetchDepth", v.coerceIn(1, 8))

    fun applyMode(m: String) {
        // Auto: a weak TV gets the light preset; any other keeps the defaults (made on a 4 GB Google TV Streamer).
        val tier = if (m == "auto") deviceTier().takeIf { it == "performance" } else m
        // (The ones you set by hand stay yours.)
        val own = perfOwn
        fun set(k: String, put: () -> Unit) { if (k !in own) put() }
        when (tier) {
            // Performance: pictures at 1280 px and drawn at 2/3 resolution, no rim refraction (no GPU effects at
            // all), a plain crossfade, small caches and slides that change on their own only on the carousel: for
            // 2 GB TVs and older graphics chips.
            "performance" -> { set("imageQuality") { imageQuality = "high" }; set("prefetchDepth") { prefetchDepth = 2 }; set("transitionMs") { transitionMs = 300 }
                set("refraction") { refraction = 0 }; set("heroGallery") { heroGallery = false }; set("glassRes") { glassRes = 480 }
                set("pageScale") { pageScale = 0.667f }; set("slideAnim") { slideAnim = "fade" }; set("memTier") { memTier = 0 }
                set("saverQuality") { saverQuality = "1080-H264" }; set("cacheMb") { cacheMb = 1024 }; set("heroAutoplayWhere") { heroAutoplayWhere = "carousel" } }
            "balanced" -> { set("imageQuality") { imageQuality = "high" }; set("prefetchDepth") { prefetchDepth = 3 }; set("transitionMs") { transitionMs = 450 }
                set("refraction") { refraction = 6 }; set("heroGallery") { heroGallery = true }; set("galleryShots") { galleryShots = 2 }; set("glassRes") { glassRes = 480 }
                set("pageScale") { pageScale = 1f }; set("memTier") { memTier = 1 } }
            // Lite: the same look (pictures, resolution, glass, animations untouched), less kept ready and less work:
            // two slides prepared ahead, the middle caches, slides that change on their own only on the carousel, the
            // screensaver's video in 1080p, and the light ways of [lean].
            "lite" -> { set("prefetchDepth") { prefetchDepth = 2 }; set("memTier") { memTier = 1 }; set("heroAutoplayWhere") { heroAutoplayWhere = "carousel" }
                set("saverQuality") { saverQuality = "1080-H264" } }
            "quality" -> { set("imageQuality") { imageQuality = "max" }; set("prefetchDepth") { prefetchDepth = 6 }; set("transitionMs") { transitionMs = 700 }
                set("refraction") { refraction = 14 }; set("heroGallery") { heroGallery = true }; set("galleryShots") { galleryShots = 3 }; set("glassRes") { glassRes = 720 }
                set("pageScale") { pageScale = 1f }; set("memTier") { memTier = 2 } }
        }
        perfMode = m
    }

    /** Apple TV style: each title shows a few of its backdrops before the carousel moves on. */
    var heroGallery: Boolean get() = b("heroGallery", true); set(v) = putB("heroGallery", v)
    var galleryShots: Int get() = i("galleryShots", 3); set(v) = putI("galleryShots", v.coerceIn(2, 6))
    var heroIntervalSec: Int get() = i("heroIntervalSec", 9); set(v) = putI("heroIntervalSec", v.coerceIn(3, 60))
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
    var dotsMode: String get() = s("dotsMode", "liquid"); set(v) = putS("dotsMode", v)
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
        get() = (if (sp("menuOrder").contains("menuOrder")) list("menuOrder") else MENU_DEFAULT).filter { it in MENU_KEYS }.let { it + MENU_KEYS.filter { k -> k !in it } }
        set(v) = putList("menuOrder", v)
    var menuAndroid: Boolean get() = b("menuAndroid", true); set(v) = putB("menuAndroid", v)
    var menuWifi: Boolean get() = b("menuWifi", false); set(v) = putB("menuWifi", v)
    /** Bluetooth in the menu: the TV's Bluetooth settings (remotes, headphones, speakers). */
    var menuBluetooth: Boolean get() = b("menuBluetooth", false); set(v) = putB("menuBluetooth", v)
    /** "Recent apps" in the menu: the app switcher (like holding Home). */
    var menuRecents: Boolean get() = b("menuRecents", false); set(v) = putB("menuRecents", v)
    /** Recent apps through ADB when the TV allows it (exact list); off: the apps Glassio saw on screen, no ADB. */
    var recentsAdb: Boolean get() = b("recentsAdb", true); set(v) = putB("recentsAdb", v)
    // ---- Music (Settings → Music)
    /** Music on: Glassio follows what any music app plays (needs its notification listener, switched on over ADB). */
    var musicOn: Boolean get() = b("musicOn", true); set(v) = putB("musicOn", v)

    // ---- Hue Sync (Philips Hue lights in the colours of what's on screen)
    var hueOn: Boolean get() = b("hueOn", false); set(v) = putB("hueOn", v)
    var hueIp: String get() = s("hueIp", ""); set(v) = putS("hueIp", v)
    var hueUser: String get() = s("hueUser", ""); set(v) = putS("hueUser", v)
    /** The bridge's certificate as first seen ("address pin", see Hue.pinVerdict). Empty: learnt at the next request. */
    var huePin: String get() = s("huePin", ""); set(v) = putS("huePin", v)
    /** Rooms and zones (group ids) whose lights follow the screen. */
    var hueGroups: List<String> get() = list("hueGroups"); set(v) = putList("hueGroups", v)
    /** Single lights (light ids) that follow the screen. */
    var hueLights: List<String> get() = list("hueLights"); set(v) = putList("hueLights", v)
    /** Where the colours come from: "backdrop" (what fills the screen), "poster", "logo". */
    var hueSource: String get() = s("hueSource", "backdrop"); set(v) = putS("hueSource", v)
    /** Videos (your video background, trailers) followed live. */
    var hueVideo: Boolean get() = b("hueVideo", true); set(v) = putB("hueVideo", v)
    var hueVideoSec: Int get() = i("hueVideoSec", 1); set(v) = putI("hueVideoSec", v.coerceIn(0, 10))
    /** A different colour of the picture for each light (true) or the main colour on all. */
    var hueSpread: Boolean get() = b("hueSpread", true); set(v) = putB("hueSpread", v)
    /** 0 Subtle, 1 Natural, 2 Vivid, 3 Maximum. */
    var hueIntensity: Int get() = i("hueIntensity", 1); set(v) = putI("hueIntensity", v.coerceIn(0, 3))
    /** Brightness, % of full. */
    var hueBri: Int get() = i("hueBri", 70); set(v) = putI("hueBri", v.coerceIn(5, 100))
    /** Darker pictures make the lights dimmer. */
    var hueFollowBri: Boolean get() = b("hueFollowBri", false); set(v) = putB("hueFollowBri", v)
    /** How long a change of colour takes. */
    var hueSpeedMs: Int get() = i("hueSpeedMs", 400); set(v) = putI("hueSpeedMs", v.coerceIn(0, 10_000))
    /** Each light's part of the screen ("id:zone,…"; not listed = automatic). */
    var hueZones: String get() = s("hueZones", ""); set(v) = putS("hueZones", v)
    /** How lights of other makes take their colour ("id:hs|xy,…"; not listed = hue and saturation). */
    var hueMethods: String get() = s("hueMethods", ""); set(v) = putS("hueMethods", v)
    /** On the screensaver too (its videos). */
    var hueSaver: Boolean get() = b("hueSaver", true); set(v) = putB("hueSaver", v)
    /** The lights take the glow's colour of the selected app in the dock / poster in the Library. */
    var hueApps: Boolean get() = b("hueApps", false); set(v) = putB("hueApps", v)
    var huePosters: Boolean get() = b("huePosters", false); set(v) = putB("huePosters", v)
    /** How bright the lights are with a selected app's / poster's colour (their own setting, not the screen's). */
    var hueAppsBri: Int get() = i("hueAppsBri", 70); set(v) = putI("hueAppsBri", v.coerceIn(5, 100))
    var huePostersBri: Int get() = i("huePostersBri", 70); set(v) = putI("huePostersBri", v.coerceIn(5, 100))
    /** Opening an app or a film: "restore" (as they were), "dim", "off", "keep". */
    var hueLeave: String get() = s("hueLeave", "restore"); set(v) = putS("hueLeave", v)
    /** The TV turning off: "restore", "off", "keep". */
    var hueOff: String get() = s("hueOff", "restore"); set(v) = putS("hueOff", v)
    /** ▶ in the menu turns it into the player. */
    var musicMenu: Boolean get() = b("musicMenu", true); set(v) = putB("musicMenu", v)
    /** A Music icon in the dock (opens the menu's player). */
    var musicDock: Boolean get() = b("musicDock", true); set(v) = putB("musicDock", v)
    /** Music mode: while a song plays, the home screen's background becomes the player (whatever background you chose). */
    var musicMode: Boolean get() = b("musicMode", true); set(v) = putB("musicMode", v)
    var musicStageScale: Float get() = f("musicStageScale", 0.9f); set(v) = putF("musicStageScale", v.coerceIn(0.7f, 1.4f))
    /** theme | white | black: the player's buttons (the other way round when selected). */
    var musicButtons: String get() = s("musicButtons", "theme"); set(v) = putS("musicButtons", v)
    /**
     * The player page's background over your own background: "auto" (yours with see-through glass, else the artwork),
     * "art" (the song's artwork, blurred) or "own" (yours stays).
     */
    var musicPageBg: String get() = s("musicPageBg", "auto"); set(v) = putS("musicPageBg", v)
    /** The player page keeps your own background (a video, a picture) instead of the artwork. */
    val musicKeepsOwnBg: Boolean get() = bgMode != "carousel" &&
        (musicPageBg == "own" || (musicPageBg == "auto" && videoGlass == "clear" && (bgMode == "video" || bgMode == "aerial")))
    /** The island on the screensaver while music plays. */
    var musicIsland: Boolean get() = b("musicIsland", true); set(v) = putB("musicIsland", v)
    var islandStyle: String get() = s("islandStyle", "theme"); set(v) = putS("islandStyle", v)
    /** Joined to the top of the screen (like a MacBook's notch) instead of floating below it. */
    var islandAttached: Boolean get() = b("islandAttached", false); set(v) = putB("islandAttached", v)
    var islandScale: Float get() = f("islandScale", 0.7f); set(v) = putF("islandScale", v.coerceIn(0.3f, 1.8f))
    var islandOffset: Float get() = f("islandOffset", 0f); set(v) = putF("islandOffset", v.coerceIn(0f, 120f))
    var islandArt: Boolean get() = b("islandArt", true); set(v) = putB("islandArt", v)
    var islandTitle: Boolean get() = b("islandTitle", true); set(v) = putB("islandTitle", v)
    var islandProgress: Boolean get() = b("islandProgress", true); set(v) = putB("islandProgress", v)
    /** art | white: the colour of the island's bars. */
    var islandBars: String get() = s("islandBars", "art"); set(v) = putS("islandBars", v)
    /** "Inputs" in the menu: the TV's HDMI inputs and what is plugged into them. */
    var menuInputs: Boolean get() = b("menuInputs", false); set(v) = putB("menuInputs", v)
    /** The inputs as icons in the dock too (moved, hidden and given icons like apps). */
    var inputsDock: Boolean get() = b("inputsDock", true); set(v) = putB("inputsDock", v)
    var menuThemes: Boolean get() = b("menuThemes", true); set(v) = putB("menuThemes", v)
    var menuBackground: Boolean get() = b("menuBackground", true); set(v) = putB("menuBackground", v)
    /** The menu's Profile row (shown when the Nuvio account has more than one profile). */
    var menuProfile: Boolean get() = b("menuProfile", true); set(v) = putB("menuProfile", v)
    /** The menu greets the profile in use by name ("Hello, …") next to its picture. */
    var menuHello: Boolean get() = b("menuHello", false); set(v) = putB("menuHello", v)
    /** Size of the menu (0.8 – 1.3). */
    var menuScale: Float get() = f("menuScale", 0.95f); set(v) = putF("menuScale", v.coerceIn(0.8f, 1.3f))
    /** The TV's numbers under the weather in the menu. */
    var menuSys: Boolean get() = b("menuSys", true); set(v) = putB("menuSys", v)
    var sysIp: Boolean get() = b("sysIp", false); set(v) = putB("sysIp", v)
    var sysCpu: Boolean get() = b("sysCpu", false); set(v) = putB("sysCpu", v)
    var sysGpu: Boolean get() = b("sysGpu", false); set(v) = putB("sysGpu", v)
    var sysRam: Boolean get() = b("sysRam", false); set(v) = putB("sysRam", v)
    var sysStorage: Boolean get() = b("sysStorage", false); set(v) = putB("sysStorage", v)
    /** Cameras, one per line: "Name|rtsp://…" (RTSP, HLS or a plain video link). */
    var cameras: List<String> get() = list("cameras"); set(v) = putList("cameras", v)
    var menuCamera: Boolean get() = b("menuCamera", false); set(v) = putB("menuCamera", v)
    var menuTrailer: Boolean get() = !Store.PLAY && b("menuTrailer", true); set(v) = putB("menuTrailer", v)
    var menuSaver: Boolean get() = b("menuSaver", true); set(v) = putB("menuSaver", v)
    /** Look of the menu: same choices as the dock (set by Themes). */
    var menuStyle: String get() = s("menuStyle", "water_still"); set(v) = putS("menuStyle", v)
    /** The + button next to Play (save to Library). */
    var heroPlus: Boolean get() = b("heroPlus", false); set(v) = putB("heroPlus", v)
    /** The carousel buttons' plates: "dark" (the Apple TV app's) or "theme" (the icons' plate style). */
    var heroButtonLook: String get() = s("heroButtonLook", "theme"); set(v) = putS("heroButtonLook", v)
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
    var heroButtons: String get() = s("heroButtons", "apple"); set(v) = putS("heroButtons", v)
    var dotsMax: Int get() = i("dotsMax", 7); set(v) = putI("dotsMax", v.coerceIn(3, 20))
    /** Trailers: a Trailer button next to Play (Apple TV buttons). */
    var trailerButton: Boolean get() = !Store.PLAY && b("trailerButton", true); set(v) = putB("trailerButton", v)
    /** cinema (fills the screen) | original (its own shape) | popup (a small glass window by the buttons). */
    var trailerSize: String get() = s("trailerSize", "popup"); set(v) = putS("trailerSize", v)
    var trailerMute: Boolean get() = b("trailerMute", false); set(v) = putB("trailerMute", v)
    /** Trailers' loudness, 10–100 % (the TV's own volume stays as it is). */
    var trailerVolume: Int get() = i("trailerVolume", 50); set(v) = putI("trailerVolume", v.coerceIn(10, 100))
    var saverTrailerVolume: Int get() = i("saverTrailerVolume", 20); set(v) = putI("saverTrailerVolume", v.coerceIn(10, 100))
    var trailerSubs: Boolean get() = b("trailerSubs", true); set(v) = putB("trailerSubs", v)
    /** While a trailer fills the page, the dock and the page dots step aside (never with the trailer in its window). */
    var trailerHideDock: Boolean get() = b("trailerHideDock", true); set(v) = putB("trailerHideDock", v)
    /** "auto": H.264 up to 1080p (720p on small TVs); "full": the best this TV plays, up to its screen (4K: VP9 / AV1). */
    var trailerSubLang: String get() = s("trailerSubLang", "ar"); set(v) = putS("trailerSubLang", v)
    /** Seconds on a title before its trailer starts by itself (0 = only when asked). */
    var trailerAutoSec: Int get() = if (Store.PLAY) 0 else i("trailerAutoSec", 0); set(v) = putI("trailerAutoSec", v.coerceIn(0, 60))
    /** Size of the trailer's subtitles (1 = as before). */
    var trailerSubScale: Float get() = f("trailerSubScale", 1.3f); set(v) = putF("trailerSubScale", v.coerceIn(0.6f, 2.2f))
    var trailerPopupScale: Float get() = f("trailerPopupScale", 2.0f); set(v) = putF("trailerPopupScale", v.coerceIn(0.6f, 2f))
    /** Size of the carousel's dots bar (1 = as designed). */
    var dotsScale: Float get() = f("dotsScale", 0.8f); set(v) = putF("dotsScale", v.coerceIn(0.5f, 1.5f))
    var perCatalog: Int get() = i("perCatalog", 40); set(v) = putI("perCatalog", v.coerceIn(5, 500))
    /** 0 = unlimited */
    var maxItems: Int get() = i("maxItems", 0); set(v) = putI("maxItems", v.coerceAtLeast(0))
    var shuffle: Boolean get() = b("shuffle", true); set(v) = putB("shuffle", v)
    /** Surprise me: a random mix from all the catalogs you chose (different pages each time), fetched again every [surpriseMin]. */
    var surprise: Boolean get() = b("surprise", true); set(v) = putB("surprise", v)
    var surpriseMin: Int get() = i("surpriseMin", 30); set(v) = putI("surpriseMin", v.coerceIn(10, 1440))
    /** Apple style: the title's logo all in white. */
    var logoWhite: Boolean get() = b("logoWhite", false); set(v) = putB("logoWhite", v)
    /** Picture cache on the TV, in MB (applies after Glassio restarts). */
    var cacheMb: Int get() = i("cacheMb", 4096); set(v) = putI("cacheMb", v.coerceIn(256, 8192))
    /** Language of synopses and titles from TMDB: en | ar | … */
    var descLang: String get() = s("descLang", "ar"); set(v) = putS("descLang", v)
    var tmdbToken: String get() = s("tmdbToken", ""); set(v) = putS("tmdbToken", v.trim())

    var transitionMs: Int get() = i("transitionMs", 900); set(v) = putI("transitionMs", v.coerceIn(100, 2000))
    var dotsAutoHide: Boolean get() = b("dotsAutoHide", true); set(v) = putB("dotsAutoHide", v)

    // ---- Clock / dock look
    var topAutoHide: Boolean get() = b("topAutoHide", false); set(v) = putB("topAutoHide", v)
    var topHideSec: Int get() = i("topHideSec", 2); set(v) = putI("topHideSec", v.coerceIn(2, 30))
    var cornerRadius: Int get() = i("cornerRadius", 16); set(v) = putI("cornerRadius", v.coerceIn(0, 40))
    /** Index of the Apps folder among the dock apps; -1 = after the last app. */
    var folderPos: Int get() = i("folderPos", -1); set(v) = putI("folderPos", v)
    /** Continue Watching: off | first (at the start of the carousel) | mixed (among the catalogs) */
    /** A remote button that shuffles the carousel on the home screen (its key code; 0 = none). */
    var shuffleKey: Int get() = i("shuffleKey", 0); set(v) = putI("shuffleKey", v)
    /** Titles a catalog has just added open the carousel for two days. */
    var newFirst: Boolean get() = b("newFirst", true); set(v) = putB("newFirst", v)
    var continueMode: String get() = s("continueMode", "first"); set(v) = putS("continueMode", v)
    /** Continue Watching is fetched while the carousel or the Library shows it (off in the carousel only = still in the Library). */
    val continueWanted: Boolean get() = continueMode != "off" || libraryCw
    /** Continue Watching also from the Trakt account (when one is connected). */
    var cwTrakt: Boolean get() = b("cwTrakt", true); set(v) = putB("cwTrakt", v)
    var cwStremio: Boolean get() = b("cwStremio", true); set(v) = putB("cwStremio", v)
    var cwSimkl: Boolean get() = b("cwSimkl", true); set(v) = putB("cwSimkl", v)
    /** What you watch on the Nuvio account (Legacy, the official app and forks push it there). */
    var cwNuvioAccount: Boolean get() = b("cwNuvioAccount", true); set(v) = putB("cwNuvioAccount", v)
    /** Catalogs from the add-ons on the Stremio account. */
    var catalogsStremio: Boolean get() = b("catalogsStremio", false); set(v) = putB("catalogsStremio", v)
    /** Jellyfin (#27): its libraries as catalogs, its titles in progress, its favourites in the Library, its titles in the search, Play opening it. */
    var catalogsJellyfin: Boolean get() = b("catalogsJellyfin", true); set(v) = putB("catalogsJellyfin", v)
    var cwJellyfin: Boolean get() = b("cwJellyfin", true); set(v) = putB("cwJellyfin", v)
    var libraryJellyfin: Boolean get() = b("libraryJellyfin", true); set(v) = putB("libraryJellyfin", v)
    var searchJellyfin: Boolean get() = b("searchJellyfin", true); set(v) = putB("searchJellyfin", v)
    var playJellyfin: Boolean get() = b("playJellyfin", true); set(v) = putB("playJellyfin", v)
    /** Continue Watching at the top of the Library: on, card shape (backdrop | poster), size, where it came from. */
    var libraryCw: Boolean get() = b("libraryCw", true); set(v) = putB("libraryCw", v)
    var libraryCwShape: String get() = s("libraryCwShape", "backdrop"); set(v) = putS("libraryCwShape", v)
    var libraryCwSize: Float get() = f("libraryCwSize", 1.0f); set(v) = putF("libraryCwSize", v.coerceIn(0.7f, 1.5f))
    /** Library posters: size (1 = fills the row), how many in a row, and the space between them (1 = normal). */
    var libraryPosterSize: Float get() = f("libraryPosterSize", 1.0f); set(v) = putF("libraryPosterSize", v.coerceIn(0.6f, 1.0f))
    var libraryColumns: Int get() = i("libraryColumns", 5); set(v) = putI("libraryColumns", v.coerceIn(3, 8))
    var librarySpacing: Float get() = f("librarySpacing", 1.2f); set(v) = putF("librarySpacing", v.coerceIn(0.5f, 2.0f))
    var libraryCwSource: Boolean get() = b("libraryCwSource", true); set(v) = putB("libraryCwSource", v)
    /** The Library's rows per streaming service: "" = the services whose app is installed, "off", or their keys with commas. */
    var libraryPlatforms: String get() = s("libraryPlatforms", ""); set(v) = putS("libraryPlatforms", v)
    /** Rows of posters per service there. */
    var libraryPlatformRows: Int get() = i("libraryPlatformRows", 1); set(v) = putI("libraryPlatformRows", v.coerceIn(1, 3))
    /** The Simkl "Plan to watch" list in the Library, and + saves there too. */
    var librarySimkl: Boolean get() = b("librarySimkl", true); set(v) = putB("librarySimkl", v)
    var libraryToSimkl: Boolean get() = b("libraryToSimkl", true); set(v) = putB("libraryToSimkl", v)
    /** How many titles in progress open the carousel (0 = all); the rest mix in with the catalogs. */
    var cwLead: Int get() = i("cwLead", 1); set(v) = putI("cwLead", v)
    /** After you leave the player, the carousel opens on what you just watched. */
    var continueJump: Boolean get() = b("continueJump", true); set(v) = putB("continueJump", v)
    /** Continue Watching is exactly Nuvio's (titles and progress), even with the other accounts syncing too. */
    var cwNuvioOnly: Boolean get() = b("cwNuvioOnly", false); set(v) = putB("cwNuvioOnly", v)
    var cwLegacy: Boolean get() = b("cwLegacy", true); set(v) = putB("cwLegacy", v)
    var cwProgress: Boolean get() = b("cwProgress", true); set(v) = putB("cwProgress", v)
    /** The next episode after one you finished joins Continue Watching (like Nuvio's "Next up"). */
    var cwNextUp: Boolean get() = b("cwNextUp", true); set(v) = putB("cwNextUp", v)
    /** 0.6 .. 1.0 (1.0 = full width) */
    var dockScale: Float get() = f("dockScale", 1.0f); set(v) = putF("dockScale", v.coerceIn(0.6f, 1.0f))
    /** Background of the dock and the Apps folder: liquid | clear | amoled | bow */
    var dockStyle: String get() = s("dockStyle", "water_still"); set(v) = putS("dockStyle", v)
    /** Background of settings and the icon picker: liquid | clear | amoled */
    var panelStyle: String get() = s("panelStyle", "clear"); set(v) = putS("panelStyle", v)
    /** liquid | clear | amoled | bow (black on white) */
    var tileStyle: String get() = s("tileStyle", "water_still"); set(v) = putS("tileStyle", v)
    /** How far the glass rim bends the background, dp. */
    var refraction: Int get() = i("refraction", 10); set(v) = putI("refraction", v.coerceIn(0, 40))
    var metaScale: Float get() = f("metaScale", 0.9f); set(v) = putF("metaScale", v.coerceIn(0.6f, 2.0f))
    var genresScale: Float get() = f("genresScale", 0.8f); set(v) = putF("genresScale", v.coerceIn(0.6f, 2.0f))
    /** Opaque dark plate behind every icon, so transparent pack icons don't show the poster through. */
    var tileBacking: Boolean get() = b("tileBacking", true); set(v) = putB("tileBacking", v)
    /** package name of the selected icon pack, "" = none */
    var iconPack: String get() = s("iconPack", ""); set(v) = putS("iconPack", v)
    /** Weather in the top bar: off | temp | condition | city | all */
    var weatherMode: String get() = s("weatherMode", "off"); set(v) = putS("weatherMode", v)
    // "" = automatic: the city of the TV's own time zone (Asia/Baghdad → Baghdad). It was "Baghdad" for everyone
    // who never typed one (#37: a TV in Canada showed Baghdad's weather).
    var weatherCity: String get() = s("weatherCity", ""); set(v) = putS("weatherCity", v.trim())
    /** The city the weather is for: the one typed, else the time zone's. */
    val weatherPlace: String get() = weatherCity.ifBlank { zoneCity() }
    var weatherF: Boolean get() = b("weatherF", false); set(v) = putB("weatherF", v)
    var showDate: Boolean get() = b("showDate", true); set(v) = putB("showDate", v)
    /** tvOS look: icons are white until selected, then show their colours on a light plate. */
    var focusColor: Boolean get() = b("focusColor", false); set(v) = putB("focusColor", v)
    /** The colour of the switches (and of a setting that's set and working) in Settings: burgundy, green, purple. */
    var settingsAccent: String get() = s("settingsAccent", "burgundy"); set(v) = putS("settingsAccent", v)
    /** Plate behind the selected icon in "Colour on focus": light | dark | glass | amoled (pure black) */
    var focusPlate: String get() = s("focusPlate", "glass"); set(v) = putS("focusPlate", v)
    /** Clock: left (Apple TV) | right (next to the settings button, weather under it) */
    var clockPos: String get() = s("clockPos", "right"); set(v) = putS("clockPos", v)
    /** Pressing further at the edge of the dock nudges the icon, like tvOS. */
    var edgeBounce: Boolean get() = b("edgeBounce", true); set(v) = putB("edgeBounce", v)
    /** The selected icon rises a little, like tvOS. */
    var tileLift: Boolean get() = b("tileLift", false); set(v) = putB("tileLift", v)
    var showClock: Boolean get() = b("showClock", false); set(v) = putB("showClock", v)
    /** The settings button at the top: its glyph (sliders, gear, dots, menu, person or "hello") and its frame (square, circle, none). */
    var settingsIcon: String get() = s("settingsIcon", "sliders"); set(v) = putS("settingsIcon", v)
    /** With Nuvio connected, the settings button becomes the Nuvio profile's picture by itself, once, while no look
     *  was ever chosen for it ([settingsIcon] never saved in this profile). A look chosen afterwards stays. */
    var settingsIconNuvio: Boolean get() = b("settingsIconNuvio", true); set(v) = putB("settingsIconNuvio", v)
    var settingsFrame: String get() = s("settingsFrame", "circle"); set(v) = putS("settingsFrame", v)
    var clock24h: Boolean get() = b("clock24h", false); set(v) = putB("clock24h", v)
    var blurRadius: Int get() = i("blurRadius", 8); set(v) = putI("blurRadius", v.coerceIn(0, 80))
    var glassTint: Int get() = i("glassTint", 0); set(v) = putI("glassTint", v.coerceIn(0, 40))
    /**
     * Glass over a video background (Videos): "bent" shows copies of the video in the glass (bent and blurred, about
     * 15 a second), "clear" lets the video itself show through (smooth and sharp like the large clock's digits, no
     * bending or blur, lighter). Only while the background is a video.
     */
    var videoGlass: String get() = s("videoGlass", "bent"); set(v) = putS("videoGlass", v)
    var showMobileApps: Boolean get() = b("showMobileApps", true); set(v) = putB("showMobileApps", v)
    var dockOrder: List<String> get() = list("dockOrder"); set(v) = putList("dockOrder", v)
    var hiddenApps: List<String> get() = list("hiddenApps"); set(v) = putList("hiddenApps", v)
    /** The Apps folder tile in the dock. */
    var showFolder: Boolean get() = b("showFolder", false); set(v) = putB("showFolder", v)
    /** With fewer apps than a full row, the dock shrinks around them. */
    var dockFit: Boolean get() = b("dockFit", true); set(v) = putB("dockFit", v)
    /** The dock hides below the screen and comes up when you go down to it. */
    var dockHide: Boolean get() = b("dockHide", false); set(v) = putB("dockHide", v)
    /** With your own background (picture, video, screensaver): bottom | center | left (a column on the left). */
    var dockPos: String get() = s("dockPos", "bottom"); set(v) = putS("dockPos", v)
    /** Updates: "stable" (releases) or "beta" (pre-releases too: new things first, may have bugs). */
    var updateChannel: String get() = s("updateChannel", "stable"); set(v) = putS("updateChannel", v)
    /** A title from Continue Watching opens in the app it was watched in (Nuvio Legacy, Nuvio, Stremio), not "Open titles in". */
    var cwOpenSource: Boolean get() = b("cwOpenSource", true); set(v) = putB("cwOpenSource", v)
    /** Play opens the title's page in its streaming app (Netflix, Apple TV, Prime Video, Disney+) when one installed has it. */
    /**
     * Play opens the title in its streaming app (Settings → Catalogs & add-ons → Play opens the streaming app): one
     * switch for a title of the carousel or the Library and for a title of an app's own carousel. The second had its
     * own switch ("appCatalogsPlay") for a few beta builds: a TV where that one was turned on (with Catalogs for
     * apps on) has this one on, so Play in an app's carousel goes on opening that app. Off clears both.
     */
    var playInApp: Boolean
        get() = (sp("playInApp").contains("playInApp") && b("playInApp", false)) ||
            (sp("appCatalogsPlay").contains("appCatalogsPlay") && b("appCatalogsPlay", false) && appCatalogsOn)
        set(v) { putB("playInApp", v); if (!v && sp("appCatalogsPlay").contains("appCatalogsPlay")) putB("appCatalogsPlay", false) }
    /** Catalogs for apps (see AppCatalogs): the switch, each app's catalogs ("package<TAB>catalog"), what stands in the carousel's place per app ("package=rows|catalogs|none"). */
    var appCatalogsOn: Boolean get() = b("appCatalogsOn", false); set(v) = putB("appCatalogsOn", v)
    var appCatalogs: List<String> get() = list("appCatalogs"); set(v) = putList("appCatalogs", v)
    var appStage: List<String> get() = list("appStage"); set(v) = putList("appStage", v)
    /** Titles in progress that belong to an app open its carousel: "start" or "off". */
    var appCatalogsCw: String get() = s("appCatalogsCw", "start"); set(v) = putS("appCatalogsCw", v)
    /** Not a setting of its own any more (see [playInApp]); the key stays for the TVs that have it saved. */
    var appCatalogsPlay: Boolean get() = b("appCatalogsPlay", true); set(v) = putB("appCatalogsPlay", v)
    /** The apps *Play opens the streaming app* leaves out (packages); empty = every one. */
    var appCatalogsPlayOff: List<String> get() = list("appCatalogsPlayOff"); set(v) = putList("appCatalogsPlayOff", v)
    /** Titles' links in the streaming apps may be looked up in JustWatch too (Wikidata is always asked first). */
    var linkJustWatch: Boolean get() = b("linkJustWatch", true); set(v) = putB("linkJustWatch", v)
    /** The country whose streaming offers are used (platform names, title links, platform rows): "" = the TV's own. */
    var watchCountry: String get() = s("watchCountry", ""); set(v) = putS("watchCountry", v.trim().uppercase())
    /** Apps in one row of the dock. */
    var dockColumns: Int get() = i("dockColumns", 7); set(v) = putI("dockColumns", v.coerceIn(4, 10))
    /** The dock as one strip from edge to edge: every app in it, the selected one in the middle, the strip moves. */
    var dockStrip: Boolean get() = b("dockStrip", true); set(v) = putB("dockStrip", v)
    /** No glass behind the dock: only the icons. */
    var dockGlassOff: Boolean get() = b("dockGlassOff", false); set(v) = putB("dockGlassOff", v)
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
    var idleSeconds: Int get() = if (sp("idleSeconds").contains("idleSeconds")) i("idleSeconds", 60) else if (sp("idleMinutes").contains("idleMinutes")) i("idleMinutes", 0) * 60 else 60
        set(v) = putI("idleSeconds", v.coerceAtLeast(0))
    /** aerial (Apple videos) | slideshow (posters with logos) | system (the TV's own screensaver, e.g. Aerial Views) */
    /** Every text in Glassio bolder (Menu & themes → Bold text). */
    /** A colour through every kind of glass: none | black | petrol (Menu & themes → Glass colour). */
    var glassColor: String get() = s("glassColor", "none"); set(v) = putS("glassColor", v)
    var glassColorAmount: Float get() = f("glassColorAmount", 0.2f); set(v) = putF("glassColorAmount", v.coerceIn(0.1f, 0.7f))
    /** Where Settings was left: its page and row (it opens there again). */
    var settingsLastCat: Int get() = i("settingsLastCat", 0); set(v) = putI("settingsLastCat", v)
    var settingsLastRow: Int get() = i("settingsLastRow", -1); set(v) = putI("settingsLastRow", v)
    /** Which order of the settings sections [settingsLastCat] counts in (1: the 19 sections before 4.0; see sectionAfterReorder). */
    /** Settings → New: the version whose new settings are marked, and the ones of them already opened. */
    var settingsNewFor: String get() = s("settingsNewFor", ""); set(v) = putS("settingsNewFor", v)
    var settingsNewSeen: List<String> get() = list("settingsNewSeen"); set(v) = putList("settingsNewSeen", v)
    var settingsLayout: Int get() = i("settingsLayout", 1); set(v) = putI("settingsLayout", v)
    /** How an HDMI input opens: auto (the TV's app, else Glassio's own) | tv | glassio. */
    var inputPlayer: String get() = s("inputPlayer", "auto"); set(v) = putS("inputPlayer", v)
    var boldText: Boolean get() = b("boldText", false); set(v) = putB("boldText", v)
    /** (The play build has no trailers: there they read as the posters.) */
    var saverMode: String get() = s("saverMode", "trailer").let { if (Store.PLAY && it == "trailer") "slideshow" else it }; set(v) = putS("saverMode", v)
    // Screensaver "Trailers": the carousel's titles' trailers, one after another.
    var saverTrailerMute: Boolean get() = b("saverTrailerMute", false); set(v) = putB("saverTrailerMute", v)
    var saverTrailerSize: String get() = s("saverTrailerSize", "cinema"); set(v) = putS("saverTrailerSize", v)
    var saverTrailerLogo: Boolean get() = b("saverTrailerLogo", true); set(v) = putB("saverTrailerLogo", v)
    var saverTrailerMeta: Boolean get() = b("saverTrailerMeta", true); set(v) = putB("saverTrailerMeta", v)
    var saverTrailerSynopsis: Boolean get() = b("saverTrailerSynopsis", false); set(v) = putB("saverTrailerSynopsis", v)
    /** Seconds the title's details stay after a trailer starts (0 = always). */
    var saverTrailerInfoSec: Int get() = i("saverTrailerInfoSec", 30); set(v) = putI("saverTrailerInfoSec", v.coerceAtLeast(0))
    var useAppleAerials: Boolean get() = b("useAppleAerials", false); set(v) = putB("useAppleAerials", v)
    var aerialUrls: List<String> get() = list("aerialUrls"); set(v) = putList("aerialUrls", v)
    var saverClock: Boolean get() = b("saverClock", true); set(v) = putB("saverClock", v)
    /** Collections of aerial videos that are on (apple, amazon, jetson, robin); see AerialSource. */
    var saverSources: List<String> get() = if (sp("saverSources").contains("saverSources")) list("saverSources").filter { it.isNotBlank() } else if (sp("useAppleAerials").contains("useAppleAerials")) (if (useAppleAerials) listOf("apple") else emptyList()) else listOf("amazon")
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
    var saverPlace: Boolean get() = b("saverPlace", false); set(v) = putB("saverPlace", v)
    var saverSys: Boolean get() = b("saverSys", false); set(v) = putB("saverSys", v)
    /** apple (clock in a corner, small text) | netflix (lines appear one by one at the top left, like the notes before a film) */
    var saverStyle: String get() = s("saverStyle", "apple"); set(v) = putS("saverStyle", v)
    /** bl | br | tl | tr | move (another corner with every video) */
    var saverPos: String get() = s("saverPos", "move"); set(v) = putS("saverPos", v)
    var saverSize: Float get() = f("saverSize", 1.0f); set(v) = putF("saverSize", v.coerceIn(0.6f, 1.8f))
    /** Seconds the info stays after each new video (0 = always). */
    var saverShowSec: Int get() = i("saverShowSec", 10); set(v) = putI("saverShowSec", v.coerceAtLeast(0))
    /** Netflix style: the lines go away a few seconds after the last one came in. */
    var saverNetflixHide: Boolean get() = b("saverNetflixHide", true); set(v) = putB("saverNetflixHide", v)
    /** Netflix style: the bar before each line — "red", "white", "black" or "off". */
    var saverBar: String get() = s("saverBar", "black"); set(v) = putS("saverBar", v)
    /** How far and how strong the glow is (0.4 … 1.6). */
    var glowSize: Float get() = f("glowSize", 1.6f); set(v) { putF("glowSize", v.coerceIn(0.3f, 1.6f)); com.glasslauncher.tv.ui.Glow.amount = v.coerceIn(0.3f, 1.6f) }
    /** Dock: on focus the whole tile grows ("tile"), only the icon inside the glass ("icon"), or nothing ("off"). */
    var tileZoom: String get() = s("tileZoom", "tile"); set(v) = putS("tileZoom", v)
    /** A very soft glow around the selected icon / poster: "off", "color" (its own colour) or "white". */
    var glow: String get() = s("glow", "color"); set(v) = putS("glow", v)
    /** Hold Home: "recents", "camera" (live camera over any app), "library", "menu" or "off". */
    /** Hold Home → camera: seconds before the corner camera closes on its own (0 = stays until closed). */
    /** Hold Home → camera: which camera opens (its link; blank = the first one in the list). */
    var cornerCamera: String get() = s("cornerCamera", ""); set(v) = putS("cornerCamera", v)
    /** Hold Home → camera: switch to the next camera every this many seconds (0 = stay on one). */
    var cameraCycleSec: Int get() = i("cameraCycleSec", 5); set(v) = putI("cameraCycleSec", if (v <= 0) 0 else v.coerceIn(5, 600))
    /** Doorbell pop-ups from Home Assistant (port 7979, PiPup's request). */
    var doorbellOn: Boolean get() = b("doorbellOn", true); set(v) = putB("doorbellOn", v)
    var doorbellScale: Float get() = f("doorbellScale", 1.0f); set(v) = putF("doorbellScale", v.coerceIn(0.6f, 1.6f))
    var doorbellText: Boolean get() = b("doorbellText", true); set(v) = putB("doorbellText", v)
    /** Optional: the doorbell pop-up only for requests carrying this (header X-Glassio-Token, or ?token=). Empty: anyone at home. */
    var doorbellToken: String get() = s("doorbellToken", ""); set(v) = putS("doorbellToken", v.trim())
    var doorbellTitle: String get() = s("doorbellTitle", "الباب الأمامي"); set(v) = putS("doorbellTitle", v)
    var doorbellMessage: String get() = s("doorbellMessage", "يوجد شخص عند الباب"); set(v) = putS("doorbellMessage", v)
    /** The doorbell's camera: a saved camera's link, or "" = the one Home Assistant sends. */
    var doorbellCamera: String get() = s("doorbellCamera", ""); set(v) = putS("doorbellCamera", v)
    /** Doorbell: go round the cameras every this many seconds (0 = stay on the doorbell camera). */
    var doorbellCycleSec: Int get() = i("doorbellCycleSec", 0); set(v) = putI("doorbellCycleSec", if (v <= 0) 0 else v.coerceIn(5, 600))
    /** Hold Home → camera: its size (1 = a third of the screen wide). */
    var cameraScale: Float get() = f("cameraScale", 1.0f); set(v) = putF("cameraScale", v.coerceIn(0.6f, 1.6f))
    /** auto | software | hardware: how the cameras' video is decoded (see CamPlayer). */
    var cameraDecoder: String get() = s("cameraDecoder", "auto"); set(v) = putS("cameraDecoder", v)
    /** Multi-view: up to four cameras in one row. The cameras (links) in it; empty = the first four. */
    var multiCams: List<String> get() = list("multiCams"); set(v) = putList("multiCams", v.take(4))
    var multiScale: Float get() = f("multiScale", 1.4f); set(v) = putF("multiScale", v.coerceIn(0.6f, 1.4f))
    /** The menu's Camera opens the cameras page (the whole screen) straight away, not the live card. */
    var cameraMenuFull: Boolean get() = b("cameraMenuFull", false); set(v) = putB("cameraMenuFull", v)
    /** The cameras page: the selected camera's sound plays by itself (only one at a time). */
    var cameraSoundFollows: Boolean get() = b("cameraSoundFollows", false); set(v) = putB("cameraSoundFollows", v)
    /** Multi-view's order: "auto" (one row when it fits at that size, else two by two), "row", "grid" (two by two). */
    var multiLayout: String get() = s("multiLayout", "auto"); set(v) = putS("multiLayout", v)
    /** Seconds it stays (0 = until closed). */
    /** Multi-view picture: "auto" (the cameras' full streams where the TV decodes in hardware), "high", "light". */
    var multiQuality: String get() = s("multiQuality", "high"); set(v) = putS("multiQuality", v)
    var multiSec: Int get() = i("multiSec", 30); set(v) = putI("multiSec", v.coerceIn(0, 600))
    /** On the screensaver: ▲ brings the multi-view up from the bottom. */
    var saverMultiCam: Boolean get() = b("saverMultiCam", true); set(v) = putB("saverMultiCam", v)
    /** Remote buttons (any app): multi-view and the corner camera. 0 = none. */
    var multiKey: Int get() = i("multiKey", 0); set(v) = putI("multiKey", v)
    var cornerKey: Int get() = i("cornerKey", 0); set(v) = putI("cornerKey", v)
    /** Remote buttons that open an app, the Apps folder, the recent apps or an input ([RemoteMap]). */
    var keyMaps: List<String> get() = list("keyMaps"); set(v) = putList("keyMaps", v)
    /** "raw button id=package": the app the TV itself opens for a raw button ([RawKeys]), closed when it is pressed. */
    var rawLaunches: List<String> get() = list("rawLaunches"); set(v) = putList("rawLaunches", v)
    var doorbellChime: Boolean get() = b("doorbellChime", true); set(v) = putB("doorbellChime", v)
    /** Doorbell straight from Home Assistant (no automation): its link, a long-lived token, and the sensor to watch. */
    var haUrl: String get() = s("haUrl", ""); set(v) = putS("haUrl", v.trim().trimEnd('/'))
    var haToken: String get() = s("haToken", ""); set(v) = putS("haToken", v.trim())
    /** Nuvio Legacy's playback sent to Home Assistant as it happens (event "glassio_nuvio"). */
    var nuvioPush: Boolean get() = b("nuvioPush", false); set(v) = putB("nuvioPush", v)
    /** The key Home Assistant sends with Nuvio's controls (X-Relay-Token), port 9091. */
    var nuvioToken: String get() = s("nuvioToken", ""); set(v) = putS("nuvioToken", v.trim())
    var haDoorbellEntity: String get() = s("haDoorbellEntity", ""); set(v) = putS("haDoorbellEntity", v.trim())
    var doorbellSec: Int get() = i("doorbellSec", 20); set(v) = putI("doorbellSec", v.coerceIn(5, 300))
    var cameraCornerSec: Int get() = i("cameraCornerSec", 30); set(v) = putI("cameraCornerSec", v.coerceIn(0, 600))
    var homeLong: String get() = s("homeLong", if (b("homeRecents", false)) "recents" else "off"); set(v) = putS("homeLong", v)
    /** Ask before removing a title from the Library. */
    var libraryConfirm: Boolean get() = b("libraryConfirm", true); set(v) = putB("libraryConfirm", v)
    /** Background video: a new one when each ends (else the same one loops). */
    var bgShuffle: Boolean get() = b("bgShuffle", false); set(v) = putB("bgShuffle", v)
    /** Screensaver as background: "1080" (light behind the glass) or "saver" (the screensaver's quality and its saved videos). */
    var bgAerialQuality: String get() = s("bgAerialQuality", "1080"); set(v) = putS("bgAerialQuality", v)
    /** Background "Photos": where they come from (mine = the backgrounds folder, bing, wallhaven, unsplash). */
    var photoSource: String get() = s("photoSource", "bing").let { if (it == "wallhaven") "commons" else it }; set(v) = putS("photoSource", v)
    /** What the photos show (Wallhaven and Unsplash search for it). */
    var photoTopic: String get() = s("photoTopic", "architecture"); set(v) = putS("photoTopic", v)
    /** A new photo every this many seconds (0 = keep the one on screen). */
    var photoEvery: Int get() = i("photoEvery", 60); set(v) = putI("photoEvery", if (v <= 0) 0 else v.coerceIn(30, 86_400))
    /** Photos in a random order (else in the source's order). */
    var photoShuffle: Boolean get() = b("photoShuffle", true); set(v) = putB("photoShuffle", v)
    /** Pexels' own API key (pexels.com/api, free): its videos in the screensaver's collections. The TV's. */
    var pexelsKey: String get() = s("pexelsKey", ""); set(v) = putS("pexelsKey", v.trim())
    var pexelsQuery: String get() = s("pexelsQuery", "aerial"); set(v) = putS("pexelsQuery", v.trim())
    /** The screensaver starts with the video the aerial background is showing, and the background goes on with the one the screensaver ended on. */
    var saverSameVideo: Boolean get() = b("saverSameVideo", true); set(v) = putB("saverSameVideo", v)
    /** The screensaver drawn on the home screen itself (no screen of its own): see HomeSaver. */
    // (One player is Glassio's own screensaver: turned on while the type is the TV's screensaver app, which is another
    // process and never plays on the home screen, the type becomes Videos. Before, the row was hidden then and the
    // phone page's switch did nothing: #40.)
    var saverInHome: Boolean get() = b("saverInHome", true); set(v) { putB("saverInHome", v); if (v && saverMode == "system") saverMode = "aerial" }
    /** YouTube links of My videos left out for now (kept in the list, not played). */
    var saverYtOff: List<String> get() = list("saverYtOff"); set(v) = putList("saverYtOff", v)
    /** A TV with less than 3 GB of memory (an onn 4K Pro, a Chromecast HD): what saves memory is on from the start there. */
    private val smallTv by lazy {
        runCatching {
            val mi = android.app.ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
            mi.totalMem < 3L * 1024 * 1024 * 1024
        }.getOrDefault(false)
    }
    /** The background video is let go while Glassio is away (another app, the screensaver), taken again on return. */
    var bgLetGo: Boolean get() = b("bgLetGo", smallTv); set(v) = putB("bgLetGo", v)
    /** An app's single row sits right above the dock (the large clock stays in view) instead of in the middle. */
    var channelsLow: Boolean get() = b("channelsLow", false); set(v) = putB("channelsLow", v)
    var unsplashKey: String get() = s("unsplashKey", ""); set(v) = putS("unsplashKey", v.trim())
    /** The photo on screen (url, place, credit), so it comes back at once after a restart. */
    var photoLast: String get() = sp("photoLast").getString("photoLast", "") ?: ""; set(v) { sp("photoLast").edit().putString("photoLast", v).apply() }
    /** Your own background: the place / title of the video or photo, like the screensaver. */
    var bgInfo: Boolean get() = b("bgInfo", false); set(v) = putB("bgInfo", v)
    var bgInfoPos: String get() = s("bgInfoPos", "tl"); set(v) = putS("bgInfoPos", v)
    /** Shown for this many seconds after each change (0 = always). */
    var bgInfoSec: Int get() = i("bgInfoSec", 10); set(v) = putI("bgInfoSec", v.coerceIn(0, 600))
    /** Your own background: a large clock in the middle, like a phone's lock screen. */
    var bgClock: Boolean get() = b("bgClock", true); set(v) = putB("bgClock", v)
    /** Your own background: Continue Watching as small glass cards above the dock (off: nothing there, as before). */
    var bgContinue: Boolean get() = b("bgContinue", false); set(v) = putB("bgContinue", v)
    /** With the cards on: an app's own "Continue watching" row shows as cards too, instead of its rows (but the apps in [bgContinueAppsOff]). */
    var bgContinueApps: Boolean get() = b("bgContinueApps", true); set(v) = putB("bgContinueApps", v)
    var bgContinueAppsOff: List<String> get() = list("bgContinueAppsOff"); set(v) = putList("bgContinueAppsOff", v)
    var bgContinueCount: Int get() = i("bgContinueCount", 3).coerceIn(1, 4); set(v) = putI("bgContinueCount", v.coerceIn(1, 4))
    var bgClockSize: Float get() = f("bgClockSize", 0.8f); set(v) = putF("bgClockSize", v.coerceIn(0.6f, 1.6f))
    /** Depth effect: on a photo, what's in front (a mountain, a person) covers the large clock. */
    var bgDepth: Boolean get() = b("bgDepth", true); set(v) = putB("bgDepth", v)
    /** Sharp corners for the menu, the dock and the icons (no rounding). */
    var squareCorners: Boolean get() = b("squareCorners", false); set(v) = putB("squareCorners", v)
    /** Large clock: "theme" (glass numerals in the glass themes, black on Black on white) or "white". */
    var bgClockStyle: String get() = s("bgClockStyle", "theme"); set(v) = putS("bgClockStyle", v)
    /** How much of the picture the large clock's glass digits show, 10–100 % (lower: clearer glass, mostly its rim). */
    var bgClockGlass: Int get() = i("bgClockGlass", 20); set(v) = putI("bgClockGlass", v.coerceIn(10, 100))
    /** The large clock's date in white, whatever the time's look. */
    var bgClockDateWhite: Boolean get() = b("bgClockDateWhite", true); set(v) = putB("bgClockDateWhite", v)
    /** The large clock on the screensaver too (Down shows / hides it). */
    var saverBigClock: Boolean get() = b("saverBigClock", false); set(v) = putB("saverBigClock", v)
    /** Lock & release on the screensaver: hold OK on a video to keep it (and again to let it go). */
    var saverLock: Boolean get() = b("saverLock", true); set(v) = putB("saverLock", v)
    /** The screensaver's large clock was left shown (true) or hidden (▲ on the remote). */
    var saverClockShown: Boolean get() = b("saverClockShown", false); set(v) = putB("saverClockShown", v)
    /** The music island at the top of the home screen too (when Music mode is off). */
    var islandHome: Boolean get() = b("islandHome", true); set(v) = putB("islandHome", v)
    /** The locked videos (their ids). */
    var saverLocked: List<String> get() = list("saverLocked"); set(v) = putList("saverLocked", v)
    /** Videos never shown again, as "id<TAB>title" (hold OK on one in the screensaver, with *Hold OK asks*). */
    var saverHidden: List<String> get() = list("saverHidden"); set(v) = putList("saverHidden", v)
    /** Hold OK on a screensaver video: off = locks it (as always), on = a small menu: keep it, or never show it again. */
    var saverHoldMenu: Boolean get() = b("saverHoldMenu", true); set(v) = putB("saverHoldMenu", v)
    /** Only the locked videos play (when there are some). */
    var saverLockedOnly: Boolean get() = b("saverLockedOnly", false); set(v) = putB("saverLockedOnly", v)
    /** Large clock: moved up (negative) or down, as a part of the screen's height. */
    var bgClockOffset: Float get() = f("bgClockOffset", -0.06f); set(v) = putF("bgClockOffset", v.coerceIn(-0.2f, 0.3f))
    /** My video / My picture: go round the others in the folder (else the chosen one stays). */
    var bgVideoShuffle: Boolean get() = b("bgVideoShuffle", false); set(v) = putB("bgVideoShuffle", v)
    /**
     * Videos as the background (one choice since "My video" and "Screensaver" were merged): "all" the videos of the
     * library (the collections and My videos, as the screensaver has them) or only "mine" (My videos: the folder,
     * the links, YouTube). "One video" is bgMode "video" (the chosen one, looping).
     */
    var bgVideos: String get() = s("bgVideos", "all"); set(v) = putS("bgVideos", v)

    /**
     * Once: "My video" with *Shuffle my videos* (the folder's videos one after another) is "Videos · My videos" now.
     * "My video" without it stays as it is ("One video").
     */
    fun mergeVideos() {
        val k = "videosMerged"
        if (sp(k).getBoolean(k, false)) return
        if (bgMode == "video" && bgVideoShuffle) { bgVideos = "mine"; bgMode = "aerial" }
        sp(k).edit().putBoolean(k, true).apply()
    }
    var bgImageShuffle: Boolean get() = b("bgImageShuffle", true); set(v) = putB("bgImageShuffle", v)
    /** App lock: your own reminder under the digits (anything, the code itself too), and whether it shows. */
    var lockHintOn: Boolean get() = b("lockHintOn", false); set(v) = putB("lockHintOn", v)
    var lockHint: String get() = s("lockHint", ""); set(v) = putS("lockHint", v)
    /** The info over your background, like the screensaver's: style, which lines, corner, how long. */
    var bgInfoStyle: String get() = s("bgInfoStyle", "netflix"); set(v) = putS("bgInfoStyle", v)
    var bgInfoClock: Boolean get() = b("bgInfoClock", false); set(v) = putB("bgInfoClock", v)
    var bgInfoDate: Boolean get() = b("bgInfoDate", false); set(v) = putB("bgInfoDate", v)
    var bgInfoWeather: Boolean get() = b("bgInfoWeather", false); set(v) = putB("bgInfoWeather", v)
    /** Pictures kept on the TV for the Photos background (oldest go first), in MB. */
    var photoCacheMb: Int get() = i("photoCacheMb", 1024); set(v) = putI("photoCacheMb", v.coerceIn(100, 8192))
    /** Apps that ask for the 4-digit code when opened from Glassio. */
    var lockedApps: List<String> get() = list("lockedApps"); set(v) = putList("lockedApps", v)
    // Channels (Settings → Channels): an app's rows (Continue watching, New…) in the carousel's place while it is selected.
    var channelsOn: Boolean get() = b("channelsOn", false); set(v) = putB("channelsOn", v)
    /** Rows shown at once (1 or 2); the others come in with Up / Down. */
    var channelsRows: Int get() = i("channelsRows", 2).coerceIn(1, 2); set(v) = putI("channelsRows", v.coerceIn(1, 2))
    /** Cards that fit across a row at first (wide cards; posters are narrower, so more of them show). */
    var channelsCards: Int get() = i("channelsCards", 3); set(v) = putI("channelsCards", v.coerceIn(3, 8))
    /** The selected card's soft light, like the Library's: off, white or in the card's own colour. */
    var channelsGlow: String get() = s("channelsGlow", "color"); set(v) = putS("channelsGlow", v)
    /** Behind the rows: the Library's grey page, or the selected card's picture blurred (like the music page). */
    var channelsBg: String get() = s("channelsBg", "blur"); set(v) = putS("channelsBg", v)
    /** The page behind the music apps, an idle player and the inputs: "grey", "glassio" (Glassio's soft lights) or "carousel" (the carousel's picture, blurred). */
    var stagePageBg: String get() = s("stagePageBg", "carousel"); set(v) = putS("stagePageBg", v)

    // ---- Search (Settings → Continue Watching & Library → Search). Off: nothing of it exists anywhere.
    var searchOn: Boolean get() = b("searchOn", true); set(v) = putB("searchOn", v)
    /** Its icon in the dock (the search bar comes up in the carousel's place while it is selected). */
    var searchDock: Boolean get() = b("searchDock", true); set(v) = putB("searchDock", v)
    var searchMovies: Boolean get() = b("searchMovies", true); set(v) = putB("searchMovies", v)
    var searchSeries: Boolean get() = b("searchSeries", true); set(v) = putB("searchSeries", v)
    /**
     * OK on a title: "list" (where to watch it), "first" (the first place in the list's order, at once), or one
     * app's key (a service's package, or NuvioLaunch's key: "legacy", "stremio" …): straight into that app, the
     * list when it can't open the title.
     */
    var searchOk: String get() = s("searchOk", "list"); set(v) = putS("searchOk", v)
    /** The streaming services' apps in the list (Netflix, Apple TV …), and the ones left out (packages). */
    var searchServices: Boolean get() = b("searchServices", true); set(v) = putB("searchServices", v)
    var searchServicesOff: List<String> get() = list("searchServicesOff"); set(v) = putList("searchServicesOff", v)
    /** Nuvio, its forks and Stremio in the list, and the ones left out (NuvioLaunch's keys: "legacy", "stremio" …). */
    var searchPlayers: Boolean get() = b("searchPlayers", true); set(v) = putB("searchPlayers", v)
    var searchPlayersOff: List<String> get() = list("searchPlayersOff"); set(v) = putList("searchPlayersOff", v)
    /** A Nuvio app from the search: its page for the title (false) or playing at once where the app can (true). */
    var searchNuvioPlay: Boolean get() = b("searchNuvioPlay", false); set(v) = putB("searchNuvioPlay", v)
    /** Only titles that one of the services' apps on this TV has. */
    var searchMine: Boolean get() = b("searchMine", false); set(v) = putB("searchMine", v)
    /** A film or series in the search: "poster" (tall) or "card" (wide, its backdrop). The name and the rest are inside the picture either way. */
    var searchShape: String get() = s("searchShape", "card"); set(v) = putS("searchShape", v)
    /** The bar starts empty each time the search is opened (true), or keeps what was typed last. */
    var searchFresh: Boolean get() = b("searchFresh", true); set(v) = putB("searchFresh", v)
    /** Before anything is typed: what is popular this week (needs the TMDB token). */
    // (Off from the start on a small TV: the search page comes up lighter, with nothing until you type.)
    var searchTrending: Boolean get() = b("searchTrending", !smallTv); set(v) = putB("searchTrending", v)
    /** A title's rating, the number alone, faint, in its line with the year and Film / Series. */
    var searchRating: Boolean get() = b("searchRating", true); set(v) = putB("searchRating", v)
    /** In the list of places: "services" first (default) or the Nuvio apps and Stremio first ("players"). */
    var searchOrder: String get() = s("searchOrder", "services"); set(v) = putS("searchOrder", v)
    /** Videos from YouTube under the titles, and the app one opens in: "auto" (SmartTube when it is there, else YouTube) or a package. */
    var searchYoutube: Boolean get() = b("searchYoutube", true); set(v) = putB("searchYoutube", v)
    var searchYoutubeApp: String get() = s("searchYoutubeApp", "auto"); set(v) = putS("searchYoutubeApp", v)
    /** Songs: SoundCloud's tracks and a way into Spotify's own search; the apps left out ("spotify", "soundcloud"). */
    var searchMusic: Boolean get() = b("searchMusic", true); set(v) = putB("searchMusic", v)
    var searchCardSize: String get() = s("searchCardSize", "large"); set(v) = putS("searchCardSize", v)
    var searchFirstRow: String get() = s("searchFirstRow", "titles"); set(v) = putS("searchFirstRow", v)
    var searchMore: Boolean get() = b("searchMore", true); set(v) = putB("searchMore", v)
    var searchStay: Boolean get() = b("searchStay", false); set(v) = putB("searchStay", v)
    var searchMusicStay: Boolean get() = b("searchMusicStay", true); set(v) = putB("searchMusicStay", v)
    var searchMusicOff: List<String> get() = list("searchMusicOff"); set(v) = putList("searchMusicOff", v)
    /** A Search row in the side menu. */
    var menuSearch: Boolean get() = b("menuSearch", true); set(v) = putB("menuSearch", v)
    /** "Manage from phone" in the side menu: the phone page's QR code in two presses (#33). */
    var menuPhone: Boolean get() = b("menuPhone", true); set(v) = putB("menuPhone", v)
    /** The words searched before (newest first, one a line), and whether they are kept at all. */
    var searchRecent: Boolean get() = b("searchRecent", true); set(v) = putB("searchRecent", v)
    var searchHistory: List<String> get() = list("searchHistory"); set(v) = putList("searchHistory", v)
    /** Stremio from the search: "list" (the add-ons' sources, chosen here), "first" (the first that fits the rules), "page" (its own page). */
    var stremioSources: String get() = s("stremioSources", "list"); set(v) = putS("stremioSources", v)
    /** The rules for Stremio's sources: 0 = any, else 2160 / 1080 / 720 (that quality first, then the nearest below). */
    var stremioQuality: Int get() = i("stremioQuality", 0); set(v) = putI("stremioQuality", v)
    /** Largest file in GB, 0 = no limit. */
    var stremioMaxGb: Int get() = i("stremioMaxGb", 0); set(v) = putI("stremioMaxGb", v)
    /** "any", "prefer" or "avoid" HDR / Dolby Vision. */
    var stremioHdr: String get() = s("stremioHdr", "any"); set(v) = putS("stremioHdr", v)
    var stremioNoCam: Boolean get() = b("stremioNoCam", true); set(v) = putB("stremioNoCam", v)
    /** Torrents among the sources. Off by default: Stremio on a TV plays them only with its streaming server running (seen: connection refused without it). */
    var stremioP2p: Boolean get() = b("stremioP2p", false); set(v) = putB("stremioP2p", v)
    /** Add-ons whose sources are left out (their manifest addresses). */
    var stremioAddonsOff: List<String> get() = list("stremioAddonsOff"); set(v) = putList("stremioAddonsOff", v)
    /** Up to three rows one above the other ("stack"), or one row in the middle that Up / Down switch ("single"). */
    var channelsLayout: String get() = s("channelsLayout", "single"); set(v) = putS("channelsLayout", v)
    var channelsGlowSize: Float get() = f("channelsGlowSize", 1.4f); set(v) = putF("channelsGlowSize", v.coerceIn(0.3f, 1.6f))
    /** The app's name over the rows: white, or in its icon's colour ("icon"). */
    var channelsLabel: String get() = s("channelsLabel", "icon"); set(v) = putS("channelsLabel", v)
    /** Hue: the lights take the colour of the card selected in the rows. */
    var channelsHue: Boolean get() = b("channelsHue", false); set(v) = putB("channelsHue", v)
    /** The music app that played last (the TV's own Play button brings that one back). */
    var musicLastPkg: String get() = s("musicLastPkg", ""); set(v) = putS("musicLastPkg", v)
    /** Music apps that show their rows (like other apps) when selected in the dock, instead of their player. */
    var channelsMusicRowApps: List<String> get() = list("channelsMusicRowApps"); set(v) = putList("channelsMusicRowApps", v)
    /** One Inputs icon in the dock: the TV's inputs above it (like the Music icon's music apps). */
    var inputsIcon: Boolean get() = b("inputsIcon", false); set(v) = putB("inputsIcon", v)
    /** Inputs left out of the Inputs icon's page, and its order (ids as "input:…"). */
    var inputsHidden: List<String> get() = list("inputsHidden"); set(v) = putList("inputsHidden", v)
    var inputsOrder: List<String> get() = list("inputsOrder"); set(v) = putList("inputsOrder", v)
    /** The island: a song name too long for it scrolls by (else it is shortened with "…"). */
    var islandMarquee: Boolean get() = b("islandMarquee", true); set(v) = putB("islandMarquee", v)
    /** Seconds a paused song stays in the island before it goes (0 at once, -1 never; #49). */
    /** After a crash, its report goes without asking (off: the next start asks, with the report to read first). */
    var crashAutoSend: Boolean get() = b("crashAutoSend", false); set(v) = putB("crashAutoSend", v)
    /**
     * An HEVC video failed to decode on this TV (#40, #53: a Fire TV's green screensaver): from then on the background
     * and the screensaver play only H.264 files here (Amazon's collection, HEVC only, is left out). The TV's own; set
     * back from Settings → Background & screensaver (*HEVC videos*).
     */
    var hevcFailed: Boolean get() = b("hevcFailed", false); set(v) = putB("hevcFailed", v)
    var islandPausedSec: Int get() = i("islandPausedSec", 20); set(v) = putI("islandPausedSec", v)
    /** Photos from subreddits: their names (comma separated), and the Reddit app id that lets Glassio in. */
    var photoSubreddit: String get() = s("photoSubreddit", "EarthPorn"); set(v) = putS("photoSubreddit", v.trim())
    var redditAppId: String get() = s("redditAppId", ""); set(v) = putS("redditAppId", v.trim())
    /** With your own background: no shade along the left side (the carousel keeps it, its text needs it). */
    var sideShadeOff: Boolean get() = b("sideShadeOff", true); set(v) = putB("sideShadeOff", v)
    /** The carousel's dark shades (left, behind the title and buttons, and bottom, above the dock): "on" or "off". */
    var carouselShade: String get() = s("carouselShade", "on"); set(v) = putS("carouselShade", v)
    /** Backups carry the sign-ins and keys (to move them to another TV). Off: they stay on each TV. */
    var backupSignIns: Boolean get() = b("backupSignIns", true); set(v) = putB("backupSignIns", v)
    /** How bright the blurred card behind the rows is, in % (100: as it was). */
    var channelsBlurBri: Int get() = i("channelsBlurBri", 110); set(v) = putI("channelsBlurBri", v.coerceIn(50, 150))
    /** A music app selected with nothing playing: its player (its icon and Play). Off: its rows, or the carousel. */
    var musicIdleOn: Boolean get() = b("musicIdleOn", true); set(v) = putB("musicIdleOn", v)
    /** Music apps without that idle player (when it is on for the others). */
    var musicIdleOffApps: List<String> get() = list("musicIdleOffApps"); set(v) = putList("musicIdleOffApps", v)
    /** Apps whose rows don't show. */
    var channelsOffApps: List<String> get() = list("channelsOffApps"); set(v) = putList("channelsOffApps", v)
    /** "app=other app" lines: the dock app shows the other app's rows (its own: "app=app"). See Channels.sourceOf. */
    var channelsFrom: List<String> get() = list("channelsFrom"); set(v) = putList("channelsFrom", v)
    /** Rows that don't show ("package|name"), and your order of the rows. */
    var channelsHidden: List<String> get() = list("channelsHidden"); set(v) = putList("channelsHidden", v)
    var channelsOrder: List<String> get() = list("channelsOrder"); set(v) = putList("channelsOrder", v)
    var lockCode: String get() = s("lockCode", "0000"); set(v) = putS("lockCode", v.filter { it.isDigit() }.take(4).padStart(4, '0'))

    /** Existing installs skip the first-run setup (it is for new TVs). */
    fun firstRun() { if (!dev.contains("setupDone")) putB("setupDone", dev.all.size > 8) }

    /** Testing over adb (see DevHooks): key=value — true/false, a number, or text; lists with \n. */
    /** QA: a setting as if it was never saved (its default again). */
    fun debugUnset(k: String) { val sp = sp(k); if (sp.contains(k)) { sp.edit().remove(k).apply(); wrote(k) } }

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
    var interceptHome: Boolean get() = b("interceptHome", true); set(v) = putB("interceptHome", v)
    /** The play build: the person read what the accessibility service is for and agreed (it does nothing before). */
    var a11yConsent: Boolean get() = b("a11yConsent", false); set(v) = putB("a11yConsent", v)
    /** Opens Glassio when the TV starts (needs the "display over other apps" right, given over ADB). */
    var bootStart: Boolean get() = b("bootStart", true); set(v) = putB("bootStart", v)
    /** Manage from phone: minutes the page stays on after you leave that screen (0 = stops at once). */
    var phoneMinutes: Int get() = i("phoneMinutes", 180); set(v) = putI("phoneMinutes", v.coerceIn(0, 720))
    /** The phone page always on, with the same address and code (a web app on the phone's home screen keeps working). On by default (his choice). */
    var phoneAlways: Boolean get() = b("phoneAlways", true); set(v) = putB("phoneAlways", v)
    /** Settings' search shows the phone page's QR code while nothing is typed (#33). */
    var searchPhoneQr: Boolean get() = b("searchPhoneQr", true); set(v) = putB("searchPhoneQr", v)
    /** App language, a key of I18n.LANGS. */
    var lang: String get() = s("lang", "en"); set(v) { putS("lang", v); I18n.lang = v }
    /** The first-run setup was finished (or skipped with Later). */
    var setupDone: Boolean get() = b("setupDone", false); set(v) = putB("setupDone", v)
    /** The What's new tour last seen (WhatsNew.ID). */
    var tourSeen: Int get() = i("tourSeen", 0); set(v) = putI("tourSeen", v)
}

/** The city in the TV's time zone's name ("America/New_York" → "New York"), or "" for zones without one (UTC). */
fun zoneCity(zone: String = java.util.TimeZone.getDefault().id): String =
    zone.takeIf { '/' in it && !it.startsWith("Etc/") }?.substringAfterLast('/')?.replace('_', ' ').orEmpty()
