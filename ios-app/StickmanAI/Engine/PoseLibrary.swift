//
//  PoseLibrary.swift
//  StickmanAI
//
//  Port del PoseLibrary.kt de Android (com.stickmanai.android.overlay) + la versio JS de PC
//  (renderer/poseLibrary.js). Poses procedurales como overrides de angulo sobre el pose de reposo
//  de cada personaje: cada funcion recibe el RigProfile (paths + rest) y oscila alrededor de sus
//  propios angulos de reposo, asi la misma logica sirve para cualquier personaje con topologia
//  mapeada sin importar su orientacion o el orden de hijos.
//

import Foundation
import CoreGraphics

/// Cual camino de indice-de-hijo tiene cada hueso nombrado (ver memoria android_rig_renderer).
public struct BonePathSet {
    public let leg1: BonePath
    public let leg1Shin: BonePath
    public let leg2: BonePath
    public let leg2Shin: BonePath
    public let torsoLower: BonePath
    public let arm1: BonePath
    public let arm2: BonePath

    public init(leg1: BonePath, leg1Shin: BonePath, leg2: BonePath, leg2Shin: BonePath, torsoLower: BonePath, arm1: BonePath, arm2: BonePath) {
        self.leg1 = leg1
        self.leg1Shin = leg1Shin
        self.leg2 = leg2
        self.leg2Shin = leg2Shin
        self.torsoLower = torsoLower
        self.arm1 = arm1
        self.arm2 = arm2
    }
}

/// Angulos de reposo que el propio rig tiene (de su rigs/<id>.json) para los huesos arriba.
public struct RestAngles {
    public let leg1: CGFloat
    public let leg1Shin: CGFloat
    public let leg2: CGFloat
    public let leg2Shin: CGFloat
    public let torsoLower: CGFloat
    public let arm1: CGFloat
    public let arm2: CGFloat

    public init(leg1: CGFloat, leg1Shin: CGFloat, leg2: CGFloat, leg2Shin: CGFloat, torsoLower: CGFloat, arm1: CGFloat, arm2: CGFloat) {
        self.leg1 = leg1
        self.leg1Shin = leg1Shin
        self.leg2 = leg2
        self.leg2Shin = leg2Shin
        self.torsoLower = torsoLower
        self.arm1 = arm1
        self.arm2 = arm2
    }
}

/// Camino + reposo de un personaje (o familia de personajes) para que las poses oscilen alrededor
/// de lo que cada rig realmente usa.
public struct RigProfile {
    public let paths: BonePathSet
    public let rest: RestAngles

    public init(paths: BonePathSet, rest: RestAngles) {
        self.paths = paths
        self.rest = rest
    }
}

public enum PoseLibrary {
    // Dos topologias entre los personajes mapeados: STANDARD (root -> [leg1, leg2, torso-chain];
    // el neck hub de la torso-chain -> [arm1, arm2, head-stalk] - Red/Blue/Green/Yellow/TCO/Orange)
    // y ALT (root -> [torso-chain, leg2, leg1]; neck hub -> [head-stalk, arm2, arm1] - TDL/victim).
    // Misma familia de rigs, mismos angulos de reposo, solo cambia el orden de hijos.
    public static let standardPaths = BonePathSet(
        leg1: [0], leg1Shin: [0, 0],
        leg2: [1], leg2Shin: [1, 0],
        torsoLower: [2],
        arm1: [2, 0, 0, 0], arm2: [2, 0, 0, 1]
    )

    public static let altPaths = BonePathSet(
        leg1: [2], leg1Shin: [2, 0],
        leg2: [1], leg2Shin: [1, 0],
        torsoLower: [0],
        arm1: [0, 0, 0, 2], arm2: [0, 0, 0, 1]
    )

    // Confirmado identico entre Red/Blue/Green/Yellow (recolor verdadero, mismos datos de rig).
    public static let redTopologyRest = RestAngles(
        leg1: 246.8, leg1Shin: 24.71,
        leg2: 294.53, leg2Shin: -26.79,
        torsoLower: 88.71,
        arm1: -207.92, arm2: -154.29
    )

    // TCO/Orange (orden STANDARD) y TDL/victim (orden ALT) comparten exactamente este set.
    public static let tcoTopologyRest = RestAngles(
        leg1: -66.8, leg1Shin: -384.7,
        leg2: -114.5, leg2Shin: -333.2,
        torsoLower: 89.4,
        arm1: -143.3, arm2: -212.7
    )

    public static let rigProfileByID: [String: RigProfile] = [
        "Red": RigProfile(paths: standardPaths, rest: redTopologyRest),
        "Blue": RigProfile(paths: standardPaths, rest: redTopologyRest),
        "Green": RigProfile(paths: standardPaths, rest: redTopologyRest),
        "Yellow": RigProfile(paths: standardPaths, rest: redTopologyRest),
        "TCO": RigProfile(paths: standardPaths, rest: tcoTopologyRest),
        "Orange": RigProfile(paths: standardPaths, rest: tcoTopologyRest),
        "TDL": RigProfile(paths: altPaths, rest: tcoTopologyRest),
        "victim": RigProfile(paths: altPaths, rest: tcoTopologyRest),
    ]

    /// Alias del mismo nombre que expone PoseLibrary en la version JS (renderer/poseLibrary.js).
    public static let PROFILE_BY_ID: [String: RigProfile] = rigProfileByID

    /// Perfil de pose de un id; nil (o stand-only) si no esta mapeado (ej. Purple).
    public static func profile(for id: String) -> RigProfile? {
        rigProfileByID[id]
    }

    private static let twoPi: CGFloat = .pi * 2

    /// Blendea de `from` una fraccion `t` hacia `to` yendo por el camino MAS CORTO del circulo en
    /// vez de resta lineal ingenua (ej. de -66.8 hacia 270 el camino largo es 336.8 grados cuando
    /// el corto son solo -23.2). Importa desde que hay poses con rest angles muy distintos.
    private static func blendToward(_ from: CGFloat, _ to: CGFloat, _ t: CGFloat) -> CGFloat {
        var diff = (to - from).truncatingRemainder(dividingBy: 360)
        if diff > 180 { diff -= 360 }
        if diff < -180 { diff += 360 }
        return from + diff * t
    }

    /// Pose de reposo = sin overrides.
    public static let stand: Pose = [:]

    /// Sway de idle en vez de un stand congelado: un tilt "de respiracion" lento en el torso y un
    /// sway opuesto sutil en los brazos.
    private static func standPose(_ p: RigProfile, frame: Int) -> Pose {
        let sway = 1 * sin(twoPi * CGFloat(frame) / 90)
        return [
            p.paths.torsoLower: p.rest.torsoLower + sway,
            p.paths.arm1: p.rest.arm1 + sway * 0.6,
            p.paths.arm2: p.rest.arm2 - sway * 0.6,
        ]
    }

    // Deltas derivados del SIT original afinado a mano de Red - relativos al rest propio de cada
    // personaje (ver el comentario del sitPose de Kotlin sobre absolutos que rompian TCO/Orange).
    private static func sitPose(_ p: RigProfile) -> Pose {
        [
            p.paths.leg1: p.rest.leg1 - 56.8,
            p.paths.leg1Shin: p.rest.leg1Shin + 65.29,
            p.paths.leg2: p.rest.leg2 + 55.47,
            p.paths.leg2Shin: p.rest.leg2Shin - 63.21,
            p.paths.torsoLower: p.rest.torsoLower + 6.29,
        ]
    }

    private static func fallPose(_ p: RigProfile) -> Pose {
        [
            p.paths.torsoLower: p.rest.torsoLower - 40,
            p.paths.arm1: p.rest.arm1 - 60,
            p.paths.arm2: p.rest.arm2 + 60,
            p.paths.leg1: p.rest.leg1 + 30,
            p.paths.leg2: p.rest.leg2 - 30,
        ]
    }

    /// Marcha de caminar/correr: las piernas oscilan en contrafase, cada pierna mueve el brazo
    /// opuesto y la rodilla se dobla en el swing hacia adelante.
    private static func walkPose(_ p: RigProfile, frame: Int, running: Bool) -> Pose {
        let period = running ? 6 : 8
        let amplitude: CGFloat = running ? 48 : 28
        let kneeBend: CGFloat = running ? 38 : 18
        let phase = twoPi * CGFloat(frame % period) / CGFloat(period)
        let legSwing = amplitude * sin(phase)
        return [
            p.paths.leg1: p.rest.leg1 + legSwing,
            p.paths.leg1Shin: p.rest.leg1Shin + kneeBend * max(0, sin(phase)),
            p.paths.leg2: p.rest.leg2 - legSwing,
            p.paths.leg2Shin: p.rest.leg2Shin + kneeBend * max(0, sin(phase + .pi)),
            p.paths.arm1: p.rest.arm1 - legSwing,
            p.paths.arm2: p.rest.arm2 + legSwing,
        ]
    }

    /// Salto/feliz: ambas piernas se agachan y luego se estiran juntas, brazos arriba al estirar.
    private static func bouncePose(_ p: RigProfile, frame: Int) -> Pose {
        let period = 6
        let phase = twoPi * CGFloat(frame % period) / CGFloat(period)
        let squat: CGFloat = 22 * max(0, sin(phase))
        return [
            p.paths.leg1Shin: p.rest.leg1Shin + squat,
            p.paths.leg2Shin: p.rest.leg2Shin + squat,
            p.paths.arm1: p.rest.arm1 - squat,
            p.paths.arm2: p.rest.arm2 + squat,
        ]
    }

    /// Tropezon: torso hacia adelante y brazos agitandose por el equilibrio.
    private static func tripPose(_ p: RigProfile, frame: Int) -> Pose {
        let jitter: CGFloat = 15 * sin(twoPi * CGFloat(frame) / 5)
        return [
            p.paths.torsoLower: p.rest.torsoLower - 25,
            p.paths.arm1: p.rest.arm1 + 40 + jitter,
            p.paths.arm2: p.rest.arm2 - 40 - jitter,
            p.paths.leg1: p.rest.leg1 + 20,
            p.paths.leg2: p.rest.leg2 - 10,
        ]
    }

    /// Colgado de la cabeza (arrastrado/pinze): matchea los sprites pinch01-07.png reales - piernas
    /// juntas y brazos pegados al torso, el cuerpo oscila entero como pendulo.
    private static func pinchPose(_ p: RigProfile, frame: Int) -> Pose {
        let sway: CGFloat = 6 * sin(twoPi * CGFloat(frame) / 14)
        let leg1Tuck = blendToward(p.rest.leg1, 270, 0.5)
        let leg2Tuck = blendToward(p.rest.leg2, 270, 0.5)
        return [
            p.paths.torsoLower: p.rest.torsoLower + sway,
            p.paths.leg1: leg1Tuck + sway,
            p.paths.leg2: leg2Tuck + sway,
            p.paths.leg1Shin: p.rest.leg1Shin * 0.3,
            p.paths.leg2Shin: p.rest.leg2Shin * 0.3,
            p.paths.arm1: p.rest.arm1 + sway * 1.5,
            p.paths.arm2: p.rest.arm2 + sway * 1.5,
        ]
    }

    /// Enojado: pisa fuerte con un pie - la canilla se levanta y golpea, brazos en tension.
    private static func angryPose(_ p: RigProfile, frame: Int) -> Pose {
        let period = 6
        let phase = twoPi * CGFloat(frame % period) / CGFloat(period)
        let stomp: CGFloat = 30 * max(0, sin(phase))
        return [
            p.paths.leg1Shin: p.rest.leg1Shin - stomp,
            p.paths.arm1: p.rest.arm1 + 25,
            p.paths.arm2: p.rest.arm2 - 25,
            p.paths.torsoLower: p.rest.torsoLower - 8,
        ]
    }

    /// Trepando una pared: miembros flexionados como agarrando un borde, alternando al subir.
    private static func climbPose(_ p: RigProfile, frame: Int) -> Pose {
        let period = 8
        let phase = twoPi * CGFloat(frame % period) / CGFloat(period)
        let limbSwing: CGFloat = 25 * sin(phase)
        return [
            p.paths.leg1: p.rest.leg1 + 20 + limbSwing,
            p.paths.leg1Shin: p.rest.leg1Shin + 30,
            p.paths.leg2: p.rest.leg2 - 20 - limbSwing,
            p.paths.leg2Shin: p.rest.leg2Shin + 30,
            p.paths.arm1: p.rest.arm1 - 40 - limbSwing,
            p.paths.arm2: p.rest.arm2 + 40 + limbSwing,
        ]
    }

    /// Cansado: sentado derrumbado con los brazos colgando (semantica "tired"/couch01 del desktop).
    private static func tiredPose(_ p: RigProfile) -> Pose {
        [
            p.paths.torsoLower: p.rest.torsoLower - 30,
            p.paths.arm1: p.rest.arm1 - 20,
            p.paths.arm2: p.rest.arm2 + 20,
            p.paths.leg1: p.rest.leg1 + 15,
            p.paths.leg1Shin: p.rest.leg1Shin + 40,
            p.paths.leg2: p.rest.leg2 - 15,
            p.paths.leg2Shin: p.rest.leg2Shin + 40,
        ]
    }

    // Gestos de comer/beber (sistema de supervivencia): brazo a la boca con masticado rapido el de
    // comer, torso inclinado atras con trago el de beber. Mismo math que character.js.
    private static func chewPose(_ p: RigProfile, frame: Int) -> Pose {
        let period = 5
        let phase = twoPi * CGFloat(frame % period) / CGFloat(period)
        let chew: CGFloat = 6 * sin(phase)
        return [
            p.paths.torsoLower: p.rest.torsoLower - 6 + chew,
            p.paths.arm1: p.rest.arm1 - 55 + chew,
            p.paths.arm2: p.rest.arm2 + 30,
            p.paths.leg1: p.rest.leg1 + 6,
            p.paths.leg2: p.rest.leg2 + 6,
        ]
    }

    private static func drinkPose(_ p: RigProfile, frame: Int) -> Pose {
        let period = 5
        let phase = twoPi * CGFloat(frame % period) / CGFloat(period)
        let gulp: CGFloat = 4 * sin(phase)
        return [
            p.paths.torsoLower: p.rest.torsoLower + 12 + gulp * 0.5,
            p.paths.arm1: p.rest.arm1 - 60,
            p.paths.arm2: p.rest.arm2 + 40,
            p.paths.leg1: p.rest.leg1 + 8,
            p.paths.leg2: p.rest.leg2 + 8,
        ]
    }

    /// Dormido: piernas estiradas juntas, brazos relajados y un sway lento de "respiracion" (la
    /// vista del overlay rota 90 grados para acostarlo, ver CharacterOverlay/CharacterRigView).
    private static func sleepPose(_ p: RigProfile, frame: Int) -> Pose {
        let breathe: CGFloat = 4 * sin(twoPi * CGFloat(frame) / 20)
        return [
            p.paths.leg1: p.rest.leg1 + 10,
            p.paths.leg1Shin: p.rest.leg1Shin * 0.2,
            p.paths.leg2: p.rest.leg2 - 10,
            p.paths.leg2Shin: p.rest.leg2Shin * 0.2,
            p.paths.arm1: p.rest.arm1 + breathe,
            p.paths.arm2: p.rest.arm2 - breathe,
            p.paths.torsoLower: p.rest.torsoLower + breathe * 0.5,
        ]
    }

    /// Nombre amigable -> camino de hueso (mantiene las listas de indices fuera del schema de IA).
    private static func nameToPath(_ paths: BonePathSet) -> [String: BonePath] {
        [
            "torso": paths.torsoLower,
            "leg1": paths.leg1,
            "leg1Shin": paths.leg1Shin,
            "leg2": paths.leg2,
            "leg2Shin": paths.leg2Shin,
            "arm1": paths.arm1,
            "arm2": paths.arm2,
        ]
    }

    private static func restFor(_ rest: RestAngles, _ name: String) -> CGFloat? {
        switch name {
        case "torso": return rest.torsoLower
        case "leg1": return rest.leg1
        case "leg1Shin": return rest.leg1Shin
        case "leg2": return rest.leg2
        case "leg2Shin": return rest.leg2Shin
        case "arm1": return rest.arm1
        case "arm2": return rest.arm2
        default: return nil
        }
    }

    /// Construye un Pose desde los deltas angulares de un keyframe escrito por la IA. Los deltas son
    /// RELATIVOS al pose de reposo de este personaje (misma convencion interna de cada pose arriba),
    /// NO grados absolutos - asi un "-60" significa el mismo gesto en Red (arm1 rest -207.92) y en
    /// TCO (arm1 rest -143.3). Nombres desconocidos se ignoran.
    public static func customPose(paths: BonePathSet, rest: RestAngles, angles: [String: CGFloat]) -> Pose {
        let map = nameToPath(paths)
        var out: Pose = [:]
        for (name, delta) in angles {
            guard let path = map[name], let base = restFor(rest, name) else { continue }
            out[path] = base + delta
        }
        return out
    }

    /// Conveniencia sobre el de arriba para cuando ya tenes el perfil resuelto.
    public static func customPose(_ angles: [String: CGFloat], profile: RigProfile) -> Pose {
        customPose(paths: profile.paths, rest: profile.rest, angles: angles)
    }

    /// Pose para el estado actual del personaje. Ids sin perfil mapeado (ej. Purple) quedan stand.
    public static func forFrameKind(_ kind: CharacterState.FrameKind, characterId: String) -> Pose {
        guard let profile = profile(for: characterId) else { return stand }
        switch kind {
        case .stand(let frame): return standPose(profile, frame: frame)
        case .sit: return sitPose(profile)
        case .walk(let frame): return walkPose(profile, frame: frame, running: false)
        case .run(let frame): return walkPose(profile, frame: frame, running: true)
        case .bounce(let frame): return bouncePose(profile, frame: frame)
        case .trip(let frame): return tripPose(profile, frame: frame)
        case .fall: return fallPose(profile)
        case .pinch(let frame): return pinchPose(profile, frame: frame)
        case .angry(let frame): return angryPose(profile, frame: frame)
        case .climb(let frame): return climbPose(profile, frame: frame)
        case .sleep(let frame): return sleepPose(profile, frame: frame)
        case .tired: return tiredPose(profile)
        case .chew(let frame): return chewPose(profile, frame: frame)
        case .drink(let frame): return drinkPose(profile, frame: frame)
        case .custom(let angles): return customPose(paths: profile.paths, rest: profile.rest, angles: angles)
        }
    }
}