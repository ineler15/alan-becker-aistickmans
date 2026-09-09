import Foundation

// Paquete de contexto que se le manda al modelo en cada turno; el dict que arma contextJSON() es
// casi el mismo que arma el body final del prompt en la funcion buildSystemPrompt de geminiProvider.js
// (PC), con la diferencia de que el bloque de personalidad va en el systemPrompt y no aca. El loop
// llena los campos del struct por personaje y aca solo se serializa.
struct ContextBundle {
    var characterId: String = ""
    var displayName: String = ""
    var background: String = ""
    var genderLine: String = ""
    var partnerLine: String = ""
    var selfPersonality: String = ""
    var extraContext: String = ""          // set_context (modo "semilla") o contexto generado (modo "ia")
    var recentHistory: [[String: Any]] = []
    var memory: [String] = []              // usa las N notas mas recientes, texto plano
    var status: [String: CGFloat] = [:]    // del World: xPercent/floorYPercent/grounded/etc.
    var peers: [[String: Any]] = []
    var userMessage: String? = nil
    var forceSay: Bool = false
    var survival: [String: Any]? = nil     // hp/hunger/thirst/dead/saved, nil = sistema desactivado
    var overflowJSON: Data? = nil          // data cruda que se mergea al json final (sistema de vida)

    // Serializa todo a JSON (context-string del body). adaptation: overflow que en Android va por
    // datastore se pasa aca como Data crudo opcional; hasta que el World lo provea, el contexto
    // de vida se arma del dict `survival`.
    func contextJSON() -> Data {
        var root: [String: Any] = [
            "characterId": characterId,
            "displayName": displayName,
            "status": status.mapValues { Double($0) },
        ]

        if let urgent = urgentInstruction() {
            root["urgentInstruction"] = urgent
        }

        root["recentHistory"] = recentHistory
        root["memory"] = peers + memory.map { ["type": "note", "note": $0] as [String: Any] }
        root["peers"] = peers

        if !extraContext.isEmpty {
            root["extraContext"] = extraContext
        }

        // El sistema de vida/escalas se inyecta en el prompt como un bloque si el World lo expone;
        // si viene overflow en data cruda (esquema futuro del sistema de vida), se mergea arriba.
        var refined = root
        if let survival = survival {
            refined["survival"] = survival
        }
        if let overflowJSON = overflowJSON,
           let parsed = try? JSONSerialization.jsonObject(with: overflowJSON) as? [String: Any] {
            for (k, v) in parsed { refined[k] = v }
        }

        guard let data = try? JSONSerialization.data(withJSONObject: refined) else {
            return Data("{}".utf8)
        }
        return data
    }

    // En PC el "userMessage" hacia doble rol: respondio con prioridad por si el usuario escribia
    // algo, y forzaba a hablar si el personaje llevaba muchos turnos en silencio - aca se unifica
    // en un unico campo urgente porque el cantante de say se encarga de la fase.
    func urgentInstruction() -> String? {
        if let userMessage = userMessage {
            return userMessage
        }
        if forceSay {
            return "Habla aunque no tengas nada que decir - hace un rato que no decis nada y el mundo se siente raro."
        }
        return nil
    }
}