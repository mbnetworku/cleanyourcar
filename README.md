# Car Launcher

A fast, liquid-glass home screen for Android car radios and Android boxes, with its own Music, Videos,
Equalizer and FM/AM Radio apps. Everything installs together from one small APK.

## Features

**Home screen**
- Liquid-glass cards over your wallpaper; light, dark, or automatic dark mode at night
- Live speedometer from GPS (km/h or mph) over a moving road with your car (drawn or a real photo)
- Analog and digital clock, now-playing card with controls
- Customisable sidebar and dock apps (tap to open, hold to change); apps and clock/music can sit left or right
- Stock launcher kept as an app you can open or stop (never disabled or uninstalled)

**Settings**
- Wi-Fi and Bluetooth: scan, connect and pair without leaving the launcher
- Every Android settings page, the head unit's radio settings, and a "Speed up" page (free memory, animations off)
- Wallpapers: bundled photos, colours, your own photo, or an automatic random Full HD wallpaper
- Drive: speedometer style/size/position, car model, colour and size

**Apps included**
- **Music**: songs, folders and artists from internal storage, SD card and every USB drive (each listed separately or all together); spinning disc, equalizer bars, shuffle, repeat, background play
- **Videos**: thumbnail library by drive and folder, full-screen player with ±10 s, next/previous
- **Equalizer**: presets (Bass boost, Deep bass, Rock, Pop, Jazz, Classical, Dance, Vocal, Treble, Car cabin), band sliders, bass boost, bass only, surround, loudness
- **Radio**: FM and AM tuner with the band plan chosen from your time zone, seek, fine tune, search all, favorites for FM and AM

## Install

1. Download `CarLauncher.apk` and install it (`adb install -r CarLauncher.apk`, or open it on the device).
2. Press Home and choose **Car Launcher → Always**.
3. Optional, for the "animations off" speed-up:
   ```bash
   adb shell pm grant com.carlauncher android.permission.WRITE_SECURE_SETTINGS
   ```

## Requirements

- Android 5.0 or newer, landscape screen (built and tested on a 1920x1080 Android 5.1 box)
- GPS for the speedometer; an FM/AM tuner with a supported driver for the Radio app

## Known limits

- **Radio** plays only through real tuner hardware, and each head-unit maker uses its own private driver. Units without a supported tuner show "No FM/AM tuner found".
- **Equalizer** always works on the built-in Music player. Whether it also changes other apps' sound depends on the device.
- Music and video formats are whatever the device's Android can decode (MP3, AAC/M4A, FLAC, OGG, Opus, WAV, MP4, MKV, WebM and more on most units).

See [TECHNICAL.md](TECHNICAL.md), [PRODUCTION.md](PRODUCTION.md), [SECURITY.md](SECURITY.md) and [LICENSE.md](LICENSE.md).
