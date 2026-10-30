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
        return JSONObject().put("addons", addons).put("bg", back).put("apps", apps).put("aerial", aerial).put("cams", cams).put("accounts", acc)
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
        const val PAGE = """<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Glassio</title><style>
body{margin:0;background:#0b0d16;color:#eef1fb;font:16px/1.5 -apple-system,Roboto,Segoe UI,sans-serif}
main{max-width:620px;margin:0 auto;padding:22px 16px 60px}h1{font-size:26px;margin:6px 0 0}h2{font-size:13px;letter-spacing:.12em;color:#9d8cff;margin:30px 0 8px;text-transform:uppercase}
p{color:#98a2bd;margin:4px 0 12px}input[type=text],input[type=password],textarea{width:100%;box-sizing:border-box;background:#141827;color:#eef1fb;border:1px solid #232a40;border-radius:12px;padding:12px;font:15px monospace}
button{background:#7b61ff;color:#fff;border:0;border-radius:12px;padding:11px 16px;font-size:15px;font-weight:600;margin-top:8px}
button.s{background:#232a40;color:#eef1fb;padding:8px 12px;font-size:13px;margin:0 4px 0 0}button.d{background:#3a1f2b;color:#ff8f9b}
#msg{position:sticky;top:0;padding:10px 0;min-height:1.4em;font-weight:600}.ok{color:#6ee7b7}.bad{color:#ff8f9b}
.card{background:#141827;border-radius:14px;padding:12px 14px;margin-bottom:8px}.card b{display:block}.card small{color:#6b7794;word-break:break-all}
.row{display:flex;gap:6px;flex-wrap:wrap;margin-top:8px}.tag{font-size:11px;background:#2a2150;color:#c8bdff;border-radius:6px;padding:1px 6px;margin-left:6px}
</style></head><body><main>
<h1>Glassio</h1><p>Manage your TV from here &middot; &#1573;&#1583;&#1575;&#1585;&#1577; &#1575;&#1604;&#1578;&#1604;&#1601;&#1586;&#1610;&#1608;&#1606; &#1605;&#1606; &#1607;&#1606;&#1575;</p>
<div id="msg"></div>
<h2>Add-ons</h2><div id="addons"></div>
<input type="text" id="au" placeholder="https://.../manifest.json  or  stremio://..."><button onclick="addAddon()">Add add-on</button>
<h2>Background</h2><p>What's behind the home screen. Your pictures and videos live in Glassio's backgrounds folder on the TV.</p>
<div class="row" id="bgm"></div><p id="bgnow"></p>
<input type="text" id="bi" placeholder="Picture link: https://.../photo.jpg"><div class="row"><button onclick="bgLink('image','bi')">Use picture link</button><button class="s" onclick="bgSave('bi')">Save to the TV</button></div>
<input type="text" id="bv" placeholder="Video link: https://.../video.mp4" style="margin-top:12px"><div class="row"><button onclick="bgLink('video','bv')">Use video link</button><button class="s" onclick="bgSave('bv')">Save to the TV</button></div>
<div class="row" style="margin-top:12px"><input type="file" id="bf" accept="image/*,video/*" style="display:none" onchange="bgUp()"><button onclick="document.getElementById('bf').click()">Upload a picture or video from this phone</button></div>
<p id="bgp"></p><div id="bgfiles"></div>
<h2>App icons</h2><p>Pick an app, then paste an image link or upload an image from this phone.</p><div id="apps"></div>
<h2>TMDB token (logos, synopsis, ratings)</h2><p>Paste the <b>API Read Access Token</b> (the long one, starting eyJ...), not the short API Key. themoviedb.org &rarr; Settings &rarr; API.</p><p id="tks"></p><input type="text" id="tk" placeholder="TMDB API Read Access Token"><button onclick="send('/api/tmdb',{token:v('tk')},'tk')">Save token</button>
<h2>Home Assistant webhook</h2><input type="text" id="ha" placeholder="http://homeassistant.local:8123/api/webhook/your_id"><button onclick="send('/api/ha',{url:v('ha')},'ha')">Save webhook</button>
<h2>Screensaver video links</h2><div id="aerial"></div>
<input type="text" id="av" placeholder="https://.../video.mp4"><button onclick="send('/api/aerial/add',{url:v('av')},'av')">Add video</button>
<h2>Accounts</h2><p id="acc"></p>
<input type="text" id="se" placeholder="Stremio email"><input type="password" id="sp" placeholder="Stremio password" style="margin-top:8px"><button onclick="send('/api/stremio/login',{email:v('se'),password:v('sp')},'sp')">Sign in to Stremio</button>
<input type="text" id="sk" placeholder="Simkl client ID (simkl.com/settings/developer)" style="margin-top:14px"><button onclick="send('/api/simkl/client',{id:v('sk')},'sk')">Save Simkl client ID</button>
<p>Nuvio, Trakt and Simkl sign in with a code on the TV: Settings &rarr; Connections.</p>
<h2>Cameras (RTSP)</h2><div id="cams"></div><input type="text" id="cn" placeholder="Name, e.g. Garden"><input type="text" id="cu" placeholder="rtsp://user:pass@camera:554/stream" style="margin-top:8px"><button onclick="send('/api/camera/add',{name:v('cn'),url:v('cu')},'cu')">Add camera</button>
<h2>Backup</h2><p>Every setting, catalog, Library and account, tokens included. Keep the file private.</p>
<div class="row"><button onclick="location.href='/api/backup?t='+encodeURIComponent(T)">Download backup</button><input type="file" id="rf" accept=".json,application/json" style="display:none" onchange="restore()"><button class="s" onclick="document.getElementById('rf').click()">Restore from a file</button></div>
</main><script>
var T=new URLSearchParams(location.search).get('t')||'';
function v(i){return document.getElementById(i).value.trim()}
function say(t,ok){var m=document.getElementById('msg');m.textContent=t;m.className=ok?'ok':'bad'}
function api(p,b){return fetch(p+'?t='+encodeURIComponent(T),b?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(b)}:{}).then(function(r){return r.json()})}
function send(p,b,clear){say('...',true);api(p,b).then(function(r){say(r.ok?r.message:r.error,r.ok);if(r.ok){if(clear)document.getElementById(clear).value='';load()}}).catch(function(){say('Cannot reach the TV',false)})}
function addAddon(){var u=v('au');if(u)send('/api/addon/add',{url:u},'au')}
function el(t,c,x){var e=document.createElement(t);if(c)e.className=c;if(x!==undefined)e.textContent=x;return e}
function up(pkg,f){say('Uploading...',true);fetch('/api/icon/upload?pkg='+encodeURIComponent(pkg)+'&t='+encodeURIComponent(T),{method:'POST',body:f}).then(function(r){return r.json()}).then(function(r){say(r.ok?r.message:r.error,r.ok);load()}).catch(function(){say('Upload failed',false)})}
function bgLink(k,i){var u=v(i);if(u)send('/api/bg/link',{kind:k,url:u},i)}
function bgSave(i){var u=v(i);if(u)send('/api/bg/download',{url:u,use:true},i)}
function bgUp(){var f=document.getElementById('bf').files[0];if(!f)return;var x=new XMLHttpRequest(),p=document.getElementById('bgp');
x.open('POST','/api/bg/upload?use=1&name='+encodeURIComponent(f.name)+'&t='+encodeURIComponent(T));
x.upload.onprogress=function(e){if(e.lengthComputable)p.textContent='Uploading '+Math.round(e.loaded*100/e.total)+'%'};
x.onload=function(){p.textContent='';try{var r=JSON.parse(x.responseText);say(r.ok?r.message:r.error,r.ok)}catch(e){say('Upload failed',false)}document.getElementById('bf').value='';load()};
x.onerror=function(){p.textContent='';say('Upload failed',false)};say('Uploading...',true);x.send(f)}
function restore(){var f=document.getElementById('rf').files[0];if(!f)return;if(!confirm('Replace every setting on the TV with this backup?'))return;f.text().then(function(t){say('Restoring...',true);return fetch('/api/restore?t='+encodeURIComponent(T),{method:'POST',headers:{'Content-Type':'application/json'},body:t})}).then(function(r){return r.json()}).then(function(r){say(r.ok?r.message:r.error,r.ok)}).catch(function(){say('Restore failed',false)})}
var BGM=[['carousel','Title backdrops'],['image','My picture'],['video','My video'],['aerial','Screensaver']],poll=0;
function load(){api('/api/state').then(function(d){
var a=document.getElementById('addons');a.textContent='';d.addons.forEach(function(x){var c=el('div','card');var t=el('b','',x.name);if(x.account)t.appendChild(el('span','tag','Nuvio account'));c.appendChild(t);c.appendChild(el('small','',x.host));if(!x.account){var r=el('div','row');var b=el('button','s d','Remove');b.onclick=function(){send('/api/addon/remove',{url:x.url})};r.appendChild(b);c.appendChild(r)}a.appendChild(c)});
var g=d.bg,m=document.getElementById('bgm');m.textContent='';BGM.forEach(function(o){var b=el('button',g.mode==o[0]?'':'s',o[1]);b.onclick=function(){send('/api/bg/mode',{mode:o[0]})};m.appendChild(b)});
document.getElementById('bgnow').textContent=(g.image?'Picture: '+g.image+'  ':'')+(g.video?'Video: '+g.video:'');
var bp=document.getElementById('bgp');if(g.downloading){bp.textContent='Downloading '+g.downloading+'...';clearTimeout(poll);poll=setTimeout(load,2000)}else if(g.last&&bp.textContent.indexOf('Downloading')==0){bp.textContent=g.last}
var bl=document.getElementById('bgfiles');bl.textContent='';g.files.forEach(function(f){var c=el('div','card');var t=el('b','',f.name);t.appendChild(el('span','tag',f.video?'video':'picture'));if(f.used)t.appendChild(el('span','tag','in use'));c.appendChild(t);c.appendChild(el('small','',f.mb+' MB'));var r=el('div','row');
var bu=el('button','s','Use');bu.onclick=function(){send('/api/bg/use',{name:f.name})};var bd=el('button','s d','Delete');bd.onclick=function(){if(confirm('Delete '+f.name+'?'))send('/api/bg/delete',{name:f.name})};r.appendChild(bu);r.appendChild(bd);c.appendChild(r);bl.appendChild(c)});
var p=document.getElementById('apps');p.textContent='';d.apps.forEach(function(x){var c=el('div','card');var t=el('b','',x.label);if(x.custom)t.appendChild(el('span','tag','custom'));c.appendChild(t);c.appendChild(el('small','',x.pkg));var r=el('div','row');
var bu=el('button','s','Link');bu.onclick=function(){var u=prompt('Image link');if(u)send('/api/icon/url',{pkg:x.pkg,url:u})};
var fi=el('input');fi.type='file';fi.accept='image/*';fi.style.display='none';fi.onchange=function(){if(fi.files[0])up(x.pkg,fi.files[0])};
var bf=el('button','s','Upload');bf.onclick=function(){fi.click()};
var br=el('button','s d','Reset');br.onclick=function(){send('/api/icon/reset',{pkg:x.pkg})};
r.appendChild(bu);r.appendChild(bf);r.appendChild(fi);if(x.custom)r.appendChild(br);c.appendChild(r);p.appendChild(c)});
var ks=document.getElementById('tks');ks.textContent=d.tmdb?'\u2713 Set':'Not set';ks.className=d.tmdb?'ok':'';
document.getElementById('tk').placeholder=d.tmdb?'Token saved (type a new one to replace, empty to remove)':'TMDB read access token';
var hs=document.getElementById('ha');hs.previousElementSibling.innerHTML='Home Assistant webhook'+(d.ha?' <span class="ok">&#10003; Set</span>':'');
document.getElementById('ha').placeholder=d.ha?'Webhook saved (type a new one to replace)':'http://homeassistant.local:8123/api/webhook/your_id';
var z=document.getElementById('aerial');z.textContent='';d.aerial.forEach(function(u){var c=el('div','card');c.appendChild(el('small','',u.replace(/^(https?:\/\/[^\/]+).*$/,'$1')));var r=el('div','row');var b=el('button','s d','Remove');b.onclick=function(){send('/api/aerial/remove',{url:u})};r.appendChild(b);c.appendChild(r);z.appendChild(c)})
var A=d.accounts||{};document.getElementById('acc').innerHTML=['nuvio','stremio','trakt','simkl'].map(function(k){return k.charAt(0).toUpperCase()+k.slice(1)+(A[k]?' <span class="ok">&#10003; connected</span>':' <span style="color:#6b7794">not connected</span>')}).join(' &middot; ');
var cm=document.getElementById('cams');cm.textContent='';(d.cams||[]).forEach(function(x,i){var c=el('div','card');c.appendChild(el('b','',x.name));c.appendChild(el('small','',x.host));var r=el('div','row');var bn=el('button','s','Rename');bn.onclick=function(){var n=prompt('Camera name',x.name);if(n)send('/api/camera/rename',{i:i,name:n})};var b=el('button','s d','Remove');b.onclick=function(){if(confirm('Remove '+x.name+'?'))send('/api/camera/remove',{i:i})};r.appendChild(bn);r.appendChild(b);c.appendChild(r);cm.appendChild(c)});
}).catch(function(){say('Cannot reach the TV',false)})}
load();
</script></body></html>"""
    }
}
