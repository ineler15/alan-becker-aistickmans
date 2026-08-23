// Real replacement for Shimeji's rendering: one small transparent always-on-top BrowserWindow per
// enabled character (renderer/character.html), driven every TICK_MS by that character's own
// CharacterState - the same physics/animation state machine already validated on Android. Grew out
// of renderer/rig.js's Red-only proof of concept; this is the version actually wired into main.js.
//
// Dragging: rather than a global click-through hit-test (unsolved - see PC full-engine-replacement
// notes), each window has `-webkit-app-region: drag` on its whole body (see character.html) and is
// sized tight around the character, so the OS itself handles the actual grab/move. This module just
// watches the window's 'move' events to know when a drag is happening (feeds CharacterState.dragTo
// so the pinch/dangle pose shows) and detects drag-end via a short quiet period with no more moves.

const { BrowserWindow, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const { pathToFileURL } = require('url');
const { CharacterState, TICK_MS } = require('./characterState');
const shimejiController = require('./jsShimejiController');
const customCharacters = require('../customCharacters');
const input = require('../actions/input');
const pointerHighlight = require('../ui/pointerHighlight');

// Android's equivalent overlay window is a 128dp square (CharacterOverlay.kt's sizePx) - these
// were never tuned to match and ended up much bigger on PC. Same aspect ratio as before, scaled
// down so the on-screen character size is closer to Android's. Cut again after 130x170 still
// looked oversized live - adjust further here if still off, it's just a constant.
// RIG_WIDTH/RIG_HEIGHT is the character's own visual box (used for the rig's scale-fit in
// character.js) - kept small and separate from the actual OS window size below, which pads out
// extra room around it for the speech bubble. Without that padding the bubble had nowhere valid
// to render (a BrowserWindow can't draw outside its own rectangle) and was invisible - see
// character.html's #speech positioning.
const RIG_WIDTH = 80;
const RIG_HEIGHT = 105;
const BUBBLE_SIDE_MARGIN = 60;
const BUBBLE_TOP_MARGIN = 50;
const WINDOW_WIDTH = RIG_WIDTH + BUBBLE_SIDE_MARGIN * 2;
const WINDOW_HEIGHT = RIG_HEIGHT + BUBBLE_TOP_MARGIN;
const DRAG_END_QUIET_MS = 150;

let entries = [];
let tickTimer = null;

function windowSize() {
  const scaleFactor = screen.getPrimaryDisplay().scaleFactor || 1;
  return {
    width: Math.round(WINDOW_WIDTH / scaleFactor),
    height: Math.round(WINDOW_HEIGHT / scaleFactor),
    rigWidth: Math.round(RIG_WIDTH / scaleFactor),
    rigHeight: Math.round(RIG_HEIGHT / scaleFactor),
  };
}

function createWindow(character, startX, startY, size) {
  const win = new BrowserWindow({
    width: size.width,
    height: size.height,
    x: startX,
    y: startY,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    hasShadow: false,
    skipTaskbar: true,
    webPreferences: {
      contextIsolation: false,
      nodeIntegration: true,
    },
  });
  const builtinRigPath = path.join(__dirname, '..', '..', 'renderer', 'rigs', `${character.id}.json`);
  const query = { id: character.id, rw: String(size.rigWidth), rh: String(size.rigHeight) };
  // Custom characters' rigs live in the writable workspace dir (see customCharacters.js), not
  // under renderer/rigs/ - that tree can end up read-only inside app.asar in a packaged build.
  if (!fs.existsSync(builtinRigPath)) {
    const rigPath = customCharacters.customRigPath(character.id);
    // Windows paths (backslashes, drive letters) aren't valid file:// URLs as-is - build the
    // proper URL here in the main process rather than string-concatenating in the renderer.
    if (rigPath) query.customRigUrl = pathToFileURL(rigPath).href;
    // poseLibrary.js's PROFILE_BY_ID only knows built-in ids - point it at whichever one this
    // custom character's rig was cloned from so it actually animates instead of standing frozen.
    // hasFace/gender drive the face+accessory drawing in character.js (see customCharacters.js).
    const meta = customCharacters.metaFor(character.id);
    if (meta) {
      query.poseProfile = meta.poseProfile;
      if (meta.hasFace) query.hasFace = '1';
      query.gender = meta.gender;
    }
  }
  win.loadFile(path.join(__dirname, '..', '..', 'renderer', 'character.html'), { query });
  return win;
}

function start(characters) {
  stop();
  const display = screen.getPrimaryDisplay();
  const workArea = display.workArea;
  const size = windowSize();
  const floorY = workArea.y + workArea.height - 4;

  entries = characters.map((character, i) => {
    const spacing = workArea.width / (characters.length + 1);
    const centerX = workArea.x + Math.round(spacing * (i + 1));
    const state = new CharacterState(workArea.width, workArea.height, floorY);
    state.x = centerX - workArea.x;
    state.y = floorY;
    const win = createWindow(character, centerX - size.width / 2, floorY - size.height, size);

    const entry = { id: character.id, character, state, win, dragEndTimer: null, workArea, size };

    win.on('move', () => {
      // setBounds() below (used to animate falling/walking/etc.) fires this same 'move' event -
      // without this guard, every animated reposition looked like the user grabbing the window,
      // flipping beingDragged back to true and aborting the fall mid-animation. Only real,
      // OS-initiated drags (the user's cursor actually moving the window) should reach here.
      if (entry.programmaticMove) {
        entry.programmaticMove = false;
        return;
      }
      const b = win.getBounds();
      state.beingDragged = true;
      state.dragTo(
        Math.round(b.x + b.width / 2 - workArea.x),
        Math.round(b.y + b.height)
      );
      if (entry.dragEndTimer) clearTimeout(entry.dragEndTimer);
      entry.dragEndTimer = setTimeout(() => {
        if (state.beingDragged) state.onRelease();
      }, DRAG_END_QUIET_MS);
    });

    win.on('closed', () => {
      if (entry.dragEndTimer) clearTimeout(entry.dragEndTimer);
      shimejiController.unregister(character.id);
    });

    shimejiController.register(character.id, state, win);
    return entry;
  });

  tickTimer = setInterval(tick, TICK_MS);
}

async function tick() {
  // Only pay for the native getMousePosition() call when someone's actually riding it -
  // characterState.js's ridingMouse/startRideMouse. Real (absolute) screen pixels, converted to
  // each character's own workArea-local coordinate space below.
  const anyRiding = entries.some((entry) => entry.state.ridingMouse);
  const mousePos = anyRiding ? await input.getMousePosition().catch(() => null) : null;

  for (const entry of entries) {
    if (entry.win.isDestroyed()) continue;
    const localMousePos = mousePos
      ? { x: mousePos.x - entry.workArea.x, y: mousePos.y - entry.workArea.y }
      : null;
    const descriptor = entry.state.tick(localMousePos);
    if (entry.state.ridingMouse && mousePos) {
      pointerHighlight.showFor(entry.id, mousePos.x, mousePos.y);
    }
    // While being dragged, the OS is already moving the window (see the 'move' listener above) -
    // repositioning it here too would fight the in-progress drag instead of just following it.
    if (!entry.state.beingDragged) {
      const x = Math.round(entry.workArea.x + entry.state.x - entry.size.width / 2);
      const y = Math.round(entry.state.y - entry.size.height);
      entry.programmaticMove = true;
      entry.win.setBounds({ x, y, width: entry.size.width, height: entry.size.height });
    }
    entry.win.webContents.send('character:pose', {
      id: entry.id,
      descriptor,
      lookRight: entry.state.lookRight,
      speechText: entry.state.speechText,
      eyeStyle: entry.state.eyeStyle,
      mouthStyle: entry.state.mouthStyle,
    });
  }
}

function stop() {
  if (tickTimer) clearInterval(tickTimer);
  tickTimer = null;
  for (const entry of entries) {
    if (entry.dragEndTimer) clearTimeout(entry.dragEndTimer);
    shimejiController.unregister(entry.id);
    if (!entry.win.isDestroyed()) entry.win.close();
  }
  entries = [];
}

module.exports = { start, stop };
