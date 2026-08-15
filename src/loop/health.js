// Estado de "salud" de IA por personaje - ok mientras su ultimo turno haya
// terminado bien (provider.decide + executor.execute sin tirar error), no-ok
// si el ultimo turno fallo (cuota agotada, timeout, etc.). Usado para no
// mostrar en la web/peers a los que en ese momento no tienen IA real detras,
// solo el fallback de move_random.
const healthById = new Map();

function setOk(characterId) {
  healthById.set(characterId, { ok: true, error: null });
}

function setError(characterId, message) {
  healthById.set(characterId, { ok: false, error: message });
}

function isOk(characterId) {
  const entry = healthById.get(characterId);
  return !entry || entry.ok;
}

module.exports = { setOk, setError, isOk };
