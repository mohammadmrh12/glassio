package com.glasslauncher.tv.saver

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import java.io.File

/**
 * Screensaver videos are kept on the TV as they play (any link: Apple, Amazon, your own), so the next time they
 * start at once and don't use the internet. The oldest go first once the size you chose is reached.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object VideoCache {
    @Volatile private var cache: SimpleCache? = null
    private fun dir(ctx: Context) = File(ctx.filesDir, "aerial-cache")

    @Synchronized fun get(ctx: Context, gb: Int): SimpleCache = cache ?: SimpleCache(
        dir(ctx), SpaceAwareEvictor(gb.toLong() * 1024 * 1024 * 1024, dir(ctx)), StandaloneDatabaseProvider(ctx.applicationContext),
    ).also { cache = it }

    fun factory(ctx: Context, on: Boolean, gb: Int): MediaSource.Factory {
        val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000)
        val upstream = DefaultDataSource.Factory(ctx, http)
        if (!on) return DefaultMediaSourceFactory(upstream)
        val cached = CacheDataSource.Factory().setCache(get(ctx, gb)).setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(cached)
    }

    /** Bytes kept on the TV. */
    fun used(ctx: Context): Long = runCatching { cache?.cacheSpace ?: dir(ctx).walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0)

    @Synchronized fun clear(ctx: Context) {
        runCatching { cache?.release() }; cache = null
        runCatching { dir(ctx).deleteRecursively() }
    }
}

/**
 * Oldest videos go first once the chosen size is reached, like Media3's LeastRecentlyUsedCacheEvictor, and also
 * whenever the TV's free space would drop under [com.glasslauncher.tv.data.Storage.RESERVE] (the chosen size can
 * be more than the TV has free, and other apps fill it too).
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class SpaceAwareEvictor(private val maxBytes: Long, private val dir: File) : androidx.media3.datasource.cache.CacheEvictor {
    private val spans = java.util.TreeSet<androidx.media3.datasource.cache.CacheSpan> { a, b ->
        val t = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp); if (t != 0) t else a.compareTo(b)
    }
    private var size = 0L
    // Free space is read at most every few seconds (it's a system call; spans arrive many times a second).
    private var freeAt = 0L
    private var free = Long.MAX_VALUE

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() {}
    override fun onStartFile(cache: androidx.media3.datasource.cache.Cache, key: String, position: Long, length: Long) {
        if (length != androidx.media3.common.C.LENGTH_UNSET.toLong()) evict(cache, length)
    }
    override fun onSpanAdded(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) {
        spans.add(span); size += span.length; evict(cache, 0)
    }
    override fun onSpanRemoved(cache: androidx.media3.datasource.cache.Cache, span: androidx.media3.datasource.cache.CacheSpan) {
        spans.remove(span); size -= span.length
    }
    override fun onSpanTouched(cache: androidx.media3.datasource.cache.Cache, oldSpan: androidx.media3.datasource.cache.CacheSpan, newSpan: androidx.media3.datasource.cache.CacheSpan) {
        onSpanRemoved(cache, oldSpan); onSpanAdded(cache, newSpan)
    }

    private fun limit(): Long {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - freeAt > 5_000) { free = com.glasslauncher.tv.data.Storage.free(dir); freeAt = now }
        // What the videos hold now plus what's free, minus the space always left for the TV.
        return minOf(maxBytes, size + free - com.glasslauncher.tv.data.Storage.RESERVE).coerceAtLeast(0L)
    }

    private fun evict(cache: androidx.media3.datasource.cache.Cache, required: Long) {
        val max = limit()
        while (size + required > max && spans.isNotEmpty()) {
            val oldest = spans.first()
            runCatching { cache.removeSpan(oldest) }.onFailure { spans.remove(oldest); size -= oldest.length }
            // Freed space shows on the next reading.
            freeAt = 0L
        }
    }
}

