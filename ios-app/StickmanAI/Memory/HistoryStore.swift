import Foundation

// Memoria de historial por personaje, espejo de src/memory/history.js (PC) / addHistory+recentHistory
// (Android). Cada personaje tiene su propio archivo (history-Red.json, etc.) - aca se persiste en
// UserDefaults con la clave "history-<id>" como JSON codificado.
final class HistoryStore {

    static let shared = HistoryStore()

    // Misma politica que PC: maximo 40 entradas, las mas nuevas al final (se descartan las viejas).
    static let maxItems = 40

    private init() {}

    func recent(_ id: String, _ n: Int) -> [[String: Any]] {
        let items = read(id)
        guard n > 0 else { return [] }
        return Array(items.suffix(n))
    }

    func add(_ id: String, _ entry: [String: Any]) {
        var items = read(id)
        // El 'ts' se agrega aca (ISO 8601 UTC) igual que en history.js - siempre adentro del dato,
        // nunca confiando en que el llamador lo mande.
        var stamped = entry
        stamped["ts"] = ISO8601DateFormatter().string(from: Date())
        items.append(stamped)
        if items.count > Self.maxItems {
            items.removeFirst(items.count - Self.maxItems)
        }
        write(id, items)
    }

    private func read(_ id: String) -> [[String: Any]] {
        guard let data = UserDefaults.standard.data(forKey: Self.key(for: id)),
              let items = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return [] }
        return items
    }

    private func write(_ id: String, _ items: [[String: Any]]) {
        guard let data = try? JSONSerialization.data(withJSONObject: items) else { return }
        UserDefaults.standard.set(data, forKey: Self.key(for: id))
    }

    static func key(for id: String) -> String { "history-\(id)" }
}