---
name: pc-settings-window
description: "PC app now shows a pre-launch settings window (provider, API keys, which characters appear) instead of requiring .env edits"
metadata: 
  node_type: memory
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-16T20:18:10.883Z
---

Added a settings window shown before the Shimeji figures appear on PC (`renderer/settings.html`/`settings.js`, `src/pcSettings.js`), mirroring the Android app's MainActivity: a shared AI provider dropdown, shared API key, and a checkbox + optional per-character API key for every character. `startShimeji()`/`agentLoop.start()` only run after the window's "Guardar y continuar" fires `stickman:save-settings`.

Two things had to change for this to actually work, both non-obvious:

1. **`config.js`'s `aiProvider`/`apiKey`/`model` fields became getters.** They used to be plain values computed once when `main.js` first required `config.js` (at the top of the file, before the settings window even exists) - so anything the settings window later wrote to `process.env` was silently ignored. Getters re-read `process.env` on every access, same as `config.gemini.apiKeyFor()` already did.
2. **Checking/unchecking a character in the window did nothing by itself.** The actual Java Shimeji engine decides which figures get drawn from its OWN config - `ActiveShimeji=Name1/Name2/...` in `conf/settings.properties` next to the external jar (`C:\Users\<user>\AppData\Local\AlanBeckersStickfigures\conf\settings.properties`) - completely separate from `src/characters.js`'s `CHARACTERS` array, which only controls who the JS side's AI loop drives. Unchecking someone in the window only stopped their AI, Shimeji kept drawing them anyway. Fixed via `pcSettings.applyActiveShimeji()`, which rewrites that line before `startShimeji()` runs.

`characters.js` now exports a mutable array (`CHARACTERS`) that gets its contents replaced in place (`.length = 0; .push(...)`) rather than reassigned, plus a non-enumerable-in-spirit `.ALL` property holding every possible character - this lets `pcSettings.applyEnabledCharacters()` change what every other already-`require()`'d module (agentLoop, peerServer, main.js's tray menu) sees on their next access, without a bigger refactor to make every consumer call a function instead of reading the array directly.

Also: the "AI" placeholder character was removed entirely per user request (was previously excluded in `characters.js` alongside Purple/TCO/TDL/victim due to exhausted shared quota - those four are back now since the user can give them their own key or leave them unchecked instead).

**How to apply:** Verified end-to-end on the user's machine - the app launches, shows the window, and (after also fixing the `ActiveShimeji` line) only the checked characters actually appear. Changing the character list requires a full restart (the settings only get applied once, right before `startShimeji()`) - there's no live-reload path if Shimeji/agentLoop are already running.
