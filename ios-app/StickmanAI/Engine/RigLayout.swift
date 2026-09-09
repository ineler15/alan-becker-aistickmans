//
//  RigLayout.swift
//  StickmanAI
//
//  Port del RigLayout.kt de Android (com.stickmanai.android.overlay). Camino de layout por
//  acumulacion de angulos (los de Stick Nodes son relativos al padre) y bounding-box del pose.
//  El math es identico al prototipo sn-proto/WASM (ver memoria sn_proto_wasm_renderer).
//

import Foundation
import CoreGraphics

/// Camino de un hueso = lista de indices de hijos desde la raiz (los .nodes no traen nombres).
public typealias BonePath = [Int]

/// Un pose = mapa camino -> angulo local ABSOLUTO de reemplazo (grados), igual que `a` del nodo.
public typealias Pose = [BonePath: CGFloat]

/// Un hueso ya posicionado en espacio de modelo (antes del scale/offset del canvas).
public struct Bone {
    public let nodeType: String
    public let start: CGPoint
    public let end: CGPoint
    public let length: CGFloat
    public let thickness: CGFloat
    public let colorHex: String
    public let hollow: Bool
    public let outline: Bool
    public let outlineColorHex: String
    public let curveRadius: CGFloat
    public let triangleType: String
    public let triangleFlipped: Bool
    public let triangleUpsideDown: Bool
    public let trapezoidHalfStart: CGFloat
    public let trapezoidHalfEnd: CGFloat
    public let trapezoidRoundedStart: Bool
    public let trapezoidRoundedEnd: Bool

    public init(
        nodeType: String,
        start: CGPoint,
        end: CGPoint,
        length: CGFloat,
        thickness: CGFloat,
        colorHex: String,
        hollow: Bool,
        outline: Bool,
        outlineColorHex: String,
        curveRadius: CGFloat,
        triangleType: String,
        triangleFlipped: Bool,
        triangleUpsideDown: Bool,
        trapezoidHalfStart: CGFloat,
        trapezoidHalfEnd: CGFloat,
        trapezoidRoundedStart: Bool,
        trapezoidRoundedEnd: Bool
    ) {
        self.nodeType = nodeType
        self.start = start
        self.end = end
        self.length = length
        self.thickness = thickness
        self.colorHex = colorHex
        self.hollow = hollow
        self.outline = outline
        self.outlineColorHex = outlineColorHex
        self.curveRadius = curveRadius
        self.triangleType = triangleType
        self.triangleFlipped = triangleFlipped
        self.triangleUpsideDown = triangleUpsideDown
        self.trapezoidHalfStart = trapezoidHalfStart
        self.trapezoidHalfEnd = trapezoidHalfEnd
        self.trapezoidRoundedStart = trapezoidRoundedStart
        self.trapezoidRoundedEnd = trapezoidRoundedEnd
    }
}

public enum RigLayout {
    /// Radio de la cabeza tipo circle: length * 0.65, calibrado en vivo contra el prototipo
    /// (ver memoria sn_proto_wasm_renderer).
    public static let circleRadiusFactor: CGFloat = 0.65

    private static let defaultOutlineHex = "#000000"

    /// Layout del arbol completo de un personaje con el pose dado.
    public static func layout(figure: RigFigure, pose: Pose = [:]) -> [Bone] {
        layout(root: figure.root, figureColor: figure.color, pose: pose)
    }

    /// Caminata de acumulacion de angulos: child.start == parent.end, y los angulos se suman
    /// bajando por el arbol. En pantalla +y va hacia abajo pero Stick Nodes usa +y arriba,
    /// entonces el desplazamiento Y se niega (igual que el prototipo).
    public static func layout(root: RigNode, figureColor: String, pose: Pose = [:]) -> [Bone] {
        var acc: [Bone] = []

        func walk(node: RigNode, path: BonePath, parentAngleDeg: CGFloat, parentEnd: CGPoint) {
            let isRoot = node.t == "RootNode"
            let localAngle = pose[path] ?? node.a
            let globalAngleDeg = isRoot ? localAngle : parentAngleDeg + localAngle
            let start = isRoot ? CGPoint.zero : parentEnd
            let rad = globalAngleDeg * .pi / 180
            let localX = isRoot ? 0 : node.l * cos(rad) * node.sc
            let localY = isRoot ? 0 : -node.l * sin(rad) * node.sc
            let end = CGPoint(x: start.x + localX, y: start.y + localY)

            if !isRoot {
                let colorHex = node.usc ? (node.c.map { RigFigure.hex(from: $0) } ?? figureColor) : figureColor
                let outlineHex = node.outline ? (node.oc.map { RigFigure.hex(from: $0) } ?? defaultOutlineHex) : defaultOutlineHex
                acc.append(Bone(
                    nodeType: node.t,
                    start: start,
                    end: end,
                    length: node.l,
                    thickness: node.th,
                    colorHex: colorHex,
                    hollow: node.hollow,
                    outline: node.outline,
                    outlineColorHex: outlineHex,
                    curveRadius: node.cr,
                    triangleType: node.tri ?? "",
                    triangleFlipped: node.triF,
                    triangleUpsideDown: node.triU,
                    trapezoidHalfStart: (node.uS && node.thS > 0 ? node.thS : node.th) / 2,
                    trapezoidHalfEnd: (node.uE && node.thE > 0 ? node.thE : node.th) / 2,
                    trapezoidRoundedStart: node.rdS,
                    trapezoidRoundedEnd: node.rdE
                ))
            }
            for (i, child) in node.ch.enumerated() {
                walk(node: child, path: path + [i], parentAngleDeg: globalAngleDeg, parentEnd: end)
            }
        }

        walk(node: root, path: [], parentAngleDeg: 0, parentEnd: .zero)
        return acc
    }

    /// Centro del circle en espacio de modelo: offset desde el start por su propio radio a lo
    /// largo del start->end, para que el borde cercano toque la articulacion (el fix del prototipo
    /// que dejo de hundir la cabeza en el cuello y dejo de flotar, ver sn_proto_wasm_renderer).
    public static func circleCenter(bone: Bone, radius: CGFloat) -> CGPoint {
        let dx = bone.end.x - bone.start.x
        let dy = bone.end.y - bone.start.y
        let dist = max(hypot(dx, dy), 1e-3)
        return CGPoint(x: bone.start.x + dx / dist * radius, y: bone.start.y + dy / dist * radius)
    }

    /// Unitario perpendicular a un segmento en espacio de rig (para base/ancho de poligonos).
    private static func perpDir(dx: CGFloat, dy: CGFloat) -> CGPoint {
        let dist = max(hypot(dx, dy), 1e-3)
        return CGPoint(x: -dy / dist, y: dx / dist)
    }

    /// Esquinas (espacio de modelo) de un poligono, mismo math que triangleCorners()/
    /// trapezoidCorners() de renderer/character.js (PC).
    public static func polygonCorners(bone: Bone) -> [CGPoint] {
        switch bone.nodeType {
        case "Triangle":
            let p = perpDir(dx: bone.end.x - bone.start.x, dy: bone.end.y - bone.start.y)
            let h = max(bone.thickness, 1) / 2
            if bone.triangleUpsideDown {
                return [
                    CGPoint(x: bone.start.x - p.x * h, y: bone.start.y - p.y * h),
                    CGPoint(x: bone.start.x + p.x * h, y: bone.start.y + p.y * h),
                    bone.end,
                ]
            } else if bone.triangleType == "RightTriangle" {
                let s: CGFloat = bone.triangleFlipped ? -1 : 1
                return [
                    bone.start,
                    CGPoint(x: bone.start.x + p.x * h * s, y: bone.start.y + p.y * h * s),
                    bone.end,
                ]
            } else {
                return [
                    CGPoint(x: bone.start.x - p.x * h, y: bone.start.y - p.y * h),
                    CGPoint(x: bone.start.x + p.x * h, y: bone.start.y + p.y * h),
                    bone.end,
                ]
            }
        case "Trapezoid":
            let p = perpDir(dx: bone.end.x - bone.start.x, dy: bone.end.y - bone.start.y)
            let hs = max(bone.trapezoidHalfStart, 1)
            let he = max(bone.trapezoidHalfEnd, 1)
            return [
                CGPoint(x: bone.start.x + p.x * hs, y: bone.start.y + p.y * hs),
                CGPoint(x: bone.start.x - p.x * hs, y: bone.start.y - p.y * hs),
                CGPoint(x: bone.end.x - p.x * he, y: bone.end.y - p.y * he),
                CGPoint(x: bone.end.x + p.x * he, y: bone.end.y + p.y * he),
            ]
        default:
            return []
        }
    }

    /// Centro de la elipse en espacio de modelo (punto medio del segmento).
    public static func ellipseCenter(bone: Bone) -> CGPoint {
        CGPoint(x: (bone.start.x + bone.end.x) / 2, y: (bone.start.y + bone.end.y) / 2)
    }

    /// Radios de la elipse: rx = l/2 a lo largo del hueso, ry = th/2 al traves.
    public static func ellipseRadii(bone: Bone) -> (rx: CGFloat, ry: CGFloat) {
        (max(bone.length / 2, 1), max(bone.thickness / 2, 1))
    }

    /// Bounding box de un set de huesos en espacio de modelo (incluye circulos, elipses y poligonos).
    public static func bounds(bones: [Bone]) -> CGRect {
        var minX = CGFloat.greatestFiniteMagnitude
        var minY = CGFloat.greatestFiniteMagnitude
        var maxX = -CGFloat.greatestFiniteMagnitude
        var maxY = -CGFloat.greatestFiniteMagnitude

        func include(_ x: CGFloat, _ y: CGFloat) {
            minX = min(minX, x); maxX = max(maxX, x)
            minY = min(minY, y); maxY = max(maxY, y)
        }

        for bone in bones {
            if bone.nodeType == "Circle" || bone.nodeType == "FilledCircle" {
                let r = max(bone.length * circleRadiusFactor, 2)
                let c = circleCenter(bone: bone, radius: r)
                include(c.x - r, c.y - r); include(c.x + r, c.y + r)
            } else if bone.nodeType == "Ellipse" {
                let (rx, ry) = ellipseRadii(bone: bone)
                let c = ellipseCenter(bone: bone)
                include(c.x - rx, c.y - ry); include(c.x + rx, c.y + ry)
            } else if bone.nodeType == "Triangle" || bone.nodeType == "Trapezoid" {
                for corner in polygonCorners(bone: bone) { include(corner.x, corner.y) }
            } else {
                include(bone.start.x, bone.start.y)
                include(bone.end.x, bone.end.y)
            }
        }
        if minX > maxX { return CGRect(x: 0, y: 0, width: 1, height: 1) }
        return CGRect(x: minX, y: minY, width: maxX - minX, height: maxY - minY)
    }

    /// Ancla de la cabeza (centro + radio) en espacio de MODELO para el layout dado. El ultimo
    /// circulo (o la ultima cadena de segmentos curveRadius) dibujado gana, como captura RigView.
    public static func headAnchor(in bones: [Bone]) -> (center: CGPoint, radius: CGFloat)? {
        var center: CGPoint?
        var radius: CGFloat = 0

        var i = 0
        while i < bones.count {
            let bone = bones[i]
            if bone.nodeType == "Circle" || bone.nodeType == "FilledCircle" {
                let r = max(bone.length * circleRadiusFactor, 2)
                center = circleCenter(bone: bone, radius: r)
                radius = r
                i += 1
                continue
            }
            if bone.curveRadius != 0 {
                var chain = [bone]
                var j = i + 1
                while j < bones.count && bones[j].curveRadius != 0 && bones[j].start == chain[chain.count - 1].end {
                    chain.append(bones[j])
                    j += 1
                }
                var pts = [chain[0].start]
                pts.append(contentsOf: chain.map(\.end))
                var cx: CGFloat = 0
                var cy: CGFloat = 0
                let n = CGFloat(pts.count)
                for p in pts { cx += p.x / n; cy += p.y / n }
                center = CGPoint(x: cx, y: cy)
                radius = hypot(pts[0].x - cx, pts[0].y - cy)
                i = j
                continue
            }
            i += 1
        }
        guard let c = center else { return nil }
        return (c, radius)
    }

    /// Ancla de la cabeza (centro + radio) desde el pose de reposo del personaje, util para
    /// previews/UI que no quieren calcular el layout por pose.
    public static func headInfo(figure: RigFigure) -> (center: CGPoint, radius: CGFloat)? {
        headAnchor(in: layout(figure: figure, pose: [:]))
    }
}