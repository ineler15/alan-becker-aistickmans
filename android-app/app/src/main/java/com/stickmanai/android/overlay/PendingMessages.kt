package com.stickmanai.android.overlay

/** Mirrors src/loop/userMessage.js - one pending chat message per character, consumed on its next AI turn. */
object PendingMessages {
    private val pending = HashMap<String, String>()

    @Synchronized
    fun set(characterId: String, text: String) {
        pending[characterId] = text
    }

    @Synchronized
    fun consume(characterId: String): String? {
        val text = pending[characterId]
        pending.remove(characterId)
        return text
    }
}
