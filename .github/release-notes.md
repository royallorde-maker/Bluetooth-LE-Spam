# Bluetooth LE Spam 1.1.0 — Revamped Fork

This is the first release of the revamped fork. It keeps the original app's package name and
GPL-3.0 licensing, and focuses on a modern interface, faster startup, reliability, and in-app
updates.

## Highlights

- **Material 3 "Specter" redesign** with a floating blur-backed navigation bar and four tabs:
  Info, Advertise, Detect, and Settings.
- **Theming**: light, dark, OLED black, system dynamic color, or a custom seed color. Blur effects
  can be disabled in Settings.
- **Faster first launch**: built-in advertisement sets are generated and inserted in bulk. Later
  launches repair and synchronize built-in data in the background.
- **Reliability fixes** for advertising/scanning services, foreground notification updates, queue
  advancing, detector results, permission handling, service switching, and TX-power preservation.
- **Hardened group editor** with validated names, atomic saves, stable ordering, and inline errors.
- **In-app updates** under Settings → Updates: checks the release feed, downloads through
  Android's DownloadManager, verifies the APK package and signing certificate, and opens the
  system installer.

## Installation

If the official app or an F-Droid build is installed, **uninstall it first**. This fork is signed
with a different key, so Android will refuse to install over it. Uninstalling removes the official
app's saved data. Once you are on a fork release, later releases update in place and keep your
data.

Download `BluetoothLESpam-release.apk` below. A SHA-256 checksum is published next to it.

## Notes

- Android 8.0 (API 26) or newer, with BLE support.
- The Detector tab needs location permission because Android requires it for background BLE
  scanning. The app does not collect or upload location data.
- This is an unofficial fork. Please report fork-specific issues here, and support upstream at
  [simondankelmann/Bluetooth-LE-Spam](https://github.com/simondankelmann/Bluetooth-LE-Spam).

**Full changelog:** https://github.com/royallorde-maker/Bluetooth-LE-Spam/commits/main
