package com.glasslauncher.tv.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Glassio profiles: one per profile of the Nuvio account, each with its own settings.
 *
 * - A profile's number is the Nuvio profile's number (1 is the account's main profile).
 * - What belongs to a profile is kept in that profile's own files; what belongs to the TV is kept once (see
 *   [isDevice] and [FILES]). The profile that was in use before profiles existed keeps the old files as they are
 *   (the "home" profile): nothing is moved or copied for it, so an update loses nothing.
 * - Another profile's files are named after it ("glass_p3", "glass_catalogs_p3"…). Backups take every file whose
 *   name starts with "glass", so they carry every profile.
 * - The profile in use is read once when Glassio starts. Changing it restarts Glassio (a second or two), so every
 *   part reads the other profile's files from the start; nothing has to be told apart while it runs.
 */
object Profiles {
    const val MAIN = 1
    /** Settings files kept per profile, besides the settings themselves ("glass", by key: see [isDevice]). */
    val FILES = listOf("glass_catalogs", "glass_cw", "glass_library", "titles_seen")

    private const val STORE = "glass_profiles"
    @Volatile private var activeId = 0
    @Volatile private var homeId = 0

    private fun store(ctx: Context): SharedPreferences = ctx.applicationContext.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    private fun load(ctx: Context) {
        if (activeId != 0) return
        synchronized(this) {
            if (activeId != 0) return
            val sp = store(ctx)
            if (!sp.contains("home")) {
                // First start with profiles: the Nuvio profile chosen so far (1 unless another was picked in
                // Settings → Connections) owns the files as they are.
                val was = ctx.applicationContext.getSharedPreferences("glass_nuvio", Context.MODE_PRIVATE).getInt("profile", MAIN).coerceAtLeast(1)
                sp.edit().putInt("home", was).putInt("active", was).putInt("autoId", was).apply()
            }
            homeId = sp.getInt("home", MAIN)
            activeId = sp.getInt("active", homeId).coerceAtLeast(1)
            // Nuvio's lists (Continue Watching, Library, add-ons) are read for the profile in use.
            val nv = ctx.applicationContext.getSharedPreferences("glass_nuvio", Context.MODE_PRIVATE)
            if (nv.getInt("profile", MAIN) != activeId) {
                val name = known(ctx).firstOrNull { it.id == activeId }?.name.orEmpty()
                nv.edit().putInt("profile", activeId).putString("profileName", name).apply()
            }
        }
    }

    /** The profile in use (since Glassio started). */
    fun active(ctx: Context): Int { load(ctx); return activeId }
    /** The profile that keeps the files from before profiles existed. */
    fun home(ctx: Context): Int { load(ctx); return homeId }

    /** The name of [base] ("glass", "glass_catalogs"…) for profile [id] when [home] is the home profile. */
    fun fileName(base: String, id: Int, home: Int): String = if (id == home) base else "${base}_p$id"

    /** A per-profile settings file of the profile in use (or of [id]). */
    fun prefs(ctx: Context, base: String, id: Int = active(ctx)): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(fileName(base, id, home(ctx)), Context.MODE_PRIVATE)

    // ───────────────────────────── what belongs to the TV ─────────────────────────────
    /**
     * Settings of the TV itself, the same for every profile: how the TV is set up and wired (performance and caches,
     * Hue, cameras and the doorbell, Home Assistant, the remote's buttons, the inputs, Nuvio's link, updates, start-up,
     * app locks, the keys of outside services), not how Glassio looks or what it shows.
     */
    private val DEVICE = setOf(
        // Performance and storage (what this TV can carry)
        "perfMode", "perfOwn", "heroAutoplayWhere", "pageScale", "memTier", "glassRes", "imageQuality", "prefetchDepth", "cacheMb", "saverCache", "saverCacheGb",
        "photoCacheMb", "bgAerialQuality", "saverQuality", "multiQuality", "cameraDecoder",
        // Cameras, the doorbell, Home Assistant
        "cameras", "cornerCamera", "cameraCycleSec", "cameraScale", "cameraCornerSec", "multiCams", "multiScale", "multiSec", "saverMultiCam",
        // The remote
        "keyMaps", "homeLong", "multiKey", "cornerKey",
        // Inputs
        "inputsIcon", "inputsHidden", "inputsOrder", "inputPlayer",
        // Nuvio's link to this TV, and what opens a title
        "nuvioPush", "nuvioToken", "nuvioTarget",
        // The TV's own things: start-up, the Home button, the phone page, updates, sync, backups, the system's screensaver
        "interceptHome", "a11yConsent", "bootStart", "phoneMinutes", "updateChannel", "recentsAdb", "setupDone", "tourSeen", "syncAll", "backupSignIns",
        "saverSys", "settingsLastCat", "settingsLastRow", "settingsLayout",
        // App locks (a profile must not be a way around them)
        "lockCode", "lockedApps", "lockHintOn", "lockHint",
        // Keys of outside services
        "tmdbToken", "unsplashKey", "redditAppId", "pexelsKey",
        // Where the TV is: the country its streaming offers are read for
        "watchCountry",
    )
    private val DEVICE_PREFIXES = listOf("hue", "doorbell", "ha")

    /** Whether [key] of the "glass" settings belongs to the TV (kept once) and not to a profile. */
    fun isDevice(key: String): Boolean = key in DEVICE || key == "channelsHue" ||
        DEVICE_PREFIXES.any { p -> key.startsWith(p) && key.length > p.length && key[p.length].isUpperCase() }

    // ───────────────────────────── a profile's first use ─────────────────────────────
    /**
     * What a new profile does not take from the home profile: what is personal (likes, hidden titles, picked artwork,
     * its picture) and the other accounts' lists (they are the home profile's person's: off until turned on).
     */
    private val NOT_COPIED = setOf("likes", "hiddenTitles", "artPicks", "settingsIcon", "musicLastPkg")
    private val OFF_AT_FIRST = setOf("cwTrakt", "cwStremio", "cwSimkl", "libraryTrakt", "librarySimkl", "libraryToTrakt", "libraryToSimkl", "catalogsStremio", "cwJellyfin", "libraryJellyfin")

    /** What a new profile's settings start with, from the home profile's [all] (kept apart for tests). */
    fun seed(all: Map<String, *>): Map<String, Any> {
        val out = HashMap<String, Any>()
        for ((k, v) in all) {
            if (v == null || isDevice(k) || k in NOT_COPIED) continue
            out[k] = if (k in OFF_AT_FIRST) false else v
        }
        OFF_AT_FIRST.forEach { out[it] = false }
        return out
    }

    /** Before a profile is used the first time: it starts as the home profile looks (theme, dock, catalogs), not empty. */
    private fun ensure(ctx: Context, id: Int) {
        val sp = store(ctx)
        if (id == home(ctx) || sp.getBoolean("made$id", false)) return
        val from = ctx.applicationContext.getSharedPreferences("glass", Context.MODE_PRIVATE)
        write(prefs(ctx, "glass", id), seed(from.all))
        val cat = ctx.applicationContext.getSharedPreferences("glass_catalogs", Context.MODE_PRIVATE).all
        write(prefs(ctx, "glass_catalogs", id), cat.filterValues { it != null }.mapValues { it.value as Any })
        // The icons made for the dock too (part of the look), but not the home profile's own picture.
        runCatching {
            val to = dir(ctx, "icons", id)
            dir(ctx, "icons", home(ctx)).listFiles().orEmpty().filter { it.isFile && it.name != "$SETTINGS_BUTTON_ICON.png" }.forEach { it.copyTo(java.io.File(to, it.name), overwrite = true) }
        }.onFailure { Glog.w("Profile $id: copying the icons", it) }
        sp.edit().putBoolean("made$id", true).commit()
        Glog.i("Profile $id: made from the home profile's look")
    }

    private fun write(sp: SharedPreferences, values: Map<String, Any>) {
        val ed = sp.edit()
        @Suppress("UNCHECKED_CAST")
        for ((k, v) in values) when (v) {
            is Boolean -> ed.putBoolean(k, v); is Int -> ed.putInt(k, v); is Long -> ed.putLong(k, v)
            is Float -> ed.putFloat(k, v); is String -> ed.putString(k, v); is Set<*> -> ed.putStringSet(k, v as Set<String>)
        }
        ed.commit()
    }

    // ───────────────────────────── choosing ─────────────────────────────
    /**
     * One profile as it is shown: Nuvio's number and name, its picture's address (empty: none), its colour in Nuvio
     * ("#RRGGBB", empty: none), and whether Nuvio keeps it behind a PIN.
     */
    data class Info(val id: Int, val name: String, val avatar: String = "", val color: String = "", val locked: Boolean = false,
        /** Nuvio's ready-made picture for it ("avatar_moss"; empty: none) and the colour Nuvio shows behind that picture. */
        val avatarId: String = "", val avatarBg: String = "")

    /** The profiles as last read from Nuvio (kept so the choice shows at once, also without a connection). */
    fun known(ctx: Context): List<Info> = runCatching {
        val sp = store(ctx)
        val a = JSONArray(sp.getString("known", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            val id = it.getInt("id")
            // (A name given in Glassio comes before Nuvio's.)
            Info(id, sp.getString("name$id", "").orEmpty().ifBlank { it.optString("name") }, it.optString("avatar"), it.optString("color"), it.optBoolean("locked"),
                it.optString("avatarId"), it.optString("avatarBg"))
        }
    }.getOrDefault(emptyList())

    /** The name given to [id] in Glassio (Settings → Connections); empty: Nuvio's name is shown. */
    fun nameOverride(ctx: Context, id: Int): String = store(ctx).getString("name$id", "").orEmpty()
    fun setName(ctx: Context, id: Int, name: String) { store(ctx).edit().apply { if (name.isBlank()) remove("name$id") else putString("name$id", name.take(40)) }.apply() }

    fun remember(ctx: Context, list: List<Info>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar).put("color", it.color).put("locked", it.locked)
            .put("avatarId", it.avatarId).put("avatarBg", it.avatarBg)) }
        store(ctx).edit().putString("known", a.toString()).apply()
    }

    /** A profile's own folder of files ("icons": its custom icons and its picture). */
    fun dir(ctx: Context, base: String, id: Int = active(ctx)): java.io.File =
        java.io.File(ctx.applicationContext.filesDir, fileName(base, id, home(ctx))).apply { mkdirs() }

    /** A profile's own picture (the settings button's picture is the same one), if it has one. */
    fun picture(ctx: Context, id: Int): java.io.File = java.io.File(dir(ctx, "icons", id), "$SETTINGS_BUTTON_ICON.png")

    /** At start: "auto" goes straight into [autoId]'s profile; "ask" shows the profiles first. */
    fun startMode(ctx: Context): String = store(ctx).getString("startMode", "auto") ?: "auto"
    fun setStartMode(ctx: Context, mode: String) { store(ctx).edit().putString("startMode", if (mode == "ask") "ask" else "auto").apply() }
    fun autoId(ctx: Context): Int = store(ctx).getInt("autoId", home(ctx))
    fun setAutoId(ctx: Context, id: Int) { store(ctx).edit().putInt("autoId", id.coerceAtLeast(1)).apply() }

    /** Every profile this TV keeps something for: the home profile and the ones opened since. */
    fun all(ctx: Context): List<Int> = (listOf(home(ctx)) + store(ctx).all.keys.mapNotNull { k -> k.removePrefix("made").takeIf { k.startsWith("made") }?.toIntOrNull() }).distinct().sorted()

    /** A new start of the TV since this was last asked (Glassio itself starting again doesn't count). */
    fun newBoot(ctx: Context): Boolean {
        val boot = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()
        val sp = store(ctx)
        val same = kotlin.math.abs(sp.getLong("boot", 0L) - boot) < 120_000L
        if (!same) sp.edit().putLong("boot", boot).apply()
        return !same
    }

    /**
     * When the TV starts or wakes after a long sleep, with [signedIn] to Nuvio: -1 to ask who is watching, a profile's
     * number to go to it (the automatic one, when another is in use), 0 for nothing. (Kept apart for tests.)
     */
    fun onWake(mode: String, active: Int, autoId: Int, ids: List<Int>, signedIn: Boolean): Int = when {
        !signedIn || ids.size < 2 -> 0
        mode == "ask" -> -1
        autoId != active && autoId in ids -> autoId
        else -> 0
    }

    /** Forgets everything Glassio keeps for [id] (not the home profile, nor the one in use). */
    fun forget(ctx: Context, id: Int): Boolean {
        if (id == home(ctx) || id == active(ctx)) return false
        (FILES + "glass").forEach { ctx.applicationContext.deleteSharedPreferences(fileName(it, id, home(ctx))) }
        runCatching { dir(ctx, "icons", id).deleteRecursively() }
        store(ctx).edit().remove("made$id").apply()
        return true
    }

    /**
     * Makes [id] the profile in use from the next start of Glassio (the caller restarts it: Backup.restart).
     * False when it already is.
     */
    fun choose(ctx: Context, id: Int): Boolean {
        if (id < 1 || id == active(ctx)) return false
        ensure(ctx, id)
        store(ctx).edit().putInt("active", id).commit()
        return true
    }
}
