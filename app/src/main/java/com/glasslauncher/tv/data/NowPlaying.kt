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
        "com.amazon.mp3" to "Amazon Music", "com.pandora.android.atv" to "Pandora",
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
        val md = c.metadata ?: return false
        return !md.getString(MediaMetadata.METADATA_KEY_ARTIST).isNullOrBlank()
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
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
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
    fun appLabel(pkg: String): String = MUSIC_APPS[pkg] ?: if (pkg in SOMETIMES_MUSIC) "YouTube" else pkg.substringAfterLast('.')
}

/** Glassio's notification listener: only there so Android lets it read the media sessions (no notifications are used). */
class MusicListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); NowPlaying.start(this) }
}
