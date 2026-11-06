package com.glasslauncher.tv.data

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.net.HttpURLConnection
import java.net.URL

/**
 * A YouTube trailer as plain streams for Glassio's own player (NewPipe Extractor finds them): the picture, the
 * sound and the subtitles, so no YouTube page, buttons or titles show, it starts sooner, and the glass can follow
 * the picture like a video background.
 */
object TrailerSource {
    data class Sub(val url: String, val lang: String, val auto: Boolean, val mime: String)
    data class Streams(
        val video: String, val audio: String?, val width: Int, val height: Int, val subs: List<Sub>,
        val userAgent: String, val title: String = "", val at: Long = System.currentTimeMillis(),
    )

    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Streams>()
    private val failed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile private var ready = false

    private object Net : Downloader() {
        override fun execute(request: Request): Response {
            val c = URL(request.url()).openConnection() as HttpURLConnection
            c.requestMethod = request.httpMethod()
            c.connectTimeout = 8_000; c.readTimeout = 12_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA)
            request.headers().forEach { (k, vs) -> c.setRequestProperty(k, vs.joinToString(", ")) }
            request.dataToSend()?.let { body -> c.doOutput = true; c.outputStream.use { it.write(body) } }
            val code = c.responseCode
            val text = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            val headers = c.headerFields.filterKeys { it != null }
            return Response(code, c.responseMessage ?: "", headers, text, c.url.toString())
        }
    }

    @Synchronized private fun init() {
        if (ready) return
        NewPipe.init(Net)
        ready = true
    }

    /** Already found (and fresh): no wait. */
    fun cached(id: String, full: Boolean = false): Streams? = cache["$id/$full"]?.takeIf { System.currentTimeMillis() - it.at < 3 * 3600_000L }

    /** This TV decodes [mime] in hardware (VP9 / AV1 for the full quality). */
    private fun hw(mime: String): Boolean = runCatching {
        android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.any { c ->
            !c.isEncoder && c.supportedTypes.any { it.equals(mime, true) } &&
                (android.os.Build.VERSION.SDK_INT < 29 || c.isHardwareAccelerated)
        }
    }.getOrDefault(false)

    /**
     * The streams of YouTube video [id], or null (then YouTube's own player is used). Blocking. [maxH]: the tallest
     * picture worth playing on this TV. Found once and kept for a few hours (the links expire after ~6 h).
     */
    private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    fun resolve(id: String, maxH: Int, full: Boolean = false): Streams? {
        cached(id, full)?.let { return it }
        // Asked twice at once (got ready ahead, and started): the second waits for the first.
        synchronized(locks.getOrPut(id) { Any() }) { return resolveNow(id, maxH, full) }
    }

    private fun resolveNow(id: String, maxH: Int, full: Boolean): Streams? {
        cached(id, full)?.let { return it }
        failed[id]?.let { if (System.currentTimeMillis() - it < 30 * 60_000L) return null }
        val t0 = android.os.SystemClock.uptimeMillis()
        val r = runCatching {
            init()
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
            fun ok(d: DeliveryMethod) = d == DeliveryMethod.PROGRESSIVE_HTTP
            // H.264 in MP4 first (every TV decodes it in hardware), then VP9; never taller than the screen needs.
            val vids = info.videoOnlyStreams.filter { ok(it.deliveryMethod) && it.isUrl && it.height in 1..maxH }
            val avc = vids.filter { it.format == MediaFormat.MPEG_4 && it.codec?.startsWith("avc") == true }
            val video = if (full) {
                // The best picture this TV plays in hardware: VP9 / AV1 go above 1080p (YouTube has no 4K H.264).
                val vp9 = hw("video/x-vnd.on2.vp9"); val av1 = hw("video/av01")
                vids.filter { v -> val c = v.codec.orEmpty()
                    c.startsWith("avc") || (vp9 && c.startsWith("vp9") || vp9 && c.startsWith("vp09")) || (av1 && c.startsWith("av01")) }
                    .maxWithOrNull(compareBy<org.schabi.newpipe.extractor.stream.VideoStream> { it.height }.thenBy { it.fps }
                        .thenBy { if (it.codec.orEmpty().startsWith("avc")) 2 else if (it.codec.orEmpty().startsWith("vp")) 1 else 0 })
            } else (avc.maxByOrNull { it.height * 100 + it.fps } ?: vids.filter { it.format == MediaFormat.MPEG_4 }.maxByOrNull { it.height * 100 + it.fps }
                ?: vids.maxByOrNull { it.height * 100 + it.fps })
            val audio = info.audioStreams.filter { ok(it.deliveryMethod) && it.isUrl }
                // The original sound track (not a dub), M4A first.
                .sortedWith(compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> { it.audioTrackType == null || it.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL }
                    .thenByDescending { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate })
                .firstOrNull()
            val subs = info.subtitles.filter { it.isUrl && (it.format == MediaFormat.VTT || it.format == MediaFormat.TTML) }.map {
                Sub(it.content, it.languageTag ?: "", it.isAutoGenerated, if (it.format == MediaFormat.VTT) "text/vtt" else "application/ttml+xml")
            }
            if (video != null) Glog.i("Trailer $id: picture ${video.format?.name} ${video.codec} ${video.height}p, sound ${audio?.format?.name} ${audio?.codec}")
            if (video != null) Streams(video.content, audio?.content, video.width, video.height, subs, UA, info.name.orEmpty())
            else {
                // No separate picture: a muxed one (picture and sound together).
                val muxed = info.videoStreams.filter { ok(it.deliveryMethod) && it.isUrl && it.height <= maxH }.maxByOrNull { it.height }
                    ?: error("no playable stream")
                Streams(muxed.content, null, muxed.width, muxed.height, subs, UA, info.name.orEmpty())
            }
        }
        val ms = android.os.SystemClock.uptimeMillis() - t0
        r.onSuccess { cache["$id/$full"] = it; Glog.i("Trailer $id: ${it.height}p${if (it.audio != null) " + sound" else ""}, ${it.subs.size} subtitles (${ms} ms)") }
            .onFailure { failed[id] = System.currentTimeMillis(); Glog.w("Trailer $id: no streams (${ms} ms): ${it.javaClass.simpleName} ${it.message?.take(120)}") }
        return r.getOrNull()
    }
}
