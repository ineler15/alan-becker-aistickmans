package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import com.stickmanai.android.CrashReporter

/**
 * The kitchen prop: a small static window (18% of the screen width, bottom-right anchored like the
 * desktop's kitchen window) showing the user's kitchen photo (assets/kitchen/kitchen.webp) instead
 * of the rig backgrounds PC used to cycle. nextStation() stays for API compatibility with
 * OverlayService's playEat/playDrink but does nothing - a single photo has no stations.
 */
class KitchenOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val screenWidthPx: Int,
) {
    private val density = context.resources.displayMetrics.density
    private val sizePx = (screenWidthPx * 0.18f).toInt()

    private val imageView: ImageView? = run {
        try {
            context.assets.open("kitchen/kitchen.webp").use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)?.let { bitmap ->
                    ImageView(context).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                }
            }
        } catch (e: Exception) {
            // Missing/corrupt asset - the whole overlay is a no-op, same as a missing kitchen rig.
            CrashReporter.report(context, "cocina: cargar foto", e)
            null
        }
    }

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

    fun attach() {
        imageView?.let { windowManager.addView(it, params) }
    }

    // Kept for API compatibility; the single-photo kitchen has no stations to fade between.
    fun nextStation() {}

    // Center of the kitchen window in screen px - eat/drink range checks and the food-prop slide
    // origin both use this (OverlayService). Null when no kitchen photo is on screen.
    fun getPosition(): Pair<Int, Int>? =
        imageView?.let { Pair(params.x + sizePx / 2, params.y + sizePx / 2) }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        imageView?.let { try { windowManager.removeView(it) } catch (e: Exception) { /* already gone */ } }
    }
}