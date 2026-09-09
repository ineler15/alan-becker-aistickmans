//
//  RigCanvasView.swift
//  StickmanAI
//
//  Port del RigView.kt de Android (com.stickmanai.android.overlay) a SwiftUI: dibuja un rig en un
//  Canvas. Fit-and-center por bounding box del pose DE REPOSO para que cambiar de pose no haga
//  zoom (mismo truco que RigView.kt). Los transforms que en Android hace CharacterOverlay.tick()
//  (rotar para trepar/dormir, espejar segun lookRight) viven aca a nivel de view.
//

import SwiftUI
import CoreGraphics

private extension Color {
    /// #rrggbb -> Color. Los hex salen de RigFigure.hex; no usado por FaceRenderer.
    init(hex: String) {
        var s = hex.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("#") { s.removeFirst() }
        var v: UInt64 = 0
        Scanner(string: s).scanHexInt64(&v)
        self.init(
            red: Double((v >> 16) & 0xFF) / 255,
            green: Double((v >> 8) & 0xFF) / 255,
            blue: Double(v & 0xFF) / 255
        )
    }
}

public struct CharacterRigView: View {
    public let rig: RigFigure
    @ObservedObject public var state: CharacterState
    public let size: CGSize
    public var hasFace: Bool = false
    public var accessory: String = "none"

    public init(rig: RigFigure, state: CharacterState, size: CGSize, hasFace: Bool = false, accessory: String = "none") {
        self.rig = rig
        self.state = state
        self.size = size
        self.hasFace = hasFace
        self.accessory = accessory
        let rest = RigLayout.layout(figure: rig, pose: [:])
        restBounds = RigLayout.bounds(bones: rest)
    }

    private let restBounds: CGRect

    /// El id por el que PoseLibrary busca el perfil: los personajes custom usan el poseProfile del
    /// built-in del que fueron clonados (ver [[custom_character_creator]]).
    private var poseID: String { rig.poseProfile ?? rig.id }

    /// Tamaño canonico de la ventana de un personaje: alto fijo, ancho por el aspect del rest pose.
    public static func canonicalSize(for rig: RigFigure) -> CGSize {
        let rest = RigLayout.layout(figure: rig, pose: [:])
        let b = RigLayout.bounds(bones: rest)
        let h: CGFloat = 128
        let w = b.width > 0 ? h * (b.width / b.height) : h
        return CGSize(width: max(w, h * 0.6), height: h)
    }

    public var body: some View {
        let orientation = verticalOrientation()
        let mirror = state.lookRight
        return Canvas { context, canvasSize in
            draw(in: context, canvasSize: canvasSize)
        }
        .frame(width: size.width, height: size.height)
        // Los mismos transforms que aplicaba CharacterOverlay.tick(): acostarlo 90 grados al
        // dormir/trepar (la pared era un lado de la pantalla), y espejar en X para que caminar a la
        // derecha muestre al personaje de frente (su postura de reposo mira a la izquierda).
        .rotationEffect(.degrees(orientation))
        .scaleEffect(
            x: (orientation == 0 && mirror) ? -1 : 1,
            y: 1
        )
        .animation(nil, value: orientation)
    }

    private func verticalOrientation() -> CGFloat {
        switch state.frameKind {
        case .climb: return state.climbSide < 0 ? 90 : -90
        case .sleep: return 90
        default: return 0
        }
    }

    private func draw(in context: GraphicsContext, canvasSize: CGSize) {
        let pose = PoseLibrary.forFrameKind(state.frameKind, characterId: poseID)
        let bones = RigLayout.layout(figure: rig, pose: pose)
        guard !bones.isEmpty else { return }

        // Fit-and-center: scale por el rest pose (constante entre poses), offset sigue el centro
        // del pose actual para que camine/salte dentro de su ventana sin zoomear.
        let pad = max(canvasSize.width * 0.1, 8)
        let scale = min(
            (canvasSize.width - pad * 2) / max(restBounds.width, 1),
            (canvasSize.height - pad * 2) / max(restBounds.height, 1)
        )
        let poseBounds = RigLayout.bounds(bones: bones)
        let offsetX = canvasSize.width / 2 - poseBounds.midX * scale
        let offsetY = canvasSize.height / 2 - poseBounds.midY * scale

        func tx(_ p: CGPoint) -> CGPoint {
            CGPoint(x: p.x * scale + offsetX, y: p.y * scale + offsetY)
        }
        func txc(_ p: CGPoint) -> CGPoint {
            CGPoint(x: p.x * scale + offsetX, y: p.y * scale + offsetY)
        }

        var headAnchor: (center: CGPoint, radius: CGFloat)?

        var i = 0
        while i < bones.count {
            let bone = bones[i]
            let color = Color(hex: bone.colorHex)

            switch bone.nodeType {
            case "Circle", "FilledCircle":
                // El radio del circle ya viene escalado en RigLayout/layout (l*0.65); aca solo
                // transformamos el centro.
                let r = max(bone.length * RigLayout.circleRadiusFactor, 2) * scale
                let c = txc(circleCenter(of: bone))
                let rect = CGRect(x: c.x - r, y: c.y - r, width: r * 2, height: r * 2)
                context.fill(Path(ellipseIn: rect), with: .color(color))
                if bone.outline {
                    context.stroke(
                        Path(ellipseIn: rect),
                        with: .color(Color(hex: bone.outlineColorHex)),
                        lineWidth: max(2 * scale, 1)
                    )
                }
                headAnchor = (c, r)
                i += 1

            case "Ellipse":
                // GraphicsContext.translateBy/rotate son mutating -> copia local del context.
                let (rx, ry) = RigLayout.ellipseRadii(bone: bone)
                let c = txc(RigLayout.ellipseCenter(bone: bone))
                var c2 = context
                c2.translateBy(x: c.x, y: c.y)
                c2.rotate(by: .radians(angleOf(bone) * .pi / 180))
                let rect = CGRect(x: -rx * scale, y: -ry * scale, width: rx * 2 * scale, height: ry * 2 * scale)
                c2.fill(Path(ellipseIn: rect), with: .color(color))
                if bone.outline {
                    c2.stroke(
                        Path(ellipseIn: rect),
                        with: .color(Color(hex: bone.outlineColorHex)),
                        lineWidth: max(2 * scale, 1)
                    )
                }
                i += 1

            case "Triangle", "Trapezoid":
                let corners = RigLayout.polygonCorners(bone: bone)
                let path = Path { p in
                    guard let first = corners.first else { return }
                    p.move(to: txc(first))
                    for corner in corners.dropFirst() { p.addLine(to: txc(corner)) }
                    p.closeSubpath()
                }
                context.fill(path, with: .color(color))
                let half = max(bone.thickness / 2, 1) * scale
                let r = min(half, max(bone.length / 2, 1) * scale)
                for corner in corners {
                    context.fill(
                        Path(ellipseIn: CGRect(x: txc(corner).x - r, y: txc(corner).y - r, width: r * 2, height: r * 2)),
                        with: .color(color)
                    )
                }
                if bone.outline {
                    context.stroke(path, with: .color(Color(hex: bone.outlineColorHex)), lineWidth: max(2 * scale, 1))
                }
                i += 1

            default:
                if bone.curveRadius != 0 {
                    // Anillo de segmentos curveados = la cabeza "hollow" (TCO/TSC/TDL/victim): se
                    // traza TODA la cadena como UN solo trazo suavizado por curvas cuadraticas
                    // pasando por los midpoints (fix duplicado del prototipo sn-proto).
                    var chain = [Bone]()
                    var j = i
                    while j < bones.count && bones[j].curveRadius != 0 && bones[j].start == chain.last?.end ?? bone.start {
                        chain.append(bones[j])
                        j += 1
                    }
                    drawCurvedChain(chain, context: context, tx: txc, color: color, scale: scale)
                    headAnchor = curvedChainAnchor(chain, tx: txc)
                    i = j
                } else {
                    // Hueso normal: linea stroked redondeada. Radius=0 no se strokee (convencion
                    // de Stick Nodes para conectores/estructura; el clamp a 1px del viejo codigo
                    // dibujaba una rayita fantasma).
                    if bone.thickness > 0 {
                        context.stroke(
                            Path { p in
                                p.move(to: txc(bone.start))
                                p.addLine(to: txc(bone.end))
                            },
                            with: .color(color),
                            style: StrokeStyle(lineWidth: max(bone.thickness * scale, 1), lineCap: .round)
                        )
                    }
                    i += 1
                }
            }
        }

        if hasFace, let anchor = headAnchor {
            FaceRenderer.drawFace(
                context: context,
                center: anchor.center,
                radius: anchor.radius,
                eyeStyle: state.eyeStyle,
                mouthStyle: state.mouthStyle
            )
            FaceRenderer.drawAccessory(context: context, center: anchor.center, radius: anchor.radius, accessory: accessory)
        }
    }

    private func circleCenter(of bone: Bone) -> CGPoint {
        let r = max(bone.length * RigLayout.circleRadiusFactor, 2)
        return RigLayout.circleCenter(bone: bone, radius: r)
    }

    private func angleOf(_ bone: Bone) -> CGFloat {
        let dx = bone.end.x - bone.start.x
        let dy = bone.end.y - bone.start.y
        let a = atan2(dy, dx) * 180 / .pi
        return a < 0 ? a + 360 : a
    }

    private func drawCurvedChain(_ chain: [Bone], context: GraphicsContext, tx: (CGPoint) -> CGPoint, color: Color, scale: CGFloat) {
        guard let first = chain.first else { return }
        let pts = [first.start] + chain.map(\.end)
        let mapped = pts.map(tx)
        guard mapped.count >= 2 else { return }
        let thickness = max((chain.first?.thickness ?? 4) * scale, 1)
        context.stroke(
            Path { p in
                p.move(to: mapped[0])
                if mapped.count == 2 {
                    p.addLine(to: mapped[1])
                } else {
                    for idx in 1..<(mapped.count - 1) {
                        let mid = CGPoint(x: (mapped[idx].x + mapped[idx + 1].x) / 2,
                                          y: (mapped[idx].y + mapped[idx + 1].y) / 2)
                        p.addQuadCurve(to: mid, control: mapped[idx])
                    }
                    p.addLine(to: mapped[mapped.count - 1])
                }
            },
            with: .color(color),
            style: StrokeStyle(lineWidth: thickness, lineCap: .round, lineJoin: .round)
        )
    }

    private func curvedChainAnchor(_ chain: [Bone], tx: (CGPoint) -> CGPoint) -> (center: CGPoint, radius: CGFloat) {
        let pts = [chain[0].start] + chain.map(\.end)
        let mapped = pts.map(tx)
        var cx: CGFloat = 0
        var cy: CGFloat = 0
        let n = CGFloat(mapped.count)
        for p in mapped { cx += p.x / n; cy += p.y / n }
        let center = CGPoint(x: cx, y: cy)
        let radius = hypot(mapped[0].x - cx, mapped[0].y - cy)
        return (center, radius)
    }
}