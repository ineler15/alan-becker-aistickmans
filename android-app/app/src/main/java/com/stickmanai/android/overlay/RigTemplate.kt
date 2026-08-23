package com.stickmanai.android.overlay

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a custom character's rig by cloning Red's rig topology (already validated - see
 * RigModel.kt/RigView.kt) and overriding only the top-level color and the head's hollow flag.
 * Mirrors src/customCharacters.js's buildRig() on the desktop app so "crear tu propio stickman"
 * produces the same result on both platforms.
 */
object RigTemplate {

    // Head is the single Circle/FilledCircle node in the whole tree - found recursively instead
    // of hardcoded by path, since which branch it sits under is just an artifact of how Red's
    // rig happened to be authored.
    private fun findHeadNode(node: JSONObject): JSONObject? {
        if (node.getString("t") == "Circle" || node.getString("t") == "FilledCircle") return node
        val children = node.getJSONArray("ch")
        for (i in 0 until children.length()) {
            findHeadNode(children.getJSONObject(i))?.let { return it }
        }
        return null
    }

    /** color is [r,g,b,a] 0-255. Used both for the live preview and for the saved rig. */
    fun build(context: Context, color: IntArray, hollow: Boolean): JSONObject {
        val template = context.assets.open("rigs/Red.json").use { JSONObject(it.reader().readText()) }
        template.put("color", JSONArray(color.toList()))
        findHeadNode(template.getJSONObject("root"))?.put("hollow", hollow)
        return template
    }

    /** Writes filesDir/custom_rigs/<id>.json and returns it. */
    fun save(context: Context, id: String, rig: JSONObject): File {
        val dir = File(context.filesDir, "custom_rigs")
        dir.mkdirs()
        val file = File(dir, "$id.json")
        file.writeText(rig.toString())
        return file
    }
}
