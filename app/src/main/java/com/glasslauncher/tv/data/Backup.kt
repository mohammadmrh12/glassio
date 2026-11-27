package com.glasslauncher.tv.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Backup and restore of everything Glassio keeps in its settings files: every setting, the add-ons and catalogs,
 * the Library and Continue Watching lists, and the accounts and tokens (Nuvio, Stremio, Trakt, Simkl, TMDB…).
 * Pictures and videos in the backgrounds folder and custom icons are not included (they can be big).
 * The file is private to whoever holds it: it contains your sign-ins.
 */
object Backup {
    // (Settings → Sync's pairing belongs to this TV: restored on another, it would follow without being asked.)
    private val SKIP = setOf("glass_home_key", "glass_peer")

    /** What a backup carries to another TV, and what to know first (Settings → System and the phone page). */
    const val MOVES = "Moves: every setting (theme, dock order and hidden apps, icon pack, channels, music, Hue, cameras, remote mapping), " +
        "add-ons and catalogs, the Library and Continue Watching lists, and the accounts with their sign-ins (Nuvio, Stremio, Trakt, Simkl, TMDB, Home Assistant)."
    const val STAYS = "Doesn't move: icons you made yourself, background pictures, videos and photos, and TV access (ADB) with the Home button service: " +
        "allow those again on the other TV. Apps that aren't installed there are left out of the dock, and the performance settings go back to Auto."
    const val WARN = "Restoring replaces everything on this TV (nothing is merged). Both TVs then share the same sign-ins: a service may sign one of them " +
        "out (sign in again there if it does). A remote's button mapping may need setting again for another remote. Keep the file private: it holds your sign-ins."
    const val FOLDER = "Glassio"

    /** The sign-ins and keys in each settings file (left out of a backup made without them, and kept on restore). */
    private val SECRETS = mapOf(
        "glass" to setOf("tmdbToken", "unsplashKey", "redditAppId", "haToken", "nuvioToken", "hueUser", "doorbellToken", "lockCode", "pexelsKey"),
        "glass_nuvio" to setOf("access", "refresh", "client", "server", "serverKey", "email", "exp"),
        "glass_stremio" to setOf("authKey", "email"),
        "glass_trakt" to setOf("access", "refresh", "expires", "user"),
        "glass_simkl" to setOf("token", "refresh", "client", "expires", "user"),
        "glass_jellyfin" to setOf("server", "token", "userId", "user", "serverName", "seeded"),
    )
    private fun secret(file: String, key: String) = SECRETS[file]?.contains(key) == true

    private fun prefNames(ctx: Context): List<String> =
        File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .map { it.name.removeSuffix(".xml") }.filter { it.startsWith("glass") && it !in SKIP }.sorted()

    /** [signIns] false: everything but the sign-ins and keys (a restore then keeps the ones on that TV). */
    fun export(ctx: Context, signIns: Boolean = true): JSONObject {
        val all = JSONObject()
        for (name in prefNames(ctx)) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (!signIns && secret(name, k)) continue
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                } ?: continue
                o.put(k, e)
            }
            all.put(name, o)
        }
        return JSONObject().put("glassio", 1).put("version", com.glasslauncher.tv.BuildConfig.VERSION_NAME)
            .put("made", System.currentTimeMillis()).put("device", android.os.Build.MODEL).put("signIns", signIns).put("prefs", all)
    }

    /** Replaces the settings files with the backup's. Returns how many files were restored. */
    fun restore(ctx: Context, j: JSONObject): Int {
        require(j.optInt("glassio") == 1) { "Not a Glassio backup" }
        val all = j.getJSONObject("prefs")
        val signIns = j.optBoolean("signIns", true)
        var n = 0
        for (name in all.keys()) {
            if (!name.startsWith("glass") || name in SKIP) continue
            val o = all.getJSONObject(name)
            val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
            // (A backup without sign-ins: this TV's own stay.)
            val kept = if (signIns) emptyMap() else sp.all.filterKeys { secret(name, it) }
            val ed = sp.edit().clear()
            for ((k, v) in kept) when (v) {
                is Boolean -> ed.putBoolean(k, v); is Int -> ed.putInt(k, v); is Long -> ed.putLong(k, v)
                is Float -> ed.putFloat(k, v); is String -> ed.putString(k, v)
                is Set<*> -> ed.putStringSet(k, v.map { it.toString() }.toSet())
            }
            // (The agreement to the accessibility service is given on each TV, by the person in front of it.)
            val agreed = sp.getBoolean("a11yConsent", false)
            for (k in o.keys()) {
                if (k == "a11yConsent") continue
                val e = o.optJSONObject(k) ?: continue
                when (e.optString("t")) {
                    "b" -> ed.putBoolean(k, e.optBoolean("v"))
                    "i" -> ed.putInt(k, e.optInt("v"))
                    "l" -> ed.putLong(k, e.optLong("v"))
                    "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
                    "s" -> ed.putString(k, e.optString("v"))
                    "set" -> ed.putStringSet(k, e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet())
                }
            }
            if (agreed) ed.putBoolean("a11yConsent", true)
            ed.commit(); n++
        }
        // A profile this TV has and the backup doesn't goes too (restoring replaces everything, nothing is merged).
        for (name in prefNames(ctx)) if (Regex(".*_p\\d+").matches(name) && !all.has(name)) { ctx.deleteSharedPreferences(name); n++ }
        // From another TV: the performance settings were made for that TV (a 4 GB box's caches on a 2 GB TV made it
        // swap and stutter). This one goes back to Auto, which picks them for its own memory and graphics chip.
        if (j.optString("device") != android.os.Build.MODEL) {
            ctx.getSharedPreferences("glass", Context.MODE_PRIVATE).edit().putString("perfMode", "auto").commit()
            Settings(ctx).applyMode("auto")
        }
        return n
    }

    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())

    /** Saves a backup in Download/Glassio on the TV (and a copy in Glassio's own files). Returns where. */
    fun saveToTv(ctx: Context): String {
        val text = export(ctx, Settings(ctx).backupSignIns).toString()
        File(ctx.filesDir, "backup-latest.json").writeText(text)
        val name = "Glassio-backup-${stamp()}.json"
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$FOLDER")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (uri != null) {
                val out = ctx.contentResolver.openOutputStream(uri) ?: error("Download isn't writable")
                out.use { it.write(text.toByteArray()) }
                return "Download/$FOLDER/$name"
            }
        }
        return "Glassio's files (Download wasn't available)"
    }

    /**
     * The newest backup on the TV. The Play build first takes the GitHub build's settings when it is installed (handed
     * over by it, see BackupProvider: its files in Download can't be seen from here); then Download/Glassio (also
     * older installs' files, through ADB) or Glassio's own copy; the GitHub build last asks the Play build.
     */
    fun latestOnTv(ctx: Context): JSONObject? {
        if (Store.PLAY) fromOtherGlassio(ctx)?.let { return it }
        return fromThisTv(ctx) ?: if (!Store.PLAY) fromOtherGlassio(ctx) else null
    }

    private fun fromThisTv(ctx: Context): JSONObject? {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Glassio-backup%'", null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    ctx.contentResolver.openInputStream(uri)?.use { return JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
                }
            }
        }.onFailure { Glog.w("Backups in Downloads", it) }
        runCatching {
            val out = LocalShell.run(ctx, "f=\$(ls -t /sdcard/Download/$FOLDER/Glassio-backup*.json /sdcard/Download/Glassio-backup*.json 2>/dev/null | head -1); [ -n \"\$f\" ] && cat \"\$f\"; true").getOrNull()
            if (!out.isNullOrBlank() && out.trimStart().startsWith("{")) return JSONObject(out.trim())
        }.onFailure { Glog.w("Backups through the shell", it) }
        return File(ctx.filesDir, "backup-latest.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
    }

    /** The other build's settings (GitHub ↔ Play), asked of it directly, only when it really is Glassio ([Certs]). */
    fun fromOtherGlassio(ctx: Context): JSONObject? {
        val pkg = Certs.otherPkg(ctx) ?: return null
        return runCatching {
            ctx.contentResolver.query(android.net.Uri.parse("content://${Certs.authority(pkg)}/settings"), null, null, null, null)?.use { c ->
                if (c.moveToFirst()) JSONObject(c.getString(0)) else null
            }
        }.onFailure { Glog.w("Settings from Glassio ($pkg)", it) }.getOrNull()?.also { Glog.i("Backup: the settings of Glassio ($pkg) on this TV") }
    }

    /**
     * Restarts Glassio so every part reads the restored settings (or the other profile's): **one** fresh start.
     *
     * It was "an alarm in 0.7 s, then end the process". On the TV that gave two starts: Android brought the screen
     * back by itself half a second after the process ended, and the alarm (which the TV delivers late, ~5 s) then
     * started it again on a cleared task: the whole home screen built twice, the selection on the first app, then
     * lost and given to Play or back to the first app (his report after changing profile), and frames of 160-540 ms.
     * Now the fresh start is asked for before the process ends, so the TV starts that one. The alarm stays as a
     * safety net for a TV that doesn't (a start from the background refused): without "clear the task", so on a
     * Glassio already up it only arrives as a new intent, and the new Glassio cancels it as it starts ([restarted]).
     */
    fun restart(ctx: Context) {
        fun intent(fresh: Boolean) = (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(ctx, com.glasslauncher.tv.MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or (if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0))
        val asked = runCatching { ctx.startActivity(intent(true)) }.onFailure { Glog.w("Restart: the fresh start", it) }.isSuccess
        val pi = PendingIntent.getActivity(ctx, 77, intent(false), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        ctx.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, System.currentTimeMillis() + (if (asked) 2500 else 700), pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /** Glassio is up again: the safety net of [restart] is no longer needed (it would bring Glassio over an app opened meanwhile). */
    fun restarted(ctx: Context) {
        runCatching {
            val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(ctx, com.glasslauncher.tv.MainActivity::class.java)
            PendingIntent.getActivity(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let { ctx.getSystemService(AlarmManager::class.java)?.cancel(it); it.cancel() }
        }
    }
}
