package com.stickmanai.android.overlay

import com.stickmanai.android.CHARACTERS

/** Mirrors src/loop/userMessage.js - one pending chat message per character, consumed on its next AI turn. */
object PendingMessages {
    private val pending = HashMap<String, String>()

    @Synchronized
    fun set(characterId: String, text: String) {
        pending[characterId] = text
    }

    /** Group chat: same message reaches every character's own context this round. */
    @Synchronized
    fun setAll(text: String) {
        for (character in CHARACTERS) pending[character.id] = text
    }

    @Synchronized
    fun consume(characterId: String): String? {
        val text = pending[characterId]
        pending.remove(characterId)
        return text
    }
}
