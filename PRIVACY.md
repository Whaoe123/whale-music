# Privacy

Whale is an Android music player based on OHMusic.

## Data accessed

- Audio files and MediaStore metadata, after the user grants Android's music
  access permission
- Notification permission, when required by the Android version, to show active
  playback controls
- The network, used only for online audio sources and accounts: search,
  recommendations, lyrics, playlist import and cover art requested from the
  corresponding public endpoints, plus sign-in pages loaded in a WebView

## Data stored

- A local Room database containing scanned song metadata and favorites
- Local preferences containing the playback mode, queue identifiers and last
  playback position

The app does not copy or upload audio files. It does not contain analytics or
advertising SDKs. Account login state for NetEase Cloud Music / Douyin is kept
locally on the device.

## Backup

Android device-to-device transfer may preserve favorites and playback settings.
Scanned song metadata, cached artwork and transient playback state are excluded
from cloud backup because they can be recreated from local media.

## Deletion

Uninstalling Whale removes its local database and preferences. Android may
retain explicitly allowed backup data according to the user's system backup
settings.
