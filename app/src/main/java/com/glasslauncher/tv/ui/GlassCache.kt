package com.glasslauncher.tv.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher

/** The three small copies of a backdrop that the glass refracts: blurred (setting), sharp, heavy (folder / veil). */
data class GlassImages(val blurred: ImageBitmap, val sharp: ImageBitmap, val heavy: ImageBitmap)

/** A TMDB image at another size ("w780", "w1280"); other URLs are returned unchanged. */
fun sized(url: String, size: String) = url.replace("/t/p/original/", "/t/p/$size/")

/**
 * Glass copies of recent and upcoming backdrops, prepared ahead of time so a slide, its backdrop and the
 * glass in front of it all change in the same frame. Each entry is ~1.5 MB (three 480×270 bitmaps).
 *
 * The work runs in its own scope: a caller that goes away (you pressed Right again) only stops waiting,
 * it never cancels a job other callers are waiting for.
 */
object GlassCache {
    /** Width of the glass copies (Settings → Performance → Glass detail). */
    @Volatile var res: Int = 480
    // Limited by memory (about 36 MB), not by count: bigger glass copies keep fewer slides instead of more memory.
    private val cache = object : LruCache<String, GlassImages>(36 * 1024 * 1024) {
        override fun sizeOf(key: String, v: GlassImages): Int =
            v.blurred.width * v.blurred.height * 4 + v.sharp.width * v.sharp.height * 4 + v.heavy.width * v.heavy.height * 4
    }
    private val pending = HashMap<String, Deferred<GlassImages?>>()
    // One low-priority worker: blurring never competes with drawing the screen.
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glass-blur").apply { isDaemon = true }
    }
    private val scope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())

    /** Memory for glass copies by tier (12 / 24 / 36 MB). */
    fun limit(tier: Int) { cache.resize(when (tier) { 0 -> 12; 2 -> 36; else -> 24 } * 1024 * 1024) }

    /** Low memory: keep only what is pinned (Continue Watching). */
    fun trim() { cache.evictAll() }

    /**
     * Glassio away for a while (an app or a film in front): the pinned pictures go too (Continue Watching's and the
     * app carousels' first slides, ~8 MB each, up to 13 of them), and the logos. Pinned again in the background when
     * Glassio is back ([released] tells the carousel to).
     */
    fun trimAll() {
        cache.evictAll(); pinned.clear(); pinnedFull.clear(); pinnedLogo.clear(); logos.evictAll()
        released.intValue++
    }
    val released = androidx.compose.runtime.mutableIntStateOf(0)

    fun peek(url: String, blur: Int): GlassImages? = pinned["$blur|$res|$url"] ?: cache.get("$blur|$res|$url")

    /** Continue Watching titles: kept for as long as they are in the list, whatever else is browsed. */
    private val pinned = java.util.concurrent.ConcurrentHashMap<String, GlassImages>()
    val pinnedFull = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
    val pinnedLogo = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

    suspend fun pin(ctx: Context, url: String, blur: Int) {
        val key = "$blur|$res|$url"
        if (pinned.containsKey(key)) return
        get(ctx, url, blur)?.let { pinned[key] = it }
    }

    /** Drops pins of titles that left Continue Watching. */
    fun keepOnly(urls: Set<String>) {
        pinned.keys.removeAll { k -> k.substringAfter('|').substringAfter('|') !in urls }
        pinnedFull.keys.retainAll(urls)
        pinnedLogo.keys.retainAll(urls)
    }

    /** The slide on screen: never waits behind the prepared-ahead ones on the low-priority worker. */
    private val urgentScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Logos of the slides around the current one, decoded ahead so the title appears with its picture. */
    val logos = LruCache<String, ImageBitmap>(16)

    private suspend fun make(ctx: Context, url: String, blur: Int, r: Int, key: String): GlassImages? = try {
        val req = ImageRequest.Builder(ctx.applicationContext).data(sized(url, "w780"))
            .size(r, r * 9 / 16).allowHardware(false).build()
        val src = (SingletonImageLoader.get(ctx.applicationContext).execute(req) as? SuccessResult)?.image?.toBitmap()
        src?.let { val sh = if (it.isMutable) it.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else it; sh.prepareToDraw(); GlassImages(blurredBackdrop(it, blur), sh.asImageBitmap(), blurredBackdrop(it, 80)) }
            ?.also { cache.put(key, it) }
    } catch (e: Exception) {
        com.glasslauncher.tv.data.Glog.w("Glass copy of $url", e)
        null
    }

    /**
     * The glass copies made from a picture already in memory (the page's own): nothing to download, so the
     * slide on screen never waits for a second copy of its picture.
     */
    fun fromBitmap(url: String, blur: Int, full: android.graphics.Bitmap): GlassImages {
        val r = res
        val key = "$blur|$r|$url"
        pinned[key]?.let { return it }
        cache.get(key)?.let { return it }
        val src = android.graphics.Bitmap.createScaledBitmap(full, r, r * 9 / 16, true)
        val sh = if (src.isMutable) src.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else src
        sh.prepareToDraw()
        return GlassImages(blurredBackdrop(src, blur), sh.asImageBitmap(), blurredBackdrop(src, 80)).also { cache.put(key, it) }
    }

    /** [urgent]: the picture going on screen now (runs at once instead of queueing behind prefetching). */
    suspend fun get(ctx: Context, url: String, blur: Int, urgent: Boolean = false): GlassImages? {
        val r = res
        val key = "$blur|$r|$url"
        pinned[key]?.let { return it }
        cache.get(key)?.let { return it }
        if (urgent) {
            val queued = synchronized(pending) { pending[key] }
            // Already being made right now? wait a little for it, otherwise make it here.
            queued?.let { q -> kotlinx.coroutines.withTimeoutOrNull(60) { q.await() }?.let { return it } }
            cache.get(key)?.let { return it }
            return urgentScope.async { make(ctx, url, blur, r, key) }.await()
        }
        val job = synchronized(pending) {
            pending.getOrPut(key) {
                scope.async {
                    try { make(ctx, url, blur, r, key) } finally { synchronized(pending) { pending.remove(key) } }
                }
            }
        }
        return job.await()
    }
}
