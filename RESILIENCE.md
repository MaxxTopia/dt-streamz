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

## Section and playback audit checkpoint (2026-08-03)

- Search and scroll no longer activate a tab from incidental focus. Tabs require an explicit OK/Enter or pointer activation, and search grids keep their focus geometry inside the content area.
- Movies, TV, and Anime search now apply both the catalog kind and a non-YouTube provider guard. Each section's For You row applies the same section-aware filter, while YouTube remains in its dedicated tab.
- The search editor replaces the previous query on the first new input after reopening, so changing sections does not silently concatenate stale text.
- The WebView accepts the current VidFast domain migration, detects known ad or verification gates, blocks the failed mirror, and continues through the ordered fallback list. Failure overlays are opaque so an ad page cannot remain visible behind an error message.
- Emulator evidence covered Movies `venom` search and scroll, Anime `naruto` search, TV `office` search, global Search scroll, section For You rows, and Naruto Shippuden's English-Dub-first picker. Venom playback reached the VidFast player after VidLink was blank and displayed a real movie frame; no popup or ad page was visible in the successful path.
- The reviewed tree passed debug and release assembly, `lintDebug`, `testDebugUnitTest` (the project currently has no unit-test sources), and `git diff --check`.
- Remaining boundary: the physical VSeeBox remote, DNS, audio output, subtitles, long-session buffering, and every upstream mirror still require a human box smoke test. No live release was pushed from this audit pass.

## Cross-surface playback audit checkpoint (2026-08-03)

- The reviewed debug APK was installed on the API-30 `Television_1080p`
  emulator after the full debug/release assembly, lint, and unit-test gate.
- A fresh search for Naruto Shippuden opened the details view, then the audio
  picker. `English Dub` was the first visible choice and the route was
  `https://vidnest.fun/anime/1735/1/dub`; the original-audio/subtitle route was
  also present.
- The embedded VidNest player reported a 23:34 duration. After pressing Play,
  the emulator UI reported 25 seconds of elapsed playback after the startup
  wait. No ad/verification page, popup, second window, fatal exception, or app
  ANR marker appeared in the smoke run.
- The physical VSeeBox remains the release boundary for remote focus, actual
  English audio output, DNS, long-session buffering, and non-emulator display
  performance. The app's existing hardware-accelerated WebView, late playback
  acceptance, host-blocker passthrough, and reconnect control remain in place;
  no speculative renderer change was made without physical-box evidence.

## Episode-title propagation checkpoint (2026-08-03)

- AniList streaming episode names are now combined with a TVMaze fallback for
  anime and series. Missing upstream names remain honest as `Episode N` rather
  than blocking the details screen.
- The title is carried through the episode list/grid, audio picker, player
  overlay, Continue Watching, and Library labels. Saved watch entries preserve
  the title while remaining compatible with older entries.
- The rebuilt debug APK was installed on the API-30 TV emulator. Solo Leveling
  showed `Episode 1 · I'm Used to It` and additional real episode names; the
  audio picker listed `English Dub` first and the player overlay retained the
  full show and episode title. The VidNest player reached 7 seconds of a
  23:40 stream with controls visible.
- The physical box remains the release boundary for remote focus, actual audio
  output, DNS, and long-session buffering. No live APK publication was made
  from this local verification pass.
