package com.glasslauncher.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The pictures of the Nuvio account's profiles, kept on the TV so they show at once and without a connection.
 *
 * A profile's picture in Nuvio is either one it uploaded (`avatar_url`) or, far more often, one of Nuvio's ready-made
 * ones (`avatar_id`, e.g. "avatar_moss"). The ready-made ones are listed by `get_avatar_catalog` (id, name, file,
 * the colour behind it) and served from the backend's public `avatars` storage; the supporters' ones are in
 * `get_member_profile_avatar_catalog` and need the account to download. This is the same way Nuvio's own TV app
 * finds them.
 */
object NuvioAvatars {
    /** Goes up when a picture arrived: what shows them reads it and draws again. */
    val rev = mutableIntStateOf(0)
    /** Test only (--ez nuvio_faces true, until Glassio restarts): Nuvio's pictures show even where one was chosen in Glassio. */
    @Volatile var testOnly = false

    private fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "nuvio_avatars").apply { mkdirs() }
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)

    /** The file a profile's picture from Nuvio is kept in: the uploaded one first, then the ready-made one. Null: it has neither. */
    fun fileOf(ctx: Context, p: Profiles.Info): File? = when {
        p.avatar.startsWith("http") -> File(dir(ctx), "url_" + hash(p.avatar))
        p.avatarId.isNotBlank() -> File(dir(ctx), "id_" + safe(p.avatarId))
        else -> null
    }

    /** The picture if it is on the TV. */
    fun ready(ctx: Context, p: Profiles.Info): File? = fileOf(ctx, p)?.takeIf { it.isFile && it.length() > 0 }

    private data class Entry(val path: String, val bg: String, val member: Boolean)

    /** One of Nuvio's lists as id → (file, colour); the file's name carries its version ("moss-v1.png"). */
    internal fun parse(list: org.json.JSONArray, member: Boolean): Map<String, Triple<String, String, Boolean>> =
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("id").isNotBlank() && it.optString("storage_path").isNotBlank() }
            .associate { it.optString("id") to Triple(it.optString("storage_path"), if (it.isNull("bg_color")) "" else it.optString("bg_color"), member) }

    private fun download(url: String, headers: Map<String, String>, to: File): Boolean {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 15000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val tmp = File(to.parentFile, "." + to.name + ".tmp")
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            // Only a real picture is kept (an error page saved as one would show as nothing for good).
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(tmp.absolutePath, o)
            if (o.outWidth <= 0 || tmp.length() > 8L * 1024 * 1024) { tmp.delete(); error("not a picture") }
            if (!tmp.renameTo(to)) { tmp.copyTo(to, overwrite = true); tmp.delete() }
            return true
        } finally { c.disconnect() }
    }

    /**
     * Fetches the pictures of [list] that aren't on the TV yet and fills in the colour behind a ready-made one.
     * Blocking. What was fetched before is never asked for again: the lists are only read when a picture is missing
     * or its colour isn't known. Returns [list] with the colours.
     */
    fun fetch(ctx: Context, nuvio: NuvioAccount, list: List<Profiles.Info>): List<Profiles.Info> {
        val known = Profiles.known(ctx).associateBy { it.id }
        var got = false
        var catalog: Map<String, Triple<String, String, Boolean>>? = null
        fun entry(id: String): Entry? {
            val c = catalog ?: (nuvio.avatarCatalog(false)?.let { parse(it, false) }.orEmpty()).also { catalog = it }
            val e = c[id] ?: run {
                // Not one of the usual ones: a supporter's picture.
                val all = c + nuvio.avatarCatalog(true)?.let { parse(it, true) }.orEmpty()
                catalog = all; all[id]
            }
            return e?.let { Entry(it.first, it.second, it.third) }
        }
        val out = list.map { p ->
            val f = fileOf(ctx, p) ?: return@map p
            if (p.avatar.startsWith("http")) {
                if (!f.isFile) runCatching { download(p.avatar, emptyMap(), f) }.onSuccess { got = true }.onFailure { Glog.w("Nuvio picture of profile ${p.id}", it) }
                return@map p
            }
            // The colour is kept with the profile: with the picture on the TV nothing is asked.
            val before = known[p.id]?.takeIf { it.avatarId == p.avatarId }?.avatarBg.orEmpty()
            if (f.isFile && before.isNotBlank()) return@map p.copy(avatarBg = before)
            val e = entry(p.avatarId) ?: run { Glog.w("Nuvio picture ${p.avatarId} of profile ${p.id}: not in Nuvio's lists"); return@map p }
            if (!f.isFile) runCatching {
                val (url, headers) = nuvio.avatarRequest(e.path, e.member) ?: error("signed out")
                download(url, headers, f)
            }.onSuccess { got = true }.onFailure { Glog.w("Nuvio picture ${p.avatarId} of profile ${p.id}", it) }
            p.copy(avatarBg = e.bg)
        }
        // Pictures no profile uses any more (it was changed in Nuvio) go.
        val keep = out.mapNotNull { fileOf(ctx, it)?.name }.toSet()
        if (out.isNotEmpty()) dir(ctx).listFiles()?.forEach { if (it.name !in keep) it.delete() }
        if (got) rev.intValue++
        return out
    }

    /** At start: the profiles' pictures and names as they are in Nuvio now (one request; nothing when signed out). */
    fun refresh(ctx: Context, nuvio: NuvioAccount) {
        if (!nuvio.connected) return
        val before = Profiles.known(ctx)
        val fresh = nuvio.profileInfos()?.takeIf { it.isNotEmpty() } ?: return
        Profiles.remember(ctx, fresh)
        if (fresh.map { it.avatar + it.avatarId + it.avatarBg } != before.map { it.avatar + it.avatarId + it.avatarBg }) rev.intValue++
    }
}
