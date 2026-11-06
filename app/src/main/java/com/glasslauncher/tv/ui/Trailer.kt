package com.glasslauncher.tv.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.Http

/**
 * Trailers in the carousel, like the Apple TV app: the title's trailer plays in place of its picture (filling the
 * screen, at its own size, or in a small glass window next to the buttons). The trailer is the title's YouTube
 * trailer as Cinemeta lists it, played by YouTube's own embedded player (a WebView that never takes the focus).
 */
object Trailers {
    data class Req(val key: String, val ytId: String, val rev: Int)

    /** The trailer playing now, or null. */
    val playing = mutableStateOf<Req?>(null)
    /** Bumped when a trailer ends on its own (the carousel then moves on). */
    val endedRev = mutableIntStateOf(0)
    private var rev = 0
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The title's YouTube trailer id (Cinemeta: trailerStreams, then trailers), or null. Blocking. */
    fun youtubeId(http: Http, type: String, imdb: String): String? {
        if (!imdb.startsWith("tt")) return null
        cache[imdb]?.let { return it.ifBlank { null } }
        val t = if (type.equals("movie", true)) "movie" else "series"
        val id = runCatching {
            val meta = http.json("https://v3-cinemeta.strem.io/meta/$t/$imdb.json", 7L * 24 * 3600 * 1000).optJSONObject("meta") ?: return@runCatching null
            meta.optJSONArray("trailerStreams")?.let { a -> (0 until a.length()).firstNotNullOfOrNull { a.optJSONObject(it)?.optString("ytId")?.takeIf { s -> s.isNotBlank() } } }
                ?: meta.optJSONArray("trailers")?.let { a -> (0 until a.length()).firstNotNullOfOrNull { a.optJSONObject(it)?.optString("source")?.takeIf { s -> s.isNotBlank() } } }
        }.getOrNull()
        cache[imdb] = id.orEmpty()
        return id
    }

    fun play(key: String, ytId: String) { playing.value = Req(key, ytId, ++rev) }
    fun stop() { playing.value = null }
    fun isPlaying(key: String?) = key != null && playing.value?.key == key
}

private class TrailerBridge(val onState: (Int) -> Unit, val onError: (Int) -> Unit) {
    @JavascriptInterface fun state(s: Int) = onState(s)
    @JavascriptInterface fun error(e: Int) = onError(e)
}

/**
 * The trailer layer of the home screen. [mode]: "cinema" fills the screen (cropped), "original" shows the whole
 * picture (black bars), "popup" is a small glass window next to the Play button.
 */
@Composable
fun TrailerLayer(app: GlassApp, s: Float) {
    val req = Trailers.playing.value ?: return
    val st = app.settings
    val mode = st.trailerSize
    val alpha = remember(req.rev) { Animatable(0f) }
    // Shown once it really plays (YouTube's own loading screen stays hidden).
    var started by remember(req.rev) { mutableStateOf(false) }
    // A moment after it starts: YouTube's title and pause marks of the first second stay unseen.
    LaunchedEffect(started) { if (started) { kotlinx.coroutines.delay(1300); alpha.animateTo(1f, tween(600)) } }
    // Never stuck on a trailer that doesn't start.
    LaunchedEffect(req.rev) { kotlinx.coroutines.delay(15_000); if (!started && Trailers.playing.value?.rev == req.rev) Trailers.stop() }
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val view = @Composable { m: Modifier, fill: Boolean ->
        AndroidView(
            factory = { c -> trailerWebView(c, req.ytId, st.trailerMute, st.trailerSubs, st.trailerSubLang, fill,
                onState = { stt -> main.post {
                    if (Trailers.playing.value?.rev != req.rev) return@post
                    if (stt == 1) started = true
                    if (stt == 0) { Trailers.stop(); Trailers.endedRev.intValue++ }
                } },
                onError = { e -> main.post { Glog.w("Trailer ${req.ytId}: YouTube error $e"); if (Trailers.playing.value?.rev == req.rev) Trailers.stop() } }) },
            modifier = m,
            onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.destroy() },
        )
    }
    if (mode == "popup") {
        val anchor = PopAnchors.play
        val density = LocalDensity.current
        val w = (300 * s * st.trailerPopupScale).dp
        val h = w * 9f / 16f
        val x = with(density) { ((anchor?.right ?: (48 * s).dp.toPx()) + (18 * s).dp.toPx()).toInt() }
        val y = with(density) { ((anchor?.center?.y ?: (200 * s).dp.toPx()) - h.toPx() / 2f).toInt().coerceAtLeast((20 * s).dp.roundToPx()) }
        val shape = RoundedCornerShape((14 * s).dp)
        Box(Modifier.offset { IntOffset(x, y) }.size(w, h).graphicsLayer { this.alpha = alpha.value }
            .clip(shape).background(Color.Black).border((1 * s).dp, Color.White.copy(alpha = 0.35f), shape)) {
            androidx.compose.runtime.key(req.rev) { view(Modifier.fillMaxSize(), false) }
        }
    } else {
        Box(Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }.background(Color.Black)) {
            androidx.compose.runtime.key(req.rev) { view(Modifier.fillMaxSize(), mode == "cinema") }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun trailerWebView(
    c: android.content.Context, id: String, mute: Boolean, subs: Boolean, lang: String, fill: Boolean,
    onState: (Int) -> Unit, onError: (Int) -> Unit,
): WebView = WebView(c).apply {
    isFocusable = false
    isFocusableInTouchMode = false
    setBackgroundColor(android.graphics.Color.BLACK)
    settings.javaScriptEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.domStorageEnabled = true
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        // One tap on the page (not on the player) once it has loaded: the page then counts as touched, so the
        // trailer may get its sound (without it, turning the sound on paused the trailer).
        override fun onPageFinished(v: WebView, url: String?) {
            v.postDelayed({
                val t = android.os.SystemClock.uptimeMillis()
                listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP).forEach { a ->
                    val e = android.view.MotionEvent.obtain(t, t, a, 2f, 2f, 0)
                    v.dispatchTouchEvent(e); e.recycle()
                }
            }, 300)
        }
    }
    addJavascriptInterface(TrailerBridge(onState, onError), "Glassio")
    // The player is sized by script from the real window size: "cover" crops it to fill like a backdrop,
    // otherwise the whole picture shows (centred, black bars). Started muted (allowed to play by itself), then
    // given its sound once it plays.
    val html = """
        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{margin:0;padding:0;background:#000;overflow:hidden;width:100%;height:100%}#p{position:absolute;pointer-events:none;border:0}</style></head>
        <body><div id="p"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
        var player, cover=${if (fill) "true" else "false"}, wantSound=${if (mute) "false" else "true"}, sounded=false;
        function fit(){ var f=document.getElementById('p'); if(!f) return; var W=window.innerWidth, H=window.innerHeight; if(!W||!H) return;
          var w=W, h=W*9/16; if(cover){ if(h<H){ h=H; w=H*16/9; } w*=1.08; h*=1.08; } else { if(h>H){ h=H; w=H*16/9; } }
          f.style.width=w+'px'; f.style.height=h+'px'; f.style.left=((W-w)/2)+'px'; f.style.top=((H-h)/2)+'px'; }
        window.addEventListener('resize', fit);
        function onYouTubeIframeAPIReady(){
          player = new YT.Player('p',{videoId:'$id',width:'100%',height:'100%',
            playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,rel:0,playsinline:1,iv_load_policy:3,modestbranding:1,mute:1,
              cc_load_policy:${if (subs) 1 else 0},cc_lang_pref:'$lang',hl:'$lang',origin:'https://glassio.app'},
            events:{
              onReady:function(e){ fit(); e.target.mute(); e.target.playVideo(); },
              onStateChange:function(e){
                if(e.data==1 && wantSound && !sounded){ sounded=true; e.target.unMute(); e.target.setVolume(100); }
                // Nobody pauses it here: a pause (turning the sound on can cause one) is undone at once.
                if(e.data==2){ setTimeout(function(){ e.target.playVideo(); }, 50); }
                Glassio.state(e.data); },
              onError:function(e){ Glassio.error(e.data); }
            }});
          fit();
        }
        </script></body></html>
    """.trimIndent()
    loadDataWithBaseURL("https://glassio.app/", html, "text/html", "utf-8", null)
}
