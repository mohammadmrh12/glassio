package com.glasslauncher.tv.data

/**
 * The play build has no trailers of its own: Google Play does not allow playing YouTube's videos outside YouTube's
 * own player, so the part that finds a trailer's streams (NewPipe Extractor, in the github build) is not here.
 * Nothing is ever found, and the carousel, the Library and the screensaver keep their pictures.
 */
object TrailerSource {
    data class Sub(val url: String, val lang: String, val auto: Boolean, val mime: String)
    data class Streams(
        val video: String, val audio: String?, val width: Int, val height: Int, val subs: List<Sub>,
        val userAgent: String, val title: String = "", val at: Long = System.currentTimeMillis(),
    )

    const val UA = ""

    fun cached(id: String, full: Boolean = false): Streams? = null
    fun resolve(id: String, maxH: Int, full: Boolean = false): Streams? = null
}
