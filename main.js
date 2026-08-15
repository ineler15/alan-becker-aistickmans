const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, globalShortcut, shell, session } = require('electron');
const path = require('path');
const fs = require('fs');
const { spawn, exec } = require('child_process');
const config = require('./src/config');
const agentLoop = require('./src/loop/agentLoop');
const userMessage = require('./src/loop/userMessage');
const webcam = require('./src/loop/webcam');
const CHARACTERS = require('./src/characters');
const peerServer = require('./src/net/peerServer');

// Without this, launching the app while it's already running spins up a second full set of
// electron.exe processes and a second javaw.exe Shimeji, fighting over the same hotkeys/webcam -
// the exact "duplicate instance" bug this session kept hitting and fixing by hand with taskkill.
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
}

let shimejiProcess = null;

// Clears each character's short-term state (position/pose, recent action log, pending
// commands) on every launch so a restart is a real fresh start - but leaves
// personality-<id>.json and memory-<id>.json alone, since those are meant to persist.
function resetShortTermState() {
  for (const character of CHARACTERS) {
    for (const prefix of ['history-', 'ai-status-', 'ai-command-']) {
      const file = path.join(config.workspaceDir, `${prefix}${character.id}.json`);
      fs.promises.unlink(file).catch(() => {});
    }
  }
}

function isShimejiRunning() {
  return new Promise((resolve) => {
    exec('tasklist /FI "IMAGENAME eq javaw.exe"', (err, stdout) => {
      resolve(!err && /javaw\.exe/i.test(stdout));
    });
  });
}

async function startShimeji() {
  if (await isShimejiRunning()) return;
  shimejiProcess = spawn(config.shimeji.javaPath, ['-jar', config.shimeji.jarPath], {
    cwd: path.dirname(config.shimeji.jarPath),
    detached: true,
    stdio: 'ignore',
  });
  shimejiProcess.on('error', (err) => {
    console.error('No se pudo iniciar el stickman visual (Java):', err.message);
  });
  shimejiProcess.unref();
}

// The stickman is no longer drawn in its own Electron window - the AI
// controls a Shimeji-ee character on the Java side instead. Electron just
// hosts the decision loop and a tray icon for pausing it. This hidden
// window's only job is rendering that tray icon once via canvas.
let iconWindow = null;
let tray = null;

function createIconWindow() {
  iconWindow = new BrowserWindow({
    show: false,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  iconWindow.loadFile(path.join(__dirname, 'renderer', 'icon.html'));
}

// Hidden window whose only job is grabbing a webcam frame every ~2s and pushing it to
// webcam.js - so the characters can react to the user in front of the camera, not just
// what's on screen. Auto-approves the 'media' permission request since this is the app's
// own first-party page, not remote content.
let webcamWindow = null;

function createWebcamWindow() {
  session.defaultSession.setPermissionRequestHandler((_webContents, permission, callback) => {
    callback(permission === 'media');
  });
  webcamWindow = new BrowserWindow({
    show: false,
    webPreferences: {
      contextIsolation: false,
      nodeIntegration: true,
    },
  });
  webcamWindow.loadFile(path.join(__dirname, 'renderer', 'webcam.html'));
}

// Windows doesn't always hand foreground focus to a window created from a background
// global-shortcut callback - forcing app.focus({steal:true}) plus a brief alwaysOnTop
// makes the chat window actually show up on top instead of opening silently behind others.
function bringToFront(win) {
  if (!win || win.isDestroyed()) return;
  win.show();
  win.setAlwaysOnTop(true);
  app.focus({ steal: true });
  win.focus();
  setTimeout(() => {
    if (!win.isDestroyed()) win.setAlwaysOnTop(false);
  }, 300);
}

let chatWindow = null;
// Which character the chat window's dropdown should default to (set right before
// opening it from a per-character tray menu item).
let chatDefaultCharacterId = CHARACTERS[0] ? CHARACTERS[0].id : null;

function openChatWindow(defaultCharacterId) {
  if (defaultCharacterId) chatDefaultCharacterId = defaultCharacterId;
  if (chatWindow) {
    chatWindow.webContents.send('stickman:chat-default', chatDefaultCharacterId);
    bringToFront(chatWindow);
    return;
  }
  chatWindow = new BrowserWindow({
    width: 360,
    height: 240,
    title: 'Hablar con el stickman',
    autoHideMenuBar: true,
    alwaysOnTop: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  chatWindow.loadFile(path.join(__dirname, 'renderer', 'chat.html'));
  chatWindow.once('ready-to-show', () => bringToFront(chatWindow));
  chatWindow.on('closed', () => {
    chatWindow = null;
  });
}

function buildTrayMenu() {
  return Menu.buildFromTemplate([
    {
      label: agentLoop.isPaused() ? 'Reanudar' : 'Pausar',
      click: () => {
        agentLoop.togglePause();
        tray.setContextMenu(buildTrayMenu());
      },
    },
    ...CHARACTERS.map((c) => ({
      label: `Hablar con ${c.displayName}`,
      click: () => openChatWindow(c.id),
    })),
    {
      label: 'Abrir carpeta workspace',
      click: () => shell.openPath(config.workspaceDir),
    },
    { type: 'separator' },
    { label: 'Salir', click: () => app.quit() },
  ]);
}

function setAppIcon(dataURL) {
  const icon = nativeImage.createFromDataURL(dataURL);
  if (!tray) {
    tray = new Tray(icon);
    tray.setToolTip('Stickman IA');
    tray.setContextMenu(buildTrayMenu());
  } else {
    tray.setImage(icon);
  }
}

app.whenReady().then(() => {
  resetShortTermState();
  createIconWindow();
  createWebcamWindow();

  ipcMain.on('stickman:icon-ready', (_event, dataURL) => {
    setAppIcon(dataURL);
  });

  ipcMain.on('webcam:frame', (_event, frameBase64) => {
    webcam.set(frameBase64);
  });

  ipcMain.on('webcam:error', (_event, message) => {
    console.warn('Webcam no disponible:', message);
  });

  ipcMain.handle('stickman:get-characters', () => ({
    characters: CHARACTERS.map((c) => ({ id: c.id, displayName: c.displayName })),
    defaultCharacterId: chatDefaultCharacterId,
  }));

  ipcMain.on('stickman:chat-message', (_event, { characterId, text }) => {
    const trimmed = typeof text === 'string' ? text.trim() : '';
    if (!trimmed) return;
    if (characterId === '__all__') {
      // Group chat: same message reaches every active character's own context this round,
      // instead of being aimed at just one - each still replies in its own voice/personality.
      for (const character of CHARACTERS) userMessage.set(character.id, trimmed);
      agentLoop.wakeNow();
    } else if (CHARACTERS.some((c) => c.id === characterId)) {
      userMessage.set(characterId, trimmed);
      agentLoop.wakeNow();
    }
  });

  const registered = globalShortcut.register(config.pauseHotkey, () => {
    agentLoop.togglePause();
    if (tray) tray.setContextMenu(buildTrayMenu());
  });
  if (!registered) {
    console.warn(`No se pudo registrar el hotkey global: ${config.pauseHotkey}`);
  }

  const chatRegistered = globalShortcut.register('Control+Shift+H', () => {
    openChatWindow();
  });
  if (!chatRegistered) {
    console.warn('No se pudo registrar el hotkey global para el chat: Control+Shift+H');
  }

  startShimeji();
  agentLoop.start();
  peerServer.start();
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
  agentLoop.stop();
});

app.on('window-all-closed', () => {
  app.quit();
});
