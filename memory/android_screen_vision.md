---
name: android-screen-vision
description: "Android characters can now \"see\" the phone screen via the existing accessibility service, sent to Gemini alongside the camera frame every AI tick"
metadata: 
  node_type: memory
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-16T19:30:17.842Z
---

Added screen vision to the Android app on 2026-08-16, reusing `TapAccessibilityService` (previously gesture-only) instead of requesting a new MediaProjection permission:

- `TapAccessibilityService.captureScreenshotBase64()` (companion, suspend) — uses `AccessibilityService.takeScreenshot()` (API 30+, returns null below that or if the service isn't enabled), wraps the `HardwareBuffer` into a software `Bitmap`, downsizes to max 720px side / JPEG quality 65 (screen text needs more resolution than CameraCapture's 480/60 face-photo settings).
- `OverlayService.startAiLoop()` captures one shared screenshot per tick round (same "one shared frame, reused by every character" pattern already used for the camera), passes it into `GeminiClient.decide(screenBase64 = ...)`.
- `GeminiClient.decide()` now sends the camera frame AND/OR the screenshot as separate `image_url` parts in the same user message (Gemini's OpenAI-compatible endpoint accepts multiple images per message).
- System prompt tells the character it may get a screenshot and to comment on it like "looking over your shoulder" but explicitly NOT to read/repeat private stuff (messages, passwords, personal data) if visible.

**Why:** user explicitly asked for this ("que puedan ver la pantalla en cel"), after I'd flagged that the original code had a deliberate comment saying screen-reading was intentionally left out for privacy. Went with the accessibility-service route (not MediaProjection) since it reuses the permission already granted for `tap` instead of adding a second consent flow.

**How to apply:** This is a real, continuous privacy tradeoff, not a one-off action - every ~12s tick (whatever `TICK_INTERVAL_MS`/`Prefs` tick interval is set to) now ships a screenshot of *whatever app is in front* to Gemini's API as long as the accessibility permission is on, not just when something interesting is happening. `tap_accessibility_service.xml` keeps `canRetrieveWindowContent="false"` - this only ever sees rendered pixels, never the UI text/node hierarchy of other apps. The user-facing permission description string (`tap_accessibility_description` in strings.xml) was updated to accurately disclose this - if that capability model changes again, that string needs to stay truthful since it's what the user reads when granting the permission.

**Ship-it bugs found and fixed on 2026-08-16 (real device, Lenovo tablet, SDK 33):**
1. `AccessibilityService.takeScreenshot()` throws `SecurityException("Services don't have the capability of taking the screenshot")` **synchronously at the call site**, not via the async failure callback - uncaught, this crashed the whole app every ~6s tick (system showed "Stickman AI continúa fallando" repeatedly). Fixed by adding `android:canTakeScreenshot="true"` to `tap_accessibility_service.xml` (which actually resolved it on this device) AND wrapping the `takeScreenshot()` call itself in try/catch as a safety net for devices/OS versions where the capability still isn't granted.
2. Reinstalling the APK (or even just toggling the accessibility switch) can leave the service listed as "enabled" in Settings but not actually bound - `dumpsys accessibility` showed it under `Crashed services`, and `TapAccessibilityService.instance` stayed null until an explicit `adb shell am force-stop` cleared the crashed flag and the app was reopened. If screen vision (or tap) silently does nothing, check `adb shell dumpsys accessibility | grep -i "Bound services\|Crashed services"` before assuming it's a code bug.
3. Sending the screenshot alongside the camera frame made Gemini responses slow enough to blow through the client's old 20s `readTimeout`, throwing `SocketTimeoutException` (also silently swallowed into a fallback, see [[android_chat_and_movement]]) - bumped to 45s in `GeminiClient.kt`.
Diagnostic logging (`Log.i`/`Log.w` under tag "StickmanAI") was added to `captureScreenshotBase64()` to make this debuggable next time - `adb logcat -s StickmanAI` (or the broader `-t N | grep -E " StickmanAI: "` since `-i "StickmanAI"` alone also matches the package name `com.stickmanai.android` in every system log line and drowns out the real signal).
