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
        "com.google.android.youtube.tv", "com.apple.atve.androidtv.appletv", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus",
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

    private fun isMusic(c: MediaController): Boolean {
        val pkg = c.packageName
        if (pkg in MUSIC_APPS) return true
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
            best?.registerCallback(callback, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        publish()
    }

    private fun publish() {
        val c = controller
        if (c == null) { track.value = null; return }
        val md = c.metadata ?: run { track.value = null; return }
        val ps = c.playbackState
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isNullOrBlank()) { track.value = null; return }
        val art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val state = ps?.state ?: PlaybackState.STATE_NONE
        if (state == PlaybackState.STATE_STOPPED || state == PlaybackState.STATE_NONE || state == PlaybackState.STATE_ERROR) { track.value = null; return }
        track.value = Track(
            c.packageName, title, md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "", art, md.getLong(MediaMetadata.METADATA_KEY_DURATION),
            ps?.position ?: 0L, ps?.lastPositionUpdateTime ?: android.os.SystemClock.elapsedRealtime(), ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING,
        )
    }

    fun playPause() { val c = controller ?: return; if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play() }
    fun next() { controller?.transportControls?.skipToNext() }
    fun previous() { controller?.transportControls?.skipToPrevious() }
    /** Stops the music (and the player view goes away). */
    fun stop() { controller?.transportControls?.stop(); track.value = null }
    /** Shuffle on / off in the playing app (most music apps take it through their media session). */
    fun shuffle(ctx: Context, on: Boolean) {
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
    fun appLabel(pkg: String): String = MUSIC_APPS[pkg] ?: pkg.substringAfterLast('.')
}

/** Glassio's notification listener: only there so Android lets it read the media sessions (no notifications are used). */
class MusicListener : android.service.notification.NotificationListenerService() {
    override fun onListenerConnected() { super.onListenerConnected(); NowPlaying.start(this) }
}
