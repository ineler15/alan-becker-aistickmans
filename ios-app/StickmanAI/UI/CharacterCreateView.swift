// "Crear tu propio stickman" (crear/editar personaje custom), espejo de renderer/createCharacter.js
// + src/customCharacters.js: nombre, paleta de 8 colores, cabeza normal/hueca (Red/TCO), cara,
// genero, accesorio (ninguno/pelo/monio).

import SwiftUI

struct CharacterCreateView: View {
  @EnvironmentObject var world: World
  @Environment(\.dismiss) private var dismiss

  let existing: CharacterConfig?

  @State private var displayName = ""
  @State private var colorHex = "FE0000"
  @State private var headModel: HeadModel = .normal
  @State private var hasFace = true
  @State private var gender = "otro"
  @State private var accessory = "ninguno"

  // Misma paleta que PC/Android (customCharacters.js PALETTE).
  static let palette = [
    "FE0000", "FF8C00", "00AA00", "0064FF",
    "E6C800", "A000C8", "FFFFFF", "141414",
  ]

  enum HeadModel: String, CaseIterable, Identifiable {
    case normal = "normal"
    case hueca = "hueca"
    var id: String { rawValue }
    var label: String {
      switch self {
      case .normal: return "Normal"
      case .hueca: return "Hueca"
      }
    }
  }

  var body: some View {
    NavigationView {
      Form {
        Section("Nombre") {
          TextField("Nombre", text: $displayName)
        }

        Section("Color") {
          LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 10) {
            ForEach(Self.palette, id: \.self) { hex in
              Circle()
                .fill(Self.color(fromHex: hex))
                .frame(width: 40, height: 40)
                .overlay(
                  Circle().stroke(Color.white, lineWidth: colorHex == hex ? 3 : 1)
                    .opacity(colorHex == hex ? 1 : 0.25)
                )
                .onTapGesture { colorHex = hex }
            }
          }
          .padding(.vertical, 4)
        }

        Section("Cabeza") {
          Picker("Modelo", selection: $headModel) {
            ForEach(HeadModel.allCases) { model in
              Text(model.label).tag(model)
            }
          }
          .pickerStyle(.segmented)
          Toggle("Cara", isOn: $hasFace)
        }

        Section("Apariencia") {
          Picker("Genero", selection: $gender) {
            Text("Masculino").tag("masculino")
            Text("Femenino").tag("femenino")
            Text("Otro").tag("otro")
          }
          Picker("Accesorio", selection: $accessory) {
            Text("Ninguno").tag("ninguno")
            Text("Pelo").tag("pelo")
            Text("Monio").tag("monio")
          }
        }
      }
      .navigationTitle(existing == nil ? "Crear stickman" : "Editar stickman")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancelar") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Guardar") { save() }
        }
      }
      .onAppear(perform: loadExisting)
    }
  }

  private func loadExisting() {
    guard let existing else { return }
    displayName = existing.displayName
    colorHex = existing.colorHex
    headModel = HeadModel(rawValue: existing.headModel) ?? .normal
    hasFace = existing.hasFace
    gender = existing.gender ?? "otro"
    accessory = Self.accessory(fromContext: world.prefs.perCharacterContext[Self.accessoryKey(for: existing.id)])
  }

  private func save() {
    let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
    let finalName = name.isEmpty ? "Stickman" : name
    let id: String
    if let existing {
      id = existing.id
    } else {
      id = Self.sanitizeId(finalName, taken: Set(world.prefs.characters.map(\.id)))
    }
    let config = CharacterConfig(
      id: id,
      displayName: finalName,
      colorHex: colorHex,
      gender: gender,
      hasFace: hasFace,
      headModel: headModel.rawValue,
      poseProfile: headModel == .normal ? "Red" : "TCO",
      provider: existing?.provider,
      apiKey: existing?.apiKey,
      contextMode: existing?.contextMode ?? "creado",
      userContext: existing?.userContext ?? "",
      partnerId: existing?.partnerId,
      affectionLevel: existing?.affectionLevel ?? 50
    )
    // El accesorio vive en perCharacterContext (el schema de CharacterConfig no lo lleva).
    world.prefs.perCharacterContext[Self.accessoryKey(for: id)] = accessory
    if existing != nil {
      world.updateCharacter(config)
    } else {
      world.createCharacter(config)
    }
    dismiss()
  }

  // sanitizeId de customCharacters.js: quita diacriticos y deja [a-zA-Z0-9].
  static func sanitizeId(_ name: String, taken: Set<String>) -> String {
    let folded = name.folding(options: .diacriticInsensitive, locale: nil)
    let filtered = folded.filter { $0.isLetter || $0.isNumber }
    let base = filtered.isEmpty ? "Stickman" : filtered
    var id = base
    var suffix = 2
    while taken.contains(id) {
      id = "\(base)\(suffix)"
      suffix += 1
    }
    return id
  }

  static func color(fromHex hex: String) -> Color {
    let cleaned = hex.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "#", with: "")
    guard cleaned.count == 6, let value = Int(cleaned, radix: 16) else { return .gray }
    let r = Double((value >> 16) & 0xFF) / 255
    let g = Double((value >> 8) & 0xFF) / 255
    let b = Double(value & 0xFF) / 255
    return Color(red: r, green: g, blue: b)
  }

  // El accesorio se guarda en perCharacterContext bajo la clave "accesorio:<id>" para que
  // el editor pueda volver a mostrarlo (CharacterConfig no lo lleva en el schema base).
  static func accessoryKey(for id: String) -> String { "accesorio:\(id)" }

  static func accessory(fromContext context: String?) -> String {
    guard let context else { return "ninguno" }
    return (context == "pelo" || context == "monio") ? context : "ninguno"
  }
}