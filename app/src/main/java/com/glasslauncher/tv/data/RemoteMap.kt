package com.glasslauncher.tv.data

import android.content.Intent
import com.glasslauncher.tv.GlassApp

/**
 * Remote mapping (Settings → Remote mapping): a remote button that opens an app, the Apps folder, the recent apps
 * or a TV input, from any app. The Home button service sees every button first (even ones the TV keeps from other
 * apps, like a Netflix button), so it works where button remappers can't (#12).
 *
 * Saved as "code|action|label" lines; the action is "app:<package>", "glassio" (Glassio's home screen, #13), "folder",
 * "recents", "input:<id>", "hue:<group id>" (those Hue lights off, and on again), "search", "cameras" (the cameras
 * page, filling the screen, #30) or "closeapp" (the app in
 * front closed through the TV's adb, #25). A double press or a hold has its own line: "code~double|…", "code~hold|…"
 * (#25); a plain "code|…" is the single press, as before.
 */
object RemoteMap {
    /** [kind]: "" a single press, "double", "hold". */
    class Map(val code: Int, val action: String, val label: String, val kind: String = "")

    // (Parsed once per change of the setting: every key of the remote asks, D-pad repeats too.)
    @Volatile private var parsed: Pair<List<String>, List<Map>>? = null
    fun all(st: Settings): List<Map> {
        val raw = st.keyMaps
        parsed?.takeIf { it.first == raw }?.let { return it.second }
        return parse(raw).also { parsed = raw to it }
    }
    private fun parse(lines: List<String>): List<Map> = lines.mapNotNull { l ->
        val p = l.split('|', limit = 3)
        val head = p.getOrNull(0).orEmpty().split('~')
        val code = head[0].toIntOrNull() ?: return@mapNotNull null
        val kind = head.getOrNull(1).orEmpty().takeIf { it == "double" || it == "hold" } ?: ""
        val action = p.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        Map(code, action, p.getOrNull(2).orEmpty(), kind)
    }
    private fun line(m: Map) = (if (m.kind.isBlank()) "${m.code}" else "${m.code}~${m.kind}") + "|${m.action}|${m.label.replace('|', ' ')}"

    /** The button [code] does [action] from now on on that kind of press (replacing what it did on it). */
    fun set(st: Settings, code: Int, action: String, label: String, kind: String = "") {
        st.keyMaps = all(st).filter { !(it.code == code && it.kind == kind) }.map(::line) + line(Map(code, action, label, kind))
    }

    /** Forgets what the button does on [kind] of press; null: on every kind. */
    fun remove(st: Settings, code: Int, kind: String? = null) {
        st.keyMaps = all(st).filter { !(it.code == code && (kind == null || it.kind == kind)) }.map(::line)
    }

    fun find(st: Settings, code: Int, kind: String = ""): Map? = if (code == 0) null else all(st).firstOrNull { it.code == code && it.kind == kind }
    /** The button has a job for a double press or a hold (its presses are then read before anything is done). */
    fun hasMore(st: Settings, code: Int) = code != 0 && all(st).any { it.code == code && it.kind.isNotBlank() }

    /** What the button does, in words. */
    fun describe(m: Map): String = when {
        m.action.startsWith("app:") -> "Opens " + m.label.ifBlank { m.action.removePrefix("app:") }
        m.action == "glassio" -> "Opens Glassio"
        m.action.startsWith("hue:") -> "Turns the ${m.label.ifBlank { "Hue" }} lights off and on"
        m.action == "folder" -> "Opens the Apps folder"
        m.action == "recents" -> "Opens the recent apps"
        m.action == "search" -> "Opens the search"
        m.action == "closeapp" -> "Closes the app in front"
        m.action == "cameras" -> "Shows the cameras on the whole screen"
        m.action == "multiview" -> "Shows multi-view"
        m.action == "corner" -> "Shows the corner camera"
        m.action == "shuffle" -> "Shuffles the carousel"
        m.action == "inputs" || m.action == "app:" + Inputs.DOCK_PKG -> "Shows the TV inputs"
        m.action.startsWith("input:") -> "Switches to " + m.label.ifBlank { "the input" }
        else -> m.action
    }

    /** Does what the button is set to (from the Home button service or Glassio itself). */
    fun run(app: GlassApp, m: Map) {
        val a = m.action
        runCatching {
            when {
                // The TV's inputs over whatever is on screen (also a button saved earlier with the Inputs icon as its
                // "app": it said "isn't installed"). Pressed again: closed.
                a == "inputs" || a == "app:" + Inputs.DOCK_PKG -> {
                    val open = com.glasslauncher.tv.InputsPickActivity.showing
                    if (open != null && !open.isFinishing) open.runOnUiThread { open.close() }
                    else app.startActivity(Intent(app, com.glasslauncher.tv.InputsPickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
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
                a == "search" -> glass(app) { it.putExtra("open_search", true) }
                a == "closeapp" -> closeFront(app)
                a == "cameras" -> com.glasslauncher.tv.MultiHost.toggle(bottom = false, full = true)
                    ?: android.widget.Toast.makeText(app, "The cameras page needs the Home button service: Glassio settings → System", android.widget.Toast.LENGTH_LONG).show()
                // Glassio's own three buttons as jobs too, so they can sit on a double press or a hold (#30): until now
                // they were buttons of their own (Shuffle / Multi-view / Corner camera button), single press only.
                a == "multiview" -> com.glasslauncher.tv.MultiHost.toggle(bottom = false)
                a == "corner" -> com.glasslauncher.tv.HomeKeyService.instance?.toggleCorner()
                    ?: android.widget.Toast.makeText(app, "The corner camera needs the Home button service: Glassio settings → System", android.widget.Toast.LENGTH_LONG).show()
                a == "shuffle" -> if (com.glasslauncher.tv.KeyHub.homeInFront) app.shuffleRev.intValue++
            }
        }.onFailure { Glog.w("Remote button ${m.code}", it) }
    }

    /**
     * Closes the app in front through the TV's own adb (am force-stop), then shows Glassio (#25). Never Glassio, the
     * TV's launcher or its settings. Without TV access (ADB) it says so.
     */
    private fun closeFront(app: GlassApp) {
        Bg.io("glass-close-app") {
            val out = LocalShell.run(app, "dumpsys activity activities | grep -m1 topResumedActivity").getOrNull()
            if (out == null) { toastMain(app, "Closing an app needs TV access (ADB): Glassio settings → System"); return@io }
            val pkg = Regex("""u0 ([A-Za-z0-9_.]+)/""").find(out)?.groupValues?.get(1)
            val keep = setOf(app.packageName, "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.android.tv.settings", "com.google.android.tungsten.setupwraith")
            if (pkg == null || pkg in keep) { toastMain(app, "No app to close"); return@io }
            val r = LocalShell.run(app, "am force-stop $pkg")
            android.os.Handler(android.os.Looper.getMainLooper()).post { glass(app) {} }
            val name = runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            toastMain(app, if (r.isSuccess) "Closed $name" else "Couldn't close $name")
        }
    }

    /** The room's / zone's lights off (all of them: "0"); if they are all off already, on again. */
    private fun hueToggle(app: GlassApp, group: String, label: String) {
        val st = app.settings
        if (st.hueIp.isBlank() || st.hueUser.isBlank()) {
            android.widget.Toast.makeText(app, "Connect your Hue Bridge first: Glassio settings → Hue lights", android.widget.Toast.LENGTH_SHORT).show(); return
        }
        Bg.io("glass-hue-key") {
            // Turned off by this button last time: on again (all of them). Asking the bridge whether any light was on
            // went wrong when the colour sync had lit some of them again in between (the next press turned them off).
            val wasOffHere = group in HueSync.heldOff
            val on = if (wasOffHere) true else Hue.groupAnyOn(st.hueIp, st.hueUser, group)
            if (on == null) { toastMain(app, "The Hue Bridge didn't answer"); return@io }
            if (on && !wasOffHere) {
                // Off: each light's state is kept first (brightness, colour), and the colour sync leaves the lights
                // alone (sends nothing) until this button turns them on again.
                val ids = Hue.groupLights(st.hueIp, st.hueUser, group)
                HueSync.beforeOff[group] = Hue.states(st.hueIp, st.hueUser, ids) to (HueSync.active && st.hueOn)
                HueSync.heldOff += group
                HueSync.leave(st, "keep")
                Hue.groupOn(st.hueIp, st.hueUser, group, false)
                toastMain(app, label.ifBlank { "Hue" } + " lights off")
            } else {
                HueSync.heldOff -= group
                // On again exactly as they were before (each light's brightness and colour): turned on as a group
                // and then given the screen's colours, they came back dimmer than they had been.
                val before = HueSync.beforeOff.remove(group)
                if (before != null && before.first.isNotEmpty()) before.first.forEach { (id, s) -> Hue.set(st.hueIp, st.hueUser, id, Hue.restoreCommand(s, 4)) }
                else Hue.groupOn(st.hueIp, st.hueUser, group, true)
                // (The screen's colours again only when they were following it before the lights went off.)
                if (HueSync.heldOff.isEmpty() && com.glasslauncher.tv.KeyHub.homeInFront && before?.second != false) HueSync.resume()
                toastMain(app, label.ifBlank { "Hue" } + " lights on")
            }
        }
    }

    private fun toastMain(app: GlassApp, text: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(app, I18n.tr(text), android.widget.Toast.LENGTH_SHORT).show()
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
