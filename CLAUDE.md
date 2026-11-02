# CLAUDE.md

Guidance for Claude Code (and other contributors) working in this repository.

## What this is

**Car Launcher**: a fast, glass-style home screen for Android car head units and Android boxes, with built-in
Music, Videos, Equalizer and FM/AM Radio apps, all shipped in **one APK** (`com.carlauncher`).
Target device during development: amlogic MBOX, Android 5.1.1 (API 22), 1920x1080, reached over `adb` at `192.168.3.33:5555`.

## Hard rules

- **No third-party libraries** (no AndroidX, no Compose). Plain Android SDK + Kotlin stdlib only. Keeps the APK small and fast on weak hardware.
- **minSdk 21.** Do not use APIs above 21 without an `SDK_INT` check.
- **Never disable or uninstall the stock launcher.** "Stop" only calls `killBackgroundProcesses`.
- **Radio is tuner-only.** No internet streams. Real playback needs a vendor-specific `Tuner` backend (see TECHNICAL.md).
- UI is built in code (except `res/layout/main.xml`, `slot.xml`, `item_app.xml`). Match the existing style: glass panels from `Themes.glass()`, text shadow via `Themes.shadowText()`, entry animations on open.
- Animations run only while visible / moving (road, disc, EQ bars) to save CPU.

## Build and run

```bash
# from the project root
D:/Android/re2/tools/gradle-8.7/bin/gradle assembleRelease --offline
cp app/build/outputs/apk/release/app-release.apk CarLauncher.apk
adb -s 192.168.3.33:5555 install -r CarLauncher.apk
adb -s 192.168.3.33:5555 shell am start -n com.carlauncher/.MainActivity
```

- SDK path is in `local.properties` (`D:/Android/re2/tools/sdk`), JDK 17.
- The first `assembleRelease` after edits occasionally produces no APK without an error; running it again works.
- In Git Bash, `adb push` to `/sdcard/...` needs `MSYS_NO_PATHCONV=1`.
- Screenshot: `adb -s 192.168.3.33:5555 exec-out screencap -p > shot.png`.

## Map of the code (`app/src/main/java/com/carlauncher/`)

| File | Role |
|---|---|
| `MainActivity` | Home screen: slots, clock, now-playing card, speedometer + road, layout options, auto dark mode |
| `SettingsActivity` | Settings hub: Launcher, Wi-Fi, Bluetooth, Android, Radio, Speed up, Wallpaper, Drive |
| `Themes` | Accent colours, backgrounds, liquid-glass drawables, light/dark/auto, text shadow |
| `SpeedometerView`, `RoadView`, `AnalogClockView` | Canvas views (dial, road with drawn or photo car, clock) |
| `CarPhotos` | Bundled car PNGs (`assets/cars`) and the user's own car photo |
| `Wallpapers` | Bundled wallpapers (`assets/wall`), generated and random downloaded wallpapers |
| `MusicLibrary` | Scans every storage root (internal, SD, each USB) for audio, grouped by source |
| `MusicService` | Background playback, queue, MediaSession, notification, audio focus |
| `MusicActivity` | Music UI: Songs / Folders / Artists, per-source filter, spinning disc, EQ bars |
| `AudioFx`, `EqualizerActivity` | Equalizer bands, presets, bass boost, bass only, surround, loudness |
| `VideoActivity` | Video library (thumbnails, folders, sources) and full-screen player |
| `RadioActivity` | FM/AM tuner UI, region from time zone, favorites, `Tuner` interface |
| `Apps`, `AppPicker`, `Boost`, `NowPlaying` | App launching, picker, memory freeing, other players' media sessions |

## Preferences (SharedPreferences)

`launcher` (theme, slots, layout, speedometer, car), `music`, `eq`, `tuner`, `radio` (legacy).
