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
        // (QA: a second key the TV has never allowed, while the file adb_testkey exists: --ez adb_testkey true.)
        val dir = File(ctx.filesDir, if (File(ctx.filesDir, "adb_testkey").exists()) "adb_test" else "adb").apply { mkdirs() }
        val priv = File(dir, "adbkey"); val pub = File(dir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    private var conn: Dadb? = null

    /**
     * The TV's "Allow USB debugging?" window is on screen (seen by [com.glasslauncher.tv.HomeKeyService]). Nothing of
     * Glassio may come over it: covered, it stays open behind and unanswered, and the TV's adb then never asks again
     * for any key, Glassio's or another app's, until the TV restarts (#25: seen on the Streamer at every start with
     * *Open Glassio when the TV starts*: the window came up, Glassio opened over it 9 s later).
     */
    @Volatile var promptUp = false
        get() = field && android.os.SystemClock.uptimeMillis() - promptSeenAt < 120_000
    @Volatile var promptSeenAt = 0L
    /** Glassio was to open itself (Open Glassio when the TV starts) while the window was up: it opens once it is answered. */
    @Volatile var openAfterPrompt = false
    @Volatile private var waitingFrom = 0L
    /** A connection is waiting for the TV's answer now (its window may be about to show). */
    fun waiting() = waitingFrom != 0L
    /** How long the connection now waiting has waited (0: none). */
    fun waitedMs() = waitingFrom.let { if (it == 0L) 0L else android.os.SystemClock.uptimeMillis() - it }
    /**
     * The TV's window closed and the connection waiting for it wasn't accepted (Cancel: the TV's adb keeps such a
     * connection waiting instead of closing it, so the answer never arrives on it). Set by HomeKeyService.
     */
    @Volatile var cancelled = false
    /** When a connection last waited for the TV's answer and got none (its window was up, or hidden behind an app). */
    @Volatile var askedAt = 0L
    /** The TV is (or was a moment ago) asking "Allow USB debugging?" for Glassio's key. */
    fun asking() = waiting() || (askedAt != 0L && android.os.SystemClock.uptimeMillis() - askedAt < 5 * 60_000L)

    /** The TV's window was answered (Allow or Cancel): Glassio opens now if it waited for that (Open Glassio when the TV starts). */
    private fun answered(ctx: Context) {
        askedAt = 0L; promptUp = false
        if (!openAfterPrompt) return
        openAfterPrompt = false
        Glog.i("The TV's window answered: opening Glassio")
        com.glasslauncher.tv.HomeKeyService.instance?.openAfterPrompt() ?: runCatching {
            ctx.startActivity(android.content.Intent(ctx, com.glasslauncher.tv.MainActivity::class.java).setAction(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }

    /**
     * One connection at a time: each connection left waiting puts its own "Allow USB debugging?" window in the TV's
     * queue (at a start the shell, the quick connection and the compile asked at once, each on two addresses), and
     * every Cancel brought the next one up.
     */
    private val openLock = Any()

    /** Where the TV's adb answered last time. */
    @Volatile private var goodHost: String? = null

    /** This TV's own addresses (its Wi-Fi / Ethernet), besides 127.0.0.1. */
    private fun ownAddresses(): List<String> = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>().mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())

    /**
     * A connection to the TV's own adb that has answered. 127.0.0.1 first, then the TV's own network address: on Fire
     * TV the adb at 127.0.0.1 never asked "Allow USB debugging?" (#14), so Glassio could never be allowed. Each try has
     * a time limit, so a connection still waiting for the TV's question moves on instead of hanging.
     */
    private fun open(ctx: Context, port: Int): Dadb = synchronized(openLock) {
        // Not again for a while after a try found no answer (it would only queue another window, or wait for nothing).
        if (android.os.SystemClock.uptimeMillis() < quietUntil) throw IllegalStateException("the TV's adb didn't answer a moment ago")
        // Nor while the TV's window for an earlier try is still unanswered (HomeKeyService sees it answered): each try
        // queued one more window, shown after the first was answered (seen after Cancel). The TV access page asks
        // again ([retryNow]).
        if (askedAt != 0L && com.glasslauncher.tv.HomeKeyService.instance != null) throw IllegalStateException("waiting for the TV's answer (Allow USB debugging)")
        if (declined) throw IllegalStateException("not allowed on the TV (Cancel)")
        // The Play build asks the TV only when the person asks (TV access page, setup) until access has worked once:
        // its users never chose TV access, and the TV's "Allow USB debugging?" window came up at every start.
        val person = android.os.SystemClock.uptimeMillis() - askedByPerson < 130_000
        if (Store.PLAY && !person && !everOk(ctx)) throw IllegalStateException("TV access isn't set up")
        var last: Throwable? = null
        for (h in (listOfNotNull(goodHost) + "127.0.0.1" + ownAddresses()).distinct()) {
            // An address that got no answer is asking the TV ("Allow USB debugging?"): another address would only
            // queue a second window, which came up after Cancel on the first. Except on Fire TV, whose adb at
            // 127.0.0.1 never asks (#14).
            if (declined || (last is java.util.concurrent.TimeoutException && !android.os.Build.MANUFACTURER.equals("Amazon", true))) break
            val d = runCatching { Dadb.create(h, port, keyPair(ctx)) }.getOrElse { last = it; continue }
            val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
            val t0 = android.os.SystemClock.uptimeMillis()
            waitingFrom = t0; cancelled = false
            // (In the TV's first minutes this service starts after the window is already up and never sees it.)
            val early = com.glasslauncher.tv.HomeKeyService.instance == null && android.os.SystemClock.elapsedRealtime() < 180_000
            val ok = runCatching {
                val f = pool.submit<Unit> { d.shell("true"); Unit }
                // 15 s, or as long as the TV's "Allow USB debugging?" window is up (2 minutes at most): the answer
                // (Allow or Cancel) then comes on this same connection.
                while (true) {
                    try { f.get(1, java.util.concurrent.TimeUnit.SECONDS); break } catch (e: java.util.concurrent.TimeoutException) {
                        if (cancelled) throw java.io.IOException("Cancel on the TV's window")
                        val waited = android.os.SystemClock.uptimeMillis() - t0
                        // (Just closed: Allow reaches this connection, or Cancel is decided (HomeKeyService), within 2 s.)
                        val now = android.os.SystemClock.uptimeMillis()
                        val closing = now - promptClosedAt < 4_000
                        // (Asked by the person: whichever connection's window is up, its answer is waited for.)
                        val person = now - askedByPerson < 130_000
                        if (waited >= 15_000 && !((promptUp || early || closing || person) && waited < 120_000)) throw e
                    }
                }
            }
            waitingFrom = 0L
            pool.shutdownNow()
            if (ok.isSuccess) { goodHost = h; quietFor = 0L; markOk(ctx); if (askedAt != 0L || openAfterPrompt) answered(ctx); return d }
            last = ok.exceptionOrNull()
            if (last is java.util.concurrent.TimeoutException) {
                askedAt = android.os.SystemClock.uptimeMillis()
                // Without the Home button service nothing sees the TV's window closing, so Cancel (or the window
                // dismissed) looked like "no answer" and the TV was asked again every 5, 10 … 30 minutes for ever,
                // two windows at a time (seen with the Play build, whose service waits for the person's agreement).
                // One unanswered window is enough: not asked again until TV access is opened.
                if (com.glasslauncher.tv.HomeKeyService.instance == null && !person) {
                    declined = true
                    Glog.i("TV adb: no answer and nothing to see the TV's window: not asked again until TV access is opened")
                }
            }
            // Refused after waiting (Cancel on the TV's window, not a closed port): not asked again until the TV
            // access page asks (each try brought the window up again).
            else if (android.os.SystemClock.uptimeMillis() - t0 > 2_000) {
                declined = true
                Glog.i("Glassio not allowed on the TV (Cancel): not asked again until TV access is opened")
                answered(ctx)
            }
            Glog.i("TV adb at $h:$port: ${last?.let { (it.cause ?: it).javaClass.simpleName + " " + (it.cause ?: it).message.orEmpty() }}")
            runCatching { d.close() }
        }
        quietFor = (if (quietFor == 0L) 5 * 60_000L else quietFor * 2).coerceAtMost(30 * 60_000L)
        quietUntil = android.os.SystemClock.uptimeMillis() + quietFor
        Glog.w("TV adb (not asked again for ${quietFor / 60_000} min)", last)
        throw last ?: IllegalStateException("the TV's adb didn't answer")
    }

    /**
     * Copies an APK to the TV and installs it from the shell, detached, so the install survives this app
     * being stopped for the update; Glass is opened again afterwards.
     */
    /**
     * Runs [command] on its own connection and hands each line of its output to [onLine] as it comes, until the
     * command ends or the connection drops. Blocking (its own thread).
     */
    fun stream(ctx: Context, command: String, port: Int = 5555, onLine: (String) -> Unit) {
        open(ctx, port).use { d ->
            d.openShell(command).use { sh ->
                val buf = StringBuilder()
                while (true) {
                    when (val p = sh.read()) {
                        is dadb.AdbShellPacket.StdOut -> {
                            buf.append(String(p.payload))
                            var i = buf.indexOf("\n")
                            while (i >= 0) { onLine(buf.substring(0, i).trimEnd('\r')); buf.delete(0, i + 1); i = buf.indexOf("\n") }
                        }
                        is dadb.AdbShellPacket.Exit -> return
                        else -> {}
                    }
                }
            }
        }
    }

    /** The TV's installer said no (the reason as pm gives it, e.g. INSTALL_FAILED_UPDATE_INCOMPATIBLE). */
    class InstallRefused(val reason: String) : Exception(reason)

    /**
     * Copies an APK to the TV and installs it from the shell. The install runs detached (it survives this app
     * being stopped for the update) and writes its answer to a file that is read back: a refusal is reported
     * ([InstallRefused]) instead of the update hanging on "Installing…". The connection and the copy have a time
     * limit: a TV still asking "Allow USB debugging?" (or one that was refused) no longer waits for ever.
     */
    fun install(ctx: Context, apk: File, port: Int = 5555): Result<Unit> {
        if (Store.PLAY) return Result.failure(IllegalStateException("not in this build"))
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        val remote = "/data/local/tmp/glass-update.apk"
        val log = "/data/local/tmp/glass-update.log"
        try {
            // Connect and copy (a 12 MB copy takes a few seconds).
            val d = runCatching {
                pool.submit<Dadb> {
                    synchronized(this) {
                        val d = conn ?: open(ctx, port).also { conn = it }
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

    /** Blocking: copies [f] to [remote] on the TV over the open connection. */
    @Synchronized
    fun push(ctx: Context, f: File, remote: String) {
        repeat(2) { attempt ->
            val r = runCatching { (conn ?: open(ctx, 5555).also { conn = it }).push(f, remote) }
            if (r.isSuccess) return
            runCatching { conn?.close() }; conn = null
            if (attempt == 1) throw r.exceptionOrNull()!!
        }
    }

    /**
     * When the TV's adb doesn't answer at all (USB debugging off, or Glassio not allowed), it is not asked again for a
     * while: every try waits up to 15 s on each of the TV's addresses, and the regular callers (Continue Watching from
     * Nuvio Legacy every 20 s, Recent apps, TV info) asked again and again, a timeout in the log every minute (#24).
     * Five minutes, then ten, at most thirty, for every caller (one window at a time, see [openLock]); [retryNow]
     * (the TV access page) clears it.
     */
    @Volatile private var quietUntil = 0L
    @Volatile private var quietFor = 0L
    fun retryNow() { quietUntil = 0L; quietFor = 0L; askedAt = 0L; declined = false }

    /**
     * Asked by the person (TV access page, setup): the TV is asked again whatever was answered before, and the answer is
     * waited for (2 minutes at most). Some TVs (the TCL) never tell this service that their window is up. Blocking.
     */
    fun ask(ctx: Context): Result<String> {
        // (A connection still waiting from before (the TCL keeps one after Cancel, without telling anyone) would take
        // this request's place and its outcome: it is stopped first, then the TV is asked afresh.)
        if (waiting()) {
            cancelled = true
            val until = android.os.SystemClock.uptimeMillis() + 3_000
            while (waiting() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(100)
        }
        promptUp = false
        retryNow(); askedByPerson = android.os.SystemClock.uptimeMillis()
        return run(ctx, "echo ok")
    }
    @Volatile private var askedByPerson = 0L
    /** TV access has worked on this TV at least once (kept in its own file: never in a backup or sync). */
    private fun everOk(ctx: Context) = ctx.getSharedPreferences("tv_access", Context.MODE_PRIVATE).getBoolean("ok", false)
    /** TV access has worked here before: a use that isn't worth the TV's "Allow USB debugging?" window checks this first. */
    fun worked(ctx: Context) = everOk(ctx)
    private fun markOk(ctx: Context) { if (!everOk(ctx)) ctx.getSharedPreferences("tv_access", Context.MODE_PRIVATE).edit().putBoolean("ok", true).apply() }
    /** Cancel was chosen on the TV's window for Glassio's key (see [open]). */
    @Volatile private var declined = false
    /** When the TV's window was last seen closing (the next one of its queue may come up right after). */
    @Volatile var promptClosedAt = 0L

    /** QA: every connection closed (the next command connects again, with the key in use then). */
    fun reset() { synchronized(this) { runCatching { conn?.close() }; conn = null }; synchronized(quickLock) { runCatching { quick?.close() }; quick = null }; goodHost = null; retryNow() }

    /** Blocking; call off the main thread. Keeps one connection open and reconnects if it drops. */
    @Synchronized
    fun run(ctx: Context, command: String, port: Int = 5555): Result<String> {
        repeat(2) { attempt ->
            val had = conn != null
            val r = runCatching {
                val d = conn ?: open(ctx, port).also { conn = it }
                val res = d.shell(command)
                check(res.exitCode == 0) { res.allOutput.trim().ifBlank { "exit ${res.exitCode}" } }
                res.allOutput
            }
            if (r.isSuccess) return r
            // (A connection kept from before may have dropped: one more try on a new one. No connection could be
            // made at all: no second try, which only asked the TV again.)
            if (!had || attempt == 1) {
                if (conn != null) Glog.w("TV adb shell", r.exceptionOrNull())
                return r
            }
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
        Bg.io("glass-shell") {
            synchronized(quickLock) {
                if (quick == null) runCatching { open(ctx, port).also { it.shell("true") } }
                    .onSuccess { quick = it }.onFailure { Glog.w("TV adb (quick)", it) }
            }
        }
    }

    /** Runs [command] on the quick connection, on its own thread (nothing waits for it). */
    /** [quick], waiting for the command to finish (call off the main thread). */
    fun quickNow(ctx: Context, command: String, port: Int = 5555): Boolean = synchronized(quickLock) {
        repeat(2) {
            val r = runCatching { (quick ?: open(ctx, port).also { quick = it }).shell(command) }
            if (r.isSuccess) return@synchronized true
            runCatching { quick?.close() }; quick = null
        }
        false
    }

    fun quick(ctx: Context, command: String, port: Int = 5555) {
        Bg.io("glass-shell") {
            synchronized(quickLock) {
                val t0 = android.os.SystemClock.uptimeMillis()
                repeat(2) { attempt ->
                    var tc = t0
                    val r = runCatching {
                        val d = quick ?: open(ctx, port).also { quick = it }
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
        }
    }
}
