# Technical overview

## Stack

- Kotlin 1.9.24, Android Gradle Plugin 8.5.2, Gradle 8.7, JDK 17
- `compileSdk`/`targetSdk` 34, `minSdk` 21
- No third-party dependencies; release builds use R8 (minify + shrink resources)
- Single module `app`, package and application id `com.carlauncher`

## Activities and services

| Component | Exported | Purpose |
|---|---|---|
| `MainActivity` | yes (HOME, LAUNCHER) | Home screen, `singleTask` |
| `SettingsActivity` | no | Settings hub |
| `MusicActivity` | yes (LAUNCHER, APP_MUSIC) | Music player UI, own task |
| `EqualizerActivity` | yes (LAUNCHER) | Equalizer UI |
| `VideoActivity` | yes (LAUNCHER) | Video library and player |
| `RadioActivity` | yes (LAUNCHER) | FM/AM tuner UI |
| `MusicService` | no | Playback, MediaSession, media notification (`mediaPlayback` FGS type) |
| `NowPlayingService` | yes (bound by system only, `BIND_NOTIFICATION_LISTENER_SERVICE`) | Reads other players' media sessions |

## Rendering and performance

- Liquid glass is layered `GradientDrawable`s (body, accent glow, specular sheen, rim); a dark tint is added over photo wallpapers. Real blur is not used, since it is too slow on API 21–22.
- `SpeedometerView` pre-renders the static dial into a bitmap; each frame draws only the needle and digits.
- `RoadView` animates with `postOnAnimation` only while the speed is above 0.5 km/h. The photo car is a cut-out PNG with an app-drawn radial shadow.
- Icons load on a 2-thread pool with an `LruCache`; the app list is cached for 30 s.
- GPS, the light sensor and media listeners are registered in `onResume` and released in `onPause`.

## Media

- **Library scanning** (`MusicLibrary`, `VideoLibrary`) walks storage roots directly instead of using MediaStore, so newly mounted USB drives appear at once. Roots come from `/storage`, `/mnt`, `/mnt/media_rw` and USB mount points. Bind-mounted duplicates are removed by comparing `Os.stat` device and inode numbers. `.nomedia` is honoured. Mount and unmount broadcasts trigger a rescan.
- **Playback** uses `MediaPlayer`. Position, duration and pause are only called once the player is prepared, because calling them earlier raises error -38. Files that cannot be decoded are skipped with a toast, with a limit on consecutive failures.
- **Effects** (`AudioFx`): `Equalizer`, `BassBoost`, `Virtualizer` and `LoudnessEnhancer` attach to session 0 (global, where allowed) and to the player's own session. Presets are defined at 5 reference frequencies and mapped to the nearest device band. "Bass only" disables the EQ, surround and loudness and keeps the bass boost.

## Radio (tuner-only)

- `Regions.fromTimeZone()` picks the band plan:
  - Americas: FM 87.9–107.9 MHz in 200 kHz steps, AM 530–1710 kHz in 10 kHz steps
  - Europe, Africa, Middle East and Asia: FM 87.5–108 MHz in 100 kHz steps, AM 531–1602 kHz in 9 kHz steps
  - Japan: FM 76–95 MHz
  - Australia and New Zealand: AM up to 1701 kHz
- `Tuner` interface: `open`, `tune`, `seek`, `signal`, `mute`, `close`. `Tuner.detect()` checks for kernel FM nodes (`/dev/radio0`, `/dev/fmradio`, …) and currently returns `NoTuner`.
- **To support a head unit**, implement `Tuner` against that unit's driver: the vendor radio service or AIDL, or V4L2 radio ioctls through JNI. Return it from `detect()`. Everything else (dial, seek, search all, favorites) already uses the interface.

## Settings storage

All settings are in private `SharedPreferences`: `launcher`, `music`, `eq`, `tuner`. Wallpapers and the user's car photo are stored in the app's private `filesDir`.

## Assets

- `assets/wall/*`: bundled wallpapers
- `assets/cars/c1..c7.png`: car cut-outs (transparent PNG, backgrounds and ground shadow removed)
