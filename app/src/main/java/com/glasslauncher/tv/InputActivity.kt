package com.glasslauncher.tv

import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.glasslauncher.tv.data.Glog

/**
 * An HDMI input shown by Glassio itself, for TVs without a TV app that opens inputs (AOSP-based TVs such as the
 * Chinese boards with "Wisdom" UIs: Google's Live TV isn't there, so asking Android to open an input did nothing).
 * The input plays full screen in a TvView, the standard way TV apps show it; Back or Home comes back to Glassio.
 */
class InputActivity : ComponentActivity() {
    private var tv: TvView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_INPUT)
        if (id.isNullOrBlank()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        val view = TvView(this)
        tv = view
        setContentView(view)
        view.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String) { fail("couldn't connect") }
            override fun onDisconnected(inputId: String) { fail("disconnected") }
            override fun onVideoUnavailable(inputId: String, reason: Int) {
                // Nothing plugged in or the device asleep: said once, the input stays (it may wake up).
                if (reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN ||
                    reason == android.media.tv.TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL)
                    android.widget.Toast.makeText(this@InputActivity, "No picture on this input yet", android.widget.Toast.LENGTH_SHORT).show()
            }
        })
        runCatching { view.tune(id, TvContract.buildChannelUriForPassthroughInput(id)) }
            .onFailure { Glog.w("Input $id: own player", it); fail(it.message ?: "couldn't open it") }
    }

    private fun fail(why: String) {
        if (isFinishing) return
        Glog.w("Input: $why")
        android.widget.Toast.makeText(this, "This TV didn't open the input ($why)", android.widget.Toast.LENGTH_LONG).show()
        finish()
    }

    /** The remote goes to the device on the input (over HDMI-CEC, as the TV passes it on); Back comes home. */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !event.isLongPress) { finish(); return true }
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        if (tv?.dispatchKeyEvent(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        runCatching { tv?.reset() }
        if (!isChangingConfigurations) finish()
    }

    companion object { const val EXTRA_INPUT = "input" }
}
