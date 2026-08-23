// A small colored ring that flashes wherever a character's move_mouse/tap action lands, so with
// several characters able to control the mouse (see config.allowMouseControl) it's visually clear
// WHICH one is doing it right now - the character's own "mouse", colored like it, layered on top
// of (not replacing) the real OS cursor movement nut-js performs. A true independent cursor that
// never touches the real one isn't possible for a REAL click on Windows - see src/actions/input.js.
const { BrowserWindow } = require('electron');
const fs = require('fs');
const path = require('path');
const config = require('../config');
const customCharacters = require('../customCharacters');

const SIZE = 28;
const VISIBLE_MS = 500;

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

async function showFor(characterId, x, y) {
  const [r, g, b] = colorFor(characterId);
  const win = new BrowserWindow({
    width: SIZE,
    height: SIZE,
    x: Math.round(Number(x) - SIZE / 2),
    y: Math.round(Number(y) - SIZE / 2),
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    hasShadow: false,
    skipTaskbar: true,
    focusable: false,
    webPreferences: { contextIsolation: false, nodeIntegration: false },
  });
  win.setIgnoreMouseEvents(true);
  const css = `background:rgba(${r},${g},${b},0.35);border:3px solid rgb(${r},${g},${b});border-radius:50%;width:100%;height:100%;box-sizing:border-box;`;
  await win.loadURL(`data:text/html,<body style="margin:0;overflow:hidden"><div style="${css}"></div></body>`);
  setTimeout(() => {
    if (!win.isDestroyed()) win.close();
  }, VISIBLE_MS);
}

module.exports = { showFor };
