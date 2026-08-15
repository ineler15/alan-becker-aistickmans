package com.stickmanai.android

/** Mirrors src/characters.js from the desktop app - same six friends, same ids/sprite folders. */
data class CharacterDef(val id: String, val displayName: String)

val CHARACTERS = listOf(
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
