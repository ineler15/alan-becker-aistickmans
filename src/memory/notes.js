const fs = require('fs');
const path = require('path');
const config = require('../config');

const MAX_NOTES = 30;

// Long-term memory notes a character wrote for itself (via the "remember" action) -
// separate from history.js's raw action log, this is meant for things actually worth
// keeping around (facts about the user, things it learned), persisted per character.
const stores = new Map();

function fileFor(characterId) {
  return path.join(config.workspaceDir, `memory-${characterId}.json`);
}

function loadNotes(characterId) {
  try {
    return JSON.parse(fs.readFileSync(fileFor(characterId), 'utf8'));
  } catch {
    return [];
  }
}

function getStore(characterId) {
  if (!stores.has(characterId)) {
    stores.set(characterId, loadNotes(characterId));
  }
  return stores.get(characterId);
}

function add(characterId, note) {
  const notes = getStore(characterId);
  notes.push({ note, ts: new Date().toISOString() });
  if (notes.length > MAX_NOTES) notes.splice(0, notes.length - MAX_NOTES);
  fs.writeFileSync(fileFor(characterId), JSON.stringify(notes, null, 2), 'utf8');
}

function recent(characterId, count = 15) {
  return getStore(characterId)
    .slice(-count)
    .map((entry) => entry.note);
}

module.exports = { add, recent };
