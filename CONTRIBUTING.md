# Contributing to Glassio

Thank you for your interest in improving Glassio, an Apple TV-style home screen for Google TV and Android TV.

## Core product principle: customization first

**New features and user-visible behavior should be customizable, not permanently forced on users.** Unless a change is strictly required for optimization, speed, stability, security, or core functionality, provide an appropriate user control.

- Add an enable/disable toggle or a clearly organized setting for each optional feature or behavior.
- Let users choose between meaningful options when a single on/off toggle is not sufficient.
- Persist user preferences across app restarts and upgrades, unless there is a clear technical reason not to.
- Choose sensible defaults, but do not treat the developer's preferred behavior as the only available behavior.
- Avoid cluttering Settings: group related controls, use clear labels, and explain what each option changes when needed.
- Make sure disabling a feature actually disables its behavior and does not leave broken UI, background work, or unnecessary resource usage.
- Respect customization across supported devices and layouts, including Google TV / Android TV remote-control focus and navigation.
- If a feature cannot reasonably be made configurable, explain the technical reason in the pull request instead of silently hard-coding the behavior.

### Performance and optimization exception

Changes whose purpose is strictly to improve performance, speed, memory usage, stability, or reliability do not need a user-facing toggle when exposing a setting would be unnecessary or harmful. Do not add settings that make the app slower, less stable, less secure, or harder to use merely to make an internal implementation configurable. Preserve existing user choices and behavior wherever practical.

## Before you start

- Check existing issues and pull requests to avoid duplicating work.
- For a substantial feature or a change to the app's architecture, open an issue or discussion first so the approach can be agreed on.
- Keep changes focused. Avoid unrelated refactors in the same pull request.
- Do not commit API keys, access tokens, passwords, signing keys, private URLs, or other secrets.

## Suggested workflow

1. Fork `mohammadmrh12/glassio`.
2. Create a branch for your change, for example `fix/poster-focus-shadow` or `feat/backup-restore`.
3. Make the change and keep the code consistent with the surrounding project.
4. Run the relevant build, tests, and checks available in the repository.
5. Test UI changes on Google TV or Android TV when possible, especially remote-control focus, screen scaling, and navigation.
6. Commit your changes with a clear message.
7. Open a pull request against the appropriate branch in the original repository and complete the pull request template.

## Pull request expectations

Please include:
- The problem being solved and why the change is useful.
- A summary of the implementation.
- Whether the feature or user-visible behavior is configurable, where its settings are located, and what the default is.
- The tests or build checks you ran, and any checks you could not run.
- Screenshots or a short recording for visible UI changes, where practical.
- Any compatibility, migration, configuration, or security implications.

Keep pull requests small enough to review. Be responsive to review feedback and explain any trade-offs.

## Bug reports

When reporting a bug, include steps to reproduce it, expected and actual behavior, the affected device/Android TV version, and relevant logs or screenshots. Remove personal information and secrets from logs before sharing them.

## Security issues

Do not report exploitable vulnerabilities in public issues. Follow the instructions in [SECURITY.md](SECURITY.md).

By participating, you agree to follow the project's [Code of Conduct](CODE_OF_CONDUCT.md).

## Developer guide

How the code is laid out, and how to add or fix something without breaking what is there.

### Build and test

You need JDK 17 and the Android SDK (Android Studio has both).

```sh
./gradlew testDebugUnitTest   # unit tests (seconds, no TV needed)
./gradlew lintDebug           # Android lint
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Every pull request runs the same three steps on GitHub (the **Build** check). A red ✗ means something to fix
before it can be merged.

Trakt, Nuvio and Simkl need app ids that are not in the repository. Put your own in `local.properties`
(never commit it):

```properties
TRAKT_CLIENT_ID=...
NUVIO_ANON_KEY=...
SIMKL_CLIENT_ID=...
```

Without them the app builds and runs; those sign-ins just ask for an id.

### Where things are

```
app/src/main/java/com/glasslauncher/tv/
  GlassApp.kt            Starts everything once: settings, image loader, repositories
  MainActivity.kt        The launcher screen, idle timer for the screensaver
  DevHooks.kt            Testing from a computer with adb (see below)
  HomeKeyService.kt      Home button → Glassio, hold Home, camera overlay
  RecentsActivity.kt     Hold Home: open apps
  data/                  No UI. Settings, catalogs, accounts (Nuvio, Trakt, Simkl, Stremio), backup, languages
  ui/                    Compose screens: HomeScreen (carousel + dock), SettingsScreen, SideMenu, LibraryScreen,
                         SetupScreen, Glass (the liquid-glass surfaces), Dialogs
  saver/                 Screensaver: videos, the TV screensaver (DreamService), video cache
  net/                   The "Manage from phone" web page
app/src/test/            Unit tests that run on the computer
```

### Adding a setting

1. **data/Settings.kt**: one line, the key is the property name:

   ```kotlin
   var myOption: Boolean get() = b("myOption", false); set(v) = putB("myOption", v)
   ```

   Pick the default that keeps today's behaviour. Reading it in a composable recomposes automatically when it
   changes.
2. **ui/SettingsScreen.kt**: add a row in the right section: `Toggle(...)` for on/off, `Adjust(...)` for a
   choice cycled with ◀ ▶, `Act(...)` for a button.
3. Use it where it matters (`app.settings.myOption`).
4. Optional: add the row's title to `data/I18n.kt` for the other languages.

The **SettingsKeysTest** fails if a key doesn't match its property name or is used twice.

**Never rename an existing key.** People's saved value would be lost. Rename the property if you must, and
keep the old key string.

Backup and restore pick a new setting up on their own.

### Fixing without breaking

- Keep a change small and about one thing; several fixes are easier to review as several pull requests.
- The TV is the slow part: on a Google TV Streamer the GPU, not the CPU, sets the frame rate. Drawing a
  full-screen picture twice, or a blur every frame, shows. Measure with
  `adb shell dumpsys gfxinfo com.glasslauncher.tv` before and after.
- Anything shown on every frame (the dock, the carousel, the glass) should read state inside `graphicsLayer {}`
  or `drawBehind {}`, so a change redraws instead of recomposing.
- Network and disk work goes on `Dispatchers.IO`, never on the main thread.
- Pure logic (parsing, choosing, formatting) is easiest to keep working if it has a small test in
  `app/src/test`.

### Testing from a computer

Opening screens works for anyone:

```sh
adb shell am start -n com.glasslauncher.tv/.MainActivity --ez open_settings 3   # or open_menu, open_library, open_folder, start_saver
```

Changing settings needs the dev key, so other apps on the TV can't do it:

```sh
K=$(adb shell cat /sdcard/Android/data/com.glasslauncher.tv/files/devkey)
adb shell am start -n com.glasslauncher.tv/.MainActivity --es dev $K --es pref theme=water_still
adb shell am start -n com.glasslauncher.tv/.MainActivity --es dev $K --ez dump_prefs true   # logcat -s GlassPrefs
```

Debug builds accept these without the key.
