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

    /** Apps that play music as well as video: followed while they play (music left playing in YouTube). */
    private val SOMETIMES_MUSIC = setOf("com.google.android.youtube.tv", "com.google.android.youtube.googletv", "com.google.android.youtube")

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
        val list = runCatching { msm?.getActiveSessions(listener(ctx)) }.getOrNull().orEmpty().filter { isMusic(it) }
        val best = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
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
        val t = controller?.let { trackOf(it) }
        // Play / Pause pressed here: an update still saying the old state (it often comes first) doesn't undo it.
        val want = pendingPlaying
        track.value = if (t != null && want != null && android.os.SystemClock.uptimeMillis() < pendingUntil && t.playing != want) t.copy(playing = want)
            else { if (t?.playing == want) pendingPlaying = null; t }
    }
    @Volatile private var pendingPlaying: Boolean? = null
    @Volatile private var pendingUntil = 0L

    /** The song of one session, or nothing (no title, stopped, or stopped from Glassio). */
    private fun trackOf(c: MediaController): Track? {
        val md = c.metadata ?: return null
        val ps = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) return null
        val art = artOf(c, md, title)
        val state = ps?.state ?: PlaybackState.STATE_NONE
        if (stoppedToken != null && c.sessionToken == stoppedToken) {
            if (state == PlaybackState.STATE_PLAYING) stoppedToken = null else return null
        }
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR) return null
        return Track(
            c.packageName, title, md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
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
        Thread({
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
        }, "glass-art").apply { isDaemon = true }.start()
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
        Thread({ runCatching { if (playing) c.transportControls.pause() else c.transportControls.play() } }, "glass-play").start()
    }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    /** Stops the music (and the player view goes away). */
    fun stop() {
        val c = controller ?: run { track.value = null; return }
        // Paused first (Spotify and others ignore "stop" and played on), then stopped; the player stays away until
        // something plays again.
        stoppedToken = c.sessionToken
        stoppedAt = android.os.SystemClock.uptimeMillis()
        runCatching { c.transportControls.pause() }
        runCatching { c.transportControls.stop() }
        track.value = null
    }
    /** When Stop was pressed here (the player then goes at once, not after the short hold of MusicUi.heldTrack). */
    @Volatile var stoppedAt = 0L
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
    fun appLabel(pkg: String): String = MUSIC_APPS[pkg] ?: if (pkg in SOMETIMES_MUSIC) "YouTube" else
        lastCtx?.let { c -> runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull() } ?: pkg.substringAfterLast('.')
}

/** Glassio's notification listener: only there so Android lets it read the media sessions (no notifications are used). */
class MusicListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); NowPlaying.start(this) }
}
