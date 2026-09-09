import Foundation

// Memoria de notas ("acordate de esto") por personaje, espejo de src/memory/notes.js (PC) /
// Prefs.rememberNote (Android). Cap 30, las viejas se descartan. Persistido en UserDefaults bajo
// "notes-<id>" como [String] (el detalle ts de PC no se expone; aca solo importa el texto).
final class NotesStore {

    static let shared = NotesStore()

    static let maxItems = 30

    private init() {}

    func recent(_ id: String, _ n: Int) -> [String] {
        let notes = read(id)
        guard n > 0 else { return [] }
        return Array(notes.suffix(n))
    }

    func add(_ id: String, _ note: String) {
        let trimmed = note.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var notes = read(id)
        notes.append(trimmed)
        if notes.count > Self.maxItems {
            notes.removeFirst(notes.count - Self.maxItems)
        }
        write(id, notes)
    }

    private func read(_ id: String) -> [String] {
        guard let data = UserDefaults.standard.data(forKey: Self.key(for: id)),
              let notes = try? JSONSerialization.jsonObject(with: data) as? [String]
        else { return [] }
        return notes
    }

    private func write(_ id: String, _ notes: [String]) {
        guard let data = try? JSONSerialization.data(withJSONObject: notes) else { return }
        UserDefaults.standard.set(data, forKey: Self.key(for: id))
    }

    static func key(for id: String) -> String { "notes-\(id)" }
}