package com.glasslauncher.tv.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** A newer release published on GitHub. */
data class Release(val version: String, val notes: String, val apkUrl: String, val pageUrl: String, val size: Long)

/**
 * Updates from GitHub Releases (github.com/mohammadmrh12/glassio).
 *
 * Checks the latest release a few seconds after start and then every 12 hours. Installing downloads the
 * APK and hands it to the TV's own adb shell (the same one used for the TV screensaver), which updates
 * silently and reopens Glass; if that is not allowed it falls back to Android's package installer, which
 * asks for confirmation.
 */
class Updater(private val ctx: Context, private val scope: CoroutineScope) {
    companion object {
        const val REPO = "mohammadmrh12/glassio"
        const val REPO_URL = "https://github.com/$REPO"
        private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    }

    val current: String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"
    val available = MutableStateFlow<Release?>(null)
    /** "" idle, otherwise a short status ("Downloading 42%", "Installing…", an error). */
    val status = MutableStateFlow("")
    val lastCheck = MutableStateFlow(0L)

    fun start() {
        scope.launch(Dispatchers.IO) {
            delay(8_000)
            while (true) { check(); delay(12 * 3600_000L) }
        }
    }

    /** Returns a message for the Settings toast. */
    fun check(): String = try {
        val c = URL(API).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 12000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "GlassLauncher/$current")
        val code = c.responseCode
        if (code == 404) { c.disconnect(); "No release published yet" }
        else {
            check(code == 200) { "GitHub answered HTTP $code" }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() }); c.disconnect()
            lastCheck.value = System.currentTimeMillis()
            val tag = j.optString("tag_name").removePrefix("v")
            val assets = j.optJSONArray("assets")
            var apk = ""; var size = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) { apk = a.optString("browser_download_url"); size = a.optLong("size"); break }
            }
            if (apk.isNotBlank() && newer(tag, current)) {
                available.value = Release(tag, j.optString("body"), apk, j.optString("html_url"), size)
                "Version $tag is available"
            } else { available.value = null; "Glassio is up to date ($current)" }
        }
    } catch (e: Exception) { "Could not check: ${e.message}" }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val y = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    fun install() {
        val r = available.value ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val f = download(r)
                status.value = "Installing…"
                // 1) The TV's adb shell: silent, then Glass opens again by itself.
                val viaShell = LocalShell.install(ctx, f)
                if (viaShell.isSuccess) { status.value = "Installed, restarting…"; return@launch }
                // The TV's installer refused it: no point asking Android's installer, it says the same.
                (viaShell.exceptionOrNull() as? LocalShell.InstallRefused)?.let { status.value = refusal(it.reason); return@launch }
                // 2) Android's installer (asks to confirm; needs "Install unknown apps" for Glass).
                installWithPackageInstaller(f)
            } catch (e: Exception) {
                status.value = "Update failed: ${e.message}"
            }
        }
    }

    /** What to do about an install the TV refused, in words. */
    private fun refusal(reason: String): String = when {
        reason.contains("UPDATE_INCOMPATIBLE") || reason.contains("SIGNATURE") ->
            "Can't update this copy: it was signed differently. Back it up (Settings → System), uninstall Glassio and install it from GitHub"
        reason.contains("INSUFFICIENT_STORAGE") -> "Not enough space on the TV for the update"
        reason.contains("VERSION_DOWNGRADE") -> "A newer Glassio is already installed"
        else -> "Update failed: $reason"
    }

    private fun download(r: Release): File {
        val out = File(ctx.cacheDir, "update.apk")
        var url = URL(r.apkUrl)
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10000; c.readTimeout = 30000
            c.setRequestProperty("User-Agent", "GlassLauncher/$current")
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            check(code == 200) { "download HTTP $code" }
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
        c.inputStream.use { inp ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L; var n: Int
                while (inp.read(buf).also { n = it } > 0) {
                    o.write(buf, 0, n); done += n
                    if (total > 0) status.value = "Downloading ${(done * 100 / total).toInt()}%"
                }
            }
        }
        c.disconnect()
        return out
    }

    private fun installWithPackageInstaller(apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("glass.apk", 0, apk.length()).use { o -> apk.inputStream().use { it.copyTo(o) }; s.fsync(o) }
            val intent = Intent(ctx, InstallResultReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, 7, intent, flags).intentSender)
        }
        status.value = "Confirm the update on screen"
    }
    // (The installer's own failures come to InstallResultReceiver, which shows them.)
}

/** Receives the package installer's answer; shows its confirmation screen when it needs one. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> android.widget.Toast.makeText(context, "Update not installed: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"), android.widget.Toast.LENGTH_LONG).show()
        }
    }
}
