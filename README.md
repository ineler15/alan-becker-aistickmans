# Alan Becker AIStickmans

Stickmans de escritorio (estilo Alan Becker / Shimeji) que caminan sobre tu pantalla, cada uno con
su propia IA que decide que hacer: moverse, hablar, dibujar en su propio Paint, leer/escribir
archivos, abrir programas, etc. Incluye una app Android complementaria y una pagina web
("la casa de los stickmans") que muestra en vivo donde esta cada uno.

## Requisitos

- [Node.js](https://nodejs.org/) 18 o superior
- Java 8 (JRE) para el motor visual (Shimeji-ee). Ruta por defecto esperada:
  `C:\Program Files (x86)\Java\jre1.8.0_501\bin\javaw.exe` (configurable, ver mas abajo)
- Windows (el proyecto usa `tasklist`/`taskkill` y rutas de Java especificas de Windows)

## Instalacion

```bash
git clone https://github.com/<tu-usuario>/alan-becker-aistickmans.git
cd alan-becker-aistickmans
npm install
```

## Configurar las API keys

1. Copia `.env.example` a un archivo nuevo llamado `.env` en la raiz del proyecto.
2. Elegi que proveedor de IA vas a usar con `AI_PROVIDER` (uno de: `anthropic`, `openai`, `gemini`,
   `groq`, `openrouter`, `ollama`).
3. Rellena **solo** la API key de ese proveedor. El resto podes dejarlas vacias.

| Proveedor | Variable | Donde conseguir la key |
|---|---|---|
| Anthropic (Claude) | `ANTHROPIC_API_KEY` | https://console.anthropic.com/settings/keys |
| OpenAI | `OPENAI_API_KEY` | https://platform.openai.com/api-keys |
| Google Gemini | `GEMINI_API_KEY` | https://aistudio.google.com/apikey |
| Groq | `GROQ_API_KEY` | https://console.groq.com/keys |
| OpenRouter | `OPENROUTER_API_KEY` | https://openrouter.ai/keys |
| Ollama | *(ninguna)* | Corre local, instala [Ollama](https://ollama.com/) y bajate un modelo |

Ejemplo minimo de `.env` usando Anthropic:

```env
AI_PROVIDER=anthropic
ANTHROPIC_API_KEY=sk-ant-tu-key-aca
```

**Tip (Gemini):** si tenes varios personajes usando Gemini con cuentas/quotas distintas, podes
darle a cada uno su propia key con `GEMINI_API_KEY_<ID>` (ej. `GEMINI_API_KEY_RED`), y el que no
tenga una propia cae en la `GEMINI_API_KEY` compartida.

Nunca subas tu `.env` real a GitHub - ya esta en `.gitignore`.

## Ejecutar

Doble click en **`Stickman AI.bat`** (instala dependencias la primera vez si hace falta, y te
ofrece crear el `.env` si todavia no existe), o desde la terminal:

```bash
npm start
```

Esto compila el TypeScript, abre la app de Electron (tray icon + hotkeys) y lanza el motor visual
Shimeji sobre tu escritorio.

- `Control+Alt+P`: pausar/reanudar el loop de IA
- `Control+Shift+H`: abrir la ventana de chat con un personaje
- Pagina web en vivo: http://localhost:8787/home (mientras la app este corriendo)

## Estructura

- `main.js` / `src/` - proceso principal de Electron (loop de IA, acciones, memoria)
- `src-ts/` - percepcion de pantalla y control del proceso Java (compilado a `dist/`)
- `java-engine/shimeji-ee` - motor visual (fork de Shimeji-ee de Kilkakon)
- `android-app/` - app Android complementaria (Kotlin)
- `renderer/` - ventanas de Electron (chat, icono, webcam, StickPaint)
- `workspace/` - datos generados en tiempo de ejecucion (historial, notas, personalidades) - no se sube al repo

## Licencias

El motor `java-engine/shimeji-ee` es un fork de Shimeji-ee (Kilkakon / Shimeji-ee Group); ver
`java-engine/shimeji-ee/licence.txt` para los terminos originales.
