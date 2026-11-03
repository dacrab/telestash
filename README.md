# TeleStash

TeleStash turns your Telegram Saved Messages into a photo library that feels like Google Photos.

Your camera roll backs up to your own Telegram account, and the app lays it out as a familiar timeline. Pick the folders you care about, let new shots upload in the background, and keep everything at full quality.

> Telegram already stores files you send yourself. TeleStash makes that storage feel like a gallery instead of a chat.

## What you get

- Background backup, with optional Wi-Fi and charging rules
- A month-by-month grid, pinch-to-zoom viewer, and video playback
- Albums, plus multi-select for bulk download or delete
- Original files, so Telegram never re-compresses anything
- Duplicate detection and a cleanup scan for old copies

## Building it

You need JDK 17 or newer and Android SDK 37.

```bash
./gradlew assembleDebug
```

The APKs land in `app/build/outputs/apk/debug/`. Most phones want `app-arm64-v8a-debug.apk`; there's a universal build in the same folder. Android Studio works too — open the project and hit Run.

The first launch asks for a Telegram `api_id` and `api_hash`. They're free at [my.telegram.org](https://my.telegram.org).

## Before you rely on it

- Telegram caps files at 2 GB each (4 GB with Premium).
- Delete a message in Saved Messages and that cloud copy is gone.
- TeleStash keeps its index on your phone; the photos themselves stay in Telegram.

## License

MIT
