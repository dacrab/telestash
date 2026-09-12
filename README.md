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

- Phone-number login (like the real Telegram client), code + 2FA cloud password support
- Upload photos from the system photo picker — sent losslessly as documents
- 3-column gallery grid with instant previews from TDLib's local cache
- Full-screen viewer: metadata, save back to device gallery, delete from Telegram
- Endless scroll through your photo history
- 100% local: no third-party server, no tracking, no bot token required

## Getting your Telegram API credentials

TeleStash needs a Telegram `api_id` / `api_hash` pair (free, takes 2 minutes):

1. Log in at <https://my.telegram.org>
2. Open **API development tools**
3. Create an app (any title, e.g. `TeleStash`)
4. Copy `api_id` and `api_hash`

Then edit `app/src/main/java/com/telestash/AppViewModel.kt`:

```kotlin
object TgCredentials {
    const val API_ID = 1234567      // your api_id
    const val API_HASH = "abcdef..." // your api_hash
}
```

> Keep these private. Never commit them to a public repo. (For a personal build this
> is fine; for distributing your own builds, move them into `local.properties`.)

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
  `InputMessageDocument` to Saved Messages (chat id 0). Documents skip Telegram's
  photo compression pipeline, so originals are preserved byte-for-byte.
- **Gallery** — `getChatHistory` on Saved Messages, mapped into a grid; thumbnails
  come from TDLib's file cache (or a lightweight minithumbnail while downloading).
- **Delete** — messages are deleted with `revoke`, removing them from Telegram.

## Storage notes

- Telegram file limit: **2 GB per file** (4 GB with Premium) — fine for photos, and
  videos up to that size.
- Telegram's unlimited-storage offer applies to documents kept in your own account.
  Using your account as your personal backup locker is exactly what Saved Messages
  is for.

## Roadmap

- [ ] Auto-backup of new device photos (WorkManager sync)
- [ ] Date-grouped timeline (months/years headers)
- [ ] Albums via Saved Messages topics
- [ ] Multi-select operations (delete/download)
- [ ] Video streaming playback
- [ ] Photo picker with "backed up" indicators

## License

MIT — see [LICENSE](LICENSE).

TDLib is Boost Software License 1.0; the bundled prebuilt AAR keeps its own license
notice (see `app/libs/`).
