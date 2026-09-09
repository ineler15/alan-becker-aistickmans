// The kitchen: a static prop the characters can eat/drink at (eat/drink only work next to it, see
// executor.js). Rendered from the user's kitchen photo (renderer/kitchen.webp, see kitchen.html) —
// a small always-on-top window with the image fit inside. getPosition() feeds the eat/drink food
// animation anchor, and the agentLoop/survival context tells characters where it is.
//
// Station cycling (kitchen-1/kitchen-2 rig backgrounds swapping on eat/drink) is gone: the photo
// is a single image, so nextStation() stays for API compatibility but does nothing.

const { BrowserWindow, screen } = require('electron');
const path = require('path');
const fs = require('fs');

let win = null;
let position = null; // { x, y } in workArea-local space, same coordinate system as CharacterState.x

function start() {
  stop();
  if (!fs.existsSync(path.join(__dirname, '..', '..', 'renderer', 'kitchen.webp'))) return;
  const display = screen.getPrimaryDisplay();
  const workArea = display.workArea;
  const scaleFactor = display.scaleFactor || 1;
  const rigW = Math.round(120 / scaleFactor);
  const rigH = Math.round(120 / scaleFactor);
  const winW = rigW + 120;
  const winH = rigH + 50;
  const floorY = workArea.y + workArea.height - 4;
  // Sit on the left side of the screen so there's a clear "kitchen is over there" anchor —
  // characters are told its position every turn (agentLoop context) and walk here to eat/drink.
  const centerX = workArea.x + Math.round(workArea.width * 0.18);
  win = new BrowserWindow({
    width: winW,
    height: winH,
    x: centerX - winW / 2,
    y: floorY - winH,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    hasShadow: false,
    skipTaskbar: true,
    webPreferences: { contextIsolation: false, nodeIntegration: true },
  });
  win.loadFile(path.join(__dirname, '..', '..', 'renderer', 'kitchen.html'));
  position = { x: centerX - workArea.x, y: floorY - workArea.y };
}

function stop() {
  if (win && !win.isDestroyed()) win.close();
  win = null;
  position = null;
}

function getPosition() {
  return position;
}

// Kept for API compatibility with foodProp.js/executor.js; the single-photo kitchen has no
// stations to cycle, so this is intentionally a no-op.
function nextStation() {}

module.exports = { start, stop, getPosition, nextStation };