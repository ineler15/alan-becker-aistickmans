---
name: android-chat-and-movement
description: Chat with characters now goes through a floating top-right button instead of tapping a character (which used to switch apps); a failed AI decision no longer triggers a random walk
metadata: 
  node_type: memory
  type: project
  originSessionId: 35f34386-ef2f-41b6-ab2c-7ccd62d52c70
  modified: 2026-08-16T19:30:32.937Z
---

Two related fixes on 2026-08-16, found by the user actually using the app on their tablet:

1. **Chat no longer switches apps.** Tapping a character used to launch `ChatActivity` (a real `AppCompatActivity`, via `OverlayService.openChat()`), which brought the Stickman app to the foreground and interrupted whatever app the user was in - and its EditText grabbing the keyboard is likely what was pushing the character overlay up over the keyboard too. Replaced with `ChatButtonOverlay` (new file, `overlay/ChatButtonOverlay.kt`): a small "💬" button fixed at the top-right corner (`Gravity.TOP or END`) plus a text-entry panel, both `TYPE_APPLICATION_OVERLAY` windows that stay in the overlay layer the whole time - tapping the button never leaves the app in front. The panel is `FLAG_NOT_FOCUSABLE` by default (like the character/speech overlays) and only has that flag cleared while open, which is what lets its `EditText` actually receive keyboard input without permanently stealing focus - the standard trick floating chat-head apps use. Sends via `PendingMessages.setAll()`, i.e. to everyone (same recipient as the existing "hablarle a todos" group chat) since there's one shared button now instead of one per character. `CharacterOverlay`'s tap callback is now a no-op (`{ }` in `OverlayService.setupOverlays()`) - the tap-vs-drag detection logic itself is left in place in case it's needed again, just disconnected from opening chat.

2. **No more random-walk fallback on error.** `OverlayService.tickCharacterAi()`'s catch block used to call `overlay.state.randomTarget()` on any `decide()` failure. During the screen-vision crash loop (see [[android_screen_vision]]) that fired every ~6s, so the character looked like it was "going crazy" (user's words) - constantly re-randomizing its walk target instead of just standing still. Removed; on error the character now just stays wherever it was (the error still gets logged and added to history for the next successful turn's context).

**Why:** both surfaced from the user directly using the app while I was debugging the screen-vision crash - not requested features so much as "wait, why is it doing that" reports.

**How to apply:** If chat needs to go back to being per-character rather than "to everyone", `ChatButtonOverlay` would need a character picker (there isn't one now - it hardcodes the group-chat recipient). `ChatActivity`/`GROUP_CHAT_ID` still exist and are still used by MainActivity's own in-app "hablarle a todos" button - only the overlay tap-to-chat path was changed, not that one.
