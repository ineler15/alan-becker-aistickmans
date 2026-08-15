const { ipcRenderer } = require('electron');

const canvas = document.getElementById('canvas');
const ctx = canvas.getContext('2d');
ctx.fillStyle = '#ffffff';
ctx.fillRect(0, 0, canvas.width, canvas.height);
ctx.lineWidth = 3;
ctx.lineCap = 'round';
ctx.strokeStyle = '#111111';
ctx.fillStyle = '#111111';

function toPixels(p) {
  return {
    x: (Math.min(100, Math.max(0, p.x)) / 100) * canvas.width,
    y: (Math.min(100, Math.max(0, p.y)) / 100) * canvas.height,
  };
}

ipcRenderer.on('stickpaint:draw', (_event, points) => {
  if (!Array.isArray(points) || points.length < 2) return;
  ctx.beginPath();
  const first = toPixels(points[0]);
  ctx.moveTo(first.x, first.y);
  for (let i = 1; i < points.length; i++) {
    const p = toPixels(points[i]);
    ctx.lineTo(p.x, p.y);
  }
  ctx.stroke();
});

ipcRenderer.on('stickpaint:write', (_event, { text, x, y }) => {
  ctx.font = '28px sans-serif';
  const pos = toPixels({ x: x ?? 50, y: y ?? 50 });
  ctx.fillText(String(text), pos.x, pos.y);
});

ipcRenderer.on('stickpaint:color', (_event, color) => {
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
});

ipcRenderer.on('stickpaint:clear', () => {
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = ctx.strokeStyle;
});

// Let the user draw with their own mouse too, not just the AI via IPC.
let drawing = false;
canvas.addEventListener('mousedown', (e) => {
  drawing = true;
  ctx.beginPath();
  ctx.moveTo(e.offsetX, e.offsetY);
});
canvas.addEventListener('mousemove', (e) => {
  if (!drawing) return;
  ctx.lineTo(e.offsetX, e.offsetY);
  ctx.stroke();
});
window.addEventListener('mouseup', () => {
  drawing = false;
});
