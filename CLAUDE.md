# viewmaxxing (personal use, formerly "DT Streamz")

Scope: Android APK for VSeebox V3 (Android 11 TV, 64-bit ARM). Aggregates movie/anime streams + Twitch live (Twire-style, ad-free) + YouTube ad-free in one sideloaded app. Display name "viewmaxxing"; internal package + keystore + GitHub-update repo still keyed on `dt-streamz` / `com.dt.streamz` (don't rename — would break sign-in-place updates).

> **CURRENT (2026-06) — supersedes the dated phase table below.** Live as of writing: v0.4.39 (versionCode 53; don't hardcode — check `app/build.gradle.kts`). Min SDK **26** / target+compile **35** (NOT the "30/34" below). Freshest detail: auto-memory `project_vseebox_apk_resume_2026_06_16` + `project_viewmaxxing_v0_4_3_resume`.
>
> ### Build / run
> - Build: `./gradlew :app:assembleDebug` (→ `app/build/outputs/apk/debug/app-debug.apk`, pkg `com.dt.streamz.debug`) or `:app:assembleRelease` (auto-signed via committed `keystore/dt-streamz.jks`).
> - Version bump: `app/build.gradle.kts` — `versionCode` (+1 EVERY release, or in-place updates break) + `versionName`.
> - Emulator: AVD `Television_1080p` (API 30). `emulator -avd Television_1080p -no-window -gpu swiftshader_indirect` → `adb wait-for-device` → assembleDebug → `adb install -r` → `adb shell monkey -p com.dt.streamz.debug -c android.intent.category.LAUNCHER 1`. D-pad = `adb shell input keyevent 19-23/4`. **Input dead mid-test → `adb reboot` BEFORE touching Compose focus code** ([[feedback_android_emulator_input_stuck]]).
>
> ### Ship — AUTO-RELEASE PRE-AUTHORIZED for vseebox-apk ONLY ([[feedback_viewmaxxing_auto_release]])
> Bump versionCode+Name → `git add app/build.gradle.kts app/src` (NEVER `-A` — excludes .claude/.wrangler/photos) → commit (no Co-Authored-By) → `git tag vX.Y.Z && git push origin main && git push origin vX.Y.Z`. CI (`.github/workflows/release.yml`) builds + publishes `dt-streamz.apk` to the Releases of **`dtman-gif/dt-streamz` itself** (`action-gh-release` has no `repository:` override, so it targets the repo it runs in). The in-app updater (`UpdateChecker`, `DEFAULT_OWNER=dtman-gif`/`DEFAULT_REPO=dt-streamz`) polls **that same repo's** `releases/latest`.
> - **⚠️ `dtman-gif/dt-streamz` is PUBLIC and MUST STAY PUBLIC.** Anonymous `releases/latest` returns 404 on a private repo → every install's auto-update silently dies with no error shown. (The old `dtman-gif/dt-streamz-releases` "mirror" is **abandoned**, stale at v0.4.1 — CI never publishes to it, the updater never reads it. Ignore it. Don't re-point anything at it without also moving CI's publish target there.)
> - **Signing identity (survives a dead SSD):** committed keystore `keystore/dt-streamz.jks`, passwords hardcoded in `app/build.gradle.kts` (`storePassword`/`keyPassword` = `dtstreamz`, alias `dt-streamz`). Both ride along in the public repo. Single-point-of-failure = the `dtman-gif` GitHub account; keep an off-machine copy of the .jks + password (vault: `_CONTINUITY\_KEY-VAULT-DO-NOT-LOSE\`).
>
> ### Verify
> - Automated: `./gradlew :app:assembleRelease` succeeds + APK installs (`adb install -r`, no INSTALL_FAILED_UPDATE_INCOMPATIBLE) + emulator smoke (Home paints, tabs/search work).
> - **Human-only — don't claim it works without the real box:** playback (Twitch/YT/movie/anime), provider availability, D-pad feel on the VSeebox V3.
>
> ### Gotchas
> - **Don't rename** package/keystore/update-repo off `dt-streamz`/`com.dt.streamz` — breaks sign-in-place updates.
> - Provider priority (anime): `anikai.to` → `animepahe` → `gogoanime.by` (anicrush dead) — [[project_dt_streamz_provider_priority]]; don't invent priorities. No CAPTCHA/verify-wall providers (OkHttp can't solve them).
> - "Nothing plays" on the box = its **DNS filtering**, NOT the app → user sets Private DNS off / 1.1.1.1 ([[project_viewmaxxing_v0_4_3_resume]]). vidsrc.cc uses capital-M `Movie`, lowercase `tv`. NewPipe YT needs `desugar_jdk_libs_nio` on the API30 box (already enabled).

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
