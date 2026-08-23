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
  if (!isRoot) acc.push({ node, start, end });
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

function findHeadNode(node) {
  if (node.t === 'Circle' || node.t === 'FilledCircle') return node;
  for (const child of node.ch || []) {
    const found = findHeadNode(child);
    if (found) return found;
  }
  return null;
}

function draw(ctx, canvas, figure) {
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

  for (const bone of bones) {
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
      continue;
    }

    if (node.th <= 0) continue;
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

const canvas = document.getElementById('preview');
const ctx = canvas.getContext('2d');
const nameInput = document.getElementById('name');
const swatchesEl = document.getElementById('swatches');
const createBtn = document.getElementById('createBtn');

let template = null;
let selectedColor = null;

function headModel() {
  return document.querySelector('input[name="head"]:checked').value;
}

function redraw() {
  if (!template || !selectedColor) return;
  const figure = JSON.parse(JSON.stringify(template));
  figure.color = selectedColor;
  const head = findHeadNode(figure.root);
  if (head) head.hollow = headModel() === 'hollow';
  draw(ctx, canvas, figure);
}

async function init() {
  const [palette, rigRes] = await Promise.all([
    window.stickmanAPI.getPalette(),
    fetch('rigs/Red.json'),
  ]);
  template = await rigRes.json();

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
  selectedColor = palette[0];
  swatchesEl.firstChild.classList.add('selected');
  redraw();
}

document.querySelectorAll('input[name="head"]').forEach((el) => el.addEventListener('change', redraw));

createBtn.addEventListener('click', () => {
  const displayName = nameInput.value.trim();
  if (!displayName) {
    nameInput.focus();
    return;
  }
  window.stickmanAPI.createCharacter({
    displayName,
    color: selectedColor,
    headModel: headModel(),
  });
  window.close();
});

init();
