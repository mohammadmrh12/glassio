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
        i.getIntExtra("open_settings", -1).takeIf { it >= 0 }?.let { app.requestSettings.intValue = it; app.requestSettingsRev.intValue++ }
        if (i.getBooleanExtra("open_menu", false)) { app.requestSettings.intValue = 100; app.requestSettingsRev.intValue++ }
        if (i.getBooleanExtra("open_library", false)) { app.requestSettings.intValue = 101; app.requestSettingsRev.intValue++ }
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
        i.getStringExtra("icon_pack")?.let { p ->
            if (p == "none") st.iconPack = "" else if (app.packs.installed().any { it.pkg == p }) st.iconPack = p
        }
        // ---- Measuring
        if (i.hasExtra("timing")) com.glasslauncher.tv.data.Glog.timing = i.getBooleanExtra("timing", false)
        if (i.hasExtra("video_ms")) com.glasslauncher.tv.ui.VideoFrames.interval = i.getLongExtra("video_ms", 66L)
        if (i.hasExtra("dbg_hide")) com.glasslauncher.tv.ui.DebugHide.intValue = i.getIntExtra("dbg_hide", 0)
        if (i.hasExtra("page_glass")) com.glasslauncher.tv.ui.PageGlass.on.value = i.getBooleanExtra("page_glass", true)
        if (i.getBooleanExtra("dump_prefs", false)) st.dumpAll().toSortedMap().forEach { (k, v) ->
            android.util.Log.i("GlassPrefs", "$k=${v.toString().take(120)}")
        }
    }
}
