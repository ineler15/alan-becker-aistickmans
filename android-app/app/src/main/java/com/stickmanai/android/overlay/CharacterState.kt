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
        const val CLIMB_SPEED = 3
        const val CLIMB_FRAME_TICKS = 4
        private const val EDGE_MARGIN = 4
        // Tiredness: forced to sleep either after being awake too long, or (with a shorter
        // threshold) if it's nighttime - matches the desktop's existing sleep/tired AIBehavior.java
        // states, but automatic here instead of only AI-chosen.
        const val AWAKE_MS_BEFORE_SLEEP = 20 * 60 * 1000L
        const val AWAKE_MS_BEFORE_SLEEP_AT_NIGHT = 10 * 60 * 1000L
        const val SLEEP_DURATION_MS = 5 * 60 * 1000L
        const val NIGHT_START_HOUR = 22
        const val NIGHT_END_HOUR = 7
        const val MAX_CUSTOM_KEYFRAMES = 12
        const val MIN_KEYFRAME_HOLD_MS = 100L
        const val MAX_KEYFRAME_HOLD_MS = 3000L
        const val DEFAULT_KEYFRAME_HOLD_MS = 400L
    }

    /**
     * One frame of an AI-authored custom animation - angles keyed by the same friendly bone
     * names PoseLibrary.customPose() understands (torso/leg1/leg1Shin/leg2/leg2Shin/arm1/arm2).
     * A bone omitted from a keyframe keeps whatever value the previous keyframe left it at
     * (or the rig's own rest angle, if no earlier keyframe set it either) rather than snapping
     * back to rest every frame - see CharacterState.tick()'s customAnimation handling.
     */
    data class Keyframe(val angles: Map<String, Float>, val holdMs: Long)

    var x: Int = screenWidth / 2
    var y: Int = floorY
    var lookRight: Boolean = true

    var beingDragged = false
    private var falling = false
    private var fallStartedAt = 0L
    private var moving = false
    private var running = false
    private var moveTargetX = 0

    // Wall-climbing: reaching a screen edge while walking climbs it instead of just stopping
    // there, like vanilla Shimeji's ClimbWall behavior.
    var climbing = false
        private set
    var climbSide = 0 // -1 = climbing the left edge, 1 = climbing the right edge
        private set
    private var climbTargetY = 0

    var sleeping = false
        private set
    private var awakeSinceMs = System.currentTimeMillis()
    private var sleepStartedAt = 0L

    private var customAnimation: List<Keyframe>? = null
    private var customIndex = 0
    private var customKeyframeStartedAt = 0L
    private var customAccumulatedAngles = mutableMapOf<String, Float>()

    private var frame = 0
    private var frameCounter = 0
    var loopEmotion: String? = null // "happy" (bounce), "sit", or "scared"/"trip" (trip) - null = normal walk/stand
        private set
    var sayUntil = 0L
        private set
    var speechText: String? = null
        private set

    fun startMoving(targetX: Int, run: Boolean) {
        beingDragged = false
        falling = false
        loopEmotion = null
        customAnimation = null
        moving = true
        running = run
        moveTargetX = targetX.coerceIn(0, screenWidth)
        lookRight = moveTargetX >= x
    }

    fun startFalling() {
        moving = false
        climbing = false
        loopEmotion = null
        customAnimation = null
        falling = true
        fallStartedAt = System.currentTimeMillis()
    }

    private fun startClimbing(side: Int) {
        climbing = true
        climbSide = side
        moving = false
        loopEmotion = null
        frame = 0
        frameCounter = 0
        climbTargetY = Random.nextInt((screenHeight * 0.1).toInt(), (screenHeight * 0.5).toInt())
    }

    /**
     * Starts an AI-authored custom animation - a sequence of keyframes played back in order,
     * instead of only the fixed named poses (sit/angry/etc). Clamped defensively since these
     * come straight from the model: at most MAX_CUSTOM_KEYFRAMES frames, each held for
     * MIN..MAX_KEYFRAME_HOLD_MS.
     */
    fun startCustomAnimation(keyframes: List<Keyframe>) {
        if (keyframes.isEmpty()) return
        beingDragged = false
        falling = false
        climbing = false
        moving = false
        loopEmotion = null
        if (sleeping) wakeUp()
        customAnimation = keyframes.take(MAX_CUSTOM_KEYFRAMES).map {
            it.copy(holdMs = it.holdMs.coerceIn(MIN_KEYFRAME_HOLD_MS, MAX_KEYFRAME_HOLD_MS))
        }
        customIndex = 0
        customKeyframeStartedAt = System.currentTimeMillis()
        customAccumulatedAngles = mutableMapOf()
    }

    fun setEmotion(state: String?) {
        moving = false
        falling = false
        customAnimation = null
        if (state == "sleep") { startSleeping(); return }
        loopEmotion = state
        frame = 0
        frameCounter = 0
    }

    private fun isNightNow(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    private fun shouldForceSleep(): Boolean {
        val awakeMs = System.currentTimeMillis() - awakeSinceMs
        val threshold = if (isNightNow()) AWAKE_MS_BEFORE_SLEEP_AT_NIGHT else AWAKE_MS_BEFORE_SLEEP
        return awakeMs > threshold
    }

    private fun startSleeping() {
        sleeping = true
        sleepStartedAt = System.currentTimeMillis()
        beingDragged = false
        moving = false
        falling = false
        climbing = false
        loopEmotion = null
        frame = 0
        frameCounter = 0
    }

    private fun wakeUp() {
        sleeping = false
        awakeSinceMs = System.currentTimeMillis()
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
            if (sleeping) wakeUp()
            customAnimation = null
            return FrameKind.Pinch(frame)
        }
        customAnimation?.let { keyframes ->
            val elapsed = System.currentTimeMillis() - customKeyframeStartedAt
            if (elapsed > keyframes[customIndex].holdMs) {
                customAccumulatedAngles.putAll(keyframes[customIndex].angles)
                customIndex++
                customKeyframeStartedAt = System.currentTimeMillis()
            }
            if (customIndex >= keyframes.size) {
                customAnimation = null
            } else {
                return FrameKind.Custom(customAccumulatedAngles + keyframes[customIndex].angles)
            }
        }
        if (sleeping) {
            if (System.currentTimeMillis() - sleepStartedAt > SLEEP_DURATION_MS) {
                wakeUp()
            } else {
                frameCounter++
                if (frameCounter >= WALK_FRAME_TICKS) {
                    frameCounter = 0
                    frame++
                }
                return FrameKind.Sleep(frame)
            }
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
        if (climbing) {
            if (kotlin.math.abs(climbTargetY - y) <= CLIMB_SPEED) {
                y = climbTargetY
                startFalling()
                return FrameKind.Fall(0)
            }
            y += if (climbTargetY > y) CLIMB_SPEED else -CLIMB_SPEED
            frameCounter++
            if (frameCounter >= CLIMB_FRAME_TICKS) {
                frameCounter = 0
                frame++
            }
            return FrameKind.Climb(frame)
        }
        if (moving) {
            val speed = if (running) RUN_SPEED else WALK_SPEED
            val ticksPerFrame = if (running) RUN_FRAME_TICKS else WALK_FRAME_TICKS
            if (kotlin.math.abs(moveTargetX - x) <= speed) {
                x = moveTargetX
                moving = false
                if (x <= EDGE_MARGIN || x >= screenWidth - EDGE_MARGIN) {
                    startClimbing(if (x <= EDGE_MARGIN) -1 else 1)
                    return FrameKind.Climb(0)
                }
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
            if (it == "sit") return FrameKind.Sit
            frameCounter++
            if (frameCounter >= WALK_FRAME_TICKS) {
                frameCounter = 0
                frame++
            }
            return when (it) {
                "happy" -> FrameKind.Bounce(frame)
                "angry" -> FrameKind.Angry(frame)
                "tired" -> FrameKind.Tired(frame)
                else -> FrameKind.Trip(frame)
            }
        }
        if (shouldForceSleep()) {
            startSleeping()
            return FrameKind.Sleep(0)
        }
        return FrameKind.Stand
    }

    sealed class FrameKind {
        object Stand : FrameKind()
        object Sit : FrameKind()
        data class Walk(val frame: Int) : FrameKind()
        data class Run(val frame: Int) : FrameKind()
        data class Fall(val frame: Int) : FrameKind()
        data class Pinch(val frame: Int) : FrameKind()
        data class Bounce(val frame: Int) : FrameKind()
        data class Trip(val frame: Int) : FrameKind()
        data class Angry(val frame: Int) : FrameKind()
        data class Climb(val frame: Int) : FrameKind()
        data class Sleep(val frame: Int) : FrameKind()
        data class Tired(val frame: Int) : FrameKind()
        data class Custom(val angles: Map<String, Float>) : FrameKind()
    }
}
