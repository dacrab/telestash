# TeleStash

A Google Photos–style gallery app that stores your photos and videos in **Telegram** — free, unlimited, and private.

## The idea

Google Photos ended free unlimited storage. Telegram gives every account unlimited cloud storage for files sent to yourself. TeleStash connects the two: it backs up your camera roll to your own Telegram "Saved Messages" and turns it back into a beautiful, familiar photo gallery.

Everything stays **yours** — your Telegram account, your files, no middleman server, no tracking.

## What it does

- **Automatic backup** — new photos and videos upload on their own (every ~6 hours), optionally only on Wi-Fi and while charging. You pick which folders are included, and exact duplicates are skipped automatically.
- **Familiar gallery** — a grid of your backed-up media with month headers like Google Photos, pinch-to-zoom full-screen viewer, and video playback.
- **Albums** — organize photos into albums; select a bunch and move them in one tap.
- **Multi-select** — long-press to select, then download or delete in bulk.
- **Upload without quality loss** — photos and videos are sent as original files, so Telegram never re-compresses them.
- **Private by design** — everything runs on your phone and talks only to Telegram. The local cache is encrypted.

## Getting started

1. Install the app on your Android phone.
2. On first launch, enter a Telegram `api_id` / `api_hash` (free — see below).
3. Log in with your phone number, the code Telegram sends you, and your 2FA password if you have one.
4. That's it — your Saved Messages is now your photo cloud.

### Getting Telegram API credentials (2 minutes)

1. Log in at <https://my.telegram.org>
2. Open **API development tools**
3. Create an app (any name works) and copy the `api_id` and `api_hash`

These are only stored on your device.

## Building from source

Requirements: JDK 17+, Android SDK 35.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and press Run.

## Good to know

- Telegram allows files up to **2 GB** each (4 GB with Premium) — plenty for photos and most videos.
- Telegram is not a magic backup service: if you delete a message in Saved Messages, the photo is gone from the cloud copy too.

## Roadmap

- Search by caption/filename
- "On this day" memories
- Trash / soft delete
- Multi-account support

## License

MIT — see [LICENSE](LICENSE).
