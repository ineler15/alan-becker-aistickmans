// Entry point de la app iOS. Muestra Configuracion si no hay API key guardada (mismo gate
// pre-lanzamiento que la PC), si no la escena principal.

import SwiftUI
import Combine

@main
struct StickmanAIApp: App {
  @StateObject private var world = World()

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(world)
    }
  }
}

struct RootView: View {
  @EnvironmentObject var world: World
  private let ticker = Timer.publish(every: 1.0 / 30.0, on: .main, in: .common).autoconnect()

  var body: some View {
    Group {
      if world.needsSetup {
        SettingsView()
      } else {
        MainView()
      }
    }
    .preferredColorScheme(.dark)
    .onReceive(ticker) { _ in
      world.tickVisuals()
    }
    .onAppear {
      world.start()
    }
    .onDisappear {
      world.stop()
    }
  }
}