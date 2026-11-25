package com.glasslauncher.tv.data

import android.content.Context
import com.glasslauncher.tv.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * A problem report from the TV (Settings → About → Report a problem): a short description with the version, the TV,
 * a few settings, Glassio's last log lines and the last crash. It becomes an issue on GitHub, which is public, so
 * everything passes through [scrub] first and the person sees exactly what goes before sending.
 *
 * It is sent to a small relay (tools/report-worker, a Cloudflare Worker) that opens the issue: the TV holds no
 * GitHub key. The relay's address comes from local.properties (REPORT_URL); a build without it, or a relay that
 * doesn't answer, shows a QR code that opens a new issue with a short summary on the phone instead.
 */
object Report {
    val relay: Boolean get() = BuildConfig.REPORT_URL.startsWith("https://") || BuildConfig.REPORT_URL.startsWith("http://")

    const val MAX_DESCRIPTION = 500
    private const val MAX_LOG = 120
    private const val HIDDEN = "[hidden]"

    /** [signature]: the same crash gives the same one (the relay adds to its issue instead of opening another); empty without a crash. */
    data class Data(val description: String, val version: String, val device: String, val android: String, val profile: String,
        val settings: String, val log: String, val crash: String, val signature: String)

    // ---- Nothing private leaves the TV ----

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Camera and stream links whole (their address, port, path and password are all private).
        Regex("(?i)\\b(rtsps?|rtmps?|onvif|smb|ftp)://\\S+") to "[stream link]",
        // A name and password inside any other link.
        Regex("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s/@:]+(:[^\\s/@]*)?@") to "$1$HIDDEN@",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+") to "[email]",
        // Sign-in tokens (three parts with dots) and "Bearer …".
        Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{4,}") to "[token]",
        Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}") to "$1 [token]",
        // Anything named like a secret, whatever its value: token=…, "apikey": "…", password: …
        Regex("(?i)([\"']?[A-Za-z0-9_.-]*(token|secret|passw(or)?d|pwd|api[_-]?key|apikey|auth|cookie|session|credential|client[_-]?id|\\bkey|\\bpin|\\bcode)[A-Za-z0-9_.-]*[\"']?\\s*[=:]\\s*)(\\[hidden\\]|\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])]+)") to "$1$HIDDEN",
        // The same inside a longer name (lockCode=…, unsplashKey=…); the app's version number is not a secret.
        Regex("(\\b[A-Za-z0-9_.-]*(?<!version)(Code|Pin|Key)\\b[\"']?\\s*[=:]\\s*)(\\[hidden\\]|\"[^\"]*\"|'[^']*'|[^\\s,;&}\\])]+)") to "$1$HIDDEN",
        // The network's name.
        Regex("(?i)(\\b(b?ssid|wi-?fi name|network name)\\b[\"']?\\s*[=:]?\\s*)(\\[hidden\\]|\"[^\"]*\"|'[^']*'|[^\\s,;]+)") to "$1$HIDDEN",
        // Hardware addresses, then network addresses (every one: a home's public address is private too).
        Regex("(?i)\\b[0-9a-f]{2}([:-][0-9a-f]{2}){5}\\b") to "[mac]",
        Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(:\\d{1,5})?\\b") to "[address]",
        // (IPv6: a "::" in it, or five groups and more. A time like 17:40:12 has neither.)
        Regex("(?i)(?<![0-9a-f:.])(?=[0-9a-f:]*::|([0-9a-f]{1,4}:){4})[0-9a-f]{0,4}(:[0-9a-f]{0,4}){2,7}(%[a-z0-9]+)?(?![0-9a-f:])") to "[address]",
        Regex("(?i)\\b[a-z0-9-]+\\.(local|lan|home|internal)\\b") to "[local name]",
        // Long strings of letters and digits are keys more often than not.
        Regex("\\b[A-Za-z0-9_-]{32,}\\b") to HIDDEN,
        // A person's folder on a computer or the TV.
        Regex("(/Users/|/home/|C:\\\\Users\\\\)[^/\\\\\\s]+") to "$1$HIDDEN",
    )

    /** [text] without anything that could identify a person, a home network or an account. Applied to every part of a report. */
    fun scrub(text: String): String = RULES.fold(text) { t, (re, to) -> re.replace(t, to) }

    // ---- What goes in ----

    /** The settings that say how Glassio is set up (looks, modes, sizes): names and plain values, never text a person typed. */
    private val SETTINGS = listOf("theme", "dockStyle", "dockPos", "tileStyle", "dockColumns", "bgMode", "bgClockStyle", "saverMode", "islandStyle",
        "menuStyle", "menuMode", "settingsIcon", "slideAnim", "heroAutoplay", "transitionMs", "perfMode", "memTier", "glassRes", "imageQuality",
        "pageScale", "prefetchDepth", "refraction", "libraryColumns", "libraryPlatforms", "playInApp", "continueMode", "updateChannel",
        "nuvioTarget", "interceptHome", "descLang", "bgVideos", "saverInHome", "searchOn", "saverQuality", "photoSource",
        // (An app's rows and the pages around them, #30.)
        "channelsOn", "channelsLayout", "channelsBg", "channelsLow", "appCatalogsOn", "musicOn", "musicMode", "stagePageBg", "videoGlass", "musicPageBg",
        // (What stands in each app's place, and whose rows an app shows: package names only.)
        "appStage", "channelsFrom")

    /** The exception's kind and the first of Glassio's own lines in it: the same crash again gives the same text. */
    fun crashKey(crash: String): String {
        val lines = crash.lines().map { it.trim() }.filter { it.isNotBlank() }
        val head = lines.firstOrNull { !it.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) }?.substringBefore(':').orEmpty()
        val frame = lines.firstOrNull { it.startsWith("at com.glasslauncher.") }?.substringBefore('(').orEmpty()
        return if (head.isBlank()) "" else "$head|$frame"
    }

    fun signatureOf(crash: String): String = crashKey(crash).takeIf { it.isNotBlank() }
        ?.let { k -> MessageDigest.getInstance("SHA-1").digest(k.toByteArray()).joinToString("") { "%02x".format(it) }.take(12) }.orEmpty()

    fun build(ctx: Context, s: Settings, version: String, description: String, signedIn: Map<String, Boolean>): Data {
        val all = s.dumpAll()
        val set = SETTINGS.mapNotNull { k -> all[k]?.let { v -> "$k=" + v.toString().take(40) } }.joinToString("  ")
        val crash = runCatching { java.io.File(ctx.filesDir, "last-crash.txt").takeIf { it.isFile }?.readText() }.getOrNull().orEmpty().take(3000)
        val dm = ctx.resources.displayMetrics
        val known = Profiles.known(ctx).size.coerceAtLeast(1)
        return Data(
            description = scrub(description.trim().take(MAX_DESCRIPTION)),
            version = "$version (${BuildConfig.VERSION_CODE})",
            device = scrub("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · ${dm.widthPixels}×${dm.heightPixels}"),
            android = "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
            // (Numbers only: a profile's name is a person's name.)
            profile = "profile ${Profiles.active(ctx)} of $known · signed in: " + signedIn.filterValues { it }.keys.joinToString(", ").ifBlank { "nothing" },
            settings = scrub(set),
            log = scrub(Glog.recent(MAX_LOG).joinToString("\n")),
            crash = scrub(crash),
            signature = signatureOf(crash),
        )
    }

    /** What the person is shown before sending: every part, as it will be on GitHub. */
    fun preview(d: Data): String = buildString {
        append(d.description).append("\n\n")
        append("Glassio ").append(d.version).append('\n').append(d.device).append('\n').append(d.android).append('\n').append(d.profile).append("\n\n")
        append("Settings\n").append(d.settings.ifBlank { "(defaults)" }).append("\n\n")
        if (d.crash.isNotBlank()) append("Last crash\n").append(d.crash.trim()).append("\n\n")
        append("Log (").append(d.log.lines().count { it.isNotBlank() }).append(" lines)\n").append(d.log.ifBlank { "(empty)" })
    }

    // ---- Sending ----

    /** A random number made once for this install (not the TV's serial or any hardware id): the relay limits reports per TV by it. */
    private fun installId(ctx: Context): String {
        val sp = ctx.getSharedPreferences("report", Context.MODE_PRIVATE)
        return sp.getString("id", null) ?: java.util.UUID.randomUUID().toString().also { sp.edit().putString("id", it).apply() }
    }

    class Sent(val ok: Boolean, val url: String, val number: Int, val note: String)

    /** Sends it to the relay. Blocking. [test]: marked as a test there (label "test"). */
    fun send(ctx: Context, d: Data, test: Boolean = false): Sent {
        if (!relay) return Sent(false, "", 0, "No relay in this build")
        return runCatching {
            val body = JSONObject().put("description", d.description).put("version", d.version).put("device", d.device).put("android", d.android)
                .put("profile", d.profile).put("settings", d.settings).put("log", d.log).put("crash", d.crash).put("signature", d.signature)
                .put("install", installId(ctx)).put("test", test).toString().toByteArray()
            val c = URL(BuildConfig.REPORT_URL.trimEnd('/') + "/report").openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 20000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "Glassio/${d.version.substringBefore(' ')}")
            try {
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                val o = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (code in 200..299 && o.optBoolean("ok")) Sent(true, o.optString("url"), o.optInt("number"), if (o.optBoolean("added")) "Added to the report of the same crash" else "")
                else Sent(false, "", 0, o.optString("error").ifBlank { "The relay answered HTTP $code" }.take(140))
            } finally { c.disconnect() }
        }.getOrElse { Glog.w("Sending a problem report", it); Sent(false, "", 0, "Couldn't reach the relay") }
    }

    /**
     * A link that opens a new issue on GitHub with a short summary filled in, for the phone (a QR code holds little:
     * the description, the version and the TV, no log).
     */
    fun issueLink(d: Data): String {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val title = d.description.lineSequence().firstOrNull().orEmpty().take(70).ifBlank { "Problem report" }
        val body = d.description.take(300) + "\n\n---\nGlassio ${d.version}\n${d.device}\n${d.android}\n" +
            (if (d.signature.isNotBlank()) "crash: ${crashLine(d.crash)}\n" else "") + "(sent from the TV's QR code: no log attached)"
        return "${Updater.REPO_URL}/issues/new?labels=bug&title=${e(title)}&body=${e(body)}"
    }

    private fun crashLine(crash: String) = crash.lines().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) }.orEmpty().take(120)
}
