package com.glasslauncher.tv.data

import android.content.Context

/**
 * Settings → System → Unneeded apps (#32, github build only): the TV's own apps that only report, advertise or install
 * other apps, turned off through the TV's adb (`pm disable-user`) and back on (`pm enable`). Nothing is uninstalled and
 * no app's data is cleared: a switch undoes it, and a factory reset brings everything back.
 *
 * Only the apps in [LIST] are ever offered: each is rated safe to turn off by the Universal Android Debloater's list
 * ("Recommended") or by more than one debloat guide for that TV, and none is needed to start the TV, for the remote,
 * Bluetooth, casting, the Play Store, updates, inputs or the TV's own home screen and settings (those are in [NEVER],
 * checked again before any command). A screensaver in use is never turned off. Telemetry blocking by DNS was not
 * built (his decision: a DNS that stops answering takes the whole TV offline).
 */
object Debloat {
    class App(val pkg: String, val title: String, val what: String)

    /** "pkg*" matches by the start of the name (the makers' installers carry the TV's code name). */
    val LIST = listOf(
        // Google TV / Android TV
        App("com.google.android.feedback", "Google feedback", "Sends feedback and problem reports to Google"),
        App("com.google.android.tv.bugreportsender", "Bug report sender", "Sends the TV's bug reports to Google"),
        App("com.google.android.apps.tv.netoscope", "Network diagnostics", "Google's connection checks and their reports"),
        App("com.google.android.adservices.api", "Ad services", "Android's ad measurement and ad topics"),
        App("com.google.android.ondevicepersonalization.services", "Ad personalisation", "Works out personalised ads on the TV"),
        App("com.google.android.federatedcompute", "Federated learning", "Trains Google's models with data from the TV"),
        App("com.google.android.play.games", "Google Play Games", "The games app and its sign-in"),
        App("com.google.android.videos", "Google TV purchases", "Films and series bought from Google: they can't be watched while it is off"),
        App("android.autoinstalls.config*", "Maker's app installer", "Installs the maker's chosen apps after a setup or a reset"),
        App("com.google.android.apps.tv.dreamx", "Ambient mode", "Google's screensaver (kept while it is the TV's screensaver)"),
        App("com.google.android.backdrop", "Backdrop", "The older Ambient screensaver (kept while it is the TV's screensaver)"),
        // Xiaomi
        App("com.miui.tv.analytics", "Xiaomi analytics", "Sends usage data to Xiaomi"),
        App("tv.alphonso.alphonso_eula", "Alphonso", "Recognises what is on screen, for ads"),
        // TCL
        App("com.tcl.bootadservice", "TCL start-up ads", "Ads shown when the TV starts"),
        App("com.tcl.overseasappshow", "TCL app promotions", "Suggested apps and promotions"),
        // Fire TV (from Fire TV debloat guides; not tried on a Fire TV here)
        App("com.amazon.device.metrics", "Amazon device metrics", "Sends usage data to Amazon"),
        App("com.amazon.client.metrics", "Amazon app metrics", "Sends app usage data to Amazon"),
        App("com.amazon.wirelessmetrics.service", "Amazon network metrics", "Sends Wi-Fi data to Amazon"),
        App("com.amazon.dp.logger", "Amazon logger", "Collects the device's logs for Amazon"),
    )

    /** Never touched, whatever a list says: the TV needs them (start, remote, sound, casting, store, updates, home). */
    val NEVER = setOf("android", "com.android.systemui", "com.android.tv.settings", "com.android.providers.tv", "com.android.providers.settings",
        "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
        "com.google.android.tungsten.setupwraith", "com.google.android.tv.axel", "com.google.android.tv.dfuservice", "com.google.android.tv.remote.service",
        "com.google.android.apps.mediashell", "com.google.android.chromecast.chromecastservice", "com.google.android.tvrecommendations",
        "com.google.android.katniss", "com.google.android.inputmethod.latin", "com.google.android.webview", "com.google.android.packageinstaller",
        "com.google.android.permissioncontroller", "com.sony.dtv.tvx", "com.tcl.fota.system", "com.tcl.framework.custom", "com.amazon.tv.launcher",
        "com.amazon.tv.settings.v2", "com.amazon.venezia")

    /** A listed app on this TV: its real package and whether it is on now. */
    class Found(val app: App, val pkg: String, val on: Boolean, val screensaver: Boolean)

    /** The listed apps installed here, read through the TV's adb; failure: no TV access. */
    fun read(ctx: Context): Result<List<Found>> = LocalShell.run(ctx, "pm list packages; echo =====; pm list packages -d; echo =====; settings get secure screensaver_components").map { out ->
        val parts = out.split("=====")
        fun pkgs(s: String?) = s.orEmpty().lines().map { it.trim().removePrefix("package:") }.filter { it.isNotBlank() }.toSet()
        val all = pkgs(parts.getOrNull(0)); val off = pkgs(parts.getOrNull(1)); val saver = parts.getOrNull(2).orEmpty()
        LIST.flatMap { a ->
            val names = if (a.pkg.endsWith("*")) all.filter { it.startsWith(a.pkg.dropLast(1)) } else listOf(a.pkg).filter { it in all }
            names.filter { it !in NEVER }.map { Found(a, it, it !in off, saver.contains("$it/")) }
        }
    }

    /** Turns one listed app off or back on; the answer is the TV's own ("new state: …"). */
    fun set(ctx: Context, pkg: String, on: Boolean): Result<Unit> {
        if (Store.PLAY || pkg in NEVER || LIST.none { a -> if (a.pkg.endsWith("*")) pkg.startsWith(a.pkg.dropLast(1)) else a.pkg == pkg })
            return Result.failure(IllegalArgumentException("Not on the list"))
        if (!Regex("[A-Za-z0-9_.]+").matches(pkg)) return Result.failure(IllegalArgumentException("Bad name"))
        return LocalShell.run(ctx, if (on) "pm enable $pkg" else "pm disable-user --user 0 $pkg").mapCatching { out ->
            if (!out.contains("new state", ignoreCase = true)) error(out.trim().take(120).ifBlank { "No answer" })
            Glog.i("Unneeded apps: $pkg " + if (on) "on" else "off")
            val mine = byGlassio(ctx)
            prefs(ctx).edit().putStringSet("off", if (on) mine - pkg else mine + pkg).apply()
        }
    }

    // (Its own file, not a "glass…" one: never in a backup, a sync or a report; it belongs to this TV.)
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("tv_apps_off", Context.MODE_PRIVATE)
    /** The apps Glassio turned off here (Turn them back on touches only these, never what was off before). */
    fun byGlassio(ctx: Context): Set<String> = prefs(ctx).getStringSet("off", emptySet()).orEmpty().toSet()
}
