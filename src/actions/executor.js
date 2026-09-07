const { confirmAction, isInsideWorkspace } = require('../safety/confirm');
const config = require('../config');
const characters = require('../characters');
const input = require('./input');
const system = require('./system');
const paint = require('./stickPaint');
const notepad = require('./notepad');
const shimeji = require('../jsEngine/jsShimejiController');
const kitchen = require('../jsEngine/kitchen');
const foodProp = require('../jsEngine/foodProp');
const survival = require('../memory/survival');
const pointerHighlight = require('../ui/pointerHighlight');

// Off by default (see pcSettings.js) - moving the real OS mouse/clicking on the user's actual
// desktop is meaningfully bigger than any of the sandboxed actions (StickPaint, walking around,
// etc.), so it needs an explicit opt-in in Settings rather than working unconditionally.
const MOUSE_CONTROL_DISABLED_RESULT = {
  ok: false,
  result: 'El control de mouse/pantalla esta desactivado - activalo en Configuracion si queres que lo use.',
};

// With several characters enabled, each deciding independently on its own ~12s cycle, the real
// cursor got yanked around by whichever one felt like it that turn - looked "loco"/chaotic rather
// than like any one of them doing something deliberate. This is a GLOBAL cooldown (shared across
// every character, not per-character) so only one grabs the real mouse at a time, with a breather
// in between, regardless of how many characters have mouse control enabled.
const MOUSE_ACTION_COOLDOWN_MS = 15000;
let lastMouseActionAt = 0;

// If a character's spoken text announces a physical action instead of only naming the tool that
// does it (the model narrates "¡Salto!" rather than also calling set_animation jump), actually
// perform the announced action right after the say - saying it reads as really doing it. Kept as
// a small fallback on top of the explicit tools (walk_to / set_animation still get priority when
// the model uses them properly); this only resolves intent from speech.
const JUMP_SPEECH_RE = /\b(salt(?:a|o|e|emos|aste|ando|ar|aremos|aré|às|aria|ábamos|òs|es|ó)|brinc(?:a|o|e|ando|emos|ar)|dar un salto)\b/i;
// Movement verbs that imply walking regardless of a discoverable destination.
const STRONG_MOVE_RE =
  /\b(muev(?:e|o)\b|camina(?:ndo)?\b|caminar\b|camino\b|corr(?:e|o)\b|acercar(?:me)?\b|llegar (?:a |hasta )|pasear|paseando|salir a caminar)/i;
// Weaker "voy/vamos/se va a X..." phrasing - only treated as movement when a real destination is
// found (a known stickman or the kitchen), so "voy a saludar"/"vamos a bailar" don't start a walk.
const WEAK_MOVE_RE = /\b(voy|vamos|vaya|ira|iré|me voy|se va|ve) (?:a |hacia |para |hasta )/i;
// A "mueve/camina/corre" phrase that also implies running (rather than just walking).
const RUN_SPEECH_RE = /\b(corr(?:e|o|iendo)|apuro|rapido|de prisa)\b/i;

function resolveMoveTarget(text, characterId) {
  const haystack = (text || '').toLowerCase();
  for (const c of characters.ALL) {
    if (!c.id || c.id === characterId) continue;
    const label = String(c.displayName || c.id).toLowerCase();
    if (haystack.includes(label) || haystack.includes(String(c.id).toLowerCase())) {
      const target = shimeji.get(c.id);
      if (target) return { x: target.state.x, label: c.displayName || c.id };
    }
  }
  const kitchenPos = kitchen.getPosition();
  if (kitchenPos && haystack.includes('cocin')) return { x: kitchenPos.x, label: 'la cocina' };
  return null;
}

function reactToSayIntent(text, characterId) {
  const speech = text || '';
  const followUps = [];
  if (JUMP_SPEECH_RE.test(speech)) {
    shimeji.sendCommand(characterId, 'set_animation', { state: 'jump' });
    followUps.push('salto');
  }
  const dest = resolveMoveTarget(speech, characterId);
  if (STRONG_MOVE_RE.test(speech) || (dest && WEAK_MOVE_RE.test(speech))) {
    const me = shimeji.get(characterId);
    if (me) {
      const run = RUN_SPEECH_RE.test(speech);
      if (dest) {
        me.state.startMoving(dest.x, run);
        followUps.push(`camina hacia ${dest.label}`);
      } else {
        me.state.randomTarget(run);
        followUps.push('camina un poco');
      }
    }
  }
  return followUps;
}

function mouseControlGate() {
  if (!config.allowMouseControl) return MOUSE_CONTROL_DISABLED_RESULT;
  const elapsed = Date.now() - lastMouseActionAt;
  if (elapsed < MOUSE_ACTION_COOLDOWN_MS) {
    return {
      ok: false,
      result: `Otro personaje uso el mouse hace poco - esperá ${Math.ceil((MOUSE_ACTION_COOLDOWN_MS - elapsed) / 1000)}s antes de volver a intentar.`,
    };
  }
  lastMouseActionAt = Date.now();
  return null;
}

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
    case 'move_mouse': {
      const gated = mouseControlGate();
      if (gated) return gated;
      await pointerHighlight.showFor(characterId, args.x, args.y);
      return { ok: true, result: await input.moveMouse(args.x, args.y) };
    }
    case 'walk_to':
      shimeji.sendCommand(characterId, 'walk_to', { x: args.x, y: args.y, run: args.run });
      return { ok: true, result: `orden enviada: ${args.run ? 'correr' : 'caminar'} a (${args.x}, ${args.y})` };
    case 'ride_mouse':
      // Not gated by allowMouseControl - this just visually rides along wherever the user's own
      // cursor already is, it never moves or clicks anything, so it isn't the same safety concern.
      shimeji.sendCommand(characterId, 'ride_mouse', { seconds: args.seconds });
      return { ok: true, result: 'orden enviada: subirse al cursor del mouse' };
    case 'click': {
      const gated = mouseControlGate();
      if (gated) return gated;
      return { ok: true, result: await input.click(args.button) };
    }
    case 'tap': {
      const gated = mouseControlGate();
      if (gated) return gated;
      await pointerHighlight.showFor(characterId, args.x, args.y);
      await input.tap(args.x, args.y, args.button);
      return { ok: true, result: `tap en (${args.x}, ${args.y})` };
    }
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
    case 'fight': {
      if (!config.survivalEnabled) return { ok: false, result: 'El sistema de vida/hambre/sed esta desactivado en Configuracion.' };
      const attacker = shimeji.get(characterId);
      if (!attacker) return { ok: false, result: 'No se encontro tu personaje.' };
      const targetId = String(args.target || '').trim();
      const target = shimeji.get(targetId);
      if (!target) return { ok: false, result: `No hay ningun personaje llamado "${args.target || ''}".` };
      const targetStats = survival.get(targetId);
      if (targetStats.dead) return { ok: false, result: `${targetId} ya esta muerto - no tiene sentido seguir pegandole.` };
      const dist = Math.abs(attacker.state.x - target.state.x);
      if (dist > 100) {
        return {
          ok: false,
          result: `Estas a ${Math.round(dist)}px de ${targetId} - muy lejos para pegarle. Acercate con walk_to (esta en x=${Math.round(target.state.x)}) y ataca de nuevo.`,
        };
      }
      const dmg = Math.min(40, Math.max(5, Math.round(Number(args.strength) || 12)));
      const after = survival.applyDamage(targetId, dmg);
      target.state.say(`¡Auch! (${dmg} de daño)`);
      target.state.setEmotion('trip');
      target.state.setFace('angry', 'frown');
      attacker.state.setEmotion('angry');
      if (after.dead) target.state.kill();
      return {
        ok: true,
        result: `Le pegaste a ${targetId} (${dmg} de daño) - le queda ${Math.round(after.hp)}/100 de vida.` + (after.dead ? ' ¡Lo mataste!' : ''),
      };
    }
    case 'eat':
    case 'drink': {
      if (!config.survivalEnabled) return { ok: false, result: 'El sistema de vida/hambre/sed esta desactivado en Configuracion.' };
      const me = shimeji.get(characterId);
      if (!me) return { ok: false, result: 'No se encontro tu personaje.' };
      const kitchenPos = kitchen.getPosition();
      if (!kitchenPos) return { ok: false, result: 'Todavia no hay cocina en este lugar - no hay nada para comer/beber.' };
      const dist = Math.abs(me.state.x - kitchenPos.x);
      if (dist > 150) {
        return {
          ok: false,
          result: `La cocina esta lejos (a ${Math.round(dist)}px). Anda hasta ahi con walk_to (x=${Math.round(kitchenPos.x)}) y cuando estes al lado pedi de nuevo ${name}.`,
        };
      }
      const stats = survival.get(characterId);
      const gain = 45;
      const noun = name === 'eat' ? 'hambre' : 'sed';
      const patch = name === 'eat' ? { hunger: stats.hunger + gain } : { thirst: stats.thirst + gain };
      survival.set(characterId, patch);
      me.state.say(name === 'eat' ? '¡Que rico!' : '¡Uf, qué sed tenía!');
      me.state.setEmotion('happy');
      // The visible performance: chew/drink gesture + food window shrink/tilt, kitchen station
      // transition. Runs on its own timeline in foodProp.js; the stats above already landed.
      if (name === 'eat') foodProp.playEat(characterId);
      else foodProp.playDrink(characterId);
      return { ok: true, result: `${name === 'eat' ? 'Comiste' : 'Tomaste agua'} y recuperaste ${noun} - ahora en ${Math.round(survival.get(characterId)[name === 'eat' ? 'hunger' : 'thirst'])}%.` };
    }
    case 'set_animation':
      shimeji.sendCommand(characterId, 'set_animation', { state: args.state, caption: args.caption });
      return { ok: true, result: 'animacion actualizada' };
    case 'set_emotion':
      shimeji.sendCommand(characterId, 'set_emotion', { eyes: args.eyes, mouth: args.mouth });
      return { ok: true, result: 'cara actualizada' };
    case 'say':
      shimeji.sendCommand(characterId, 'say', { text: args.text });
      // "Salta"/"se mueve a X" dichos en voz alta se hacen de verdad (ver reactToSayIntent).
      {
        const followUps = reactToSayIntent(args.text, characterId);
        return {
          ok: true,
          result: followUps.length ? `mensaje mostrado (ademas: ${followUps.join(', ')})` : 'mensaje mostrado',
        };
      }
    case 'set_custom_animation': {
      // Same string-vs-array leniency as draw_in_paint's points - a tool-calling model may hand
      // back "keyframes" as a JSON-encoded string instead of a real array.
      const raw = typeof args.keyframes === 'string' ? JSON.parse(args.keyframes) : args.keyframes;
      shimeji.sendCommand(characterId, 'set_custom_animation', { keyframes: raw || [] });
      return { ok: true, result: 'animacion personalizada iniciada' };
    }
    case 'define_personality':
      return { ok: true, result: String(args.description || '').slice(0, 500) };
    case 'set_context':
      return { ok: true, result: String(args.context || '').slice(0, 1000) };
    case 'remember':
      return { ok: true, result: String(args.note || '').slice(0, 300) };
    default:
      return { ok: false, result: `Accion desconocida: ${name}` };
  }
}

module.exports = { execute };
