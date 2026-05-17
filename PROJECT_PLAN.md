# Ad-Free Podcast Player - Current Plan

## Product Direction

Build one local-first podcast product with two real client targets:

1. Windows desktop
2. Android

The current Windows app ships through Electron because it preserves the original podcast-player foundation quickly and already integrates the Python ad-removal engine. That is now the reference implementation, not the long-term destination.

The long-term target is:

1. Native Windows app
2. Native Android app
3. Shared local data contract and ad-removal contract
4. No mandatory always-on backend server for core playback and local processing

## Current Shipping Baseline

### Windows
- Electron desktop host
- Embedded local HTTP server for the copied player UI
- Original podcast-player web foundation as the active player surface
- Python AdCutForge sidecar for ad removal
- Offline episode cache in app data
- Desktop-owned JSON state file for library, progress, and ad-remover settings

### Android
- Native Android app based on the original podcast-player project
- Existing playback and ad-removal integration work continues here

### Ad Removal Engine
- Python-based AdCutForge remains the processing engine
- Parakeet transcription plus OpenAI GPT detection is the only supported job contract

## What Is Already Done

- Original podcast-player foundation is the live Windows renderer
- Ad remover is integrated into the player instead of living as a separate app
- Offline saving writes playable cached audio into desktop app data
- Saved offline episodes can be opened in the ad-remover flow
- Saved offline episodes can now start ad removal directly from the episode card
- Ad-removal progress shows stage, percent, elapsed time, ETA, and live log output
- The player dock no longer covers the bottom of the screen
- Windows state now moves through a desktop-managed JSON file instead of depending on browser-origin storage
- Existing Electron localStorage data is migrated on first launch into the new desktop state file

## Current Local-First Architecture

### Desktop Data
- State file: desktop-managed JSON in app data
- Offline audio cache: desktop-managed files in app data
- Feed metadata, episode library, playback progress, and ad-remover settings remain local to the device

### Desktop Runtime
- Electron main process owns filesystem access, file picking, and ad-removal process launch
- Preload bridge exposes a narrow desktop API to the player UI
- Embedded local server serves player assets and handles RSS, audio proxying, offline save, and cached-audio playback

### Android Runtime
- Native Android app remains the mobile implementation path
- Android should converge on the same state schema and ad-removal job schema where practical

## Native Rewrite Track

### Goal

Replace the Electron Windows shell with a native Windows client while keeping Android native and aligning both clients around the same local contracts.

### Shared Contracts To Freeze First

These need to be stable before the native rewrite moves quickly:

1. Player state JSON schema
   - podcasts
   - episodes
   - playback progress
   - settings
   - ad-remover settings and last result
2. Offline audio cache layout
   - filename rules
   - cache key rules
   - retained metadata fields
3. Ad-removal job request schema
   - source file path
   - transcription backend
   - detection mode
   - remove-original behavior
4. Ad-removal result schema
   - output directory
   - edited audio path
   - run summary
   - emitted progress events

### Windows Native Target

Recommended stack:

- C#
- .NET 8
- WinUI 3
- Windows App SDK

Core native services to build:

1. State store service
   - reads and writes the shared player-state JSON
2. Feed sync service
   - fetches RSS feeds
   - parses episodes
   - merges updates into local state
3. Download and offline cache service
   - saves episode audio locally
   - serves playback from cached files when available
4. Playback service
   - audio playback
   - seek controls
   - progress persistence
5. Ad-removal bridge service
   - launches the packaged Python engine or packaged executable sidecar
   - streams structured progress events back to the UI

### Android Native Target

Recommended stack:

- Kotlin
- Media3
- WorkManager
- Room or JSON-backed local persistence depending on parity needs

Android parity goals:

1. Same podcast and episode identity rules
2. Same progress model
3. Same offline-save behavior where platform rules allow it
4. Same ad-removal settings model where mobile hardware/runtime supports it

Android caution:

- Full local Parakeet-class processing may not be the first Android milestone because of model/runtime cost.
- Phase 1 can still keep Android native while using a reduced ad-removal mode if necessary.

## Rewrite Phases

### Phase 1: Freeze Contracts
- Document the desktop state schema
- Document the ad-removal job/result schema
- Lock cache-path and file-naming rules
- Add sample fixtures for state and ad-removal results

### Phase 2: Build Native Windows Services Without UI Parity Pressure
- Implement state file service
- Implement RSS fetch and parse service
- Implement offline save service
- Implement playback service
- Implement Python sidecar launch and progress parsing

### Phase 3: Build Native Windows UI
- Recreate the player-first layout in WinUI 3
- Match the current library, podcast, episode, player dock, and ad-remover flows
- Keep Electron running only as the parity reference while UI features land

### Phase 4: Promote Shared Contracts Into Android
- Align Android models with the shared state and job schemas
- Reuse fixtures and parity tests
- Keep Android and Windows behavior consistent at the data boundary even if implementation details differ

### Phase 5: Retire Electron
- Confirm Windows native feature parity
- Confirm state migration from Electron desktop file paths if needed
- Stop packaging Electron once native Windows is the shipping path

## Immediate Implementation Queue

1. Extract the player-state schema into a shared contract artifact
2. Extract the ad-removal request/result schema into a shared contract artifact
3. Keep the current Electron app as the working reference build
4. Start the Windows native proof of concept around:
   - local state file
   - feed loading
   - playback
   - offline save
   - Python sidecar launch
5. Keep Android native and align its models with the same contracts as those stabilize

## Risks To Manage

1. Android local ASR may lag Windows because of runtime and model constraints
2. The Python engine interface must stay stable while Windows native and Android clients converge on shared contracts
3. Desktop state migration must be explicit so existing users do not lose subscriptions, progress, or cached audio references
4. Electron should remain the parity harness only until the native Windows app can replace it without feature loss

## Definition Of Success

1. Windows native app can load feeds, play episodes, save offline audio, and launch ad removal locally
2. Android native app follows the same podcast, episode, progress, and ad-removal contract shapes
3. State is local-first and portable across app versions
4. Electron is no longer required for the shipping Windows experience
3. Proceed with Phase 1: Project Setup

