package com.glasslauncher.tv.net

import com.glasslauncher.tv.data.Bg
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * Tiny LAN-only web server for "Manage from phone" (the page itself is [PhonePage]). It runs while Settings →
 * System → Manage from phone is open (and for as long after as chosen there), and every request must carry the
 * one-time token embedded in the QR code. Secrets (tokens, keys, passwords) are write-only.
 */
class PhoneServer(private val app: GlassApp, private val token: String) {
    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start(): Int? {
        for (port in 9090..9099) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), 8) }
                socket = s; running = true
                Bg.io("glass-phone-server") { loop(s) }
                return port
            } catch (_: Exception) { }
        }
        return null
    }

    private val busy = java.util.concurrent.atomic.AtomicInteger()
    /** One upload at a time (two into the same file would mix). */
    private val uploading = Any()

    fun stop() { running = false; runCatching { socket?.close() }; socket = null }

    private fun loop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            // Each request on its own (a big upload no longer held the page still until it ended); a few at once.
            if (busy.incrementAndGet() > 6) { busy.decrementAndGet(); runCatching { c.close() }; continue }
            Bg.io("glass-phone-req") {
                try { c.soTimeout = 15000; handle(c) } catch (_: Exception) { } finally { runCatching { c.close() }; busy.decrementAndGet() }
            }
        }
    }

    private fun readLine(i: InputStream): String? {
        val o = ByteArrayOutputStream()
        while (o.size() < 8192) {
            val b = i.read()
            if (b < 0) return if (o.size() == 0) null else o.toString("UTF-8")
            if (b == '\n'.code) return o.toString("UTF-8").trimEnd('\r')
            o.write(b)
        }
        return null
    }

    private fun query(q: String, key: String) = q.split('&').firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }.orEmpty()

    private fun handle(c: Socket) {
        val input = java.io.PushbackInputStream(c.getInputStream())
        // A browser trying HTTPS first (Safari upgrades a name to HTTPS): closed at once, so it falls back to HTTP
        // without waiting (it held the connection until the 15 s timeout).
        val first = input.read()
        if (first < 0 || first == 0x16) return
        input.unread(first)
        val req = readLine(input) ?: return
        val parts = req.split(' ')
        if (parts.size < 2) return
        val method = parts[0].uppercase(); val target = parts[1]
        var len = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("content-length", true)) len = line.substring(idx + 1).trim().toIntOrNull() ?: 0
        }
        val path = target.substringBefore('?'); val q = target.substringAfter('?', "")
        if (!MessageDigest.isEqual(query(q, "t").toByteArray(), token.toByteArray())) { respond(c, 403, "text/plain", "Forbidden"); return }
        // The page opened by IP checks that the phone finds the TV by its name before moving there.
        if (method == "GET" && path == "/api/ping") { respond(c, 200, "application/json", "{\"ok\":true}", "Access-Control-Allow-Origin: *\r\n"); return }
        if (method == "GET" && path == "/") { respond(c, 200, "text/html", PhonePage.PAGE.replace("__T__", token).replace("__C__", top)); return }
        // A web app on the phone's home screen: its manifest (opens this page, with its code) and its icon.
        if (method == "GET" && path == "/manifest.webmanifest") { respond(c, 200, "application/manifest+json", manifest().toString()); return }
        if (method == "GET" && path == "/icon.png") { respond(c, 200, "image/png", icon, "Cache-Control: max-age=86400\r\n"); return }
        // The settings button's picture: one button that sends a picture from the phone (the QR code in Settings → Menu & themes).
        if (method == "GET" && path == "/button") { respond(c, 200, "text/html", PhonePage.BUTTON); return }
        // The page's background: Glassio's own soft lights (the setup's picture), made once.
        if (method == "GET" && path == "/bg.jpg") { respond(c, 200, "image/jpeg", lights, "Cache-Control: max-age=86400\r\n"); return }
        if (method == "GET" && path == "/api/state") { respond(c, 200, "application/json", state().toString()); return }
        if (method == "GET" && path == "/api/backup") {
            respond(c, 200, "application/json", com.glasslauncher.tv.data.Backup.export(app, app.settings.backupSignIns).toString(1),
                "Content-Disposition: attachment; filename=\"Glassio-backup.json\"\r\n"); return
        }
        if (method != "POST") { respond(c, 404, "text/plain", "Not found"); return }
        if (path == "/api/bg/upload") {
            // Straight to the backgrounds folder, never held in memory (videos can be hundreds of MB).
            val msg = runCatching { synchronized(uploading) { bgUpload(query(q, "name"), query(q, "use") == "1", input, len) } }
            respond(c, 200, "application/json", msg.fold({ JSONObject().put("ok", true).put("message", it).toString() }, { err(it.message ?: "Upload failed") }))
            return
        }
        val max = if (path == "/api/icon/upload" || path == "/api/restore") 8 * 1024 * 1024 else 64 * 1024
        if (len !in 0..max) { respond(c, 400, "application/json", err("Too large")); return }
        val body = ByteArray(len)
        var read = 0
        while (read < len) { val n = input.read(body, read, len - read); if (n < 0) break; read += n }
        try {
            val msg = if (path == "/api/icon/upload") {
                icon(query(q, "pkg"), body.copyOf(read))
            } else {
                val j = runCatching { JSONObject(String(body, 0, read, Charsets.UTF_8)) }.getOrDefault(JSONObject())
                post(path, j)
            }
            respond(c, 200, "application/json", JSONObject().put("ok", true).put("message", msg).toString())
        } catch (e: Exception) {
            respond(c, 200, "application/json", err(e.message ?: "Failed"))
        }
    }

    private fun icon(pkg: String, bytes: ByteArray): String {
        require(pkg.isNotBlank()) { "Missing app" }
        app.icons.setFromBytes(pkg, bytes)
        return "Icon updated"
    }

    private val bg = com.glasslauncher.tv.ui.CustomBackground
    @Volatile private var downloading = ""

    /** A safe file name in the backgrounds folder, with an extension Glassio can show. */
    private fun bgFile(raw: String, fallbackExt: String = ""): java.io.File {
        var name = raw.substringAfterLast('/').substringBefore('?').replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(80)
        var ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in bg.exts && ext !in bg.videoExts) { ext = fallbackExt; name = (name.ifBlank { "background" }.substringBeforeLast('.')) + "." + ext }
        require(ext in bg.exts || ext in bg.videoExts) { "Use a picture (jpg, png, webp) or a video (mp4, mkv, webm, mov)" }
        if (name.startsWith(".")) name = "background$name"
        return java.io.File(bg.folder(app), name)
    }

    private fun useFile(f: java.io.File) {
        if (f.extension.lowercase() in bg.videoExts) { app.settings.bgVideo = f.absolutePath; app.settings.bgMode = "video" }
        else { app.settings.bgImage = f.absolutePath; app.settings.bgMode = "image" }
    }

    private fun bgUpload(name: String, use: Boolean, input: InputStream, len: Int): String {
        val f = bgFile(name)
        val video = f.extension.lowercase() in bg.videoExts
        require(len > 0) { "Empty file" }
        require(len <= (if (video) 1500 else 40) * 1024 * 1024) { if (video) "Videos up to 1.5 GB" else "Pictures up to 40 MB" }
        require(len + 200L * 1024 * 1024 < (f.parentFile?.usableSpace ?: Long.MAX_VALUE)) { "Not enough space on the TV" }
        val part = java.io.File(f.parentFile, f.name + ".part")
        var left = len.toLong()
        part.outputStream().buffered(256 * 1024).use { o ->
            val buf = ByteArray(256 * 1024)
            while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n }
        }
        if (left > 0) { part.delete(); error("The upload stopped before the end") }
        f.delete(); check(part.renameTo(f)) { "Couldn't save the file" }
        if (use) useFile(f)
        return "Saved ${f.name} on the TV" + if (use) " and set as the background" else ""
    }

    /** Downloads a picture or video link into the backgrounds folder (in the background), then uses it. */
    @Synchronized private fun bgDownload(url: String, use: Boolean): String {
        require(url.startsWith("http")) { "The link must start with http" }
        check(downloading.isEmpty()) { "Already downloading $downloading" }
        val guess = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        val first = runCatching { bgFile(url) }.getOrNull()
        downloading = first?.name ?: "the link"
        Bg.io("glass-bg-download") {
            try {
                var c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 30000; c.instanceFollowRedirects = true
                val type = c.contentType.orEmpty().lowercase()
                val ext = when { guess in bg.exts || guess in bg.videoExts -> guess; "png" in type -> "png"; "webp" in type -> "webp"; "image" in type -> "jpg"
                    "webm" in type -> "webm"; "matroska" in type -> "mkv"; "quicktime" in type -> "mov"; "video" in type -> "mp4"; else -> "" }
                val f = first ?: bgFile("background-" + System.currentTimeMillis() / 1000, ext)
                downloading = f.name
                val part = java.io.File(f.parentFile, f.name + ".part")
                check(c.responseCode in 200..299) { "HTTP ${c.responseCode}" }
                // Not bigger than an upload may be, and never filling the TV (a link to a huge file filled it).
                val video = f.extension.lowercase() in bg.videoExts
                val cap = (if (video) 1500L else 40L) * 1024 * 1024
                val free = f.parentFile?.usableSpace ?: Long.MAX_VALUE
                val size = c.contentLengthLong
                check(size <= cap) { if (video) "Videos up to 1.5 GB" else "Pictures up to 40 MB" }
                check(size < 0 || size + 200L * 1024 * 1024 < free) { "Not enough space on the TV" }
                try {
                    c.inputStream.use { i -> part.outputStream().buffered(256 * 1024).use { o ->
                        val buf = ByteArray(256 * 1024); var total = 0L
                        while (true) {
                            val n = i.read(buf); if (n < 0) break
                            total += n
                            check(total <= cap) { if (video) "Videos up to 1.5 GB" else "Pictures up to 40 MB" }
                            check(total + 200L * 1024 * 1024 < free) { "Not enough space on the TV" }
                            o.write(buf, 0, n)
                        }
                    } }
                } catch (e: Exception) { part.delete(); throw e }
                f.delete(); part.renameTo(f)
                if (use) useFile(f)
                lastDownload = "Downloaded ${f.name}" + if (use) " and set as the background" else ""
            } catch (e: Exception) {
                lastDownload = "Download failed: ${e.message}"
            } finally { downloading = "" }
        }
        return "Downloading to the TV… (this page shows when it's done)"
    }
    @Volatile private var lastDownload = ""
    /** The problem report the phone is reading before it is sent. */
    @Volatile private var report: com.glasslauncher.tv.data.Report.Data? = null

    private fun post(path: String, j: JSONObject): String = when (path) {
        "/api/pref" -> setPref(j.optString("k"), j.optString("v"))
        "/api/bg/mode" -> {
            val m = j.optString("mode")
            require(m in listOf("carousel", "image", "video", "photos", "aerial")) { "Unknown mode" }
            require(m != "image" || app.settings.bgImage.isNotBlank()) { "Add a picture first" }
            require(m != "video" || app.settings.bgVideo.isNotBlank()) { "Add a video first" }
            app.settings.bgMode = m; "Background changed"
        }
        // Videos as the background: all the library's videos or only My videos (One video is the mode "video").
        "/api/bg/videos" -> {
            val v = j.optString("videos")
            require(v in listOf("all", "mine")) { "Unknown choice" }
            app.settings.bgVideos = v; app.settings.bgMode = "aerial"; "Background changed"
        }
        "/api/bg/link" -> {
            val u = j.optString("url").trim()
            require(u.startsWith("http")) { "The link must start with http" }
            if (j.optString("kind") == "video") { app.settings.bgVideo = u; app.settings.bgMode = "video"; "Video link set as the background" }
            else { app.settings.bgImage = u; app.settings.bgMode = "image"; "Picture link set as the background" }
        }
        "/api/bg/download" -> bgDownload(j.optString("url").trim(), j.optBoolean("use", true))
        "/api/bg/use" -> { val f = java.io.File(bg.folder(app), j.optString("name").substringAfterLast('/')); require(f.isFile) { "Not found" }; useFile(f); "Background changed" }
        "/api/bg/delete" -> {
            val f = java.io.File(bg.folder(app), j.optString("name").substringAfterLast('/')); require(f.isFile) { "Not found" }
            val st = app.settings
            if (st.bgImage == f.absolutePath) { st.bgImage = ""; if (st.bgMode == "image") st.bgMode = "carousel" }
            if (st.bgVideo == f.absolutePath) { st.bgVideo = ""; if (st.bgMode == "video") st.bgMode = "carousel" }
            f.delete(); "Deleted"
        }
        "/api/addon/add" -> runBlocking { app.catalogs.addAddon(j.optString("url")) }
        "/api/addon/remove" -> runBlocking { app.catalogs.removeAddon(j.optString("url")) }
        "/api/icon/url" -> { app.icons.setFromUrl(j.optString("pkg"), j.optString("url").trim()); "Icon updated" }
        "/api/icon/reset" -> { app.icons.reset(j.optString("pkg")); "Icon reset" }
        "/api/tmdb" -> {
            val t = j.optString("token").trim()
            require(!com.glasslauncher.tv.data.Tmdb.looksLikeApiKey(t)) { "That's the API Key. Paste the API Read Access Token (the long one, starting eyJ...)" }
            if (t.isNotBlank()) require(com.glasslauncher.tv.data.Tmdb.verify(t) != false) { "TMDB refused this token. Copy the API Read Access Token again" }
            app.settings.tmdbToken = t; app.catalogs.clearEnrichment()
            if (t.isBlank()) "TMDB token removed" else "TMDB token saved and working"
        }
        "/api/reddit" -> {
            j.optString("subs").takeIf { it.isNotBlank() }?.let { app.settings.photoSubreddit = it }
            j.optString("id").takeIf { it.isNotBlank() }?.let { app.settings.redditAppId = it }
            app.settings.photoSource = "reddit"; app.settings.bgMode = "photos"
            "Saved: the background now shows r/" + app.settings.photoSubreddit.replace(",", ", r/")
        }
        "/api/unsplash" -> {
            app.settings.unsplashKey = j.optString("key")
            if (app.settings.unsplashKey.isNotBlank()) { app.settings.photoSource = "unsplash"; app.settings.bgMode = "photos" }
            if (app.settings.unsplashKey.isBlank()) "Unsplash key removed" else "Unsplash key saved: the background now shows Unsplash photos"
        }
        "/api/ha/doorbell" -> {
            val u = j.optString("url").trim(); val t = j.optString("token").trim()
            require(u.startsWith("http")) { "The link starts with http:// or https://" }
            app.settings.haUrl = u
            if (t.isNotBlank()) app.settings.haToken = t
            HaWatch.restart()
            "Saved. Now pick the doorbell sensor on the TV: Settings → Smart home → Doorbell"
        }
        "/api/ha" -> { app.settings.haWebhook = j.optString("url"); app.settings.haOnLaunch = app.settings.haWebhook.startsWith("http"); "Home Assistant webhook saved" }
        "/api/aerial/add" -> {
            val u = j.optString("url").trim()
            require(u.startsWith("http")) { "The link must start with http" }
            app.settings.aerialUrls = (app.settings.aerialUrls + u).distinct(); "Video link added"
        }
        "/api/aerial/remove" -> { app.settings.aerialUrls = app.settings.aerialUrls - j.optString("url"); "Removed" }
        "/api/restore" -> {
            val n = com.glasslauncher.tv.data.Backup.restore(app, j)
            Bg.io("glass-restart") { Thread.sleep(1500); com.glasslauncher.tv.data.Backup.restart(app) }
            "Restored ($n parts). Glassio restarts, open this page again from the TV"
        }
        "/api/nuvio/server" -> {
            val nv = app.library.nuvio
            nv.setServer(j.optString("url"), j.optString("key"), j.optString("login"))
            app.library.nuvioList.value = emptyList()
            if (nv.custom) "Nuvio server set to ${runCatching { java.net.URI(nv.server).host }.getOrNull() ?: nv.server}. Now sign in" else "Back on the official Nuvio server"
        }
        "/api/nuvio/login" -> {
            val nv = app.library.nuvio
            if (nv.signInWithPassword(j.optString("email"), j.optString("password"))) {
                app.settings.libraryNuvio = true
                Bg.io("glass-nuvio-refresh") { runCatching { app.library.refreshNuvio(); app.cw.pollNow() }.onFailure { com.glasslauncher.tv.data.Glog.w("Phone page: Nuvio refresh", it) } }
                "Nuvio account connected" + (nv.email.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "")
            } else error("Nuvio: ${nv.lastError}")
        }
        "/api/stremio/login" -> {
            val ok = app.cw.stremio.login(j.optString("email").trim(), j.optString("password"))
            if (ok) { Bg.io("glass-stremio-sync") { runCatching { app.cw.syncStremio() }.onFailure { com.glasslauncher.tv.data.Glog.w("Phone page: Stremio sync", it) } }; "Stremio connected as ${app.cw.stremio.email}" } else error("Stremio didn't accept that email and password")
        }
        // Jellyfin (#27): the server's address, user name and password, typed here (the password isn't kept).
        "/api/jellyfin/login" -> {
            val jf = app.cw.jellyfin
            val server = j.optString("server").trim()
            require(server.isNotBlank()) { "Type the server's address" }
            jf.check(jf.normalize(server)).getOrElse { throw IllegalArgumentException(it.message ?: "No Jellyfin server there") }
            jf.signIn(server, j.optString("user").trim(), j.optString("password")).getOrElse { throw IllegalArgumentException(it.message ?: "Sign-in failed") }
            app.cw.pollNow()
            Bg.io("glass-jellyfin-catalogs") { runCatching { app.catalogs.refresh() }.onFailure { com.glasslauncher.tv.data.Glog.w("Phone page: Jellyfin catalogs", it) } }
            "Jellyfin connected as ${jf.userName}"
        }
        "/api/jellyfin/logout" -> { app.cw.jellyfin.signOut(); Bg.io("glass-jellyfin-catalogs") { runCatching { app.catalogs.refresh() }.onFailure { com.glasslauncher.tv.data.Glog.w("Phone page: Jellyfin catalogs", it) } }; "Signed out of Jellyfin" }
        // Pexels: the key (empty removes it) and the words its videos show; turned on with a key.
        "/api/pexels" -> {
            val st = app.settings
            val key = j.optString("key").trim(); val words = j.optString("words").trim()
            if (j.has("key")) { st.pexelsKey = key; st.saverSources = if (key.isNotBlank()) (st.saverSources + com.glasslauncher.tv.saver.AerialSource.PEXELS).distinct() else st.saverSources - com.glasslauncher.tv.saver.AerialSource.PEXELS }
            if (words.isNotBlank()) st.pexelsQuery = words
            st.saverPick = ""
            if (key.isNotBlank() || !j.has("key")) "Pexels saved" else "Pexels key removed"
        }
        "/api/simkl/client" -> { app.cw.simkl.clientId = j.optString("id").trim(); "Simkl client ID saved. Sign in on the TV: Settings → Accounts & profiles" }
        "/api/camera/add" -> {
            val name = j.optString("name").trim().replace("|", " ").ifBlank { "Camera" }; val url = j.optString("url").trim()
            require(url.startsWith("rtsp://") || url.startsWith("http")) { "Use an rtsp:// or http(s):// link" }
            app.settings.cameras = app.settings.cameras + "$name|$url"; "Camera added"
        }
        "/api/camera/rename" -> {
            val i = j.optInt("i", -1); val name = j.optString("name").trim().replace("|", " ")
            require(name.isNotBlank()) { "Type a name" }
            app.settings.cameras = app.settings.cameras.mapIndexed { k, l -> if (k == i) name + "|" + l.substringAfter('|') else l }; "Camera renamed"
        }
        // ---- Things typed more easily on a phone than with the remote
        "/api/weather" -> { val c = j.optString("city").trim(); require(c.isNotBlank()) { "Type a city" }; app.settings.weatherCity = c; app.weather.reload(); "Weather city: $c" }
        "/api/profile/name" -> {
            val P = com.glasslauncher.tv.data.Profiles; val id = P.active(app); val n = j.optString("name").trim()
            P.setName(app, id, n)
            app.library.nuvio.setProfile(id, P.known(app).firstOrNull { it.id == id }?.name ?: n)
            if (n.isBlank()) "The profile's name is the one in Nuvio again" else "Profile name: $n"
        }
        "/api/app/hide" -> {
            val pkg = j.optString("pkg"); require(app.apps.ordered(includeHidden = true).any { it.pkg == pkg }) { "Unknown app" }
            app.apps.toggleHidden(pkg)
            if (pkg in app.settings.hiddenApps) "Hidden from the dock" else "Back in the dock"
        }
        "/api/photos/source" -> {
            val src = j.optString("source"); require(com.glasslauncher.tv.data.Photos.SOURCES.any { it.first == src }) { "Unknown source" }
            app.settings.photoSource = src; app.settings.bgMode = "photos"
            "The background now shows: " + com.glasslauncher.tv.data.Photos.SOURCES.first { it.first == src }.second
        }
        "/api/doorbell/text" -> {
            j.optString("title").trim().takeIf { it.isNotBlank() }?.let { app.settings.doorbellTitle = it }
            j.optString("message").trim().takeIf { it.isNotBlank() }?.let { app.settings.doorbellMessage = it }
            "Doorbell words saved"
        }
        "/api/hue" -> { app.settings.hueIp = j.optString("ip").trim(); if (app.settings.hueIp.isBlank()) "Hue bridge address removed" else "Saved. Now on the TV: Settings → Smart home → Philips Hue → Connect" }
        // A problem report typed on the phone: built and cleaned on the TV, read in full on the phone, then sent by the TV.
        "/api/report/preview" -> {
            val said = j.optString("text").trim(); require(said.length in 10..500) { "Between 10 and 500 characters" }
            val R = com.glasslauncher.tv.data.Report
            val d = R.build(app, app.settings, app.updater.current, said, mapOf("Nuvio" to app.library.nuvio.connected, "Trakt" to app.cw.trakt.connected,
                "Simkl" to app.cw.simkl.connected, "Stremio" to app.cw.stremio.connected, "Jellyfin" to app.cw.jellyfin.connected))
            report = d; R.preview(d)
        }
        "/api/report/send" -> {
            val R = com.glasslauncher.tv.data.Report
            val d = report ?: error("Write the report again")
            val sent = R.send(app, d)
            // Not sent from the TV (no relay, or it didn't answer): the phone can open the same report on GitHub itself.
            if (!sent.ok) error((sent.note.ifBlank { "Not sent" }) + " " + R.issueLink(d))
            report = null
            (sent.note.ifBlank { "Sent: issue #${sent.number}" }) + " " + sent.url
        }
        "/api/camera/remove" -> { val i = j.optInt("i", -1); app.settings.cameras = app.settings.cameras.filterIndexed { k, _ -> k != i }; "Camera removed" }
        else -> error("Unknown action")
    }

    private fun manifest(): JSONObject = JSONObject().put("name", "Glassio").put("short_name", "Glassio")
        .put("start_url", "/?t=$token").put("scope", "/").put("display", "standalone")
        .put("background_color", "#140c24").put("theme_color", "#140c24")
        .put("icons", JSONArray().put(JSONObject().put("src", "/icon.png?t=$token").put("sizes", "192x192").put("type", "image/png")))

    /** Glassio's own app icon, for the phone's home screen. */
    private val icon: ByteArray by lazy {
        val d = app.packageManager.getApplicationIcon(app.packageName)
        val b = android.graphics.Bitmap.createBitmap(192, 192, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).let { cv -> d.setBounds(0, 0, 192, 192); d.draw(cv) }
        ByteArrayOutputStream().also { o -> b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o); b.recycle() }.toByteArray()
    }

    /**
     * Settings the page can change directly (pick lists and switches), each with the section it shows in. Values are
     * strings; only these keys and these values are taken (`/api/pref`).
     */
    private class Pref(val key: String, val sec: String, val title: String, val desc: String, val opts: List<Pair<String, String>>?)
    private val onOff: List<Pair<String, String>>? = null
    private val prefs: List<Pref> by lazy {
        listOfNotNull(
            Pref("theme", "look", "Theme", "One look for the dock, the icons and the menu", listOf("water_still" to "Still Glass", "water" to "Dynamic Glass", "liquid" to "Frosted Glass",
                "clear" to "Clear", "amoled" to "Black AMOLED", "bow" to "Black on white")),
            Pref("glassColor", "look", "Glass colour", "A tint through all the glass", com.glasslauncher.tv.ui.GlassTint.NAMES.toList()),
            Pref("lang", "look", "Language", "Glassio's screens", com.glasslauncher.tv.data.I18n.LANGS.toList()),
            Pref("showClock", "look", "Clock at the top", "Time and date in the corner of the home screen", onOff),
            Pref("menuPhone", "look", "Manage from phone in the menu", "This page's QR code in the side menu", onOff),
            Pref("heroAutoplay", "catalogs", "Slides change on their own", "The carousel moves to the next title by itself", onOff),
            Pref("saverMode", "bg", "Screensaver shows", "", listOfNotNull("aerial" to "Videos", "slideshow" to "Posters",
                ("trailer" to "Trailers").takeIf { !com.glasslauncher.tv.data.Store.PLAY }, "system" to "The TV's own")),
            Pref("idleSeconds", "bg", "Screensaver starts after", "Without a key pressed on the home screen", listOf("0" to "Off", "10" to "10 s", "30" to "30 s", "60" to "1 min",
                "120" to "2 min", "300" to "5 min", "600" to "10 min")),
            Pref("saverInHome", "bg", "One player", "The screensaver plays on the home screen itself: back at once", onOff),
            Pref("saverShuffle", "bg", "Shuffle the screensaver", "", onOff),
            Pref("bgClock", "bg", "Large clock", "The big glass clock over your own background", onOff),
            Pref("searchPhoneQr", "system", "QR code in Settings' search", "This page's code where the search is empty", onOff),
            Pref("updateChannel", "system", "Updates", "Stable releases, or betas too", listOf("stable" to "Stable", "beta" to "Beta"))
                .takeIf { !com.glasslauncher.tv.data.Store.PLAY },
        )
    }

    private fun prefValue(k: String): String { val st = app.settings; return when (k) {
        "theme" -> st.theme; "glassColor" -> st.glassColor; "lang" -> st.lang; "showClock" -> st.showClock.toString()
        "menuPhone" -> st.menuPhone.toString(); "heroAutoplay" -> st.heroAutoplay.toString(); "saverMode" -> st.saverMode
        "idleSeconds" -> st.idleSeconds.toString(); "saverInHome" -> st.saverInHome.toString(); "saverShuffle" -> st.saverShuffle.toString()
        "bgClock" -> st.bgClock.toString(); "searchPhoneQr" -> st.searchPhoneQr.toString(); "updateChannel" -> st.updateChannel
        else -> "" } }

    private fun setPref(k: String, v: String): String {
        val p = prefs.firstOrNull { it.key == k } ?: throw IllegalArgumentException("Unknown setting")
        val opts = p.opts
        val label = if (opts == null) { require(v == "true" || v == "false") { "On or off" }; if (v == "true") "On" else "Off" }
            else opts.firstOrNull { it.first == v }?.second ?: throw IllegalArgumentException("Unknown value")
        val st = app.settings; val b = v == "true"
        when (k) {
            "theme" -> st.applyTheme(v); "glassColor" -> { st.glassColor = v; com.glasslauncher.tv.ui.GlassTint.apply(st) }; "lang" -> st.lang = v
            "showClock" -> st.showClock = b; "menuPhone" -> st.menuPhone = b; "heroAutoplay" -> st.heroAutoplay = b; "saverMode" -> st.saverMode = v
            "idleSeconds" -> st.idleSeconds = v.toInt(); "saverInHome" -> st.saverInHome = b; "saverShuffle" -> st.saverShuffle = b
            "bgClock" -> st.bgClock = b; "searchPhoneQr" -> st.searchPhoneQr = b; "updateChannel" -> st.updateChannel = v
        }
        return "${p.title}: $label"
    }

    private fun state(): JSONObject {
        val addons = JSONArray()
        app.catalogs.addons.sorted().forEach { addons.put(JSONObject().put("url", it).put("name", app.catalogs.addonName(it)).put("host", runCatching { java.net.URI(it).host }.getOrNull() ?: "")) }
        app.catalogs.accountOnly().forEach { addons.put(JSONObject().put("url", it).put("name", app.catalogs.addonName(it)).put("host", runCatching { java.net.URI(it).host }.getOrNull() ?: "").put("account", true)) }
        val apps = JSONArray()
        val hidden = app.settings.hiddenApps.toSet()
        // First: Glassio's own settings button (Settings → Menu & themes → Settings button → Your picture).
        val sb = com.glasslauncher.tv.data.SETTINGS_BUTTON_ICON
        apps.put(JSONObject().put("pkg", sb).put("label", "Settings button (Glassio)").put("custom", app.icons.fileFor(sb).exists()).put("own", true))
        app.apps.ordered(includeHidden = true).forEach {
            apps.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("custom", app.icons.fileFor(it.pkg).exists()).put("hidden", it.pkg in hidden))
        }
        val aerial = JSONArray(); app.settings.aerialUrls.forEach { aerial.put(it) }
        val st = app.settings
        val files = JSONArray()
        (bg.files(app, false) + bg.files(app, true)).forEach { f ->
            files.put(JSONObject().put("name", f.name).put("video", f.extension.lowercase() in bg.videoExts).put("mb", (f.length() / 1024 / 1024).toInt())
                .put("used", f.absolutePath == st.bgImage || f.absolutePath == st.bgVideo))
        }
        fun shown(v: String) = if (v.startsWith("http")) v else v.substringAfterLast('/')
        val back = JSONObject().put("mode", st.bgMode).put("videos", st.bgVideos).put("image", shown(st.bgImage)).put("video", shown(st.bgVideo)).put("files", files)
            .put("downloading", downloading).put("last", lastDownload)
        val cams = JSONArray()
        st.cameras.forEach { l -> cams.put(JSONObject().put("name", l.substringBefore('|')).put("host", runCatching { java.net.URI(l.substringAfter('|')).host }.getOrNull() ?: "")) }
        val acc = JSONObject().put("nuvio", app.cw.nuvio?.connected == true).put("stremio", app.cw.stremio.connected)
            .put("trakt", app.cw.trakt.connected).put("simkl", app.cw.simkl.connected).put("jellyfin", app.cw.jellyfin.connected)
            .put("jellyfinWho", if (app.cw.jellyfin.connected) "${app.cw.jellyfin.userName} · ${app.cw.jellyfin.serverName.ifBlank { "your server" }}" else "")
        val nv = app.library.nuvio
        val nuvioServer = JSONObject().put("custom", nv.custom).put("host", if (nv.custom) runCatching { java.net.URI(nv.server).host }.getOrNull() ?: nv.server else "")
            .put("login", if (nv.custom) nv.loginPage else "")
        val pexels = JSONObject().put("key", app.settings.pexelsKey.isNotBlank()).put("words", app.settings.pexelsQuery).put("on", com.glasslauncher.tv.saver.AerialSource.PEXELS in app.settings.saverSources)
        val pr = JSONArray()
        prefs.forEach { p -> pr.put(JSONObject().put("k", p.key).put("sec", p.sec).put("t", p.title).put("d", p.desc).put("v", prefValue(p.key))
            .put("opts", p.opts?.let { o -> JSONArray().apply { o.forEach { (v, l) -> put(JSONArray().put(v).put(l)) } } } ?: JSONObject.NULL)) }
        return JSONObject().put("prefs", pr).put("byName", PhoneHost.byName(app) ?: "").put("name", LocalName.host(app) + ".local").put("pexels", pexels).put("youtube", !com.glasslauncher.tv.data.Store.PLAY).put("addons", addons).put("bg", back).put("apps", apps).put("aerial", aerial).put("cams", cams).put("accounts", acc).put("nuvioServer", nuvioServer)
            .put("tmdb", app.settings.tmdbToken.isNotBlank()).put("ha", app.settings.haWebhook.isNotBlank())
            .put("simklClient", app.cw.simkl.clientId.isNotBlank()).put("city", st.weatherCity)
            .put("profile", JSONObject().put("name", com.glasslauncher.tv.data.Profiles.nameOverride(app, com.glasslauncher.tv.data.Profiles.active(app))))
            // (Keys are write-only: only whether one is set.)
            .put("photos", JSONObject().put("source", st.photoSource).put("unsplash", st.unsplashKey.isNotBlank()).put("reddit", st.redditAppId.isNotBlank()).put("subs", st.photoSubreddit)
                .put("sources", JSONArray().apply { com.glasslauncher.tv.data.Photos.SOURCES.forEach { put(JSONObject().put("id", it.first).put("name", it.second)) } }))
            .put("home", JSONObject().put("url", st.haUrl).put("title", st.doorbellTitle).put("message", st.doorbellMessage).put("hue", st.hueIp))
            .put("backup", JSONObject().put("moves", com.glasslauncher.tv.data.Backup.MOVES).put("stays", com.glasslauncher.tv.data.Backup.STAYS))
            .put("version", app.updater.current).put("tv", android.os.Build.MODEL.let { m -> if (m.startsWith(android.os.Build.MANUFACTURER, ignoreCase = true)) m else android.os.Build.MANUFACTURER + " " + m })
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m).toString()

    /**
     * The colour along the top of the page's picture (with its shade), for the browser's own top: it took a black
     * band from the old theme colour, and Safari paints that part with a colour, never a picture.
     */
    private val top: String by lazy {
        runCatching {
            val b = com.glasslauncher.tv.ui.orbsPicture(640, 360)
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            for (y in 0 until 24) for (x in 0 until b.width step 4) { val c = b.getPixel(x, y); r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; bl += c and 0xff; n++ }
            b.recycle()
            fun mix(v: Long, k: Int) = ((v / n) * 0.72 + k * 0.28).toInt().coerceIn(0, 255)
            String.format("#%02x%02x%02x", mix(r, 5), mix(g, 6), mix(bl, 11))
        }.getOrDefault("#2a1838")
    }

    private val lights: ByteArray by lazy {
        ByteArrayOutputStream().also { o -> com.glasslauncher.tv.ui.orbsPicture(640, 360).let { b -> b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, o); b.recycle() } }.toByteArray()
    }

    private fun respond(c: Socket, code: Int, type: String, body: String, extra: String = "") = respond(c, code, type, body.toByteArray(Charsets.UTF_8), extra)

    private fun respond(c: Socket, code: Int, type: String, bytes: ByteArray, extra: String = "") {
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type${if (type.startsWith("image/")) "" else "; charset=utf-8"}\r\nContent-Length: ${bytes.size}\r\n" +
            (if (extra.contains("Cache-Control")) "" else "Cache-Control: no-store\r\n") + "Referrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" + extra + "Connection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }
}
