package com.glasslauncher.tv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Pictures that failed to download are asked for again ([rev] goes up) when the network comes back, or when the
 * home screen shows again after one failed. Without it a row's card, the carousel's backdrop or a logo that failed
 * once (the TV waking before its network was up, a site that didn't answer) stayed grey for as long as the page was
 * up: the Library looked fine (it loads its posters each time it opens), the home screen didn't (his photo).
 */
object NetState {
    /** Read by the pictures that can fail: a change means "try again". */
    val rev = androidx.compose.runtime.mutableIntStateOf(0)
    @Volatile private var failedSince = 0L
    /** QA (dev key, --ei net_fail_test <seconds>): picture downloads fail until then. */
    @Volatile var failUntil = 0L
    @Volatile private var lastBump = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A picture failed: the next [retryIfFailed] (or the network coming back) asks again. */
    fun failed() { if (failedSince == 0L) failedSince = System.currentTimeMillis() }

    /** Asks again if something failed since the last time (the home screen shows again). */
    fun retryIfFailed(why: String) {
        if (failedSince == 0L) return
        bump(why)
    }

    private fun bump(why: String) {
        val now = System.currentTimeMillis()
        if (now - lastBump < 3000) return
        lastBump = now; failedSince = 0L
        Glog.i("Pictures that failed: trying again ($why)")
        main.post { rev.intValue++ }
    }

    /** Watches the network: back (validated) after it was lost or not working, the failed pictures are asked again. */
    fun watch(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                @Volatile var up = true
                override fun onLost(network: Network) { up = false }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    val ok = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    if (ok && !up) { up = true; if (failedSince != 0L) bump("the network is back") }
                    else if (!ok) up = false
                }
            })
        }.onFailure { Glog.w("Watching the network", it) }
    }
}
