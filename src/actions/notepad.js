const { keyboard, Key } = require('@nut-tree-fork/nut-js');
const { clipboard } = require('electron');

const activeWin = () => import('active-win').then((m) => m.default());

function isNotepadWindow(win) {
  if (!win) return false;
  const ownerName = (win.owner && win.owner.name) || '';
  const title = win.title || '';
  return /notepad/i.test(ownerName) || /bloc de notas|notepad/i.test(title);
}

/** Reads the text of the Notepad window, if it's the currently active/focused window. */
async function readNotepad() {
  const win = await activeWin();
  if (!isNotepadWindow(win)) {
    throw new Error('Notepad no es la ventana activa en este momento');
  }

  // Select-all + copy is more reliable than OCR for plain text, and Electron's clipboard
  // is already available in this process. Restore whatever was on the clipboard after,
  // so this doesn't clobber something the user just copied.
  const previousClipboard = clipboard.readText();
  await keyboard.pressKey(Key.LeftControl, Key.A);
  await keyboard.releaseKey(Key.LeftControl, Key.A);
  await new Promise((r) => setTimeout(r, 100));
  await keyboard.pressKey(Key.LeftControl, Key.C);
  await keyboard.releaseKey(Key.LeftControl, Key.C);
  await new Promise((r) => setTimeout(r, 100));

  const text = clipboard.readText();
  clipboard.writeText(previousClipboard);
  return text;
}

module.exports = { readNotepad };
