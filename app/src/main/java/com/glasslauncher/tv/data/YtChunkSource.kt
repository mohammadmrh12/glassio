package com.glasslauncher.tv.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

/**
 * Reads a YouTube stream in pieces asked for with its "range" parameter, as YouTube's own players do: one long
 * download of the whole file is slowed down by YouTube after a few seconds (the trailer froze), pieces come at
 * full speed. Other links (subtitles) are read as they are.
 */
@UnstableApi
class YtChunkSource(private val upstream: DataSource.Factory) : BaseDataSource(true) {
    private var cur: DataSource? = null
    private var uri: Uri? = null
    private var pos = 0L
    /** Where the data asked for ends (exclusive), or -1 when unknown. */
    private var end = -1L
    private var chunkLeft = 0L
    private var plain = false
    private var transferring = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        plain = dataSpec.uri.host?.contains("googlevideo") != true
        if (plain) {
            val d = upstream.createDataSource(); cur = d
            return d.open(dataSpec)
        }
        transferInitializing(dataSpec)
        val clen = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull()
        pos = dataSpec.position
        end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) pos + dataSpec.length else clen ?: -1L
        openChunk()
        transferStarted(dataSpec); transferring = true
        return if (end > 0) end - pos else C.LENGTH_UNSET.toLong()
    }

    private fun openChunk() {
        runCatching { cur?.close() }
        val last = (if (end > 0) minOf(end, pos + CHUNK) else pos + CHUNK) - 1
        val u = uri!!.buildUpon().appendQueryParameter("range", "$pos-$last").build()
        val d = upstream.createDataSource()
        d.open(DataSpec(u))
        cur = d
        chunkLeft = last - pos + 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (plain) return cur!!.read(buffer, offset, length)
        if (length == 0) return 0
        if (end in 1..pos) return C.RESULT_END_OF_INPUT
        if (chunkLeft <= 0) openChunk()
        val n = cur!!.read(buffer, offset, minOf(length.toLong(), chunkLeft).toInt())
        if (n == C.RESULT_END_OF_INPUT) {
            // The piece ended early: that was the end of the file (its length wasn't known).
            return C.RESULT_END_OF_INPUT
        }
        pos += n; chunkLeft -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        runCatching { cur?.close() }
        cur = null
        if (transferring) { transferring = false; transferEnded() }
        uri = null
    }

    companion object { private const val CHUNK = 4L * 1024 * 1024 }
}
