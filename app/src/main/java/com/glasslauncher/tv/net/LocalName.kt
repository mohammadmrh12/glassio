package com.glasslauncher.tv.net

import android.content.Context
import com.glasslauncher.tv.data.Bg
import com.glasslauncher.tv.data.Glog
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * A name for this TV on the home network ("glassio-<tv>.local"), answered over multicast DNS while the phone page
 * runs: the address a phone keeps (a web app on its home screen) stays the same when the router gives the TV another
 * IP. Only questions for that one name are answered: A with the TV's own IPv4 address, and AAAA (and anything else)
 * with an NSEC record saying there is only the A one. Without that a phone waited ~5 s for an IPv6 answer before
 * every visit (his web app: a black screen, then Safari's warning after its HTTPS try).
 */
object LocalName {
    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353
    @Volatile private var socket: MulticastSocket? = null
    private var lock: android.net.wifi.WifiManager.MulticastLock? = null

    /** "glassio-<the TV's name>", letters, digits and dashes only (two Glassio TVs at home get two names). */
    fun host(ctx: Context): String {
        val dev = runCatching { android.provider.Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL
        val s = dev.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        return if (s.isEmpty()) "glassio" else "glassio-$s"
    }

    @Synchronized fun start(ctx: Context) {
        if (socket != null) return
        val name = host(ctx) + ".local"
        runCatching {
            lock = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager)
                ?.createMulticastLock("glassio-mdns")?.apply { setReferenceCounted(false); acquire() }
        }
        val s = runCatching {
            MulticastSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)); timeToLive = 255 }
        }.getOrElse { Glog.w("Phone page name", it); return }
        val ifaces = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && it.supportsMulticast() &&
            it.inetAddresses.toList().any { a -> a is Inet4Address } }
        ifaces.forEach { runCatching { s.joinGroup(InetSocketAddress(GROUP, PORT), it) } }
        socket = s
        Bg.io("glass-mdns") {
            // Said once at the start, so a phone that asked a moment ago learns it at once.
            Net.localIp()?.let { ip -> repeat(2) { runCatching { send(s, answer(0, name, ip, null, true, 1), GROUP, PORT) }; kotlinx.coroutines.delay(1000L) } }
            val buf = ByteArray(1500)
            while (socket === s) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: Exception) { break }
                runCatching { reply(s, p, name) }
            }
        }
    }

    @Synchronized fun stop() {
        runCatching { socket?.close() }; socket = null
        runCatching { lock?.release() }; lock = null
    }

    private fun reply(s: MulticastSocket, p: DatagramPacket, name: String) {
        val d = p.data; val n = p.length
        if (n < 12) return
        if (d[2].toInt() and 0x80 != 0) return                           // an answer, not a question
        val qd = ((d[4].toInt() and 0xff) shl 8) or (d[5].toInt() and 0xff)
        var off = 12
        repeat(qd) {
            val (q, end) = readName(d, off, n) ?: return
            if (end + 4 > n) return
            val type = ((d[end].toInt() and 0xff) shl 8) or (d[end + 1].toInt() and 0xff)
            val unicast = d[end + 2].toInt() and 0x80 != 0
            off = end + 4
            if (q.equals(name, ignoreCase = true)) {
                val ip = Net.localIp() ?: return
                val id = ((d[0].toInt() and 0xff) shl 8) or (d[1].toInt() and 0xff)
                val has = type == 1 || type == 255
                // A one-off resolver (not port 5353) wants its own id and the question back, to itself.
                if (p.port != PORT) send(s, answer(id, name, ip, name, has, type), p.address, p.port)
                else if (unicast) send(s, answer(0, name, ip, null, has, type), p.address, p.port)
                else send(s, answer(0, name, ip, null, has, type), GROUP, PORT)
                return
            }
        }
    }

    private fun send(s: MulticastSocket, b: ByteArray, to: InetAddress, port: Int) = s.send(DatagramPacket(b, b.size, to, port))

    private fun labels(name: String): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        name.split('.').filter { it.isNotEmpty() }.forEach { l -> val b = l.toByteArray(); o.write(b.size); o.write(b) }
        o.write(0); return o.toByteArray()
    }

    /**
     * The reply: with [hasA] the A record answers and an NSEC (only A here) is added; otherwise (an AAAA or another
     * type) the NSEC answers, so the asker stops waiting, and the A record is added for next time.
     */
    private fun answer(id: Int, name: String, ip: String, question: String?, hasA: Boolean, qtype: Int): ByteArray {
        val o = java.io.ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8 and 0xff); o.write(v and 0xff) }
        val unicastLegacy = question != null
        val cls = if (unicastLegacy) 1 else 0x8001                          // cache-flush bit, never to a legacy asker
        fun a() { o.write(labels(name)); u16(1); u16(cls); u16(0); u16(120); u16(4); InetAddress.getByName(ip).address.forEach { o.write(it.toInt() and 0xff) } }
        fun nsec() {
            val n = labels(name)
            o.write(n); u16(47); u16(cls); u16(0); u16(120); u16(n.size + 3); o.write(n); o.write(0); o.write(1); o.write(0x40)   // only type 1 (A)
        }
        u16(id); u16(0x8400); u16(if (unicastLegacy) 1 else 0); u16(1); u16(0); u16(1)
        if (question != null) { o.write(labels(question)); u16(qtype); u16(1) }
        if (hasA) { a(); nsec() } else { nsec(); a() }
        return o.toByteArray()
    }

    /** The name at [start] and where the question goes on after it (names in questions may point back). */
    private fun readName(d: ByteArray, start: Int, n: Int): Pair<String, Int>? {
        val parts = ArrayList<String>(); var i = start; var end = -1; var jumps = 0
        while (i < n) {
            val len = d[i].toInt() and 0xff
            when {
                len == 0 -> { if (end < 0) end = i + 1; return parts.joinToString(".") to end }
                len and 0xc0 == 0xc0 -> {
                    if (i + 1 >= n || ++jumps > 8) return null
                    if (end < 0) end = i + 2
                    i = ((len and 0x3f) shl 8) or (d[i + 1].toInt() and 0xff)
                }
                else -> { if (i + 1 + len > n) return null; parts += String(d, i + 1, len, Charsets.UTF_8); i += 1 + len }
            }
        }
        return null
    }
}
