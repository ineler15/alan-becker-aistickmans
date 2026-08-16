// Lista completa de "amigos" con IA propia. El id debe coincidir EXACTO (mayusculas
// incluidas) con el nombre de carpeta de sprites bajo img/ (ver
// conf/settings.properties -> ActiveShimeji) y con el nombre que
// Main.AI_DRIVEN_CHARACTERS reconoce del lado Java.
//
// Para sumar otro amigo: agregar una entrada aca y otra en AI_DRIVEN_CHARACTERS
// (Main.java), recompilar el jar. No hace falta tocar el resto del codigo.
//
// Personalidad vacia a proposito en todos: cada uno se define solo con la accion
// define_personality (ver agentLoop.js) y queda guardado en workspace/personality-<id>.json.
const ALL_CHARACTERS = [
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
  { id: 'Purple', displayName: 'Purple', personality: '' },
  { id: 'TCO', displayName: 'The Chosen One', personality: '' },
  { id: 'TDL', displayName: 'The Dark Lord', personality: '' },
  { id: 'victim', displayName: 'Victim', personality: '' },
];

// Which of the above are actually AI-driven at startup - chosen from the pre-launch settings
// window (see src/pcSettings.js) instead of being hardcoded. Starts out with the same subset
// that used to be hardcoded here by default (the rest had shared Gemini keys with an exhausted
// free-tier quota and just fell back to move_random with no real AI - see pcSettings.applyEnabledCharacters,
// called from main.js right before startShimeji()/agentLoop.start()). This array's IDENTITY
// (not just contents) matters - every module below requires this same array object and reads it
// live each tick, so it's mutated in place rather than reassigned.
const CHARACTERS = ['Red', 'Orange', 'Green', 'Blue', 'Yellow']
  .map((id) => ALL_CHARACTERS.find((c) => c.id === id))
  .filter(Boolean);

CHARACTERS.ALL = ALL_CHARACTERS;

module.exports = CHARACTERS;
