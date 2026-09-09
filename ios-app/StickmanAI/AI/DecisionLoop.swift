import Foundation
import CoreGraphics

// Contrato que conecta el loop con el mundo de la escena iOS. Es el "World" de la arquitectura;
// el Motor/los componentes lo implementan y DecisionLoop solo depende de este protocol.
protocol WorldProtocol: AnyObject {
    func characterIDs() -> [String]
    func setSay(_ text: String, for id: String)
    func agentConfig(_ id: String) -> (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int)?
    func peersSnapshot() -> [[String: Any]]
    func localStatus(_ id: String) -> [String: CGFloat]
    func survivalStats(_ id: String) -> [String: Any]?
    func applySurvival(_ stats: [String: Any], for id: String)
    func agentState(_ id: String) -> CharacterAgentState
    func kitchenPosition() -> [String: CGFloat]?
    func consumeUserMessage(_ id: String) -> String?
    func revive(_ id: String)
    func isDead(_ id: String) -> Bool
}

// Mutaciones pedidas por una decision de IA. El World lo lee via pendingState(for:) y las aplica con
// su propio motor/pose. position/frame/frame los escribe el Engine del mundo; aca solo se informan
// a modo de estado.
struct CharacterAgentState {
    var position: CGPoint? = nil
    var frameKind: String = "idle"
    var frame: Int = 0
    var pendingMove: (x: Double, y: Double, run: Bool)? = nil
    var pendingAnimation: String? = nil
    var pendingFace: [String: String]? = nil
    var pendingCustomKeyframes: [[String: Any]]? = nil

    mutating func clearPending() {
        pendingMove = nil
        pendingAnimation = nil
        pendingFace = nil
        pendingCustomKeyframes = nil
    }
}

// Bucle principal de decision por personaje - espejo de src/loop/agentLoop.js (PC, la fuente
// original) y de OverlayService aiLoop (Android). Decisiones cada TICK, escalonadas por personaje
// para repartir la carga de solicitudes, con el guard de repeticion, el sistema de dormir
// (reloj + cansancio), el fallback silencioso ante errores y la memoria (historial/notas/
// personalidad/contexto). Por contrato el loop NO setea estado del personaje directo en el mundo;
// las herramientas se aplican via pendingXXX (que el World mergea en agentState) y el globo de
// dialogo se avisa con setSay.
@MainActor
final class DecisionLoop {

    private struct RepetitionBookkeeping {
        var lastToolAlias = ""          // tool con la que el personaje "actuo" por ultima vez
        var lastToolRepeatStreak = 0
        var turnsSinceSay = 1           // inicializado en 1 para que la primera decision hable
    }

    private struct SleepBookkeeping {
        var awakeSince: Date
        var sleepStartedAt: Date?
    }

    // Configuracion del tick - mismos numeros de agentLoop.js / CharacterState.kt.
    static let tickInterval: TimeInterval = 12          // TICK_INTERVAL_SECONDS en PC
    static let silentTurnLimit = 3                      // SILENT_TURN_LIMIT
    static let sleepDuration: TimeInterval = 5 * 60     // SLEEP_DURATION_SECONDS
    static let awakeDayLimit: TimeInterval = 20 * 60    // despiertan de dia: 20 min
    static let awakeNightLimit: TimeInterval = 10 * 60  // y 10 min si es de noche
    static let nightStartHour = 22                      // noche entre 22:00 y 07:00 (reloj local)

    // Limites del guard de repeticion (agentLoop.js): la misma tool se permite N veces seguidas y
    // a la siguiente se la convierte en walk_to (hacia un peer o un punto al azar).
    static let repeatLimitWait = 2
    static let repeatLimitGeneral = 3

    // Distancias de "cerca de" en porcentaje del ancho de escena (el 100% es el ancho, asi que
    // 25% es ~100px a 400pt y 37% es ~150pt) - mismas referencias que usan fight/eat/drink/wash en
    // las prompts, para que el personaje diga "esta lejos" como corresponde.
    static let fightDistancePercent = 25.0
    static let kitchenDistancePercent = 37.0

    private let world: WorldProtocol
    private var prefs: ProviderPrefs
    private let history: HistoryStore
    private let notes: NotesStore
    private let personality: PersonalityStore

    private var repetition: [String: RepetitionBookkeeping] = [:]
    private var timers: [String: SleepBookkeeping] = [:]
    private var nextTickAt: [String: Date] = [:]
    private var startedAt = Date()

    // Mutaciones pendientes de la ultima decision de cada personaje.
    private var pendingMove: [String: (x: Double, y: Double, run: Bool)] = [:]
    private var pendingAnimation: [String: String] = [:]
    private var pendingFace: [String: [String: String]] = [:]
    private var pendingCustomKeyframes: [String: [[String: Any]]] = [:]

    private var loopTask: Task<Void, Never>? = nil
    private(set) var isRunning = false

    init(world: WorldProtocol, prefs: ProviderPrefs = ProviderPrefs()) {
        self.world = world
        self.prefs = prefs
        self.history = .shared
        self.notes = .shared
        self.personality = .shared
    }

    // La Shell puede reinyectar la configuracion de providers en caliente (pantalla de ajustes).
    func refreshPrefs(_ newPrefs: ProviderPrefs) {
        prefs = newPrefs
    }

    // Si esta durmiendo: el Motor puede mostrar la pose dormido y quieto.
    func isAsleep(_ id: String) -> Bool {
        timers[id]?.sleepStartedAt != nil
    }

    // La parte "pendiente" del estado (mutaciones de la ultima decision) que el World mergea en
    // agentState().
    func pendingState(for id: String) -> CharacterAgentState {
        CharacterAgentState(
            pendingMove: pendingMove[id],
            pendingAnimation: pendingAnimation[id],
            pendingFace: pendingFace[id],
            pendingCustomKeyframes: pendingCustomKeyframes[id]
        )
    }

    // MARK: - ciclo de vida

    func start() {
        guard !isRunning else { return }
        isRunning = true
        startedAt = Date()
        loopTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.tick()
                try? await Task.sleep(nanoseconds: UInt64(Self.tickInterval * 1_000_000_000))
            }
        }
    }

    func stop() {
        isRunning = false
        loopTask?.cancel()
        loopTask = nil
    }

    // MARK: - tick

    private func tick() async {
        let now = Date()
        for (index, id) in world.characterIDs().enumerated() {
            var due = nextTickAt[id] ?? now
            if nextTickAt[id] == nil {
                // primer turno escalonado para no disparar todas las solicitudes a la vez
                due = startedAt.addingTimeInterval(Double(index) * 0.2)
            }
            guard now >= due else { continue }
            nextTickAt[id] = now.addingTimeInterval(Self.tickInterval)
            await handleCharacter(id, now: now)
        }
    }

    private func handleCharacter(_ id: String, now: Date) async {
        guard let config = world.agentConfig(id) else { return }

        // 1) mensaje del usuario: prioridad y despierta a cualquiera.
        var userMessage = world.consumeUserMessage(id)
        if let msg = userMessage {
            history.add(id, ["tool": "userMessage", "text": msg])
            if isAsleep(id) {
                wake(id)
                return // responde el proximo turno, no se pierde el mensaje
            }
        }

        // 2) reloj de despertar / dormir por cansancio.
        let bookkeeping = timers[id] ?? SleepBookkeeping(awakeSince: now)
        if let sleepStartedAt = bookkeeping.sleepStartedAt {
            if now.timeIntervalSince(sleepStartedAt) < Self.sleepDuration {
                return // sigue durmiendo, no consume mensajes ni decide
            }
            wake(id) // se desperto solo; timers queda con awakeSince fresco
        } else {
            let night = isNight(now)
            let limit = night ? Self.awakeNightLimit : Self.awakeDayLimit
            if now.timeIntervalSince(bookkeeping.awakeSince) >= limit {
                timers[id] = SleepBookkeeping(awakeSince: bookkeeping.awakeSince, sleepStartedAt: now)
                pendingAnimation[id] = "sleep"
                history.add(id, ["tool": "sleep"])
                return
            }
            // siempre persistir el reloj para que el acumulado no se pierda entre ticks
            timers[id] = bookkeeping
        }

        // 3) modo "ia": generar el contexto propio una sola vez (solo si falta).
        if config.contextMode == "ia" {
            ensureAiContext(id, config: config)
        }

        // 4) decidir y ejecutar la accion.
        let outcome = await decideNextAction(id: id, config: config, userMessage: &userMessage)
        switch outcome {
        case .say(let text):
            world.setSay(text, for: id)
        case .silent, .failed:
            break // el fallo ya quedo en el historial; el mensaje del usuario queda para el proximo turno
        }
    }

    private func wake(_ id: String) {
        let now = Date()
        timers[id] = SleepBookkeeping(awakeSince: now, sleepStartedAt: nil)
        pendingAnimation[id] = "idle" // al despertar se para - el proximo turno decide con calma
    }

    // MARK: - decision

    private enum DecideOutcome {
        case say(String)   // hubo globo de dialogo (frase del propio personaje o forzada)
        case silent        // accion sin globo (walk_to, pose, etc.)
        case failed        // no se pudo decidir; si habia userMessage queda para el proximo turno
    }

    private func decideNextAction(
        id: String,
        config: (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int),
        userMessage: inout String?
    ) async -> DecideOutcome {
        guard let endpoint = prefs.endpoint(for: id) else {
            return .silent // sin API key el personaje queda en standby, no roto
        }

        let snippet = buildContextBundle(id: id, config: config, userMessage: userMessage)
        let systemPrompt = buildPersonalityHeader(id: id, config: config)

        do {
            let client = AIClient(apiKey: prefs.apiKey(for: id))
            let (toolName, args) = try await client.decide(endpoint: endpoint, systemPrompt: systemPrompt, contextJSON: snippet.contextJSON())
            userMessage = nil // el mensaje se respondio con exito

            var bookkeeping = repetition[id] ?? RepetitionBookkeeping()
            let guarded = applyRepetitionGuard(id: id, toolName: toolName, args: args, bookkeeping: &bookkeeping)

            let speech = applyAction(id: id, toolName: guarded.tool, args: guarded.args, config: config)

            if guarded.tool == "say" {
                bookkeeping.turnsSinceSay = 0
            } else if bookkeeping.turnsSinceSay < Int.max {
                bookkeeping.turnsSinceSay += 1
            }
            repetition[id] = bookkeeping

            if bookkeeping.turnsSinceSay >= Self.silentTurnLimit {
                bookkeeping.turnsSinceSay = 0
                repetition[id] = bookkeeping
                return .say("Oye... ¿estas ahi?")
            }

            return speech.isEmpty ? .silent : .say(speech)
        } catch {
            history.add(id, ["tool": "error", "error": error.localizedDescription ?? "\(error)"])
            return .failed
        }
    }

    // MARK: - contexto de cada turno

    private func buildContextBundle(
        id: String,
        config: (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int),
        userMessage: String?
    ) -> ContextBundle {
        // Modo "semilla": el usuario escribio el contexto a mano en el chat. Modo "ia": contexto
        // generado una vez por el propio modelo (o por el usuario con semilla). El contexto del
        // prompt en agentLoop.js arma este bundle + el bloque de personalidad en el systemPrompt.
        let extraContext: String
        switch config.contextMode {
        case "ia":
            extraContext = personality.loadContext(id)
        case "matrix":
            extraContext = config.userContext
        default:
            extraContext = config.userContext
        }

        return ContextBundle(
            characterId: id,
            displayName: config.displayName,
            background: ProviderPrefs.loreFor(id),
            genderLine: config.gender.map { "Tu genero es \($0)." } ?? "",
            partnerLine: config.partnerDisplayName.map { "Esta es tu pareja: \($0). " + Self.affectionPhrase(displayName: $0, level: config.affectionLevel) } ?? "",
            selfPersonality: personality.loadPersonality(id),
            extraContext: extraContext,
            recentHistory: history.recent(id, 5),
            memory: notes.recent(id, 8),
            status: world.localStatus(id),
            peers: world.peersSnapshot(),
            userMessage: userMessage,
            forceSay: false,
            survival: world.survivalStats(id)
        )
    }

    private func buildPersonalityHeader(
        id: String,
        config: (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int)
    ) -> String {
        var parts: [String] = [ProviderPrefs.systemPrompt]
        let canon = ProviderPrefs.loreFor(id)
        let own = personality.loadPersonality(id)
        if !canon.isEmpty || !own.isEmpty {
            parts.append("Antecedentes de tu personaje:\n\(canon)")
            if !own.isEmpty {
                parts.append("Personalidad (definida por vos):\n\(own)")
            }
        }
        if let gender = config.gender, !gender.isEmpty {
            parts.append("Tu genero es \(gender).")
        }
        if let partner = config.partnerDisplayName {
            parts.append("Esta es tu pareja: \(partner). " + Self.affectionPhrase(displayName: partner, level: config.affectionLevel))
        }
        return parts.joined(separator: "\n\n")
    }

    // Mismos tiers de agentLoop.js: un texto fijo por rango de afecto (0-100).
    static func affectionPhrase(displayName: String, level: Int) -> String {
        if level >= 80 { return "Es lo que mas te importa en el mundo." }
        if level >= 50 { return "Lo queres. Lo consideras de verdad tu pareja." }
        if level >= 25 { return "Le tenes cariño." }
        if level >= 10 { return "Lo ves con buenos ojos." }
        return "Apenas lo registras."
    }

    // MARK: - guard de repeticion

    // Si la misma tool se repite el limite permitido (wait=2, resto=3, igual que agentLoop.js),
    // se la cambia por walk_to hacia un peer de la misma escena (o un punto al azar si no hay)
    // para que no se quede en bucle.
    private func applyRepetitionGuard(
        id: String,
        toolName: String,
        args: [String: JSONValue],
        bookkeeping: inout RepetitionBookkeeping
    ) -> (tool: String, args: [String: JSONValue]) {
        guard toolName != "walk_to" && toolName != "wait" else {
            bookkeeping.lastToolAlias = toolName
            bookkeeping.lastToolRepeatStreak = 0
            return (toolName, args)
        }

        var name = toolName
        var newArgs = args
        if name == bookkeeping.lastToolAlias {
            bookkeeping.lastToolRepeatStreak += 1
        } else {
            bookkeeping.lastToolAlias = name
            bookkeeping.lastToolRepeatStreak = 1
        }
        let limit = (name == "wait") ? Self.repeatLimitWait : Self.repeatLimitGeneral
        if bookkeeping.lastToolRepeatStreak > limit {
            let peers = world.peersSnapshot().filter { ("\($0["device"] ?? "")").isEmpty }
            if let targetX = (peers.first?["xPercent"] as? Double) {
                name = "walk_to"
                newArgs = ["x": .double(targetX), "run": .bool(false)]
            } else {
                name = "walk_to"
                newArgs = ["x": .double(Double.random(in: 5...95)), "run": .bool(false)]
            }
            bookkeeping.lastToolAlias = "walk_to"
            bookkeeping.lastToolRepeatStreak = 0
        }
        return (name, newArgs)
    }

    // MARK: - ejecutar la accion elegida

    // Aplica una herramienta y devuelve el texto del globo ("" si fue una accion muda). Los limites
    // de repeticion ya los aplico el guard.
    private func applyAction(
        id: String,
        toolName: String,
        args: [String: JSONValue],
        config: (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int)
    ) -> String {
        switch toolName {
        case "walk_to":
            if let x = args["x"]?.asDouble() {
                pendingMove[id] = (x: x, y: 0, run: args["run"]?.asBool() ?? false)
            }
            return ""

        case "set_animation":
            if let state = args["state"]?.asString() {
                pendingAnimation[id] = state
                if state == "sleep" {
                    timers[id] = SleepBookkeeping(awakeSince: Date(), sleepStartedAt: Date())
                } else if state == "idle" {
                    timers[id]?.sleepStartedAt = nil
                }
            }
            return ""

        case "set_emotion":
            var face: [String: String] = [:]
            if let eyes = args["eyes"]?.asString() { face["eyes"] = eyes }
            if let mouth = args["mouth"]?.asString() { face["mouth"] = mouth }
            if !face.isEmpty { pendingFace[id] = face }
            return ""

        case "set_custom_animation":
            guard let keyframes = args["keyframes"]?.arrayValue() else { return "" }
            let frames = keyframes.compactMap { value -> [String: Any]? in
                guard case .object(let dict) = value else { return nil }
                return dict.mapValues { $0.toAny() }
            }
            if !frames.isEmpty {
                pendingCustomKeyframes[id] = Array(frames.prefix(12))
                history.add(id, ["tool": "custom_animation", "frames": frames.count])
            }
            return ""

        case "say":
            if let text = args["text"]?.asString() {
                world.setSay(text, for: id)
            }
            return ""

        case "define_personality":
            if let desc = args["description"]?.asString(), !desc.isEmpty {
                personality.setPersonality(id, desc)
                history.add(id, ["tool": "define_personality", "definition": desc])
            }
            return ""

        case "remember":
            if let note = args["note"]?.asString(), !note.isEmpty {
                notes.add(id, note)
                history.add(id, ["tool": "remember", "note": note])
            }
            return ""

        case "set_context":
            if let c = args["context"]?.asString(), !c.isEmpty {
                personality.setSelfContext(id, c)
                history.add(id, ["tool": "set_context", "context": c])
            }
            return ""

        case "fight", "eat", "drink", "wash":
            return survivalAction(id: id, toolName: toolName, args: args, config: config)

        case "wait":
            return ""

        default:
            history.add(id, ["tool": "error", "error": "unknown tool \(toolName)"])
            return ""
        }
    }

    // MARK: - sistema de vida (survival)

    private func survivalAction(
        id: String,
        toolName: String,
        args: [String: JSONValue],
        config: (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int)
    ) -> String {
        guard world.survivalStats(id) != nil else {
            return "No puedo, el sistema de vida no esta activado."
        }
        if world.isDead(id) {
            return "Me muevo por inercia..."
        }
        switch toolName {
        case "fight":
            return fight(id: id, args: args, config: config)
        case "eat", "drink", "wash":
            return kitchenDrain(id: id, toolName: toolName)
        default:
            return ""
        }
    }

    // Pelea: si el objetivo esta dentro del rango de pelea le baja hp (5-40, default 12), y si llega
    // a 0 el peer queda muerto (revivir = mensaje del usuario, igual que en Android/PC).
    private func fight(
        id: String,
        args: [String: JSONValue],
        config: (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int)
    ) -> String {
        guard let target = args["target"]?.asString(), !target.isEmpty else {
            return "No puedo pelear sin saber contra quien."
        }

        let peers = world.peersSnapshot()
        let peer = peers.first {
            ($0["id"] as? String) == target || ($0["displayName"] as? String) == target
        }
        guard let peer, let peerX = (peer["xPercent"] as? Double) else {
            return "No encuentro a \(target) cerca."
        }

        let localX = world.localStatus(id)["xPercent"] ?? 0
        guard abs(localX - peerX) <= Self.fightDistancePercent else {
            return "\(target) esta demasiado lejos para pelear - tengo que acercarme."
        }

        let strength = min(40, max(5, args["strength"]?.asInt() ?? 12))
        var targetStats = world.survivalStats(target) ?? ["hp": 100.0, "hunger": 100.0, "thirst": 100.0]
        let oldHP = (targetStats["hp"] as? Double) ?? 100
        let newHP = max(0, oldHP - Double(strength))
        targetStats["hp"] = newHP
        targetStats["dead"] = newHP <= 0
        world.applySurvival(targetStats, for: target)

        if newHP <= 0 {
            world.setSay("No puedo mas... me vencio \(config.displayName).", for: target)
        } else {
            world.setSay("¡Auch! \(config.displayName) me pego fuerte.", for: target)
        }

        pendingAnimation[id] = "angry"
        history.add(id, ["tool": "fight", "target": target, "damage": strength])
        return "Le pegue a \(peer["displayName"] as? String ?? target) (\(strength) de dano)."
    }

    // Comer/tomar agua/lavarse: recarga hambre o sed (~45%) solo estando cerca de la cocina; igual
    // que en Android (DRINK_HUNGER_REGEN etc.). El detalle de "me lave" es un texto nomas - la
    // logica de lavarse es identica a beber para sed.
    private func kitchenDrain(id: String, toolName: String) -> String {
        let kitchenX = world.kitchenPosition()?["xPercent"] ?? 50
        let localX = world.localStatus(id)["xPercent"] ?? 0
        guard abs(localX - kitchenX) <= Self.kitchenDistancePercent else {
            return "La cocina esta lejos - primero camino hasta alla."
        }

        guard var survival = world.survivalStats(id) else { return "" }
        let key = toolName == "eat" ? "hunger" : "thirst"
        let current = (survival[key] as? Double) ?? 100
        survival[key] = min(100, current + 45)
        world.applySurvival(survival, for: id)

        history.add(id, ["tool": toolName, "gain": 45])
        if toolName == "eat" {
            return "Me llego un antojo: comi algo en la cocina."
        }
        if toolName == "wash" {
            return "Me lave la cara en la cocina con agua limpia."
        }
        return "Tome agua para quitarme la sed."
    }

    // MARK: - helpers

    private func isNight(_ date: Date) -> Bool {
        let hour = Calendar.current.component(.hour, from: date)
        return hour >= Self.nightStartHour || hour < 7
    }

    // Genera el contexto del modo "ia" la primera vez: un intento one-shot para que el modelo
    // describa en una o dos lineas quien es, guardado en PersonalityStore (fallo silencioso = lo
    // reintenta el tick siguiente).
    private func ensureAiContext(
        _ id: String,
        config: (displayName: String, contextMode: String, userContext: String, gender: String?, hasFace: Bool, partnerDisplayName: String?, affectionLevel: Int)
    ) {
        guard personality.loadContext(id).isEmpty,
              let endpoint = prefs.endpoint(for: id) else { return }

        let snippet = ContextBundle(
            characterId: id,
            displayName: config.displayName,
            background: ProviderPrefs.loreFor(id),
            selfPersonality: personality.loadPersonality(id),
            extraContext: config.userContext,
            recentHistory: history.recent(id, 5),
            memory: notes.recent(id, 8),
            status: world.localStatus(id),
            peers: world.peersSnapshot(),
            userMessage: "Sos un personaje nuevo y todavia no definiste quien sos. En una o dos lineas " +
                         "y en espanol neutro escribi tu propia descripcion de personaje (tu forma de ser " +
                         "y tu historia), como si la escribieras vos mismo. Guardala con define_personality.",
            forceSay: true,
            survival: world.survivalStats(id)
        )

        Task { [weak self] in
            guard let self else { return }
            do {
                let client = AIClient(apiKey: self.prefs.apiKey(for: id))
                let result = try await client.decide(
                    endpoint: endpoint,
                    systemPrompt: self.buildPersonalityHeader(id: id, config: config),
                    contextJSON: snippet.contextJSON()
                )
                // La respuesta es una tool call; leer el texto de la que traiga.
                var generated: String? = nil
                for key in ["description", "context", "text", "note"] {
                    if let v = result.args[key]?.asString() { generated = v; break }
                }
                if let generated, !generated.isEmpty {
                    self.personality.setContext(id, generated)
                }
            } catch {
                // fallo silencioso: se reintenta el proximo tick
            }
        }
    }
}