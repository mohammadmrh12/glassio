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
    private val SKIP = setOf("glass_home_key")
    const val FOLDER = "Glassio"

    private fun prefNames(ctx: Context): List<String> =
        File(ctx.applicationInfo.dataDir, "shared_prefs").listFiles().orEmpty()
            .map { it.name.removeSuffix(".xml") }.filter { it.startsWith("glass") && it !in SKIP }.sorted()

    fun export(ctx: Context): JSONObject {
        val all = JSONObject()
        for (name in prefNames(ctx)) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
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
            .put("made", System.currentTimeMillis()).put("prefs", all)
    }

    /** Replaces the settings files with the backup's. Returns how many files were restored. */
    fun restore(ctx: Context, j: JSONObject): Int {
        require(j.optInt("glassio") == 1) { "Not a Glassio backup" }
        val all = j.getJSONObject("prefs")
        var n = 0
        for (name in all.keys()) {
            if (!name.startsWith("glass") || name in SKIP) continue
            val o = all.getJSONObject(name)
            val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
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
            ed.commit(); n++
        }
        return n
    }

    private fun stamp() = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())

    /** Saves a backup in Download/Glassio on the TV (and a copy in Glassio's own files). Returns where. */
    fun saveToTv(ctx: Context): String {
        val text = export(ctx).toString()
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
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                return "Download/$FOLDER/$name"
            }
        }
        return "Glassio's files (Download wasn't available)"
    }

    /** The newest backup on the TV: Download/Glassio (also older installs' files, through ADB) or Glassio's own copy. */
    fun latestOnTv(ctx: Context): JSONObject? {
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'Glassio-backup%'", null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c ->
                if (c.moveToFirst()) {
                    val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                    ctx.contentResolver.openInputStream(uri)?.use { return JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
                }
            }
        }
        runCatching {
            val out = LocalShell.run(ctx, "f=\$(ls -t /sdcard/Download/$FOLDER/Glassio-backup*.json /sdcard/Download/Glassio-backup*.json 2>/dev/null | head -1); [ -n \"\$f\" ] && cat \"\$f\"; true").getOrNull()
            if (!out.isNullOrBlank() && out.trimStart().startsWith("{")) return JSONObject(out.trim())
        }
        return File(ctx.filesDir, "backup-latest.json").takeIf { it.isFile }?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
    }

    /** Restarts Glassio so every part reads the restored settings. */
    fun restart(ctx: Context) {
        val i = (ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: Intent(ctx, com.glasslauncher.tv.MainActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pi = PendingIntent.getActivity(ctx, 77, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        ctx.getSystemService(AlarmManager::class.java)?.set(AlarmManager.RTC, System.currentTimeMillis() + 700, pi)
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
