package com.glasslauncher.tv.data

import java.io.File

/**
 * Glassio's caches (screensaver videos, pictures) never fill the TV: whatever size is chosen in Settings, they
 * leave at least [RESERVE] free. A full TV gets slow, can't update apps and can fail in odd ways.
 */
object Storage {
    const val RESERVE = 3L * 1024 * 1024 * 1024

    /** Free space where [dir] lives. */
    fun free(dir: File): Long = runCatching { (if (dir.exists()) dir else dir.parentFile ?: dir).usableSpace }.getOrDefault(Long.MAX_VALUE)

    /** Bytes in [dir] (one level, as the picture cache keeps its files). */
    fun used(dir: File): Long = runCatching { dir.listFiles()?.sumOf { if (it.isFile) it.length() else used(it) } ?: 0L }.getOrDefault(0L)

    /**
     * The picture cache's size for this run: the chosen size, or less when the TV is short of space (what it
     * already holds counts as available, since it can be reused). Never under 256 MB.
     * Called from Coil's disk cache set-up, which runs on a background thread the first time it's needed.
     */
    fun pictureCacheLimit(dir: File, chosen: Long): Long {
        val spare = used(dir) + free(dir) - RESERVE
        val limit = minOf(chosen, spare).coerceAtLeast(256L * 1024 * 1024)
        if (limit < chosen) Glog.w("Picture cache limited to ${limit / 1048576} MB: the TV is short of space")
        return limit
    }
}
