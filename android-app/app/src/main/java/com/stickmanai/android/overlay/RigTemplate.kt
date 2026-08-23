package com.stickmanai.android.overlay

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds a custom character's rig by cloning an existing character's rig topology and overriding
 * only the top-level color. Mirrors src/customCharacters.js's buildRig() on the desktop app so
 * "crear tu propio stickman" produces the same result on both platforms.
 *
 * "Normal" head = Red's rig: the head is one filled Circle node. "Hollow" head = TCO's rig: the
 * head is a chain of curved RoundedSegment bones stroked into a ring (RigView already draws these
 * chains as one smoothed path) - there's no Circle node in it at all, so it can't be produced by
 * flipping a flag on Red's head. TCO was picked over TDL (same ring construction) because TDL's
 * rig also carries a sword prop (extra colored Segment nodes) that don't belong on a generic
 * custom character.
 */
object RigTemplate {

    private fun templateAssetFor(hollow: Boolean) = if (hollow) "rigs/TCO.json" else "rigs/Red.json"

    /** color is [r,g,b,a] 0-255. Used both for the live preview and for the saved rig. */
    fun build(context: Context, color: IntArray, hollow: Boolean): JSONObject {
        val template = context.assets.open(templateAssetFor(hollow)).use { JSONObject(it.reader().readText()) }
        template.put("color", JSONArray(color.toList()))
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

    /** Reads back a previously-saved custom rig, e.g. to recover its color for "editar personaje". */
    fun load(context: Context, id: String): JSONObject? {
        val file = File(File(context.filesDir, "custom_rigs"), "$id.json")
        if (!file.exists()) return null
        return JSONObject(file.readText())
    }
}
