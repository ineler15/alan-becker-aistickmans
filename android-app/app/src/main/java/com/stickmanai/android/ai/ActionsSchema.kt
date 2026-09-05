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

    // Same vocabulary as overlay/CharacterState.kt's EYE_STYLES/MOUTH_STYLES - duplicated since
    // that one is the runtime state and this is just schema description text.
    private val EYE_STYLES = listOf("normal", "wide", "angry", "heart")
    private val MOUTH_STYLES = listOf("neutral", "smile", "frown", "open", "angry")

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

    // Lets eyes/mouth ride along with WHATEVER action a character picks this turn instead of
    // needing a dedicated set_emotion turn just for the face - only one tool call happens per
    // decision, so without this a character with something to say AND a reaction to show would
    // have to pick one and wait a full extra turn for the other.
    private fun withFaceParams(properties: JSONObject): JSONObject {
        properties.put("eyes", prop("string", "Opcional - si tenes cara propia, actualiza tus ojos en este mismo turno sin gastar una accion aparte", EYE_STYLES))
        properties.put("mouth", prop("string", "Opcional - si tenes cara propia, actualiza tu boca en este mismo turno sin gastar una accion aparte", MOUTH_STYLES))
        return properties
    }

    fun tools(): JSONArray = JSONArray().apply {
        put(
            tool(
                "walk_to",
                "Camina con proposito hacia una posicion x de la pantalla (0-100, porcentaje del ancho).",
                withFaceParams(
                    JSONObject()
                        .put("x", prop("number", "Posicion horizontal destino, 0-100% del ancho de pantalla"))
                        .put("run", prop("boolean", "true para correr en vez de caminar"))
                ),
                listOf("x")
            )
        )
        put(
            tool(
                "set_animation",
                "Cambia la pose de tu cuerpo. tired = te tiras cansado, sleep = te acostas a dormir " +
                    "(dejas de recibir turnos hasta que te despierten o pase un rato). Esto es solo el " +
                    "cuerpo - si tenes cara propia, sumale eyes/mouth a esta misma llamada para la " +
                    "expresion facial, no hace falta un turno aparte con set_emotion.",
                withFaceParams(
                    JSONObject().put(
                        "state",
                        prop("string", "Estado emocional", listOf("idle", "happy", "trip", "sad", "scared", "sit", "angry", "tired", "sleep"))
                    )
                ),
                listOf("state")
            )
        )
        put(
            tool(
                "set_emotion",
                "Cambia SOLO la expresion de tu cara (ojos y boca), sin hacer ninguna otra cosa este " +
                    "turno - independiente de la pose del cuerpo (set_animation/set_custom_animation). " +
                    "Usala cuando lo unico que queres hacer es cambiar la cara; si ademas queres decir " +
                    "algo, caminar, etc. en el mismo turno, mejor sumale eyes/mouth a ESA accion (todas " +
                    "aceptan esos dos parametros opcionales) en vez de gastar un turno aparte aca. Solo " +
                    "se nota si tenes cara propia (se eligio al crearte) - si no, no hace nada visible.",
                JSONObject()
                    .put("eyes", prop("string", "Opcional", EYE_STYLES))
                    .put("mouth", prop("string", "Opcional", MOUTH_STYLES))
            )
        )
        put(
            tool(
                "say",
                "Comenta algo en voz alta en un globo de texto. Una frase corta y casual.",
                withFaceParams(JSONObject().put("text", prop("string", "Lo que decis"))),
                listOf("text")
            )
        )
        put(
            tool(
                "define_personality",
                "Definite tu propia personalidad la primera vez, o cambiala si sentis que cambiaste.",
                withFaceParams(JSONObject().put("description", prop("string", "Tu personalidad en pocas palabras, casual"))),
                listOf("description")
            )
        )
        put(
            tool(
                "remember",
                "Anota algo que valga la pena recordar despues (algo que el usuario conto, algo importante).",
                withFaceParams(JSONObject().put("note", prop("string", "La nota a recordar"))),
                listOf("note")
            )
        )
        put(
            tool(
                "set_context",
                "Define o actualiza, en tus propias palabras, un contexto propio extra que quieras que " +
                    "se siga aplicando en el futuro. Esto es DISTINTO del contexto automatico que ya " +
                    "recibis cada turno (historial, tus peers, tu posicion, etc.) - aca va lo que VOS " +
                    "queres que se recuerde sobre ti o tu situacion mas alla de eso: tus planes, tu " +
                    "historia, como ves las cosas, relaciones entre hechos. Se guarda y lo vas a seguir " +
                    "viendo en turnos futuros, incluso despues de reiniciar.",
                withFaceParams(JSONObject().put("context", prop("string", "Tu contexto extra, en tus palabras"))),
                listOf("context")
            )
        )
        put(tool("wait", "No haces nada este turno. Reservalo para turnos excepcionales.", withFaceParams(JSONObject())))
        put(
            tool(
                "open_app",
                "Abre una pagina web en el navegador del celular. Para buscar algo en Google, " +
                    "arma vos mismo la URL: https://www.google.com/search?q=tu+busqueda+aqui",
                withFaceParams(JSONObject().put("url", prop("string", "URL completa a abrir"))),
                listOf("url")
            )
        )
        put(
            tool(
                "set_custom_animation",
                "Armate tu propia mini-animacion (unica, no una de las poses fijas) moviendo tu cuerpo " +
                    "cuadro por cuadro. Cada angulo es un DELTA en grados desde tu postura parada normal " +
                    "(0 = se queda como estaba parado, no un angulo absoluto) - asi los mismos numeros dan " +
                    "mas o menos el mismo gesto sin importar que personaje seas. La parte que no " +
                    "menciones se queda como estaba en el cuadro anterior. Ejemplos calibrados de " +
                    "referencia (copialos, combinalos, o inventa los tuyos con valores parecidos si no " +
                    "hay uno para lo que queres): sentado = {leg1:-57, leg1Shin:65, leg2:55, leg2Shin:-63, " +
                    "torso:6}; agachado/en cuclillas = {leg1Shin:22, leg2Shin:22, arm1:-22, arm2:22}; " +
                    "brazos abiertos cayendo = {torso:-40, arm1:-60, arm2:60, leg1:30, leg2:-30}; " +
                    "inclinado adelante con brazos cruzando el cuerpo = {torso:-25, arm1:40, arm2:-40}; " +
                    "encorvado/cansado = {torso:-30, arm1:-20, arm2:20, leg1:15, leg1Shin:40, leg2:-15, " +
                    "leg2Shin:40}. Si tenes cara propia, cada cuadro tambien puede traer su propio " +
                    "eyes/mouth - si un cuadro no los trae, se mantienen los ultimos que se usaron. Usa " +
                    "esto seguido, no solo de vez en cuando: es tu forma de expresarte de verdad cuando " +
                    "ninguna pose fija encaja. Solo funciona para vos si tu cuerpo es de los que soportan " +
                    "pose completa (no todos la tienen todavia) - si no pasa nada, no insistas turno tras " +
                    "turno.",
                JSONObject().put(
                    "keyframes",
                    JSONObject()
                        .put("type", "array")
                        .put("description", "Lista ordenada de cuadros (maximo 12), se reproducen en secuencia")
                        .put(
                            "items",
                            JSONObject()
                                .put("type", "object")
                                .put(
                                    "properties",
                                    JSONObject().apply {
                                        for (part in listOf("torso", "leg1", "leg1Shin", "leg2", "leg2Shin", "arm1", "arm2")) {
                                            put(part, prop("number", "Delta en grados desde tu postura normal para $part (opcional, ver ejemplos calibrados arriba)"))
                                        }
                                        put("eyes", prop("string", "Ojos para este cuadro (opcional)", EYE_STYLES))
                                        put("mouth", prop("string", "Boca para este cuadro (opcional)", MOUTH_STYLES))
                                        put("holdMs", prop("number", "Cuanto dura este cuadro en milisegundos (100-3000, default 400)"))
                                    }
                                )
                        )
                ),
                listOf("keyframes")
            )
        )
        put(
            tool(
                "tap",
                "Toca la pantalla del celular en una posicion (0-100% del ancho y alto), como si " +
                    "fuera un dedo. Requiere que el usuario haya habilitado el permiso de accesibilidad " +
                    "Y activado el control de pantalla en la pantalla principal de la app; si no, no pasa " +
                    "nada, no insistas turno tras turno. Usalo con cuidado y solo cuando de verdad tenga " +
                    "sentido tocar algo puntual.",
                withFaceParams(
                    JSONObject()
                        .put("x", prop("number", "Posicion horizontal, 0-100% del ancho"))
                        .put("y", prop("number", "Posicion vertical, 0-100% del alto"))
                ),
                listOf("x", "y")
            )
        )
    }
}
