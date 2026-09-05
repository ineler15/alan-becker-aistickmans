---
name: project-overview
description: What the stickman project is and where it lives on GitHub
metadata: 
  node_type: memory
  type: reference
  originSessionId: 799e5adb-1d49-411a-8eb8-27c00f336635
  modified: 2026-08-24T02:05:41.820Z
---

"Alan Becker AIStickmans" - a desktop Electron app that drives Alan-Becker-style stick figure
characters (via a Shimeji-ee Java engine) with real LLM-backed decision loops, plus an Android
companion app that adds "tablet" characters over LAN, and a live web view ("la casa de los
stickmans") showing everyone's position.

Public GitHub repo (created 2026-08-15, owner `ineler15`):
https://github.com/ineler15/alan-becker-aistickmans

See [[gemini-quota-per-project]] for the Gemini free-tier quota model learned while debugging
characters that "wouldn't respond", and [[windows-dev-workflow]] for how to restart/rebuild this
project locally.

As of 2026-08-23, latest releases: Windows v2.17 (installed copy updated to match), Android v1.16.
v2.17/v1.16 added a 0-100 affection SLIDER next to the partner dropdown (`perCharacterAffection` in
pcSettings.js / `Prefs.affectionFor` on Android) - user explicitly wanted a "barra" to dial
intensity, not just the binary partner on/off - `affectionPhrase(name, level)` in
`agentLoop.js`/`OverlayService.kt` picks one of 5 tiered phrases (barely-registers through
profoundly-in-love) instead of one fixed "es tu pareja" line. **KNOWN BUG, not yet fixed**: in
`pcSettings.js`'s `applyPartners()`, `Number(rawLevel) || 50` treats an explicit slider value of
`0` as falsy and silently upgrades it to 50 - a character dialed all the way down to "no me
importa nada" still gets the 40-59% tier's affectionate behavior. Fix is a plain
`undefined`/`null`/`''` check instead of `||`; diagnosed but not yet shipped when the session ended
(user said "guarda y bye" mid-fix). Same session also fixed a real duplicate-paste bug in API key
fields (pre-filled value + re-paste without clearing = silently appended, happened for real up to
3x) via a `collapseRepeatedKey()` guard in `renderer/settings.js`, applied both on display and on
save. v2.16 (superseded) added v2.16/v1.15 fixed why the AI
almost never used `set_custom_animation` well: its bone angles were raw ABSOLUTE degrees in each
character's own bizarre rig coordinate system (Red's arm1 rest is -207.92, TCO's is -143.3), so the
exact same gesture needed completely different numbers per character with zero calibration anchor
in the schema description - genuinely hard for a model to reason about. Changed `customPose()` in
both `renderer/poseLibrary.js` and Android's `PoseLibrary.kt` to treat angles as DELTAS from that
character's own rest pose (the same convention every built-in pose like sit/fall/trip/tired already
used internally via `rest.leg1 - 56.8`-style math) and added calibrated example deltas (lifted
directly from those verified poses) into the `set_custom_animation` schema description on both
platforms as reference points. User's own framing: "que para la IA sea mas facil crear posiciones
para ellas" (make it easier for the AI to invent its own poses), NOT a user-facing pose editor -
that alternate interpretation was floated and explicitly turned down first. v2.15/v1.14 added an "Editar"
button for existing CUSTOM characters only (explicitly not the built-ins, per user request) -
reuses the same create-character screen pre-filled with current values (name/color/head
model/hasFace/gender/accessory), saves in place under the same id (`customCharacters.update()` on
PC, `Prefs.updateCustomCharacter()` on Android) instead of requiring delete-and-recreate. Built
because the user hit that exact friction twice in one session (wanting to toggle Rosa/pink's face
and accessory after already creating her). v2.14/v1.13 decoupled the head
accessory from gender: the bow was previously auto-drawn for any "femenino" character with no way
to opt out or choose anything else - now the creator has an independent Ninguno/Pelo/Moño choice
(new `accessory` field on the custom-character record, `renderer/face.js`'s `drawAccessory()` /
Android's `FaceRenderer.drawAccessory()` replacing the old gender-gated `drawGenderAccessory()`).
v2.13/v1.12 made `src/ui/pointerHighlight.js`'s mouse/click indicator persistent per character
(one window per character, moved in place instead of recreated/flashed, colored per-character,
hidden after a short idle period) rather than a 500ms flash, and added partner-location awareness -
`agentLoop.js`/`OverlayService.kt` now restate the partner's live position directly next to "this
is your partner" instead of leaving the AI to cross-reference the general peers list on its own.
v2.12/v1.11 added an explicit
"pareja" (partner) field per character (Configuracion on PC, a Spinner per row on Android) -
stronger/more reliable than the emergent crush behavior, since the user reported pairing two
specific characters ("Rosa"/id `pink` and Red) via the emergent system didn't reliably read as
affectionate; also fixed `sanitizeId()`/`addCustomCharacter()` doing case-SENSITIVE collision
checks against existing ids, discovered because the user actually had a custom character with id
"red" (lowercase) alongside built-in "Red" - Windows resolves `personality-Red.json` and
`personality-red.json` to the same file, so they'd have silently shared/overwritten personality
and history data had both ever been enabled together (they weren't - no active corruption, but the
collision itself was live and is now fixed). Investigating that also found the user's actual
"Rosa" is a custom character literally named "pink" (displayName "pink", hasFace was false - user
had it turned on since a felt crush would need eyes to show heart-eyes), and that "se tratan re
mal" between Red/Rosa was a misread: their real chat history is a friendly game of tag with playful
banter, no hostility, no `remember` entries about each other yet from either side. v2.11/v1.10 added: characters can
organically develop affection ("crush") for a peer via existing tools (remember/
define_personality/say/set_emotion eyes:heart) - user asked to restrict this to opposite-gender
pairs only and that was declined (encoding a same-gender exclusion rule is a values decision, not
a technical one - see [[feedback_workflow_style]] for how this was handled); a real bug fix where
geminiProvider.js/Android's GeminiClient.kt unconditionally told every character "you're male",
contradicting the per-character gender line added earlier for custom characters; and a global
15s cooldown on move_mouse/click/tap after the user reported the real cursor "iba loco" with
several characters independently grabbing it on their own cycles. v2.9/v1.9 added an opt-in
(off-by-default) toggle gating real mouse/touch control (move_mouse/click/tap on PC, tap on
Android), with a colored ring/dot (tinted per-character) flashing at the action point so it's
clear which character is acting - real click still briefly moves the actual cursor (message-based
"fake clicks" that don't touch the real cursor are unreliable/ignored by most modern apps,
confirmed this session). v2.10 (PC-only) found and fixed that `ride_mouse` had been a pure no-op
in the JS engine since the Shimeji replacement - it was in the schema and providers were told to
use it, but nothing implemented it; now it polls the real cursor position and follows with a
weighted lerp instead of snapping onto it. See [[custom_character_creator]] for the rest of
2026-08-23's session (character creator, face/emotion system, move_random removal). v2.8/v1.8 redesigned the face
system (independent eyes/mouth axes instead of one bundled emotion enum) and fixed the real
architectural cause of face reactions feeling delayed - only one AI tool call executes per
decision turn, so every action's schema now accepts optional eyes/mouth params that ride along
with whatever else it does that turn instead of needing a dedicated turn just for the face - see
[[custom_character_creator]]. v2.7/v1.7 fixed set_emotion never
actually being used (the narrative system prompt, not just the tool's schema description, needed
updating - see [[custom_character_creator]]) and added a 🍪 "give a snack" button to the chat UI
on both platforms (sends a canned line through the existing chat pipeline, character reacts in its
own voice via say/set_emotion - no new backend). v2.4/v1.4 introduced "crear tu
propio stickman" (custom character creator: name/color/head-model) plus, on Android, a real fix
for the keyboard-hide bug (detects the IME via the accessibility service's window list instead of
measuring from the view it was hiding, so it un-hides again and works in any foreground app).
v2.5/v1.5 fixed two bugs in that same character creator: (1) the "hollow head" model was just a
hollow flag on Red's filled-Circle head (wrong look - the real hollow look, TCO/TSC/TDL, is a ring
of curved bones stroked as one path, no Circle node at all - hollow now clones TCO's rig instead);
(2) custom characters stood completely frozen because PoseLibrary's angle tables are keyed by
built-in id only - fixed by telling PoseLibrary which built-in profile (Red/TCO) a custom
character's rig was cloned from. Lesson: when cloning an existing rig/animation system for a new
"custom" entity, check whether ANY subsystem keys off the exact built-in id rather than the
underlying topology. v2.6/v1.6 added: an optional face (eyes+mouth drawn on the head, either head
model) with its own emotion (new `set_emotion` action, independent of body pose - reused the same
"which node/chain is the head" detection already in the renderer's draw loop rather than adding
separate head-lookup logic); gender (masculino/femenino/otro - femenino adds a bow accessory, and
is surfaced to the AI as a fixed personality fact); `set_custom_animation` keyframes can now carry
an optional per-frame facial expression too; an advanced color picker (native color input on PC,
RGB sliders on Android) alongside the 8-swatch palette; and `move_random` was removed as an
AI-selectable action (anti-repeat/error-fallback now use `wait` instead) since the pre-existing
autonomous-wander mechanism already prevents characters from standing frozen forever on its own.
PC also got v2.3 (native JS rig renderer fully replacing Shimeji-ee, per-character AI provider,
sleep/idle system, several packaged-build/window fixes) folded into v2.4's release notes since it
had never been cut as its own release. Each feature session gets its own tagged GitHub release
with the built artifact attached (v0.1.0 through v2.6 for Windows, v1.0 through v1.6 for Android) -
old releases were kept, never deleted/overwritten. Windows installer is built with `npm run dist`
(release/*.exe); Android with `./gradlew.bat assembleDebug` (JAVA_HOME must point at a JDK 11+,
see [[windows-dev-workflow]]) - both uploaded via `gh release create <tag> <artifact> --title ... --notes ...`.
