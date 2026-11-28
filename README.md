# Clean Launcher for Fire TV

A quiet home screen for the **Fire TV Stick 4K Max**: a photograph, the time,
and a frosted-glass dock of your apps. No ads, no rows of sponsored content.

It's modelled on [CleanFrame](https://github.com/nthnclrk/CleanFrame), a
launcher for Samsung's The Frame TV, and rebuilt as a small native Android app
in plain Java.

## What you get

- **A calm home screen.** A full-screen photo (15 from Unsplash are included),
  a 12-hour clock in the corner, and your favourite apps in a frosted-glass
  dock at the bottom.
- **Clean app icons.** Popular services (Prime Video, Netflix, Disney+, HBO
  Max, YouTube, Spotify and more) get matching white logos. Other apps keep
  their usual icon.
- **Your apps, your order.** Add, remove and reorder dock apps from the remote.
- **Wallpaper picker** with previews and photographer credits.
- **Tasks:** see which apps are running and how much memory each uses, and
  close the ones you've left open in the background.
- **Gentle on memory.** The Fire TV Stick has little RAM, and heavy streaming
  apps use a lot of it. Clean Launcher:
  - drops its images while you're watching something;
  - clears apps left open in the background when you open a new one;
  - steps in automatically if memory runs low. It never closes the app you're
    watching or any Amazon system app.
- **Dark mode**, plus a list layout if you prefer it to the dock.

## How the Home button works

Fire OS doesn't let you change the default launcher. Clean Launcher includes an
accessibility service that notices when Amazon's home screen is about to appear
and brings Clean Launcher up instead. In **Settings › Home button** you can
choose:

- **Clean:** Home always opens Clean Launcher.
- **Twice:** a second press within 2.5 s goes to Amazon's home.
- **Amazon:** Home goes to Amazon's home screen as usual.

## Remote

- **D-pad + Select:** move and open.
- **Up from the dock:** Wallpaper, Apps, Tasks and Settings.
- **Hold Select, or press Menu, on an app:** move it left or right, add it to
  or remove it from Home, or open app info.
- **Back** closes any panel. In the wallpaper picker it cancels the preview.
- **In Tasks:** select an app to close it, or use **Close all**. The app on
  screen is marked "In use" and is never closed.

## Install

Turn on ADB debugging on the Fire TV (Settings › My Fire TV › Developer
options), then from a computer on the same network:

    ./install.sh                 # defaults to 192.168.1.126
    ./install.sh 192.168.x.x     # another TV

`CleanLauncher.apk` is prebuilt. The script:

- installs it;
- turns on the accessibility service that sends the Home button here;
- grants a few optional permissions:
  - reading the system log, for faster Home detection;
  - location, so it can show the Wi-Fi network's name;
  - diagnostics (`DUMP`), so Tasks can show memory per app.

To update without switching what's on screen, run `adb install -r
CleanLauncher.apk` instead and re-grant those permissions.

## Rebuild

- `./build-local.sh` reproduces the included APK without Gradle. It's signed
  with `debug.keystore`, so updates install over the top.
- Or open the project in Android Studio, or push to GitHub (an Actions
  workflow is included). Gradle builds use a different debug key, so run
  `./uninstall.sh` once before installing a Gradle build over the prebuilt APK.

## Uninstall

    ./uninstall.sh

## Credits

- Design: CleanFrame by Nathan Clark (MIT).
- Photographs: Unsplash License. The photographers are listed in
  `app/src/main/assets/wallpapers/wallpapers.json` and shown in Settings.
- Manrope font: SIL Open Font License (`assets/fonts/OFL.txt`).
- Service marks: Simple Icons (CC0) and Custom Brand Icons (MIT), via
  CleanFrame. Trademarks belong to their owners.
