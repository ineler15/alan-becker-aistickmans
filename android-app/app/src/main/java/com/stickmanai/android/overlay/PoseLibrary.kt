package com.stickmanai.android.overlay

import kotlin.math.max
import kotlin.math.sin

/**
 * Bone paths for the shared rig topology (root -> [leg1, leg2, torso-chain], torso-chain's neck
 * hub -> [arm1, arm2, head-stalk]) - see sn_proto_wasm_renderer memory for how this was mapped
 * out from the parsed .nodes tree. Stick Nodes files carry no bone names, only tree order, so
 * these paths only hold for rigs sharing this exact structure.
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

/**
 * A rig's own authored rest angles for the bones above (from its rigs/<id>.json) - poses swing
 * around these rather than a single hardcoded set, since Red/Blue/Green/Yellow (a true recolor,
 * identical rig data) and TCO/Orange (a different rig sharing this same bone *topology*, but with
 * its own limb orientation) don't share the same numbers, even though the tree shape matches.
 */
private data class RestAngles(
    val leg1: Float,
    val leg1Shin: Float,
    val leg2: Float,
    val leg2Shin: Float,
    val torsoLower: Float,
    val arm1: Float,
    val arm2: Float,
)

// Confirmed identical across Red/Blue/Green/Yellow (a true recolor - same rig data) by comparing
// their actual angle values, not just tree shape.
private val RED_TOPOLOGY_REST = RestAngles(
    leg1 = 246.8f, leg1Shin = 24.71f,
    leg2 = 294.53f, leg2Shin = -26.79f,
    torsoLower = 88.71f,
    arm1 = -207.92f, arm2 = -154.29f,
)

// TCO and Orange (The Second Coming) share this exact same bone-path topology as Red's group
// (confirmed by walking both trees) but were authored with a different limb orientation.
private val TCO_TOPOLOGY_REST = RestAngles(
    leg1 = -66.8f, leg1Shin = -384.7f,
    leg2 = -114.5f, leg2Shin = -333.2f,
    torsoLower = 89.4f,
    arm1 = -143.3f, arm2 = -212.7f,
)

private const val TWO_PI = (2.0 * Math.PI).toFloat()

/**
 * Blends `from` a fraction `t` of the way toward `to`, going by the shortest way around the
 * circle instead of naive linear subtraction - e.g. blending from -66.8 toward 270 the "long way"
 * (336.8 degrees) lands somewhere nowhere near either angle, when the actual shortest path is
 * only -23.2 degrees. Matters once poses stopped being tuned against a single hardcoded rest
 * angle (Red's) and started taking wildly different per-character rest angles (TCO/Orange).
 */
private fun blendToward(from: Float, to: Float, t: Float): Float {
    var diff = (to - from) % 360f
    if (diff > 180f) diff -= 360f
    if (diff < -180f) diff += 360f
    return from + diff * t
}

/**
 * Named/procedural poses as angle overrides on top of a rig's own authored rest pose. Angles are
 * absolute local_angle replacements (same convention as RigNode.localAngleDeg), not deltas - each
 * pose function takes the character's RestAngles and swings around those, so the same pose logic
 * works for any character sharing this bone topology regardless of its specific rest orientation.
 *
 * None of these are matched against a real keyframe - Stick Nodes only gave us one static rest
 * pose per character (see sn_proto_wasm_renderer memory), so every non-STAND pose here is a
 * first approximation tuned by eye against Red, not verified per-character. Expect to adjust
 * after actually looking at each character rendered on-device.
 */
object PoseLibrary {
    val STAND: Pose = emptyMap()

    // Deltas derived from Red's original hand-tuned SIT (leg1 190 vs rest 246.8, etc.) - was
    // hardcoded as absolute angles disconnected from `rest` entirely (the compiler even flagged
    // the unused parameter), which only happened to look right for Red/Blue/Green/Yellow because
    // they all share Red's exact rest angles. For TCO/Orange (a very different rest orientation)
    // those same absolutes produced a pose that read as something else entirely - fixed to be
    // relative to each character's own rest, same as every other pose here.
    private fun sitPose(rest: RestAngles): Pose = mapOf(
        BonePaths.LEG1 to rest.leg1 - 56.8f,
        BonePaths.LEG1_SHIN to rest.leg1Shin + 65.29f,
        BonePaths.LEG2 to rest.leg2 + 55.47f,
        BonePaths.LEG2_SHIN to rest.leg2Shin - 63.21f,
        BonePaths.TORSO_LOWER to rest.torsoLower + 6.29f,
    )

    private fun fallPose(rest: RestAngles): Pose = mapOf(
        BonePaths.TORSO_LOWER to rest.torsoLower - 40f,
        BonePaths.ARM1 to rest.arm1 - 60f,
        BonePaths.ARM2 to rest.arm2 + 60f,
        BonePaths.LEG1 to rest.leg1 + 30f,
        BonePaths.LEG2 to rest.leg2 - 30f,
    )

    /** Walking/running gait: legs swing opposite phase, opposite arm swings with each leg, knees bend on the forward swing. */
    private fun walkPose(rest: RestAngles, frame: Int, running: Boolean): Pose {
        val period = if (running) 6f else 8f
        val amplitude = if (running) 48f else 28f
        val kneeBend = if (running) 38f else 18f
        val phase = TWO_PI * (frame % period) / period
        val legSwing = amplitude * sin(phase)
        return mapOf(
            BonePaths.LEG1 to rest.leg1 + legSwing,
            BonePaths.LEG1_SHIN to rest.leg1Shin + kneeBend * max(0f, sin(phase)),
            BonePaths.LEG2 to rest.leg2 - legSwing,
            BonePaths.LEG2_SHIN to rest.leg2Shin + kneeBend * max(0f, sin(phase + Math.PI.toFloat())),
            BonePaths.ARM1 to rest.arm1 - legSwing,
            BonePaths.ARM2 to rest.arm2 + legSwing,
        )
    }

    /** Jump/happy: both legs crouch then extend together, arms lift on the extend. */
    private fun bouncePose(rest: RestAngles, frame: Int): Pose {
        val period = 6f
        val phase = TWO_PI * (frame % period) / period
        val squat = 22f * max(0f, sin(phase))
        return mapOf(
            BonePaths.LEG1_SHIN to rest.leg1Shin + squat,
            BonePaths.LEG2_SHIN to rest.leg2Shin + squat,
            BonePaths.ARM1 to rest.arm1 - squat,
            BonePaths.ARM2 to rest.arm2 + squat,
        )
    }

    /** Off-balance stumble: torso pitched forward, arms flailing for balance. */
    private fun tripPose(rest: RestAngles, frame: Int): Pose {
        val jitter = 15f * sin(TWO_PI * frame / 5f)
        return mapOf(
            BonePaths.TORSO_LOWER to rest.torsoLower - 25f,
            BonePaths.ARM1 to rest.arm1 + 40f + jitter,
            BonePaths.ARM2 to rest.arm2 - 40f - jitter,
            BonePaths.LEG1 to rest.leg1 + 20f,
            BonePaths.LEG2 to rest.leg2 - 10f,
        )
    }

    /**
     * Dangling from the head (dragged/pinched): matches the real pinch01-07.png sprites - body
     * hangs limp with legs pulled together (not spread like standing) and arms close to the
     * torso, the whole body swaying together like a pendulum rather than each limb independently.
     */
    private fun pinchPose(rest: RestAngles, frame: Int): Pose {
        val sway = 6f * sin(TWO_PI * frame / 14f)
        val leg1Tuck = blendToward(rest.leg1, 270f, 0.5f)
        val leg2Tuck = blendToward(rest.leg2, 270f, 0.5f)
        return mapOf(
            BonePaths.TORSO_LOWER to rest.torsoLower + sway,
            BonePaths.LEG1 to leg1Tuck + sway,
            BonePaths.LEG2 to leg2Tuck + sway,
            BonePaths.LEG1_SHIN to rest.leg1Shin * 0.3f,
            BonePaths.LEG2_SHIN to rest.leg2Shin * 0.3f,
            BonePaths.ARM1 to rest.arm1 + sway * 1.5f,
            BonePaths.ARM2 to rest.arm2 + sway * 1.5f,
        )
    }

    /** Angry: stomps one foot repeatedly - shin lifts back then slams down, arms held tense. */
    private fun angryPose(rest: RestAngles, frame: Int): Pose {
        val period = 6f
        val phase = TWO_PI * (frame % period) / period
        val stomp = 30f * max(0f, sin(phase))
        return mapOf(
            BonePaths.LEG1_SHIN to rest.leg1Shin - stomp,
            BonePaths.ARM1 to rest.arm1 + 25f,
            BonePaths.ARM2 to rest.arm2 - 25f,
            BonePaths.TORSO_LOWER to rest.torsoLower - 8f,
        )
    }

    /** Climbing a wall: limbs bent and reaching like gripping a ledge, alternating as it scurries up. */
    private fun climbPose(rest: RestAngles, frame: Int): Pose {
        val period = 8f
        val phase = TWO_PI * (frame % period) / period
        val limbSwing = 25f * sin(phase)
        return mapOf(
            BonePaths.LEG1 to rest.leg1 + 20f + limbSwing,
            BonePaths.LEG1_SHIN to rest.leg1Shin + 30f,
            BonePaths.LEG2 to rest.leg2 - 20f - limbSwing,
            BonePaths.LEG2_SHIN to rest.leg2Shin + 30f,
            BonePaths.ARM1 to rest.arm1 - 40f - limbSwing,
            BonePaths.ARM2 to rest.arm2 + 40f + limbSwing,
        )
    }

    /** Tired: slumped sitting, arms hanging - matches desktop's "tired"/couch01 semantics. */
    private fun tiredPose(rest: RestAngles): Pose = mapOf(
        BonePaths.TORSO_LOWER to rest.torsoLower - 30f,
        BonePaths.ARM1 to rest.arm1 - 20f,
        BonePaths.ARM2 to rest.arm2 + 20f,
        BonePaths.LEG1 to rest.leg1 + 15f,
        BonePaths.LEG1_SHIN to rest.leg1Shin + 40f,
        BonePaths.LEG2 to rest.leg2 - 15f,
        BonePaths.LEG2_SHIN to rest.leg2Shin + 40f,
    )

    /** Asleep: legs straightened together, arms relaxed, a slow "breathing" sway - the view itself gets rotated 90 to lie flat, see CharacterOverlay. */
    private fun sleepPose(rest: RestAngles, frame: Int): Pose {
        val breathe = 4f * sin(TWO_PI * frame / 20f)
        return mapOf(
            BonePaths.LEG1 to rest.leg1 + 10f,
            BonePaths.LEG1_SHIN to rest.leg1Shin * 0.2f,
            BonePaths.LEG2 to rest.leg2 - 10f,
            BonePaths.LEG2_SHIN to rest.leg2Shin * 0.2f,
            BonePaths.ARM1 to rest.arm1 + breathe,
            BonePaths.ARM2 to rest.arm2 - breathe,
            BonePaths.TORSO_LOWER to rest.torsoLower + breathe * 0.5f,
        )
    }

    // Friendly names an AI-authored keyframe can use (see CharacterState.Keyframe/startCustomAnimation)
    // mapped to the actual bone paths - keeps the raw path lists (child-index lists, meaningless
    // to a model) out of the AI-facing schema entirely.
    private val NAME_TO_PATH = mapOf(
        "torso" to BonePaths.TORSO_LOWER,
        "leg1" to BonePaths.LEG1,
        "leg1Shin" to BonePaths.LEG1_SHIN,
        "leg2" to BonePaths.LEG2,
        "leg2Shin" to BonePaths.LEG2_SHIN,
        "arm1" to BonePaths.ARM1,
        "arm2" to BonePaths.ARM2,
    )

    /** Builds a Pose from an AI-authored keyframe's friendly-named angles; unknown names are ignored. */
    fun customPose(angles: Map<String, Float>): Pose =
        angles.mapNotNull { (name, angle) -> NAME_TO_PATH[name]?.let { it to angle } }.toMap()

    // The bone paths above only hold for a rig sharing this exact bone tree shape (mapped out by
    // hand per topology - see sn_proto_wasm_renderer memory). Red/Blue/Green/Yellow are a true
    // recolor (identical rig data, confirmed by comparing actual angle values, not just tree
    // shape). TCO/Orange (The Second Coming) share the same bone *topology* as that group but
    // were authored with a different limb orientation, hence the separate RestAngles. Purple has
    // a genuinely different tree shape, and TDL/victim build their head out of a segment ring
    // AND have a different topology than TCO/Orange despite superficially similar depth - none of
    // those three have been mapped, so they just stand for now.
    private val REST_BY_ID = mapOf(
        "Red" to RED_TOPOLOGY_REST,
        "Blue" to RED_TOPOLOGY_REST,
        "Green" to RED_TOPOLOGY_REST,
        "Yellow" to RED_TOPOLOGY_REST,
        "TCO" to TCO_TOPOLOGY_REST,
        "Orange" to TCO_TOPOLOGY_REST,
    )

    fun forFrameKind(kind: CharacterState.FrameKind, characterId: String): Pose {
        val rest = REST_BY_ID[characterId] ?: return STAND
        return when (kind) {
            CharacterState.FrameKind.Stand -> STAND
            CharacterState.FrameKind.Sit -> sitPose(rest)
            is CharacterState.FrameKind.Walk -> walkPose(rest, kind.frame, running = false)
            is CharacterState.FrameKind.Run -> walkPose(rest, kind.frame, running = true)
            is CharacterState.FrameKind.Bounce -> bouncePose(rest, kind.frame)
            is CharacterState.FrameKind.Trip -> tripPose(rest, kind.frame)
            is CharacterState.FrameKind.Fall -> fallPose(rest)
            is CharacterState.FrameKind.Pinch -> pinchPose(rest, kind.frame)
            is CharacterState.FrameKind.Angry -> angryPose(rest, kind.frame)
            is CharacterState.FrameKind.Climb -> climbPose(rest, kind.frame)
            is CharacterState.FrameKind.Sleep -> sleepPose(rest, kind.frame)
            is CharacterState.FrameKind.Tired -> tiredPose(rest)
            is CharacterState.FrameKind.Custom -> customPose(kind.angles)
        }
    }
}
