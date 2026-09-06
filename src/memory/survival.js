const fs = require('fs');
const path = require('path');
const config = require('../config');

// Survival stats (vida/hambre/sed) per character, persisted to workspace/stats-<id>.json - same
// one-file-per-character pattern as history-<id>.json/notes-*. Values are floats (drained on
// coarse real-time polls from jsCharacterEngine.js and by the fight action) clamped to [0, 100].
// hp hitting 0 means dead: the character lies still, the AI loop skips it, and a chat message
// from the user revives it (see agentLoop.js). Whole thing gated behind config.survivalEnabled -
// toggling the feature off resets everyone to full stats (pcSettings → ENABLE_SURVIVAL env).
const DEFAULTS = { hp: 100, hunger: 100, thirst: 100, dead: false };
const stores = new Map();

function fileFor(characterId) {
  return path.join(config.workspaceDir, `stats-${characterId}.json`);
}

function get(characterId) {
  if (!stores.has(characterId)) {
    let data = null;
    try {
      data = JSON.parse(fs.readFileSync(fileFor(characterId), 'utf8'));
    } catch {
      data = null;
    }
    stores.set(characterId, { ...DEFAULTS, ...(data || {}) });
  }
  return stores.get(characterId);
}

function set(characterId, patch) {
  const data = get(characterId);
  Object.assign(data, patch);
  if (typeof data.hp === 'number') data.hp = Math.min(100, Math.max(0, data.hp));
  if (typeof data.hunger === 'number') data.hunger = Math.min(100, Math.max(0, data.hunger));
  if (typeof data.thirst === 'number') data.thirst = Math.min(100, Math.max(0, data.thirst));
  fs.mkdirSync(config.workspaceDir, { recursive: true });
  fs.writeFileSync(fileFor(characterId), JSON.stringify(data, null, 2), 'utf8');
  return data;
}

function reset(characterId) {
  stores.set(characterId, { ...DEFAULTS });
  fs.mkdirSync(config.workspaceDir, { recursive: true });
  fs.writeFileSync(fileFor(characterId), JSON.stringify(stores.get(characterId), null, 2), 'utf8');
  return stores.get(characterId);
}

// Shared damage path for the fight action (executor.js) and long-term starvation
// (jsCharacterEngine.js) - kills in place when hp hits 0 so both callers get one death check.
function applyDamage(characterId, amount) {
  const data = get(characterId);
  const hp = Math.max(0, data.hp - amount);
  set(characterId, { hp, dead: hp <= 0 });
  return data;
}

function revive(characterId) {
  const data = get(characterId);
  set(characterId, { hp: 100, dead: false });
  return data;
}

module.exports = { get, set, reset, applyDamage, revive, DEFAULTS, fileFor };