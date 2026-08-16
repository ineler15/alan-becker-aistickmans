package com.stickmanai.android

import android.content.Context

/** Simple SharedPreferences wrapper - which characters are enabled and their API keys/personality/memory. */
object Prefs {
    private const val FILE = "stickman_prefs"

    // Providers sharing the OpenAI-compatible tool-call shape used by GeminiClient - same
    // list as the desktop's AI_PROVIDER options minus anthropic/ollama (native Anthropic shape
    // and localhost-only Ollama don't fit this mobile client).
    val PROVIDERS = listOf("gemini", "openai", "groq", "openrouter")

    // Notes can contain any punctuation/spaces, so join them with a control character
    // the user could never type instead of something like a space or comma.
    private const val MEMORY_SEP = ""

    private fun sp(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context, characterId: String): Boolean =
        sp(context).getBoolean("enabled_$characterId", characterId == "Red")

    fun setEnabled(context: Context, characterId: String, enabled: Boolean) {
        sp(context).edit().putBoolean("enabled_$characterId", enabled).apply()
    }

    fun sharedApiKey(context: Context): String = sp(context).getString("shared_api_key", "") ?: ""

    fun setSharedApiKey(context: Context, key: String) {
        sp(context).edit().putString("shared_api_key", key).apply()
    }

    fun apiKeyFor(context: Context, characterId: String): String {
        val perCharacter = sp(context).getString("api_key_$characterId", "") ?: ""
        return perCharacter.ifBlank { sharedApiKey(context) }
    }

    fun perCharacterApiKey(context: Context, characterId: String): String =
        sp(context).getString("api_key_$characterId", "") ?: ""

    fun setApiKeyFor(context: Context, characterId: String, key: String) {
        sp(context).edit().putString("api_key_$characterId", key).apply()
    }

    fun sharedProvider(context: Context): String = sp(context).getString("shared_provider", "gemini") ?: "gemini"

    fun setSharedProvider(context: Context, provider: String) {
        sp(context).edit().putString("shared_provider", provider).apply()
    }

    fun providerFor(context: Context, characterId: String): String {
        val perCharacter = sp(context).getString("provider_$characterId", "") ?: ""
        return perCharacter.ifBlank { sharedProvider(context) }
    }

    fun perCharacterProvider(context: Context, characterId: String): String =
        sp(context).getString("provider_$characterId", "") ?: ""

    fun setProviderFor(context: Context, characterId: String, provider: String) {
        sp(context).edit().putString("provider_$characterId", provider).apply()
    }

    fun personality(context: Context, characterId: String): String =
        sp(context).getString("personality_$characterId", "") ?: ""

    fun setPersonality(context: Context, characterId: String, value: String) {
        sp(context).edit().putString("personality_$characterId", value).apply()
    }

    fun memory(context: Context, characterId: String): List<String> {
        val raw = sp(context).getString("memory_$characterId", "") ?: ""
        return if (raw.isBlank()) emptyList() else raw.split(MEMORY_SEP)
    }

    fun addMemory(context: Context, characterId: String, note: String) {
        val existing = memory(context, characterId)
        val updated = (existing + note).takeLast(30)
        sp(context).edit().putString("memory_$characterId", updated.joinToString(MEMORY_SEP)).apply()
    }

    fun enabledCharacters(context: Context): List<CharacterDef> =
        CHARACTERS.filter { isEnabled(context, it.id) }

    // "ip:puerto" of the desktop app's peer server (src/net/peerServer.js), so the tablet's
    // characters and the PC's characters can see each other - same LAN only, entered by hand
    // since there's no device discovery here.
    fun pcAddress(context: Context): String = sp(context).getString("pc_address", "") ?: ""

    fun setPcAddress(context: Context, address: String) {
        sp(context).edit().putString("pc_address", address).apply()
    }
}
