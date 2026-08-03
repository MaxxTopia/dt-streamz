# Viewmaxxing VSeeBox resilience

This is the recovery and release boundary for the Android TV/VSeeBox player.
The emulator and source build cannot substitute for playback on the physical
box, its DNS, and its network.

| Risk | Signal | Immediate response | Durable safeguard |
| --- | --- | --- | --- |
| EVERYONE: a provider changes, rate-limits, or goes down | The WebView has no player controls, remains on a loader, or cannot obtain a duration | Retry the next ordered mirror; keep the title unavailable rather than opening an arbitrary site | Keep the provider list short, current, and manually reviewed; preserve the learned dead-host registry |
| EVERYONE: a provider injects a popup, ad redirect, or second window | A new Android window is requested, a known ad host is seen, or navigation leaves the player | Block the request and return to the in-app player | Multiple windows are disabled, `window.open` is neutralized, and host blocking remains enabled |
| EVERYONE: catalog identity or artwork drifts | A poster is missing, the resolved title differs, or season totals disagree with episodes | Do not ship the affected entry; repair the canonical ID/art mapping | Keep a catalog audit in the release gate and derive displayed counts from actual seasons |
| EVERYONE: a saved audio choice hides English Dub | Anime opens straight into Japanese audio with no visible language choice | Return to the audio picker and choose English Dub when the provider exposes it | Never let a global Sub/Dub preference bypass the picker; show English Dub first and keep titles without an upstream dub honest |
| Box-only: DNS or network filtering blocks all mirrors | All provider hosts fail with name-resolution or connection errors | Check the box network/Private DNS and retry; this requires the owner of the box | Keep this separate from app failures so a network outage is not misdiagnosed as catalog damage |
| Box-only: a long session expires a provider token | Playback starts, then buffers or loses the stream after an extended period | Retry the next mirror; record the title and elapsed time | Keep late playback acceptance from being broken by unrelated page filtering; add a targeted repair if field evidence recurs |
| Release drift or stale APK | Version/tag, build output, or installed app does not match the reviewed source | Stop distribution and rebuild from the reviewed tree | Run assemble, lint, and a human VSeeBox smoke test before any release decision |

## Provider order

The initial order is environment-specific: the current PC audit places VidFast
first, while the movie/series VSeeBox order retains the previously box-proven
VidLink first and the anime path places VidFast first. The app's learned server
score can reorder providers after real playback evidence. A real box test is
required before changing the box-proven default.

## Release boundary

The physical VSeeBox smoke test remains user-owned: open a movie, a series
episode, and an anime episode; confirm controls and duration; wait long enough
to catch buffering; and confirm no popup or external window appears.

## Audio-language repair checkpoint (2026-08-03)

- AniList anime now exposes `English Dub` and `Original Japanese Audio + Subtitles` as explicit choices. The picker always presents English Dub first, including when opened from the in-player audio switch.
- The old global `audio_pref` shortcut was removed from routing so a previous Sub selection cannot hide the English-Dub option on Naruto Shippuden or another anime.
- Local verification passed before this release bump: debug and release APK assembly, `lintDebug`, `git diff --check`, and `testDebugUnitTest` (the project currently has no unit-test sources). This release is 0.4.65 / versionCode 79.
- v0.4.65 is now published as the non-draft `dt-streamz.apk` GitHub release asset. The repository moved to `MaxxTopia/dt-streamz`; the old `dtman-gif/dt-streamz` updater API redirects to the same v0.4.65 release.
- A live read-only check confirmed the VidNest Naruto Shippuden routes for AniList 1735 episode 1 return HTTP 200, and both current provider endpoints return encrypted source payloads for `dub` and `sub`. This is provider-route evidence, not physical-box playback proof.
- Diggy-owned verification remains: install the rebuilt APK on the VSeeBox, open Naruto Shippuden, select `English Dub`, confirm English voices and duration, press the in-player audio switch, and confirm no popup or external window appears. Titles without an upstream English dub must remain honestly unavailable rather than being labeled dubbed.
