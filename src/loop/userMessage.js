// Holds messages typed by the user through the chat window, one per character id, until
// the next tick round picks each one up and clears it - so a message aimed at "Red" only
// shows up in Red's context, not every active character's.
const pending = new Map();

function set(characterId, text) {
  pending.set(characterId, text);
}

function consume(characterId) {
  const message = pending.get(characterId) || null;
  pending.delete(characterId);
  return message;
}

module.exports = { set, consume };
