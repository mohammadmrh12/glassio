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
- **Always keep working on the TV, even while he is browsing or playing something** (his rule since 2026-11-19, 9 pm):
  install, send keys and measure without waiting for him to stop. Still put back what you changed, and never press
  Send / Delete / Sign out or anything that can't be undone on a screen he opened himself.
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
  - The last published version is **4.1.0 (code 71, stable, 2026-11-22)**; 4.1.0-beta1 (code 70) was its one beta.
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
  - **The menu's greeting** (`menuHello`, on by default; Settings → Menu & themes → *Greeting*): "Hello, <name>" at
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
- In Settings **look at the screen before every OK**: Right from the sections lands on the row nearest in height,
  not the first (a blind OK switched a setting of his once; put back).
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
  - Excluding a video: asked him how (long OK already locks one).
  His settings put back (bgMode carousel, aerialUrls his one link, saverMine off, no pick, channelsLow off,
  tourSeen 66).
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
- The next release must replace `NEW_IN`'s 4.1.0 entry with its own rows and bump `WhatsNew.ID` (67) if the tour
  changes. Code 72+.
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
