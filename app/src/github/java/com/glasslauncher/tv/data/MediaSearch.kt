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

    /**
     * One search of YouTube or SoundCloud, a page at a time: [next] gives the first page, then each following one,
     * and an empty list when there is no more (or the site didn't answer). Blocking; not for two threads at once.
     */
    class Pager internal constructor(private val service: org.schabi.newpipe.extractor.StreamingService, private val query: String, private val filter: String) {
        private var ex: org.schabi.newpipe.extractor.search.SearchExtractor? = null
        private var following: org.schabi.newpipe.extractor.Page? = null
        private var ended = false
        val more: Boolean get() = !ended
        fun next(): List<Clip> {
            if (ended) return emptyList()
            TrailerSource.init()
            val page = ex.let { e ->
                if (e == null) service.getSearchExtractor(query, listOf(filter), "").also { ex = it; it.fetchPage() }.initialPage
                else e.getPage(following ?: run { ended = true; return emptyList() })
            }
            following = page.nextPage.takeIf { page.hasNextPage() }
            if (following == null) ended = true
            return page.items.filterIsInstance<StreamInfoItem>().take(20).mapNotNull { i ->
                val url = i.url ?: return@mapNotNull null
                // The widest picture that is still a thumbnail (the first is often tiny).
                val pic = i.thumbnails.filter { it.width in 1..800 }.maxByOrNull { it.width }?.url ?: i.thumbnails.lastOrNull()?.url ?: ""
                Clip(url, i.name.orEmpty(), i.uploaderName.orEmpty(), i.duration.coerceAtLeast(0), pic)
            }
        }
    }

    fun youtube(query: String) = Pager(ServiceList.YouTube, query, "videos")
    fun soundcloud(query: String) = Pager(ServiceList.SoundCloud, query, "tracks")
}
