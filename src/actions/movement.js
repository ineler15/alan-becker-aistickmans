const { screen } = require('electron');

let win = null;

function init(mainWindow) {
  win = mainWindow;
}

function walkTo(x, y, durationMs = 1500) {
  return new Promise((resolve) => {
    if (!win) return resolve('sin ventana');
    const [startX, startY] = win.getPosition();
    const targetX = Math.round(Number(x));
    const targetY = Math.round(Number(y));
    const steps = Math.max(1, Math.round(durationMs / 30));
    let i = 0;

    win.__stickmanProgrammaticMove = true;
    win.webContents.send('stickman:set-animation', { state: 'walk' });

    const interval = setInterval(() => {
      i++;
      const t = i / steps;
      const curX = Math.round(startX + (targetX - startX) * t);
      const curY = Math.round(startY + (targetY - startY) * t);
      win.setPosition(curX, curY);
      if (i >= steps) {
        clearInterval(interval);
        win.__stickmanProgrammaticMove = false;
        win.webContents.send('stickman:set-animation', { state: 'idle' });
        resolve(`camino a (${targetX}, ${targetY})`);
      }
    }, 30);
  });
}

function randomWalk() {
  if (!win) return Promise.resolve('sin ventana');
  const display = screen.getPrimaryDisplay();
  const { width, height } = display.workAreaSize;
  const { width: winWidth, height: winHeight } = win.getBounds();
  const x = Math.round(Math.random() * Math.max(0, width - winWidth));
  const y = Math.round(Math.random() * Math.max(0, height - winHeight));
  return walkTo(x, y, 1200 + Math.random() * 1000);
}

module.exports = { init, walkTo, randomWalk };
