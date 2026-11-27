// A song's words in time with it, beside the player (see data/Lyrics.kt): the look of Apple Music's lyrics view.
package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.data.Lyrics
import com.glasslauncher.tv.data.NowPlaying
import kotlinx.coroutines.delay

/** QA (dev key, `--ei lyrics_blur N`): 0 = the other lines only dimmed, anything else = out of focus too (as shipped). In memory. */
object LyricsQa { val blur = androidx.compose.runtime.mutableIntStateOf(2) }

/** How soft a line is by how far it is from the one being sung (a shadow's blur, in dp at the player's size). */
private val SOFT = floatArrayOf(0f, 1.5f, 2.6f, 3.8f)

/**
 * The words light a moment ahead of the voice (reading is slower than hearing; the app's position is a little late
 * too): more for a line whose words light by guess than for words that each have their own moment.
 */
private const val LEAD_MS = 300L
private const val LEAD_WORD_MS = 120L

/**
 * The words of [song], following [tr]: the line being sung white and solid, lighting word by word; the others dim,
 * dimmer the further away; the list moving up so that line stays in the upper part; three dots that fill while the
 * music plays before the first words and in a pause between them. Words written right to left stand on the right.
 *
 * A source that times each word (NetEase, KuGou) lights each as it is sung. One that times lines only (LRCLIB):
 * within a line the words light in turn, each for a time by its length, which follows the voice closely on even
 * lines and less so on a line with a long held note.
 *
 * Nothing here runs once a frame: where the song is, is looked at 12 times a second, and the page is only drawn
 * again when a word or a line changes (and while the list moves up to the next line).
 */
@Composable
fun LyricsPane(tr: NowPlaying.Track, song: Lyrics.Song, k: Float, modifier: Modifier = Modifier, soft: Boolean = true) {
    val lines = song.lines
    // The lines not being sung are out of focus, more so the further away (Android 12+; [soft] off on a weak TV).
    val blurMode = LyricsQa.blur.intValue
    val blurs = soft && android.os.Build.VERSION.SDK_INT >= 31 && blurMode > 0
    val px = androidx.compose.ui.platform.LocalDensity.current.density * k
    // The intro's dots are a row of their own before the first line (when the song opens with music).
    val intro = lines.isNotEmpty() && lines[0].at > 2_500 && song.synced
    var cur by remember(song) { mutableIntStateOf(-1) }
    var sung by remember(song) { mutableIntStateOf(0) }
    val wait = remember(song) { mutableFloatStateOf(0f) }
    val words = remember(song) { lines.map { l -> l.text.split(' ').filter { it.isNotBlank() } } }
    // A line in the parts that light one after the other: its words as the source cut them (their spaces with
    // them), else its words between spaces.
    val parts = remember(song) { lines.mapIndexed { i, l -> l.words?.map { it.text } ?: words[i].let { ws -> ws.mapIndexed { n, w -> if (n < ws.size - 1) "$w " else w } } } }
    // (Words written right to left: the dots stand on the right too, where the first line begins.)
    val rtl = remember(song) { lines.firstOrNull { it.text.isNotBlank() }?.text?.firstOrNull { Character.isLetter(it) }?.let {
        val dir = Character.getDirectionality(it); dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT || dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC } == true }
    // A line with no words is a pause: its dots show only when it is long enough to read as one (3 s), and never
    // after the last words (many songs' words end with an empty line: the dots stood there, full, to the song's end).
    val pause = remember(song) { BooleanArray(lines.size) { i -> lines[i].text.isBlank() && i < lines.size - 1 && lines[i + 1].at - lines[i].at >= 3_000 } }
    LaunchedEffect(song, tr.key, tr.playing, tr.position, tr.at) {
        while (true) {
            val pos = tr.positionNow() + if (song.byWord) LEAD_WORD_MS else LEAD_MS
            val (i, f) = Lyrics.at(lines, pos)
            cur = i
            sung = if (i < 0 || !song.synced) Int.MAX_VALUE else lines[i].words?.count { it.at <= pos } ?: Lyrics.wordsSung(words[i], f)
            // (How far through a wait: before the first line, or in a pause.)
            // (A pause's own length, however long: [Lyrics.at] counts a line as 9 s at most.)
            wait.floatValue = if (i < 0) (pos.toFloat() / lines[0].at.coerceAtLeast(1)).coerceIn(0f, 1f)
                else if (pause[i]) ((pos - lines[i].at).toFloat() / (lines[i + 1].at - lines[i].at)).coerceIn(0f, 1f) else 0f
            if (!tr.playing) break
            delay(80)
        }
    }
    val list = rememberLazyListState()
    val row = (if (intro) 1 else 0) + cur.coerceAtLeast(if (intro) -1 else 0)
    LaunchedEffect(row, song) { runCatching { list.animateScrollToItem(row.coerceAtLeast(0)) } }
    BoxWithConstraints(modifier
        // The top and the bottom fade out, so lines come and go softly (one layer for the whole list).
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.10f to Color.Black, 0.72f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
        }) {
        // The line being sung stands a fifth of the way down.
        LazyColumn(Modifier.fillMaxSize(), state = list, userScrollEnabled = false,
            contentPadding = PaddingValues(top = maxHeight * 0.2f, bottom = maxHeight * 0.8f)) {
            if (intro) item(key = "intro") { Dots(k, rtl, shown = cur < 0, fill = { wait.floatValue }) }
            items(lines.size, key = { it }) { i ->
                val line = lines[i]
                val d = i - cur
                if (line.text.isBlank()) { Dots(k, rtl, shown = d == 0 && pause[i], fill = { wait.floatValue }); return@items }
                // (Dimmer the further from the line being sung; the ones already sung a little dimmer than those coming.)
                val a by animateFloatAsState(when { d == 0 -> 1f; !song.synced -> 0.78f; d == 1 -> 0.5f; d == 2 -> 0.38f; d > 2 -> 0.28f; d == -1 -> 0.34f; else -> 0.22f },
                    tween(380), label = "lyric")
                // How far out of focus: 0 (the line being sung) to 3.
                val level = if (!blurs || !song.synced) 0 else kotlin.math.abs(d).coerceAtMost(3)
                val base = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = TextDirection.Content)
                Box(Modifier.fillMaxWidth().padding(horizontal = (8 * k).dp, vertical = (9 * k).dp)) {
                    val sharp by animateFloatAsState(if (level == 0) 1f else 0f, tween(380), label = "lyricSharp")
                    val text = if (d == 0 && song.synced) buildAnnotatedString {
                        parts[i].forEachIndexed { n, w -> withStyle(SpanStyle(color = Color.White.copy(alpha = if (n < sung) 1f else 0.42f))) { append(w) } }
                    } else buildAnnotatedString { append(line.text) }
                    // The words themselves, sharp. Always laid out (the line's size is theirs), drawn only while in focus.
                    Text(text, color = Color.White, fontSize = (22 * k).sp, lineHeight = (28 * k).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start,
                        style = base.copy(shadow = InkShadow),
                        modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = a * sharp; compositingStrategy = CompositingStrategy.ModulateAlpha })
                    // Out of focus: the same words drawn as nothing but a soft white shadow of themselves (their own
                    // colour clear), over a wider dark one so they still read on a bright picture. A letter's shadow is
                    // kept ready like the letter itself, so this costs what plain writing costs; a blur of each line
                    // as a layer (RenderEffect) was made again at every frame: 100-140 ms a frame on the Streamer.
                    // One fixed softness a level, cross-faded: a softness that changes would make every letter anew.
                    for (lv in 1..3) {
                        val w by animateFloatAsState(if (level == lv) 1f else 0f, tween(380), label = "lyricSoft")
                        if (w > 0.01f) {
                            val r = SOFT[lv] * px
                            for (sh in 0..1) Text(line.text, color = Color.Transparent, fontSize = (22 * k).sp, lineHeight = (28 * k).sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Start,
                                // (A shadow colour that isn't fully opaque keeps its own strength under clear writing.)
                                style = base.copy(shadow = if (sh == 0) androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.55f), Offset(0f, 2f), 6f + r)
                                    else androidx.compose.ui.graphics.Shadow(Color.White.copy(alpha = 0.99f), Offset.Zero, r)),
                                modifier = Modifier.matchParentSize().graphicsLayer { alpha = a * w; compositingStrategy = CompositingStrategy.ModulateAlpha })
                        }
                    }
                }
            }
        }
    }
}

/**
 * The three dots of a wait: they fill one after the other as the wait goes by ([fill], 0..1, read while drawing), and
 * take no room once the words are on.
 */
@Composable
private fun Dots(k: Float, rtl: Boolean, shown: Boolean, fill: () -> Float) {
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(300), label = "dots")
    if (a < 0.01f) { Spacer(Modifier.height((4 * k).dp)); return }
    Box(Modifier.fillMaxWidth().padding(horizontal = (8 * k).dp).height((34 * k).dp).graphicsLayer { alpha = a }, contentAlignment = if (rtl) androidx.compose.ui.Alignment.CenterEnd else androidx.compose.ui.Alignment.CenterStart) {
        Canvas(Modifier.size((70 * k).dp, (34 * k).dp)) {
            val p = fill()
            val r = 5.2f * k * density
            for (i in 0 until 3) {
                // (Each dot comes up over its third of the wait; all three swell a little towards the end.)
                val on = ((p * 3f) - i).coerceIn(0f, 1f)
                val swell = 1f + 0.18f * ((p - 0.85f) / 0.15f).coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = 0.28f + 0.72f * on), r * (0.82f + 0.18f * on) * swell, Offset(r * 1.4f + i * r * 3.1f, size.height / 2f))
            }
        }
    }
}

/** Whether the screensaver shows the song's words now (its place info then keeps out of their corner). */
fun saverLyricsShown(app: com.glasslauncher.tv.GlassApp): Boolean {
    val st = app.settings
    if (com.glasslauncher.tv.data.Store.PLAY || !st.lyricsOn || !st.lyricsSaver || !st.musicOn) return false
    val tr = NowPlaying.track.value ?: return false
    return tr.playing && Lyrics.shown.value?.takeIf { it.first == Lyrics.keyOf(tr.title, tr.artist) }?.second?.lines?.isNotEmpty() == true
}

/**
 * The song's words over the video screensaver (*Lyrics* with *On the video screensaver*; never the posters or the
 * trailers): on the right, under the large clock when it shows, while the song plays. Asked for here as the song
 * changes, so they come without the player ever having been opened.
 */
@Composable
fun SaverLyrics(app: com.glasslauncher.tv.GlassApp) {
    val st = app.settings
    if (com.glasslauncher.tv.data.Store.PLAY || !st.lyricsOn || !st.lyricsSaver || !st.musicOn) return
    val t by NowPlaying.track.collectAsState()
    val tr = t ?: return
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(tr.title, tr.artist, tr.duration > 0, st.lyricsSourcesOff) { Lyrics.want(ctx, tr.title, tr.artist, tr.duration) }
    val song = Lyrics.shown.value?.takeIf { it.first == Lyrics.keyOf(tr.title, tr.artist) }?.second?.takeIf { it.lines.isNotEmpty() } ?: return
    val a by animateFloatAsState(if (tr.playing) 1f else 0f, tween(600), label = "saverLyrics")
    if (a < 0.01f) return
    BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { alpha = a }) {
        val s = maxWidth.value / 960f
        // Under the clock (which moves down below the island while a song plays), else under the island.
        val top = if (st.saverBigClock && com.glasslauncher.tv.saver.SaverControl.clockShown.value) (clockEnds(st) + 0.12f).coerceIn(0.3f, 0.62f) else 0.16f
        LyricsPane(tr, song, s * 0.95f, soft = !st.lean, modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd)
            .fillMaxWidth(0.45f).fillMaxHeight(1f - top).padding(end = (44 * s).dp, bottom = (26 * s).dp))
    }
}
