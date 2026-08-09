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

## v0.4.66 live release checkpoint (2026-08-03)

- The reviewed episode-title and playback-flow changes are committed as
  `36b13ea`, pushed to `main`, and tagged `v0.4.66`.
- The local release gate passed debug and release assembly, `lintDebug`,
  `testDebugUnitTest`, and `git diff --cached --check`. The only build warning
  remains the existing deprecated WebView `databaseEnabled` API.
- GitHub Actions run `30861217260` completed successfully in 4m34s. The
  non-draft, non-prerelease release contains the signed `dt-streamz.apk` asset:
  `https://github.com/MaxxTopia/dt-streamz/releases/tag/v0.4.66`.
- The APK is version `0.4.66` / versionCode `80`. The physical VSeeBox was not
  directly ADB-connected during publication, so its updater or manual update
  check remains the final device-side step. A previously installed debug
  package may appear separately because the release package uses the non-debug
  application ID.

## Top-tab focus indicator checkpoint (2026-08-03)

- The top tab strip now keeps content selection separate from remote focus.
  Left/Right moves the focus highlight and the white pill follows the focused
  tab; the section changes only after OK/Enter or pointer activation.
- The custom indicator preserves the full tab-row geometry, including the
  far-right Settings tab, and clears stale focus when the remote returns to
  content. This avoids the old invisible-focus state without restoring the
  accidental tab switches caused by scrolling.
- API-30 `Television_1080p` emulator verification covered Home -> tab focus,
  focus on Anime while Movies content remained active, OK selection into Movies,
  and traversal through Settings. The release gate is pending for v0.4.67.

## v0.4.67 live release checkpoint (2026-08-04)

- The tab-focus repair is committed as `494b299`, pushed to `main`, and tagged
  `v0.4.67`.
- The release gate passed debug and release assembly, `lintDebug`,
  `testDebugUnitTest`, and `git diff --check`. The existing deprecated WebView
  `databaseEnabled` warning remains the only build warning.
- GitHub Actions run `30880406830` completed successfully in 4m38s. The
  non-draft, non-prerelease release contains `dt-streamz.apk` and its download
  URL returned HTTP 200:
  `https://github.com/MaxxTopia/dt-streamz/releases/tag/v0.4.67`.
- The APK is version `0.4.67` / versionCode `81`. The physical VSeeBox was not
  directly ADB-connected, so it must complete its normal updater or manual
  install step before the fix is physically installed on that device.

## v0.4.68 playback-label and buffering release (2026-08-04)

- Playback keeps the show and episode title for routing, watch history, and
  episode navigation, but no longer renders that label over the native player
  or the embedded WebView player. Common embed-host `You're Watching / title`
  headers are also hidden after late provider re-renders using sparse delayed
  checks; the repair is bounded so it does not continuously scan the player
  during playback.
- Native VOD playback now keeps a 30-second minimum / 180-second maximum
  cushion and waits up to 8 seconds after a rebuffer before resuming. Segment
  connections remain alive for short Wi-Fi dips with 15-second connect and
  30-second read timeouts. Live playback keeps its separate low-latency buffer.
- Local verification passed debug and release assembly, `lintDebug`,
  `testDebugUnitTest` (no unit-test sources), and `git diff --check`. The API-30
  TV emulator showed a real VidFast player frame with the provider title absent
  from the rendered screen and accessibility tree; the physical box and a
  long continuous playback soak remain the release boundary.
- The change is committed as `c91c1a3`, pushed to `main`, and published as
  `v0.4.68`. GitHub Actions run `30888511978` succeeded. The signed
  `dt-streamz.apk` asset is live at
  `https://github.com/MaxxTopia/dt-streamz/releases/tag/v0.4.68`, returned HTTP
  200, and has SHA-256
  `f90b3ad3ba964fe56b03682d81d38217030155fd89be973a3824012a5085b683`.
- The default native YouTube choice remains `Smooth (<=720p, recommended)`;
  it is a box-safe cap, not universal adaptive bitrate. Movie/anime embeds
  choose quality upstream, so sustained throughput and low packet loss are
  still required. If the physical box continues to buffer, test `Settings ->
  Video quality -> Data saver`, then compare 5 GHz or Ethernet against the
  current Wi-Fi path and record the exact title/server.

## Continue Watching identity incident and fail-closed checkpoint (2026-08-08)

- Incident: after an app restart, `VidSrcProvider` no longer had the in-memory
  search result for a persisted Continue Watching entry. Its old fallback
  treated an unknown title as a movie. Rick and Morty's canonical IMDb id
  `tt2861424` resolves to TMDb TV id `60625`, but the stale path constructed
  `/movie/60625`. A live VidLink check showed that route was a different
  adult-marked record, while `/tv/60625/1/1` was the Rick and Morty route.
  This was a provider identity/type collision, not content belonging to the
  selected Rick and Morty episode.
- Fix: the provider now re-derives the media type from the canonical IMDb to
  TMDb lookup on cold start, rejects cached-vs-canonical type mismatches,
  rejects TMDb records marked adult, and refuses to build any URL when the
  type cannot be verified. TV and movie paths are constructed only from the
  verified type.
- Fix: Continue Watching now re-hydrates and verifies provider id, title id,
  saved kind, and the exact canonical episode id before resolving streams.
  Invalid or stale entries are refused/removed; an empty or failed source
  resolution never opens the player.
- Fix: WebView playback keeps the unsafe-content boundary active for the
  entire session. Known explicit hosts/paths are blocked even when ordinary
  ad blocking is disabled, unsafe/adult page markers are checked before a
  mirror is accepted, and the former blanket late-playback blocker bypass was
  removed. A bad mirror is stopped and the next mirror is tried, or playback
  fails closed.
- Verification: `:app:assembleDebug`, `:app:assembleRelease`, `:app:lintDebug`,
  and `:app:testDebugUnitTest` passed on 2026-08-08. Direct checks confirmed
  the canonical TV route and reproduced the wrong movie route's adult marker.
  The physical VSeeBox has not yet been updated or tested; that device-side
  install and a real Continue Watching cold-resume are still the final field
  gate.

### Risk register

| Failure mode | Detection | Mitigation / plan B | Residual boundary |
|---|---|---|---|
| Persisted entry loses provider type after restart | Canonical lookup disagrees or returns no type | Refuse URL construction; require reopening from Search | A provider outage may make a valid title temporarily unresumable |
| Provider returns the wrong catalog type for a numeric id | Cached kind differs from canonical TMDb kind | Reject the entry and do not route to WebView | Depends on canonical metadata being available and correct |
| Mirror returns explicit or unrelated HTML with HTTP 200 | Adult marker/unsafe marker in page metadata or known unsafe request host/path | Stop mirror, mark it failed, walk to the next verified source, or show a blocked error | A novel unsafe payload that contains none of the detectable signals cannot be proven safe by a third-party WebView |
| Late ad or redirect request appears after playback begins | Unsafe host/path interception or top-level cross-domain redirect | Block the request; normal ad blocker remains active; no popup window | Third-party embed behavior can change upstream |

Locked decision: an unverified or mismatched provider identity fails closed. The
app must never guess `Movie` for a persisted title merely because that is the
easiest fallback. An absolute guarantee against every future third-party embed
failure would require removing third-party embeds or adding server-side media
fingerprinting; this release closes the reproduced identity collision and the
known unsafe-content paths without claiming that uncontrolled upstream HTML is
infallible.

## v0.4.92 fail-closed release checkpoint (2026-08-08)

- Release contents: the provider identity guard, strict Continue Watching
  validation, always-on unsafe WebView boundary, and the adult-marked TMDb
  rejection shipped as commit `eb13af1` / tag `v0.4.92`. No unrelated telemetry
  worker or untracked workspace files were included.
- Local gate: debug and release APK assembly, `lintDebug`,
  `testDebugUnitTest`, and `git diff --cached --check` passed. The local
  release metadata is version `0.4.92` / versionCode `106`; the only compiler
  warning is the existing deprecated WebView `databaseEnabled` API.
- Hosted gate: GitHub Actions run `31286780014` completed successfully. The
  non-draft, non-prerelease release is live at
  `https://github.com/MaxxTopia/dt-streamz/releases/tag/v0.4.92` and the
  `dt-streamz.apk` asset returned HTTP 200 at
  `https://github.com/MaxxTopia/dt-streamz/releases/download/v0.4.92/dt-streamz.apk`.
- Device boundary: the physical VSeeBox has not been updated or tested from
  this session. Diggy's final field check is to install v0.4.92, cold-launch
  the app, select the existing Rick and Morty Continue Watching card, verify
  the exact saved episode opens, and confirm that a provider failure now shows
  a blocked/unavailable message instead of opening an unrelated player.
- Next best move: after that test, record the exact device result, provider
  mirror, and whether playback reached the selected episode. If a mirror is
  still wrong, keep the entry blocked and capture the WebView log rather than
  weakening the identity guard.

## Finished movie Continue Watching checkpoint (2026-08-08)

- Issue: movie entries stayed visible after the saved position reached the
  existing 20-second end guard. `isFinished()` was only consulted when the
  user pressed Resume; it did not remove or hide the completed movie from the
  persisted Continue Watching flow.
- Fix: `ContinueWatchingStore.entries` now filters effectively finished movie
  entries for every surface that consumes the store, including Home, Library,
  Genres, and Settings. `updatePosition` removes them from persisted history,
  and `record` cleans up older completed entries when new history is written.
  Legacy entries without `kind` are recognized by the movie episode sentinel.
- Scope boundary: finished series/anime episodes remain available so the
  existing Up Next behavior can resolve the following episode. Only movies
  leave the row automatically.
- Verification: the full debug/release assembly, `lintDebug`,
  `testDebugUnitTest`, and `git diff --check` passed after the change. Release
  publication is the remaining step for this checkpoint.

## v0.4.93 finished-movie release checkpoint (2026-08-08)

- Release contents: the completed-movie filtering/removal fix shipped as
  commit `6a3db9d` / tag `v0.4.93`. The version is `0.4.93` / versionCode
  `107`; unrelated telemetry and untracked workspace files were preserved.
- Hosted gate: GitHub Actions run `31291838465` completed successfully. The
  non-draft, non-prerelease release is live at
  `https://github.com/MaxxTopia/dt-streamz/releases/tag/v0.4.93` and its
  `dt-streamz.apk` asset returned HTTP 200 at
  `https://github.com/MaxxTopia/dt-streamz/releases/download/v0.4.93/dt-streamz.apk`.
- Device boundary: install v0.4.93 on the physical VSeeBox, finish or seek a
  movie to within roughly 20 seconds of its duration, exit playback, and
  confirm it disappears from Continue Watching on Home and Library. A normal
  unfinished movie and a finished TV episode should remain available for
  their respective resume/Up Next behavior.

## v0.4.94 English Dub caption-default release candidate (2026-08-09)

- Scope: English Dub anime sources now carry an explicit captions-off default
  through the route and mirror fallback metadata. Original Japanese Audio +
  Subtitles remains captions-on by default. The provider's own caption button
  can still re-enable subtitles during playback.
- Implementation: AniList/VidNest, Anikai, and GogoAnimeBy source builders set
  the per-source default. The WebView applies a bounded initial caption-off
  repair because VidNest currently marks an English text track DEFAULT even on
  its `/dub` route; the repair stops after the initial load window so a manual
  caption choice is respected.
- Verification: debug/release assembly, `lintDebug`, `testDebugUnitTest`, and
  `git diff --check` passed. The debug APK was installed on the `Television_1080p`
  emulator, Naruto: Shippuden Episode 1 English Dub loaded from the real
  VidNest URL, and WebView inspection confirmed the caption-off flag and all
  text tracks in `disabled` mode.
- Release/device boundary: this release candidate is v0.4.94 / versionCode
  108. It has not yet been committed, pushed, published, or installed on the
  physical VSeeBox. The physical box still needs the final English Dub
  subtitle check and a short playback/buffering check.
- Next best move: after publication, install v0.4.94 and test English Dub with
  captions off, manually turn captions on, and exercise the provider's player
  controls during a short buffer stall.
