const config = require('../config');
const { toOpenAITools } = require('./actions.schema');

const tools = toOpenAITools();
const SYSTEM_PROMPT = `Cada turno recibes tu historial reciente de acciones y su resultado, la ventana
activa del sistema y tu posicion actual en la pantalla. Debes elegir EXACTAMENTE una accion (function call)
de las disponibles, basandote en ese contexto. Si el usuario te escribio algo en Paint, respondele
(normalmente escribiendo en Paint con write_in_paint, o abriendo Paint primero si no esta abierto).
Si quieres comunicarle algo al usuario directamente, usa la accion say.
Si no hay nada particular que hacer, usa set_animation con estado "idle", o wait.`;

async function decide(context) {
  const { screenshotBase64, personality, ...contextForModel } = context;
  const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${config.openrouter.apiKey}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      model: config.openrouter.model,
      tools,
      tool_choice: 'required',
      messages: [
        { role: 'system', content: personality ? `${SYSTEM_PROMPT}\n\n${personality}` : SYSTEM_PROMPT },
        { role: 'user', content: JSON.stringify(contextForModel) },
      ],
    }),
  });
  const data = await res.json();
  const call = data.choices?.[0]?.message?.tool_calls?.[0];
  if (!call) return { tool: 'wait', args: {} };
  return { tool: call.function.name, args: JSON.parse(call.function.arguments || '{}') };
}

module.exports = { decide };
