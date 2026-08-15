package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Loads a character's sprite frames from assets/sprites/<id>/ (e.g. stand01.png). Some characters
 * are missing a frame or two (e.g. run12, fall02) - loadFrames() just skips whatever isn't there
 * instead of failing the whole set, same tolerance the desktop AIBehavior.tryLoad() has.
 */
class SpriteSet private constructor(
    val stand: Bitmap,
    val walk: List<Bitmap>,
    val run: List<Bitmap>,
    val fall: List<Bitmap>,
    val pinch: List<Bitmap>,
    val bounce: List<Bitmap>,
    val trip: List<Bitmap>,
) {
    companion object {
        private val cache = HashMap<String, SpriteSet>()

        fun forCharacter(context: Context, characterId: String): SpriteSet =
            cache.getOrPut(characterId) { load(context, characterId) }

        private fun load(context: Context, characterId: String): SpriteSet {
            val assets = context.assets
            fun frame(name: String): Bitmap? = try {
                assets.open("sprites/$characterId/$name.png").use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            fun frames(prefix: String, count: Int): List<Bitmap> =
                (1..count).mapNotNull { frame(prefix + "%02d".format(it)) }

            val stand = frame("stand01") ?: throw IllegalStateException("missing stand01 for $characterId")
            return SpriteSet(
                stand = stand,
                walk = frames("walk", 5),
                run = frames("run", 12),
                fall = frames("fall", 2),
                pinch = frames("pinch", 7),
                bounce = frames("bounce", 4),
                trip = frames("trip", 6),
            )
        }
    }
}
