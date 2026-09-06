// Eat/drink "performance" on top of the stamina system: when a character eats, a small food rig
// window (currently the user's Pizza-5.nodes -> renderer/rigs/pizza.json) appears near its mouth,
// shrinks bite by bite via a shrinking wedge (character.js's foodBites clip), and vanishes when
// the chew gesture ends; drinking does the same with the hand-authored cup rig, tilting it with
// each swallow. The food windows reuse character.html?id=<rig> + a couple of food IPC params, so
// adding another food is just dropping its .nodes into the pack and letting the converter generate
// a rig in renderer/rigs/ (the next-available rig is cycled each serve -> "transitions entre
// comidas"). Each eat/drink also nudges the kitchen to its next station (kitchen.js's
// nextStation) as a visible "transition entre estaciones de la cocina".
//
// Timelines run here in the main process (setTimeout chains) while CharacterState plays the chew/
// drink gesture and the food window renders the shrinking prop - three independent players on one
// shared duration, so nothing else needs to know about the food window's lifecycle.

const { BrowserWindow, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const shimeji = require('./jsShimejiController');
const kitchen = require('./kitchen');

// Rig-box for the food/drink window (reuse the character window page sized for a small prop).
const FOOD_RIG = 46;
const BITE_MS = 700;
const EAT_BITES = 6;
const EAT_DURATION_MS = 5000; // must match characterState.js's EAT_DURATION_MS
const DRINK_GULP_MS = 1200;
const DRINK_DURATION_MS = 4200; // must match characterState.js's DRINK_DURATION_MS
const SLIDE_STEPS = 6;

// Add future foods (e.g. toast.json) here or keep it a discovery list - only existing rigs are
// served, cycling between them so consecutive meals visibly switch foods.
const FOOD_CANDIDATES = ['pizza'];

let foodCycle = 0;
const active = new Map(); // characterId -> { win, timers: [] }

function foodsAvailable() {
  return FOOD_CANDIDATES.filter((id) =>
    fs.existsSync(path.join(__dirname, '..', '..', 'renderer', 'rigs', `${id}.json`))
  );
}

function nextFoodId() {
  const list = foodsAvailable();
  if (!list.length) return null;
  const id = list[foodCycle % list.length];
  foodCycle++;
  return id;
}

function scaleFactor() {
  return screen.getPrimaryDisplay().scaleFactor || 1;
}

// Position in OS screen space. The food window is anchored near the character's mouth: the
// character's rig box is right-topped at (state.x - winW/2, state.y - winH), and the head/mouth
// sits in the upper part of that box, biased toward the direction the character is looking.
function mouthScreenPos(entry) {
  const workArea = entry.workArea;
  const bx = workArea.x + entry.state.x - entry.size.width / 2;
  const by = entry.state.y - entry.size.height;
  const bias = entry.state.lookRight ? 0.6 : 0.4;
  return {
    x: bx + entry.size.width * bias,
    y: by + entry.size.height * 0.18,
  };
}

function clearTimers(timers) {
  for (const t of timers) clearTimeout(t);
}

function createFoodWindow(rigId, query) {
  const sf = scaleFactor();
  const rigW = Math.round(FOOD_RIG / sf);
  const rigH = Math.round(FOOD_RIG / sf);
  const win = new BrowserWindow({
    width: rigW,
    height: rigH,
    x: -2000,
    y: -2000,
    frame: false,
    transparent: true,
    alwaysOnTop: true,
    resizable: false,
    hasShadow: false,
    skipTaskbar: true,
    webPreferences: { contextIsolation: false, nodeIntegration: true },
  });
  win.loadFile(path.join(__dirname, '..', '..', 'renderer', 'character.html'), {
    query: { id: rigId, rw: String(rigW), rh: String(rigH), ...query },
  });
  return { win, rigW, rigH };
}

// Slide a food window from one screen point to another in a few steps - used to make the food
// "arrive" from the kitchen's direction (station transition) and leave back toward it.
function slide(timers, win, from, to, steps, stepMs) {
  for (let i = 1; i <= steps; i++) {
    const t = i / steps;
    const eased = t * t; // ease-out so it settles into place
    timers.push(
      setTimeout(() => {
        win.setPosition(
          Math.round(from.x + (to.x - from.x) * eased),
          Math.round(from.y + (to.y - from.y) * eased)
        );
      }, stepMs * i)
    );
  }
}

function ensureRole(characterId) {
  const entry = shimeji.get(characterId);
  if (!entry || entry.state.dead || active.has(characterId)) return null;
  active.set(characterId, { win: null, timers: [] });
  return entry;
}

function finish(characterId, food) {
  clearTimers(food.timers);
  if (food.win && !food.win.isDestroyed()) food.win.close();
  active.delete(characterId);
}

function playEat(characterId) {
  const entry = ensureRole(characterId);
  if (!entry) return;
  const foodId = nextFoodId();
  if (!foodId) return finish(characterId, active.get(characterId));
  const { win, rigW, rigH } = createFoodWindow(foodId, { foodBites: String(EAT_BITES) });
  const food = active.get(characterId);
  food.win = win;

  kitchen.nextStation(); // transition entre estaciones de la cocina while eating

  const mouth = mouthScreenPos(entry);
  const sf = scaleFactor();
  const dest = { x: Math.round(mouth.x - rigW / 2), y: Math.round(mouth.y - rigH / 2) };
  const kitchenPos = kitchen.getPosition();
  // Arrive sliding in from the kitchen's side (or just fade in when there's no kitchen yet).
  const from = kitchenPos
    ? { x: Math.round(entry.workArea.x + kitchenPos.x - rigW / 2), y: dest.y }
    : { x: dest.x, y: dest.y - 40 / sf };
  slide(food.timers, win, from, dest, SLIDE_STEPS, 50);

  entry.state.startEat();

  let bites = EAT_BITES;
  for (let i = 1; i < EAT_BITES; i++) {
    food.timers.push(
      setTimeout(() => {
        bites--;
        if (win.isDestroyed()) return;
        win.webContents.send('character:food', {
          id: foodId,
          bites,
          jiggle: (Math.random() - 0.5) * 0.14,
        });
      }, i * BITE_MS)
    );
  }
  // Leave back toward the kitchen side once the last bite is done.
  food.timers.push(
    setTimeout(() => {
      const leaveFrom = win.getPosition();
      slide(food.timers, win, leaveFrom, { x: from.x, y: dest.y }, SLIDE_STEPS, 45);
      finish(characterId, food);
    }, EAT_DURATION_MS - SLIDE_STEPS * 45)
  );
}

function playDrink(characterId) {
  const entry = ensureRole(characterId);
  if (!entry) return;
  const { win, rigW, rigH } = createFoodWindow('cup', { drink: '1' });
  const food = active.get(characterId);
  food.win = win;

  kitchen.nextStation();

  const mouth = mouthScreenPos(entry);
  const sf = scaleFactor();
  const dest = { x: Math.round(mouth.x - rigW / 2), y: Math.round(mouth.y - rigH / 2) };
  const kitchenPos = kitchen.getPosition();
  const from = kitchenPos
    ? { x: Math.round(entry.workArea.x + kitchenPos.x - rigW / 2), y: dest.y }
    : { x: dest.x, y: dest.y - 40 / sf };
  slide(food.timers, win, from, dest, SLIDE_STEPS, 50);

  entry.state.startDrink();

  // Swallow rhythm: tilt up then back down per gulp.
  const gulps = 3;
  for (let i = 0; i < gulps; i++) {
    food.timers.push(
      setTimeout(() => {
        if (win.isDestroyed()) return;
        win.webContents.send('character:food', { id: 'cup', drinkTilt: 16 });
        setTimeout(() => {
          if (win.isDestroyed()) return;
          win.webContents.send('character:food', { id: 'cup', drinkTilt: 0 });
        }, DRINK_GULP_MS / 2);
      }, i * DRINK_GULP_MS + 300)
    );
  }
  food.timers.push(
    setTimeout(() => {
      const leaveFrom = win.getPosition();
      slide(food.timers, win, leaveFrom, { x: from.x, y: dest.y }, SLIDE_STEPS, 45);
      finish(characterId, food);
    }, DRINK_DURATION_MS - SLIDE_STEPS * 45)
  );
}

function stop() {
  for (const [characterId, food] of active) finish(characterId, food);
}

module.exports = { playEat, playDrink, stop };