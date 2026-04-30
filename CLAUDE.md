# viewmaxxing (personal use, formerly "DT Streamz")

Scope: Android APK for VSeebox V3 (Android 11 TV, 64-bit ARM). Aggregates movie/anime streams + Twitch live (Twire-style, ad-free) + YouTube ad-free in one sideloaded app. Display name "viewmaxxing"; internal package + keystore + GitHub-update repo still keyed on `dt-streamz` / `com.dt.streamz` (don't rename — would break sign-in-place updates).

## Current state (2026-04-23) — Phases 1-9 complete, Phase 10 = user soak test

| Phase | Status | Summary |
|---|---|---|
| 1 — Skeleton | ✅ | Compose for TV app shell, Gradle wiring, release workflow |
| 2 — Player | ✅ | Media3 ExoPlayer HLS, verified on Mux BipBop fixture |
| 3 — Scrapers | ✅ | Provider framework + gogoanime.by (primary anime), anikai (search only), VidSrc (movies/TV via IMDB + vidsrc.to), Fixtures (offline dev) |
| 3b — MegaCloud | ✅ | AES-CBC extractor for anicrush-family embeds; shipped stand-by in case anicrush returns |
| 4 — Movies + picker | ✅ | VidSrcProvider replaces dead fmovies; multi-source picker screen for episodes with > 1 stream |
| 5 — WebView adblock | ✅ | HostBlocker with ~88k hosts (Peter Lowe's + StevenBlack upstream), WorkManager weekly refresh, shouldInterceptRequest filter on WebView |
| 6 — Twitch HLS | ✅ | Twire-pattern PlaybackAccessToken -> Usher URL, ExoPlayer HLS |
| 7a — Network monitor | ✅ | TCP :443 probe, top-right indicator, auto-hides when green >30s, retargets stream CDN during playback |
| 7b — Twitch favorites | ✅ | DataStore-backed pinned channel list; add via dialog, remove via long-press |
| 7c — Twitch chat | ✅ | Anonymous IRC (justinfan) overlay, 380dp right column during Twitch playback |
| 8a — Home browse rows | ✅ | Per-provider LazyRow on Home/Anime/Movies tabs |
| 8b — Continue watching | ✅ | DataStore history, tops Home, one-tap resume through same source picker flow |
| 9 — Settings + updater | ✅ | Settings tab (refresh blocklist, clear continue, list providers, check for update); GitHub Releases self-updater via FileProvider |
| 10 — Polish + soak | 🏁 | Handoff: use it on the box for a week, file issues for what annoys |

## Stack (locked from 2026-04-22)

- Native Kotlin 2.1 (not React Native, not a Cloudstream fork)
- Jetpack Compose for TV (`androidx.tv:tv-material`) + `compose.material3` for form fields
- Media3 ExoPlayer (HLS + MP4)
- OkHttp (scraper HTTP)
- WebView (DirectEmbed playback) with host-level adblock
- DataStore Preferences (favorites, continue-watching history)
- WorkManager (weekly blocklist refresh)
- Min SDK 30 (Android 11 — VSeebox V3 ships this), target SDK 34, compileSdk 35

## Providers (as registered in `DtApplication`)

1. **FixturesProvider** — offline seeded catalog incl. Mux BipBop for sanity checks
2. **GogoAnimeByProvider** — primary anime source; search `/?s=`, details `/series/<slug>/`, streams via `data-plain-url` to megavid.buzz (rendered in WebView)
3. **VidSrcProvider** — movies + TV; IMDB suggestion API for metadata, vidsrc.to + 2embed.cc embeds for playback
4. **AnikaiProvider** — animekai.to (via anikai.to). Search-only; episode list + streams need JS RE
5. **AnicrushProvider** — dead upstream; code stays in-tree with graceful empty returns + MegaCloud extractor ready if the site comes back

## Important

Personal use only. Sideload-only. NOT published to Play Store. NOT part of the public maxxer suite — copyright/ToS gray area. Keep sideload-only.

Twitch Client-ID = Twitch's public web-player ID (`kimne78kx3ncx6brgo4mv6wki5h1ko`); no user credentials. Chat is read-only (justinfan nick).

## Cross-cutting rules

- **D-pad-first**: every screen navigable by remote alone, no focus traps. Stress-tested each phase.
- **Instantaneous feel**: no splash, no spinners on cached data, all I/O off main thread, ExoPlayer pre-buffered 5s, home paints from DataStore while provider browse() resolves.
- **No CAPTCHA/fingerprint-wall sources** — rejected during site triage (see `project_dt_streamz_provider_priority` memory).

## References (re-read when tuning scrapers)

- Full build plan: `~/.claude/plans/hi-claude-how-are-enchanted-gem.md`
- User memory: `~/.claude/projects/C--Users-Diggy-projects/memory/project_dt_streamz*.md`
- Cloudstream scraper patterns → github.com/recloudstream/cloudstream (read, don't fork)
- Twire's Twitch HLS fetch → github.com/twireapp/Twire
- AdblockAndroid integration → github.com/Edsuns/AdblockAndroid (not used — chose simpler HostBlocker)
