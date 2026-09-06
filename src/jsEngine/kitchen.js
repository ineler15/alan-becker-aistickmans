// The kitchen: a static prop the characters can eat/drink at (eat/drink only work next to it, see
// executor.js). The user uploads the kitchen as a Stick Nodes .nodes file - the same converter
// used for character rigs turns it into renderer/rigs/kitchen.json. Until that file exists
// nothing spawns and getPosition() returns null, so the eat/drink actions tell the AI there's
// nowhere to go (and agentLoop's context says "todavia no hay cocina").
//
// Rendered by reusing the character window page (renderer/character.html?id=kitchen): poseLibrary
// has no profile for 'kitchen', so forDescriptor() returns an empty override map and the rig just
// renders its authored rest pose - exactly right for a static prop with no animation/AI.

const { BrowserWindow, screen } = require('electron');
const path = require('path');
const fs = require('fs');

let win = null;
let position = null; // { x, y } in workArea-local space, same coordinate system as CharacterState.x
let stationIndex = 0;

function rigPath() {
  return path.join(__dirname, '..', '..', 'renderer', 'rigs', 'kitchen.json');
}

// The kitchen can cycle "stations" - alternative background rigs (kitchen-1.json etc., generated
// from the other kitchen .nodes files in the pack) swapped in with a fade whenever a character
// eats/drinks (see foodProp.js). Only the ones that actually exist are offered; a single rig just
// stays put, same as before.
function stations() {
  return ['kitchen', 'kitchen-1', 'kitchen-2'].filter((id) =>
    fs.existsSync(path.join(__dirname, '..', '..', 'renderer', 'rigs', `${id}.json`))
  );
}

function nextStation() {
  const list = stations();
  if (list.length < 2 || !win || win.isDestroyed()) return;
  stationIndex = (stationIndex + 1) % list.length;
  win.webContents.send('character:rig', { id: 'kitchen', url: `rigs/${list[stationIndex]}.json` });
}

function start() {
  stop();
  if (!fs.existsSync(rigPath())) return; // user hasn't dropped the kitchen .nodes yet
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
  win.loadFile(path.join(__dirname, '..', '..', 'renderer', 'character.html'), {
    query: { id: 'kitchen', rw: String(rigW), rh: String(rigH) },
  });
  position = { x: centerX - workArea.x, y: floorY - workArea.y };
}

function stop() {
  if (win && !win.isDestroyed()) win.close();
  win = null;
  position = null;
  stationIndex = 0;
}

function getPosition() {
  return position;
}

module.exports = { start, stop, getPosition, nextStation };