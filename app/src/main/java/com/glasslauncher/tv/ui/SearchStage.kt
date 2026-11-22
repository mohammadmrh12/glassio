// The dock's Search icon: its page (the search bar and the titles found) in the carousel's place.
package com.glasslauncher.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.core.graphics.drawable.toBitmap
import coil3.compose.AsyncImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.Glog
import com.glasslauncher.tv.data.HeroItem
import com.glasslauncher.tv.data.I18n
import com.glasslauncher.tv.data.MediaSearch
import com.glasslauncher.tv.data.NuvioLaunch
import com.glasslauncher.tv.data.Search
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The dock's Search icon and its page above the dock (like the Inputs icon's inputs). */
object SearchUi {
    const val DOCK_PKG = "search:all"
    val on = mutableStateOf(false)
    /** Its page is up (not behind the open dock, the folder or another screen). */
    val page = mutableStateOf(false)
    @Volatile var focused = false
    /** The search bar. */
    val entry = FocusRequester()
    /** What is typed: kept while Glassio runs, so coming back to the icon shows the same titles. */
    val query = mutableStateOf("")
    /** A list of this page is open over it, or was a moment ago: the page stays though nothing in it is selected. */
    @Volatile var busy = false
    @Volatile var holdUntil = 0L
    fun held() = busy || android.os.SystemClock.uptimeMillis() < holdUntil
    /** The selection is only passing over the Search icon (walking along the dock): its page waits, the island stays. */
    val passing = mutableStateOf(false)
    /** OK on the icon: straight into typing. */
    val type = mutableIntStateOf(0)
    /** Asked for from outside its icon (the menu's row, a remote button): the page comes up and the bar is selected. */
    @Volatile var pending = false
    val want = mutableIntStateOf(0)
    fun open(st: com.glasslauncher.tv.data.Settings) {
        pending = true; holdUntil = android.os.SystemClock.uptimeMillis() + 3000
        if (inPlace(st)) {
            // The page of the app that was selected in the dock goes (its rows, the music apps, the inputs): the
            // search came up over an app's rows, both drawn, and the selection went back into the rows under it.
            ChannelsUi.stagePkg.value = null; MusicUi.appsOn.value = false; InputsUi.on.value = false
            on.value = true
        } else full.value = true
        want.intValue++
    }
    /**
     * The page has the carousel's place above the dock. Not with the dock in the middle or as a column (your own
     * background): nothing comes up above the dock there, so the search takes the whole screen ([full]) on OK.
     */
    fun inPlace(st: com.glasslauncher.tv.data.Settings) = st.bgMode == "carousel" || st.dockPos == "bottom"
    val full = mutableStateOf(false)

    /** A magnifying glass, drawn like the Inputs icon. */
    fun dockIcon(): android.graphics.Bitmap {
        val w = 640; val h = 360
        val b = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = 15f; strokeCap = android.graphics.Paint.Cap.ROUND }
        c.drawCircle(w * 0.475f, h * 0.44f, h * 0.21f, p)
        c.drawLine(w * 0.475f + h * 0.155f, h * 0.44f + h * 0.155f, w * 0.475f + h * 0.30f, h * 0.44f + h * 0.30f, p)
        return b
    }
}

/**
 * The Search icon's page: the bar, and under it the words searched before or what was found, in rows: films and
 * series as the Library's posters (with the small icons of the services' apps here that have each), YouTube's
 * videos, songs. OK on the bar types (the TV's own keyboard, with its voice key); OK on a title lists where it can
 * be watched (or opens a place at once: Settings → Search); a video or a song opens in its app.
 * ▲ ▼ go from row to row; ▼ from the last row and Back: the Search icon; ▲ from the bar: the menu. ◀ ▶ stay in the row.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun SearchStage(app: GlassApp, s: Float, bottom: Float, leave: () -> Unit, toMenu: () -> Unit) {
    val left = remember { arrayOfNulls<BooleanArray>(1) }
    val onDown: () -> Unit = { left[0]?.set(0, false); leave() }
    val onUp: () -> Unit = { left[0]?.set(0, false); toMenu() }
    val ctx = LocalContext.current
    val st = app.settings
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    var query by SearchUi.query
    var results by remember { mutableStateOf<List<HeroItem>>(emptyList()) }
    var videos by remember { mutableStateOf<List<MediaSearch.Clip>>(emptyList()) }
    var songs by remember { mutableStateOf<List<MediaSearch.Clip>>(emptyList()) }
    // More at the end of a row: the next page of each source, asked for as the selection nears the row's end.
    val tubePager = remember { arrayOfNulls<MediaSearch.Pager>(1) }
    val cloudPager = remember { arrayOfNulls<MediaSearch.Pager>(1) }
    val pages = remember { intArrayOf(1, 1) }           // the titles found, the popular ones
    val busyMore = remember { HashSet<String>() }
    val endMore = remember { HashSet<String>() }
    val onApps = remember { mutableStateMapOf<String, List<String>>() }
    val popularList = remember { mutableStateOf<List<HeroItem>>(emptyList()) }
    val popularKey = remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val field = remember { FocusRequester() }
    val back = remember { arrayOfNulls<FocusRequester>(1) }
    val scroll = rememberScrollState()
    val tubeApp = remember(st.searchYoutube, st.searchYoutubeApp) { if (st.searchYoutube && MediaSearch.AVAILABLE) Search.youtubeApp(ctx, st) else null }
    val spotify = remember(st.searchMusic, st.searchMusicOff) { Search.musicApp(ctx, st, "spotify", Search.SPOTIFY) }
    val soundcloud = remember(st.searchMusic, st.searchMusicOff) { MediaSearch.AVAILABLE && Search.musicApp(ctx, st, "soundcloud", Search.SOUNDCLOUD) }

    // What was found for the words, once the typing rests: the titles first, the videos and the songs as they come.
    LaunchedEffect(query, st.searchMovies, st.searchSeries, tubeApp, soundcloud) {
        val q = query.trim()
        if (q.length < 2) { results = emptyList(); videos = emptyList(); songs = emptyList(); asked = ""; note = ""; return@LaunchedEffect }
        if (q == asked) return@LaunchedEffect
        delay(280)
        note = "Searching…"
        val tp = if (tubeApp != null) MediaSearch.youtube(q) else null
        val cp = if (soundcloud) MediaSearch.soundcloud(q) else null
        val tube = async(Dispatchers.IO) { tp?.let { p -> runCatching { p.next() }.onFailure { Glog.w("Search: YouTube", it) }.getOrDefault(emptyList()) } ?: emptyList() }
        val cloud = async(Dispatchers.IO) { cp?.let { p -> runCatching { p.next() }.onFailure { Glog.w("Search: SoundCloud", it) }.getOrDefault(emptyList()) } ?: emptyList() }
        val got = withContext(Dispatchers.IO) { runCatching { Search.titles(app.http, st, q) }.onFailure { Glog.w("Search", it) }.getOrNull() }
        results = got ?: emptyList(); asked = q; videos = emptyList(); songs = emptyList()
        tubePager[0] = tp; cloudPager[0] = cp; pages[0] = 1; busyMore.clear(); endMore.remove("titles"); endMore.remove("videos"); endMore.remove("music")
        runCatching { scroll.scrollTo(0) }
        videos = tube.await(); songs = cloud.await()
        note = when { got == null && videos.isEmpty() && songs.isEmpty() -> "The search didn't answer"; results.isEmpty() && videos.isEmpty() && songs.isEmpty() && !spotify -> "Nothing found"; else -> "" }
    }
    // Which of the services' apps here have each title (the icons on the posters), a few at a time.
    LaunchedEffect(results, popularKey.value, st.searchMine, st.searchServices, st.searchServicesOff) {
        if (!st.searchMine || Search.services(ctx, st).isEmpty()) return@LaunchedEffect
        for (group in (results.ifEmpty { popularList.value }).take(40).chunked(6)) {
            group.map { item -> async(Dispatchers.IO) { item.key to (Search.watchOnKnown(st, item) ?: Search.watchOn(ctx, app.http, st, item)) } }
                .forEach { d -> d.await().let { (k, v) -> onApps[k] = v } }
        }
    }
    // OK on the dock's icon: into the bar, typing.
    // (Only a press made while the page is up: the count kept from an earlier visit opened the keyboard by itself.)
    val typeSeen = remember { intArrayOf(SearchUi.type.intValue) }
    LaunchedEffect(SearchUi.type.intValue) { if (SearchUi.type.intValue != typeSeen[0]) { typeSeen[0] = SearchUi.type.intValue; runCatching { SearchUi.entry.requestFocus() }; editing = true } }
    // Opened from the menu's row or a remote button: the bar is selected (asked for until it is there).
    LaunchedEffect(SearchUi.want.intValue) {
        if (!SearchUi.pending) return@LaunchedEffect
        repeat(25) { if (runCatching { SearchUi.entry.requestFocus() }.getOrDefault(false)) { SearchUi.pending = false; return@LaunchedEffect }; delay(40) }
        SearchUi.pending = false
    }
    LaunchedEffect(editing) {
        if (editing) { repeat(8) { if (runCatching { field.requestFocus() }.isSuccess) { keyboard?.show(); return@LaunchedEffect }; delay(30) } }
        else keyboard?.hide()
    }
    SearchUi.busy = dialog != null
    // Leaving the search: the bar is empty the next time (Settings → Search → Start with an empty bar).
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (st.searchFresh) SearchUi.query.value = "" } }
    // Titles in progress (Continue Watching), by kind and name: their progress shows on their cards here.
    val watching by app.cw.items.collectAsState()
    val progress = remember(watching) { watching.filter { it.progress > 0f }.associate { Search.nameKey(it.type, it.name) to it } }
    // New results come in with a short fade.
    val fade = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(results, videos.isEmpty(), songs.isEmpty()) { fade.snapTo(0.35f); fade.animateTo(1f, tween(200)) }
    // The selection is in this page until it leaves by ▼, ▲ or Back. Lost any other way (the card it was on went
    // away with new results, Glassio came back from another app), it goes to the bar: with nothing selected the
    // page closed and the carousel's button took the selection.
    val inPage = remember { booleanArrayOf(false) }
    left[0] = inPage
    LaunchedEffect(Unit) {
        while (true) {
            delay(200)
            if (SearchUi.focused) inPage[0] = true
            else if (inPage[0] && !editing && dialog == null) {
                SearchUi.holdUntil = android.os.SystemClock.uptimeMillis() + 1500
                if (!runCatching { SearchUi.entry.requestFocus() }.getOrDefault(false)) inPage[0] = false
            }
        }
    }
    fun close() { dialog = null; SearchUi.holdUntil = android.os.SystemClock.uptimeMillis() + 1500; scope.launch { delay(60); runCatching { (back[0] ?: SearchUi.entry).requestFocus() } } }

    /** Stremio with its sources: the add-ons are asked, then the list (or the first that fits) goes to Stremio. */
    fun sources(item: HeroItem) {
        if (com.glasslauncher.tv.data.Store.PLAY) { NuvioLaunch.openIn(ctx, "stremio", item); return }
        scope.launch {
            note = "Asking the add-ons…"
            SearchUi.holdUntil = android.os.SystemClock.uptimeMillis() + 20_000
            val found = withContext(Dispatchers.IO) { runCatching { Search.sources(app.http, st, app.catalogs.allAddons(), item) }.onFailure { Glog.w("Search: sources", it) }.getOrDefault(emptyList()) }
            note = ""
            fun play(src: Search.Source) = Search.playInStremio(ctx, src, item)
            when {
                found.isEmpty() -> { toast(ctx, "No source fits: opening its page in Stremio"); NuvioLaunch.openIn(ctx, "stremio", item) }
                st.stremioSources == "first" -> play(found.first())
                // (On the menus' glass, like every list of the home screen: it reads the page under it.)
                else -> dialog = PickDialog(item.name + "  ·  Stremio", "", found.take(40).map { src ->
                    PickRow(listOfNotNull(when { src.quality >= 2160 -> "4K"; src.quality > 0 -> "${src.quality}p"; else -> null }, src.bytes.takeIf { it > 0 }?.let { Search.sizeText(it) },
                        "HDR".takeIf { src.hdr }, "CAM".takeIf { src.cam }, "P2P".takeIf { src.p2p }).joinToString("  ·  ").ifBlank { "Source" }, src.addonName, { "" }) { close(); play(src) }
                }, st.menuStyle)
            }
        }
    }

    /** OK on a title: where it can be watched. */
    fun open(found: HeroItem) {
        Search.remember(st, query)
        scope.launch {
            note = "Looking where it can be watched…"
            SearchUi.holdUntil = android.os.SystemClock.uptimeMillis() + 8000
            val item = withContext(Dispatchers.IO) { Search.resolve(app.http, st, found) }
            if (item == null) { note = ""; toast(ctx, "${found.name} can't be opened: it has no IMDb number"); return@launch }
            val places = withContext(Dispatchers.IO) { withTimeoutOrNull(6000) { runCatching { Search.places(ctx, st, item) { sources(it) } }.onFailure { Glog.w("Search: places", it) }.getOrNull() } } ?: emptyList()
            note = ""
            if (places.isEmpty()) { toast(ctx, "No app here can open ${item.name}"); return@launch }
            // OK straight into a place: the first of the list, or the one app chosen (the list when that app can't open this title).
            when (st.searchOk) {
                "first" -> { places.first().open(); return@launch }
                "list" -> {}
                else -> places.firstOrNull { it.key == st.searchOk }?.let { it.open(); return@launch }
            }
            val rows = places.map { p -> PickRow(if (!p.service && p.key != "stremio" && st.searchNuvioPlay) I18n.tr("Play in") + " " + p.label else p.label, "", { if (p.sources) "›" else "" }) { close(); p.open() } }.toMutableList()
            if (!app.library.has(item.key)) rows += PickRow("Save to the Library", "", { "" }) { app.library.toggle(item); toast(ctx, I18n.tr("Saved") + " · ${item.name}"); close() }
            dialog = PickDialog(item.name, listOf(item.year, I18n.tr(if (item.type == "movie") "Film" else "Series")).filter { it.isNotBlank() }.joinToString("  ·  "), rows, st.menuStyle)
        }
    }

    // The titles shown: with "only on my services", the ones a service's app here is known to have.
    // Nothing typed: what is popular this week stands in the titles' row.
    LaunchedEffect(st.searchTrending, st.tmdbToken.isBlank()) {
        popularList.value = if (!st.searchTrending) emptyList() else withContext(Dispatchers.IO) { runCatching { Search.trending(app.http, st) }.onFailure { Glog.w("Search: popular", it) }.getOrDefault(emptyList()) }
        popularKey.intValue++
    }
    val popular = popularList.value
    val idle = query.trim().length < 2
    val shownTitles = if (idle) popular else results
    val titles = if (st.searchMine && !idle) shownTitles.filter { !onApps[it.key].isNullOrEmpty() } else shownTitles
    /** The next page of a row, added at its end. Five pages a row at most; a row that has no more is left alone. */
    fun more(row: String) {
        val key = if (row == "titles" && idle) "popular" else row
        if (!st.searchMore || key in busyMore || key in endMore) return
        busyMore.add(key)
        scope.launch {
            val q = asked
            runCatching {
                when (key) {
                    "popular" -> {
                        val page = pages[1] + 1
                        val got = withContext(Dispatchers.IO) { Search.trending(app.http, st, page) }
                        if (got.isEmpty()) endMore.add(key) else { pages[1] = page; popularList.value = (popularList.value + got).distinctBy { it.key }; if (page >= 5) endMore.add(key) }
                    }
                    "titles" -> {
                        val page = pages[0] + 1
                        val got = withContext(Dispatchers.IO) { Search.titles(app.http, st, q, page) }
                        if (q == asked) { if (got.isEmpty()) endMore.add(key) else { pages[0] = page; results = (results + got).distinctBy { it.key }; if (page >= 5) endMore.add(key) } }
                    }
                    else -> {
                        val pager = (if (key == "videos") tubePager else cloudPager)[0]
                        if (pager == null) endMore.add(key) else {
                            val got = withContext(Dispatchers.IO) { pager.next() }
                            if (q == asked && (if (key == "videos") tubePager else cloudPager)[0] === pager) {
                                if (key == "videos") videos = (videos + got).distinctBy { it.url } else songs = (songs + got).distinctBy { it.url }
                                if (got.isEmpty() || !pager.more || (if (key == "videos") videos else songs).size >= 100) endMore.add(key)
                            }
                        }
                    }
                }
            }.onFailure { Glog.w("Search: more $key", it); endMore.add(key) }
            Search.logMore(key, when (key) { "popular" -> popularList.value.size; "titles" -> results.size; "videos" -> videos.size; else -> songs.size }, key in endMore)
            busyMore.remove(key)
        }
    }
    val recent = st.searchHistory
    val showRecent = query.isBlank() && st.searchRecent && recent.isNotEmpty()
    // The rows in order, each with the place the selection enters it by; ▲ ▼ go to the one before and after.
    // (The three results' rows in the person's order: the one chosen first, the others as they were.)
    val order = when (st.searchFirstRow) { "youtube" -> listOf("videos", "titles", "music"); "music" -> listOf("music", "titles", "videos"); else -> listOf("titles", "videos", "music") }
    val rowKeys = buildList {
        if (showRecent) add("recent")
        for (k in order) when (k) {
            "titles" -> if (titles.isNotEmpty()) add("titles")
            "videos" -> if (videos.isNotEmpty() && tubeApp != null) add("videos")
            else -> if (query.trim().length >= 2 && (spotify || songs.isNotEmpty())) add("music")
        }
    }
    val entries = remember { HashMap<String, FocusRequester>() }
    val tops = remember { HashMap<String, Int>() }
    fun entry(k: String) = entries.getOrPut(k) { FocusRequester() }
    fun go(from: String, d: Int) {
        val i = rowKeys.indexOf(from) + d
        when {
            i < 0 -> runCatching { SearchUi.entry.requestFocus() }
            i > rowKeys.lastIndex -> onDown()
            else -> runCatching { entry(rowKeys[i]).requestFocus() }
        }
    }
    /** A row's keys: ▲ ▼ to the next row, ◀ ▶ stop at its ends (they went on to the menu's button). */
    // The page moves as the Library's does (its Glide: one movement after a target, never an animation a step).
    // The rows are built one a frame (titles, then videos, then songs): all in the page's first frame they held the
    // main thread for 80-125 ms, a visible catch in the fade to the search.
    var built by remember { mutableIntStateOf(0) }
    // And only once the selection has rested on the Search icon for a moment (or has come into the page): running
    // along the dock across the icon built the rows and threw them away again, frames of 110-214 ms each time
    // (37 at most between other icons). The bar comes at once; the rows a fifth of a second later.
    LaunchedEffect(Unit) {
        val t0 = android.os.SystemClock.uptimeMillis()
        while (android.os.SystemClock.uptimeMillis() - t0 < 220 && !SearchUi.focused && !SearchUi.pending && !SearchUi.full.value) delay(20)
        repeat(3) { androidx.compose.runtime.withFrameNanos { }; built++ }
    }
    val pageGlide = remember(scroll) { Glide(scroll, scope) }
    val lastMove = remember { longArrayOf(0L) }
    fun Modifier.rowKeys(key: String, sel: () -> Int, last: () -> Int, glide: Glide? = null) = this.onPreviewKeyEvent { ev ->
        val down = ev.type == KeyEventType.KeyDown
        val side = ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight
        // A held ◀ ▶: a card every 140 ms, the row moving steadily under it (the Library's beat).
        if (side) {
            val now = android.os.SystemClock.uptimeMillis()
            if (!down) { LibPace.held = false; LibPace.run = 0; glide?.release(); pageGlide.release() }
            else if (ev.nativeKeyEvent.repeatCount == 0) { LibPace.held = false; LibPace.run = 0; lastMove[0] = now }
            else {
                if (now - lastMove[0] < 140L) return@onPreviewKeyEvent true
                LibPace.period = (now - lastMove[0]).coerceIn(100, 400); lastMove[0] = now; LibPace.held = true; LibPace.run++
            }
        }
        when (ev.key) {
            Key.DirectionUp -> { if (down) go(key, -1); true }
            Key.DirectionDown -> { if (down) go(key, 1); true }
            Key.DirectionLeft -> sel() <= 0
            Key.DirectionRight -> sel() >= last()
            else -> false
        }
    }.onFocusChanged { f -> if (f.hasFocus) tops[key]?.let { y -> pageGlide.aim(((y - (4 * s * ctx.resources.displayMetrics.density)).coerceAtLeast(0f) - scroll.value).coerceAtMost((scroll.maxValue - scroll.value).toFloat()), false, 300) } }
        .onGloballyPositioned { tops[key] = it.positionInParent().y.toInt() }
    /** A row's own sideways movement: the Library's (it moves only when the next card would pass the margin). */
    @Composable fun Glided(list: androidx.compose.foundation.lazy.LazyListState, content: @Composable (Glide) -> Unit) {
        val g = remember(list) { Glide(list, scope) }
        val margin = with(LocalDensity.current) { (46 * s).dp.toPx() }
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides remember(g, margin) { RowScroll(margin, g) }) { content(g) }
    }

    CompositionLocalProvider(LocalGlow provides st.glow) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // (At the top: the island never shows with the search.)
        val top = (if (!st.topAutoHide && st.showClock) 64 else 24) * s
        val room = maxHeight.value - bottom - top
        // The titles' and the videos' cards as big as the room above the dock allows with both rows whole (his ask:
        // bigger, but never cut by the dock; the music row may sit under it until it is reached). What is not
        // cards: the bar (34), the gap under it (8), two headings with their rows' margins (38 + 34), a little air.
        val wideTitles = st.searchShape == "card"
        // (By the two rows that come first: the music row keeps its own size, the other one takes what is left.)
        val firstTwo = order.take(2)
        val cardsH = firstTwo.sumOf { (when (it) { "titles" -> if (wideTitles) 84 else 144; "videos" -> 84; else -> 0 }).toDouble() }.toFloat()
        val musicH = if ("music" in firstTwo) 96f + 34f else 0f
        val z = if (st.searchCardSize != "large") 1f else ((room / s - 34f - 8f - 72f - 6f - musicH) / cardsH).coerceIn(0.8f, 1.5f)
        // What is left under the two rows: when the Music heading would only show its top edge above the dock
        // (his photo), it is pushed down out of sight instead.
        val leftOver = room / s - 34f - 8f - 72f - cardsH * z
        val pushMusic = if (order.last() == "music" && leftOver < 24f) (leftOver + 4f).coerceAtLeast(0f) else 0f
        Box(Modifier.padding(top = top.dp).fillMaxWidth().height(room.dp), contentAlignment = Alignment.TopCenter) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize().onFocusChanged { SearchUi.focused = it.hasFocus }
                .onPreviewKeyEvent { ev -> if (ev.key == Key.Back && !editing) { if (ev.type == KeyEventType.KeyUp) onDown(); true } else false }) {
                // The bar: selected like any button; OK types in it.
                var barFocus by remember { mutableStateOf(false) }
                val lit = barFocus || editing
                val shape = RoundedCornerShape(50)
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.width((430 * s).dp).height((34 * s).dp).clip(shape)
                        .background(if (lit) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.14f))
                        .border((1 * s).dp, Color.White.copy(alpha = if (lit) 0f else 0.22f), shape)
                        .focusRequester(SearchUi.entry).onFocusChanged { barFocus = it.isFocused }
                        .tvPress(onKey = { ev ->
                            when (ev.key) {
                                Key.DirectionUp -> { if (ev.type == KeyEventType.KeyDown) onUp(); true }
                                Key.DirectionDown -> { if (ev.type == KeyEventType.KeyDown) { if (rowKeys.isEmpty()) onDown() else runCatching { entry(rowKeys.first()).requestFocus() } }; true }
                                Key.DirectionLeft, Key.DirectionRight -> true
                                else -> false
                            }
                        }) { editing = true }
                        .padding(horizontal = (14 * s).dp)) {
                    val ink = if (lit) Color(0xFF111114) else Color.White
                    Text("⌕", color = ink.copy(alpha = 0.7f), fontSize = (15 * s).sp)
                    Spacer(Modifier.width((8 * s).dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search", color = ink.copy(alpha = 0.5f), fontSize = (13 * s).sp, maxLines = 1)
                        BasicTextField(query, { query = it.take(60) }, singleLine = true, enabled = editing,
                            textStyle = TextStyle(color = ink, fontSize = (13 * s).sp), cursorBrush = SolidColor(ink),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { editing = false; runCatching { SearchUi.entry.requestFocus() } }),
                            modifier = Modifier.fillMaxWidth().focusRequester(field)
                                .onFocusChanged { if (!it.isFocused && editing) editing = false }
                                .onPreviewKeyEvent { ev ->
                                    // Out of the typing: Back (after the keyboard's own Back), or ▼ once it has gone.
                                    // (▼ goes straight on to the first row: it took one press to leave the typing and a second to move.)
                                    if (ev.key == Key.Back || ev.key == Key.DirectionDown || ev.key == Key.DirectionUp) {
                                        if (ev.type == KeyEventType.KeyUp) {
                                            editing = false
                                            val row = rowKeys.firstOrNull()?.takeIf { ev.key == Key.DirectionDown }
                                            if (row == null || !runCatching { entry(row).requestFocus() }.getOrDefault(false)) runCatching { SearchUi.entry.requestFocus() }
                                        }
                                        true
                                    } else false
                                })
                    }
                    if (query.isNotEmpty() && !editing) Text("OK types", color = ink.copy(alpha = 0.45f), fontSize = (9 * s).sp)
                }
                Spacer(Modifier.height((8 * s).dp))
                if (rowKeys.isEmpty() || note.isNotBlank()) Text(note.ifBlank { if (query.isBlank()) "OK types  ·  hold the microphone key to speak" else "" },
                    color = Color.White.copy(alpha = 0.5f), fontSize = (10 * s).sp)
                val side = (46 * s).dp
                @Composable fun heading(t: String) = Text(t, color = Color.White.copy(alpha = 0.7f), fontSize = (10.5f * s).sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().padding(start = side, top = (4 * s).dp))
                Column(Modifier.fillMaxWidth().weight(1f).graphicsLayer { alpha = fade.value; compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha }.verticalScroll(scroll)) {
                    if (showRecent) {
                        heading("Searched before")
                        val shown = recent.take(6)
                        val reqs = remember(shown) { List(shown.size) { FocusRequester() } }
                        var sel by remember { mutableIntStateOf(0) }
                        Row(horizontalArrangement = Arrangement.spacedBy((8 * s).dp),
                            modifier = Modifier.padding(start = side, top = (8 * s).dp, bottom = (8 * s).dp).focusRequester(entry("recent")).focusProperties { enter = { reqs.first() } }
                                .rowKeys("recent", { sel }, { shown.lastIndex }).focusGroup()) {
                            shown.forEachIndexed { i, q ->
                                var f by remember { mutableStateOf(false) }
                                Plain(q, color = if (f) Color(0xFF111114) else Color.White.copy(alpha = 0.85f), fontSize = (11 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = (150 * s).dp).clip(RoundedCornerShape(50)).background(if (f) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.12f))
                                        .focusRequester(reqs.at(i)).onFocusChanged { f = it.isFocused; if (it.isFocused) sel = i }
                                        .tvPress { query = q; runCatching { SearchUi.entry.requestFocus() } }
                                        .padding(horizontal = (12 * s).dp, vertical = (5 * s).dp))
                            }
                        }
                    }
                    val titlesRow: @Composable (Int) -> Unit = { pos -> if (titles.isNotEmpty() && built >= pos) {
                        heading(if (idle) "Popular this week" else "Films and series")
                        CompositionLocalProvider(LocalItemMenu provides { item, _ -> open(item) }, LocalGlow provides st.glow) {
                            val list = rememberLazyListState()
                            // (Kept while the row grows at its end: made again with every new page, the selection's
                            // place was forgotten.)
                            val reqs = remember(asked, idle) { ArrayList<FocusRequester>() }
                            reqs.growTo(titles.size)
                            var sel by remember(asked, idle) { mutableIntStateOf(0) }
                            Glided(list) { g -> LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy((14 * s).dp), contentPadding = PaddingValues(start = side, end = side, top = (10 * s).dp, bottom = (10 * s).dp),
                                modifier = Modifier.fillMaxWidth().focusRequester(entry("titles")).focusProperties { enter = { reqs.getOrNull(sel.coerceAtMost(reqs.lastIndex)) ?: FocusRequester.Default } }
                                    .rowKeys("titles", { sel }, { titles.lastIndex }, g).focusGroup()) {
                                itemsIndexed(titles, key = { _, it -> it.key }) { i, item ->
                                    val cw = progress[Search.nameKey(item.type, item.name)]
                                    // One faint line inside the picture: the year (or the episode in progress), Film / Series, the rating.
                                    val sub = listOf(cw?.sub?.takeIf { it.isNotBlank() } ?: item.year, I18n.tr(if (item.type == "movie") "Film" else "Series"),
                                        item.rating.takeIf { st.searchRating }.orEmpty()).filter { it.isNotBlank() }.joinToString("  ·  ")
                                    val wide = st.searchShape == "card"
                                    SearchCard(if (wide) item.background.ifBlank { item.poster } else item.poster.ifBlank { item.background }, item.name, sub, s,
                                        (if (wide) 150f else 96f) * z, if (wide) 16f / 9f else 2f / 3f, cw?.progress,
                                        Modifier.focusRequester(reqs.at(i)).onFocusChanged { if (it.hasFocus) { back[0] = reqs.at(i); sel = i; if (i >= titles.lastIndex - 3) more("titles") } }) { open(item) }
                                }
                            } }
                        }
                    } }
                    val videosRow: @Composable (Int) -> Unit = { pos -> if (videos.isNotEmpty() && tubeApp != null && built >= pos) {
                        heading("YouTube")
                        val reqs = remember(asked) { ArrayList<FocusRequester>() }
                        reqs.growTo(videos.size)
                        var sel by remember(asked) { mutableIntStateOf(0) }
                        val vlist = rememberLazyListState()
                        Glided(vlist) { g -> LazyRow(state = vlist, horizontalArrangement = Arrangement.spacedBy((14 * s).dp), contentPadding = PaddingValues(start = side, end = side, top = (8 * s).dp, bottom = (8 * s).dp),
                            modifier = Modifier.fillMaxWidth().focusRequester(entry("videos")).focusProperties { enter = { reqs.getOrNull(sel.coerceAtMost(reqs.lastIndex)) ?: FocusRequester.Default } }
                                .rowKeys("videos", { sel }, { videos.lastIndex }, g).focusGroup()) {
                            itemsIndexed(videos, key = { _, it -> it.url }) { i, c ->
                                ClipCard(c, s, 150f * z, 16f / 9f, Modifier.focusRequester(reqs.at(i)).onFocusChanged { if (it.hasFocus) { back[0] = reqs.at(i); sel = i; if (i >= videos.lastIndex - 3) more("videos") } }) {
                                    Search.remember(st, query); Search.openClip(ctx, tubeApp, c.url)
                                }
                            }
                        } }
                    } }
                    val musicRow: @Composable (Int) -> Unit = { pos -> if (query.trim().length >= 2 && (spotify || songs.isNotEmpty()) && built >= pos) {
                        if (pushMusic > 0f && titles.isNotEmpty() && videos.isNotEmpty() && tubeApp != null) Spacer(Modifier.height((pushMusic * s).dp))
                        heading("Music")
                        val n = songs.size + (if (spotify) 1 else 0)
                        val reqs = remember(asked, spotify) { ArrayList<FocusRequester>() }
                        reqs.growTo(n)
                        var sel by remember(asked) { mutableIntStateOf(0) }
                        val mlist = rememberLazyListState()
                        Glided(mlist) { g -> LazyRow(state = mlist, horizontalArrangement = Arrangement.spacedBy((14 * s).dp), contentPadding = PaddingValues(horizontal = side, vertical = (12 * s).dp),
                            modifier = Modifier.fillMaxWidth().focusRequester(entry("music")).focusProperties { enter = { reqs.getOrNull(sel.coerceAtMost(reqs.lastIndex)) ?: FocusRequester.Default } }
                                .rowKeys("music", { sel }, { n - 1 }, g).focusGroup()) {
                            // Spotify has no search open to others: its own search, with the words in it.
                            if (spotify) item(key = "spotify") {
                                var f by remember { mutableStateOf(false) }
                                val k by animateFloatAsState(if (f) 1.08f else 1f, tween(160), label = "spotify")
                                Column(Modifier.width((96 * s).dp)) {
                                    Box(Modifier.fillMaxWidth().aspectRatio(1f).focusRequester(reqs.at(0)).onFocusChanged { f = it.isFocused; if (it.isFocused) { back[0] = reqs.at(0); sel = 0 } }
                                        .tvPress { Search.remember(st, query); Search.openSpotify(ctx, query) }
                                        .graphicsLayer { scaleX = k; scaleY = k }.clip(RoundedCornerShape((8 * s).dp)).background(Color(0xFF1DB954).copy(alpha = if (f) 1f else 0.75f)),
                                        contentAlignment = Alignment.Center) { AppBadge(Search.SPOTIFY, s, 34f) }
                                    Plain("Spotify", color = Color.White.copy(alpha = if (f) 0.95f else 0.6f), fontSize = (9.5f * s).sp, maxLines = 1, modifier = Modifier.padding(top = (7 * s).dp))
                                    Text("Its own search", color = Color.White.copy(alpha = 0.4f), fontSize = (8 * s).sp, maxLines = 1)
                                }
                            }
                            itemsIndexed(songs, key = { _, it -> it.url }) { j, c ->
                                val i = j + (if (spotify) 1 else 0)
                                ClipCard(c, s, 96f, 1f, Modifier.focusRequester(reqs.at(i)).onFocusChanged { if (it.hasFocus) { back[0] = reqs.at(i); sel = i; if (j >= songs.lastIndex - 3) more("music") } }) {
                                    // The page is held while the song's app is in front and Glassio comes back by itself: back that soon the
                                    // selection wasn't in the page yet when the home screen looked, and the search was closed.
                                    SearchUi.holdUntil = android.os.SystemClock.uptimeMillis() + 15_000
                                    Search.remember(st, query); Search.playSong(ctx, Search.SOUNDCLOUD, c.url, c.title, st.searchMusicStay)
                                }
                            }
                        } }
                    } }
                    // (One row a frame, in the chosen order.)
                    order.forEachIndexed { n, k -> androidx.compose.runtime.key(k) { when (k) { "titles" -> titlesRow(n + 1); "videos" -> videosRow(n + 1); else -> musicRow(n + 1) } } }
                    Spacer(Modifier.height((10 * s).dp))
                }
            }
        }
    }
    }
    DialogHost(dialog) { close() }
}

/** An app's own icon, small and rounded. */
@Composable
private fun AppBadge(pkg: String, s: Float, size: Float = 15f) {
    val ctx = LocalContext.current
    val icon = remember(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() } ?: return
    Image(icon, null, Modifier.size((size * s).dp).clip(RoundedCornerShape((size * 0.24f * s).dp)))
}

/**
 * Takes the card's enlargement back off what it holds ([k] is the card's scale now), keeping its width on screen:
 * the content is laid out [k] times wider and drawn 1 / [k] as big, so on screen it is exactly as on a card at rest.
 */
private fun Modifier.unzoomed(k: () -> Float): Modifier = graphicsLayer {
    val kk = k(); scaleX = 1f / kk; scaleY = 1f / kk; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
}.layout { m, c ->
    val kk = k()
    val p = m.measure(if (c.hasBoundedWidth) c.copy(minWidth = (c.minWidth * kk).toInt(), maxWidth = (c.maxWidth * kk).toInt()) else c)
    layout(if (c.hasBoundedWidth) c.maxWidth else p.width, p.height) { p.place(0, 0) }
}

/**
 * A card's name: one line, cut with "…"; on the selected card a name too long for it slides by twice, then rests.
 *
 * Its own movement, not `basicMarquee`, for three things seen on the TV (2026-11-21):
 * - Every frame of this page takes about 27 ms to draw (the whole screen is drawn again for any change), so a
 *   movement asked for at every 16.7 ms frame reached the screen unevenly, one step then two: one step every
 *   second frame here (an even 30 a second).
 * - Running for ever it kept the processor at 40-58 % of a core while a card was selected: two passes, then rest.
 * - The letters danced: moved by a fraction of a pixel, and inside the card's enlarged layer, each letter was
 *   drawn a little differently in every frame. So the name is taken out of the enlargement ([unzoomed]) and
 *   moves by whole pixels of the screen: every frame shows the very same letters, only further along.
 */
@Composable
private fun SlidingName(name: String, s: Float, selected: Boolean, k: () -> Float) {
    var done by remember(name, selected) { mutableStateOf(false) }
    var textW by remember(name) { mutableIntStateOf(0) }
    var boxW by remember { mutableIntStateOf(0) }
    val long = selected && textW > boxW && boxW > 0
    if (!long || done) {
        // (Measured here too while selected: whether it is too long is known from this very text.)
        Box(Modifier.unzoomed(k).fillMaxWidth().onSizeChanged { boxW = it.width }) {
            if (selected && !done) androidx.compose.material3.Text(name, fontSize = (9.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                modifier = Modifier.wrapContentWidth(Alignment.Start, unbounded = true).onSizeChanged { textW = it.width }.graphicsLayer { alpha = 0f })
            Plain(name, color = Color.White, fontSize = (9.5f * s).sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    val x = remember(name) { mutableIntStateOf(0) }
    val gap = with(LocalDensity.current) { (30 * s).dp.roundToPx() }
    // Whole pixels a step, 30 steps a second: about the island's speed.
    val step = with(LocalDensity.current) { ((28 * s).dp.toPx() / 30f).roundToInt().coerceAtLeast(1) }
    LaunchedEffect(name) {
        repeat(2) { pass ->
            x.intValue = 0
            delay(if (pass == 0) 900 else 1800)
            while (x.intValue + step < textW + gap) {
                androidx.compose.runtime.withFrameNanos { }; androidx.compose.runtime.withFrameNanos { }
                x.intValue += step
            }
            x.intValue = 0
        }
        delay(1200); done = true
    }
    @Composable fun words() = androidx.compose.material3.Text(name, color = Color.White, fontSize = (9.5f * s).sp, fontWeight = FontWeight.SemiBold,
        maxLines = 1, softWrap = false)
    Box(Modifier.unzoomed(k).fillMaxWidth().onSizeChanged { boxW = it.width }.clipToBounds()) {
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).graphicsLayer { translationX = -x.intValue.toFloat() }) {
            words(); Spacer(Modifier.width(with(LocalDensity.current) { gap.toDp() })); words()
        }
    }
}

/**
 * A row's requester for a card. A row's cards can be asked for once more with the list of a moment ago while the
 * requesters are already the new list's (fewer of them): reading past the end ended Glassio
 * (an index past the end of the list, three times on 2026-11-21, while results were arriving).
 */
/** One requester more for each card a row has gained (a row grows at its end as more results come). */
private fun MutableList<FocusRequester>.growTo(n: Int) { while (size < n) add(FocusRequester()) }
private val spareRequester = FocusRequester()
private fun List<FocusRequester>.at(i: Int): FocusRequester = getOrNull(i) ?: spareRequester

/**
 * A card of the search, like a Continue Watching card of the Library: the picture, and inside it at the bottom, on
 * a soft shade, the name, one faint line under it and how far it was watched. Selected, it grows a little, gets a
 * bright rim, and a name too long for it scrolls by (as a song's name does in the island).
 */
@Composable
private fun SearchCard(picture: String, name: String, sub: String, s: Float, width: Float, ratio: Float, progress: Float?, modifier: Modifier,
                       corner: (@Composable androidx.compose.foundation.layout.BoxScope.() -> Unit)? = null, onOpen: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    // Selected as in the Library: a poster grows by 11 %, a wide card by 6 %, with the glow (Settings → Look: Glow)
    // in the picture's own colour. No white rim (his ask).
    val tall = ratio < 1f
    val glow = LocalGlow.current
    var gc by remember(picture) { mutableStateOf(Glow.cached("s:$picture")) }
    val glowA by animateFloatAsState(if (f && glow != "off") 1f else 0f, tween(220), label = "glow")
    val k by animateFloatAsState(if (f) (if (tall) 1.11f else 1.06f) else 1f, tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "card")
    val lift by animateFloatAsState(if (f) 1f else 0f, tween(160), label = "lift")
    val shape = RoundedCornerShape((11 * s).dp)
    val cornerPx = with(LocalDensity.current) { (11 * s).dp.toPx() }
    Box(Modifier.width((width * s).dp).aspectRatio(ratio).then(modifier).onFocusChanged { f = it.isFocused }.tvPress(onClick = onOpen)
        .graphicsLayer { scaleX = k; scaleY = k }
        .drawBehind {
            // The Library's soft shadow under every card, deeper under the selected one.
            if (ratio > 1.2f) softShadow(PosterShadow.wide(), PosterShadow.WIDE_PAD, lift, cornerPx) else softShadow(PosterShadow.get(), PosterShadow.PAD, lift, cornerPx)
            with(Glow) { glow(if (glow == "white") Color.White else gc ?: Color.White, glowA, tallShape = tall, corner = cornerPx) }
        }
        .clip(shape).background(Color(0xFF2A2A30))) {
        AsyncImage(picture, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(), onSuccess = { r ->
            if (glow == "color" && gc == null) { val b = r.result.image; com.glasslauncher.tv.data.Bg.io("glass-glow") { gc = Glow.colorOf("s:$picture", (b as? coil3.BitmapImage)?.bitmap) } }
        })
        corner?.invoke(this)
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth()
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.88f))))
            .padding(start = (7 * s).dp, end = (7 * s).dp, top = (18 * s).dp, bottom = (6 * s).dp)) {
            SlidingName(name, s, f) { k }
            if (sub.isNotBlank()) Plain(sub, color = Color.White.copy(alpha = 0.55f), fontSize = (7.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (progress != null) Box(Modifier.padding(top = (4 * s).dp).fillMaxWidth().height((2.5f * s).dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(progress.coerceIn(0.03f, 1f)).height((2.5f * s).dp).background(Color.White))
            }
        }
    }
}

/** A video or a song found: its picture with, inside it, its name, who made it and how long it is. */
@Composable
private fun ClipCard(c: MediaSearch.Clip, s: Float, width: Float, ratio: Float, modifier: Modifier, onOpen: () -> Unit) {
    SearchCard(c.thumb, c.title, listOf(c.by, Search.clock(c.seconds)).filter { it.isNotBlank() }.joinToString("  ·  "), s, width, ratio, null, modifier, onOpen = onOpen)
}
