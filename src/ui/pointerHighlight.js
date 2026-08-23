// A small colored ring, persistent per character, that shows wherever that character's own
// move_mouse/click/tap/ride_mouse action is happening right now - so with several characters able
// to control the mouse (see config.allowMouseControl) it's visually clear WHICH one is doing it,
// each with its own distinct color. Layered on top of (not replacing) the real OS cursor movement
// nut-js performs - a true independent cursor that never touches the real one isn't possible for a
// REAL click on Windows, see src/actions/input.js. One window per character, moved in place rather
// than recreated on every call (recreating flickered and lost the "this dot belongs to them"
// continuity while ride_mouse calls this every tick) - it hides itself after a short idle period
// with no new position instead of on a fixed flash timer, so it stays up for the whole ride_mouse
// duration but still disappears once that character stops acting.
const { BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');
const config = require('../config');
const customCharacters = require('../customCharacters');

const SIZE = 28;
const IDLE_HIDE_MS = 600;

const windows = new Map(); // characterId -> BrowserWindow
const hideTimers = new Map(); // characterId -> Timeout

function colorFor(characterId) {
  const customPath = customCharacters.customRigPath(characterId);
  const builtinPath = path.join(config.rootDir, 'renderer', 'rigs', `${characterId}.json`);
  const rigPath = customPath || (fs.existsSync(builtinPath) ? builtinPath : null);
  if (!rigPath) return [255, 255, 255, 255];
  try {
    const rig = JSON.parse(fs.readFileSync(rigPath, 'utf8'));
    return rig.color || [255, 255, 255, 255];
  } catch (e) {
    return [255, 255, 255, 255];
  }
}

function createWindow(characterId) {
  const [r, g, b] = colorFor(characterId);
  const win = new BrowserWindow({
    width: SIZE,
    height: SIZE,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    hasShadow: false,
    skipTaskbar: true,
    focusable: false,
    show: false,
    webPreferences: { contextIsolation: false, nodeIntegration: false },
  });
  win.setIgnoreMouseEvents(true);
  const css = `background:rgba(${r},${g},${b},0.35);border:3px solid rgb(${r},${g},${b});border-radius:50%;width:100%;height:100%;box-sizing:border-box;`;
  win.loadURL(`data:text/html,<body style="margin:0;overflow:hidden"><div style="${css}"></div></body>`);
  win.on('closed', () => {
    windows.delete(characterId);
    clearTimeout(hideTimers.get(characterId));
    hideTimers.delete(characterId);
  });
  return win;
}

async function showFor(characterId, x, y) {
  let win = windows.get(characterId);
  if (!win || win.isDestroyed()) {
    win = createWindow(characterId);
    windows.set(characterId, win);
  }
  win.setBounds({ x: Math.round(Number(x) - SIZE / 2), y: Math.round(Number(y) - SIZE / 2), width: SIZE, height: SIZE });
  win.showInactive();
  clearTimeout(hideTimers.get(characterId));
  hideTimers.set(
    characterId,
    setTimeout(() => {
      if (!win.isDestroyed()) win.hide();
    }, IDLE_HIDE_MS)
  );
}

module.exports = { showFor };
