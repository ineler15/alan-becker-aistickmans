---
name: pc-engine-replacement
description: "PC's Shimeji-ee Java engine fully replaced by a native JS rig renderer (per-character transparent windows) — pushed 2026-08-16, commit a57608d"
metadata:
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-23T03:19:24.642Z
---

User explicitly confirmed full replacement ("Sí, reemplazar todo ahora") after a Red-only proof-of-concept ([[android_rig_renderer]] describes the Android side this was ported from). Done and pushed to GitHub (commit `a57608d`, after `00c48e0` which added the Android idle-sway/anti-repetition work in the same session).

**What changed:**
- Each enabled character is now its own small transparent/frameless/always-on-top `BrowserWindow` (`renderer/character.html` + `character.js`), drawn from its real Stick Nodes rig JSON (`renderer/rigs/*.json`), instead of Shimeji-ee's sprite PNGs.
- `src/jsEngine/characterState.js` — JS port of Android's `CharacterState.kt` physics/animation state machine (same constants, same priority chain). `src/jsEngine/jsCharacterEngine.js` creates/positions the windows and runs the shared 40ms tick loop.
- `src/jsEngine/jsShimejiController.js` implements the exact same `sendCommand()`/`readStatus()` interface as the old `dist/shimejiController.js` (file-based IPC to the Java process) — so `agentLoop.js` and `executor.js` needed only a `require()` path change, no logic changes.
- `main.js` — removed `startShimeji()`/`isShimejiRunning()`/`shimejiProcess` entirely, replaced with `startCharacterEngine()` calling `jsCharacterEngine.start(CHARACTERS)`. Also removed the standalone rig-test window/hotkey (Ctrl+Shift+R) since real character windows supersede it.
- `src/pcSettings.js` — removed `applyActiveShimeji()` (rewrote the external jar's `conf/settings.properties`), since there's no jar to configure anymore.
- **Dragging**: no global click-through hit-testing (this was flagged as unsolved in earlier planning). Instead each window has `-webkit-app-region: drag` on its whole `<body>` and is sized tight around the character, so the OS handles the actual grab/move natively. `jsCharacterEngine.js` watches the window's `'move'` event to feed `CharacterState.dragTo()` (for the pinch/dangle pose) and detects drag-end via a 150ms quiet period with no further `'move'` events.
- Also added `set_custom_animation` (AI-authored keyframe animations) to `src/ai/actions.schema.js`/`src/actions/executor.js`, mirroring the Android feature that was already live there. Ported TDL/victim's `ALT_PATHS` bone-topology fix into `renderer/poseLibrary.js` (previously only had the older single-topology version, which would have rendered their poses distorted).

**Why:** vanilla Shimeji-ee (external Java jar) was the last thing standing between "we have full rig data and a working procedural renderer" (validated on Android) and actually using it everywhere; the settings-window/character-checkbox work earlier this session made clear how much friction the two separate config systems (`characters.js` array vs `conf/settings.properties`) caused.

**Follow-up (2026-08-22):** two real bugs found and fixed after live testing:
- Character windows were way too big (200x260 CSS px vs Android's 128dp square overlay - never
  tuned to match). Cut to 130x170, still looked "gigante" live, cut again to 80x105
  (`WINDOW_WIDTH`/`WINDOW_HEIGHT` in `jsCharacterEngine.js`) - adjust further if still off, it's
  just a constant, no other code depends on the exact number.
- `characterState.js`'s `dragTo(px, py)` had no bounds clamping (an exact match of Android's
  `CharacterState.kt`, which also doesn't clamp - but Android's touch-drag naturally stays
  on-screen while PC's OS-level window drag doesn't). Dragging a character's window below the
  floor line and releasing made `state.y > floorY`, so the very next `tick()`'s falling-branch
  check (`this.y >= this.floorY`) fired immediately, teleporting the character straight to the
  floor with zero fall animation - reported as "se bugea al bajar". Fixed by clamping x/y to
  `[0, screenWidth]`/`floorY` inside `dragTo()` (PC-only fix, didn't touch the Android Kotlin).

**How to apply — known caveats, don't assume this is fully polished:**
- `ride_mouse` is now a silent no-op in `jsShimejiController.sendCommand()` (no mouse-riding physics ported yet) — if the AI calls it, nothing visibly happens but it won't error.
- Walking physics were unit-tested directly (`node -e` instantiating `CharacterState`, calling `startMoving()`, ticking — x reliably moved from 500→350 over 50 ticks) so the underlying movement logic is sound. But live on-screen dragging, and a full walk cycle actually visible in the real app window, were **not** visually confirmed this session — a stray duplicate test launch (see below) only ran ~12s, not long enough for a real AI decision round to land a `walk_to`.
- Mid-session the user watched a live window and described characters as "temblando" (trembling) instead of walking — this was almost certainly the idle-sway animation (subtle sine-wave torso/arm sway added for [[android_rig_renderer]]'s "characters feel frozen" fix, ported to `poseLibrary.js`'s `standPose()`) being seen during a window with no AI walk command yet, not a broken walk cycle — but this was **not conclusively confirmed**, just inferred from the unit test + timing. Verify live before assuming it's fine, and reconsider the sway amplitude/speed if it still reads as trembling once a character is actually confirmed idling (not just mid-test).
- A pre-existing (not-introduced-by-this-work) bug: `main.js`'s `requestSingleInstanceLock()` check calls `app.quit()` but doesn't `return` afterward, so a second launched instance's `whenReady()` startup logic (hotkey registration, `peerServer.start()`, `resetShortTermState()` which deletes `history-<id>.json` files) still partially runs before the process actually exits, causing hotkey-registration-failed / `EADDRINUSE:8787` warnings and clearing history files. Harmless (personality/memory files are untouched), but noisy — worth a one-line fix (`return` after `app.quit()`) if it comes up again.
