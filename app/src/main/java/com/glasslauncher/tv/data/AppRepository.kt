package com.glasslauncher.tv.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val component: ComponentName, val leanback: Boolean)

data class Art(val bmp: ImageBitmap, val transparent: Boolean, val light: Boolean = false, val onLight: ImageBitmap = bmp)

class AppRepository(private val ctx: Context, private val settings: Settings, private val packs: IconPacks, private val icons: IconStore) {
    private val pm: PackageManager = ctx.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val preferred = listOf(
        "space.nuvio.nativelegacy", "com.nuvio.tv", "com.nuvio.tv.test", "com.nuvioreshaped.tv", "com.apple.atve.androidtv.appletv",
        "com.google.android.youtube.tv", "com.google.android.apps.youtube.unplugged",
        "com.netflix.ninja", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom",
        "com.google.android.videos", "com.android.vending",
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reload() }
    }

    fun start() {
        reload()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        runCatching { ctx.registerReceiver(receiver, f) }
    }

    fun reload() {
        val found = linkedMapOf<String, AppEntry>()
        fun scan(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                if (found.containsKey(ai.packageName)) continue
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("").ifBlank { ai.packageName.substringAfterLast('.') }
                found[ai.packageName] = AppEntry(ai.packageName, label, ComponentName(ai.packageName, ai.name), leanback)
            }
        }
        scan(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        scan(Intent.CATEGORY_LAUNCHER, false)
        // The TV's inputs as dock icons (Settings → Menu & themes → Inputs in the dock).
        if (settings.inputsDock) Inputs.list.value.forEach { i ->
            val pkg = Inputs.PREFIX + i.id
            found[pkg] = AppEntry(pkg, i.label, ComponentName(ctx.packageName, "input"), true)
        }
        if (settings.musicOn && settings.musicDock) found[com.glasslauncher.tv.ui.MusicUi.DOCK_PKG] =
            AppEntry(com.glasslauncher.tv.ui.MusicUi.DOCK_PKG, "Music", ComponentName(ctx.packageName, "music"), true)
        _apps.value = found.values.toList()
    }

    /** Apps in the user's saved order, hidden ones removed unless [includeHidden]. */
    fun ordered(includeHidden: Boolean = false): List<AppEntry> {
        val hidden = settings.hiddenApps.toSet()
        val saved = settings.dockOrder
        val all = _apps.value.filter { it.leanback || settings.showMobileApps || it.pkg in saved }
        val byPkg = all.associateBy { it.pkg }
        val result = mutableListOf<AppEntry>()
        saved.forEach { p -> byPkg[p]?.let(result::add) }
        val rest = all.filter { a -> result.none { it.pkg == a.pkg } }
        val rank = { a: AppEntry -> preferred.indexOf(a.pkg).let { if (it < 0) Int.MAX_VALUE else it } }
        result += rest.sortedWith(compareBy<AppEntry> { rank(it) }.thenBy { it.label.lowercase() })
        return if (includeHidden) result else result.filter { it.pkg !in hidden }
    }

    fun move(pkg: String, delta: Int) {
        val order = ordered(includeHidden = true).map { it.pkg }.toMutableList()
        val i = order.indexOf(pkg)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, order.lastIndex)
        order.removeAt(i); order.add(j, pkg)
        settings.dockOrder = order
    }

    /** Moves an app by [delta] positions among the apps that are visible in the dock. */
    fun moveVisible(pkg: String, delta: Int) {
        val full = ordered(includeHidden = true).map { it.pkg }.toMutableList()
        val vis = ordered(includeHidden = false).map { it.pkg }
        val i = vis.indexOf(pkg)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, vis.lastIndex)
        if (j == i) return
        val target = vis[j]
        full.remove(pkg)
        val ti = full.indexOf(target)
        full.add(if (delta > 0) ti + 1 else ti, pkg)
        settings.dockOrder = full
    }

    fun toggleHidden(pkg: String) {
        val h = settings.hiddenApps.toMutableList()
        if (!h.remove(pkg)) h.add(pkg)
        settings.hiddenApps = h
    }

    fun launchIntent(e: AppEntry): Intent? = if (Inputs.isInput(e.pkg) || e.pkg == com.glasslauncher.tv.ui.MusicUi.DOCK_PKG) null else
        (pm.getLeanbackLaunchIntentForPackage(e.pkg) ?: pm.getLaunchIntentForPackage(e.pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private val cache = object : LruCache<String, Art>(64 * 1024) {
        override fun sizeOf(key: String, value: Art) = value.bmp.width * value.bmp.height * 4 / 1024
    }

    private fun cacheKey(e: AppEntry) = "${settings.iconPack}|${icons.rev.intValue}|${e.pkg}"

    /** Already-decoded artwork, available instantly (no flicker when the dock recomposes). */
    fun cachedArtwork(e: AppEntry): Art? = cache.get(cacheKey(e))

    /** Custom icon, else the icon pack's, else the app's banner, else its icon. */
    suspend fun artwork(e: AppEntry): Art? {
        val key = cacheKey(e)
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            runCatching {
                val custom = icons.fileFor(e.pkg)
                val bmp: Bitmap = if (custom.exists()) {
                    android.graphics.BitmapFactory.decodeFile(custom.absolutePath) ?: error("bad custom icon")
                } else if (Inputs.isInput(e.pkg)) {
                    Inputs.icon(e.label)
                } else if (e.pkg == com.glasslauncher.tv.ui.MusicUi.DOCK_PKG) {
                    com.glasslauncher.tv.ui.MusicUi.dockIcon()
                } else {
                    val pack = settings.iconPack
                    val d: Drawable = (if (pack.isNotEmpty()) packs.drawableFor(pack, e.component) else null)
                        ?: pm.getActivityInfo(e.component, 0).let { ai -> hiRes(ai) ?: ai.loadBanner(pm) ?: ai.loadIcon(pm) }
                    val iw = d.intrinsicWidth; val ih = d.intrinsicHeight
                    val wide = iw > 0 && ih > 0 && iw.toFloat() / ih > 1.3f
                    d.toBitmap(if (wide) 640 else 256)
                }
                val transparent = isTransparent(bmp)
                Art(bmp.asImageBitmap(), transparent, isLight(bmp), if (transparent) darkenWhites(bmp).asImageBitmap() else bmp.asImageBitmap())
                    .also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    /**
     * The app's banner (or icon) at the highest density it ships: loadBanner() picks the TV's own density
     * (xhdpi, 320×180) and the tile then scales it up, which made icons soft.
     */
    private fun hiRes(ai: android.content.pm.ActivityInfo): Drawable? = runCatching {
        val res = pm.getResourcesForApplication(ai.applicationInfo)
        val banner = if (ai.banner != 0) ai.banner else ai.applicationInfo.banner
        val icon = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
        val id = if (banner != 0) banner else icon
        if (id == 0) null else res.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXHIGH, null)
    }.getOrNull()

    /** White / light-grey parts (lettering) turn near-black; coloured parts keep their colour. */
    private fun darkenWhites(src: Bitmap): Bitmap {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]; val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            if (mx - mn < 48 && mn > 150) {
                val v = 0x1C + (255 - mn) / 6
                px[i] = (a shl 24) or (v shl 16) or (v shl 8) or (v + 2).coerceAtMost(255)
            }
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** True when the visible part of the icon is (near) white: it needs darkening on a light plate. */
    private fun isLight(b: Bitmap): Boolean {
        var n = 0; var light = 0
        val stepX = maxOf(1, b.width / 24); val stepY = maxOf(1, b.height / 14)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                if ((p ushr 24) > 160) {
                    n++
                    val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
                    if (minOf(r, g, bl) > 200 && maxOf(r, g, bl) - minOf(r, g, bl) < 40) light++
                }
                x += stepX
            }
            y += stepY
        }
        return n > 0 && light * 100 / n >= 70
    }

    /** True when a good part of the image is see-through (glyph icons), false for full banners. */
    private fun isTransparent(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        var clear = 0; var total = 0
        val stepX = maxOf(1, b.width / 16); val stepY = maxOf(1, b.height / 10)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) { total++; if ((b.getPixel(x, y) ushr 24) < 40) clear++; x += stepX }
            y += stepY
        }
        return total > 0 && clear * 100 / total >= 35
    }

    private fun Drawable.toBitmap(targetW: Int): Bitmap {
        val iw = intrinsicWidth.takeIf { it > 0 } ?: targetW
        val ih = intrinsicHeight.takeIf { it > 0 } ?: targetW
        val scale = targetW.toFloat() / iw
        val w = targetW; val h = (ih * scale).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        setBounds(0, 0, w, h); draw(c)
        return bmp
    }
}
