// Escena principal: la "habitacion" oscura con el piso, la cocina (si supervivencia),
// los agentes locales y los fantasmas de los peers remotos (PC/Android).

import SwiftUI

struct MainView: View {
  @EnvironmentObject var world: World
  @State private var showingChat = false
  @State private var showingSettings = false
  @State private var showingCreate = false

  var body: some View {
    // Lee frameCounter para que SwiftUI re-renderice posiciones cada tick.
    let _ = world.frameCounter
    return GeometryReader { geo in
      ZStack {
        Color(red: 0.07, green: 0.07, blue: 0.10).ignoresSafeArea()

        // Borde del "piso".
        Rectangle()
          .fill(Color.white.opacity(0.16))
          .frame(height: 2)
          .position(x: geo.size.width / 2, y: world.floorY)

        // Cocina (prop de supervivencia).
        if world.prefs.survivalEnabled {
          RoundedRectangle(cornerRadius: 6)
            .fill(Color(red: 0.16, green: 0.16, blue: 0.20))
            .frame(width: world.kitchenSize.width, height: world.kitchenSize.height)
            .overlay(
              Text("cocina")
                .font(.system(size: 11, weight: .medium))
                .foregroundColor(.white.opacity(0.65))
            )
            .overlay(
              RoundedRectangle(cornerRadius: 6)
                .stroke(Color.white.opacity(0.12), lineWidth: 1)
            )
            .position(x: world.kitchenCenter.x, y: world.kitchenCenter.y)
        }

        // Fantasmas de peers remotos (sin rig, solo un circulo + nombre).
        ForEach(world.remotePeers) { peer in
          RemotePeerGhost(peer: peer)
            .position(x: peer.x, y: peer.y)
        }

        // Agentes locales.
        ForEach(world.agents) { agent in
          CharacterView(agent: agent)
            .position(x: agent.state.position.x, y: agent.state.position.y)
        }

        // Botones superiores: chat y configuracion.
        VStack {
          HStack {
            Spacer()
            Button {
              showingChat = true
            } label: {
              iconButton(systemName: "bubble.left.fill")
            }
            Button {
              showingSettings = true
            } label: {
              iconButton(systemName: "gearshape.fill")
            }
            Button {
              showingCreate = true
            } label: {
              iconButton(systemName: "plus")
            }
          }
          Spacer()
        }
        .padding(.top, 8)
        .padding(.trailing, 12)
      }
    }
    .sheet(isPresented: $showingChat) {
      ChatView()
    }
    .sheet(isPresented: $showingSettings) {
      SettingsView()
    }
    .sheet(isPresented: $showingCreate) {
      CharacterCreateView(existing: nil)
    }
  }

  private func iconButton(systemName: String) -> some View {
    Image(systemName: systemName)
      .font(.system(size: 15, weight: .semibold))
      .foregroundColor(.white)
      .frame(width: 38, height: 38)
      .background(Color.white.opacity(0.12))
      .clipShape(Circle())
      .overlay(Circle().stroke(Color.white.opacity(0.15), lineWidth: 1))
  }
}

// Fantasma semi-transparente que representa un personaje de PC/Android visto por LAN.
private struct RemotePeerGhost: View {
  let peer: PeerClient.RemotePeer

  var body: some View {
    VStack(spacing: 2) {
      Capsule()
        .fill(Color.gray.opacity(0.55))
        .frame(width: 22, height: 34)
      Text(peer.displayName)
        .font(.system(size: 9))
        .foregroundColor(.white.opacity(0.6))
        .lineLimit(1)
    }
    .opacity(0.6)
    .frame(width: 50, height: 60)
  }
}