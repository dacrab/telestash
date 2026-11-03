# TeleStash

A Google Photos–style gallery that keeps your photos and videos in your own Telegram account.

TeleStash backs up your camera roll to your Telegram Saved Messages and turns it into a familiar photo timeline. New photos can upload automatically, and everything stays at its original quality. There's no middleman server — the app talks straight to Telegram.

## Features

- Automatic backup of new photos and videos
- Optional Wi-Fi and charging-only rules
- A month-by-month gallery with pinch-to-zoom and video playback
- Albums you can arrange, plus bulk download and delete
- Original files, so Telegram never re-compresses your photos
- Duplicate detection and a cleanup scan for existing copies

## Run it locally

You'll need JDK 17 or newer and Android SDK 37.

```bash
./gradlew assembleDebug
```

Install `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` on your phone, or open the project in Android Studio and press Run.

On first launch, sign in with a free Telegram `api_id` and `api_hash` from [my.telegram.org](https://my.telegram.org).

## Good to know

- Your media lives in your own Telegram Saved Messages, not on a TeleStash server.
- Telegram allows files up to 2 GB each (4 GB with Premium).
- If you delete a message in Saved Messages, that cloud copy is gone too.

## Built with

Kotlin, Jetpack Compose, Room, and Telegram's TDLib.

## License

MIT
