const fs = require('fs');
const path = require('path');
const config = require('../config');

const MAX_ITEMS = 40;

// One in-memory item list per character, persisted to its own file so
// several AI-driven friends don't share (and clobber) the same history.
const stores = new Map();

function fileFor(characterId) {
  return path.join(config.workspaceDir, `history-${characterId}.json`);
}

function loadItems(characterId) {
  try {
    return JSON.parse(fs.readFileSync(fileFor(characterId), 'utf8'));
  } catch {
    return [];
  }
}

function getStore(characterId) {
  if (!stores.has(characterId)) {
    stores.set(characterId, loadItems(characterId));
  }
  return stores.get(characterId);
}

function add(characterId, entry) {
  const items = getStore(characterId);
  items.push({ ...entry, ts: new Date().toISOString() });
  if (items.length > MAX_ITEMS) items.splice(0, items.length - MAX_ITEMS);
  fs.writeFileSync(fileFor(characterId), JSON.stringify(items, null, 2), 'utf8');
}

function recent(characterId, n = 10) {
  return getStore(characterId).slice(-n);
}

module.exports = { add, recent };
