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
const CHARACTERS = [
  { id: 'Red', displayName: 'Red', personality: '' },
  {
    // Usa el sprite de "Orange" pero narrativamente es The Second Coming (TSC).
    id: 'Orange',
    displayName: 'The Second Coming',
    personality: '',
  },
  { id: 'Purple', displayName: 'Purple', personality: '' },
  { id: 'Green', displayName: 'Green', personality: '' },
  { id: 'Blue', displayName: 'Blue', personality: '' },
  { id: 'Yellow', displayName: 'Yellow', personality: '' },
  { id: 'AI', displayName: 'AI', personality: '' },
  { id: 'TCO', displayName: 'TCO', personality: '' },
  { id: 'TDL', displayName: 'TDL', personality: '' },
  { id: 'victim', displayName: 'victim', personality: '' },
];

module.exports = CHARACTERS;
