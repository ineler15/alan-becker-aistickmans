package com.stickmanai.android.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.stickmanai.android.CharacterDef
import com.stickmanai.android.BuildConfig
import com.stickmanai.android.CrashReporter
import com.stickmanai.android.Prefs
import com.stickmanai.android.input.TapAccessibilityService

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
    private val screenWidthPx: Int,
    private val screenHeightPx: Int,
    private val onTap: (String) -> Unit,
) {
    companion object {
        // Vanilla Shimeji's Dragged.tick() offsets the anchor 120/128 of the sprite height
        // below the cursor so the pinch point (near the head) lines up with the finger/cursor
        // instead of the feet - see Dragged.java and AIBehavior.rideCursor() on desktop.
        private const val PINCH_OFFSET_RATIO = 120f / 128f
        private const val TAP_MAX_MOVE_PX = 20
        private const val TAP_MAX_MS = 250L
        // Slam-into-edge damage: releasing a dragged character pinned against any screen edge
        // (left/right wall, ceiling, or slammed onto the floor) costs hp, like a fight hit.
        private const val EDGE_SLAM_MARGIN_DP = 8
        private const val SLAM_DAMAGE = 10
    }

    // Which renderer to use. Modern (rigs) prefers the procedural rig and falls back to sprites.
    // Legacy (sprites, RENDER_MODE=sprites like the desktop's legacy build) does the opposite:
    // sprites first, rig only for characters that have no sprite art (custom characters get a
    // generated rig but never sprite PNGs).
    private class RenderChoice(val rig: RigFigure?, val sprites: SpriteSet?) {
        companion object {
            fun pick(context: Context, id: String): RenderChoice {
                val forceSprites = BuildConfig.RENDER_MODE == "sprites"
                val rig = RigFigure.forCharacterOrNull(context, id)
                if (forceSprites) {
                    return try {
                        RenderChoice(null, SpriteSet.forCharacter(context, id))
                    } catch (e: Exception) {
                        RenderChoice(rig, null)
                    }
                }
                return if (rig != null) {
                    RenderChoice(rig, null)
                } else {
                    RenderChoice(null, try { SpriteSet.forCharacter(context, id) } catch (e: Exception) { null })
                }
            }
        }
    }
    private val renderChoice = RenderChoice.pick(context, def.id)
    private val rigFigure = renderChoice.rig
    private val sprites = renderChoice.sprites
    // Tints TouchPointerOverlay's dot so with several characters able to tap (see
    // Prefs.allowScreenControl), it's visually clear which one is doing it right now.
    val pointerColor: Int get() = rigFigure?.bodyColor ?: Color.WHITE
    // A custom character's own id isn't in PoseLibrary's PROFILE_BY_ID - use whichever built-in
    // profile (Red/TCO) its rig was cloned from instead, so it actually animates. hasFace/gender
    // drive RigView's face+accessory drawing. null (built-in character) means no face, no
    // accessory, own id for poses - same as before any of this existed.
    private val customMeta = Prefs.customMeta(context, def.id)
    private val poseId = customMeta?.poseProfile ?: def.id
    private val density = context.resources.displayMetrics.density
    val sizePx = (128 * density).toInt()
    private val floorY = screenHeightPx - (48 * density).toInt()
    val state = CharacterState(screenWidthPx, screenHeightPx, floorY)

    val recentHistory = ArrayDeque<String>()
    var lastSayText: String? = null
        private set

    private val rigView: RigView? = rigFigure?.let {
        RigView(context, it, hasFace = customMeta?.hasFace ?: false, accessory = customMeta?.accessory ?: "none")
    }
    private val characterView: View = rigView ?: ImageView(context).apply { setImageBitmap(sprites!!.stand) }
    private val speechView = TextView(context).apply {
        setBackgroundColor(Color.parseColor("#EEFFFFFF"))
        setTextColor(Color.BLACK)
        setPadding(16, 8, 16, 8)
        textSize = 13f
        visibility = View.GONE
    }

    // Survival bars strip - created only while the system is on; hidden/removed entirely when
    // not, so a toggled-off run looks exactly like it did before the feature existed.
    private val barsW = (76 * density).toInt()
    private val barsH = (22 * density).toInt()
    private val statsView: SurvivalBarsView? = if (Prefs.survivalEnabled(context)) {
        SurvivalBarsView(context, def.id)
    } else null

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

    private val statsParams = WindowManager.LayoutParams(
        barsW, barsH,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    private var downRawX = 0f
    private var downRawY = 0f
    private var downAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        characterView.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(characterView, imageParams)
        windowManager.addView(speechView, speechParams)
        statsView?.let { statsView ->
            try {
                windowManager.addView(statsView, statsParams)
                statsView.stats = Prefs.survival(context, def.id)
            } catch (e: Throwable) {
                CrashReporter.report(context, "barra de stats ${def.id}", e)
            }
        }
        try {
            render()
        } catch (e: Throwable) {
            CrashReporter.report(context, "render inicial ${def.id}", e)
        }
    }

    fun detach() {
        try { windowManager.removeView(characterView) } catch (e: Exception) { /* already gone */ }
        try { windowManager.removeView(speechView) } catch (e: Exception) { /* already gone */ }
        statsView?.let { try { windowManager.removeView(it) } catch (e: Exception) { /* already gone */ } }
    }

    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // A dead character can't be dragged into life - only a user chat message revives
                // it (OverlayService passes player messages straight through regardless).
                if (state.dead) return false
                downRawX = event.rawX
                downRawY = event.rawY
                downAt = System.currentTimeMillis()
                state.beingDragged = true
                TouchTracker.record(event.rawX, event.rawY, screenWidthPx, screenHeightPx)
            }
            MotionEvent.ACTION_MOVE -> {
                if (state.beingDragged) {
                    state.dragTo(event.rawX.toInt(), (event.rawY + PINCH_OFFSET_RATIO * sizePx).toInt())
                    TouchTracker.record(event.rawX, event.rawY, screenWidthPx, screenHeightPx)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val movedPx = kotlin.math.hypot((event.rawX - downRawX).toDouble(), (event.rawY - downRawY).toDouble())
                val heldMs = System.currentTimeMillis() - downAt
                if (state.beingDragged) {
                    // Only an actual drag (past the tap threshold) counts - a plain tap on a
                    // resting character shouldn't deal floor-slam damage.
                    val hadSlam = movedPx > TAP_MAX_MOVE_PX &&
                        Prefs.survivalEnabled(context) && !state.dead && isEdgeSlam()
                    state.onRelease()
                    if (hadSlam) {
                        Prefs.applyDamage(context, def.id, SLAM_DAMAGE.toFloat())
                        val after = Prefs.survival(context, def.id)!!
                        state.say("¡Auch! ($SLAM_DAMAGE de daño)")
                        state.setFace("wide", "open")
                        if (after.dead) state.kill()
                    }
                }
                if (movedPx <= TAP_MAX_MOVE_PX && heldMs <= TAP_MAX_MS) {
                    onTap(def.id)
                }
            }
        }
        return true
    }

    // Released while pinned against an edge? dragTo() clamps x to [0, screenWidthPx] and y to
    // [0, floorY], so slamming against any extreme leaves the anchor near one of them.
    private fun isEdgeSlam(): Boolean {
        val margin = (EDGE_SLAM_MARGIN_DP * density).toInt()
        return state.x <= margin ||
            state.x >= screenWidthPx - margin ||
            state.y <= margin ||
            state.y >= floorY - margin
    }

    /** Runs one physics tick and repositions/re-renders both overlay windows. Call every ~40ms on the main thread. */
    fun tick() {
        val kind = state.tick()
        if (rigView != null) {
            rigView.pose = PoseLibrary.forFrameKind(kind, poseId)
            rigView.eyeStyle = state.eyeStyle
            rigView.mouthStyle = state.mouthStyle
        } else {
            val bitmap = when (kind) {
                is CharacterState.FrameKind.Stand -> sprites!!.stand
                is CharacterState.FrameKind.Walk -> sprites!!.walk.frameAt(kind.frame)
                is CharacterState.FrameKind.Run -> sprites!!.run.frameAt(kind.frame)
                is CharacterState.FrameKind.Fall -> sprites!!.fall.frameAt(kind.frame)
                is CharacterState.FrameKind.Pinch -> sprites!!.pinch.frameAt(kind.frame)
                is CharacterState.FrameKind.Bounce -> sprites!!.bounce.frameAt(kind.frame)
                is CharacterState.FrameKind.Trip -> sprites!!.trip.frameAt(kind.frame)
                // No sprite art for these - sprite-backed characters just stand instead.
                is CharacterState.FrameKind.Sit, is CharacterState.FrameKind.Angry,
                is CharacterState.FrameKind.Climb, is CharacterState.FrameKind.Sleep,
                is CharacterState.FrameKind.Tired, is CharacterState.FrameKind.Chew,
                is CharacterState.FrameKind.Drink, is CharacterState.FrameKind.Custom -> sprites!!.stand
            }
            (characterView as ImageView).setImageBitmap(bitmap)
        }
        if (state.climbing) {
            // Rotate to cling to the wall - head points away from the wall it's climbing.
            characterView.rotation = if (state.climbSide < 0) 90f else -90f
            characterView.scaleX = 1f
        } else if (state.sleeping) {
            // Lie flat on the ground instead of standing - reuses the same rotation trick as
            // climbing, just always to one side since "which way is down" doesn't depend on
            // anything here.
            characterView.rotation = 90f
            characterView.scaleX = 1f
        } else {
            characterView.rotation = 0f
            // Both sprites and the rig face left by default - mirror only when walking right.
            characterView.scaleX = if (state.lookRight) -1f else 1f
        }
        render()
    }

    private fun List<android.graphics.Bitmap>.frameAt(i: Int): android.graphics.Bitmap =
        if (isEmpty()) sprites!!.stand else this[i % size]

    private fun render() {
        // Just lifting the character above the keyboard still parks it right at the keyboard's
        // top edge - exactly where a text field being typed into usually sits, so it kept
        // covering the very thing the user was writing in. Hiding it outright while the
        // keyboard is up is the only way to guarantee it's never "in the way" of typing; the
        // underlying physics (state.x/state.y) keeps running so it resumes normally once the
        // keyboard closes. isKeyboardVisible() is recomputed every tick (cheap: one IPC-free
        // list scan) and works in ANY foreground app, not just this one - see TapAccessibilityService.
        if (TapAccessibilityService.isKeyboardVisible()) {
            characterView.visibility = View.GONE
            speechView.visibility = View.GONE
            statsView?.visibility = View.GONE
            return
        }
        characterView.visibility = View.VISIBLE
        imageParams.x = state.x - sizePx / 2
        imageParams.y = state.y - sizePx
        windowManager.updateViewLayout(characterView, imageParams)

        // Survival strip sits centered above the head, tracking the character every tick.
        statsView?.let { view ->
            view.visibility = View.VISIBLE
            view.stats = Prefs.survival(context, def.id)
            statsParams.x = imageParams.x + sizePx / 2 - barsW / 2
            statsParams.y = (imageParams.y - barsH - (4 * density).toInt()).coerceAtLeast(0)
            windowManager.updateViewLayout(view, statsParams)
        }

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
