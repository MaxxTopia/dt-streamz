# DT Streamz

Personal Android TV app for the VSeeBox V3. Sideload-only. Not for distribution.

## First-time setup

1. Open this folder in Android Studio → "Trust Project" → Studio will sync Gradle and regenerate the wrapper jar if missing.
2. SDK Manager → confirm Android 14 (API 34) and Android 11 (API 30) platforms installed, plus Android TV API 30 system image for the emulator.
3. Create an AVD: Tools → Device Manager → Create Device → TV category → any TV profile → API 30 image.
4. Run → select the AVD → app should launch and D-pad should navigate the top tabs.

## Build + sideload

- Debug build: `./gradlew :app:assembleDebug` → APK at `app/build/outputs/apk/debug/app-debug.apk`
- Release tag: push a tag like `v0.1.0` and the GitHub Action builds a signed APK into the Release.
- On the VSeeBox: open **Downloader** app → enter the short URL of the release → install.

## Project layout

Phase 1 is app-only. Further modules (`core`, `player`, `scrapers/*`, `twitch`, `adblock`, `updater`) get added per the plan in `~/.claude/plans/hi-claude-how-are-enchanted-gem.md` when each phase lands.
