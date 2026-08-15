const Anthropic = require('@anthropic-ai/sdk');
const config = require('../config');
const { toAnthropicTools } = require('./actions.schema');

const client = new Anthropic({ apiKey: config.anthropic.apiKey });
const tools = toAnthropicTools();

const SYSTEM_PROMPT = `Cada turno recibes una captura de pantalla actual, la ventana activa del sistema,
tu historial reciente de acciones y su resultado, y tu posicion actual en la pantalla.
Debes elegir EXACTAMENTE una accion (tool call) de las disponibles, basandote en lo que observas
en la captura de pantalla y en el contexto. Si el usuario te escribio algo en Paint, respondele
(normalmente escribiendo en Paint con write_in_paint, o abriendo Paint primero si no esta abierto).
Si quieres comunicarle algo al usuario directamente, usa la accion say.
Si no hay nada particular que hacer, usa set_animation con estado "idle", o wait.`;

async function decide(context) {
  const { screenshotBase64, personality, ...contextForModel } = context;

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
