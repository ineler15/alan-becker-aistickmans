# Stickman AI — versión iOS (SwiftUI)

App iOS del proyecto "Alan Becker AIStickmans". Versión inicial: los personajes viven **dentro de
la app** en una escena propia (iOS no permite overlays flotando sobre otras apps ni leer la pantalla
de otras apps como hace la versión Android).

## Qué hace (por ahora)

- Escena donde los stickman aparecen con su **rig procedural** (mismos JSON que PC/Android, en
  `StickmanAI/Resources/rigs/`), físicas y animaciones portadas de `CharacterState.kt`/`PoseLibrary.kt`.
- Loop de IA por personaje con los mismos **providers** (gemini / openai / groq / openrouter) vía un
  cliente OpenAI-compatible con function calling (mismo sistema de tools que `ActionsSchema.kt`).
- Memoria (historial + notas `remember`), contexto de longevidad y lore de Alan Becker.
- Baby steps del sistema de supervivencia (vida/hambre/sed).
- **Sync LAN**: la app se ve con la PC/Android a través del `peerServer` (puerto 8787/8788) — sube
  la posición de sus personajes y ve los de las otras pantallas.
- Chat "hablarle a todos" y a un personaje.

## Lo que NO está (limitaciones de iOS / no portado aún)

- Nada de overlays sobre otras apps, ni visión de pantalla, ni tocar pixels de otras apps.
- Cámara: instalada con permiso pero el visión real aún no está cableada (queda como follow-up).
- Sprite mode (PNGs) — la versión iOS arranca con rigs; los sprites quedan para PC legacy.

## Cómo construir (en una Mac)

Requisitos: macOS con Xcode 15+ (iOS 16+ de deployment target) y [XcodeGen](https://github.com/yonaskolb/XcodeGen).

```sh
brew install xcodegen
cd ios-app
xcodegen generate
open StickmanAI.xcodeproj
```

En Xcode: elegir el simulador o dispositivo, correr (Cmd+R). La primera vez que arranque hay que
configurar un **API key** compartida (o por personaje) en el tab de Configuración dentro de la app;
sin eso ninguna llamada de IA funciona (igual que en Android/PC, misma cuota por proyecto —
ver `memory/gemini_quota_per_project.md`).

Para que la **sync LAN con la PC** funcione: el iPhone/iPad y la PC tienen que estar en la misma
red, y la app usa la variable de entorno `PEER_HOST` (ej. `http://192.168.1.50:8787`) definida en el
esquema de Xcode (Edit Scheme → Run → Environment Variables) apuntando a la PC. Si no se setea, la
app funciona standalone sin verse con la PC.

## Estructura

```
StickmanAI/
  App/            # entry point + arranque
  Engine/         # rigs, físicas, poses (port de CharacterState.kt / PoseLibrary.kt / RigLayout.kt)
  Rendering/      # dibujo Core Graphics/SwiftUI del personaje sobre la escena
  AI/             # schema de tools, cliente de proveedores, loop de decisión
  Memory/         # historial, notas, personalidad, lore
  Model/          # Character, Prefs (provider/keys), Survival
  Net/            # peer sync LAN
  UI/             # escena, chat, configuración, crear/editar personaje
  Resources/rigs/ # JSON de los rigs (idénticos a android-app/assets/rigs)
```