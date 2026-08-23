const path = require('path');
const fs = require('fs');
const { app } = require('electron');
require('dotenv').config();

const ROOT_DIR = path.join(__dirname, '..');
// In a packaged build, ROOT_DIR resolves to inside app.asar - a single read-only file, not a
// real directory, so creating a workspace folder "inside" it fails with ENOTDIR. Default to
// Electron's writable per-user data dir there instead; dev (unpackaged) keeps using the
// project folder like before. A relative WORKSPACE_DIR override (e.g. from a dev .env file
// that predates this fix) must resolve against that same writable base when packaged, not
// against ROOT_DIR, or it silently re-creates the ENOTDIR crash this was fixing.
const WORKSPACE_BASE_DIR = app.isPackaged ? app.getPath('userData') : ROOT_DIR;
const WORKSPACE_DIR = process.env.WORKSPACE_DIR
  ? path.resolve(WORKSPACE_BASE_DIR, process.env.WORKSPACE_DIR)
  : path.join(WORKSPACE_BASE_DIR, 'workspace');

if (!fs.existsSync(WORKSPACE_DIR)) {
  fs.mkdirSync(WORKSPACE_DIR, { recursive: true });
}

const config = {
  rootDir: ROOT_DIR,
  workspaceDir: WORKSPACE_DIR,
  // aiProvider/apiKey/etc. below are getters, not plain values, so the new pre-launch settings
  // window (src/pcSettings.js) can set process.env.AI_PROVIDER/*_API_KEY right before
  // startShimeji()/agentLoop.start() and have it actually take effect - a plain value computed
  // once at require() time (when main.js first imports this module) would freeze in whatever
  // .env already had, ignoring anything set afterwards.
  get aiProvider() {
    return (process.env.AI_PROVIDER || 'anthropic').toLowerCase();
  },
  // Per-character provider override (AI_PROVIDER_<ID>), same idea as gemini.apiKeyFor below -
  // lets each character run on a different model instead of sharing one for the whole app.
  providerFor(characterId) {
    const perCharacter = characterId && process.env[`AI_PROVIDER_${characterId.toUpperCase()}`];
    return (perCharacter || this.aiProvider).toLowerCase();
  },
  anthropic: {
    get apiKey() {
      return process.env.ANTHROPIC_API_KEY || '';
    },
    get model() {
      return process.env.ANTHROPIC_MODEL || 'claude-sonnet-4-5';
    },
    apiKeyFor(characterId) {
      const perCharacter = process.env[`ANTHROPIC_API_KEY_${characterId.toUpperCase()}`];
      return perCharacter || this.apiKey;
    },
  },
  openrouter: {
    get apiKey() {
      return process.env.OPENROUTER_API_KEY || '';
    },
    get model() {
      return process.env.OPENROUTER_MODEL || 'anthropic/claude-sonnet-4.5';
    },
    apiKeyFor(characterId) {
      const perCharacter = process.env[`OPENROUTER_API_KEY_${characterId.toUpperCase()}`];
      return perCharacter || this.apiKey;
    },
  },
  groq: {
    get apiKey() {
      return process.env.GROQ_API_KEY || '';
    },
    get model() {
      return process.env.GROQ_MODEL || 'qwen/qwen3.6-27b';
    },
    apiKeyFor(characterId) {
      const perCharacter = process.env[`GROQ_API_KEY_${characterId.toUpperCase()}`];
      return perCharacter || this.apiKey;
    },
  },
  openai: {
    get apiKey() {
      return process.env.OPENAI_API_KEY || '';
    },
    get model() {
      return process.env.OPENAI_MODEL || 'gpt-4o-mini';
    },
    apiKeyFor(characterId) {
      const perCharacter = process.env[`OPENAI_API_KEY_${characterId.toUpperCase()}`];
      return perCharacter || this.apiKey;
    },
  },
  gemini: {
    get apiKey() {
      return process.env.GEMINI_API_KEY || '';
    },
    get model() {
      return process.env.GEMINI_MODEL || 'gemini-3.5-flash-lite';
    },
    // Optional per-character keys (GEMINI_API_KEY_<ID>, e.g. GEMINI_API_KEY_RED) so each
    // friend draws from its own free-tier quota instead of all sharing one. Falls back to
    // the shared key above when a character doesn't have its own.
    apiKeyFor(characterId) {
      const perCharacter = process.env[`GEMINI_API_KEY_${characterId.toUpperCase()}`];
      return perCharacter || this.apiKey;
    },
  },
  ollama: {
    get baseUrl() {
      return process.env.OLLAMA_BASE_URL || 'http://localhost:11434';
    },
    get model() {
      return process.env.OLLAMA_MODEL || 'gemma4:12b';
    },
  },
  tickIntervalMs: (Number(process.env.TICK_INTERVAL_SECONDS) || 12) * 1000,
  // Ollama runs local with no quota but is much slower per call on this machine; cloud
  // providers (groq/anthropic/openrouter) are fast but groq's free tier needs spacing
  // between characters to not blow its per-minute token budget.
  get decideTimeoutMs() {
    return this.aiProvider === 'ollama' ? 180000 : 30000;
  },
  get characterStaggerMs() {
    return this.aiProvider === 'groq' ? 35000 : 0;
  },
  pauseHotkey: process.env.PAUSE_HOTKEY || 'Control+Alt+P',
  stickmanColor: process.env.STICKMAN_COLOR || '#111111',
  shimeji: {
    javaPath: process.env.SHIMEJI_JAVA_PATH || 'C:\\Program Files (x86)\\Java\\jre1.8.0_501\\bin\\javaw.exe',
    jarPath: process.env.SHIMEJI_JAR_PATH || 'C:\\Users\\jh4ck\\AppData\\Local\\AlanBeckersStickfigures\\AlansStickfigures.jar',
  },
};

module.exports = config;
