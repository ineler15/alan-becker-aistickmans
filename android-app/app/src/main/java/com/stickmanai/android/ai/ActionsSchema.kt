package com.stickmanai.android.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Trimmed version of src/ai/actions.schema.js for the mobile port - no desktop automation
 * (Paint/Notepad/files/type_text) since there's no such surface on Android, just movement,
 * speech, emotion, the same self-personality/memory tools, and two device-control actions that
 * mirror the desktop's open_app/click: open_app (launch a URL) and tap (touch the screen via
 * TapAccessibilityService).
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
        put(
            tool(
                "open_app",
                "Abre una pagina web en el navegador del celular. Para buscar algo en Google, " +
                    "arma vos mismo la URL: https://www.google.com/search?q=tu+busqueda+aqui",
                JSONObject().put("url", prop("string", "URL completa a abrir")),
                listOf("url")
            )
        )
        put(
            tool(
                "tap",
                "Toca la pantalla del celular en una posicion (0-100% del ancho y alto), como si " +
                    "fuera un dedo. Requiere que el usuario haya habilitado el permiso de accesibilidad; " +
                    "si no, no pasa nada. Usalo con cuidado y solo cuando de verdad tenga sentido tocar algo puntual.",
                JSONObject()
                    .put("x", prop("number", "Posicion horizontal, 0-100% del ancho"))
                    .put("y", prop("number", "Posicion vertical, 0-100% del alto")),
                listOf("x", "y")
            )
        )
    }
}
