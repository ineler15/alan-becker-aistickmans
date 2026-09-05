---
name: windows-dev-workflow
description: "How to build/restart this project locally on the user's Windows machine"
metadata: 
  node_type: memory
  type: project
  originSessionId: 799e5adb-1d49-411a-8eb8-27c00f336635
  modified: 2026-08-23T22:21:20.488Z
---

**Restarting after an `.env` change:** `src/config.js` loads `.env` via `dotenv` once at process
start, so editing `.env` (e.g. a new Gemini key) has no effect on an already-running instance.
Full restart sequence used this session: `taskkill //IM electron.exe //F` and
`taskkill //IM javaw.exe //F` (there are usually several `electron.exe` processes per single
logical launch - kill all of them), then relaunch with `npm start` (builds TS then runs Electron)
or the `Stickman AI.bat` launcher. Prefer PowerShell over the Bash tool's `cmd.exe /c "...bat"`
for running the `.bat` launcher - a bare `cmd.exe /c "Stickman AI.bat"` from the Bash tool silently
no-op'd (likely a quoting/argument-parsing quirk with the space in the filename); `& ".\Stickman AI.bat"`
via the PowerShell tool worked reliably.

**Android build:** requires a JDK 11+ (project's Gradle/AGP 8.7.2 needs it), but the machine's
default/system Java is the JRE 8 used for the Shimeji Java engine
(`C:\Program Files (x86)\Java\jre1.8.0_501`). Android Studio's bundled JBR at
`C:\Program Files\Android\Android Studio\jbr` (JDK 21, NOT `C:\Program Files\Android Studio\jbr` -
that path doesn't exist, Android Studio installs under the `Android\` subfolder on this machine)
works - set `JAVA_HOME` to that path for `./gradlew.bat assembleDebug` (PowerShell:
`$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"`). adb lives at
`C:\Users\jh4ck\AppData\Local\Android\Sdk\platform-tools\adb.exe` (not on PATH) - `adb devices`
then `adb install -r <path-to-apk>` installs straight over USB once the tablet/phone is plugged in
and authorized. In Git Bash, `adb shell`/`push`/`pull` paths starting with `/sdcard/...` get
mangled by MSYS path conversion (e.g. `/sdcard/x.png` becomes `C:/Program Files/Git/sdcard/x.png`)
- prefix the command with `export MSYS_NO_PATHCONV=1` first.

**Packaging gotcha:** `config.js` resolves its default workspace dir relative to `__dirname`,
which inside a packaged build sits in `app.asar` (a single read-only file, not a real directory) -
`mkdirSync` there throws `ENOTDIR` on first launch. Fixed by defaulting to
`app.getPath('userData')` when `app.isPackaged`. Rebuilding the installer (`npm run dist`) does
NOT update an already-installed copy in `C:\Program Files\...` - that only happens by actually
running the new installer (silently with `/S` for a quick non-interactive update, or normally).
Forgetting this step after a code fix means the old installed copy keeps failing with the old bug
even though the freshly-built installer/win-unpacked folder is already fixed.

**Don't `npm start` for a smoke test if the real installed app might already be running.**
Electron's `app.getPath('userData')` keys off package.json's `"name"` (`"stickman-ia"`), so a dev
run via `npm start` points at the EXACT SAME workspace files as the installed app
(`C:\Users\jh4ck\AppData\Roaming\stickman-ia\workspace\`) - not a separate sandbox. Confirmed
2026-08-23: a 12-second `npm start` smoke test while the user's real app was already running (its
`peerServer` holding port 8787 gave it away via an `EADDRINUSE` log) left `history-Red.json`
truncated to just 2 entries afterward - two processes read-modify-wrote the same JSON history file
concurrently and the dev instance's near-empty in-memory copy won the race. No data corruption
beyond that (personality/memory/settings files untouched), but real chat history was lost. **How to
apply:** before launching the app from the repo to verify a change actually runs, check whether the
installed app is already open (ask the user, or check for a lock/port already in use) - prefer
`node --check` + code review for syntax/logic verification, and only do a live launch when the
installed app is confirmed closed.

**Java 8 JDK** (Eclipse Adoptium `jdk-8.0.502.7-hotspot`, under
`C:\Program Files\Eclipse Adoptium`) is present too, relevant if ever rebuilding
`java-engine/shimeji-ee` itself (its own Ant build, separate from the Android Gradle project) -
note the actually-running Shimeji jar is `C:\Users\jh4ck\AppData\Local\AlanBeckersStickfigures\AlansStickfigures.jar`,
a separate deployed copy, not built directly from `java-engine/shimeji-ee` in this repo.
