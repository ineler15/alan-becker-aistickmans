// Sheet de chat: un log de burbujas, un campo de texto, selector de destinatario y
// toggle "a todos" (hablarle a todos vs a un personaje).

import SwiftUI

struct ChatView: View {
  @EnvironmentObject var world: World
  @Environment(\.dismiss) private var dismiss
  @State private var text = ""
  @State private var group = true
  @State private var recipientId: String?

  var body: some View {
    NavigationView {
      VStack(spacing: 0) {
        ScrollViewReader { proxy in
          ScrollView {
            LazyVStack(spacing: 6) {
              if world.chatLog.isEmpty {
                Text("Todavia no se hablaron nada.")
                  .font(.footnote)
                  .foregroundColor(.secondary)
                  .padding(.top, 40)
              }
              ForEach(Array(world.chatLog.enumerated()), id: \.offset) { index, entry in
                bubble(entry, index: index)
                  .id(index)
              }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
          }
          .onChange(of: world.chatLog.count) { _ in
            let last = world.chatLog.count - 1
            if last >= 0 {
              withAnimation { proxy.scrollTo(last, anchor: .bottom) }
            }
          }
        }

        Divider()

        // Barra de envio.
        VStack(spacing: 8) {
          HStack(spacing: 8) {
            if !group {
              Picker("Destinatario", selection: $recipientId) {
                ForEach(world.agents) { agent in
                  Text(agent.displayName).tag(agent.id as String?)
                }
              }
              .pickerStyle(.menu)
              .frame(maxWidth: 130)
            }
            TextField("Escribile algo...", text: $text, axis: .vertical)
              .textFieldStyle(.roundedBorder)
              .lineLimit(1...3)
            Button {
              send()
            } label: {
              Image(systemName: "paperplane.fill")
                .foregroundColor(.white)
                .padding(8)
                .background(Color.accentColor)
                .clipShape(Circle())
            }
            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
          }
          HStack {
            Toggle("a todos", isOn: $group)
              .font(.footnote)
              .toggleStyle(.switch)
              .frame(maxWidth: nil, alignment: .leading)
              .fixedSize()
            Spacer()
          }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
      }
      .navigationTitle("Chat")
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cerrar") { dismiss() }
        }
      }
    }
  }

  private func bubble(_ entry: (id: String, text: String), index: Int) -> some View {
    let isUser = entry.id == "user"
    return HStack {
      if isUser { Spacer(minLength: 50) }
      VStack(alignment: isUser ? .trailing : .leading, spacing: 2) {
        Text(world.displayName(for: entry.id))
          .font(.system(size: 10, weight: .semibold))
          .foregroundColor(isUser ? .white.opacity(0.6) : .accentColor)
        Text(entry.text)
          .font(.system(size: 13))
          .foregroundColor(.white)
          .padding(.horizontal, 10)
          .padding(.vertical, 6)
          .background(isUser ? Color.accentColor : Color.white.opacity(0.12))
          .clipShape(RoundedRectangle(cornerRadius: 12))
      }
      if !isUser { Spacer(minLength: 50) }
    }
    .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
  }

  private func send() {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return }
    if group {
      world.sendChatMessage(trimmed, to: nil, group: true)
    } else {
      let to = recipientId ?? world.agents.first?.id
      if let to { world.sendChatMessage(trimmed, to: to, group: false) }
    }
    text = ""
  }
}