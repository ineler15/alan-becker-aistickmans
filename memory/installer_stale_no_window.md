---
name: installer-stale-no-window
description: "Root cause found for 'installer opens but icon does nothing' — release/ installer was built before the PC engine replacement, missing renderer files main.js now requires"
metadata: 
  node_type: memory
  type: project
  modified: 2026-08-23T02:35:02.869Z
  originSessionId: 6696e20e-ecf3-47c5-a836-ac00a1440781
---

**Symptom reported (2026-08-22):** installs fine, but launching from the desktop icon does nothing —
no window, no visible error.

**Root cause found by reproducing it:** the installers under `release/` (`Setup 2.2.0.exe` and its
`win-unpacked` folder) were built 2026-08-15, before several later commits — most importantly
[[pc_engine_replacement]] (commit `a57608d`, same day but later). Diffing the packaged `app.asar`
contents against current `main.js` showed `renderer/settings.html`, `renderer/character.html`,
`renderer/poseLibrary.js`, and `renderer/rigs/*.json` are **missing from that build entirely** —
it only has the pre-refactor files (`renderer/index.html`, `stickman.js`, `stickpaint.html`).
`main.js`'s `app.whenReady()` handler unconditionally calls `createSettingsWindow()` →
`loadFile('renderer/settings.html')`, which doesn't exist in that asar.

It is NOT a "missing Electron" problem — `electron.exe` (renamed to `Alan Becker AIStickmans.exe`)
is correctly bundled in `win-unpacked`, and `electron` correctly does NOT appear inside
`node_modules` in the asar (electron-builder excludes it there on purpose — that's normal, not a bug).

**Reproduction:** launched `release/win-unpacked/Alan Becker AIStickmans.exe` directly with zero
prior instances confirmed running. The process (main + GPU + renderer, 3 total, normal for
Electron) stays alive indefinitely but never creates a single window — verified via a
`user32.dll EnumWindows` P/Invoke listing every top-level window on the system by PID, not just
`Get-Process`'s `MainWindowHandle` (which was also 0 for all three). No stdout/stderr output either
way.

**Fix:** rebuild (`npm run dist`) from current source and reinstall — an already-installed copy
does NOT self-update, per [[windows_dev_workflow]]'s packaging-gotcha note; the new Setup .exe has
to actually be run again.

**How to apply:** before trusting any `release/*.exe` as representative of current behavior, check
its build date/version against `git log` — this project has a habit of accumulating source commits
without rebuilding the installer (matches the same staleness pattern noted in
[[pc_engine_replacement]]'s "known caveats" section, which was never live-verified in a packaged
build either).

**Follow-up (2026-08-22, same session): a second, real bug was hiding under the staleness.**
After rebuilding with current source, the packaged exe *still* crashed — not with a blank window,
but with a visible `Error` dialog: `ENOTDIR, not a directory` at `config.js` (mkdirSync line).
This is the exact ENOTDIR bug [[windows_dev_workflow]] said was already fixed via
`app.isPackaged ? app.getPath('userData') : ROOT_DIR` — except the project's own `.env` has a
leftover `WORKSPACE_DIR=./workspace` line (predates that fix, harmless in dev since it resolves to
the same real folder there). `config.js` used `path.resolve(ROOT_DIR, process.env.WORKSPACE_DIR)`
unconditionally whenever `WORKSPACE_DIR` was set — in a packaged build `ROOT_DIR` is inside
`app.asar`, so the override silently pointed `WORKSPACE_DIR` back inside the read-only asar,
completely bypassing the `isPackaged` safety fallback. Confirmed by instrumenting a copy of the
packaged `config.js` (extract asar with `npx asar extract`, add debug logging, `npx asar pack`
back in, no rebuild needed) — the ENOTDIR error dialog swallows/truncates the actual failing path,
so don't trust its text alone; log the resolved path directly.

Reproduces whenever the packaged exe's working directory is the project folder (`.env` lives there)
— e.g. testing `release/win-unpacked/*.exe` from a dev shell with cwd set to the repo root, which
is exactly how both this session's testing AND (most likely) the user's own manual testing exercise
it. The properly-installed Start Menu / Public Desktop shortcuts (`cwd` = `Program Files\...`) don't
have a `.env` there and were never actually affected — this bug bites *dev testing of packaged
builds*, not necessarily the shipped shortcut, but it's the same "the installer never opens" symptom
from the user's chair.

**Fix applied:** `src/config.js` now resolves any `WORKSPACE_DIR` env override against
`app.isPackaged ? app.getPath('userData') : ROOT_DIR` (the same safe base as the default), instead
of always against `ROOT_DIR`. Rebuilt (`npm run dist`) and reinstalled (`/S` silent flag); verified
live by relaunching from a project-root-cwd shell — settings window now opens correctly instead of
the ENOTDIR dialog.
