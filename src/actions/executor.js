const { confirmAction, isInsideWorkspace } = require('../safety/confirm');
const input = require('./input');
const system = require('./system');
const paint = require('./stickPaint');
const notepad = require('./notepad');
const shimeji = require('../../dist/shimejiController');

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
      return { ok: true, result: await input.moveMouse(args.x, args.y) };
    case 'walk_to':
      shimeji.sendCommand(characterId, 'walk_to', { x: args.x, y: args.y, run: args.run });
      return { ok: true, result: `orden enviada: ${args.run ? 'correr' : 'caminar'} a (${args.x}, ${args.y})` };
    case 'move_random':
      shimeji.sendCommand(characterId, 'move_random', { run: args.run });
      return { ok: true, result: `orden enviada: ${args.run ? 'correr' : 'caminar'} a un punto aleatorio` };
    case 'ride_mouse':
      shimeji.sendCommand(characterId, 'ride_mouse', { seconds: args.seconds });
      return { ok: true, result: 'orden enviada: subirse al cursor del mouse' };
    case 'click':
      return { ok: true, result: await input.click(args.button) };
    case 'type_text':
      return { ok: true, result: await input.typeText(args.text) };
    case 'open_paint':
      return { ok: true, result: await paint.openPaint() };
    case 'write_in_paint':
      return { ok: true, result: await paint.writeInPaint(args.text) };
    case 'draw_in_paint': {
      // The schema declares every param as a string, so a tool-calling model may hand
      // back "points" either as a real array or as a JSON-encoded string - accept both.
      // It also tends to send each point as [x, y] instead of the documented {x, y} -
      // normalize both shapes here instead of drawing silently-broken NaN coordinates.
      const raw = typeof args.points === 'string' ? JSON.parse(args.points) : args.points;
      const points = (raw || []).map((p) => (Array.isArray(p) ? { x: p[0], y: p[1] } : p));
      await paint.drawInPaint(points);
      return { ok: true, result: 'dibujo trazado' };
    }
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
    case 'say':
      shimeji.sendCommand(characterId, 'say', { text: args.text });
      return { ok: true, result: 'mensaje mostrado' };
    case 'define_personality':
      return { ok: true, result: String(args.description || '').slice(0, 500) };
    case 'remember':
      return { ok: true, result: String(args.note || '').slice(0, 300) };
    default:
      return { ok: false, result: `Accion desconocida: ${name}` };
  }
}

module.exports = { execute };
