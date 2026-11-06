package com.glasslauncher.tv

import android.app.Application
import com.glasslauncher.tv.data.AppRepository
import com.glasslauncher.tv.data.CatalogRepository
import com.glasslauncher.tv.data.Http
import com.glasslauncher.tv.data.IconPacks
import com.glasslauncher.tv.data.IconStore
import com.glasslauncher.tv.data.Settings
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class GlassApp : Application(), coil3.SingletonImageLoader.Factory {
    /** Bigger caches than Coil's defaults: backdrops and logos come back instantly when you browse back. */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            // Thumbnails of your videos (backgrounds folder, screensaver).
            .components {
                add(coil3.video.VideoFrameDecoder.Factory())
                // More downloads at once from one picture site (OkHttp allows 5): logos no longer wait behind backdrops.
                add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = {
                    okhttp3.OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequests = 32; maxRequestsPerHost = 12 }).build()
                }))
            }
            // Pictures decoded two at a time: decoding on every core at once (the titles around, their glass copies,
            // the logos, the Library) took the whole processor for seconds after start-up, and the home screen
            // stuttered meanwhile. The one on screen still comes first (the others wait behind it).
            .decoderCoroutineContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(2))
            // A fixed size by memory tier: a percentage of the (large) heap gave Coil over 100 MB on 2 GB TVs,
            // and Android then closed Glassio while you browsed.
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizeBytes(when (settings.memTier) { 0 -> 40L; 2 -> 120L; else -> 72L } * 1024 * 1024).build() }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(java.io.File(cacheDir, "images").absolutePath.let { okio.Path.Companion.run { it.toPath() } })
                    .maxSizeBytes(com.glasslauncher.tv.data.Storage.pictureCacheLimit(java.io.File(cacheDir, "images"), settings.cacheMb.toLong() * 1024 * 1024))
                    .build()
            }
            .build()

    /**
     * The TV is short of memory: drop pictures that can be made again, so Android doesn't have to close Glassio
     * (coming back to a closed launcher rebuilds the whole page).
     * Only when memory is really short: "UI hidden" (level 20) just means another app is in front, and clearing
     * everything then made every return to the home screen decode its pictures again.
     */
    /** Activities of Glassio on screen; when 0 another app is in front. */
    @Volatile private var shown = 0
    @Volatile private var hiddenAt = 0L
    private val smallTv by lazy {
        val mi = android.app.ActivityManager.MemoryInfo().also { (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(it) }
        mi.totalMem < 3L * 1024 * 1024 * 1024
    }
    private fun releasePictures(why: String) {
        com.glasslauncher.tv.data.Glog.w("Freeing picture caches ($why)")
        runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
        com.glasslauncher.tv.ui.GlassCache.trim()
        com.glasslauncher.tv.ui.trimPageCaches()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) hiddenAt = System.currentTimeMillis()
        @Suppress("DEPRECATION") when {
            // In the background and Android is looking for memory: everything that can be made again goes.
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                com.glasslauncher.tv.data.Glog.w("Low memory in the background (level $level): clearing picture caches")
                runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
                com.glasslauncher.tv.ui.GlassCache.trim()
                com.glasslauncher.tv.ui.trimPageCaches()
            }
            // Another app in front. Glassio's home-key service keeps it running, so Android never asks it for
            // memory the way it asks a closed app: on a small TV (2 GB) its pictures stayed while you watched, and the
            // TV swapped and stuttered. There they go after a minute away (a quick trip out and back keeps them).
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> if (settings.memTier == 0 || smallTv) {
                val at = hiddenAt; scope.launch {
                    kotlinx.coroutines.delay(60_000)
                    if (shown == 0 && hiddenAt == at) releasePictures("in the background")
                }
            }
            // In front and memory is getting short (older Android): half the decoded pictures, the screen stays.
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                com.glasslauncher.tv.data.Glog.w("Low memory (level $level): halving the picture cache")
                runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.let { it.trimToSize(it.maxSize / 2) } }
            }
        }
    }

    /** Bumped when Home is pressed while Glass is already in front: the carousel goes back to its start. */
    val homeRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Bumped by the shuffle button: the carousel's catalog titles in a new random order. */
    val shuffleRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Last Left/Right in the carousel: while browsing, slides switch at once and fades are short. */
    @Volatile var navAt = 0L
    fun browsing() = System.currentTimeMillis() - navAt < 1200

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings; private set
    lateinit var http: Http; private set
    lateinit var icons: IconStore; private set
    lateinit var packs: IconPacks; private set
    lateinit var apps: AppRepository; private set
    /** Bumped on user input (throttled) so the clock / settings button can auto-hide. */
    val interaction = mutableLongStateOf(0L)
    /** Set by `am start ... --ez open_folder true`: opens the Apps folder (testing without the remote). */
    val requestFolder = androidx.compose.runtime.mutableStateOf(false)
    /** Set by `am start ... --ei open_settings <tab>`: opens Settings on that tab. */
    val requestSettings = androidx.compose.runtime.mutableIntStateOf(-1)
    /** Bumped with every request, so asking for the same tab twice (or while the screen was asleep) still works. */
    val requestSettingsRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Bumped to show the first-run setup again (System → Run setup again). */
    val requestSetup = androidx.compose.runtime.mutableIntStateOf(0)
    fun touch() {
        val n = System.currentTimeMillis()
        if (n - interaction.longValue > 400) interaction.longValue = n
    }
    lateinit var catalogs: CatalogRepository; private set
    lateinit var cw: com.glasslauncher.tv.data.ContinueWatching; private set
    lateinit var weather: com.glasslauncher.tv.data.Weather; private set
    lateinit var updater: com.glasslauncher.tv.data.Updater; private set
    lateinit var library: com.glasslauncher.tv.data.Library; private set
    lateinit var sync: com.glasslauncher.tv.data.Sync; private set

    override fun onCreate() {
        super.onCreate()
        // A launcher must never vanish silently: keep the last crash for Settings → About.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "last-crash.txt").writeText(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + "\n" + e.stackTraceToString().take(4000))
            }
            previous?.uncaughtException(t, e)
        }
        settings = Settings(this)
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { shown++ }
            override fun onActivityStopped(a: android.app.Activity) { shown = (shown - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
        com.glasslauncher.tv.data.I18n.lang = settings.lang
        settings.firstRun()
        Thread { runCatching { DevHooks.key(this) } }.apply { isDaemon = true }.start()
        com.glasslauncher.tv.ui.Glow.amount = settings.glowSize
        // The menu's TV numbers are ready before it first opens.
        if (settings.menuSys) Thread { runCatching { com.glasslauncher.tv.data.SystemInfo.read(this, settings.sysCpu || settings.sysGpu) } }.apply { isDaemon = true }.start()
        if (settings.perfMode == "auto") settings.applyMode("auto")
        com.glasslauncher.tv.ui.GlassCache.res = settings.glassRes
        http = Http(this)
        icons = IconStore(this)
        packs = IconPacks(this)
        // The TV's inputs known from last time, before the dock is built (so an input's icon is there at once).
        if (settings.menuInputs || settings.inputsDock) com.glasslauncher.tv.data.Inputs.load(this)
        apps = AppRepository(this, settings, packs, icons).also { it.start() }
        catalogs = CatalogRepository(this, settings, http).also { it.start(scope) }
        cw = com.glasslauncher.tv.data.ContinueWatching(this, settings, http, scope).also { it.start() }
        weather = com.glasslauncher.tv.data.Weather(settings, http, scope).also { it.start() }
        library = com.glasslauncher.tv.data.Library(this, settings, http, cw.trakt, scope, cw.simkl)
        cw.nuvio = library.nuvio
        sync = com.glasslauncher.tv.data.Sync(this, settings, library, cw, scope).also { it.start() }
        com.glasslauncher.tv.net.NotifyServer.start(this)
        com.glasslauncher.tv.net.HaWatch.start(this)
        com.glasslauncher.tv.data.Photos.start(this)
        // Music: follows what any music app plays (its listener is switched on when Music is turned on).
        if (settings.musicOn) Thread { runCatching { com.glasslauncher.tv.data.NowPlaying.grant(this); com.glasslauncher.tv.data.NowPlaying.start(this) } }.apply { isDaemon = true }.start()
        // The TV's inputs (HDMI, CEC devices): read now and every few minutes.
        scope.launch {
            while (true) {
                if (settings.menuInputs || settings.inputsDock) {
                    val before = com.glasslauncher.tv.data.Inputs.list.value
                    val now = runCatching { com.glasslauncher.tv.data.Inputs.refresh(this@GlassApp) }.getOrDefault(before)
                    if (now != before && settings.inputsDock) apps.reload()
                }
                kotlinx.coroutines.delay(if (com.glasslauncher.tv.data.Inputs.list.value.isEmpty()) 20_000 else 180_000)
            }
        }
        // Add-ons from the accounts that are on (Nuvio, Stremio), as one list (each add-on once, see CatalogRepository).
        catalogs.account = {
            val n = if (settings.catalogsNuvio && library.nuvio.connected) library.nuvio.addons() else emptyList()
            val s = if (settings.catalogsStremio && cw.stremio.connected) cw.stremio.addons() else emptyList()
            if (n == null && s == null) null else (n.orEmpty() + s.orEmpty())
        }
        updater = com.glasslauncher.tv.data.Updater(this, scope).also { it.start() }
        // If Glass was closed while the TV screensaver was being started, put the screen timeout back.
        Thread { com.glasslauncher.tv.saver.SystemDream.restoreIfPending(this) }.apply { isDaemon = true }.start()
        icons.importDropFolder(apps.apps.value.map { it.pkg })
        ensureHomeService()
        // Decode dock artwork once, off the main thread, so the first frames are instant.
        scope.launch { apps.apps.value.forEach { runCatching { apps.artwork(it) } } }
    }

    /**
     * Android drops a sideloaded app from the enabled accessibility services whenever it is updated.
     * With WRITE_SECURE_SETTINGS (granted once over adb) Glass puts its Home-button service back.
     */
    fun ensureHomeService() {
        if (!settings.interceptHome) return
        if (checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val me = "$packageName/${HomeKeyService::class.java.name}"
            val cr = contentResolver
            val cur = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val parts = cur.split(':').filter { it.isNotBlank() }
            val mine = parts.any { it == me || it == "$packageName/.HomeKeyService" }
            if (!mine) android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (parts + me).joinToString(":"))
            android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, "1")
        }
    }
}
