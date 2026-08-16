// Generic per-character rig-rendering window - the real replacement for Shimeji's sprite
// rendering (see renderer/rig.js for the original single-character proof of concept this grew
// out of). One of these BrowserWindows exists per enabled character (see main.js's
// jsCharacterEngine), each loaded with ?id=<characterId> and kept positioned by the main process
// to track that character's CharacterState (src/jsEngine/characterState.js). Pose math itself
// lives in poseLibrary.js (loaded before this script, exposes window.PoseLibrary).

const { ipcRenderer } = require('electron');

const params = new URLSearchParams(location.search);
const characterId = params.get('id');

const canvas = document.getElementById('c');
const ctx = canvas.getContext('2d');
const speechEl = document.getElementById('speech');

const CIRCLE_RADIUS_FACTOR = 0.65;
let figure = null;
let currentPose = new Map();
let lookRight = true;

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

// Locked to the rest pose's bounding box so the character doesn't visibly grow/shrink switching
// between poses of different heights - same fix as the Android renderer (RigView.kt).
let restBoundsCache = null;
function restBounds() {
  if (!restBoundsCache) restBoundsCache = bounds(layout(figure.root, 0, { x: 0, y: 0 }, [], []));
  return restBoundsCache;
}

function draw() {
  if (!figure) return;
  const bones = layout(figure.root, 0, { x: 0, y: 0 }, [], []);
  if (!bones.length) return;
  const rb = restBounds();
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
}

ipcRenderer.on('character:pose', (_event, payload) => {
  if (payload.id !== characterId) return;
  currentPose = window.PoseLibrary.forDescriptor(payload.descriptor, characterId);
  if (payload.lookRight !== undefined) lookRight = payload.lookRight;
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
  canvas.width = window.innerWidth;
  canvas.height = window.innerHeight;
  restBoundsCache = null;
  draw();
}

window.addEventListener('resize', resizeCanvas);

async function main() {
  const res = await fetch(`rigs/${characterId}.json`);
  figure = await res.json();
  resizeCanvas();
}

main();
