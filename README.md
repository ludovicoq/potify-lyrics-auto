# Lyrics Auto

Spotify Lyrics companion app for Android Auto.

> Display the lyrics of whatever you're listening to on Spotify, right on your car's Android Auto screen.

---

## Features

- 🎵 **Detects the currently playing Spotify track** automatically (polls every ~3 seconds)
- 📄 **Fetches lyrics** from [LRCLIB](https://lrclib.net/) — free, no API key required
- 🚗 **Android Auto integration** — lyrics displayed as browsable media items in the car UI
- ⏭ **Playback controls** — skip to next / previous track directly from Android Auto
- 🔐 **Spotify OAuth 2.0 PKCE** — secure, no client secret needed
- 📱 **Phone companion UI** — see lyrics on your phone too

---

## Screenshots

*(Coming soon)*

---

## Prerequisites

- Android Studio Hedgehog (or newer)
- A **Spotify Developer** account → [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
- **Spotify Premium** subscription (required for playback control APIs)
- Android phone running **Android 8.0+ (API 26+)**
- A vehicle with **Android Auto** support, **or** the [Android Auto Desktop Head Unit (DHU)](https://developer.android.com/training/cars/testing/dhu)

---

## Setup

### 1. Clone the repository

```bash
git clone https://github.com/ludovicoq/potify-lyrics-auto.git
cd potify-lyrics-auto
```

### 2. Register your Spotify app

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard) and create a new app.
2. Under **Redirect URIs**, add: `spotify-lyrics-auto://callback`
3. Copy your **Client ID**.

### 3. Configure the Client ID

Open `app/src/main/java/com/lyricsauto/app/data/SpotifyRepository.kt` and replace:

```kotlin
private const val CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
```

with your actual Client ID.

### 4. Build and install

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and press **Run**.

---

## Testing with Android Auto Desktop Head Unit (DHU)

1. Enable **Developer options** on your phone.
2. Enable **Unknown sources** in Android Auto developer settings.
3. Download and install the [DHU](https://developer.android.com/training/cars/testing/dhu).
4. Connect your phone via USB and run:

```bash
adb forward tcp:5277 tcp:5277
desktop-head-unit
```

5. Open **Android Auto** on the DHU and look for "Lyrics Auto" in the media section.

---

## Architecture Overview

```
┌─────────────────────────────────────┐
│           MainActivity              │  ← Phone companion UI
│  (OAuth, Now Playing, Lyrics view)  │
└─────────────────────────────────────┘
            │
            ▼
┌─────────────────────────────────────┐
│     LyricsMediaBrowserService       │  ← Android Auto integration
│  (MediaBrowserServiceCompat)        │    Polls Spotify every 3s
│  (MediaSessionCompat)               │    Serves lyrics as media items
└─────────────────────────────────────┘
            │
     ┌──────┴──────┐
     ▼             ▼
┌─────────┐  ┌────────────────┐
│ Spotify │  │ LyricsRepository│
│ Repo    │  │ (LRCLIB API)   │
│ (OAuth) │  │ (in-mem cache) │
└─────────┘  └────────────────┘
```

---

## Known Limitations

- **Android Auto display restrictions**: Android Auto limits the number of items shown in a browse list. Very long lyrics may be truncated.
- **Lyrics availability**: LRCLIB has a large catalogue but not every track has lyrics.
- **Spotify Premium required**: Playback control (skip next/previous) requires a Spotify Premium account.
- **Token expiry**: Access tokens expire after 1 hour; the app refreshes them automatically.

---

## License

MIT License — see [LICENSE](LICENSE) for details.
