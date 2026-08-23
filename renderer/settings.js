// Rebuilding charList wipes any not-yet-saved edits in it, but that only happens when a
// characters-updated push arrives (i.e. the user just created a character from this same
// window) - an acceptable tradeoff for keeping this simple.
let keyInputs = {};
let providerSelects = {};
let checkboxes = {};
let partnerSelects = {};

async function renderCharacterList(providers) {
  const { characters, settings } = await window.stickmanAPI.getPcSettings();
  const charList = document.getElementById('charList');
  charList.innerHTML = '';
  keyInputs = {};
  providerSelects = {};
  checkboxes = {};
  partnerSelects = {};
  const enabledIds = new Set(settings.enabledIds || []);
  const perCharacterProvider = settings.perCharacterProvider || {};
  const perCharacterPartner = settings.perCharacterPartner || {};
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
    input.value = (settings.perCharacterKeys || {})[c.id] || '';
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
    partnerSelect.title = 'Pareja - configuralo en ambos personajes para que sea mutuo';
    partnerSelects[c.id] = partnerSelect;

    row.appendChild(checkbox);
    row.appendChild(label);
    row.appendChild(charProviderSelect);
    row.appendChild(input);
    row.appendChild(partnerSelect);
    charList.appendChild(row);
  }
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

  document.getElementById('sharedKey').value = settings.sharedApiKey || '';
  document.getElementById('allowMouseControl').checked = !!settings.allowMouseControl;

  await renderCharacterList(providers);

  window.stickmanAPI.onCharactersUpdated(() => renderCharacterList(providers));

  document.getElementById('createCharacterBtn').addEventListener('click', () => {
    window.stickmanAPI.openCreateCharacterWindow();
  });

  document.getElementById('continueBtn').addEventListener('click', () => {
    const perCharacterKeys = {};
    for (const id in keyInputs) perCharacterKeys[id] = keyInputs[id].value.trim();
    const perCharacterProviderOut = {};
    for (const id in providerSelects) perCharacterProviderOut[id] = providerSelects[id].value;
    const perCharacterPartnerOut = {};
    for (const id in partnerSelects) perCharacterPartnerOut[id] = partnerSelects[id].value;
    const enabled = Object.keys(checkboxes).filter((id) => checkboxes[id].checked);
    window.stickmanAPI.savePcSettings({
      provider: providerSelect.value,
      sharedApiKey: document.getElementById('sharedKey').value.trim(),
      perCharacterKeys,
      perCharacterProvider: perCharacterProviderOut,
      perCharacterPartner: perCharacterPartnerOut,
      enabledIds: enabled,
      allowMouseControl: document.getElementById('allowMouseControl').checked,
    });
  });
}

init();
