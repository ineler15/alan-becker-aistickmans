const { confirmAction, isInsideWorkspace } = require('../safety/confirm');
const config = require('../config');
const input = require('./input');
const system = require('./system');
const paint = require('./stickPaint');
const notepad = require('./notepad');
const shimeji = require('../jsEngine/jsShimejiController');
const pointerHighlight = require('../ui/pointerHighlight');

// Off by default (see pcSettings.js) - moving the real OS mouse/clicking on the user's actual
// desktop is meaningfully bigger than any of the sandboxed actions (StickPaint, walking around,
// etc.), so it needs an explicit opt-in in Settings rather than working unconditionally.
const MOUSE_CONTROL_DISABLED_RESULT = {
  ok: false,
  result: 'El control de mouse/pantalla esta desactivado - activalo en Configuracion si queres que lo use.',
};

async function needsConfirmation(name, args) {
  if (name === 'run_command') return true;
  if (name === 'close_app') return true;
  if ((name === 'write_file' || name === 'delete_file') && !isInsideWorkspace(args.filePath || '')) return true;
  return false;
}

async function execute(name, args, characterId) {
  if (await needsConfirmation(name, args)) {
    const allowed = await confirmAction(name, args);
    if (!allowed) return { ok: false, result: 'El usuario rechazo esta accion.' };
  }

  switch (name) {
    case 'open_app':
      return { ok: true, result: await system.openApp(args.target) };
    case 'close_app':
      return { ok: true, result: await system.closeApp(args.processName) };
    case 'move_mouse':
      if (!config.allowMouseControl) return MOUSE_CONTROL_DISABLED_RESULT;
      await pointerHighlight.showFor(characterId, args.x, args.y);
      return { ok: true, result: await input.moveMouse(args.x, args.y) };
    case 'walk_to':
      shimeji.sendCommand(characterId, 'walk_to', { x: args.x, y: args.y, run: args.run });
      return { ok: true, result: `orden enviada: ${args.run ? 'correr' : 'caminar'} a (${args.x}, ${args.y})` };
    case 'ride_mouse':
      // Not gated by allowMouseControl - this just visually rides along wherever the user's own
      // cursor already is, it never moves or clicks anything, so it isn't the same safety concern.
      shimeji.sendCommand(characterId, 'ride_mouse', { seconds: args.seconds });
      return { ok: true, result: 'orden enviada: subirse al cursor del mouse' };
    case 'click':
      if (!config.allowMouseControl) return MOUSE_CONTROL_DISABLED_RESULT;
      return { ok: true, result: await input.click(args.button) };
    case 'tap':
      if (!config.allowMouseControl) return MOUSE_CONTROL_DISABLED_RESULT;
      await pointerHighlight.showFor(characterId, args.x, args.y);
      await input.tap(args.x, args.y, args.button);
      return { ok: true, result: `tap en (${args.x}, ${args.y})` };
    case 'type_text':
      return { ok: true, result: await input.typeText(args.text) };
    case 'open_paint':
      return { ok: true, result: await paint.openPaint() };
    case 'write_in_paint':
      return { ok: true, result: await paint.writeInPaint(args.text, args.x, args.y) };
    case 'draw_in_paint': {
      // The schema declares every param as a string, so a tool-calling model may hand
      // back "points" either as a real array or as a JSON-encoded string - accept both.
      // It also tends to send each point as [x, y] instead of the documented {x, y} -
      // normalize both shapes here instead of drawing silently-broken NaN coordinates.
      const raw = typeof args.points === 'string' ? JSON.parse(args.points) : args.points;
      const points = (raw || []).map((p) => (Array.isArray(p) ? { x: p[0], y: p[1] } : p));
      await paint.drawInPaint(points, { close: !!args.close, fill: !!args.fill });
      return { ok: true, result: 'dibujo trazado' };
    }
    case 'draw_shape':
      await paint.drawShape(args.shape, args.x, args.y, args.width, args.height, !!args.fill);
      return { ok: true, result: `${args.shape} dibujado` };
    case 'read_paint':
      return { ok: true, result: await paint.readPaint() };
    case 'set_paint_color':
      await paint.setColor(args.color);
      return { ok: true, result: `color cambiado a ${args.color}` };
    case 'clear_paint':
      await paint.clear();
      return { ok: true, result: 'lienzo borrado' };
    case 'read_notepad':
      return { ok: true, result: await notepad.readNotepad() };
    case 'list_dir':
      return { ok: true, result: await system.listDir(args.dirPath) };
    case 'read_file':
      return { ok: true, result: await system.readFile(args.filePath) };
    case 'write_file':
      return { ok: true, result: await system.writeFile(args.filePath, args.content) };
    case 'delete_file':
      return { ok: true, result: await system.deleteFile(args.filePath) };
    case 'run_command':
      return { ok: true, result: await system.runCommand(args.command) };
    case 'wait':
      return { ok: true, result: 'esperando' };
    case 'set_animation':
      shimeji.sendCommand(characterId, 'set_animation', { state: args.state, caption: args.caption });
      return { ok: true, result: 'animacion actualizada' };
    case 'set_emotion':
      shimeji.sendCommand(characterId, 'set_emotion', { eyes: args.eyes, mouth: args.mouth });
      return { ok: true, result: 'cara actualizada' };
    case 'say':
      shimeji.sendCommand(characterId, 'say', { text: args.text });
      return { ok: true, result: 'mensaje mostrado' };
    case 'set_custom_animation': {
      // Same string-vs-array leniency as draw_in_paint's points - a tool-calling model may hand
      // back "keyframes" as a JSON-encoded string instead of a real array.
      const raw = typeof args.keyframes === 'string' ? JSON.parse(args.keyframes) : args.keyframes;
      shimeji.sendCommand(characterId, 'set_custom_animation', { keyframes: raw || [] });
      return { ok: true, result: 'animacion personalizada iniciada' };
    }
    case 'define_personality':
      return { ok: true, result: String(args.description || '').slice(0, 500) };
    case 'remember':
      return { ok: true, result: String(args.note || '').slice(0, 300) };
    default:
      return { ok: false, result: `Accion desconocida: ${name}` };
  }
}

module.exports = { execute };
