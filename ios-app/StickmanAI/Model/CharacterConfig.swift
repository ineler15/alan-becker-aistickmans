// Configuracion de cada personaje + prefs globales de la app. Persistidos en UserDefaults.

import Foundation

struct CharacterConfig: Identifiable, Codable, Equatable {
  let id: String
  var displayName: String
  var colorHex: String
  var gender: String?
  var hasFace: Bool
  var headModel: String      // "normal" | "hueca"
  var poseProfile: String    // id del rig base del que se clono (Red/TCO/...)
  var provider: String?      // nil = usar el compartido
  var apiKey: String?
  var contextMode: String    // "canon" | "creado" | "ia"
  var userContext: String
  var partnerId: String?
  var affectionLevel: Int    // 0-100

  var isCustom: Bool {
    CharacterConfig.roster.contains { $0.id == id } == false
  }
}

extension CharacterConfig {
  // Roster identico a src/characters.js (displayName en minusculas como la PC) y colorHex
  // tomado del campo "color" de cada rig en renderer/rigs/*.json. Built-in == sin cara ni
  // genero (jsCharacterEngine.js solo agrega hasFace/gender/accessory para custom characters).
  static let roster: [CharacterConfig] = [
    CharacterConfig(id: "Red", displayName: "Red", colorHex: "FE0000",
                    gender: nil, hasFace: false, headModel: "normal", poseProfile: "Red",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "Orange", displayName: "The Second Coming", colorHex: "FF741A",
                    gender: nil, hasFace: false, headModel: "hueca", poseProfile: "Orange",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "Green", displayName: "Green", colorHex: "80FF00",
                    gender: nil, hasFace: false, headModel: "normal", poseProfile: "Green",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "Blue", displayName: "Blue", colorHex: "34E8FF",
                    gender: nil, hasFace: false, headModel: "normal", poseProfile: "Blue",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "Yellow", displayName: "Yellow", colorHex: "FFF500",
                    gender: nil, hasFace: false, headModel: "normal", poseProfile: "Yellow",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "Purple", displayName: "Purple", colorHex: "D92FD0",
                    gender: nil, hasFace: false, headModel: "normal", poseProfile: "Purple",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "TCO", displayName: "The Chosen One", colorHex: "000000",
                    gender: nil, hasFace: false, headModel: "hueca", poseProfile: "TCO",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "TDL", displayName: "The Dark Lord", colorHex: "FF0000",
                    gender: nil, hasFace: false, headModel: "hueca", poseProfile: "TDL",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
    CharacterConfig(id: "victim", displayName: "Victim", colorHex: "292929",
                    gender: nil, hasFace: false, headModel: "hueca", poseProfile: "victim",
                    provider: nil, apiKey: nil, contextMode: "canon", userContext: "",
                    partnerId: nil, affectionLevel: 50),
  ]

  static func defaultEnabledIds() -> [String] {
    ["Red", "Orange", "Green", "Blue", "Yellow"]
  }
}

// Prefs globales, persistidas bajo la clave "stickman.prefs".
struct AppPrefs: Codable, Equatable {
  var sharedProvider: String
  var sharedApiKey: String
  var survivalEnabled: Bool
  var peerHost: String?
  var characters: [CharacterConfig]
  var enabledIds: [String]
  var perCharacterContext: [String: String]

  static func `default`() -> AppPrefs {
    AppPrefs(
      sharedProvider: "gemini",
      sharedApiKey: "",
      survivalEnabled: false,
      peerHost: nil,
      characters: CharacterConfig.roster,
      enabledIds: CharacterConfig.defaultEnabledIds(),
      perCharacterContext: [:]
    )
  }

  static let storageKey = "stickman.prefs"
  static let userDefaults = UserDefaults.standard

  static func load() -> AppPrefs {
    guard let data = userDefaults.data(forKey: storageKey),
          let decoded = try? JSONDecoder().decode(AppPrefs.self, from: data) else {
      return .default()
    }
    return decoded
  }

  func save() {
    guard let data = try? JSONEncoder().encode(self) else { return }
    AppPrefs.userDefaults.set(data, forKey: AppPrefs.storageKey)
  }
}