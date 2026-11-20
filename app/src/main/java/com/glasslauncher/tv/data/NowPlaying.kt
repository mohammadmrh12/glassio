package com.glasslauncher.tv.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What music is playing on the TV, from any app (Spotify, YouTube Music, Apple Music, Anghami, Deezer…), and its
 * controls. Android hands out the other apps' media sessions only to a notification listener ([MusicListener]):
 * Glassio switches that on for itself through the TV's debugging connection (or its secure-settings right).
 * Video apps (Nuvio, Stremio, Netflix…) are left out: only music counts.
 */
object NowPlaying {
    data class Track(
        val pkg: String, val title: String, val artist: String, val album: String, val art: Bitmap?,
        val duration: Long, val position: Long, val at: Long, val speed: Float, val playing: Boolean,
    ) {
        /** The position now (it moves on by itself while playing). */
        fun positionNow(): Long = if (!playing) position else (position + ((android.os.SystemClock.elapsedRealtime() - at) * speed).toLong()).coerceAtMost(if (duration > 0) duration else Long.MAX_VALUE)
        val key get() = "$pkg|$title|$artist"
    }

    val track = MutableStateFlow<Track?>(null)
    /**
     * While YouTube is the session followed (a video playing), the music app under it (Spotify paused by it):
     * the music page shows that one until YouTube has really played on as music, rather than going and coming back.
     */
    val alt = MutableStateFlow<Track?>(null)
    /** Shuffle in the playing app: one state for every player (menu, music mode, island). */
    val shuffled = androidx.compose.runtime.mutableStateOf(false)
    /** What the app last said, and what was set here (kept while the app doesn't report it: many never do). */
    private var reported: Boolean? = null
    private var userShuffle: Boolean? = null
    @Volatile private var controller: MediaController? = null
    @Volatile private var started = false

    /** The music apps Glassio knows (for the dock icon's "platform" and to tell music from video). */
    val MUSIC_APPS = linkedMapOf(
        "com.spotify.tv.android" to "Spotify", "com.spotify.music" to "Spotify", "com.google.android.youtube.tvmusic" to "YouTube Music",
        "com.google.android.apps.youtube.music" to "YouTube Music", "com.apple.android.music" to "Apple Music", "com.anghami" to "Anghami",
        "deezer.android.tv" to "Deezer", "deezer.android.app" to "Deezer", "com.aspiro.tidal" to "TIDAL", "com.soundcloud.android" to "SoundCloud",
        "com.amazon.mp3" to "Amazon Music", "com.amazon.music.tv" to "Amazon Music", "com.pandora.android.atv" to "Pandora", "com.pandora.android" to "Pandora",
        "com.clearchannel.iheartradio.controller" to "iHeart", "com.clearchannel.iheartradio.tv" to "iHeart", "com.soundcloud.android.tv" to "SoundCloud",
        "tunein.player" to "TuneIn", "radiotime.player" to "TuneIn", "com.sirius" to "SiriusXM", "com.siriusxm.androidtv" to "SiriusXM",
        "com.qobuz.music" to "Qobuz", "com.napster.android" to "Napster", "com.audiomack" to "Audiomack", "com.plexapp.plexamp" to "Plexamp",
        "com.jio.media.jiobeats" to "JioSaavn", "com.gaana" to "Gaana", "com.tencent.ibg.joox" to "JOOX", "com.skysoft.kkbox.android" to "KKBOX",
        "ru.yandex.music" to "Yandex Music", "com.zvooq.openplay" to "Zvuk", "com.vk.music" to "VK Music", "com.bandcamp.android" to "Bandcamp",
        "com.idagio.app" to "IDAGIO", "com.primephonic.android" to "Apple Classical", "com.boomplay.music" to "Boomplay", "com.audials" to "Audials",
        "com.maxmpz.audioplayer" to "Poweramp", "org.videolan.vlc.music" to "VLC", "com.foobar2000.foobar2000" to "foobar2000",
        "com.radio.garden" to "Radio Garden", "com.radio.fmradio" to "Radio FM", "com.spotify.lite" to "Spotify Lite",
        "com.google.android.apps.podcasts" to "Podcasts", "au.com.shiftyjelly.pocketcasts" to "Pocket Casts", "com.audible.application" to "Audible",
    )
    private val VIDEO_APPS = setOf(
        "space.nuvio.nativelegacy", "com.nuvio.tv", "com.nuvio.tv.test", "com.nuvioreshaped.tv", "com.stremio.one", "com.netflix.ninja",
        "com.apple.atve.androidtv.appletv", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
        "org.videolan.vlc", "org.xbmc.kodi", "net.mbc.shahidTV", "com.glasslauncher.tv",
    )

    private fun listener(ctx: Context) = ComponentName(ctx, MusicListener::class.java)

    fun hasAccess(ctx: Context): Boolean = runCatching {
        android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty().contains(listener(ctx).flattenToString())
    }.getOrDefault(false)

    /** Blocking. Lets Glassio read the media sessions (its notification listener on). */
    fun grant(ctx: Context): Boolean {
        val comp = listener(ctx).flattenToString()
        if (hasAccess(ctx)) return true
        LocalShell.run(ctx, "cmd notification allow_listener $comp")
        if (!hasAccess(ctx)) runCatching {
            val cur = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            android.provider.Settings.Secure.putString(ctx.contentResolver, "enabled_notification_listeners", if (cur.isBlank()) comp else "$cur:$comp")
        }
        return hasAccess(ctx)
    }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { publish() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { publish() }
        override fun onSessionDestroyed() { controller = null; pick(lastCtx ?: return) }
    }
    @Volatile private var lastCtx: Context? = null
    private var msm: MediaSessionManager? = null
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { lastCtx?.let { pick(it) } }
    private val watched = HashMap<android.media.session.MediaSession.Token, MediaController>()
    private val watchCb = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val s = state?.state
            if (s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_CONNECTING) lastCtx?.let { pick(it) }
        }
    }

    /** Starts following the media sessions (once access is there). Safe to call again. */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        lastCtx = app
        if (started || !hasAccess(app)) { if (started) pick(app); return }
        runCatching {
            val m = app.getSystemService(MediaSessionManager::class.java) ?: return
            msm = m
            m.addOnActiveSessionsChangedListener(sessionsListener, listener(app), android.os.Handler(android.os.Looper.getMainLooper()))
            started = true
            pick(app)
        }.onFailure { Glog.w("Now playing", it) }
    }

    /** Other YouTube apps for the TV, each with its name (they play music and video, like YouTube). */
    val YOUTUBE_CLIENTS = linkedMapOf(
        "io.gh.reisxd.tizentube.cobalt" to "TizenTube", "org.smarttube.stable" to "SmartTube", "org.smarttube.beta" to "SmartTube Beta",
        "com.teamsmart.videomanager.tv" to "SmartTube", "com.liskovsoft.smarttubetv" to "SmartTube", "com.liskovsoft.smarttubetv.beta" to "SmartTube Beta",
    )
    /** Apps that play music as well as video: followed while they play (music left playing in YouTube). */
    private val SOMETIMES_MUSIC = setOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv", "com.google.android.youtube") + YOUTUBE_CLIENTS.keys

    private fun isMusic(c: MediaController): Boolean {
        val pkg = c.packageName
        if (pkg in MUSIC_APPS) return true
        if (pkg in SOMETIMES_MUSIC) return c.playbackState?.state.let { it == PlaybackState.STATE_PLAYING || it == PlaybackState.STATE_BUFFERING || it == PlaybackState.STATE_PAUSED && c.sessionToken == controller?.sessionToken }
        if (pkg in VIDEO_APPS) return false
        // Any other app: music when it says so (its sound is music, or it is an audio app), or its song has an artist.
        val attrs = runCatching { c.playbackInfo?.audioAttributes }.getOrNull()
        if (attrs?.contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE) return false
        if (attrs?.contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC) return true
        if (lastCtx?.let { audioApp(it, pkg) } == true) return true
        val md = c.metadata ?: return false
        return !md.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank()
    }

    private val audioCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    /** The app calls itself an audio app (Android's app category). */
    fun audioApp(ctx: Context, pkg: String): Boolean = audioCache.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).category == android.content.pm.ApplicationInfo.CATEGORY_AUDIO }.getOrDefault(false)
    }

    /** The music session to follow: the one playing, else the last one that has a song. */
    private fun pick(ctx: Context) {
        val all = runCatching { msm?.getActiveSessions(listener(ctx)) }.getOrNull().orEmpty()
        val list = all.filter { isMusic(it) }
        // The one playing; else the one followed so far, while it is still there (stopped from Glassio too: its
        // player goes, and another app's paused song from before didn't come up under it); else the last to play.
        // (Starting counts: Spotify paused as SoundCloud was still loading, and the player stayed with Spotify.)
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull { it.sessionToken != controller?.sessionToken && it.playbackState?.state.let { s -> s == PlaybackState.STATE_BUFFERING || s == PlaybackState.STATE_CONNECTING } }
            ?: list.firstOrNull { it.sessionToken == controller?.sessionToken }
            // (Not after Stop here: the song stopped went, and another app's paused song came up in its place.)
            ?: list.maxByOrNull { lastPlayed[it.packageName] ?: 0L }?.takeIf { !afterStop && (lastPlayed[it.packageName] ?: 0L) > 0L }
            ?: list.firstOrNull().takeIf { controller == null && !afterStop }
        if (list.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) afterStop = false
        list.forEach { if (it.playbackState?.state == PlaybackState.STATE_PLAYING) lastPlayed[it.packageName] = android.os.SystemClock.uptimeMillis() }
        // Every music app is listened to (not only the one followed): one that starts playing takes over at once.
        // (YouTube, SmartTube, TizenTube too while they don't play yet: their session often comes before their song, and
        // unwatched, their starting to play went unnoticed and the player stayed with a paused Spotify.)
        val musicTokens = list.map { it.sessionToken }.toSet()
        val heard = all.filter { it.sessionToken in musicTokens || it.packageName in SOMETIMES_MUSIC }
        val tokens = heard.map { it.sessionToken }.toSet()
        watched.keys.filter { it !in tokens }.forEach { t -> runCatching { watched.remove(t)?.unregisterCallback(watchCb) } }
        heard.forEach { c -> if (c.sessionToken !in watched) runCatching { c.registerCallback(watchCb, android.os.Handler(android.os.Looper.getMainLooper())); watched[c.sessionToken] = c } }
        if (best?.sessionToken != controller?.sessionToken) {
            runCatching { controller?.unregisterCallback(callback) }
            controller = best
            reported = null; userShuffle = null
            best?.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        alt.value = if (best != null && best.packageName in SOMETIMES_MUSIC)
            list.filter { it.packageName !in SOMETIMES_MUSIC && it.sessionToken != best.sessionToken }.firstNotNullOfOrNull { trackOf(it) }
        else null
        publish()
    }

    private fun publish() {
        lastCtx?.let { ctx ->
            val r = shuffleOn(ctx)
            if (reported != null && r != reported) userShuffle = null
            reported = r
            val v = userShuffle ?: r
            if (shuffled.value != v) shuffled.value = v
        }
        artWaiting = false
        val t = controller?.let { trackOf(it) }
        t?.let { seenAt[it.pkg] = android.os.SystemClock.uptimeMillis() }
        // A new song whose artwork is still coming from its link (SoundCloud and others): the last song stays a moment
        // (up to 1.5 s) and the new one comes with its picture, all at once like Spotify's, not first with Glassio's
        // stand-in picture and then again with its own.
        val old = track.value
        if (t != null && artWaiting && old != null && old.pkg == t.pkg && old.title != t.title) {
            val now = android.os.SystemClock.uptimeMillis()
            if (waitFor != t.title) { waitFor = t.title; waitUntil = now + 1500 }
            if (now < waitUntil) { mainHandler.removeCallbacks(waitDone); mainHandler.postDelayed(waitDone, waitUntil - now); return }
        }
        waitFor = null
        // Play / Pause pressed here: an update still saying the old state (it often comes first) doesn't undo it.
        val want = pendingPlaying
        track.value = if (t != null && want != null && android.os.SystemClock.uptimeMillis() < pendingUntil && t.playing != want) t.copy(playing = want)
            else { if (t?.playing == want) pendingPlaying = null; t }
    }
    /** For testing: every media session and the fields of its song (log "GlassMedia"). */
    fun dumpSessions(ctx: Context) {
        runCatching { msm?.getActiveSessions(listener(ctx)) }.getOrNull().orEmpty().forEach { c ->
            val md = c.metadata
            val keys = md?.keySet().orEmpty().joinToString(", ") { k -> k.removePrefix("android.media.metadata.") + "=" + (runCatching { md?.getString(k) }.getOrNull()?.take(40) ?: "…") }
            android.util.Log.i("GlassMedia", "${c.packageName} state=${c.playbackState?.state} music=${isMusic(c)} followed=${c.sessionToken == controller?.sessionToken} | $keys")
        }
    }
    /** A YouTube video's title, channel and picture, by its id (asked once, in the background). */
    private val ytCache = java.util.concurrent.ConcurrentHashMap<String, Triple<String, String, Bitmap?>>()
    private val ytAsked = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private fun ytInfo(id: String): Triple<String, String, Bitmap?>? {
        ytCache[id]?.let { return it }
        if (ytAsked.add(id)) Bg.io("glass-ytinfo") {
            runCatching {
                val c = java.net.URL("https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8"))
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 6000
                val o = try { org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
                val pic = runCatching {
                    val p = java.net.URL("https://i.ytimg.com/vi/$id/hqdefault.jpg").openConnection() as java.net.HttpURLConnection
                    p.connectTimeout = 5000; p.readTimeout = 6000
                    try { p.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } } finally { p.disconnect() }
                }.getOrNull()?.let { b ->
                    // The 4:3 picture has black bars: its 16:9 middle, made square around the centre like an album cover.
                    val h = b.width * 9 / 16; val y = (b.height - h) / 2; val side = minOf(b.width, h); val x = (b.width - side) / 2
                    Bitmap.createBitmap(b, x, y.coerceAtLeast(0), side, side)
                }
                ytCache[id] = Triple(o.optString("title"), o.optString("author_name"), pic)
                android.os.Handler(android.os.Looper.getMainLooper()).post { publish() }
            }.onFailure { Glog.w("YouTube info $id", it) }
        }
        return null
    }

    /** The song whose artwork is awaited, and until when (see publish). */
    @Volatile private var artWaiting = false
    @Volatile private var waitFor: String? = null
    @Volatile private var waitUntil = 0L
    private val mainHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    private val waitDone = Runnable { publish() }

    private val seenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** [pkg] had a song a moment ago (between two videos an app's session is empty for a second or two). */
    fun hadSong(pkg: String, withinMs: Long = 6000) = android.os.SystemClock.uptimeMillis() - (seenAt[pkg] ?: -withinMs * 2) < withinMs
    @Volatile private var pendingPlaying: Boolean? = null
    @Volatile private var pendingUntil = 0L

    /** The song of one session, or nothing (no title, stopped, or stopped from Glassio). */
    private fun trackOf(c: MediaController): Track? {
        val md = c.metadata ?: return null
        val ps = c.playbackState
        // SmartTube playing in the background sends its song with every field empty but the video's id: its title,
        // channel and picture come from YouTube by that id (until then, the app's name).
        val vid = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)?.takeIf { c.packageName in YOUTUBE_CLIENTS && Regex("[A-Za-z0-9_-]{11}").matches(it) }
        val own = (md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.takeIf { it.isNotBlank() }
        val yt = if (own == null && vid != null) ytInfo(vid) else null
        val title = own ?: yt?.first ?: vid?.let { appLabel(c.packageName) }
        if (title.isNullOrBlank()) return null
        val art = if (own == null && yt?.third != null) yt.third else artOf(c, md, title)
        val state = ps?.state ?: PlaybackState.STATE_NONE
        if (stoppedToken != null && c.sessionToken == stoppedToken) {
            if (state == PlaybackState.STATE_PLAYING) stoppedToken = null else return null
        }
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR) return null
        return Track(
            c.packageName, title, md.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() } ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.takeIf { it.isNotBlank() } ?: yt?.second ?: "",
            md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "", art, md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            ps?.position ?: 0L, ps?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime(), ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING,
        )
    }

    /**
     * The song's artwork: the picture the app hands over, else the one at the link it gives (SoundCloud and others send
     * only a link: the player showed an empty grey square), loaded once in the background; until then, or when there
     * is none at all, Glassio's own artwork ([PlaceholderArt]).
     */
    private fun artOf(c: MediaController, md: MediaMetadata, title: String): Bitmap? {
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_ART)?.let { return it }
        md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        md.description?.iconBitmap?.let { return it }
        val uri = listOf(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
            .firstNotNullOfOrNull { k -> md.getString(k)?.takeIf { it.isNotBlank() } } ?: md.description?.iconUri?.toString()
        if (uri != null) {
            linkArt[uri]?.let { return it }
            loadArt(c.packageName, uri)
            if (uri !in artFailed && c.sessionToken == controller?.sessionToken) artWaiting = true
        }
        return PlaceholderArt.of(title)
    }

    private val linkArt = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>?) = size > 24
    }.let { java.util.Collections.synchronizedMap(it) }
    private val artLoading: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val artFailed: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    private fun loadArt(pkg: String, uri: String) {
        if (uri in artFailed || !artLoading.add(uri)) return
        val ctx = lastCtx ?: return
        Bg.io("glass-art") {
            val b = runCatching {
                val u = android.net.Uri.parse(uri)
                val bytes = when (u.scheme?.lowercase()) {
                    "http", "https" -> (java.net.URL(uri).openConnection() as java.net.HttpURLConnection).run {
                        connectTimeout = 5000; readTimeout = 8000; instanceFollowRedirects = true
                        try { inputStream.use { it.readBytes() } } finally { disconnect() }
                    }
                    else -> ctx.contentResolver.openInputStream(u)?.use { it.readBytes() }
                } ?: error("no data")
                // Not bigger than the player shows it (some apps link a 3000 px cover).
                val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var sample = 1
                while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 600) sample *= 2
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: error("not a picture")
            }.onFailure { Glog.w("Artwork of $pkg: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
            artLoading.remove(uri)
            if (b != null) linkArt[uri] = b else artFailed += uri
            if (b != null) android.os.Handler(android.os.Looper.getMainLooper()).post { publish() }
        }
    }

    /** For testing: what each media session hands over (log "GlassMedia"). */
    fun dump() {
        val ctx = lastCtx ?: return
        runCatching { msm?.getActiveSessions(listener(ctx)) }.getOrNull().orEmpty().forEach { c ->
            val md = c.metadata
            android.util.Log.i("GlassMedia", "${c.packageName} state=${c.playbackState?.state} keys=${md?.keySet()?.sorted()} " +
                "bitmap=${md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) != null || md?.getBitmap(MediaMetadata.METADATA_KEY_ART) != null || md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) != null} " +
                "uri=${(md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI))?.substringBefore('?')?.take(60)} " +
                "descIcon=${md?.description?.iconBitmap != null}/${md?.description?.iconUri?.scheme}")
        }
    }

    fun playPause() {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        // Shown at once (the button, the page's dim, the dock's glass): the app's own report came a moment later.
        pendingPlaying = !playing; pendingUntil = android.os.SystemClock.uptimeMillis() + 1500
        track.value?.let { t -> track.value = t.copy(playing = !playing, position = t.positionNow(), at = android.os.SystemClock.elapsedRealtime()) }
        // Sent off the main thread: Play waits on the system (it lets the app start in the background first) and
        // held the screen still for a moment on every resume.
        Bg.io("glass-play") { runCatching { if (playing) c.transportControls.pause() else c.transportControls.play() } }
    }
    /** Play (never pause): for an app just woken, which may not take it at first. */
    fun play() { val c = controller ?: return; Bg.io("glass-play") { runCatching { c.transportControls.play() } } }
    /** What the app itself reports now (not what Glassio shows ahead of it). */
    fun reallyPlaying(): Boolean = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    /** Stops the music (and the player view goes away). */
    fun stop() {
        val c = controller ?: run { track.value = null; return }
        // Paused first (Spotify and others ignore "stop" and played on), then stopped; the player stays away until
        // something plays again.
        stoppedToken = c.sessionToken
        afterStop = true
        stoppedAt = android.os.SystemClock.uptimeMillis()
        runCatching { c.transportControls.pause() }
        runCatching { c.transportControls.stop() }
        track.value = null
    }
    /** Stopped here, and nothing has played since: no other app's paused song takes its place. */
    @Volatile private var afterStop = false
    /** When Stop was pressed here (the player then goes at once, not after the short hold of MusicUi.heldTrack). */
    @Volatile var stoppedAt = 0L
    /** When each app last played (which one comes back when the one followed goes away). */
    private val lastPlayed = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Stopped from Glassio: this session shows nothing until it plays again. */
    @Volatile private var stoppedToken: android.media.session.MediaSession.Token? = null
    /** Shuffle on / off in the playing app (most music apps take it through their media session). */
    fun shuffle(ctx: Context, on: Boolean) {
        userShuffle = on; shuffled.value = on
        val c = controller ?: return
        runCatching {
            val compat = android.support.v4.media.session.MediaControllerCompat(ctx, android.support.v4.media.session.MediaSessionCompat.Token.fromToken(c.sessionToken))
            compat.transportControls.setShuffleMode(if (on) android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_ALL else android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_NONE)
        }.onFailure { Glog.w("Shuffle", it) }
    }
    fun shuffleOn(ctx: Context): Boolean = runCatching {
        val c = controller ?: return false
        android.support.v4.media.session.MediaControllerCompat(ctx, android.support.v4.media.session.MediaSessionCompat.Token.fromToken(c.sessionToken)).shuffleMode ==
            android.support.v4.media.session.PlaybackStateCompat.SHUFFLE_MODE_ALL
    }.getOrDefault(false)
    /** Apps that play video as well as music (YouTube): their playing counts as music only once it goes on. */
    fun sometimesMusic(pkg: String) = pkg in SOMETIMES_MUSIC
    fun appLabel(pkg: String): String = MUSIC_APPS[pkg] ?: YOUTUBE_CLIENTS[pkg] ?: if (pkg in SOMETIMES_MUSIC) "YouTube" else
        lastCtx?.let { c -> runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() } ?: pkg.substringAfterLast('.')
}

/** Glassio's notification listener: only there so Android lets it read the media sessions (no notifications are used). */
class MusicListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); NowPlaying.start(this) }
}
