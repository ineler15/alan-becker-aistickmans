package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.stickmanai.android.CrashReporter
import kotlin.random.Random

/**
 * The kitchen prop: a small static window (18% of the screen width, bottom-anchored like the
 * desktop's kitchen window) holding a RigView with one of the kitchen background stations - the
 * same rigs character.js cycles on PC (kitchen -> kitchen-1 -> kitchen-2). OverlayService drives
 * charThe station swap (nextStation(), fading between them) so the kitchen "comes alive" without
 * any AI involvement.
 */
class KitchenOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenWidthPx: Int,
) {
    private val density = context.resources.displayMetrics.density
    // Each station's side length: the biggest dimension of any station rig's rest bounds,
    // auto-scaled by RigView's pivot-fit anyway, so a single square size works for all three.
    private val sizePx = (screenWidthPx * 0.18f).toInt()
    private val stationIds = listOf("kitchen", "kitchen-1", "kitchen-2")
    private var stationIndex = Random.nextInt(stationIds.size)

    // The kitchen rigs ship in assets/rigs/ - if one is ever missing, the whole overlay is a no-op.
    private val rigView: RigView? = RigFigure.forCharacterOrNull(context, stationIds[stationIndex])?.let { RigView(context, it) }

    private val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = screenWidthPx - sizePx - (8 * density).toInt()
        y = context.resources.displayMetrics.heightPixels - sizePx - (8 * density).toInt()
    }

    private val handler = Handler(Looper.getMainLooper())

    private val fadeOut = object : Runnable {
        override fun run() {
            val view = rigView ?: return
            // Fade old station out, swap in the new one at full alpha, fade back in.
            try {
                view.animate().alpha(0f).setDuration(500).withEndAction {
                    stationIndex = (stationIndex + 1) % stationIds.size
                    val next = RigFigure.forCharacterOrNull(context, stationIds[stationIndex]) ?: return@withEndAction
                    view.setFigure(next)
                    view.alpha = 0f
                    view.animate().alpha(1f).setDuration(500).start()
                }.start()
            } catch (e: Throwable) {
                CrashReporter.report(context, "cocina: cambiar estacion", e)
            }
        }
    }

    fun attach() {
        rigView?.let { windowManager.addView(it, params); it.alpha = 1f }
    }

    fun nextStation() {
        handler.post(fadeOut)
    }

    // Center of the kitchen window in screen px - eat/drink range checks and the food-prop slide
    // origin both use this (OverlayService). Null when no kitchen rig is on screen.
    fun getPosition(): Pair<Int, Int>? =
        rigView?.let { Pair(params.x + sizePx / 2, params.y + sizePx / 2) }

    fun detach() {
        handler.removeCallbacks(fadeOut)
        rigView?.let { it.animate().cancel(); try { windowManager.removeView(it) } catch (e: Exception) { /* already gone */ } }
    }
}