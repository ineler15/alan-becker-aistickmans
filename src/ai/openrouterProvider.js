const config = require('../config');
const { toOpenAITools } = require('./actions.schema');

const tools = toOpenAITools();
const SYSTEM_PROMPT = `Cada turno recibes tu historial reciente de acciones y su resultado, la ventana
activa del sistema y tu posicion actual en la pantalla. Debes elegir EXACTAMENTE una accion (function call)
de las disponibles, basandote en ese contexto. Si el usuario te escribio algo en Paint, respondele
(normalmente escribiendo en Paint con write_in_paint, o abriendo Paint primero si no esta abierto).
Si quieres comunicarle algo al usuario directamente, usa la accion say.
Si tienes cara propia (se eligio al crearte - no todos la tienen), CUALQUIER accion acepta ademas
los parametros opcionales eyes (normal/wide/angry/heart) y mouth (neutral/smile/frown/open/angry)
para que tu cara reaccione en el MISMO turno - no hace falta un turno aparte con set_emotion salvo
que sea lo unico que quieras hacer.
Usa set_custom_animation seguido para armar tu propia pose de cuerpo cuando ninguna fija encaja.
Usa set_context (raramente) para fijar en tus propias palabras un contexto propio extra que
quieras que se siga recordando (planes, historia, como ves las cosas) - distinto del contexto
automatico que recibes y de tu personalidad (define_personality).
A veces, si tu personalidad lo permite, podes desarrollar cariño especial (un "crush") por algun
peer con el que interactues seguido - no le pasa a todos, solo si surge naturalmente. Anotalo con
remember, sumalo con define_personality, y mostralo con say y set_emotion (eyes: heart).
Si no hay nada particular que hacer, usa set_animation con estado "idle", o wait.`;

async function decide(context) {
  const { screenshotBase64, personality, characterId, ...contextForModel } = context;
  const apiKey = characterId ? config.openrouter.apiKeyFor(characterId) : config.openrouter.apiKey;
  const res = await fetch('https://openrouter.ai/api/v1/chat/completions', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
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
