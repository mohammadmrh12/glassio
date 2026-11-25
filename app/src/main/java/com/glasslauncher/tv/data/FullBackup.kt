package com.glasslauncher.tv.data

import android.content.Context
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * QA only (dev key): a whole copy of Glassio's own data, before a long test that goes through every setting: every
 * settings file (sign-ins, keys and codes included, also the ones a backup leaves out: TV access, the phone page's
 * code, the apps Glassio turned off) and every file of its own (custom icons, pictures, lists). Kept in its external
 * files folder (readable with adb) and put back as it was, then Glassio starts again.
 */
object FullBackup {
    private fun dataDir(ctx: Context) = File(ctx.applicationInfo.dataDir)

    private val VIDEO = setOf("mp4", "mkv", "webm", "mov", "m4v", "ts", "avi", "exo", "m3u8")
    private fun isCache(f: File) = f.path.split('/').any { it.contains("cache", true) }
    private fun isVideo(f: File) = f.extension.lowercase() in VIDEO || f.length() > 20L * 1024 * 1024

    /** Writes the copy and returns its file. */
    fun save(ctx: Context): File {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(java.util.Date())
        val out = File(ctx.getExternalFilesDir(null), "glassio-full-$stamp.zip")
        val root = dataDir(ctx)
        // (What each folder of files holds, in the log; caches (pictures, videos, lists fetched again) are left out.)
        File(root, "files").listFiles().orEmpty().forEach { d ->
            val sz = d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            Glog.i("QA: files/${d.name}: ${sz / 1024} KB" + if (isCache(d)) " (left out)" else "")
        }
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (dir in listOf("shared_prefs", "files")) {
                // (No videos: your videos folder and the video caches are large and come back on their own; his ask.)
                File(root, dir).walkTopDown().filter { it.isFile && !isVideo(it) && !isCache(it) }.forEach { f ->
                    z.putNextEntry(ZipEntry(f.relativeTo(root).path))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
        }
        Glog.i("QA: full copy of Glassio's data: ${out.name}, ${out.length() / 1024} KB")
        return out
    }

    /** Puts [zip] back over Glassio's data (settings files and files replaced, nothing else touched), then restarts. */
    fun restore(ctx: Context, zip: File) {
        val root = dataDir(ctx)
        var n = 0
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val f = File(root, e.name)
                // (Only inside the two folders it was made from.)
                if (!f.canonicalPath.startsWith(File(root, "shared_prefs").canonicalPath) && !f.canonicalPath.startsWith(File(root, "files").canonicalPath)) continue
                f.parentFile?.mkdirs()
                f.outputStream().use { z.copyTo(it) }
                n++
            }
        }
        Glog.i("QA: full copy put back: $n files, restarting")
        Backup.restart(ctx)
    }
}
