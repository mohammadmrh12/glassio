package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Icon packs Glassio can install by itself (Settings → Dock & icons): each one's newest release from its own GitHub
 * page (or F-Droid), installed through the TV's own shell (TV access), else Android's installer.
 */
object IconPackStore {
    /** [repo] GitHub "owner/name" with [asset] a part of the APK's file name; or [fdroid] an F-Droid package. */
    class Pack(val name: String, val pkg: String, val about: String, val repo: String? = null, val asset: String = ".apk", val fdroid: Boolean = false)

    val PACKS = listOf(
        Pack("Projectivy Icon Pack", "dev.projectivy.blueprint.app", "Banner icons made for TV launchers, 800+ apps", repo = "SicMundus86/ProjectivyIconPack"),
        Pack("Core Builds Icon Pack", "tv.corebuilds.iconpack", "Simple coloured line icons, 900+ apps", repo = "brevityA/CoreBuildsApps", asset = "iconpack-release"),
        Pack("Core Builds Glyphs", "tv.corebuilds.iconpack.glyphs", "The same icons as plain glyphs", repo = "brevityA/CoreBuildsApps", asset = "iconpack-glyphs-release"),
        Pack("Minimalist Icon Pack", "com.hqnscl.minimalist.app", "Clean symbols on one background colour", repo = "hqn-scl/android-tv-minimalist-icon-pack"),
        Pack("Arcticons", "com.donnnno.arcticons", "15,000+ line icons (square, made for phones)", repo = "Arcticons-Team/Arcticons", asset = "-normal-release"),
        Pack("Arcticons Day & Night", "com.donnnno.arcticons.daynight", "Arcticons that follow light and dark", repo = "Arcticons-Team/Arcticons", asset = "-dayNight-release"),
        Pack("Appstract", "dev.appstract.iconpack", "Abstract coloured icons (square)", fdroid = true),
    )

    /** What each pack is doing now: "Downloading 40%", "Installing…", an error; absent when idle. */
    val status = MutableStateFlow<Map<String, String>>(emptyMap())
    private fun say(pkg: String, s: String?) { status.value = if (s == null) status.value - pkg else status.value + (pkg to s) }

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun get(url: String): HttpURLConnection {
        var u = URL(url); var hops = 0
        while (true) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "Glassio")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 6) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue }
            check(code == 200) { "HTTP $code" }
            return c
        }
    }

    /** The newest APK's address. */
    private fun apkUrl(p: Pack): String {
        if (p.fdroid) {
            val o = JSONObject(get("https://f-droid.org/api/v1/packages/${p.pkg}").inputStream.bufferedReader().use { it.readText() })
            return "https://f-droid.org/repo/${p.pkg}_${o.getInt("suggestedVersionCode")}.apk"
        }
        val o = JSONObject(get("https://api.github.com/repos/${p.repo}/releases/latest").inputStream.bufferedReader().use { it.readText() })
        val a = o.getJSONArray("assets")
        for (i in 0 until a.length()) {
            val x = a.getJSONObject(i); val n = x.getString("name")
            if (n.endsWith(".apk") && n.contains(p.asset, ignoreCase = true)) return x.getString("browser_download_url")
        }
        error("no APK in the newest release")
    }

    /** Blocking: downloads and installs [p]. */
    fun install(ctx: Context, p: Pack, installer: (File) -> Unit) {
        if (status.value.containsKey(p.pkg)) return
        val f = File(ctx.cacheDir, "iconpack-${p.pkg}.apk")
        try {
            say(p.pkg, "Downloading…")
            val c = get(apkUrl(p))
            val total = c.contentLengthLong
            c.inputStream.use { i -> f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                    if (total > 0) say(p.pkg, "Downloading ${(done * 100 / total).toInt()}%") }
            } }
            say(p.pkg, "Installing…")
            val r = LocalShell.run(ctx, "true").let { if (it.isSuccess) shellInstall(ctx, f) else Result.failure(it.exceptionOrNull() ?: Exception()) }
            if (r.isFailure) installer(f)
            say(p.pkg, null)
        } catch (e: Exception) {
            Glog.w("Icon pack ${p.name}", e)
            say(p.pkg, "Failed: ${e.message}")
        }
    }

    /** Through the TV's own shell: silent, and Glassio isn't restarted (it is not an update of Glassio). */
    private fun shellInstall(ctx: Context, f: File): Result<Unit> = runCatching {
        // (The shell can read Glassio's cache only through a copy it can reach: /data/local/tmp.)
        val tmp = "/data/local/tmp/glass-iconpack.apk"
        val bytes = f.length()
        LocalShell.push(ctx, f, tmp)
        val out = LocalShell.run(ctx, "pm install -r $tmp; rm -f $tmp").getOrThrow()
        check(out.contains("Success")) { out.trim().ifBlank { "refused ($bytes bytes)" } }
    }
}
