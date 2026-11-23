package com.glasslauncher.tv.data

import android.util.Log

/**
 * One log tag for everything that can go wrong quietly (network, Trakt, the TV's adb, opening a title).
 * To see it: adb logcat -s Glassio
 *
 * The last lines are also kept in memory, for a problem report (Settings → About → Report a problem).
 */
object Glog {
    /** (Here, not in the screens' files: the words collector would take it for a text to translate.) */
    const val HOME_SAVER = "Screensaver on the home screen: "
    const val TAG = "Glassio"
    private const val KEEP = 250
    private val lines = ArrayDeque<String>()

    private fun keep(level: Char, text: String) {
        val t = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        synchronized(lines) { lines.addLast("$t $level ${text.take(600)}"); while (lines.size > KEEP) lines.removeFirst() }
    }

    fun w(what: String, e: Throwable? = null) {
        val text = if (e != null) "$what: ${e.javaClass.simpleName}: ${e.message}" else what
        Log.w(TAG, text); runCatching { keep('W', text) }
    }
    fun i(what: String) { Log.i(TAG, what); runCatching { keep('I', what) } }

    /** The last [n] lines, oldest first. */
    fun recent(n: Int = KEEP): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    /** Test only (am start --ez timing true): logs how long each slide takes to be ready. */
    @Volatile var timing = false
}
