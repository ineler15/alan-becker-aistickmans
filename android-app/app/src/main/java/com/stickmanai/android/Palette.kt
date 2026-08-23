package com.stickmanai.android

/**
 * Same 8 swatches as the desktop app's src/customCharacters.js PALETTE, so "crear tu propio
 * stickman" looks the same on both platforms - the 6 existing character colors plus black/white.
 */
object Palette {
    val COLORS: List<IntArray> = listOf(
        intArrayOf(254, 0, 0, 255),
        intArrayOf(255, 140, 0, 255),
        intArrayOf(0, 170, 0, 255),
        intArrayOf(0, 100, 255, 255),
        intArrayOf(230, 200, 0, 255),
        intArrayOf(160, 0, 200, 255),
        intArrayOf(255, 255, 255, 255),
        intArrayOf(20, 20, 20, 255),
    )

    fun toColorInt(rgba: IntArray): Int =
        android.graphics.Color.argb(rgba[3], rgba[0], rgba[1], rgba[2])
}
