package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONObject

/**
 * Opens a title inside a Nuvio app: Nuvio Legacy, the official Nuvio TV app, Nuvio Arc (fork), Nuvio Re-Shaped
 * , Nuvio One or Nuvio+IPTV (forks). "auto" (default) uses the first one installed, in that order.
 */
object NuvioLaunch {
    const val LEGACY_PKG = "space.nuvio.nativelegacy"
    const val OFFICIAL_PKG = "com.nuvio.tv"
    const val ARC_PKG = "com.nuvio.tv.test"
    const val RESHAPED_PKG = "com.nuvioreshaped.tv"
    /** Nuvio One (zoga2o3-ops/Nuvio-One): a fork of Nuvio TV with its own package; it reads the same nuvio:// links. */
    const val ONE_PKG = "app.onetube.tv"
    /** Nuvio+IPTV (homelessbrian/NuvioPlusIPTV): a fork of Nuvio TV with live TV, its own package, the same nuvio:// links. */
    const val IPTV_PKG = "com.nuvio.livetv"
    const val STREMIO_PKG = "com.stremio.one"

    /** Setting value → package, in the "auto" priority order. */
    val TARGETS = linkedMapOf(
        "legacy" to LEGACY_PKG, "official" to OFFICIAL_PKG, "arc" to ARC_PKG, "reshaped" to RESHAPED_PKG, "one" to ONE_PKG, "iptv" to IPTV_PKG, "stremio" to STREMIO_PKG,
    )
    val NAMES = mapOf(
        "auto" to "Auto", "legacy" to "Nuvio Legacy", "official" to "Nuvio (official)",
        "arc" to "Nuvio Arc", "reshaped" to "Nuvio Re-Shaped", "one" to "Nuvio One", "iptv" to "Nuvio+IPTV", "stremio" to "Stremio",
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
     * Starts the title in a Nuvio app instead of showing its page, where the app has a way in for that; its page
     * otherwise. The app picks the source itself, by its own settings.
     * - Nuvio Legacy: nuvio-legacy://play/<type>/<id> (seen on the Streamer: plays at once when it is running; from a
     *   cold start it stops at its own "Who's watching?" first).
     * - Nuvio and the forks built from it: its main screen with launchMode=stream opens the title's sources, where
     *   its own auto-play goes on (read in NuvioMedia/NuvioTV; a film only: an episode needs its own id).
     */
    fun play(ctx: Context, target: String, item: HeroItem): Boolean {
        val made = runCatching {
        val t = if (item.type.equals("movie", true)) "movie" else "series"
        val id = item.id.trim()
        val pkg = TARGETS.getValue(target)
        val i = when {
            target == "legacy" -> { NuvioWatch.opened(t, id)
                Intent(Intent.ACTION_VIEW, Uri.Builder().scheme("nuvio-legacy").authority("play").appendPath(t).appendPath(id).build()).setPackage(pkg) }
            target != "stremio" && t == "movie" -> (ctx.packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: ctx.packageManager.getLaunchIntentForPackage(pkg))
                ?.putExtra("launchMode", "stream")?.putExtra("contentId", id)?.putExtra("contentType", t)?.putExtra("videoId", id)?.putExtra("name", item.name)
                ?.putExtra("poster", item.poster)?.putExtra("backdrop", item.background)?.putExtra("logo", item.logo)
            else -> null
        }
        i?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }.onFailure { Glog.w("Play ${item.type} ${item.id} in $target", it) }.getOrNull() ?: return openIn(ctx, target, item)
        return runCatching { ctx.startActivity(made) }.onFailure { Glog.w("Play ${item.type} ${item.id} in $target", it) }.isSuccess
    }

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
        "jellyfin" to Jellyfin.PKG,
    )

    /** Each service's word and its app ("netflix" → Netflix's package), for telling which app a catalog's name means. */
    fun platformWords(): List<Pair<String, String>> = PLATFORMS

    /** The streaming app's package for [platform] (e.g. "Apple TV+"), installed or not; null if unknown. */
    fun platformPackage(platform: String): String? {
        val p = platform.lowercase()
        if (p.isBlank()) return null
        return PLATFORMS.firstOrNull { p.contains(it.first) }?.second
    }

    /** The installed streaming app for [platform], or null. */
    fun platformApp(ctx: Context, platform: String): String? = platformPackage(platform)?.takeIf { installed(ctx, it) }

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
            // Official Nuvio TV, Nuvio Arc, Re-Shaped, Nuvio One and Nuvio+IPTV all read nuvio://meta?type=…&id=…
            Uri.Builder().scheme("nuvio").authority("meta")
                .appendQueryParameter("type", t).appendQueryParameter("id", id.trim()).build()
        }
        return Intent(Intent.ACTION_VIEW, uri).setPackage(pkg).addFlags(flags)
    }

    /** Where a title in Continue Watching was watched ("Nuvio Legacy", "Nuvio", "Stremio", "Trakt" …), by its key; set by the app. */
    @Volatile var cwSource: (String) -> String? = { null }

    /** The app a Continue Watching title opens in when it should open where it was watched; null: the usual one. */
    fun sourceTarget(ctx: Context, source: String?): String? = when (source) {
        "Nuvio Legacy" -> "legacy".takeIf { installed(ctx, LEGACY_PKG) }
        // The Nuvio account: the official app or a fork that syncs with it, then Legacy.
        "Nuvio" -> listOf("official", "arc", "reshaped", "one", "iptv", "legacy").firstOrNull { installed(ctx, TARGETS.getValue(it)) }
        "Stremio" -> "stremio".takeIf { installed(ctx, STREMIO_PKG) }
        else -> null  // Trakt, Simkl: no app of their own
    }

    /** A Jellyfin server (#27), and the Jellyfin id of a title in progress there, by its key; set by the app. */
    @Volatile var jellyfin: Jellyfin? = null
    @Volatile var jellyfinCw: (String) -> String? = { null }

    fun open(ctx: Context, s: Settings, http: Http, item: HeroItem) {
        // Jellyfin: a title that comes from the server opens on its page in the Jellyfin app; with *Play opens
        // Jellyfin* any title the server has does too (found by its IMDb id). Otherwise as before.
        val jf = jellyfin?.takeIf { it.connected && Jellyfin.installed(ctx) }
        if (jf != null) {
            val direct = when {
                item.id.startsWith("jf:") -> item.id.removePrefix("jf:")
                s.cwOpenSource && cwSource(item.key) == "Jellyfin" -> jellyfinCw(item.key)
                else -> null
            }
            if (direct != null && Jellyfin.open(ctx, direct)) return
            // (What is known now: Play never waits for the server.)
            if (s.playJellyfin && item.id.startsWith("tt")) jf.peek(item.id)?.let { if (Jellyfin.open(ctx, it.id)) return }
        }
        // A title only a Jellyfin server knows can't open anywhere else.
        if (item.id.startsWith("jf:")) { Toast.makeText(ctx, I18n.tr("Install the Jellyfin app and sign in to open this title"), Toast.LENGTH_LONG).show(); return }
        openRest(ctx, s, http, item)
    }

    private fun openRest(ctx: Context, s: Settings, http: Http, item: HeroItem) {
        // Continue Watching (Settings → Continue Watching → Open where it was watched): that app.
        val own = if (s.cwOpenSource) sourceTarget(ctx, cwSource(item.key)) else null
        // Settings → Carousel → Play opens the streaming app: the title's page in Netflix, Apple TV… when one of the
        // installed apps has it; anything else opens as before.
        if (own == null && s.playInApp) PlatformLinks.openOr(ctx, s, item) { openUsual(ctx, s, http, item, null) }
        else openUsual(ctx, s, http, item, own)
    }

    private fun openUsual(ctx: Context, s: Settings, http: Http, item: HeroItem, own: String?) {
        try {
            ctx.startActivity(if (own != null) intentFor(own, item.type, item.id) else intent(ctx, s, item.type, item.id))
        } catch (e: Exception) {
            Glog.w("Open ${item.type} ${item.id} in ${packageName(ctx, s)}", e)
            Toast.makeText(ctx, "${NAMES[resolve(ctx, s)]} is not installed or can't open this title", Toast.LENGTH_LONG).show()
            return
        }
        if (s.haOnLaunch && s.haWebhook.startsWith("http")) {
            Bg.io("ha-hook") {
                runCatching {
                    http.post(s.haWebhook, JSONObject()
                        .put("event", "launch").put("title", item.name).put("type", item.type)
                        .put("id", item.id).put("app", packageName(ctx, s)))
                }.onFailure { Glog.w("Home Assistant webhook", it) }
            }
        }
    }

    fun testWebhook(s: Settings, http: Http): String = try {
        if (!s.haWebhook.startsWith("http")) "Set the webhook URL first"
        else "Home Assistant answered HTTP " + http.post(s.haWebhook, JSONObject().put("event", "test").put("source", "glassio"))
    } catch (e: Exception) { "Failed: ${e.message}" }
}
