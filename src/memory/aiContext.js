const fs = require('fs');
const path = require('path');
const config = require('../config');

// A character's AI-generated background for 'ia' context mode (see agentLoop.js's
// generateAiContextOnce), persisted so it survives restarts - SEPARATE from the automatic context
// agentLoop builds every tick and from the character's own set_context store (characterContext.js,
// which this mirrors in shape). Generated once per character at startup and only written when the
// provider actually answered with a say; empty until then.
const cache = new Map();

function fileFor(characterId) {
  return path.join(config.workspaceDir, `ai-context-${characterId}.json`);
}

function load(characterId) {
  if (cache.has(characterId)) return cache.get(characterId);
  let text = '';
  try {
    text = JSON.parse(fs.readFileSync(fileFor(characterId), 'utf8')).context || '';
  } catch {
    // no AI-generated context yet
  }
  cache.set(characterId, text);
  return text;
}

function set(characterId, context) {
  cache.set(characterId, context);
  fs.writeFileSync(fileFor(characterId), JSON.stringify({ context }, null, 2), 'utf8');
}

module.exports = { load, set };