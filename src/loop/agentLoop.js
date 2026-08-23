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

// User-controlled 0-100 slider (settings.js, next to the partner dropdown) instead of a single
// fixed "es tu pareja" phrase - lets the user dial a relationship anywhere from barely-registers
// to head-over-heels rather than only on/off. Deliberately coarse tiers, not a continuous
// interpolation - the AI reads prose, not numbers, so a handful of clearly distinct phrasings
// works better than tiny wording deltas per point of the slider.
function affectionPhrase(displayName, level) {
  if (level >= 80) {
    return (
      `Estas profundamente enamorado/a de ${displayName} - es lo que mas te importa en el mundo, ` +
      'buscala/buscalo todo el tiempo y mostralo sin filtro con lo que decis y con tu cara (eyes: heart). '
    );
  }
  if (level >= 60) {
    return (
      `Sentis carino especial por ${displayName} - te importa de verdad, buscala/buscalo seguido y ` +
      'mostraselo de a poco con lo que decis. '
    );
  }
  if (level >= 40) {
    return `Le tenes bastante carino a ${displayName} - te gusta pasar tiempo con esa persona. `;
  }
  if (level >= 20) {
    return `${displayName} te cae bien, nada mas. `;
  }
  return `No sentis nada en particular por ${displayName} mas alla de conocerse. `;
}

// Last Paint OCR text seen by each character, keyed by character id - kept
// separate per character so one friend's read_paint doesn't leak into
// another's context.
const lastPaintTextById = new Map();

// Same idea for the last Notepad text a character read via read_notepad.
const lastNotepadTextById = new Map();

// Consecutive identical-tool decisions per character - the model doesn't reliably follow
// prompt instructions to vary its behavior (e.g. it'll pick "say" or "wait" over and over),
// so this forces a walk_to (if there's somewhere purposeful to go) or a wait after a few repeats
// instead of trusting the prompt alone.
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

    // Fixed fact from character creation (see customCharacters.js), not something the AI defines
    // itself via define_personality - prepended so every provider picks it up for free, since
    // they all already embed context.personality verbatim into the prompt.
    const genderLine =
      character.gender === 'femenino'
        ? 'Tu genero es femenino. '
        : character.gender === 'masculino'
          ? 'Tu genero es masculino. '
          : '';

    // Explicit "pareja" set in Settings (pcSettings.applyPartners), stronger/more reliable than
    // the emergent "crush" behavior in the system prompts - a designated fact, not something that
    // may or may not surface on its own.
    const partner = character.partnerId ? CHARACTERS.ALL.find((c) => c.id === character.partnerId) : null;
    // Restate the partner's live position directly here instead of relying on the model to find
    // the right entry in "peers" by name on its own - the peers array already has this (it's the
    // same status.x/y every peer carries), but repeating it right next to "this one is your
    // partner" is what actually makes characters go find each other reliably.
    const partnerPeer = partner ? peers.find((p) => p.id === partner.id) : null;
    const partnerLocationLine =
      partnerPeer && partnerPeer.status
        ? `Ahora mismo esta en x=${Math.round(partnerPeer.status.x)}, y=${Math.round(partnerPeer.status.y)}${
            partnerPeer.device === 'tablet' ? ' (en la tablet)' : ''
          }. `
        : '';
    const partnerLine = partner ? affectionPhrase(partner.displayName, character.affectionLevel) + partnerLocationLine : '';

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
        genderLine +
        partnerLine +
        (selfPersonality.load(characterId) ||
          character.personality ||
          'Todavia no definiste tu propia personalidad. Cuando quieras, usa define_personality para ' +
            'decidir en tus propias palabras como sos.'),
      peers,
      userMessage: userMessageText || null,
      forceSay: (turnsSinceSayById.get(characterId) || 0) >= SILENT_TURN_LIMIT,
    };

    let { tool, args } = await withTimeout(
      provider.decide(context),
      config.decideTimeoutMs,
      `provider.decide[${characterId}]`
    );
    // Captured before the repeat-guard below can swap `tool`/`args` out from under the model
    // (e.g. forcing wait/walk_to) - a pending face reaction shouldn't get silently dropped just
    // because the body action it rode in on got overridden. See actions.schema.js's EYES_PARAM.
    const requestedEyes = args && args.eyes;
    const requestedMouth = args && args.mouth;

    if (tool === 'say') {
      turnsSinceSayById.set(characterId, 0);
    } else {
      turnsSinceSayById.set(characterId, (turnsSinceSayById.get(characterId) || 0) + 1);
    }

    const repeatLimit = tool === 'wait' ? 2 : 3;
    if (tool === lastToolById.get(characterId)) {
      const streak = (repeatStreakById.get(characterId) || 0) + 1;
      repeatStreakById.set(characterId, streak);
      if (streak >= repeatLimit && tool !== 'walk_to') {
        const target = purposefulWalkTarget(perception, status);
        tool = target ? 'walk_to' : 'wait';
        args = target || {};
        repeatStreakById.set(characterId, 0);
      }
    } else {
      repeatStreakById.set(characterId, 0);
    }
    lastToolById.set(characterId, tool);

    // Let eyes/mouth ride along regardless of which tool actually ran this turn, so a character
    // doesn't need a whole separate decision cycle just to update its face - see
    // actions.schema.js's EYES_PARAM/MOUTH_PARAM (every action accepts these two optional params).
    if (tool !== 'set_emotion' && (requestedEyes || requestedMouth)) {
      executor.execute('set_emotion', { eyes: requestedEyes, mouth: requestedMouth }, characterId).catch(() => {});
    }

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

    // A failed decide()/execute() (rate limit, timeout, etc.) shouldn't error again on the
    // fallback itself - 'wait' always succeeds. The character won't stand there forever either
    // way: characterState.js's autonomous wander (IDLE_WALK_TIMEOUT_MS) kicks in on its own once
    // enough time passes without a real decision, error or not.
    try {
      const fallbackResult = await executor.execute('wait', {}, characterId);
      history.add(characterId, {
        tool: 'wait',
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
