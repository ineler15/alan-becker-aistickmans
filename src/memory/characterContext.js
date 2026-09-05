const fs = require('fs');
const path = require('path');
const config = require('../config');

// A character's self-written extra context (via the set_context action), persisted so it survives
// restarts - SEPARATE from the automatic context agentLoop builds every tick (history, peers,
// status, etc.). The user can also write context via the settings window (see pcSettings.js's
// perCharacterContext / applyContexts); this store is only for what the character writes itself.
const cache = new Map();

function fileFor(characterId) {
  return path.join(config.workspaceDir, `context-${characterId}.json`);
}

function load(characterId) {
  if (cache.has(characterId)) return cache.get(characterId);
  let text = '';
  try {
    text = JSON.parse(fs.readFileSync(fileFor(characterId), 'utf8')).context || '';
  } catch {
    // no self-written context yet
  }
  cache.set(characterId, text);
  return text;
}

function set(characterId, context) {
  cache.set(characterId, context);
  fs.writeFileSync(fileFor(characterId), JSON.stringify({ context }, null, 2), 'utf8');
}

module.exports = { load, set };