package com.glasslauncher.tv.data

import android.content.Intent
import com.glasslauncher.tv.GlassApp

/**
 * Remote mapping (Settings → Remote mapping): a remote button that opens an app, the Apps folder, the recent apps
 * or a TV input, from any app. The Home button service sees every button first (even ones the TV keeps from other
 * apps, like a Netflix button), so it works where button remappers can't (#12).
 *
 * Saved as "code|action|label" lines; the action is "app:<package>", "folder", "recents" or "input:<id>".
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
                a == "recents" && com.glasslauncher.tv.RecentsActivity.showing?.isFinishing == false ->
                    com.glasslauncher.tv.RecentsActivity.showing?.let { r -> r.runOnUiThread { r.close() } }
                a == "recents" -> app.startActivity(Intent(app, com.glasslauncher.tv.RecentsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                a.startsWith("input:") -> Inputs.open(app, a)
            }
        }.onFailure { Glog.w("Remote button ${m.code}", it) }
    }

    private fun glass(app: GlassApp, extra: (Intent) -> Unit) {
        val i = Intent(app, com.glasslauncher.tv.MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        extra(i)
        app.startActivity(i)
    }
}
