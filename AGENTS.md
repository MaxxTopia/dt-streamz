# Viewmaxxing continuity

Read `CLAUDE.md`, `_IF-YOU-LOSE-CLAUDE.txt`, the repository status/history,
and the newest matching `_CONTINUITY/snapshot/claude-memory` note before
editing. Preserve all unrelated and unfinished changes.

- Display name: Viewmaxxing. Keep package, signing, and updater identifiers on
  `dt-streamz` / `com.dt.streamz`; renaming them breaks installed updates.
- The app is Android TV and D-pad-first. PC/emulator mouse and keyboard support
  must complement, not replace, the on-screen TV keyboard and remote focus path.
- Build with `.\gradlew.bat :app:assembleDebug :app:assembleRelease`; run lint
  for UI changes. Verify on the API-30 `Television_1080p` emulator, but describe
  playback and real-remote behavior as box-pending until Diggy tests it.
- Viewmaxxing alone has durable authorization to bump, commit, tag, and push a
  finished release to `main`. Increment `versionCode` every release, keep tag
  and `versionName` identical, never force-push, and stage only intended files.
- Do not stage `.wrangler/`, photos, emulator captures, or unrelated telemetry
  work. Do not use broad `git add -A`.

Prior checkpoint (2026-10-26, v0.4.111 live; superseded by v0.4.112 below): based on v0.4.110, the
Home, Movies, and TV surfaces now refresh their browse cache every five minutes
or from a D-pad "Refresh picks" action. TMDb category rows rotate across three
pages, while the per-tab For You recommender interleaves learned-interest
search buckets, removes Continue Watching duplicates, accepts local
More-like-this/Not-interested feedback, and falls back to popular browse data
on a cold profile or provider miss. Successful browse results persist as a
last-known-good snapshot and remote provider toggles fail open. Twitch still
refreshes live state on entry, on demand, and every minute, sorts live channels
first, distinguishes unavailable probes from offline channels, searches public
channels, loads recent past broadcasts, filters archives, and resumes VODs.
YouTube native resolution keeps a short Piped/NewPipe backup race and prefers a
wrapped DASH source when available; the tab has an account-free local Continue
Watching shelf with resume, D-pad activation, and MENU removal. Playback errors
now expose Retry/Change source recovery, and `scripts/vseebox-smoke.ps1` gives
an ASCII-safe emulator launch check. `:app:compileDebugKotlin`,
`:app:testDebugUnitTest` (5 tests), `:app:lintDebug`, debug/release assembly,
the Twitch GraphQL/PAT/Usher read-only smoke, API-30 emulator install/launch,
and `git diff --check` pass. Commit `7829cb1` is pushed to `main`, tag
`v0.4.111` is pushed, and GitHub Actions release run `40336949397` published
the non-draft `dt-streamz.apk` asset. The physical VSeeBox remote, playback,
DNS, Twitch VOD, and long-session checks remain Diggy's human-only gate. See
RESILIENCE.md and the newest continuity snapshot.

Current checkpoint (2026-10-26, v0.4.112 live): the native
PlayerScreen now watches for a previously-ready VOD or live player whose position
stops advancing, re-prepares the source up to two times, and then hands the current
position to the existing fallback/error route. Native and WebView player surfaces
keep the screen awake; native playback also holds the Activity screen-awake flag
while mounted. The Activity consumes native-player LEFT/RIGHT/OK/media transport
keys before Media3 can trap remote focus in its controller rail and shows a short
seek/playback cue. YouTube and other fallback routes preserve the supplied resume
position. `:app:compileDebugKotlin`, `:app:testDebugUnitTest`, `:app:lintDebug`,
debug/release assembly, and `git diff --check` pass. No emulator is attached, so
long-video recovery, live-stream idle behavior, and physical remote feel remain
Diggy's box-test gate. Commit `7b32982` is pushed to `main`, tag `v0.4.112` is
pushed, and GitHub Actions release run `40454927838` published the non-draft
`dt-streamz.apk` asset. The published SHA-256 is
`6A6B8A2A526E3538472EBD3810D1E8AA22248E4B57AB8C165C647D6C8EB7624F`.
Best next move: install the published APK on the VSeeBox and exercise one long
VOD, one live stream, repeated RIGHT/LEFT, CENTER, BACK, and pause/resume.
