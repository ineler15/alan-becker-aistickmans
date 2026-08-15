package com.stickmanai.android.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Trimmed version of src/ai/actions.schema.js for the mobile port - no desktop automation
 * (Paint/Notepad/files/click/type_text) since there's no such surface on Android, just
 * movement, speech, emotion and the same self-personality/memory tools.
 */
object ActionsSchema {

    private fun tool(name: String, description: String, properties: JSONObject, required: List<String> = emptyList()): JSONObject {
        val params = JSONObject()
            .put("type", "object")
            .put("properties", properties)
        if (required.isNotEmpty()) params.put("required", JSONArray(required))
        val fn = JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", params)
        return JSONObject().put("type", "function").put("function", fn)
    }

    private fun prop(type: String, description: String, enumValues: List<String>? = null): JSONObject {
        val p = JSONObject().put("type", type).put("description", description)
        if (enumValues != null) p.put("enum", JSONArray(enumValues))
        return p
    }

    fun tools(): JSONArray = JSONArray().apply {
        put(
            tool(
                "walk_to",
                "Camina con proposito hacia una posicion x de la pantalla (0-100, porcentaje del ancho).",
                JSONObject()
                    .put("x", prop("number", "Posicion horizontal destino, 0-100% del ancho de pantalla"))
                    .put("run", prop("boolean", "true para correr en vez de caminar")),
                listOf("x")
            )
        )
        put(
            tool(
                "move_random",
                "Camina a un punto al azar de la pantalla. Usar solo si no hay nada puntual a donde ir.",
                JSONObject().put("run", prop("boolean", "true para correr en vez de caminar"))
            )
        )
        put(
            tool(
                "set_animation",
                "Expresa una emocion con el cuerpo (no tenes cara).",
                JSONObject().put(
                    "state",
                    prop("string", "Estado emocional", listOf("idle", "happy", "trip", "sad", "scared"))
                ),
                listOf("state")
            )
        )
        put(
            tool(
                "say",
                "Comenta algo en voz alta en un globo de texto. Una frase corta y casual.",
                JSONObject().put("text", prop("string", "Lo que decis")),
                listOf("text")
            )
        )
        put(
            tool(
                "define_personality",
                "Definite tu propia personalidad la primera vez, o cambiala si sentis que cambiaste.",
                JSONObject().put("description", prop("string", "Tu personalidad en pocas palabras, casual")),
                listOf("description")
            )
        )
        put(
            tool(
                "remember",
                "Anota algo que valga la pena recordar despues (algo que el usuario conto, algo importante).",
                JSONObject().put("note", prop("string", "La nota a recordar")),
                listOf("note")
            )
        )
        put(tool("wait", "No haces nada este turno. Reservalo para turnos excepcionales.", JSONObject()))
    }
}
