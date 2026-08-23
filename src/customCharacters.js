const fs = require('fs');
const path = require('path');
const config = require('./config');
const CHARACTERS = require('./characters');

const CUSTOM_CHARACTERS_PATH = path.join(config.workspaceDir, 'custom_characters.json');
const CUSTOM_RIGS_DIR = path.join(config.workspaceDir, 'custom_rigs');

// "Normal" head = Red's rig: the head is one filled Circle node. "Hollow" head = TCO's rig: the
// head is a chain of curved RoundedSegment bones stroked into a ring (see renderer/character.js's
// curveRadius handling) - there's no Circle node in it at all, so it can't be produced by flipping
// a flag on Red's head. TCO was picked over TDL (same ring construction) because TDL's rig also
// carries a sword prop (extra colored Segment nodes) that don't belong on a generic custom character.
const RIG_TEMPLATE_PATH = {
  normal: path.join(config.rootDir, 'renderer', 'rigs', 'Red.json'),
  hollow: path.join(config.rootDir, 'renderer', 'rigs', 'TCO.json'),
};

// renderer/poseLibrary.js's PROFILE_BY_ID only has entries for the built-in characters it names
// (Red, TCO, etc.) - a custom character's own id (e.g. "JoseNandu") isn't in there, so without
// this it would fall through to an empty pose and never animate. Since a custom rig is always an
// exact clone of one of these two templates (same bone paths, same rest angles), telling
// PoseLibrary to look up THIS id instead of the character's own is enough to fully animate it.
const POSE_PROFILE_BY_HEAD_MODEL = { normal: 'Red', hollow: 'TCO' };

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
      CHARACTERS.ALL.push({ id: custom.id, displayName: custom.displayName, personality: '', gender: custom.gender });
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

function buildRig(color, headModel) {
  const templatePath = RIG_TEMPLATE_PATH[headModel] || RIG_TEMPLATE_PATH.normal;
  const template = JSON.parse(fs.readFileSync(templatePath, 'utf8'));
  template.color = color;
  return template;
}

function create({ displayName, color, headModel, hasFace, gender }) {
  const name = (displayName || '').trim() || 'Stickman';
  const id = sanitizeId(name);
  const rig = buildRig(color, headModel);

  fs.mkdirSync(CUSTOM_RIGS_DIR, { recursive: true });
  fs.writeFileSync(path.join(CUSTOM_RIGS_DIR, `${id}.json`), JSON.stringify(rig));

  const record = { id, displayName: name, color, headModel, hasFace: !!hasFace, gender: gender || 'otro' };
  const list = load();
  list.push(record);
  save(list);

  CHARACTERS.ALL.push({ id, displayName: name, personality: '', gender: record.gender });
  return record;
}

function customRigPath(id) {
  const p = path.join(CUSTOM_RIGS_DIR, `${id}.json`);
  return fs.existsSync(p) ? p : null;
}

// Null for a built-in character (or an unknown id) - callers should keep using the character's
// own id/no face/no accessory in that case. Consolidates what used to be a separate
// poseProfileFor() lookup - one read of custom_characters.json instead of one per field.
function metaFor(id) {
  const record = load().find((c) => c.id === id);
  if (!record) return null;
  return {
    poseProfile: POSE_PROFILE_BY_HEAD_MODEL[record.headModel] || 'Red',
    hasFace: !!record.hasFace,
    gender: record.gender || 'otro',
  };
}

module.exports = {
  PALETTE,
  loadIntoRoster,
  create,
  customRigPath,
  metaFor,
};
