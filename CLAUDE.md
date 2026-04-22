# VSeebox APK (personal use)

Status: plan approved 2026-04-22, prereqs pending. Phase 1 starts once Android Studio + JDK 17 + SDKs installed, repo created, Twitch Client-ID + uBO filter list URL shared.
Scope: Android APK for VSeebox V3 (Android 11 TV, 64-bit ARM). Aggregates movie/anime streams (anicrush.to, fmovies) + Twitch live (Twire-style, ad-free) in one sideloaded app. Branded "DT Streamz".

Resolved stack (from session 2026-04-22):
- Native Kotlin (NOT React Native, NOT Cloudstream fork)
- Jetpack Compose for TV (`androidx.tv:tv-foundation`, `tv-material`) — custom UI is the reason for building this
- Media3 ExoPlayer for playback
- OkHttp + Jsoup + hidden-WebView JS eval for scrapers
- Twitch: Helix API + direct `usher.ttvnw.net` HLS manifest fetch (Twire pattern) → no ads inject because we skip Twitch's player JS
- AdblockAndroid (Edsuns) for WebView filtering, filter lists bundled in assets + refreshed via WorkManager
- Self-update: poll GitHub Releases API, prompt ACTION_INSTALL_PACKAGE intent
- Multi-module layout: app / core / player / scrapers/{api,anicrush,fmovies} / twitch / adblock / updater
- Min SDK 30 (Android 11), Target SDK 34

Full build plan: `~/.claude/plans/hi-claude-how-are-enchanted-gem.md` (10-week phased, 6-10 weeks polished v1).

Phase 6 Twitch reference: user's existing Twitch adblock on desktop is `pixeltris/TwitchAdSolutions` VAFT scriptlet (`vaft-ublock-origin.js`) — reverse-engineer its token/integrity-spoofing for our Kotlin HLS fetcher. Twitch Client-ID stored at `app/src/main/kotlin/com/dt/streamz/config/TwitchConfig.kt`.

Phase 5 WebView filter lists to bundle (user picked 2026-04-22): uBO defaults (EasyList + EasyPrivacy + uBO filters + Peter Lowe's) + oisd + Fanboy's Annoyances + AdGuard Mobile Ads. Refresh weekly via WorkManager.

**Important:** personal use only. Pulls streams from third-party sites — DO NOT publish to Play Store or put on the public maxxer suite site. Copyright/ToS issues make it a brand risk if associated with the public suite. Keep sideload-only.

NOT part of the public maxxer suite — see `project_maxxer_suite_plan.md` in user memory.
