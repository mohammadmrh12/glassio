package com.glasslauncher.tv.data

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Environment
import android.os.StatFs
import java.net.Inet4Address

/**
 * The TV's numbers for the menu and the screensaver: IP, CPU, GPU, RAM and storage.
 *
 * RAM, storage and IP come from Android itself. Apps may not read the CPU counters (/proc/stat) since Android 8,
 * so CPU and GPU are read through the TV's own debugging connection (the same one Continue Watching uses);
 * without it those two stay empty.
 */
object SystemInfo {
    data class Snap(
        val ip: String = "", val cpu: Int = -1, val gpu: String = "",
        val ramUsed: Long = 0, val ramTotal: Long = 0, val storeUsed: Long = 0, val storeTotal: Long = 0, val tempC: Int = -1,
    )

    private var last: LongArray? = null
    /** The last numbers read: the menu shows them at once while new ones are read. */
    @Volatile var latest: Snap? = null

    /** Blocking (about half a second when CPU is read); call off the main thread. */
    fun read(ctx: Context, cpuAndGpu: Boolean = true): Snap {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val st = runCatching { StatFs(Environment.getDataDirectory().path) }.getOrNull()
        val storeTotal = st?.totalBytes ?: 0L
        val storeUsed = storeTotal - (st?.availableBytes ?: 0L)
        val ip = runCatching {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }?.hostAddress
        }.getOrNull().orEmpty()
        var cpu = -1; var gpu = ""; var temp = -1
        if (cpuAndGpu) runCatching {
            val out = LocalShell.run(ctx, "head -1 /proc/stat; sleep 0.5; head -1 /proc/stat; " +
                "cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null; echo '#'; cat /sys/class/misc/mali0/device/utilization 2>/dev/null; echo '#'; " +
                "dumpsys gpu 2>/dev/null | grep -m1 'Global total'; echo '#'; cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null; true").getOrThrow()
            val lines = out.lines()
            val stats = lines.filter { it.startsWith("cpu ") }.map { l -> l.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() } }
            if (stats.size >= 2) {
                fun idle(v: List<Long>) = v.getOrElse(3) { 0 } + v.getOrElse(4) { 0 }
                val t = stats[1].sum() - stats[0].sum(); val i = idle(stats[1]) - idle(stats[0])
                if (t > 0) cpu = (100 * (t - i) / t).toInt().coerceIn(0, 100)
            }
            val parts = out.substringAfter(lines.lastOrNull { it.startsWith("cpu ") } ?: "").split('#').map { it.trim() }
            val busy = parts.getOrNull(0)?.filter { it.isDigit() }?.toIntOrNull() ?: parts.getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()
            gpu = when {
                busy != null -> "$busy%"
                else -> Regex("Global total: (\\d+)").find(parts.getOrNull(2).orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { gb(it) + " used" }.orEmpty()
            }
            temp = parts.getOrNull(3)?.trim()?.toIntOrNull()?.let { if (it > 1000) it / 1000 else it } ?: -1
        }
        val prev = latest
        if (!cpuAndGpu && prev != null) { cpu = prev.cpu; gpu = prev.gpu; temp = prev.tempC }
        return Snap(ip, cpu, gpu, mi.totalMem - mi.availMem, mi.totalMem, storeUsed, storeTotal, temp).also { latest = it }
    }

    fun gb(b: Long): String = if (b >= 1L shl 30) String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0) else "${b / 1048576} MB"
}
