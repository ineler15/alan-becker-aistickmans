const fs = require('fs');
const path = require('path');
const config = require('../config');

// A character's self-written personality (via the define_personality action), persisted so
// it survives restarts instead of resetting to whatever seed was in characters.js.
const cache = new Map();

function fileFor(characterId) {
  return path.join(config.workspaceDir, `personality-${characterId}.json`);
}

function load(characterId) {
  if (cache.has(characterId)) return cache.get(characterId);
  let description = '';
  try {
    description = JSON.parse(fs.readFileSync(fileFor(characterId), 'utf8')).description || '';
  } catch {
    // no self-defined personality yet
  }
  cache.set(characterId, description);
  return description;
}

function set(characterId, description) {
  cache.set(characterId, description);
  fs.writeFileSync(fileFor(characterId), JSON.stringify({ description }, null, 2), 'utf8');
}

module.exports = { load, set };
