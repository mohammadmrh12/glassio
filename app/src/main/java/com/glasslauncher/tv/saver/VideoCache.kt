package com.glasslauncher.tv.saver

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
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
        dir(ctx), LeastRecentlyUsedCacheEvictor(gb.toLong() * 1024 * 1024 * 1024), StandaloneDatabaseProvider(ctx.applicationContext),
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
