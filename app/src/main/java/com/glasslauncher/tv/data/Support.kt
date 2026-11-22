package com.glasslauncher.tv.data

import com.glasslauncher.tv.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "Support me": a PlayStation (PSN, US store) gift card code typed on the TV goes straight to the developer.
 *
 * It is sent as one answer to a private Google Form: only the developer sees the answers (and gets an email for each
 * one). No password or token is kept in the app. The form's address and its two field ids come from local.properties
 * (SUPPORT_FORM, SUPPORT_CODE_ENTRY, SUPPORT_NOTE_ENTRY); a build without them shows the option as unavailable.
 */
object Support {
    val ready: Boolean get() = !Store.PLAY && BuildConfig.SUPPORT_FORM.isNotBlank() && BuildConfig.SUPPORT_CODE_ENTRY.isNotBlank()

    /** A PSN gift card code: 12 letters and digits (written XXXX-XXXX-XXXX). Null if it isn't one. */
    fun clean(code: String): String? {
        val c = code.uppercase().filter { it.isLetterOrDigit() }
        return if (c.length == 12) c.chunked(4).joinToString("-") else null
    }

    /** Sends it; true when the form took it. Blocking (call off the main thread). */
    fun send(code: String, note: String, version: String): Boolean = !Store.PLAY && runCatching {
        fun e(s: String) = URLEncoder.encode(s, "UTF-8")
        val body = buildString {
            append("entry.").append(BuildConfig.SUPPORT_CODE_ENTRY).append('=').append(e(code))
            if (BuildConfig.SUPPORT_NOTE_ENTRY.isNotBlank())
                append("&entry.").append(BuildConfig.SUPPORT_NOTE_ENTRY).append('=').append(e(listOf(note.trim(), "Glassio $version").filter { it.isNotBlank() }.joinToString(" · ")))
        }
        val c = URL(BuildConfig.SUPPORT_FORM).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.instanceFollowRedirects = false
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.outputStream.use { it.write(body.toByteArray()) }
        val r = c.responseCode
        c.disconnect()
        r in 200..399
    }.getOrDefault(false)
}
