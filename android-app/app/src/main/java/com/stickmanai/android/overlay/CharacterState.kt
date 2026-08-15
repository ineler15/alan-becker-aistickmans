package com.stickmanai.android.overlay

import kotlin.random.Random

/**
 * Port of the physics-lite state machine from AIBehavior.java (desktop) - same priority chain
 * (dragged > falling > moving > loop animation > idle) and similar speed/timing constants,
 * just running on Android's own tick loop instead of Shimeji's 40ms native tick.
 */
class CharacterState(private val screenWidth: Int, private val screenHeight: Int, private val floorY: Int) {

    companion object {
        const val TICK_MS = 40L
        const val WALK_SPEED = 3
        const val RUN_SPEED = 7
        const val WALK_FRAME_TICKS = 4
        const val RUN_FRAME_TICKS = 2
        const val FALL_SPEED = 6
        const val FALL_FRAME_TICKS = 3
        const val FALL_TIMEOUT_MS = 4000L
        const val SAY_DURATION_MIN_MS = 8000L
        const val SAY_DURATION_PER_CHAR_MS = 90L
    }

    var x: Int = screenWidth / 2
    var y: Int = floorY
    var lookRight: Boolean = true

    var beingDragged = false
    private var falling = false
    private var fallStartedAt = 0L
    private var moving = false
    private var running = false
    private var moveTargetX = 0

    private var frame = 0
    private var frameCounter = 0
    var loopEmotion: String? = null // "happy" (bounce) or "scared"/"trip" (trip) - null = normal walk/stand
        private set
    var sayUntil = 0L
        private set
    var speechText: String? = null
        private set

    fun startMoving(targetX: Int, run: Boolean) {
        beingDragged = false
        falling = false
        loopEmotion = null
        moving = true
        running = run
        moveTargetX = targetX.coerceIn(0, screenWidth)
        lookRight = moveTargetX >= x
    }

    fun startFalling() {
        moving = false
        loopEmotion = null
        falling = true
        fallStartedAt = System.currentTimeMillis()
    }

    fun setEmotion(state: String?) {
        moving = false
        falling = false
        loopEmotion = state
        frame = 0
        frameCounter = 0
    }

    fun say(text: String) {
        speechText = text
        sayUntil = System.currentTimeMillis() +
            SAY_DURATION_MIN_MS.coerceAtLeast(text.length * SAY_DURATION_PER_CHAR_MS)
    }

    fun randomTarget(run: Boolean = false) {
        startMoving(Random.nextInt(0, screenWidth), run)
    }

    /** Called every TICK_MS while beingDragged, following the finger. */
    fun dragTo(px: Int, py: Int) {
        x = px
        y = py
        frameCounter++
        if (frameCounter >= WALK_FRAME_TICKS) {
            frameCounter = 0
            frame++
        }
    }

    fun onRelease() {
        beingDragged = false
        startFalling()
    }

    /** Advances physics one tick; returns the sprite frame index to show for the current state. */
    fun tick(): FrameKind {
        if (System.currentTimeMillis() > sayUntil) speechText = null

        if (beingDragged) {
            return FrameKind.Pinch(frame)
        }
        if (falling) {
            if (System.currentTimeMillis() - fallStartedAt > FALL_TIMEOUT_MS || y >= floorY) {
                falling = false
                y = floorY
                return FrameKind.Stand
            }
            y = (y + FALL_SPEED).coerceAtMost(floorY)
            frameCounter++
            if (frameCounter >= FALL_FRAME_TICKS) {
                frameCounter = 0
                frame++
            }
            return FrameKind.Fall(frame)
        }
        if (moving) {
            val speed = if (running) RUN_SPEED else WALK_SPEED
            val ticksPerFrame = if (running) RUN_FRAME_TICKS else WALK_FRAME_TICKS
            if (kotlin.math.abs(moveTargetX - x) <= speed) {
                x = moveTargetX
                moving = false
                return FrameKind.Stand
            }
            x += if (moveTargetX > x) speed else -speed
            frameCounter++
            if (frameCounter >= ticksPerFrame) {
                frameCounter = 0
                frame++
            }
            return if (running) FrameKind.Run(frame) else FrameKind.Walk(frame)
        }
        loopEmotion?.let {
            frameCounter++
            if (frameCounter >= WALK_FRAME_TICKS) {
                frameCounter = 0
                frame++
            }
            return if (it == "happy") FrameKind.Bounce(frame) else FrameKind.Trip(frame)
        }
        return FrameKind.Stand
    }

    sealed class FrameKind {
        object Stand : FrameKind()
        data class Walk(val frame: Int) : FrameKind()
        data class Run(val frame: Int) : FrameKind()
        data class Fall(val frame: Int) : FrameKind()
        data class Pinch(val frame: Int) : FrameKind()
        data class Bounce(val frame: Int) : FrameKind()
        data class Trip(val frame: Int) : FrameKind()
    }
}
