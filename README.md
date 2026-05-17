# Ad Free Podcast Player

A cross-platform podcast player with integrated ad removal for Windows and Android.

> **⚠️ EXPERIMENTAL PROJECT** — This is an experimental tool. Users of the ad remover feature must own the podcast rights or have explicit permission from the content creator. Unauthorized removal of ads from podcasts you do not own or have rights to modify may violate copyright and licensing agreements.

## Features

- **Cross-Platform**: Windows (Electron) and Android (native Java)
- **RSS Feed Support**: Add and manage podcasts via RSS feeds
- **Integrated Ad Removal**: Automatically detect and remove ads from episodes
- **Offline Playback**: Save episodes locally for offline listening
- **Episode Search**: Find and manage episodes from your subscribed feeds
- **State Persistence**: Desktop (JSON file) and Android (SQLite) state management

## Project Structure

```
ad-free-podcast-player/
├── apps/
│   ├── desktop/              # Windows Electron app
│   │   ├── src/              # Main process, server, processing
│   │   ├── player-ui/        # Player UI (HTML/CSS/JS)
│   │   ├── scripts/          # Build & smoke test scripts
│   │   └── package.json      # Desktop dependencies
│   ├── android/              # Android native Java app
│   │   ├── app/              # Main Android module
│   │   ├── gradle/           # Gradle wrapper
│   │   └── build.gradle      # Android build config
│   ├── server/               # PHP API + worker processing stack
│   │   ├── api/              # PHP upload/job API
│   │   ├── worker/           # Linux worker (ffmpeg/AdCutForge)
│   │   └── docker-compose.yml
│   └── web/                  # Web/Vite project template
├── package.json              # Root workspace config
├── packages/                 # Shared packages (if any)
├── PROJECT_PLAN.md           # Long-term roadmap
└── README.md                 # This file
```

## Tech Stack

### Desktop (Windows)
- **Framework**: Electron 37.7.1
- **UI**: HTML/CSS/JavaScript (Vite-based player UI)
- **Backend**: Node.js HTTP server + IPC bridge
- **Ad Removal**: Python (`ad_cut_forge.py` via subprocess)
- **State**: JSON file (`%APPDATA%\desktop\player-state.json`)

### Android
- **Language**: Java
- **UI Framework**: Android native (Material Design)
- **Audio**: Media3 Transformer for export
- **Transcription**: Parakeet on the Windows processor
- **Ad Detection**: OpenAI GPT via the server/Windows processor
- **State**: SQLite database

### Web Server
- **API**: PHP 8.3 + SQLite
- **Worker**: Python 3.11
- **Media Runtime**: ffmpeg (default test mode) or AdCutForge
- **Deployment**: Docker Compose (`apps/server/docker-compose.yml`)

### Shared
- **Ad Removal Engine**: Python (`ad_cut_forge.py`)
- **Transcription**: Parakeet only
- **Ad Detection**: OpenAI GPT only
- **RSS Parsing**: Standard feed format support

## Development Setup

### Prerequisites
- **Windows/Desktop**:
  - Node.js 18+
  - npm or yarn
  
- **Android**:
  - Java JDK 11+
  - Android SDK (auto-downloaded to `apps/android/.android-sdk/`)
  - Gradle (via wrapper)

- **Python** (for ad removal):
  - Python 3.9+
  - See `c:\Users\Gabe\Documents\Codex\2026-05-08\podcast ad remover\requirements.txt` for dependencies

### Install Dependencies

Root dependencies:
```bash
npm install
```

Desktop app:
```bash
npm install -w apps/desktop
```

Android (Gradle handles dependencies):
```bash
cd apps/android
./gradlew build
```

## Building

### Windows (Electron)
```bash
npm run windows:package
# Output: apps/desktop/Ad Free Podcast Player-win32-x64/Ad Free Podcast Player.exe
```

### Android (APK)
```bash
npm run android:package
# Output: apps/android/Ad Free Podcast Player-debug.apk
```

### Smoke Tests

Desktop state & source-promotion test:
```bash
npm run smoke:desktop
# Tests state persistence and ad-free file promotion logic
```

## Running

### Windows Desktop
```bash
./apps/desktop/Ad Free Podcast Player-win32-x64/Ad Free Podcast Player.exe
```

### Android
```bash
# Install to emulator or connected device
adb install apps/android/Ad Free Podcast Player-debug.apk

# Launch
adb shell am start -n com.localpod.player/.MainActivity
```

### Web Server API + Worker
```bash
# from repo root
npm run server:up

# API UI at
http://localhost:8080
```

Server usage details live in [apps/server/README.md](apps/server/README.md).
Windows local setup details, including the UI disable toggle, live in [apps/server/windows/README.md](apps/server/windows/README.md).

## Project Status

### Completed
- Windows EXE with player UI and ad remover integration
- Direct "Remove ads" action on episode cards
- Ad-free export replaces episode playback source
- Android native app with parity ad removal action
- Offline save and playback (both platforms)
- Build artifacts at top-level platform folders
- Comprehensive `.gitignore`

### Pending / Roadmap
- Native Windows rewrite (long-term, see `PROJECT_PLAN.md`)
- Native Android enhancements
- Cloud sync for state across devices
- Advanced filtering and playlist support
- Streaming optimization

## Legal & Attribution

### Ad Removal Disclaimer
The ad removal feature is designed for personal use on podcasts you own or have explicit permission to modify. Unauthorized removal of ads from third-party content you do not own or have rights to may violate:
- Copyright law
- Podcast licensing agreements
- Content creator agreements with ad networks

**Use responsibly and legally.**

### Python Ad Removal Engine
Based on `ad_cut_forge.py` from the separate podcast ad remover project. Uses Parakeet for transcription and OpenAI GPT for ad detection.

## Contributing

For now, this is a personal/experimental project. Long-term contribution guidelines will be added as the project matures.

## License

TBD — Project is currently experimental. License will be formalized once the project reaches stability.

## Troubleshooting

### Port 4173 Already in Use (Desktop)
The internal Node server uses port 4173. If you see `EADDRINUSE`, kill the conflicting process:
```powershell
Get-Process -Name node | Stop-Process -Force
```

### Android Emulator Setup
To test on an Android Emulator:
```bash
# Create and start an AVD
avdmanager create avd -n test-avd -k "system-images;android-35;default;x86_64"
emulator -avd test-avd

# Then install and test
adb install apps/android/Ad Free Podcast Player-debug.apk
```

## Contact & Support

This is an experimental project. For feedback or issues, refer to the project repository.
