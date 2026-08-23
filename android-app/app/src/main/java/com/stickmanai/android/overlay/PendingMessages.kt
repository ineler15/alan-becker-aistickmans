package com.stickmanai.android.overlay

import android.content.Context
import com.stickmanai.android.allCharacters

/** Mirrors src/loop/userMessage.js - one pending chat message per character, consumed on its next AI turn. */
object PendingMessages {
    private val pending = HashMap<String, String>()

    @Synchronized
    fun set(characterId: String, text: String) {
        pending[characterId] = text
    }

    /** Group chat: same message reaches every character's own context this round. */
    @Synchronized
    fun setAll(context: Context, text: String) {
        for (character in allCharacters(context)) pending[character.id] = text
    }

    @Synchronized
    fun consume(characterId: String): String? {
        val text = pending[characterId]
        pending.remove(characterId)
        return text
    }
}
