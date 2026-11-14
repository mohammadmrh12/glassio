package com.glasslauncher.tv.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import com.glasslauncher.tv.GlassApp
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.launch

/**
 * Glassio on several TVs, kept the same (Settings → Sync). One TV is the main one; the others follow it over the home
 * network, live (every change on the main TV shows on them a moment later) or when you press Sync now.
 *
 * - The main TV runs a small server (ports 9110–9119) and announces itself on the network (mDNS, `_glassio._tcp`).
 *   A follower finds it (or scans the network if the router hides mDNS), and pairs once with a 4-digit code shown on
 *   the main TV. The code gives it the main TV's sync key; every request carries it.
 * - What syncs is chosen by groups (look, dock and icons, home screen…). Never synced: accounts and sign-ins (Nuvio and
 *   Trakt replace their tokens on use: two TVs sharing one would sign each other out), and what belongs to each TV
 *   (performance, remote buttons, HDMI inputs, cameras, Hue, Home Assistant, the lock code, TV access).
 * - Automatic: the follower keeps one request waiting on the main TV, which answers as soon as something changes.
 *   Settings are applied in place (every screen redraws with them, nothing restarts); icons you made are copied too.
 * - One way only, main → followers: a change made on a follower to a synced part is replaced by the main TV's next one.
 */
object PeerSync {
    const val SERVICE = "_glassio._tcp."
    private const val PORT0 = 9110

    /** What can be synced, in the order Settings shows it. */
    val GROUPS = linkedMapOf(
        "look" to "Look & theme", "dock" to "Dock & icons", "home" to "Home screen & carousel",
        "channels" to "Channels & music", "saver" to "Screensaver", "catalogs" to "Add-ons & catalogs", "lists" to "Library & likes",
    )
    /** Also syncable, but only when turned on (they fit TVs in the same home, with the same kind of remote). */
    val EXTRAS = linkedMapOf(
        "keys" to "Keys: TMDB, Unsplash, Home Assistant", "hue" to "Hue bridge", "cameras" to "Cameras & doorbell",
        "remote" to "Remote buttons", "lock" to "App lock & code", "perf" to "Performance",
    )
    private val DEFAULT_GROUPS = GROUPS.keys

    // ---- What belongs to each TV (never synced)
    private val NEVER = setOf(
        "interceptHome", "inputs", "inputPlayer", "setupDone", "tourSeen", "musicLastPkg", "bootStart", "recentsAdb",
        "nuvioToken", "nuvioTarget", "cameraDecoder", "phoneMinutes", "photoLast", "syncAll", "nuvioPush", "aerialUrls", "bgImage", "bgVideo",
    )
    // (Hue: the bridge and its key are shared on request; the lights and rooms each TV drives stay its own.)
    private val NEVER_PREFIX = listOf("hue", "sys", "saverCache", "settingsLast")
    private fun prefixed(k: String, p: String) = k.startsWith(p) && k.length > p.length && k[p.length].isUpperCase()
    /**
     * What weighs on the TV: the performance mode and everything it sets. Off unless turned on, so a weaker TV that
     * follows a stronger one stays smooth with its own choices.
     */
    private val PERF = setOf(
        "perfMode", "memTier", "pageScale", "glassRes", "imageQuality", "cacheMb", "prefetchDepth", "maxItems", "photoCacheMb",
        "refraction", "transitionMs", "slideAnim", "heroGallery", "galleryShots", "saverQuality",
    )
    private val LOOK = setOf(
        "theme", "glass", "glassColor", "glassColorAmount", "glassTint", "blurRadius", "refraction", "tileStyle", "tileBacking", "tileLift",
        "tileZoom", "dockStyle", "menuStyle", "panelStyle", "focusColor", "focusPlate", "glow", "glowSize", "cornerRadius", "squareCorners",
        "boldText", "settingsAccent", "settingsIcon", "settingsFrame", "logoWhite", "edgeBounce", "transitionMs", "slideAnim", "iconPack", "lang",
    )
    private val LISTS = setOf("library", "likes", "artPicks", "artChoice")

    /** The group a setting syncs with, or null for one that stays on each TV. */
    fun groupOf(k: String): String? = when {
        k in NEVER -> null
        k in PERF -> "perf"
        k == "tmdbToken" || k == "unsplashKey" || prefixed(k, "ha") -> "keys"
        k == "hueIp" || k == "hueUser" -> "hue"
        NEVER_PREFIX.any { prefixed(k, it) } -> null
        k == "cameras" || k == "cornerCamera" || prefixed(k, "camera") || prefixed(k, "multi") || prefixed(k, "doorbell") -> "cameras"
        k in setOf("keyMaps", "shuffleKey", "multiKey", "cornerKey", "homeLong", "homeRecents") -> "remote"
        k == "lockCode" || k == "lockedApps" || k.startsWith("lockHint") -> "lock"
        k in LOOK -> "look"
        k in LISTS -> "lists"
        k.startsWith("dock") || k in setOf("hiddenApps", "showFolder", "folderPos", "showMobileApps", "inputsDock", "musicDock") -> "dock"
        k.startsWith("channels") || k.startsWith("music") || k.startsWith("island") -> "channels"
        k.startsWith("saver") || k.startsWith("idle") || k == "useAppleAerials" -> "saver"
        k.startsWith("catalogs") || k == "perCatalog" -> "catalogs"
        else -> "home"
    }

    // ---- This TV's sync state (its own file: not in backups, not synced)
    private lateinit var app: GlassApp
    private val sp: SharedPreferences by lazy { app.getSharedPreferences("glass_peer", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())

    /** off | main | follow */
    var role: String
        get() = sp.getString("role", "off").orEmpty()
        set(v) { sp.edit().putString("role", v).apply(); rev.intValue++; restart() }
    /** auto | manual (a follower) */
    var mode: String
        get() = sp.getString("mode", "auto").orEmpty()
        set(v) { sp.edit().putString("mode", v).apply(); rev.intValue++; restart() }
    var name: String
        get() = sp.getString("name", "").orEmpty().ifBlank { Build.MODEL }
        set(v) { sp.edit().putString("name", v).apply(); rev.intValue++; if (role == "main") { unregister(); register() } }
    var groups: Set<String>
        get() = sp.getStringSet("groups", null) ?: DEFAULT_GROUPS
        set(v) {
            // Performance turned off: this TV goes back to its own performance mode (what the main TV sent is undone).
            val perfOff = "perf" in groups && "perf" !in v
            sp.edit().putStringSet("groups", v).remove("applied").apply(); rev.intValue++
            if (perfOff) main.post { app.settings.applyMode(app.settings.perfMode) }
            wake()
        }
    /** The main TV a follower follows (its name, where it was last found, the key it gave). */
    /** A follower: a short note on screen when a change from the main TV came. */
    var notify: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) { sp.edit().putBoolean("notify", v).apply(); rev.intValue++ }
    /**
     * The main TV: sharing paused (to try things out). The followers keep the state from the moment it was paused, and
     * get everything changed meanwhile when it goes on again.
     */
    var paused: Boolean
        get() = sp.getBoolean("paused", false)
        set(v) {
            frozen = if (v) snapshot() else null
            sp.edit().putBoolean("paused", v).putString("frozen", frozen?.toString()).apply(); rev.intValue++
            synchronized(waiters) { (waiters as Object).notifyAll() }
        }
    @Volatile private var frozen: JSONObject? = null
    val peerName: String get() = sp.getString("peerName", "").orEmpty()
    private val peerHost: String get() = sp.getString("peerHost", "").orEmpty()
    private val peerPort: Int get() = sp.getInt("peerPort", 0)
    private val peerKey: String get() = sp.getString("peerKey", "").orEmpty()
    val lastAt: Long get() = sp.getLong("lastAt", 0L)
    /** The main TV's own sync key (pairing hands it out; a new one drops every follower). */
    private val myKey: String get() = sp.getString("key", "").orEmpty().ifBlank { newKey() }
    private fun newKey(): String = (1..24).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("").also { sp.edit().putString("key", it).apply() }

    /** Redraws Settings → Sync as things change. */
    val rev = androidx.compose.runtime.mutableIntStateOf(0)
    /** What a follower says about its main TV now ("Up to date", "Changes waiting", "Can't reach…"). */
    val status = mutableStateOf("")
    /** A follower: the main TV has changes this TV hasn't taken (manual mode). */
    val waiting = mutableStateOf(false)

    // ---- Main TV: pairing and who follows
    @Volatile var pairCode: String = ""; private set
    @Volatile private var pairUntil = 0L
    /** Opens pairing for a few minutes and gives the code to show. */
    @Volatile private var pairMisses = 0
    fun openPairing(): String {
        pairMisses = 0
        pairCode = (1000..9999).random().toString(); pairUntil = System.currentTimeMillis() + 5 * 60_000L
        return pairCode
    }
    fun closePairing() { pairUntil = 0L }
    /** Followers seen lately: name → (address, last seen). */
    val followers = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
    fun dropFollowers() { newKey(); followers.clear(); rev.intValue++ }

    fun start(a: GlassApp) {
        app = a
        restart()
    }

    private var serverThread: Thread? = null
    private var server: ServerSocket? = null
    private var followThread: Thread? = null
    @Volatile private var gen = 0

    private fun restart() {
        gen++
        runCatching { server?.close() }; server = null
        unregister()
        followThread?.interrupt(); followThread = null
        status.value = ""; waiting.value = false
        when (role) {
            "main" -> startServer()
            "follow" -> if (peerKey.isNotBlank()) startFollow()
        }
    }

    // ───────────────────────────── main TV ─────────────────────────────

    private val waiters = Object()
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> synchronized(waiters) { (waiters as Object).notifyAll() } }

    private fun startServer() {
        val my = gen
        serverThread = Thread({
            val s = (PORT0..PORT0 + 9).firstNotNullOfOrNull { p -> runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(p), 16) } }.getOrNull() }
                ?: run { Glog.w("Sync: no free port", null); return@Thread }
            server = s
            app.getSharedPreferences("glass", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
            app.getSharedPreferences("glass_catalogs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefListener)
            register(s.localPort)
            while (gen == my && !s.isClosed) {
                val c = runCatching { s.accept() }.getOrNull() ?: break
                Thread({ runCatching { serve(c) }; runCatching { c.close() } }, "glass-sync-req").apply { isDaemon = true }.start()
            }
        }, "glass-sync-server").apply { isDaemon = true; start() }
    }

    private fun serve(c: Socket) {
        c.soTimeout = 10_000
        val inp = c.getInputStream().bufferedReader()
        val line = inp.readLine() ?: return
        while (true) { val h = inp.readLine() ?: break; if (h.isEmpty()) break }
        val target = line.split(' ').getOrNull(1).orEmpty()
        val path = target.substringBefore('?')
        val q = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val out = c.getOutputStream()
        fun send(code: Int, type: String, body: ByteArray) {
            out.write("HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body); out.flush()
        }
        fun json(code: Int, o: JSONObject) = send(code, "application/json", o.toString().toByteArray())
        if (role != "main") return json(404, JSONObject().put("error", "not a main TV"))
        when (path) {
            "/hello" -> return json(200, JSONObject().put("glassio", 1).put("name", name).put("model", Build.MODEL))
            "/pair" -> {
                val ok = System.currentTimeMillis() < pairUntil && q["code"] == pairCode
                if (!ok) {
                    // A few wrong codes close pairing (no guessing all 10,000): Add a TV gives a new code.
                    if (System.currentTimeMillis() < pairUntil && ++pairMisses >= 5) { pairUntil = 0L; main.post { rev.intValue++ } }
                    return json(403, JSONObject().put("error", "Wrong or expired code"))
                }
                pairUntil = 0L
                seen(q["name"], c); main.post { rev.intValue++ }
                return json(200, JSONObject().put("key", myKey).put("name", name))
            }
        }
        if (q["k"] != myKey) return json(403, JSONObject().put("error", "Not paired with this TV"))
        seen(q["name"], c)
        when (path) {
            "/hash" -> json(200, JSONObject().put("h", hashOf(shared())))
            "/wait" -> {
                // Answers as soon as something synced changes (or after 50 s, with the same state).
                val since = q["h"].orEmpty(); val end = System.currentTimeMillis() + 50_000
                var h = hashOf(shared())
                while (h == since && System.currentTimeMillis() < end && role == "main") {
                    synchronized(waiters) { (waiters as Object).wait(1500) }
                    h = hashOf(shared())
                }
                json(200, JSONObject().put("h", h))
            }
            "/state" -> { val s = shared(); json(200, JSONObject(s.toString()).put("h", hashOf(s))) }
            "/icon" -> {
                val f = app.icons.fileFor(q["pkg"].orEmpty())
                if (f.isFile) send(200, "image/png", f.readBytes()) else json(404, JSONObject())
            }
            else -> json(404, JSONObject())
        }
    }

    private fun seen(n: String?, c: Socket) {
        val who = n?.takeIf { it.isNotBlank() } ?: c.inetAddress.hostAddress.orEmpty()
        val before = followers[who]
        followers[who] = (c.inetAddress.hostAddress.orEmpty()) to System.currentTimeMillis()
        if (before == null) main.post { rev.intValue++ }
    }

    /** What the followers get: the state now, or the one from when sharing was paused. */
    private fun shared(): JSONObject {
        if (!paused) return snapshot()
        frozen?.let { return it }
        return (sp.getString("frozen", null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: snapshot()).also { frozen = it }
    }

    /** Everything that can sync, as the main TV has it now. */
    private fun snapshot(): JSONObject {
        val glass = JSONObject()
        app.getSharedPreferences("glass", Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) -> if (groupOf(k) != null) enc(v)?.let { glass.put(k, it) } }
        val cat = JSONObject()
        app.getSharedPreferences("glass_catalogs", Context.MODE_PRIVATE).all.toSortedMap().forEach { (k, v) ->
            // (The account's own add-ons come from each TV's own sign-in.)
            if (k != "account_addons") enc(v)?.let { cat.put(k, it) }
        }
        val icons = JSONObject()
        iconDir().listFiles().orEmpty().sortedBy { it.name }.forEach { f -> if (f.name.endsWith(".png")) icons.put(f.name.removeSuffix(".png"), "${f.length()}-${f.lastModified()}") }
        return JSONObject().put("glassio", 1).put("name", name).put("glass", glass).put("catalogs", cat).put("icons", icons)
    }

    private fun iconDir() = File(app.filesDir, "icons")

    private fun enc(v: Any?): JSONObject? = when (v) {
        is Boolean -> JSONObject().put("t", "b").put("v", v)
        is Int -> JSONObject().put("t", "i").put("v", v)
        is Long -> JSONObject().put("t", "l").put("v", v)
        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
        is String -> JSONObject().put("t", "s").put("v", v)
        is Set<*> -> JSONObject().put("t", "set").put("v", org.json.JSONArray(v.map { it.toString() }.sorted()))
        else -> null
    }

    private fun hashOf(s: JSONObject): String {
        val text = listOf("glass", "catalogs", "icons").joinToString("|") { s.optJSONObject(it)?.toString().orEmpty() }
        return java.security.MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }

    // mDNS: the main TV announces itself, so a follower finds it without typing an address.
    private var nsdReg: android.net.nsd.NsdManager.RegistrationListener? = null
    private fun register(port: Int = server?.localPort ?: 0) {
        if (port == 0) return
        runCatching {
            val nsd = app.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
            val info = android.net.nsd.NsdServiceInfo().apply { serviceName = "Glassio · $name"; serviceType = SERVICE; this.port = port }
            val l = object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: android.net.nsd.NsdServiceInfo) {}
                override fun onRegistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) { Glog.w("Sync: mDNS registration failed ($e)", null) }
                override fun onServiceUnregistered(i: android.net.nsd.NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l); nsdReg = l
        }.onFailure { Glog.w("Sync: mDNS", it) }
    }
    private fun unregister() {
        nsdReg?.let { l -> runCatching { (app.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager).unregisterService(l) } }
        nsdReg = null
        if (::app.isInitialized) runCatching {
            app.getSharedPreferences("glass", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
            app.getSharedPreferences("glass_catalogs", Context.MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefListener)
        }
    }

    // ───────────────────────────── finding a main TV ─────────────────────────────

    class Found(val name: String, val host: String, val port: Int)

    /** Blocking (a few seconds): the main TVs on the network, by mDNS and by a quick scan of the network as well. */
    fun discover(): List<Found> {
        val found = java.util.concurrent.ConcurrentHashMap<String, Found>()
        fun check(host: String, port: Int) {
            val o = runCatching { get("http://$host:$port/hello", 900) }.getOrNull() ?: return
            if (o.optInt("glassio") == 1) found["$host:$port"] = Found(o.optString("name").ifBlank { host }, host, port)
        }
        val nsd = app.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager
        val pool = java.util.concurrent.Executors.newFixedThreadPool(48)
        val dl = object : android.net.nsd.NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceLost(i: android.net.nsd.NsdServiceInfo) {}
            override fun onServiceFound(i: android.net.nsd.NsdServiceInfo) {
                @Suppress("DEPRECATION")
                runCatching { nsd.resolveService(i, object : android.net.nsd.NsdManager.ResolveListener {
                    override fun onResolveFailed(i: android.net.nsd.NsdServiceInfo, e: Int) {}
                    override fun onServiceResolved(r: android.net.nsd.NsdServiceInfo) {
                        val h = r.host?.hostAddress ?: return
                        pool.execute { check(h, r.port) }
                    }
                }) }
            }
        }
        runCatching { nsd.discoverServices(SERVICE, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, dl) }
        // The scan: every address of this TV's network on the first sync ports (some routers drop mDNS between Wi-Fi
        // and cable).
        val mine = myAddress()
        if (mine != null) {
            val base = mine.substringBeforeLast('.')
            for (n in 1..254) {
                val h = "$base.$n"; if (h == mine) continue
                pool.execute {
                    for (p in PORT0..PORT0 + 1) if (runCatching { Socket().use { s -> s.connect(InetSocketAddress(h, p), 350) } }.isSuccess) check(h, p)
                }
            }
        }
        Thread.sleep(4500)
        runCatching { nsd.stopServiceDiscovery(dl) }
        pool.shutdown(); runCatching { pool.awaitTermination(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        return found.values.distinctBy { it.host }.sortedBy { it.name.lowercase() }
    }

    private fun myAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .map { it.hostAddress.orEmpty() }.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    }.getOrNull()

    /** Blocking: pairs with [f] using the code shown on it. Null when it worked, else what went wrong. */
    fun pair(f: Found, code: String): String? {
        val o = runCatching { get("http://${f.host}:${f.port}/pair?code=${enc(code)}&name=${enc(name)}", 4000) }
            .getOrElse { return if (it.message?.contains("403") == true) "Wrong or expired code" else "Can't reach ${f.name}" }
        val key = o.optString("key").ifBlank { return o.optString("error").ifBlank { "Pairing failed" } }
        sp.edit().putString("peerName", o.optString("name").ifBlank { f.name }).putString("peerHost", f.host).putInt("peerPort", f.port)
            .putString("peerKey", key).remove("applied").remove("iconSigs").putString("role", "follow").apply()
        main.post { rev.intValue++; restart() }
        return null
    }

    fun unfollow() {
        sp.edit().remove("peerName").remove("peerHost").remove("peerPort").remove("peerKey").remove("applied").remove("iconSigs").remove("lastAt").putString("role", "off").apply()
        rev.intValue++; restart()
    }

    // ───────────────────────────── follower ─────────────────────────────

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun base() = "http://$peerHost:$peerPort"
    private fun auth() = "k=${enc(peerKey)}&name=${enc(name)}"

    private fun startFollow() {
        val my = gen
        followThread = Thread({
            var fails = 0
            while (gen == my && role == "follow") {
                try {
                    woken = false
                    if (mode == "auto") {
                        // Waits on the main TV until something changes there.
                        val h = get("${base()}/wait?${auth()}&h=${enc(sp.getString("applied", "").orEmpty())}", 60_000).optString("h")
                        if (gen != my) break
                        if (h.isNotBlank() && h != sp.getString("applied", "")) pull(announce = true)
                        else setStatus("Up to date", false)
                    } else {
                        check()
                        Thread.sleep(10 * 60_000L)
                    }
                    fails = 0
                } catch (e: InterruptedException) {
                    if (gen != my) break
                } catch (e: Exception) {
                    if (gen != my) break
                    if (woken) { woken = false; continue }
                    fails++
                    setStatus(if (e.message?.contains("403") == true) "${peerName.ifBlank { "The main TV" }} no longer knows this TV: follow it again"
                        else "Can't reach ${peerName.ifBlank { "the main TV" }} (asleep or off the network)", false)
                    // The main TV's address may have changed (a new address from the router): found again by name.
                    if (fails % 3 == 0) runCatching { relocate() }
                    try { Thread.sleep(if (fails < 3) 15_000L else 60_000L) } catch (_: InterruptedException) { if (gen != my) break }
                }
            }
        }, "glass-sync-follow").apply { isDaemon = true; start() }
    }

    /** Glassio came back to the screen: a follower checks at once (a missed change shows straight away). */
    fun resumed() { if (::app.isInitialized && role == "follow") wake() }
    // A waiting request isn't stopped by interrupt(): its connection is closed instead.
    @Volatile private var waitConn: java.net.HttpURLConnection? = null
    @Volatile private var woken = false
    private fun wake() { woken = true; followThread?.interrupt(); runCatching { waitConn?.disconnect() } }

    private fun relocate() {
        val f = discover().firstOrNull { it.name == peerName } ?: return
        if (f.host != peerHost || f.port != peerPort) sp.edit().putString("peerHost", f.host).putInt("peerPort", f.port).apply()
    }

    /** Blocking: is there anything new on the main TV (manual mode shows "Changes waiting")? */
    fun check() {
        val h = get("${base()}/hash?${auth()}", 5000).optString("h")
        val news = h.isNotBlank() && h != sp.getString("applied", "")
        setStatus(if (news) "Changes waiting on ${peerName.ifBlank { "the main TV" }}" else "Up to date", news)
    }

    /** Blocking: takes the main TV's state now. Null when it worked, else what went wrong. */
    fun syncNow(): String? = runCatching { pull(announce = false); null }.getOrElse {
        setStatus("Can't reach ${peerName.ifBlank { "the main TV" }}", false); it.message ?: "failed"
    }

    private fun setStatus(s: String, news: Boolean) = main.post { status.value = s; waiting.value = news; rev.intValue++ }

    @Synchronized private fun pull(announce: Boolean) {
        val s = get("${base()}/state?${auth()}", 15_000)
        val g = groups
        val n = apply(s, g)
        sp.edit().putString("applied", s.optString("h")).putLong("lastAt", System.currentTimeMillis()).apply()
        setStatus("Up to date", false)
        if (announce && n > 0 && notify) main.post {
            if (app.foreground()) android.widget.Toast.makeText(app, "Synced from ${peerName.ifBlank { "the main TV" }}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Applies the main TV's state for the chosen groups. Returns how many things changed. */
    private fun apply(s: JSONObject, g: Set<String>): Int {
        var changed = 0
        val glass = s.optJSONObject("glass") ?: JSONObject()
        val gsp = app.getSharedPreferences("glass", Context.MODE_PRIVATE)
        val now = gsp.all
        val put = HashMap<String, Any>(); val remove = HashSet<String>()
        for (k in glass.keys()) {
            val grp = groupOf(k) ?: continue
            if (grp !in g) continue
            val v = dec(glass.getJSONObject(k)) ?: continue
            if (now[k] != v) put[k] = v
        }
        // Back to the default here where the main TV has none (it was never changed there).
        // (Not for the opt-in parts: a key or camera this TV has stays when the main TV has none.)
        for ((k, _) in now) { val grp = groupOf(k) ?: continue; if (grp in g && grp !in EXTRAS && !glass.has(k)) remove += k }
        // A background picture or video is a file on the main TV: this TV keeps its own background then.
        (put["bgMode"] as? String)?.let { m -> if (m == "image" && app.settings.bgImage.isBlank() || m == "video" && app.settings.bgVideo.isBlank()) put.remove("bgMode") }
        if (put.isNotEmpty() || remove.isNotEmpty()) {
            changed += put.size + remove.size
            android.util.Log.i("PeerSync", "set ${put.keys.sorted()} reset ${remove.sorted()}")
            val latch = java.util.concurrent.CountDownLatch(1)
            main.post { try { app.settings.applyRemote(put, remove); com.glasslauncher.tv.ui.GlassTint.apply(app.settings); com.glasslauncher.tv.ui.TextLook.bold.value = app.settings.boldText } finally { latch.countDown() } }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        if ("catalogs" in g) changed += applyCatalogs(s.optJSONObject("catalogs") ?: JSONObject())
        if ("dock" in g) changed += applyIcons(s.optJSONObject("icons") ?: JSONObject())
        return changed
    }

    private fun applyCatalogs(cat: JSONObject): Int {
        val csp = app.getSharedPreferences("glass_catalogs", Context.MODE_PRIVATE)
        val now = csp.all
        val ed = csp.edit(); var n = 0
        for (k in cat.keys()) { val v = dec(cat.getJSONObject(k)) ?: continue; if (now[k] != v) { put(ed, k, v); n++ } }
        for (k in now.keys) if (k != "account_addons" && !cat.has(k)) { ed.remove(k); n++ }
        if (n == 0) return 0
        ed.commit()
        app.catalogs.reloadFromPrefs()
        app.scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.catalogs.refresh() } }
        return n
    }

    private fun applyIcons(icons: JSONObject): Int {
        val had = sp.getString("iconSigs", "").orEmpty().split('\n').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val sigs = HashMap<String, String>(); var n = 0
        for (pkg in icons.keys()) {
            if (!Regex("[A-Za-z0-9._]+").matches(pkg)) continue
            val sig = icons.getString(pkg)
            val f = app.icons.fileFor(pkg)
            if (had[pkg] == sig && f.isFile) { sigs[pkg] = sig; continue }
            // (Not taken now: tried again next time.)
            val bytes = runCatching { getBytes("${base()}/icon?${auth()}&pkg=${enc(pkg)}", 10_000) }.getOrNull()
            if (bytes == null || bytes.isEmpty()) { had[pkg]?.let { sigs[pkg] = it }; continue }
            f.writeBytes(bytes); sigs[pkg] = sig; n++
        }
        // Icons the main TV no longer has (one synced before, or this TV's own made the same way): back to the app's.
        for (pkg in had.keys) if (pkg !in sigs && !icons.has(pkg)) { app.icons.fileFor(pkg).delete(); n++ }
        sp.edit().putString("iconSigs", sigs.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
        if (n > 0) main.post { app.icons.rev.intValue++ }
        return n
    }

    private fun dec(e: JSONObject): Any? = when (e.optString("t")) {
        "b" -> e.optBoolean("v"); "i" -> e.optInt("v"); "l" -> e.optLong("v"); "f" -> e.optDouble("v").toFloat(); "s" -> e.optString("v")
        "set" -> e.optJSONArray("v")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet<String>()
        else -> null
    }

    private fun put(ed: SharedPreferences.Editor, k: String, v: Any) {
        @Suppress("UNCHECKED_CAST")
        when (v) { is Boolean -> ed.putBoolean(k, v); is Int -> ed.putInt(k, v); is Long -> ed.putLong(k, v); is Float -> ed.putFloat(k, v)
            is String -> ed.putString(k, v); is Set<*> -> ed.putStringSet(k, v as Set<String>) }
    }

    private fun open(u: String, timeout: Int): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = minOf(timeout, 4000); readTimeout = timeout
    }
    private fun getBytes(u: String, timeout: Int): ByteArray {
        val c = open(u, timeout)
        val waiting = "/wait?" in u
        if (waiting) waitConn = c
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { inp -> ByteArrayOutputStream().also { inp.copyTo(it) }.toByteArray() }
        } finally { if (waiting) waitConn = null; c.disconnect() }
    }
    private fun get(u: String, timeout: Int): JSONObject = JSONObject(String(getBytes(u, timeout)))
}
