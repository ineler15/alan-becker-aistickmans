//
//  CharacterState.swift
//  StickmanAI
//
//  Port del CharacterState.kt de Android (com.stickmanai.android.overlay). Maquina de estados
//  "physics-lite" con la misma cadena de prioridades que AIBehavior.java del desktop
//  (dragged > custom animation > eat/drink > sleeping > falling > climb > moving > loop emotion
//  > auto-sleep > idle/wander). Corre en el tick principal (~40ms).
//

import Foundation
import Combine
import CoreGraphics

@MainActor
public final class CharacterState: ObservableObject {

    /// Estado de animacion que el pose provider (PoseLibrary) convierte en angulos.
    public enum FrameKind: Equatable {
        case stand(frame: Int)
        case sit
        case walk(frame: Int)
        case run(frame: Int)
        case fall(frame: Int)
        case pinch(frame: Int)
        case bounce(frame: Int)
        case trip(frame: Int)
        case angry(frame: Int)
        case climb(frame: Int)
        case sleep(frame: Int)
        case tired(frame: Int)
        case chew(frame: Int)
        case drink(frame: Int)
        case custom(angles: [String: CGFloat])
    }

    /// Un frame de una animacion custom escrita por la IA: angulos por nombre amigable
    /// (torso/leg1/leg1Shin/leg2/leg2Shin/arm1/arm2) que PoseLibrary.customPose convierte a caminos
    /// + deltas de rest. Un hueso omitido conserva el valor del keyframe anterior (o el rest si
    /// ninguno lo seteó) en vez de volver a rest cada frame.
    public struct Keyframe {
        public let angles: [String: CGFloat]
        public let holdMs: Int64
        public let eyes: String?
        public let mouth: String?

        public init(angles: [String: CGFloat], holdMs: Int64, eyes: String? = nil, mouth: String? = nil) {
            self.angles = angles
            self.holdMs = holdMs
            self.eyes = eyes
            self.mouth = mouth
        }
    }

    // Velocidades/frame-ticks escalados para el tick mas rapido (40->25 = 0.625x) para que la
    // cadencia real de walk/run/fall/climb se mantenga igual que antes.
    public static let TICK_MS: Int64 = 25
    public static let WALK_SPEED: CGFloat = 2
    public static let RUN_SPEED: CGFloat = 4
    public static let WALK_FRAME_TICKS = 6
    public static let RUN_FRAME_TICKS = 3
    public static let FALL_SPEED: CGFloat = 4
    public static let FALL_FRAME_TICKS = 5
    public static let FALL_TIMEOUT_MS: Int64 = 4000
    public static let SAY_DURATION_MIN_MS: Int64 = 8000
    public static let SAY_DURATION_PER_CHAR_MS: Int64 = 90
    public static let CLIMB_SPEED: CGFloat = 2
    public static let CLIMB_FRAME_TICKS = 6
    fileprivate static let EDGE_MARGIN: CGFloat = 4
    // Cansancio: a dormir forzado tras estar despierto mucho, o con umbral mas corto si es de noche.
    public static let AWAKE_MS_BEFORE_SLEEP: Int64 = 20 * 60 * 1000
    public static let AWAKE_MS_BEFORE_SLEEP_AT_NIGHT: Int64 = 10 * 60 * 1000
    public static let SLEEP_DURATION_MS: Int64 = 5 * 60 * 1000
    public static let NIGHT_START_HOUR = 22
    public static let NIGHT_END_HOUR = 7
    public static let MAX_CUSTOM_KEYFRAMES = 12
    public static let MIN_KEYFRAME_HOLD_MS: Int64 = 100
    public static let MAX_KEYFRAME_HOLD_MS: Int64 = 3000
    public static let DEFAULT_KEYFRAME_HOLD_MS: Int64 = 400
    // Wander autonomo: si nada (decision de IA o drag) movio al personaje hace rato, camina solo.
    public static let IDLE_WALK_TIMEOUT_MS: Int64 = 6000
    // Duracion de los gestos de comer/beber (el prop de comida se encoge en la misma ventana).
    public static let EAT_DURATION_MS: Int64 = 5000
    public static let DRINK_DURATION_MS: Int64 = 4200
    // Mismo vocabulario que FaceRenderer. Ejes independientes (no una "emocion" unica): la IA puede
    // mezclar cualquier par de ojos/boca.
    public static let EYE_STYLES = ["normal", "wide", "angry", "heart"]
    public static let MOUTH_STYLES = ["neutral", "smile", "frown", "open", "angry"]

    private let screenWidth: CGFloat
    private let screenHeight: CGFloat
    private let floorY: CGFloat

    @Published public private(set) var x: CGFloat
    @Published public private(set) var y: CGFloat
    @Published public private(set) var lookRight = true

    /// Posicion del personaje como punto (conveniencia; equivalente a (x, y)).
    public var position: CGPoint { CGPoint(x: x, y: y) }

    // Muerto (sistema de supervivencia, hp 0): queda acostado, sin moverse y sin IA; solo un
    // mensaje del usuario revive (Prefs.stats es la fuente de verdad persistida).
    @Published public private(set) var dead = false

    /// Ultimo FrameKind devuelto por tick() - la vista lo usa para elegir pose + transform.
    @Published public private(set) var frameKind: FrameKind = .stand(frame: 0)

    // Expresion facial, independiente del pose del cuerpo (puede caminar Y estar feliz).
    @Published public private(set) var eyeStyle = "normal"
    @Published public private(set) var mouthStyle = "neutral"

    /// Frame actual (para poses procedurales). Se avanza en casi todos los estados.
    @Published public private(set) var frame = 0

    @Published public private(set) var sleeping = false
    @Published public private(set) var speechText: String?

    public var beingDragged = false
    public private(set) var loopEmotion: String? // "happy" (bounce), "sit", "scared"/"trip", etc.
    public private(set) var sayUntil: Int64 = 0
    public private(set) var climbing = false
    public private(set) var climbSide = 0 // -1 borde izquierdo, 1 borde derecho
    private var fallStartedAt: Int64 = 0
    private var falling = false
    private var moving = false
    private var running = false
    private var moveTargetX: CGFloat = 0
    private var climbTargetY: CGFloat = 0
    private var awakeSinceMs: Int64
    private var sleepStartedAt: Int64 = 0
    private var eating = false
    private var drinking = false
    private var eatingUntil: Int64 = 0
    private var drinkingUntil: Int64 = 0
    private var customAnimation: [Keyframe]?
    private var customIndex = 0
    private var customKeyframeStartedAt: Int64 = 0
    private var customAccumulatedAngles: [String: CGFloat] = [:]
    private var frameCounter = 0
    private var lastActiveAt: Int64

    public init(screenWidth: CGFloat, screenHeight: CGFloat, floorY: CGFloat) {
        self.screenWidth = screenWidth
        self.screenHeight = screenHeight
        self.floorY = floorY
        x = screenWidth / 2
        y = floorY
        let now = CharacterState.nowMs
        awakeSinceMs = now
        lastActiveAt = now
    }

    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    public func startMoving(targetX: CGFloat, run: Bool) {
        lastActiveAt = Self.nowMs
        beingDragged = false
        falling = false
        loopEmotion = nil
        customAnimation = nil
        eating = false
        drinking = false
        moving = true
        running = run
        moveTargetX = min(max(targetX, 0), screenWidth)
        lookRight = moveTargetX >= x
    }

    public func randomTarget(run: Bool = false) {
        startMoving(targetX: CGFloat.random(in: 0...screenWidth), run: run)
    }

    public func startFalling() {
        lastActiveAt = Self.nowMs
        moving = false
        climbing = false
        loopEmotion = nil
        customAnimation = nil
        eating = false
        drinking = false
        falling = true
        fallStartedAt = Self.nowMs
    }

    /// Trepar pared: llegar a un borde de pantalla mientras caminas lo escala en vez de frenar
    /// (como el ClimbWall de Shimeji clasico).
    private func startClimbing(side: Int) {
        lastActiveAt = Self.nowMs
        climbing = true
        climbSide = side
        moving = false
        loopEmotion = nil
        eating = false
        drinking = false
        frame = 0
        frameCounter = 0
        let lo = Int(screenHeight * 0.1)
        let hi = Int(screenHeight * 0.5)
        climbTargetY = CGFloat(Int.random(in: lo...max(lo, hi)))
    }

    public func startCustomAnimation(keyframes: [Keyframe]) {
        if keyframes.isEmpty { return }
        lastActiveAt = Self.nowMs
        beingDragged = false
        falling = false
        climbing = false
        moving = false
        loopEmotion = nil
        eating = false
        drinking = false
        if sleeping { wakeUp() }
        customAnimation = Array(keyframes.prefix(Self.MAX_CUSTOM_KEYFRAMES)).map { kf in
            Keyframe(
                angles: kf.angles,
                holdMs: min(max(kf.holdMs, Self.MIN_KEYFRAME_HOLD_MS), Self.MAX_KEYFRAME_HOLD_MS),
                eyes: kf.eyes.flatMap { Self.EYE_STYLES.contains($0) ? $0 : nil },
                mouth: kf.mouth.flatMap { Self.MOUTH_STYLES.contains($0) ? $0 : nil }
            )
        }
        customIndex = 0
        customKeyframeStartedAt = Self.nowMs
        customAccumulatedAngles = [:]
        // Un keyframe sin ojos/boca propios conserva lo que haya seteadao el anterior (o el
        // eyeStyle/mouthStyle actuales si ninguno en la secuencia seteó uno) - solo se aplica el
        // propio cuando de verdad lo tiene.
        if let e = customAnimation?.first?.eyes { eyeStyle = e }
        if let m = customAnimation?.first?.mouth { mouthStyle = m }
    }

    /// Cualquiera de los dos params puede ser nil/invalido para dejar ese eje intacto - ej.
    /// setFace(eyes: nil, mouth: "smile") cambia solo la boca.
    public func setFace(eyes: String?, mouth: String?) {
        lastActiveAt = Self.nowMs
        if let e = eyes, Self.EYE_STYLES.contains(e) { eyeStyle = e }
        if let m = mouth, Self.MOUTH_STYLES.contains(m) { mouthStyle = m }
    }

    public func setEmotion(state: String?) {
        lastActiveAt = Self.nowMs
        moving = false
        falling = false
        customAnimation = nil
        eating = false
        drinking = false
        if state == "sleep" {
            startSleeping()
            return
        }
        loopEmotion = state
        frame = 0
        frameCounter = 0
    }

    /// Muerto por pelea o por hambre/sed (hp <= 0) - ver Prefs.stats en Android / survival.js en PC.
    public func kill() {
        dead = true
        beingDragged = false
        moving = false
        falling = false
        climbing = false
        loopEmotion = nil
        customAnimation = nil
        eating = false
        drinking = false
        speechText = nil
    }

    public func revive() {
        dead = false
        awakeSinceMs = Self.nowMs
    }

    /// Gestos de comer/beber por tiempo: deja de caminar/idlear y mastica o traga durante toda la
    /// ventana de la comida (el prop se encoge en paralelo, igual que en PC/Android).
    public func startEat() {
        lastActiveAt = Self.nowMs
        beingDragged = false
        falling = false
        moving = false
        climbing = false
        customAnimation = nil
        drinking = false
        if sleeping { wakeUp() }
        eating = true
        eatingUntil = Self.nowMs + Self.EAT_DURATION_MS
        frame = 0
        frameCounter = 0
    }

    public func startDrink() {
        lastActiveAt = Self.nowMs
        beingDragged = false
        falling = false
        moving = false
        climbing = false
        customAnimation = nil
        eating = false
        if sleeping { wakeUp() }
        drinking = true
        drinkingUntil = Self.nowMs + Self.DRINK_DURATION_MS
        frame = 0
        frameCounter = 0
    }

    private func isNightNow() -> Bool {
        let hour = Calendar.current.component(.hour, from: Date())
        return hour >= Self.NIGHT_START_HOUR || hour < Self.NIGHT_END_HOUR
    }

    private func shouldForceSleep() -> Bool {
        let awakeMs = Self.nowMs - awakeSinceMs
        let threshold = isNightNow() ? Self.AWAKE_MS_BEFORE_SLEEP_AT_NIGHT : Self.AWAKE_MS_BEFORE_SLEEP
        return awakeMs > threshold
    }

    private func startSleeping() {
        sleeping = true
        sleepStartedAt = Self.nowMs
        beingDragged = false
        moving = false
        falling = false
        climbing = false
        loopEmotion = nil
        frame = 0
        frameCounter = 0
    }

    public func wakeUp() {
        sleeping = false
        awakeSinceMs = Self.nowMs
    }

    public func say(text: String) {
        speechText = text
        sayUntil = Self.nowMs + max(Self.SAY_DURATION_MIN_MS, Int64(text.count) * Self.SAY_DURATION_PER_CHAR_MS)
    }

    /// Llamado cada tick mientras esta siendo arrastrado (sigue el dedo/cursor).
    public func dragTo(px: CGFloat, py: CGFloat) {
        lastActiveAt = Self.nowMs
        // Clamp al piso/los bordes: un py sin clamp mas abajo del floorY haria que el branch de
        // caida del proximo tick (`y >= floorY`) dispare al instante, apareciendo en el piso sin
        // animacion de caida (bug real encontrado en el port de PC; ver memoria pc_engine_replacement).
        x = min(max(px, 0), screenWidth)
        y = min(py, floorY)
        frameCounter += 1
        if frameCounter >= Self.WALK_FRAME_TICKS {
            frameCounter = 0
            frame += 1
        }
    }

    public func onRelease() {
        beingDragged = false
        startFalling()
    }

    /// Avanza la fisica un tick y devuelve el FrameKind a mostrar. Publica `frameKind` para que la
    /// vista SwiftUI se redibuje.
    @discardableResult
    public func tick() -> FrameKind {
        let kind = advance()
        frameKind = kind
        return kind
    }

    private func advance() -> FrameKind {
        if Self.nowMs > sayUntil { speechText = nil }

        // Muerto gana sobre todo: queda acostado (reusa el pose de dormido), no vaga solo y la IA
        // saltea sus turnos.
        if dead {
            speechText = nil
            moving = false
            falling = false
            climbing = false
            customAnimation = nil
            return .sleep(frame: 0)
        }

        if beingDragged {
            if sleeping { wakeUp() }
            customAnimation = nil
            return .pinch(frame: frame)
        }

        if let keyframes = customAnimation {
            let elapsed = Self.nowMs - customKeyframeStartedAt
            if elapsed > keyframes[customIndex].holdMs {
                customAccumulatedAngles.merge(keyframes[customIndex].angles) { _, new in new }
                customIndex += 1
                customKeyframeStartedAt = Self.nowMs
                if customIndex < keyframes.count {
                    if let e = keyframes[customIndex].eyes { eyeStyle = e }
                    if let m = keyframes[customIndex].mouth { mouthStyle = m }
                }
            }
            if customIndex >= keyframes.count {
                customAnimation = nil
            } else {
                return .custom(angles: customAccumulatedAngles.merging(keyframes[customIndex].angles) { _, new in new })
            }
        }

        if eating {
            if Self.nowMs > eatingUntil {
                eating = false
            } else {
                frameCounter += 1
                if frameCounter >= Self.WALK_FRAME_TICKS {
                    frameCounter = 0
                    frame += 1
                }
                return .chew(frame: frame)
            }
        }
        if drinking {
            if Self.nowMs > drinkingUntil {
                drinking = false
            } else {
                frameCounter += 1
                if frameCounter >= Self.WALK_FRAME_TICKS {
                    frameCounter = 0
                    frame += 1
                }
                return .drink(frame: frame)
            }
        }

        if sleeping {
            if Self.nowMs - sleepStartedAt > Self.SLEEP_DURATION_MS {
                wakeUp()
            } else {
                frameCounter += 1
                if frameCounter >= Self.WALK_FRAME_TICKS {
                    frameCounter = 0
                    frame += 1
                }
                return .sleep(frame: frame)
            }
        }

        if falling {
            if Self.nowMs - fallStartedAt > Self.FALL_TIMEOUT_MS || y >= floorY {
                falling = false
                y = floorY
                return .stand(frame: 0)
            }
            y = min(y + Self.FALL_SPEED, floorY)
            frameCounter += 1
            if frameCounter >= Self.FALL_FRAME_TICKS {
                frameCounter = 0
                frame += 1
            }
            return .fall(frame: frame)
        }

        if climbing {
            if abs(climbTargetY - y) <= Self.CLIMB_SPEED {
                y = climbTargetY
                startFalling()
                return .fall(frame: 0)
            }
            y += climbTargetY > y ? Self.CLIMB_SPEED : -Self.CLIMB_SPEED
            frameCounter += 1
            if frameCounter >= Self.CLIMB_FRAME_TICKS {
                frameCounter = 0
                frame += 1
            }
            return .climb(frame: frame)
        }

        if moving {
            let speed = running ? Self.RUN_SPEED : Self.WALK_SPEED
            let ticksPerFrame = running ? Self.RUN_FRAME_TICKS : Self.WALK_FRAME_TICKS
            if abs(moveTargetX - x) <= speed {
                x = moveTargetX
                moving = false
                if x <= Self.EDGE_MARGIN || x >= screenWidth - Self.EDGE_MARGIN {
                    startClimbing(side: x <= Self.EDGE_MARGIN ? -1 : 1)
                    return .climb(frame: 0)
                }
                return .stand(frame: 0)
            }
            x += moveTargetX > x ? speed : -speed
            frameCounter += 1
            if frameCounter >= ticksPerFrame {
                frameCounter = 0
                frame += 1
            }
            return running ? .run(frame: frame) : .walk(frame: frame)
        }

        if let emotion = loopEmotion {
            if emotion == "sit" { return .sit }
            frameCounter += 1
            if frameCounter >= Self.WALK_FRAME_TICKS {
                frameCounter = 0
                frame += 1
            }
            switch emotion {
            case "happy", "jump": return .bounce(frame: frame)
            case "angry": return .angry(frame: frame)
            case "tired": return .tired(frame: frame)
            default: return .trip(frame: frame)
            }
        }

        if shouldForceSleep() {
            startSleeping()
            return .sleep(frame: 0)
        }

        // Wander autonomo: si nada (decision de IA o drag) movio al personaje en un rato, camina
        // solo en vez de quedarse parado para siempre (un personaje sin key de API nunca recibe
        // decision de IA - ver IDLE_WALK_TIMEOUT_MS).
        if Self.nowMs - lastActiveAt > Self.IDLE_WALK_TIMEOUT_MS {
            randomTarget()
            frame = 0
            frameCounter = 0
            return .walk(frame: 0)
        }

        // Idle sway en vez de un frame congelado (ver PoseLibrary.standPose). El frame sobrante de
        // otro estado no importa para un sway continuo.
        frameCounter += 1
        if frameCounter >= Self.WALK_FRAME_TICKS {
            frameCounter = 0
            frame += 1
        }
        return .stand(frame: frame)
    }
}