package com.glasslauncher.tv.data

/**
 * The play build has no search of YouTube or SoundCloud inside Glassio (the part that reads them, NewPipe
 * Extractor, is only in the github build): nothing is found, and the search offers to open the words in the apps.
 */
object MediaSearch {
    const val AVAILABLE = false
    class Clip(val url: String, val title: String, val by: String, val seconds: Long, val thumb: String)
    fun youtube(query: String): List<Clip> = emptyList()
    fun soundcloud(query: String): List<Clip> = emptyList()
}
