package com.glasslauncher.tv.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class IconPackInfo(val pkg: String, val label: String)

/**
 * Reads standard ADW / Nova style icon packs (the format Projectivy, Blueprint and Core Builds packs use):
 * an `appfilter.xml` that maps `ComponentInfo{package/Activity}` to a drawable inside the pack app.
 */
class IconPacks(private val ctx: Context) {
    private val pm = ctx.packageManager
    private val actions = listOf(
        "org.adw.launcher.THEMES", "com.novalauncher.THEME",
        "com.gau.go.launcherex.theme", "org.adw.launcher.icons.ACTION_PICK_ICON",
    )

    private class Loaded(val pkg: String, val res: Resources, val byComponent: Map<String, String>, val byPackage: Map<String, String>)
    @Volatile private var loaded: Loaded? = null

    fun installed(): List<IconPackInfo> {
        val out = linkedMapOf<String, IconPackInfo>()
        for (a in actions) {
            for (ri in runCatching { pm.queryIntentActivities(Intent(a), 0) }.getOrDefault(emptyList())) {
                val p = ri.activityInfo?.packageName ?: continue
                out.putIfAbsent(p, IconPackInfo(p, runCatching { ri.loadLabel(pm).toString() }.getOrDefault(p)))
            }
        }
        return out.values.sortedBy { it.label.lowercase() }
    }

    fun label(pkg: String): String = installed().firstOrNull { it.pkg == pkg }?.label ?: pkg

    @Synchronized
    private fun ensure(pack: String): Loaded? {
        loaded?.takeIf { it.pkg == pack }?.let { return it }
        val l = runCatching { parse(pack) }.getOrNull()
        loaded = l
        return l
    }

    /** Opens res/xml/<name> (compiled, what most packs ship) and assets/<name>.xml (often a template copy). */
    private fun parsers(pctx: Context, pack: String, name: String): List<Pair<XmlPullParser, InputStream?>> {
        val out = mutableListOf<Pair<XmlPullParser, InputStream?>>()
        val id = pctx.resources.getIdentifier(name, "xml", pack)
        if (id != 0) runCatching { out += pctx.resources.getXml(id) to null }
        runCatching { pctx.assets.open("$name.xml") }.getOrNull()?.let { s ->
            out += Xml.newPullParser().apply { setInput(s, null) } to s
        }
        return out
    }

    private fun parse(pack: String): Loaded {
        val pctx = ctx.createPackageContext(pack, 0)
        val res = pctx.resources
        val byComponent = HashMap<String, String>(4096)
        val byPackage = HashMap<String, String>(2048)
        for ((parser, stream) in parsers(pctx, pack, "appfilter")) {
            runCatching {
                var ev = parser.eventType
                while (ev != XmlPullParser.END_DOCUMENT) {
                    if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                        val comp = parser.getAttributeValue(null, "component")
                        val drawable = parser.getAttributeValue(null, "drawable")
                        if (comp != null && !drawable.isNullOrBlank() && comp.startsWith("ComponentInfo{") && comp.endsWith("}")) {
                            val inner = comp.substring(14, comp.length - 1)
                            val pkg = inner.substringBefore('/')
                            var cls = inner.substringAfter('/', "")
                            if (cls.startsWith(".")) cls = pkg + cls
                            // Real entries win over template samples: only fill what is still empty.
                            if (pkg.isNotEmpty()) {
                                if (cls.isNotEmpty()) byComponent.putIfAbsent("$pkg/$cls", drawable)
                                byPackage.putIfAbsent(pkg, drawable)
                            }
                        }
                    }
                    ev = parser.next()
                }
            }
            stream?.close()
        }
        return Loaded(pack, res, byComponent, byPackage)
    }

    private val namesCache = HashMap<String, List<String>>()

    /** Every icon the pack offers (drawable.xml, else every icon its appfilter maps). */
    @Synchronized
    fun drawableNames(pack: String): List<String> {
        namesCache[pack]?.let { return it }
        val names = LinkedHashSet<String>()
        runCatching {
            val pctx = ctx.createPackageContext(pack, 0)
            for ((parser, stream) in parsers(pctx, pack, "drawable")) {
                runCatching {
                    var ev = parser.eventType
                    while (ev != XmlPullParser.END_DOCUMENT) {
                        if (ev == XmlPullParser.START_TAG && parser.name == "item") parser.getAttributeValue(null, "drawable")?.let { if (it.isNotBlank()) names += it }
                        ev = parser.next()
                    }
                }
                stream?.close()
            }
        }
        // Some packs (Blueprint templates, like Projectivy's) list sample names in drawable.xml: also take every icon
        // the appfilter maps, then keep only names that really exist as a drawable in the pack.
        ensure(pack)?.let { names += it.byComponent.values; names += it.byPackage.values }
        val res = ensure(pack)?.res ?: runCatching { ctx.packageManager.getResourcesForApplication(pack) }.getOrNull()
        // …and every other picture in the pack: alternates and generic icons that no app is mapped to (Projectivy's
        // pack has about 400 of them, which never showed).
        if (res != null) names += pictureDrawables(res, pack, names.firstOrNull { res.getIdentifier(it, "drawable", pack) != 0 })
        val list = names.asSequence()
            .filterNot { it.startsWith("cb_back") || it == "cb_mask" || it == "cb_upon" || it.startsWith("iconback") }
            .filter { n -> res != null && (res.getIdentifier(n, "drawable", pack) != 0 || res.getIdentifier(n, "mipmap", pack) != 0) }
            .toList()
        namesCache[pack] = list
        return list
    }

    // The pack app's own interface and its libraries (AndroidX, Material, Google sign-in…), not icons.
    private val notIcons = Regex("^(\\$|abc_.*(_mtrl|_holo)|mtrl_|m3_|material_|design_|common_|googleg_|notification_|tooltip_|btn_|test_|avd_|exo_|radio_|preference_|ic_|adaptive_|bottom_|drawer_|fetch_|app_splash|indeterminate_|select_|navigation_|cb_|iconback|iconmask|iconupon)")

    /** Every bitmap (png / webp / jpg) drawable in the pack, walked by resource id from one known drawable. */
    private fun pictureDrawables(res: Resources, pack: String, known: String?): List<String> {
        val first = known?.let { res.getIdentifier(it, "drawable", pack) } ?: return emptyList()
        if (first == 0) return emptyList()
        val base = first and 0xFFFF0000.toInt()
        val out = ArrayList<String>()
        val tv = android.util.TypedValue()
        var misses = 0
        var i = 0
        while (i < 0x10000 && misses < 64) {
            val id = base or i
            i++
            val name = runCatching { res.getResourceEntryName(id) }.getOrNull()
            if (name == null) { misses++; continue }
            misses = 0
            if (notIcons.containsMatchIn(name)) continue
            val file = runCatching { res.getValue(id, tv, true); tv.string?.toString() }.getOrNull() ?: continue
            if (file.endsWith(".png", true) || file.endsWith(".webp", true) || file.endsWith(".jpg", true)) out += name
        }
        return out
    }

    /** Icons in [pack] that fit this app: its mapped icon first, then icons whose name contains the app's name. */
    fun suggestions(pack: String, c: ComponentName, label: String): List<String> {
        val l = ensure(pack) ?: return emptyList()
        val out = LinkedHashSet<String>()
        l.byComponent["${c.packageName}/${c.className}"]?.let(out::add)
        l.byPackage[c.packageName]?.let(out::add)
        val stop = setOf("com", "android", "google", "app", "apps", "tv", "the", "and", "leanback", "atve", "androidtv", "mobile")
        val tokens = (label.lowercase().split(Regex("[^a-z0-9]+")) + c.packageName.lowercase().split('.'))
            .filter { it.length >= 3 && it !in stop }.distinct()
        if (tokens.isNotEmpty()) {
            drawableNames(pack).asSequence()
                .filter { n -> val k = n.lowercase().replace("_", ""); tokens.any { k.contains(it) } }
                .take(24).forEach { out += it }
        }
        return out.toList()
    }

    fun drawable(pack: String, name: String): Drawable? {
        val res = ensure(pack)?.res ?: runCatching { ctx.packageManager.getResourcesForApplication(pack) }.getOrNull() ?: return null
        var id = res.getIdentifier(name, "drawable", pack)
        if (id == 0) id = res.getIdentifier(name, "mipmap", pack)
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    /** Draws [d] into a bitmap [targetW] pixels wide, keeping its aspect ratio. */
    fun render(d: Drawable, targetW: Int): Bitmap {
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: targetW
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: targetW
        val w = targetW
        val h = (ih * (targetW.toFloat() / iw)).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, w, h); d.draw(Canvas(bmp))
        return bmp
    }

    /** The pack's drawable for this activity, or null when the pack has none. */
    fun drawableFor(pack: String, c: ComponentName): Drawable? {
        val l = ensure(pack) ?: return null
        val name = l.byComponent["${c.packageName}/${c.className}"] ?: l.byPackage[c.packageName] ?: return null
        var id = l.res.getIdentifier(name, "drawable", pack)
        if (id == 0) id = l.res.getIdentifier(name, "mipmap", pack)
        if (id == 0) return null
        return runCatching { l.res.getDrawable(id, null) }.getOrNull()
    }
}
