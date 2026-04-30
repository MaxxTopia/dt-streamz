# viewmaxxing

Personal Android TV app for the VSeeBox V3 (formerly "DT Streamz"). Sideload-only. Not for distribution.

> Internal package, signing keystore, and GitHub-update repo refs all
> still use the `dt-streamz` / `com.dt.streamz` identifiers — renaming
> those would break update-in-place and APK signature verification on
> existing installs. The display name is the only thing that changed.

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

## Debugging the WebView player (white screens)

Embed sources (vidsrc, 2embed, megacloud) play through a WebView. When one
white-screens, three things to try in order:

1. **Toggle adblock off.** Settings → "Block ads in player" → off → return
   to the title and pick the source again. If the embed plays now, the
   blocklist was killing one of its CDNs; leave adblock off for that
   provider, or grow the `CDN_ALLOWLIST_SUFFIXES` set in
   `WebPlayerScreen.kt`.
2. **Try a different mirror.** VidSrc lists `.to`, `.net`, `.xyz`, plus
   2embed — they rotate, one is usually alive.
3. **Remote-debug the WebView.** Debug builds enable
   `WebView.setWebContentsDebuggingEnabled(true)`. Connect the VSeeBox
   over `adb` (`adb connect <ip>:5555`), then on a desktop Chrome open
   `chrome://inspect/#devices`. The active WebView appears under DT
   Streamz; click "inspect" to see the live DOM, console errors, and
   network panel — that tells you whether the embed is being challenged
   by Cloudflare, blocked by referer, or 404ing.

`adb logcat -s WebPlayer WebPlayer/js HostBlocker` also surfaces blocked
hosts and console errors without DevTools.
