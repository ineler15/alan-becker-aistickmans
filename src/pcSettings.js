const fs = require('fs');
const path = require('path');
const config = require('./config');
const CHARACTERS = require('./characters');

const SETTINGS_PATH = path.join(config.workspaceDir, 'pc_settings.json');

// Providers with a real API key field in config.js - anthropic is config's own default so it's
// included too; ollama runs local with no key, so it's left out of this list (nothing to type).
const PROVIDERS = ['anthropic', 'gemini', 'openai', 'groq', 'openrouter'];

function load() {
  try {
    return JSON.parse(fs.readFileSync(SETTINGS_PATH, 'utf8'));
  } catch (e) {
    return {
      provider: config.aiProvider,
      sharedApiKey: '',
      perCharacterKeys: {},
      // No settings file yet (first run) - default to the same subset that used to be
      // hardcoded in characters.js, so behavior is unchanged until the user touches a checkbox.
      enabledIds: CHARACTERS.map((c) => c.id),
    };
  }
}

// Mutates the shared CHARACTERS array in place (not a reassignment) so every module that
// already did `require('../characters')` - agentLoop, peerServer, main.js's tray menu - sees
// the update on their next access, without each of them needing to re-require anything.
function applyEnabledCharacters(settings) {
  const enabledIds = settings.enabledIds && settings.enabledIds.length ? settings.enabledIds : CHARACTERS.ALL.map((c) => c.id);
  const chosen = enabledIds.map((id) => CHARACTERS.ALL.find((c) => c.id === id)).filter(Boolean);
  CHARACTERS.length = 0;
  CHARACTERS.push(...chosen);
}

function save(settings) {
  fs.mkdirSync(config.workspaceDir, { recursive: true });
  fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2));
}

// Applies saved settings into process.env BEFORE startCharacterEngine()/agentLoop.start() are called,
// so the existing config.js/provider.js machinery (which reads process.env fresh on every call,
// see config.gemini.apiKeyFor) picks them up with no changes to that code at all.
function applyToEnv(settings) {
  if (settings.provider) process.env.AI_PROVIDER = settings.provider;
  const keyEnvVar = {
    anthropic: 'ANTHROPIC_API_KEY',
    gemini: 'GEMINI_API_KEY',
    openai: 'OPENAI_API_KEY',
    groq: 'GROQ_API_KEY',
    openrouter: 'OPENROUTER_API_KEY',
  }[settings.provider];
  if (keyEnvVar && settings.sharedApiKey) process.env[keyEnvVar] = settings.sharedApiKey;
  // Per-character key override only actually does anything for gemini today - see
  // config.gemini.apiKeyFor(characterId), the only provider with that per-character lookup.
  if (settings.provider === 'gemini') {
    for (const [id, key] of Object.entries(settings.perCharacterKeys || {})) {
      if (key) process.env[`GEMINI_API_KEY_${id.toUpperCase()}`] = key;
    }
  }
}

module.exports = {
  PROVIDERS,
  CHARACTERS,
  load,
  save,
  applyToEnv,
  applyEnabledCharacters,
  SETTINGS_PATH,
};
