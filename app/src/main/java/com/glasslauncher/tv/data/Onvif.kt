package com.glasslauncher.tv.data

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * Finds cameras on the home network the standard way (ONVIF: Hikvision, Dahua, Reolink, Tapo, Amcrest, Axis,
 * Uniview and most others), then asks a camera for its live stream link with your camera login.
 */
object Onvif {
    data class Found(val ip: String, val service: String, val name: String)

    /** Blocking, about 3 s: WS-Discovery probe on the local network. */
    fun discover(timeoutMs: Int = 3000): List<Found> {
        val probe = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
        val out = LinkedHashMap<String, Found>()
        runCatching {
            DatagramSocket().use { sock ->
                sock.soTimeout = 400; sock.broadcast = true
                val data = probe.toByteArray()
                repeat(2) { sock.send(DatagramPacket(data, data.size, InetAddress.getByName("239.255.255.250"), 3702)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(65536)
                while (System.currentTimeMillis() < end) {
                    val p = DatagramPacket(buf, buf.size)
                    try { sock.receive(p) } catch (_: java.net.SocketTimeoutException) { continue }
                    val xml = String(p.data, 0, p.length)
                    val xaddr = Regex("<[^>]*XAddrs>([^<]+)<").find(xml)?.groupValues?.get(1)?.trim()?.split(' ')?.firstOrNull { it.startsWith("http") } ?: continue
                    val ip = p.address.hostAddress ?: continue
                    val scopes = Regex("<[^>]*Scopes>([^<]+)<").find(xml)?.groupValues?.get(1).orEmpty()
                    val name = Regex("onvif://www.onvif.org/name/([^ ]+)").find(scopes)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
                        ?: Regex("onvif://www.onvif.org/hardware/([^ ]+)").find(scopes)?.groupValues?.get(1) ?: "Camera"
                    out[ip] = Found(ip, xaddr, name)
                }
            }
        }.onFailure { Glog.w("Camera search", it) }
        return out.values.toList()
    }

    private fun soap(url: String, body: String, user: String, pass: String): String {
        val nonce = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val created = java.time.Instant.now().toString()
        val digest = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(nonce + created.toByteArray() + pass.toByteArray()))
        val sec = if (user.isBlank()) "" else """<s:Header><Security xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>$user</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${Base64.getEncoder().encodeToString(nonce)}</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security></s:Header>"""
        val env = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">$sec<s:Body>$body</s:Body></s:Envelope>"""
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 6000; c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
        try {
            c.outputStream.use { it.write(env.toByteArray()) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { if (code == 401 || text.contains("NotAuthorized")) "Wrong camera login" else "Camera answered HTTP $code" }
            return text
        } finally { c.disconnect() }
    }

    /** Blocking. The camera's main live stream (rtsp://…), with the login put into the link. */
    fun streamUri(found: Found, user: String, pass: String): String {
        val media = runCatching {
            val caps = soap(found.service, """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>""", user, pass)
            Regex("<[^>]*Media>.*?<[^>]*XAddr>([^<]+)<", RegexOption.DOT_MATCHES_ALL).find(caps)?.groupValues?.get(1)
        }.getOrNull() ?: found.service.replace("device_service", "media_service")
        val profiles = soap(media, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""", user, pass)
        val token = Regex("""<[^>]*Profiles[^>]*token="([^"]+)"""").find(profiles)?.groupValues?.get(1) ?: error("The camera has no stream profile")
        val r = soap(media, """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>$token</ProfileToken></GetStreamUri>""", user, pass)
        val uri = Regex("<[^>]*Uri>([^<]+)<").find(r)?.groupValues?.get(1)?.replace("&amp;", "&") ?: error("The camera gave no stream link")
        return if (user.isBlank() || uri.contains("@")) uri
        else uri.replaceFirst("://", "://${java.net.URLEncoder.encode(user, "UTF-8")}:${java.net.URLEncoder.encode(pass, "UTF-8")}@")
    }
}
