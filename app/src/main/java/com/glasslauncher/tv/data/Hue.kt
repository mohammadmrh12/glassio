package com.glasslauncher.tv.data

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.pow

/**
 * Philips Hue on the home network (the bridge's local API: nothing goes through the internet once it is found).
 * Pairing: the bridge's button is pressed, Glassio asks for a key ([pair]). Then the lights take the colours of
 * what is on screen ([HueSync]).
 */
object Hue {
    /**
     * A light and what it can show: [kind] "color", "ct" (white shades only) or "dim"; Philips' own lights report
     * their colour range ([gamut], three xy corners); lights of other makes (Lidl Livarno, Tuya…) usually don't, and
     * show colours more faithfully from hue and saturation than from xy.
     */
    class Light(val id: String, val name: String, val color: Boolean, val kind: String = if (color) "color" else "dim",
                val maker: String = "", val model: String = "", val gamut: List<Pair<Double, Double>>? = null, val gradient: Boolean = false) {
        val philips get() = maker.contains("Signify", true) || maker.contains("Philips", true)
    }
    /**
     * A room, a zone or an entertainment area. An entertainment area (made in the Hue app) knows where each light
     * stands around the TV ([places]: x -1 left … 1 right, z -1 floor … 1 ceiling).
     */
    class Group(val id: String, val name: String, val type: String, val lights: List<String>, val places: Map<String, Pair<Double, Double>> = emptyMap())

    private fun call(method: String, url: String, body: String? = null, timeout: Int = 4000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = timeout; c.readTimeout = timeout
        if (body != null) {
            c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Bridges on this network: announced on the network itself (mDNS, like the Hue app finds them), else Philips' finder. Blocking. */
    fun discover(ctx: android.content.Context): List<String> = discoverLocal(ctx).ifEmpty { discoverCloud() }

    private fun discoverLocal(ctx: android.content.Context): List<String> {
        val nsd = ctx.getSystemService(android.net.nsd.NsdManager::class.java) ?: return emptyList()
        val found = java.util.concurrent.CopyOnWriteArrayList<String>()
        val resolving = java.util.concurrent.Semaphore(1)
        val l = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) {}
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceLost(i: android.net.nsd.NsdServiceInfo?) {}
            @Suppress("DEPRECATION")
            override fun onServiceFound(i: android.net.nsd.NsdServiceInfo) {
                Thread {
                    // One resolve at a time (older Android refuses a second one while one runs).
                    if (!resolving.tryAcquire(3, java.util.concurrent.TimeUnit.SECONDS)) return@Thread
                    runCatching {
                        nsd.resolveService(i, object : android.net.nsd.NsdManager.ResolveListener {
                            override fun onResolveFailed(s: android.net.nsd.NsdServiceInfo?, e: Int) { resolving.release() }
                            override fun onServiceResolved(s: android.net.nsd.NsdServiceInfo) {
                                s.host?.hostAddress?.takeIf { it.count { c -> c == '.' } == 3 }?.let { if (it !in found) found += it }
                                resolving.release()
                            }
                        })
                    }.onFailure { resolving.release() }
                }.start()
            }
        }
        return runCatching {
            nsd.discoverServices("_hue._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l)
            repeat(25) { if (found.isNotEmpty()) return@repeat; Thread.sleep(200) }
            Thread.sleep(300)
            runCatching { nsd.stopServiceDiscovery(l) }
            found.toList()
        }.getOrElse { Glog.w("Hue local discovery", it); emptyList() }
    }

    private fun discoverCloud(): List<String> = runCatching {
        val a = JSONArray(call("GET", "https://discovery.meethue.com/", timeout = 6000))
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("internalipaddress")?.takeIf { s -> s.isNotBlank() } }
    }.getOrElse { Glog.w("Hue discovery", it); emptyList() }

    class PairResult(val user: String?, val waiting: Boolean, val error: String?)

    /** Asks the bridge for a key; [PairResult.waiting] while its button hasn't been pressed. Blocking. */
    fun pair(ip: String): PairResult = runCatching {
        val r = JSONArray(call("POST", "http://$ip/api", JSONObject().put("devicetype", "glassio#tv").toString()))
        val o = r.getJSONObject(0)
        o.optJSONObject("success")?.optString("username")?.takeIf { it.isNotBlank() }?.let { return PairResult(it, false, null) }
        val e = o.optJSONObject("error")
        if (e?.optInt("type") == 101) PairResult(null, true, null) else PairResult(null, false, e?.optString("description") ?: "no answer")
    }.getOrElse { PairResult(null, false, it.message ?: it.javaClass.simpleName) }

    fun lights(ip: String, user: String): List<Light> = runCatching {
        val o = JSONObject(call("GET", "http://$ip/api/$user/lights"))
        o.keys().asSequence().map { id ->
            val l = o.getJSONObject(id)
            val st = l.optJSONObject("state")
            val color = st?.has("xy") == true || st?.has("hue") == true
            val ctl = l.optJSONObject("capabilities")?.optJSONObject("control")
            val g = ctl?.optJSONArray("colorgamut")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { p -> p.getDouble(0) to p.getDouble(1) } } }?.takeIf { it.size == 3 }
            val kind = if (color) "color" else if (st?.has("ct") == true) "ct" else "dim"
            val prod = l.optString("productname") + " " + l.optString("type")
            Light(id, l.optString("name", "Light $id"), color, kind, l.optString("manufacturername"), l.optString("modelid"), g,
                gradient = prod.contains("gradient", true))
        }.sortedBy { it.name }.toList()
    }.getOrElse { Glog.w("Hue lights", it); emptyList() }

    /** Rooms and zones. */
    fun groups(ip: String, user: String): List<Group> = runCatching {
        val o = JSONObject(call("GET", "http://$ip/api/$user/groups"))
        o.keys().asSequence().mapNotNull { id ->
            val g = o.getJSONObject(id)
            val type = g.optString("type")
            if (type != "Room" && type != "Zone" && type != "Entertainment") return@mapNotNull null
            val ls = g.optJSONArray("lights")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            val places = g.optJSONObject("locations")?.let { o2 ->
                o2.keys().asSequence().mapNotNull { k -> o2.optJSONArray(k)?.takeIf { it.length() >= 3 }?.let { a -> k to (a.optDouble(0) to a.optDouble(2)) } }.toMap()
            }.orEmpty()
            Group(id, g.optString("name"), type, ls, places)
        }.sortedWith(compareBy({ it.type }, { it.name })).toList()
    }.getOrElse { Glog.w("Hue groups", it); emptyList() }

    /** Each light's state now (to put it back later). Blocking. */
    fun states(ip: String, user: String, ids: Collection<String>): Map<String, JSONObject> = runCatching {
        val o = JSONObject(call("GET", "http://$ip/api/$user/lights"))
        ids.mapNotNull { id -> o.optJSONObject(id)?.optJSONObject("state")?.let { id to it } }.toMap()
    }.getOrElse { Glog.w("Hue states", it); emptyMap() }

    fun set(ip: String, user: String, id: String, state: JSONObject) {
        runCatching { call("PUT", "http://$ip/api/$user/lights/$id/state", state.toString(), 2500) }
            .onFailure { Glog.w("Hue light $id: ${it.message}") }
    }

    /** A light's saved state as a command that brings it back. */
    fun restoreCommand(s: JSONObject, transition: Int): JSONObject {
        val on = s.optBoolean("on", true)
        val c = JSONObject().put("on", on).put("transitiontime", transition)
        if (!on) return c
        if (s.has("bri")) c.put("bri", s.optInt("bri"))
        when (s.optString("colormode")) {
            "ct" -> if (s.has("ct")) c.put("ct", s.optInt("ct"))
            "hs" -> { if (s.has("hue")) c.put("hue", s.optInt("hue")); if (s.has("sat")) c.put("sat", s.optInt("sat")) }
            "xy" -> s.optJSONArray("xy")?.let { c.put("xy", it) }
        }
        return c
    }

    /** sRGB to the CIE xy the bridge takes (Philips' own conversion, wide gamut). */
    fun xy(color: Int): Pair<Double, Double> {
        fun lin(v: Int): Double { val c = v / 255.0; return if (c > 0.04045) ((c + 0.055) / 1.055).pow(2.4) else c / 12.92 }
        val r = lin(Color.red(color)); val g = lin(Color.green(color)); val b = lin(Color.blue(color))
        val x = r * 0.664511 + g * 0.154324 + b * 0.162028
        val y = r * 0.283881 + g * 0.668433 + b * 0.047685
        val z = r * 0.000088 + g * 0.072310 + b * 0.986039
        val sum = x + y + z
        return if (sum <= 0.0) 0.3227 to 0.329 else (x / sum) to (y / sum)
    }

    /** [xy] moved into a light's colour range (the nearest point of its triangle when it falls outside). */
    fun clamp(xy: Pair<Double, Double>, g: List<Pair<Double, Double>>?): Pair<Double, Double> {
        if (g == null) return xy
        val (r, gr, b) = Triple(g[0], g[1], g[2])
        fun cross(a: Pair<Double, Double>, b: Pair<Double, Double>, p: Pair<Double, Double>) = (b.first - a.first) * (p.second - a.second) - (b.second - a.second) * (p.first - a.first)
        val inside = cross(r, gr, xy) >= 0 == cross(gr, b, xy) >= 0 && cross(gr, b, xy) >= 0 == cross(b, r, xy) >= 0
        if (inside) return xy
        fun nearest(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
            val dx = b.first - a.first; val dy = b.second - a.second
            val t = (((xy.first - a.first) * dx + (xy.second - a.second) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            return (a.first + t * dx) to (a.second + t * dy)
        }
        return listOf(nearest(r, gr), nearest(gr, b), nearest(b, r)).minBy { (it.first - xy.first).pow(2) + (it.second - xy.second).pow(2) }
    }

    /** The white shade (mirek, 153 cold … 500 warm) nearest a colour, for lights that only do shades of white. */
    fun mirek(color: Int): Int {
        val (x, y) = xy(color)
        val n = (x - 0.3320) / (0.1858 - y)
        val cct = 449 * n.pow(3) + 3525 * n.pow(2) + 6823.3 * n + 5520.33
        return (1_000_000 / cct.coerceIn(2000.0, 6500.0)).toInt().coerceIn(153, 500)
    }

    /** Makes a light blink once (to tell which one it is). */
    fun identify(ip: String, user: String, id: String) = set(ip, user, id, JSONObject().put("alert", "select"))

    /** The part of the screen in front of a light standing at [place] (its x and height in an entertainment area). */
    fun placeRect(place: Pair<Double, Double>): android.graphics.RectF {
        val cx = ((place.first + 1) / 2).toFloat().coerceIn(0f, 1f)
        val cy = (1 - (place.second + 1) / 2).toFloat().coerceIn(0f, 1f)
        val l = (cx - 0.2f).coerceIn(0f, 0.6f); val t = (cy - 0.25f).coerceIn(0f, 0.5f)
        return android.graphics.RectF(l, t, l + 0.4f, t + 0.5f)
    }

    /** A part of the screen (for a light behind that part of the TV), as fractions of the picture. */
    fun zoneRect(zone: String): android.graphics.RectF = when (zone) {
        "left" -> android.graphics.RectF(0f, 0f, 0.33f, 1f)
        "right" -> android.graphics.RectF(0.67f, 0f, 1f, 1f)
        "top" -> android.graphics.RectF(0f, 0f, 1f, 0.33f)
        "bottom" -> android.graphics.RectF(0f, 0.67f, 1f, 1f)
        "center" -> android.graphics.RectF(0.25f, 0.25f, 0.75f, 0.75f)
        "topleft" -> android.graphics.RectF(0f, 0f, 0.5f, 0.5f)
        "topright" -> android.graphics.RectF(0.5f, 0f, 1f, 0.5f)
        "bottomleft" -> android.graphics.RectF(0f, 0.5f, 0.5f, 1f)
        "bottomright" -> android.graphics.RectF(0.5f, 0.5f, 1f, 1f)
        else -> android.graphics.RectF(0f, 0f, 1f, 1f)
    }
    val ZONES = listOf("auto", "whole", "left", "right", "top", "bottom", "center", "topleft", "topright", "bottomleft", "bottomright", "gradient")
    /** What a part of the screen means, for the list to choose from. */
    fun zoneHint(z: String) = when (z) {
        "auto" -> "Its place in the entertainment area, else one of the picture's colours"
        "whole" -> "The picture's main colour"; "gradient" -> "Several colours along a gradient strip"
        else -> "The colour that fills that part most"
    }
    fun zoneName(z: String) = when (z) {
        "whole" -> "Whole screen"; "left" -> "Left"; "right" -> "Right"; "top" -> "Top"; "bottom" -> "Bottom"; "center" -> "Centre"
        "topleft" -> "Top left"; "topright" -> "Top right"; "bottomleft" -> "Bottom left"; "bottomright" -> "Bottom right"
        "gradient" -> "Along the strip (left → top → right)"; else -> "Automatic"
    }

    /** A part of [b] (fractions), small. */
    fun crop(b: Bitmap, r: android.graphics.RectF): Bitmap {
        val x = (r.left * b.width).toInt().coerceIn(0, b.width - 1); val y = (r.top * b.height).toInt().coerceIn(0, b.height - 1)
        val w = ((r.right - r.left) * b.width).toInt().coerceIn(1, b.width - x); val h = ((r.bottom - r.top) * b.height).toInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    // ---- The bridge's newer API (for gradient strips: several colours along one light). HTTPS with the bridge's
    // own certificate (signed by Philips, not by a public authority): accepted for the bridge's address only.
    private val v2Ids = java.util.concurrent.ConcurrentHashMap<String, String>()
    private fun https(method: String, ip: String, user: String, path: String, body: String? = null): String {
        val c = URL("https://$ip$path").openConnection() as javax.net.ssl.HttpsURLConnection
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(a: Array<out java.security.cert.X509Certificate>?, t: String?) {}
            override fun checkServerTrusted(a: Array<out java.security.cert.X509Certificate>?, t: String?) {}
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
        }
        c.sslSocketFactory = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }.socketFactory
        c.hostnameVerifier = javax.net.ssl.HostnameVerifier { h, _ -> h == ip }
        c.requestMethod = method; c.connectTimeout = 3000; c.readTimeout = 3000
        c.setRequestProperty("hue-application-key", user)
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        return try { c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }
    private fun v2Id(ip: String, user: String, id: String): String? = v2Ids[id] ?: runCatching {
        val d = JSONObject(https("GET", ip, user, "/clip/v2/resource/light")).getJSONArray("data")
        for (i in 0 until d.length()) { val o = d.getJSONObject(i); v2Ids[o.optString("id_v1").substringAfterLast('/')] = o.getString("id") }
        v2Ids[id]
    }.getOrNull()

    /** A gradient strip in several colours (up to five points, along the strip). */
    fun setGradient(ip: String, user: String, id: String, colors: List<Pair<Double, Double>>, bri: Int, transitionMs: Int) {
        val v2 = v2Id(ip, user, id) ?: return
        val pts = JSONArray(); colors.take(5).forEach { (x, y) -> pts.put(JSONObject().put("color", JSONObject().put("xy", JSONObject().put("x", x).put("y", y)))) }
        val body = JSONObject().put("on", JSONObject().put("on", true)).put("dimming", JSONObject().put("brightness", bri * 100.0 / 254))
            .put("gradient", JSONObject().put("points", pts)).put("dynamics", JSONObject().put("duration", transitionMs))
        runCatching { https("PUT", ip, user, "/clip/v2/resource/light/$v2", body.toString()) }.onFailure { Glog.w("Hue gradient $id: ${it.message}") }
    }

    /**
     * The picture's main colours, most important first: pixels grouped by hue, each group weighed by how many
     * pixels and how colourful they are; near-black and near-grey pixels count little (a dark film poster still
     * gives its colours). [n] colours at most; a grey picture gives one soft white.
     */
    fun palette(src: Bitmap, n: Int): List<Int> {
        val s = Bitmap.createScaledBitmap(src, 32, 18, true)
        val px = IntArray(32 * 18); s.getPixels(px, 0, 32, 0, 0, 32, 18)
        if (s !== src) s.recycle()
        val bins = 16
        val w = DoubleArray(bins); val r = DoubleArray(bins); val g = DoubleArray(bins); val b = DoubleArray(bins)
        var grey = 0.0; var gr = 0.0; var gg = 0.0; var gb = 0.0
        val hsv = FloatArray(3)
        for (p in px) {
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            if (hsv[2] < 0.08f) continue
            if (hsv[1] < 0.18f) { val k = hsv[2].toDouble(); grey += k; gr += Color.red(p) * k; gg += Color.green(p) * k; gb += Color.blue(p) * k; continue }
            val i = ((hsv[0] / 360f) * bins).toInt().coerceIn(0, bins - 1)
            val k = (hsv[1] * hsv[1] * (0.3f + hsv[2])).toDouble()
            w[i] += k; r[i] += Color.red(p) * k; g[i] += Color.green(p) * k; b[i] += Color.blue(p) * k
        }
        val order = (0 until bins).filter { w[it] > 0.4 }.sortedByDescending { w[it] }
        val out = order.take(n).map { i -> Color.rgb((r[i] / w[i]).toInt(), (g[i] / w[i]).toInt(), (b[i] / w[i]).toInt()) }.toMutableList()
        if (out.isEmpty()) out += if (grey > 0) Color.rgb((gr / grey).toInt(), (gg / grey).toInt(), (gb / grey).toInt()) else Color.rgb(255, 214, 170)
        return out
    }
}

/**
 * Hue Sync: the chosen lights take the colours of what Glassio shows (the backdrop, the poster or the logo, a
 * video, the music's artwork). Each light can follow a part of the screen (the one it sits behind), and each make
 * gets the colour the way it shows it best. Their own state is kept first, and given back when you leave Glassio
 * for an app or a film, or the TV turns off (or they are dimmed or turned off, as chosen).
 *
 * Speed: a new picture is worked out at once and only the lights whose colour really changed are sent, one after
 * another about ten a second (the bridge's own pace; faster, it drops commands).
 */
object HueSync {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "glass-hue").apply { isDaemon = true } }
    @Volatile private var saved: Map<String, JSONObject>? = null
    /** Glassio is on screen (sync allowed). */
    @Volatile var active = false

    fun ready(st: Settings) = st.hueOn && st.hueIp.isNotBlank() && st.hueUser.isNotBlank()

    @Volatile private var groupCache: List<Hue.Group>? = null
    @Volatile private var lightCache: Map<String, Hue.Light>? = null
    fun refreshGroups() { groupCache = null; lightCache = null }

    /** The lights to colour: the chosen rooms' and zones' lights and the chosen lights, once each. */
    fun targets(st: Settings, groups: List<Hue.Group>): List<String> =
        (groups.filter { it.id in st.hueGroups }.flatMap { it.lights } + st.hueLights).distinct()

    /** Each light's part of the screen ("id:zone,…"). */
    fun zones(st: Settings): Map<String, String> = st.hueZones.split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    fun setZone(st: Settings, id: String, zone: String) { st.hueZones = (zones(st) + (id to zone)).filterValues { it != "auto" }.entries.joinToString(",") { "${it.key}:${it.value}" } }
    /** How a light of another make is sent its colour ("id:hs|xy,…"; automatic: hue and saturation). */
    fun methods(st: Settings): Map<String, String> = st.hueMethods.split(',').mapNotNull { e -> e.split(':').takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    fun setMethod(st: Settings, id: String, m: String) { st.hueMethods = (methods(st) + (id to m)).filterValues { it != "auto" }.entries.joinToString(",") { "${it.key}:${it.value}" } }

    /** How colourful: Subtle, Natural, Vivid, Maximum. */
    private fun intensify(c: Int, level: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        hsv[1] = when (level) { 0 -> hsv[1] * 0.6f; 1 -> hsv[1]; 2 -> (hsv[1] * 1.35f + 0.1f); else -> if (hsv[1] > 0.15f) 1f else hsv[1] }.coerceIn(0f, 1f)
        hsv[2] = 1f
        return Color.HSVToColor(hsv)
    }

    /** One light's next state, as worked out from the picture (sent by [sender]). */
    private class Want(val cmd: JSONObject?, val gradient: List<Pair<Double, Double>>?, val bri: Int, val key: String)
    private val pending = java.util.concurrent.ConcurrentHashMap<String, Want>()
    private val sentKey = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The colour each light was last given (a big change of colour is made quickly, see [work]). */
    private val lastXy = java.util.concurrent.ConcurrentHashMap<String, Pair<Double, Double>>()
    @Volatile private var senderOn = false

    private fun startSender(st: Settings) {
        if (senderOn) return
        senderOn = true
        Thread({
            while (true) {
                val next = pending.entries.firstOrNull() ?: run { Thread.sleep(25); null } ?: continue
                pending.remove(next.key, next.value)
                if (!active || !ready(st)) continue
                val w = next.value
                if (sentKey[next.key] == w.key) continue
                if (w.gradient != null) Hue.setGradient(st.hueIp, st.hueUser, next.key, w.gradient, w.bri, st.hueSpeedMs)
                else w.cmd?.let { Hue.set(st.hueIp, st.hueUser, next.key, it) }
                sentKey[next.key] = w.key
                Thread.sleep(95)
            }
        }, "glass-hue-send").apply { isDaemon = true }.start()
    }

    /** From a picture on screen: every chosen light gets its colour (from its part of the screen, or the picture's palette). */
    fun showPicture(st: Settings, pic: Bitmap?, briPct: Int? = null) {
        if (pic == null || !ready(st) || !active) return
        // A picture in graphics memory can't be read pixel by pixel: a small readable copy.
        val b = runCatching { if (pic.config == Bitmap.Config.HARDWARE) pic.copy(Bitmap.Config.ARGB_8888, false) else pic }.getOrNull() ?: return
        val small = runCatching { Bitmap.createScaledBitmap(b, 64, 36, true) }.getOrNull() ?: return
        val ip = st.hueIp; val user = st.hueUser
        worker.execute { runCatching { work(st, small, ip, user, briPct) }.onFailure { Glog.w("Hue sync", it) } }
    }

    private fun work(st: Settings, small: Bitmap, ip: String, user: String, briPct: Int? = null) {
        run {
            val groups = groupCache ?: Hue.groups(ip, user).also { groupCache = it }
            val lights = lightCache ?: Hue.lights(ip, user).associateBy { it.id }.also { lightCache = it }
            val ids = targets(st, groups)
            if (ids.isEmpty()) return
            // Where each light stands, from the chosen entertainment areas.
            val places = groups.filter { it.id in st.hueGroups }.flatMap { it.places.entries }.associate { it.key to it.value }
            // Their own state first (once, until it is given back).
            if (saved == null) saved = Hue.states(ip, user, ids)
            startSender(st)
            val palette = runCatching { Hue.palette(small, 5) }.getOrNull() ?: return
            val zones = zones(st); val methods = methods(st)
            val tt = (st.hueSpeedMs / 100).coerceIn(0, 100)
            fun briOf(region: Bitmap?): Int {
                val lum = if (st.hueFollowBri && region != null) luminance(region) else 1f
                if (briPct != null) return (briPct / 100f * 254).toInt().coerceIn(1, 254)
                return ((st.hueBri / 100f) * (if (st.hueFollowBri) (0.3f + 0.7f * lum) else 1f) * 254).toInt().coerceIn(1, 254)
            }
            ids.forEachIndexed { i, id ->
                val l = lights[id] ?: Hue.Light(id, id, true)
                val zone = zones[id] ?: "auto"
                if (zone == "gradient" && l.gradient) {
                    // Along the strip behind the TV: up the left side, across the top, down the right.
                    val parts = listOf("left", "topleft", "top", "topright", "right").map { z -> Hue.crop(small, Hue.zoneRect(z)) }
                    val xs = parts.map { p -> Hue.clamp(Hue.xy(intensify(Hue.palette(p, 1)[0], st.hueIntensity)), l.gamut) }
                    val bri = briOf(small)
                    pending[id] = Want(null, xs, bri, xs.joinToString { "%.3f,%.3f".format(it.first, it.second) } + "|$bri")
                    return@forEachIndexed
                }
                val place = if (zone == "auto") places[id] else null
                val region = if (place != null) Hue.crop(small, Hue.placeRect(place)) else if (zone == "auto") null else Hue.crop(small, Hue.zoneRect(zone))
                val base = if (region != null) Hue.palette(region, 1)[0] else palette[if (st.hueSpread) i % palette.size else 0]
                val c = intensify(base, st.hueIntensity)
                val bri = briOf(region ?: small)
                // A light fades in a straight line through the colour chart: from red to blue it went through pink
                // and white first (a wrong colour for a moment at each new title). A big change is made quickly;
                // small ones (a video moving on) keep the chosen speed.
                val xyNow = Hue.xy(c)
                val far = lastXy[id]?.let { (it.first - xyNow.first).pow(2) + (it.second - xyNow.second).pow(2) > 0.012 } ?: true
                lastXy[id] = xyNow
                val cmd = JSONObject().put("on", true).put("bri", bri).put("transitiontime", if (far) minOf(tt, 1) else tt)
                val key: String
                when {
                    l.kind == "ct" -> { val m = Hue.mirek(c); cmd.put("ct", m); key = "ct$m|$bri" }
                    l.kind == "dim" -> key = "b$bri"
                    !l.philips && (methods[id] ?: "hs") == "hs" -> {
                        // Other makes: hue and saturation (their xy is often far off).
                        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
                        val h = (hsv[0] / 360f * 65535).toInt(); val sa = (hsv[1] * 254).toInt()
                        cmd.put("hue", h).put("sat", sa); key = "hs${h / 400},${sa / 4}|${bri / 4}"
                    }
                    else -> {
                        val (x, y) = Hue.clamp(Hue.xy(c), l.gamut)
                        cmd.put("xy", JSONArray().put(Math.round(x * 10000) / 10000.0).put(Math.round(y * 10000) / 10000.0))
                        key = "xy%.3f,%.3f|%d".format(x, y, bri / 4)
                    }
                }
                pending[id] = Want(cmd, null, bri, key)
            }
        }
    }

    private fun luminance(b: Bitmap): Float = runCatching {
        val s = Bitmap.createScaledBitmap(b, 8, 8, true)
        var sum = 0f
        for (y in 0 until 8) for (x in 0 until 8) { val p = s.getPixel(x, y); sum += (0.3f * Color.red(p) + 0.59f * Color.green(p) + 0.11f * Color.blue(p)) / 255f }
        (sum / 64f * 1.6f).coerceIn(0f, 1f)
    }.getOrDefault(1f)

    /**
     * Glassio left (an app or a film opened, or the TV turned off): the lights go back as they were, or are
     * dimmed, turned off or left, as chosen ([how]: "restore", "dim", "off", "keep").
     */
    fun leave(st: Settings, how: String) {
        active = false
        pending.clear(); sentKey.clear(); lastXy.clear()
        // Left as they are: their own state is still kept (the TV turning off later can still give it back).
        if (how == "keep") return
        val s = saved ?: return
        saved = null
        if (!ready(st)) return
        val ip = st.hueIp; val user = st.hueUser
        worker.execute {
            s.forEach { (id, state) ->
                val cmd = when (how) {
                    "off" -> JSONObject().put("on", false).put("transitiontime", 8)
                    "dim" -> Hue.restoreCommand(state, 8).put("on", state.optBoolean("on", true)).also { if (state.optBoolean("on", true)) it.put("bri", (254 * 0.2).toInt()) }
                    else -> Hue.restoreCommand(state, 8)
                }
                Hue.set(ip, user, id, cmd)
            }
        }
    }

    /**
     * The selected app in the dock or poster in the Library (Settings → Hue lights): every light takes its glow's
     * colour while it stays selected; a moment after the selection leaves them, the screen's colours come back.
     */
    @Volatile var focusOn = false
    private var focusToken = 0
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    fun focusColor(st: Settings, argb: Int, briPct: Int) {
        if (!ready(st) || !active) return
        focusToken++; focusOn = true
        showPicture(st, Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888).apply { eraseColor(argb) }, briPct)
    }
    fun focusLeft() {
        val t = focusToken
        main.postDelayed({ if (t == focusToken) focusOn = false }, 350)
    }

    /** Glassio is back on screen: the lights' state is read again before the next colours (you may have changed it). */
    fun resume() { active = true; sentKey.clear(); lastXy.clear() }
}
