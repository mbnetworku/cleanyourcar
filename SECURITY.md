# Security policy

## Reporting a vulnerability

Please report security issues privately to the maintainer through GitHub's
**Security → Report a vulnerability** on this repository, rather than in a public issue.
Include the device, Android version, app version and steps to reproduce. You should get a reply within 7 days.

## Permissions and why they are used

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | GPS speed for the speedometer; Wi-Fi scan results on newer Android |
| `KILL_BACKGROUND_PROCESSES` | "Free memory" and stopping other launchers (never uninstall or disable) |
| `WRITE_SECURE_SETTINGS` | Optional "animations off"; only works after a manual `adb pm grant` |
| `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Wi-Fi page: scan and connect |
| `BLUETOOTH`, `BLUETOOTH_ADMIN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | Bluetooth page: pair and connect |
| `READ_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO` | Music and video libraries on internal storage, SD and USB |
| `MODIFY_AUDIO_SETTINGS` | Equalizer and sound effects |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK` | Music keeps playing in the background |
| `INTERNET` | Optional random wallpaper download only |

## Data handling

- No accounts, analytics, ads or tracking. No personal data leaves the device.
- The only network requests are for random wallpapers (picsum.photos), and only when the user turns that on.
- Wi-Fi passwords typed in the Wi-Fi page are passed straight to Android's `WifiManager`. The app does not store them.
- All settings, the user's wallpaper and car photo stay in the app's private storage.

## Exported components

- The launcher and app entry activities are exported only so they can be opened from Home or the launcher.
- `NowPlayingService` is exported but protected by `BIND_NOTIFICATION_LISTENER_SERVICE`, so only the system can bind it.
- `SettingsActivity` and `MusicService` are not exported.

## Supported versions

Only the latest release receives fixes.
