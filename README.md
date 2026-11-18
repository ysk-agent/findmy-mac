# Find My Mac (Android)

A personal Android app for locating your own Mac through Apple's Find My
service, without having to sign in to iCloud in a browser every time.

Sign in once with your Apple ID (including two-factor authentication), and
the app keeps the session — encrypted on the device — so on later launches
it goes straight to the map. On first use you pick the Mac you want to
track; from then on the app asks Apple to locate only that device and
shows it on an OpenStreetMap-based map.

## What it does

- Apple ID sign-in with 2FA (trusted-device security code), using Apple's
  global `icloud.com` endpoints.
- Session persistence: the session token set is stored encrypted with an
  Android Keystore key (AES-256/GCM), never in plaintext, and the app is
  excluded from Android Auto Backup.
- Mac-only scope: a first-run picker stores the target Mac's stable
  device id; refreshes pass it to Apple as `selectedDevice`, family
  sharing is off, and the foreground refresh interval is two minutes.
- Map: MapLibre GL with OpenStreetMap raster tiles. No map API key, and
  the device's coordinates are never sent to any third-party conversion
  or map service — WGS84 coordinates are drawn as received.
- Play Sound for the Mac, from the device list.
- The only permission the app declares is `INTERNET`.

## Attribution and licence

This is a renovated fork of
[FindMyNet](https://github.com/PrelinaMontelli/FindMyNet) by
PrelinaMontelli, used under the Creative Commons Attribution-NonCommercial
4.0 International licence (see `LICENSE`). Personal, non-commercial use
only.

Renovation changes relative to upstream:

- Globalised the authentication endpoints (upstream hardcoded Apple's
  China-region `.com.cn` hosts and a `zh_CN` locale).
- Deleted the unused second implementation (`icloud3/` package), the
  never-launched WebView login, and a committed prebuilt APK.
- Removed all logging of session tokens, the 2FA code, and device
  locations (upstream logged them to logcat).
- Deleted the coordinate converter that sent device coordinates to a
  third-party API on every refresh.
- Replaced the Amap map SDK with MapLibre + OpenStreetMap.
- Narrowed device scope to a single user-chosen Mac.
- Replaced plaintext session storage with Keystore-backed AES-GCM
  encryption, dropped the stored password fingerprint, and disabled
  backup of app data.

## Building

Builds run in GitHub Actions (`.github/workflows/build.yml`): a release
build signed with the project's pinned keystore (held in repository
secrets), plus an emulator launch test. The release artifact is always
named `findmy-mac.apk`.

Local builds without the signing environment variables fall back to the
standard debug signing:

```sh
./gradlew assembleDebug
```
