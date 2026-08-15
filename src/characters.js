// Lista de "amigos" con IA propia. El id debe coincidir EXACTO (mayusculas
// incluidas) con el nombre de carpeta de sprites bajo img/ (ver
// conf/settings.properties -> ActiveShimeji) y con el nombre que
// Main.AI_DRIVEN_CHARACTERS reconoce del lado Java.
//
// Para sumar otro amigo: agregar una entrada aca y otra en AI_DRIVEN_CHARACTERS
// (Main.java), recompilar el jar. No hace falta tocar el resto del codigo.
//
// Personalidad vacia a proposito en todos: cada uno se define solo con la accion
// define_personality (ver agentLoop.js) y queda guardado en workspace/personality-<id>.json.
// Purple, AI, TCO, TDL y victim quedaron afuera a proposito: comparten keys de Gemini con la
// cuota gratuita agotada (ver .env) y solo terminaban en el fallback de move_random sin IA real.
// Agregalos de nuevo aca (con su key nueva en .env) cuando tengan cuota disponible.
const CHARACTERS = [
  { id: 'Red', displayName: 'Red', personality: '' },
  {
    // Usa el sprite de "Orange" pero narrativamente es The Second Coming (TSC).
    id: 'Orange',
    displayName: 'The Second Coming',
    personality: '',
  },
  { id: 'Green', displayName: 'Green', personality: '' },
  { id: 'Blue', displayName: 'Blue', personality: '' },
  { id: 'Yellow', displayName: 'Yellow', personality: '' },
];

module.exports = CHARACTERS;
