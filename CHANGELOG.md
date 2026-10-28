# Changelog

All notable changes to TeleStash are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Pre-release suffixes (`-alpha`, `-beta`, `-rc`) are published as GitHub
pre-releases and sort before the matching stable version.

## [Unreleased]

### Added

- Debug builds are now attached to each release alongside the stable APKs
  (`telestash-VERSION-<abi>-debug.apk`). They keep readable class and method
  names for crash reports, are signed with the same key so they install over a
  stable build, and are not debuggable.

Version 0.4.0 is the next planned release.

## [0.3.0] - 2026-09-13

### Added

- Albums, built on Saved Messages topics: create, open, and move selected media
  into an album (forward + delete).
- Multi-select mode: long-press to start, select all, then bulk download or
  delete.
- Date-grouped gallery timeline with month headers.
- Perceptual-hash (aHash) duplicate skipping for uploads and auto-backup.
- Folder-scoped auto-backup with per-folder checkboxes in Settings.
- Login hint that surfaces `AuthenticationCodeTypeTelegramMessage`, so it is
  clear when the code arrives in the Telegram app instead of by SMS.

### Changed

- APK is now split per ABI with a universal fallback, cutting the download from
  ~95 MB to ~26 MB for most phones.

## [0.2.0] - 2026-09-12

### Added

- Multi-photo picker upload (`PickMultipleVisualMedia`).
- ExoPlayer video playback and a pinch-to-zoom photo viewer.
- WorkManager auto-backup with Wi-Fi and charging constraints.
- Room metadata cache, so the gallery renders instantly, with incremental
  history sync.
- DataStore-backed settings, plus onboarding for API credentials (no source
  edit needed).
- Keystore-wrapped TDLib database encryption.
- Correct Saved Messages resolution via `my_id` / `createPrivateChat`.

## [0.1.0] - 2026-09-12

### Added

- Phone authentication via TDLib/MTProto, including 2FA.
- Lossless photo upload, sent as documents to Saved Messages.
- Compose gallery grid with cached thumbnails, a viewer, delete, and download.
- Prebuilt TDLib AAR for all four ABIs.

<!-- Link reference definitions, newest first. -->
[Unreleased]: https://github.com/dacrab/telestash/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/dacrab/telestash/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/dacrab/telestash/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/dacrab/telestash/releases/tag/v0.1.0
