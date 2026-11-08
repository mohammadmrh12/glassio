package com.glasslauncher.tv

import android.content.Intent
import java.io.File
import java.security.MessageDigest

/**
 * Testing from a computer with `adb shell am start -n com.glasslauncher.tv/.MainActivity --es …`.
 *
 * Opening a screen (folder, settings page, menu, Library, screensaver) is open to anyone: it changes nothing.
 * Everything else changes settings or prints them to the log, so it needs the dev key: a random code kept in
 * Glassio's own folder on the shared storage, which adb can read and other apps can't (Android 11+):
 *
 *     K=$(adb shell cat /sdcard/Android/data/com.glasslauncher.tv/files/devkey)
 *     adb shell am start -n com.glasslauncher.tv/.MainActivity --es dev $K --es pref theme=water
 *
 * Without it another app on the TV could, for example, point the Home Assistant webhook at its own server.
 */
object DevHooks {
    private fun keyFile(app: GlassApp): File? = app.getExternalFilesDir(null)?.let { File(it, "devkey") }

    /** The dev key, made once. */
    fun key(app: GlassApp): String {
        val f = keyFile(app) ?: return ""
        f.takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.length >= 16 }?.let { return it }
        val k = com.glasslauncher.tv.net.Net.token() + com.glasslauncher.tv.net.Net.token()
        runCatching { f.parentFile?.mkdirs(); f.writeText(k) }
        return k
    }

    private fun allowed(app: GlassApp, i: Intent): Boolean {
        if (BuildConfig.DEBUG) return true
        val given = i.getStringExtra("dev") ?: return false
        val want = key(app)
        return want.isNotBlank() && MessageDigest.isEqual(given.toByteArray(), want.toByteArray())
    }

    fun handle(app: GlassApp, a: MainActivity, i: Intent?) {
        i ?: return
        // ---- Open a screen (safe for anyone)
        if (i.getBooleanExtra("open_folder", false)) app.requestFolder.value = true
        // A remote button set to a locked app: its code is asked here first (AppLock).
        i.getStringExtra("open_app")?.let { pkg ->
            app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == pkg }?.let { e -> com.glasslauncher.tv.ui.AppLock.open(app, a, e) }
        }
        i.getIntExtra("open_settings", -1).takeIf { it >= 0 }?.let { app.requestSettings.intValue = it; app.requestSettingsRev.intValue++ }
        // The silent update path, with this same APK (Glassio restarts): --ez self_install true.
        if (i.getBooleanExtra("self_install", false)) Thread {
            val f = java.io.File(app.cacheDir, "self.apk")
            java.io.File(app.applicationInfo.sourceDir).copyTo(f, overwrite = true)
            val r = com.glasslauncher.tv.data.LocalShell.install(app, f)
            com.glasslauncher.tv.data.Glog.i("Self install: ${r.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message}" } ?: "ok"}")
        }.start()
        // Hue: find the bridge (--ez hue_find true), then pair after its button (--ez hue_pair true); log only.
        if (i.getBooleanExtra("hue_find", false)) Thread {
            val f = com.glasslauncher.tv.data.Hue.discover(app)
            com.glasslauncher.tv.data.Glog.i("Hue found: $f"); f.firstOrNull()?.let { app.settings.hueIp = it }
        }.start()
        if (i.getBooleanExtra("hue_pair", false)) Thread {
            repeat(40) {
                val r = com.glasslauncher.tv.data.Hue.pair(app.settings.hueIp)
                if (r.user != null) { app.settings.hueUser = r.user; com.glasslauncher.tv.data.Glog.i("Hue paired"); return@Thread }
                if (!r.waiting) { com.glasslauncher.tv.data.Glog.w("Hue pair: ${r.error}"); return@Thread }
                Thread.sleep(1000)
            }
            com.glasslauncher.tv.data.Glog.w("Hue pair: button not pressed")
        }.start()
        // Opens a title in Nuvio Legacy as the carousel does: --es open_legacy movie:tt0111161.
        i.getStringExtra("open_legacy")?.let { v -> com.glasslauncher.tv.data.NuvioLaunch.openIn(app, "legacy",
            com.glasslauncher.tv.data.HeroItem(v.substringAfter(':'), v.substringAfter(':'), v.substringBefore(':'), "", "", "", "", "", "", "", emptyList(), "")) }
        if (i.getBooleanExtra("show_tour", false)) com.glasslauncher.tv.ui.WhatsNew.show()
        if (i.getBooleanExtra("open_menu", false)) { app.requestSettings.intValue = 100; app.requestSettingsRev.intValue++ }
        if (i.getBooleanExtra("open_library", false)) { app.requestSettings.intValue = 101; app.requestSettingsRev.intValue++ }
        // Home Assistant cameras: lists them and asks for the first one's live link (log only).
        if (i.getBooleanExtra("ha_cams", false)) Thread {
            runCatching {
                val l = com.glasslauncher.tv.net.HaWatch.cameras(app.settings.haUrl, app.settings.haToken)
                com.glasslauncher.tv.data.Glog.i("HA cameras: " + l.joinToString { it.first })
                l.firstOrNull()?.let { com.glasslauncher.tv.data.Glog.i("HA stream ${it.first}: " + (com.glasslauncher.tv.net.HaWatch.streamUrl(app.settings.haUrl, app.settings.haToken, it.first)?.replace(Regex("/api/hls/[^/]+/"), "/api/hls/…/") ?: "none")) }
            }.onFailure { com.glasslauncher.tv.data.Glog.w("HA cameras", it) }
        }.start()
        // A pretend song for looking at the music player, the island and music mode (cleared with fake_song=false).
        // Finds a trailer's streams (log: "Trailer …"): --es trailer_test <YouTube id>.
        i.getStringExtra("trailer_test")?.let { id -> Thread {
            com.glasslauncher.tv.data.TrailerSource.resolve(id, 1080)?.let { r ->
                // The links (for testing their speed from a computer); written to the app's own folder.
                java.io.File(app.getExternalFilesDir(null), "trailer.txt").writeText(r.video + "\n" + (r.audio ?: "") + "\n" + r.subs.joinToString("\n") { "${it.lang} ${it.auto} ${it.url}" } + "\n")
            }
        }.start() }
        // Starts (or stops) the trailer of the title on screen, like the Play menu: --ez trailer_now true.
        if (i.getBooleanExtra("trailer_now", false)) com.glasslauncher.tv.ui.Trailers.request.intValue++
        if (i.hasExtra("fake_song")) {
            if (i.getBooleanExtra("fake_song", false)) {
                val b = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(b).drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, 0xFF7B61FF.toInt(), 0xFFFF6FA8.toInt(), android.graphics.Shader.TileMode.CLAMP) })
                com.glasslauncher.tv.data.NowPlaying.track.value = com.glasslauncher.tv.data.NowPlaying.Track("com.spotify.tv.android", "Test song", "Test artist", "Album", b,
                    200_000, 60_000, android.os.SystemClock.elapsedRealtime(), 1f, true)
            } else com.glasslauncher.tv.data.NowPlaying.track.value = null
        }
        if (i.getBooleanExtra("start_saver", false)) a.startActivity(Intent(a, com.glasslauncher.tv.saver.ScreensaverActivity::class.java))

        if (!allowed(app, i)) return
        val st = app.settings
        // ---- Settings
        i.getStringArrayExtra("prefs")?.forEach { st.debugSet(it) }
        i.getStringExtra("pref")?.let { st.debugSet(it) }
        i.getStringExtra("cameras")?.let { st.cameras = it.split(';').filter { l -> l.contains("://") } }
        i.getStringExtra("theme")?.let { st.applyTheme(it) }
        i.getStringExtra("perf_mode")?.let { if (it in setOf("auto", "performance", "balanced", "quality")) st.applyMode(it) }
        i.getStringExtra("ha_webhook")?.let { st.haWebhook = it; st.haOnLaunch = it.startsWith("http") }
        i.getStringExtra("reset_icon")?.let { app.icons.reset(it) }
        // An app back in the dock (or hidden): --es show_app <package> / --es hide_app <package>.
        i.getStringExtra("show_app")?.let { p -> st.hiddenApps = st.hiddenApps.filterNot { it == p } }
        i.getStringExtra("hide_app")?.let { p -> if (p !in st.hiddenApps) st.hiddenApps = st.hiddenApps + p }
        // The hidden apps in full (the settings dump cuts long values).
        if (i.getBooleanExtra("dump_hidden", false)) android.util.Log.i("GlassPrefs", "hiddenApps(all)=" + st.hiddenApps.joinToString(","))
        i.getStringExtra("icon_pack")?.let { p ->
            if (p == "none") st.iconPack = "" else if (app.packs.installed().any { it.pkg == p }) st.iconPack = p
        }
        // How many icons each installed pack offers in the icon picker (logged as GlassPacks).
        if (i.getBooleanExtra("pack_counts", false)) Thread {
            app.packs.installed().forEach { p -> android.util.Log.i("GlassPacks", "${p.pkg}: ${app.packs.drawableNames(p.pkg).size}") }
        }.start()
        // A remote mapping action, run 1.5 s later as if its button was pressed: --es run_map folder|recents|app:<pkg>
        i.getStringExtra("run_map")?.let { act ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.glasslauncher.tv.data.RemoteMap.run(app, com.glasslauncher.tv.data.RemoteMap.Map(0, act, ""))
            }, 1500)
        }
        // ---- Measuring
        if (i.hasExtra("timing")) com.glasslauncher.tv.data.Glog.timing = i.getBooleanExtra("timing", false)
        if (i.hasExtra("video_ms")) com.glasslauncher.tv.ui.VideoFrames.interval = i.getLongExtra("video_ms", 66L)
        if (i.hasExtra("dbg_hide")) com.glasslauncher.tv.ui.DebugHide.intValue = i.getIntExtra("dbg_hide", 0)
        // A key held down, with the repeats the TV sends (adb can only send single presses):
        // --ei hold_key 20 --ei hold_ms 2500 --ei hold_every 50
        if (i.hasExtra("hold_key")) {
            val code = i.getIntExtra("hold_key", android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            val ms = i.getIntExtra("hold_ms", 2000).toLong(); val every = i.getIntExtra("hold_every", 50).toLong()
            val h = android.os.Handler(android.os.Looper.getMainLooper())
            val down = android.os.SystemClock.uptimeMillis() + 300
            var n = 0
            fun send() {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - down >= ms) { a.dispatchKeyEvent(android.view.KeyEvent(down, now, android.view.KeyEvent.ACTION_UP, code, 0)); return }
                a.dispatchKeyEvent(android.view.KeyEvent(down, now, android.view.KeyEvent.ACTION_DOWN, code, n))
                h.postDelayed({ send() }, if (n++ == 0) 400 else every)
            }
            h.postDelayed({ send() }, 300)
        }
        if (i.hasExtra("page_glass")) com.glasslauncher.tv.ui.PageGlass.on.value = i.getBooleanExtra("page_glass", true)
        if (i.getBooleanExtra("dump_prefs", false)) st.dumpAll().toSortedMap().forEach { (k, v) ->
            android.util.Log.i("GlassPrefs", "$k=${v.toString().take(120)}")
        }
    }
}
