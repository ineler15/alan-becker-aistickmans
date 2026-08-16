async function init() {
  const { providers, characters, settings } = await window.stickmanAPI.getPcSettings();

  const providerSelect = document.getElementById('provider');
  for (const p of providers) {
    const opt = document.createElement('option');
    opt.value = p;
    opt.textContent = p;
    if (p === settings.provider) opt.selected = true;
    providerSelect.appendChild(opt);
  }

  document.getElementById('sharedKey').value = settings.sharedApiKey || '';

  const charList = document.getElementById('charList');
  const keyInputs = {};
  const checkboxes = {};
  const enabledIds = new Set(settings.enabledIds || []);
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

    const input = document.createElement('input');
    input.type = 'password';
    input.placeholder = 'API key propia (opcional)';
    input.value = (settings.perCharacterKeys || {})[c.id] || '';
    keyInputs[c.id] = input;

    row.appendChild(checkbox);
    row.appendChild(label);
    row.appendChild(input);
    charList.appendChild(row);
  }

  document.getElementById('continueBtn').addEventListener('click', () => {
    const perCharacterKeys = {};
    for (const id in keyInputs) perCharacterKeys[id] = keyInputs[id].value.trim();
    const enabled = Object.keys(checkboxes).filter((id) => checkboxes[id].checked);
    window.stickmanAPI.savePcSettings({
      provider: providerSelect.value,
      sharedApiKey: document.getElementById('sharedKey').value.trim(),
      perCharacterKeys,
      enabledIds: enabled,
    });
  });
}

init();
