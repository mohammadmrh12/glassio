package com.glasslauncher.tv

import com.glasslauncher.tv.data.Bg
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
        runCatching { f.parentFile?.mkdirs(); f.writeText(k) }.onFailure { com.glasslauncher.tv.data.Glog.w("Dev key file", it) }
        return k
    }

    private fun allowed(app: GlassApp, i: Intent): Boolean {
        if (BuildConfig.DEBUG) return true
        val given = i.getStringExtra("dev") ?: return false
        val want = key(app)
        return want.isNotBlank() && MessageDigest.isEqual(given.toByteArray(), want.toByteArray())
    }

    /** The side menu is open (set by the home screen; for the QA focus watch). */
    @Volatile var menuOpen = false
    private var watching: Runnable? = null

    /** QA (--ez focus_watch true|false): logs where the selection is (GlassFocusW) every time it changes, 4 times a second. */
    private fun focusWatch(a: MainActivity, on: Boolean) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        watching?.let { h.removeCallbacks(it) }; watching = null
        if (!on) return
        var last = ""
        val r = object : Runnable { override fun run() {
            val f = com.glasslauncher.tv.ui.DockFocus
            val line = "screen=${a.screenName} menu=$menuOpen saver=${com.glasslauncher.tv.saver.HomeSaver.state.value} hero=${f.hero} top=${f.top} dock=${f.dock} idx=${f.idx} " +
                "dockPkg=${com.glasslauncher.tv.ui.ChannelsUi.dockPkg.value} page=${com.glasslauncher.tv.ui.HomePage.now} " +
                "musicStage=${com.glasslauncher.tv.ui.MusicUi.stageFocused} musicApps=${com.glasslauncher.tv.ui.MusicUi.appsFocused} inputs=${com.glasslauncher.tv.ui.InputsUi.focused} " +
                "search=${com.glasslauncher.tv.ui.SearchUi.focused} rows=${com.glasslauncher.tv.ui.ChannelsUi.focused} cams=${MultiCamOverlay.open.value} " +
                "chooser=${com.glasslauncher.tv.ui.ProfilesUi.open.value} state=${a.lifecycle.currentState}"
            if (line != last) { last = line; android.util.Log.i("GlassFocusW", line) }
            h.postDelayed(this, 250)
        } }
        watching = r; h.post(r)
    }

    fun handle(app: GlassApp, a: MainActivity, i: Intent?) {
        i ?: return
        // ---- Open a screen (safe for anyone)
        if (i.getBooleanExtra("open_folder", false)) app.requestFolder.value = true
        // A remote button set to a locked app: its code is asked here first (AppLock).
        i.getStringExtra("open_app")?.let { pkg ->
            app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == pkg }?.let { e -> com.glasslauncher.tv.ui.AppLock.open(app, a, e) }
        }
        // Settings searching for these words (QA: the search's results without typing on the TV's keyboard).
        i.getStringExtra("settings_search")?.let { q -> com.glasslauncher.tv.ui.SettingsAskSearch.value = q; app.requestSettings.intValue = com.glasslauncher.tv.ui.CATEGORY_ORDER.indexOf("Accounts & profiles").coerceAtLeast(0); app.requestSettingsRev.intValue++ }
        i.getIntExtra("open_settings", -1).takeIf { it >= 0 }?.let { app.requestSettings.intValue = it; app.requestSettingsRev.intValue++ }
        if (i.getBooleanExtra("show_tour", false)) com.glasslauncher.tv.ui.WhatsNew.show()
        // Next picture / video / photo of your own background (QA: --ez next_bg true).
        if (i.getBooleanExtra("next_bg", false)) com.glasslauncher.tv.ui.CustomBackground.nextNow(app.settings)
        // Multi-view cameras on / off (QA: --ez multi_cams true).
        if (i.getBooleanExtra("multi_cams", false)) com.glasslauncher.tv.HomeKeyService.instance?.toggleMulti(true)
        // The cameras page on the whole screen (#30), and multi-view in the middle (as the menu opens it).
        if (i.getBooleanExtra("cams_full", false)) com.glasslauncher.tv.MultiHost.toggle(false, full = true)
        if (i.getBooleanExtra("multi_mid", false)) com.glasslauncher.tv.MultiHost.toggle(false)
        if (i.getBooleanExtra("open_menu", false)) { app.requestSettings.intValue = 100; app.requestSettingsRev.intValue++ }
        if (i.getBooleanExtra("open_library", false)) { app.requestSettings.intValue = 101; app.requestSettingsRev.intValue++ }
        // The search (a remote button set to it): its page on the home screen, the bar selected. Nothing when it is off.
        if (i.getBooleanExtra("open_search", false) && app.settings.searchOn) { app.requestSettings.intValue = 102; app.requestSettingsRev.intValue++ }
        // Starts (or stops) the trailer of the title on screen, like the Play menu: --ez trailer_now true.
        if (i.getBooleanExtra("trailer_now", false)) com.glasslauncher.tv.ui.Trailers.request.intValue++
        if (i.getBooleanExtra("show_setup", false)) app.requestSetup.intValue++
        if (i.getBooleanExtra("start_saver", false)) com.glasslauncher.tv.saver.HomeSaver.begin(a)

        if (!allowed(app, i)) return
        // ---- These change something or print something private: after the key, like the rest below.
        // The silent update path, with this same APK (Glassio restarts): --ez self_install true.
        if (!com.glasslauncher.tv.data.Store.PLAY && i.getBooleanExtra("self_install", false)) Bg.io("glass-dev") {
            val f = java.io.File(app.cacheDir, "self.apk")
            java.io.File(app.applicationInfo.sourceDir).copyTo(f, overwrite = true)
            val r = com.glasslauncher.tv.data.LocalShell.install(app, f)
            com.glasslauncher.tv.data.Glog.i("Self install: ${r.exceptionOrNull()?.let { "${it.javaClass.simpleName} ${it.message}" } ?: "ok"}")
        }
        // The "Manage from phone" page without reading the QR code (QA from a computer): starts it and logs its
        // address (tag GlassPhone): --ez phone_url true; false stops it again.
        if (i.hasExtra("phone_url")) {
            if (i.getBooleanExtra("phone_url", false)) android.util.Log.i("GlassPhone", com.glasslauncher.tv.net.PhoneHost.start(app) ?: "no network")
            else com.glasslauncher.tv.net.PhoneHost.stop()
        }
        // Hue: find the bridge (--ez hue_find true), then pair after its button (--ez hue_pair true); log only.
        if (i.getBooleanExtra("hue_find", false)) Bg.io("glass-dev") {
            val f = com.glasslauncher.tv.data.Hue.discover(app)
            com.glasslauncher.tv.data.Glog.i("Hue found: $f"); f.firstOrNull()?.let { app.settings.hueIp = it }
        }
        if (i.getBooleanExtra("hue_pair", false)) Bg.io("glass-dev") {
            repeat(40) {
                val r = com.glasslauncher.tv.data.Hue.pair(app.settings.hueIp)
                if (r.user != null) { app.settings.hueUser = r.user; app.settings.huePin = ""; com.glasslauncher.tv.data.Glog.i("Hue paired"); return@io }
                if (!r.waiting) { com.glasslauncher.tv.data.Glog.w("Hue pair: ${r.error}"); return@io }
                Thread.sleep(1000)
            }
            com.glasslauncher.tv.data.Glog.w("Hue pair: button not pressed")
        }
        // Opens a title in Nuvio Legacy as the carousel does: --es open_legacy movie:tt0111161.
        i.getStringExtra("open_legacy")?.let { v -> com.glasslauncher.tv.data.NuvioLaunch.openIn(app, "legacy",
            com.glasslauncher.tv.data.HeroItem(v.substringAfter(':'), v.substringAfter(':'), v.substringBefore(':'), "", "", "", "", "", "", "", emptyList(), "")) }
        // Home Assistant cameras: lists them and asks for the first one's live link (log only).
        // Jellyfin (#27): sign in ("server|user|password"; "-" signs out), and what Glassio reads from it (tag GlassJellyfin, no addresses).
        i.getStringExtra("jf_signin")?.let { v -> Bg.io("glass-dev") {
            val jf = app.cw.jellyfin
            if (v == "-") { jf.signOut(); return@io }
            val p = v.split('|')
            val r = jf.signIn(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
            android.util.Log.i("GlassJellyfin", "sign in: " + (r.exceptionOrNull()?.message ?: "ok"))
            if (r.isSuccess) { app.cw.pollNow(); runCatching { app.catalogs.refresh() } }
        } }
        if (i.getBooleanExtra("jf_dump", false)) Bg.io("glass-dev") {
            val jf = app.cw.jellyfin
            fun l(s: String) = android.util.Log.i("GlassJellyfin", s)
            l("connected=${jf.connected} app=${com.glasslauncher.tv.data.Jellyfin.installed(app)}")
            runCatching { l("libraries: " + jf.libraries().joinToString { "${it.second} (${it.third})" }) }.onFailure { l("libraries: ${it.message}") }
            runCatching { l("latest: " + jf.latest(limit = 5).joinToString { it.name }) }
            runCatching { l("continue: " + jf.continueWatching().joinToString { "${it.name} ${(it.progress * 100).toInt()}% ${it.key}" }) }
            runCatching { l("favourites: " + jf.favourites().joinToString { it.name }) }
            runCatching { l("ids: ${jf.byImdb().size}, Dracula=" + (jf.find("tt0021814")?.name ?: "-")) }
            l("catalogs: " + app.catalogs.catalogs.value.filter { it.addon == com.glasslauncher.tv.data.JELLYFIN }.joinToString { it.name + (if (it.key in app.catalogs.selectedKeys.value) " ✓" else "") })
            l("carousel jellyfin titles: " + app.catalogs.items.value.count { it.platform == "Jellyfin" } + " of " + app.catalogs.items.value.size)
            l("cw: " + app.cw.entries.value.filter { it.app == "Jellyfin" }.joinToString { it.key })
        }
        // Every thread's stack (tag GlassThreads): what a stuck page waits on.
        if (i.getBooleanExtra("dump_threads", false)) Thread.getAllStackTraces().forEach { (th, st) ->
            if (st.isEmpty()) return@forEach
            android.util.Log.i("GlassThreads", "== ${th.name} ${th.state}")
            st.take(14).forEach { f -> android.util.Log.i("GlassThreads", "   $f") }
        }
        // Picture downloads fail for N seconds, after the picture caches are emptied (the retry after a failure: NetState).
        // QA: opens the TV's settings the way the menu does (with fire_settings_test: the Fire TV way, logged).
        if (i.getBooleanExtra("tv_settings", false)) com.glasslauncher.tv.data.SystemSetup.openTvSettings(app, android.provider.Settings.ACTION_SETTINGS)
        if (i.hasExtra("fire_settings_test")) { com.glasslauncher.tv.data.SystemSetup.fireSettingsTest = i.getBooleanExtra("fire_settings_test", false); com.glasslauncher.tv.data.Glog.i("QA: Fire TV settings way ${com.glasslauncher.tv.data.SystemSetup.fireSettingsTest}") }
        // TV access with a key the TV has never allowed (true), or Glassio's own again (false).
        if (i.hasExtra("adb_testkey")) {
            val f = java.io.File(app.filesDir, "adb_testkey")
            if (i.getBooleanExtra("adb_testkey", false)) f.createNewFile() else f.delete()
            com.glasslauncher.tv.data.LocalShell.reset()
            com.glasslauncher.tv.data.Glog.i("QA: adb test key ${f.exists()}")
        }
        // One command over TV access, its answer and time in the log.
        if (i.getBooleanExtra("adb_probe", false)) Bg.io("glass-dev") {
            // (After this hook's own start of Glassio: a window coming up over Glassio, as from the TV access page.)
            kotlinx.coroutines.delay(3000)
            val t0 = android.os.SystemClock.uptimeMillis()
            val r = com.glasslauncher.tv.data.LocalShell.run(app, "echo ok")
            com.glasslauncher.tv.data.Glog.i("QA: adb probe ${android.os.SystemClock.uptimeMillis() - t0} ms: ${r.getOrNull()?.trim() ?: r.exceptionOrNull()}")
        }
        i.getIntExtra("net_fail_test", 0).takeIf { it > 0 }?.let { sec ->
            com.glasslauncher.tv.data.NetState.failUntil = System.currentTimeMillis() + sec * 1000L
            // (Emptied once the downloads under way have failed: emptied under them, a page waited for ever.)
            Bg.io("glass-dev") { Thread.sleep(2000); runCatching { coil3.SingletonImageLoader.get(app).let { l -> l.memoryCache?.clear(); l.diskCache?.clear() } } }
            com.glasslauncher.tv.data.Glog.i("QA: picture downloads fail for $sec s")
        }
        // A Home Assistant for cam_test ha://… (a test server; "-" back to the one set).
        i.getStringExtra("ha_test")?.let { v -> if (v == "-") { com.glasslauncher.tv.CamPlayer.haTestUrl = null; com.glasslauncher.tv.CamPlayer.haTestToken = null }
            else { com.glasslauncher.tv.CamPlayer.haTestUrl = v.substringBefore('|'); com.glasslauncher.tv.CamPlayer.haTestToken = v.substringAfter('|', "test") } }
        // QA: a whole copy of Glassio's data before a long test, and putting it back (FullBackup).
        if (i.getBooleanExtra("full_backup", false)) Bg.io("glass-dev") { runCatching { com.glasslauncher.tv.data.FullBackup.save(app) }.onFailure { com.glasslauncher.tv.data.Glog.w("QA full copy", it) } }
        i.getStringExtra("full_restore")?.let { n -> Bg.io("glass-dev") {
            runCatching { com.glasslauncher.tv.data.FullBackup.restore(app, java.io.File(app.getExternalFilesDir(null), java.io.File(n).name)) }.onFailure { com.glasslauncher.tv.data.Glog.w("QA full restore", it) }
        } }
        // QA: asks the other build (GitHub <-> Play) for its settings and logs what came, without restoring anything.
        if (i.getBooleanExtra("backup_other", false)) Bg.io("glass-dev") {
            val j = com.glasslauncher.tv.data.Backup.fromOtherGlassio(app)
            com.glasslauncher.tv.data.Glog.i("QA: other build's backup: " + (j?.let { o -> o.keys().asSequence().joinToString { k -> "$k=" + (o.optJSONObject(k)?.length() ?: 0) } } ?: "none"))
        }
        i.getStringExtra("cams_test")?.let { v ->
            com.glasslauncher.tv.MultiCamOverlay.testCams = if (v == "-") null
                else v.split(',').mapNotNull { e -> e.split('|', limit = 2).takeIf { it.size == 2 }?.let { com.glasslauncher.tv.ui.Cam(it[0], it[1]) } }
        }
        i.getStringExtra("cam_test")?.let { u -> com.glasslauncher.tv.HomeKeyService.instance?.testCamera(u) }
        if (i.getBooleanExtra("ha_cams", false)) Bg.io("glass-dev") {
            runCatching {
                val l = com.glasslauncher.tv.net.HaWatch.cameras(app.settings.haUrl, app.settings.haToken)
                com.glasslauncher.tv.data.Glog.i("HA cameras: " + l.joinToString { it.first })
                l.firstOrNull()?.let { com.glasslauncher.tv.data.Glog.i("HA stream ${it.first}: " + (com.glasslauncher.tv.net.HaWatch.streamUrl(app.settings.haUrl, app.settings.haToken, it.first)?.replace(Regex("/api/hls/[^/]+/"), "/api/hls/…/") ?: "none")) }
            }.onFailure { com.glasslauncher.tv.data.Glog.w("HA cameras", it) }
        }
        // A pretend song for looking at the music player, the island and music mode (cleared with fake_song=false).
        // Finds a trailer's streams (log: "Trailer …"): --es trailer_test <YouTube id>.
        i.getStringExtra("trailer_test")?.let { id -> Bg.io("glass-dev") {
            com.glasslauncher.tv.data.TrailerSource.resolve(id, 1080)?.let { r ->
                // The links (for testing their speed from a computer); written to the app's own folder.
                java.io.File(app.getExternalFilesDir(null), "trailer.txt").writeText(r.video + "\n" + (r.audio ?: "") + "\n" + r.subs.joinToString("\n") { "${it.lang} ${it.auto} ${it.url}" } + "\n")
            }
        } }
        // Finds a title's trailer the way the carousel does (log: "Trailer …"): --es trailer_find "<name>|<year>|<series|movie>|<imdb>".
        i.getStringExtra("trailer_find")?.let { q -> Bg.io("glass-dev") {
            val p = q.split('|')
            val r = com.glasslauncher.tv.ui.Trailers.prepare(app, p.getOrElse(2) { "series" }, p.getOrElse(3) { "" }, p.getOrElse(0) { "" }, p.getOrElse(1) { "" })
            com.glasslauncher.tv.data.Glog.i("Trailer find '${p[0]}': " + (r?.let { "${it.first} ${it.second?.height}p" } ?: "none (the picture stays)"))
        } }
        if (i.hasExtra("fake_song")) {
            if (i.getBooleanExtra("fake_song", false)) {
                val b = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(b).drawPaint(android.graphics.Paint().apply { shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, 0xFF7B61FF.toInt(), 0xFFFF6FA8.toInt(), android.graphics.Shader.TileMode.CLAMP) })
                com.glasslauncher.tv.data.NowPlaying.track.value = com.glasslauncher.tv.data.NowPlaying.Track("com.spotify.tv.android", i.getStringExtra("fake_title") ?: "Test song", "Test artist", "Album",
                    if (i.getBooleanExtra("fake_noart", false)) com.glasslauncher.tv.data.PlaceholderArt.of("Test song") else b,
                    200_000, 60_000, android.os.SystemClock.elapsedRealtime(), 1f, !i.getBooleanExtra("fake_paused", false))
            } else com.glasslauncher.tv.data.NowPlaying.track.value = null
        }
        if (i.getBooleanExtra("dump_media", false)) com.glasslauncher.tv.data.NowPlaying.dump()
        if (i.getBooleanExtra("dump_media", false)) com.glasslauncher.tv.data.NowPlaying.dumpSessions(app)
        if (i.getBooleanExtra("dump_channels", false)) Bg.io("glass-dev") { com.glasslauncher.tv.data.Channels.dump(app) }
        val st = app.settings
        // ---- Settings
        i.getStringArrayExtra("prefs")?.forEach { st.debugSet(it) }
        i.getStringExtra("pref_unset")?.let { st.debugUnset(it) }
        i.getStringExtra("pref")?.let { st.debugSet(it); com.glasslauncher.tv.ui.GlassTint.apply(st); com.glasslauncher.tv.ui.TextLook.bold.value = st.boldText; com.glasslauncher.tv.data.I18n.lang = st.lang }
        if (i.hasExtra("cam_room")) com.glasslauncher.tv.CamPlayer.roomOverride = i.getIntExtra("cam_room", 0)
        if (i.getBooleanExtra("settings_lose_focus", false)) com.glasslauncher.tv.ui.SettingsLoseFocus.intValue++
        i.getStringExtra("cameras")?.let { st.cameras = it.split(';').filter { l -> l.contains("://") } }
        i.getStringExtra("theme")?.let { st.applyTheme(it) }
        i.getStringExtra("perf_mode")?.let { if (it in setOf("auto", "lite", "performance", "balanced", "quality")) st.applyMode(it) }
        i.getStringExtra("ha_webhook")?.let { st.haWebhook = it; st.haOnLaunch = it.startsWith("http") }
        i.getStringExtra("reset_icon")?.let { app.icons.reset(it) }
        // An app back in the dock (or hidden): --es show_app <package> / --es hide_app <package>.
        i.getStringExtra("show_app")?.let { p -> st.hiddenApps = st.hiddenApps.filterNot { it == p } }
        i.getStringExtra("hide_app")?.let { p -> if (p !in st.hiddenApps) st.hiddenApps = st.hiddenApps + p }
        // The hidden apps in full (the settings dump cuts long values).
        if (i.getBooleanExtra("dump_look", false)) android.util.Log.i("GlassPrefs", "theme=${st.theme} dock=${st.dockStyle} tile=${st.tileStyle} clock=${st.bgClockStyle} island=${st.islandStyle} bg=${st.bgMode} saver=${st.saverMode} saverClock=${st.saverBigClock} blur=${st.blurRadius} clockGlass=${st.bgClockGlass} order=${st.dockOrder.joinToString(",")}")
        // A title's link in a streaming app: --es link_test "tt11280740:series:Severance[:Apple TV]" logs it;
        // with --ez link_open true it is opened as Play would; --ez link_forget true drops what is remembered.
        // A problem report without the remote: --es report "what went wrong" logs what would be sent (after the
        // scrubbing); with --ez report_send true it is sent to the relay marked as a test (label "test").
        i.getStringExtra("report")?.let { said ->
            val go = i.getBooleanExtra("report_send", false)
            Bg.io("glass-dev") {
                val R = com.glasslauncher.tv.data.Report
                val d = R.build(app, app.settings, app.updater.current, said, mapOf("Nuvio" to app.library.nuvio.connected))
                R.preview(d).lines().take(40).forEach { android.util.Log.i("GlassReport", it) }
                android.util.Log.i("GlassReport", "relay=${R.relay} signature=${d.signature.ifBlank { "-" }} qr=${R.issueLink(d).length} chars")
                if (go) R.send(app, d, test = true).let { r -> android.util.Log.i("GlassReport", "sent ok=${r.ok} #${r.number} ${r.url} ${r.note}") }
            }
        }
        // Continue Watching per app (see AppCatalogs.continueFor): --ez app_cw true logs, for each title in progress,
        // the app it belongs to (or "-"), from what is known now.
        // --es app_cw_as "Title=package": that title in progress counts as that app's until Glassio restarts ("-" clears).
        // --es app_cat "package=Catalog name": ticks or unticks that catalog for the app, as its Catalogs list does.
        i.getStringExtra("app_cw_as")?.let { v -> com.glasslauncher.tv.data.AppCatalogs.testOwner.let { m -> if (v == "-") m.clear() else m[v.substringBeforeLast('=')] = v.substringAfterLast('=') } }
        i.getStringExtra("app_cat")?.let { v ->
            val all = app.catalogs.catalogs.value
            all.firstOrNull { it.name == v.substringAfter('=') }?.let { c -> com.glasslauncher.tv.data.AppCatalogs.toggle(app.settings, all, v.substringBefore('='), c.key) }
                ?: android.util.Log.i("GlassAppCw", "no catalog named ${v.substringAfter('=')}")
        }
        // The Library's movement frame by frame (QA): --ez lib_trace true starts, false writes it to the log.
        // The trailer screensaver with no trailer found (QA): --ei saver_no_trailers <seconds> (then it finds them again
        // by itself, so it can be watched without coming back here: this hook brings Glassio to the front), 0 = off now.
        if (i.hasExtra("saver_no_trailers")) i.getIntExtra("saver_no_trailers", 0).let { sec ->
            com.glasslauncher.tv.ui.SaverTest.noTrailers.value = sec > 0
            if (sec > 0) Bg.io("glass-dev") { kotlinx.coroutines.delay(sec * 1000L); a.runOnUiThread { com.glasslauncher.tv.ui.SaverTest.noTrailers.value = false } }
        }
        if (i.hasExtra("lib_trace")) i.getBooleanExtra("lib_trace", false).let { on ->
            if (!on && com.glasslauncher.tv.ui.LibTrace.on.value) com.glasslauncher.tv.ui.LibTrace.dump()
            com.glasslauncher.tv.ui.LibTrace.on.value = on
        }
        if (i.getBooleanExtra("app_cw", false)) {
            val A = com.glasslauncher.tv.data.AppCatalogs; val N = com.glasslauncher.tv.data.NuvioLaunch
            val en = app.catalogs.enrichedItems.value
            app.cw.items.value.forEach { c ->
                val plat = (en[c.key] ?: c).platform
                val src = N.cwSource(c.key)
                android.util.Log.i("GlassAppCw", "${c.name} | source=${src ?: "-"} | platform=${plat.ifBlank { "-" }} | app=${A.appOf(N.sourceTarget(app, src)?.let { N.TARGETS[it] }, plat) { p -> N.installed(app, p) } ?: "-"}")
            }
            android.util.Log.i("GlassAppCw", "${app.cw.items.value.size} titles in progress; continueMode=${app.settings.continueMode} appCatalogsCw=${app.settings.appCatalogsCw}")
        }
        if (i.getBooleanExtra("link_forget", false)) com.glasslauncher.tv.data.PlatformLinks.forget(app)
        i.getStringExtra("link_test")?.let { v ->
            val p = v.split(':')
            val item = com.glasslauncher.tv.data.HeroItem(p[0], p[0], p.getOrElse(1) { "movie" }, p.getOrElse(2) { "" }, "", "", "", "", "", "", emptyList(), p.getOrElse(3) { "" })
            val open = i.getBooleanExtra("link_open", false)
            Bg.io("glass-dev") {
                val t = System.currentTimeMillis()
                val l = com.glasslauncher.tv.data.PlatformLinks.find(app, app.settings, item)
                com.glasslauncher.tv.data.Glog.i("Link ${p[0]}: ${l?.let { "${it.pkg} ${it.url}" } ?: "none"} (${System.currentTimeMillis() - t} ms)")
                if (open) a.runOnUiThread { com.glasslauncher.tv.data.PlatformLinks.openOr(app, app.settings, item) { com.glasslauncher.tv.data.Glog.i("Link ${p[0]}: opened the usual way") } }
            }
        }
        // The search without the remote (tag GlassSearch): --es search_words "words" puts them in the bar;
        // --es search_test "tt0111161:movie:The Shawshank Redemption" logs the places it can be watched in and how
        // many sources the add-ons give for Stremio (the first few as shown, never their addresses).
        i.getStringExtra("search_words")?.let { com.glasslauncher.tv.ui.SearchUi.query.value = it }
        // Low memory, pretended: the next N page pictures fail as if memory ran out (--ei oom_test N); the log shows
        // "Low memory making …" and Glassio must go on with a smaller picture.
        if (i.hasExtra("oom_test")) com.glasslauncher.tv.ui.PictureMemory.failNext = i.getIntExtra("oom_test", 0)
        // An icon moved in the dock's order without the remote: --es move_app "package:+1" (or -1 …).
        i.getStringExtra("move_app")?.let { v -> v.substringAfterLast(':').toIntOrNull()?.let { d -> app.apps.move(v.substringBeforeLast(':'), d) } }
        // The dock's list read again (after a setting that adds or removes one of Glassio's own icons): --ez reload_apps true.
        if (i.getBooleanExtra("reload_apps", false)) Bg.io("glass-dev") { if (app.settings.searchOn && app.settings.searchDock) app.apps.placeNearFront(com.glasslauncher.tv.ui.SearchUi.DOCK_PKG); app.apps.reload() }
        i.getStringExtra("search_test")?.let { v ->
            val p = v.split(':')
            val item = com.glasslauncher.tv.data.HeroItem("search:" + p[0], p[0], p.getOrElse(1) { "movie" }, p.getOrElse(2) { "" }, "", "", "", "", "", "", emptyList(), "")
            Bg.io("glass-dev") {
                val S = com.glasslauncher.tv.data.Search
                var t = System.currentTimeMillis()
                val found = S.titles(app.http, app.settings, item.name)
                android.util.Log.i("GlassSearch", "titles for \"${item.name}\": ${found.size} (${System.currentTimeMillis() - t} ms): " + found.take(5).joinToString { "${it.name} ${it.year} ${it.type}" })
                found.firstOrNull()?.let { f -> android.util.Log.i("GlassSearch", "first: ${f.id} -> ${S.resolve(app.http, app.settings, f)?.id ?: "no IMDb id"}") }
                t = System.currentTimeMillis()
                val places = S.places(app, app.settings, item) { }
                android.util.Log.i("GlassSearch", "places (${System.currentTimeMillis() - t} ms): " + places.joinToString { it.label + if (it.sources) " ›" else "" }.ifBlank { "none" })
                t = System.currentTimeMillis()
                val src = S.sources(app.http, app.settings, app.catalogs.allAddons(), item)
                android.util.Log.i("GlassSearch", "sources: ${src.size} (${System.currentTimeMillis() - t} ms)")
                src.take(6).forEach { android.util.Log.i("GlassSearch", "  " + it.line) }
            }
        }
        // Where the selection is (QA: --ez dump_focus true), one line in the log under GlassFocus.
        if (i.getBooleanExtra("dump_focus", false)) {
            val f = com.glasslauncher.tv.ui.DockFocus
            android.util.Log.i("GlassFocus", "screen=${a.screenName} hero=${f.hero} top=${f.top} dock=${f.dock} idx=${f.idx} cols=${f.cols} " +
                "musicStage=${com.glasslauncher.tv.ui.MusicUi.stageFocused} musicApps=${com.glasslauncher.tv.ui.MusicUi.appsFocused} " +
                "inputs=${com.glasslauncher.tv.ui.InputsUi.focused} search=${com.glasslauncher.tv.ui.SearchUi.focused} rows=${com.glasslauncher.tv.ui.ChannelsUi.focused} " +
                "profile=${com.glasslauncher.tv.data.Profiles.active(app)} home=${com.glasslauncher.tv.data.Profiles.home(app)} " +
                "chooser=${com.glasslauncher.tv.ui.ProfilesUi.open.value} chooserSel=${com.glasslauncher.tv.ui.ProfilesUi.sel.intValue} appCat=${com.glasslauncher.tv.data.AppCatalogs.pkg.value ?: "-"} slide=${com.glasslauncher.tv.data.AppCatalogs.slide.replace(' ', '_')} " +
                "dockPkg=${com.glasslauncher.tv.ui.ChannelsUi.dockPkg.value} page=${com.glasslauncher.tv.ui.HomePage.now} glassPass=${com.glasslauncher.tv.ui.VideoGlass.clear.value && com.glasslauncher.tv.ui.VideoFrames.showing.value} videoShowing=${com.glasslauncher.tv.ui.VideoFrames.showing.value} cams=${MultiCamOverlay.open.value} " +
                "writes=${app.settings.recentWrites.joinToString(",")}")
        }
        if (i.hasExtra("focus_watch")) focusWatch(a, i.getBooleanExtra("focus_watch", false))
        // QA: a crash on the main thread, to try the report offered after a crash.
        if (i.getBooleanExtra("crash_clear", false)) { java.io.File(app.filesDir, "last-crash.txt").delete(); com.glasslauncher.tv.data.Glog.i("QA: last crash cleared") }
        if (i.getBooleanExtra("crash_test", false)) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ throw RuntimeException("QA: a crash to try the crash report") }, 500)
        if (i.getBooleanExtra("dump_hidden", false)) android.util.Log.i("GlassPrefs", "hiddenApps(all)=" + st.hiddenApps.joinToString(","))
        i.getStringExtra("icon_pack")?.let { p ->
            if (p == "none") st.iconPack = "" else if (app.packs.installed().any { it.pkg == p }) st.iconPack = p
        }
        // How many icons each installed pack offers in the icon picker (logged as GlassPacks).
        if (i.getBooleanExtra("pack_counts", false)) Bg.io("glass-dev") {
            app.packs.installed().forEach { p -> android.util.Log.i("GlassPacks", "${p.pkg}: ${app.packs.drawableNames(p.pkg).size}") }
        }
        // A remote mapping action, run 1.5 s later as if its button was pressed: --es run_map folder|recents|app:<pkg>
        i.getStringExtra("run_map")?.let { act ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                com.glasslauncher.tv.data.RemoteMap.run(app, com.glasslauncher.tv.data.RemoteMap.Map(0, act, ""))
            }, 1500)
        }
        // ---- Measuring
        if (i.hasExtra("timing")) com.glasslauncher.tv.data.Glog.timing = i.getBooleanExtra("timing", false)
        if (i.hasExtra("video_ms")) com.glasslauncher.tv.ui.VideoFrames.interval = i.getLongExtra("video_ms", 66L)
        if (i.getBooleanExtra("nuvio_profiles", false)) com.glasslauncher.tv.data.Bg.io("glass-dev") {
            com.glasslauncher.tv.data.Glog.i("Nuvio profiles: " + app.library.nuvio.profileFields())
        }
        // Sync between TVs without the remote: --es sync_follow "address:port:code" pairs this TV with a main TV showing
        // that code, --ez sync_stop true stops following, --ez sync_now true takes the main TV's state, --ez sync_status true
        // logs where it stands.
        i.getStringExtra("sync_follow")?.let { spec ->
            val p = spec.split(':')
            com.glasslauncher.tv.data.Bg.io("glass-dev") {
                val r = com.glasslauncher.tv.data.PeerSync.pair(com.glasslauncher.tv.data.PeerSync.Found("main TV", p[0], p.getOrNull(1)?.toIntOrNull() ?: 9110), p.getOrElse(2) { "" })
                com.glasslauncher.tv.data.Glog.i("Sync follow: ${r ?: "paired"}")
            }
        }
        if (i.getBooleanExtra("sync_stop", false)) { com.glasslauncher.tv.data.PeerSync.unfollow(); com.glasslauncher.tv.data.Glog.i("Sync: stopped following") }
        if (i.getBooleanExtra("sync_now", false)) com.glasslauncher.tv.data.Bg.io("glass-dev") { com.glasslauncher.tv.data.Glog.i("Sync now: ${com.glasslauncher.tv.data.PeerSync.syncNow() ?: "ok"}") }
        if (i.getBooleanExtra("sync_status", false)) com.glasslauncher.tv.data.PeerSync.let { ps ->
            com.glasslauncher.tv.data.Glog.i("Sync status: role=${ps.role} mode=${ps.mode} main=${ps.peerName} status=${ps.status.value} followers=${ps.followers.keys}")
        }
        // Profiles for looking at the chooser on a one-profile account: --es profiles_fake "2:Kids:#FF9F0A,3:Guest:#34C759"
        // (added to the known ones; "-" forgets them again), --ez profile_ask true shows the chooser,
        // --ez profile_wake true does what a start of the TV does, --ei profile_forget N drops a profile's files.
        i.getStringExtra("profiles_fake")?.let { spec ->
            val P = com.glasslauncher.tv.data.Profiles
            val real = P.known(app).filter { it.id == P.home(app) }.ifEmpty { listOf(com.glasslauncher.tv.data.Profiles.Info(P.home(app), app.library.nuvio.profileName.ifBlank { "Main profile" })) }
            val fake = if (spec == "-") emptyList() else spec.split(',').mapNotNull { e -> val p = e.split(':'); p.getOrNull(0)?.toIntOrNull()?.let { com.glasslauncher.tv.data.Profiles.Info(it, p.getOrElse(1) { "Profile $it" }, color = p.getOrElse(2) { "" }) } }
            P.remember(app, (real + fake).distinctBy { it.id }.sortedBy { it.id })
            com.glasslauncher.tv.ui.ProfilesUi.keepList = fake.isNotEmpty()
        }
        // Nuvio's pictures instead of the ones chosen in Glassio, until Glassio restarts (nothing is changed or removed).
        if (i.hasExtra("nuvio_faces")) { com.glasslauncher.tv.data.NuvioAvatars.testOnly = i.getBooleanExtra("nuvio_faces", false); com.glasslauncher.tv.data.NuvioAvatars.rev.intValue++ }
        if (i.getBooleanExtra("profile_ask", false)) com.glasslauncher.tv.ui.ProfilesUi.show(app)
        if (i.getBooleanExtra("profile_wake", false)) com.glasslauncher.tv.ui.ProfilesUi.wake(app)
        i.getIntExtra("profile_forget", 0).takeIf { it > 0 }?.let { com.glasslauncher.tv.data.Glog.i("Profile $it forgotten: " + com.glasslauncher.tv.data.Profiles.forget(app, it)) }
        // Profiles: Glassio as another profile (--ei profile <number>; it starts again), and which one is in use.
        i.getIntExtra("profile", 0).takeIf { it > 0 }?.let { id ->
            if (com.glasslauncher.tv.data.Profiles.choose(app, id)) {
                com.glasslauncher.tv.data.Glog.i("Profile: starting again as $id")
                com.glasslauncher.tv.data.Bg.io("glass-restart") { kotlinx.coroutines.delay(300); com.glasslauncher.tv.data.Backup.restart(app) }
            }
        }
        // The Hue bridge's secure connection and its certificate pin, without touching a light.
        if (i.getBooleanExtra("hue_secure", false)) com.glasslauncher.tv.data.Bg.io("glass-dev") {
            com.glasslauncher.tv.data.Glog.i("Hue secure check: " + com.glasslauncher.tv.data.Hue.secureCheck(st.hueIp, st.hueUser))
        }
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
