package com.stickmanai.android.overlay

import android.graphics.PointF
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One drawable bone in model space (before any canvas scale/offset). Mirrors the layout() walk
 * in the sn-proto WASM prototype (D:\JMATEO\stickman-scratch\sn-proto\index.html) - child.start
 * == parent.end, angles accumulate down the tree since Stick Nodes stores each bone's angle
 * relative to its parent, not in world space.
 */
data class Bone(
    val nodeType: String,
    val start: PointF,
    val end: PointF,
    val length: Float,
    val thickness: Float,
    val color: Int,
    val hollow: Boolean,
    val outline: Boolean,
    val outlineColor: Int,
    val curveRadius: Float,
)

/**
 * A pose is a set of angle overrides (degrees, absolute - replaces the rig's authored
 * local_angle) keyed by the bone's child-index path from the root, e.g. listOf(0) for the root's
 * first child. Paths are how bones are identified since Stick Nodes .nodes files carry no bone
 * names - only stable as long as characters share the same topology as Red (2 legs, spine, 2
 * arms, head), which is all we support for now.
 */
typealias Pose = Map<List<Int>, Float>

object RigLayout {
    fun layout(root: RigNode, figureColor: Int, pose: Pose): List<Bone> {
        val acc = mutableListOf<Bone>()

        fun walk(node: RigNode, path: List<Int>, parentAngleDeg: Float, parentEnd: PointF) {
            val isRoot = node.nodeType == "RootNode"
            val localAngle = pose[path] ?: node.localAngleDeg
            val globalAngleDeg = if (isRoot) localAngle else parentAngleDeg + localAngle
            val start = if (isRoot) PointF(0f, 0f) else parentEnd
            val rad = Math.toRadians(globalAngleDeg.toDouble())
            val localX = if (isRoot) 0f else (node.length * cos(rad) * node.scale).toFloat()
            // Screen space has +y pointing down, but angles here follow Stick Nodes' math
            // convention (+y up), so the y offset is negated - same as the prototype's layout().
            val localY = if (isRoot) 0f else (-node.length * sin(rad) * node.scale).toFloat()
            val end = PointF(start.x + localX, start.y + localY)

            if (!isRoot) {
                acc.add(
                    Bone(
                        nodeType = node.nodeType,
                        start = start,
                        end = end,
                        length = node.length,
                        thickness = node.thickness,
                        color = node.color ?: figureColor,
                        hollow = node.hollow,
                        outline = node.outline,
                        outlineColor = node.outlineColor ?: android.graphics.Color.BLACK,
                        curveRadius = node.curveRadius,
                    )
                )
            }
            node.children.forEachIndexed { i, child -> walk(child, path + i, globalAngleDeg, end) }
        }

        walk(root, emptyList(), 0f, PointF(0f, 0f))
        return acc
    }

    /**
     * Model-space circle center for a Circle/FilledCircle bone: offset from its start point by
     * its own radius along the start->end direction, so the circle's near edge sits at the joint
     * instead of being centered on it (which sinks the head halfway into the neck) or floating a
     * full bone-length above it (both tried and rejected live against the "Red" rig - see
     * sn_proto_wasm_renderer memory).
     */
    fun circleCenter(bone: Bone, radius: Float): PointF {
        val dx = bone.end.x - bone.start.x
        val dy = bone.end.y - bone.start.y
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1e-3f)
        return PointF(bone.start.x + dx / dist * radius, bone.start.y + dy / dist * radius)
    }

    /** Diameter-to-radius factor matched live against the prototype - see sn_proto_wasm_renderer memory. */
    const val CIRCLE_RADIUS_FACTOR = 0.65f

    fun bounds(bones: List<Bone>): android.graphics.RectF {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        fun include(x: Float, y: Float) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        for (bone in bones) {
            if (bone.nodeType == "Circle" || bone.nodeType == "FilledCircle") {
                val r = (bone.length * CIRCLE_RADIUS_FACTOR).coerceAtLeast(2f)
                val c = circleCenter(bone, r)
                include(c.x - r, c.y - r)
                include(c.x + r, c.y + r)
            } else {
                include(bone.start.x, bone.start.y)
                include(bone.end.x, bone.end.y)
            }
        }
        if (minX > maxX) return android.graphics.RectF(0f, 0f, 1f, 1f)
        return android.graphics.RectF(minX, minY, maxX, maxY)
    }
}
