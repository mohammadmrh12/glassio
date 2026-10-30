package com.glasslauncher.tv.saver

import android.service.dreams.DreamService
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.glasslauncher.tv.GlassApp

/**
 * Glassio as the TV's own screensaver (Settings > Screensaver > "Glassio is the TV screensaver"): the TV starts it
 * when idle in any app, not only on the home screen. It shows exactly what Glassio's screensaver shows.
 */
class GlassDreamService : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        saved.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Interactive: ◀ ▶ change the video and OK shows / hides the info; any other key wakes the TV.
        isInteractive = true
        isFullscreen = true
        isScreenBright = true
        val app = application as GlassApp
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@GlassDreamService)
            setViewTreeSavedStateRegistryOwner(this@GlassDreamService)
            setContent { com.glasslauncher.tv.ui.GlassType { SaverContent(app) } }
        }
        window?.decorView?.let { it.setViewTreeLifecycleOwner(this); it.setViewTreeSavedStateRegistryOwner(this) }
        setContentView(view)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val k = event.keyCode
        if (k in setOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER)) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN && event.repeatCount == 0) SaverControl.key(k)
            return true
        }
        if (event.action == android.view.KeyEvent.ACTION_DOWN) wakeUp()
        return true
    }
    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean { wakeUp(); return true }

    override fun onDreamingStarted() { super.onDreamingStarted(); registry.currentState = Lifecycle.State.RESUMED }
    override fun onDreamingStopped() { registry.currentState = Lifecycle.State.CREATED; super.onDreamingStopped() }
    override fun onDestroy() { registry.currentState = Lifecycle.State.DESTROYED; super.onDestroy() }
}
