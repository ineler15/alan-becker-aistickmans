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
});
