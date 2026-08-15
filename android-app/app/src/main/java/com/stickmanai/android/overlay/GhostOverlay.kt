package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView

/**
 * A non-interactive stand-in for a character that actually lives on the PC, rendered here purely
 * from the position/lastSay the desktop's peer server reports - no physics, no touch, just a
 * static sprite placed proportionally on this screen and a speech bubble when it says something.
 */
class GhostOverlay(context: Context, private val windowManager: WindowManager, characterId: String) {

    private val sprites = SpriteSet.forCharacter(context, characterId)
    private val density = context.resources.displayMetrics.density
    private val sizePx = (128 * density).toInt()
    private var lastSayShown: String? = null
    private var sayUntil = 0L
    private var screenHeight = 0
    // Same reasoning as CharacterOverlay: a ghost parked at a fixed floor position would sit
    // right where a text field usually is once the keyboard is up, so it's hidden outright
    // instead of just repositioned.
    private var keyboardInsetPx = 0

    private val imageView = ImageView(context).apply {
        setImageBitmap(sprites.stand)
        alpha = 0.85f // slightly translucent so it visually reads as "not really here"
    }
    private val speechView = TextView(context).apply {
        setBackgroundColor(Color.parseColor("#EEE0F0FF")) // faint blue tint to distinguish from local speech
        setTextColor(Color.BLACK)
        setPadding(16, 8, 16, 8)
        textSize = 13f
        visibility = View.GONE
    }

    private val imageParams = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val speechParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    fun attach() {
        windowManager.addView(imageView, imageParams)
        windowManager.addView(speechView, speechParams)
        imageView.viewTreeObserver.addOnGlobalLayoutListener {
            if (screenHeight == 0) return@addOnGlobalLayoutListener
            val rect = Rect()
            imageView.getWindowVisibleDisplayFrame(rect)
            val covered = screenHeight - rect.bottom
            keyboardInsetPx = if (covered > screenHeight * 0.15) covered else 0
        }
    }

    fun detach() {
        try { windowManager.removeView(imageView) } catch (e: Exception) { /* already gone */ }
        try { windowManager.removeView(speechView) } catch (e: Exception) { /* already gone */ }
    }

    /** floorY/screenWidthPx are this tablet's own metrics; xPercent (0-100) came from the PC's own screen width. */
    fun update(xPercent: Int, screenWidthPx: Int, floorY: Int, lastSay: String?) {
        screenHeight = floorY + (48 * density).toInt()
        if (keyboardInsetPx > 0) {
            imageView.visibility = View.GONE
            speechView.visibility = View.GONE
            return
        }
        imageView.visibility = View.VISIBLE
        val x = (xPercent / 100.0 * screenWidthPx).toInt()
        imageParams.x = x - sizePx / 2
        imageParams.y = floorY - sizePx
        windowManager.updateViewLayout(imageView, imageParams)

        if (lastSay != null && lastSay != lastSayShown) {
            lastSayShown = lastSay
            sayUntil = System.currentTimeMillis() + 8000
        }
        if (lastSay != null && System.currentTimeMillis() < sayUntil) {
            speechView.text = lastSay
            speechView.visibility = View.VISIBLE
            speechParams.x = (imageParams.x - 20).coerceAtLeast(0)
            speechParams.y = (imageParams.y - (40 * density).toInt()).coerceAtLeast(0)
            windowManager.updateViewLayout(speechView, speechParams)
        } else {
            speechView.visibility = View.GONE
        }
    }
}
