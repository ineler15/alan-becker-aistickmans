(() => {
  const canvas = document.getElementById('stickman-canvas');
  const ctx = canvas.getContext('2d');
  const captionEl = document.getElementById('caption');

  const W = canvas.width;
  const H = canvas.height;
  const CX = W / 2;
  const GROUND = H - 30;

  let state = 'idle';
  let color = '#111111';
  let captionTimer = null;

  function setCaption(text) {
    if (captionTimer) clearTimeout(captionTimer);
    if (!text) {
      captionEl.classList.remove('visible');
      captionEl.textContent = '';
      return;
    }
    captionEl.textContent = text;
    captionEl.classList.add('visible');
    captionTimer = setTimeout(() => captionEl.classList.remove('visible'), 4000);
  }

  function drawStick(t, pose) {
    ctx.clearRect(0, 0, W, H);
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.lineWidth = 5;
    ctx.lineCap = 'round';
    ctx.lineJoin = 'round';

    const bob = pose.bob || 0;
    const legLift = pose.legLift || 0;
    const headY = 60 + bob;
    const neckY = headY + 20;
    const hipY = neckY + 70;
    const shoulderY = neckY + 8;

    // cabeza (sin ojos ni boca, como un stickman clasico)
    ctx.beginPath();
    ctx.arc(CX, headY, 18, 0, Math.PI * 2);
    ctx.stroke();

    // cuerpo
    ctx.beginPath();
    ctx.moveTo(CX, neckY);
    ctx.lineTo(CX, hipY);
    ctx.stroke();

    // brazos
    const armSwing = pose.armSwing || 0;
    ctx.beginPath();
    ctx.moveTo(CX, shoulderY);
    ctx.lineTo(CX - 22, shoulderY + 30 + armSwing);
    ctx.moveTo(CX, shoulderY);
    ctx.lineTo(CX + 22, shoulderY + 30 - armSwing);
    ctx.stroke();

    if (pose.leftArmOverride) pose.leftArmOverride(ctx, CX, shoulderY);
    if (pose.rightArmOverride) pose.rightArmOverride(ctx, CX, shoulderY);

    // piernas
    const legSwing = pose.legSwing || 0;
    const legY = GROUND + bob - legLift;
    ctx.beginPath();
    ctx.moveTo(CX, hipY);
    ctx.lineTo(CX - 16 - legSwing, legY);
    ctx.moveTo(CX, hipY);
    ctx.lineTo(CX + 16 + legSwing, legY);
    ctx.stroke();

    if (pose.thoughtDots) {
      ctx.font = '20px sans-serif';
      const dots = Math.floor(t * 2) % 4;
      ctx.fillText('.'.repeat(dots), CX + 22, headY - 24);
    }

    if (pose.zzz) {
      ctx.font = '16px sans-serif';
      const wobble = Math.sin(t * 2) * 3;
      ctx.fillText('Z z z', CX + 14, headY - 22 + wobble);
    }
  }

  function frame(now) {
    const t = now / 1000;
    let pose;

    switch (state) {
      case 'walk':
        pose = {
          bob: Math.sin(t * 8) * 2,
          armSwing: Math.sin(t * 8) * 14,
          legSwing: Math.sin(t * 8) * 14,
        };
        break;
      case 'think':
        pose = {
          bob: Math.sin(t * 1.5) * 1.5,
          thoughtDots: true,
          rightArmOverride: (c, cx, sy) => {
            c.beginPath();
            c.moveTo(cx, sy);
            c.lineTo(cx + 14, sy + 10);
            c.lineTo(cx + 8, sy - 18);
            c.stroke();
          },
        };
        break;
      case 'talk':
        pose = {
          bob: Math.sin(t * 2) * 1.5,
          armSwing: Math.sin(t * 10) * 8,
        };
        break;
      case 'point':
        pose = {
          rightArmOverride: (c, cx, sy) => {
            c.beginPath();
            c.moveTo(cx, sy);
            c.lineTo(cx + 40, sy + 4);
            c.stroke();
          },
        };
        break;
      case 'jump':
        pose = { bob: -Math.abs(Math.sin(t * 5)) * 18 };
        break;
      case 'sit':
        pose = { legLift: 24, armSwing: 4 };
        break;
      case 'wave':
        pose = {
          bob: Math.sin(t * 1.5) * 1.5,
          rightArmOverride: (c, cx, sy) => {
            const wave = Math.sin(t * 8) * 18;
            c.beginPath();
            c.moveTo(cx, sy);
            c.lineTo(cx + 20, sy - 30 + wave);
            c.stroke();
          },
        };
        break;
      case 'sleep':
        pose = { bob: Math.sin(t * 1) * 1, legLift: 10, armSwing: 2, zzz: true };
        break;
      case 'held':
        pose = {
          bob: Math.sin(t * 20) * 1.5,
          armSwing: 0,
          legSwing: Math.sin(t * 12) * 10,
          leftArmOverride: (c, cx, sy) => {
            c.beginPath();
            c.moveTo(cx, sy);
            c.lineTo(cx - 14, sy - 26);
            c.stroke();
          },
          rightArmOverride: (c, cx, sy) => {
            c.beginPath();
            c.moveTo(cx, sy);
            c.lineTo(cx + 14, sy - 26);
            c.stroke();
          },
        };
        break;
      default:
        pose = { bob: Math.sin(t * 1.5) * 2, armSwing: Math.sin(t * 1.5) * 2 };
    }

    drawStick(t, pose);
    requestAnimationFrame(frame);
  }

  requestAnimationFrame(frame);

  window.stickmanAPI.onSetAnimation((payload) => {
    if (payload && payload.state) state = payload.state;
    if (payload && 'caption' in payload) setCaption(payload.caption);
  });

  window.stickmanAPI.onSetColor((newColor) => {
    if (newColor) color = newColor;
  });

  // Genera un icono simple (pose de pie) para la ventana/tray, una sola vez.
  window.addEventListener('load', () => {
    const iconCanvas = document.createElement('canvas');
    iconCanvas.width = 64;
    iconCanvas.height = 64;
    const ictx = iconCanvas.getContext('2d');
    ictx.strokeStyle = color;
    ictx.fillStyle = color;
    ictx.lineWidth = 4;
    ictx.lineCap = 'round';
    ictx.beginPath();
    ictx.arc(32, 14, 9, 0, Math.PI * 2);
    ictx.stroke();
    ictx.beginPath();
    ictx.moveTo(32, 23);
    ictx.lineTo(32, 42);
    ictx.moveTo(32, 28);
    ictx.lineTo(18, 36);
    ictx.moveTo(32, 28);
    ictx.lineTo(46, 36);
    ictx.moveTo(32, 42);
    ictx.lineTo(20, 58);
    ictx.moveTo(32, 42);
    ictx.lineTo(44, 58);
    ictx.stroke();
    window.stickmanAPI.sendIcon(iconCanvas.toDataURL('image/png'));
  });
})();
