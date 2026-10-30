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
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}
