package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/**
 * One bone of a Stick Nodes rig, trimmed down to what RigLayout/RigView need. Matches the "slim"
 * JSON produced from the sn-proto WASM prototype's parse_nodes_file() output (see
 * D:\JMATEO\stickman-scratch\sn-proto\index.html) - field names are shortened (t/a/l/th/...) to
 * keep the bundled asset small since the full Stick Nodes schema has many unused properties
 * (gradients, trapezoids, smart-stretch, etc.) we don't render.
 */
data class RigNode(
    val nodeType: String,
    val localAngleDeg: Float,
    val length: Float,
    val thickness: Float,
    val scale: Float,
    val color: Int?,
    val hollow: Boolean,
    val outline: Boolean,
    val outlineColor: Int?,
    // Some rigs build a "circle" head out of several plain segments instead of a Circle node -
    // each one bows slightly (Stick Nodes' segment_curve_radius) so the ring reads as round
    // instead of an octagon when the whole run is drawn as one smoothed stroke (see RigView).
    // 0 for a straight segment (true for every bone in Red's rig).
    val curveRadius: Float,
    val children: List<RigNode>,
)

class RigFigure(val bodyColor: Int, val root: RigNode) {
    companion object {
        private val cache = HashMap<String, RigFigure>()

        /** Null if there's no rigs/<characterId>.json asset - callers fall back to sprites. */
        fun forCharacterOrNull(context: Context, characterId: String): RigFigure? {
            cache[characterId]?.let { return it }
            val json = try {
                context.assets.open("rigs/$characterId.json").use { it.reader().readText() }
            } catch (e: Exception) {
                return null
            }
            val obj = JSONObject(json)
            val figure = RigFigure(colorFrom(obj.getJSONArray("color")), parseNode(obj.getJSONObject("root")))
            cache[characterId] = figure
            return figure
        }

        private fun colorFrom(arr: JSONArray): Int =
            Color.argb(arr.getInt(3), arr.getInt(0), arr.getInt(1), arr.getInt(2))

        private fun parseNode(o: JSONObject): RigNode {
            val childrenArr = o.getJSONArray("ch")
            val children = (0 until childrenArr.length()).map { parseNode(childrenArr.getJSONObject(it)) }
            val outline = o.getBoolean("outline")
            return RigNode(
                nodeType = o.getString("t"),
                localAngleDeg = o.getDouble("a").toFloat(),
                length = o.getDouble("l").toFloat(),
                thickness = o.getDouble("th").toFloat(),
                scale = o.getDouble("sc").toFloat(),
                color = if (o.getBoolean("usc")) colorFrom(o.getJSONArray("c")) else null,
                hollow = o.getBoolean("hollow"),
                outline = outline,
                outlineColor = if (outline) colorFrom(o.getJSONArray("oc")) else null,
                curveRadius = o.optDouble("cr", 0.0).toFloat(),
                children = children,
            )
        }
    }
}
