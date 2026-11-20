// What shows behind the dock, worked out in one place.
package com.glasslauncher.tv.ui

import androidx.compose.runtime.Composable
import com.glasslauncher.tv.data.NowPlaying
import com.glasslauncher.tv.data.Settings

/** The page in the carousel's place (for reading the state and for tests; the flags below decide what is drawn). */
enum class PageKind { Carousel, Rows, Player, IdlePlayer, MusicApps, Inputs }

/**
 * What stands in the carousel's place right now and how the page behind the dock is drawn for it.
 *
 * One answer for the whole home screen, made from what is selected in the dock (ChannelsUi, MusicUi, InputsUi), the
 * song playing and the settings. The home screen used to work these out line by line between other things; each
 * line's reason is kept here beside it.
 */
internal class HomePage(
    val kind: PageKind,
    /** The song as the music page holds it (kept a moment after it stops), and the one this page is for. */
    val song: NowPlaying.Track?, val songShown: NowPlaying.Track?,
    /** A music app with nothing playing is selected, or the Music icon's apps. */
    val idle: Boolean,
    /** An app's rows are on, or chosen for the selected app. */
    val rows: Boolean,
    /** The dock sits in the middle of the screen (your own background): nothing comes up above it. */
    val dockMid: Boolean,
    /** The music page (the player, or an idle player) has the carousel's place. */
    val stage: Boolean,
    /** The carousel's block is this page's own (the player): it stays, and keeps the selection. */
    val heroOwn: Boolean,
    /** A page that isn't the carousel is on, or coming. */
    val other: Boolean,
    /** Your own background (a picture, a video, the screensaver) is behind everything. */
    val ownBg: Boolean,
    /** The song's page is drawn (over your own background: only its player with a song). */
    val songPage: Boolean,
    /** From the music page to an app's rows: the music page stays until the rows' background is up. */
    val musicToRows: Boolean,
    /** A page with its own grey background (the music apps, the inputs, an idle player): never the song's picture. */
    val grey: Boolean,
) {
    companion object {
        /** The page as last worked out (tests: dump_focus prints it). */
        @Volatile var now = PageKind.Carousel
    }
}

/**
 * [musicWas]: whether the music page showed just before the rows came (the rows fade from it then); kept by the
 * home screen across compositions, set here.
 *
 * Everything is read here, in composition (not inside an effect): read in an effect, a change didn't redraw the
 * home screen, and the grey page came late.
 */
@Composable
internal fun homePage(settings: Settings, musicWas: BooleanArray): HomePage {
    // Music mode: the glass (dock, menus, pop-ups) shows the music page (its blurred artwork), not the carousel
    // picture under it, and changes with each song.
    val songNow = MusicUi.heldTrack()
    // (Not while the channels' rows show: the music is the island then, and the page and glass stay theirs.)
    // (Its pictures are kept meanwhile: made again on the way back, the page waited for them.)
    val stageRaw = MusicUi.stageOn(settings, songNow)
    // (A music app selected in the dock: only its own song; another app's: its idle player on the grey page.)
    val songShown = MusicUi.songFor(songNow)
    val idleNow = (songShown == null && settings.musicOn && MusicUi.dockApp.value != null) || MusicUi.appsPage.value
    // (Nuvio / Stremio selected: their carousel, not the music page.)
    // (Going to an idle player or the music apps: this page stays until the grey one has faded in over it;
    // dropped at once, the carousel's picture showed under it for a moment.)
    // (Rows chosen for the selected app, read at once: "shown" comes a frame later, and the music page flashed.)
    val rowsNow = ChannelsUi.stagePkg.value != null || ChannelsUi.shown.value
    val dockMid = settings.bgMode != "carousel" && settings.dockPos == "center"
    val stageWanted = !dockMid && stageRaw && (songShown != null || idleNow) && !ChannelsUi.pageOn.value && !MusicUi.carouselApp.value && !InputsUi.on.value &&
        (!rowsNow || musicWas[0])
    if (!rowsNow) musicWas[0] = stageWanted
    // The hero has this page itself: the player, or an app's idle player (its icon and Play, on the grey page).
    // (A music app's own player too, playing or idle: right after Play on its idle player the page waited a moment
    // for the music page, the hero was hidden meanwhile and the selection fell to the first app.)
    val heroOwn = stageWanted || (settings.musicOn && MusicUi.dockApp.value != null && !rowsNow && !MusicUi.carouselApp.value)
    val otherPageNow = !MusicUi.carouselApp.value && (stageWanted || rowsNow || MusicUi.appsShowing || idleNow || MusicUi.dockApp.value != null || InputsUi.on.value)
    // (Over your own picture, video or the screensaver, the song's page is only its player with a song: an idle
    // player and an app's rows show your background, and so does the glass. The song's artwork was left in the
    // dock (and over the whole screen with a picture) after the player, until another app was chosen.)
    val ownBg = settings.bgMode != "carousel"
    val songPage = stageWanted && (!ownBg || songShown != null)
    val musicToRows = rowsNow && musicWas[0] && !ChannelsUi.pageOn.value && !MusicUi.carouselApp.value && !ownBg
    // (The music apps and an idle player have their own grey page: the dark one under them, never the song's
    // picture.)
    val greyPage = MusicUi.appsShowing || InputsUi.on.value || (idleNow && !stageWanted)
    val kind = when {
        InputsUi.on.value -> PageKind.Inputs
        MusicUi.appsShowing -> PageKind.MusicApps
        stageWanted && songShown != null -> PageKind.Player
        rowsNow -> PageKind.Rows
        idleNow && otherPageNow -> PageKind.IdlePlayer
        else -> PageKind.Carousel
    }
    HomePage.now = kind
    return HomePage(kind, songNow, songShown, idleNow, rowsNow, dockMid, stageWanted, heroOwn, otherPageNow, ownBg, songPage, musicToRows, greyPage)
}
