const { mouse, keyboard, Point, Button } = require('@nut-tree-fork/nut-js');

keyboard.config.autoDelayMs = 20;
mouse.config.autoDelayMs = 10;

async function moveMouse(x, y) {
  await mouse.setPosition(new Point(Number(x), Number(y)));
}

async function click(button = 'left') {
  const map = { left: Button.LEFT, right: Button.RIGHT, middle: Button.MIDDLE };
  await mouse.click(map[button] || Button.LEFT);
}

async function typeText(text) {
  await keyboard.type(String(text));
}

module.exports = { moveMouse, click, typeText };
