package com.glasslauncher.tv.net

import android.content.Context
import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the "Manage from phone" page running after you leave that screen, for as long as
 * Settings → Manage from phone → "Keep the page on" says (then it stops and the code changes).
 * "Always" (`phoneAlways`) keeps it running whenever Glassio runs. The code is one per TV, kept (a new one only from
 * Settings → Manage from phone → New code), and the TV answers to a name on the home network ([LocalName]): the page
 * saved on the phone's home screen keeps working whatever the timer is set to and whatever IP the TV gets.
 */
object PhoneHost {
    private var server: PhoneServer? = null
    private var url: String? = null
    private var stopJob: Job? = null
    /** The code the running page answers to. */
    @Volatile private var token: String? = null

    /**
     * The code for "Always": kept in its own file (not `glass…`, so never in a backup, a sync or a report), made once.
     */
    private fun lastingToken(ctx: Context): String {
        val sp = ctx.getSharedPreferences("phone_access", Context.MODE_PRIVATE)
        return sp.getString("token", null)?.takeIf { it.isNotBlank() } ?: Net.token().also { sp.edit().putString("token", it).apply() }
    }

    /** Starts the page (or keeps the one already running). Returns its address with the code. */
    @Synchronized fun start(app: GlassApp): String? {
        stopJob?.cancel(); stopJob = null
        val want = lastingToken(app)
        if (server != null && token != want) stop()
        url?.let { if (server != null) return it }
        val t = want
        val s = PhoneServer(app, t)
        val port = s.start() ?: return null
        val ip = Net.localIp() ?: run { s.stop(); return null }
        server = s; token = t; url = "http://$ip:$port/?t=$t"; this.port = port
        runCatching { LocalName.start(app) }
        return url
    }

    @Volatile private var port = 0
    /** The address by name (it stays when the TV's IP changes), while the page runs. */
    fun byName(app: GlassApp): String? = token?.let { t -> if (port == 0) null else "http://${LocalName.host(app)}.local:$port/?t=$t" }

    @Synchronized fun leave(app: GlassApp) {
        stopJob?.cancel()
        if (app.settings.phoneAlways) return
        val min = app.settings.phoneMinutes
        if (min <= 0) { stop(); return }
        stopJob = app.scope.launch { delay(min * 60_000L); stop() }
    }

    /** At Glassio's start: the page with "Always" on. */
    fun ensure(app: GlassApp) { if (app.settings.phoneAlways) start(app) }

    /** A new code (Settings → Manage from phone → New code): every saved page and QR code stops working. */
    @Synchronized fun newCode(app: GlassApp) {
        app.getSharedPreferences("phone_access", Context.MODE_PRIVATE).edit().clear().apply()
        stop()
    }

    /** "Keep the page on" choices in minutes, -1 for Always (Settings → Manage from phone and the menu's page). */
    val KEEP = listOf(0, 10, 30, 60, 180, -1)
    fun keepNow(app: GlassApp) = if (app.settings.phoneAlways) -1 else app.settings.phoneMinutes
    fun keepLabel(v: Int) = when { v < 0 -> "Always"; v == 0 -> "Stops when you leave"; v < 60 -> "$v min"; else -> "${v / 60} h" }

    /** Sets "Keep the page on" ([KEEP]) and returns the page's address now (it changes when Always goes on or off). Blocking. */
    fun setKeep(app: GlassApp, v: Int): String? {
        val st = app.settings
        if (v < 0) st.phoneAlways = true
        else { st.phoneAlways = false; st.phoneMinutes = v }
        return start(app)
    }

    @Synchronized fun stop() { stopJob?.cancel(); stopJob = null; server?.stop(); server = null; url = null; token = null; port = 0; runCatching { LocalName.stop() } }
}
