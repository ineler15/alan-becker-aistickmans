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

ipcRenderer.on('stickpaint:draw', (_event, payload) => {
  // Backwards compatible with the old shape (a bare points array, no close/fill).
  const { points, close, fill } = Array.isArray(payload) ? { points: payload } : payload;
  if (!Array.isArray(points) || points.length < 2) return;
  ctx.beginPath();
  const first = toPixels(points[0]);
  ctx.moveTo(first.x, first.y);
  for (let i = 1; i < points.length; i++) {
    const p = toPixels(points[i]);
    ctx.lineTo(p.x, p.y);
  }
  if (close || fill) ctx.closePath();
  if (fill) ctx.fill();
  ctx.stroke();
});

ipcRenderer.on('stickpaint:shape', (_event, { shape, x, y, width, height, fill }) => {
  const center = toPixels({ x, y });
  // width/height are given as percent-of-canvas too, same convention as point coordinates -
  // scaled against canvas width/height directly rather than toPixels (which clamps to a point).
  const w = (Math.max(0, width) / 100) * canvas.width;
  const h = (Math.max(0, height) / 100) * canvas.height;
  ctx.beginPath();
  if (shape === 'circle') {
    ctx.arc(center.x, center.y, Math.max(w, h) / 2, 0, Math.PI * 2);
  } else if (shape === 'ellipse') {
    ctx.ellipse(center.x, center.y, w / 2, h / 2, 0, 0, Math.PI * 2);
  } else {
    ctx.rect(center.x - w / 2, center.y - h / 2, w, h);
  }
  if (fill) ctx.fill();
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
