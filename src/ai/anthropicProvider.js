const Anthropic = require('@anthropic-ai/sdk');
const config = require('../config');
const { toAnthropicTools } = require('./actions.schema');

const tools = toAnthropicTools();

const SYSTEM_PROMPT = `Cada turno recibes una captura de pantalla actual, la ventana activa del sistema,
tu historial reciente de acciones y su resultado, y tu posicion actual en la pantalla.
Debes elegir EXACTAMENTE una accion (tool call) de las disponibles, basandote en lo que observas
en la captura de pantalla y en el contexto. Si el usuario te escribio algo en Paint, respondele
(normalmente escribiendo en Paint con write_in_paint, o abriendo Paint primero si no esta abierto).
Si quieres comunicarle algo al usuario directamente, usa la accion say.
Si tienes cara propia (se eligio al crearte - no todos la tienen), CUALQUIER accion acepta ademas
los parametros opcionales eyes (normal/wide/angry/heart) y mouth (neutral/smile/frown/open/angry)
para que tu cara reaccione en el MISMO turno - no hace falta un turno aparte con set_emotion salvo
que sea lo unico que quieras hacer.
Usa set_custom_animation seguido para armar tu propia pose de cuerpo cuando ninguna fija encaja.
A veces, si tu personalidad lo permite, podes desarrollar cariño especial (un "crush") por algun
peer con el que interactues seguido - no le pasa a todos, solo si surge naturalmente. Anotalo con
remember, sumalo con define_personality, y mostralo con say y set_emotion (eyes: heart).
Si no hay nada particular que hacer, usa set_animation con estado "idle", o wait.`;

async function decide(context) {
  const { screenshotBase64, personality, characterId, ...contextForModel } = context;
  const apiKey = characterId ? config.anthropic.apiKeyFor(characterId) : config.anthropic.apiKey;
  const client = new Anthropic({ apiKey });

  const content = [{ type: 'text', text: JSON.stringify(contextForModel) }];
  if (screenshotBase64) {
    content.push({
      type: 'image',
      source: { type: 'base64', media_type: 'image/png', data: screenshotBase64 },
    });
  }

  const msg = await client.messages.create({
    model: config.anthropic.model,
    max_tokens: 400,
    system: personality ? `${SYSTEM_PROMPT}\n\n${personality}` : SYSTEM_PROMPT,
    tools,
    tool_choice: { type: 'any' },
    messages: [{ role: 'user', content }],
  });

  const toolUse = msg.content.find((b) => b.type === 'tool_use');
  if (!toolUse) return { tool: 'wait', args: {} };
  return { tool: toolUse.name, args: toolUse.input || {} };
}

module.exports = { decide };
