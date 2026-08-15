const path = require('path');

// StickPaint is a plain BrowserWindow the app fully controls, used instead of real
// mspaint.exe - Windows never reliably hands focus to an automation-launched mspaint
// window (see paint.js's isPaintRunning/focusPaintWindow), which made open_paint/
// draw_in_paint fail or spawn duplicate windows. Since this window is ours, .show()/
// .focus() just work.
let win = null;
const drawLog = [];

function getWindow() {
  const { BrowserWindow } = require('electron');
  if (win && !win.isDestroyed()) return win;
  win = new BrowserWindow({
    width: 700,
    height: 550,
    title: 'StickPaint',
    autoHideMenuBar: true,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
    },
  });
  win.loadFile(path.join(__dirname, '..', '..', 'renderer', 'stickpaint.html'));
  win.on('closed', () => {
    win = null;
  });
  return win;
}

async function openPaint() {
  const w = getWindow();
  if (!w.isVisible()) w.show();
  w.focus();
  return true;
}

async function writeInPaint(text, x, y) {
  const w = getWindow();
  if (!w.isVisible()) w.show();
  w.webContents.send('stickpaint:write', { text, x, y });
  drawLog.push(`escribio: "${text}"`);
}

async function drawInPaint(points) {
  if (!Array.isArray(points) || points.length < 2) {
    throw new Error('draw_in_paint necesita al menos 2 puntos');
  }
  const w = getWindow();
  if (!w.isVisible()) w.show();
  w.webContents.send('stickpaint:draw', points);
  drawLog.push(`dibujo un trazo de ${points.length} puntos`);
}

async function setColor(color) {
  const w = getWindow();
  w.webContents.send('stickpaint:color', color);
  drawLog.push(`cambio el color a ${color}`);
}

async function clear() {
  const w = getWindow();
  w.webContents.send('stickpaint:clear');
  drawLog.length = 0;
}

async function readPaint() {
  return drawLog.length ? drawLog.join('\n') : '(el lienzo esta vacio)';
}

module.exports = { openPaint, writeInPaint, drawInPaint, readPaint, setColor, clear };
