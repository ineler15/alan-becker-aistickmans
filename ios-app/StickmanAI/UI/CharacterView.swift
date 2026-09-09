// View de UN agente: rig procedural + nombre + bubble de ultima frase + barras de
// supervivencia. El drag lo mueve (world.dragAgent -> CharacterState.dragTo).

import SwiftUI

struct CharacterView: View {
  @EnvironmentObject var world: World
  let agent: VisibleAgent
  @State private var dragStart: CGPoint?

  var body: some View {
    let size = World.characterSize
    return ZStack(alignment: .topLeading) {
      if let rig = agent.rig {
        CharacterRigView(rig: rig, state: agent.state, size: size)
          .offset(x: -size.width / 2, y: -size.height / 2)
      }
      if world.prefs.survivalEnabled, let stats = world.survival[agent.id] {
        SurvivalBars(stats: stats)
          .offset(x: size.width / 2 - 26, y: -size.height / 2 - 3)
      }
      if let say = world.lastSay(for: agent.id) {
        SpeechBubble(text: say)
          .offset(x: 0, y: -size.height / 2 - 4)
      }
      Text(agent.displayName)
        .font(.system(size: 9, weight: .medium))
        .foregroundColor(.white.opacity(0.75))
        .shadow(color: .black, radius: 2)
        .offset(x: 0, y: size.height / 2 - 14)
    }
    .frame(width: size.width, height: size.height)
    .contentShape(Rectangle())
    .gesture(dragGesture)
  }

  private var dragGesture: some Gesture {
    DragGesture(minimumDistance: 2)
      .onChanged { value in
        if dragStart == nil {
          dragStart = agent.state.position
          world.setDragging(agent.id, true)
        }
        guard let start = dragStart else { return }
        world.dragAgent(
          agent.id,
          to: CGPoint(x: start.x + value.translation.width,
                      y: start.y + value.translation.height)
        )
      }
      .onEnded { _ in
        dragStart = nil
        world.setDragging(agent.id, false)
      }
  }
}

// Barras de vida/hambre/sed (espejo de la PC: hp rojo, hambre amarillo, sed celeste).
private struct SurvivalBars: View {
  let stats: Survival

  var body: some View {
    VStack(spacing: 1) {
      bar(value: stats.hp, color: Color(red: 0.85, green: 0.2, blue: 0.2))
      bar(value: stats.hunger, color: Color(red: 0.95, green: 0.8, blue: 0.2))
      bar(value: stats.thirst, color: Color(red: 0.3, green: 0.6, blue: 0.95))
    }
    .frame(width: 52)
    .padding(2)
    .background(Color.black.opacity(0.45))
    .clipShape(RoundedRectangle(cornerRadius: 3))
  }

  private func bar(value: Double, color: Color) -> some View {
    GeometryReader { geo in
      ZStack(alignment: .leading) {
        Capsule().fill(Color.white.opacity(0.15))
        Capsule().fill(color).frame(width: geo.size.width * CGFloat(min(max(value, 0), 100) / 100))
      }
    }
    .frame(height: 4)
  }
}

// Bubble con la ultima frase, se desvanece sola via World.expireLastSays (6s).
private struct SpeechBubble: View {
  let text: String

  var body: some View {
    Text(text)
      .font(.system(size: 10))
      .foregroundColor(.white)
      .lineLimit(2)
      .padding(.horizontal, 8)
      .padding(.vertical, 4)
      .background(Color.black.opacity(0.75))
      .clipShape(RoundedRectangle(cornerRadius: 6))
      .overlay(
        RoundedRectangle(cornerRadius: 6)
          .stroke(Color.white.opacity(0.15), lineWidth: 1)
      )
      .fixedSize()
  }
}