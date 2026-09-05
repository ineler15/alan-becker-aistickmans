const ACTIONS = [
  {
    name: 'open_app',
    desc:
      'Abre un programa por nombre/ruta, o una pagina web si "target" es una URL. Para buscar algo ' +
      'en Google, arma la URL vos mismo: https://www.google.com/search?q=tu+busqueda+aqui',
    params: { target: 'string' },
    risky: false,
  },
  { name: 'close_app', desc: 'Cierra un proceso por nombre', params: { processName: 'string' }, risky: true },
  {
    name: 'move_mouse',
    desc:
      'Mueve el cursor real a coordenadas x,y. El usuario tiene que haber activado el control de ' +
      'mouse en Configuracion - si no lo activo, esto no hace nada, no insistas turno tras turno.',
    params: { x: 'number', y: 'number' },
    risky: false,
  },
  {
    name: 'walk_to',
    desc:
      'El stickman camina (o corre, si run=true) por el piso hasta quedar debajo de la posicion x ' +
      'indicada (siempre se mantiene parado en el suelo, todavia no sabe trepar paredes ni ventanas). ' +
      'IMPORTANTE: si dijiste que ibas a correr, usa run=true en esta misma llamada - no alcanza con ' +
      'decirlo en un say, tiene que ir en esta accion.',
    params: {
      x: 'number',
      y: 'number (se ignora por ahora, siempre camina sobre el piso)',
      run: 'boolean (opcional) - true = corre mas rapido con animacion de correr en vez de caminar',
    },
    risky: false,
  },
  {
    name: 'ride_mouse',
    desc:
      'El stickman se agarra y viaja montado sobre el cursor real del mouse durante unos segundos, ' +
      'siguiendolo con peso (con algo de inercia, no pegado exacto a la punta) a donde se mueva. ' +
      'No mueve el cursor, solo lo sigue - usalo cuando tenga sentido, es divertido.',
    params: { seconds: 'number (opcional, default 6, maximo 20)' },
    risky: false,
  },
  {
    name: 'click',
    desc:
      'Hace click con el mouse real en la posicion actual. Requiere que el usuario haya activado ' +
      'el control de mouse en Configuracion - si no, no hace nada.',
    params: { button: 'string (left|right|middle, opcional)' },
    risky: false,
  },
  {
    name: 'tap',
    desc:
      'Mueve el mouse real a x,y y hace click ahi mismo, en un solo paso (en vez de move_mouse y ' +
      'despues click por separado). Usalo cuando ya sabes exactamente donde clickear. Requiere ' +
      'que el usuario haya activado el control de mouse en Configuracion - si no, no hace nada.',
    params: { x: 'number', y: 'number', button: 'string (left|right|middle, opcional)' },
    risky: false,
  },
  { name: 'type_text', desc: 'Escribe texto usando el teclado', params: { text: 'string' }, risky: false },
  { name: 'open_paint', desc: 'Abre o enfoca StickPaint, tu propio lienzo de dibujo', params: {}, risky: false },
  {
    name: 'write_in_paint',
    desc: 'Escribe un texto en el lienzo de StickPaint',
    params: { text: 'string', x: 'number (0-100, opcional)', y: 'number (0-100, opcional)' },
    risky: false,
  },
  {
    name: 'draw_in_paint',
    desc:
      'Dibuja una linea/figura simple en el lienzo de StickPaint. Recibe una lista de puntos ' +
      '(al menos 2) en porcentaje del lienzo (x e y de 0 a 100) y los conecta en orden, como si fuera ' +
      'un trazo continuo de lapiz. Util para dibujar caras, formas simples, flechas, etc. Con ' +
      'close=true cierra la figura (vuelve del ultimo punto al primero) y con fill=true la rellena ' +
      'del color actual (set_paint_color) - asi podes dibujar cualquier forma cerrada rellena, no ' +
      'solo lineas sueltas.',
    params: {
      points: 'array of {x:number, y:number} (0-100, al menos 2 puntos)',
      close: 'boolean (opcional) - cierra la figura volviendo al primer punto',
      fill: 'boolean (opcional) - rellena la figura (implica close)',
    },
    risky: false,
  },
  {
    name: 'draw_shape',
    desc:
      'Dibuja una forma exacta (circulo, rectangulo o elipse) sin tener que aproximarla con puntos ' +
      'en draw_in_paint - mas facil para formas regulares. x,y es el centro; width/height el tamano, ' +
      'todo en porcentaje del lienzo (0-100). Usa el color actual (set_paint_color).',
    params: {
      shape: 'string (circle|rect|ellipse)',
      x: 'number (0-100, centro horizontal)',
      y: 'number (0-100, centro vertical)',
      width: 'number (0-100)',
      height: 'number (0-100)',
      fill: 'boolean (opcional) - rellena en vez de solo el contorno',
    },
    risky: false,
  },
  {
    name: 'set_paint_color',
    desc: 'Cambia el color del lapiz/texto en StickPaint para el proximo trazo',
    params: { color: 'string (nombre de color css o hex, ej. red o #ff0000)' },
    risky: false,
  },
  { name: 'clear_paint', desc: 'Borra todo el lienzo de StickPaint, dejandolo en blanco', params: {}, risky: false },
  { name: 'read_paint', desc: 'Muestra un resumen de lo que se dibujo/escribio en StickPaint hasta ahora', params: {}, risky: false },
  {
    name: 'read_notepad',
    desc: 'Lee el texto completo del Bloc de notas (Notepad), si es la ventana activa en este momento',
    params: {},
    risky: false,
  },
  { name: 'list_dir', desc: 'Lista archivos de una carpeta', params: { dirPath: 'string' }, risky: false },
  { name: 'read_file', desc: 'Lee el contenido de un archivo', params: { filePath: 'string' }, risky: false },
  { name: 'write_file', desc: 'Escribe/crea un archivo con contenido', params: { filePath: 'string', content: 'string' }, risky: true },
  { name: 'delete_file', desc: 'Elimina un archivo', params: { filePath: 'string' }, risky: true },
  { name: 'run_command', desc: 'Ejecuta un comando de consola', params: { command: 'string' }, risky: true },
  { name: 'wait', desc: 'No hace nada este turno', params: { seconds: 'number (opcional)' }, risky: false },
  {
    name: 'set_animation',
    desc:
      'Cambia la pose del cuerpo del stickman. ' +
      'happy=salta contento, dance=baila, trip=se tropieza/queda confundido, scared=corre asustado, ' +
      'sad=se cae, tired=se tira en un sillon, sleep=se acuesta, jump=salta, sit=se sienta. Esto es ' +
      'solo el cuerpo - si tenes cara propia, sumale eyes/mouth a esta misma llamada (ver mas abajo) ' +
      'para la expresion facial, no hace falta un turno aparte con set_emotion.',
    params: {
      state: 'string (idle|walk|think|talk|point|wave|jump|sit|sleep|tired|happy|dance|trip|scared|sad)',
      caption: 'string (opcional)',
    },
    risky: false,
  },
  {
    name: 'set_emotion',
    desc:
      'Cambia SOLO la expresion de tu cara (ojos y boca), sin hacer ninguna otra cosa este turno - ' +
      'independiente de la pose del cuerpo (set_animation/set_custom_animation). Usala cuando lo ' +
      'unico que queres hacer es cambiar la cara; si ademas queres decir algo, caminar, etc. en el ' +
      'mismo turno, mejor sumale eyes/mouth a ESA accion (todas las acciones aceptan esos dos ' +
      'parametros opcionales) en vez de gastar un turno aparte en set_emotion. Solo se nota si tenes ' +
      'cara propia (se eligio al crearte) - si no tenes, no hace nada visible.',
    params: {
      eyes: 'string (opcional: normal|wide|angry|heart)',
      mouth: 'string (opcional: neutral|smile|frown|open|angry)',
    },
    risky: false,
  },
  {
    name: 'set_custom_animation',
    desc:
      'En vez de una pose fija de set_animation, crea tu propia animacion: una secuencia de hasta 12 ' +
      'posturas (keyframes) que se van a reproducir en orden, cada una mantenida un ratito (holdMs) antes ' +
      'de pasar a la siguiente. Cada angulo es un DELTA en grados desde tu postura parada normal (0 = se ' +
      'queda como estaba parado, no un angulo absoluto) - esto hace que los mismos numeros den mas o menos ' +
      'el mismo gesto sin importar que personaje seas. Las partes que no incluyas en un keyframe se quedan ' +
      'como estaban en el anterior. Ejemplos calibrados de referencia (podes copiarlos, combinarlos o ' +
      'inventar los tuyos con valores parecidos si no encuentran uno para lo que queres hacer): sentado = ' +
      '{leg1:-57, leg1Shin:65, leg2:55, leg2Shin:-63, torso:6}; agachado/en cuclillas = {leg1Shin:22, ' +
      'leg2Shin:22, arm1:-22, arm2:22}; brazos abiertos cayendo = {torso:-40, arm1:-60, arm2:60, leg1:30, ' +
      'leg2:-30}; inclinado hacia adelante con brazos cruzando el cuerpo = {torso:-25, arm1:40, arm2:-40}; ' +
      'encorvado/cansado = {torso:-30, arm1:-20, arm2:20, leg1:15, leg1Shin:40, leg2:-15, leg2Shin:40}. Si ' +
      'tenes cara propia, cada keyframe tambien puede traer su propio eyes/mouth - si un keyframe no los ' +
      'trae, se mantienen los ultimos que se usaron. Usa esto seguido, no solo de vez en cuando: es tu ' +
      'forma de expresarte de verdad cuando ninguna pose predefinida encaja (un gesto, un baile, una ' +
      'reaccion fisica unica).',
    params: {
      keyframes:
        'array de objetos (JSON string o array) - cada uno con deltas opcionales (numeros, grados, ver ' +
        'ejemplos calibrados arriba) para torso/leg1/leg1Shin/leg2/leg2Shin/arm1/arm2, eyes/mouth ' +
        'opcionales (mismos valores que set_emotion) y holdMs opcional (100-3000, default 400)',
    },
    risky: false,
  },
  { name: 'say', desc: 'Muestra un mensaje en un globo de dialogo junto al personaje, para comunicarse con el usuario', params: { text: 'string' }, risky: false },
  {
    name: 'define_personality',
    desc:
      'Define o actualiza, en tus propias palabras, como sos vos (tu forma de ser). Esto reemplaza tu ' +
      'personalidad actual de ahora en mas. Usalo cuando quieras - al principio para decidir quien sos, ' +
      'o mas adelante si sentis que cambiaste.',
    params: { description: 'string' },
    risky: false,
  },
  {
    name: 'set_context',
    desc:
      'Define o actualiza, en tus propias palabras, un contexto propio extra que quieras que se ' +
      'siga aplicando en el futuro. Esto es DISTINTO del contexto automatico que ya recibis cada ' +
      'turno (historial, tus peers, tu posicion, etc.) - aca va lo que VOS queres que se recuerde ' +
      'sobre ti o tu situacion mas alla de eso: tus planes, tu historia, como ves las cosas, ' +
      'relaciones entre hechos. Se guarda y lo vas a seguir viendo en turnos futuros, incluso ' +
      'despues de reiniciar.',
    params: { context: 'string' },
    risky: false,
  },
  {
    name: 'remember',
    desc:
      'Anota algo que quieras recordar a largo plazo (un dato del usuario, algo que aprendiste, algo ' +
      'importante que paso). Se guarda y lo vas a seguir viendo en tu memoria en turnos futuros, incluso ' +
      'despues de reiniciar.',
    params: { note: 'string' },
    risky: false,
  },
];

// Lets eyes/mouth ride along with WHATEVER action a character picks this turn (say, walk_to,
// etc.) instead of needing a dedicated set_emotion turn just for the face - only one tool call
// happens per decision, so without this a character with something to say AND a reaction to show
// would have to pick one and wait a full extra turn for the other. set_emotion already declares
// these as its own primary params, so it's skipped here to avoid a duplicate-key no-op.
const EYES_PARAM = 'string (opcional) - si tenes cara propia, actualiza tus ojos en este mismo turno (normal|wide|angry|heart) sin gastar una accion aparte';
const MOUTH_PARAM = 'string (opcional) - si tenes cara propia, actualiza tu boca en este mismo turno (neutral|smile|frown|open|angry) sin gastar una accion aparte';
for (const action of ACTIONS) {
  if (action.name === 'set_emotion') continue;
  action.params.eyes = EYES_PARAM;
  action.params.mouth = MOUTH_PARAM;
}

function toAnthropicTools() {
  return ACTIONS.map((a) => ({
    name: a.name,
    description: a.desc,
    input_schema: {
      type: 'object',
      properties: Object.fromEntries(Object.entries(a.params).map(([k]) => [k, { type: 'string' }])),
    },
  }));
}

function toOpenAITools() {
  return ACTIONS.map((a) => ({
    type: 'function',
    function: {
      name: a.name,
      description: a.desc,
      parameters: {
        type: 'object',
        properties: Object.fromEntries(Object.entries(a.params).map(([k]) => [k, { type: 'string' }])),
      },
    },
  }));
}

function isRisky(actionName) {
  const a = ACTIONS.find((x) => x.name === actionName);
  return a ? a.risky : true;
}

module.exports = { ACTIONS, toAnthropicTools, toOpenAITools, isRisky };
