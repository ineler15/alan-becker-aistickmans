// Draws a simple stickman glyph once and sends it to the main process as the
// tray icon. The stickman itself is no longer drawn in a visible window -
// this hidden window exists only to render this one icon via canvas.
(() => {
  const canvas = document.createElement('canvas');
  canvas.width = 64;
  canvas.height = 64;
  const ctx = canvas.getContext('2d');
  ctx.strokeStyle = '#111111';
  ctx.fillStyle = '#111111';
  ctx.lineWidth = 4;
  ctx.lineCap = 'round';
  ctx.beginPath();
  ctx.arc(32, 14, 9, 0, Math.PI * 2);
  ctx.stroke();
  ctx.beginPath();
  ctx.moveTo(32, 23);
  ctx.lineTo(32, 42);
  ctx.moveTo(32, 28);
  ctx.lineTo(18, 36);
  ctx.moveTo(32, 28);
  ctx.lineTo(46, 36);
  ctx.moveTo(32, 42);
  ctx.lineTo(20, 58);
  ctx.moveTo(32, 42);
  ctx.lineTo(44, 58);
  ctx.stroke();
  window.stickmanAPI.sendIcon(canvas.toDataURL('image/png'));
})();
