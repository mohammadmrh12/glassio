package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent

/**
 * The TV-side settings Glassio needs, set through the TV's own debugging connection (ADB, [LocalShell]):
 * the Home button service, Glassio as the home app and as the TV screensaver, and starting with the TV.
 * Used by the setup's System step (Settings → System has the same switches one by one).
 */
object SystemSetup {
    private fun pkg(ctx: Context) = ctx.packageName

    /** Glassio may use the TV's debugging connection (asks "Allow USB debugging?" on the TV the first time). Blocking. */
    fun adb(ctx: Context): Boolean = LocalShell.run(ctx, "echo ok").getOrNull()?.contains("ok") == true

    fun developerOn(ctx: Context): Boolean = runCatching {
        android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 &&
            android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
    }.getOrDefault(false)

    /**
     * Fire TV: apps may no longer use the TV's own ADB (Fire OS 7.6.6.9 / 8.1.0.3 and later; computers and phones still
     * may), so Glassio can't be allowed there (#14). A computer gives Glassio the right to change the TV's settings
     * instead ([FIRE_TV_COMMANDS]), and Glassio then turns its services on by itself.
     */
    fun isFireTv(): Boolean = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /** What to type on a computer (ADB) once, for a Fire TV. */
    const val FIRE_TV_COMMANDS = "adb shell pm grant com.glasslauncher.tv android.permission.WRITE_SECURE_SETTINGS  ·  " +
        "adb shell appops set com.glasslauncher.tv SYSTEM_ALERT_WINDOW allow"

    /** Glassio may change the TV's secure settings itself (given once from a computer, or over its own ADB). */
    fun secureOk(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun homeKeyOn(ctx: Context): Boolean = runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_accessibility_services").orEmpty()
            .contains("${pkg(ctx)}/com.glasslauncher.tv.HomeKeyService")
    }.getOrDefault(false)

    /** Turns on the Home button service. Blocking. */
    fun enableHomeKey(ctx: Context): Boolean {
        val svc = "${pkg(ctx)}/com.glasslauncher.tv.HomeKeyService"
        // Without ADB (Fire TV): the right to change the secure settings is enough.
        if (secureOk(ctx)) runCatching {
            val cr = ctx.contentResolver
            val parts = android.provider.Settings.Secure.getString(cr, "enabled_accessibility_services").orEmpty().split(':').filter { it.isNotBlank() }
            if (svc !in parts) android.provider.Settings.Secure.putString(cr, "enabled_accessibility_services", (parts + svc).joinToString(":"))
            android.provider.Settings.Secure.putString(cr, "accessibility_enabled", "1")
            if (homeKeyOn(ctx)) return true
        }
        val cmd = "cur=\$(settings get secure enabled_accessibility_services); " +
            "case \"\$cur\" in *$svc*) ;; null|'') settings put secure enabled_accessibility_services $svc ;; " +
            "*) settings put secure enabled_accessibility_services \"\$cur:$svc\" ;; esac; " +
            "settings put secure accessibility_enabled 1; appops set ${pkg(ctx)} ACCESS_RESTRICTED_SETTINGS allow 2>/dev/null; " +
            "pm grant ${pkg(ctx)} android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; true"
        LocalShell.run(ctx, cmd)
        return homeKeyOn(ctx)
    }

    fun isHomeApp(ctx: Context): Boolean {
        // Google TV keeps answering a Home intent with its own launcher even when Glassio holds the home role (set
        // here): the role is what counts (the check showed "not set" while it was).
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching {
            if (ctx.getSystemService(android.app.role.RoleManager::class.java)?.isRoleHeld(android.app.role.RoleManager.ROLE_HOME) == true) return true
        }
        return runCatching {
            ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName == pkg(ctx)
        }.getOrDefault(false)
    }

    /** Glassio as the TV's home app (the Home button then lands here too). Blocking. */
    fun makeHomeApp(ctx: Context): Boolean {
        LocalShell.run(ctx, "cmd package set-home-activity ${pkg(ctx)}/com.glasslauncher.tv.MainActivity >/dev/null 2>&1; true")
        return isHomeApp(ctx)
    }

    fun isScreensaver(ctx: Context): Boolean = runCatching {
        val cr = ctx.contentResolver
        android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 &&
            android.provider.Settings.Secure.getString(cr, "screensaver_components").orEmpty().startsWith(pkg(ctx))
    }.getOrDefault(false)

    /** Glassio as the TV screensaver (in every app), as Settings → Screensaver does it. Blocking. */
    fun makeScreensaver(ctx: Context): Boolean {
        val dream = "${pkg(ctx)}/com.glasslauncher.tv.saver.GlassDreamService"
        if (secureOk(ctx)) runCatching {
            val cr = ctx.contentResolver
            android.provider.Settings.Secure.putString(cr, "screensaver_components", dream)
            android.provider.Settings.Secure.putInt(cr, "screensaver_enabled", 1)
            android.provider.Settings.Secure.putInt(cr, "screensaver_activate_on_sleep", 1)
        }
        LocalShell.run(ctx, "settings put secure screensaver_components $dream && settings put secure screensaver_enabled 1 && settings put secure screensaver_activate_on_sleep 1; " +
            "t=\$(settings get system screen_off_timeout); sl=\$(settings get secure sleep_timeout); case \$sl in ''|null|-1|0) sl=1800000;; esac; " +
            "if [ \"\$t\" -ge \"\$sl\" ] 2>/dev/null || [ \"\$t\" -ge 1800000 ] 2>/dev/null; then settings put system screen_off_timeout 300000; fi; " +
            "device_config put attention_manager_service keep_screen_on_enabled false 2>/dev/null; true")
        return isScreensaver(ctx)
    }

    /** Glassio opens when the TV starts. Blocking. */
    fun startWithTv(ctx: Context, st: Settings): Boolean {
        st.bootStart = true
        return LocalShell.run(ctx, "appops set ${pkg(ctx)} SYSTEM_ALERT_WINDOW allow; true").isSuccess
    }
}
