package com.glasslauncher.tv.data

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Runs a shell command through the TV's own adb over localhost (like Nuvio TV Relay does).
 * The first time, the TV asks "Allow USB debugging?" for Glassio's key: choose "Always allow".
 * Used for things only the shell may do, such as starting the TV's screensaver on demand.
 */
object LocalShell {
    private fun keyPair(ctx: Context): AdbKeyPair {
        val dir = File(ctx.filesDir, "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private var conn: Dadb? = null

    /**
     * Copies an APK to the TV and installs it from the shell, detached, so the install survives this app
     * being stopped for the update; Glass is opened again afterwards.
     */
    /** The TV's installer said no (the reason as pm gives it, e.g. INSTALL_FAILED_UPDATE_INCOMPATIBLE). */
    class InstallRefused(val reason: String) : Exception(reason)

    /**
     * Copies an APK to the TV and installs it from the shell. The install runs detached (it survives this app
     * being stopped for the update) and writes its answer to a file that is read back: a refusal is reported
     * ([InstallRefused]) instead of the update hanging on "Installing…". The connection and the copy have a time
     * limit: a TV still asking "Allow USB debugging?" (or one that was refused) no longer waits for ever.
     */
    fun install(ctx: Context, apk: File, port: Int = 5555): Result<Unit> {
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val remote = "/data/local/tmp/glass-update.apk"
        val log = "/data/local/tmp/glass-update.log"
        try {
            // Connect and copy (a 12 MB copy takes a few seconds).
            val d = runCatching {
                pool.submit<Dadb> {
                    synchronized(this) {
                        val d = conn ?: Dadb.create("127.0.0.1", port, keyPair(ctx)).also { conn = it }
                        d.push(apk, remote)
                        val cmd = "rm -f $log; nohup sh -c 'pm install -r -d $remote > $log 2>&1; rm -f $remote; " +
                            "grep -q Success $log && am start -n ${ctx.packageName}/.MainActivity' >/dev/null 2>&1 &"
                        val r = d.shell(cmd)
                        check(r.exitCode == 0) { r.allOutput.trim().ifBlank { "exit ${r.exitCode}" } }
                        d
                    }
                }.get(45, java.util.concurrent.TimeUnit.SECONDS)
            }.getOrElse { e ->
                Glog.w("Update over the TV's adb: ${e.javaClass.simpleName} ${e.message}")
                runCatching { conn?.close() }; conn = null
                return Result.failure(e)
            }
            // The installer's answer (on success this app is stopped and opened again before long).
            val until = android.os.SystemClock.uptimeMillis() + 120_000
            while (android.os.SystemClock.uptimeMillis() < until) {
                Thread.sleep(1500)
                val out = runCatching { pool.submit<String> { d.shell("cat $log 2>/dev/null").allOutput }.get(10, java.util.concurrent.TimeUnit.SECONDS) }.getOrNull() ?: continue
                if (out.contains("Success")) return Result.success(Unit)
                val bad = Regex("""(Failure|Error)[^\n]*""").find(out)?.value
                if (bad != null) { Glog.w("Update refused: $bad"); return Result.failure(InstallRefused(bad.trim())) }
            }
            return Result.failure(java.util.concurrent.TimeoutException("no answer from the installer"))
        } finally { pool.shutdownNow() }
    }

    /** Blocking; call off the main thread. Keeps one connection open and reconnects if it drops. */
    @Synchronized
    fun run(ctx: Context, command: String, port: Int = 5555): Result<String> {
        repeat(2) { attempt ->
            val r = runCatching {
                val d = conn ?: Dadb.create("127.0.0.1", port, keyPair(ctx)).also { conn = it }
                val res = d.shell(command)
                check(res.exitCode == 0) { res.allOutput.trim().ifBlank { "exit ${res.exitCode}" } }
                res.allOutput
            }
            if (r.isSuccess) return r
            if (attempt == 1) { Glog.w("TV adb shell", r.exceptionOrNull()); return r }
            runCatching { conn?.close() }; conn = null
        }
        return Result.failure(IllegalStateException())
    }

    // A second connection for quick commands (switching the TV's input): it never waits behind a long command on
    // the main one, and it is opened ahead ([warm]), so the switch goes out at once.
    private var quick: Dadb? = null
    private val quickLock = Any()

    /** Opens the quick connection now (off the main thread), so the next [quick] needs no handshake. */
    fun warm(ctx: Context, port: Int = 5555) {
        Thread {
            synchronized(quickLock) {
                if (quick == null) runCatching { Dadb.create("127.0.0.1", port, keyPair(ctx)).also { it.shell("true") } }
                    .onSuccess { quick = it }.onFailure { Glog.w("TV adb (quick)", it) }
            }
        }.apply { isDaemon = true }.start()
    }

    /** Runs [command] on the quick connection, on its own thread (nothing waits for it). */
    fun quick(ctx: Context, command: String, port: Int = 5555) {
        Thread {
            synchronized(quickLock) {
                val t0 = android.os.SystemClock.uptimeMillis()
                repeat(2) { attempt ->
                    var tc = t0
                    val r = runCatching {
                        val d = quick ?: Dadb.create("127.0.0.1", port, keyPair(ctx)).also { quick = it }
                        tc = android.os.SystemClock.uptimeMillis()
                        d.shell(command)
                    }
                    if (r.isSuccess) {
                        val now = android.os.SystemClock.uptimeMillis()
                        Glog.i("TV shell: $command (connection ${tc - t0} ms, command ${now - tc} ms)")
                        return@synchronized
                    }
                    runCatching { quick?.close() }; quick = null
                    if (attempt == 1) Glog.w("TV adb (quick): $command", r.exceptionOrNull())
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
