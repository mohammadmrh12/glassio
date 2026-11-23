package com.glasslauncher.tv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Opens Glassio when the TV starts (System → "Open Glassio when the TV starts"). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED && intent?.action != "android.intent.action.QUICKBOOT_POWERON") return
        val st = (ctx.applicationContext as? GlassApp)?.settings ?: return
        if (!st.bootStart) return
        val app = ctx.applicationContext
        fun open() = runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
        val shell = com.glasslauncher.tv.data.LocalShell
        // The TV's "Allow USB debugging?" window is up (or Glassio's own connection is waiting for it): opened over
        // it, the window stayed open behind Glassio, unanswered, and the TV never asked again (#25). Glassio opens
        // when the window is answered (HomeKeyService), or once nothing waits any more if no window was seen.
        // (The TV may hand Glassio its start minutes late: a try that got no answer counts until the window is
        // answered, not for a while.)
        if (!shell.promptUp && !shell.waiting() && shell.askedAt == 0L) { open(); return }
        shell.openAfterPrompt = true
        com.glasslauncher.tv.data.Glog.i("Open Glassio when the TV starts: after the TV's Allow USB debugging window")
        // (A connection still waiting that then fails some other way (no window ever came): open once nothing asks.
        // After a try that got no answer, only the answer opens Glassio: the window may be up unseen.)
        com.glasslauncher.tv.data.Bg.io("glass-boot-open") {
            repeat(240) {
                kotlinx.coroutines.delay(500)
                if (!shell.openAfterPrompt) return@io
                if (!shell.promptUp && !shell.waiting() && shell.askedAt == 0L) { shell.openAfterPrompt = false; open(); return@io }
            }
        }
    }
}
