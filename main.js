const { app, BrowserWindow, Tray, Menu, nativeImage, ipcMain, globalShortcut, shell, session } = require('electron');
const path = require('path');
const fs = require('fs');
const config = require('./src/config');
const agentLoop = require('./src/loop/agentLoop');
const userMessage = require('./src/loop/userMessage');
const webcam = require('./src/loop/webcam');
const CHARACTERS = require('./src/characters');
const peerServer = require('./src/net/peerServer');
const pcSettings = require('./src/pcSettings');
const customCharacters = require('./src/customCharacters');
const jsCharacterEngine = require('./src/jsEngine/jsCharacterEngine');

// Merge any custom characters saved in a previous session into CHARACTERS.ALL before the
// settings window (and pcSettings.load()'s enabledIds default) ever reads it.
customCharacters.loadIntoRoster();

// Without this, launching the app while it's already running spins up a second full set of
// electron.exe processes and character windows, fighting over the same hotkeys/webcam - the
// exact "duplicate instance" bug this session kept hitting and fixing by hand with taskkill.
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
}

// Clears each character's short-term state (position/pose, recent action log) on every launch
// so a restart is a real fresh start - but leaves personality-<id>.json and memory-<id>.json
// alone, since those are meant to persist.
function resetShortTermState() {
  for (const character of CHARACTERS) {
    for (const prefix of ['history-']) {
      const file = path.join(config.workspaceDir, `${prefix}${character.id}.json`);
      fs.promises.unlink(file).catch(() => {});
    }
  }
}

// Each character is its own small transparent always-on-top window (renderer/character.html),
// drawn from its actual Stick Nodes rig data and driven by jsEngine/characterState.js - the same
// physics/animation state machine already validated on Android. Replaces the old Shimeji-ee Java
// process entirely (see jsCharacterEngine.js for the drag-detection/positioning details).
function startCharacterEngine() {
  jsCharacterEngine.start(CHARACTERS);
}

// Electron just hosts the decision loop, the character windows, and a tray icon for pausing it.
// This hidden window's only job is rendering that tray icon once via canvas.
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

// Shown once at startup, before the character windows appear - lets the user pick a shared AI
// provider and per-character API keys instead of hand-editing .env. startCharacterEngine()/
// agentLoop.start() only run once this window sends 'stickman:save-settings' (see app.whenReady() below).
let settingsWindow = null;

function createSettingsWindow() {
  settingsWindow = new BrowserWindow({
    width: 480,
    height: 560,
    title: 'Configuracion - Stickman AI',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  settingsWindow.loadFile(path.join(__dirname, 'renderer', 'settings.html'));
  settingsWindow.on('closed', () => {
    settingsWindow = null;
  });
}

// Dedicated window for "crear tu propio stickman" - separate from settingsWindow so it can be
// opened both pre-launch (from a button in settings.html) and later from the tray, without
// disturbing whatever the user already has entered in the settings form.
let createCharacterWindow = null;

// editId set means the same window/page is reused to edit an existing custom character instead of
// creating a new one - see createCharacter.js's init(), which reads ?editId= to switch modes.
function openCreateCharacterWindow(editId) {
  if (createCharacterWindow) {
    bringToFront(createCharacterWindow);
    return;
  }
  createCharacterWindow = new BrowserWindow({
    width: 420,
    height: 420,
    title: editId ? 'Editar stickman' : 'Crear tu propio stickman',
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
    },
  });
  createCharacterWindow.loadFile(path.join(__dirname, 'renderer', 'createCharacter.html'), {
    query: editId ? { editId } : {},
  });
  createCharacterWindow.once('ready-to-show', () => bringToFront(createCharacterWindow));
  createCharacterWindow.on('closed', () => {
    createCharacterWindow = null;
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
    { type: 'separator' },
    {
      label: 'Crear un stickman nuevo',
      click: () => openCreateCharacterWindow(),
    },
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

  ipcMain.handle('stickman:get-settings', () => ({
    providers: pcSettings.PROVIDERS,
    characters: CHARACTERS.ALL.map((c) => ({
      id: c.id,
      displayName: c.displayName,
      // Only custom characters (see customCharacters.js) have appearance fields to edit - the
      // vanilla/built-in ones (Red, TCO, etc.) don't get an "Editar" button in settings.js.
      isCustom: !!customCharacters.metaFor(c.id),
    })),
    settings: pcSettings.load(),
  }));

  ipcMain.on('stickman:save-settings', (_event, settings) => {
    pcSettings.save(settings);
    pcSettings.applyToEnv(settings);
    pcSettings.applyEnabledCharacters(settings);
    pcSettings.applyPartners(settings);
    pcSettings.applyContexts(settings);
    if (settingsWindow) settingsWindow.close();
    startCharacterEngine();
    agentLoop.start();
  });

  ipcMain.on('stickman:open-create-character-window', () => {
    openCreateCharacterWindow();
  });

  ipcMain.on('stickman:open-edit-character-window', (_event, id) => {
    openCreateCharacterWindow(id);
  });

  ipcMain.handle('stickman:get-palette', () => customCharacters.PALETTE);

  ipcMain.handle('stickman:get-custom-character', (_event, id) => customCharacters.getRecord(id));

  ipcMain.on('stickman:create-character', (_event, data) => {
    customCharacters.create(data);
    if (createCharacterWindow) createCharacterWindow.close();
    // Reflect the new character in whatever's already open/running - the settings window (if
    // the user created a character mid pre-launch-setup) and the tray's "Hablar con" list. The
    // new character isn't enabled by default (same as any other unchecked one in settings), so
    // this doesn't spin up a visible window for it until the user ticks its checkbox and saves.
    if (settingsWindow) settingsWindow.webContents.send('stickman:characters-updated');
    if (tray) tray.setContextMenu(buildTrayMenu());
  });

  ipcMain.on('stickman:update-character', (_event, { id, data }) => {
    customCharacters.update(id, data);
    if (createCharacterWindow) createCharacterWindow.close();
    // The running character engine (if this character is currently enabled) still has the OLD
    // rig/appearance loaded in its window - same "takes effect after the next
    // Configuracion save/restart" tradeoff as every other settings.js change, not worth a special
    // case just for this.
    if (settingsWindow) settingsWindow.webContents.send('stickman:characters-updated');
    if (tray) tray.setContextMenu(buildTrayMenu());
  });

  createSettingsWindow();
  peerServer.start();
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
  agentLoop.stop();
  jsCharacterEngine.stop();
});

app.on('window-all-closed', () => {
  app.quit();
});
