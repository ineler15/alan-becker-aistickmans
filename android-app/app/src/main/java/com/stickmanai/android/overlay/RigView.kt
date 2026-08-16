package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.View

/**
 * Draws a Stick Nodes rig (bones as rounded lines, head as a filled circle) instead of a raster
 * sprite frame. The current pose's bounding box is always fit-and-centered inside the view like
 * the sn-proto prototype does, EXCEPT the scale factor itself is locked to the rig's rest pose
 * (poseOverride = emptyMap) so the character doesn't visibly grow/shrink switching between poses
 * of different heights (e.g. standing vs sitting) - only translation follows the current pose.
 */
class RigView(context: Context, private val figure: RigFigure) : View(context) {

    var pose: Pose = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    private val restBounds = RigLayout.bounds(RigLayout.layout(figure.root, figure.bodyColor, emptyMap()))

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
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

        for (bone in bones) {
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
            } else {
                val s = tx(bone.start)
                val e = tx(bone.end)
                strokePaint.strokeWidth = (bone.thickness * scale).coerceAtLeast(1f)
                strokePaint.color = bone.color
                canvas.drawLine(s.x, s.y, e.x, e.y, strokePaint)
            }
        }
    }
}
