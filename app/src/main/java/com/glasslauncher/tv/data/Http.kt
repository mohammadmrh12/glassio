package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Small JSON-over-HTTP helper with an on-disk cache that also serves as an offline fallback. */
class Http(ctx: Context) {
    private val dir = File(ctx.cacheDir, "api-cache").apply { mkdirs() }

    private fun key(url: String) =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun text(url: String, ttlMs: Long, bearer: String? = null): String {
        val f = File(dir, key(url) + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttlMs) {
            runCatching { return f.readText() }
        }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", "GlassLauncher/1.0")
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                runCatching { f.writeText(body) }.onFailure { Glog.w("Saving a response for later", it) }
                return body
            } finally { c.disconnect() }
        } catch (e: Exception) {
            if (f.exists()) runCatching { return f.readText() }
            throw e
        }
    }

    fun json(url: String, ttlMs: Long, bearer: String? = null) = JSONObject(text(url, ttlMs, bearer))
    fun jsonArray(url: String, ttlMs: Long = 0) = JSONArray(text(url, ttlMs))

    /** Never cached and never falls back: used for the local Nuvio add-on server. */
    fun liveArray(url: String, timeoutMs: Int = 2500): JSONArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONArray(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    /** Fire-and-forget JSON POST (used for the Home Assistant webhook). */
    fun post(url: String, body: JSONObject): Int {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 6000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        try {
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            return c.responseCode
        } finally { c.disconnect() }
    }
}
