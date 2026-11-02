# 🔒 Security Policy

Car Launcher runs on the screen of a moving vehicle and has access to its connections. We take that seriously.

---

## 📣 Reporting a vulnerability

**Please report problems privately. Don't open a public issue.**

1. On GitHub, open this repository's **Security** tab and choose **Report a vulnerability**.
2. Include:
   - the device and head-unit model, and its Android version
   - the Car Launcher version
   - what happened, and the steps to make it happen again
   - screenshots or logs if you have them

| Stage | Target time |
|---|---|
| First reply | within **7 days** |
| Fix or plan | within **30 days** for serious issues |
| Public note | after a fix is released |

## ✅ Supported versions

| Version | Fixes |
|---|---|
| Latest release | ✅ Yes |
| Older releases | ❌ No, please update |

---

## 🛡 What the app does and doesn't do

| ✅ Does | ❌ Doesn't |
|---|---|
| Store all settings in the app's private storage | Collect analytics, ads or tracking data |
| Pass Wi-Fi and hotspot passwords straight to Android | Store or send your passwords anywhere |
| Read music, video and files on your drives to show them | Upload your files |
| Download a random wallpaper, only when you turn that on | Contact any other server |
| Stop other launchers' background work when you ask | Uninstall anything |

The only network request the app makes is the optional random wallpaper download from `picsum.photos`.

---

## 🔑 Permissions and why each is needed

| Permission | Used for |
|---|---|
| Location (fine and coarse) | GPS speed for the speedometer; Wi-Fi scan results on newer Android |
| Wi-Fi state, change Wi-Fi state | Wi-Fi page, on/off switch and hotspot |
| Network state, Internet | Home-screen connection status; optional wallpaper download |
| Bluetooth, Bluetooth admin, connect and scan | Bluetooth app: pair, connect and rename |
| Read and write storage, read media audio and video | Music, Videos and Files on internal storage, SD and USB |
| Request install packages | Installing an APK you tap in Files (Android still asks you to confirm) |
| Modify audio settings | Equalizer and sound effects |
| Foreground service (media playback), wake lock | Music keeps playing in the background |
| Kill background processes | *Free memory* and stopping or disabling other launchers |
| Write secure settings *(optional)* | *Animations off*. Only works after you grant it yourself over ADB |

## 🚪 Exported components

| Component | Why exported | Protection |
|---|---|---|
| Home screen and app entry screens | So they can open from Home and other launchers | Standard launcher entries |
| `NowPlayingService` | Read other players' song info | Only the system can bind it (`BIND_NOTIFICATION_LISTENER_SERVICE`) |
| `SettingsActivity`, `MusicService` | — | Not exported |

---

## 🚗 Driving safety

Use the launcher's controls only when it's safe and legal to do so. Set up wallpapers, Wi-Fi, pairing and other settings while the vehicle is parked.

---

© 2026 MB
