# TeleStash

A Google Photos–style gallery app that stores your photos and videos in your Telegram account.

## The idea

Google Photos ended free unlimited storage. Telegram provides cloud storage for files sent to yourself. TeleStash connects the two: it backs up your camera roll to your own Telegram "Saved Messages" and turns it back into a beautiful, familiar photo gallery.

TeleStash uses your Telegram account directly; there is no middleman server.

## What it does

- **Automatic backup** — new photos and videos upload on their own (every ~6 hours, or instantly as they're detected), optionally only on Wi-Fi and while charging. Folders with media are auto-detected so you pick which ones to include, and exact duplicates are skipped automatically.
- **Familiar gallery** — a grid of your backed-up media with month headers like Google Photos, pinch-to-zoom full-screen viewer, and video playback.
- **Albums** — organize photos into albums; select a bunch and move them in one tap.
- **Multi-select** — long-press to select, then download or delete in bulk.
- **Upload without quality loss** — photos and videos are sent as original files, so Telegram never re-compresses them.
- **Local data** — Room metadata and temporary upload staging stay in app-private storage, not separately encrypted by TeleStash. TDLib's local database key is wrapped by Android Keystore.

## Getting started

1. Install the app on your Android phone.
2. On first launch, enter a Telegram `api_id` / `api_hash` (free — see below).
3. Log in with your phone number, the code Telegram sends you, and your 2FA password if you have one.
4. That's it — your Saved Messages is now your photo cloud.

### Getting Telegram API credentials (2 minutes)

1. Log in at <https://my.telegram.org>
2. Open **API development tools**
3. Create an app (any name works) and copy the `api_id` and `api_hash`

The app stores these credentials locally and sends them to Telegram to authenticate your app.

## Building from source

Requirements: JDK 17+, Android SDK 37.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

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
