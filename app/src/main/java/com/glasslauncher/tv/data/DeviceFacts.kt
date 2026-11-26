package com.glasslauncher.tv.data

import android.content.Context

/**
 * The TV's own facts for a problem report, nothing personal: memory, storage, what its video decoders can do, which app
 * is its home and its screensaver, whether Glassio's Home button service and TV access work, the other Glassio build,
 * where Glassio came from. The reports of #40 (a green screensaver) and #46 (two home screens taking turns) needed
 * exactly these and had none of them.
 */
object DeviceFacts {
    private fun gb(b: Long) = "%.1f GB".format(java.util.Locale.US, b / 1_073_741_824.0)

    fun line(ctx: Context): String = runCatching {
        val pm = ctx.packageManager
        val am = ctx.getSystemService(android.app.ActivityManager::class.java)
        val mi = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val pss = android.os.Debug.getPss() / 1024
        val free = runCatching { android.os.StatFs(ctx.filesDir.path).availableBytes }.getOrDefault(0L)
        val home = runCatching { pm.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull() ?: "?"
        val cr = ctx.contentResolver
        val saverOn = runCatching { android.provider.Settings.Secure.getInt(cr, "screensaver_enabled", 1) == 1 }.getOrDefault(false)
        val saver = if (!saverOn) "off" else runCatching { android.provider.Settings.Secure.getString(cr, "screensaver_components") }.getOrNull().orEmpty().substringBefore(',').substringBefore('/').ifBlank { "none" }
        val other = listOf("com.glasslauncher.tv", "com.glasslauncher.tv.play").filter { it != ctx.packageName && NuvioLaunch.installed(ctx, it) }
        val from = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(ctx.packageName).installingPackageName
            else @Suppress("DEPRECATION") pm.getInstallerPackageName(ctx.packageName)
        }.getOrNull() ?: "sideloaded"
        listOf(
            "ram ${gb(mi.totalMem)} (free ${gb(mi.availMem)}, Glassio ${pss} MB)",
            "storage free ${gb(free)}",
            "video ${decoders()}",
            "home app $home",
            "TV screensaver $saver",
            "Home button service " + (if (com.glasslauncher.tv.HomeKeyService.instance != null) "on" else "off"),
            "TV access " + (if (LocalShell.worked(ctx)) "works" else "not set"),
            "other Glassio " + (other.joinToString().ifBlank { "none" }),
            "from $from",
        ).joinToString(" · ")
    }.getOrElse { "(not read: ${it.javaClass.simpleName})" }

    /** The hardware video decoders: for H.264 and HEVC how many videos at once and whether 4K. */
    private fun decoders(): String {
        val list = runCatching { android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull() ?: return "?"
        return listOf("video/avc" to "H.264", "video/hevc" to "HEVC").joinToString(", ") { (mime, name) ->
            val hw = list.filter { !it.isEncoder && it.supportedTypes.any { t -> t.equals(mime, true) } &&
                (android.os.Build.VERSION.SDK_INT < 29 || it.isHardwareAccelerated) }
            if (hw.isEmpty()) "$name none" else {
                val caps = hw.mapNotNull { runCatching { it.getCapabilitiesForType(mime) }.getOrNull() }
                val most = caps.maxOfOrNull { it.maxSupportedInstances } ?: 0
                val k4 = caps.any { c -> runCatching { c.videoCapabilities?.isSizeSupported(3840, 2160) }.getOrNull() == true }
                "$name ×$most" + if (k4) " 4K" else ""
            }
        }
    }
}
