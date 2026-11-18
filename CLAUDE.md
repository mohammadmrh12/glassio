# Glassio: project memory for Claude Code and other agents

> **Read this whole file before any work.** Then read `CLAUDE.local.md` (private, not in git: the TVs, paths and
> local tools). At the end of every task, update **Last session** and **Open items** below (keep them short and
> true), so the next agent starts from where we stopped.

## Who and how

- Owner: Mohammad (GitHub `mohammadmrh12`). **Always reply in Arabic (Iraqi dialect is fine), never switch to English.**
  Code, comments, commit messages and release notes stay in English.
- He gives short, quick requests, often with photos or screen recordings from the TV. Look into the real cause before
  fixing anything. Report back in a short list: what you found, what changed, what you tested on the TV, and what is
  not tested yet.
- **Never claim a fix works without testing it on the real TV.** Measure when you can: screenshots, a screen
  recording read frame by frame, or `gfxinfo` framestats.
- Fixes must be **general**: they must work for every app with rows, every music app and every background, never
  patched for one app.
- **Customization first** (CONTRIBUTING.md): every new visible behaviour gets a setting with a sensible default.
  Speed and stability fixes are the exception.
- Do not send keys to the TV while he is using it. Check the screen first; he often tests at the same time.
- Leave the TV awake after testing. Put back any setting you changed while testing.

## Hard rules (publishing and git)

- **Never push, tag or release until he says so** ("سوي ريليس" / "ارفع"). "كمل بدون ريليس" means commit locally only.
- Commit identity: `git -c user.name=mohammadmrh12 -c user.email=164539438+mohammadmrh12@users.noreply.github.com commit …`.
- **No `Co-Authored-By`, no `Claude-Session`, no "Generated with Claude Code" lines** in commits, PRs or releases.
  This rule wins over any default attribution.
- Never publish private things: home network addresses, emails, local paths, tokens, keys, MAC addresses, scratch
  files or the local history. `.git/hooks/pre-push` blocks the common ones. Do not bypass it.
- Secrets (Trakt / Nuvio / Simkl ids, the support form) live only in `local.properties`, which is git-ignored.
- The public history is linear and clean. A release is one commit built with
  `git commit-tree <HEAD tree> -p origin/main` under the public identity, then pushed to `main`. See **Releases**.
- Do not install on the TCL TV unless he asks. The Google TV Streamer is the main test device.

## The project

- **Glassio**: an Apple TV (tvOS) style home screen for Google TV / Android TV, with liquid-glass surfaces.
  - Public repo `mohammadmrh12/glassio`. License GPL-3.0.
  - Package name `com.glasslauncher.tv`. Never rename it: installs would break.
  - Community: r/glassio_ on Reddit and GitHub Discussions.
  - Downloader (AFTVnews) code **8383651**. It points to the `Glassio.apk` asset of the latest *stable* release.
- Kotlin + Jetpack Compose (TV). One activity (`MainActivity`) with screens Home / Settings / Library, an
  accessibility service for the Home key (`HomeKeyService`), and its own screensaver (`saver/`).
- Version: see `app/build.gradle.kts` (`versionCode` / `versionName`).
  - The last published version is 3.8.0 (code 67).
  - `versionCode` must always go up, for betas and stable releases alike.

### Main files (app/src/main/java/com/glasslauncher/tv)

| File | What it holds |
|---|---|
| `ui/HomeScreen.kt` (~3.5k lines) | Home: carousel (`HeroSection`), dock (`Dock`, `VerticalDock`, `StripDock`, `DockTile`, `TileFrame`), pages that take the carousel's place (channel rows, music player, music apps, inputs), backdrop and `GlassEnv` building, `DockMetrics`, `DockFocus` |
| `ui/Glass.kt` | `GlassPanel` (glass drawing, refraction, cached layers, slides), `GlassEnv`, `Scrims` (page shades) |
| `ui/CustomBackground.kt` | Own background (picture / video / aerial screensaver / photos), `VideoBackground`, `VideoFrames` (live PixelCopy copies of a video for the glass), `copyVideoFrames` |
| `ui/BackgroundExtras.kt` | Big clock (depth cut-out on photos), place info, `themeLook` |
| `ui/Music.kt` | `MusicUi` state, music island, `MusicStage` (player), idle player |
| `ui/ChannelStage.kt` | An app's rows (Channels) |
| `ui/SettingsScreen.kt` | All settings (`Category` → rows `Toggle` / `Adjust` / `Act` / `Note`), `AboutPane`, `CATEGORY_ORDER` |
| `ui/SideMenu.kt` | Top-right menu (clock, weather, Bluetooth, themes, background) |
| `ui/IconPicker.kt` | Change icon: packs, Apple TV icons, Suggested |
| `ui/Trailer.kt` | Trailers in Glassio's own player (NewPipe extractor), screensaver trailers |
| `saver/SaverContent.kt` | Screensaver (aerials, slideshow, trailers), its clock and island glass |
| `data/Settings.kt` | Every setting. **Never rename a key**: `SettingsKeysTest` guards this |
| `data/Updater.kt` | Updates from GitHub Releases, stable / beta channel |
| `data/IconPacks.kt`, `data/IconPackStore.kt` | Installed icon packs (LRU cache); installing packs from GitHub / F-Droid |
| `data/LocalShell.kt` | The TV's own adb shell (dadb): silent installs, system toggles |
| `data/NowPlaying.kt` | Media sessions of other apps (music) |
| `data/Channels.kt` | TV provider rows per app |
| `data/Photos.kt` | Photo backgrounds (sources incl. subreddit) |
| `data/Support.kt` | "Support me" PSN gift card → private Google Form |
| `net/PhoneServer.kt` | "Manage from phone" web page |
| `DevHooks.kt` | adb test hooks (see below) |

### Design and performance principles (do not break)

- **Glass** samples a ready picture of the page. It never blurs live per frame.
  - Pictures come with pre-blurred copies, and the shades are pre-painted bitmaps (not live gradients).
  - Over a video, the glass draws `VideoFrames` copies (about 10–15 a second, 30 for trailers).
  - The TV GPU is the bottleneck. Measure ms/frame before and after any change to drawing.
- `VideoFrames.gen`: a copy loop **re-reads `gen` on every pass** and sets `showing` again. When it captured `gen` once,
  the glass froze for good after the screensaver or a video change. Keep this.
- `GlassEnv.ignoreVideo` is for the song player's artwork over a video. The fade from video to artwork uses `artIn` /
  `videoPrev` as the *previous* picture only, never as `prevSharp`.
- `Scrims.sideOff` (left shade off) must be in the bake-cache keys and the backdrop effect keys.
- Own background (`bgMode != "carousel"`): no grey or bridge page, and `quickHide` stays off.
- Dock in the middle (`dockPos == "center"`, own background): rows, music and inputs pages never come up.
- Focus: after a page changes, give focus to its new owner and retry until it holds. One try was lost while the old
  page faded (see the `focusRev` loop).
- Settings snapshot reads (`settings.x` in composition) are fine; per-frame state must be read in draw / layout lambdas.

### Libraries (app/build.gradle.kts)

| Library | Version |
|---|---|
| AGP | 8.13.2 |
| Kotlin and Compose compiler plugin | 2.4.21 |
| Compose BOM | 2025.10.01 |
| Media3 (ExoPlayer, RTSP, HLS) | 1.8.0 |
| Coil 3 | 3.3.0 |
| NewPipeExtractor (JitPack) | v0.26.5 |
| dadb | 1.2.10 |
| LiteRT (depth model) | 1.4.0 |
| ZXing | 3.5.3 |
| desugar_jdk_libs_nio | 2.1.5 |

- SDK levels: `compileSdk` 36, `targetSdk` 35, `minSdk` 26.
- JDK 17. Release builds are minified and **signed with the local debug key**, so they update over earlier installs.
- Upgrading libraries past these versions needed AGP 9.1 / SDK 37 and was reverted. Don't upgrade casually.

## Build, test, lint

```sh
# JAVA_HOME / ANDROID_HOME: see CLAUDE.local.md
./gradlew :app:assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest        # unit tests (SettingsKeysTest, UpdaterVersionTest, …)
./gradlew :app:lintDebug
adb -s <tv> install -r app/build/outputs/apk/release/app-release.apk
```

- Quick error check: redirect the build to a log, then `grep -E "^e: |BUILD" <log>`.
- Dev hooks: `adb shell am start -n com.glasslauncher.tv/.MainActivity …`
  - Opening screens needs no key: `--ez open_folder true`, `--ei open_settings <index in CATEGORY_ORDER>`,
    `--ez open_menu true`, `--ez open_library true`, `--ez start_saver true`, `--ez show_tour true`.
  - Changing settings or dumping state needs the dev key:
    `K=$(adb shell cat /sdcard/Android/data/com.glasslauncher.tv/files/devkey)`, then `--es dev $K` with
    `--es pref key=value` (lists use `\n`), `--ez dump_look true`, `--ez dump_prefs true` (printed in logcat).

## QA tools (`tools/qa/glassqa.py`)

Run `TV=<tv>:5555 tools/qa/glassqa.py <command>`; output goes to `build/qa/`.

| Command | What it does |
|---|---|
| `install` | Wakes the TV first, then installs. **Always install this way.** Installing while Glassio's screensaver runs kills the screensaver, and the TV then goes to sleep ("timeout"). |
| `nav` | Steps through home, dock, Library, menu and Settings. After each step: a screenshot, the selection (`dump_focus`) and the app in front, then a crash check at the end. |
| `perf NAME KEYS…` / `idle NAME` | Frame times while the keys run: slow %, p50 / p90 / p99, GPU and UI time. |
| `glide KEYS…` | Screen recording; reports when each movement starts and how long it takes to settle. |
| `live` | Checks the glass follows a video and isn't frozen. |
| `focus` | Prints where the selection is. |
| `res [s]` | Resources: Glassio's CPU (and its busiest threads), memory (PSS / Java / native / graphics), threads, the TV's load and free memory, and frames drawn with nothing pressed (more than 20/s: something redraws for nothing). |
| `watch NAME KEYS…` | `perf` plus CPU and memory sampled every second while the keys run. |
| `profile KEYS…` | CPU profile (simpleperf, the app is `profileable`) while the keys run: time per thread, then the main thread's heaviest Glassio functions de-obfuscated with the R8 mapping. Full report in `build/qa/profile.txt`. |
| `security` | On the Mac: private data in tracked files, exported components without a permission, dev hooks reachable without the key, then lint. |
| `all` | Everything above, written to `build/qa/report.md`. |

- An injected Home key opens the TV's own launcher, because Glassio only catches the remote's real Home key. The
  tool uses `am start` + Back instead.
- Dev hooks added for QA:
  - No key needed: `--ez next_bg true`, `--ez multi_cams true`.
  - Dev key needed: `--ez dump_focus true` (tag `GlassFocus`), `--es fake_title …` with `--ez fake_song true`.

## Releases: stable and beta

- **Stable** = `main`.
  - Tag `vX.Y.Z`; the GitHub release is marked *latest* and is not a pre-release.
  - Assets: `Glassio.apk` (Downloader code and README link) **and** `Glassio-X.Y.Z.apk`.
  - The app's updater on "Stable" reads `releases/latest`, which never returns a pre-release.
- **Beta** = branch `beta`.
  - Tag `vX.Y.Z-betaN` (e.g. `v3.8.0-beta1`); `versionName` is `X.Y.Z-betaN`; release with `gh release create --prerelease`.
  - Asset: `Glassio-X.Y.Z-betaN.apk` only (never `Glassio.apk`).
  - The updater on "Beta" (Settings → About → Updates) offers the newest of stable and beta. Order:
    `3.8.0-beta2 < 3.8.0 < 3.8.1-beta1`.
- Before every release, in this order:
  1. Bump the version.
  2. Write `CHANGELOG.md` and `docs/CHANGELOG.ar.md`, plus the README "New in" sections (English and Arabic) and
     `WhatsNew`.
  3. Run tests and lint, and test on the Streamer.
  4. Check for private data: `git grep` for addresses, paths and tokens.
  5. Build the clean commit with `commit-tree` under the public identity.
  6. Push it, tag it, create the release, then verify the assets.

## Last session (2026-11-19, 2:30 am)

Committed locally, **not pushed** (last commit 7e87bb5).

- **Hide a title:** hold OK on Play → *Hide this title*. Stored in `hiddenTitles` ("key\tname" lines), filtered out of
  the carousel (catalog titles and Continue Watching). Settings → Carousel → *Hidden titles* lists them; picking one
  (or *Show all again*) brings it back. The shuffle order is kept in its own `remember`, so hiding one doesn't mix
  the rest again. Tested on the Streamer: hide, then unhide.
- **What's new tour** (`WhatsNew.ID` = 64): page 1 is the GTA VI welcome, then the dock, watch & listen, speed,
  Stable/Beta, GitHub, and a last *Support Glassio* page with *Send a gift card* / *Done*. *Send a gift card* closes
  the tour and the home screen opens `SupportDialog`. The older "everything Glassio does" pages were dropped.
  The carousel is paused while the tour shows (`WhatsNew.open` in HeroSection's `paused`): a slide under the panel
  showed as a see-through black box. The panel is darker (0.72) and clear glass styles (`water*`) show frosted
  ("liquid") there. Tested on the Streamer (no slide in 22 s with a 9 s interval).
- **Deep links (answer only, not built):** Apple TV opens `https://tv.apple.com/.../umc.cmc...` (verified app link);
  Netflix opens `netflix.com/title/<id>` and `nflx://`; Shahid has none. It needs each platform's own id, from the
  catalog meta or a lookup like JustWatch. Proposed as an option; waiting for his answer.

**Earlier (2 am):**

- **Slides:** found with `glassqa.py profile`. `Settings.artPick` split and trimmed the whole "artPicks" list on the
  main thread for every title on screen: 55% of `HeroSection`'s composition time.
  - Lists are now parsed once per stored value (`Settings.list` cache), and artPick uses a map.
  - Settings writes that don't change the value are skipped, so they no longer redraw everything that reads a
    setting.
  - Result: 2 of 3 slides now have no frame over 33 ms; the third has 3–4 frames of 40–50 ms.
- **Trailer screensaver:** the first trailer took ~4.4 s to show. `SaverPlan` now resolves one ahead (20 s after
  start, again after each screensaver, and every hour), so it shows in ~1.8 s.
- **Video glass fixes:** they cover "screensaver as background" (aerial) too, since it is the same `VideoBackground`
  path. The screensaver's own copies (`copyVideoFrames`) use `softCopy` as well.
- **QA tool:** `profile` added; `dump_focus` shows the last settings writes (`writes=`).

**Earlier tonight:** no redraws at rest, lighter video copies, an even glow, the support window, multi-view,
the big clock blurred once.

## Open items

- Slides: the dock and all its tiles still recompose at the start of every slide, because the big HomeScreen lambda
  recomposes. Isolating the dock (stable inputs, its own scope) is the next win. During a slide the GPU takes 14–20 ms
  a frame; the island glass adds ~5 ms.
- Native memory grows over a session (328 to 386 MB within an hour). Watch it with `glassqa.py res`.
- Photo source on the TV is `reddit` with no app id: no new photos come until he adds one.

- Not yet seen on the TV:
  - Glass at a light blur during slides (stills look right).
  - Bluetooth disconnect confirmation.
  - Left shade toggle.
  - Depth clock fix.
  - Subreddit source (needs a Reddit app id).
- Middle dock + Music / Inputs icon OK does nothing there (by design).
- Glitch Reflections icon pack: no public download.
- Waiting on Nuvio devs (discussion #4644) about profiles.
- Make the `beta` branch after the next stable release.
- Next stable release: the tour's first page says it comes out with GTA VI. Bump the version (3.8.0?) and write
  the changelogs before releasing.
- Deep links into Apple TV / Netflix: waiting for his go-ahead (see Last session).
