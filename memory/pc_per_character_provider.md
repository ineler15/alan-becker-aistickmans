---
name: pc-per-character-provider
description: "PC now supports per-character AI provider selection, ported from Android's per-node model mode"
metadata: 
  node_type: memory
  type: project
  modified: 2026-08-23T18:17:21.069Z
  originSessionId: 6696e20e-ecf3-47c5-a836-ac00a1440781
---

Ported Android's per-node provider selection ([[android_multi_provider_mode]]) to PC (2026-08-22).
**Update 2026-08-23: confirmed committed** (was part of commit `e1f8e7c` "Fix packaged-build
ENOTDIR crash and add per-character AI providers on PC" or thereabouts) - `git status` was clean
before this note was written, so this has landed on `master`, not just local uncommitted changes.

**What changed:**
- `src/config.js`: added `providerFor(characterId)` (reads `AI_PROVIDER_<ID>`, falls back to the
  shared `aiProvider`), and generalized `apiKeyFor(characterId)` (previously gemini-only) to
  anthropic/openai/groq/openrouter too, each reading `<PROVIDER>_API_KEY_<ID>`.
- `src/ai/provider.js`'s `getProvider(characterId)` now resolves via `config.providerFor()`
  instead of always the shared `config.aiProvider`.
- Every provider file (`openaiProvider.js`, `groqProvider.js`, `openrouterProvider.js`,
  `anthropicProvider.js`) now resolves its key via `apiKeyFor(characterId)` when present, mirroring
  what `geminiProvider.js` already did. `anthropicProvider.js` had to stop building its `Anthropic`
  client once at module load (baked in the shared key forever) - now builds it per-call with the
  resolved key.
- `src/pcSettings.js`: added `perCharacterProvider` to the settings shape; `applyToEnv()` now sets
  `AI_PROVIDER_<ID>` per character, and resolves each character's key env var against **that
  character's own effective provider** (its override, or the shared one) instead of always
  assuming gemini.
- `renderer/settings.html`/`settings.js`: added a per-character provider `<select>` next to each
  character's API-key field, with a `(compartido)` first option meaning "use the shared default" -
  same UX as Android's Spinner.

**How to apply:** logic verified with a standalone `node -e` script (mocking the `electron` module)
before touching the UI - `config.providerFor('Red')`/`apiKeyFor('Red')` correctly picked per-character
overrides and fell back to shared otherwise. Not yet visually confirmed live in the running app
(settings window screenshot, actual character behaving under a non-default provider).
