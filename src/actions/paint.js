const { exec } = require('child_process');
const path = require('path');
const fs = require('fs');
const { mouse, keyboard, screen, Point, Key, Button, imageResource } = require('@nut-tree-fork/nut-js');
const config = require('../config');

const activeWin = () => import('active-win').then((m) => m.default());

let paintBounds = null;
const TEXT_TOOL_REF = path.join(config.rootDir, 'assets', 'paint-text-tool.png');

function spawnPaint() {
  return new Promise((resolve) => {
    exec('start mspaint', () => resolve());
  });
}

// mspaint.exe allows any number of simultaneous instances - without this check, every
// character (or every retry) that calls openPaint() would spawn yet another window
// instead of reusing the one already open.
function isPaintRunning() {
  return new Promise((resolve) => {
    exec('tasklist /FI "IMAGENAME eq mspaint.exe"', (err, stdout) => {
      resolve(!err && /mspaint\.exe/i.test(stdout));
    });
  });
}

// Paint might already be open but not the focused window (e.g. right after the AI moved
// the mouse elsewhere) - bring it to the front instead of spawning a duplicate.
function focusPaintWindow() {
  return new Promise((resolve) => {
    exec(
      'powershell -NoProfile -Command "(New-Object -ComObject WScript.Shell).AppActivate(\'Paint\')"',
      () => resolve()
    );
  });
}

async function locatePaintWindow(retries = 15) {
  for (let i = 0; i < retries; i++) {
    const win = await activeWin();
    if (win && /paint/i.test(win.title || '') || (win && win.owner && /mspaint/i.test(win.owner.name || ''))) {
      paintBounds = win.bounds;
      return win.bounds;
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  return null;
}

async function openPaint() {
  if (await isPaintRunning()) {
    await focusPaintWindow();
  } else {
    await spawnPaint();
  }
  const bounds = await locatePaintWindow();
  if (!bounds) throw new Error('No se pudo localizar la ventana de Paint');
  return bounds;
}

async function clickTextTool() {
  if (fs.existsSync(TEXT_TOOL_REF)) {
    try {
      const region = await screen.find(imageResource(TEXT_TOOL_REF));
      await mouse.setPosition(new Point(region.left + region.width / 2, region.top + region.height / 2));
      await mouse.leftClick();
      return true;
    } catch {
      // sigue al fallback de coordenadas si no encuentra la imagen de referencia
    }
  }
  if (!paintBounds) return false;
  // Fallback aproximado: icono "Texto" en la cinta clasica de Paint. Requiere calibracion manual.
  await mouse.setPosition(new Point(paintBounds.x + 240, paintBounds.y + 95));
  await mouse.leftClick();
  return true;
}

async function writeInPaint(text) {
  if (!paintBounds) await openPaint();
  await clickTextTool();
  const clickX = paintBounds.x + Math.round(paintBounds.width * 0.3);
  const clickY = paintBounds.y + Math.round(paintBounds.height * 0.45);
  await mouse.setPosition(new Point(clickX, clickY));
  await mouse.leftClick();
  await new Promise((r) => setTimeout(r, 150));
  await keyboard.type(String(text));
  await keyboard.pressKey(Key.Escape);
  await keyboard.releaseKey(Key.Escape);
}

// The canvas sits below Paint's ribbon (same offset readPaint() crops from). Points come
// in as {x,y} percentages (0-100) of that canvas area, so the model can describe a simple
// line drawing without knowing real screen pixels.
const CANVAS_TOP_OFFSET = 110;
const CANVAS_BOTTOM_MARGIN = 40;

async function drawInPaint(points) {
  if (!Array.isArray(points) || points.length < 2) {
    throw new Error('draw_in_paint necesita al menos 2 puntos');
  }
  if (!paintBounds) await openPaint();

  const canvasTop = paintBounds.y + CANVAS_TOP_OFFSET;
  const canvasHeight = paintBounds.height - CANVAS_TOP_OFFSET - CANVAS_BOTTOM_MARGIN;
  const toScreenPoint = (p) =>
    new Point(
      paintBounds.x + Math.round((Math.min(100, Math.max(0, p.x)) / 100) * paintBounds.width),
      canvasTop + Math.round((Math.min(100, Math.max(0, p.y)) / 100) * canvasHeight)
    );

  await mouse.setPosition(toScreenPoint(points[0]));
  await mouse.pressButton(Button.LEFT);
  for (let i = 1; i < points.length; i++) {
    await mouse.setPosition(toScreenPoint(points[i]));
  }
  await mouse.releaseButton(Button.LEFT);
}

async function readPaint() {
  if (!paintBounds) throw new Error('Paint no esta abierto todavia');
  const shotPath = path.join(config.workspaceDir, 'tmp_paint_capture.png');
  const region = {
    left: paintBounds.x,
    top: paintBounds.y + 110,
    width: paintBounds.width,
    height: paintBounds.height - 150,
  };
  await screen.captureRegion('tmp_paint_capture', region.left, region.top, region.width, region.height, undefined, config.workspaceDir);
  const Tesseract = require('tesseract.js');
  const { data } = await Tesseract.recognize(shotPath, 'eng');
  return (data.text || '').trim();
}

module.exports = { openPaint, writeInPaint, readPaint, drawInPaint };
