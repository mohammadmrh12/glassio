package com.glasslauncher.tv.net

import com.glasslauncher.tv.GlassApp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the "Manage from phone" page running after you leave that screen, for as long as
 * Settings → Manage from phone → "Keep the page on" says (then it stops and the code changes).
 */
object PhoneHost {
    private var server: PhoneServer? = null
    private var url: String? = null
    private var stopJob: Job? = null

    /** Starts the page (or keeps the one already running). Returns its address with the one-time code. */
    @Synchronized fun start(app: GlassApp): String? {
        stopJob?.cancel(); stopJob = null
        url?.let { if (server != null) return it }
        val token = Net.token()
        val s = PhoneServer(app, token)
        val port = s.start() ?: return null
        val ip = Net.localIp() ?: run { s.stop(); return null }
        server = s; url = "http://$ip:$port/?t=$token"
        return url
    }

    @Synchronized fun leave(app: GlassApp) {
        stopJob?.cancel()
        val min = app.settings.phoneMinutes
        if (min <= 0) { stop(); return }
        stopJob = app.scope.launch { delay(min * 60_000L); stop() }
    }

    @Synchronized fun stop() { stopJob?.cancel(); stopJob = null; server?.stop(); server = null; url = null }
}
