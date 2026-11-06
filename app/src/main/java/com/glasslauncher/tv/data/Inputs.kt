package com.glasslauncher.tv.data

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The TV's inputs (HDMI) and what is plugged into them, found on their own.
 *
 * - On a TV (Android TV built into the screen, e.g. TCL): the TV's own list of inputs. A device that answers on
 *   HDMI-CEC shows with its own name (PlayStation 5, Apple TV…) in place of "HDMI 2". Opening one switches the
 *   screen to it; Home on the remote comes back to Glassio.
 * - On a box plugged into a TV (Google TV Streamer): the devices on the HDMI-CEC bus, read from the box's CEC
 *   service. Opening one asks the TV to switch to it (CEC "routing change"); Home brings the TV back to the box.
 *   Not every TV follows a switch asked for by another device (some LG TVs don't).
 *
 * Inputs can also sit in the dock as icons (pkg "input:…"), moved, hidden and given their own icon like apps.
 */
object Inputs {
    data class Input(val id: String, val label: String, val port: Int)

    const val PREFIX = "input:"
    val list = MutableStateFlow<List<Input>>(emptyList())
    /** When Glassio last switched the TV away (the box then brings it back on Home). */
    @Volatile var awayAt = 0L

    fun isInput(pkg: String) = pkg.startsWith(PREFIX)

    /** Blocking. Reads the inputs again (TV list, else CEC devices through the TV's debugging connection). */
    /**
     * The inputs found last time, straight away (kept on the TV): after the TV was off, its CEC list is empty for a
     * while and the PlayStation icon left the dock until it was read again.
     */
    fun load(ctx: Context) {
        if (list.value.isNotEmpty()) return
        val saved = ctx.getSharedPreferences("glass_inputs", Context.MODE_PRIVATE).getString("list", "").orEmpty()
        val l = saved.lines().mapNotNull { ln -> ln.split('\t').takeIf { it.size == 3 }?.let { Input(it[0], it[1], it[2].toIntOrNull() ?: 0) } }
        if (l.isNotEmpty()) list.value = l
    }

    private fun save(ctx: Context, l: List<Input>) =
        ctx.getSharedPreferences("glass_inputs", Context.MODE_PRIVATE).edit().putString("list", l.joinToString("\n") { "${it.id}\t${it.label}\t${it.port}" }).apply()

    fun refresh(ctx: Context): List<Input> {
        load(ctx)
        val found = runCatching { tvInputs(ctx) }.getOrDefault(emptyList()).ifEmpty { runCatching { cecDevices(ctx) }.getOrDefault(emptyList()) }
        // Nothing answering just now (the TV or the device waking up): the ones known stay.
        val l = found.ifEmpty { list.value }
        if (l != list.value) { list.value = l; save(ctx, l) } else if (found.isNotEmpty()) save(ctx, l)
        // Devices switched through the TV's shell: its quick connection is kept open, so a switch goes out at once.
        if (l.any { it.id.startsWith("cec:") }) LocalShell.warm(ctx)
        return l
    }

    private fun tvInputs(ctx: Context): List<Input> {
        val tm = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager ?: return emptyList()
        val all = tm.tvInputList
        val hdmi = all.filter { it.type == android.media.tv.TvInputInfo.TYPE_HDMI }
        val out = mutableListOf<Input>()
        val ports = hdmi.filter { it.parentId == null }
        ports.forEachIndexed { i, p ->
            val name = runCatching { p.loadCustomLabel(ctx)?.toString() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { p.loadLabel(ctx).toString() }.getOrDefault("HDMI ${i + 1}")
            // The device answering on that port (CEC), by its own name; else the port itself.
            val child = hdmi.firstOrNull { it.parentId == p.id }
            if (child != null) {
                val dev = runCatching { child.loadLabel(ctx).toString() }.getOrDefault(name).ifBlank { name }
                out += Input("tv:${child.id}", dev, i + 1)
            } else out += Input("tv:${p.id}", name, i + 1)
        }
        return out
    }

    private val devRe = Regex("""logical_address:\s*0x([0-9a-fA-F]+)\s+device_type:\s*(\d+).*?display_name:\s*(.*?)\s+power_status:.*?physical_address:\s*0x([0-9a-fA-F]+)""")

    private fun cecDevices(ctx: Context): List<Input> {
        val out = LocalShell.run(ctx, "dumpsys hdmi_control").getOrNull() ?: return emptyList()
        // This box's own address (its "mDeviceInfo" line) and the TV (address 0) are left out.
        val own = Regex("""mDeviceInfo:.*?logical_address:\s*0x([0-9a-fA-F]+)""").find(out)?.groupValues?.get(1)?.toIntOrNull(16)
        val section = out.substringAfter("mDeviceInfos:", "").substringBefore("\n  m", "")
        return devRe.findAll(section.ifBlank { out }).mapNotNull { m ->
            val la = m.groupValues[1].toInt(16)
            val pa = m.groupValues[4].toInt(16)
            if (la == 0 || la == own || la == 15) return@mapNotNull null
            val port = (pa shr 12) and 0xF
            val name = m.groupValues[3].trim().ifBlank { "HDMI $port" }
            Input("cec:$la:$pa", name, port)
        }.distinctBy { it.id }.sortedBy { it.port }.toList()
    }

    /** Switches the screen to [id]. True when it was asked. */
    fun open(ctx: Context, id: String): Boolean {
        val raw = id.removePrefix(PREFIX)
        awayAt = System.currentTimeMillis()
        return if (raw.startsWith("tv:")) runCatching {
            val uri = android.media.tv.TvContract.buildChannelUriForPassthroughInput(raw.removePrefix("tv:"))
            ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Glog.w("Input $raw", it) }.isSuccess
        else {
            val la = raw.removePrefix("cec:").substringBefore(':')
            // At once on screen, while the TV switches (the device itself takes a moment to answer).
            list.value.firstOrNull { it.id == raw }?.let { i ->
                android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, "Switching to ${i.label}…", android.widget.Toast.LENGTH_SHORT).show() }
            }
            LocalShell.quick(ctx, "cmd hdmi_control deviceselect $la")
            true
        }
    }

    /** Home pressed after Glassio switched the TV away (box only): the TV comes back to this box. */
    fun comeBack(ctx: Context) {
        if (awayAt == 0L) return
        awayAt = 0L
        if (list.value.any { it.id.startsWith("cec:") }) LocalShell.quick(ctx, "cmd hdmi_control otp")
    }

    /** A simple icon for an input: its name with an HDMI mark, see-through like the apps' glyph icons (the icon can be changed like any app's). */
    fun icon(label: String): android.graphics.Bitmap {
        val w = 640; val h = 360
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        // HDMI plug outline.
        p.style = android.graphics.Paint.Style.STROKE; p.strokeWidth = 10f; p.color = 0xFFFFFFFF.toInt(); p.strokeJoin = android.graphics.Paint.Join.ROUND
        val path = android.graphics.Path().apply {
            moveTo(w * 0.40f, h * 0.20f); lineTo(w * 0.60f, h * 0.20f); lineTo(w * 0.60f, h * 0.30f); lineTo(w * 0.57f, h * 0.38f)
            lineTo(w * 0.43f, h * 0.38f); lineTo(w * 0.40f, h * 0.30f); close()
        }
        c.drawPath(path, p)
        p.style = android.graphics.Paint.Style.FILL; p.textAlign = android.graphics.Paint.Align.CENTER
        p.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        var size = 64f
        p.textSize = size
        while (p.measureText(label) > w * 0.86f && size > 30f) { size -= 4f; p.textSize = size }
        c.drawText(label, w / 2f, h * 0.70f, p)
        return b
    }
}
