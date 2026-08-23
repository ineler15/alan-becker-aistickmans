// Drop-in replacement for dist/shimejiController.js's sendCommand()/readStatus() interface (see
// src-ts/shimejiController.ts) - same shape, so agentLoop.js and executor.js need no changes
// beyond their require() path. The original wrote/read JSON files that a separate Java process
// polled every ~200ms; this drives an in-process CharacterState directly instead, since there's
// no external process anymore (see main.js's jsCharacterEngine).

const registry = new Map(); // characterId -> { state, win }

function register(characterId, state, win) {
  registry.set(characterId, { state, win });
}

function unregister(characterId) {
  registry.delete(characterId);
}

function get(characterId) {
  return registry.get(characterId);
}

function sendCommand(characterId, tool, args = {}) {
  const entry = registry.get(characterId);
  if (!entry) return;
  const { state } = entry;
  switch (tool) {
    case 'walk_to':
      state.startMoving(args.x, !!args.run);
      break;
    case 'ride_mouse':
      state.startRideMouse(args.seconds);
      break;
    case 'set_animation':
      state.setEmotion(args.state);
      break;
    case 'set_emotion':
      state.setFace(args.eyes, args.mouth);
      break;
    case 'set_custom_animation':
      state.startCustomAnimation(Array.isArray(args.keyframes) ? args.keyframes : []);
      break;
    case 'say':
      state.say(String(args.text || ''));
      break;
  }
}

function readStatus(characterId) {
  const entry = registry.get(characterId);
  if (!entry) return null;
  const { state } = entry;
  return {
    x: state.x,
    y: state.y,
    moving: state.moving,
    lookRight: state.lookRight,
    pose: state.loopEmotion || (state.moving ? (state.running ? 'run' : 'walk') : 'stand'),
    commandId: null,
  };
}

module.exports = { register, unregister, get, sendCommand, readStatus };
