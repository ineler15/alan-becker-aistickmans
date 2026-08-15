package com.stickmanai.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.stickmanai.android.CharacterDef

/**
 * One character's pair of overlay windows (the sprite itself + its speech bubble), plus the
 * physics state and small rolling history that feeds the AI prompt. All positioning here mirrors
 * AIBehavior.java: an "anchor" point at the sprite's feet/bottom-center, converted to the
 * WindowManager view's top-left corner.
 */
class CharacterOverlay(
    private val context: Context,
    val def: CharacterDef,
    private val windowManager: WindowManager,
    screenWidthPx: Int,
    screenHeightPx: Int,
    private val onTap: (String) -> Unit,
) {
    companion object {
        // Vanilla Shimeji's Dragged.tick() offsets the anchor 120/128 of the sprite height
        // below the cursor so the pinch point (near the head) lines up with the finger/cursor
        // instead of the feet - see Dragged.java and AIBehavior.rideCursor() on desktop.
        private const val PINCH_OFFSET_RATIO = 120f / 128f
        private const val TAP_MAX_MOVE_PX = 20
        private const val TAP_MAX_MS = 250L
    }

    private val sprites = SpriteSet.forCharacter(context, def.id)
    private val density = context.resources.displayMetrics.density
    val sizePx = (128 * density).toInt()
    private val floorY = screenHeightPx - (48 * density).toInt()
    private val screenHeight = screenHeightPx
    val state = CharacterState(screenWidthPx, screenHeightPx, floorY)

    // Overlay windows don't get resized by the keyboard the way an app window would, so the
    // character would otherwise keep resting at its usual floor position UNDER the keyboard -
    // both covering it and (since the character's hitbox eats touches) blocking taps on it.
    // getWindowVisibleDisplayFrame() still reports the keyboard cutting into this window's
    // visible area, so that's used to detect it and lift the character above it visually.
    private var keyboardInsetPx = 0

    val recentHistory = ArrayDeque<String>()
    var lastSayText: String? = null
        private set

    private val imageView = ImageView(context).apply {
        setImageBitmap(sprites.stand)
    }
    private val speechView = TextView(context).apply {
        setBackgroundColor(Color.parseColor("#EEFFFFFF"))
        setTextColor(Color.BLACK)
        setPadding(16, 8, 16, 8)
        textSize = 13f
        visibility = View.GONE
    }

    private val imageParams = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // NOT_TOUCH_MODAL so touches outside this tiny window still reach the app behind it;
        // NOT_FOCUSABLE so it never steals keyboard/input focus from whatever app is in front.
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private val speechParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        imageView.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(imageView, imageParams)
        windowManager.addView(speechView, speechParams)
        imageView.viewTreeObserver.addOnGlobalLayoutListener {
            val rect = Rect()
            imageView.getWindowVisibleDisplayFrame(rect)
            val covered = screenHeight - rect.bottom
            // Small covered slivers are just status/nav bar chrome, not a keyboard - require a
            // sizeable chunk before treating it as one.
            keyboardInsetPx = if (covered > screenHeight * 0.15) covered else 0
        }
        render()
    }

    fun detach() {
        try { windowManager.removeView(imageView) } catch (e: Exception) { /* already gone */ }
        try { windowManager.removeView(speechView) } catch (e: Exception) { /* already gone */ }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downAt = System.currentTimeMillis()
                state.beingDragged = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (state.beingDragged) {
                    state.dragTo(event.rawX.toInt(), (event.rawY + PINCH_OFFSET_RATIO * sizePx).toInt())
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val movedPx = kotlin.math.hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())
                val heldMs = System.currentTimeMillis() - downAt
                if (state.beingDragged) state.onRelease()
                if (movedPx <= TAP_MAX_MOVE_PX && heldMs <= TAP_MAX_MS) {
                    onTap(def.id)
                }
            }
        }
        return true
    }

    /** Runs one physics tick and repositions/re-renders both overlay windows. Call every ~40ms on the main thread. */
    fun tick() {
        val kind = state.tick()
        val bitmap = when (kind) {
            is CharacterState.FrameKind.Stand -> sprites.stand
            is CharacterState.FrameKind.Walk -> sprites.walk.frameAt(kind.frame)
            is CharacterState.FrameKind.Run -> sprites.run.frameAt(kind.frame)
            is CharacterState.FrameKind.Fall -> sprites.fall.frameAt(kind.frame)
            is CharacterState.FrameKind.Pinch -> sprites.pinch.frameAt(kind.frame)
            is CharacterState.FrameKind.Bounce -> sprites.bounce.frameAt(kind.frame)
            is CharacterState.FrameKind.Trip -> sprites.trip.frameAt(kind.frame)
        }
        imageView.setImageBitmap(bitmap)
        // Sprites face left by default (matches the desktop assets) - mirror only when walking right.
        imageView.scaleX = if (state.lookRight) -1f else 1f
        render()
    }

    private fun List<android.graphics.Bitmap>.frameAt(i: Int): android.graphics.Bitmap =
        if (isEmpty()) sprites.stand else this[i % size]

    private fun render() {
        // Clamp the visible feet position above the keyboard when it's up - the underlying
        // physics (state.x/state.y) keeps running normally, only where it's drawn changes, so
        // walking/dragging behavior is unaffected once the keyboard closes again.
        val feetY = if (keyboardInsetPx > 0) minOf(state.y, screenHeight - keyboardInsetPx) else state.y
        imageParams.x = state.x - sizePx / 2
        imageParams.y = feetY - sizePx
        windowManager.updateViewLayout(imageView, imageParams)

        if (state.speechText != null) {
            speechView.text = state.speechText
            speechView.visibility = View.VISIBLE
            speechParams.x = (imageParams.x - 20).coerceAtLeast(0)
            speechParams.y = (imageParams.y - (40 * density).toInt()).coerceAtLeast(0)
            windowManager.updateViewLayout(speechView, speechParams)
        } else {
            speechView.visibility = View.GONE
        }
    }

    fun addHistory(entry: String) {
        recentHistory.addLast(entry)
        while (recentHistory.size > 8) recentHistory.removeFirst()
    }

    fun say(text: String) {
        state.say(text)
        lastSayText = text
    }

    fun xPercent(screenWidthPx: Int): Int = ((state.x * 100) / screenWidthPx).coerceIn(0, 100)
}
