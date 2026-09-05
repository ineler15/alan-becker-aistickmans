---
name: android-multi-provider-mode
description: "In-progress Android feature letting each character/node pick its own AI provider+model, not just Gemini"
metadata: 
  node_type: memory
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-23T18:17:28.350Z
---

Added a "per-node model" mode to the Android app: each stickman character (a "node", same concept as the peer nodes in the PC group chat at `src/net/home.html`) can use a different AI provider/model instead of being locked to Gemini.

- `Prefs.kt`: new `PROVIDERS = listOf("gemini", "openai", "groq", "openrouter")` (anthropic/ollama excluded — don't fit the mobile client's OpenAI-compatible tool-call shape or require localhost). Added `sharedProvider`/`setSharedProvider` (global default) and `providerFor`/`perCharacterProvider`/`setProviderFor` (per-character override, falls back to shared when blank).
- `GeminiClient.kt`: `ENDPOINT`/`MODEL` constants replaced with `endpointFor(provider)`/`modelFor(provider)`; `decide(...)` now takes a `provider` param. Default models per provider: gemini→`gemini-3.5-flash-lite`, openai→`gpt-4o-mini`, groq→`qwen/qwen3.6-27b` (with `reasoning_effort: none`), openrouter→`anthropic/claude-sonnet-4.5`.
- `MainActivity.kt` / `activity_main.xml`: added a shared-provider `Spinner` plus a per-character provider `Spinner` next to each character's API-key field ("(compartido)" = use the shared default).
- `OverlayService.kt`: passes `Prefs.providerFor(this, characterId)` into `GeminiClient.decide`.

**Why:** mirrors the desktop app's existing multi-provider support (`geminiProvider.js`/`openaiProvider.js`/`groqProvider.js`/`openrouterProvider.js`), bringing provider choice to Android per-character instead of hardcoding Gemini.

**How to apply:** This was mid-progress and got interrupted (uncommitted changes as of 2026-08-16, not yet committed). Before continuing, check `git status`/`git diff` on these files to see current state — don't assume it's finished or committed. Related: [[project_overview]], [[gemini_quota_per_project]].

**Update 2026-08-23: confirmed committed and shipped.** `git status` is clean and this logic
(`Prefs.providerFor`, per-character Spinners, etc.) is live in the current codebase - the
2026-08-16 "uncommitted, mid-progress" note above is stale, kept only for the original design
rationale.
