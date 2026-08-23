package com.stickmanai.android

import android.content.Context
import org.json.JSONArray

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
        allCharacters(context).filter { isEnabled(context, it.id) }

    // Characters created at runtime via "crear tu propio stickman" - stored as a JSON array
    // since CHARACTERS_BUILTIN is a compile-time list and can't be appended to directly.
    fun customCharacters(context: Context): List<CharacterDef> {
        val raw = sp(context).getString("custom_characters", "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            CharacterDef(obj.getString("id"), obj.getString("displayName"))
        }
    }

    // Sanitizes displayName into an id (letters/digits only, deduped against every existing
    // builtin + custom id) and appends it - mirrors src/customCharacters.js's sanitizeId on PC.
    // headModel ("normal"/"hollow"), hasFace and gender ("masculino"/"femenino"/"otro") are stored
    // alongside so customMeta() below can find them later.
    fun addCustomCharacter(
        context: Context,
        displayName: String,
        headModel: String,
        hasFace: Boolean,
        gender: String,
    ): CharacterDef {
        val base = displayName.filter { it.isLetterOrDigit() }.ifBlank { "Stickman" }
        val existingIds = allCharacters(context).map { it.id }.toSet()
        var id = base
        var suffix = 2
        while (id in existingIds) {
            id = "${base}_$suffix"
            suffix += 1
        }
        val def = CharacterDef(id, displayName.ifBlank { "Stickman" })

        val raw = sp(context).getString("custom_characters", "[]") ?: "[]"
        val array = JSONArray(raw)
        val entry = org.json.JSONObject()
        entry.put("id", def.id)
        entry.put("displayName", def.displayName)
        entry.put("headModel", headModel)
        entry.put("hasFace", hasFace)
        entry.put("gender", gender)
        array.put(entry)
        sp(context).edit().putString("custom_characters", array.toString()).apply()
        return def
    }

    /** poseProfile is PoseLibrary's PROFILE_BY_ID key (built-ins don't need one - the caller keeps using its own id). */
    data class CustomCharacterMeta(val poseProfile: String, val hasFace: Boolean, val gender: String)

    // PoseLibrary's PROFILE_BY_ID only knows built-in ids - a custom character's rig is always an
    // exact clone of Red's (headModel "normal") or TCO's (headModel "hollow") rig, so pointing
    // PoseLibrary at that id instead of the custom one is enough to fully animate it. Null for a
    // built-in character (or an unknown id) - callers should keep using the character's own
    // id/no face/no accessory in that case. Consolidates what used to be a separate
    // poseProfileFor() - one JSON scan instead of one per field.
    fun customMeta(context: Context, characterId: String): CustomCharacterMeta? {
        val raw = sp(context).getString("custom_characters", "[]") ?: "[]"
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (obj.getString("id") == characterId) {
                val poseProfile = if (obj.optString("headModel") == "hollow") "TCO" else "Red"
                return CustomCharacterMeta(
                    poseProfile = poseProfile,
                    hasFace = obj.optBoolean("hasFace", false),
                    gender = obj.optString("gender", "otro"),
                )
            }
        }
        return null
    }

    // Off by default - letting the AI actually tap the screen (beyond the accessibility
    // permission grant itself) is a meaningfully bigger deal than everything else it can do, so
    // it needs an explicit opt-in. Mirrors the desktop's allowMouseControl (pcSettings.js).
    fun allowScreenControl(context: Context): Boolean = sp(context).getBoolean("allow_screen_control", false)

    fun setAllowScreenControl(context: Context, allowed: Boolean) {
        sp(context).edit().putBoolean("allow_screen_control", allowed).apply()
    }

    // "ip:puerto" of the desktop app's peer server (src/net/peerServer.js), so the tablet's
    // characters and the PC's characters can see each other - same LAN only, entered by hand
    // since there's no device discovery here.
    fun pcAddress(context: Context): String = sp(context).getString("pc_address", "") ?: ""

    fun setPcAddress(context: Context, address: String) {
        sp(context).edit().putString("pc_address", address).apply()
    }
}
