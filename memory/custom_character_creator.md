---
name: custom-character-creator
description: "Crear tu propio stickman" feature architecture - custom rig templates, pose-profile mapping, face/emotion/gender - both platforms
metadata:
  node_type: memory
  type: project
  modified: 2026-08-23T00:00:00.000Z
---

Built across three sessions on 2026-08-23 (shipped as Windows v2.4→v2.6, Android v1.4→v1.6). Lets
a user create a custom character (name/color/head-model/face/gender) from a dedicated screen on
both platforms, instead of editing source.

**Core mechanic - clone a real character's rig, don't build one from scratch:**
- "Normal" head = clone of Red's rig (`renderer/rigs/Red.json` / Android `assets/rigs/Red.json`):
  the head is one filled `Circle` node.
- "Hollow" head = clone of **TCO's** rig, not TDL's (same ring-of-curved-bones head construction,
  but TDL's rig also carries a sword prop that doesn't belong on a generic character). The ring
  head has no `Circle` node at all - it's drawn by stroking a chain of `curveRadius` bones as one
  smooth path, which is what gives it the actually-hollow look. A hollow flag on a Circle node
  does NOT reproduce this - learned the hard way after v2.4 shipped with the wrong approach.
- Color is a straight override of the template's top-level `color` field.

**Every subsystem that keys off character id needed a "which built-in was this cloned from"
lookup**, since a custom id like "JoseNandu" is invisible to code that only knows built-in ids:
- Animation: `poseLibrary.js`/`PoseLibrary.kt`'s angle-override tables are keyed by id (`Red`,
  `TCO`, etc.) - custom characters get told to use whichever profile their rig was cloned from
  (`poseProfile` field), or they stand frozen forever.
- Face/gender rendering and the AI-facing personality gender line work the same way: a
  `metaFor(id)` (PC, `src/customCharacters.js`) / `Prefs.customMeta(context, id)` (Android) lookup
  returns `{poseProfile, hasFace, gender}` in one read of the custom-characters store, consumed at
  render/decision time. Returns null for built-ins/unknown ids, and callers fall back to "use the
  character's own id, no face, no accessory" in that case - built-in characters were deliberately
  left untouched by this whole feature.

**Face/emotion:** drawn on top of whichever bone the renderer already identified as "the head"
during its normal draw loop (the single Circle node, or the centroid of the final curveRadius
chain) - captured as a byproduct of existing rendering code instead of adding separate head
detection. 6-expression vocabulary (`neutral/happy/sad/angry/surprised/love`) shared as
`renderer/face.js` (PC) / `overlay/FaceRenderer.kt` (Android). Emotion is a new AI action
`set_emotion`, fully independent of body pose (`CharacterState.faceEmotion`/`.setFaceEmotion()`,
not touched by `loopEmotion`/moving/falling) - a character can be sitting and happy at once.
`set_custom_animation` keyframes also accept an optional per-frame `face`.

**Gender:** `masculino`/`femenino`/`otro`, prepended as a fixed line ("Tu genero es X.") ahead of
the character's own personality text in the AI prompt - a creation-time fact, not something
`define_personality` can override. **Corrected in v2.14/v1.14:** the head accessory (bow) used to
be auto-drawn for `femenino` only, with no opt-out and no alternative - user pushback ("saca el
moño y pon que elijas entre pelo y moño") decoupled it into its own independent `accessory` field
(`none`/`hair`/`bow`, any gender) - `renderer/face.js`'s `drawAccessory()` / Android's
`FaceRenderer.drawAccessory()`, same head-anchor drawing pass as before.

**Color picker:** the 8-swatch shared palette (`PALETTE` in `customCharacters.js` / `Palette.kt`)
plus an "advanced" full picker - native `<input type="color">` on PC, three RGB `SeekBar`s on
Android (no built-in HSV picker widget without a third-party dep).

**Removed in the same batch:** `move_random` as an AI-choosable action (schema entry gone on both
platforms) - its old jobs (anti-repeat escape hatch, error-turn fallback) now just use `wait`,
since a pre-existing autonomous-wander mechanism (`IDLE_WALK_TIMEOUT_MS` in both
`characterState.js`/`CharacterState.kt`) already walks an idle character somewhere on its own
without needing an explicit forced action.

**Real bug found via live user testing (v2.6→v2.7 fix):** shipping `set_emotion` in
`actions.schema.js`/`ActionsSchema.kt` was not enough to make the AI ever use it - the free-text
system prompts (duplicated across `geminiProvider.js`/`groqProvider.js`/`openaiProvider.js`/
`ollamaProvider.js`/`anthropicProvider.js`/`openrouterProvider.js` on PC, `GeminiClient.kt` on
Android) still said "no tenes cara" and never mentioned the new action in prose. A tool's schema
description alone doesn't reliably drive spontaneous usage - the narrative system prompt needs its
own explicit mention too. **Lesson for next time a new action is added to this codebase: search
for and update the free-text system prompt in EVERY provider file, not just `actions.schema.js`'s
`desc` field** - this project duplicates that prompt per-provider rather than sharing one.

**Redesigned in v2.8/v1.8 after more live testing** ("tengo que decirle que las ponga", "las caras
tardan en ponerse", "que cree su propia cara"):
- Root cause of the delay: only ONE tool call executes per AI decision turn. If a character wanted
  to both say something and show a reaction, it had to pick one and wait a full separate turn for
  the other - `set_emotion` was competing for that one slot against everything else. Fixed by
  making EVERY action's schema accept optional `eyes`/`mouth` params that ride along with whatever
  else it does that turn (PC: `actions.schema.js` injects them into every action's `params` via a
  loop, skipping `set_emotion` itself; Android: `ActionsSchema.kt`'s `withFaceParams()` helper).
  `agentLoop.js`/`OverlayService.kt` capture `eyes`/`mouth` from the ORIGINAL decision **before**
  the anti-repeat guard can swap the tool out, so a forced `wait`/`walk_to` doesn't silently drop a
  pending face reaction.
- "Cara propia" reinterpreted as two independent axes instead of one bundled enum: `eyeStyle` ∈
  {normal, wide, angry, heart} and `mouthStyle` ∈ {neutral, smile, frown, open, angry}, mixed
  freely (e.g. wide eyes + a frown) rather than only 6 fixed combos. `setFace(eyes, mouth)` /
  `CharacterState.setFace()` - either param can be omitted/invalid to leave that axis untouched
  (important for the piggyback case: an action that only sends `eyes` shouldn't reset the mouth).
- Also pushed all 6 PC provider prompts + Android's GeminiClient.kt to actually encourage
  `set_custom_animation` usage ("usalo seguido, no de vez en cuando") - it existed but was almost
  never used, same class of problem as `set_emotion` before its first fix (schema alone doesn't
  drive usage, the narrative prompt has to ask for it explicitly and repeatedly).

**v2.15/v1.14 added an editor for existing custom characters** (never built-ins) - reuses the same
create-character screen pre-filled with current values, saves in place under the same id
(`customCharacters.update()` / `Prefs.updateCustomCharacter()`) instead of forcing delete-and-
recreate. Built after the user hit that exact friction twice in one session over the accessory/face
changes above.

See [[project_overview]] for the release history and [[windows_dev_workflow]] for build commands.
