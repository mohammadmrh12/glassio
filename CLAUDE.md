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
- **A fix goes everywhere** (his rule since 2026-11-24): after fixing something, look through the app for the same
  pattern (a row that removes itself, a video under something that covers it, a key release swallowed …) and fix
  every place in the same piece of work; say which places were found.
- **Customization first** (CONTRIBUTING.md): every new visible behaviour gets a setting with a sensible default.
  Speed and stability fixes are the exception.
- **Always keep working on the TV, even while he is browsing or playing something** (his rule since 2026-11-19, 9 pm):
  install, send keys and measure without waiting for him to stop. Still put back what you changed, and never press
  Send / Delete / Sign out or anything that can't be undone on a screen he opened himself.
- **Act without asking** (his rule since 2026-11-24: "ثبت اي شي تريده بدون ما تسالني", "سوي اي شي تريدة بدون ما
  تسالني"): install apps or builds for a test, run test servers, decide the details myself; remove what I installed
  after. **No guesses anywhere**: a cause is "found" only once it was seen on the TV, else say it is from the code.
  Still never Allow on the TV's USB debugging window, sign in or change things in his accounts myself; releases as below.
- **Issues:** reply as needed, but **always read the issue's newest comments again right before commenting** (his
  rule since 2026-11-24): edit my earlier comment or fix the new problem first, then reply. Only tested facts.
- **Every message to him in Arabic**, progress notes too; English only for code and copy-ready text.
- **No `nav` check any more** (his rule since 2026-11-25: "لا تسوي فحص نافكيشن بعد"): test only the part that changed on the TV.
- Leave the TV awake after testing. Put back any setting you changed while testing.
- **Test lightly on the TV** (his rule since 2026-11-20; "never claim it works without the TV" still stands):
  - A setting that only changes a look: one screenshot per choice is enough, not a grid of every choice × every
    page × every background. More only where the code really takes another path.
  - Speed is measured only for changes that touch drawing while something moves (the carousel, the glass,
    scrolling), and two runs (before / after) are enough; a third only when the two contradict each other.
  - Never take the same screenshot again unless it failed. `nav` once at the end of a batch of requests, not after
    each one.

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
  - Community: GitHub Discussions (r/glassio_ was banned by Reddit; its links were removed in 4.0.0).
  - Downloader (AFTVnews) code **8383651**. It points to the `Glassio.apk` asset of the latest *stable* release.
- Kotlin + Jetpack Compose (TV). One activity (`MainActivity`) with screens Home / Settings / Library, an
  accessibility service for the Home key (`HomeKeyService`), and its own screensaver (`saver/`).
- Version: see `app/build.gradle.kts` (`versionCode` / `versionName`).
  - The last published version is **4.4.2 (code 80, stable, 2026-11-24)**, public `c42674e`, sha256 `729040a0…d623fde`; 4.4.1 (code 79, `d81b8f9`); 4.4.0 (code 77, `4064a51`); 4.3.0 (code 76, stable, 2026-11-23): public `9af8b7c` on `origin/main`
    (parent ea23eb4, the privacy policy on 4.2.1), tag `v4.3.0`, *latest*, `Glassio.apk` and `Glassio-4.3.0.apk`
    (sha256 `5c97d378…e92f4241`, downloaded and compared, also through `releases/latest/download/Glassio.apk`; the
    same file is on the Streamer). 4.2.1 (code 74, `85cfac8`) before it.
    4.2.0 (code 73, `86aad11`) before it.
    `origin/beta` stays at `4e92df7` (4.1.1-beta1, code 72). 4.1.0 (code 71) before it.
    4.0.0 (code 69) before it. Work goes on on the local `beta` branch.
  - `versionCode` must always go up, for betas and stable releases alike.

### Main files (app/src/main/java/com/glasslauncher/tv)

| File | What it holds |
|---|---|
| `ui/HomeScreen.kt` (~1.2k lines) | Puts the home screen together: which page shows behind the dock, the backdrop and `GlassEnv` building, focus between the parts, menus and dialogs |
| `ui/HomePage.kt` | `homePage()`: what stands in the carousel's place (carousel, an app's rows, the player, an idle player, the music apps, the inputs) and how the page behind the dock is drawn for it, worked out in one place. The selection itself still lives in `ChannelsUi`, `MusicUi` and `InputsUi` |
| `ui/Dock.kt` | The dock (`Dock`, `VerticalDock`, `StripDock`, `DockGlass`), its icons (`DockTile`, `TileFrame`, `TilePlate`, `FolderTile`), the Apps folder, `DockMetrics`, `DockFocus` |
| `ui/Hero.kt` | The carousel: `HeroSection`, `SlideBackdrop` / `FadeBackdrop`, `HeroInfo`, `HeroButtons`, the dots |
| `ui/TopBar.kt` | The settings button and the update notice |
| `ui/PagePictures.kt` | Page pictures: `imgReq`, `bakeBackdrop`, `BakeCache`, `GlassHalf`, the prepare-ahead thread, `ChannelPages` (rows' backgrounds), `StagePages` (the music apps' / inputs' page: grey, Glassio's lights, the carousel's picture blurred) |
| `ui/HomeStages.kt` | The music apps page and the inputs page (`MusicAppsStage`, `InputsStage`, `InputsUi`) |
| `ui/Glass.kt` | `GlassPanel` (glass drawing, refraction, cached layers, slides), `GlassEnv`, `Scrims` (page shades) |
| `ui/CustomBackground.kt` | Own background (picture / video / aerial screensaver / photos), `VideoBackground`, `VideoFrames` (live PixelCopy copies of a video for the glass), `copyVideoFrames` |
| `ui/BackgroundExtras.kt` | Big clock (depth cut-out on photos), place info, `themeLook` |
| `ui/Music.kt` | `MusicUi` state, music island, `MusicStage` (player), idle player |
| `ui/ChannelStage.kt` | An app's rows (Channels) |
| `ui/SettingsScreen.kt` | All settings (`Category` → rows `Toggle` / `Adjust` / `Act` / `Note`, sub-pages, link rows), `AboutPane`, `CATEGORY_ORDER` (see **Settings layout**) |
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
| `net/PhoneServer.kt`, `net/PhonePage.kt` | "Manage from phone": the small LAN server (requests, state, uploads) and the page it serves (see **Manage from phone**) |
| `data/PlatformLinks.kt`, `data/PlatformRows.kt`, `data/Region.kt` | A title's own page in a streaming app, the Library's rows per streaming service, the country both read (see **Title links**) |
| `data/AppCatalogs.kt` | Catalogs of an app: the carousel shows an app's own catalogs while it is selected in the dock (see **Catalogs for apps**) |
| `data/Report.kt`, `tools/report-worker/` | A problem report from the TV to GitHub Issues, and the relay that opens the issue (see **Problem reports**) |
| `data/Search.kt`, `ui/SearchStage.kt` | Search: the titles, where each can be watched, Stremio's sources; the Search icon's page above the dock (see **Search**) |
| `data/I18n.kt`, `ui/Texts.kt`, `assets/i18n/`, `tools/i18n/` | The languages: the table, the Text every screen uses, each language's words, and the scripts that collect and merge them (see **Languages**) |
| `data/Bg.kt` | `Bg.io("name") { … }`: the one scope for background work that belongs to the app (servers, watchers, a request off the main thread). No hand-made `Thread`s: only the named single-thread executors with a low priority stay (`glass-prep`, `glass-blur`, `glass-trailers`, `glass-depth`, `glass-chan-blur`, Hue's pool) |
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
- `Scrims.sideOff` / `Scrims.bottomOff` (the page's shades off) must be in the bake-cache keys and the backdrop
  effect keys: use `Scrims.key` for both. They are snapshot state (as a plain value the switch did nothing until the
  next picture: the drawn page stayed). The slide shaders (`GLASS_SLIDE_SKSL`, the dots') paint the shades by
  arithmetic: their `side` / `bottom` uniforms must follow the same two switches.
- **Carousel shade** (`carouselShade`, "on" default / "off"; Settings → Carousel): both shades of the carousel page
  (left and bottom), set in `HomeScreen` beside the own background's *Left shade* (`sideShadeOff`, which still only
  counts with `bgMode != "carousel"`). One switch for both: simplest, and the writing has its own protection.
  With the left shade off (either switch) the carousel's writing gets a soft black shadow, its grey lines turn
  white and the logo gets its own shape in black just under it (`HeroInfo`, `Title`); the buttons keep plain text.
- **The Library's scrolling is one movement** (`Glide` in `ui/LibraryScreen.kt`, for the page and for the Continue
  Watching row). Compose's "bring into view" is only asked where the selected row belongs (`PivotScroll` /
  `RowScroll` answer 0 and hand the distance to the `Glide`); it never animates here. It started a new animation for
  every step of the selection: holding Down, the page's speed went 117 → 1 → 117 px a frame five times a second
  (the stutter; frames were fine, 60 a second). The `Glide` follows a target on a critically damped spring: a single
  press puts the target on the row at once (top speed after ~0.1 s, about half a second in all); a held key moves the
  target at the steady speed of the key's beat, so the page never slows between rows; letting go softens the spring
  and it eases onto the row reached; the end of the list is eased into (`room`). The beat (`LibPace`): a row every
  280 ms, every 180 after five rows, a card or poster every 140 ms, each landing on the TV's own key repeats (every
  200 ms on the Streamer: a row per 400 ms, then per 200). Don't put a per-step animation back.
- **The strip dock keeps its selection as the app** (`selKey` in `StripDock`), never as a place in the list: places
  are counted from the selected app and `pos - shift` is only how far the strip still has to slide. Kept as a place
  (and a count of steps taken round a list of another length), any change of the list (hide, show, install,
  remove, reorder) left the selected app off to one side until the dock was built again.
- **The glass colour** (`GlassTint`) is drawn by every glass surface for itself, except one that sits on a glass
  panel (`LocalOnGlass`, given by `GlassPanel` to its content): there the panel under it carries the colour
  already, so a clear plate on the dock adds none, and a non-bending surface may leave it out while the page
  slides. A surface on the page itself must always draw it (the strip dock, the carousel's buttons, a dock with
  refraction at 0): going by "bends nothing" instead, those lost the colour for the length of every slide.
- **A glass surface on the home page reads the picture by its place in the page** (`GlassPage.placeOf` in
  `ui/Glass.kt`; the home screen gives the page's coordinates from inside its sliding layer), in whole pixels,
  never by its place on screen: the page slides to and from the Library with everything on it, so that place is
  the same at rest, in every frame of the slide and while the page is away. Surfaces outside the page (menus, the
  Library, pop-ups) keep their place on screen. The earlier way (place on screen, frozen while the page slid and
  shifted by a screen while it was away: `GlassMotion`, removed) left a surface made or moved during the slide on
  a wrong place until the page settled. Don't freeze places again, and don't use `positionInRoot` for glass on the page.
- Own background (`bgMode != "carousel"`): no grey or bridge page, and `quickHide` stays off.
- Dock in the middle (`dockPos == "center"`, own background): rows, music and inputs pages never come up.
- Focus: after a page changes, give focus to its new owner and retry until it holds. One try was lost while the old
  page faded (see the `focusRev` loop).
- **A freshly installed build runs in Android's interpreter** (`dumpsys package` → *Dexopt state*: `verify`): the
  main thread is several times slower and every page change has frames of 100-240 ms. The TV compiles only from a
  profile (`speed` asked for still gives `speed-profile`), and only when it is idle. So: `app/src/main/baseline-prof.txt`
  puts all of Glassio's own code in the APK's profile; the github build asks the TV's shell to compile once per
  installed build, a minute after its start (`glass-compile` in `GlassApp`; the compiled code is used from the
  next start of Glassio); `glassqa.py install` applies the profile and compiles too (`--no-compile` skips it).
  **Never measure speed on a build whose state is `verify`**: restart Glassio after the compile first.
  Half of 2026-11-21's "slow" numbers were this.
- Background work goes through `Bg.io` (an error nobody caught is logged there). A `runCatching` whose failure
  matters ends in `.onFailure { Glog.w("what", it) }`; the bare form is for things that may fail by design (a focus
  request, closing a socket).
- Settings snapshot reads (`settings.x` in composition) are fine; per-frame state must be read in draw / layout lambdas.

### Libraries (app/build.gradle.kts)

| Library | Version |
|---|---|
| Gradle wrapper | 9.9.1 |
| AGP | 9.5.1 (built-in Kotlin: no `kotlin-android` plugin) |
| Kotlin and Compose compiler plugin | 2.4.21 (set by the `buildscript` classpath line in `build.gradle.kts`) |
| Compose BOM | 2026.09.00 |
| activity-compose / lifecycle-runtime-compose / core-ktx | 1.13.0 / 2.11.0 / 1.19.1 |
| Media3 (ExoPlayer, RTSP, HLS) | 1.11.1 |
| Coil 3 | 3.6.3 |
| NewPipeExtractor (JitPack) | v0.27.1 |
| dadb | 2.0.0 |
| LiteRT (depth model) | 1.4.2 (kept on 1.x, see below) |
| ZXing | 3.5.4 |
| desugar_jdk_libs_nio | 2.1.5 |

- SDK levels: `compileSdk` 37, `targetSdk` 37, `minSdk` 26. Build tools 36.0.0.
- JDK 17. Release builds are minified and **signed with the local debug key**, so they update over earlier installs.
- All of the above were the latest stable on 2026-11-19 (checked on Google Maven / Maven Central, not from memory).
- **LiteRT stays on 1.x.** 2.x pulls `com.google.android.play:ai-delivery` (a beta) with WorkManager and Room: the
  app crashed at start (`WorkManagerInitializer`, Room could not build `WorkDatabase` under AGP 9's strict R8) and
  the APK grew by 3 MB, for one `Interpreter` call.
- AGP 9 keeps only what a keep rule names (`-keep class X` no longer keeps its constructor): write `{ <init>(...); }`.
- targetSdk 37 only changes behaviour on Android 16/17 TVs, and the Streamer runs Android 14, so these are **not
  tested on a device**: the local network permission (`ACCESS_LOCAL_NETWORK`, asked once in `MainActivity`), Back
  kept as a key (`enableOnBackInvokedCallback="false"`), certificate transparency off for `sylvan.apple.com`.
- New Compose lint: a `FocusRequester()` made in composition must be remembered. Per-key requesters kept in a
  remembered map go through `requester(key)` (`ui/Tv.kt`).

## Profiles (`data/Profiles.kt`)

One Glassio per profile of the Nuvio account. A profile's number is Nuvio's (`profile_index`, 1 = main).

- **Where things are kept.** The profile in use before profiles existed (the *home* profile, usually 1) keeps the
  old files as they are: nothing was moved, so the update lost nothing. Any other profile `N` has its own files:
  `glass_pN` (settings), `glass_catalogs_pN`, `glass_cw_pN`, `glass_library_pN`, `titles_seen_pN`.
  Which profile is in use, the home profile, the start mode and the known profiles are in `glass_profiles`.
- **Per profile:** everything in the `glass` settings that isn't listed as the TV's: theme and look, the dock (order,
  hidden apps, size, style), carousel and titles, background, screensaver, music and island, channels' rows, menu,
  Library and Continue Watching options, likes, hidden titles, picked artwork, language, the settings button's
  picture. Plus the four files above (catalog choices, Continue Watching, Library, "new" titles).
- **The TV's, shared by every profile** (`Profiles.isDevice`): performance and caches (`perfMode`, `pageScale`,
  `memTier`, `glassRes`, `imageQuality`, `prefetchDepth`, cache sizes, video qualities), everything `hue*`,
  `doorbell*` and `ha*`, cameras and multi-view, the remote's buttons (`keyMaps`, `homeLong`, `multiKey`,
  `cornerKey`), inputs, Nuvio's link (`nuvioPush`, `nuvioToken`, `nuvioTarget`), start-up and the Home button,
  the phone page, `updateChannel`, sync and backup options, the system screensaver switch, app locks, and the
  keys of outside services (`tmdbToken`, `unsplashKey`, `redditAppId`). Also every other file: the accounts
  (`glass_nuvio`, `glass_trakt`, `glass_simkl`, `glass_stremio`), `glass_peer`, `glass_sync`, `glass_inputs`,
  `glass_recents`, `glass_home_key`.
- **A new profile** starts as the home profile looks (`Profiles.seed`), without what is personal (likes, hidden
  titles, picked artwork, its picture) and with the other accounts' lists off (Trakt, Simkl, Stremio: they are one
  person's).
- **Changing profile restarts Glassio** (about 2 s on the Streamer): the profile is read once at start, so no part
  has to tell profiles apart while running. `Profiles.choose(ctx, id)` then `Backup.restart`.
- A new setting is per profile unless its key is added to `DEVICE` in `Profiles.kt` (a test checks those keys exist).
- **Choosing** (`ui/ProfileChooser.kt`, `ProfilesUi`): "Who is watching?" over everything, on the setup's soft
  lights. Circles in a row with the name under each; OK opens a profile; holding OK makes it the automatic one;
  the button under the row switches *At start* between Ask and Automatic. It takes the remote's keys itself (like
  the What's new tour), never the selection, so what was selected under it stays selected.
  - Shown from the menu's *Profile* row (`menuProfile`, only when the account has more than one profile) and from
    Settings → Connections → *Profile*; *At start* is set there too. Default: Automatic on the home profile.
  - A start of the TV (`Profiles.newBoot`) or a wake after more than 5 minutes off (`GlassApp`'s screen receiver)
    calls `ProfilesUi.wake`: Ask shows the chooser, Automatic goes back to its profile if another is in use.
    Glassio's own restarts never ask.
  - A profile's picture is its settings button's picture (`Profiles.picture`, kept with that profile's icons), else
    Nuvio's picture (`data/NuvioAvatars.kt`), else Nuvio's colour with the first letter.
  - **Nuvio's pictures.** `sync_pull_profiles` gives `avatar_url` (an uploaded one, usually null) and `avatar_id`
    (one of Nuvio's ready-made ones: his are `avatar_moss` and `avatar_tommy_shelby`, shown in Nuvio as "Moss" and
    "Rowan"). The ready-made ones are listed by the rpc `get_avatar_catalog` (anon key: `id`, `display_name`,
    `storage_path`, `bg_color`) and served at `<backend>/storage/v1/object/public/avatars/<storage_path>`; the
    supporters' ones by `get_member_profile_avatar_catalog` and the `membership-profile-avatars` storage, with the
    account's token (the same way Nuvio's own TV app does it, `AvatarRepository.kt` in NuvioMedia/NuvioTV).
    They are kept in `files/nuvio_avatars` (`id_<avatar id>` / `url_<hash>`), the colour behind a ready-made one with
    the known profiles (`avatarId`, `avatarBg`). The lists are only read when a picture or its colour is missing;
    a picture no profile uses any more is deleted. Fetched whenever the profiles are read (chooser, setup) and 6 s
    after the start. Shown in the chooser, on the setup's profile buttons, and as the settings button when it is
    set to *Your picture* and none was chosen in Glassio.
  - **The settings button turns into it by itself** (`settingsIconNuvio`, on; Look & menu → *Settings button* →
    *Nuvio's picture by itself*): when the profile in use has Nuvio's picture ready and `settingsIcon` was never
    saved in this profile, the top bar writes `settingsIcon = "picture"` once (log "Settings button: Nuvio's profile
    picture"; asked again whenever `NuvioAvatars.rev` changes). A look chosen by hand (saved) is never changed. A new
    profile starts without `settingsIcon` (`NOT_COPIED`), so it gets its own Nuvio picture. QA (dev key):
    `--es pref_unset <key>` removes one saved setting.
  - **The menu's greeting** (`menuHello`, off by default (the code and `DefaultsTest`); Settings → Menu & themes → *Greeting*): "Hello, <name>" at
    the top of the menu with the profile's picture (`Face`), for the profile in use, with the name as Glassio shows
    it. It goes with the settings button above and never repeats it (`menuGreeting` in `ui/SideMenu.kt`): under a
    *Hello* / *hello* button only the name, under a *Your picture* button no picture. No known profile (not signed
    in to Nuvio): nothing.
  - Nuvio sends per profile: `profile_index`, `name`, `avatar_color_hex`, `avatar_id`, `avatar_url`, `pin_enabled`,
    `uses_primary_addons`, `profile_background_*`. A profile with a PIN in Nuvio can't be opened from Glassio (there
    is no way yet to check the PIN).
- **Setup:** after the Nuvio sign-in, when the account has more than one profile, a page asks who is watching
  (or "Ask every time"). It is applied at the end: Glassio opens as that profile with the look chosen in the setup.
- **Name and picture:** Settings → Connections → *Name* is kept in Glassio (`glass_profiles`, `nameN`) and comes
  before Nuvio's; *Picture* is the settings button's picture (one file for both, per profile). Neither is written
  to Nuvio yet: he asked not to change his real profile for a test, and the write call isn't known.
- **Backups** carry every profile (every file whose name starts with `glass`); a restore also drops a profile the
  backup doesn't have. Custom icons and pictures are files, not in backups (as before).
- **Sync between TVs** is per profile: a follower asks with `&p=<its profile>` and gets that same profile as the
  main TV keeps it (the TV's own settings plus that profile's), whichever profile the main TV shows. A profile the
  main TV never opened gives "missing": the follower keeps its own and says so. What a follower has taken
  (`applied`, `iconSigs`) is remembered per profile. A follower from before profiles sends no `p` and gets the main
  TV's profile in use, as before.
- Test: `--ei profile N`, `--es profiles_fake "2:Kids:#FF9F0A,3:Guest:#34C759"` (`-` removes them),
  `--ez profile_ask true`, `--ez profile_wake true`, `--ei profile_forget N`, `--ez nuvio_profiles true` (field names
  only), `--es sync_follow "address:port:code"`, `--ez sync_stop true`, `--ez sync_now true`, `--ez sync_status true`,
  `--ez nuvio_faces true` (Nuvio's pictures even where one was chosen in Glassio, until a restart; `false` ends it),
  all with the dev key (`nuvio_profiles` now also prints the picture ids and colours); `--ez show_setup true` needs none; `dump_focus` prints `profile=`, `home=`, `chooser=`, `chooserSel=`.

## Title links (`data/PlatformLinks.kt`)

Settings → Catalogs & add-ons → *Play opens the streaming app* (`playInApp`, off by default; one switch with the app carousels' Play since the settings were reorganised, see **Settings layout**): Play opens the title's own page
in Netflix, Apple TV, Prime Video or Disney+ when one of the installed ones has it. Anything else opens as before.

- **Links the apps take** (checked with `cmd package query-activities` and by opening them):
  - Netflix `https://www.netflix.com/title/<id>` with the extra `source=30`.
  - Apple TV `https://tv.apple.com/us/<movie|show>/<any-slug>/umc.cmc.…`.
  - Prime Video `https://watch.amazon.com/detail?gti=…` or `?asin=…` (also `app.primevideo.com`).
  - Disney+ `https://www.disneyplus.com/browse/entity-<id>` (from JustWatch; the app is on neither TV).
  - Shahid takes `https://shahid.mbc.net/<lang>/series|movies|shows|player/…`, but no source gives its ids yet.
- **Where the id comes from** (the catalogs only carry IMDb ids):
  - Wikidata (open data), used only for the app the title is listed on (`HeroItem.platform`): it says an app has a
    page for the title, not that it can be watched there (Netflix numbers of titles it dropped, Apple TV pages of
    other services' shows).
  - JustWatch's site search (`apis.justwatch.com/graphql`, **not an official service**; `linkJustWatch`, on by
    default once the feature is on): subscription / free / with-ads offers in the country, for any installed app.
    A failure (no answer, a changed answer, a country it doesn't know) is logged and JustWatch is left alone for
    10 minutes: Play opens the usual way at once, and nothing is remembered for that title.
    Of 40 popular titles per platform Wikidata had 25 / 39 / 4 / 26 (Netflix / Apple TV / Prime / Disney+),
    JustWatch 38 / 40 / 40 / 23.
- **Country** (`Region.of`): `watchCountry` (the TV's, not per profile), "" = the TV's own (`Locale`), else US.
  Settings → Library → *Country* (also under Catalogs & add-ons when the Play switch is on). It sets the platform name from
  TMDB (`Tmdb.provider`), JustWatch's country, the Apple TV storefront (taken from JustWatch's link) and the rows.
  Both TVs are set to en-US, so Automatic is the US for him.
- **Platform rows** (`PlatformRows`, Settings → Library → *Platform rows*): "On Netflix", "On Apple TV" … under his
  own lists, from TMDB discover (`with_watch_providers`, `watch_region`, subscription / free / with ads), films and
  series mixed, whole rows only. Needs the TMDB token. `libraryPlatforms`: "" = the services whose app is installed
  (default), `off`, or keys with commas; `libraryPlatformRows` 1-3. A service's provider numbers are read from
  TMDB's list for the country by name (`idsIn`: Shahid had no row in Saudi Arabia with a fixed number), never a
  "… Channel" or "… Store" entry. Each title needs its IMDb id (one more request, kept 90 days). OK opens like any
  Library title (so the Play switch applies); the menu has *Save to the Library* instead of *Remove*.
- Kept in `links_cache` (not in backups), per country: a found link 30 days, "nothing" 3 days and only for the same installed apps.
  The carousel looks the slide's link up ahead (`warm`); a link not known yet is waited for 2.5 s at most.
- Continue Watching's *Open where it was watched* comes first. Library's *Open in <app>* uses the link too.
- Netflix and Apple TV block screenshots (0-byte `screencap`): check with `dumpsys activity activities` (the app in
  front and its intent) and ask him to look.
- Test (dev key): `--es link_test "tt11280740:series:Severance:Apple TV"` logs the link, with `--ez link_open true`
  it opens as Play would; `--ez link_forget true`.

## Catalogs for apps (`data/AppCatalogs.kt`)

With a streaming app selected in the dock, the carousel shows the titles of the catalogs linked to that app.

- **Off by default** (`appCatalogsOn`, Settings → Catalogs & add-ons → *Catalogs for apps*): nothing changes for anyone until it
  is turned on, or a catalog is ticked from an app's menu in the dock (hold OK → *Catalogs*), which turns it on.
- **Which catalogs** (`appCatalogs`, "package<TAB>catalog" lines, per profile): the ones ticked for the app; while
  nothing was ever ticked for it, the catalogs whose name carries the service's name (`suggested`, the words of
  `NuvioLaunch.PLATFORMS` as whole words: "Netflix Movies" → Netflix). A linked catalog needn't be on in the
  general carousel.
- **What stands in the carousel's place per app** (`appStage`, "package=rows|catalogs|none"; `effective`): not
  chosen: its rows (Channels) when it has any, as before, else its catalogs, else nothing. An app with rows shows
  its catalogs only when *Its catalogs in the carousel* is chosen for it.
- **Loaded ahead** (`CatalogRepository.loadApps` → `appItems`, from the home screen, again every 30 minutes and when
  the links change): the change is one slide to a list already there. One title from each catalog in turn.
- **In the carousel** (`ui/Hero.kt`): `AppCatalogs.pkg` (set in the home screen's dock-selection effect, 240 ms after
  the selection rests) picks the list; each list keeps its own slide (`places`); no Continue Watching or new-first in
  an app's list. With nothing selected in the dock the list stays (Up to the carousel is how a title is opened), and
  Down from the carousel returns to that app's icon. Another app without catalogs brings the general carousel back.
- **Continue Watching there** (`appCatalogsCw`, "start" by default, "off"; `AppCatalogs.continueFor`): an app's
  carousel opens with the titles in progress that belong to it, with their progress. **The service comes first
  when its app is on the TV** (`appOf(watchedIn, platform, installed)`): a title belongs to the app of the service
  TMDB lists it on in the country (the enriched `platform`), also when it was watched in Nuvio; when no service is
  listed, or its app isn't installed (HBO with no HBO app), to the app it was watched in (`NuvioLaunch.cwSource` →
  `sourceTarget`: Nuvio Legacy, Nuvio, Stremio), so it doesn't vanish from every carousel; neither: none. A title
  whose only source is Trakt / Simkl has no app to fall back to.
  Nothing is asked when the app changes: Continue Watching's list and the platforms are already there. It follows
  `continueMode` (off: none) and the hidden titles. Glassio's Continue Watching comes from Nuvio, Stremio, Trakt
  and Simkl, never from Netflix itself: what was watched inside Netflix is not known here.
- **Play there** (was its own switch `appCatalogsPlay`; now the one Play switch `playInApp`, see **Settings layout**): the title's page in that app through `PlatformLinks` with
  `prefer` (that app or nothing, kept apart in the cache), also with *Play opens the streaming app* off; no link: as usual.
  Which apps (`appCatalogsPlayOff`, the packages left out; empty = all; `AppCatalogs.playsIn`): Settings → Catalogs
  & add-ons → *Play opens the streaming app* → *Apps*, a `PickDialog` of the installed streaming apps and the apps
  that have catalogs, each ticked or not. The list counts for every Play (`PlatformLinks.allowed`). The others
  open a title as usual.
- `ui/Dialogs.kt`: `PickDialog` (a list that stays open while its rows are ticked or cycled), `appCatalogsDialog`
  (the one list for both ways in: the dock's menu and Settings). It is a small pop-up on the menus' glass
  (`GlassPanel` with `menuStyle`, 300 wide, rows of one line, the ticked ones on top in the order at opening), not
  the grey `DialogSurface`. Its window covers the screen and barely dims it: the glass reads the page by its place
  on screen, so a window the size of its content would show the wrong part of the page.
- **Over Settings every glass pop-up is frosted** (`SettingsGlass`, `LocalOverSettings`, `OverSettings` in
  `ui/Glass.kt`): Settings publishes its page's blurred picture while it is up; `DialogHost`, `PopMenuHost` and
  `AppLockHost` wrap what they show in `OverSettings`, and `GlassPanel` then draws any glass style as `liquid`, 10 %
  darker, from that picture (never the carousel's), with the rest of the screen dimmed (`SettingsGlass.DIM`).
  `amoled` and `bow` stay. A new host of something that can open over Settings only needs the `OverSettings` wrap.
  **Lighter since 4.1.0-beta1** (his ask: "too dark"): over Settings a panel keeps a tenth of its own darkening
  (`SettingsGlass.DARKEN`, was the whole of it plus 10 %) and the screen dims by 0.2 (`SettingsGlass.DIM`, was
  0.42); the plain windows (`DialogSurface`: text entry, menus, QR, the agreement) are a lighter grey on a screen
  dimmed by 0.28 instead of near-black on the system's 0.6.
- Test: `dump_focus` prints `appCat=` and `slide=` (the title showing); with the dev key `--ez app_cw true` logs
  each title in progress with its source, platform and app (tag `GlassAppCw`), `--es app_cat "package=Catalog name"`
  ticks a catalog for an app, `--es app_cw_as "'Title=package'"` counts a title as an app's until a restart (`-`
  clears; quote it twice, adb's shell splits at the space). **Check `dockPkg` before a long press and look at the menu before pressing
  an option**: a blind sequence hid Nuvio from the dock once (put back from the Apps folder).

## Problem reports (`data/Report.kt`, `tools/report-worker/`)

Settings → About → *Report a problem*: a few words typed on the TV become an issue in `mohammadmrh12/glassio`.

- **What goes** (`Report.build`): the description (10 to 500 characters), version and code, the TV's make, model and
  screen size, Android version, the profile's number and which accounts are signed in (no names), a fixed list of
  settings that describe the setup (`SETTINGS`: looks, modes, sizes, never text a person typed), the last 120 lines
  of `Glog` (it now keeps its last 250 lines in memory) and `last-crash.txt`.
- **Cleaning is not optional** (`Report.scrub`, on every part, and again in the Worker's `src/scrub.js`: keep the
  two lists the same): stream and camera links whole, names and passwords in links, emails, tokens (JWT, Bearer),
  the value of anything named like a secret (token, key, password, pin, code, also `lockCode=`, `unsplashKey=`),
  network names, MAC addresses, every IPv4 and IPv6 address, `.local` names, strings of 32 or more letters and
  digits, personal folders. `PureLogicTest.reportScrubbing` and the Worker's tests hold the cases: add one for
  anything new that could leak. Test examples are made-up addresses and names, never his.
- **Before sending** the person sees all of it (`ReportDialog`, Up and Down read through it), then Send.
- **The relay** (`tools/report-worker`, a Cloudflare Worker; its README has the steps): signs in as a GitHub App
  (Issues: write, on glassio only; its id, installation id and key are `wrangler secret`s), takes only a real
  report (from Glassio, at most 48 KB, a description that is words), limits 5 a day per TV (a random install id),
  10 per address and 150 in all, labels `bug` + `report-from-tv` (`test` + `report-from-tv` for a test), adds the
  same crash (`Report.signatureOf`: the exception and the first Glassio line) to its open issue as a comment, and
  defuses `@mentions`, `#numbers` and code fences. `DRY_RUN=1` answers without touching GitHub.
- **It is online since 2026-11-19** (his Cloudflare account, the GitHub App `glassio-reports` on the repo).
- **Its address** is `REPORT_URL` in `local.properties` (not in git, never written in a tracked file), like the other secrets. Without it, or when
  the relay does not answer, the dialog shows a QR code that opens a new issue on the phone with a short summary
  (`Report.issueLink`: no log).
- Test (dev key): `--es report "what went wrong"` logs what would go (tag `GlassReport`), with
  `--ez report_send true` it is sent marked as a test. Locally: `wrangler dev … --var DRY_RUN:1 --ip 0.0.0.0` and
  `REPORT_URL=http://<this computer>:8799` for one build (put `local.properties` back after).

## Settings layout (`ui/SettingsScreen.kt`)

The agreed plan is `build/settings-plan.json` (not in git). All three phases are done. Older notes in this file still name the 19 earlier sections: `OLD_SECTIONS` in
`SettingsScreen.kt` says where each one's rows went.

- **11 sections** (`CATEGORY_ORDER`): Accounts & profiles, Catalogs & add-ons, Continue Watching & Library, Carousel,
  Dock & icons, Look & menu, Background & screensaver, Music & app rows, Smart home, System, About. `open_settings N`
  is the index in that list (0-10); `open_settings 1000` (`PHONE_CATEGORY`) opens System → Manage from phone.
- **The list on the left never scrolls:** a section's row is the height under the search divided by their number, the
  two lines between the groups (`CATEGORY_BREAKS`) counted, and the writing never outgrows its row. The interface
  size is the screen's width / 960 (`LocalScale`), so the whole of it grows with the screen; `pageScale` is the
  pictures' size, not the interface's.
- **Rules:** every setting has one place; where it is needed elsewhere a `link(section, row, desc, title)` row opens
  that section with the row selected (`seek`: the row's title there, or a group's heading for the first row under
  it). A list longer than five is one row with its count that opens a sub-page. Sections are groups under headings
  (`Note`). A row that does nothing in the mode in use isn't shown. Keys never change.
- **Sub-pages** (`SubPage(title, kind, rows)`, `subs`, `openSub(title) { rows }`, `closeSub()`): a row with
  `more(count)` ("13  ›") opens a page in the section's place; the title shows the way ("Section › Page › Page");
  Back goes up one page to the row that opened it; changing section closes them. A sub-page's rows remember their
  place in `lastRow[SUB_SLOT + depth]`. While the page is swapped the selection waits on an unseen place inside
  the right pane (`hold()`, `holdFocus`; it can only be selected during a swap): left on a row that goes away it
  fell to the search field, which opened the search, and kept on the section's name (`catFocus`, as at first) the
  section lit up white at every page opened and every Back. The row is then asked for in every frame until it
  takes the selection (`LaunchedEffect(jump)`, `requestFocus()`'s answer): no fixed waits. `goTo` and `link` use
  the same `hold()`. Never park the selection on the sidebar.
  **A sub-page reads its own state** (`collectAsState`, `produceState` inside its lambda): the section's page is
  not composed while a sub-page shows, so state collected there stands still.
  **A row that opens a page carries it** (`pageRow(title, desc, value, sp = SubPage(...) { rows })`, `Act.sub`):
  never open a sub-page from a bare `onClick`, or the search, New and Changed can't see into it.
- **Every row, everywhere** (`walkAll()`: each section's rows and, three pages deep, the rows of the pages they open,
  as `Found(section, pages on the way, row)`). `goTo(found)` opens the section and those pages (Back goes up through
  them) with the row selected. **Walked once and kept** (`allRows()`): until a setting is written (`Settings.changes`)
  or a list the rows are made from changes size (catalogs, add-ons, apps, Hue); New's and Changed's rows are
  `remember`ed on that walk. Call `allRows()`, never `walkAll()` directly: it ran at every recomposition of the
  screen (~280 ms of the main thread each). A walk composes every sub-page once (Hue's *Lights & areas* asks the
  bridge then) and leaves the composition: rows a sub-page loads later aren't in it until the next walk.
- **Search** looks at every row (`walkAll`), sub-pages too; a result shows its place ("Look & menu › Menu items").
  Typing "changed" / "default" (or "new") also offers those pages. About's rows are still not searched (`AboutPane`).
- **New** (`NEW_IN`: per version, the titles of the rows it added; 4.0.0 has 17, the same list for the beta and the stable): a blue *New* mark after the title
  (`LocalNew`, `Label`) until the row is used once (OK or ◀ ▶ on it: `settingsNewSeen`), and the *✦ New N* entry
  under the search opens a page of them, each leading to its place, with *Mark them all as seen*. A new version
  (`settingsNewFor`) starts its own list; a fresh install (first install time = last update) starts with them all
  seen. A link row (→) never shows the mark. The next release must replace `NEW_IN`'s entry with its own rows.
- **Changed** (the entry beside New, or found by the search): every Toggle / Adjust whose shown value differs from
  what it shows at the defaults, under its section. `Settings.probe(defaults) { … }` evaluates a row on the asking
  thread only: with `defaults` every read gives its default, and it returns the keys read (and the lists read).
  Left out: Accounts & profiles, rows that read a list (catalogs, lights, inputs, menu order …), rows that read no
  setting set here, `NOT_CHANGES`. OK asks: *Back to default* (through the row's own control: a switch is set, an
  Adjust is stepped until it shows the default; never by forgetting the keys a row reads, since a row may read
  another's setting: *Play opens the streaming app* reads `appCatalogsOn`), *Go to it*, *Cancel*.
- Sidebar: *New* and *Changed* side by side under the search (Right from New reaches Changed; OK, or Right from
  Changed, goes into the page); the sections share the rest.
- **The section left open** (`settingsLastCat`) counts in the order `settingsLayout` says (new key, the TV's): below
  `SETTINGS_LAYOUT` (2) it is mapped once with `sectionAfterReorder` and the row is forgotten.
- **Merged** (one row now): Glass refraction, Transition speed and Backdrop gallery (were also in Performance; they
  still set the mode to Custom), Dock position, the dock's glass style (was *Dock glass* in Background and *Dock
  background* in Dock: one key, `dockStyle`, now *Dock glass → Glass style*), Down from the dock opens the Library,
  the + button, the island (was in Screensaver and Music), multi-view (Cameras and Screensaver), the large clock's
  look (Background and Screensaver), Continue Watching's sources and the Trakt / Simkl lists (Continue Watching,
  Library and Connections), Hue for cards (Channels and Hue), Play opens the app (phase 1).
- **One Play switch** (phase 1). *Play opens the streaming app* (`playInApp`) also stands for the app carousels' Play
  (`appCatalogsPlay`, the key stays and is only read). `playInApp` reads on when it was saved on, or when
  `appCatalogsPlay` was saved on with `appCatalogsOn`. Turning it off writes both off. `appCatalogsPlayOff` is the one
  list of apps left out, for both. *Country* is in that group, always shown; the Library has a link to it.
- **Rows that became links** because their setting already has a place: *Hold Home shows a camera* (it is what *Hold
  Home* in System chooses), the trailer screensaver's *Subtitles* and *Subtitle size* (the carousel's trailers'),
  *Camera* and the player among the menu's items (Smart home's *Camera in the menu*, Music's *Player in the menu*),
  a source of Continue Watching that isn't signed in (Accounts & profiles), the Library's *Country*.
- **Not in the plan, placed by nearest fit:** *Sign in with email* (Nuvio, Stremio) with their accounts; the titles in
  progress one by one inside *Clear the list*; *Install an icon pack* (a sub-page under Icon pack); the screensaver's
  *Collections* (a sub-page); *Colours from*, *Change speed* and the two "afterwards" choices of Hue (*Hue colours*,
  *Hue · Afterwards*); the three Glassio remote buttons (shuffle, multi-view, corner camera) inside *Remote
  buttons*; the master *Music* switch first in Music; the doorbell's optional token inside *Home Assistant link*.
  Dropped: *Next photo* (*Next* does it), *Catalogs from the Nuvio account's add-ons* in Connections (the same key
  as *Add-ons from my Nuvio account*), the brightness under Hue's card switch (the posters' brightness, one key).
- About is still its own pane (`AboutPane`), not regrouped.
- In Settings **look at the screen before every OK**. Right from the sections lands on the page's **first row**, the
  page back at its top (his video, 2026-11-23: it landed on the row facing the section, in the middle of the page;
  the Pane's `enter` now holds the selection and jumps to row 0). Before that a blind OK switched a setting of his.
- Each page logs its row count (`GlassSettings: <section>|<rows>`, debug level), for QA. `--es settings_search
  "words"` (no key) opens Settings searching for them.

## Search (`data/Search.kt`, `ui/SearchStage.kt`)

Settings → Continue Watching & Library → *Search* (`searchOn`, **on by default since after 4.1.0-beta1**, his
decision; it was off in beta1). A dock arranged before (a saved `dockOrder` without it) gets the icon second, once,
when the apps are read (`AppRepository.reload`); a new install's dock puts it second by itself (`ordered()`). Seen
with the play build installed beside his: new install Nuvio Legacy, Search, …; an order "Netflix, Apple TV, Nuvio
Legacy" became "Netflix, Search, Apple TV, Nuvio Legacy, …" after a restart. Off: as before.
Glassio plays nothing itself (his decision): a title opens in an app.

- **The Search icon** (`SearchUi.DOCK_PKG` = "search:all", `searchDock`) is one of Glassio's own dock icons like
  Music and Inputs, and its page is built the same way: `SearchUi.on` / `page` / `focused` beside `InputsUi`'s
  everywhere (`homePage()` → `PageKind.Search`, the home screen's dock-selection effect, `StagePages`' background, the
  dock's `up`). Selected: the search bar comes up in the carousel's place on the Music & Inputs page's background
  (`stagePageBg`); OK on the icon or ▲ goes to the bar; OK on the bar types (the TV's own keyboard, its voice key);
  ▼ / Back return to the icon. Moving on and off it was measured the same as the Music icon, frame for frame.
  Switched on, the icon is put **second** in the dock once (`AppRepository.placeNearFront`): at the end it would sit
  in the Apps folder; first, the selection would come back to it instead of his own app.
- **Titles** (`Search.titles`): TMDB's `search/multi` when a token is set (titles carry `tmdb:<n>` until one is
  opened: `Search.resolve` asks for the IMDb id then), else Cinemeta's search (no key, weaker: it did not find
  *Big Buck Bunny* by its full name). Shown as the Library's posters (`Poster`, now `internal`), one row. A title
  without a poster is left out as long as others have one (`Search.withPictures`: they showed as grey cards).
- **Its lists** (where to watch, Stremio's sources) are `PickDialog`s on the menus' glass (`menuStyle`), so they
  read the page under them like every list of the home screen, never the grey `MenuDialog` window.
- **OK on a title** (`searchOk`: `list` / `first` / one app's key, a service's package or NuvioLaunch's key:
  straight into that app, the list when it can't open the title): the places it can be watched in (`Search.places`): the services'
  apps that have it now (`PlatformLinks.offers`: JustWatch, every installed app, not only one), then the Nuvio apps,
  then Stremio; *Save to the Library* at the end. Each group has its switch and its list of apps left out
  (`searchServices` / `searchServicesOff`, `searchPlayers` / `searchPlayersOff`).
- **Rows** (since the second pass): *Films and series*, *YouTube*, *Music*, one under the other in a column that
  scrolls; ▲ ▼ go from row to row (`go`), ◀ ▶ stop at a row's ends (`rowKeys`: they went on to the menu's button),
  each row remembers its card. The selection is kept inside the page (`inPage`): lost by anything but leaving, it
  goes to the bar; the home screen's "nothing selected" watch and the menu's close go to the bar too while the page
  is up. The page's switch back to the carousel's picture has `SearchUi.page` among its keys (without it the blur
  stayed until the next slide).
- **The dock in the middle or as a column** (your own background): no page stands above the dock there, so OK on
  the icon, the menu's row and the remote button open the search over the whole screen (`SearchUi.full`,
  `SearchUi.inPlace`; drawn at the top of `HomeScreen`, Back or ▼ past the last row closes it).
- **Nothing typed:** *Popular this week* (`searchTrending`; TMDB's trending, needs the token) in the titles' row.
- **Cards** (`SearchCard` in `ui/SearchStage.kt`; his final ask): every result is one picture with its words
  **inside** it at the bottom, on a shade, like the Library's Continue Watching card: the name (it scrolls by on the
  selected card when it is too long, `basicMarquee`), one faint line (year · Film / Series · rating; a video's
  channel and length) and the progress bar. Selected: a little bigger, a bright rim. *Titles as* (`searchShape`):
  `card` (wide, the backdrop; the default, his choice) or `poster` (tall). **No service logos on the pictures** (he found them ragged:
  removed, with the code that cut them out of the app icons).
- **Card size** (`searchCardSize`, `large` by default, `standard`): the titles' and the videos' cards are as big
  as the room above the dock allows with both rows whole (`z` in `SearchStage`, 0.8 to 1.5 times the standard
  96 × 144 poster / 150 × 84 card: on the Streamer 111 × 166 posters with 173 × 97 videos, or 225 × 126 for both
  with *Titles as* Card). Never cut by the dock; the music row keeps its size and sits under the dock until it
  is reached (his ask). Every card has the Library's soft shadow (`softShadow` in `ui/LibraryScreen.kt`, now
  shared; `PosterShadow.wide()` for 16:9), deeper under the selected one.
- **Continue Watching** shows on a title's card: its progress as a thin bar and "S2 · E4" in place of the year,
  matched by kind and name (`Search.nameKey`; the search's titles carry TMDB's number, Continue Watching's IMDb's:
  two titles of the same name and kind are taken for each other).
- **The bar starts empty** each time the search is left and opened again (`searchFresh`, on; the stage clears
  `SearchUi.query` when it goes). *Keep the words searched* is another thing: the list of earlier searches under
  an empty bar. From the typing, ▼ goes straight to the first row (it took one press to leave the typing).
- *Only on my services* (`searchMine`, off; `Search.watchOn`: TMDB's `watch/providers` for a title TMDB found, kept
  a month, else JustWatch; the first 40 titles, six at a time) hides titles none of the services' apps here has.
  *Order of the list* (`searchOrder`): services or the Nuvio apps first. *Page behind the search* is `stagePageBg`
  itself (the Music & Inputs page's key), set from the Search page too: the link that stood there named the wrong
  section and went nowhere.
- **YouTube and music** (`MediaSearch`, in `src/github`: NewPipe Extractor's search, no key; `src/play` has a
  stand-in with `AVAILABLE = false`): videos as cards that open in SmartTube or YouTube's app
  (`searchYoutube`, `searchYoutubeApp`); SoundCloud's tracks as cards that open in its app, and a Spotify card
  that opens Spotify's own search with the words in it (`searchMusic`, `searchMusicOff`). Spotify has no search
  open to others without a key, so it has no cards.
- **A song plays under the search** (`searchMusicStay`, on; `Search.playSong`): OK on a SoundCloud song sends its
  link, and Glassio's own task is moved back to the front (`AppTask.moveToFront`, never an intent: started with
  one from the background Android built the home screen anew and the search closed) as soon as SoundCloud's
  session reports that song playing: 1.2-1.6 s with SoundCloud running, about 6 s from cold. What SoundCloud
  does with a link, all seen on the Streamer: a link equal to the one its task was first opened with only brings
  the task forward and nothing plays (each press now carries its own `Intent.identifier`, Android 10+); over a
  song that is playing it shows the new one's page and plays on (the old one is paused first); the song it holds
  paused opens paused (told to play); working a link out takes it 0.4 s to more than 6 s, and Glassio in front
  before that ends it (so no return on a timer). Never plays within 12 s: SoundCloud stays in front. Needs
  Glassio's media access (Notification access); without it SoundCloud stays open, as with the switch off.
- **First row** (`searchFirstRow`: `titles` default, `youtube`, `music`; asked for in #21): the row at the top of the
  results, the other two after it as they were. The rows are pieces (`titlesRow`, `videosRow`, `musicRow`) drawn
  in `order`; the cards' size is worked out from the two rows that come first.
- **More results** (`searchMore`, on; #21): three cards before a row's end the next page is added to it
  (`more()` in `SearchStage`): TMDB's next page for the titles and for *Popular this week* (five pages at most;
  Cinemeta has one page), `MediaSearch.Pager` for YouTube and SoundCloud (NewPipe's next page, 100 cards at
  most). A row's requesters are kept while it grows (`growTo`), so the selection keeps its place.
- **Stay in the search** (`searchStay`, off): with the Search icon selected ▼ doesn't open the Library, and ▲ from
  the search bar doesn't go to the menu. Off: as before.
- **Passing over an icon with a page** (the Search icon, the Music icon, an app with rows or catalogs, Inputs):
  arrived within half a second of the move before, its page waits 0.42 s (`dockMovedAt`, `SearchUi.passing`; the
  island stays too); one press after a rest opens it as before (Search 50 ms, the others 240). His "heavy when
  passing the Search icon": on his dock it sits between the Music icon and SmartTube's rows, and a walk at 0.36 s
  a step brought up three pages in turn. Walk Nuvio → Music → Search → SmartTube → input and back, frames over
  50 ms: 13-17 → 0-3 (between plain icons: 0).
- **Ways in besides the icon:** a *Search* row in the side menu (`menuSearch`, key "search" in `MENU_KEYS`) and a
  remote button (*Remote buttons* → Search, action "search", `--ez open_search true`): `SearchUi.open()`.
- **Hybrid** (what each app takes from outside, all seen on the Streamer except official Nuvio, read in its source):
  - Stremio plays one exact source: `stremio:///player/<stream>/<stream add-on manifest>/<meta add-on manifest>/<type>/<id>/<video id>`,
    each part percent-encoded, `<stream>` = standard base64 of the zlib-compressed JSON of the add-on's stream
    object. **Started cold with that link Stremio stops** ("RUNTIME not initialized"): `Search.playInStremio` opens
    Stremio first and sends the link 1.6 s later. So for a film Glassio reads the add-ons' sources
    (`Search.sources`, never kept on disk: an address can carry the person's key), sorts them by the rules
    (`stremioQuality`, `stremioMaxGb`, `stremioHdr`, `stremioNoCam`, `stremioP2p`, `stremioAddonsOff`) and lists
    them or plays the first (`stremioSources`: `list` / `first` / `page`). A series opens Stremio's page.
  - Nuvio Legacy: `nuvio-legacy://play/<type>/<id>` starts the title with Legacy's own pick when Legacy is running;
    cold it stops at its "Who's watching?" (no way round it found in the app). Official Nuvio and the forks: the
    extras `launchMode=stream`, `contentId`, `contentType`, `videoId`, `name` open the sources screen (films only).
    Both behind *Nuvio plays at once* (`searchNuvioPlay`, off: the title's page as before).
  - The services open the title's page (the same links as *Play opens the streaming app*).
- **Not in the play build:** Stremio's sources (`Store.PLAY`: Stremio opens its own page; the code is dropped: the
  play APK has no `stremio:///player` and no `infoHash`).
- Test (dev key): `--es search_test "tt1375666:movie:Inception"` logs titles, places and sources (tag
  `GlassSearch`, never an address), `--es search_words "words"` fills the bar, `--ez reload_apps true` reads the
  dock's list again, `--es move_app "package:+1"`; `dump_focus` prints `search=` and `page=Search`.

## Languages (`data/I18n.kt`, `ui/Texts.kt`)

Nine languages. English is the source and the key: a text is looked up by its English words.

- **Every text goes through one place:** `ui.Text` stands in for Material's `Text` in the whole `ui` package (the
  files no longer import Material's; `saver/` and `RecentsActivity` import `ui.Text`) and shows `I18n.tr(text)`.
  A new screen needs nothing but its words in the tables. A name that isn't Glassio's own (a film, a song, a
  profile, a camera) is drawn with `Plain`: a film called *Home* must not be translated.
- **Arabic is formal Arabic (فصحى) as Saudi system interfaces word it** (his ask, 2026-11-21; it was Iraqi dialect):
  no dialect words anywhere, the table's Arabic column included.
- **Where the words are:** the small table in `I18n.kt` (by hand, comes first) and
  `assets/i18n/<code>.tsv` ("English<TAB>translation", a line break as `\n`; about 1,530 a language), read when the
  language is set. A change of language bumps `I18n.rev`, which every text reads, so it shows at once.
- **What stays English:** a sentence put together from parts (a number or a name inside it, ~360 of them), the
  phone page, the problem report, log lines. Arabic and Persian are still laid out left to right.
- **Adding words:** `tools/i18n/extract.py` (collects the screens' string literals into `build/i18n/en.json`),
  translate into `build/i18n/<lang>.<n>.json` (English → translation), `tools/i18n/merge.py` (writes the assets;
  fails when a language misses a word). `PureLogicTest.languageFilesAreWhole` checks the files.
- The translations were made by machine in one pass (2026-11-21) and read by nobody who speaks the languages:
  wrong or clumsy wording is to be expected and fixed as people report it.

## Jellyfin (`data/Jellyfin.kt`, #27)

A Jellyfin server as an account like Stremio / Simkl (the TV's, `glass_jellyfin`, not per profile; in backups as
a sign-in, `Backup.SECRETS`). Settings → Accounts & profiles → *Connect Jellyfin* (the server's address, then
Quick Connect: a code approved in another Jellyfin app signed in to that server, a QR to its web page) or *Sign in
with a password*; signed in: *Jellyfin account* (Sign out) and *What Jellyfin brings* (five switches, all on,
per profile; `cwJellyfin` and `libraryJellyfin` off in a new profile).

- **Carousel:** each film / series library is a catalog "<library> · Jellyfin" (`CatalogRef` with addon
  `JELLYFIN`, newest first, `catalogsJellyfin`), ticked once the first time they are seen (`jellyfinSeeded` in
  `glass_catalogs`, forgotten while signed out). The name carries "Jellyfin", so the Jellyfin app's icon in the
  dock gets them in *Catalogs for apps* (`NuvioLaunch.PLATFORMS` has "jellyfin"). A title is the server's own
  pictures (`heroOf`), known by its IMDb id when the server has one (so it merges with the rest), else `jf:<id>`.
- **Continue Watching** (`cwJellyfin`, a source in *Sources*): `/UserItems/Resume` (+ `/Shows/NextUp`), only
  titles with an IMDb id; `Remote` ref = the Jellyfin item id; removing hides it from the server's resume list.
  Signed out or the switch off: its titles leave Continue Watching (seen: 5 → 0 after the sign-out).
- **Library** (`libraryJellyfin`): the server's favourites, row *Jellyfin · Favourites*; hold OK takes one off.
- **Search** (`searchJellyfin`): the server's titles first in the titles' row (TMDB's same title, by its TMDB
  number, left out), and *Jellyfin* always first among the places when the server has the title (`Search.places`).
  A Jellyfin card opens in Jellyfin directly (with *Play opens Jellyfin*), whatever *OK on a title* says.
- **Play** (`playJellyfin`): any title the server has (IMDb id, the library's ids kept 30 min, `byImdb`) opens on
  its page in the Jellyfin app (`org.jellyfin.androidtv`, StartupActivity, extra `ItemId`; read in its source).
  A `jf:` title always does. Not installed: as before.
- **The Jellyfin app 0.20.0 crashes** (NullPointerException in it) when it is opened on a title while it is still
  open behind another app: `Jellyfin.open` starts it on a fresh task (`FLAG_ACTIVITY_CLEAR_TASK`), which never
  crashed. It starts its screens "on home", so Back out of it showed the TV's own launcher: `HomeKeyService`
  brings Glassio back (as it was) when the window before the launcher's was Jellyfin's and Glassio opened it.
- Tested against Jellyfin's public demo (`https://demo.jellyfin.org/stable`, user `demo`, no password; other
  people use it too, so its resume list changes). Test (dev key): `--es jf_signin "server|user|password"` (`-`
  signs out), `--ez jf_dump true` (tag `GlassJellyfin`: libraries, latest, in progress, favourites, ids, the
  carousel's catalogs). A Jellyfin server on the Mac: `brew install --cask jellyfin` is installed but its binary
  waits (macOS quarantine on the download): he has to open it once himself.

## Two builds: `github` and `play` (`docs/play/README.md`)

One code, two flavors (dimension `store`). **`github`** is Glassio as it always was (package `com.glasslauncher.tv`,
the debug key): the GitHub releases and betas are built from it, and nothing in it changed. **`play`** is for Google
Play (package `com.glasslauncher.tv.play`, its own upload key from `local.properties`, an AAB): never published
anywhere until he says so.

- `Store.PLAY` (`data/Store.kt`, a constant from `BuildConfig.PLAY`) switches off what Play does not allow, and R8
  drops the code behind it: the updater and the Beta channel, *Install an icon pack*, trailers everywhere (the
  trailer settings read as off and their rows are hidden; a saved `saverMode` "trailer" reads as the posters), the
  PSN gift card, every shell command that gives Glassio a right (`SystemSetup.enableHomeKey`, `NowPlaying.grant`,
  `Channels.grant`, the overlay appop, `ensureHomeService`), `LocalShell.install`.
- `TrailerSource.kt` lives in `src/github` (it needs NewPipe Extractor, a `githubImplementation`); `src/play` has a
  stub that finds nothing, and a manifest that removes `REQUEST_INSTALL_PACKAGES`, `WRITE_SECURE_SETTINGS`,
  `ACCESS_ALL_EPG_DATA` and the install receiver.
- **The accessibility service in the play build** does nothing until the person agreed (`a11yConsent`, the TV's;
  `ConsentDialog` with `A11Y_TEXT`, shown by Settings → System and the setup before the TV's Accessibility settings
  open; *Take back my agreement* is under it). Music asks for Notification access and *Open Glassio when the TV
  starts* for "Display over other apps" through the TV's own pages.
- Kept in the play build by his choice ("خليهن"): `LocalShell` for the rest (inputs, Bluetooth, screensaver, recent
  apps, set-home-activity), Apple's aerials, the *Apple TV icons* source, Bing's pictures, JustWatch, Nuvio / Stremio.
- **A new thing that Play would not allow goes behind `Store.PLAY`** (or into `src/github`), and after any change
  near these parts scan the play APK: `strings` on its dex files must find none of `newpipe`, `pm grant`,
  `allow_listener`, `WRITE_SECURE`, `f-droid`, `releases/latest`, `PackageInstaller`, `timedtext`, `gift`
  (`pm install…` stays inside dadb's own unused code).
- **Publishing to Google Play from the Mac** (`tools/play/publish.py`, since 2026-11-25): `status` lists the tracks;
  `upload [--track alpha] [--notes ...]` uploads `app-play-release.aab`, sets the track's release and sends it for review.
  It signs in as a Google Cloud service account (project "Glassio Play") that the Play Console allows only *Release to
  testing tracks* on this app (production stays his); its key's path is `PLAY_API_KEY` in `local.properties`, never in
  git. Build first (`./gradlew :app:bundlePlayRelease`). The browser can't upload a bundle (its upload tool takes 10 MB
  at most and only shared files).
- `docs/PRIVACY.md` is the privacy policy for both; `docs/play/README.md` has the listing's words, the Console's
  declarations and what is still to do before an upload.

## Manage from phone (`net/PhonePage.kt`, `net/PhoneServer.kt`)

The page is laid out as Settings on the TV: a home list of 7 sections (Accounts & profiles, Catalogs & add-ons, Dock &
icons, Look & menu, Background & screensaver, Smart home, System) with a value on the right, each a page of rows
under headings, long lists on their own pages (`#section/page`: Add-ons, Apps, Saved on the TV, Your videos,
Cameras), and a search on the home list that finds any row, lists' items too, and opens its place with the row lit.

- **One file, no libraries, nothing from the internet.** Rows are built in `model()` from `/api/state` every time
  something changes. A value is typed in a small window (`edit`), chosen from a list (`pick`, also the confirmation
  before removing: `ask`), or read before sending (`show`): native `<dialog>`s, never `prompt` / `confirm`.
- It is a Kotlin string: no dollar sign before a name or a brace in its JavaScript, and old-style functions.
- **Its look is Glassio's** (his choice, 2026-11-20): liquid glass panes (`backdrop-filter`, a light rim and a top
  highlight) over the setup's soft lights, which the TV sends as a picture (`/bg.jpg`, `orbsPicture`), always dark,
  and a **burgundy** accent (`#8c1c3a`, the lighter `#e08aa0` for focus and the chosen item), never the old violet.
  The one-button page for the settings button's picture (`/button`) has the same look. A browser without backdrop
  blur gets plain dark panes.
- **A new row** is one entry in `model()` (`txt(title, desc, value, {e: {…fields…, save}})` for a typed value), its
  value in `state()` and its action in `post()`. Secrets are write-only: the state says only whether one is set.
- On the page since 4.0.0's rewrite, besides what it had: the profile's name, the weather city, an app hidden from
  or shown in the dock, the Photos background and its source, the doorbell's words, the Hue bridge's address, the
  settings button's picture, and *Report a problem* (typed on the phone, built and cleaned on the TV, read in full
  on the phone, sent by the TV; when the TV can't send it the phone opens the same report on GitHub).
- Test: `--ez phone_url true` with the dev key starts the page and logs its address (tag `GlassPhone`), `false` stops
  it; then any browser on the network (Chrome with a phone-size window was used).
- **Dev hooks that change or reveal something are all behind the key now** (`self_install`, `hue_find`, `hue_pair`,
  `open_legacy`, `ha_cams`, `trailer_test`, `trailer_find`, `fake_song`, `dump_media`, `dump_channels` sat before
  the check, reachable by any app on the TV through the exported activity). Only the ones that open a screen stay
  open: `open_folder`, `open_app`, `settings_search`, `open_settings`, `show_tour`, `next_bg`, `multi_cams`,
  `open_menu`, `open_library`, `trailer_now`, `show_setup`, `start_saver`.

## Build, test, lint

```sh
# JAVA_HOME / ANDROID_HOME: see CLAUDE.local.md
./gradlew :app:assembleGithubRelease    # app/build/outputs/apk/github/release/app-github-release.apk (the GitHub releases)
./gradlew :app:testGithubDebugUnitTest  # unit tests (SettingsKeysTest, UpdaterVersionTest, …); testPlayDebugUnitTest for the play build
./gradlew :app:lintGithubDebug          # lintPlayDebug for the play build
./gradlew :app:bundlePlayRelease        # app/build/outputs/bundle/playRelease/app-play-release.aab (Google Play)
adb -s <tv> install -r app/build/outputs/apk/github/release/app-github-release.apk
```

- Quick error check: redirect the build to a log, then `grep -E "^e: |BUILD" <log>`.
- Dev hooks: `adb shell am start -n com.glasslauncher.tv/.MainActivity …`
  - Opening screens needs no key: `--ez open_folder true`, `--ei open_settings <index in CATEGORY_ORDER>`,
    `--ez open_menu true`, `--ez open_library true`, `--ez start_saver true`, `--ez show_tour true`.
  - Changing settings or dumping state needs the dev key:
    `K=$(adb shell cat /sdcard/Android/data/com.glasslauncher.tv/files/devkey)`, then `--es dev $K` with
    `--es pref key=value` (lists use `\n`; `lang=ar` now shows at once), `--ez dump_look true`, `--ez dump_prefs true` (printed in logcat).

## QA tools (`tools/qa/glassqa.py`)

Run `TV=<tv>:5555 tools/qa/glassqa.py <command>`; output goes to `build/qa/`.

| Command | What it does |
|---|---|
| `install` | Wakes the TV first, then installs. **Always install this way.** Installing while Glassio's screensaver runs kills the screensaver, and the TV then goes to sleep ("timeout"). |
| `nav` | Steps through home, dock, Library, menu and Settings. After each step: a screenshot, the selection (`dump_focus`) and the app in front, then a crash check at the end. |
| `perf NAME KEYS…` / `idle NAME` | Frame times while the keys run: slow %, p50 / p90 / p99, GPU and UI time. |
| `glide KEYS…` | Screen recording; reports when each movement starts and how long it takes to settle. |
| `libtrace KEYS…` | The Library's page frame by frame while the keys run (Glassio's own trace, `--ez lib_trace`): px moved in each frame, pauses inside the movement, how evenly the speed changes. |
| `live` | Checks the glass follows a video and isn't frozen. |
| `focus` | Prints where the selection is. |
| `res [s]` | Resources: Glassio's CPU (and its busiest threads), memory (PSS / Java / native / graphics), threads, the TV's load and free memory, and frames drawn with nothing pressed (more than 20/s: something redraws for nothing). |
| `watch NAME KEYS…` | `perf` plus CPU and memory sampled every second while the keys run. |
| `profile KEYS…` | CPU profile (simpleperf, the app is `profileable`) while the keys run: time per thread, then the main thread's heaviest Glassio functions de-obfuscated with the R8 mapping. Full report in `build/qa/profile.txt`. |
| `phases NAME KEYS…` | Each frame in its parts while the keys run: whole frame, main thread, draw commands, GPU (p50 / p90), and how many frames missed 16.7 and 33 ms. |
| `lab slides\|dock\|pages [BITS…]` | The clean measurement: music paused, automatic slides off, carousel background, the selection put in place first, everything put back after. `BITS` are `dbg_hide` values tried one after the other. Slides are measured one by one. |
| `security` | On the Mac: private data in tracked files, exported components without a permission, dev hooks reachable without the key, then lint. |
| `all` | Everything above, written to `build/qa/report.md`. |

- `tools/qa/bench.py OUTDIR [passes]`: the controlled speed bench for comparing two builds. It puts the selection
  where each scenario needs it (checked with `dump_focus`) before measuring. `perf` with bare keys is not
  comparable between runs: the same keys land on the dock, the Library or a playing trailer depending on where
  the selection was. To compare with an older version, build it from its commit with the same `versionCode`
  (a lower one cannot be installed over a newer one without wiping the app's data).
- After a restart of Glassio a song left paused in a music app shows again (the "stopped here" state is in memory
  only): stop it from Glassio's player before measuring, or the menu and island differ from the earlier run.
- **A held key**: `hold:DOWN:3` in any key list (also `hold:DOWN:3:UP:3`). It makes a keyboard for the moment with
  Android's `hid` tool and the TV repeats the key itself (`repeatCount` > 0). `input keyevent` never repeats, and
  the shell may not write to `/dev/input` (SELinux). The Streamer repeats a held key every 200 ms after 400 ms.
- **A screen recording can't show whether a movement is continuous**: the TV's recorder kept 15-20 frames a second
  while Glassio drew 60 (it looked like jumps with pauses). Use `libtrace`, or `gfxinfo framestats` for frame times.
- An injected Home key opens the TV's own launcher, because Glassio only catches the remote's real Home key. The
  tool uses `am start` + Back instead.
- Dev hooks added for QA:
  - No key needed: `--ez next_bg true`, `--ez multi_cams true`.
  - Dev key needed: `--ez dump_focus true` (tag `GlassFocus`; it also prints `page=` from `HomePage`), `--es fake_title …` with `--ez fake_song true`.

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

## Last session (2026-11-19, 7 am)

- **Beta phase started.** Local branch `beta` (not pushed), version 4.0.0-beta1 (code 68; named 3.9.0-beta1 at first), installed on the
  Streamer and the TCL. Commits: version, build tools, libraries, targetSdk 37, LiteRT back to 1.4.2.
- **Step 1 (everything to the latest stable) is done**, see the Libraries table. Each phase built and passed
  `testDebugUnitTest` and `lintDebug`.
  - First install crashed at start because of LiteRT 2.3.2 (see Libraries). Fixed by staying on 1.4.2.
  - Speed and memory against 3.8.0 built with the old libraries (same `versionCode`, `tools/qa/bench.py`, A/B/A):
    no regression. Dock moves p50 26–32 ms on both, slides 22–31 ms, Library 15 ms, PSS ~570 MB, no frames at rest.
    The 3.8.0 numbers from the first uncontrolled run are in `build/qa/baseline-3.8.0/`.
  - Checked on the Streamer with the new build: glass over a video and over the aerial background (`live` ok),
    trailer in the home player, screensaver trailers with subtitles, multi-view (3 RTSP cameras), island, depth
    clock on a picture and a video (LiteRT 1.4.2), Bluetooth list in the menu (dadb 2.0.0), Updater check
    ("up to date"), navigation (same as 3.8.0, no crash), Hue sender running with no warnings.
  - Phone page: a 300 MB upload with 18 other requests at the same time, all answered in under 50 ms; the test
    file was deleted.
- **His list after the upgrade, done (each tested on the TVs and committed):**
  - Step 2: the dock is no longer composed with every slide (stable menu functions); `HomeScreen.kt` split into
    `Dock.kt`, `Hero.kt`, `TopBar.kt`, `PagePictures.kt`, `HomeStages.kt` (code unchanged, line for line); the
    page behind the dock is decided in `HomePage.kt`.
  - Step 3: ~50 hand-made threads became `Bg.io` coroutines; about 30 failures that matter are now logged.
  - Hue: the bridge's certificate is pinned (tested on the real bridge: learnt, trusted, refused with a wrong pin).
    `--ez hue_secure true` (dev key) makes one harmless secure request and logs the result.
  - Silent install through the TV's own shell (dadb 2.0.0) with `--ez self_install true`: reinstalled and back on
    screen in 12 s, the Home key service still on. The download half could not be tried (nothing newer to fetch).
  - Left shade: it was broken (the switch changed nothing until the next picture). Fixed and measured on a bright
    picture (left side 52 with the shade, 225 without) and over a video.
  - `glassqa.py phases` and `lab` (were `build/qa/phases.py` and `lab.py`).
- **Glass work started** (his request during the session): see the first Open item. Sharper clear glass and a
  cheaper dock are in; a slide on the Streamer is at ~18 ms a frame, not yet under 16.7.
- **Profiles: all five phases done and tested** (data and migration, chooser, setup step, name and picture, backups
  and sync):
  - No setting changed by the update (293 keys on the Streamer, 266 on the TCL); changing profile takes 2.3 s.
  - Chooser, setup page and settings rows tried on the Streamer with pretend profiles (his account has one).
  - Sync, Streamer as main and the TCL following: first sync, a live change there and back, profile 2 to profile 2
    (5 on the main TV replaced the TCL's 7; profile 1 kept its 11), a profile the main TV never opened.
  - Backup and restore on the TCL with two profiles: after the sync had changed it and profile 2 was dropped, the
    restore brought back all 266 keys with no difference and profile 2 with its marker.
  - Both TVs were put back: one profile each, the TCL not following, pretend profiles and their files removed.
  - Open: writing the name and picture to Nuvio; profiles with a PIN in Nuvio can't be opened; the other accounts
    (Trakt, Simkl, Stremio) are shared by all profiles and off in a new one; changing profile restarts Glassio.

- **Title links (afternoon, after the Mac restarted mid-work):** see **Title links**. Built, tests and lint pass,
  commit `2333ae6`. On the Streamer: Severance opened Apple TV with its show link, Stranger Things opened Netflix
  with its title link, a Prime title and an unknown title gave no link and opened the usual way, three presses of
  Play on real slides with the setting on (all Prime / Peacock titles) opened Nuvio as before. Settings put back.
  The TCL was off the network, so Prime Video was not opened anywhere.

- **Country and platform rows (evening):** his answers: JustWatch stays on by default, the feature off by default,
  build the rows. Done in one commit (the two parts share files). On the Streamer: rows for Netflix, Apple TV and
  Shahid (the installed apps); with the Play switch on, OK on the Netflix row opened Netflix with the title's
  link, on the Apple TV row Apple TV with the show's link, on the Shahid row Nuvio (no link); country Saudi Arabia
  changed Netflix's titles and, after the fix, showed Shahid's; chosen platforms and two rows each; an unknown
  country made JustWatch fail: logged once, the next title answered in 4 ms the usual way; `nav` clean, no crash.
  His settings put back (country Automatic, installed apps, one row, Play switch off).

- **Nuvio's profile pictures (night):** see **Profiles → Nuvio's pictures**. His account now has two profiles
  (his and a second one). On the Streamer: the chooser showed Rowan for the second one and his own Glassio picture
  for his (it comes first); with `nuvio_faces` it showed Moss for his, in the chooser, on the setup's profile
  page and as the settings button; switched off again, his own picture is back. Nothing was written to Nuvio.

- **Menu greeting (evening):** on the Streamer: "Hello, <his name>" in profile 1 and "Hello, <the second name>" with Rowan in
  profile 2 (switched with `--ei profile`, back on 1 after); the three button styles (picture: words only,
  sliders: picture and words, Hello: picture and name); the switch off hides it. His button put back to *Your
  picture*. His own Glassio picture for profile 1 was no longer there when this was tested (the button and the
  chooser show Moss): not removed by this work as far as could be checked; he may have removed it himself.

- **Chooser, smaller secondary parts (evening):** the *At start* pill (12 sp, as wide as its words) and the hint
  under it (11 sp, lighter); circles, names and "In use" unchanged. Seen on the Streamer, selected and not. The
  setup's profile step is its own page (pills), not this screen: left as it is.

- **The beta is 4.0.0-beta1** (his decision: profiles, the new tools and libraries, the platforms). Code stays 68.
  `UpdaterVersionTest` has the 4.0.0 order. On the Streamer: About shows 4.0.0-beta1, and the update check says it
  is up to date on both channels (3.8.0 is not offered).

- **Problem reports (night):** see **Problem reports**. The app side and the Worker are written, tested and
  committed; the Worker is **not online** (it needs his Cloudflare sign-in and the GitHub App: steps in
  `tools/report-worker/README.md`, then `./setup.sh`). On the Streamer, against the Worker run locally without
  GitHub: a report from the dev hook and one from the dialog arrived and came back as sent; the Worker stopped:
  the QR page; a build without `REPORT_URL` (the one installed now): the QR page at once. `local.properties` was
  put back as it was. No issue was opened on GitHub, so the test report with the label `test` is still to do.

- **Catalogs for apps (night):** see **Catalogs for apps**. On the Streamer: turned on, Netflix got "Netflix
  Movies" and "Netflix Series" on its own (40 titles) and kept its rows until *Its catalogs in the carousel* was
  chosen from its menu; a catalog ticked by hand for Apple TV; Nuvio / Netflix / Spotify / Apple TV / Shahid in
  turn: each showed its own (the general carousel, Netflix's, the player, Apple TV's, the general one); Up kept
  Netflix's list, Down returned to Netflix; Play on a Netflix title opened Netflix with its title link (the
  general Play switch off). Dock moves (`lab dock`, whole frame p50 / p90): before 16.9 / 25.5 ms; after with the
  feature off 16.6 / 23.6; with it on and the carousel changing under each move 26.5 / 30.5 (a slide's cost, the
  GPU; the main thread 1.7 / 3.9). Settings put back (off, no links). A blind key sequence hid Nuvio from the dock
  and opened a title's page in Nuvio: put back from the Apps folder, nothing was played.

- **The Catalogs list, compact and on glass (night):** see **Catalogs for apps**. Seen on the Streamer from
  Netflix's menu in three menu looks (Still Glass, Black AMOLED, Black on white); the look put back, nothing ticked.

- **Continue Watching in app carousels (night):** see **Catalogs for apps**. His 14 titles in progress belong to
  Nuvio / Stremio (watched there) or to HBO, Disney, Prime, Hulu (by service): none to Netflix or Apple TV. So on the
  Streamer one title was counted as Netflix's with the in-memory hook: it opened Netflix's carousel with
  "Continue · 66%", was not in Apple TV's, and was gone with the option off and with Continue Watching off (his
  setting). Everything put back (Continue Watching off, the feature off, no links).

- **Carousel buttons that follow the theme (9 pm):** `heroButtonLook` ("dark" default, "theme"; Settings → Carousel →
  *Button look*), `HeroPlate` / `HeroLook` in `ui/Hero.kt`. The plate is the icons' style (`tileStyle`): clear or
  frosted glass with a dark tint (26 / 22 %) so white writing reads, black, or off-white with dark writing. The
  selected one is white, or what *Selected icon plate* says when *Colour on focus* is on (glass: the same glass
  with a bright rim and bending; on the two flat themes a bright rim / a dark plate). On the Streamer, a slide's
  frames (p50, frames not locked at 30): before 16.5 ms; Dark 16.5; theme on clear glass 16.7; on frosted glass
  21.3 with Dark and 22.3 with the theme's (draw commands 5.1 → 5.9), so frosted plates are a plain dark pane while
  the page slides (then 5.0-5.4, as Dark). Seen in Still Glass, Frosted, Black AMOLED, Black on white and with the
  glass selected plate, a button selected and none. His settings put back (Dark, Still Glass).
  `glassqa.py`: `to_hero` now leaves the Music / Inputs icon first (Up from them is their page), and a setting that
  did not exist before a `Clean` run is **not** put back by it (a new key has no old value): set it back by hand.
- **Music & Inputs page background (9:30 pm):** `stagePageBg` ("grey" default, "glassio", "carousel"; Settings →
  Dock & icons → *Music & Inputs page*; per profile), `StagePages` in `ui/PagePictures.kt`. It is the page of the
  music apps, the inputs and an idle player (the one grey page they shared); an app's rows keep their own setting.
  Glassio blur is `orbsPicture` (the setup's lights, shaded 28 %); Carousel blur is made from the slide's ready
  glass copy like the rows' blurred card (so *Background brightness* of Channels applies), 0.9 s after each slide
  settles, on `glass-chan-blur`. Not ready yet: the last blurred slide, else the grey page, then a fade. The left
  shade is drawn over it as over the grey page. On the Streamer: the three choices on both pages (dock and icons
  read the same page); a change of page costs the same in all three (whole frame p50 ~16 ms music apps, ~17.5
  inputs); read frame by frame from a recording: coming to the page, page and dock change in the same frame (a
  cut, as the grey page always did), going back is one fade, between the two pages nothing changes; no other
  colour in between. `nav` clean. Put back to Grey. His paused song (Spotify) was stopped to see the music apps.

- **Continue Watching in app carousels, the service first (10 pm):** see **Catalogs for apps**. On the Streamer with
  his real list (Continue Watching on for the test): in the US none of his 14 titles is on Netflix or Apple TV
  (Prime, Hulu, HBO, Disney; *The Black Adder*, watched in Nuvio Legacy, now counts as Prime Video's; titles with
  no known service stay with Nuvio / Stremio). With the country set to France, *Friends* and *The Naked Gun*
  (watched in Stremio) are on Netflix there: Netflix's carousel opened with Friends ("Next up"), then The Naked
  Gun, then its catalogs. Country back to Automatic, Continue Watching back off. **A country change only reaches
  the titles' platforms after Glassio restarts** (they are kept from when each title was looked up).
- **Glass pop-ups over Settings (10:30 pm):** see **Catalogs for apps** (the last point). On the Streamer: the
  Catalogs list from Settings → Catalogs and the lock's code window from Settings → System, in Still Glass and
  Dynamic Glass: frosted, dark, the writing reads, the rest dimmed; Black on white stays off-white; an app's menu
  in the dock on the home screen is as before (clear glass). A `PickDialog` keeps the style it was opened with
  (changing the theme under an open list doesn't restyle it). Theme put back (Still Glass).

- **Carousel shade switch (11 pm):** see the design principles (*Carousel shade*). On the Streamer: on and off on a
  bright title (Prince Charming, whose second picture is nearly white on the left) and a dark one (CIA): off shows
  the picture as it is, the dock and the buttons' glass read it without the shades, the logo and every line still
  read over the white picture; the change shows within 0.7 s on the slide in view, both ways. A slide's frames:
  on 17.5 ms, off 17.4-18 (same). Put back to On. The shades are global (`Scrims`), so Off also takes the left
  shade off the grey / blurred pages of rows, music apps and inputs while the carousel background is in use.

- **The report relay is online (midnight):** a build with `REPORT_URL` is on the Streamer. One test report sent
  from the TV (`--ez report_send true`) opened issue #17 as the App, labels `report-from-tv` + `test`; its text
  held no address, email, path, token or name (only a public TMDB picture link from the log); closed as not
  planned. The same words sent again came back as #17 (no second issue). Refused by the relay, each without an
  issue: GET /report 404, empty 400, not JSON 400, `{}` 400, a good body from a browser 400 ("Not from Glassio"), a
  description that isn't words or too short 400, 60 KB 413. The dialog (Settings → About → *Report a problem*)
  shows the preview with Send, not the QR; Send was not pressed there (it would be a real `bug` issue).
  `setup.sh` now stops with an explanation when the deploy gives no address or the sign-in has several accounts
  (README has both notes); the script's new checks were not run end to end (it would redeploy his Worker).

- **Continue Watching: an uninstalled service's titles go back to where they were watched (2026-11-20):** his
  answer. On the Streamer (no Hulu / HBO / Disney apps; Continue Watching on for the test, then back off):
  *Predator: Killer of Killers* (Hulu, watched in Stremio) now belongs to Stremio, *Family Guy* (Hulu, Nuvio) to
  Nuvio; Prime titles stay with Prime Video (installed). Seen through `app_cw`, not in a carousel.

- **Play opens that app: which apps (2026-11-20):** see **Catalogs for apps** (*Play there*). On the Streamer: the
  list showed Apple TV, Netflix and Prime Video ticked; Netflix unticked, the row read "Apple TV · Prime Video"; Play
  on a title of Netflix's carousel then opened Nuvio (as usual). His settings put back (the switch off, the list
  empty). Tests, lint and `nav` clean once at the end of the batch. The unfinished `hold:` keys in `glassqa.py`
  (for the Library scroll, left for a later run) were dropped, not committed.

- **Library scrolling like the Apple TV app (2026-11-20, Fable run):** see the design principles (*The Library's
  scrolling is one movement*). What was wrong was the movement's shape, not the frames (`libScroll` in `HomeScreen`
  is the Library sliding up from the dock, not the rows' scroll). On the Streamer, `libtrace`, before → after:
  - Down held 3-4 s: speed between rows 117 ↔ 1 px a frame every 200 ms → steady 22 px a frame (a row per 400 ms),
    rising smoothly to ~50 after five rows; speed change per frame 25.3 → 3.3 px; Up held 20.2 → 1.7.
  - A single press: top speed 156 px in the 2nd frame → 43 px in the 5th, one curve, ~0.6 s.
  - Letting go: eases 45 → 0 over ~0.7 s onto the row; the end of the list is eased into (it was a hard stop).
  - Frame times (`phases`, whole frame p50 / p90, frames over 16.7 of 120): Down held 16.3 / 20.2, 49 → 15.2 / 21.0,
    32; Up held 16.7 / 20.7, 58 → 14.9 / 19.4, 24; five single presses 17.4 / 28.1 → 13.8 / 15.7; Right held in
    Continue Watching 20.1 / 22.7, 104 → 15.3 / 16.5, 10. Over 33 ms: 0-1 either way.
  - Up held from deep in the Library still ends on the dock; a held Right moves the Continue Watching row; the
    platform rows' posters are loaded ahead with the others. Tests, lint, `nav` clean.
  - Before it ran five rows a second from the first repeat; now about 2.5 then 5 (his description of Apple TV: two
    to three rows a second).

- **Four hard ones (2026-11-20, 2 am, Fable run), all on the Streamer:**
  - *Strip dock after the list changes:* see the design principles. Went round the strip (13 steps over 11 apps),
    hid Play Store with `--es hide_app`: the selected app stayed in the middle, the next step went to its
    neighbour; shown again: still in the middle, Play Store back in its place. The selected app itself hidden: the
    strip centres the one that took its place (the selection went to the carousel: through the dock's menu the home
    screen selects the neighbour itself). Nothing was uninstalled; his hidden list is as it was.
  - *Refraction on the strip dock:* it was simply off (`refract = false` since the strip was written, no reason
    recorded). On now: the rims bend at the top, bottom and the screen's edges. A slide's frames not locked at 30
    (whole p50 / GPU p50): before 18.6-21.9 / 8.2-8.5, after 18.3-19.3 / 9.3-9.4: the whole frame the same, the
    GPU about 1 ms more (that run also draws the colour during the slide now, see below).
  - *Screensaver trailers starting again:* `SaverTrailers` shuffled its list again whenever the catalogs' size
    changed and its choosing started over (also once at the start, from the "online" check): seen on a cold start,
    a trailer was got ready and thrown away for another; with the one found ahead put first every time it was the
    same trailer from the beginning. Now one order for the whole screensaver (later titles go to the end) and the
    choice only starts over when there was nothing to play. Cold start, first minute: one trailer chosen, played to
    its end (32 s), then the next (log `Screensaver trailer: …`, and a recording sampled every 4 s).
  - *Glass colour going off and on:* see the design principles. His phone video and the TV agree: with Glass
    colour Black 20 % the strip dock's glass was 25 % brighter in the middle of every slide than after it (63 → 50,
    41 → 33 …), i.e. the colour was missing while the page moved; the plates were coloured twice at rest (darker
    than the dock and than the selected icon). After: the dock's glass reads the same mid-slide and after it on six
    slides in a row, and the plates match the dock.
  - Tests, lint, `nav` clean. `heroAutoplay` was found off after the runs and put back on.

- **His setup became the defaults (2026-11-20):** 47 defaults in `data/Settings.kt` now equal the Streamer's settings
  (look and general behaviour: blur 8, refraction 10, the strip dock with 7 apps, liquid dots, theme buttons, the
  island in the theme's look, the Music & Inputs page as Carousel blur, the left shade off with your own
  background, trailers as the screensaver, 900 ms slides, shuffle and new titles first, Channels' look …).
  `DefaultsTest` holds the list against his values. Installed over his data: all 309 of his settings unchanged
  (he had set every one of them himself, so a new default can't move him). Left as they were: what is personal or
  needs setting up (accounts, catalogs, Hue / Home Assistant / cameras / doorbell, city, codes, icon pack, apps'
  order and folder place, menu order, photo source, background files, remote keys, performance mode `custom`) and
  four that reshape the home screen for everyone who never touched them, his to decide: `channelsOn` (an app's rows
  instead of the carousel), `continueMode` (his is off: Continue Watching gone), `musicMode` (his is off: no player
  page while a song plays), `inputsIcon` (an Inputs icon needs TV inputs / CEC). Also kept: `dockPos` (his is
  "left", only with your own background: a column dock), `settingsIcon` (his is a picture: a new install has none),
  `saverLockedOnly` (needs his list of locked videos), `idleSeconds` (his is 0: no screensaver from Glassio).
  Old users who never changed a setting get the new look after the update (accepted by him).

- **His answer on the defaults (2026-11-20, morning):** `channelsOn`, `continueMode`, `musicMode`, `inputsIcon` stay on
  their old defaults (untouched; `DefaultsTest` checks three of them). The trailer screensaver now shows the posters
  (the slideshow) when it has nothing to play (`saverShowsPosters` in `ui/Trailer.kt`: no titles, no trailer found
  for any, or no internet), for everyone, without changing the saved `saverMode`; it looks for trailers again every
  two minutes (and as titles arrive) and goes back to them by itself. The note ("No titles in the carousel yet" …)
  shows only when there are no backdrops either. Test hook (dev key): `--ei saver_no_trailers <seconds>` (no
  trailer is found for that long, then they are again by themselves; start it with `--ez start_saver true` in the
  same command, any other `am start` brings Glassio to the front and ends the screensaver). On the Streamer: posters
  with their logos at 12 s, the switch ended at 25 s, a trailer chosen and on screen 5 s later (at 50 s it played);
  `saverMode` still `trailer`. Tests (`trailerScreensaverFallsBackToPosters`), lint, `nav` clean.
  Not seen: a real new install with no catalogs (the note then), the TV's own screensaver (dream) falling back.

- **Settings, phase 1: Catalogs & add-ons (2026-11-20, Fable run):** see **Settings layout**. On the Streamer: the
  section with its four groups; *Add-ons* (13) → an add-on's catalogs → Back to the same add-on → Back to the
  *Add-ons* row; *Catalogs in the carousel* (31 / 123) with the ones on first; *Apps and their catalogs* (3). His 309
  settings unchanged (`appCatalogsPlay` was on, `playInApp` saved off: the one switch reads on for him). Tests, lint,
  `nav` clean.

- **Settings, phase 2: the 11 sections (2026-11-20, Fable run; the Mac restarted in the middle, nothing lost):** see
  **Settings layout**. Rows on the Streamer, counted by the app, before → after: 489 in 17 sections with rows →
  279 in 10 (Accounts 25 → 12, Catalogs 12 → 12, Continue Watching + Library 51 → 26, Carousel 47 → 46, Dock 55 → 27,
  Look & menu 44 → 27, Background + Screensaver 99 → 37, Music + Channels 40 → 27, Smart home 77 → 40, System with
  Remote, Performance and Sync 39 → 25). Seen there: every section; all 11 names and About with no room left under
  it, in English and Arabic; a link (Screensaver → Hue's *On the screensaver*) opening Smart home on that row;
  *Remote buttons* and back to its row; Manage from phone opened from outside and Back to System; a TV left on the old
  *Screensaver* section opening Background & screensaver. His 309 settings unchanged. Tests
  (`lastSectionFollowsTheNewOrder`), lint, `nav` clean. Screenshots in `build/qa/p2/`.

- **Settings, phase 3: New and Changed (2026-11-20, Opus run):** see **Settings layout**. On the Streamer: *✦ New
  17* and *Changed* under the search, the section list still with no scrolling; the New page by section; *Catalogs
  for apps* from it opened Catalogs & add-ons on that row with New marks on the new rows; *Apps and their catalogs*
  opened once lost its mark (16); Changed listed 25 of his settings with their defaults; *Dock position* → *Back to
  default* asked first, then changed only `dockPos` (left → bottom; put back); the search found *Bluetooth* inside
  *Look & menu › Menu items*, opened that page on it, Back went to the *Menu items* row; Changed in Arabic through the
  search. His settings unchanged (the New list put back to unseen). Tests, lint, `nav` clean. Screenshots in
  `build/qa/p3/`.

- **Settings: the selection jumping to the sidebar, and heavy New / Changed (2026-11-20, afternoon):** see
  **Settings layout** (sub-pages, *Walked once and kept*). On the Streamer, recordings read frame by frame
  (`build/qa/p4/`):
  - Before: opening *Add-ons* lit the section's name for ~190 ms, Back for ~320 ms (and the row jumped to the top
    of the page, then back). After, on *Add-ons*, an add-on's page inside it and *Catalogs in the carousel*, open
    and Back: no frame with the section lit; nothing selected for ~45-65 ms, then the first row / the row that
    opened the page, where it was. From the New page to a row (`goTo`): the same.
  - New / Changed (Down from the search, Right, OK, Back, Left, OK, Back; `phases`, main thread p50 / p90):
    267 / 314 ms a frame, 27 frames → 12 / 69 ms, 12 frames (moving between ordinary sections: 8 / 90). The cause:
    the walk of every row at each recomposition, 78 % of it `AppCatalogs.suggested` (a `Regex` built per catalog
    name, per app, several times per app in a sort): now the walk is kept, the regex is one and a name's words are
    kept. The first walk after Settings opens (or after a setting changes) is still one frame of ~145 ms.
  - Changed's count followed a setting changed from outside and put back (26 → 27 → 26). "Continue Watching &
    Library" fits with and without the New chip. Tests, lint, `nav` clean. His New list put back (all seen).

- **The selected icon's glass on the way back from the Library (2026-11-20, afternoon):** see the design principles
  (*reads the picture by its place in the page*). His phone video and the Streamer agree. The cause: Up from the
  Library selects the icon again, its glass is made during the slide, and places were frozen then: it kept the
  place (0, 0) and showed the picture's top left corner, a flat dark plate, in every frame of the way back, then
  jumped to the right picture when the page settled. Recordings read frame by frame (`build/qa/p5/`), the plate
  against its look at rest (0-255): before ~37 in every frame of the slide; after 20 → 3 on the way back and 3 → 19
  on the way down (what is left is the plate growing and its glow), the same part of the picture in every frame.
  Frames (`phases`, Down then Up, old and new builds one after the other on the same slide, runs 3-4 after an
  install): whole p50 26.9 / 22.6 → 26.9 / 26.9 ms, GPU p50 12.0 / 10.5 → 12.3 / 12.3 (the same within the run to run
  spread; the first two runs after an install are slower on both); the main thread's p90 9-10 → 20-21 ms (the
  selected plate now follows its own growing during the return, as it does on any move in the dock).
  Places changed 9 times in a slide there and back, all the selected plate's. Tests, lint, `nav` clean.
  `heroAutoplay` was off for the recordings and is back on.

- **"سوي ريليس بيتا" (2026-11-20, evening):** changelogs (English, Arabic) with a 4.0.0-beta1 section, a "Latest
  beta" line in both READMEs, the What's new tour for 4.0 (`WhatsNew.ID` 65: a beta welcome, profiles, streaming
  apps, Settings, smoother); tests (36), lint and the release build pass; installed on the Streamer, the tour's pages
  seen, `nav` clean; his `tourSeen` put back to 64 so he sees the tour himself. The pre-push hook would have refused
  the tree (test fixtures with 192.168 addresses, a gmail address, a JWT header and his second profile's name, also
  in CLAUDE.md): replaced with documentation-only addresses, example.com and made-up names, tests passing, nothing
  bypassed. `tools/fs2.sh` (a harmless frame-time script) is tracked since 1.9.3 and already public; left as is.
  Branch and tag pushed; the release is the open item above.

- **"سوي ريليس للبيتا، حولها stable" (2026-11-20, afternoon):** 4.0.0 (code 69) is 4.0.0-beta1 unchanged but for
  the version, the tour's first page ("Welcome to Glassio 4.0"), the changelogs and READMEs (one "Latest" line, no
  beta line) and the Reddit links (removed from both READMEs and the issue template: his answer). Tests, lint and
  the release build pass; on the Streamer: installed over the beta, the tour's first page seen, `nav` clean, his
  `tourSeen` put back to 64. `glassqa.py security` flags `tools/report-worker/src/github.js` (the words "BEGIN RSA
  PRIVATE KEY" in a comment and a regex, no key): a false alarm, already public in the beta.

- **Play Store variant, audit only (2026-11-20, evening; nothing changed in code):** he wants a separate build for
  Google Play with whatever breaks its rules taken out, and asked for the findings first. Read from the code, judged
  from the Play policies as known (not re-read on Google's pages; the review decides):
  - Must go: the updater (`Updater`, `REQUEST_INSTALL_PACKAGES`, the Beta channel), icon packs installed as APKs
    (`IconPackStore`, `pm install` through `LocalShell`), NewPipeExtractor trailers (`TrailerSource`, the YouTube
    search scrape, `TrailerSubs`), the PSN gift card (`Support`), `WRITE_SECURE_SETTINGS` and `ACCESS_ALL_EPG_DATA`,
    the shell commands that grant Glassio its own rights (`SystemSetup`, `Channels`, `NowPlaying.allow_listener`),
    the debug signing key (Play needs its own key and an AAB).
  - His to decide: all of `LocalShell` / dadb or only the grants and installs; Apple's aerials and the *Apple TV
    icons* source; Bing's daily pictures; JustWatch; the Nuvio / Stremio wording; the package id (another signature
    can't update the GitHub install: a `play` flavor with its own id is the proposal).
  - Needed besides code: the accessibility declaration with an in-app disclosure and consent page, a privacy policy
    and the Data safety form (problem reports, accounts), TMDB's attribution, a listing with no Apple / tvOS words.
  - Fine as it is: native libraries are 16 KB aligned, targetSdk 37, no `QUERY_ALL_PACKAGES`, Hue's pinned trust.

- **The play flavor, built (2026-11-20, evening; local commits only):** see **Two builds**. His answer on the grey
  points was "خليهن" (keep them); taken to mean the features stay, the package gets `.play` (my proposal: tell him
  again, it can't be changed after the first upload). Tests and lint pass for both flavors. The github APK is the
  same size to the byte as 4.0.0's (12,226,298); installed on the Streamer over his, `nav` clean. The play APK
  (11.4 MB) was installed beside it (left there for him to try: `adb uninstall com.glasslauncher.tv.play` removes
  it): the setup's first page, Settings → System (no *With ADB* row, the new words under *Open Glassio when the TV
  starts*), the agreement page on *Turn on the service* (Not now pressed), About (no Updates rows, no gift card,
  the privacy line). The dex scan is clean. **Found on the way:** the YouTube embedded player the audit expected
  as a fallback no longer exists (`TrailerLayer` needs streams), so the play build has no trailers at all.

- **Play upload key and the files for the Console (2026-11-20, evening; "كمل الباقي واحفظ المفتاح ع سطح المكتب"):**
  the upload key was made (RSA 4096, `CN=Glassio`, alias `glassio-upload`) and is outside git: where it is and its
  password are in CLAUDE.local.md and `local.properties` (`PLAY_*`). The play AAB and APK are now signed with it
  (certificate SHA-256 `d1d9d86b…8d5434`); the github APK still with the debug key, the same size as 4.0.0. On the
  Streamer, the play APK signed with the new key (the debug-signed one was removed first): the setup's System step
  (Home button, Default home app, Default screensaver, Start with the TV), the agreement page from there, *Agree*
  opened the TV's Accessibility settings (the service was **not** turned on there: his own Glassio holds the Home
  button). The github build after the split: a trailer found and its streams resolved (`trailer_find`, 1080p).
  A folder for the Console was put beside the key: the AAB, the APK, the 1280x720 banner, the 512 icon, a 1024x500
  feature graphic cut from the banner, four 1080p screenshots of his home screen (they show other companies' logos
  and posters: his to choose), the listing's words and the privacy policy.

- **Everything checked again and fixed (2026-11-20, evening; his request):** found in my own play work and fixed:
  the service's Back for a camera window it shows now works before the agreement (the check sat above it); the
  *Home button opens Glassio* switch opened the agreement but then did nothing (it opens the TV's page now); the
  setup's *Start with the TV* did not ask for "Display over other apps"; the agreement travelled with sync and
  with a backup (now `a11yConsent` is in `PeerSync.NEVER` and a restore keeps this TV's own); words about trailers
  left in the play build (the Library's hint, the tour, the menu's screensaver choices and order, two Hue lines);
  the service's description in the TV's settings (the play build has its own, `src/play/res`); the build commands
  in CI (`.github/workflows/build.yml` would have failed on the next push: `testDebugUnitTest` no longer exists),
  the PR template, CONTRIBUTING, README and `tools/tvtest.sh`; the privacy policy's list of services (Wikimedia,
  wsrv.nl, Hue's discovery, YouTube and Google Translate for the GitHub build). Tests and lint pass for both
  flavors, debug and release of both build. On the Streamer: the github build installed, `nav` clean; the play
  build: the setup's System step, *Start with the TV* opened the TV's "Display over other apps" page (nothing
  switched there); the TV has the pages the play build opens (Accessibility, Notification access, Home app).
  The play APK scanned again: clean (dead words about trailers remain as text nobody reaches).
  Left as it is, his to know: the play build's About still shows the GitHub page and its QR code (the source link a
  GPL app owes; the README there has the GitHub APK's download button, which a reviewer could dislike).

- **Manage from phone, rewritten (2026-11-20, night; his request: minimal and ordered like Settings, add what is
  missing, review all of it):** see **Manage from phone**. Seen from a browser on the network at a phone's size,
  dark and light, against the Streamer: the home list, Background & screensaver, Accounts & profiles, Apps with its
  filter (19 apps; "net" → Netflix and its menu), the choice list for the background, the search ("weather" → City
  in Look & menu, "netflix" → the app, nothing found), a found row opening its section lit, the typing window
  (focus in the field, the saved value in it), saving the weather city with its own value (answer shown), a
  report's preview (2,650 characters, no address in it; **not sent**), a wrong value and a request without the
  code refused. No console errors. Tests and lint pass for both flavors. Found in the review and fixed: the page
  had no Photos choice for the background, used the browser's own prompt / confirm, and several dev hooks sat
  before the key check (see above).

- **Manage from phone: liquid glass, Glassio's lights, burgundy (2026-11-20, night):** see **Manage from phone**.
  Seen from a browser at a phone's size against the Streamer: the home list on the lights, a section, the typing
  window with its burgundy button. `/bg.jpg` answers with the picture (8.7 KB) only with the code. His other ask,
  "always approve": the browser and design tools (chrome-devtools, claude-in-chrome, figma, easel) are in the
  allowed list of `.claude/settings.local.json`; pushing and releasing still ask, as his rule says.

- **Google Play, the account and the listing's pictures (2026-11-21):** the developer account exists; an app can't
  be created until the Console's own verification steps are finished (his to do: see CLAUDE.local.md). New
  1080p listing screenshots (themes, with and without *Colour on focus*, no trailer button or Trailer row) are in
  the Desktop folder: Play takes 8, the folder had 10 at last look. The play build was removed from the Streamer.
- **Universal search, looked into only (2026-11-21; nothing changed in code):** a request (issue *Universal media
  search*) and his idea on top: show the add-ons' sources in the search and play one in Nuvio / Stremio with rules
  for quality and size. Found on the Streamer with a free 10 s test clip and *Big Buck Bunny* (tt1254207):
  - **Stremio** (`com.stremio.one`) plays one exact source from outside:
    `stremio:///player/<stream>/<stream add-on manifest>/<meta add-on manifest>/<type>/<id>/<video id>`, each part
    percent-encoded, `<stream>` = standard base64 (not URL-safe) of the zlib-compressed JSON of the add-on's stream
    object (`Stream::encode` in Stremio/stremio-core). Played at once. It also has `stremio:///search`.
  - **Nuvio Legacy** (`space.nuvio.nativelegacy` 2.0.8): `nuvio-legacy://play/<type>/<id>` starts playing with its
    own pick (its settings: quality first, HDR, size limits, allowed add-ons, a regex) when it is already running;
    from a cold start it stopped at its profile chooser and then showed the title's page with the source prepared.
    It ignores the Stremio player link: no way found to hand it one exact source. Its words say links can also
    search; the form was not found.
  - **Nuvio (official,** read from its source, not on the TV**):** an exported `MainActivity` with the extras
    `launchMode=stream`, `contentId`, `contentType`, `videoId`, `name` opens the sources screen of that title, where
    its own auto-play (first source, or a regex) applies. No exact source from outside. Arc / Re-Shaped not looked at.
  - **Spotify** (`com.spotify.tv.android`): `spotify:search:<words>` opens its search with the results;
    `spotify:track:<id>` starts playing that track at once (also `open.spotify.com/track|album|artist|playlist|search`).
    Results as cards inside Glassio would need Spotify's API with a person's own
    key (its present terms not checked).
  - **SoundCloud** (`com.soundcloud.android`, a TV release): a track's own link (`https://soundcloud.com/<artist>/<track>`)
    opens the track and plays it at once. A search with the words filled in was not found:
    `https://soundcloud.com/search?q=` opens the Search tab empty (after the app's own subscription page, closed
    with Back), `com.soundcloud.android.action.SEARCH` with `query` opens the keyboard empty;
    `MEDIA_PLAY_FROM_SEARCH` not tried. Searching SoundCloud without a key (as NewPipe Extractor does) was not
    confirmed: a quick try from the Mac found no client id.
  - SmartTube (`org.smarttube.stable`) is on the Streamer, the official YouTube app is not; neither link tried yet.
  - His decisions (2026-11-21): no player in Glassio; hybrid (Stremio by Glassio's rules, the other apps by their
    own); all of it optional, off = as before; results look like the Library with their own settings; a way round
    Nuvio Legacy's profile chooser wanted (none found in the app: keeping it running is the proposal). Not built.
  - So: Glassio's own list and rules can end in the app only for Stremio; for the Nuvios the choice stays theirs.
    Playing a source in Glassio's own player would make it a streaming app (out of the play build, and a risk for
    the repo): his decision, not taken. The test left *Big Buck Bunny* possibly in Stremio's Continue Watching.

- **4.1.0-beta1, built and on the Streamer, not published (2026-11-21; his "نفذ بنسخة بيتا"):** three local commits.
  - *Search* (issue #21 and his own additions): see **Search**. On the Streamer: the icon's page on the carousel-blur
    background; typing with the TV's keyboard, titles while typing; *Inception* → Nuvio Legacy, Stremio ›, Save; for
    *Big Buck Bunny* Stremio's 11 sources listed by quality and size, one chosen, Stremio (cold) took it and its
    player started it (that 4K file then failed in Stremio's own player: "Exo failed to play this video"); Back
    from Stremio returns to the search page with the title selected; the settings page and its rows; moving on
    and off the icon against the Music icon (same); `nav` clean, no crash. Found and fixed on the way: the keyboard
    opening by itself on the next visit, Cinemeta's weak search (TMDB first now), Stremio stopping on a cold start.
  - *Nuvio One* (issue #20): `app.onetube.tv`, the same `nuvio://` links (read in zoga2o3-ops/Nuvio-One); added to
    the apps a title opens in. Not installed here: never opened.
  - *Languages* (issue #19): see **Languages**. On the Streamer: a settings section in Spanish and in Arabic, whole;
    switching Spanish → Arabic → English with Settings open shows at once (it didn't before the `rev` fix).
  - *After his look at it (same day):* the search's lists on the menus' glass; *OK on a title* can be one chosen
    app (set to Stremio for a try: OK went straight to its sources; put back to the list); no grey posters; glass
    pop-ups over Settings and the plain windows lighter; Arabic redone in formal Arabic. Seen on the Streamer: the
    pick list over Settings before and after, a section in the new Arabic, the search with its glass list.
    A feature request was opened on Nuvio Legacy's repository from his account (iqui27/nuvio-native-legacy #810:
    skip "Who's watching?" for a deep link on a cold start, by a setting or a profile in the link).
    **A dropped adb command let later keys land elsewhere** (the Play Store opened on an app's page; nothing was
    installed): keys are now only sent after the screen is confirmed, and never OK in the same command as the check.
  - *Second pass, after his list (same day):* the rows above (YouTube, music, service icons, order, menu row, remote
    button); the bar lower so the island doesn't sit on its edge; ◀ ▶ no longer leave a row for the menu; back from
    the menu to the bar; the blur left after Search → another app; in Settings, ◀ inside a page a row opened goes
    up one page (it went to the sections with the page still up: his video). On the Streamer: "daft punk" with the
    three rows, ▲ ▼ between them, 26 presses of ▶ staying in the row, the menu and Back to the bar, "stranger
    things" and "severance" with Netflix / Apple TV / Prime icons on their posters, new words while a poster was
    selected (the selection went to the bar, the page stayed), the page sharp again 1.2 s after leaving the icon
    (it stayed blurred), ◀ from the Search settings page. Not opened: a video in SmartTube, a song, Spotify's card.
  - *Third pass (same day):* the search over the whole screen for a dock in the middle or a column (seen with the
    dock in the middle: OK on the icon, rows, Back to the icon; the column dock's icon could not be selected by the
    test, same code path); logos without plates and the rating on posters (seen: Netflix, Apple TV, Prime Video);
    *Popular this week*; a *Search* switch among *Menu items*; the menu's row has its magnifying glass (seen).
  - *Fourth pass (same day):* cards with the name inside and a scrolling long name, Continue Watching on cards
    (seen: *Family Guy* with its bar and "S2 · E4"), sharper logos (seen), the bar empty on coming back (seen),
    ▼ once from the typing after the keyboard is closed (seen; with the keyboard up ▼ is the keyboard's own),
    results 280 ms after the typing rests and fading in. Not seen: the marquee moving, a song or a video card selected.
  - *Fifth pass (same day):* the cards as above (seen in both shapes, the name scrolling on the selected one),
    logos gone, the rating in the faint line, *Page behind the search* a real setting.
  - *Sixth pass (same day):* the bar at the top of the page and **no island while the search is up** (page or full
    screen); a selected card as in the Library (a poster grows 11 %, a wide card 6 %, the glow in the picture's
    colour, a bright rim); the rows move with the Library's `Glide` and `RowScroll` (now `internal`), the page
    between rows with a `Glide` too, a held ◀ ▶ on the Library's beat. Seen: the bar at the top, the island gone,
    the glow and zoom on a poster, the row moved along by eleven steps. Frames while holding a key were not
    measured (the tool returned no frames).
  - *Seventh pass:* no white rim on a selected search card (the zoom and glow stay). Faster fades: the page
    picture's way back from **any** stage page (search, music apps, inputs, an app's rows) is 200 ms instead of a
    slide's length (`fromStage` in `HomeScreen`; it was 560 ms and more), the way in 180 ms, the Search icon's rest
    before its page comes 130 ms (240 for the others). From a recording: in within ~0.25 s, back within ~0.45 s.
  - *Eighth pass: to the search and back at once.* The island goes as soon as the Search icon is selected (70 ms);
    the icon's rest before its page is 50 ms and 30 ms on the way out. Back to the slide that was showing, the
    carousel's picture is put back as it was (`keptPage` in `HomeScreen`: nothing loaded or made again; only for
    the same list). To an app with its own carousel, its opening slide is ready ahead: the picture and glass pinned
    by the carousel (`appFirst` in `Hero.kt`), the screen-size picture made ahead (`HeroAhead`, `BakeCache.keep`,
    three at most, not at `memTier` 0). From recordings (the TV's recorder drops frames: rough): Search → Nuvio
    ~0.5 s → ~0.1 s; Search → Apple TV (its own carousel) 1.4 s → 0.3 s; into the search 0.2-0.3 s. `nav` clean.
  - *Ninth pass: smoothness, measured frame by frame* (`gfxinfo framestats` after one key; the script is not in
    the repo). Found: (1) the interpreter, above: the biggest part; (2) the crossfade to and from the search drew
    two full pages and every glass surface twice, 45-55 ms a frame on the GPU: the search's page now comes and
    goes in one frame (`searchCut`), its cards fade by their own alpha (`ModulateAlpha`, no full-screen layer);
    (3) the search's rows are built one a frame. Longest frame, before → after (compiled, Glassio restarted):
    into the search 150-240 → 80-100 ms; Search → Nuvio 60-115 → 39; Search → Apple TV 100-128 → 60.
    Still there: one frame of 80-100 ms when the search page is first built (its bar and first row), and the
    home screen's usual 27-30 ms a frame while anything moves (the dock's glass: see Open items).
    `glassqa.py profile` read an old mapping file (pre-flavors path) and printed wrong function names: fixed.
  - *Tenth pass: a song from the search* (his: OK on a song went into the app and did not play). See **Search**
    (*A song plays under the search*). Seen on the Streamer: a song from cold; a second press on the same song;
    the song SoundCloud held paused; another song while one played; each plays and Glassio is back on the same
    card of the search. Found on the way: **a crash of the search** ("Index 1 out of bounds", three times that
    day while results arrived: a row's requesters read past their end; guarded, `at()` in `SearchStage.kt`), and
    the player's "selection lost" rule sending the selection to the first app while the search was up (`Hero.kt`).
    Not seen: Android below 10 (no identifier there), no media access, a song SoundCloud can't play (its
    30-second previews were not pressed), YouTube's cards (unchanged: they stay in the YouTube app).
  - *Eleventh pass:* bigger cards that never reach the dock and the Library's shadow under them (see **Search**,
    *Card size*). Seen on the Streamer in both shapes. Frames while moving along a row: 23-34 ms with either
    size (the page's usual range; the shadow's own cost was not isolated). The shadow is faint on his dark page.
    Spotify stays one card that opens its search (his decision; Spotify's TV app refuses
    `MEDIA_PLAY_FROM_SEARCH`: tried. Its session lists play-from-uri and play-from-media-id: not tried).
    The words collector picks up a quoted sentence inside a comment in `ui/`: don't quote one there.
  - *Twelfth pass:* titles are wide cards by default (`searchShape` "card"). **The scrolling name on a selected
    card trembled and Glassio's processor use was high** (his video). One cause, measured: `basicMarquee` asked for
    a frame every 16.7 ms and ran for ever, and any change on this page draws the whole screen again in ~27 ms, so
    the steps reached the screen unevenly and Glassio drew without end (40-58 % of a core, RenderThread; a song
    playing and the hidden island cost nothing: 0 frames). Now `SlidingName` in `SearchStage.kt`: one step every
    second frame, the words moved as a layer, two passes then rest. On the Streamer: every gap between frames is
    two refreshes (119 of 119; frame cost 25.6 ms), and after the passes 0 frames and 2 % of a core.
    (A first try, the marquee in its own layer, changed nothing.)
    Then "the letters still dance": the layer's place is put on whole pixels by Android, so a speed of 1.87 px a
    step gave steps of 2, 2, 2, 1 px (a twitch four times a second); and a second try, the words as a ready
    picture (`Offscreen`) inside the card's enlarged layer, was worse (the letters' white cores differed by 12-17 %
    between frames, against ~1 %). Now the name is taken out of the card's enlargement (`unzoomed`) and moves by
    the same whole number of pixels every step. Measured: frames every two refreshes (93 of 93), cores ~1 %.
    **Not confirmed by his eyes yet.** Comparing screenshots of moving text: only the pure-white cores count
    (the picture behind the letters shifts with the alignment and fills every other measure with noise).
    **Any animation that runs on this page must step every second frame** until a frame costs under 16.7 ms.
  - *Thirteenth pass:* running along the dock across the Search icon was heavy (his report): the page's rows
    were built and thrown away at every crossing. The rows now wait until the selection has rested on the icon
    for 220 ms or has come into the page (the bar still comes at once). Four keys across the icon, longest frame /
    main thread: 110-214 / 88-189 ms → 49-65 / 32 or less (between other icons: 37 / 18); what is left is the
    page picture's change (GPU). The Music heading's top edge showed above the dock with posters: pushed down
    out of sight when less than a heading's height is left (`pushMusic`).
    **A measurement in the first two minutes after an install is worthless**: Glassio's own compile
    (`dex2oat`, a minute after its start) takes a whole core; wait for it and restart Glassio.
  - *Fourteenth pass:* *Stay in the search* (seen on the Streamer on and off). **Back from the screensaver the
    carousel was black for ~0.4 s and the dock's glass showed the screensaver's picture** (his report), after
    ~0.8 s with no frame at all: the screensaver's video copies for the island's glass (`VideoFrames.showing`)
    were only let go when its screen was destroyed, after the home screen was back, so the home screen took a
    video to be behind it. Now `VideoFrames.saverClosed()` when the screensaver closes (its copy loops stay
    quiet for 3 s). Recordings (aerial screensaver, island in glass): the home screen's first frame has its
    picture; key to picture 1.3 s → 0.5-0.6 s (three runs). Still there: a few frames of 100-200 ms (main
    thread) while the home screen comes out of black; a profile of it showed nothing that stands out.
    Not seen: the trailer screensaver, the posters, your own background, an app's rows behind (his "blur
    background"), the TV's own start of the screensaver (dream).
  - *Remote buttons with no key code* (his ask: on a Homatics Box R 4K Plus the Netflix button can't be given
    a job in Glassio, in other launchers it can; that box is not here). Found in the code: a button the TV gives
    no key code (0) was refused everywhere (`KeyHub.usable`, and the window set "Not set"). Now such a button is
    known by its scan code, kept as a negative number (`KeyHub.idOf`, shown as "Button 183"), and what arrives
    while a button is being learnt is written to the log (so a problem report shows it). On the Streamer, with a
    keyboard made by Android's `hid` tool whose F13 has no key code there: learnt as "Button 183" (put back).
    **Whether this is what that box's Netflix button sends is not known**: if it still fails there, a report from
    that TV right after trying shows the line "Remote button while learning: …", or nothing at all (then the
    button never reaches Glassio's service: is the Home button service on?). `input keyevent` can't test any of
    this: injected keys skip the accessibility service, and 313 (All apps) is taken by the system before the app.
  - *The menu's Search over an app's rows* (his report: the search came up over the rows and the selection
    vanished): `SearchUi.open` now lets the selected app's page go (rows, music apps, inputs), the rows are not
    drawn while the search is on, and the menu hands the selection to the search bar first (it went into the
    rows under the search). Seen on the Streamer from SmartTube's rows: the search alone, its bar selected, Back
    to the Search icon. Not seen: from the music apps' and the inputs' pages, the remote button, a dock without
    the Search icon.
  - Tests and lint pass for both flavors; the play APK scanned clean. His settings as before except: `searchOn` on
    and the Search icon second in his dock (left so he can try it), `settingsNewFor` 4.1.0.

**Earlier (2:45 am):** released 3.8.0 stable (code 67, tag `v3.8.0`, `main` = d68f00a).

## Open items

- **Glass smoothness and sharpness (his request, in progress).** Done so far (7 am, both TVs):
  - Clear glass reads the page picture at its full size (it read a half-size copy enlarged again, softer than the
    page around it). No cost in a slide on either TV. `memTier` 0 keeps the half-size copy.
  - With no icon selected the dock's row of icons is one ready picture (`CompositingStrategy.Offscreen` on the
    row), and a clear icon that isn't selected on a clear dock draws only its tint (`LocalClearBehind`): it showed
    the same picture the dock already shows. A slide on the Streamer went from ~21.5 to ~18 ms a frame.
  - The rim during a slide is drawn as four bands instead of a ring path where the two are the same shape.
  - What a slide's frame costs on the Streamer (ms, `IntendedVsync` to `FrameCompleted`): nothing drawn 8.5,
    backdrop ~4.5, carousel text ~2, top bar ~1, dock ~3 now (it was ~7: rim shader ~3, icons ~2.4, icon glass
    ~1.8). A frame must stay under 16.7: at ~18 it still misses, and then often locks at 30 (frames of 28 ms).
    "Heavy" and "light" slides are that lock, not two kinds of work.
  - Tried and measured as no gain (not kept): refraction off, the rim's slope worked out directly, one picture
    reading instead of two in the rim shader, CLAMP instead of DECAL, each icon as its own cached layer, no rounded
    clip on the icons, pausing the picture/blur/trailer workers while the page moves, Android's performance hints
    and a busy "warm-up" thread.
  - Next: the rim shader's ~3 ms (it is the draw itself, not the arithmetic), the carousel text and backdrop
    during a slide, then page changes (carousel to rows to music: still 21-32 ms) and the strip / vertical docks,
    which did not get the ready-picture row yet. Frosted styles (`liquid`, `clear`) were not measured.
  - The TCL draws a slide in ~14 ms (its GPU takes under 2 ms); its slow part is the main thread (p90 17-34 ms).
- **Measuring rules learnt the hard way:**
  - `--ei dbg_hide` (and every other dev hook that changes something) needs `--es dev <key>`. Without the key it
    is silently ignored: a night of "hiding changes nothing" results was exactly that.
  - Pause the music and turn `heroAutoplay` off first, or the island's frames and the automatic slides fill the
    120-frame buffer. `glassqa.py lab` does this, puts everything back, and has the scenarios.
  - Judge a slide by its own frames (press, then read `gfxinfo framestats`), several slides in a row; a p50 over
    mixed frames says nothing. First-visit and repeat slides, background work and outside CPU load all looked like
    causes at some point and were not.
- Slides: the dock and all its tiles still recompose at the start of every slide, because the big HomeScreen lambda
  recomposes. Isolating the dock (stable inputs, its own scope) is the next win (step 2 of the beta work). During a
  slide the GPU takes 14–20 ms a frame; the island glass adds ~5 ms.
- Dock moves are heavy on both 3.8.0 and the beta: every frame over 16.7 ms, p50 26–32 ms, p90 55–60 ms, main
  thread p90 16 ms. The GPU half sits at either ~20 or ~28 ms a frame from run to run (cause not found yet).
- Not tested: the updater's download (no release newer than the beta exists; the silent install itself works, see
  Last session), a real Bluetooth disconnect, Hue colours on the lights themselves, anything specific to
  Android 16/17.
- Native memory grows over a session (328 to 386 MB within an hour). Watch it with `glassqa.py res`.
- Photo source on the TV is `reddit` with no app id: no new photos come until he adds one.

- Not yet seen on the TV:
  - Glass at a light blur during slides (stills look right).
  - Bluetooth disconnect confirmation.
  - Subreddit source (needs a Reddit app id).
- Middle dock + Music / Inputs icon OK does nothing there (by design).
- Glitch Reflections icon pack: no public download.
- Waiting on Nuvio devs (discussion #4644) about profiles.
- Continue Watching in app carousels, not tested: Play on a title in progress continuing in that app, a second
  profile. His own country (US) gives no title for an installed app; it was seen with France.
- Catalogs for apps, not done or not tested: the Settings rows (*Apps and their catalogs*) were never opened on the
  TV (the dock menu's way was); a second profile's own choices; the vertical and middle docks; an app's list while
  auto-play advances (the place of a list is saved only when the list changes); the first slide's picture of an
  app's list is not prepared ahead (only the titles are); the carousel changing under every dock move costs a
  slide's frames (26.5 / 30.5 ms): if it bothers him, raise the 240 ms rest or change only on Up.
- Problem reports, not tested against the real relay: a report sent with the dialog's own Send (label `bug`), the
  same crash twice (one issue, one comment), the per-TV limit of 5 a day, a real crash in a report, the QR read by a
  phone. Not done: the row in the side menu (About only).
- Nuvio's pictures, not tested: an uploaded picture (`avatar_url`, his profiles have none), a supporter's picture
  (needs a supporter account), a picture changed in Nuvio while Glassio runs (it is read at the next start or when
  the chooser opens), the TCL.
- Carousel buttons that follow the theme, not seen: over a really bright picture (his slides are dark on the left,
  where the shade is; with his photo background the carousel's block did not come up for a screenshot), the
  *Play only* button, the + button, square corners, the TCL.
- Music & Inputs page, not seen: an idle player on the new backgrounds (Spotify shows its rows on his TV), the
  island over them with a song playing elsewhere, the carousel changing by itself while the page is up, a second
  profile, the Settings row itself (set through the dev hook).
- Glass pop-ups over Settings, not seen: a `PopMenu` over Settings (none opens there today), the icon picker opened
  from Settings (its panels now follow the same rule), a video background behind Settings, Black AMOLED.
- Carousel shade off, not seen: an app's rows and the music / inputs pages without their left shade, the trailer
  in the home player, Slide and Fade animations (Apple TV was the one in use), the big clock, the TCL.
- Library scrolling, not done or not seen: the TCL (asleep, not woken: his permission was "if it is on"); his own
  remote (the beat was measured with the QA keyboard: 200 ms repeats); a frame caught in the middle of a fast hold
  (posters were all there right after one); in a held Down the first row still glides and slows (43 → 8 px a frame)
  before the run starts at the first repeat, 0.4 s in; one or two frames a second arrive a frame late in the fast
  part (the page then moves two frames' worth, as before the change); a Library much longer than his (7,400 px).
- From the 2 am run, not seen: the strip dock after a real install or uninstall and after a reorder (the same
  code path as hide / show, which was tried); the regular and vertical docks with a glass colour while sliding
  (their rule changed too: a plate on the dock no longer adds the colour); Glass colour Petrol; frosted themes with
  a colour; the screensaver started by the TV itself (dream) and one running across a catalog refresh; whether the
  strip's refraction is worth its ~1 ms of GPU is his call (`refract = true` in `StripDock`).
- Settings, not seen (phases 1 and 2): most sub-pages (only Add-ons, an add-on, Catalogs in the carousel, Apps and
  their catalogs, Remote buttons and Manage from phone were opened); Lights & areas and Bridge of Hue reading the
  bridge inside their page; Sync between TVs as a page; Arrange apps (picking an app up and moving it there); the
  rows that only show in another mode (Photos, screensaver videos, the aerial background, a trailer window);
  the other links; the search finding moved rows; the dialogs opened from sub-pages; Select all / none and Remove
  add-on (not pressed: his catalogs); the menu's Manage from phone row; another screen size (the layout is
  proportional; only 1080p was seen); the TCL. Phase 3, not seen: *Back to default* on a switch (only an Adjust was
  tried), *Mark them all as seen*, a fresh install (no marks), the next version replacing the marks, the search for
  "new", Changed's sentence at the top in Arabic (it has a number: left in English), the marks in Arabic.
- Settings after the p4 fixes, not seen: Back from *Manage from phone* and from About's pages, a link row (→) to
  another section (same `hold()`, not recorded), the search's results opening a row inside a page, the flat
  panel styles (Black AMOLED / Black on white), Arabic, the TCL. The first walk's one frame of ~145 ms is not
  split up; a section's page costs up to ~90 ms of the main thread when it is first built (not new).
- Glass by its place in the page, not seen: the regular and the vertical dock, the dock in the middle, frosted
  styles, a video or your own background behind (the page doesn't slide the same way there), the island and the big
  clock during the slide (the clock's cut-out still uses its place on screen, `BackgroundExtras.kt`), the TCL. The
  main thread's p90 during the return rose by ~10 ms (the selected plate remade while it grows): if it shows,
  let the plate keep one place until it has grown.
- **4.0.0 stable released (2026-11-20):** the public commit `c1795a2` (one commit on 461c7a4, the tree of local
  `beta` at `dbf37e5`) is `origin/main`, tagged `v4.0.0`, the *latest* release, with `Glassio.apk` and
  `Glassio-4.0.0.apk` (sha256 `78c4f2ef990004931e17085c3099cca35514fc8023622f3d90b1c1447c1f4446`, downloaded and
  compared: the build on the Streamer). `origin/beta` stays at `9631597` (4.0.0-beta1, still a prerelease). Not
  seen: the updater offering 4.0.0 to a TV on 3.8.0 or on the beta (the TCL), the Downloader code fetching it.
- The play build, not seen on a TV: *Take back my agreement*, Music asking for Notification access,
  Channels' own question, the Carousel section without its Trailers group, the screensaver with a saved "trailer"
  mode, the tour without the support page, a backup from the github build restored into it, the AAB itself (built,
  never uploaded or installed with bundletool), the service really on in the play build. Not done: an embedded YouTube player to bring trailers back, a banner and screenshots for the
  listing, moving `IconPackStore` / the updater's installer out of `main` (R8 drops them today).
- Manage from phone, not tried (each would change his TV): signing in, adding or removing an add-on, a camera, a
  video, hiding an app, an icon from a link or an upload, a background upload with its progress, a download from a
  link, the Photos source, the doorbell's words, the Hue address, the profile's name, restore, sending a report.
  Not seen: a real phone (Safari on iOS: the keyboard over the typing window, the back swipe), Arabic text in a
  field, the play build's page (the same file). The page is English only.
- **4.1.0-beta1 released as a beta (2026-11-21, "سوي ريليس للبيتا"):** the public commit `e6ae176` (one commit on
  `origin/main` c1795a2, the tree of local `beta` at `ebd181c`) is `origin/beta` (forced over 4.0.0-beta1's commit,
  which its tag keeps), tagged `v4.1.0-beta1`, a prerelease with `Glassio-4.1.0-beta1.apk`
  (sha256 `3f41ea91…cf2d827`, downloaded and compared; the same file is on the Streamer). *latest* is still v4.0.0.
  Issues #19, #20 and #21 were answered and left open (waiting for their authors). `WhatsNew.ID` is 66.
  Not seen: the updater offering it to a TV on 4.0.0 with the Beta channel on.
  A rebuilt APK is not the same file byte for byte: install the very file that is uploaded before releasing.
- Search, not done: Spotify's own cards (need a key), episodes of a series for
  Stremio's sources, keeping Nuvio Legacy running so its profile chooser doesn't come up.
- Search, not seen: a title that is on an installed service (none of the ones tried was on Netflix or Apple TV in
  his country, so no service row was ever shown or opened from the search), *Opens the first place*, *Nuvio plays at
  once* from the search (the link itself was tried by hand), the vertical and regular docks, the dock in the middle
  (the icon then does nothing: by the same rule as Music and Inputs), your own background, the play build, Arabic
  typed in the bar, the voice key, results without a TMDB token on the TV, a source that needs Stremio's
  streaming server (connection refused there: the *Torrents* switch is off by default for that).
- After leaving the Search, Music or Inputs icon for a carousel app the page stayed blurred for two seconds or
  more before the sharp picture returned (measured the same for Search and Music, with automatic slides off for the
  test): not looked into, may be the test's state.
- The windows he called too dark "in the setup": which ones was not clear (the setup has no glass pop-up of its
  own; its dialogs are the plain windows, now lighter). Not seen on the TV: a plain window (text entry, menu, QR)
  after the change, anything in the setup.
- Languages, not seen: the other six languages on the TV, the side menu, the setup, dialogs and the tour in any of
  them, a text too long for its row (titles are one line and cut with "…").
- **4.1.0 released as stable (2026-11-22, "سوي ريليس ستيبل"):** the public commit `6b59a79` (one commit on
  `origin/main` c1795a2, the tree of local `beta` at `8a3376a`) is `origin/main`, tagged `v4.1.0`, the *latest*
  release with `Glassio.apk` and `Glassio-4.1.0.apk` (sha256 `548de0ea…07992ce`, downloaded and compared, also
  through the `releases/latest/download/Glassio.apk` link the Downloader code uses; the same file is on the
  Streamer). `origin/beta` stays at `e6ae176` (4.1.0-beta1). The tour's first page says 4.1 (`WhatsNew.ID` 67;
  his `tourSeen` put back to 66 so he sees it himself). #21, #22 and #23 were told it is out (left open for their
  authors to confirm). Not seen: the updater offering 4.1.0 to a TV on 4.0.0 or on the beta.
- **After 4.1.0, his "do them" from #21 / #22 (2026-11-22, not released):**
  - *Rows above the dock* (`channelsLow`, off: as before): an app's single row sits right above the dock, so the
    large clock and the page's top stay in view (#21's photo). Seen with SmartTube's row, off and on.
  - *Same video as the screensaver* (`saverSameVideo`, on; shown with the aerial background): the screensaver
    starts with the background's video where it was (`CustomBackground.leftVideo` / `leftPos`, matched by title),
    and the background goes on with the one the screensaver ended on (`SaverControl.ended*`). Seen: background
    *Sand Dunes* → screensaver #0 *Sand Dunes* → back, "on from the screensaver's (Sand Dunes)"; `live` ok.
  - **The background video is let go while Glassio is away** (stopped for 5 s: another app, the screensaver):
    `player.stop()`, prepared again on the way back (never for the Library or Settings). Seen after the screensaver
    and after 9 s in the TV's settings: "let go while away", the video and its glass back (`live` ok). PSS on the
    screensaver 371-382 MB against 543 on the home screen with the aerial background (not compared with 4.1.0).
  - *Pexels* in the screensaver's *Collections* (`pexelsKey`, the TV's; `pexelsQuery` "aerial"; source key
    `pexels`): Pexels' video search with the person's own key, landscape, 1080p and 4K files, each title naming
    its maker and Pexels. **Not tried: no key here** (making one needs a Pexels account: his).
  - *A YouTube link among My videos* (github build: `AerialSource.youtubeId`, `TrailerSource.resolve`; the cache's
    data source sends the trailers' user agent to `googlevideo.com` only; the play build leaves such links out).
    Seen: Big Buck Bunny's YouTube link as the only screensaver video, 1080p H.264, playing. Plays in the
    screensaver and in the aerial background (both read My videos); not as *Your video* (`bgVideo`).
  - Excluding a video (his choice, all optional): *Hold OK asks* (`saverHoldMenu`, off: held OK locks as always)
    opens *Keep this video* / *Don't show it again* (`saverHidden`, "id<TAB>title"; left out of both playlists);
    *Hidden videos* brings them back. Seen: the menu, a video hidden and the next one coming, the list, shown again.
  - *Let the video go while away* (`bgLetGo`, on) is its own switch now.
  - **#23: OK on the Search icon did nothing**: pressed before the page had come up (now it waits 50-420 ms) the
    press was taken as old. `SearchUi.typeAsked`: the page goes into typing as soon as it is there. Seen: two quick
    Rights then OK at once, the keyboard up.
  - **The TV's own "Are you still watching?" window** (it shows when the TV says another input was chosen) took
    an OK meant for Glassio's menu during a test: its selected button was *Don't ask again*. Told him (his TV's
    setting; not changed back by me). Look at the screen right before every OK, also inside the screensaver.
  - Full navigation check on the beta's exact file: `nav` 13 steps ok, no crash; by hand: the Music, Search and
    SmartTube pages up and back, the search from the menu and from outside, the folder, the Library, back from
    the screensaver. #21, #22, #23 told.
  His settings put back (bgMode carousel, aerialUrls his one link, saverMine off, no pick, channelsLow off,
  tourSeen 66).
- **After 4.1.1-beta1 (2026-11-22 evening, not released): his "the sync isn't right, the return is heavy".**
  His setup then: `bgMode` aerial, `bgLetGo` off (he turned it off), screensaver 4K, a song playing.
  - Sync, fixed: with the video not let go, the background stood paused where the screensaver began and jumped
    back on return (the seek was only in the let-go branch). Now it seeks to the screensaver's real place
    (`SaverControl.positionNow`, the player's own position) either way. The screensaver's start place was lost
    (`sameFrom` was a plain local: 0 after a recomposition; `sameFromBox` now). Seen three returns in a row: 45 s →
    73 s → 101 s of the same video, each the screensaver's 15 s further.
  - Return, lighter but **not solved**: (1) `VideoFrames.clearForHome()`: with a video background of its own the
    copies are kept when the screensaver ends (cleared, every glass surface changed to "no video" and back, twice);
    (2) `inFront()` / `WhenResumed` / `VideoBackgroundInFront` in `HomeScreen.kt`: whether Glassio is in front is
    read in small composables (read in `HomeScreen`, every return composed the whole screen twice); (3) the
    shuffle state is asked off the main thread, once a second at most (`NowPlaying.publish` made a
    `MediaControllerCompat` call at every update). Longest frame on return, main thread: 195-314 ms before;
    104-131 with the song paused (some returns none over 80); **still 240-290 with a song playing**: what is
    left belongs to the music state on return (`NowPlaying` callbacks, `pick`, the island composed again),
    not found yet. `glassqa.py profile` printed an old report all day (it reads a stale file when the record
    fails): record by hand (`simpleperf record --app … -f 4000 -g --duration 2`, report `--comms
    com.glasslauncher.tv --children`) and translate with the mapping.
  - Defaults: `saverHoldMenu` on (his ask); `bgLetGo` on only on a TV with less than 3 GB (`Settings.smallTv`):
    letting go costs a reload on return.
  - #23's author on the beta: "OK on the Search icon, then Up, for the bar to show": not reproduced (strip and
    regular docks, carousel and video background: OK goes into typing); asked for his dock and background.
    #25 (new): several actions on one button, an action that closes the app in front: asked, not built.
  - A gift card came through the support form (his photo; code not copied anywhere); thanked on #22 without
    naming the sender for sure.
- **Three fixes (2026-11-22 night, not released), each seen on the Streamer:**
  - #23 (Search icon in the dock's second row did nothing): a search asked for while the dock is open folds it
    (`SearchUi.want` → `expanded = false` in `HomeScreen`; `AppLock.open` asks `SearchUi.open` from a lower row).
    Seen: Search moved to row 2 of the regular dock, OK → the search with the keyboard (put back after).
  - Back from the screensaver over a video background: the stand-in frame (taken when the screensaver began, the
    small frosted copy enlarged: blurred and lighter, then a jump) is gone; the stand-in is only the sharp copy,
    never after a Same-video seek; the screensaver's black (`SaverGate.cover`) waits for the video's first frame
    (`VideoFrames.drawnRev`, 1.5 s at most). Recordings: one fade from black to the video, no jump (0.6-1.5 s of
    black first). His slow-motion video showed the old way: 87 frames brighter and blurred, then the jump.
  - The Music icon over a video background: the player's page fades with the glass (`AnimatedVisibility`, 220 ms,
    in `Hero.kt` when `customBg`); it came in one frame while the dock's glass took 0.2 s ("the dock draws its
    glass late"). Recordings: page and dock rim change in the same frames, both ways.
- **The screensaver on the home screen (option 3, 2026-11-22 night, not released):** `saverInHome` (off; Settings →
  Background & screensaver → *Screensaver · When* → *On the home screen*, hidden with the TV screensaver type),
  `saver/HomeSaver.kt`. No screen is opened (`HomeSaver.begin` from the idle timer, Back, `start_saver`; the TV
  screensaver type and Settings' *Preview now* keep their own screen): the home page fades away (`homeAlpha`,
  700 ms) and MainActivity draws the saver over it (`HomeSaverLayer`), keeping the screen on. Aerial background +
  aerial screensaver (`onBackground`): the background's own player goes on (◀ ▶ step it: `CustomBackground.next` /
  `back`; it always moves on unless the video is kept), the place, island, large clock (from `VideoFrames`) and the
  held-OK menu are drawn over it; Back only fades the home page in. Anything else: `SaverContent` over the home
  screen (its own player; the home screen's video waits paused), closed through black (`state` 2: the surface is
  gone before the black fades). Any other key, a touch, Home or `onStop` ends it; the carousel's autoplay, its
  trailers and Hue's driver wait while it shows. On the Streamer, with a song playing (`pass.py`, Back, longest
  frame / main thread): on the home screen 72 / 50 and 33 / 17 ms; its own screen 310 / 300 and 329 / 289; the
  posters on the home screen 35 / 6. Seen: dock and carousel gone, clock and island, ▶ to the next video, Back to
  the dock at once, the posters and back. **His heavy return with a song is gone only with this switch on**; the
  old way is unchanged. Not seen: the held-OK menu and ▼ / ▲ on it, the idle timer starting it (his is off), the
  trailer screensaver, a My video background paused under it, a profile chooser or the TV's dream over it. The
  4K-while-it-shows idea was not built. His `saverInHome` is off (as before).
  **One island at a time** (his "follow the glass in this option"): the screensaver's island and the home
  screen's were both up at the start (one fading out under the other) and on the way back the screensaver's went
  at once while the home page was still fading in (two frames with no island). Now `HomeSaver.ownsIsland` hides
  the home screen's while the screensaver shows; going back is `state` 3 for 220 ms (the home page fades in, the
  screensaver's words fade out, its island stays) and the two islands change in one frame (`HomeSaver.homeIsland`,
  set by the home screen; when the home screen has no island the screensaver's fades). Recordings read frame by
  frame (island / dock brightness): start 115 → 117 while the dock fades 54 → 38; back 111 → 110 → 117 (the step
  is the song name's scroll starting again in the home screen's island, the glass is the same), dock 38 → 54; on
  ▶ the island's glass darkens with the video's fade and comes back with the next one (147 → 37 → 135); still
  copies were checked live (0.7 s apart: island 28 / 26 / 18 / 15, background 19 / 15 / 13 / 10).
- **One player on by default, the menu's screensaver page, #28 (2026-11-22 night, not released):**
  - `saverInHome` is on by default and its row is called *One player* (Settings and the menu). His is on.
  - The menu's *Screensaver* page has *Collections ›* (the collections and Pexels with its key, a page of their
    own as in Settings; Back returns to its row), *YouTube lists ›* (github build, when My videos has YouTube
    links) and *One player*. `saverSub` in `SideMenu`; a "more" glyph for rows that open a page.
  - YouTube links can be left out without removing them (`saverYtOff`; Settings → My videos → *YouTube lists*,
    and the menu). Their names are the playlist's / video's own (`AerialSource.ytTitles`, filled as they are read).
  - Seen on the Streamer: the page, Collections and back, One player switched on from the menu. Not seen: the
    YouTube lists page (his My videos has no YouTube link).
  - **#28 (an onn 4K Pro on 4.1.1-beta1: "crashes when trying to access my IP camera")**: read with the mapping of
    the released build (rebuilt from `origin/beta` `4e92df7`: the same r8 map id as the release's APK).
    `RtspClient.MessageListener.handleRtspResponse` (Media3 1.11.1, line 547) takes the reply's `CSeq` with
    `checkNotNull`: a camera that answers without it threw on the player's thread and Glassio closed. Camera
    players now have their own playback thread that drops such a message and goes on (`CamPlayer.safeLooper`,
    `CamPlayer.release` ends it; runtime exceptions only, logged once). Tested with a fake camera on the Mac that
    answers without CSeq (`--es cam_test <link>`, dev key, opens the corner camera on one link): the same
    NullPointerException in the log, Glassio's process the same before and after. **Port 8554 on the Mac is his
    mediamtx**: use another port for a test server.
- **Hue button, TV inputs button, About (2026-11-23, his reports):**
  - *Hue lights off / on from a remote button* came back dimmer than before: on again it only switched the group
    on and the screen's colours followed. Now each light's state is kept before off (`HueSync.beforeOff`,
    `Hue.groupLights`, `Hue.states`) and put back exactly (`Hue.restoreCommand`); the screen's colours follow
    again only if they did before. **Not tried on his lights** (3 am; it changes his room).
  - *A button set to the Inputs icon* said "isn't installed" (the icon was saved as an app). Now action `inputs`
    (old "app:inputs:all" lines too): `InputsPickActivity` over any app (its own task: in Glassio's task it pulled
    the home screen up behind it), the inputs as cards, OK switches, Back or the button again closes; its glass on
    Glassio's bright lights like the tour (his choice: a screenshot of the app behind was tried first, through the
    TV's adb, and dropped; a file the shell writes can't be read by the app anyway). *TV inputs* in the buttons'
    choices; the Inputs icon no longer among the apps there. Seen on the Streamer over the TV's settings.
  - *About* took several Right presses: the jump to the first row (this morning) found no rows in About's own
    pane and sent the selection back. About (and New / Changed) are left out of it; seen: one press.
- **The TCL, measured and lightened (2026-11-23, his ask; the local build is installed there now):** BeyondTV2,
  Android 11, 32-bit, Realtek RTD285O, 4 cores, 2 GB (~100 MB free, swap in use). Its load average of ~20 is the
  Realtek drivers' threads waiting (D state), not processor use: the CPU is ~70 % idle. Its `perfMode` is
  `custom`, so the automatic presets never apply there (changing any of six settings makes a TV custom for good).
  - Measured: moving between plain dock icons is fine (p50 12-16 ms, main thread 2-7 ms, the render thread
    ~5-7 ms, GPU ~2 ms). The heavy moments are pages coming up as an icon is reached (an app's rows, the
    search): one frame of 25-60 ms drawing on the main thread and 2-3 frames of 13-20 ms composing, p90 55-95 ms.
    At rest ~24 % of a core on average, spikes at each automatic slide (pictures to the GPU, Hue colours).
  - **Run-to-run noise there is larger than these changes' effect** (the same step 27 → 118 ms): before / after
    could not be shown on it; the changes are by reasoning (less work), not by a measured gain.
  - Changed (speed fixes, tier-0 = `memTier` 0): the slide's preload asks for the copy the page uses (w1280 with
    "1280 enhanced", it loaded the original for nothing); no trailer worked out ahead on tier 0 unless trailers
    start by themselves; the selected dock plate is plain below Android 13 (the glass can't bend there); Coil
    decodes one picture at a time on tier 0; the island's bars ~6/s and progress 1/s on tier 0; the Music &
    Inputs page blur only while one of those icons is selected on tier 0 (it ran after every slide); an app's
    rows page is built a row a frame. Also the player page's fading focus (`fadingFocus` in `Hero.kt`).
  - Not done, proposed to him: a cleaner Performance section (show what the TV is, a "recommended for this TV"
    choice, and not losing Auto when one setting is changed), automatic slides only on the carousel, the search
    page lighter on tier 0.
- **The large clock with rows above the dock (#21) and in the menu (2026-11-23):** with *Rows above the dock* over
  your own background the clock is drawn outside the carousel's fade (`clockThrough` in `Hero.kt`), so it stays
  above an app's row (it went with the carousel). The menu's Background page has *Large clock* (`bgClock`) after
  *Next video*. Seen on the Streamer: the clock above SmartTube's row; the menu row turned it off. His `bgClock`
  and `channelsLow` back off.
- **Performance for weak TVs, his four yeses (2026-11-23):**
  - *Recommended for this TV* (Settings → System → Performance): the TV's kind (`Settings.tvKind`: model, memory,
    Android, cores, OpenGL ES) and the tier Auto picks; OK = Auto with every performance key back to what Auto
    picks here (`recommendedMode`, your own changes too). "In use" when nothing differs.
  - *Auto isn't lost*: a performance setting changed by hand is kept in `perfOwn` (the TV's, in sync's perf group)
    and the mode leaves it alone (`applyMode` skips it); the Mode row says "N set by you". Was: any change made the
    whole mode Custom. A TV already on Custom stays Custom until Recommended or a mode is chosen.
  - *Slides change on their own · Where* (`heroAutoplayWhere`, "always" / "carousel"; Settings → Carousel; the
    Performance preset sets "carousel"): only while the carousel itself is selected.
  - *Popular this week* is off from the start on a TV under 3 GB (`searchTrending` default `!smallTv`).
  - Seen on the Streamer: Auto + Prepare ahead changed → "Auto (Balanced) · 1 set by you"; Recommended → every key
    back, "In use"; his values put back (Custom, his 13 values; `perfOwn` left empty). Autoplay over 35 s each:
    Always on the dock changed, carousel-only on the dock didn't, on the carousel did. On the TCL (local build
    installed again): the empty search without Popular, Performance showing "BeyondTV2 · 2 GB · Android 11 · 4 cores
    · OpenGL ES 2.0", Recommended → Performance (not pressed: his TCL stays Custom).
- **Code review of everything since 4.0.0 (2026-11-23, his ask):** four read-only reviews (search; accounts,
  Jellyfin, phone page; screensaver and video; remote, cameras, UI), every finding checked in the code, the real
  ones fixed. The main ones:
  - *Screensaver on the home screen:* it only starts over the home screen itself (`HomeSaver.homeShown`, read in
    composition, and `folderOpen`), else its own screen (it started during the setup and took every key, and over
    Settings / the Library); the key that ended it is eaten up to its release (`eatAfterEnd`: Back held 220 ms
    started it again); its player stops on ON_STOP; "Same video" state cleared with its players; a mouse / hover
    ends it. Seen: Back held → it ran once; from Settings → its own screen.
  - *Video background:* let go also when Glassio is left from Settings or the Library, and while the home
    screensaver plays its own video (two decoders on small TVs, #22); the list is rebuilt when a video is hidden;
    *Keep* / *Don't show it again* work for every list (`idOfUrl` adds up, `mineItems` fills it; the toast only when
    it was hidden); the clock's copies with See-through glass in the home screensaver; `BakeCache.clear` clears the
    pictures made ahead.
  - *Search:* a JustWatch failure no longer hides titles as "on no service"; every result is checked for *Only on
    my services* (pages added later too, not Popular); "Searching…" can't stick (`asked` set last); focus answers
    read (`getOrDefault(false)`); one fade per search; "didn't answer" when offline (titles / resolve throw when no
    source answered); places interruptible; one 13 s deadline for all Stremio add-ons; dead code out.
  - *Jellyfin:* picture links without the token (they were saved in the Library and sent to Nuvio); the device id
    from the TV (`ANDROID_ID`), never in a backup; a 401 signs out; Play uses the index in memory (`peek`), never
    waits; a failed index retried after a minute; `jf:` titles never sent to Nuvio and never opened in Nuvio
    (a toast); the "catalogs ticked once" flag in `glass_jellyfin` (in `glass_catalogs` it travelled with sync);
    libraries with no type (films and series together) shown; Next up request, `still`, `searchInApp`,
    `lastError` removed.
  - *Cameras:* a Home Assistant answer after the player moved on is dropped (`playNo`); the MJPEG stream
    reconnects; the surface is let go at once on stop; multi-view frees its players through `CamPlayer.release`
    (it leaked a looper thread per camera).
  - *Remote:* the double press counts from the second press going down (a slow second press was two singles; seen
    fixed with a virtual key); the button map parsed once per change; button toasts translated.
  - *Other:* the update-time compile on its own adb connection (`LocalShell.stream`: it held every other TV command);
    example addresses `jellyfin.local` (192.168 would be refused by the pre-push hook); doc comments put back
    beside their settings. Left: the player page's focus during its 220 ms fade over your own background (low risk).
  `nav` clean, tests and lint for both builds.
- **Home Assistant cameras without a stream (#28's second report, 2026-11-23):** `HaWatch.streamUrl` asks for HLS
  (`camera/stream`); an MJPEG or snapshot-only camera (or HA's `stream` integration off) answers with an error, and
  the player was only stopped: "Connecting…" for ever. Now `CamPlayer.play(ctx, p, url, tex, onFail, onLive)`
  falls back to `HaPictures`: the camera's MJPEG stream (`/api/camera_proxy_stream/<entity>`, JPEGs cut between
  their markers) drawn on the same TextureView (the player detached first), else its snapshot
  (`/api/camera_proxy/<entity>`) every second; nothing after 5 tries: "Can't reach the camera". All three camera
  windows (corner, multi-view, the menu's `CameraView`) pass their view. QA (dev key): `--es ha_test
  "http://host:port|token"` (`-` clears) points `cam_test ha://…` at a test server. Seen on the Streamer with a fake
  HA on the Mac that refuses streams: the MJPEG camera's picture, the snapshot camera's picture, "Can't reach the
  camera" for one with neither. Not seen: a real HA MJPEG camera (the fake sent all its frames at once, so motion
  wasn't checked), multi-view with such a camera.
- **Glass over the video: See-through (2026-11-23, his ask after "why is the clock smooth and the dock not"):**
  the large clock's glass digits let the video itself show through (its picture inside the digits is faded by
  *Glass opacity*), while the dock, the island and the menus draw copies of the video (~15 a second, 720×405,
  bent): late and grainy. `videoGlass` ("bent" default, "clear"; Settings → Background & screensaver, shown only
  with a video background; `VideoGlass.clear` is set by the home screen only while `bgMode` is video / aerial):
  `LiquidSurface` then draws nothing of the video (no refraction, no blur, no shade: his ask, fully see-through),
  only the veil, the shuffle's black and the glass colour; the island's writing gets the soft shadow it has with
  no glass (`bare`), so it reads over a bright video; the copies are no longer
  made for the glass (only for the clock's digits). Seen on the Streamer: dock and island show the video itself,
  the island reads over a bright sky. Frames moving along the dock (p50 / p90, two runs each): bent 25/47, 21/44;
  see-through 29/47, 20/43 (the same); Glassio's CPU at rest ~57 % against ~59 %. His setting left on Bent.
- **The phone page and the rest, since 4.0.0 (2026-11-23, his "did you think of the phone page and everywhere"):**
  the phone page has Jellyfin's sign-in (server, user, password; `/api/jellyfin/login`, `/logout`; Accounts "of
  5"), the Pexels key and words (`/api/pexels`, under Videos) and *Your videos* mentions YouTube videos and
  playlists (github build, `youtube` in the state). Tried against the Streamer with curl: the page's script compiles
  (`node --check`), the Jellyfin demo signed in and out through it, the state carries the new parts. Not seen in a
  phone's browser. Also found and fixed: `pexelsKey` is a secret in backups (`Backup.SECRETS`), `saverYtOff` stays
  on each TV (`PeerSync.NEVER`, like `aerialUrls`), problem reports carry `bgVideos`, `saverInHome`, `searchOn`.
  Already right: `pexelsKey` is the TV's (`Profiles.DEVICE`), Jellyfin's account in backups, Jellyfin in the
  reports' accounts, `cwJellyfin` / `libraryJellyfin` off in a new profile, the new rows in New, search and
  Changed, the nine languages, the play build (YouTube left out).
- **"My video" and "Screensaver" merged into Videos (2026-11-23, his yes to the plan):** the background is Title
  backdrops / My picture / Photos / **Videos**, and Videos has **Plays**: *All the videos* (bgMode `aerial`,
  `bgVideos` "all": the library as the screensaver has it), *My videos* (`aerial`, "mine": `AerialSource.mineItems`,
  the folder, links and YouTube, without the hidden ones), *One video* (bgMode `video`: the chosen one on a loop).
  Keys unchanged; `Settings.mergeVideos()` (once, from `GlassApp`) turns "My video" with *Shuffle my videos* into
  Videos · My videos; *Shuffle my videos* is gone from Settings and the menu. One library for both: the group is
  *Videos · the screensaver's and the background's*, the screensaver's type reads *Videos*. The menu's Background
  page: the four choices, *Plays* (OK goes to the next choice) and *Next video*. The phone page: *Videos* /
  *One video* in the list and a *Plays* row (`/api/bg/videos`); its rows may now be null (skipped). Seen on the
  Streamer from the menu: My videos → 9 (his folder's 8 and his link), One video → his Las Vegas video looping,
  All → 119; his choice (Videos · All) put back. Not seen: the migration itself on a TV with "My video" and
  shuffle (his had moved to Screensaver meanwhile), the phone page.
- **The search's pop-up list (2026-11-23, his photos):** its glass read the background video's copy, so the video
  showed through it as if the posters weren't there; and the screensaver on the home screen started under an open
  pop-up, which stayed over the video. Now a pick list that isn't over Settings takes one copy of the screen behind
  it as it opens (`ScreenGlass.capture` in `ui/Glass.kt`: PixelCopy of the activity's window over the video's latest
  copy, blurred), frosted for every style and darker (0.42); and `DialogHost` / `PopMenuHost` draw nothing while
  the home screensaver shows (the pop-up comes back after it). Seen on the Streamer: the list over Inception's
  posters, frosted and readable; the screensaver started with it open: no list on the video, back: the list again.
- **Island joined to the top, and a held OK in the search (2026-11-23, his photos):** joined to the top
  (`islandAttached`) the island's glass still had its top corners and rim (`GlassPanel` takes one radius): its
  panel now reaches above the screen by its corner's size (a `layout` in `MusicIsland`), so it meets the edge flat
  like a notch (seen on the Streamer). **Not for the changelog, the tour or GitHub (his ask).** In the search a held
  OK on a title always opens the list of places, *Save to the Library* and the rest (`open(item, list = true)`),
  whatever *OK on a title* says, also for a Jellyfin card (seen: his OK goes to Nuvio Legacy, the held OK showed
  the list).
- **Jellyfin froze when opened (2026-11-23, his "it hangs and doesn't go into the app"):** the TV's ANR records
  (`dumpsys dropbox --print data_app_anr`) had the Jellyfin app "no focused window" at 23:34, 23:49, 23:50: its
  main thread stuck in `org.jellyfin.androidtv.integration.provider.ImageProvider` writing a WebP into a pipe.
  Its rows' pictures are `content://org.jellyfin.androidtv.integration.provider.ImageProvider?src=<server image
  url>`: that provider downloads and compresses each one on the app's main thread and waits there until the
  reader takes it, so Glassio showing its rows (many cards, two decodes at a time) held the Jellyfin app up.
  Glassio's image loader now maps any `content://…?src=http(s)://…` to the `src` address (a Coil `Mapper` in
  `GlassApp.newImageLoader`): the pictures come from the server, the Jellyfin app is never asked. Seen: with the
  cache emptied and the Jellyfin app stopped, its rows' pictures all came up and its process was still not
  running; OK on Dracula → its page in ~1.5 s; no new ANR. (The Glassio ANR at 23:51 was my first `net_fail_test`
  emptying the cache on the main thread: fixed in the hook.)
- **Grey cards and an empty carousel (2026-11-23, his photo of Prime Video's rows):** a picture that failed once
  stayed failed for as long as the page was up: a row's card (`failed`, the grey under it), the carousel's backdrop
  (three tries, then nothing) and a logo (three tries, then the name). The Library looked fine (its posters load
  each time it opens). Likely the TV waking before its network was up. Now `data/NetState.kt`: a failure is noted
  and the pictures are asked again when the network is back (validated after a loss: `registerDefaultNetworkCallback`)
  or when the home screen resumes (`MainActivity.onResume`); the cards, the logo and the backdrop effect read
  `NetState.rev`. QA (dev key): `--ei net_fail_test <s>` (picture downloads fail for that long; the picture caches
  are emptied 2 s later, once the downloads under way have failed: emptied under them, a page waited for ever)
  and `--ez dump_threads true` (tag GlassThreads). Seen on the Streamer: Prime Video's cards grey with only their
  names while it failed, then, back from the TV's settings, "trying again" and every picture there. Not seen: the
  network really dropping and coming back (adb runs over the same network), the backdrop's retry by itself (a test
  that emptied the cache under running downloads left the carousel stuck; with the hook fixed it wasn't tried
  again). A restart had brought his pictures back first.
- **The What's new tour's window on Glassio's lights (2026-11-22 night, his ask: "only the pop-up"):** the home
  screen stays dimmed behind it as before; the window's glass reads the setup's soft lights (`GlassEnv` from
  `orbsPicture`, `ignoreVideo`: over his aerial background the window showed the video), a little lighter (darken
  0.45, was 0.72 for bright posters). A first try put the whole screen on the lights: not what he wanted. Seen on
  the Streamer over the aerial video. His `tourSeen` put back to 66.
  Then (his ask): **no dimming behind it** (the home screen as it is), the window light ("still dark": its own
  brighter lights, `orbsPicture(bright = true)`: a lighter ground, stronger lights; darken 0.08), **its own look
  whatever the theme** (frosted `liquid`, white writing; seen the same with Black on white, Black AMOLED and Still
  Glass, his `menuStyle` water put back), and
  **the tour covers everything since 4.0.0** (`WhatsNew.ID` 68): welcome, Profiles, Your streaming apps, New
  Settings, Search, Nine languages, Jellyfin, Screensaver (One player, hold OK, Pexels / YouTube, the menu's page),
  Remote buttons, And more, GitHub, Support: 12 pages, all seen on the Streamer (the GitHub page's window reaches
  the dock's top edge). The next release keeps these pages and adds its own.
- **Jellyfin (#27, 2026-11-22 night, not released):** see **Jellyfin**. On the Streamer with the demo server:
  sign-in, its two libraries as ticked catalogs (12 titles in the carousel), four titles in the Library's Continue
  Watching with the Jellyfin mark, OK on one → its page in the Jellyfin app ("Resume from 0:39"), Back → Glassio;
  the search for "dracula" with the server's Dracula first, OK → Jellyfin; places Jellyfin, Nuvio Legacy, Stremio ›;
  the Settings search finds every row. The Jellyfin app (0.20.0, from its GitHub release) is installed on the
  Streamer, signed in to the demo, and in his dock (Glassio adds new apps). Glassio was signed out of the demo
  after the test. Not seen: Quick Connect from the Settings row (the QC flow was only exercised by the Jellyfin
  app itself), the password row, the Library's favourites row (the demo user's favourites change), a series in
  progress (the demo had none), *Catalogs for apps* with the Jellyfin icon (his `appCatalogsOn`), the phone page
  (no Jellyfin rows there yet), a big library (the ids list is one request).
- **#25: a button's press, double press and hold; Close the app in front (2026-11-22 night, not released).**
  `RemoteMap` lines "code~double|…" / "code~hold|…" beside the plain "code|…" (old lines unchanged; an older
  Glassio drops the new ones). `KeyHub.presses`: with a double press or a hold set, a hold acts after 550 ms
  held, a second press within 350 ms is the double press, else the press acts 350 ms after release (a button
  with only a press set acts at once, as before; a button with a hold or double set and no press does nothing on
  a press). Action `closeapp`: the app in front (`topResumedActivity`) force-stopped through `LocalShell`, then
  Glassio; never Glassio, the TV's launchers or its settings; without TV access a toast says so. Settings →
  Remote buttons: one row a button with its three jobs. Seen with a virtual keyboard's F13 (scan code 183, no
  key code): press → Recent apps, double → the search (from the Play Store and from Glassio), hold with the
  Play Store in front → it stopped and Glassio came back. His mapping put back. #26 (a report from a Skyworth
  LEAP-S1 on 4.1.0: "can't change top left side for options to other one"): not understood, not reproduced
  (the Settings section list moves); a question is drafted, nothing posted.
- **YouTube playlists in My videos (#22, github build, 2026-11-22 night, not released):** a playlist link
  (`AerialSource.isPlaylist`: `list=`, not a YouTube mix `RD…`) becomes its videos (`MediaSearch.playlist`,
  NewPipe's `PlaylistInfo`, 200 at most, kept 6 hours), each with its title. A YouTube video now stays as its page
  address in the list; its picture stream is looked up when it is about to play (`VideoCache.factory`'s
  `ResolvingDataSource`, on the loading thread), so the stream never runs out and the copy kept on the TV is
  found again by the page address. Seen: a public 27-video playlist with one other link and the folder's videos:
  "YouTube playlist: 27 videos", its videos playing with their titles (1012p-1080p). The play build leaves these
  links out (scan: no NewPipe, PlaylistInfo or googlevideo). **His `saverSources` was emptied for the test and
  put back from his own backup on the TV** (`Download/Glassio/Glassio-backup-2026-11-15-1046.json`: robin,
  amazon); note a setting's value before changing it.
- **After 4.1.0-beta1, from the issues (2026-11-22; looked into and answered, nothing built yet):**
  - **#24 (his report from the TV, the same person as #22): the crash is an `OutOfMemoryError` in
    `Bitmap.createBitmap` making a page picture (`bakeBackdrop`, from the carousel's slide in `HomeScreen`)**, on an
    onn 4K Pro after switching between title backdrops and aerial videos and using multi-view. Read with the
    mapping of the exact released build (rebuilt from `ebd181c` in a worktree: the same `classes.dex` and
    `r8-map-id-fcff117e2aef`). Fixed: `roomFor()` in `PagePictures.kt` (page pictures and their glass copies): on
    OutOfMemoryError the prepared pages and half the decoded pictures are let go and it tries again, then in 16-bit
    colour, then at half size. Tested with `--ei oom_test 3` (dev key): three failures logged, the slides showed,
    the same process. **Also in his log: "TV adb shell: TimeoutException" every minute** (his TV's adb never
    answers: Continue Watching's Nuvio Legacy poll every 20 s, each try up to 15 s per address): `LocalShell.run`
    now leaves it alone 5, 10, up to 30 minutes after a failed connection; the TV access page clears that
    (`retryNow`). Not tested on a TV whose adb doesn't answer (the Streamer's does). His "RAM creeps up" with
    aerials and multi-view: not reproduced on the Streamer (PSS ~600 MB before, 599 after six aerial / carousel
    switches, 589 after four multi-view openings; threads 58 → 81 → 67). His "can't find the phone QR": it was at
    the bottom of System in beta1; first in System now. Answered on #22 (fix in the next beta); #24 closed as its duplicate.
  - #22 (Onn 4K Pro): Glassio freezes, Home too, "sometimes" when switching between a picture and a video
    background. Not reproduced on the Streamer (28 switches of `bgMode` image / video with the screen on, slow
    and 0.5 s apart: every one answered, no crash, no ANR). Asked: how he switches, which video, what else is on,
    whether 4.0.0 did it, a report from the TV. First guess: memory on a smaller box.
  - #20: Nuvio One works (confirmed by its author, closed). He also says his carousel stopped working: asked
    what shows, when, which dock app, a report.
  - #23: *Nuvio+IPTV* (`homelessbrian/NuvioPlusIPTV`, a fork of NuvioTV): package `com.nuvio.livetv`, the same
    `nuvio://` scheme (read in its `build.gradle.kts` and manifest). **Added** (`NuvioLaunch.IPTV_PKG`, key "iptv",
    the manifest's `<queries>`), not on the Streamer: never opened. Its test builds (`com.nuvio.livetv.test`) are
    not covered: he has the normal release (1.2.0-beta.2.1; its APK read with aapt2: `com.nuvio.livetv`, schemes `nuvio` and `stremio`). Told it comes with the next beta.
  - #21: **built** (see **Search**: *First row*, *More results*). On the Streamer: YouTube first, then films;
    along the videos' row 20 → 40 → 58 cards, along the titles' row 19 → 37, the selection keeping its place.
    Not seen: Music first, *Popular this week* growing, the play build's row order, a row's very end.
    Still wanted there: a lower
    height for "the catalogs on each app" (which screen: asked for a photo). Told: any remote button can open
    the search (Back can't be given a job); *Back on the home screen starts it* can be turned off.
  - **The Streamer sleeps with the TV off**: `KEYCODE_WAKEUP` wakes it for a couple of minutes only, and a test
    run then is worthless (black screenshots, 0 frames). Check `mWakefulness` before and after a run.
- **Settings order (2026-11-22, his ask):** *Search* is the first row of Continue Watching & Library (its own
  heading); System opens with *Remote & phone* (*Remote buttons*, *Manage from phone*), then *Home button &
  start*; *Hold Home* and the Recent apps rows have their own heading, *Home button & recent apps*. Seen on the
  Streamer. **The premultiplied-fade problem of the GNOME liquid-glass project is not in Glassio:** recorded the
  menu's glass opening and closing and the search bar's glass fading in and out, the region's brightness frame by
  frame never went above both its start and its end (Compose's layer alpha keeps colours premultiplied).
- Next beta or the stable 4.1.0: the public commit goes on `origin/beta` (a beta) or `origin/main` (stable) with
  the last public commit of `origin/main` as parent, made with `commit-tree`; code 71+. The local `beta` keeps its
  own history.
- **4.2.0 released as stable (2026-11-23, "سوي نشر عام ستيبل"):** everything since 4.1.1-beta1 (Jellyfin, One
  player, three-press buttons, TV inputs button, Hue restore, see-through glass, Recommended for this TV, the clock
  over rows, the menu's Large clock). Issues looked at first: only #21's "the big clock still doesn't show" was new
  (fixed, in it). The repo's description now names Jellyfin and search; topic `jellyfin` added. The tour's first page
  says 4.2 (`WhatsNew.ID` 68, his `tourSeen` 68). #21, #22, #25, #27, #28 were told it is out (his wording
  approved, no link) and closed as completed (2026-11-23); #23 was already closed by him. Not seen: the updater offering 4.2.0 to a TV on 4.1.0 or on the beta.
- **#22 and #25 after 4.2.0 (2026-11-23, his "test on the Streamer and the TCL until it is exact, then fix"; not
  released, nothing posted):**
  - *#22, My videos → Play only one of them:* three causes, each reproduced on the Streamer first. (1) No pictures
    for YouTube videos: `VideoThumbs` read a frame from the link, a YouTube page; now YouTube's own still
    (`VideoThumbs.youtube`, `i.ytimg.com/vi/<id>/mqdefault.jpg`), and the background's still frame the same way
    (`CustomBackground.frameOf`: it retried a page every ~9 s). (2) The list jumped back to its first row ~25 s after
    opening (the pictures' fill replaces the `ThumbDialog`, and `DialogHost`'s `key(state)` made it anew): keyed by
    title now; OK had picked the first video. (3) The background on *My videos* (and the screensaver on it, One player)
    ignored the pick: `AerialSource.mineItems` honours `saverPick` (seen: 36 videos → 1, it loops). Seen on both TVs:
    pictures, the selection held 45 s and through a rebuild, the background playing the chosen video.
  - *#25, "Allow USB debugging?" never shows / flashes at a restart:* seen on the Streamer from the boot log: Glassio's
    own connection at start brings the TV's window up, then *Open Glassio when the TV starts* opened Glassio over it
    9 s later; covered, `UsbDebuggingActivity` stays behind unanswered and **adbd shows no window again for any key
    (Glassio's or another app's, the Mac's adb too: "prompt currently pending, skipping") until the TV restarts**.
    Also, every connection left waiting queued one more window (shell, quick, compile, two addresses), each shown
    after Cancel. Fixed in `LocalShell` / `HomeKeyService` / `BootReceiver` / `HomeSaver`: one connection at a time
    (`openLock`), a timed-out address stops the others (except Fire TV), an unanswered window blocks background tries
    until answered (`askedAt`), Cancel stops asking until the TV access page (`declined`; the TV's adb keeps a cancelled
    connection waiting: Cancel is read from the window closing, `cancelled`), the window seen as SystemUI's
    `android.app.Dialog` while Glassio waits (`promptUp`, 2 min at most), the wait is 2 min while it is up / at a TV
    start / when the person asked (`LocalShell.ask`: TV access page, setup), Start with the TV waits for the answer,
    Home and Glassio's own screensaver don't come over it. **The service's XML had `packageNames` (two Google
    launchers): on Android 11 (the TCL) clearing it from the code did nothing, so the service got no other app's
    window at all** (Recent apps without ADB there too): removed. QA (dev key): `--ez adb_testkey true|false` (a key
    the TV never allowed / Glassio's own), `--ez adb_probe true`; window events in logcat tag `GlassA11y`.
    Seen with the test key (Allow never pressed: it would authorise a key): Streamer, 8 restarts: the window stays
    (7 min), one window only, Cancel → Glassio opens, nothing hidden after; TV access page: waits 70 s, Cancel → no
    more windows. TCL (its buttons are CANCEL left, ALLOW right): restart, install, TV access page, past its 1-minute
    screensaver. Not tried: **Allow** (his to press, or the customer's), Fire TV. Already stuck TVs need one restart
    after the update. Both TVs back on Glassio's own key (probe ok), the Streamer's idle time back to 2 min.
  - Settings: a page opened from a row looked for a selectable row among its first four only; TV access (four notes)
    put the selection on the sections: now nine.
- **#29, Fire TV: the menu's Android settings "restarts" Glassio (2026-11-23, not released, nothing posted):** on Fire
  OS `ACTION_SETTINGS` goes to `com.amazon.tv.settings.v2/.compliance.CTSDummySettingsHandler`, which starts Amazon's
  `HomeActivity_vNext` as a HOME start (Settings is a panel in it): with Glassio as the home app that start lands on
  Glassio (the same bug and fix in other launchers: couch-tv PR #5, Homestead PR #10, Fire-Stick-Debloat #22).
  `SystemSetup.openTvSettings` (every place that opens a TV settings screen: the menu, Settings, the setup): on Fire TV
  an action that ends at such a stand-in (or `ACTION_SETTINGS`) opens `com.amazon.tv.launcher/.ui.MainSettingsActivity`
  (Fire OS 8), then `.ui.SettingsActivity`, then the action as before; `<queries>` for the two Amazon packages.
  **No Fire TV here: not seen on one.** Seen on the Streamer and the TCL: the normal way unchanged, and with
  `--ez fire_settings_test true` (dev key) the Fire TV way falls back to the TV's own Settings, no crash.
- **4.2.1 released as stable (2026-11-23, "سوي ريليس", his choice: stable):** #22, #25 and #29's fixes only. Public
  `85cfac8` (tree of local `beta` at `51e4c54`) on `origin/main`, tag `v4.2.1`, *latest*, both APKs (sha256
  `9da263f6…8c3034e`, the Streamer's file). Tests, lint (both flavors), play scan clean, `nav` clean on the Streamer.
  A fixes release with no `NEW_IN` entry now keeps its minor version's New rows (`newVer` falls back to X.Y.0), so
  4.2.0's marks and what was seen of them stay. Tour unchanged (`WhatsNew.ID` 68). #22, #25, #29 told (his "وجاوبهم"; one
  comment each, no link); #22 and #25 stay closed, #29 left open for its author to confirm on a Fire TV. Not seen: the updater offering 4.2.1 to a TV on 4.2.0.
- **The play build brought up to 4.2.1 (2026-11-23, his "سوي النقاط وافحص ع الستريمر"; local commits only):** the
  accessibility agreement (`A11Y_TEXT`), the service's description (`src/play/res`), the Console's wording
  (`docs/play/README.md`) now say the service also knows which app is in front (its name only: Recent apps, back
  from an app Glassio opened, waiting on the TV's USB debugging window, cameras over apps); `docs/PRIVACY.md` has
  Jellyfin, Pexels, the search (JustWatch) and YouTube / SoundCloud search (github build); the listing names Search,
  Jellyfin, One player and the remote's three presses. **Found: New's rows under another row ("  · Where", Pexels'
  key and words) never matched their marks** (the seen list is kept trimmed, rows are compared by `norm`): New counted
  3 with an empty page, also on 4.2.0; both sides now go through `norm` (in the next github release too). The play
  AAB and APK (4.2.1, code 74, upload key `d1d9d86b…8d5434`) are in the Desktop folder with the policy and listing
  copied from the repo (4.0.0's files moved to `old 4.0.0`). Seen on the Streamer (installed fresh, then removed
  again): setup → Later, no New chip, the agreement window whole with Not now selected (pressed), About without
  updates or the gift card, Settings' search finding Jellyfin and no YouTube rows, the home search with titles and
  no YouTube row. Then, his "سويهن كلهن": **the AAB itself** through bundletool (1.18.3 from the Gradle cache:
  its jar has no main class; classpath with a `-jre` Guava, kotlin-stdlib, protobuf, aapt2-proto, dagger, gson,
  jose4j, apksig, and `--aapt2` from the SDK's build tools): `build-apks --connected-device` gave base-master,
  armeabi_v7a (TensorFlow Lite and androidx graphics libraries) and en, installed with `install-apks`, setup, home
  and Settings ran from them; **the service on in the play build**: Agree on the agreement page opened the TV's
  Accessibility page, the service was switched to the play build's with `settings put secure` (his yes), and a real
  Home (a virtual remote made with `hid`, consumer usage AC Home 0x0223: `input keyevent` never reaches the service)
  from the TV's settings brought the play build up while the TV's own home app is Google TV's. Put back after:
  `enabled_accessibility_services` the GitHub build's (as before), the play build removed, Home brings the GitHub
  build. The new privacy policy is public: `ea23eb4` on `origin/main` (docs only, parent 85cfac8 = 4.2.1; his "سوي push").
  The developer account's verification finished the same day (2026-11-23): *Create app* is open in the Console.
- **Settings button → Nuvio's picture by itself (2026-11-23, his ask; local, not released):** see **Profiles**.
  Seen on the Streamer in his profile: `settingsIcon` removed → after a restart the button showed Moss (his Nuvio
  picture) and the log line; `sliders` chosen → stayed sliders after a restart; the switch off with nothing chosen →
  stayed sliders; the row found by Settings' search. His `settingsIcon` put back to `picture`, `settingsIconNuvio`
  removed again. Its row is not in `NEW_IN` yet (add it to the next release's entry). Seen once while testing:
  after several `am start` hooks the TV had **no focused window** (`dumpsys input` → FocusedWindows empty): keys did
  nothing, no frame drawn, no ANR; opening the TV's settings and coming back fixed it. Not looked into.
- **The play build is for TVs only** (2026-11-23, his yes): `src/play/AndroidManifest.xml` makes
  `android.software.leanback` required (`tools:replace`), so Play never offers it to phones, tablets or XR headsets;
  the GitHub build keeps it optional. Lint reads that overlay alone and asked for a TV launcher and an optional
  touchscreen (both are in the main manifest): ignored on that file. The rebuilt AAB (4.2.1, code 74) ran on the
  Streamer from bundletool's split APKs and is in the Desktop folder. Play Console progress is in CLAUDE.local.md.
- **#33 and the phone page (2026-11-23 evening, his asks; not released, nothing posted):**
  - Settings' empty search shows the phone page's QR code (`searchPhoneQr`, on; `PhoneQr` in `SettingsScreen.kt`),
    started as System → Manage from phone starts it and left on for as long as that page says. Seen on the Streamer.
  - *Manage from phone* in the side menu (`menuPhone`, on; key `phone` in `MENU_KEYS` / `MENU_DEFAULT`, a phone
    glyph): its own page in the menu (his ask: not Settings) with the QR code and *Keep the page on* (OK → the next
    of `PhoneHost.KEEP`, Always last; `PhoneHost.setKeep` is shared with Settings). A saved menu order gets it at the
    end. Seen: the QR in the menu; 3 h → Always (the code changed to the lasting one; after a force-stop and a start
    the same address answered 200 with nothing opened); cycled back to 3 h (`phoneAlways` false, 180).
  - *Keep the page on* → **Always** (`phoneAlways`, **on by default** since his ask, the TV's, never synced): the page runs whenever Glassio runs
    (started in `GlassApp`, retried while the network comes up) with one code kept in `phone_access` (not a `glass…`
    file: never in a backup, sync or report); switching Always off forgets that code. Not yet seen across a TV restart.
  - The page as a web app: `/manifest.webmanifest` (start URL with the code, standalone), `/icon.png` (Glassio's
    icon, 192 px), Apple's web-app tags; the page is served with its code filled in (`__T__`). No `theme-color` any
    more and `html` has the lights picture, so the browser's top (his photo: a black band) shows Glassio's lights.
    Checked in Chrome at a phone's size; **not seen on his iPhone** (Safari's top and Add to Home Screen).
  - **One code per TV, and a name** (his ask: the saved web app must keep working): the page's code is always the
    lasting one in `phone_access` (Settings → Manage from phone → *New code* makes another and stops the old pages);
    changing *Keep the page on* no longer changes it. While the page runs the TV answers multicast DNS for
    `glassio-<device name>.local` (`net/LocalName.kt`: A questions for that one name, the TV's own IPv4; a
    `MulticastLock`, permission `CHANGE_WIFI_MULTICAST_STATE`), so the address stays when the router gives another IP.
    The QR stays the IP address (every phone opens it); the page's System → *Address by name* checks the name
    (`/api/ping`, CORS) and moves there, to be added to the home screen. Seen from the Mac: `dns-sd` and `curl` reach
    `glassio-google-tv.local:9090` (HTTP 200). The test Chrome could not (macOS Local Network permission for that
    browser). **Not seen on his iPhone.** The browser's top: `theme-color` and `html`'s colour are the lights
    picture's top colour (`#231830` here, `__C__`), not black. Not seen in Safari.
    **The web app's black screen then Safari's "Not Secure" warning** (his photos): the name had only an A record, so
    every lookup waited ~5 s for an IPv6 answer, and Safari's HTTPS try held the connection until the server's 15 s
    timeout. Now any other question for the name gets an NSEC "only A" answer (`LocalName.answer`), and a connection
    whose first byte is a TLS handshake (0x16) is closed at once (`PhoneServer.handle`). From the Mac: lookup
    5.0 s → 0.003 s, HTTPS try 20 s → 0.006 s. Safari may still show its warning once for a plain-HTTP name
    (its HTTPS-first rule); *Continue* is remembered for that site.
    **After the fix, on his iPhone:** the page opens normally in Safari, but the home-screen web app still starts
    slowly. He said to leave it (2026-11-23); cause not looked into.
  - Settings from the phone: `/api/pref` with an allowlist (`Pref` table in `PhoneServer`: theme, glass colour,
    language, clock at the top, menu row, autoplay, screensaver type / start / One player / shuffle, large clock,
    the search QR, updates channel on the github build); any other key is refused. Rows under *Settings* in each
    section (`prefRows`). Seen: Clock at the top on and back off from the page, an unknown key refused.
  - **Nuvio's and Stremio's apps (and forks) show their rows again** (his ask: they were left out on purpose once;
    #33's Nuvio One rows stayed blank because of it): `Channels.read` no longer drops them; `carouselApp` only while
    such an app shows no rows. On his Streamer only Stremio publishes rows (Continue Watching, Library); it is listed
    under *Apps with channels* now; not opened from the dock (it is in his Apps folder). Nuvio Legacy publishes none.
  - Its rows are not in `NEW_IN` yet (the next release's entry). `nav` clean, tests and lint for both builds.
- **4.3.0 released as stable (2026-11-23, his choice):** everything since 4.2.1 (the phone page in the menu and
  Settings' search, Always by default, one code, the TV's `.local` name, settings from the phone, Nuvio / Stremio
  rows again, Nuvio's picture for the settings button). Public `9af8b7c` (tree of local `beta` at `e033505`).
  Code 76 (the Play bundle already used 75). `NEW_IN` 4.3.0: *Nuvio's picture by itself*, *QR code in Settings'
  search*, *New code*. The tour keeps its 12 pages and adds *Manage from phone* plus one line in *And more*
  (`WhatsNew.ID` 69, his ask: "the tour as it is, only the new things"); his `tourSeen` put back to 68. Tests, lint
  (both flavors), play scan clean, `nav` clean. No issue told (#33 not answered: his rule). Not seen: the updater
  offering 4.3.0 to a TV on 4.2.1. Supporter list in About: his idea, waiting for the supporter's yes and name.
- **Supporters (2026-11-24, not released):** EbM-Tech said yes on #25 ("You can use my nickname, EB"). Settings → About →
  *Supporters* under the GitHub code (his ask): the word in Glassio's lights' colours (purple, blue, rose), then
  "EB (@EbM-Tech)" (github build only; `SUPPORTERS` in `SettingsScreen.kt`: name and GitHub account, never
  translated) and a *Supporters* section in both READMEs. "Made by Mohammad (mrh12)" moved there too, above it (the
  credits line stays at the end of About's rows). A name is added only with that person's yes; never the gift code or the form.
  Seen on the Streamer (English). Not seen: Arabic. Nothing posted on #25.
- **Multi-view never past the screen, and a cameras page (#31's comment, #30; 2026-11-24, not released):** the row
  was sized for four cameras times *Size* (140 % by default), so four ran off both sides. Now `multiLayout`
  ("auto" default, "row", "grid"; Settings → Smart home → Multi-view → *Layout*): auto is one row when it fits at
  that size, else two by two; a card is never wider or taller than its share of the screen. **The cameras page**
  (`MultiCamOverlay(full = true)`): the multi-view cameras fill the screen on black (1 whole, 2 side by side, 3-4 two by
  two) until Back; the Home button service hands it the arrows and OK (`key()`): the arrows choose a camera (white
  rim), OK turns its sound on (one at a time, all muted at first; a camera without an audio track says *No sound*).
  Ways in (his choice: not Down from the dock): menu → Camera → *Whole screen* (Down from the camera card), a remote
  button (action `cameras`), Settings → *On the whole screen*. QA hooks (no key): `--ez cams_full true`,
  `--ez multi_mid true` (each toggles). Seen on the Streamer: his three cameras plus a dead test link as four (auto →
  two by two, nothing cut), his three in one row, the page with four and three, Right + OK through a `hid` keyboard
  (rim, *No sound*: his cameras send video only, checked with ffprobe), the menu's row. Not heard: a camera with sound.
  PTZ not done (no camera to try; #31's author offered to test).
  **`dump_prefs` cuts long values**: never rebuild a setting from it (his cameras were rebuilt from his backup on the
  TV, 2026-11-15, after a test cut DOOR's link; he was told).
- **His changes to the cameras, PTZ and #32 (2026-11-24, not released):**
  - Four cameras two by two are as big as the screen allows (`rowsN > 1`: the fit size times Size / 140 %).
  - The cameras page stands on Glassio's lights (`orbsPicture`, shade 0.25), not black.
  - **PTZ** (`net/Ptz.kt`): hold OK (0.6 s) on a camera of the cameras page: the arrows move it while held, OK switches
    to zoom (▲ ▼) and presets (◀ ▶), Back leaves the controls (a second Back closes the page). Home Assistant cameras:
    `onvif.ptz`; others: ONVIF to the camera (port 80, or `onvif=<port>` in the link's query) with the RTSP link's user
    and password, WS-Security digest with the camera's clock, the profile of the link's channel (Dahua `channel=N`,
    Hikvision `/Channels/N01`: his cameras are channels 1, 2, 4 of one Dahua-style recorder, which reports PTZ on
    every channel). **The service gets no key repeats**: a held arrow resends the move every 0.7 s (each a second
    long, `Timeout PT1S`, 20 s at most), so a lost release stops the camera by itself. Tested against a fake ONVIF
    camera on the Mac (move, stop on release, resends, zoom, presets, Back) through a `hid` keyboard and a `hid`
    consumer remote (AC Back 0x0224) run on the TV with `nohup` (the relay dropped the foreground `hid`). His real
    cameras answered GetCapabilities / GetProfiles / the channel's profile; **no move was sent to them** (fixed
    cameras behind a recorder that claims PTZ: they would not move). Not tried: a real PTZ camera, HA's onvif.ptz.
  - **Unneeded apps** (#32, `data/Debloat.kt`, Settings → System, github build only; his choice: no DNS blocking):
    a fixed list of the TV's report / ad / installer apps (UAD-ng "Recommended" or several guides), `pm disable-user`
    and `pm enable` only, a `NEVER` set checked again, a screensaver in use kept, *Turn back on what Glassio turned off*
    only touches its own (`tv_apps_off`, not a `glass…` file). His Streamer already had 22 apps off (Google feedback,
    ad services, tvrecommendations, Ambient …); the page showed them off and the *Maker's app installer* on: turned
    off from the page (adb agreed) and back on (22 off again, as before). The page's rows stay put (a row that came
    and went moved the selection onto another app once: seen, nothing pressed). Its words are English only so far.
  - **After his look (same night):** multi-view takes the arrows and OK too (they moved the app behind it): OK opens
    the cameras page, ▼ closes the one from the bottom (screensaver), the rest do nothing; seen: three Rights left the
    dock's selection as it was, OK opened the page. The cameras page is dark grey (`#1C1C1E`, his ask), not the lights.
    The menu's *Whole screen* row lets Up go back to the card (it was `first`). New, off by default: `cameraMenuFull`
    (the menu's Camera opens the cameras page; seen) and `cameraSoundFollows` (the selected camera is heard; his cameras
    send no sound: not heard). Both were set for the test and removed after.
- **Three of his reports (2026-11-24, not released), each reproduced on the Streamer first:**
  - *Removing a camera left the section or removed two:* the row went with the selection on it (it fell out of the
    page; the OK still held then hit the next row). Now Remove asks first and `keepRowAt(i)` (SettingsScreen: `hold()`
    then `jump` to that row of the page as it is after) puts the selection on the next camera, or the one before at
    the end. The same for *Hidden videos* (Show again) and *My videos* (Remove, also asked first). Seen: two test
    cameras removed one at a time, the selection on TEST B then on OUTDOOR 2, his three untouched; a hidden video
    shown again, the selection on the row before.
  - *▼ on the screensaver: the cameras came and went at once:* the multi-view (which now takes the keys) took the
    release of the ▼ that opened it and closed itself. It only acts on a key it saw go down (`pressed`). Seen: ▼ up and
    staying, ▼ again closes.
  - *Don't show it again, then ◀ brought the video back, and the home screen came back with nothing selected:* the
    screensaver's list is the one it started with; ◀ ▶ now pass over hidden videos (seen: ◀ skipped Mafate). The
    held-OK menu takes the selection, and the home screen's watch gave it back to Play, or not at all; the home screen
    now keeps the dock app it was on (`dockWas`, not updated while the screensaver is up) and gives it back when the
    screensaver ends (seen: Music, then Nuvio Legacy, selected again after the menu and Back). His hidden list and
    cameras were put back as they were (Mafate and Vettisfossen hidden for the tests, shown again).
- **His second round on the cameras and the removals (2026-11-24, not released):**
  - *▼ after closing the cameras took two presses:* the cameras swallowed the release of the ▼ that opened them, so
    the screensaver saw ▼ held for ever (Android repeated it, 136 repeats in the log) and the next ▼ was a repeat.
    A release whose press went elsewhere now goes on to it (`MultiCamOverlay.key`, `pressed`). Seen: one ▼ opens,
    one closes, one opens again.
  - *Lag while the cameras show* (the screensaver behind, measured with `gfxinfo framestats` per window, p50):
    screensaver window 47 → 10-20 ms, cameras window 57 → 20-23 ms, frames over 33 ms 153 → 8-30 in 6 s. Causes
    found with simpleperf: the RenderThread spent a fifth of its time in `PixelCopy` of the 4K video (the veil's
    copy every 70 ms, the island / clock copies, Hue Sync's copy twice a second) plus a GPU blur of the whole
    screen every frame and the TV's window blur. Now (his idea) the screensaver's and the background's video wait
    under the cameras; the veil is one small softened copy; no copies at all while the cameras are up; no window blur
    over Glassio's screensaver (`ownVeil`); the window is only as big as the cameras there; the cameras start one
    after another after the slide (260 ms + 150 ms each).
  - *One camera of four didn't play* (seen: Dahua 4, `DECODER_INIT_FAILED` on both streams, also after retries):
    the paused screensaver video still held a decoder. Once the veil has its copy it is let go (`stop()`), taken up
    again where it was when the cameras close, the veil staying until its first frame (`underCams`). Seen: all four
    live, the video moving again after. A camera that fails or isn't live in 10 s is started again (small stream,
    three times). Not done: the video background (*One player* on aerial) only pauses, it keeps its decoder.
  - *Removals everywhere:* a watch over all of Settings. The selection that left a removed row fell to the search
    field, which opened the search at once (seen with `--es dev K --ez settings_lose_focus true`, a new QA hook).
    The search field now opens only when reached with an arrow (within 0.6 s) or asked for; otherwise the page's
    row where it was takes the selection (`jump`, which now also looks upward). Seen: dropped on System's 5th row,
    back on it; Up from the sections still opens the search. Rows reviewed by hand: sign-outs, Remove add-on,
    Continue Watching's Clear, Forget the bridge, remote buttons, sync, Last crash (all covered by the watch).
  - His camera list is now *Dahua 1-4* (added by him with Find cameras after the earlier tests).
- **The same fixes everywhere (2026-11-24, his rule; not released):**
  - *One player and the home screen's video background* (`VideoBackground`): wait under the cameras and let their
    decoder go, the video's last sharp copy (`still` / `cover`) over it until it draws again; the home screen's veil
    counts as `ownVeil` (no window blur over it). Seen with *Videos* as the background for the test (put back to
    Title backdrops): "let go under the cameras", all four cameras live, back in 1.0-1.4 s.
  - *Trailers* (the carousel's and the home player's, not only the screensaver's): wait under the cameras and let
    their decoder go, back where they were. Not seen (no trailer was playing).
  - *The blur after closing the cameras* (his question: it was on purpose, the video loads again ~1 s): now the blur
    goes with the cameras and a sharp still copy of where the video stopped stands in for it until it draws
    (screensaver: `stillFrame`, 960×540; background: its `cover`). All three resume at the nearest key frame,
    seeked before preparing (it didn't make the return much faster: the network and the decoder are most of it).
    Seen: right after closing, a sharp picture; the video moving again after ~1 s.
  - *The Library*: a title removed while selected took the selection back to the first title at the top; now the
    one that took its place (or the one before) is selected (`keep(list, i)`, `lastAt`, forgotten when the Library
    goes). Seen: One Piece saved from a platform row, removed from Saved: the next one selected, the page in place.
  - Looked at and fine as they are: the corner camera's and KeyHub's buttons take a key's press and release both;
    the screensaver's hidden videos in the background's list (it is built again when one is hidden).
- **Memory and CPU, branch `lite` (2026-11-24, his ask; local only, `beta` untouched until he says):**
  - Deep check first (no change): on the Streamer PSS 550-750 MB, native 340-480 (pictures: heapprofd showed 373 MB
    of 387 MB held native memory in Bitmaps: Coil's decodes 158 MB, page pictures 75, app artwork 47, glass copies
    22), GL 121, Java 15-100. CPU at rest ~16-40 % of a core, mostly RenderThread (the island's bars redraw the screen
    ~12 times a second while a song plays); spikes to ~290 % when slides are prepared (decode, page picture, blur)
    and the GC after them. On the screensaver PixelCopy of the 4K video for the island's glass is a quarter of the
    RenderThread. `immutable()`'s copy is not a duplicate (the mutable one is recycled). Tools: `heapprofd` with
    `perfetto --txt -c -` (the app is profileable; restart it under the profile), `pip3 install perfetto` for the
    analysis, the R8 mapping to read the frames; `am dumpheap` needs a debuggable build; `simpleperf` and `perfetto`
    run on the TV with `nohup … &` (cd /data/local/tmp first) when the relay drops.
  - Step 1 (`8df87bc`): the icons' copy for light plates made when first drawn (TCL 161 -> 142 MB); pictures let
    go when Glassio is left, started from its own screens (`awayFor`): Android never sent TRIM_MEMORY_UI_HIDDEN (the
    Home button service keeps the process important), so the old release never ran on either TV; the camera
    thread named from inside (named from the main thread, ART renamed the main thread `glass-cam-play`).
  - A (`9f46766`): away for 3 min (1 on small TVs or Lite) every kept page picture goes (`trimAllPages`: the pins,
    the app rows' and music page's backgrounds, logos), pinned again on return (`GlassCache.released`). Streamer
    under another app native 461 -> 226 MB; the return as fast (145 ms both, the picture there at once).
  - B: mode **Lite** (`perfMode` "lite"; Mode cycles Auto, Lite, Performance, Balanced, Quality): the same look
    (picture quality, resolution, glass, animations untouched), prefetch 2, `memTier` 1, autoplay on the carousel
    only, screensaver 1080p, and `Settings.lean` (also tier 0): slower island bars, the music page's blur and a
    trailer worked out only when needed, Hue every 2 s when set to "as fast as possible", pictures let go after a
    minute away. Auto never picks it (weak TVs keep Performance, lighter). Streamer: PSS 531-561 -> 426-457 MB,
    frames at rest 13.1 -> 9.3 ms; CPU at rest the same (~40 % right after a restart either way); the spikes not
    measured. His own settings win (`perfOwn`: his prefetchDepth 8 stayed). His Streamer back on Quality with his
    values. **The TCL went off the network during its Lite test: it may be on Lite; `--es perf_mode auto` puts it
    back (its values were exactly Auto's).**
- **Branch `lite`, after B (2026-11-24):**
  - For everyone (`407f598`): camera players have decoder fallback on (a hardware decoder that can't start hands over
    to the software one); cameras beyond the TV's hardware decoders (`CamPlayer.hardwareRoom`, the smallest
    `maxSupportedInstances` of its H.264 / HEVC decoders: 4 on the Streamer) start on the processor with their small
    stream. QA (dev key): `--ei cam_room N` (0 = the TV's). Seen with 2: Dahua 3 and 4 on `c2.android.avc.decoder`,
    all four live. One OkHttp base client (`Net.ok`) for Coil, Home Assistant and PTZ (`newBuilder()`); the 28
    `HttpURLConnection` users already share Android's own pool.
  - Lite (and Performance, `lean`): app icons decoded at 400 / 192 px (was 640 / 256), video buffers capped (background
    20 s / 24 MB, screensaver 25 s / 32 MB). Memory on the Streamer the same as B within noise (451-469 MB): the gain
    is small and only with a video playing. Seen: the cameras over the screensaver on Lite. His settings back.
  - **Not done: hardware bitmaps for page pictures.** At least 12 places read pictures' pixels on the CPU
    (Hue colours, the clock's cut-out, glass copies, glow, island tint…) and `createScaledBitmap` of a hardware
    bitmap throws: each would need a software copy first. **His answer (2026-11-24): leave it.**
  - The Mac had restarted: the Homebrew adb hung (even `adb version`); the SDK's adb works
    (`$ANDROID_HOME/platform-tools` first on PATH). The relay script was rewritten in the scratchpad.
- **4.4.0 (code 77), prepared for release (2026-11-24, his "سوي النواقص وسوي ريليس"):** `lite` merged into `beta`
  (fast-forward). Unneeded apps' page and app list translated in the eight languages; the phone page sets
  `multiLayout`, `cameraMenuFull`, `cameraSoundFollows` and `perfMode` (with Lite, through `applyMode`). `NEW_IN`
  4.4.0: Layout, Menu's Camera opens the whole screen, Sound follows the selection, On the whole screen, Unneeded
  apps. The tour keeps its pages and puts 4.4's first (*Cameras*, *Lighter*; `WhatsNew.ID` 70, 15 pages; his
  `tourSeen` put back to 69). Tests, lint (both flavors), play scan clean, `nav` clean on the Streamer.
  **Released (2026-11-24):** public `4064a51` (tree of local `beta` at `d455cbd`, parent 9af8b7c) on `origin/main`,
  tag `v4.4.0`, *latest*, `Glassio.apk` and `Glassio-4.4.0.apk` (sha256 `625182b8…621623eea`, downloaded and compared,
  also through `releases/latest/download/Glassio.apk`; the same file is on the Streamer). Code 78+ next.
  Issues told (his "رد على الissue", no links): #33 (QR in search, Nuvio One rows: 4.3.0) and #32 (Unneeded apps)
  closed as completed; #30 (cameras page, PTZ: asked its author to try PTZ through HA) and #31 (cameras part done,
  the Library layout not) left open; #29 asked for the Fire Stick's model and Fire OS version (still broken there).
  #26 not answered (a report from a TV, not understood).
- **4.4.1 (code 79, 2026-11-24, #34 and the Play review):** #34 (a Xiaomi MiTV, 720p, `memTier` 0: "the screensaver's
  video cuts"): the Lite buffer caps of 4.4.0 also applied at tier 0 (`lean`): reverted for everyone (background
  15-50 s, screensaver 20-60 s). `mergeVideos()` ran before `settings` existed (since 4.3.0 it threw at every start
  and never merged): now after. Photos' failing source backs off 30 s → 30 min (a changed choice asks at once), the
  doorbell's and Nuvio's busy ports 30 s → 10 min. Reports carry `saverQuality` and `photoSource`. Wi-Fi is
  `required=false` (CHANGE_WIFI_MULTICAST_STATE implied it: Play's 4.4.0 review dropped 6 cable-only TVs; code 77
  and 78 bundles stay unreleased in the Console's library). Seen on the Streamer: no merge warning at start, a
  failing Reddit source retried after 30 s, `nav` clean; his bgMode / photoSource put back.
  **Released:** public `d81b8f9` on `origin/main`, tag `v4.4.1`, *latest*, both APKs (sha256 `8d3507e0…c3e52902`, downloaded
  and compared, the Streamer's file). Play bundle 79 (4.4.1) in the Desktop folder. #34 not told yet.
- **After 4.4.1 (2026-11-24 night, local, not released):** #36 the TV's screensaver (dream) crash on close; cameras
  page and multi-view up to nine (grid past four, `Layout` "Grid"); an empty Library takes the selection (#33);
  TV access: a dismissed USB debugging window isn't asked again without the Home button service, the Play build asks
  only when the person does until it worked once (`tv_access` file). **Backups between the two builds**
  (`data/BackupProvider.kt`, `data/Certs.kt`): each build answers at `<package>.backup` only to the other build,
  checked by package and SHA-256 signing certificate (GitHub debug key `0c5478f7…`, Play's app signing key `ae5fe0ca…`
  from the Console and the upload key `d1d9d86b…`); the Play build's *Restore backup* takes the GitHub build's
  settings first, the GitHub build asks the Play build last. Seen: 336 of 336 settings restored from the setup's
  *Restore a backup*; the shell refused by both; QA `--ez backup_other true` (dev key) logs the other build's backup
  without restoring. Also seen: the Play build can't read the GitHub build's files in Download ("No backup on the TV
  yet"); the phone page's restore takes the file (280 settings then). QA hooks need the TV awake: a running dream
  (TV screensaver) swallows `am start` ("Activity not started, its current task has been brought to the front").
  Nuvio One (EB, #30): its *Continue watching* row shows and stays in Glassio (fresh-process update, Glassio restart):
  not reproduced; it was installed for the test with his account (he signed in).
- **Same night, more (local):** weather city "" = automatic from the TV's time zone (`Settings.weatherPlace`,
  `zoneCity`; the default was "Baghdad" for everyone, #37; his saved `dhiqar` untouched). **`MultiHost`** (in
  `MultiCamOverlay.kt`): every way into multi-view / the cameras page goes through it; without the Home button service
  (Fire TV, Play before consent) they show in MainActivity's own window (`TYPE_APPLICATION_PANEL`) and MainActivity
  passes them its keys (#30). Seen with the service turned off and back on: page, Right, OK = sound, Back. Fire TV
  settings now log the device's Amazon settings screens and each try (#29; QA `--ez tv_settings`). #31 / #38 (UGOOS
  AM9 PRO, Amlogic): kernel panics, he says at the background's switch from H.264 (Apple) to HEVC (Amazon, the only
  HEVC-only collection: checked in AerialViews' JSON with ffprobe); asked for his logcat, workaround Collections →
  Amazon Fire TV off; #38 closed as its duplicate. Replies posted on #29, #30, #31, #37, #38.
- **#31's logcat (UGOOS AM9 PRO, kernel panic):** at the background's switch to an Amazon HEVC video the player opened
  `c2.amlogic.hevc.decoder` while `CustomBackground.cachedFrame` read a still from the same video with
  `MediaMetadataRetriever` (a second hardware decode in mediaserver); the log ends 0.4 s later. Now the background's
  still is a PixelCopy of the player's own picture 1.5 s in (`saveStill`, log "Background video still: from its own
  picture"), only YouTube stills are downloaded; `VideoThumbs` makes one picture at a time and waits while a player
  starts (`DecodeGate`, set by the background's and the screensaver's video changes). Seen on the Streamer: three
  video changes, stills saved, no retriever in Glassio's log. Not seen on an Amlogic box.
- **4.4.2 released (2026-11-24, his "نزل التحديث"):** public `c42674e` (tree of local `beta` at `fb8994d`), tag `v4.4.2`,
  *latest*, both APKs downloaded and compared (also `releases/latest`), the Streamer's file. Play: 4.4.1 (79) was
  approved on Alpha; bundle 80 (4.4.2) is in the Desktop folder, a new Alpha release opened for it. Issues told:
  #29, #30, #31, #33, #36, #37 (#36 and #37 closed as completed). Play: 80 (4.4.2) sent to Alpha's review through the API. Code 81+ next.
- **After 4.4.2 (2026-11-25, local):** #40 (Fire TV, 4.4.2): the page behind Nuvio's rows was solid green where the video
  background is. Not reproduced (no Fire TV); cause unknown: the video failing to decode there (#39's log has
  `DECODING_FAILED`) or an empty PixelCopy kept as the still. Guard added: `looksEmpty()` (16 x 9 samples, every channel
  within 8 steps) keeps no one-colour copy as a still (background still, its cover under the cameras, the screensaver's);
  still files renamed `bgframe2-`. Seen on the Streamer: four stills kept, none refused. Asked for a report while green.
  #39 (same Fire TV) was sent before pressing *Android settings*: asked again in that order. #31: Mubde asked how to
  hide Nuvio's rows (dock menu → *Hide its rows*, or Apps with channels): answered.
- **Player page over your own background (2026-11-25, his video):** with a video background and see-through glass,
  the Music icon's page drew the song's artwork (blurred) over the whole screen, so the video vanished under the dock
  too and the glass looked bent there only. New `musicPageBg` ("art" default, "own"; Settings → Music & app rows →
  Music mode → *Page behind the player*, shown with your own background): "own" draws no artwork page (a 0.32 shade
  instead) and the glass keeps the page (`Settings.musicKeepsOwnBg`). Also: see-through glass no longer follows
  `ignoreVideo`. Seen on the Streamer: the video behind the player, the dock see-through on it. Add the row to the
  next release's `NEW_IN`.
- **Menu rows (2026-11-25, his ask):** Themes → *Glass over the video* (Bent / See-through, `videoGlass`, with a video
  background); Background → *Quality* (1080p / the screensaver's, `bgAerialQuality`, while Videos play); Screensaver →
  *Quality* (the five `AerialSource.QUALITIES`, `saverQuality`, marked as his own perf choice). Seen in the menu on the
  Streamer, nothing pressed; his values unchanged.
- **Same day, more (local):** `musicPageBg` default "auto" (your background when *Glass over the video* is See-through
  over a video, else the artwork): he had turned see-through on and still saw the artwork page; seen: the video stays
  behind the player by itself. The island's name scrolls with `IslandName` (whole pixels, a step every second frame,
  three passes; `basicMarquee` made the letters dance) and its shadow is a short one under the letters (offset 2 px,
  blur 3) instead of a 10 px blur behind the line. **Menu chips** (`MenuChips` in `SideMenu.kt`): Themes (swatches, the
  name under the row), Background (Titles, My picture, Photos, Videos) and Screensaver (Videos, Posters, Trailers) show
  their kinds across one row; the other settings stay a column. Seen on the Streamer, nothing pressed.
- **Same day, later (local):** #41 (Dave's reading, checked): Down to the Library worked only with *Bounce at the edges*
  on: the edge check now comes before the bounce check in `TileFrame` (key-ups return `bounce`). The home screensaver
  with its own content (trailers…) over a video background: the background's `SurfaceView` goes INVISIBLE while it
  shows (stopped, it held its last frame, seen between trailers); seen: black between trailers. The large clock:
  the rim slivers drawn through `BlurEffect(0.6 dp)` (they stepped), the white date with a shadow under its letters.
  Menu themes row: uniform tiles (`themeTile`), focus = ring and growth (a white fill hid light swatches), a dot
  under the theme in use.
- **Same day, his photos after that (local):** the clock's softened rim looked faded: the blur is gone, the rim and
  its shade are painted at twice the size and drawn down with high-quality filtering (`paint2` in `ThemedNumerals`);
  seen smooth and sharp in all six themes. Dynamic Glass's tile has two waves (was a circle). The home screensaver
  closes the side menu when it starts (`HomeSaver.closeMenu`): menu open → screensaver → ▼ cameras → Back left the
  menu up with nothing selected; seen: the menu gone, the dock selected after. The search over a bright picture: the
  bar and the earlier searches on a dark see-through fill, the headings and the hint with a short shadow (seen).
  Still "pixelated" (his eyes): the rim was a flat opaque band 3-4 px wide with a hard inner cut (read pixel by
  pixel on a screenshot); now each edge fades inwards in five steps (`fadingEdge`), bright at the outline. Seen in
  all six themes.
- The next release with new rows adds its own `NEW_IN` entry; bump `WhatsNew.ID` only if the tour changes. Code 77+.
- Title links, not done or not seen:
  - He must look at the TV: the apps block screenshots, so that Apple TV and Netflix land on the title's page (and
    not their home or a profile picker) is only known from the intent in front.
  - Prime Video (only on the TCL, which was off) and Disney+ (on neither TV) were never opened.
  - No real carousel slide with a link was pressed (the slides tried were Prime / Peacock titles); from the
    Library's platform rows it was.
  - Shahid has no id source. A country JustWatch doesn't know gives no JustWatch links (Wikidata only).
- Platform rows, not done or not seen: with nothing else in the Library (the first selection goes to the first
  platform poster: written, never seen, his Library is not empty); 6 or more posters in a row; the rows' cost
  while scrolling was not measured; services other than Netflix, Apple TV and Shahid were never shown.
