---
name: sleep-tiredness-system
description: "Characters on both Android and PC now auto-force sleep after being awake too long or at night, not just when the AI itself chooses to"
metadata: 
  node_type: memory
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-16T20:33:18.198Z
---

Added automatic sleep/tiredness on 2026-08-16, on both platforms, combining the two things the user asked for at once: time-of-day (night) AND cumulative awake-duration.

- **Thresholds (same on both platforms):** forced asleep after 20 min awake, or after only 10 min if it's nighttime (22:00-07:00 local clock). Sleeps for 5 min then auto-wakes, or wakes immediately if a direct message arrives for that character while asleep.
- **PC already had `sleep`/`tired` as AI-selectable `set_animation` states** (`AIBehavior.java`'s `applyStaticPose`, wired through `actions.schema.js`) - but only ever manually chosen by the model, no automatic timer, and Java has no duration/auto-wake concept for them (unlike `sayUntil`/`rideCursorUntil`). The automatic forcing + wake timer both had to live in `src/loop/agentLoop.js` (per-character `awakeSinceById`/`sleepStartedAtById` Maps, same pattern as the existing `turnsSinceSayById`), forcing state the same way the AI action does (`shimeji.sendCommand(id, 'set_animation', {state:'sleep'})`) and skipping `tickCharacter` (so `provider.decide()` is never reached) for any sleeping character.
- **Android had neither concept before this.** Added to [[android_rig_renderer]]'s `CharacterState`/`PoseLibrary`/`ActionsSchema.kt` - a lying-down pose for Red (reuses the view-rotation trick already built for wall-climbing) plus a slumped "tired" pose, both gated behind the same `SUPPORTED_IDS` topology check as every other Red-specific pose.

**Why:** user wants this to feel more alive/realistic - not idle forever, and quieter (cheaper) overnight.

**How to apply:** Neither platform's automatic trigger has been visually confirmed live - the thresholds (10-20 min awake, or the whole night window) are too long to practically wait out during a session, and on PC the AI loop doesn't even start until the user manually clicks through the new [[pc_settings_window]]'s "Guardar y continuar", which can't be automated from here. Both are straightforward time-comparison logic mirrored 1:1 across platforms, but treat the exact thresholds as unverified defaults, not confirmed-good numbers, until actually observed.
