import Foundation

// Schema de tools (function calling) espejo de ActionsSchema.kt en Android / actions.schema.js en
// PC, recortado a lo que la escena iOS puede ejecutar: movimiento, habla, emocion, las herramientas
// de memoria/personalidad y el sistema de supervivencia. NO incluye lo que iOS no tiene: open_app/
// tap (Android), ni nada de Paint/Notepad/mouse/ride_mouse (solo existian en el desktop).
// A diferencia de Android, se agrega "wash" como herramienta de supervivencia en la cocina (el
// ciclo de vida/hambre/sed portado no tenia una accion equivalente y el contrato del app lo pide).

enum ActionsSchema {

    // Mismo vocabulario que CharacterState (EYES_STYLES/MOUTH_STYLES) - aca es solo texto de schema.
    static let eyeStyles = ["normal", "wide", "angry", "heart"]
    static let mouthStyles = ["neutral", "smile", "frown", "open", "angry"]

    // Let eyes/mouth viajar con CUALQUIER accion que elija el personaje este turno en vez de
    // necesitar un turno dedicado de set_emotion solo para la cara - por turno corre una sola
    // tool call, asi que sin esto un personaje que quiere decir algo Y reaccionar tendria que
    // elegir uno y esperar un turno entero por el otro.
    private static let eyesParamDesc = "Opcional - si tenes cara propia, actualiza tus ojos en este mismo turno sin gastar una accion aparte"
    private static let mouthParamDesc = "Opcional - si tenes cara propia, actualiza tu boca en este mismo turno sin gastar una accion aparte"

    // --- helpers para armar el JSON de cada tool -------------------------------------------

    private static func prop(_ type: String, _ description: String, enumValues: [String]? = nil) -> [String: Any] {
        var p: [String: Any] = ["type": type, "description": description]
        if let enumValues = enumValues { p["enum"] = enumValues }
        return p
    }

    private static func withFaceParams(_ properties: [String: Any]) -> [String: Any] {
        var p = properties
        p["eyes"] = prop("string", eyesParamDesc, enumValues: eyeStyles)
        p["mouth"] = prop("string", mouthParamDesc, enumValues: mouthStyles)
        return p
    }

    private static func tool(_ name: String, _ description: String, properties: [String: Any], required: [String] = []) -> [String: Any] {
        var params: [String: Any] = ["type": "object", "properties": properties]
        if !required.isEmpty { params["required"] = required }
        return ["type": "function", "function": ["name": name, "description": description, "parameters": params]]
    }

    // --- lista completa --------------------------------------------------------------------

    static func tools() -> [[String: Any]] {
        var all: [[String: Any]] = []

        all.append(tool(
            "walk_to",
            "Camina con proposito hacia una posicion x de la escena (0-100, porcentaje del ancho).",
            properties: withFaceParams([
                "x": prop("number", "Posicion horizontal destino, 0-100% del ancho de la escena"),
                "run": prop("boolean", "true para correr en vez de caminar"),
            ]),
            required: ["x"]
        ))

        all.append(tool(
            "set_animation",
            "Cambia la pose de tu cuerpo. jump = saltas, tired = te tiras cansado, sleep = te acostas a dormir " +
                "(dejas de recibir turnos hasta que te despierten o pase un rato). Esto es solo el " +
                "cuerpo - si tenes cara propia, sumale eyes/mouth a esta misma llamada para la " +
                "expresion facial, no hace falta un turno aparte con set_emotion.",
            properties: withFaceParams([
                "state": prop("string", "Estado emocional", enumValues: ["idle", "happy", "trip", "sad", "scared", "sit", "angry", "tired", "sleep", "jump"])
            ]),
            required: ["state"]
        ))

        all.append(tool(
            "set_emotion",
            "Cambia SOLO la expresion de tu cara (ojos y boca), sin hacer ninguna otra cosa este " +
                "turno - independiente de la pose del cuerpo (set_animation/set_custom_animation). " +
                "Usala cuando lo unico que queres hacer es cambiar la cara; si ademas queres decir " +
                "algo, caminar, etc. en el mismo turno, mejor sumale eyes/mouth a ESA accion (todas " +
                "aceptan esos dos parametros opcionales) en vez de gastar un turno aparte aca. Solo " +
                "se nota si tenes cara propia (se eligio al crearte) - si no, no hace nada visible.",
            properties: [
                "eyes": prop("string", "Opcional", enumValues: eyeStyles),
                "mouth": prop("string", "Opcional", enumValues: mouthStyles),
            ]
        ))

        all.append(tool(
            "say",
            "Comenta algo en voz alta en un globo de texto. Una frase corta y casual.",
            properties: withFaceParams(["text": prop("string", "Lo que decis")]),
            required: ["text"]
        ))

        all.append(tool(
            "define_personality",
            "Definite tu propia personalidad la primera vez, o cambiala si sentis que cambiaste.",
            properties: withFaceParams(["description": prop("string", "Tu personalidad en pocas palabras, casual")]),
            required: ["description"]
        ))

        all.append(tool(
            "remember",
            "Anota algo que valga la pena recordar despues (algo que el usuario conto, algo importante).",
            properties: withFaceParams(["note": prop("string", "La nota a recordar")]),
            required: ["note"]
        ))

        all.append(tool(
            "set_context",
            "Define o actualiza, en tus propias palabras, un contexto propio extra que quieras que " +
                "se siga aplicando en el futuro. Esto es DISTINTO del contexto automatico que ya " +
                "recibis cada turno (historial, tus peers, tu posicion, etc.) - aca va lo que VOS " +
                "queres que se recuerde sobre ti o tu situacion mas alla de eso: tus planes, tu " +
                "historia, como ves las cosas, relaciones entre hechos. Se guarda y lo vas a seguir " +
                "viendo en turnos futuros, incluso despues de reiniciar.",
            properties: withFaceParams(["context": prop("string", "Tu contexto extra, en tus palabras")]),
            required: ["context"]
        ))

        all.append(tool("wait", "No haces nada este turno. Reservalo para turnos excepcionales.", withFaceParams([:])))

        all.append(tool(
            "fight",
            "Pelea contra otro stickman que este cerca tuyo (a menos de ~100px en pantalla, unos ~25% " +
                "del ancho de la escena). Le pegas un golpe " +
                "directo que le baja la vida (hp), y si le llegas a bajar toda la vida se muere y hay " +
                "que revivirlo desde el chat. Solo funciona con el sistema de vida activado y solo si " +
                "estas al lado del otro - si tu objetivo esta lejos, primero acercate con walk_to (en " +
                "tu contexto ves la posicion de tus peers). No la uses para atacar por atacar: pelea " +
                "solo si tiene sentido para tu personaje.",
            properties: withFaceParams([
                "target": prop("string", "id de otro personaje, ej: Red, Blue, TCO, victim, Orange"),
                "strength": prop("number", "opcional, 5-40, default 12"),
            ]),
            required: ["target"]
        ))

        all.append(tool(
            "eat",
            "Come algo en la cocina y recupera hambre (~45%). SOLO funciona estando cerca de la " +
                "cocina (a menos de ~150px en pantalla, unos ~37% del ancho de la escena) - si estas lejos, primero camina hasta ella con walk_to " +
                "(su posicion viene en tu contexto). Un personaje con el hambre en 0 empieza a perder " +
                "vida, asi que es importante volver a la cocina de vez en cuando. Requiere el sistema " +
                "de vida activado.",
            properties: withFaceParams([:])
        ))

        all.append(tool(
            "drink",
            "Toma agua en la cocina y recupera sed (~45%). Igual que eat: SOLO funciona estando " +
                "cerca de la cocina - si estas lejos, camina con walk_to primero. La sed vacia tambien " +
                "hace perder vida, asi que no la descuides. Requiere el sistema de vida activado.",
            properties: withFaceParams([:])
        ))

        all.append(tool(
            "wash",
            "Lavate las manos o la cara en la cocina con agua limpia y recuperas algo de sed (~45%). " +
                "Igual que drink: SOLO funciona estando cerca de la cocina - si estas lejos, camina con " +
                "walk_to primero. Requiere el sistema de vida activado.",
            properties: withFaceParams([:])
        ))

        let partKeys = ["torso", "leg1", "leg1Shin", "leg2", "leg2Shin", "arm1", "arm2"]
        var kfProps: [String: Any] = [:]
        for part in partKeys {
            kfProps[part] = prop("number", "Delta en grados desde tu postura normal para \(part) (opcional, ver ejemplos calibrados arriba)")
        }
        kfProps["eyes"] = prop("string", "Ojos para este cuadro (opcional)", enumValues: eyeStyles)
        kfProps["mouth"] = prop("string", "Boca para este cuadro (opcional)", enumValues: mouthStyles)
        kfProps["holdMs"] = prop("number", "Cuanto dura este cuadro en milisegundos (100-3000, default 400)")

        all.append(tool(
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
            properties: [
                "keyframes": [
                    "type": "array",
                    "description": "Lista ordenada de cuadros (maximo 12), se reproducen en secuencia",
                    "items": ["type": "object", "properties": kfProps],
                ]
            ],
            required: ["keyframes"]
        ))

        return all
    }
}