//
//  FaceRenderer.swift
//  StickmanAI
//
//  Port del FaceRenderer.kt de Android (com.stickmanai.android.overlay) + drawAccessory. Dibuja
//  ojos/boca independientes (dos ejes, no una "emocion" unica) y el accesorio de cabeza
//  (pelo/moño), anclados en el centro de la cabeza que detecta el layout/char.js.
//

import SwiftUI
import CoreGraphics

public enum FaceRenderer {
    public static let EYE_STYLES = ["normal", "wide", "angry", "heart"]
    public static let MOUTH_STYLES = ["neutral", "smile", "frown", "open", "angry"]

    // Colores fijos del moño (rosa) y el pelo (marrón) del creador de personajes, sin compartir
    // helper hex entre archivos para no chocar redeclaraciones.
    private static let bowColor = Color(red: 224.0 / 255, green: 64.0 / 255, blue: 154.0 / 255)
    private static let hairColor = Color(red: 58.0 / 255, green: 42.0 / 255, blue: 26.0 / 255)

    /// Arco de circunferencia muestreado en N segmentos de linea (y hacia abajo en pantalla, la
    /// convencion del pain). Evita la semantica ambigua de `clockwise` de SwiftUI: empezamos en
    /// startDeg y barremos sweepDeg en sentido horario del circulo visual - para el lerp de un angulo
    /// th de 0 a sweep, punto = center + r*(cos, sin) con rad y asi 0 grados queda a la derecha,
    /// 90 abajo, 180 izquierda, 270 arriba - la mitad del circulo que corresponde a cada boca
    /// (smile = mitad inferior, frown = mitad superior) sale con los grados del Kotlin original.
    private static func arcPath(center: CGPoint, radius: CGFloat, startDeg: CGFloat, sweepDeg: CGFloat) -> Path {
        var path = Path()
        let steps = 24
        let step = sweepDeg / CGFloat(steps)
        for k in 0...steps {
            let deg = (startDeg + step * CGFloat(k)) * .pi / 180
            let p = CGPoint(x: center.x + radius * cos(deg), y: center.y + radius * sin(deg))
            if k == 0 {
                path.move(to: p)
            } else {
                path.addLine(to: p)
            }
        }
        return path
    }

    public static func drawFace(context: GraphicsContext, center: CGPoint, radius: CGFloat, eyeStyle: String, mouthStyle: String) {
        let cx = center.x
        let cy = center.y
        let r = radius

        // Ojos: mirror en X alrededor del centro; agrandar el radio para "wide" y encogerse para
        // el resto usa el 0.13r base del Kotlin.
        let eyeR = (eyeStyle == "wide" ? r * 0.22 : r * 0.13)
        let dx = r * 0.35
        let dy = -r * 0.1
        let eyeLineWidth = r * 0.06

        func eye(at sx: CGFloat) {
            let ex = cx + sx * dx
            let ey = cy + dy
            let eyeRect = CGRect(x: ex - eyeR, y: ey - eyeR, width: eyeR * 2, height: eyeR * 2)
            if eyeStyle == "heart" {
                // Corazon: dos circulos + triangulo (los ojos enamorados de set_emotion eyes:heart).
                let s = eyeR
                let c1 = CGPoint(x: ex - s * 0.45, y: ey)
                let c2 = CGPoint(x: ex + s * 0.45, y: ey)
                context.fill(Path(ellipseIn: CGRect(x: c1.x - s * 0.45, y: c1.y - s * 0.4, width: s * 0.9, height: s * 0.9)), with: .color(bowColor))
                context.fill(Path(ellipseIn: CGRect(x: c2.x - s * 0.45, y: c2.y - s * 0.4, width: s * 0.9, height: s * 0.9)), with: .color(bowColor))
                let tri = Path { p in
                    p.move(to: CGPoint(x: ex - s, y: ey - s * 0.1))
                    p.addLine(to: CGPoint(x: ex + s, y: ey - s * 0.1))
                    p.addLine(to: CGPoint(x: ex, y: ey + s * 0.9))
                    p.closeSubpath()
                }
                context.fill(tri, with: .color(bowColor))
            } else if eyeStyle == "angry" {
                // Cejas enojadas: dos lineas que caen hacia el centro, ANCLADAS al centro del ojo
                // (mismo offset del dibujo de ojitos de char.js).
                let bx = ex - eyeR * 1.4
                let by = ey - eyeR * 1.6
                context.stroke(
                    Path { p in
                        p.move(to: CGPoint(x: bx, y: by))
                        p.addLine(to: CGPoint(x: bx + eyeR * 2.8 * sx, y: by + eyeR * 1.2))
                    },
                    with: .color(bowColor),
                    style: StrokeStyle(lineWidth: eyeLineWidth, lineCap: .round)
                )
            } else {
                // normal/wide: un ojito de color de frente (pupila) sobre el mismo espejo de ojos.
                context.fill(
                    Path(ellipseIn: CGRect(x: ex - eyeR * 0.4, y: ey - eyeR * 0.4, width: eyeR * 0.8, height: eyeR * 0.8)),
                    with: .color(bowColor)
                )
            }
        }

        eye(at: -1)
        eye(at: 1)

        // Boca: arcos de la mitad inferior (smile), superior (frown) o linea/relleno.
        let mouthLineWidth = r * 0.08
        let my = cy + r * 0.35
        switch mouthStyle {
        case "smile":
            context.stroke(
                arcPath(center: CGPoint(x: cx, y: my - r * 0.15), radius: r * 0.32, startDeg: 27, sweepDeg: 126),
                with: .color(bowColor),
                style: StrokeStyle(lineWidth: mouthLineWidth, lineCap: .round)
            )
        case "frown":
            context.stroke(
                arcPath(center: CGPoint(x: cx, y: my + r * 0.35), radius: r * 0.32, startDeg: 207, sweepDeg: 126),
                with: .color(bowColor),
                style: StrokeStyle(lineWidth: mouthLineWidth, lineCap: .round)
            )
        case "open":
            context.fill(
                Path(ellipseIn: CGRect(x: cx - r * 0.14, y: my - r * 0.12, width: r * 0.28, height: r * 0.24)),
                with: .color(bowColor)
            )
        case "angry":
            // Boca enojada: "sarasa" hacia abajo -> una V invertida de dos lineas.
            context.stroke(
                Path { p in
                    p.move(to: CGPoint(x: cx - r * 0.14, y: my))
                    p.addLine(to: CGPoint(x: cx, y: my + r * 0.16))
                    p.addLine(to: CGPoint(x: cx + r * 0.14, y: my))
                },
                with: .color(bowColor),
                style: StrokeStyle(lineWidth: mouthLineWidth, lineCap: .round, lineJoin: .round)
            )
        default: // neutral
            context.stroke(
                Path { p in
                    p.move(to: CGPoint(x: cx - r * 0.22, y: my))
                    p.addLine(to: CGPoint(x: cx + r * 0.22, y: my))
                },
                with: .color(bowColor),
                style: StrokeStyle(lineWidth: mouthLineWidth, lineCap: .round)
            )
        }
    }

    /// Accesorio de cabeza independente del genero (v2.14/v1.14): "none"/"hair"/"bow".
    public static func drawAccessory(context: GraphicsContext, center: CGPoint, radius: CGFloat, accessory: String) {
        guard accessory != "none" else { return }
        let cx = center.x
        let cy = center.y
        let r = radius
        switch accessory {
        case "hair":
            // Pelo: arco superior de circunferencia grande + tres mechones encima.
            let top = CGPoint(x: cx, y: cy + r * 0.1)
            let tuftR = r * 1.05
            context.stroke(
                arcPath(center: top, radius: tuftR, startDeg: 180, sweepDeg: 180),
                with: .color(hairColor),
                style: StrokeStyle(lineWidth: r * 0.28, lineCap: .round)
            )
            let tx = cx
            let ty = cy - r * 0.85
            for (offset, up) in [(-0.55, -0.5), (0.0, -0.75), (0.55, -0.5)] {
                context.stroke(
                    Path { p in
                        p.move(to: CGPoint(x: cx + offset * r, y: cy - r * 0.35))
                        p.addLine(to: CGPoint(x: tx + offset * r, y: ty - up * r))
                    },
                    with: .color(hairColor),
                    style: StrokeStyle(lineWidth: r * 0.12, lineCap: .round)
                )
            }
        case "bow":
            // Moño espejado: dos triangulos + circulo central.
            let by = cy - r * 0.95
            let wing = r * 0.3
            for side: CGFloat in [-1, 1] {
                let tip = CGPoint(x: cx + side * wing, y: by + wing * 0.4)
                let mid = CGPoint(x: cx + side * wing * 0.35, y: by)
                let inner = CGPoint(x: cx, y: by)
                let tri = Path { p in
                    p.move(to: tip)
                    p.addLine(to: mid)
                    p.addLine(to: inner)
                    p.closeSubpath()
                }
                context.fill(tri, with: .color(bowColor))
            }
            context.fill(
                Path(ellipseIn: CGRect(x: cx - r * 0.28, y: by - r * 0.28, width: r * 0.56, height: r * 0.56)),
                with: .color(bowColor)
            )
        default:
            break
        }
    }
}