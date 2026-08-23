// Shared face/gender-accessory drawing, used by the real per-character renderer (character.js),
// the "crear tu propio stickman" preview (createCharacter.js), and mirrored on Android in
// RigView.kt. Eyes and mouth are independent axes (not a single bundled "emotion") so the AI can
// mix any pair - e.g. wide eyes + a frown - instead of only 6 fixed combos. Geometry is relative
// to the head's own center/radius in already-transformed canvas space, so a character looks the
// same on both platforms and in the creator's preview as it does live.
(function () {
  const EYE_STYLES = ['normal', 'wide', 'angry', 'heart'];
  const MOUTH_STYLES = ['neutral', 'smile', 'frown', 'open', 'angry'];

  function drawEyes(ctx, cx, cy, r, style) {
    const dx = r * 0.35;
    const dy = -r * 0.1;
    const eyeR = style === 'wide' ? r * 0.22 : r * 0.13;
    ctx.fillStyle = '#000';
    if (style === 'heart') {
      for (const sign of [-1, 1]) {
        const ex = cx + sign * dx;
        const ey = cy + dy;
        const s = r * 0.16;
        ctx.beginPath();
        ctx.arc(ex - s * 0.5, ey, s * 0.5, 0, Math.PI * 2);
        ctx.arc(ex + s * 0.5, ey, s * 0.5, 0, Math.PI * 2);
        ctx.moveTo(ex - s, ey + s * 0.15);
        ctx.lineTo(ex, ey + s * 1.2);
        ctx.lineTo(ex + s, ey + s * 0.15);
        ctx.closePath();
        ctx.fill();
      }
      return;
    }
    for (const sign of [-1, 1]) {
      ctx.beginPath();
      ctx.arc(cx + sign * dx, cy + dy, eyeR, 0, Math.PI * 2);
      ctx.fill();
    }
    if (style === 'angry') {
      ctx.strokeStyle = '#000';
      ctx.lineWidth = Math.max(1, r * 0.06);
      ctx.lineCap = 'round';
      for (const sign of [-1, 1]) {
        ctx.beginPath();
        ctx.moveTo(cx + sign * (dx - eyeR * 1.4), cy + dy - eyeR * 1.6);
        ctx.lineTo(cx + sign * (dx + eyeR * 1.4), cy + dy - eyeR * 0.4);
        ctx.stroke();
      }
    }
  }

  function drawMouth(ctx, cx, cy, r, style) {
    const my = cy + r * 0.35;
    ctx.strokeStyle = '#000';
    ctx.lineWidth = Math.max(1, r * 0.08);
    ctx.lineCap = 'round';
    ctx.beginPath();
    switch (style) {
      case 'smile':
        ctx.arc(cx, my - r * 0.15, r * 0.32, 0.15 * Math.PI, 0.85 * Math.PI);
        ctx.stroke();
        break;
      case 'frown':
        ctx.arc(cx, my + r * 0.35, r * 0.32, 1.15 * Math.PI, 1.85 * Math.PI);
        ctx.stroke();
        break;
      case 'angry':
        ctx.moveTo(cx - r * 0.28, my + r * 0.05);
        ctx.lineTo(cx, my - r * 0.08);
        ctx.lineTo(cx + r * 0.28, my + r * 0.05);
        ctx.stroke();
        break;
      case 'open':
        ctx.fillStyle = '#000';
        ctx.arc(cx, my, r * 0.14, 0, Math.PI * 2);
        ctx.fill();
        break;
      default:
        ctx.moveTo(cx - r * 0.22, my);
        ctx.lineTo(cx + r * 0.22, my);
        ctx.stroke();
    }
  }

  function drawFace(ctx, cx, cy, r, eyeStyle, mouthStyle) {
    drawEyes(ctx, cx, cy, r, EYE_STYLES.includes(eyeStyle) ? eyeStyle : 'normal');
    drawMouth(ctx, cx, cy, r, MOUTH_STYLES.includes(mouthStyle) ? mouthStyle : 'neutral');
  }

  // Optional head accessory, freely chosen at character creation (see src/customCharacters.js) -
  // independent of gender, classic minimal stick-figure pictogram convention. 'none' draws
  // nothing.
  function drawBow(ctx, cx, cy, r) {
    const by = cy - r * 0.95;
    const wing = r * 0.3;
    ctx.fillStyle = '#e0409a';
    for (const sign of [-1, 1]) {
      ctx.beginPath();
      ctx.moveTo(cx, by);
      ctx.lineTo(cx + sign * wing, by - wing * 0.6);
      ctx.lineTo(cx + sign * wing, by + wing * 0.6);
      ctx.closePath();
      ctx.fill();
    }
    ctx.beginPath();
    ctx.arc(cx, by, wing * 0.28, 0, Math.PI * 2);
    ctx.fill();
  }

  function drawHair(ctx, cx, cy, r) {
    ctx.fillStyle = '#3a2a1a';
    ctx.beginPath();
    ctx.arc(cx, cy - r * 0.35, r * 1.05, Math.PI, 0);
    ctx.closePath();
    ctx.fill();
    for (const sign of [-1, 0, 1]) {
      ctx.beginPath();
      ctx.moveTo(cx + sign * r * 0.55, cy - r * 0.75);
      ctx.lineTo(cx + sign * r * 0.7, cy - r * 1.25);
      ctx.lineTo(cx + sign * r * 0.35, cy - r * 0.8);
      ctx.closePath();
      ctx.fill();
    }
  }

  function drawAccessory(ctx, cx, cy, r, accessory) {
    if (accessory === 'bow') drawBow(ctx, cx, cy, r);
    else if (accessory === 'hair') drawHair(ctx, cx, cy, r);
  }

  window.FaceRenderer = { EYE_STYLES, MOUTH_STYLES, drawFace, drawAccessory };
})();
