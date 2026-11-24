package com.glasslauncher.tv.net

import android.content.Context
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.ui.Cam
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Moving a camera (pan, tilt, zoom and its presets) from the cameras page (#30, #31): hold OK on a camera.
 *
 * - A camera from Home Assistant (`ha://camera.x`): Home Assistant's own `onvif.ptz` service (the camera must come from
 *   its ONVIF integration).
 * - Any other camera: ONVIF straight to the camera, at its address on port 80 (or the one set with `onvif=<port>` in
 *   the link's query), with the user and password of its RTSP link (WS-Security digest, the camera's clock taken
 *   into account). Its PTZ and media services come from GetCapabilities, the profile from GetProfiles.
 *
 * Every call of one camera runs in order on its own thread (a Stop never overtakes its Move); none on the main thread.
 */
class Ptz private constructor(private val ctx: Context, private val cam: Cam) {
    private val run = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-ptz").apply { isDaemon = true } }
    private var onvif: Onvif? = null
    private var presets: List<Pair<String, String>> = emptyList()
    /** The presets' names (empty: none, or not known). */
    val presetNames get() = presets.map { it.second }

    /** A direction (−1, 0, 1 each) held down: moves for a second (sent again while held) or until [stop]. */
    fun move(pan: Int, tilt: Int, zoom: Int) = run.execute {
        runCatching { if (cam.url.startsWith("ha://")) ha(pan, tilt, zoom, null) else onvif?.move(pan * 0.5f, tilt * 0.5f, zoom * 0.5f) }
            .onFailure { Glog.w("PTZ ${cam.name}: move", it) }
    }
    fun stop() = run.execute {
        runCatching { if (cam.url.startsWith("ha://")) ha(0, 0, 0, null, stop = true) else onvif?.stop() }.onFailure { Glog.w("PTZ ${cam.name}: stop", it) }
    }
    fun preset(i: Int) = run.execute {
        val p = presets.getOrNull(i) ?: return@execute
        runCatching { if (cam.url.startsWith("ha://")) ha(0, 0, 0, p.first) else onvif?.preset(p.first) }.onFailure { Glog.w("PTZ ${cam.name}: preset", it) }
    }
    fun close() { run.execute { runCatching { if (!cam.url.startsWith("ha://")) onvif?.stop() } }; run.shutdown() }

    /** Home Assistant: onvif.ptz (ContinuousMove for a held key, Stop, GotoPreset). */
    private fun ha(pan: Int, tilt: Int, zoom: Int, preset: String?, stop: Boolean = false) {
        val st = (ctx.applicationContext as GlassApp).settings
        val base = (com.glasslauncher.tv.CamPlayer.haTestUrl ?: st.haUrl).trimEnd('/')
        val token = com.glasslauncher.tv.CamPlayer.haTestToken ?: st.haToken
        val body = JSONObject().put("entity_id", cam.url.removePrefix("ha://"))
        when {
            stop -> body.put("move_mode", "Stop")
            preset != null -> body.put("move_mode", "GotoPreset").put("preset", preset)
            else -> {
                // A second at most: the held key sends it again, so a lost release stops the camera by itself.
                body.put("move_mode", "ContinuousMove").put("speed", 0.5).put("continuous_duration", 1)
                if (pan != 0) body.put("pan", if (pan < 0) "LEFT" else "RIGHT")
                if (tilt != 0) body.put("tilt", if (tilt > 0) "UP" else "DOWN")
                if (zoom != 0) body.put("zoom", if (zoom > 0) "ZOOM_IN" else "ZOOM_OUT")
            }
        }
        val r = http.newCall(Request.Builder().url("$base/api/services/onvif/ptz").header("Authorization", "Bearer $token")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
        r.use { if (!it.isSuccessful) error("Home Assistant answered ${it.code}") }
    }

    companion object {
        private val http = Net.ok.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS).build()

        /**
         * Blocking. The camera's PTZ, or a failure saying why (no PTZ, no answer, refused). Home Assistant: its
         * `onvif` services must exist (checked once here).
         */
        fun open(ctx: Context, cam: Cam): Result<Ptz> = runCatching {
            val p = Ptz(ctx, cam)
            if (cam.url.startsWith("ha://")) {
                val st = (ctx.applicationContext as GlassApp).settings
                val base = (com.glasslauncher.tv.CamPlayer.haTestUrl ?: st.haUrl).trimEnd('/')
                val token = com.glasslauncher.tv.CamPlayer.haTestToken ?: st.haToken
                val out = http.newCall(Request.Builder().url("$base/api/services").header("Authorization", "Bearer $token").build()).execute()
                    .use { if (!it.isSuccessful) error("Home Assistant answered ${it.code}"); it.body?.string().orEmpty() }
                if (!out.contains("\"onvif\"")) error("No PTZ: Home Assistant has no ONVIF cameras")
            } else {
                val o = Onvif.of(cam.url) ?: error("No PTZ: not a camera link with an address")
                o.connect()
                p.onvif = o
                p.presets = runCatching { o.presets() }.getOrDefault(emptyList())
            }
            p
        }.onFailure { Glog.w("PTZ ${cam.name}: " + (it.message ?: it.javaClass.simpleName)) }
    }

    /** ONVIF over SOAP 1.2 (device, media and PTZ services), with a WS-Security UsernameToken digest. */
    private class Onvif(val host: String, val port: Int, val user: String, val pass: String, val channel: Int) {
        private var ptzUrl = ""
        private var mediaUrl = ""
        private var profile = ""
        private var skewMs = 0L

        companion object {
            fun of(url: String): Onvif? {
                val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
                val host = u.host ?: return null
                val info = u.rawUserInfo.orEmpty()
                val user = java.net.URLDecoder.decode(info.substringBefore(':'), "UTF-8")
                val pass = java.net.URLDecoder.decode(info.substringAfter(':', ""), "UTF-8")
                val q = u.rawQuery.orEmpty().split('&')
                val port = q.firstOrNull { it.startsWith("onvif=") }?.substringAfter('=')?.toIntOrNull() ?: 80
                // A recorder's channel: Dahua's "channel=2", Hikvision's "/Streaming/Channels/201" (0: not said).
                val channel = q.firstOrNull { it.startsWith("channel=") }?.substringAfter('=')?.toIntOrNull()
                    ?: Regex("/Channels/(\\d+?)0\\d$").find(u.rawPath.orEmpty())?.groupValues?.get(1)?.toIntOrNull() ?: 0
                return Onvif(host, port, user, pass, channel)
            }
        }

        fun connect() {
            val device = "http://$host:$port/onvif/device_service"
            // The camera's clock: the digest carries the time, and a camera far off refuses it.
            runCatching {
                val t = soap(device, "<tds:GetSystemDateAndTime/>", secure = false)
                fun num(tag: String) = Regex("<[^>]*UTCDateTime>[\\s\\S]*?<[^>:]*:?$tag>(\\d+)<").find(t)?.groupValues?.get(1)?.toInt()
                val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
                    clear(); set(num("Year")!!, num("Month")!! - 1, num("Day")!!, num("Hour")!!, num("Minute")!!, num("Second")!!)
                }
                skewMs = c.timeInMillis - System.currentTimeMillis()
            }
            val caps = soap(device, "<tds:GetCapabilities><tds:Category>All</tds:Category></tds:GetCapabilities>")
            fun xaddr(section: String) = Regex("<[^>]*:?$section>[\\s\\S]*?<[^>]*:?XAddr>([^<]+)<").find(caps)?.groupValues?.get(1)?.trim()
            mediaUrl = xaddr("Media") ?: error("No media service")
            ptzUrl = xaddr("PTZ") ?: error("No PTZ: this camera can't move")
            val profiles = soap(mediaUrl, "<trt:GetProfiles/>")
            // A recorder has profiles per channel (its cameras): the link's channel is the n-th video source. Then the
            // first profile of it with a PTZ configuration, else the first one.
            val blocks = Regex("<[^>]*:?Profiles\\b[^>]*token=\"([^\"]+)\"[\\s\\S]*?</[^>]*:?Profiles>").findAll(profiles).toList()
            fun src(b: MatchResult) = Regex("<[^>]*:?SourceToken>([^<]*)<").find(b.value)?.groupValues?.get(1).orEmpty()
            val sources = blocks.map(::src).distinct()
            val mine = sources.getOrNull(channel - 1)?.let { s -> blocks.filter { src(it) == s } }?.takeIf { it.isNotEmpty() } ?: blocks
            profile = (mine.firstOrNull { it.value.contains("PTZConfiguration") } ?: mine.firstOrNull())?.groupValues?.get(1) ?: error("No profile")
            Glog.i("PTZ: profile $profile (channel $channel of ${sources.size})")
        }

        fun move(x: Float, y: Float, z: Float) {
            val pt = if (x != 0f || y != 0f) "<tt:PanTilt x=\"$x\" y=\"$y\"/>" else ""
            val zm = if (z != 0f) "<tt:Zoom x=\"$z\"/>" else ""
            soap(ptzUrl, "<tptz:ContinuousMove><tptz:ProfileToken>$profile</tptz:ProfileToken><tptz:Velocity>$pt$zm</tptz:Velocity><tptz:Timeout>PT1S</tptz:Timeout></tptz:ContinuousMove>")
        }
        fun stop() = soap(ptzUrl, "<tptz:Stop><tptz:ProfileToken>$profile</tptz:ProfileToken><tptz:PanTilt>true</tptz:PanTilt><tptz:Zoom>true</tptz:Zoom></tptz:Stop>")
        fun preset(token: String) = soap(ptzUrl, "<tptz:GotoPreset><tptz:ProfileToken>$profile</tptz:ProfileToken><tptz:PresetToken>$token</tptz:PresetToken></tptz:GotoPreset>")
        fun presets(): List<Pair<String, String>> {
            val out = soap(ptzUrl, "<tptz:GetPresets><tptz:ProfileToken>$profile</tptz:ProfileToken></tptz:GetPresets>")
            return Regex("<[^>]*:?Preset\\b[^>]*token=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</[^>]*:?Preset>").findAll(out).map { m ->
                m.groupValues[1] to (Regex("<[^>]*:?Name>([^<]*)<").find(m.groupValues[2])?.groupValues?.get(1)?.trim().orEmpty().ifBlank { m.groupValues[1] })
            }.toList().take(20)
        }

        private fun soap(url: String, body: String, secure: Boolean = true): String {
            val head = if (secure && user.isNotEmpty()) security() else ""
            val env = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"" +
                " xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\" xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"" +
                " xmlns:tptz=\"http://www.onvif.org/ver20/ptz/wsdl\" xmlns:tt=\"http://www.onvif.org/ver10/schema\">" +
                "<s:Header>$head</s:Header><s:Body>$body</s:Body></s:Envelope>"
            val r = http.newCall(Request.Builder().url(url).post(env.toRequestBody("application/soap+xml; charset=utf-8".toMediaType())).build()).execute()
            return r.use { val t = it.body?.string().orEmpty(); if (!it.isSuccessful) error("Camera answered ${it.code}" + (Regex("<[^>]*:?Text[^>]*>([^<]+)<").find(t)?.groupValues?.get(1)?.let { m -> ": $m" } ?: "")); t }
        }

        private fun security(): String {
            val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val created = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date(System.currentTimeMillis() + skewMs))
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(nonce + created.toByteArray() + pass.toByteArray())
            fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
            return "<Security s:mustUnderstand=\"1\" xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\">" +
                "<UsernameToken><Username>${xml(user)}</Username>" +
                "<Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">${b64(digest)}</Password>" +
                "<Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">${b64(nonce)}</Nonce>" +
                "<Created xmlns=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">$created</Created></UsernameToken></Security>"
        }
        private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
