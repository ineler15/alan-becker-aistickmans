// Generic per-character rig-rendering window - the real replacement for Shimeji's sprite
// rendering (see renderer/rig.js for the original single-character proof of concept this grew
// out of). One of these BrowserWindows exists per enabled character (see main.js's
// jsCharacterEngine), each loaded with ?id=<characterId> and kept positioned by the main process
// to track that character's CharacterState (src/jsEngine/characterState.js). Pose math itself
// lives in poseLibrary.js (loaded before this script, exposes window.PoseLibrary).

const { ipcRenderer } = require('electron');

const params = new URLSearchParams(location.search);
const characterId = params.get('id');
// A custom character's own id isn't in poseLibrary.js's PROFILE_BY_ID - jsCharacterEngine.js
// resolves which built-in profile its rig was cloned from (Red/TCO) and passes it here so it
// actually animates. Absent for built-in characters, which just use their own id as before.
const poseId = params.get('poseProfile') || characterId;
// Face/accessory - only ever set for custom characters (see customCharacters.js's metaFor());
// built-in characters get neither, same as before this existed. Accessory (hair/bow/none) is a
// free choice independent of gender.
const hasFace = params.get('hasFace') === '1';
const accessory = params.get('accessory') || 'none';
let currentEyeStyle = 'normal';
let currentMouthStyle = 'neutral';
// The rig's own visual box (jsCharacterEngine.js's RIG_WIDTH/RIG_HEIGHT) - kept separate from the
// actual (larger) window size, which pads out extra room for the speech bubble. Falls back to the
// window's own size if launched without these (e.g. the older standalone rig-test page).
const rigWidth = Number(params.get('rw')) || window.innerWidth;
const rigHeight = Number(params.get('rh')) || window.innerHeight;

// Food/kitchen-prop extras. foodBites puts this window in "food" mode: the rig (e.g. pizza.json) is
// drawn clipped to a shrinking wedge so each bite visibly removes a piece, plus a per-bite jiggle.
// drink mode rotates the whole rig (a cup) by the tilt angle, synced with swallow timings.
const foodBitesTotal = Number(params.get('foodBites')) || 0;
const drinkMode = params.get('drink') === '1';
let foodBitesLeft = foodBitesTotal;
let foodJiggleRad = 0;
let drinkTiltDeg = 0;

// Legacy sprite rendering mode: jsCharacterEngine sets ?renderMode=sprites when this window's
// app build is the sprite (legacy) variant, so poses draw from renderer/sprites/<id>/ PNGs
// instead of the rig (mirrors Android's SpriteSet path). Custom characters have no sprites, so
// they report not-ready and this window falls back to rig rendering for them.
const spriteModeRequested = params.get('renderMode') === 'sprites';
let spriteSet = null;
let currentSprite = null;

const canvas = document.getElementById('c');
const ctx = canvas.getContext('2d');
const speechEl = document.getElementById('speech');

const CIRCLE_RADIUS_FACTOR = 0.65;
let figure = null;
let currentPose = new Map();
let lookRight = true;
// Survival stats + death state, sent along with each pose when the life system is on - drawn as
// three mini bars over the canvas (vida roja, hambre naranja, sed azul) and a red X when dead.
let lastStats = null;
let dead = false;

function layout(node, parentAngleDeg, parentEnd, path, acc) {
  const isRoot = node.t === 'RootNode';
  const override = currentPose.get(path.join(','));
  const localAngle = override !== undefined ? override : node.a;
  const globalAngleDeg = isRoot ? localAngle : parentAngleDeg + localAngle;
  const start = isRoot ? { x: 0, y: 0 } : parentEnd;
  const rad = (globalAngleDeg * Math.PI) / 180;
  const localX = isRoot ? 0 : node.l * Math.cos(rad) * node.sc;
  const localY = isRoot ? 0 : -node.l * Math.sin(rad) * node.sc;
  const end = { x: start.x + localX, y: start.y + localY };
  if (!isRoot) acc.push({ node, start, end, curveRadius: node.cr || 0 });
  (node.ch || []).forEach((child, i) => layout(child, globalAngleDeg, end, path.concat(i), acc));
  return acc;
}

function circleCenter(bone, radius) {
  const dx = bone.end.x - bone.start.x;
  const dy = bone.end.y - bone.start.y;
  const dist = Math.hypot(dx, dy) || 1e-3;
  return { x: bone.start.x + (dx / dist) * radius, y: bone.start.y + (dy / dist) * radius };
}

// Unit vector perpendicular to a rig-space segment (used for polygon base/width extents).
function perpDir(dx, dy) {
  const dist = Math.hypot(dx, dy) || 1e-3;
  return { x: -dy / dist, y: dx / dist };
}

// Polygons (Triangle/Ellipse/Trapezoid, added for the kitchen/food props) corner helpers.
// Trapezoid start half-width uses trapezoid_thickness_start when use_trapezoid_thickness_start,
// else the node's plain thickness - same for the end. Ellipse draws centered on the segment with
// rx = length/2 along the bone and ry = thickness/2 across, matching Stick Nodes' proportions.
function trapezoidHalfStart(node) {
  return (node.uS && node.thS > 0 ? node.thS : node.th) / 2;
}
function trapezoidHalfEnd(node) {
  return (node.uE && node.thE > 0 ? node.thE : node.th) / 2;
}
function triangleCorners(bone, node) {
  const p = perpDir(bone.end.x - bone.start.x, bone.end.y - bone.start.y);
  const h = (node.th || 1) / 2;
  if (node.triU) {
    return [
      { x: bone.start.x - p.x * h, y: bone.start.y - p.y * h },
      { x: bone.start.x + p.x * h, y: bone.start.y + p.y * h },
      bone.end,
    ];
  }
  if (node.tri === 'RightTriangle') {
    const s = node.triF ? -1 : 1;
    return [bone.start, { x: bone.start.x + p.x * h * s, y: bone.start.y + p.y * h * s }, bone.end];
  }
  return [
    { x: bone.start.x - p.x * h, y: bone.start.y - p.y * h },
    { x: bone.start.x + p.x * h, y: bone.start.y + p.y * h },
    bone.end,
  ];
}
function trapezoidCorners(bone, node) {
  const p = perpDir(bone.end.x - bone.start.x, bone.end.y - bone.start.y);
  const hs = trapezoidHalfStart(node);
  const he = trapezoidHalfEnd(node);
  return [
    { x: bone.start.x + p.x * hs, y: bone.start.y + p.y * hs },
    { x: bone.start.x - p.x * hs, y: bone.start.y - p.y * hs },
    { x: bone.end.x - p.x * he, y: bone.end.y - p.y * he },
    { x: bone.end.x + p.x * he, y: bone.end.y + p.y * he },
  ];
}

function bounds(bones) {
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  const inc = (x, y) => {
    if (x < minX) minX = x;
    if (x > maxX) maxX = x;
    if (y < minY) minY = y;
    if (y > maxY) maxY = y;
  };
  for (const bone of bones) {
    if (bone.node.t === 'Circle' || bone.node.t === 'FilledCircle') {
      const r = Math.max(2, bone.node.l * CIRCLE_RADIUS_FACTOR);
      const c = circleCenter(bone, r);
      inc(c.x - r, c.y - r);
      inc(c.x + r, c.y + r);
    } else if (bone.node.t === 'Ellipse') {
      const rx = Math.max(1, bone.node.l / 2);
      const ry = Math.max(1, bone.node.th / 2);
      const c = { x: (bone.start.x + bone.end.x) / 2, y: (bone.start.y + bone.end.y) / 2 };
      inc(c.x - rx, c.y - ry);
      inc(c.x + rx, c.y + ry);
    } else if (bone.node.t === 'Triangle') {
      for (const corner of triangleCorners(bone, bone.node)) inc(corner.x, corner.y);
    } else if (bone.node.t === 'Trapezoid') {
      for (const corner of trapezoidCorners(bone, bone.node)) inc(corner.x, corner.y);
    } else {
      inc(bone.start.x, bone.start.y);
      inc(bone.end.x, bone.end.y);
    }
  }
  if (minX > maxX) return { x: 0, y: 0, w: 1, h: 1 };
  return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
}

function colorCss(rgba) {
  return `rgba(${rgba[0]},${rgba[1]},${rgba[2]},${rgba[3] / 255})`;
}

// Locked to a fixed bounding box so the character doesn't visibly grow/shrink switching between
// poses of different heights - same fix as the Android renderer (RigView.kt). NOT just the rest
// pose's own bounds though: fallPose swings arms/legs far past rest (+-60/30 degrees), and with
// only 12% padding that stuck out past the canvas edge and got clipped - looked like the fall
// animation was "bugging out". Union rest bounds with every pose's bounds up front instead, so
// the scale-fit has room for the most extreme one without needing to special-case fall.
let maxBoundsCache = null;
function maxBounds() {
  if (maxBoundsCache) return maxBoundsCache;
  const restPose = currentPose;
  let acc = null;
  const union = (a, b) => {
    if (!a) return b;
    const minX = Math.min(a.x, b.x);
    const minY = Math.min(a.y, b.y);
    const maxX = Math.max(a.x + a.w, b.x + b.w);
    const maxY = Math.max(a.y + a.h, b.y + b.h);
    return { x: minX, y: minY, w: maxX - minX, h: maxY - minY };
  };
  const kinds = ['stand', 'sit', 'walk', 'run', 'bounce', 'trip', 'fall', 'pinch', 'angry', 'sleep', 'tired', 'chew', 'drink'];
  // Several poses swing with `frame` on a sine wave (walk/run/bounce/trip/pinch/angry/sleep,
  // periods up to 20) - frame 0 alone would miss their peak amplitude entirely (sin(0) = 0).
  // Sampling a full 20-frame span covers every period's peak regardless of which kind it is.
  for (const kind of kinds) {
    for (let frame = 0; frame < 20; frame++) {
      currentPose = window.PoseLibrary.forDescriptor({ kind, frame }, poseId);
      acc = union(acc, bounds(layout(figure.root, 0, { x: 0, y: 0 }, [], [])));
    }
  }
  currentPose = restPose;
  maxBoundsCache = acc || bounds(layout(figure.root, 0, { x: 0, y: 0 }, [], []));
  return maxBoundsCache;
}

function draw() {
  if (!figure) return;
  const bones = layout(figure.root, 0, { x: 0, y: 0 }, [], []);
  if (!bones.length) return;
  const rb = maxBounds();
  const padding = canvas.width * 0.12;
  const scale = Math.min(
    (canvas.width - padding * 2) / Math.max(rb.w, 1),
    (canvas.height - padding * 2) / Math.max(rb.h, 1)
  );
  const b = bounds(bones);
  const offX = canvas.width / 2 - (b.x + b.w / 2) * scale;
  const offY = canvas.height / 2 - (b.y + b.h / 2) * scale;
  const tx = (p) => ({ x: p.x * scale + offX, y: p.y * scale + offY });

  ctx.clearRect(0, 0, canvas.width, canvas.height);

  // Food mode: clip the whole rig draw to a shrinking wedge (each bite removes a slice) and jiggle
  // the rig slightly per bite; drink mode rotates the cup to match the swallow timing. Both applied
  // around the current rig's center so the rotation/clip stay anchored regardless of pose.
  let boundsCenter = null;
  if (foodBitesTotal > 0 || (drinkMode && drinkTiltDeg)) {
    boundsCenter = tx({ x: rb.x + rb.w / 2, y: rb.y + rb.h / 2 });
    ctx.save();
    if (foodJiggleRad) {
      ctx.translate(boundsCenter.x, boundsCenter.y);
      ctx.rotate(foodJiggleRad);
      ctx.translate(-boundsCenter.x, -boundsCenter.y);
    }
    if (drinkMode && drinkTiltDeg) {
      ctx.translate(boundsCenter.x, boundsCenter.y);
      ctx.rotate((drinkTiltDeg * Math.PI) / 180);
      ctx.translate(-boundsCenter.x, -boundsCenter.y);
    }
    if (foodBitesTotal > 0) {
      const radius = (Math.max(rb.w, rb.h) / 2) * scale + 4;
      const remaining = Math.min(1, Math.max(0, foodBitesLeft / foodBitesTotal));
      ctx.beginPath();
      ctx.moveTo(boundsCenter.x, boundsCenter.y);
      ctx.arc(boundsCenter.x, boundsCenter.y, radius, -Math.PI / 2, -Math.PI / 2 + remaining * Math.PI * 2, false);
      ctx.closePath();
      ctx.clip();
    }
  }

  // The head is always the deepest/last-drawn thing in the tree in both rig templates (see
  // customCharacters.js) - a plain Circle/FilledCircle node for the "normal" model, or the final
  // curveRadius ring chain for the "hollow" one. Capturing whichever is drawn LAST instead of
  // adding separate head-detection logic means the face/accessory always lands in the right
  // place for either template with no extra bookkeeping.
  let headAnchor = null;

  const consumed = new Set();
  for (let i = 0; i < bones.length; i++) {
    if (consumed.has(i)) continue;
    const bone = bones[i];
    const node = bone.node;
    const color = node.usc ? colorCss(node.c) : colorCss(figure.color);

    if (node.t === 'Circle' || node.t === 'FilledCircle') {
      const modelR = Math.max(2, node.l * CIRCLE_RADIUS_FACTOR);
      const center = tx(circleCenter(bone, modelR));
      const r = modelR * scale;
      ctx.beginPath();
      ctx.arc(center.x, center.y, r, 0, Math.PI * 2);
      if (!node.hollow) {
        ctx.fillStyle = color;
        ctx.fill();
      }
      if (node.outline || node.hollow) {
        ctx.lineWidth = 2;
        ctx.strokeStyle = node.oc ? colorCss(node.oc) : '#000';
        ctx.stroke();
      }
      headAnchor = { x: center.x, y: center.y, r };
      continue;
    }

    // Polygon nodes (kitchen/food props): Ellipse fills an ellipse centered on the segment,
    // Triangle/Trapezoid fill their corner polygon. Honoring the hollow/outline fields like the
    // circle branch does so these stay consistent with how Stick Nodes draws non-stroke nodes.
    if (node.t === 'Ellipse') {
      const rx = Math.max(1, (node.l / 2) * scale);
      const ry = Math.max(1, (node.th / 2) * scale);
      const center = tx({ x: (bone.start.x + bone.end.x) / 2, y: (bone.start.y + bone.end.y) / 2 });
      const rad = (Math.atan2(-(bone.end.y - bone.start.y), bone.end.x - bone.start.x) * 180) / Math.PI;
      ctx.beginPath();
      ctx.ellipse(center.x, center.y, rx, ry, (-rad * Math.PI) / 180, 0, Math.PI * 2);
      ctx.fillStyle = color;
      ctx.fill();
      if (node.outline) {
        ctx.lineWidth = 2;
        ctx.strokeStyle = node.oc ? colorCss(node.oc) : '#000';
        ctx.stroke();
      }
      continue;
    }
    if (node.t === 'Triangle' || node.t === 'Trapezoid') {
      const corners = node.t === 'Triangle' ? triangleCorners(bone, node) : trapezoidCorners(bone, node);
      ctx.beginPath();
      const first = tx(corners[0]);
      ctx.moveTo(first.x, first.y);
      for (let k = 1; k < corners.length; k++) {
        const c = tx(corners[k]);
        ctx.lineTo(c.x, c.y);
      }
      ctx.closePath();
      ctx.fillStyle = color;
      ctx.fill();
      if (node.outline) {
        ctx.lineWidth = 2;
        ctx.strokeStyle = node.oc ? colorCss(node.oc) : '#000';
        ctx.stroke();
      }
      // Rounded trapezoid ends (rdS/rdE) - cap them with a filled disc of that end's width.
      if (node.t === 'Trapezoid') {
        if (node.rdS) {
          const s = tx(bone.start);
          ctx.beginPath();
          ctx.arc(s.x, s.y, trapezoidHalfStart(node) * scale, 0, Math.PI * 2);
          ctx.fillStyle = color;
          ctx.fill();
        }
        if (node.rdE) {
          const e = tx(bone.end);
          ctx.beginPath();
          ctx.arc(e.x, e.y, trapezoidHalfEnd(node) * scale, 0, Math.PI * 2);
          ctx.fillStyle = color;
          ctx.fill();
        }
      }
      continue;
    }

    if (node.th <= 0) continue;

    if (bone.curveRadius) {
      const chain = [bone];
      let j = i + 1;
      while (j < bones.length && bones[j].curveRadius && bones[j].start === chain[chain.length - 1].end) {
        chain.push(bones[j]);
        consumed.add(j);
        j++;
      }
      const pts = [tx(chain[0].start), ...chain.map((c) => tx(c.end))];
      ctx.beginPath();
      ctx.moveTo(pts[0].x, pts[0].y);
      for (let k = 1; k < pts.length - 1; k++) {
        const mx = (pts[k].x + pts[k + 1].x) / 2;
        const my = (pts[k].y + pts[k + 1].y) / 2;
        ctx.quadraticCurveTo(pts[k].x, pts[k].y, mx, my);
      }
      ctx.lineTo(pts[pts.length - 1].x, pts[pts.length - 1].y);
      ctx.lineWidth = Math.max(1, node.th * scale);
      ctx.lineCap = 'round';
      ctx.strokeStyle = color;
      ctx.stroke();
      const ringCenter = { x: 0, y: 0 };
      for (const p of pts) {
        ringCenter.x += p.x / pts.length;
        ringCenter.y += p.y / pts.length;
      }
      const ringR = Math.hypot(pts[0].x - ringCenter.x, pts[0].y - ringCenter.y);
      headAnchor = { x: ringCenter.x, y: ringCenter.y, r: ringR };
      continue;
    }

    const s = tx(bone.start);
    const e = tx(bone.end);
    ctx.beginPath();
    ctx.moveTo(s.x, s.y);
    ctx.lineTo(e.x, e.y);
    ctx.lineWidth = Math.max(1, node.th * scale);
    ctx.lineCap = 'round';
    ctx.strokeStyle = color;
    ctx.stroke();
  }

  if (boundsCenter) ctx.restore();

  if (headAnchor) {
    if (hasFace) window.FaceRenderer.drawFace(ctx, headAnchor.x, headAnchor.y, headAnchor.r, currentEyeStyle, currentMouthStyle);
    window.FaceRenderer.drawAccessory(ctx, headAnchor.x, headAnchor.y, headAnchor.r, accessory);
  }

  // Dead: a bold red X over the middle of the character reads as "muerto/eliminado" from a
  // glance, on top of the lying-down pose it's already in.
  if (dead) {
    const cx = canvas.width / 2;
    const cy = canvas.height / 2;
    const r = Math.min(canvas.width, canvas.height) * 0.35;
    ctx.strokeStyle = 'rgba(255, 40, 40, 0.95)';
    ctx.lineWidth = 4;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(cx - r, cy - r);
    ctx.lineTo(cx + r, cy + r);
    ctx.moveTo(cx + r, cy - r);
    ctx.lineTo(cx - r, cy + r);
    ctx.stroke();
  }

  // Survival bars hogged along the top edge (the canvas is character-stripped and pinned to the
  // window's bottom, so the top row is its own clean strip): vida, hambre, sed.
  if (lastStats) {
    const bars = [
      { label: 'vida', value: lastStats.hp, color: '#e53935' },
      { label: 'hambre', value: lastStats.hunger, color: '#fb8c00' },
      { label: 'sed', value: lastStats.thirst, color: '#1e88e5' },
    ];
    const barW = 20;
    const barH = 4;
    const gap = 3;
    const totalW = bars.length * barW + (bars.length - 1) * gap;
    let bx = canvas.width / 2 - totalW / 2;
    const by = 4;
    ctx.textAlign = 'center';
    for (const bar of bars) {
      ctx.fillStyle = 'rgba(0, 0, 0, 0.45)';
      ctx.fillRect(bx, by, barW, barH);
      ctx.fillStyle = bar.color;
      ctx.fillRect(bx, by, Math.max(barH, (Math.min(100, Math.max(0, bar.value)) / 100) * barW), barH);
      ctx.fillStyle = '#fff';
      ctx.font = '7px system-ui, sans-serif';
      ctx.fillText(bar.label, bx + barW / 2, by + barH + 7);
      bx += barW + gap;
    }
  }
}

function drawSprite() {
  if (!currentSprite) return;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const pad = canvas.width * 0.05;
  const scale = Math.min(
    (canvas.width - pad * 2) / Math.max(currentSprite.width, 1),
    (canvas.height - pad * 2) / Math.max(currentSprite.height, 1)
  );
  const dw = currentSprite.width * scale;
  const dh = currentSprite.height * scale;
  ctx.drawImage(currentSprite, (canvas.width - dw) / 2, (canvas.height - dh) / 2, dw, dh);
  if (dead) {
    const cx = canvas.width / 2;
    const cy = canvas.height / 2;
    const r = Math.min(canvas.width, canvas.height) * 0.35;
    ctx.strokeStyle = 'rgba(255, 40, 40, 0.95)';
    ctx.lineWidth = 4;
    ctx.lineCap = 'round';
    ctx.beginPath();
    ctx.moveTo(cx - r, cy - r);
    ctx.lineTo(cx + r, cy + r);
    ctx.moveTo(cx + r, cy - r);
    ctx.lineTo(cx - r, cy + r);
    ctx.stroke();
  }
  if (lastStats) {
    const bars = [
      { label: 'vida', value: lastStats.hp, color: '#e53935' },
      { label: 'hambre', value: lastStats.hunger, color: '#fb8c00' },
      { label: 'sed', value: lastStats.thirst, color: '#1e88e5' },
    ];
    const barW = 20;
    const barH = 4;
    const gap = 3;
    const totalW = bars.length * barW + (bars.length - 1) * gap;
    let bx = canvas.width / 2 - totalW / 2;
    const by = 4;
    ctx.textAlign = 'center';
    for (const bar of bars) {
      ctx.fillStyle = 'rgba(0, 0, 0, 0.45)';
      ctx.fillRect(bx, by, barW, barH);
      ctx.fillStyle = bar.color;
      ctx.fillRect(bx, by, Math.max(barH, (Math.min(100, Math.max(0, bar.value)) / 100) * barW), barH);
      ctx.fillStyle = '#fff';
      ctx.font = '7px system-ui, sans-serif';
      ctx.fillText(bar.label, bx + barW / 2, by + barH + 7);
      bx += barW + gap;
    }
  }
}

ipcRenderer.on('character:pose', (_event, payload) => {
  if (payload.id !== characterId) return;
  if (spriteSet && spriteSet.ready) {
    currentSprite = spriteSet.frameFor(payload.descriptor.kind, payload.descriptor.frame);
    if (payload.lookRight !== undefined) lookRight = payload.lookRight;
    lastStats = payload.stats || null;
    if (payload.dead !== undefined) dead = payload.dead;
    canvas.style.transform = lookRight ? 'scaleX(-1)' : 'none';
    if (payload.speechText) {
      speechEl.textContent = payload.speechText;
      speechEl.style.display = 'block';
    } else {
      speechEl.style.display = 'none';
    }
    drawSprite();
    return;
  }
  currentPose = window.PoseLibrary.forDescriptor(payload.descriptor, poseId);
  if (payload.eyeStyle) currentEyeStyle = payload.eyeStyle;
  if (payload.mouthStyle) currentMouthStyle = payload.mouthStyle;
  if (payload.lookRight !== undefined) lookRight = payload.lookRight;
  lastStats = payload.stats || null;
  if (payload.dead !== undefined) dead = payload.dead;
  canvas.style.transform = lookRight ? 'scaleX(-1)' : 'none';
  if (payload.speechText) {
    speechEl.textContent = payload.speechText;
    speechEl.style.display = 'block';
  } else {
    speechEl.style.display = 'none';
  }
  draw();
});

function resizeCanvas() {
  canvas.width = rigWidth;
  canvas.height = rigHeight;
  maxBoundsCache = null;
  if (spriteSet && spriteSet.ready) drawSprite();
  else draw();
}

// Food/window-role updates for prop windows (pizza bites + jiggle, drink tilt).
ipcRenderer.on('character:food', (_event, payload) => {
  if (payload.id !== characterId) return;
  if (payload.bites !== undefined) foodBitesLeft = payload.bites;
  if (payload.jiggle !== undefined) foodJiggleRad = payload.jiggle;
  if (payload.drinkTilt !== undefined) drinkTiltDeg = payload.drinkTilt;
  draw();
});

// Swap this window's rig at runtime - used by the kitchen's station cycling (kitchen.js) so the
// kitchen prop can transition between its different background rigs with a quick fade.
ipcRenderer.on('character:rig', async (_event, payload) => {
  if (payload.id !== characterId) return;
  canvas.style.transition = 'opacity 200ms';
  canvas.style.opacity = '0';
  await new Promise((r) => setTimeout(r, 210));
  try {
    const res = await fetch(payload.url);
    figure = await res.json();
  } catch {
    canvas.style.opacity = '1';
    return;
  }
  resizeCanvas();
  canvas.style.opacity = '1';
  setTimeout(() => {
    canvas.style.transition = 'none';
  }, 250);
});

window.addEventListener('resize', resizeCanvas);

function isPropWindow() {
  return foodBitesTotal > 0 || drinkMode;
}

async function main() {
  // Sprite (legacy) build and not a kitchen/food prop (those stay rigs in both builds - there
  // is no sprite art for them).
  if (spriteModeRequested && !isPropWindow()) {
    spriteSet = window.SpriteSet.load(characterId);
    return new Promise((resolve) => {
      spriteSet.onReady = (hasSprites) => {
        if (hasSprites) {
          resizeCanvas();
        } else {
          // No sprite folder (custom characters, e.g.) - fall back to rig rendering.
          spriteSet = null;
          mainRig();
        }
        resolve();
      };
    });
  }
  mainRig();
}

async function mainRig() {
  // Custom (user-created) characters store their rig outside renderer/rigs/, in the writable
  // workspace dir - see jsCharacterEngine.js's createWindow(), which passes this query param
  // only when there's no built-in renderer/rigs/<id>.json for this character.
  const customRigUrl = params.get('customRigUrl');
  const res = await fetch(customRigUrl || `rigs/${characterId}.json`);
  figure = await res.json();
  resizeCanvas();
}

main();
