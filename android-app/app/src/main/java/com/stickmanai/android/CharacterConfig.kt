package com.stickmanai.android

import android.content.Context

/** Mirrors src/characters.js from the desktop app - same six friends, same ids/sprite folders. */
data class CharacterDef(val id: String, val displayName: String)

val CHARACTERS_BUILTIN = listOf(
    CharacterDef("Red", "Red"),
    CharacterDef("Orange", "The Second Coming"),
    CharacterDef("Purple", "Purple"),
    CharacterDef("Green", "Green"),
    CharacterDef("Blue", "Blue"),
    CharacterDef("Yellow", "Yellow"),
    CharacterDef("AI", "AI"),
    CharacterDef("TCO", "TCO"),
    CharacterDef("TDL", "TDL"),
    CharacterDef("victim", "victim"),
)

// The full roster is builtins + whatever the user made with "crear tu propio stickman"
// (Prefs.customCharacters, persisted at runtime - see CreateCharacterActivity). Every consumer
// of the character list (Prefs.enabledCharacters, MainActivity.buildCharacterRows,
// OverlayService.setupOverlays) reads through this single function instead of CHARACTERS_BUILTIN
// directly, so a newly created character shows up everywhere without an app rebuild.
fun allCharacters(context: Context): List<CharacterDef> = CHARACTERS_BUILTIN + Prefs.customCharacters(context)
