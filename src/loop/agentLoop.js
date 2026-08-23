const config = require('../config');
const history = require('../memory/history');
const executor = require('../actions/executor');
const { getProvider } = require('../ai/provider');
const { capturePerception, cropForCharacter } = require('../../dist/perception');
const shimeji = require('../jsEngine/jsShimejiController');
const CHARACTERS = require('../characters');
const userMessage = require('./userMessage');
const selfPersonality = require('../memory/selfPersonality');
const notes = require('../memory/notes');
const webcam = require('./webcam');
const peerServer = require('../net/peerServer');
const health = require('./health');

let paused = true;
let timer = null;
let ticking = false;

// Last Paint OCR text seen by each character, keyed by character id - kept
// separate per character so one friend's read_paint doesn't leak into
// another's context.
const lastPaintTextById = new Map();

// Same idea for the last Notepad text a character read via read_notepad.
const lastNotepadTextById = new Map();

// Consecutive identical-tool decisions per character - the model doesn't reliably follow
// prompt instructions to vary its behavior (e.g. it'll pick "say" or "wait" over and over),
// so this forces a move_random after a few repeats instead of trusting the prompt alone.
const lastToolById = new Map();
const repeatStreakById = new Map();

// The model reliably falls quiet for many turns in a row despite prompt instructions to
// talk spontaneously - same story as the repeat-streak forcing above, prompt wording alone
// doesn't hold, so this forces a "say" turn in code after too many silent turns.
const turnsSinceSayById = new Map();
const SILENT_TURN_LIMIT = 3;

// Automatic tiredness/sleep - forces the existing sleep/tired set_animation states (see
// actions.schema.js and AIBehavior.java's applyStaticPose) after being awake too long, sooner at
// night, instead of only when the model itself decides to. Java has no auto-wake timer for these
// (no equivalent of its sayUntil/rideCursorUntil fields), so tracking sleep duration and waking
// back up both happen here on the JS side. Mirrors the same thresholds used on the Android port.
const awakeSinceById = new Map();
const sleepStartedAtById = new Map();
const AWAKE_MS_BEFORE_SLEEP = 20 * 60 * 1000;
const AWAKE_MS_BEFORE_SLEEP_AT_NIGHT = 10 * 60 * 1000;
const SLEEP_DURATION_MS = 5 * 60 * 1000;
const NIGHT_START_HOUR = 22;
const NIGHT_END_HOUR = 7;

function isNightNow() {
  const hour = new Date().getHours();
  return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
}

function shouldForceSleep(characterId) {
  const awakeSince = awakeSinceById.get(characterId) || Date.now();
  const threshold = isNightNow() ? AWAKE_MS_BEFORE_SLEEP_AT_NIGHT : AWAKE_MS_BEFORE_SLEEP;
  return Date.now() - awakeSince > threshold;
}

function wakeUp(characterId) {
  sleepStartedAtById.delete(characterId);
  awakeSinceById.set(characterId, Date.now());
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function withTimeout(promise, ms, label) {
  let timeoutId;
  const timeout = new Promise((_, reject) => {
    timeoutId = setTimeout(() => reject(new Error(`${label} timed out after ${ms}ms`)), ms);
  });
  return Promise.race([promise, timeout]).finally(() => clearTimeout(timeoutId));
}

// When a character needs to be nudged into moving without asking the model again, aim it
// at the active window instead of a blind random point - walking toward whatever's
// actually on screen reads as purposeful instead of a random-walk fallback.
function purposefulWalkTarget(perception, status) {
  const bounds = perception && perception.activeWindow && perception.activeWindow.bounds;
  if (!bounds || !bounds.width || !bounds.height) return null;
  // x toward the window, but stay on the ground - there's no climbing yet, so a target
  // partway up a window would otherwise have the character walk floating through the air.
  return {
    x: Math.round(bounds.x + bounds.width / 2),
    y: status ? status.y : Math.round(bounds.y + bounds.height),
  };
}

async function tickCharacter(character, perception, userMessageText) {
  const { id: characterId } = character;
  try {
    const provider = getProvider(characterId);
    const status = shimeji.readStatus(characterId);
    const screenshotBase64 = perception
      ? await cropForCharacter(perception.screenshot, status)
      : null;

    // So each friend is aware of the others - where they are and what they last did -
    // instead of acting as if it were alone on the desktop.
    const peers = CHARACTERS.filter((c) => c.id !== characterId).map((c) => {
      const peerStatus = shimeji.readStatus(c.id);
      const lastAction = history.recent(c.id, 1)[0] || null;
      return {
        id: c.id,
        displayName: c.displayName,
        status: peerStatus,
        lastAction: lastAction ? { tool: lastAction.tool, args: lastAction.args, result: lastAction.result } : null,
      };
    });

    // Characters running on the Android port (if connected over the LAN) - same shape as a
    // local peer, tagged with device so the model knows it's not sharing this screen/desktop.
    for (const remote of peerServer.getRemotePeers()) {
      peers.push({
        id: remote.id,
        displayName: remote.displayName,
        device: 'tablet',
        status: { x: remote.x, y: remote.y },
        lastAction: remote.lastSay ? { tool: 'say', args: { text: remote.lastSay } } : null,
      });
    }

    const context = {
      characterId,
      recentHistory: history.recent(characterId, 8),
      memory: notes.recent(characterId, 15),
      lastPaintText: lastPaintTextById.get(characterId) || '',
      lastNotepadText: lastNotepadTextById.get(characterId) || '',
      shimejiStatus: status,
      activeWindow: perception ? perception.activeWindow : null,
      screenshotBase64,
      webcamBase64: webcam.get(),
      personality:
        selfPersonality.load(characterId) ||
        character.personality ||
        'Todavia no definiste tu propia personalidad. Cuando quieras, usa define_personality para ' +
          'decidir en tus propias palabras como sos.',
      peers,
      userMessage: userMessageText || null,
      forceSay: (turnsSinceSayById.get(characterId) || 0) >= SILENT_TURN_LIMIT,
    };

    let { tool, args } = await withTimeout(
      provider.decide(context),
      config.decideTimeoutMs,
      `provider.decide[${characterId}]`
    );

    if (tool === 'say') {
      turnsSinceSayById.set(characterId, 0);
    } else {
      turnsSinceSayById.set(characterId, (turnsSinceSayById.get(characterId) || 0) + 1);
    }

    const repeatLimit = tool === 'wait' ? 2 : 3;
    if (tool === lastToolById.get(characterId)) {
      const streak = (repeatStreakById.get(characterId) || 0) + 1;
      repeatStreakById.set(characterId, streak);
      if (streak >= repeatLimit && tool !== 'move_random' && tool !== 'walk_to') {
        const target = purposefulWalkTarget(perception, status);
        tool = target ? 'walk_to' : 'move_random';
        args = target || {};
        repeatStreakById.set(characterId, 0);
      }
    } else {
      repeatStreakById.set(characterId, 0);
    }
    lastToolById.set(characterId, tool);

    const { ok, result } = await withTimeout(
      executor.execute(tool, args || {}, characterId),
      20000,
      `executor.execute[${characterId}:${tool}]`
    );
    history.add(characterId, { tool, args, ok, result: typeof result === 'string' ? result.slice(0, 500) : result });

    if (tool === 'read_paint' && ok && typeof result === 'string') {
      lastPaintTextById.set(characterId, result);
    }
    if (tool === 'read_notepad' && ok && typeof result === 'string') {
      lastNotepadTextById.set(characterId, result);
    }
    if (tool === 'define_personality' && ok && typeof result === 'string' && result.trim()) {
      selfPersonality.set(characterId, result.trim());
    }
    if (tool === 'remember' && ok && typeof result === 'string' && result.trim()) {
      notes.add(characterId, result.trim());
    }
    health.setOk(characterId);
  } catch (err) {
    const errorMessage = String(err && err.message ? err.message : err);
    history.add(characterId, { tool: 'error', args: {}, ok: false, result: errorMessage });
    health.setError(characterId, errorMessage);

    // A failed decide()/execute() (rate limit, timeout, etc.) shouldn't leave the
    // character frozen - walk somewhere so there's still visible life on a bad turn.
    try {
      const fallbackResult = await executor.execute('move_random', {}, characterId);
      history.add(characterId, {
        tool: 'move_random',
        args: {},
        ok: fallbackResult.ok,
        result: `fallback tras error (${errorMessage.slice(0, 80)}): ${fallbackResult.result}`,
      });
    } catch {
      // best-effort fallback - if even this fails, leave the error entry above as-is.
    }
  }
}

async function tick() {
  if (paused) {
    scheduleNext();
    return;
  }
  ticking = true;
  // One shared screen capture per round - each character crops its own view
  // out of it below, so the cost doesn't multiply with the number of friends.
  const perception = await withTimeout(capturePerception(), 20000, 'capturePerception').catch(() => null);

  // Sequential and staggered on purpose: back-to-back calls for several
  // characters would burn the whole per-minute token budget in one round.
  for (let i = 0; i < CHARACTERS.length; i++) {
    const character = CHARACTERS[i];
    if (!awakeSinceById.has(character.id)) awakeSinceById.set(character.id, Date.now());
    // Each character only gets a message that was aimed specifically at it.
    const pendingUserMessage = userMessage.consume(character.id);

    if (sleepStartedAtById.has(character.id)) {
      if (pendingUserMessage) {
        // A direct message wakes it up to actually respond this turn, instead of the message
        // silently waiting until it wakes on its own.
        wakeUp(character.id);
      } else if (Date.now() - sleepStartedAtById.get(character.id) > SLEEP_DURATION_MS) {
        wakeUp(character.id);
        shimeji.sendCommand(character.id, 'set_animation', { state: 'idle' });
      } else {
        // Still asleep - skip calling the AI provider entirely this round (saves quota; a
        // sleeping character has no business deciding anything anyway).
        continue;
      }
    } else if (shouldForceSleep(character.id)) {
      shimeji.sendCommand(character.id, 'set_animation', { state: 'sleep' });
      sleepStartedAtById.set(character.id, Date.now());
      continue;
    }

    await tickCharacter(character, perception, pendingUserMessage);
    if (i < CHARACTERS.length - 1 && config.characterStaggerMs > 0) await delay(config.characterStaggerMs);
  }

  ticking = false;
  scheduleNext();
}

function scheduleNext() {
  if (timer) clearTimeout(timer);
  timer = setTimeout(tick, config.tickIntervalMs);
}

// Called right after a chat message is queued, so the character reacts to it right away
// instead of waiting out the rest of the normal tick interval.
function wakeNow() {
  if (paused || ticking) return;
  if (timer) clearTimeout(timer);
  tick();
}

function start() {
  paused = false;
  scheduleNext();
}

function stop() {
  if (timer) clearTimeout(timer);
}

function isPaused() {
  return paused;
}

function togglePause() {
  paused = !paused;
  return paused;
}

module.exports = { start, stop, isPaused, togglePause, wakeNow };
