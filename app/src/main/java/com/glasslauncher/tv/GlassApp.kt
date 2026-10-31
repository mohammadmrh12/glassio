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
            .components { add(coil3.video.VideoFrameDecoder.Factory()) }
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
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION") when {
            // In the background and Android is looking for memory: everything that can be made again goes.
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                com.glasslauncher.tv.data.Glog.w("Low memory in the background (level $level): clearing picture caches")
                runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
                com.glasslauncher.tv.ui.GlassCache.trim()
                com.glasslauncher.tv.ui.trimPageCaches()
            }
            level == android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> Unit
            // In front and memory is getting short (older Android): half the decoded pictures, the screen stays.
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                com.glasslauncher.tv.data.Glog.w("Low memory (level $level): halving the picture cache")
                runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.let { it.trimToSize(it.maxSize / 2) } }
            }
        }
    }

    /** Bumped when Home is pressed while Glass is already in front: the carousel goes back to its start. */
    val homeRev = androidx.compose.runtime.mutableIntStateOf(0)
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
        apps = AppRepository(this, settings, packs, icons).also { it.start() }
        catalogs = CatalogRepository(this, settings, http).also { it.start(scope) }
        cw = com.glasslauncher.tv.data.ContinueWatching(this, settings, http, scope).also { it.start() }
        weather = com.glasslauncher.tv.data.Weather(settings, http, scope).also { it.start() }
        library = com.glasslauncher.tv.data.Library(this, settings, http, cw.trakt, scope, cw.simkl)
        cw.nuvio = library.nuvio
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
