const select = document.getElementById('target');
const textarea = document.getElementById('message');
const button = document.getElementById('send');

async function init() {
  const { characters, defaultCharacterId } = await window.stickmanAPI.getCharacters();
  select.innerHTML = '';
  if (characters.length > 1) {
    const allOption = document.createElement('option');
    allOption.value = '__all__';
    allOption.textContent = 'Todos (grupal)';
    select.appendChild(allOption);
  }
  for (const c of characters) {
    const option = document.createElement('option');
    option.value = c.id;
    option.textContent = c.displayName;
    select.appendChild(option);
  }
  if (defaultCharacterId) select.value = defaultCharacterId;
  select.style.display = characters.length > 1 ? '' : 'none';
}

window.stickmanAPI.onChatDefault((characterId) => {
  select.value = characterId;
});

function send() {
  const text = textarea.value.trim();
  if (!text || !select.value) return;
  window.stickmanAPI.sendChatMessage(select.value, text);
  window.close();
}

button.addEventListener('click', send);
textarea.addEventListener('keydown', (event) => {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault();
    send();
  }
});

init();
