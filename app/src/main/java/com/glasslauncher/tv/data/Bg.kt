package com.glasslauncher.tv.data

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Background work that belongs to the app, not to a screen: the small servers, the watchers, a request sent off the
 * main thread. One scope for all of it (these used to be threads started by hand, each on its own), so every piece
 * has a name, runs on the shared input/output threads, and an error nobody caught is written to the log instead of
 * ending the app or vanishing.
 */
object Bg {
    private val uncaught = CoroutineExceptionHandler { c, e -> Glog.w("Background work \"${c[CoroutineName]?.name ?: "?"}\" stopped", e) }

    /** Lives as long as the app. Work that blocks (network, files, the TV's shell) is what it is for. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + uncaught)

    /**
     * Starts [block] under [name] (the name shows in the log if it fails). The block has no receiver of its own, so
     * `this` inside it is still the caller's (an activity, the app), as it was in the thread it replaces.
     */
    fun io(name: String, block: suspend () -> Unit): Job = scope.launch(CoroutineName(name)) { block() }
}
