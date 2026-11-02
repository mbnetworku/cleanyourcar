# Production guide

## Release checklist

1. **Signing**: release builds are currently signed with the **debug key** (`signingConfig = debug`). Before distributing:
   - Create a release keystore: `keytool -genkeypair -v -keystore carlauncher.jks -alias carlauncher -keyalg RSA -keysize 4096 -validity 10000`
   - Add a `release` signing config that reads the passwords from environment variables or `~/.gradle/gradle.properties`. Never commit them.
   - Keep the keystore backed up. Updates must be signed with the same key.
2. **Version**: bump `versionCode` and `versionName` in `app/build.gradle.kts`.
3. **Build**: `gradle assembleRelease`, then copy `app/build/outputs/apk/release/app-release.apk` to `release/CarLauncher.apk`.
4. **Asset rights**: confirm you have the right to redistribute every image in `assets/wall` and `assets/cars` (see LICENSE.md). Replace any you do not.
5. **Smoke test on a real unit**:
   - Home: slots, dock, clock, speedometer with GPS while driving, light/dark/auto
   - Settings: Wi-Fi connect, Bluetooth pair and connect, wallpaper change, Drive options
   - Music: internal and USB sources, plug and unplug a USB drive, background play, steering-wheel buttons
   - Videos: play, seek, next
   - Equalizer: presets audible in Music, bass only
   - Radio: on units with a tuner backend, seek, search all and favorites; elsewhere the "No tuner" message
6. **Set as default home** on the unit (Home, then Car Launcher, then Always).

## Device notes

- Android 5.x head units: HTTPS to some modern servers can fail (old root certificates). Features that download try HTTPS first and fall back where safe.
- `WRITE_SECURE_SETTINGS` (animations off) needs a one-time `adb shell pm grant com.carlauncher android.permission.WRITE_SECURE_SETTINGS`.
- Notification access (for showing other players' songs) is granted by the user in Android settings.
- On Android 13+ the media and notification permissions are requested at runtime.

## Radio rollout

Each head-unit family (MTK, FYT/UIS, Rockchip, Allwinner/Microntek, …) needs its own `Tuner` backend. Ship it only after testing on that family, and keep `NoTuner` as the safe fallback.

## Size and performance budget

- APK is about 8 MB, almost all of it bundled images. The code is under 200 KB.
- Target cold start under 1 s on a 4-core ARMv7 box. Keep main-thread work out of `onCreate`; scanning, thumbnails and downloads run on background threads.
