const { mouse, keyboard, Point, Button } = require('@nut-tree-fork/nut-js');

keyboard.config.autoDelayMs = 20;
mouse.config.autoDelayMs = 10;

async function moveMouse(x, y) {
  await mouse.setPosition(new Point(Number(x), Number(y)));
}

// Absolute screen pixels, same coordinate space moveMouse/tap already use - lets ride_mouse
// (characterState.js) actually follow the real cursor instead of the no-op it used to be.
async function getMousePosition() {
  const p = await mouse.getPosition();
  return { x: p.x, y: p.y };
}

async function click(button = 'left') {
  const map = { left: Button.LEFT, right: Button.RIGHT, middle: Button.MIDDLE };
  await mouse.click(map[button] || Button.LEFT);
}

// Same idea as Android's "tap": move+click in one atomic action instead of two separate turns
// (move_mouse then click) - saves a round trip when the model already knows exactly where to
// click, same as it would tap a touchscreen.
async function tap(x, y, button = 'left') {
  await moveMouse(x, y);
  await click(button);
}

async function typeText(text) {
  await keyboard.type(String(text));
}

module.exports = { moveMouse, click, tap, typeText, getMousePosition };
