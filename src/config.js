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
  // Off by default (see pcSettings.js) - gates move_mouse/click/tap/ride_mouse in
  // src/actions/executor.js so an AI can't take over the real mouse without explicit opt-in.
  get allowMouseControl() {
    return process.env.ALLOW_MOUSE_CONTROL === '1';
  },
  // Global attention preference injected into the AI context every turn: 'camera' (pay more
  // attention to the webcam frame), 'mouse' (pay more attention to the cursor position),
  // 'screen' (pay more attention to the screen screenshot) or 'all' (everything together).
  get attentionFocus() {
    const v = process.env.ATTENTION_FOCUS;
    return v === 'mouse' || v === 'screen' || v === 'all' ? v : 'camera';
  },
  // 'rigs' (the modern .nodes-model rendering) vs 'sprites' (the legacy Shimeji-style sprite
  // rendering, renderer/sprites/). The legacy build bakes it in via electron-builder's
  // extraMetadata { renderMode: "sprites" } into the packaged package.json; dev/npm start can
  // also force it with env RENDER_MODE=sprites. Defaults to the modern rigs rendering.
  get renderMode() {
    const fromEnv = process.env.RENDER_MODE;
    if (fromEnv === 'sprites' || fromEnv === 'rigs') return fromEnv;
    try {
      const pkg = require(path.join(ROOT_DIR, 'package.json'));
      if (pkg.renderMode === 'sprites') return 'sprites';
    } catch (e) {
      // ignore - fall through to the default below
    }
    return 'rigs';
  },
  get isSpritesMode() {
    return this.renderMode === 'sprites';
  },
  // Puerto HTTP del peerServer de esta instancia (src/net/peerServer.js). Primero manda el env
  // PEER_PORT, luego el campo peerPort del package.json empaquetado (solo el build legacy lo
  // hornea como 8788 via electron-builder's extraMetadata; el moderno se queda en el default
  // 8787). Misiva el patron del getter renderMode de arriba: require en try/catch e ignorado.
  get peerPort() {
    const fromEnv = Number(process.env.PEER_PORT || 0);
    if (fromEnv) return fromEnv;
    try {
      const pkg = require(path.join(ROOT_DIR, 'package.json'));
      const fromPkg = Number(pkg.peerPort);
      if (fromPkg > 0) return fromPkg;
    } catch (e) {
      // ignore - cae al default de abajo
    }
    return 8787;
  },
  // Puerto de la OTRA instancia de escritorio (la que vive en el escritorio lado a lado). Cada
  // instancia habla con la contraria: la moderna (8787) sincroniza con la legacy (8788) y
  // viceversa. El env PEER_REMOTE_PORT puede forzarlo manualmente si hiciera falta.
  get peerRemotePort() {
    return Number(process.env.PEER_REMOTE_PORT || 0) || (this.peerPort === 8787 ? 8788 : 8787);
  },
  // Survival system (vida/hambre/sed): stat bars over each character, slow hunger/thirst drain,
  // eat/drink only at the kitchen, fight-induced damage, and death (manual revive from the chat).
  // Toggleable in Configuracion - when off, stats stay full, nobody fights, nobody dies. Default
  // on, since this is an opt-out feature (see pcSettings.survivalEnabled).
  get survivalEnabled() {
    return process.env.ENABLE_SURVIVAL !== '0';
  },
  stickmanColor: process.env.STICKMAN_COLOR || '#111111',
  shimeji: {
    javaPath: process.env.SHIMEJI_JAVA_PATH || 'C:\\Program Files (x86)\\Java\\jre1.8.0_501\\bin\\javaw.exe',
    jarPath: process.env.SHIMEJI_JAR_PATH || 'C:\\Users\\jh4ck\\AppData\\Local\\AlanBeckersStickfigures\\AlansStickfigures.jar',
  },
};

module.exports = config;
