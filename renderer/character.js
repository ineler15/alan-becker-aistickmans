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
  const kinds = ['stand', 'sit', 'walk', 'run', 'bounce', 'trip', 'fall', 'pinch', 'angry', 'sleep', 'tired'];
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

  if (headAnchor) {
    if (hasFace) window.FaceRenderer.drawFace(ctx, headAnchor.x, headAnchor.y, headAnchor.r, currentEyeStyle, currentMouthStyle);
    window.FaceRenderer.drawAccessory(ctx, headAnchor.x, headAnchor.y, headAnchor.r, accessory);
  }
}

ipcRenderer.on('character:pose', (_event, payload) => {
  if (payload.id !== characterId) return;
  currentPose = window.PoseLibrary.forDescriptor(payload.descriptor, poseId);
  if (payload.eyeStyle) currentEyeStyle = payload.eyeStyle;
  if (payload.mouthStyle) currentMouthStyle = payload.mouthStyle;
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
  canvas.width = rigWidth;
  canvas.height = rigHeight;
  maxBoundsCache = null;
  draw();
}

window.addEventListener('resize', resizeCanvas);

async function main() {
  // Custom (user-created) characters store their rig outside renderer/rigs/, in the writable
  // workspace dir - see jsCharacterEngine.js's createWindow(), which passes this query param
  // only when there's no built-in renderer/rigs/<id>.json for this character.
  const customRigUrl = params.get('customRigUrl');
  const res = await fetch(customRigUrl || `rigs/${characterId}.json`);
  figure = await res.json();
  resizeCanvas();
}

main();
