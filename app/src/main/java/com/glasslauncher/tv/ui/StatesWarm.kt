package com.glasslauncher.tv.ui

/**
 * Makes every Compose state Glassio keeps outside a screen (in an `object`, or a value at the top of a file) as the
 * app starts, before anything is drawn.
 *
 * Such a holder is made the first time it is touched. Touched first while a screen was being drawn, its states
 * belonged to that drawing until it ended; another window drawing for the first time at that moment (the TV's
 * screensaver, the cameras' window, Recent apps) read them and Glassio closed: "Reading a state that was created
 * after the snapshot was taken or in a snapshot that has not yet been applied" (#39: a Fire TV on 4.4.2, a Xiaomi
 * MiTV on 4.6.1). Made here, they belong to no drawing. A new holder of states goes in this list.
 */
internal fun warmStates() {
    val all: List<Any> = listOf(
        com.glasslauncher.tv.data.AppCatalogs, com.glasslauncher.tv.data.Channels, com.glasslauncher.tv.data.I18n,
        com.glasslauncher.tv.data.NetState, com.glasslauncher.tv.data.NowPlaying, com.glasslauncher.tv.data.NuvioAvatars,
        com.glasslauncher.tv.data.PeerSync, com.glasslauncher.tv.data.Photos,
        com.glasslauncher.tv.saver.HomeSaver, com.glasslauncher.tv.saver.SaverControl, com.glasslauncher.tv.saver.SaverFrames,
        com.glasslauncher.tv.saver.TrailerSaver,
        AppLock, ChannelsUi, CustomBackground, Depth, GlassCache, GlassShape, GlassTint, InputsUi, LibTrace, MusicUi,
        PageGlass, PopAnchors, ProfilesUi, SaverGate, SaverTest, Scrims, SearchUi, SettingsGlass, Trailers, VideoFrames,
        VideoGlass, WhatsNew, com.glasslauncher.tv.MultiCamOverlay.open,
        // (Values at the top of a file: touching one makes them all for that file.)
        DebugHide, SettingsAskSearch, SettingsCovers, SettingsLoseFocus, topBarStates(), settingsStates(),
    )
    android.util.Log.d("Glassio", "States made at start: ${all.size}")
}
