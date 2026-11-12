package com.glasslauncher.tv.data

import android.content.Intent
import com.glasslauncher.tv.GlassApp

/**
 * Remote mapping (Settings → Remote mapping): a remote button that opens an app, the Apps folder, the recent apps
 * or a TV input, from any app. The Home button service sees every button first (even ones the TV keeps from other
 * apps, like a Netflix button), so it works where button remappers can't (#12).
 *
 * Saved as "code|action|label" lines; the action is "app:<package>", "glassio" (Glassio's home screen, #13), "folder",
 * "recents", "input:<id>" or "hue:<group id>" (those Hue lights off, and on again).
 */
object RemoteMap {
    class Map(val code: Int, val action: String, val label: String)

    fun all(st: Settings): List<Map> = st.keyMaps.mapNotNull { l ->
        val p = l.split('|', limit = 3)
        val code = p.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
        val action = p.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Map(code, action, p.getOrNull(2).orEmpty())
    }

    /** The button [code] does [action] from now on (replacing what it did). */
    fun set(st: Settings, code: Int, action: String, label: String) {
        st.keyMaps = all(st).filter { it.code != code }.map { "${it.code}|${it.action}|${it.label}" } + "$code|$action|${label.replace('|', ' ')}"
    }

    fun remove(st: Settings, code: Int) { st.keyMaps = all(st).filter { it.code != code }.map { "${it.code}|${it.action}|${it.label}" } }

    fun find(st: Settings, code: Int): Map? = if (code == 0) null else all(st).firstOrNull { it.code == code }

    /** What the button does, in words. */
    fun describe(m: Map): String = when {
        m.action.startsWith("app:") -> "Opens " + m.label.ifBlank { m.action.removePrefix("app:") }
        m.action == "glassio" -> "Opens Glassio"
        m.action.startsWith("hue:") -> "Turns the ${m.label.ifBlank { "Hue" }} lights off and on"
        m.action == "folder" -> "Opens the Apps folder"
        m.action == "recents" -> "Opens the recent apps"
        m.action.startsWith("input:") -> "Switches to " + m.label.ifBlank { "the input" }
        else -> m.action
    }

    /** Does what the button is set to (from the Home button service or Glassio itself). */
    fun run(app: GlassApp, m: Map) {
        val a = m.action
        runCatching {
            when {
                a.startsWith("app:") -> {
                    val pkg = a.removePrefix("app:")
                    // A locked app asks for its code in Glassio first.
                    if (pkg in app.settings.lockedApps) glass(app) { it.putExtra("open_app", pkg) }
                    else {
                        val pm = app.packageManager
                        val i = (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg))
                            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        if (i != null) app.startActivity(i)
                        else android.widget.Toast.makeText(app, "${m.label.ifBlank { pkg }} isn't installed", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                // On the home screen the button opens the folder and closes it again; elsewhere it opens Glassio on it.
                a == "folder" -> if (com.glasslauncher.tv.KeyHub.homeInFront) app.folderToggle.intValue++ else glass(app) { it.putExtra("open_folder", true) }
                // Glassio's own home screen (for a remote whose Home button doesn't work, #13).
                a == "glassio" -> glass(app) {}
                a.startsWith("hue:") -> hueToggle(app, a.removePrefix("hue:"), m.label)
                a == "recents" && com.glasslauncher.tv.RecentsActivity.showing?.isFinishing == false ->
                    com.glasslauncher.tv.RecentsActivity.showing?.let { r -> r.runOnUiThread { r.close() } }
                a == "recents" -> app.startActivity(Intent(app, com.glasslauncher.tv.RecentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                a.startsWith("input:") -> Inputs.open(app, a)
            }
        }.onFailure { Glog.w("Remote button ${m.code}", it) }
    }

    /** The room's / zone's lights off (all of them: "0"); if they are all off already, on again. */
    private fun hueToggle(app: GlassApp, group: String, label: String) {
        val st = app.settings
        if (st.hueIp.isBlank() || st.hueUser.isBlank()) {
            android.widget.Toast.makeText(app, "Connect your Hue Bridge first: Glassio settings → Hue lights", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        Thread({
            // Turned off by this button last time: on again (all of them). Asking the bridge whether any light was on
            // went wrong when the colour sync had lit some of them again in between (the next press turned them off).
            val wasOffHere = group in HueSync.heldOff
            val on = if (wasOffHere) true else Hue.groupAnyOn(st.hueIp, st.hueUser, group)
            if (on == null) { toastMain(app, "The Hue Bridge didn't answer"); return@Thread }
            if (on && !wasOffHere) {
                // Off: the colour sync leaves the lights alone (sends nothing) until this button turns them on again.
                HueSync.heldOff += group
                HueSync.leave(st, "keep")
                Hue.groupOn(st.hueIp, st.hueUser, group, false)
                toastMain(app, label.ifBlank { "Hue" } + " lights off")
            } else {
                HueSync.heldOff -= group
                Hue.groupOn(st.hueIp, st.hueUser, group, true)
                if (HueSync.heldOff.isEmpty() && com.glasslauncher.tv.KeyHub.homeInFront) HueSync.resume()
                toastMain(app, label.ifBlank { "Hue" } + " lights on")
            }
        }, "glass-hue-key").start()
    }

    private fun toastMain(app: GlassApp, text: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun glass(app: GlassApp, extra: (Intent) -> Unit) {
        // As the Home button does (MAIN + HOME): the same Glassio comes to the front. Without the category Android
        // opened a second Glassio beside the home one, and both then played the trailer (double sound; the hidden
        // one never showed a picture and stopped the trailer after 12 s).
        val i = Intent(app, com.glasslauncher.tv.MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        extra(i)
        app.startActivity(i)
    }
}
