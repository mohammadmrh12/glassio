package com.glasslauncher.tv.data

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * The search's videos and songs: YouTube's and SoundCloud's own search, read by NewPipe Extractor (no key, no
 * account). Only what is found is shown here: a video opens in the YouTube app (or SmartTube), a song in
 * SoundCloud's app. Nothing is played by Glassio.
 *
 * Not in the play build (NewPipe Extractor is not there): its stand-in finds nothing and says so with [AVAILABLE].
 */
object MediaSearch {
    const val AVAILABLE = true

    /** One video or song found: its page's address, its name, who made it, its length in seconds (0: live or unknown), a picture. */
    class Clip(val url: String, val title: String, val by: String, val seconds: Long, val thumb: String)

    private fun find(service: org.schabi.newpipe.extractor.StreamingService, query: String, filter: String): List<Clip> {
        TrailerSource.init()
        val ex = service.getSearchExtractor(query, listOf(filter), "")
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().take(20).mapNotNull { i ->
            val url = i.url ?: return@mapNotNull null
            // The widest picture that is still a thumbnail (the first is often tiny).
            val pic = i.thumbnails.filter { it.width in 1..800 }.maxByOrNull { it.width }?.url ?: i.thumbnails.lastOrNull()?.url ?: ""
            Clip(url, i.name.orEmpty(), i.uploaderName.orEmpty(), i.duration.coerceAtLeast(0), pic)
        }
    }

    /** Blocking. */
    fun youtube(query: String): List<Clip> = find(ServiceList.YouTube, query, "videos")
    /** Blocking. */
    fun soundcloud(query: String): List<Clip> = find(ServiceList.SoundCloud, query, "tracks")
}
