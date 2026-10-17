# TV Downloader

Android TV app to download any file from a link. APKs install automatically when finished.

## Features
- Type/paste a link and press **Download** (or share a link to the app from another app)
- **Send links from your phone**: open the address shown at the top right (e.g. `http://192.168.1.20:8090`) on a phone on the same Wi‑Fi
- **APK auto-install**: when an APK finishes, the Android installer opens (toggle "Auto-install APKs")
- Download queue with progress, speed, cancel and retry
- **History** (saved): press OK on any item to Install / Open / Delete file / Remove from history / Download again
- **Clear all**: delete history only, or history + files
- Follows redirects, reads the real file name from the server, works with http and https

## First APK install
Android asks once to allow installs from this app. Turn ON **Allow from this source** for TV Downloader, press Back, and the install continues.

## Build
Push to `main` — GitHub Actions builds a signed APK and publishes it under **Releases** (`TvDownloader-vN.apk`).
A fixed signing key is in `app/keystore/` so new versions install over old ones.
