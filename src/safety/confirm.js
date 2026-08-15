const { dialog } = require('electron');
const path = require('path');
const config = require('../config');

function isInsideWorkspace(targetPath) {
  const resolved = path.resolve(targetPath);
  return resolved.startsWith(config.workspaceDir);
}

async function confirmAction(action, args) {
  const { response } = await dialog.showMessageBox({
    type: 'question',
    buttons: ['Permitir', 'Rechazar'],
    defaultId: 1,
    cancelId: 1,
    title: 'El stickman quiere hacer algo',
    message: `Accion: ${action}`,
    detail: JSON.stringify(args, null, 2),
  });
  return response === 0;
}

module.exports = { confirmAction, isInsideWorkspace };
