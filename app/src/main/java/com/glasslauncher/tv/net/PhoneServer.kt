package com.glasslauncher.tv.net

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
 * Tiny LAN-only web page ("Manage from phone"). It only runs while the settings page is open and every
 * request must carry the one-time token embedded in the QR code. Secrets (TMDB token) are write-only.
 */
class PhoneServer(private val app: GlassApp, private val token: String) {
    @Volatile private var running = false
    private var socket: ServerSocket? = null

    fun start(): Int? {
        for (port in 9090..9099) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), 8) }
                socket = s; running = true
                Thread({ loop(s) }, "glass-phone-server").also { it.isDaemon = true; it.start() }
                return port
            } catch (_: Exception) { }
        }
        return null
    }

    fun stop() { running = false; runCatching { socket?.close() }; socket = null }

    private fun loop(s: ServerSocket) {
        while (running) {
            val c = try { s.accept() } catch (_: Exception) { break }
            try { c.soTimeout = 15000; handle(c) } catch (_: Exception) { } finally { runCatching { c.close() } }
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
        val input = c.getInputStream()
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
        if (method == "GET" && path == "/") { respond(c, 200, "text/html", PAGE); return }
        if (method == "GET" && path == "/api/state") { respond(c, 200, "application/json", state().toString()); return }
        if (method == "GET" && path == "/api/backup") {
            respond(c, 200, "application/json", com.glasslauncher.tv.data.Backup.export(app).toString(1),
                "Content-Disposition: attachment; filename=\"Glassio-backup.json\"\r\n"); return
        }
        if (method != "POST") { respond(c, 404, "text/plain", "Not found"); return }
        if (path == "/api/bg/upload") {
            // Straight to the backgrounds folder, never held in memory (videos can be hundreds of MB).
            val msg = runCatching { bgUpload(query(q, "name"), query(q, "use") == "1", input, len) }
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
    private fun bgDownload(url: String, use: Boolean): String {
        require(url.startsWith("http")) { "The link must start with http" }
        check(downloading.isEmpty()) { "Already downloading $downloading" }
        val guess = url.substringBefore('?').substringAfterLast('.', "").lowercase()
        val first = runCatching { bgFile(url) }.getOrNull()
        downloading = first?.name ?: "the link"
        Thread({
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
                c.inputStream.use { i -> part.outputStream().use { i.copyTo(it, 256 * 1024) } }
                f.delete(); part.renameTo(f)
                if (use) useFile(f)
                lastDownload = "Downloaded ${f.name}" + if (use) " and set as the background" else ""
            } catch (e: Exception) {
                lastDownload = "Download failed: ${e.message}"
            } finally { downloading = "" }
        }, "glass-bg-download").apply { isDaemon = true; start() }
        return "Downloading to the TV… (this page shows when it's done)"
    }
    @Volatile private var lastDownload = ""

    private fun post(path: String, j: JSONObject): String = when (path) {
        "/api/bg/mode" -> {
            val m = j.optString("mode")
            require(m in listOf("carousel", "image", "video", "aerial")) { "Unknown mode" }
            require(m != "image" || app.settings.bgImage.isNotBlank()) { "Add a picture first" }
            require(m != "video" || app.settings.bgVideo.isNotBlank()) { "Add a video first" }
            app.settings.bgMode = m; "Background changed"
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
            "Saved. Now pick the doorbell sensor on the TV: Settings → Cameras"
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
            Thread { Thread.sleep(1500); com.glasslauncher.tv.data.Backup.restart(app) }.start()
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
                Thread { runCatching { app.library.refreshNuvio(); app.cw.pollNow() } }.start()
                "Nuvio account connected" + (nv.email.takeIf { it.isNotBlank() }?.let { " as $it" } ?: "")
            } else error("Nuvio: ${nv.lastError}")
        }
        "/api/stremio/login" -> {
            val ok = app.cw.stremio.login(j.optString("email").trim(), j.optString("password"))
            if (ok) { Thread { runCatching { app.cw.syncStremio() } }.start(); "Stremio connected as ${app.cw.stremio.email}" } else error("Stremio didn't accept that email and password")
        }
        "/api/simkl/client" -> { app.cw.simkl.clientId = j.optString("id").trim(); "Simkl client ID saved. Sign in on the TV: Settings → Connections" }
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
        "/api/camera/remove" -> { val i = j.optInt("i", -1); app.settings.cameras = app.settings.cameras.filterIndexed { k, _ -> k != i }; "Camera removed" }
        else -> error("Unknown action")
    }

    private fun state(): JSONObject {
        val addons = JSONArray()
        app.catalogs.addons.sorted().forEach { addons.put(JSONObject().put("url", it).put("name", app.catalogs.addonName(it)).put("host", runCatching { java.net.URI(it).host }.getOrNull() ?: "")) }
        app.catalogs.accountOnly().forEach { addons.put(JSONObject().put("url", it).put("name", app.catalogs.addonName(it)).put("host", runCatching { java.net.URI(it).host }.getOrNull() ?: "").put("account", true)) }
        val apps = JSONArray()
        app.apps.ordered(includeHidden = true).forEach {
            apps.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("custom", app.icons.fileFor(it.pkg).exists()))
        }
        val aerial = JSONArray(); app.settings.aerialUrls.forEach { aerial.put(it) }
        val st = app.settings
        val files = JSONArray()
        (bg.files(app, false) + bg.files(app, true)).forEach { f ->
            files.put(JSONObject().put("name", f.name).put("video", f.extension.lowercase() in bg.videoExts).put("mb", (f.length() / 1024 / 1024).toInt())
                .put("used", f.absolutePath == st.bgImage || f.absolutePath == st.bgVideo))
        }
        fun shown(v: String) = if (v.startsWith("http")) v else v.substringAfterLast('/')
        val back = JSONObject().put("mode", st.bgMode).put("image", shown(st.bgImage)).put("video", shown(st.bgVideo)).put("files", files)
            .put("downloading", downloading).put("last", lastDownload)
        val cams = JSONArray()
        st.cameras.forEach { l -> cams.put(JSONObject().put("name", l.substringBefore('|')).put("host", runCatching { java.net.URI(l.substringAfter('|')).host }.getOrNull() ?: "")) }
        val acc = JSONObject().put("nuvio", app.cw.nuvio?.connected == true).put("stremio", app.cw.stremio.connected)
            .put("trakt", app.cw.trakt.connected).put("simkl", app.cw.simkl.connected)
        val nv = app.library.nuvio
        val nuvioServer = JSONObject().put("custom", nv.custom).put("host", if (nv.custom) runCatching { java.net.URI(nv.server).host }.getOrNull() ?: nv.server else "")
            .put("login", if (nv.custom) nv.loginPage else "")
        return JSONObject().put("addons", addons).put("bg", back).put("apps", apps).put("aerial", aerial).put("cams", cams).put("accounts", acc).put("nuvioServer", nuvioServer)
            .put("tmdb", app.settings.tmdbToken.isNotBlank()).put("ha", app.settings.haWebhook.isNotBlank())
    }

    private fun err(m: String) = JSONObject().put("ok", false).put("error", m).toString()

    private fun respond(c: Socket, code: Int, type: String, body: String, extra: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; else -> "Not Found" }
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" + extra + "Connection: close\r\n\r\n"
        val out = c.getOutputStream()
        out.write(head.toByteArray(Charsets.UTF_8)); out.write(bytes); out.flush()
    }

    private companion object {
        const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light dark"><title>Glassio</title><style>
:root{--bg:#f2f2f7;--card:#fff;--ink:#000;--sub:#8a8a8e;--line:rgba(60,60,67,.18);--field:#f2f2f7;--blue:#007aff;--red:#ff3b30;--green:#34c759}
@media (prefers-color-scheme:dark){:root{--bg:#000;--card:#1c1c1e;--ink:#fff;--sub:#8d8d93;--line:rgba(84,84,88,.6);--field:#2c2c2e;--blue:#0a84ff;--red:#ff453a;--green:#30d158}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:17px/1.35 -apple-system,BlinkMacSystemFont,"SF Pro Text",Roboto,"Segoe UI",sans-serif;-webkit-font-smoothing:antialiased}
main{max-width:640px;margin:0 auto;padding:max(18px,env(safe-area-inset-top)) 16px 90px}
h1{font-size:34px;font-weight:700;letter-spacing:-.02em;margin:18px 4px 2px}.lead{color:var(--sub);font-size:15px;margin:0 4px 14px}
#q{width:100%;border:0;border-radius:10px;background:rgba(118,118,128,.12);color:var(--ink);font-size:17px;padding:9px 12px;outline:none;margin-bottom:6px}
section{margin-top:26px}h2{font-size:13px;font-weight:400;text-transform:uppercase;color:var(--sub);margin:0 16px 7px;letter-spacing:.01em}
.list{background:var(--card);border-radius:12px;overflow:hidden}
.it{padding:11px 16px;position:relative;display:flex;align-items:center;gap:10px;flex-wrap:wrap}
.list>*,.list>div>*{position:relative}.list>*+*:before,.list>div>*+*:before{content:"";position:absolute;top:0;left:16px;right:0;border-top:.5px solid var(--line)}.act{display:block}.hide:before{display:none}
.it .t{flex:1;min-width:0}.it .t b{font-weight:400;display:block;overflow:hidden;text-overflow:ellipsis}.it .t small{display:block;color:var(--sub);font-size:13px;word-break:break-all}
.foot{color:var(--sub);font-size:13px;margin:7px 16px 0}
input[type=text],input[type=password]{width:100%;border:0;background:transparent;color:var(--ink);font-size:17px;padding:2px 0;outline:none}
.it.f{padding:12px 16px}
a.b,button{appearance:none;border:0;background:none;color:var(--blue);font-size:17px;padding:0;cursor:pointer}
button.x{color:var(--red)}button.p{background:var(--blue);color:#fff;border-radius:10px;padding:8px 14px;font-weight:600;font-size:15px}
.act{color:var(--blue);text-align:left;width:100%;padding:12px 16px;font-size:17px}
.seg{display:flex;background:rgba(118,118,128,.12);border-radius:9px;padding:2px;gap:2px;width:100%}.seg button{flex:1;color:var(--ink);font-size:13px;padding:6px 4px;border-radius:7px}
.seg button.on{background:var(--card);box-shadow:0 1px 3px rgba(0,0,0,.15);font-weight:600}
.tag{font-size:12px;color:var(--sub);margin-left:6px}.ok{color:var(--green)}.dim{color:var(--sub)}
.btns{display:flex;gap:16px}
#msg{position:fixed;left:50%;bottom:max(22px,env(safe-area-inset-bottom));transform:translateX(-50%) translateY(140%);background:rgba(30,30,32,.92);color:#fff;font-size:15px;padding:11px 18px;border-radius:22px;transition:transform .25s;max-width:90%;text-align:center;z-index:9}
#msg.show{transform:translateX(-50%) translateY(0)}#msg.bad{background:rgba(180,30,30,.95)}
.hide{display:none}
</style></head><body><main>
<h1>Glassio</h1><p class="lead">Manage your TV from here &middot; &#1573;&#1583;&#1575;&#1585;&#1577; &#1575;&#1604;&#1578;&#1604;&#1601;&#1586;&#1610;&#1608;&#1606; &#1605;&#1606; &#1607;&#1606;&#1575;</p>
<input id="q" type="search" placeholder="Search" oninput="filt()">
<div id="msg"></div>

<section data-k="accounts stremio simkl nuvio trakt sign in"><h2>Accounts</h2><div class="list">
<div class="it"><div class="t"><b id="acc" style="font-size:15px"></b></div></div>
<div class="it f"><input type="text" id="se" placeholder="Stremio email" autocomplete="username"></div>
<div class="it f"><input type="password" id="sp" placeholder="Stremio password" autocomplete="current-password"></div>
<button class="act" onclick="send('/api/stremio/login',{email:v('se'),password:v('sp')},'sp')">Sign in to Stremio</button>
<div class="it f"><input type="text" id="sk" placeholder="Simkl client ID"></div>
<button class="act" onclick="send('/api/simkl/client',{id:v('sk')},'sk')">Save Simkl client ID</button>
</div><p class="foot">Nuvio, Trakt and Simkl sign in with a code on the TV: Settings &rarr; Connections.</p></section>

<section data-k="nuvio server self-hosted supabase backend email sign in"><h2>Nuvio server</h2><div class="list">
<div class="it"><div class="t"><b>Server</b></div><span id="nvs" class="dim"></span></div>
<div class="it f"><input type="text" id="nu" placeholder="https://your-project.supabase.co (empty: official)"></div>
<div class="it f"><input type="text" id="nk" placeholder="Anon (public) key"></div>
<div class="it f"><input type="text" id="nl" placeholder="TV sign-in page (optional)"></div>
<button class="act" onclick="send('/api/nuvio/server',{url:v('nu'),key:v('nk'),login:v('nl')},'nk')">Save server</button>
<div class="it f"><input type="text" id="ne" placeholder="Nuvio email" autocomplete="username"></div>
<div class="it f"><input type="password" id="np" placeholder="Nuvio password" autocomplete="current-password"></div>
<button class="act" onclick="send('/api/nuvio/login',{email:v('ne'),password:v('np')},'np')">Sign in to Nuvio</button>
</div><p class="foot">Only for a self-hosted Nuvio backend. The anon key: Supabase &rarr; Project Settings &rarr; API. Without a TV sign-in page, sign in here with email and password. Changing the server signs out.</p></section>

<section data-k="add-ons addons catalogs manifest stremio"><h2>Add-ons</h2><div class="list"><div id="addons"></div>
<div class="it f"><input type="text" id="au" placeholder="https://&hellip;/manifest.json or stremio://&hellip;"></div>
<button class="act" onclick="addAddon()">Add add-on</button></div></section>

<section data-k="background picture video wallpaper upload"><h2>Background</h2><div class="list">
<div class="it"><div class="seg" id="bgm"></div></div>
<div class="it" id="bgnowr"><div class="t"><small id="bgnow"></small></div></div>
<div class="it f"><input type="text" id="bi" placeholder="Picture link"></div>
<div class="it"><div class="btns"><button onclick="bgLink('image','bi')">Use link</button><button onclick="bgSave('bi')">Save to the TV</button></div></div>
<div class="it f"><input type="text" id="bv" placeholder="Video link"></div>
<div class="it"><div class="btns"><button onclick="bgLink('video','bv')">Use link</button><button onclick="bgSave('bv')">Save to the TV</button></div></div>
<input type="file" id="bf" accept="image/*,video/*" class="hide" onchange="bgUp()"><button class="act" onclick="document.getElementById('bf').click()">Upload from this phone&hellip;</button>
</div><p class="foot" id="bgp"></p>
<div class="list" id="bgfiles" style="margin-top:10px"></div></section>

<section data-k="app icons dock"><h2>App icons</h2><div class="list">
<div class="it f"><input type="text" id="af" placeholder="Find an app" oninput="drawApps()"></div><div id="apps"></div></div>
<p class="foot">Use an image link, or upload an image from this phone.</p></section>

<section data-k="screensaver video links aerial"><h2>Screensaver videos</h2><div class="list"><div id="aerial"></div>
<div class="it f"><input type="text" id="av" placeholder="https://&hellip;/video.mp4"></div>
<button class="act" onclick="send('/api/aerial/add',{url:v('av')},'av')">Add video</button></div></section>

<section data-k="cameras rtsp multi view"><h2>Cameras</h2><div class="list"><div id="cams"></div>
<div class="it f"><input type="text" id="cn" placeholder="Name, e.g. Garden"></div>
<div class="it f"><input type="text" id="cu" placeholder="rtsp://user:pass@camera:554/stream"></div>
<button class="act" onclick="send('/api/camera/add',{name:v('cn'),url:v('cu')},'cu')">Add camera</button></div></section>

<section data-k="tmdb token unsplash key logos ratings"><h2>Keys</h2><div class="list">
<div class="it"><div class="t"><b>TMDB</b><small>Logos, synopsis, ratings</small></div><span id="tks" class="dim"></span></div>
<div class="it f"><input type="text" id="tk" placeholder="API Read Access Token (starts eyJ&hellip;)"></div>
<button class="act" onclick="send('/api/tmdb',{token:v('tk')},'tk')">Save TMDB token</button>
<div class="it"><div class="t"><b>Unsplash</b><small>Photos as the background</small></div></div>
<div class="it f"><input type="text" id="uk" placeholder="Access Key"></div>
<button class="act" onclick="send('/api/unsplash',{key:v('uk')},'uk')">Save Unsplash key</button>
</div><p class="foot">TMDB: themoviedb.org &rarr; Settings &rarr; API, the long Read Access Token (not the short API Key). Unsplash: unsplash.com/developers &rarr; New application.</p></section>

<section data-k="home assistant doorbell webhook"><h2>Home Assistant</h2><div class="list">
<div class="it"><div class="t"><b>Webhook</b><small>Called when you open an app</small></div><span id="has" class="dim"></span></div>
<div class="it f"><input type="text" id="ha" placeholder="http://homeassistant.local:8123/api/webhook/&hellip;"></div>
<button class="act" onclick="send('/api/ha',{url:v('ha')},'ha')">Save webhook</button>
<div class="it"><div class="t"><b>Doorbell</b><small>Glassio watches it itself</small></div></div>
<div class="it f"><input type="text" id="hu" placeholder="http://homeassistant.local:8123"></div>
<div class="it f"><input type="password" id="ht" placeholder="Long-lived access token"></div>
<button class="act" onclick="send('/api/ha/doorbell',{url:v('hu'),token:v('ht')},'ht')">Save</button>
</div><p class="foot">Token: Home Assistant &rarr; your profile &rarr; Security &rarr; Long-lived access tokens.</p></section>

<section data-k="backup restore settings"><h2>Backup</h2><div class="list">
<button class="act" onclick="location.href='/api/backup?t='+encodeURIComponent(T)">Download backup</button>
<input type="file" id="rf" accept=".json,application/json" class="hide" onchange="restore()"><button class="act" onclick="document.getElementById('rf').click()">Restore from a file&hellip;</button>
</div><p class="foot"><b>Another TV:</b> download the backup here, open this page on the other TV and restore it there.</p>
<p class="foot">${com.glasslauncher.tv.data.Backup.MOVES}</p><p class="foot">${com.glasslauncher.tv.data.Backup.STAYS}</p>
<p class="foot"><b>Before restoring:</b> ${com.glasslauncher.tv.data.Backup.WARN}</p></section>
</main><script>
var T=new URLSearchParams(location.search).get('t')||'',D=null,mt=0;
function v(i){return document.getElementById(i).value.trim()}
function say(t,ok){var m=document.getElementById('msg');m.textContent=t;m.className=(ok?'':'bad')+' show';clearTimeout(mt);mt=setTimeout(function(){m.className=ok?'':'bad'},3200)}
function api(p,b){return fetch(p+'?t='+encodeURIComponent(T),b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:{}).then(function(r){return r.json()})}
function send(p,b,clear){api(p,b).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok){if(clear)document.getElementById(clear).value='';load()}}).catch(function(){say('Cannot reach the TV',false)})}
function addAddon(){var u=v('au');if(u)send('/api/addon/add',{url:u},'au')}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!==undefined)e.textContent=x;return e}
function item(title,sub,tags){var c=el('div','it'),t=el('div','t'),b=el('b','',title);(tags||[]).forEach(function(g){b.appendChild(el('span','tag',g))});t.appendChild(b);if(sub)t.appendChild(el('small','',sub));c.appendChild(t);return c}
function btn(label,cls,fn){var b=el('button',cls||'',label);b.onclick=fn;return b}
function filt(){var q=v('q').toLowerCase();document.querySelectorAll('section').forEach(function(s){s.classList.toggle('hide',q&&(s.dataset.k+' '+s.textContent).toLowerCase().indexOf(q)<0)})}
function up(pkg,f){say('Uploading…',true);fetch('/api/icon/upload?pkg='+encodeURIComponent(pkg)+'&t='+encodeURIComponent(T),{method:'POST',body:f}).then(function(r){return r.json()}).then(function(r){say(r.ok?r.message:r.error,r.ok);load()}).catch(function(){say('Upload failed',false)})}
function bgLink(k,i){var u=v(i);if(u)send('/api/bg/link',{kind:k,url:u},i)}
function bgSave(i){var u=v(i);if(u)send('/api/bg/download',{url:u,use:true},i)}
function bgUp(){var f=document.getElementById('bf').files[0];if(!f)return;var x=new XMLHttpRequest(),p=document.getElementById('bgp');
x.open('POST','/api/bg/upload?use=1&name='+encodeURIComponent(f.name)+'&t='+encodeURIComponent(T));
x.upload.onprogress=function(e){if(e.lengthComputable)p.textContent='Uploading '+Math.round(e.loaded*100/e.total)+'%'};
x.onload=function(){p.textContent='';try{var r=JSON.parse(x.responseText);say(r.ok?r.message:r.error,r.ok)}catch(e){say('Upload failed',false)}document.getElementById('bf').value='';load()};
x.onerror=function(){p.textContent='';say('Upload failed',false)};say('Uploading…',true);x.send(f)}
function restore(){var f=document.getElementById('rf').files[0];if(!f)return;if(!confirm('Replace every setting on this TV with this backup? Nothing is merged, and Glassio restarts. Icons you made, background pictures and TV access (ADB) stay as they are on this TV.'))return;f.text().then(function(t){say('Restoring…',true);return fetch('/api/restore?t='+encodeURIComponent(T),{method:'POST',headers:{'Content-Type':'application/json'},body:t})}).then(function(r){return r.json()}).then(function(r){say(r.ok?r.message:r.error,r.ok)}).catch(function(){say('Restore failed',false)})}
function drawApps(){if(!D)return;var q=v('af').toLowerCase(),p=document.getElementById('apps');p.textContent='';D.apps.filter(function(x){return !q||x.label.toLowerCase().indexOf(q)>=0}).forEach(function(x){var c=item(x.label,'',x.custom?['custom']:[]);var r=el('div','btns');
r.appendChild(btn('Link','',function(){var u=prompt('Image link for '+x.label);if(u)send('/api/icon/url',{pkg:x.pkg,url:u})}));
var fi=el('input');fi.type='file';fi.accept='image/*';fi.className='hide';fi.onchange=function(){if(fi.files[0])up(x.pkg,fi.files[0])};
r.appendChild(btn('Upload','',function(){fi.click()}));r.appendChild(fi);if(x.custom)r.appendChild(btn('Reset','x',function(){send('/api/icon/reset',{pkg:x.pkg})}));c.appendChild(r);p.appendChild(c)})}
var BGM=[['carousel','Titles'],['image','Picture'],['video','Video'],['aerial','Screensaver']],poll=0;
function load(){api('/api/state').then(function(d){D=d;
var a=document.getElementById('addons');a.textContent='';d.addons.forEach(function(x){var c=item(x.name,x.host,x.account?['Nuvio account']:[]);if(!x.account)c.appendChild(btn('Remove','x',function(){if(confirm('Remove '+x.name+'?'))send('/api/addon/remove',{url:x.url})}));a.appendChild(c)});
var g=d.bg,m=document.getElementById('bgm');m.textContent='';BGM.forEach(function(o){m.appendChild(btn(o[1],g.mode==o[0]?'on':'',function(){send('/api/bg/mode',{mode:o[0]})}))});
var now=(g.image?'Picture: '+g.image+'  ':'')+(g.video?'Video: '+g.video:'');document.getElementById('bgnow').textContent=now;document.getElementById('bgnowr').classList.toggle('hide',!now);
var bp=document.getElementById('bgp');if(g.downloading){bp.textContent='Downloading '+g.downloading+'…';clearTimeout(poll);poll=setTimeout(load,2000)}else if(g.last&&bp.textContent.indexOf('Downloading')==0){bp.textContent=g.last}
var bl=document.getElementById('bgfiles');bl.textContent='';bl.classList.toggle('hide',!g.files.length);g.files.forEach(function(f){var c=item(f.name,f.mb+' MB',[f.video?'video':'picture'].concat(f.used?['in use']:[]));var r=el('div','btns');
r.appendChild(btn('Use','',function(){send('/api/bg/use',{name:f.name})}));r.appendChild(btn('Delete','x',function(){if(confirm('Delete '+f.name+'?'))send('/api/bg/delete',{name:f.name})}));c.appendChild(r);bl.appendChild(c)});
drawApps();
var ks=document.getElementById('tks');ks.textContent=d.tmdb?'✓ Set':'Not set';ks.className=d.tmdb?'ok':'dim';
document.getElementById('tk').placeholder=d.tmdb?'Saved · type a new one to replace, empty to remove':'API Read Access Token (starts eyJ…)';
var hs=document.getElementById('has');hs.textContent=d.ha?'✓ Set':'';hs.className=d.ha?'ok':'dim';
var z=document.getElementById('aerial');z.textContent='';d.aerial.forEach(function(u){var c=item(u.replace(/^(https?:\/\/[^\/]+).*/,'$1'),'');c.appendChild(btn('Remove','x',function(){send('/api/aerial/remove',{url:u})}));z.appendChild(c)});
var N=d.nuvioServer||{};var ns=document.getElementById('nvs');ns.textContent=N.custom?N.host:'Official';ns.className=N.custom?'ok':'dim';
var A=d.accounts||{};document.getElementById('acc').innerHTML=['nuvio','stremio','trakt','simkl'].map(function(k){return k.charAt(0).toUpperCase()+k.slice(1)+(A[k]?' <span class="ok">✓</span>':' <span class="dim">–</span>')}).join(' &nbsp;&middot;&nbsp; ');
var cm=document.getElementById('cams');cm.textContent='';(d.cams||[]).forEach(function(x,i){var c=item(x.name,x.host);var r=el('div','btns');r.appendChild(btn('Rename','',function(){var n=prompt('Camera name',x.name);if(n)send('/api/camera/rename',{i:i,name:n})}));r.appendChild(btn('Remove','x',function(){if(confirm('Remove '+x.name+'?'))send('/api/camera/remove',{i:i})}));c.appendChild(r);cm.appendChild(c)});
}).catch(function(){say('Cannot reach the TV',false)})}
load();
</script></body></html>"""
    }
}
