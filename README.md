<img src="docs/brand/readme_banner.png" width="100%" alt="Glassio">

<p align="center">
  <a href="https://www.reddit.com/r/glassio_/"><img src="https://img.shields.io/badge/Reddit-r%2Fglassio__-FF4500?style=for-the-badge&logo=reddit&logoColor=white" alt="Join r/glassio_ on Reddit"></a>
  <a href="https://github.com/mohammadmrh12/glassio/releases/latest"><img src="https://img.shields.io/github/v/release/mohammadmrh12/glassio?style=for-the-badge&label=Download&color=6E5BFF" alt="Download the latest release"></a>
  <a href="https://github.com/mohammadmrh12/glassio/discussions"><img src="https://img.shields.io/badge/Discussions-GitHub-2EA2C4?style=for-the-badge&logo=github&logoColor=white" alt="Discussions"></a>
</p>

**An Apple TV–style home screen for Google TV / Android TV**: real liquid glass, a carousel of your Nuvio and Stremio
catalogs with trailers, each app's channels, music mode, Continue Watching from Nuvio, Stremio, Trakt and Simkl, a 4K /
HDR screensaver, Hue lights, cameras, Home Assistant, and sync between your TVs. Built with Kotlin and Jetpack Compose.
Tested on the Google TV Streamer (4K).

[العربية — دليل الاستخدام الكامل](docs/README.ar.md) · [Discussions](https://github.com/mohammadmrh12/glassio/discussions) · [Report a bug](https://github.com/mohammadmrh12/glassio/issues/new/choose) · [Contributing](CONTRIBUTING.md) · [Changelog](CHANGELOG.md)

![Home](docs/screenshots/50-home.jpg)

| | |
|---|---|
| ![Trailers screensaver](docs/screenshots/72-screensaver-trailers.jpg) | ![Petrol glass](docs/screenshots/71-dock-petrol-glass.jpg) |
| ![Dock with glow](docs/screenshots/51-dock-glow.jpg) | ![Library](docs/screenshots/52-library-continue-watching.jpg) |
| ![Menu with TV info](docs/screenshots/53-menu-tv-info.jpg) | ![Screensaver, Netflix style](docs/screenshots/54-screensaver-netflix.jpg) |
| ![Setup: Hello](docs/screenshots/55-setup-hello.jpg) | ![Setup: Connect](docs/screenshots/57-setup-connect.jpg) |
| ![Carousel](docs/screenshots/02-carousel.jpg) | ![Apps folder](docs/screenshots/05-apps-folder.jpg) |
| ![Petrol glass](docs/screenshots/70-home-petrol-glass.jpg) | ![Carousel](docs/screenshots/81-carousel.jpg) |
| ![Trailer screensaver menu](docs/screenshots/73-screensaver-trailer-menu.jpg) | ![What's new tour](docs/screenshots/77-tour-whats-new-3-3.jpg) |
| ![Themes](docs/screenshots/41-menu-themes.jpg) | ![Black on white](docs/screenshots/45-theme-black-on-white.jpg) |

---

## Features

Latest: **3.6.0** — sync between your TVs, Nuvio profiles, a new settings button. What changed in every version:
[CHANGELOG](CHANGELOG.md).

- **Home screen** — an endless carousel from your Nuvio / Stremio catalogs with trailers (subtitles in your language),
  logos, backdrop galleries and Continue Watching; opens titles in Nuvio or Stremio.
- **Liquid glass** — real refraction, six themes, a glass colour (black or petrol), bold text, round or sharp corners.
- **Dock & icons** — tvOS-style focus, glow and bounce, icon packs and the Apple TV icon library, an Apps folder, app lock.
- **Channels** — select YouTube, Shahid, Netflix… in the dock and its rows take the carousel's place.
- **Music** — Now Playing from any music app: a full-screen music mode, a dynamic island and the Music icon.
- **Library & accounts** — Continue Watching and lists from Nuvio (any profile), Stremio, Trakt and Simkl, kept in step.
- **Screensaver** — 4K / HDR / Dolby Vision aerials, trailers or posters, with the clock and info in Apple or Netflix style.
- **Backgrounds** — 4K photos, your own picture or video, a large glass clock with a depth effect.
- **Sync between TVs** — one main TV, the others follow it live over your home network; each chooses what it takes.
- **Smart home** — Hue lights that follow the screen, cameras and multi-view, a doorbell pop-up, Home Assistant.
- **TV & remote** — HDMI inputs, any remote button mapped, the Hold Home app switcher and recent apps.
- **Menu** — a Control Center at the settings button: weather, TV info, a camera, inputs, themes, music.
- **Easy to live with** — first-run setup with QR sign-ins, backup and restore, managing from your phone, 9 languages,
  updates from GitHub, performance presets for weaker TVs.

---

## Install

1. Download `Glassio-x.y.z.apk` from [Releases](https://github.com/mohammadmrh12/glassio/releases).
2. Install it on the TV: with the **Downloader** app (enter the release link), a USB stick, or from a computer:
   ```sh
   adb connect <tv-ip>:5555
   adb install -r Glassio-x.y.z.apk
   ```
3. Open **Glassio** from the TV's apps. The first time, the setup walks you through accounts, catalogs and the look.

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
Continue Watching from Nuvio Legacy, Glassio as the TV screensaver, TV info (CPU / GPU), open apps, starting with the TV
and silent updates use the TV's own
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
Settings → **Screensaver**:
1. *Type*: **Aerial videos**, posters with logos (OK opens the title in Nuvio), **Trailers** (the carousel's
   trailers with their logo and details; OK opens the title, hold OK for a menu), or the TV's own screensaver app.
2. Turn on the collections you like (Apple tvOS, Amazon Fire TV, Jetson Creative, Robin Fourcade) and/or **My videos**
   (your links and the videos in the backgrounds folder; upload them from your phone). *Quality*: 1080p H.264, 1080p HEVC,
   4K, 1080p HDR, 4K HDR / Dolby Vision — videos without that quality play the nearest one.
3. **Glassio is the TV screensaver (ADB)**: it then starts in every app when the TV is idle, after *TV screensaver starts
   after*. Otherwise *Start after* sets the idle time on the home screen.
4. *Keep videos on the TV* saves each video as it plays (up to the space you choose), so it starts at once next time.
5. While it plays: **◀ ▶** change the video, **OK** shows or hides the info, any other key ends it.

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
- Settings → **Connections → Home Assistant webhook**, or paste it on the phone page (Settings → *Manage from phone*).
- Turn on **Tell Home Assistant when a title opens**, then **Send a test event** (sends `{"event":"test"}`).

Use the IP address of Home Assistant (the TV may not resolve `homeassistant.local`). `local_only: true` keeps the webhook
on your home network.

### Doorbell pop-up

Settings → **Cameras → Doorbell pop-up**. When the doorbell rings, the camera shows in the corner over any app, for as
long as you set, with an optional chime and words at the bottom. **Try it** shows it straight away.

**Easiest — no automation.** Under *Straight from Home Assistant*, add the Home Assistant link, a **long-lived access
token** (your profile → Security; easier to paste from *Manage from phone*) and pick the **doorbell sensor**. Glassio
watches that sensor itself (a binary sensor turning on, or an `event.` entity firing).

**Or from an automation.** Home Assistant sends the same request PiPup takes to `http://<tv-ip>:7979/notify`:

```yaml
# configuration.yaml
rest_command:
  glassio_doorbell:
    url: "http://<tv-ip>:7979/notify"
    method: POST
    content_type: "application/json"
    payload: '{"camera": "Front door", "duration": 30, "title": "Front Door", "message": "Someone is at the door"}'
```

Then call `rest_command.glassio_doorbell` from the automation that runs when the doorbell rings.

`camera` is the name of one of your cameras in Glassio (or send `video_url` with a stream link). Everything is optional:
without it, Glassio uses the camera and words chosen in Settings. Keep port 7979 on your home network.

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
- Settings snapshot: see *Testing from a computer* in [CONTRIBUTING.md](CONTRIBUTING.md) (`--ez dump_prefs true`, then `adb logcat -s GlassPrefs`).
- Continue Watching from Nuvio Legacy, Glassio as the TV screensaver, open apps and silent updates need TV access (ADB): Settings → System.
- Stuck somewhere? Settings → System → **Back up now** keeps everything; *Restore backup* brings it back after a reinstall.

## Tools
- `tools/appletv_icons.py <out>` — builds the Apple TV icon library from Apple's public iTunes Search API (the tvOS
  home-screen icon of each app, 1280×768) and renders dark / white / clear looks. The icons belong to their owners and are
  not included in this repository.
- `TV=<tv-ip>:5555 tools/push_appletv_icons.sh <out>` — copies the library to the TV
  (`Android/data/com.glasslauncher.tv/files/appletv/`).
- `tools/framestats.py` — summarises `adb shell dumpsys gfxinfo com.glasslauncher.tv framestats` per frame stage.

Terminal helpers (`adb shell am start -n com.glasslauncher.tv/.MainActivity …`): `--ez open_folder true`,
`--ei open_settings <tab>`, `--ez open_menu true`, `--ez open_library true`, `--ez start_saver true`, `--ez show_tour true`. Changing settings
from a computer needs the dev key (so other apps can't): see [CONTRIBUTING.md](CONTRIBUTING.md#testing-from-a-computer).

## Build
```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
```

## Privacy & security
- No accounts, analytics or ads. Settings stay on the TV (`allowBackup=false`).
- The phone page runs while its screen is open (and for the time you choose after), and every request needs the one-time
  token in its QR code. Changing settings from a computer needs a dev key only adb can read.
- Backups contain your sign-ins: keep the file private.
- Webhook, TMDB token and add-on links are only sent where you point them.
- The TV's adb is used only for: the screensaver settings, reading Nuvio Legacy's playback log, TV info (CPU / GPU), open
  apps, starting with the TV and installing Glassio updates.

## Credits
Inter typeface (SIL Open Font License) · Weather by [Open-Meteo](https://open-meteo.com) · Catalog data from your Nuvio add-ons ·
This product uses the TMDB API but is not endorsed or certified by TMDB. · Not affiliated with Apple, Google or Nuvio.

## Copyright, name and license
Copyright (C) 2026 mohammad (mrh12).

Glassio is free software under the [GNU General Public License v3.0](LICENSE) (GPL-3.0), the same license as Nuvio.
You may use, study, change and share the code under GPL-3.0. If you distribute a modified version, you must comply with the license, including its applicable source-code and notice requirements.

The GPL-3.0 license grants rights to the code; it does not automatically grant permission to use the Glassio name or logo in a way that suggests an independent build is an official release. See [TRADEMARKS.md](TRADEMARKS.md).
