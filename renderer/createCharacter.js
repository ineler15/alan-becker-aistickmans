// Preview-only rig renderer: same layout/draw math as renderer/rig.js's standalone proof of
// concept (no pose overrides, no ipcRenderer) - just enough to show what the color+head-model
// combo will actually look like before committing to "Crear stickman".
const CIRCLE_RADIUS_FACTOR = 0.65;

function layout(node, parentAngleDeg, parentEnd, acc) {
  const isRoot = node.t === 'RootNode';
  const globalAngleDeg = isRoot ? node.a : parentAngleDeg + node.a;
  const start = isRoot ? { x: 0, y: 0 } : parentEnd;
  const rad = (globalAngleDeg * Math.PI) / 180;
  const localX = isRoot ? 0 : node.l * Math.cos(rad) * node.sc;
  const localY = isRoot ? 0 : -node.l * Math.sin(rad) * node.sc;
  const end = { x: start.x + localX, y: start.y + localY };
  if (!isRoot) acc.push({ node, start, end, curveRadius: node.cr || 0 });
  (node.ch || []).forEach((child) => layout(child, globalAngleDeg, end, acc));
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

function draw(ctx, canvas, figure, hasFaceOn, accessoryVal) {
  const bones = layout(figure.root, 0, { x: 0, y: 0 }, []);
  if (!bones.length) return;
  const b = bounds(bones);
  const padding = canvas.width * 0.12;
  const scale = Math.min(
    (canvas.width - padding * 2) / Math.max(b.w, 1),
    (canvas.height - padding * 2) / Math.max(b.h, 1)
  );
  const offX = canvas.width / 2 - (b.x + b.w / 2) * scale;
  const offY = canvas.height / 2 - (b.y + b.h / 2) * scale;
  const tx = (p) => ({ x: p.x * scale + offX, y: p.y * scale + offY });

  ctx.clearRect(0, 0, canvas.width, canvas.height);

  // "Hollow" heads (TCO/TSC/TDL's template) aren't a Circle node at all - they're a chain of
  // curved bones (node.cr != 0) meant to be stroked as ONE smooth ring, not as separate straight
  // segments (which reads as a jagged octagon instead of a circle - see sn_proto_wasm_renderer
  // memory). Mirrors renderer/character.js's curveRadius handling.
  // headAnchor: same "last Circle/ring drawn wins" trick as character.js, so the preview's
  // face/accessory land in the same spot the real renderer would put them.
  let headAnchor = null;
  const consumed = new Set();
  for (let i = 0; i < bones.length; i++) {
    if (consumed.has(i)) continue;
    const bone = bones[i];
    const node = bone.node;
    const color = colorCss(figure.color);

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
        ctx.strokeStyle = '#000';
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
    if (hasFaceOn) window.FaceRenderer.drawFace(ctx, headAnchor.x, headAnchor.y, headAnchor.r, 'normal', 'neutral');
    window.FaceRenderer.drawAccessory(ctx, headAnchor.x, headAnchor.y, headAnchor.r, accessoryVal);
  }
}

const canvas = document.getElementById('preview');
const ctx = canvas.getContext('2d');
const nameInput = document.getElementById('name');
const swatchesEl = document.getElementById('swatches');
const colorPicker = document.getElementById('colorPicker');
const createBtn = document.getElementById('createBtn');

function hexToRgba(hex) {
  const n = parseInt(hex.slice(1), 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255, 255];
}

// "Normal" = Red's rig (filled Circle head); "hollow" = TCO's rig (ring of curved bones, no
// Circle node at all) - see src/customCharacters.js's buildRig() for why these can't be the same
// base rig with a flag flipped.
const templates = { normal: null, hollow: null };
let selectedColor = null;
const editId = new URLSearchParams(location.search).get('editId');

function setRadioValue(name, value) {
  const el = document.querySelector(`input[name="${name}"][value="${value}"]`);
  if (el) el.checked = true;
}

function headModel() {
  return document.querySelector('input[name="head"]:checked').value;
}

function hasFaceChecked() {
  return document.getElementById('hasFace').checked;
}

function genderValue() {
  return document.querySelector('input[name="gender"]:checked').value;
}

function accessoryValue() {
  return document.querySelector('input[name="accessory"]:checked').value;
}

function redraw() {
  const template = templates[headModel()];
  if (!template || !selectedColor) return;
  const figure = JSON.parse(JSON.stringify(template));
  figure.color = selectedColor;
  draw(ctx, canvas, figure, hasFaceChecked(), accessoryValue());
}

async function init() {
  const [palette, normalRes, hollowRes] = await Promise.all([
    window.stickmanAPI.getPalette(),
    fetch('rigs/Red.json'),
    fetch('rigs/TCO.json'),
  ]);
  templates.normal = await normalRes.json();
  templates.hollow = await hollowRes.json();

  for (const color of palette) {
    const swatch = document.createElement('div');
    swatch.className = 'swatch';
    swatch.style.background = colorCss(color);
    swatch.addEventListener('click', () => {
      selectedColor = color;
      for (const el of swatchesEl.children) el.classList.remove('selected');
      swatch.classList.add('selected');
      redraw();
    });
    swatchesEl.appendChild(swatch);
  }

  if (editId) {
    const record = await window.stickmanAPI.getCustomCharacter(editId);
    if (record) {
      document.querySelector('h2').textContent = 'Editar stickman';
      createBtn.textContent = 'Guardar cambios';
      nameInput.value = record.displayName || '';
      selectedColor = record.color;
      setRadioValue('head', record.headModel || 'normal');
      document.getElementById('hasFace').checked = !!record.hasFace;
      setRadioValue('gender', record.gender || 'otro');
      setRadioValue('accessory', record.accessory || 'none');
    }
  } else {
    selectedColor = palette[0];
    swatchesEl.firstChild.classList.add('selected');
  }
  redraw();
}

colorPicker.addEventListener('input', () => {
  selectedColor = hexToRgba(colorPicker.value);
  for (const el of swatchesEl.children) el.classList.remove('selected');
  redraw();
});

document.querySelectorAll('input[name="head"]').forEach((el) => el.addEventListener('change', redraw));
document.querySelectorAll('input[name="gender"]').forEach((el) => el.addEventListener('change', redraw));
document.querySelectorAll('input[name="accessory"]').forEach((el) => el.addEventListener('change', redraw));
document.getElementById('hasFace').addEventListener('change', redraw);

createBtn.addEventListener('click', () => {
  const displayName = nameInput.value.trim();
  if (!displayName) {
    nameInput.focus();
    return;
  }
  const data = {
    displayName,
    color: selectedColor,
    headModel: headModel(),
    hasFace: hasFaceChecked(),
    gender: genderValue(),
    accessory: accessoryValue(),
  };
  if (editId) window.stickmanAPI.updateCharacter(editId, data);
  else window.stickmanAPI.createCharacter(data);
  window.close();
});

init();
