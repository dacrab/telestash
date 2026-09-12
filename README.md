# TeleStash

Open-source Google Photos–style gallery that uses **Telegram as unlimited photo storage**.

Photos are uploaded as **documents** (lossless, no Telegram re-compression) into your own
**Saved Messages** via MTProto (TDLib) — your account, your cloud, no middleman server.

## Why

Google Photos ended free unlimited storage. Telegram gives every account free unlimited
cloud storage for documents sent to yourself. TeleStash turns that into a real photo
gallery app — a maintained, open alternative to abandoned projects like TeleDrive and
closed ones like Unlim.

## Features

- **First-run onboarding**: paste your own `api_id`/`api_hash` (from my.telegram.org) — stored on-device only, no build-time config needed
- Phone-number login (like the real Telegram client), SMS code + 2FA cloud password support
- Upload multiple photos/videos per pick (system photo picker) — sent losslessly as documents
- **Auto-backup** (optional, Settings): WorkManager periodically uploads new device photos/videos to Telegram, with Wi-Fi-only and charging-only constraints
- **Encrypted TDLib database**: the local TDLib key is generated once and wrapped by Android Keystore (AES/GCM) — never stored in plaintext
- 3-column gallery grid backed by a Room cache — instant startup, endless scroll, "backed up" cloud badge on device photos
- Full-screen viewer: **pinch-to-zoom** (zoomable lib), **ExoPlayer video streaming**, metadata, save back to device, delete from Telegram
- 100% local: no third-party server, no tracking, no bot token required

## Getting your Telegram API credentials

TeleStash needs a Telegram `api_id` / `api_hash` pair (free, takes 2 minutes):

1. Log in at <https://my.telegram.org>
2. Open **API development tools**
3. Create an app (any title, e.g. `TeleStash`)
4. Copy `api_id` and `api_hash`

The app asks for these on first launch. Keep them private; they're stored in
DataStore on your device only. (To bake them into a personal build instead, put
them in `AppViewModel.kt`'s startup or use a `local.properties` scheme.)

## Building

Requirements: JDK 17+ (21 recommended), Android SDK 35.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected device:

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and press Run.

## How it works

- **TDLib (MTProto)** — the same native library official Telegram clients use, bundled
  as a prebuilt AAR (`app/libs/core-release.aar`, all 4 ABIs, from the
  [tdlib-android](https://github.com/AkashPriyadarshii/tdlib-android) distribution,
  Boost-licensed, CI-built from the official tdlib/td source).
- **Upload** — files are staged in app cache and sent via `sendMessage` with an
  `InputMessageDocument` to Saved Messages (resolved via `getOption("my_id")` →
  `createPrivateChat`). Documents skip Telegram's photo compression pipeline, so
  originals are preserved byte-for-byte.
- **Gallery** — Room caches message metadata; `getChatHistory` pages sync it. Thumbnails
  come from TDLib's file cache, downloading on demand.
- **Auto-backup** — `BackupWorker` (WorkManager, ~6h periodic) scans MediaStore for
  images/videos not yet uploaded (deduped by MediaStore URI) and uploads them.
- **Security** — TDLib's local database is encrypted with a 64-byte key that is
  generated once and stored only in Keystore-wrapped form (AES/GCM).

## Storage notes

- Telegram file limit: **2 GB per file** (4 GB with Premium) — fine for photos, and
  videos up to that size.
- Telegram's unlimited-storage offer applies to documents kept in your own account.
  Using your account as your personal backup locker is exactly what Saved Messages
  is for.

## Roadmap

- [ ] Date-grouped timeline (months/years headers)
- [ ] Albums via Saved Messages topics
- [ ] Multi-select operations (delete/download)
- [ ] Instant local-camera-upload ("backup this folder only")
- [ ] Duplicate detection (perceptual hashing)

## License

MIT — see [LICENSE](LICENSE).

TDLib is Boost Software License 1.0; the bundled prebuilt AAR keeps its own license
notice (see `app/libs/`).
