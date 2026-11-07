package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Opens a title inside a Nuvio app: Nuvio Legacy, the official Nuvio TV app, Nuvio Arc (fork) or
 * Nuvio Re-Shaped. "auto" (default) uses the first one installed, in that order.
 */
object NuvioLaunch {
    const val LEGACY_PKG = "space.nuvio.nativelegacy"
    const val OFFICIAL_PKG = "com.nuvio.tv"
    const val ARC_PKG = "com.nuvio.tv.test"
    const val RESHAPED_PKG = "com.nuvioreshaped.tv"
    const val STREMIO_PKG = "com.stremio.one"

    /** Setting value → package, in the "auto" priority order. */
    val TARGETS = linkedMapOf(
        "legacy" to LEGACY_PKG, "official" to OFFICIAL_PKG, "arc" to ARC_PKG, "reshaped" to RESHAPED_PKG, "stremio" to STREMIO_PKG,
    )
    val NAMES = mapOf(
        "auto" to "Auto", "legacy" to "Nuvio Legacy", "official" to "Nuvio (official)",
        "arc" to "Nuvio Arc", "reshaped" to "Nuvio Re-Shaped", "stremio" to "Stremio",
    )

    fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** The target actually used: the chosen one, or for "auto" the first installed (Legacy if none). */
    fun resolve(ctx: Context, s: Settings): String {
        val t = s.nuvioTarget
        if (t in TARGETS) return t
        return TARGETS.entries.firstOrNull { installed(ctx, it.value) }?.key ?: "legacy"
    }

    fun packageName(ctx: Context, s: Settings) = TARGETS.getValue(resolve(ctx, s))

    fun intent(ctx: Context, s: Settings, type: String, id: String): Intent = intentFor(resolve(ctx, s), type, id)

    /** The installed apps a title can be opened in (Nuvio's versions and Stremio), as target → name. */
    fun installedTargets(ctx: Context): List<Pair<String, String>> =
        TARGETS.entries.filter { installed(ctx, it.value) }.map { it.key to NAMES.getValue(it.key) }

    /** Opens the title in one particular app ([target] as in [TARGETS]). */
    fun openIn(ctx: Context, target: String, item: HeroItem): Boolean = runCatching { ctx.startActivity(intentFor(target, item.type, item.id)) }
        .onFailure { Glog.w("Open in $target", it); Toast.makeText(ctx, "${NAMES[target]} can't open this title", Toast.LENGTH_LONG).show() }.isSuccess

    /**
     * Streaming apps by the names TMDB gives the platform (network) a title is on: name keyword → package.
     * They are opened at their home page (they don't take a TMDB or IMDb id).
     */
    private val PLATFORMS = listOf(
        "netflix" to "com.netflix.ninja", "apple tv" to "com.apple.atve.androidtv.appletv", "prime" to "com.amazon.amazonvideo.livingroom",
        "amazon" to "com.amazon.amazonvideo.livingroom", "disney" to "com.disney.disneyplus", "hbo" to "com.wbd.stream", "max" to "com.wbd.stream",
        "hulu" to "com.hulu.livingroomplus", "paramount" to "com.cbs.ott", "peacock" to "com.peacocktv.peacockandroid",
        "shahid" to "net.mbc.shahidTV", "youtube" to "com.google.android.youtube.tv", "crunchyroll" to "com.crunchyroll.crunchyroid",
        "osn" to "com.osn.go", "starz" to "com.bydeluxe.d3.android.program.starz", "mubi" to "com.mubi",
    )

    /** The installed streaming app for [platform] (e.g. "Apple TV+"), or null. */
    fun platformApp(ctx: Context, platform: String): String? {
        val p = platform.lowercase()
        if (p.isBlank()) return null
        return PLATFORMS.firstOrNull { p.contains(it.first) }?.second?.takeIf { installed(ctx, it) }
    }

    fun openApp(ctx: Context, pkg: String): Boolean = runCatching {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) ?: error("no launch intent")
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    private fun intentFor(target: String, type: String, id: String): Intent {
        if (target == "legacy") NuvioWatch.opened(type, id.trim())
        // No CLEAR_TASK: a running Nuvio is brought forward and opens the title at once, without
        // restarting (no splash). If Nuvio is not running it starts normally.
        // Stremio is the exception: a running Stremio takes the link as a new intent and ignores it (it just came to
        // the front on its home page), so its task starts again and the title's page opens (still quick: warm start).
        val flags = if (target == "stremio") Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pkg = TARGETS.getValue(target)
        val t = if (type.equals("movie", true)) "movie" else "series"
        val uri = if (target == "stremio") {
            // Stremio's own link: stremio:///detail/movie/tt0111161 (its page for the title, with your add-ons' streams).
            Uri.parse("stremio:///detail/$t/${id.trim()}/${if (t == "movie") id.trim() else ""}".trimEnd('/'))
        } else if (target == "legacy") {
            Uri.Builder().scheme("nuvio-legacy").authority("meta").appendPath(t).appendPath(id.trim()).build()
        } else {
            // Official Nuvio TV, Nuvio Arc and Re-Shaped all read nuvio://meta?type=…&id=…
            Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", t).appendQueryParameter("id", id.trim()).build()
        }
        return Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(flags)
    }

    fun open(ctx: Context, s: Settings, http: Http, item: HeroItem) {
        try {
            ctx.startActivity(intent(ctx, s, item.type, item.id))
        } catch (e: Exception) {
            Glog.w("Open ${item.type} ${item.id} in ${packageName(ctx, s)}", e)
            Toast.makeText(ctx, "${NAMES[resolve(ctx, s)]} is not installed or can't open this title", Toast.LENGTH_LONG).show()
            return
        }
        if (s.haOnLaunch && s.haWebhook.startsWith("http")) {
            thread(name = "ha-hook") {
                runCatching {
                    http.post(s.haWebhook, JSONObject()
                        .put("event", "launch").put("title", item.name).put("type", item.type)
                        .put("id", item.id).put("app", packageName(ctx, s)))
                }
            }
        }
    }

    fun testWebhook(s: Settings, http: Http): String = try {
        if (!s.haWebhook.startsWith("http")) "Set the webhook URL first"
        else "Home Assistant answered HTTP " + http.post(s.haWebhook, JSONObject().put("event", "test").put("source", "glassio"))
    } catch (e: Exception) { "Failed: ${e.message}" }
}
