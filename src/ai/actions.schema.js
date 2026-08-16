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
  { name: 'move_mouse', desc: 'Mueve el cursor a coordenadas x,y', params: { x: 'number', y: 'number' }, risky: false },
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
    name: 'move_random',
    desc: 'El stickman camina (o corre, si run=true) a un punto aleatorio de la pantalla, para no quedarse quieto',
    params: { run: 'boolean (opcional) - true = corre en vez de caminar' },
    risky: false,
  },
  {
    name: 'ride_mouse',
    desc:
      'El stickman se sube y viaja montado sobre el cursor del mouse del usuario durante unos segundos, ' +
      'siguiendolo a donde se mueva.',
    params: { seconds: 'number (opcional, default 6)' },
    risky: false,
  },
  { name: 'click', desc: 'Hace click con el mouse en la posicion actual', params: { button: 'string (left|right|middle, opcional)' }, risky: false },
  {
    name: 'tap',
    desc:
      'Mueve el mouse a x,y y hace click ahi mismo, en un solo paso (en vez de move_mouse y ' +
      'despues click por separado). Usalo cuando ya sabes exactamente donde clickear.',
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
      'Cambia la pose/emocion del stickman (no tiene cara, expresa todo con el cuerpo). ' +
      'happy=salta contento, dance=baila, trip=se tropieza/queda confundido, scared=corre asustado, ' +
      'sad=se cae, tired=se tira en un sillon, sleep=se acuesta, jump=salta, sit=se sienta.',
    params: {
      state: 'string (idle|walk|think|talk|point|wave|jump|sit|sleep|tired|happy|dance|trip|scared|sad)',
      caption: 'string (opcional)',
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
    name: 'remember',
    desc:
      'Anota algo que quieras recordar a largo plazo (un dato del usuario, algo que aprendiste, algo ' +
      'importante que paso). Se guarda y lo vas a seguir viendo en tu memoria en turnos futuros, incluso ' +
      'despues de reiniciar.',
    params: { note: 'string' },
    risky: false,
  },
];

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
