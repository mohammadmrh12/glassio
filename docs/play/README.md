# Glassio for Google Play

The `play` flavor is the same code as the GitHub build, with what Google Play does not allow left out. Its package
is `com.glasslauncher.tv.play` (another signing key can't update the GitHub install; the two can live side by side,
and a backup made in one restores in the other).

```sh
./gradlew :app:bundlePlayRelease     # app/build/outputs/bundle/playRelease/app-play-release.aab  (what Play takes)
./gradlew :app:assemblePlayRelease   # app/build/outputs/apk/play/release/app-play-release.apk    (to try on a TV)
```

## What the play build leaves out (`Store.PLAY`)

| Left out | Why | Instead |
|---|---|---|
| Updates from GitHub, the Beta channel, `REQUEST_INSTALL_PACKAGES` | An app from Play may only be updated by Play | "Updates come from Google Play" in About |
| *Install an icon pack* (APKs from GitHub / F-Droid) | No installing apps from outside Play | *Find icon packs on Google Play* stays |
| Trailers (NewPipe Extractor, the YouTube search, trailer subtitles): the carousel's, the Library's, the screensaver's | YouTube's videos may only play in YouTube's own player | The pictures stay; the trailer screensaver shows the posters |
| The PlayStation gift card | Tips to a developer must go through Play Billing | Nothing |
| Glassio giving itself rights over the TV's ADB: the accessibility service, `WRITE_SECURE_SETTINGS`, notification access, `pm grant`, the overlay right; `ACCESS_ALL_EPG_DATA` | Consent screens may not be skipped | The TV's own settings pages and questions |
| The silent install path (`LocalShell.install`) | As the first row | Nothing |

Checked after every change: `strings` on the play APK's dex files finds none of `newpipe`, `pm grant`,
`allow_listener`, `WRITE_SECURE`, `f-droid`, `releases/latest`, `PackageInstaller`, `timedtext`, `gift`.
(`pm install…` remains inside the dadb library's own unused code.)

## Kept by the owner's choice (a reviewer may still ask about them)

The TV's own ADB (`LocalShell`: inputs, Bluetooth, the screensaver, recent apps; always after the TV's own "Allow
USB debugging?" question), Apple's aerial videos, the *Apple TV icons* source of the icon picker, Bing's daily
pictures, JustWatch links, the Nuvio and Stremio catalogs (titles and pictures only: Glassio plays nothing).

## The accessibility service

Before the TV's Accessibility settings open, the play build shows what the service is for and asks for agreement
(`ConsentDialog`, `A11Y_TEXT` in `ui/Dialogs.kt`; in the setup and in Settings → System). Until then the service
does nothing even when it is on (`HomeKeyService.agreed`, `a11yConsent`); the agreement can be taken back.

In the Play Console (App content → Accessibility API): not an accessibility tool (`isAccessibilityTool` is not set);
purpose: "Detects remote-control key presses so the Home button opens this home screen and user-chosen remote buttons
work; reads which app's window is in front (package and class name only, from window-state events) for the Recent
apps list, to return to the home screen after an app it opened, to wait while the system asks to allow USB debugging,
and to show camera pop-ups over other apps. No screen content is read (canRetrieveWindowContent is false); nothing
leaves the device." A short video of the agreement
page and the Home button is asked for.

## Before the first upload (not code)

1. A Play upload key: `keytool -genkeypair -v -keystore glassio-play.jks -alias glassio -keyalg RSA -keysize 4096
   -validity 10000`, kept outside git, then in `local.properties`: `PLAY_STORE_FILE`, `PLAY_STORE_PASSWORD`,
   `PLAY_KEY_ALIAS`, `PLAY_KEY_PASSWORD`. Without them the bundle is signed with the debug key, which Play refuses.
2. Play App Signing on (the default).
3. Privacy policy link: `https://github.com/mohammadmrh12/glassio/blob/main/docs/PRIVACY.md` (public once it is
   pushed to `main`).
4. Data safety: no data collected automatically. Optional and sent by the person: diagnostics and crash logs (problem
   reports), with a random install id. Not sold, not used for ads. Data is sent over HTTPS. Deletion: on request.
5. Android TV: opt in to the TV form factor, a 1280x720 banner, TV screenshots, the content rating questionnaire,
   the target audience (not children).
6. A new personal developer account needs a closed test (12 testers, 14 days) before production.

## The listing

No "Apple", "Apple TV", "tvOS" or other brands' names in the title, the description or the pictures (screenshots
without other companies' logos where possible), and nothing that sounds like free films.

- **Name:** Glassio: TV Home Screen
- **Short description:** A calm, glassy home screen for Android TV and Google TV that you shape yourself.
- **Full description:**

  Glassio is a home screen for Android TV and Google TV: your apps in a dock, a carousel of the titles from the
  catalogs you add, a Library, and glass surfaces that follow the picture behind them.

  - Make it yours: themes, glass styles, dock layouts, icon packs, your own picture, photos or video as the background.
  - Profiles: each person has their own look, dock and lists.
  - Search: films and series, and where you can watch them among the apps on your TV.
  - Your lists in one place: Continue Watching and Library from the accounts you connect (Trakt, Simkl, your own
    Jellyfin server and others).
  - Music: what is playing in your music app, as an island or a full player.
  - Smart home: Philips Hue colours, Home Assistant, cameras and the doorbell.
  - A screensaver with your posters or videos (also Pexels with your own key), a big clock and the weather; it can
    play on the home screen itself.
  - Remote buttons: give any button a job for a press, a double press and a hold.
  - No ads, no tracking. Open source (GPL-3.0).

  Glassio shows information about titles and opens them in the apps you have. It does not play or provide films or
  series itself.

  Accessibility: Glassio offers an optional accessibility service, used to notice remote-control buttons so the Home
  button can open Glassio, and which app is in front (its name only) for Recent apps and returning to Glassio. It
  reads no screen content and collects nothing.
