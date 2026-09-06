package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.view.View

/**
 * Draws a Stick Nodes rig (bones as rounded lines, head as a filled circle) instead of a raster
 * sprite frame. The current pose's bounding box is always fit-and-centered inside the view like
 * the sn-proto prototype does, EXCEPT the scale factor itself is locked to the rig's rest pose
 * (poseOverride = emptyMap) so the character doesn't visibly grow/shrink switching between poses
 * of different heights (e.g. standing vs sitting) - only translation follows the current pose.
 */
class RigView(
    context: Context,
    figure: RigFigure,
    private val hasFace: Boolean = false,
    private val accessory: String = "none",
) : View(context) {

    // Swappable so the kitchen prop can cycle its background stations (KitchenOverlay.nextStation)
    // - same idea as the desktop's character:rig IPC. Recomputed restBounds keep the fit-and-center
    // scale locked to each new rig's own rest pose.
    private var figure: RigFigure = figure
    var pose: Pose = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    // Independent of `pose` (body) - see CharacterState.kt's eyeStyle/mouthStyle. Only ever
    // meaningful when hasFace is true; harmless to set otherwise.
    var eyeStyle: String = "normal"
        set(value) {
            field = value
            invalidate()
        }
    var mouthStyle: String = "neutral"
        set(value) {
            field = value
            invalidate()
        }

    // Food/drink prop mode (FoodPropOverlay): the whole rig is drawn clipped to a shrinking wedge
    // so each bite visibly removes a slice (plus a per-bite jiggle), and drink mode rotates the
    // rig (a cup) by the tilt angle, synced with the swallow timeline in OverlayService - exactly
    // the desktop's character.js foodBites/drinkTilt behavior.
    private var foodBitesTotal = 0
    private var foodBitesLeft = 0
    private var foodJiggleRad = 0f
    private var drinkTiltDeg = 0f

    private var restBounds = RigLayout.bounds(RigLayout.layout(figure.root, figure.bodyColor, emptyMap()))

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun setFigure(newFigure: RigFigure) {
        figure = newFigure
        restBounds = RigLayout.bounds(RigLayout.layout(figure.root, figure.bodyColor, emptyMap()))
        invalidate()
    }

    /** Puts the whole rig in "food" mode: `foodBites` total bites to wedge-clip away. */
    fun setFoodMode(foodBites: Int) {
        foodBitesTotal = foodBites
        foodBitesLeft = foodBites
        foodJiggleRad = 0f
        drinkTiltDeg = 0f
        invalidate()
    }

    /** Per-bite update: remaining bites + a small random jiggle (in radians), like character.js. */
    fun setFoodBites(bitesLeft: Int, jiggle: Float) {
        foodBitesLeft = bitesLeft.coerceIn(0, foodBitesTotal)
        foodJiggleRad = jiggle
        invalidate()
    }

    /** Drink swallow: tilt the rig (a cup) by this many degrees; 0 = upright. */
    fun setDrinkTilt(deg: Float) {
        drinkTiltDeg = deg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bones = RigLayout.layout(figure.root, figure.bodyColor, pose)
        if (bones.isEmpty() || width == 0 || height == 0) return

        val padding = width * 0.1f
        val scale = minOf(
            (width - padding * 2) / restBounds.width().coerceAtLeast(1f),
            (height - padding * 2) / restBounds.height().coerceAtLeast(1f),
        )
        val poseBounds = RigLayout.bounds(bones)
        val offX = width / 2f - poseBounds.centerX() * scale
        val offY = height / 2f - poseBounds.centerY() * scale

        fun tx(p: PointF) = PointF(p.x * scale + offX, p.y * scale + offY)

        // Food mode: clip the whole rig draw to a shrinking wedge (each bite removes a slice) and
        // jiggle it per bite / rotate it for drinking - around the rig box's center so it stays
        // anchored regardless of pose. Mirrors character.js's foodBites/drinkTilt handling.
        var clipCenter: PointF? = null
        if (foodBitesTotal > 0 || drinkTiltDeg != 0f) {
            clipCenter = tx(
                PointF(restBounds.centerX(), restBounds.centerY())
            )
            canvas.save()
            if (foodJiggleRad != 0f) {
                canvas.rotate(Math.toDegrees(foodJiggleRad.toDouble()).toFloat(), clipCenter.x, clipCenter.y)
            }
            if (drinkTiltDeg != 0f) {
                canvas.rotate(drinkTiltDeg, clipCenter.x, clipCenter.y)
            }
            if (foodBitesTotal > 0) {
                val radius = maxOf(restBounds.width(), restBounds.height()) / 2f * scale + 4f
                val remaining = (foodBitesLeft.toFloat() / foodBitesTotal).coerceIn(0f, 1f)
                val path = android.graphics.Path().apply {
                    moveTo(clipCenter.x, clipCenter.y)
                    addArc(
                        clipCenter.x - radius, clipCenter.y - radius,
                        clipCenter.x + radius, clipCenter.y + radius,
                        -90f, remaining * 360f,
                    )
                    close()
                }
                canvas.clipPath(path)
            }
        }

        // Curved-radius segments (segment_curve_radius != 0) are how some rigs build a "circle"
        // out of plain bones instead of a Circle node (see e.g. TCO/TDL/TSC/Victim heads) -
        // drawing each one as its own straight line rendered a visible octagon. Stick Nodes
        // treats the whole run as one smooth ring, so a run of consecutive curved bones (chained
        // by object identity: next.start === prev.end) is stroked as ONE continuous path,
        // smoothed through the joints via the standard "quadratic through midpoints" trick,
        // instead of separately - see sn_proto_wasm_renderer memory for why per-segment bowing
        // looked worse (a "flower of petals": every segment's own round line-cap still bulged at
        // its joint on top of the added curve).
        // Head is always the deepest/last-drawn thing in the tree for both rig templates (see
        // src/customCharacters.js on the desktop side) - capturing whichever is drawn LAST here
        // instead of adding separate head-detection logic means the face/accessory always land in
        // the right place for either the Circle head (normal) or the ring head (hollow).
        var headCenter: PointF? = null
        var headRadius = 0f

        var i = 0
        while (i < bones.size) {
            val bone = bones[i]
            if (bone.nodeType == "Circle" || bone.nodeType == "FilledCircle") {
                val modelRadius = (bone.length * RigLayout.CIRCLE_RADIUS_FACTOR).coerceAtLeast(2f)
                val center = tx(RigLayout.circleCenter(bone, modelRadius))
                val r = modelRadius * scale
                if (!bone.hollow) {
                    fillPaint.color = bone.color
                    canvas.drawCircle(center.x, center.y, r, fillPaint)
                }
                if (bone.outline || bone.hollow) {
                    outlinePaint.color = bone.outlineColor
                    canvas.drawCircle(center.x, center.y, r, outlinePaint)
                }
                headCenter = center
                headRadius = r
                i++
                continue
            }

            // Polygon nodes (kitchen/food props): Ellipse fills an ellipse centered on the segment
            // (rx = l/2 along the bone, ry = th/2 across, canvas rotated to the bone's angle -
            // same as character.js's ellipse branch), Triangle/Trapezoid fill their corner polygon
            // computed in RigLayout.polygonCorners. Honoring hollow/outline like the circle branch
            // keeps these consistent with how Stick Nodes draws non-stroke nodes; rounded
            // trapezoid ends (rdS/rdE) get a filled disc of that end's width, like the desktop.
            if (bone.nodeType == "Ellipse") {
                val (rx, ry) = RigLayout.ellipseRadii(bone)
                val center = tx(RigLayout.ellipseCenter(bone))
                val rad = -Math.toDegrees(
                    Math.atan2((bone.end.y - bone.start.y).toDouble(), (bone.end.x - bone.start.x).toDouble())
                ).toFloat()
                val path = android.graphics.Path().apply {
                    addOval(
                        android.graphics.RectF(
                            center.x - rx * scale, center.y - ry * scale,
                            center.x + rx * scale, center.y + ry * scale,
                        ),
                        android.graphics.Path.Direction.CW,
                    )
                }
                canvas.save()
                canvas.rotate(rad, center.x, center.y)
                if (!bone.hollow) {
                    fillPaint.color = bone.color
                    canvas.drawPath(path, fillPaint)
                }
                if (bone.outline || bone.hollow) {
                    outlinePaint.color = bone.outlineColor
                    canvas.drawPath(path, outlinePaint)
                }
                canvas.restore()
                i++
                continue
            }

            if (bone.nodeType == "Triangle" || bone.nodeType == "Trapezoid") {
                val corners = RigLayout.polygonCorners(bone)
                val path = android.graphics.Path()
                corners.forEachIndexed { idx, c ->
                    val p = tx(c)
                    if (idx == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                if (!bone.hollow) {
                    fillPaint.color = bone.color
                    canvas.drawPath(path, fillPaint)
                }
                if (bone.outline || bone.hollow) {
                    outlinePaint.color = bone.outlineColor
                    canvas.drawPath(path, outlinePaint)
                }
                if (bone.nodeType == "Trapezoid") {
                    // Rounded trapezoid ends - cap each end with a filled disc of that end's width.
                    if (bone.trapezoidRoundedStart) {
                        val s = tx(bone.start)
                        fillPaint.color = bone.color
                        canvas.drawCircle(s.x, s.y, bone.trapezoidHalfStart.coerceAtLeast(1f) * scale, fillPaint)
                    }
                    if (bone.trapezoidRoundedEnd) {
                        val e = tx(bone.end)
                        fillPaint.color = bone.color
                        canvas.drawCircle(e.x, e.y, bone.trapezoidHalfEnd.coerceAtLeast(1f) * scale, fillPaint)
                    }
                }
                i++
                continue
            }

            // thickness 0 means "invisible structural connector" (e.g. a long thin spoke
            // positioning a segment-built head's ring relative to the neck) - Stick Nodes
            // doesn't render a hairline for it, so skip the stroke entirely.
            if (bone.thickness <= 0f) {
                i++
                continue
            }

            if (bone.curveRadius != 0f) {
                val chain = mutableListOf(bone)
                var j = i + 1
                while (j < bones.size && bones[j].curveRadius != 0f && bones[j].start === chain.last().end) {
                    chain.add(bones[j])
                    j++
                }
                val pts = (listOf(chain.first().start) + chain.map { it.end }).map(::tx)
                val path = Path().apply { moveTo(pts[0].x, pts[0].y) }
                for (k in 1 until pts.size - 1) {
                    val mx = (pts[k].x + pts[k + 1].x) / 2f
                    val my = (pts[k].y + pts[k + 1].y) / 2f
                    path.quadTo(pts[k].x, pts[k].y, mx, my)
                }
                path.lineTo(pts.last().x, pts.last().y)
                strokePaint.strokeWidth = (bone.thickness * scale).coerceAtLeast(1f)
                strokePaint.color = bone.color
                canvas.drawPath(path, strokePaint)
                var cx = 0f
                var cy = 0f
                for (p in pts) {
                    cx += p.x / pts.size
                    cy += p.y / pts.size
                }
                headCenter = PointF(cx, cy)
                headRadius = kotlin.math.hypot((pts[0].x - cx).toDouble(), (pts[0].y - cy).toDouble()).toFloat()
                i = j
                continue
            }

            val s = tx(bone.start)
            val e = tx(bone.end)
            strokePaint.strokeWidth = (bone.thickness * scale).coerceAtLeast(1f)
            strokePaint.color = bone.color
            canvas.drawLine(s.x, s.y, e.x, e.y, strokePaint)
            i++
        }

        // Pop the food/drink canvas transform so the face (and any future overlays) aren't
        // clipped/rotated with the wedge-shorn food.
        if (clipCenter != null) canvas.restore()

        headCenter?.let { center ->
            if (hasFace) FaceRenderer.drawFace(canvas, center.x, center.y, headRadius, eyeStyle, mouthStyle)
            FaceRenderer.drawAccessory(canvas, center.x, center.y, headRadius, accessory)
        }
    }
}
