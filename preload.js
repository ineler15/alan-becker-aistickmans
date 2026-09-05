const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('stickmanAPI', {
  sendIcon: (dataURL) => ipcRenderer.send('stickman:icon-ready', dataURL),
  onSetAnimation: (callback) => {
    ipcRenderer.on('stickman:set-animation', (_event, payload) => callback(payload));
  },
  onSetColor: (callback) => {
    ipcRenderer.on('stickman:set-color', (_event, color) => callback(color));
  },
  sendChatMessage: (characterId, text) => ipcRenderer.send('stickman:chat-message', { characterId, text }),
  getCharacters: () => ipcRenderer.invoke('stickman:get-characters'),
  onChatDefault: (callback) => {
    ipcRenderer.on('stickman:chat-default', (_event, characterId) => callback(characterId));
  },
  getPcSettings: () => ipcRenderer.invoke('stickman:get-settings'),
  savePcSettings: (settings) => ipcRenderer.send('stickman:save-settings', settings),
  quitApp: () => ipcRenderer.send('stickman:close-app'),
  onCharactersUpdated: (callback) => {
    ipcRenderer.on('stickman:characters-updated', () => callback());
  },
  getPalette: () => ipcRenderer.invoke('stickman:get-palette'),
  createCharacter: (data) => ipcRenderer.send('stickman:create-character', data),
  openCreateCharacterWindow: () => ipcRenderer.send('stickman:open-create-character-window'),
  openEditCharacterWindow: (id) => ipcRenderer.send('stickman:open-edit-character-window', id),
  getCustomCharacter: (id) => ipcRenderer.invoke('stickman:get-custom-character', id),
  updateCharacter: (id, data) => ipcRenderer.send('stickman:update-character', { id, data }),
});
