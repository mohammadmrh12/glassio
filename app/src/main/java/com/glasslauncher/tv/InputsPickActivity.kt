package com.glasslauncher.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.data.Inputs
import com.glasslauncher.tv.ui.GlassEnv
import com.glasslauncher.tv.ui.GlassPanel
import com.glasslauncher.tv.ui.LocalGlassEnv
import com.glasslauncher.tv.ui.LocalGlassEnvRef
import com.glasslauncher.tv.ui.Plain
import com.glasslauncher.tv.ui.Text
import com.glasslauncher.tv.ui.tvPress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The TV's inputs from a remote button, over whatever is on screen (another app, Glassio): a small glass panel with
 * each input as a card; OK switches to it, Back or the same button again closes it. Its glass is on Glassio's own
 * lights, like the What's new tour's (his choice): the same look over any app.
 */
class InputsPickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showing = this
        val app = application as GlassApp
        setContent { com.glasslauncher.tv.ui.GlassType { InputsPick(app) { close() } } }
    }

    fun close() { if (!isFinishing) { finish(); @Suppress("DEPRECATION") overridePendingTransition(0, android.R.anim.fade_out) } }

    override fun onDestroy() { if (showing === this) showing = null; super.onDestroy() }

    override fun onStop() { super.onStop(); if (!isChangingConfigurations) close() }

    companion object {
        @Volatile var showing: InputsPickActivity? = null

    }
}

@Composable
private fun InputsPick(app: GlassApp, close: () -> Unit) {
    val st = app.settings
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val inputs = remember { Inputs.forPage(st) }
    val shown by animateFloatAsState(1f, tween(180), label = "inputsIn")
    val first = remember { FocusRequester() }
    LaunchedEffect(inputs.size) { repeat(10) { if (runCatching { first.requestFocus() }.getOrDefault(false)) return@LaunchedEffect; kotlinx.coroutines.delay(40) } }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val s = maxWidth.value / 960f
        val w = constraints.maxWidth; val h = constraints.maxHeight
        val env = remember(w, h) {
            val pic = com.glasslauncher.tv.ui.orbsPicture(480, 270, bright = true).asImageBitmap()
            GlassEnv(pic, pic, pic, w, h, st.glassTint, st.refraction, blurDp = st.blurRadius * 0.45f, ignoreVideo = true)
        }
        val ref = rememberUpdatedState(env)
        CompositionLocalProvider(LocalGlassEnv provides env, LocalGlassEnvRef provides ref) {
            GlassPanel(Modifier.graphicsLayer { alpha = shown }, radius = (24 * s).dp, style = "liquid", darken = 0.08f) {
                Column(Modifier.padding(horizontal = (22 * s).dp, vertical = (18 * s).dp)) {
                    Text("TV inputs", color = Color.White.copy(alpha = 0.7f), fontSize = (11 * s).sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height((12 * s).dp))
                    if (inputs.isEmpty()) Text("No TV inputs found", color = Color.White, fontSize = (13 * s).sp)
                    else Row(horizontalArrangement = Arrangement.spacedBy((12 * s).dp)) {
                        inputs.forEachIndexed { i, inp ->
                            InputCard(app, inp, s, if (i == 0) Modifier.focusRequester(first) else Modifier) {
                                close(); Inputs.open(ctx, Inputs.PREFIX + inp.id)
                            }
                        }
                    }
                }
            }
        }
    }
    androidx.activity.compose.BackHandler { close() }
}

@Composable
private fun InputCard(app: GlassApp, inp: Inputs.Input, s: Float, modifier: Modifier, onOpen: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val k by animateFloatAsState(if (f) 1.08f else 1f, tween(140), label = "inputCard")
    val pic = remember(inp.id) {
        app.apps.ordered(includeHidden = true).firstOrNull { it.pkg == Inputs.PREFIX + inp.id }?.let { app.apps.cachedArtwork(it)?.bmp }
    }
    val shape = RoundedCornerShape((12 * s).dp)
    Column(modifier.width((132 * s).dp).onFocusChanged { f = it.isFocused }.tvPress(onClick = onOpen).graphicsLayer { scaleX = k; scaleY = k },
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size((132 * s).dp, (74 * s).dp).clip(shape).background(Color.White.copy(alpha = if (f) 0.22f else 0.08f))
            .border((if (f) 1.2f else 0.6f).dp, Color.White.copy(alpha = if (f) 0.85f else 0.18f), shape), contentAlignment = Alignment.Center) {
            if (pic != null) Image(pic, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Plain(inp.label, color = Color.White, fontSize = (13 * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = (8 * s).dp))
        }
        Spacer(Modifier.height((6 * s).dp))
        Plain(inp.label, color = Color.White.copy(alpha = if (f) 1f else 0.7f), fontSize = (10 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
