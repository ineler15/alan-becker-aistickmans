const config = require('../config');

function getProvider(characterId) {
  switch (config.providerFor(characterId)) {
    case 'openrouter':
      return require('./openrouterProvider');
    case 'groq':
      return require('./groqProvider');
    case 'ollama':
      return require('./ollamaProvider');
    case 'openai':
      return require('./openaiProvider');
    case 'gemini':
      return require('./geminiProvider');
    default:
      return require('./anthropicProvider');
  }
}

module.exports = { getProvider };
