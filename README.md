<p align="center"><img src="docs/brand/icon_512.png" width="140" alt="Glassio icon"></p>

# Glassio

**An Apple TV–style home screen for Google TV / Android TV**, with real liquid glass, a carousel fed by your
Nuvio add-on catalogs, Continue Watching from Nuvio Legacy, tvOS-style icons, weather, screensavers and
Home Assistant hooks. Built with Kotlin and Jetpack Compose. Tested on the Google TV Streamer (4K).

[العربية — دليل الاستخدام الكامل](docs/README.ar.md) · [Discussions](https://github.com/mohammadmrh12/glassio/discussions) · [Report a bug](https://github.com/mohammadmrh12/glassio/issues/new/choose)

![Home](docs/screenshots/01-home.jpg)

| | |
|---|---|
| ![Carousel](docs/screenshots/02-carousel.jpg) | ![Dock](docs/screenshots/03-dock-focus.jpg) |
| ![Apps open](docs/screenshots/04-apps-open.jpg) | ![Apps folder](docs/screenshots/05-apps-folder.jpg) |
| ![App menu](docs/screenshots/06-app-menu.jpg) | ![Icon picker](docs/screenshots/07-icon-picker-appletv.jpg) |
| ![Performance](docs/screenshots/10-settings-performance.jpg) | ![Carousel settings](docs/screenshots/11-settings-carousel.jpg) |
| ![Dock & icons](docs/screenshots/13-settings-dock-icons.jpg) | ![Catalogs](docs/screenshots/14-settings-catalogs.jpg) |
| ![Continue Watching](docs/screenshots/15-settings-continue-watching.jpg) | ![Nuvio & Home Assistant](docs/screenshots/16-settings-nuvio-ha.jpg) |
| ![Screensaver](docs/screenshots/17-settings-screensaver.jpg) | ![About](docs/screenshots/20-settings-about.jpg) |
| ![Menu](docs/screenshots/40-menu.jpg) | ![Themes](docs/screenshots/41-menu-themes.jpg) |
| ![Background](docs/screenshots/42-menu-background.jpg) | ![Library](docs/screenshots/43-library.jpg) |
| ![Screensaver as background](docs/screenshots/44-screensaver-background.jpg) | ![Black on white](docs/screenshots/45-theme-black-on-white.jpg) |

---

## Features

**New in 1.9.7**
- **Focus plate**: White, Dark, Glass or Black AMOLED behind the selected icon — also in the menu's Themes page.
- **Add-ons from your Nuvio account** (Settings → Catalogs): merged with the TV's own, an add-on never shows up twice. The Nuvio account row is also in Catalogs and Nuvio & Home Assistant.
- **Backgrounds from your phone** (Manage from phone): picture or video link, save a link to the TV, upload a picture or video from the phone, pick or delete files in the backgrounds folder.
- **Aerial Views as the TV screensaver** on / off and its start time (20 s, 30 s, 1 min, 2 min) through ADB, in Settings → Screensaver. Glassio's own screensaver can start after 10 s or 30 s.
- **TMDB token check**: a green check when the token works with TMDB, red when it's refused or when the API Key was pasted instead of the API Read Access Token. The Home Assistant webhook shows a green check when set.
- **Smoother dock**: it opens at full frame rate (the page dims and blurs right after), moving inside the open dock is smooth, and the menu opens every time you go up to the settings button.

**Menu, Library, themes and backgrounds (1.9.5)**
- **Menu** at the settings button, like the Apple TV Control Center: clock, date, weather (high / low, feels like, humidity, wind, city), then Library, Glassio settings, Android settings, Wi-Fi, Themes and Background. It opens when you go up to the button (or with OK, or never: Settings → Menu, Library & themes), and every part can be hidden.
- **Library**: the **+** button between Play and Next saves the title. Grey page, five posters a row; OK opens the title, hold OK removes it. It also shows your **Nuvio account library** (Nuvio Legacy, official Nuvio and the forks that sync with the account; sign in with a QR code) and your **Trakt watchlist**; + can save to both.
- **Themes**: Black on white, Black AMOLED, Liquid, Clear, Glass still, Glass is glass — for the dock, the icons and the menu (black ones keep a clear matte dock). *Colour on focus* is in the same list.
- **Background**: each title's backdrop, **your picture** or **your video** (a link, or a file in the backgrounds folder shown in Settings → Clock & glass), or **the screensaver's aerials**. With your own background the page is just that (no title or buttons); the glass follows the video.
- **Turn the accessibility service on with ADB** from Settings → System, for TVs that grey it out (Google TV Streamer).
- Faster and lighter: the open dock and slides cost far less GPU, about half the memory, 16-bit backdrops in Performance, logos and pictures retry when a download fails.

**Carousel**
- Endless hero carousel from your Nuvio add-on catalogs (Cinemeta, AIOMetadata, TMDB lists …), shuffled across all catalogs if you like.
- Settings → Carousel → **Slide animation**: **Apple TV** (default: the next picture opens up from the right with parallax, from the left going back, glass included), *Slide* (pushed in) or *Fade*.
- **Buttons**: **Play + Next** (default, like Apple TV: Right from Play selects the round Next button, Right or OK on it shows the next title, Left on Play the previous one; Play stays in place whatever the synopsis length), *Play only* or *Hidden*.
- **Dots**: *Dots*, **Liquid glass dots** (clear glass drops on a glass capsule that stretch, join and part like water as you move), *Progress bar* or *Hidden*.
- Title logo (sizes evened out), rating / year / platform chips, genres, synopsis in Apple's grey, Play button when selected.
- **Backdrop gallery** like the Apple TV app: each title shows several of its pictures before the carousel moves on.
- **Continue Watching** from Nuvio Legacy: at the start of the carousel (1–5 slides or all) or mixed in, with progress
  (`Continue · S1 · E3 · 42%`). Back from Nuvio, the carousel opens on what you just watched. These titles are kept in
  memory, so going back to them — even after browsing far or pressing Home — never reloads.
- Home pressed on the home screen jumps back to the first slide.
- Opening a title brings Nuvio forward and opens it directly (no restart, no splash when Nuvio is running).
  Works with **Nuvio Legacy**, the **official Nuvio TV** app, **Nuvio Arc** and **Nuvio Re-Shaped**
  (Settings → Nuvio & Home Assistant → *Open titles in*; **Auto** picks the first installed in that order).

**Glass**
- Real refraction (AGSL shader), blurred backdrop shared by every glass surface, "Glass like glass" (with or without ripple), clear, AMOLED black, black-on-white.
- The page blurs and dims when you move down into the apps, glass included.
- Backdrop and glass always change in the same frame.

**Dock & icons**
- 6 apps per row, Apps folder with every app (hidden ones too), move / hide / change icon / app info (hold OK).
- tvOS behaviour: the selected icon lifts, **colour on focus** (icons white at rest, coloured on a light or dark plate),
  rubber-band **bounce at the edges**.
- Icons from icon packs (Projectivy, Blueprint, Nova, ADW), a link, the TV's photos, your phone, or the **Apple TV icon
  library** (default, dark, white and clear looks, see [tools](#tools)).

**Top bar**
- Clock and date on the left (Apple TV) or next to Settings; weather (Open-Meteo, no key) with condition and city, each part optional; auto-hide.

**Screensaver**
- Your TV's own screensaver (for example Aerial Views) started instantly, Apple aerial videos, or posters with logos.
  Back on the home screen can start it, like Apple TV.

**Home Assistant**
- Webhook when a title opens (title, type, id, app). See [Home Assistant](#home-assistant).

**Updates**
- Checks GitHub Releases, shows a banner and a pill next to the clock; Settings → About → Install update downloads it
  and installs it (silently through the TV's adb, or with Android's installer).

**Performance**
- Performance / Balanced / Quality modes; animations read in the draw and layout phases (no recomposition per frame),
  one cached GPU layer for the backdrop, shader early-outs, background-priority blur, Compose baseline profiles, R8.

---

## Install

1. Download `GlassLauncher-x.y.z.apk` from [Releases](https://github.com/mohammadmrh12/glassio/releases).
2. Install it on the TV: with the **Downloader** app (enter the release link), a USB stick, or from a computer:
   ```sh
   adb connect <tv-ip>:5555
   adb install -r GlassLauncher-x.y.z.apk
   ```
3. Open **Glassio** from the TV's apps.

### Make it your home screen (Home button)

Google TV always sends the Home button to its own launcher, so Glass uses an accessibility service to catch it.

1. Settings → **System** → *Turn on the accessibility service* → choose **Glassio** → On.
2. Recommended — so Android keeps the service on after updates, and so Glass can start the TV screensaver and read Nuvio's
   progress, run once from a computer:
   ```sh
   adb shell pm grant com.glasslauncher.tv android.permission.WRITE_SECURE_SETTINGS
   ```
3. The first time Glass uses the TV's own adb (TV screensaver, Continue Watching, silent updates) the TV asks
   **"Allow USB debugging?"** for Glassio: tick **Always allow** and choose **Allow**. *Network debugging* must be
   on (Settings → System → Developer options).

---

## Setup

### Catalogs (Nuvio add-ons)
- **Settings → Catalogs → Add add-ons from my phone (QR)**: scan the code with your phone (same Wi-Fi), paste manifest
  links (`https://…/manifest.json`) and, optionally, your TMDB token.
- Or **Connect to Nuvio**: in Nuvio open *Add-ons → Manage from phone* first, then press Connect.
- Choose the catalogs you want; *Refresh catalogs* reloads them.

### TMDB token (logos, full-size art, synopsis language, ratings, gallery)
1. Create a free account at [themoviedb.org](https://www.themoviedb.org/) → Settings → API → copy the **API Read Access Token**.
2. Settings → Catalogs → **TMDB token** (or paste it on the phone page).
3. Settings → Carousel → **Synopsis language**: 24 languages (English, Arabic, French, Spanish, German, Italian,
   Portuguese, Turkish, Russian, Persian, Hindi, Japanese, Korean, Chinese …). English is used when a title has no text in that language.

### Continue Watching
- Works with **Nuvio Legacy**: Nuvio Legacy writes what you play (title, episode, how far) to the TV's log, and Glassio
  reads that log through the TV's own debugging connection (ADB, see below). Nothing leaves the TV.
- **Trakt**: Settings → Continue Watching → *Connect Trakt account* shows a short code; enter it at trakt.tv/activate on
  your phone (no password on the TV). What you pause in any app that scrobbles to Trakt — the official Nuvio app,
  Nuvio Arc, Re-Shaped, Stremio, Kodi… — then shows up too (synced every few minutes and when you come back to Glassio).
  Removing a Trakt title here removes it on Trakt as well.
- The official Nuvio app, Nuvio Arc and Re-Shaped don't write the Legacy log, so for them use Trakt. Other apps (Netflix, YouTube…) keep their Continue Watching private on Google TV.
- Settings → **Continue Watching**: at the start / mixed / off, how many slides lead, open on what you just watched, progress bar, clear the list.
- A title leaves the list when you finish it (past 92 %) or remove it.

### TV access (ADB) — one-time setup
Continue Watching, *Make Aerial Views the TV screensaver*, starting the screensaver and silent updates use the TV's own
debugging connection (Glassio talks to it on the TV itself, like Nuvio TV Relay). Settings → **System** walks you through it:
1. **Open About** → press OK on *Android TV OS build* (or *Build*) **7 times** until it says you are a developer.
2. **Open Developer options** → turn on **USB debugging** (on some TVs also *Network debugging* / *ADB over network*).
3. **Allow Glassio** → the TV asks *Allow USB debugging?* → tick **Always allow from this computer** → OK. Asked only once.
The rows show what is already done (Developer options on, USB debugging on, Glassio allowed).

### Icons
**One app**
- On the home screen, hold **OK** on the app → **Change icon**, then pick from:
  Suggested, the Apple TV icons (default / dark / white / clear), any installed icon pack,
  a photo saved on the TV, a link to a PNG, your phone (Settings → Manage from phone), or back to the original.

**Icon packs (all apps at once)**
1. Install an Android TV / Projectivy icon pack from Google Play
   (Settings → Dock & icons → *Find icon packs on Google Play* opens the search). ADW / Nova / Blueprint packs work too.
2. Settings → Dock & icons → **Icon pack** → choose it. Every app takes the pack's icon; single apps can still be changed.

**Many icons from files**
- Copy `<package>.png` files (for example `com.netflix.ninja.png`) into the icon folder shown in
  Settings → Dock & icons, then press **Import icons from the icon folder**.

**Apps folder**: Settings → Dock & icons → *Show Apps folder* (or hold OK on the folder → *Hide from the dock*).

Look: Settings → Dock & icons (dock size, dock and settings background, icon style) and
Settings → Clock & glass (colour on focus, plate light/dark, bounce, lift).

### Weather
Settings → Clock & glass → **Weather** (everything / temperature + sky / temperature + city / temperature / off),
**Weather city** (English or Arabic name; the place found is shown next to it), Fahrenheit.

### Screensaver
**Recommended: [Aerial Views](https://play.google.com/store/apps/details?id=com.neilturner.aerialviews)**, the best
screensaver with Glassio: Apple, Amazon and your own 4K videos, clock and info overlays, free.

1. Settings → **Screensaver** → **Install Aerial Views** (opens Google Play).
2. Back in Glassio: **Make Aerial Views the TV screensaver**. The first time the TV asks to allow USB debugging
   for Glassio: choose *Always allow*. (By hand: `adb shell settings put secure screensaver_components com.neilturner.aerialviews/.ui.screensaver.DreamActivity`.)
3. *Type* is set to **TV screensaver**. *Open Aerial Views settings* picks videos, clock and overlays.
4. *Start after* (idle time on the launcher) and **Back on the home screen starts it**.

Without Aerial Views: *Type* → Apple aerial videos (streamed by Glassio) or posters with logos, and *Add video link* for your own .mp4 / .mov.

### Performance
Settings → **Performance** → *Mode*: **Auto** (default: picked for the TV from its memory and graphics chip,
e.g. a 2 GB TCL gets Performance, a Google TV Streamer gets Balanced), *Performance*, *Balanced*, *Quality*.
Each setting can be changed on its own (the mode then shows *Custom*):
- **Glass detail**: resolution of the picture the glass bends (480 px, or 720 px for crisper glass).
- Picture quality, prepare ahead, slide transition, glass refraction, backdrop gallery.

The clear dock and tiles show the page picture itself (half screen resolution), so the glass lines up with the
backdrop and stays sharp at any *Glass detail*.

In Settings, rows changed with ◀ ▶ (and app order) only change after you press **OK** on them; OK or Back
again when done. Moving down the list never changes a value by accident.

### Artwork
Settings → Carousel → **Artwork**: Main (TMDB), Second picture, Highest resolution, or the add-on's own picture.
For one title: on the carousel **hold OK** (or press Menu) to switch to its next picture; Glassio remembers it
(*Reset pictures picked per title* undoes all of them).

---

## Home Assistant

Glass posts JSON to a Home Assistant **webhook** every time you open a title from the carousel:

```json
{ "event": "launch", "title": "Severance", "type": "series", "id": "tt11280740", "app": "space.nuvio.nativelegacy" }
```

**1. Create the automation** (Settings → Automations → Create → Edit in YAML):

```yaml
alias: Glassio – title opened
mode: queued
triggers:
  - trigger: webhook
    webhook_id: glass_launcher_CHANGE_ME   # pick a long random id
    allowed_methods: [POST]
    local_only: true
actions:
  # Re-fire as an event other automations can use
  - event: glass_launcher
    event_data:
      title: "{{ trigger.json.title }}"
      type: "{{ trigger.json.type }}"
      id: "{{ trigger.json.id }}"
  # Example: movie time
  - if: "{{ trigger.json.event == 'launch' }}"
    then:
      - action: light.turn_off
        target: { area_id: living_room }
```

**2. Give Glass the URL**: `http://<home-assistant-ip>:8123/api/webhook/glass_launcher_CHANGE_ME`
- Settings → **Nuvio & Home Assistant → Home Assistant webhook**, or paste it on the phone page (Catalogs → *Add add-ons from my phone*).
- Turn on **Tell Home Assistant when a title opens**, then **Send a test event** (sends `{"event":"test"}`).

Use the IP address of Home Assistant (the TV may not resolve `homeassistant.local`). `local_only: true` keeps the webhook
on your home network.

---

## Updates
Glass checks this repository's latest release at start and every 12 hours. An **Update x.y.z** pill appears next to the
clock with a small banner of what's new: go up to it and press **OK** to download and install right there (or Settings →
**About → Install update**). With "Allow USB debugging" accepted, the update is
silent and Glass reopens by itself; otherwise Android asks you to confirm (allow *Install unknown apps* for Glassio).

Releases are signed with the same key, so they install over each other.

---

## Troubleshooting
- Everything that fails quietly (network, Trakt, the TV's adb, opening a title in Nuvio) is logged under one tag:
  `adb logcat -s Glassio`.
- Settings snapshot: `adb shell am start -n com.glasslauncher.tv/.MainActivity --ez dump_settings true`, then `adb logcat -s GlassSettings`.
- Continue Watching from Nuvio Legacy, the Aerial Views button and silent updates need TV access (ADB): Settings → System.

## Tools
- `tools/appletv_icons.py <out>` — builds the Apple TV icon library from Apple's public iTunes Search API (the tvOS
  home-screen icon of each app, 1280×768) and renders dark / white / clear looks. The icons belong to their owners and are
  not included in this repository.
- `TV=<tv-ip>:5555 tools/push_appletv_icons.sh <out>` — copies the library to the TV
  (`Android/data/com.glasslauncher.tv/files/appletv/`).
- `tools/framestats.py` — summarises `adb shell dumpsys gfxinfo com.glasslauncher.tv framestats` per frame stage.

Terminal helpers (`adb shell am start -n com.glasslauncher.tv/.MainActivity …`): `--ez open_folder true`,
`--ei open_settings <tab>`, `--es icon_pack <pkg|none>`, `--es tile_style …`, `--es dock_style …`, `--ei blur <n>`,
`--es saver_mode aerial|slideshow|system`, `--ez start_saver true`, `--es continue_mode first|mixed|off`, `--ez dump_settings true`.

## Build
```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Privacy & security
- No accounts, analytics or ads. Settings stay on the TV (`allowBackup=false`).
- The phone page only runs while its screen is open and every request needs the one-time token in its QR code.
- Webhook, TMDB token and add-on links are only sent where you point them.
- The TV's adb is used only for: starting the screensaver, reading Nuvio's playback log, installing Glass updates.

## Credits
Inter typeface (SIL Open Font License) · Weather by [Open-Meteo](https://open-meteo.com) · Catalog data from your Nuvio add-ons ·
This product uses the TMDB API but is not endorsed or certified by TMDB. · Not affiliated with Apple, Google or Nuvio.

## Copyright, name and license
Copyright (C) 2026 mohammad (mrh12).

Glassio is free software under the [GNU General Public License v3.0](LICENSE) (GPL-3.0), the same license as Nuvio.
You may use, study, change and share the code under GPL-3.0. If you distribute a modified version, you must comply with the license, including its applicable source-code and notice requirements.

The GPL-3.0 license grants rights to the code; it does not automatically grant permission to use the Glassio name or logo in a way that suggests an independent build is an official release. See [TRADEMARKS.md](TRADEMARKS.md).
