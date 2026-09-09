// Configuracion, equivalente iOS de renderer/settings.js + src/pcSettings.js: proveedor
// compartido, API keys, personajes (toggle/que aparecen) con provider/pareja/afecto/contexto,
// crear stickman, host LAN. Guardar aplica via world.applySettings (reconstruye agentes).

import SwiftUI

struct SettingsView: View {
  @EnvironmentObject var world: World
  @Environment(\.dismiss) private var dismiss

  @State private var prefs: AppPrefs = AppPrefs.default()
  @State private var showingContextEditor = false
  @State private var contextTargetId: String?
  @State private var contextText = ""
  @State private var editingCharacter: CharacterConfig?
  @State private var showingCharacterEditor = false

  static let providers = ["gemini", "openai", "groq", "openrouter"]

  private let contextModes: [(value: String, label: String)] = [
    ("canon", "Alan Becker (canon)"),
    ("creado", "Creado por vos"),
    ("ia", "IA al iniciar"),
  ]

  var body: some View {
    NavigationView {
      Form {
        Section("Compartido") {
          Picker("Proveedor", selection: $prefs.sharedProvider) {
            ForEach(Self.providers, id: \.self) { p in
              Text(p).tag(p)
            }
          }
          SecureField("API key compartida", text: $prefs.sharedApiKey)
          Toggle("Supervivencia (vida/hambre/sed)", isOn: $prefs.survivalEnabled)
          TextField(
            "Host LAN (PEER_HOST)",
            text: Binding(
              get: { prefs.peerHost ?? "" },
              set: { prefs.peerHost = $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
            )
          )
          .textInputAutocapitalization(.never)
          .autocorrectionDisabled()
          .font(.footnote)
          .foregroundColor(.secondary)
        }

        Section("Personajes") {
          ForEach(prefs.characters.indices, id: \.self) { i in
            CharacterSettingsRow(
              character: $prefs.characters[i],
              enabled: Binding(
                get: { prefs.enabledIds.contains(prefs.characters[i].id) },
                set: { on in
                  let id = prefs.characters[i].id
                  if on {
                    if !prefs.enabledIds.contains(id) { prefs.enabledIds.append(id) }
                  } else {
                    prefs.enabledIds.removeAll { $0 == id }
                  }
                }
              ),
              characterOptions: prefs.characters.map { ($0.id, $0.displayName) },
              contextModes: contextModes,
              onEditContext: {
                contextTargetId = prefs.characters[i].id
                contextText = prefs.perCharacterContext[prefs.characters[i].id]
                  ?? prefs.characters[i].userContext
                showingContextEditor = true
              },
              onEditCustom: {
                editingCharacter = prefs.characters[i]
                showingCharacterEditor = true
              }
            )
          }
        }

        Section {
          Button {
            editingCharacter = nil
            showingCharacterEditor = true
          } label: {
            Label("Crear stickman", systemImage: "person.crop.circle.badge.plus")
          }
        }
      }
      .navigationTitle("Configuracion")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancelar") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Guardar") {
            world.applySettings(prefs)
            dismiss()
          }
        }
      }
    }
    .onAppear {
      prefs = world.prefs
    }
    .sheet(isPresented: $showingContextEditor, onDismiss: {
      contextTargetId = nil
    }) {
      contextEditor
    }
    .sheet(isPresented: $showingCharacterEditor, onDismiss: {
      prefs = world.prefs
    }) {
      CharacterCreateView(existing: editingCharacter)
    }
  }

  private var contextEditor: some View {
    NavigationView {
      VStack(alignment: .leading, spacing: 8) {
        if let id = contextTargetId {
          Text("Contexto de \(world.displayName(for: id))")
            .font(.footnote)
            .foregroundColor(.secondary)
        }
        TextEditor(text: $contextText)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
          .padding(4)
          .overlay(
            RoundedRectangle(cornerRadius: 6)
              .stroke(Color.white.opacity(0.15), lineWidth: 1)
          )
      }
      .padding()
      .navigationTitle("Contexto")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancelar") {
            contextTargetId = nil
            showingContextEditor = false
          }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Guardar") {
            if let id = contextTargetId {
              prefs.perCharacterContext[id] = contextText
            }
            showingContextEditor = false
          }
        }
      }
    }
  }
}

// Fila de un personaje dentro del formulario.
private struct CharacterSettingsRow: View {
  @Binding var character: CharacterConfig
  @Binding var enabled: Bool
  let characterOptions: [(id: String, displayName: String)]
  let contextModes: [(value: String, label: String)]
  let onEditContext: () -> Void
  let onEditCustom: () -> Void

  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      HStack {
        Toggle("", isOn: $enabled).labelsHidden()
        Text(character.displayName)
          .font(.subheadline).bold()
          .foregroundColor(CharacterCreateView.color(fromHex: character.colorHex))
        Spacer()
        if character.isCustom {
          Button("Editar", action: onEditCustom).font(.footnote)
        }
        Button("Contexto", action: onEditContext).font(.footnote)
      }

      if enabled {
        Picker("Proveedor", selection: providerBinding) {
          Text("(compartido)").tag("")
          ForEach(SettingsView.providers, id: \.self) { p in
            Text(p).tag(p)
          }
        }
        SecureField("API key", text: apiKeyBinding)
        Picker("Pareja", selection: partnerBinding) {
          Text("(ninguna)").tag(String?.none)
          ForEach(characterOptions, id: \.id) { opt in
            Text(opt.displayName).tag(String?.some(opt.id))
          }
        }
        VStack(alignment: .leading, spacing: 2) {
          Slider(value: affectionBinding, in: 0...100, step: 1)
          Text("Afecto: \(character.affectionLevel)")
            .font(.caption2)
            .foregroundColor(.secondary)
        }
        Picker("Contexto", selection: contextModeBinding) {
          ForEach(contextModes, id: \.value) { mode in
            Text(mode.label).tag(mode.value)
          }
        }
      }
    }
    .padding(.vertical, 2)
  }

  private var providerBinding: Binding<String> {
    Binding(
      get: { character.provider ?? "" },
      set: { character.provider = $0.isEmpty ? nil : $0 }
    )
  }

  private var apiKeyBinding: Binding<String> {
    Binding(
      get: { character.apiKey ?? "" },
      set: { character.apiKey = $0.isEmpty ? nil : $0 }
    )
  }

  private var partnerBinding: Binding<String?> {
    Binding(
      get: { character.partnerId },
      set: { character.partnerId = $0 }
    )
  }

  private var affectionBinding: Binding<Double> {
    Binding(
      get: { Double(character.affectionLevel) },
      set: { character.affectionLevel = Int($0) }
    )
  }

  private var contextModeBinding: Binding<String> {
    Binding(
      get: { character.contextMode },
      set: { character.contextMode = $0 }
    )
  }
}