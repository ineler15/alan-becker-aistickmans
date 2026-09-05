// Rebuilding charList wipes any not-yet-saved edits in it, but that only happens when a
// characters-updated push arrives (i.e. the user just created a character from this same
// window) - an acceptable tradeoff for keeping this simple.
// A key field pre-fills with whatever's already saved, so re-pasting into it without clearing
// first (easy to do across separate settings sessions) silently appends instead of replacing -
// confirmed happening for real (a key ended up pasted 2x, then 3x, back to back). Collapses an
// exact N-times self-repeated string back to one copy; leaves anything else untouched.
function collapseRepeatedKey(value) {
  const v = (value || '').trim();
  for (const times of [4, 3, 2]) {
    if (v.length % times !== 0 || v.length === 0) continue;
    const unit = v.slice(0, v.length / times);
    if (unit.repeat(times) === v) return unit;
  }
  return v;
}

let keyInputs = {};
let providerSelects = {};
let checkboxes = {};
let partnerSelects = {};
let affectionInputs = {};
let contextInputs = {};
let editingContextId = null;

async function renderCharacterList(providers) {
  const { characters, settings } = await window.stickmanAPI.getPcSettings();
  const charList = document.getElementById('charList');
  charList.innerHTML = '';
  keyInputs = {};
  providerSelects = {};
  checkboxes = {};
  partnerSelects = {};
  affectionInputs = {};
  contextInputs = {};
  const enabledIds = new Set(settings.enabledIds || []);
  const perCharacterProvider = settings.perCharacterProvider || {};
  const perCharacterPartner = settings.perCharacterPartner || {};
  const perCharacterAffection = settings.perCharacterAffection || {};
  const perCharacterContext = settings.perCharacterContext || {};
  for (const c of characters) {
    const row = document.createElement('div');
    row.className = 'char-row';

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.style.width = 'auto';
    checkbox.checked = enabledIds.has(c.id);
    checkboxes[c.id] = checkbox;

    const label = document.createElement('span');
    label.textContent = c.displayName;

    const charProviderSelect = document.createElement('select');
    const sharedOpt = document.createElement('option');
    sharedOpt.value = '';
    sharedOpt.textContent = '(compartido)';
    charProviderSelect.appendChild(sharedOpt);
    for (const p of providers) {
      const opt = document.createElement('option');
      opt.value = p;
      opt.textContent = p;
      charProviderSelect.appendChild(opt);
    }
    charProviderSelect.value = perCharacterProvider[c.id] || '';
    providerSelects[c.id] = charProviderSelect;

    const input = document.createElement('input');
    input.type = 'password';
    input.placeholder = 'API key propia (opcional)';
    input.value = collapseRepeatedKey((settings.perCharacterKeys || {})[c.id] || '');
    keyInputs[c.id] = input;

    const partnerSelect = document.createElement('select');
    const noPartnerOpt = document.createElement('option');
    noPartnerOpt.value = '';
    noPartnerOpt.textContent = '(sin pareja)';
    partnerSelect.appendChild(noPartnerOpt);
    for (const other of characters) {
      if (other.id === c.id) continue;
      const opt = document.createElement('option');
      opt.value = other.id;
      opt.textContent = other.displayName;
      partnerSelect.appendChild(opt);
    }
    partnerSelect.value = perCharacterPartner[c.id] || '';
    partnerSelect.title = 'A quien le tiene cariño - configuralo en ambos personajes para que sea mutuo';
    partnerSelects[c.id] = partnerSelect;

    // How strong that affection is - a slider instead of just on/off, only meaningful once a
    // target is picked above (disabled otherwise so it's clear it does nothing on its own).
    const affectionWrap = document.createElement('div');
    affectionWrap.className = 'affection-wrap';
    const affectionInput = document.createElement('input');
    affectionInput.type = 'range';
    affectionInput.min = '0';
    affectionInput.max = '100';
    affectionInput.value = String(perCharacterAffection[c.id] ?? 50);
    affectionInput.disabled = !partnerSelect.value;
    affectionInput.title = 'Nivel de afecto hacia esa persona';
    const affectionLabel = document.createElement('span');
    affectionLabel.className = 'affection-label';
    affectionLabel.textContent = `${affectionInput.value}%`;
    affectionInput.addEventListener('input', () => {
      affectionLabel.textContent = `${affectionInput.value}%`;
    });
    partnerSelect.addEventListener('change', () => {
      affectionInput.disabled = !partnerSelect.value;
    });
    affectionWrap.appendChild(affectionInput);
    affectionWrap.appendChild(affectionLabel);
    affectionInputs[c.id] = affectionInput;

    row.appendChild(checkbox);
    row.appendChild(label);
    row.appendChild(charProviderSelect);
    row.appendChild(input);
    row.appendChild(partnerSelect);
    row.appendChild(affectionWrap);

    // Only custom characters (see main.js's isCustom flag) have appearance fields worth editing -
    // the vanilla/built-in ones don't get this button.
    if (c.isCustom) {
      const editBtn = document.createElement('button');
      editBtn.textContent = 'Editar';
      editBtn.type = 'button';
      editBtn.addEventListener('click', () => window.stickmanAPI.openEditCharacterWindow(c.id));
      row.appendChild(editBtn);
    }

    const contextBtn = document.createElement('button');
    contextBtn.textContent = 'Contexto';
    contextBtn.type = 'button';
    contextBtn.addEventListener('click', () => openContextEditor(c));
    row.appendChild(contextBtn);

    // Seed this character's saved user-context so openContextEditor() can prefill it. Stored as
    // a plain string map (not a DOM input) - it only changes through the modal.
    contextInputs[c.id] = perCharacterContext[c.id] || '';

    charList.appendChild(row);
  }
}

function openContextEditor(c) {
  editingContextId = c.id;
  document.getElementById('contextModalTitle').textContent = `Contexto de ${c.displayName}`;
  document.getElementById('contextText').value = contextInputs[c.id] || '';
  document.getElementById('contextModal').style.display = 'flex';
}

async function init() {
  const { providers, settings } = await window.stickmanAPI.getPcSettings();

  const providerSelect = document.getElementById('provider');
  for (const p of providers) {
    const opt = document.createElement('option');
    opt.value = p;
    opt.textContent = p;
    if (p === settings.provider) opt.selected = true;
    providerSelect.appendChild(opt);
  }

  document.getElementById('sharedKey').value = collapseRepeatedKey(settings.sharedApiKey || '');
  document.getElementById('allowMouseControl').checked = !!settings.allowMouseControl;
  document.getElementById('attentionFocus').value = settings.attentionFocus === 'mouse' ? 'mouse' : 'camera';

  document.getElementById('contextSaveBtn').addEventListener('click', () => {
    if (editingContextId) {
      contextInputs[editingContextId] = document.getElementById('contextText').value.trim();
    }
    document.getElementById('contextModal').style.display = 'none';
  });
  document.getElementById('contextCancelBtn').addEventListener('click', () => {
    document.getElementById('contextModal').style.display = 'none';
  });

  await renderCharacterList(providers);

  window.stickmanAPI.onCharactersUpdated(() => renderCharacterList(providers));

  document.getElementById('createCharacterBtn').addEventListener('click', () => {
    window.stickmanAPI.openCreateCharacterWindow();
  });

  document.getElementById('exitBtn').addEventListener('click', () => {
    window.stickmanAPI.quitApp();
  });

  document.getElementById('continueBtn').addEventListener('click', () => {
    const perCharacterKeys = {};
    for (const id in keyInputs) perCharacterKeys[id] = collapseRepeatedKey(keyInputs[id].value);
    const perCharacterProviderOut = {};
    for (const id in providerSelects) perCharacterProviderOut[id] = providerSelects[id].value;
    const perCharacterPartnerOut = {};
    for (const id in partnerSelects) perCharacterPartnerOut[id] = partnerSelects[id].value;
    const perCharacterAffectionOut = {};
    for (const id in affectionInputs) perCharacterAffectionOut[id] = Number(affectionInputs[id].value);
    const perCharacterContextOut = {};
    for (const id in contextInputs) {
      if (contextInputs[id]) perCharacterContextOut[id] = contextInputs[id];
    }
    const enabled = Object.keys(checkboxes).filter((id) => checkboxes[id].checked);
    window.stickmanAPI.savePcSettings({
      provider: providerSelect.value,
      sharedApiKey: collapseRepeatedKey(document.getElementById('sharedKey').value),
      perCharacterKeys,
      perCharacterProvider: perCharacterProviderOut,
      perCharacterPartner: perCharacterPartnerOut,
      perCharacterAffection: perCharacterAffectionOut,
      perCharacterContext: perCharacterContextOut,
      enabledIds: enabled,
      allowMouseControl: document.getElementById('allowMouseControl').checked,
      attentionFocus: document.getElementById('attentionFocus').value,
    });
  });
}

init();
