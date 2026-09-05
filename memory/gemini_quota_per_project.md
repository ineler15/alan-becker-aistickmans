---
name: gemini-quota-per-project
description: "How Gemini free-tier quota behaves across this project's per-character API keys"
metadata: 
  node_type: memory
  type: project
  originSessionId: 799e5adb-1d49-411a-8eb8-27c00f336635
  modified: 2026-08-23T03:19:14.086Z
---

Each AI-driven stickman character can have its own `GEMINI_API_KEY_<ID>` in `.env` (falls back to
the shared `GEMINI_API_KEY`). The free tier is 500 `generate_content` requests/day, and that quota
is tied to the underlying Google Cloud **project**, not to the key string itself - keys minted
under the same GCP project share one 500/day pool.

**Why this matters:** with `TICK_INTERVAL_SECONDS` set low (e.g. 6s) and ~10 characters ticking,
a shared-project quota gets exhausted within minutes, and every character whose key shares that
project silently falls back to `move_random` (see agentLoop.js's catch-block fallback) instead of
real AI - they just walk around aimlessly, which reads as "not responding."

**How to apply:** to keep several characters genuinely AI-driven at once, each needs a key from a
*separate* GCP project (the user's approach: create one Google Cloud project per character and
mint that character's key there). A quick way to test a key's *auth* validity (not quota) without
burning generate_content quota is `GET https://generativelanguage.googleapis.com/v1beta/openai/models`
with `Authorization: Bearer <key>` - returns 200 even when the generate_content quota is exhausted,
so it only proves the key is valid, not that quota remains.

**Known quirk fixed in `src/ai/geminiProvider.js`:** some error responses (e.g. 429 quota-exceeded)
come back wrapped in a JSON array (`[{error:...}]`) instead of a bare object (`{error:...}`) - the
original code only checked `data.error`, so these errors went undetected and silently fell through
to the `wait` fallback instead of surfacing. Now handled by checking `Array.isArray(data)` first.

As of 2026-08-15: Red, Orange, Green, Blue, Yellow have working per-project keys. Purple and the
shared `GEMINI_API_KEY` (used by AI, TCO, TDL, victim) were still quota-exhausted, so those five
were temporarily removed from the active `CHARACTERS` list in `src/characters.js` (commented
with instructions to re-add once they have fresh per-project keys) rather than left ticking into
a permanent `move_random` fallback.

**Android's CameraX has the same "silently does nothing" failure shape:** `ProcessCameraProvider.bindToLifecycle`
must run on the main thread. `OverlayService`'s AI loop runs on `Dispatchers.Default`, so calling
it there threw and was swallowed by a try/catch, meaning the camera feature was wired in but never
actually activated until `CameraCapture.captureBase64()` was wrapped in `withContext(Dispatchers.Main)`.
General lesson for this project: several "the feature does nothing" reports here turned out to be
a caught exception on the wrong thread/shape rather than a logic bug - check error handling paths
for silently-swallowed exceptions first.

**Correction (2026-08-22): `AQ.Ab8RN6...` IS a valid, working Gemini key format - the 2026-08-16
note below was wrong.** Re-tested live: `AQ.Ab8RN6...` keys from this project's `.env`/pc_settings
work fine (200, real completion) against the actual endpoint this codebase calls -
`https://generativelanguage.googleapis.com/v1beta/openai/chat/completions` (OpenAI-compat shim)
with `Authorization: Bearer <key>` (see `src/ai/geminiProvider.js`). They fail with `401
API_KEY_SERVICE_BLOCKED` against the *native* `v1beta/models/<model>:generateContent` endpoint -
that's a different auth path, not proof the key is invalid. **Don't test a key's validity against
an endpoint the actual code doesn't call** - check which URL/auth-header shape `geminiProvider.js`
(or the Android equivalent) really uses before drawing conclusions from a probe.

**Original 2026-08-16 note, now believed to be a false diagnosis:** user reported several Android
characters "not responding" even after entering an API key; logcat showed `gemini API error
(401): Invalid API Key`; every per-character `api_key_*` started `AQ.Ab8RN6...`, which was
concluded to be an invalid format vs. real keys starting `AIzaSy...` (minted at
https://aistudio.google.com/apikey). Given the correction above, that 401 was more likely a
different root cause (wrong endpoint/auth shape on Android's side, quota, or something else
entirely) that happened to correlate with the key prefix - re-investigate if this resurfaces
instead of trusting the old conclusion. See [[android_idle_sway_and_repetition]] for the full
incident (also surfaced a real keyboard-hide bug, fixed the same session).
