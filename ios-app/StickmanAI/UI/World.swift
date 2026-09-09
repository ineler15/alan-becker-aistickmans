// World: el estado compartido de la escena. Dueña de los agentes (VisibleAgent), del chat,
// de la supervivencia, de los peers remotos (LAN) y del puente entre el DECISION LOOP (cadencia
// de IA ~12s) y el RENDER (tick rapido ~30fps). Implementa WorldProtocol (definido por el agente
// de IA).

import Foundation
import Combine
import SwiftUI

// Agente visible en la escena. Este struct es propiedad de la World (no del Engine).
struct VisibleAgent: Identifiable {
  let id: String
  let displayName: String
  let rig: RigFigure?
  let state: CharacterState
}

extension CGPoint {
  static func distance(_ a: CGPoint, _ b: CGPoint) -> CGFloat {
    hypot(a.x - b.x, a.y - b.y)
  }
}

private typealias PendingMove = (x: CGFloat, y: CGFloat, run: Bool)

@MainActor
final class World: ObservableObject, WorldProtocol {

  // MARK: - Estado publicado

  @Published var agents: [VisibleAgent] = []
  @Published var chatLog: [(id: String, text: String)] = []
  @Published var prefs: AppPrefs
  @Published var remotePeers: [PeerClient.RemotePeer] = []
  @Published var survival: [String: Survival] = [:]
  @Published var userMessages: [String: String] = [:]
  // Contador que sube cada tickVisuals para que la UI se anime aunque nada mas cambie.
  @Published private(set) var frameCounter: Int = 0

  // MARK: - Constantes de escena

  static let characterSize = CGSize(width: 80, height: 105)   // RIG_WIDTH/RIG_HEIGHT de la PC
  var floorY: CGFloat
  var kitchenCenter: CGPoint                                    // "cocina", abajo al centro
  var kitchenSize = CGSize(width: 110, height: 34)
  var screenSize: CGSize

  // MARK: - Estado interno

  private var lastSays: [String: String] = [:]
  private var lastSayAt: [String: Date] = [:]
  private var sayDuration: TimeInterval = 6
  private var lastSurvivalAt: [String: Date] = [:]
  private var pendingMoves: [String: PendingMove] = [:]
  private var pendingAnimations: [String: String] = [:]
  private var pendingFaces: [String: [String: String]] = [:]
  private var draggingIds: Set<String> = []
  private var provider: ProviderPrefs
  private var peerClient: PeerClient
  private var _loop: DecisionLoop?
  private var cancellables: Set<AnyCancellable> = []

  // MARK: - Init

  init() {
    let loaded = AppPrefs.load()
    let size = World.initialScreenSize()
    let floor = max(0, size.height - 110)

    self.prefs = loaded
    self.screenSize = size
    self.floorY = floor
    self.kitchenCenter = CGPoint(x: size.width / 2, y: floor - 30)
    self.provider = World.providerPrefs(from: loaded)
    self.agents = World.buildAgents(prefs: loaded, screenSize: size, floorY: floor)
    for id in self.agents.map(\.id) {
      self.survival[id] = Survival()
    }
    self.peerClient = PeerClient(host: loaded.peerHost)

    // Proveedores del PeerClient apuntan a la World con referencia debil.
    peerClient.localPeersProvider = { [weak self] in self?.peersNetworkPayload() ?? [] }
    peerClient.screenWidthProvider = { [weak self] in self?.screenSize.width ?? 0 }
    peerClient.$remotePeers
      .sink { [weak self] peers in self?.remotePeers = peers }
      .store(in: &cancellables)
  }

  // MARK: - El loop de IA (lazy: evita el huevo-gallina world->loop->world)

  var loop: DecisionLoop {
    if let loop = _loop { return loop }
    let created = makeLoop()
    _loop = created
    return created
  }

  private func makeLoop() -> DecisionLoop {
    DecisionLoop(world: self, prefs: provider,
                 stores: (HistoryStore(), NotesStore(), PersonalityStore()))
  }

  func start() {
    peerClient.start()
    loop.start()
  }

  func stop() {
    peerClient.stop()
    loop.stop()
  }

  var needsSetup: Bool {
    if !prefs.sharedApiKey.trimmingCharacters(in: .whitespaces).isEmpty { return false }
    let anyEnabledWithKey = prefs.enabledIds.contains { id in
      guard let cfg = prefs.characters.first(where: { $0.id == id }) else { return false }
      return cfg.apiKey?.trimmingCharacters(in: .whitespaces).isEmpty == false
    }
    return !anyEnabledWithKey
  }

  // MARK: - Construccion de agentes

  private static func buildAgents(prefs: AppPrefs, screenSize: CGSize, floorY: CGFloat) -> [VisibleAgent] {
    prefs.enabledIds.compactMap { id in
      guard let cfg = prefs.characters.first(where: { $0.id == id }) else { return nil }
      let rigName = cfg.isCustom ? cfg.poseProfile : cfg.id
      var rig = RigFigure.load(bundleName: rigName)
      if cfg.isCustom {
        rig = rig.map { recolor(figure: $0, colorHex: cfg.colorHex) }
      }
      let state = CharacterState(worldSize: CGSize(width: screenSize.width, height: screenSize.height))
      state.position = CGPoint(x: CGFloat.random(in: 30..<max(31, screenSize.width - 100)),
                               y: floorY + CGFloat.random(in: -90...(-20)))
      return VisibleAgent(id: cfg.id, displayName: cfg.displayName, rig: rig, state: state)
    }
  }

  private static func initialScreenSize() -> CGSize {
    let s = UIScreen.main.bounds.size
    return s.width > 0 ? s : CGSize(width: 390, height: 844)
  }

  // Recolor simple: re-encodifica el figura y pisa el color top-level con el del usuario.
  // Requiere que RigFigure sea Codable (el Engine ya lo hace Decodable; falta Encodable).
  static func recolor(figure: RigFigure, colorHex: String) -> RigFigure {
    guard let rgba = rgbaArray(fromHex: colorHex),
          let data = try? JSONEncoder().encode(figure),
          var obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
      return figure
    }
    obj["color"] = rgba
    guard let newData = try? JSONSerialization.data(withJSONObject: obj),
          let recolored = try? JSONDecoder().decode(RigFigure.self, from: newData) else {
      return figure
    }
    return recolored
  }

  static func rgbaArray(fromHex hex: String) -> [Int]? {
    var h = hex.trimmingCharacters(in: .whitespacesAndNewlines)
    if h.hasPrefix("#") { h.removeFirst() }
    guard h.count == 6, let value = Int(h, radix: 16) else { return nil }
    return [(value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF, 255]
  }

  // MARK: - WorldProtocol

  func setSay(_ text: String, for id: String) {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return }
    chatLog.append((id: id, text: trimmed))
    if chatLog.count > 200 { chatLog.removeFirst(chatLog.count - 200) }
    lastSays[id] = trimmed
    lastSayAt[id] = Date()
  }

  func agentConfig(_ id: String) -> (
    displayName: String, contextMode: String, userContext: String, gender: String?,
    hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int
  )? {
    guard let cfg = prefs.characters.first(where: { $0.id == id }) else { return nil }
    var partner: String? = nil
    if let partnerId = cfg.partnerId, let p = prefs.characters.first(where: { $0.id == partnerId }) {
      partner = p.displayName
    }
    let effectiveContext = prefs.perCharacterContext[id] ?? cfg.userContext
    return (displayName: cfg.displayName,
            contextMode: cfg.contextMode,
            userContext: effectiveContext,
            gender: cfg.gender,
            hasFace: cfg.hasFace,
            partnerDisplayName: partner,
            affectionLevel: cfg.affectionLevel)
  }

  func peersSnapshot() -> [[String: Any]] {
    var result: [[String: Any]] = []
    for agent in agents {
      let pos = agent.state.position
      result.append([
        "id": agent.id,
        "displayName": agent.displayName,
        "device": "iphone",
        "x": pos.x,
        "y": pos.y,
        "lastSay": lastSays[agent.id] as Any,
      ])
    }
    for peer in remotePeers {
      result.append([
        "id": peer.id,
        "displayName": peer.displayName,
        "device": peer.device ?? "desconocido",
        "x": peer.x,
        "y": peer.y,
        "lastSay": peer.lastSay as Any,
      ])
    }
    return result
  }

  func localStatus(_ id: String) -> [String: CGFloat] {
    guard let agent = agent(id) else { return [:] }
    return ["x": agent.state.position.x, "y": agent.state.position.y]
  }

  func survivalStats(_ id: String) -> [String: Any]? {
    guard prefs.survivalEnabled, let s = survival[id] else { return nil }
    return s.asDictionary()
  }

  func applySurvival(_ stats: [String: Any], for id: String) {
    guard prefs.survivalEnabled else { return }
    guard let parsed = Survival.fromDictionary(stats) else { return }
    var current = survival[id] ?? Survival()
    current.hp = parsed.hp
    current.hunger = parsed.hunger
    current.thirst = parsed.thirst
    survival[id] = current
    if current.isDead { agent(id)?.state.dead = true }
  }

  func agentState(_ id: String) -> DecisionLoop.CharacterAgentState {
    guard let agent = agent(id) else {
      return DecisionLoop.CharacterAgentState(position: nil, frameKind: "none", frame: 0,
                                              pendingMove: nil, pendingAnimation: nil,
                                              pendingFace: nil)
    }
    let st = agent.state
    return DecisionLoop.CharacterAgentState(position: st.position,
                                            frameKind: frameKindString(st.frameKind),
                                            frame: st.frame,
                                            pendingMove: pendingMoves[id],
                                            pendingAnimation: pendingAnimations[id],
                                            pendingFace: pendingFaces[id])
  }

  func kitchenPosition() -> [String: CGFloat]? {
    guard prefs.survivalEnabled else { return nil }
    return ["x": kitchenCenter.x, "y": kitchenCenter.y]
  }

  func consumeUserMessage(_ id: String) -> String? {
    let message = userMessages[id]
    userMessages[id] = nil
    if let message { lastSays[id] = message }
    return message
  }

  func revive(_ id: String) {
    if let index = survival.index(forKey: id) {
      survival.remove(at: index)
    }
    survival[id] = Survival()
    lastSurvivalAt[id] = nil
    agent(id)?.state.revive()
  }

  func isDead(_ id: String) -> Bool {
    if prefs.survivalEnabled { return survival[id]?.isDead ?? false }
    return agent(id)?.state.dead ?? false
  }

  // MARK: - Tick de render (30fps): fisica + comandos pendientes + supervivencia + bubbles

  func tickVisuals() {
    let now = Date()
    for agent in agents {
      if draggingIds.contains(agent.id) { continue }
      if !agent.state.dead {
        agent.state.tick()
      }
    }
    applyPendingCommands()
    tickSurvival(now: now)
    expireLastSays(now: now)
    projectRemotePeers()
    frameCounter += 1
  }

  private func applyPendingCommands() {
    for (id, move) in pendingMoves {
      guard let agent = agent(id), !draggingIds.contains(id) else { continue }
      agent.state.startMoving(x: move.x, y: move.y, run: move.run)
      pendingMoves[id] = nil
    }
    for (id, animation) in pendingAnimations {
      guard let agent = agent(id), !draggingIds.contains(id) else { continue }
      agent.state.setAnimation(animation)
      pendingAnimations[id] = nil
    }
    for (id, face) in pendingFaces {
      guard let agent = agent(id) else { continue }
      agent.state.setFace(eyes: face["eyes"], mouth: face["mouth"])
      pendingFaces[id] = nil
    }
  }

  private func tickSurvival(now: Date) {
    guard prefs.survivalEnabled else {
      if !survival.isEmpty {
        for agent in agents { agent.state.revive() }
        survival = [:]
      }
      return
    }
    for agent in agents {
      guard !agent.state.dead else { continue }
      let last = lastSurvivalAt[agent.id] ?? now
      guard now.timeIntervalSince(last) >= Survival.pollInterval else { continue }
      var stats = survival[agent.id] ?? Survival()
      stats.tick()
      lastSurvivalAt[agent.id] = now
      survival[agent.id] = stats
      if stats.isDead {
        agent.state.dead = true
      }
    }
  }

  private func expireLastSays(now: Date) {
    for id in lastSays.keys {
      guard let at = lastSayAt[id], now.timeIntervalSince(at) > sayDuration else { continue }
      lastSays[id] = nil
      lastSayAt[id] = nil
    }
  }

  // Los remotos vienen en coordenadas de otras pantallas; los proyectamos al lienzo local
  // (escalado proporcional) para que se vean como fantasmas semi-transparentes en la escena.
  private func projectRemotePeers() {
    let refSize = CGSize(width: 1440, height: 900)
    let scaleW = screenSize.width / refSize.width
    let scaleH = (screenSize.height - floorY) / refSize.height
    for i in remotePeers.indices {
      var peer = remotePeers[i]
      let projected = CGPoint(x: min(max(peer.x * scaleW, 8), screenSize.width - 8),
                              y: min(max(peer.y * scaleH, 8), screenSize.height - 8))
      if projected.x != peer.x || projected.y != peer.y {
        peer.x = projected.x
        peer.y = projected.y
        remotePeers[i] = peer
      }
    }
  }

  // MARK: - Comandos desde la UI (drag, chat)

  func dragAgent(_ id: String, to point: CGPoint) {
    guard let agent = agent(id) else { return }
    let clamped = CGPoint(x: min(max(point.x, 0), screenSize.width),
                          y: min(max(point.y, 0), floorY))
    agent.state.dragTo(x: clamped.x, y: clamped.y)
    pendingMoves[id] = (x: clamped.x, y: clamped.y, run: false)
  }

  func setDragging(_ id: String, _ active: Bool) {
    if active {
      draggingIds.insert(id)
    } else {
      draggingIds.remove(id)
      pendingMoves[id] = nil   // el drop ya aplico dragTo directo
    }
  }

  func sendChatMessage(_ text: String, to id: String?, group: Bool) {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return }
    if let id {
      let queued = (userMessages[id].map { $0 + "\n" } ?? "") + trimmed
      userMessages[id] = queued
      chatLog.append((id: "user", text: trimmed))
    } else if group {
      for agent in agents {
        let queued = (userMessages[agent.id].map { $0 + "\n" } ?? "") + trimmed
        userMessages[agent.id] = queued
        lastSays[agent.id] = trimmed
        lastSayAt[agent.id] = Date()
      }
      chatLog.append((id: "user", text: trimmed))
    }
    loop.wakeNow()
  }

  // MARK: - Crear / editar personaje

  func createCharacter(_ cfg: CharacterConfig) {
    upsert(cfg)
  }

  func updateCharacter(_ cfg: CharacterConfig) {
    upsert(cfg)
  }

  private func upsert(_ cfg: CharacterConfig) {
    if let i = prefs.characters.firstIndex(where: { $0.id == cfg.id }) {
      prefs.characters[i] = cfg
    } else {
      prefs.characters.append(cfg)
    }
    if !prefs.enabledIds.contains(cfg.id) { prefs.enabledIds.append(cfg.id) }
    persistAndRebuild()
  }

  // Guardar settings: aplica prefs, reconstruye agentes, reinicia peer/loop si hace falta.
  func applySettings(_ newPrefs: AppPrefs) {
    prefs = newPrefs
    prefs.save()
    agents = World.buildAgents(prefs: prefs, screenSize: screenSize, floorY: floorY)
    provider = World.providerPrefs(from: prefs)
    for id in agents.map(\.id) where survival[id] == nil { survival[id] = Survival() }

    // Reiniciamos el peer sync con el host nuevo (o el mismo).
    peerClient.stop()
    peerClient.setHost(prefs.peerHost)
    peerClient.start()

    // Prefs de IA cambiaron: recrear el loop de decision si ya estaba creado.
    if _loop != nil {
      loop.stop()
      _loop = nil
      _loop = makeLoop()
      loop.start()
    }
  }

  func persistAndRebuild() {
    applySettings(prefs)
  }

  // MARK: - Helpers

  func agent(_ id: String) -> VisibleAgent? {
    agents.first { $0.id == id }
  }

  func displayName(for id: String) -> String {
    if id == "user" || id == "todos" { return "Tu" }
    return agent(id)?.displayName
      ?? prefs.characters.first { $0.id == id }?.displayName
      ?? remotePeers.first { $0.id == id }?.displayName
      ?? id
  }

  func lastSay(for id: String) -> String? { lastSays[id] }

  private func frameKindString(_ kind: Any) -> String {
    if let s = kind as? String { return s }
    return String(describing: kind)
  }

  // Payload que subimos al peerServer: {screenWidth, peers:[...]}
  private func peersNetworkPayload() -> [[String: Any]] {
    agents.map { agent in
      let pos = agent.state.position
      return [
        "id": agent.id,
        "displayName": agent.displayName,
        "device": "iphone",
        "x": pos.x,
        "y": pos.y,
        "lastSay": lastSays[agent.id] ?? "",
      ]
    }
  }

  // MARK: - ProviderPrefs

  static func providerPrefs(from prefs: AppPrefs) -> ProviderPrefs {
    var perCharacter: [String: (kind: ProviderKind, apiKey: String)] = [:]
    for cfg in prefs.characters {
      guard let providerString = cfg.provider,
            let kind = ProviderKind(rawValue: providerString) else { continue }
      perCharacter[cfg.id] = (kind: kind, apiKey: cfg.apiKey ?? "")
    }
    return ProviderPrefs(
      sharedKind: ProviderKind(rawValue: prefs.sharedProvider) ?? ProviderKind.gemini,
      sharedApiKey: prefs.sharedApiKey,
      perCharacter: perCharacter
    )
  }
}