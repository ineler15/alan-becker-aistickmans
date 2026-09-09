import Foundation

// Cliente OpenAI-compatible de chat/completions con function calling, espejo de GeminiClient.kt en
// Android / geminiProvider.js en PC. Mismo contrato de wire para los 4 providers (gemini/openai/
// groq/openrouter): solo cambian baseURL y model. Extensiones:
// - JSONValue: enumito Codable con caso por tipo JSON (helper para leer args de la tool call).
// - AIEndpoint: tupla con etiquetas del endpoint resuelto (baseURL + model) - el apiKey lo lleva
//   la instancia de AIClient, porque decide(endpoint:) segun contrato no recibe el key.
// - AIError: errores tipados, incluyendo el body de error que a veces viene envuelto en un array
//   [{error:...}] (ej. respuestas 429 de cuota) - sin ese manejo el error real se perdia.

typealias AIEndpoint = (baseURL: URL, model: String)

enum AIError: LocalizedError {
    case api(code: Int, message: String)
    case invalidResponse(String)

    var errorDescription: String? {
        switch self {
        case .api(let code, let message): return "API error (\(code)): \(message)"
        case .invalidResponse(let text): return "respuesta invalida: \(text)"
        }
    }
}

enum JSONValue: Codable, Equatable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case null
    case array([JSONValue])
    case object([String: JSONValue])

    func asString() -> String? {
        if case .string(let s) = self { return s }
        return nil
    }

    func asInt() -> Int? {
        switch self {
        case .int(let i): return i
        case .double(let d) where d.isFinite && d == d.rounded() && abs(d) <= Double(Int.max): return Int(d)
        default: return nil
        }
    }

    func asDouble() -> Double? {
        switch self {
        case .double(let d): return d
        case .int(let i): return Double(i)
        default: return nil
        }
    }

    func asBool() -> Bool? {
        switch self {
        case .bool(let b): return b
        case .int(let i): return i != 0
        default: return nil
        }
    }

    func arrayValue() -> [JSONValue]? {
        if case .array(let a) = self { return a }
        return nil
    }

    subscript(key: String) -> JSONValue? {
        if case .object(let d) = self { return d[key] }
        return nil
    }

    // Convierte a tipo Any (NSNumber/String/...) lista para JSONSerialization - se usa para
    // meter los args de una tool call en el historial y para applySurvival.
    func toAny() -> Any {
        switch self {
        case .string(let s): return s
        case .int(let i): return i
        case .double(let d): return d
        case .bool(let b): return b
        case .null: return NSNull()
        case .array(let a): return a.map { $0.toAny() }
        case .object(let o): return o.mapValues { $0.toAny() }
        }
    }

    // Convierte cualquier Any (lo que devuelve JSONSerialization) a JSONValue.
    static func from(_ any: Any) -> JSONValue {
        switch any {
        case let s as String: return .string(s)
        case let b as Bool: return .bool(b)
        case is NSNull: return .null
        case let n as NSNumber:
            // JSONSerialization da los numeros como NSNumber y los booleanos los empata con Bool
            // arriba; aca se guarda como int cuando es entero exacto y como double si no.
            let d = n.doubleValue
            if d.isFinite && d == d.rounded() && abs(d) <= Double(Int.max) {
                return .int(Int(d))
            }
            return .double(d)
        case let i as Int: return .int(i)
        case let d as Double: return .double(d)
        case let arr as [Any]: return .array(arr.map { from($0) })
        case let dict as [String: Any]: return .object(dict.mapValues { from($0) })
        default: return .null
        }
    }

    // Codable - decode tolerante a cualquier forma que mande el modelo.
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let s = try? c.decode(String.self) { self = .string(s); return }
        if let i = try? c.decode(Int.self) { self = .int(i); return }
        if let d = try? c.decode(Double.self) { self = .double(d); return }
        if let b = try? c.decode(Bool.self) { self = .bool(b); return }
        if c.decodeNil() { self = .null; return }
        if let a = try? c.decode([JSONValue].self) { self = .array(a); return }
        if let o = try? c.decode([String: JSONValue].self) { self = .object(o); return }
        throw DecodingError.dataCorruptedError(in: c, debugDescription: "JSONValue: tipo desconocido")
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .int(let v): try c.encode(v)
        case .double(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        }
    }
}

extension Dictionary where Key == String, Value == JSONValue {
    func toAnyDict() -> [String: Any] {
        mapValues { $0.toAny() }
    }
}

struct AIClient {

    // La instancia lleva el apiKey porque decide(endpoint:...) del contrato no lo recibe -
    // endpoint() valida que no este vacio y aca se usa para el header Authorization.
    var apiKey: String = ""

    // Mismos endpoints/modelos default que config.js (PC) / GeminiClient.kt (Android).
    static func endpoint(kind: ProviderKind, apiKey: String, modelOverride: String?) -> AIEndpoint? {
        guard !apiKey.isEmpty else { return nil }
        guard let baseURL = baseURL(for: kind) else { return nil }
        let model = modelOverride ?? defaultModel(for: kind)
        return (baseURL: baseURL, model: model)
    }

    static func baseURL(for kind: ProviderKind) -> URL? {
        let s: String
        switch kind {
        case .openai: s = "https://api.openai.com/v1/chat/completions"
        case .groq: s = "https://api.groq.com/openai/v1/chat/completions"
        case .openrouter: s = "https://openrouter.ai/api/v1/chat/completions"
        case .gemini: s = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
        }
        return URL(string: s)
    }

    static func defaultModel(for kind: ProviderKind) -> String {
        switch kind {
        case .gemini: return "gemini-3.5-flash-lite"
        case .openai: return "gpt-4o-mini"
        case .groq: return "qwen/qwen3.6-27b"
        case .openrouter: return "anthropic/claude-sonnet-4.5"
        }
    }

    // Decidir la proxima accion: mandar el contexto como texto plano (sin imagenes), con tools +
    // tool_choice "required" para forzar function calling. El contexto es el JSON del ContextBundle.
    func decide(endpoint: AIEndpoint, systemPrompt: String, contextJSON: Data) async throws -> (tool: String, args: [String: JSONValue]) {
        let contextText = String(decoding: contextJSON, as: UTF8.self)

        let body: [String: Any] = [
            "model": endpoint.model,
            "tools": ActionsSchema.tools(),
            "tool_choice": "required",
            "max_tokens": 300,
            "messages": [
                ["role": "system", "content": systemPrompt],
                ["role": "user", "content": [["type": "text", "text": contextText]]],
            ],
        ]

        var request = URLRequest(url: endpoint.baseURL)
        request.httpMethod = "POST"
        request.timeoutInterval = 45
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        guard request.httpBody != nil else {
            throw AIError.invalidResponse("no se pudo serializar el body")
        }

        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else {
            throw AIError.invalidResponse("respuesta sin HTTP")
        }
        guard (200..<300).contains(http.statusCode) else {
            throw Self.apiError(from: data, code: http.statusCode)
        }

        // Forzar tool_choice a "required" a veces vuelve sin tool_calls - cae a "wait" silencioso
        // como en GeminiClient (sin error, el contador de silencio se encarga de destrabar).
        guard let obj = Self.jsonObject(data) as? [String: Any],
              let choices = obj["choices"] as? [[String: Any]],
              let message = choices.first?["message"] as? [String: Any],
              let calls = message["tool_calls"] as? [[String: Any]],
              let fn = calls.first?["function"] as? [String: Any],
              let name = fn["name"] as? String
        else {
            return ("wait", [:])
        }

        let argsText = (fn["arguments"] as? String) ?? "{}"
        let argsObj = Self.jsonObject(Data(argsText.utf8)) as? [String: Any] ?? [:]
        return (name, argsObj.mapValues { JSONValue.from($0) })
    }

    // Nota de port: GeminiClient/PC mandaban "reasoning_effort": "none" para groq - el contrato
    // del body iOS no lo incluye, se deja fuera a proposito.

    private static func jsonObject(_ data: Data) -> Any? {
        try? JSONSerialization.jsonObject(with: data)
    }

    // El body de error a veces es un JSONObject plano y a veces un array de un elemento
    // [{error:...}] (ej. ciertos 429 de cuota) - soportar ambos para no perder el mensaje real.
    private static func apiError(from data: Data, code: Int) -> AIError {
        var message: String? = nil
        if let arr = Self.jsonObject(data) as? [[String: Any]],
           let err = arr.first?["error"] as? [String: Any] {
            message = err["message"] as? String
        } else if let obj = Self.jsonObject(data) as? [String: Any],
                  let err = obj["error"] as? [String: Any] {
            message = err["message"] as? String
        } else if let obj = Self.jsonObject(data) as? [String: Any] {
            message = obj["message"] as? String
        }
        let fallback = String(decoding: data, as: UTF8.self).prefix(200)
        return .api(code: code, message: message ?? String(fallback))
    }
}