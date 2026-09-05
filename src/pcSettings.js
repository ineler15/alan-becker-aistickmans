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
      // Per-character provider override ('' / absent = use the shared `provider` above),
      // mirrors Android's per-node provider Spinner (Prefs.kt's providerFor/setProviderFor).
      perCharacterProvider: {},
      // Explicit "pareja" - a character id this one has a designated partner/love interest in,
      // surfaced to the AI as a strong personality fact (see applyPartners() below) rather than
      // left to the emergent/optional "crush" behavior in the system prompts. One-directional -
      // set it on both characters for a mutual relationship.
      perCharacterPartner: {},
      // How strong that affection is, 0-100, user-controlled via a slider next to the partner
      // dropdown in settings.js - meaningless without a perCharacterPartner target set too.
      perCharacterAffection: {},
      // Extra context written by the USER for a character (editable via the "Contexto" button in
      // the settings window) - injected into the AI prompt SEPARATE from the automatic context
      // (history/peers/status/etc.). Same map shape as perCharacterPartner: id -> text.
      perCharacterContext: {},
      // No settings file yet (first run) - default to the same subset that used to be
      // hardcoded in characters.js, so behavior is unchanged until the user touches a checkbox.
      enabledIds: CHARACTERS.map((c) => c.id),
      // Off by default - letting an AI move the real mouse/click on its own is a meaningfully
      // bigger deal than any of the sandboxed actions (StickPaint, walking, etc.), so it needs an
      // explicit opt-in rather than working out of the box like everything else.
      allowMouseControl: false,
      // Where the AI should pay more attention each decision turn: 'camera' (the webcam frame)
      // or 'mouse' (the cursor's position, tracked via input.getMousePosition). Global, not per
      // character - it only changes the emphasis in the prompt, both signals keep coming in.
      attentionFocus: 'camera',
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

// Stamps each character's designated affection target (if any) and how strong that affection is
// (a user-controlled 0-100 slider, not just on/off) onto its CHARACTERS.ALL entry, same "mutate in
// place, every module sees it live" pattern as the rest of this file - agentLoop.js reads
// character.partnerId/affectionLevel to build the personality line. Level is meaningless without a
// target, so it's only stamped when partnerId is actually set.
function applyPartners(settings) {
  for (const character of CHARACTERS.ALL) {
    character.partnerId = (settings.perCharacterPartner || {})[character.id] || null;
    const rawLevel = (settings.perCharacterAffection || {})[character.id];
    character.affectionLevel = character.partnerId ? Math.min(100, Math.max(0, Number(rawLevel) || 50)) : 0;
  }
}

// Stamps each character's USER-WRITTEN extra context (the "Contexto" button in the settings
// window) onto its CHARACTERS.ALL entry, same "mutate in place, every module sees it live" pattern
// as applyPartners - agentLoop.js reads character.userContext to build the extraContext line it
// injects SEPARATE from the automatic context. This is distinct from the context a character
// writes for itself via the set_context action (persisted in workspace context-<id>.json).
function applyContexts(settings) {
  for (const character of CHARACTERS.ALL) {
    character.userContext = ((settings.perCharacterContext || {})[character.id] || '').trim();
  }
}

function save(settings) {
  fs.mkdirSync(config.workspaceDir, { recursive: true });
  fs.writeFileSync(SETTINGS_PATH, JSON.stringify(settings, null, 2));
}

const KEY_ENV_VAR = {
  anthropic: 'ANTHROPIC_API_KEY',
  gemini: 'GEMINI_API_KEY',
  openai: 'OPENAI_API_KEY',
  groq: 'GROQ_API_KEY',
  openrouter: 'OPENROUTER_API_KEY',
};

// Applies saved settings into process.env BEFORE startCharacterEngine()/agentLoop.start() are called,
// so the existing config.js/provider.js machinery (which reads process.env fresh on every call,
// see config.gemini.apiKeyFor) picks them up with no changes to that code at all.
function applyToEnv(settings) {
  process.env.ALLOW_MOUSE_CONTROL = settings.allowMouseControl ? '1' : '0';
  process.env.ATTENTION_FOCUS = settings.attentionFocus === 'mouse' ? 'mouse' : 'camera';
  if (settings.provider) process.env.AI_PROVIDER = settings.provider;
  const keyEnvVar = KEY_ENV_VAR[settings.provider];
  if (keyEnvVar && settings.sharedApiKey) process.env[keyEnvVar] = settings.sharedApiKey;

  // Per-character provider override (config.providerFor/getProvider read this) - '' or absent
  // means that character just uses the shared `provider` above.
  for (const [id, provider] of Object.entries(settings.perCharacterProvider || {})) {
    if (provider) process.env[`AI_PROVIDER_${id.toUpperCase()}`] = provider;
  }

  // Per-character key override, keyed to THAT character's own effective provider (its override,
  // falling back to the shared one) - not always gemini, now that providers can differ per
  // character too. See config.<provider>.apiKeyFor(characterId).
  for (const [id, key] of Object.entries(settings.perCharacterKeys || {})) {
    if (!key) continue;
    const effectiveProvider = (settings.perCharacterProvider || {})[id] || settings.provider;
    const envVar = KEY_ENV_VAR[effectiveProvider];
    if (envVar) process.env[`${envVar}_${id.toUpperCase()}`] = key;
  }
}

module.exports = {
  PROVIDERS,
  CHARACTERS,
  load,
  save,
  applyToEnv,
  applyEnabledCharacters,
  applyPartners,
  applyContexts,
  SETTINGS_PATH,
};
