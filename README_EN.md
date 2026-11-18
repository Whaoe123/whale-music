# Whale

[简体中文](README.md) | English

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen.svg)](#requirements)

Whale is an Android music player built with Kotlin and Jetpack Compose, based
on [OHMusic](https://github.com/linkyplus-sys/OHMusic). On top of local
playback it connects to NetEase Cloud Music, Kuwo and Qishui recommendations,
with account sign-in, online search, playlist import and lyrics.

## Features

- NetEase Cloud Music QR / web sign-in, syncing daily recommendations and likes
- Online search (Kuwo / NetEase) on the Discover page, auto-resume playback
  from Qishui recommendations on cold start
- Import NetEase and QQ Music playlist links in one paste
- Local MediaStore scanning; audio must be longer than two minutes
- Media3 `MediaLibraryService` background playback
- System media controls for notifications, lock screens, headsets and Bluetooth
- Sequential, repeat-one and shuffle playback modes
- Favorites, queue and playback-position recovery
- Embedded album artwork with a bundled fallback cover

## Privacy

Whale reads audio metadata and files only after Android grants music access.
Local music metadata, favorites and playback state remain on the device.
Networking is used only for online audio sources and accounts: search,
recommendations, lyrics and playlist import request the corresponding public
endpoints; NetEase / Douyin sign-in happens inside a WebView and the login
state is stored locally. See [PRIVACY.md](PRIVACY.md) for details.

## Requirements

- Android Studio with JDK 17 or newer
- Android SDK 37
- Android 10 / API 29 or newer device

If the SDK is not detected automatically, create an untracked
`local.properties` file:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build and test

On Linux or macOS:

```bash
./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-parallel
./gradlew lintDebug --no-parallel
```

On Windows:

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug assembleDebugAndroidTest --no-parallel
.\gradlew.bat lintDebug --no-parallel
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Its
application ID is `com.whale.music`, so it can coexist with a release
installation.

For release verification without signing credentials:

```bash
./gradlew lintRelease --no-parallel
./gradlew assembleRelease bundleRelease --no-parallel
```

Release signing is configured exclusively through environment variables. See
[CONTRIBUTING.md](CONTRIBUTING.md#release-signing) and never commit a keystore.

## Architecture

```text
Compose UI -> ViewModel -> MusicPlayerManager -> MediaController
                                          \-> MediaLibraryService -> ExoPlayer
Compose UI -> ViewModel -> MusicRepository -> Room / MediaStore
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for lifecycle and data-flow
details. Device-specific behavior is tracked in
[docs/DEVICE_COMPATIBILITY.md](docs/DEVICE_COMPATIBILITY.md).

## Contributing

Issues and pull requests are welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md)
and report security problems according to [SECURITY.md](SECURITY.md).

## License

Whale is based on modifications to OHMusic and is licensed under the
[Apache License 2.0](LICENSE). Third-party attributions are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
