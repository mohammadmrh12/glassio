package com.glasslauncher.tv.data

/**
 * The play build has no search of YouTube or SoundCloud inside Glassio (the part that reads them, NewPipe
 * Extractor, is only in the github build): nothing is found, and the search offers to open the words in the apps.
 */
object MediaSearch {
    const val AVAILABLE = false
    class Clip(val url: String, val title: String, val by: String, val seconds: Long, val thumb: String)
    class Pager { val more: Boolean get() = false; fun next(): List<Clip> = emptyList() }
    fun youtube(query: String) = Pager()
    fun soundcloud(query: String) = Pager()
}
