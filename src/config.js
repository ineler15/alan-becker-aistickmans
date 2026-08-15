const path = require('path');
const fs = require('fs');
const { app } = require('electron');
require('dotenv').config();

const ROOT_DIR = path.join(__dirname, '..');
// In a packaged build, ROOT_DIR resolves to inside app.asar - a single read-only file, not a
// real directory, so creating a workspace folder "inside" it fails with ENOTDIR. Default to
// Electron's writable per-user data dir there instead; dev (unpackaged) keeps using the
// project folder like before.
const DEFAULT_WORKSPACE_DIR = app.isPackaged
  ? path.join(app.getPath('userData'), 'workspace')
  : path.join(ROOT_DIR, 'workspace');
const WORKSPACE_DIR = process.env.WORKSPACE_DIR
  ? path.resolve(ROOT_DIR, process.env.WORKSPACE_DIR)
  : DEFAULT_WORKSPACE_DIR;

if (!fs.existsSync(WORKSPACE_DIR)) {
  fs.mkdirSync(WORKSPACE_DIR, { recursive: true });
}

const config = {
  rootDir: ROOT_DIR,
  workspaceDir: WORKSPACE_DIR,
  aiProvider: (process.env.AI_PROVIDER || 'anthropic').toLowerCase(),
  anthropic: {
    apiKey: process.env.ANTHROPIC_API_KEY || '',
    model: process.env.ANTHROPIC_MODEL || 'claude-sonnet-4-5',
  },
  openrouter: {
    apiKey: process.env.OPENROUTER_API_KEY || '',
    model: process.env.OPENROUTER_MODEL || 'anthropic/claude-sonnet-4.5',
  },
  groq: {
    apiKey: process.env.GROQ_API_KEY || '',
    model: process.env.GROQ_MODEL || 'qwen/qwen3.6-27b',
  },
  openai: {
    apiKey: process.env.OPENAI_API_KEY || '',
    model: process.env.OPENAI_MODEL || 'gpt-4o-mini',
  },
  gemini: {
    apiKey: process.env.GEMINI_API_KEY || '',
    model: process.env.GEMINI_MODEL || 'gemini-3.5-flash-lite',
    // Optional per-character keys (GEMINI_API_KEY_<ID>, e.g. GEMINI_API_KEY_RED) so each
    // friend draws from its own free-tier quota instead of all sharing one. Falls back to
    // the shared key above when a character doesn't have its own.
    apiKeyFor(characterId) {
      const perCharacter = process.env[`GEMINI_API_KEY_${characterId.toUpperCase()}`];
      return perCharacter || this.apiKey;
    },
  },
  ollama: {
    baseUrl: process.env.OLLAMA_BASE_URL || 'http://localhost:11434',
    model: process.env.OLLAMA_MODEL || 'gemma4:12b',
  },
  tickIntervalMs: (Number(process.env.TICK_INTERVAL_SECONDS) || 12) * 1000,
  // Ollama runs local with no quota but is much slower per call on this machine; cloud
  // providers (groq/anthropic/openrouter) are fast but groq's free tier needs spacing
  // between characters to not blow its per-minute token budget.
  decideTimeoutMs: (process.env.AI_PROVIDER || 'anthropic').toLowerCase() === 'ollama' ? 180000 : 30000,
  characterStaggerMs: (process.env.AI_PROVIDER || 'anthropic').toLowerCase() === 'groq' ? 35000 : 0,
  pauseHotkey: process.env.PAUSE_HOTKEY || 'Control+Alt+P',
  stickmanColor: process.env.STICKMAN_COLOR || '#111111',
  shimeji: {
    javaPath: process.env.SHIMEJI_JAVA_PATH || 'C:\\Program Files (x86)\\Java\\jre1.8.0_501\\bin\\javaw.exe',
    jarPath: process.env.SHIMEJI_JAR_PATH || 'C:\\Users\\jh4ck\\AppData\\Local\\AlanBeckersStickfigures\\AlansStickfigures.jar',
  },
};

module.exports = config;
