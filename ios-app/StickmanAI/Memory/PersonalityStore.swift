import Foundation

// Store de la "voz interior" persistente por personaje: personalidad (define_personality) y el
// contexto extra propio (set_context), + el contexto auto-generado del modo "ia". Espejo de
// selfPersonality.js / characterContext.js / aiContext.js en PC (todo JSON por personaje) y de los
// Prefs equivalentes en Android. Persistido en UserDefaults por clave.
final class PersonalityStore {

    static let shared = PersonalityStore()

    private init() {}

    // --- personalidad (define_personality) -------------------------------------------------

    // Texto libre si ya se definio, "" si todavia no (el prompt cae al default en ese caso).
    func loadPersonality(_ id: String) -> String {
        loadField(id, key: Self.personalityKey(for: id), field: "description")
    }

    func setPersonality(_ id: String, _ text: String) {
        saveField(id, key: Self.personalityKey(for: id), field: "description", value: text)
    }

    // --- contexto propio (set_context) ------------------------------------------------------

    func loadSelfContext(_ id: String) -> String {
        loadField(id, key: Self.selfContextKey(for: id), field: "context")
    }

    func setSelfContext(_ id: String, _ text: String) {
        saveField(id, key: Self.selfContextKey(for: id), field: "context", value: text)
    }

    // --- contexto auto-generado (modo "ia") -------------------------------------------------

    func loadContext(_ id: String) -> String {
        loadField(id, key: Self.contextKey(for: id), field: "context")
    }

    func setContext(_ id: String, _ text: String) {
        saveField(id, key: Self.contextKey(for: id), field: "context", value: text)
    }

    static func personalityKey(for id: String) -> String { "personality-\(id)" }

    private static func selfContextKey(for id: String) -> String { "context-\(id)" }

    private static func contextKey(for id: String) -> String { "ai-context-\(id)" }

    private static func fieldKey(_ key: String, _ field: String) -> String { "\(key).\(field)" }

    private func loadField(_ id: String, key: String, field: String) -> String {
        // El mismo dato se usa en dos lugares (la Store + el bundle), por eso se monta el dict
        // desde un solo par clave/valor en UserDefaults con sufijo de campo.
        UserDefaults.standard.string(forKey: Self.fieldKey(key, field)) ?? ""
    }

    private func saveField(_ id: String, key: String, field: String, value: String) {
        UserDefaults.standard.set(value, forKey: Self.fieldKey(key, field))
    }
}