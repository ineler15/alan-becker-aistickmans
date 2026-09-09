//
//  RigModel.swift
//  StickmanAI
//
//  Port del RigModel.kt de Android (com.stickmanai.android.overlay). Mismo JSON "slim" que
//  android-app/assets/rigs: nodos con claves cortas (t/a/l/th/sc/usc/c/hollow/outline/oc/cr/...).
//  Los nombres de propiedad = claves JSON para poder decodear los assets tal cual; en Kotlin son
//  nodeType/localAngleDeg/length/etc. (ver RigModel.kt).
//

import Foundation
import CoreGraphics

/// Un hueso del rig Stick Nodes, recortado a lo que RigLayout/CharacterRigView necesitan.
/// thickness <= 0 significa "conector estructural invisible": no se dibuja ninguna linea.
/// La cabeza de anillo (TCO/TDL/victim) se arma con segmentos curveRadius != 0 encadenados.
public struct RigNode: Decodable {
    public let t: String     // nodeType: RootNode/RoundedSegment/Circle/FilledCircle/Ellipse/Triangle/Trapezoid
    public let a: CGFloat    // localAngleDeg (grados, relativo al padre)
    public let l: CGFloat    // length
    public let th: CGFloat   // thickness
    public let sc: CGFloat   // scale
    public let c: [Int]?     // color [r,g,b,a]; presente solo si usc
    public let usc: Bool     // usa color propio
    public let hollow: Bool
    public let outline: Bool
    public let oc: [Int]?    // outlineColor [r,g,b,a] (si outline)
    public let cr: CGFloat   // curveRadius (0 = segmento recto)
    public let tri: String?  // triangleType ("" / "RightTriangle")
    public let triF: Bool    // triangleFlipped
    public let triU: Bool    // triangleUpsideDown
    public let thS: CGFloat  // trapezoidThicknessStart
    public let thE: CGFloat  // trapezoidThicknessEnd
    public let uS: Bool      // useTrapezoidThicknessStart
    public let uE: Bool      // useTrapezoidThicknessEnd
    public let rdS: Bool     // trapezoidRoundedStart
    public let rdE: Bool     // trapezoidRoundedEnd
    public let ch: [RigNode] // children

    private enum CodingKeys: String, CodingKey {
        case t, a, l, th, sc, c, usc, hollow, outline, oc, cr, tri, triF, triU, thS, thE, uS, uE, rdS, rdE, ch
    }

    public init(from decoder: Decoder) throws {
        let k = try decoder.container(keyedBy: CodingKeys.self)
        t = try k.decode(String.self, forKey: .t)
        a = try k.decodeIfPresent(CGFloat.self, forKey: .a) ?? 0
        l = try k.decodeIfPresent(CGFloat.self, forKey: .l) ?? 0
        th = try k.decodeIfPresent(CGFloat.self, forKey: .th) ?? 0
        sc = try k.decodeIfPresent(CGFloat.self, forKey: .sc) ?? 1
        usc = try k.decodeIfPresent(Bool.self, forKey: .usc) ?? false
        c = try k.decodeIfPresent([Int].self, forKey: .c)
        hollow = try k.decodeIfPresent(Bool.self, forKey: .hollow) ?? false
        outline = try k.decodeIfPresent(Bool.self, forKey: .outline) ?? false
        oc = try k.decodeIfPresent([Int].self, forKey: .oc)
        cr = try k.decodeIfPresent(CGFloat.self, forKey: .cr) ?? 0
        tri = try k.decodeIfPresent(String.self, forKey: .tri)
        triF = try k.decodeIfPresent(Bool.self, forKey: .triF) ?? false
        triU = try k.decodeIfPresent(Bool.self, forKey: .triU) ?? false
        thS = try k.decodeIfPresent(CGFloat.self, forKey: .thS) ?? 0
        thE = try k.decodeIfPresent(CGFloat.self, forKey: .thE) ?? 0
        uS = try k.decodeIfPresent(Bool.self, forKey: .uS) ?? false
        uE = try k.decodeIfPresent(Bool.self, forKey: .uE) ?? false
        rdS = try k.decodeIfPresent(Bool.self, forKey: .rdS) ?? false
        rdE = try k.decodeIfPresent(Bool.self, forKey: .rdE) ?? false
        ch = try k.decodeIfPresent([RigNode].self, forKey: .ch) ?? []
    }
}

/// Un rig completo: color del cuerpo + arbol de nodos con raiz en `root`.
/// El JSON trae `{"color":[r,g,b,a],"root":{...}}`; aca el color queda como hex "#rrggbb" (la
/// forma que usa la version PC/JS) ademas del arbol. `nodes` es conveniencia: el arbol como lista.
public struct RigFigure {
    public let id: String          // id del personaje ("" si se decodio sin load)
    public let color: String       // hex "#rrggbb" convertido del array [r,g,b,a]
    public let root: RigNode       // raiz del arbol (JSON clave "root")
    /// Perfil de pose (Red/TCO) usado cuando este rig pertenece a un personaje custom clonado de
    /// un built-in: PoseLibrary.forFrameKind resuelve `rig.poseProfile ?? rig.id` (igual que el
    /// `poseId = customMeta?.poseProfile ?: def.id` de CharacterOverlay.kt). nil = usar el propio id.
    public let poseProfile: String?

    public var nodes: [RigNode] { [root] }

    public init(id: String = "", color: String, root: RigNode, poseProfile: String? = nil) {
        self.id = id
        self.color = color
        self.root = root
        self.poseProfile = poseProfile
    }

    /// Carga `Resources/rigs/<id>.json` desde el bundle (mismo JSON que android-app/assets/rigs).
    public static func load(bundleName id: String) -> RigFigure? {
        guard let data = rigData(id: id) else { return nil }
        guard let fig = try? JSONDecoder().decode(RigFigure.self, from: data) else { return nil }
        return RigFigure(id: id, color: fig.color, root: fig.root, poseProfile: nil)
    }

    /// Convierte un color [r,g,b,a] (o [r,g,b]) del JSON al hex "#rrggbb".
    public static func hex(from rgba: [Int]) -> String {
        guard rgba.count >= 3 else { return "#000000" }
        return String(format: "#%02x%02x%02x", rgba[0] & 0xFF, rgba[1] & 0xFF, rgba[2] & 0xFF)
    }

    private static func rigData(id: String) -> Data? {
        var urls: [URL] = []
        // Grupo de Xcode: el JSON queda suelto en la raiz del bundle.
        if let u = Bundle.main.url(forResource: id, withExtension: "json") { urls.append(u) }
        // Folder reference / subdirectorio "rigs".
        if let u = Bundle.main.url(forResource: id, withExtension: "json", subdirectory: "rigs") { urls.append(u) }
        if let res = Bundle.main.resourceURL {
            urls.append(res.appendingPathComponent("rigs/\(id).json"))
            urls.append(res.appendingPathComponent("Resources/rigs/\(id).json"))
        }
        for u in urls where (try? Data(contentsOf: u)) != nil {
            return try? Data(contentsOf: u)
        }
        return nil
    }
}

extension RigFigure: Decodable {
    private enum CodingKeys: String, CodingKey { case color, root }

    public init(from decoder: Decoder) throws {
        let k = try decoder.container(keyedBy: CodingKeys.self)
        let arr = try k.decode([Int].self, forKey: .color)
        color = RigFigure.hex(from: arr)
        root = try k.decode(RigNode.self, forKey: .root)
        id = ""
        poseProfile = nil
    }
}