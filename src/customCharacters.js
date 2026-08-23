const fs = require('fs');
const path = require('path');
const config = require('./config');
const CHARACTERS = require('./characters');

const CUSTOM_CHARACTERS_PATH = path.join(config.workspaceDir, 'custom_characters.json');
const CUSTOM_RIGS_DIR = path.join(config.workspaceDir, 'custom_rigs');
const RED_RIG_TEMPLATE_PATH = path.join(config.rootDir, 'renderer', 'rigs', 'Red.json');

// Same 8 swatches on PC and Android so "crear tu propio stickman" looks the same on both -
// the 6 existing character colors plus black/white for anyone who wants neither.
const PALETTE = [
  [254, 0, 0, 255],
  [255, 140, 0, 255],
  [0, 170, 0, 255],
  [0, 100, 255, 255],
  [230, 200, 0, 255],
  [160, 0, 200, 255],
  [255, 255, 255, 255],
  [20, 20, 20, 255],
];

function load() {
  try {
    return JSON.parse(fs.readFileSync(CUSTOM_CHARACTERS_PATH, 'utf8'));
  } catch (e) {
    return [];
  }
}

function save(list) {
  fs.mkdirSync(config.workspaceDir, { recursive: true });
  fs.writeFileSync(CUSTOM_CHARACTERS_PATH, JSON.stringify(list, null, 2));
}

// Merges any custom characters saved in a previous session into CHARACTERS.ALL, so the settings
// window's character list and startCharacterEngine() both see them immediately at startup -
// same "mutate the shared array in place" pattern pcSettings.applyEnabledCharacters already uses.
function loadIntoRoster() {
  for (const custom of load()) {
    if (!CHARACTERS.ALL.some((c) => c.id === custom.id)) {
      CHARACTERS.ALL.push({ id: custom.id, displayName: custom.displayName, personality: '' });
    }
  }
}

function sanitizeId(displayName) {
  const base =
    displayName
      .normalize('NFD')
      .replace(new RegExp('[̀-ͯ]', 'g'), '')
      .replace(/[^a-zA-Z0-9]/g, '') || 'Stickman';
  let id = base;
  let suffix = 2;
  while (CHARACTERS.ALL.some((c) => c.id === id)) {
    id = `${base}_${suffix}`;
    suffix += 1;
  }
  return id;
}

// Head is the single Circle node in the whole rig tree - find it recursively instead of
// hardcoding a path, since which branch it sits under is just an artifact of how Red's rig
// happened to be authored.
function findHeadNode(node) {
  if (node.t === 'Circle' || node.t === 'FilledCircle') return node;
  for (const child of node.ch || []) {
    const found = findHeadNode(child);
    if (found) return found;
  }
  return null;
}

function buildRig(color, headModel) {
  const template = JSON.parse(fs.readFileSync(RED_RIG_TEMPLATE_PATH, 'utf8'));
  template.color = color;
  const head = findHeadNode(template.root);
  if (head) head.hollow = headModel === 'hollow';
  return template;
}

function create({ displayName, color, headModel }) {
  const name = (displayName || '').trim() || 'Stickman';
  const id = sanitizeId(name);
  const rig = buildRig(color, headModel);

  fs.mkdirSync(CUSTOM_RIGS_DIR, { recursive: true });
  fs.writeFileSync(path.join(CUSTOM_RIGS_DIR, `${id}.json`), JSON.stringify(rig));

  const record = { id, displayName: name, color, headModel };
  const list = load();
  list.push(record);
  save(list);

  CHARACTERS.ALL.push({ id, displayName: name, personality: '' });
  return record;
}

function customRigPath(id) {
  const p = path.join(CUSTOM_RIGS_DIR, `${id}.json`);
  return fs.existsSync(p) ? p : null;
}

module.exports = {
  PALETTE,
  loadIntoRoster,
  create,
  customRigPath,
};
