# Clean Launcher: context for Claude Code

## What this is
A custom home screen for a **Fire TV Stick 4K Max (1st gen, 2021)**, modelled on
[CleanFrame](https://github.com/nthnclrk/CleanFrame) (a Samsung The Frame / Tizen
launcher), rebuilt as a native Android app in plain Java with no AndroidX.

- Package: `com.hector.cleanlauncher`
- Device: Fire OS 7 (Android 9, API 28), reached over Wi-Fi ADB at
  **192.168.1.126:5555** (was 192.168.50.231 on the old network)
- The user is on a **Mac**; `adb` is installed via Homebrew
  (`android-platform-tools`).

## Design (keep it)
- Full-screen Unsplash photo (15 bundled, from CleanFrame) with a top-left
  clock in 12-hour `h:mm` format.
- Top right: Wallpaper / Apps / Settings pills, icon-only until focused.
- Bottom: a frosted-glass dock. `GlassView` draws a slice of a **pre-blurred**
  copy of the wallpaper (`assets/materials/<id>-blur.jpg`), because Android 9
  has no RenderEffect.
- White monochrome service marks (`res/drawable/brand_*.xml`), matched by
  package or by app name; other apps use their normal icon.
- Font: Manrope Regular/Medium (`assets/fonts`).
- Default dock order, the household's most-used apps: Prime Video, HBO Max,
  Netflix, Disney+, Stremio, TizenTube, Jellyfin (`DEFAULT_DOCK`, saved under
  the pref key `dock_v2`).

## Files
- `MainActivity.java`: the whole UI (dock, app library, settings,
  wallpaper picker); views are built in code.
- `GlassView.java`: the glass material.
- `Wallpapers.java`: reads `assets/wallpapers/wallpapers.json`.
- `HomeWatcherService.java`: an AccessibilityService that listens for
  `TYPE_WINDOW_STATE_CHANGED`. When Amazon's home screen comes to the front
  (`KNOWN_HOMES`, or the learned `homePkg` pref), it starts `MainActivity`.
  The Settings › Home button modes are `clean`, `twice` (a second press within
  2.5 s stays on Amazon) and `amazon`. "Detect Home screen" sets the
  `learnUntil` pref and records the next foreground package as `homePkg`.
- `install.sh`: `adb install -r`, then enables the service with
  `settings put secure enabled_accessibility_services ...` and
  `accessibility_enabled 1`.
- `build-local.sh`: a Gradle-free build (aapt2/javac/d8/apksigner) that makes
  `CleanLauncher.apk`. Works on the Mac with Homebrew `android-commandlinetools`
  and `openjdk@17` (falls back to the old Linux `/tmp/tools` paths). Signed with
  `debug.keystore`. Homebrew's Gradle 9 is too new for AGP 8.2.2, so prefer this.
- `AppCatalog.java`: the app list, scanned once per process in the background and
  shared by MainActivity and the cover; screens rebuild only when it changes.

## Current problem (start here)
**Pressing Home still opens Amazon's UI instead of Clean Launcher**, after a
reinstall with `install.sh` to 192.168.50.231. The cause isn't known yet.
Diagnose with the device rather than guessing:

```bash
adb connect 192.168.50.231:5555
adb shell settings get secure enabled_accessibility_services
adb shell settings get secure accessibility_enabled
adb shell dumpsys accessibility          # is HomeWatcherService bound?
adb shell dumpsys window | grep -E "mCurrentFocus|mFocusedApp"   # with Amazon home showing
adb shell getprop ro.build.version.name  # Fire OS version
adb logcat -c; (press Home); adb logcat -d | grep -iE "cleanlauncher|accessib|ActivityManager"
```

Hypotheses, in order:
1. The service setting is written but Fire OS strips it, or doesn't bind
   sideloaded accessibility services. Check `dumpsys accessibility`.
2. The service is bound, but the home screen's package isn't in
   `KNOWN_HOMES`. Read it from `mCurrentFocus` and add it, or use
   Settings › Detect Home screen.
3. Events arrive, but the `startActivity` from the service is blocked or the
   event is filtered. Add `Log.d` calls in `onAccessibilityEvent` and watch
   logcat.

Background: Fire OS 7.2.7.3 and later block the old "Launcher Manager"
approach, and a later 4K Max update blocked accessibility-based remote
**key** remapping. This app watches window changes rather than keys, so it may
or may not be blocked too; confirm on the device.

## Rules
- **Never** run `pm disable-user` / `pm uninstall` on `com.amazon.tv.launcher`
  or other Amazon system packages: on recent Fire OS this can leave the stick
  stuck on boot.
- Ask before changing secure settings other than the accessibility
  service ones above.
- Updating over the prebuilt APK needs the same signature: build with
  `debug.keystore` (alias `androiddebugkey`, store and key password `android`),
  or run `./uninstall.sh` first.

## Building on the Mac
The simplest route is a Gradle build: open the folder in Android Studio, or
install JDK 17, Gradle 8.5 and the Android SDK command-line tools via Homebrew
and run `gradle assembleDebug` (the project uses AGP 8.2.2, compileSdk 34,
minSdk 22). To keep the existing signature, add a `signingConfigs.debug`
pointing at `debug.keystore`. The output goes to
`app/build/outputs/apk/debug/`. Install with
`adb -s 192.168.50.231:5555 install -r <apk>`.
