---
name: android-idle-sway-and-repetition
description: "Android idle-sway + anti-repetition guard added 2026-08-16 (commit 00c48e0) — but user reports live on-device that characters now just 'temblando' (trembling) instead of actually walking; NOT resolved"
metadata:
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-16T22:11:47.568Z
---

Follow-up to [[android_rig_renderer]]: user asked why PC characters "parecen mas vivos" (seem more alive) than Android, and picked both halves of the fix ("los dos primeros" — idle stillness AND repetitive AI decisions).

**What was built (commit `00c48e0`, pushed):**
- `PoseLibrary.kt` gained `standPose(p, frame)` — subtle sine-wave torso/arm sway while idle (`sway = 3° * sin(2π*frame/30)`), instead of a perfectly frozen `STAND` empty-map pose.
- `CharacterState.kt`'s `FrameKind.Stand` changed from a singleton `object` to `data class Stand(val frame: Int)` so the sway has a frame counter to animate against; the idle-fallback branch of `tick()` now advances `frameCounter`/`frame` like every other animated state instead of returning a static frame.
- `OverlayService.kt` gained a `dedupeRepeatedAction()` anti-repetition guard (mirrors PC's `agentLoop.js` `lastToolById`/`repeatStreakById` pattern): if the AI picks the same tool 3 times in a row for a character, it's swapped for `move_random` instead (exempting `walk_to`/`move_random` themselves).
- Verified: builds clean (`gradlew assembleDebug`), installed via `adb install -r` to the connected device, launched, no `FATAL EXCEPTION`/crash in logcat for the package.

**UNRESOLVED as of 2026-08-16 — user reports it live:** right after installing/launching, user said "no se mueven en android, simplemente se mueven como temblando" (they don't move on Android, they just move like trembling). This was reported **after** the build+install+launch check above (which only confirmed no crash, not correct visible behavior) — so the idle-sway change may be too easily mistaken for/actually causing a stuck "trembling in place" look instead of real walking, OR (more likely per [[android_rig_renderer]]'s existing note) most non-Red characters never receive an AI decision at all because they lack a real Gemini key ([[gemini_quota_per_project]]) and so *only* the new idle sway ever plays — which now reads as "shaking" instead of the previous silent frozen stand, i.e. made the missing-API-key limitation newly visible as motion instead of stillness.

**Next steps, don't assume either fix actually works until checked:**
1. Confirm whether Red specifically (the one character with a real key) also just trembles instead of walking, or whether it's only the keyless characters — that would confirm the "no AI decision ever arrives" theory rather than a real regression in `startMoving()`/the walk branch of `CharacterState.tick()`.
2. If Red also just trembles, suspect a real regression from the `Stand(0)`/`frame` refactor — check `CharacterOverlay.kt`'s pose wiring (`PoseLibrary.forFrameKind(kind, def.id)`) actually gets called every tick with fresh `kind`, and that `RigView` redraws on each new pose (not just once).
3. Consider tuning/removing the sway amplitude if it's genuinely being confused for jitter even while a character IS between real actions (3° over a 4.8s period should be subtle - verify that's actually what's rendering, not something amplified by a scale/transform bug in `RigView`).

**RESOLVED same session (2026-08-16), root cause confirmed via logcat, not guessed:** `decide() failed for Red` / `for TDL` — `java.lang.IllegalStateException: gemini API error (401): Invalid API Key`. Checked the actual stored prefs (`adb shell run-as com.stickmanai.android cat .../shared_prefs/stickman_prefs.xml`) and every per-character `api_key_*` the user had entered was a token starting `AQ.Ab8RN6...` — **not a real Gemini API key** (those start `AIzaSy...`, from https://aistudio.google.com/apikey). So it was never a code bug at all - see [[gemini_quota_per_project]] for the key-format note added there. Also found `shared_provider` was set to `groq` with `shared_api_key` empty, while several characters had their OWN `provider_<id>=gemini` + the bad key overriding it - so "I set it as shared" didn't take effect for those characters either.

Two follow-up commits landed regardless (good regardless of the key issue, and both pushed):
- `f6c8f17` — reduced idle-sway amplitude/period (3°/30-frame period → 1°/90-frame period) since the original read as jittery "trembling" rather than a subtle breathing motion, AND added an autonomous-wander fallback (`IDLE_WALK_TIMEOUT_MS = 6000L` in `CharacterState.kt`/`characterState.js`, both platforms): if nothing has moved a character (AI decision or drag) in 6s, it auto-walks to a random point via `randomTarget()`. This does NOT fix a broken/invalid API key - it just guarantees visible life either way. `lastActiveAt` is reset in `startMoving`/`setEmotion`/`startCustomAnimation`/`dragTo`/`startFalling`/`startClimbing`.
- `5c32e4a` — unrelated but found via the same live-debugging session: `CharacterOverlay.kt`'s keyboard-hide logic (`keyboardInsetPx`) was only recomputed inside an `OnGlobalLayoutListener` that reliably fires when the keyboard opens but often does NOT fire again when it closes (nothing forces another layout pass for that small overlay window) - so characters stayed hidden until app restart. Fixed by recomputing `keyboardInsetPx` every `tick()` (~40ms) instead of only from that one-off listener.
