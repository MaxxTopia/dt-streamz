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

Current checkpoint (2026-07-29): v0.4.49 / versionCode 63 fixes physical
keyboard typing, Backspace, and Enter in the shared search editor and adds
explicit pointer support to the top tabs plus YouTube search, clear, and video
cards. Debug compile, Android lint, signed release build/install, live YouTube
results, D-pad input, and hardware-key input were emulator-verified. The real
VSeeBox remote remains Diggy's human-only check.
