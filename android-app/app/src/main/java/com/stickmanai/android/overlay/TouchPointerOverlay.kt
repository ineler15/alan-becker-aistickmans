package com.stickmanai.android.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator

/**
 * A small colored dot that visibly travels from a character's position to wherever it's about to
 * tap, colored like that character (see CharacterOverlay.pointerColor) - the "puntito que se
 * mueve para tocar" the user asked for, so a tap reads as an intentional, visible action instead
 * of an instant invisible touch. Only ever shown when Prefs.allowScreenControl is on.
 */
object TouchPointerOverlay {
    private const val SIZE_DP = 28f
    private const val TRAVEL_MS = 220L
    private const val LINGER_MS = 150L

    fun animateAndTap(
        context: Context,
        windowManager: WindowManager,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        color: Int,
        onArrived: () -> Unit,
    ) {
        val density = context.resources.displayMetrics.density
        val sizePx = (SIZE_DP * density).toInt()
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke((2 * density).toInt(), 0x66000000.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (fromX - sizePx / 2).toInt()
            y = (fromY - sizePx / 2).toInt()
        }

        try {
            windowManager.addView(dot, params)
        } catch (e: Exception) {
            onArrived()
            return
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = TRAVEL_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                params.x = (fromX + (toX - fromX) * t - sizePx / 2).toInt()
                params.y = (fromY + (toY - fromY) * t - sizePx / 2).toInt()
                try {
                    windowManager.updateViewLayout(dot, params)
                } catch (e: Exception) {
                    /* view may already be gone */
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onArrived()
                    dot.postDelayed({
                        try {
                            windowManager.removeView(dot)
                        } catch (e: Exception) {
                            /* already gone */
                        }
                    }, LINGER_MS)
                }
            })
            start()
        }
    }
}
