package com.stickmanai.android.overlay

import kotlin.math.max
import kotlin.math.sin

/**
 * Bone paths for Red's rig topology (root -> [leg1, leg2, torso-chain], torso-chain's neck hub ->
 * [arm1, arm2, head-stalk]) - see sn_proto_wasm_renderer memory for how this was mapped out from
 * the parsed .nodes tree. Stick Nodes files carry no bone names, only tree order, so these paths
 * only hold for rigs sharing this exact structure (currently just Red).
 */
private object BonePaths {
    val LEG1 = listOf(0)
    val LEG1_SHIN = listOf(0, 0)
    val LEG2 = listOf(1)
    val LEG2_SHIN = listOf(1, 0)
    val TORSO_LOWER = listOf(2)
    val ARM1 = listOf(2, 0, 0, 0)
    val ARM2 = listOf(2, 0, 0, 1)
}

// Red's authored rest angles (Red.json), used as the base that procedural poses swing around.
private const val REST_LEG1 = 246.8f
private const val REST_LEG1_SHIN = 24.71f
private const val REST_LEG2 = 294.53f
private const val REST_LEG2_SHIN = -26.79f
private const val REST_TORSO_LOWER = 88.71f
private const val REST_ARM1 = -207.92f
private const val REST_ARM2 = -154.29f

private const val TWO_PI = (2.0 * Math.PI).toFloat()

/**
 * Named/procedural poses as angle overrides on top of the rig's authored rest pose. Angles are
 * absolute local_angle replacements (same convention as RigNode.localAngleDeg), not deltas.
 *
 * None of these are matched against a real keyframe - Stick Nodes only gave us one static rest
 * pose per character (see sn_proto_wasm_renderer memory), so every non-STAND pose here is a
 * first approximation tuned by eye, built to be easy to retune rather than "correct". Expect to
 * adjust after looking at each one rendered on-device.
 */
object PoseLibrary {
    val STAND: Pose = emptyMap()

    val SIT: Pose = mapOf(
        BonePaths.LEG1 to 190f,
        BonePaths.LEG1_SHIN to 90f,
        BonePaths.LEG2 to 350f,
        BonePaths.LEG2_SHIN to -90f,
        BonePaths.TORSO_LOWER to 95f,
    )

    val FALL: Pose = mapOf(
        BonePaths.TORSO_LOWER to REST_TORSO_LOWER - 40f,
        BonePaths.ARM1 to REST_ARM1 - 60f,
        BonePaths.ARM2 to REST_ARM2 + 60f,
        BonePaths.LEG1 to REST_LEG1 + 30f,
        BonePaths.LEG2 to REST_LEG2 - 30f,
    )

    /** Walking/running gait: legs swing opposite phase, opposite arm swings with each leg, knees bend on the forward swing. */
    private fun walkPose(frame: Int, running: Boolean): Pose {
        val period = if (running) 6f else 8f
        val amplitude = if (running) 48f else 28f
        val kneeBend = if (running) 38f else 18f
        val phase = TWO_PI * (frame % period) / period
        val legSwing = amplitude * sin(phase)
        return mapOf(
            BonePaths.LEG1 to REST_LEG1 + legSwing,
            BonePaths.LEG1_SHIN to REST_LEG1_SHIN + kneeBend * max(0f, sin(phase)),
            BonePaths.LEG2 to REST_LEG2 - legSwing,
            BonePaths.LEG2_SHIN to REST_LEG2_SHIN + kneeBend * max(0f, sin(phase + Math.PI.toFloat())),
            BonePaths.ARM1 to REST_ARM1 - legSwing,
            BonePaths.ARM2 to REST_ARM2 + legSwing,
        )
    }

    /** Jump/happy: both legs crouch then extend together, arms lift on the extend. */
    private fun bouncePose(frame: Int): Pose {
        val period = 6f
        val phase = TWO_PI * (frame % period) / period
        val squat = 22f * max(0f, sin(phase))
        return mapOf(
            BonePaths.LEG1_SHIN to REST_LEG1_SHIN + squat,
            BonePaths.LEG2_SHIN to REST_LEG2_SHIN + squat,
            BonePaths.ARM1 to REST_ARM1 - squat,
            BonePaths.ARM2 to REST_ARM2 + squat,
        )
    }

    /** Off-balance stumble: torso pitched forward, arms flailing for balance. */
    private fun tripPose(frame: Int): Pose {
        val jitter = 15f * sin(TWO_PI * frame / 5f)
        return mapOf(
            BonePaths.TORSO_LOWER to REST_TORSO_LOWER - 25f,
            BonePaths.ARM1 to REST_ARM1 + 40f + jitter,
            BonePaths.ARM2 to REST_ARM2 - 40f - jitter,
            BonePaths.LEG1 to REST_LEG1 + 20f,
            BonePaths.LEG2 to REST_LEG2 - 10f,
        )
    }

    /**
     * Dangling from the head (dragged/pinched): matches the real pinch01-07.png sprites - body
     * hangs limp with legs pulled together (not spread like standing) and arms close to the
     * torso, the whole body swaying together like a pendulum rather than each limb independently.
     */
    private fun pinchPose(frame: Int): Pose {
        val sway = 6f * sin(TWO_PI * frame / 14f)
        val leg1Tuck = REST_LEG1 + (270f - REST_LEG1) * 0.5f
        val leg2Tuck = REST_LEG2 + (270f - REST_LEG2) * 0.5f
        return mapOf(
            BonePaths.TORSO_LOWER to REST_TORSO_LOWER + sway,
            BonePaths.LEG1 to leg1Tuck + sway,
            BonePaths.LEG2 to leg2Tuck + sway,
            BonePaths.LEG1_SHIN to REST_LEG1_SHIN * 0.3f,
            BonePaths.LEG2_SHIN to REST_LEG2_SHIN * 0.3f,
            BonePaths.ARM1 to REST_ARM1 + sway * 1.5f,
            BonePaths.ARM2 to REST_ARM2 + sway * 1.5f,
        )
    }

    /** Angry: stomps one foot repeatedly - shin lifts back then slams down, arms held tense. */
    private fun angryPose(frame: Int): Pose {
        val period = 6f
        val phase = TWO_PI * (frame % period) / period
        val stomp = 30f * max(0f, sin(phase))
        return mapOf(
            BonePaths.LEG1_SHIN to REST_LEG1_SHIN - stomp,
            BonePaths.ARM1 to REST_ARM1 + 25f,
            BonePaths.ARM2 to REST_ARM2 - 25f,
            BonePaths.TORSO_LOWER to REST_TORSO_LOWER - 8f,
        )
    }

    /** Climbing a wall: limbs bent and reaching like gripping a ledge, alternating as it scurries up. */
    private fun climbPose(frame: Int): Pose {
        val period = 8f
        val phase = TWO_PI * (frame % period) / period
        val limbSwing = 25f * sin(phase)
        return mapOf(
            BonePaths.LEG1 to REST_LEG1 + 20f + limbSwing,
            BonePaths.LEG1_SHIN to REST_LEG1_SHIN + 30f,
            BonePaths.LEG2 to REST_LEG2 - 20f - limbSwing,
            BonePaths.LEG2_SHIN to REST_LEG2_SHIN + 30f,
            BonePaths.ARM1 to REST_ARM1 - 40f - limbSwing,
            BonePaths.ARM2 to REST_ARM2 + 40f + limbSwing,
        )
    }

    /** Tired: slumped sitting, arms hanging - matches desktop's "tired"/couch01 semantics. */
    val TIRED: Pose = mapOf(
        BonePaths.TORSO_LOWER to REST_TORSO_LOWER - 30f,
        BonePaths.ARM1 to REST_ARM1 - 20f,
        BonePaths.ARM2 to REST_ARM2 + 20f,
        BonePaths.LEG1 to REST_LEG1 + 15f,
        BonePaths.LEG1_SHIN to REST_LEG1_SHIN + 40f,
        BonePaths.LEG2 to REST_LEG2 - 15f,
        BonePaths.LEG2_SHIN to REST_LEG2_SHIN + 40f,
    )

    /** Asleep: legs straightened together, arms relaxed, a slow "breathing" sway - the view itself gets rotated 90 to lie flat, see CharacterOverlay. */
    private fun sleepPose(frame: Int): Pose {
        val breathe = 4f * sin(TWO_PI * frame / 20f)
        return mapOf(
            BonePaths.LEG1 to REST_LEG1 + 10f,
            BonePaths.LEG1_SHIN to REST_LEG1_SHIN * 0.2f,
            BonePaths.LEG2 to REST_LEG2 - 10f,
            BonePaths.LEG2_SHIN to REST_LEG2_SHIN * 0.2f,
            BonePaths.ARM1 to REST_ARM1 + breathe,
            BonePaths.ARM2 to REST_ARM2 - breathe,
            BonePaths.TORSO_LOWER to REST_TORSO_LOWER + breathe * 0.5f,
        )
    }

    // The bone paths above are hardcoded for Red's specific topology (mapped out by hand - see
    // sn_proto_wasm_renderer memory) and only hold for a rig sharing that exact bone tree shape.
    // Blue/Green/Yellow are confirmed (by comparing each rig's node_type tree shape) to share
    // Red's exact topology - just recolored - so they reuse these poses directly. Every other
    // rig character (Purple has a genuinely different tree shape; TCO/TDL/Orange/victim build
    // their head out of a segment ring instead of a Circle node) hasn't been mapped, so applying
    // Red's paths to them would rotate the wrong bones - they just stand for now.
    private val SUPPORTED_IDS = setOf("Red", "Blue", "Green", "Yellow")

    fun forFrameKind(kind: CharacterState.FrameKind, characterId: String): Pose {
        if (characterId !in SUPPORTED_IDS) return STAND
        return when (kind) {
            CharacterState.FrameKind.Stand -> STAND
            CharacterState.FrameKind.Sit -> SIT
            is CharacterState.FrameKind.Walk -> walkPose(kind.frame, running = false)
            is CharacterState.FrameKind.Run -> walkPose(kind.frame, running = true)
            is CharacterState.FrameKind.Bounce -> bouncePose(kind.frame)
            is CharacterState.FrameKind.Trip -> tripPose(kind.frame)
            is CharacterState.FrameKind.Fall -> FALL
            is CharacterState.FrameKind.Pinch -> pinchPose(kind.frame)
            is CharacterState.FrameKind.Angry -> angryPose(kind.frame)
            is CharacterState.FrameKind.Climb -> climbPose(kind.frame)
            is CharacterState.FrameKind.Sleep -> sleepPose(kind.frame)
            is CharacterState.FrameKind.Tired -> TIRED
        }
    }
}
