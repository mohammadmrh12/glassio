package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The TV's paired Bluetooth devices, for the menu: listed, connected and disconnected through the TV's own shell
 * (TV access, ADB) — an app itself may only open the settings.
 */
object Bluetooth {
    data class Dev(val address: String, val name: String, val connected: Boolean, val battery: Int, val kind: Int) {
        /** The TV's remote: never disconnected from here (nothing would be left to reconnect it with). */
        val remote get() = name.contains("remote", ignoreCase = true)
    }
    val list = MutableStateFlow<List<Dev>>(emptyList())
    /** Why the list can't be shown (no TV access…), or null. */
    val problem = MutableStateFlow<String?>(null)
    /** The device being connected or disconnected. */
    val busy = MutableStateFlow<String?>(null)

    private fun shell(ctx: Context, args: String): Result<String> =
        LocalShell.run(ctx, "timeout 12 env CLASSPATH='${ctx.applicationInfo.sourceDir}' app_process /system/bin com.glasslauncher.tv.bt.BtShell $args")

    /** Blocking. */
    fun refresh(ctx: Context) {
        shell(ctx, "list").onSuccess { out ->
            val devs = out.lines().mapNotNull { l ->
                val p = l.trim().split('|')
                if (p.size < 5) null else Dev(p[0], p[1].ifBlank { p[0] }, p[2] == "true", p[3].toIntOrNull() ?: -1, p[4].toIntOrNull() ?: 0)
            }.sortedWith(compareByDescending<Dev> { it.connected }.thenBy { it.name.lowercase() })
            list.value = devs
            problem.value = if (out.contains("ERR")) "Bluetooth isn't on" else null
        }.onFailure { problem.value = "Needs TV access (ADB): Glassio settings → System" }
    }

    /** Blocking: connects or disconnects, then waits (up to 8 s) for the TV to say it did. */
    fun toggle(ctx: Context, d: Dev) {
        busy.value = d.address
        try {
            shell(ctx, (if (d.connected) "disconnect " else "connect ") + d.address)
            repeat(8) {
                Thread.sleep(1000)
                refresh(ctx)
                if (list.value.firstOrNull { it.address == d.address }?.connected != d.connected) return
            }
        } finally { busy.value = null }
    }
}
