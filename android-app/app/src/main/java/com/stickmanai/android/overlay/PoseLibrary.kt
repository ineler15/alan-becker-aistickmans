package com.stickmanai.android.overlay

import kotlin.math.max
import kotlin.math.sin

/**
 * Which child-index path each named bone lives at - see sn_proto_wasm_renderer memory for how
 * these were mapped out from the parsed .nodes tree. Two topologies exist among the characters
 * mapped so far: STANDARD (root -> [leg1, leg2, torso-chain], torso-chain's neck hub ->
 * [arm1, arm2, head-stalk] - Red/Blue/Green/Yellow/TCO/Orange) and ALT (root -> [torso-chain,
 * leg2, leg1], torso-chain's neck hub -> [head-stalk, arm2, arm1] - TDL/victim). Same rig family,
 * same rest angles even, just serialized with a different child order.
 */
private data class BonePathSet(
    val leg1: List<Int>,
    val leg1Shin: List<Int>,
    val leg2: List<Int>,
    val leg2Shin: List<Int>,
    val torsoLower: List<Int>,
    val arm1: List<Int>,
    val arm2: List<Int>,
)

private val STANDARD_PATHS = BonePathSet(
    leg1 = listOf(0), leg1Shin = listOf(0, 0),
    leg2 = listOf(1), leg2Shin = listOf(1, 0),
    torsoLower = listOf(2),
    arm1 = listOf(2, 0, 0, 0), arm2 = listOf(2, 0, 0, 1),
)

private val ALT_PATHS = BonePathSet(
    leg1 = listOf(2), leg1Shin = listOf(2, 0),
    leg2 = listOf(1), leg2Shin = listOf(1, 0),
    torsoLower = listOf(0),
    arm1 = listOf(0, 0, 0, 2), arm2 = listOf(0, 0, 0, 1),
)

/**
 * A rig's own authored rest angles for the bones above (from its rigs/<id>.json) - poses swing
 * around these rather than a single hardcoded set, since Red/Blue/Green/Yellow (a true recolor,
 * identical rig data) and TCO/Orange/TDL/victim (a different rig sharing this bone *topology*,
 * but with its own limb orientation) don't share the same numbers, even though the tree shape
 * (or, for TDL/victim, the tree shape modulo child order) matches.
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

// TCO/Orange (STANDARD path order) and TDL/victim (ALT path order) all share this exact same set
// of rest angles - confirmed by comparing actual values across all four trees, not just shape.
private val TCO_TOPOLOGY_REST = RestAngles(
    leg1 = -66.8f, leg1Shin = -384.7f,
    leg2 = -114.5f, leg2Shin = -333.2f,
    torsoLower = 89.4f,
    arm1 = -143.3f, arm2 = -212.7f,
)

private data class RigProfile(val paths: BonePathSet, val rest: RestAngles)

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
 * pose function takes the character's RigProfile (bone paths + rest angles) and swings around
 * those, so the same pose logic works for any character sharing a mapped topology regardless of
 * its specific rest orientation or child order.
 *
 * None of these are matched against a real keyframe - Stick Nodes only gave us one static rest
 * pose per character (see sn_proto_wasm_renderer memory), so every non-STAND pose here is a
 * first approximation tuned by eye against Red, not verified per-character. Expect to adjust
 * after actually looking at each character rendered on-device.
 */
object PoseLibrary {
    val STAND: Pose = emptyMap()

    /**
     * Idle sway instead of a perfectly frozen stand - just a slow "breathing" tilt on the torso
     * and a slight opposite arm sway, subtle enough to still read as standing still rather than
     * doing something, but enough that the character doesn't look frozen/dead between decisions.
     */
    private fun standPose(p: RigProfile, frame: Int): Pose {
        val sway = 1f * sin(TWO_PI * frame / 90f)
        return mapOf(
            p.paths.torsoLower to p.rest.torsoLower + sway,
            p.paths.arm1 to p.rest.arm1 + sway * 0.6f,
            p.paths.arm2 to p.rest.arm2 - sway * 0.6f,
        )
    }

    // Deltas derived from Red's original hand-tuned SIT (leg1 190 vs rest 246.8, etc.) - was
    // hardcoded as absolute angles disconnected from `rest` entirely (the compiler even flagged
    // the unused parameter), which only happened to look right for Red/Blue/Green/Yellow because
    // they all share Red's exact rest angles. For TCO/Orange (a very different rest orientation)
    // those same absolutes produced a pose that read as something else entirely - fixed to be
    // relative to each character's own rest, same as every other pose here.
    private fun sitPose(p: RigProfile): Pose = mapOf(
        p.paths.leg1 to p.rest.leg1 - 56.8f,
        p.paths.leg1Shin to p.rest.leg1Shin + 65.29f,
        p.paths.leg2 to p.rest.leg2 + 55.47f,
        p.paths.leg2Shin to p.rest.leg2Shin - 63.21f,
        p.paths.torsoLower to p.rest.torsoLower + 6.29f,
    )

    private fun fallPose(p: RigProfile): Pose = mapOf(
        p.paths.torsoLower to p.rest.torsoLower - 40f,
        p.paths.arm1 to p.rest.arm1 - 60f,
        p.paths.arm2 to p.rest.arm2 + 60f,
        p.paths.leg1 to p.rest.leg1 + 30f,
        p.paths.leg2 to p.rest.leg2 - 30f,
    )

    /** Walking/running gait: legs swing opposite phase, opposite arm swings with each leg, knees bend on the forward swing. */
    private fun walkPose(p: RigProfile, frame: Int, running: Boolean): Pose {
        val period = if (running) 6f else 8f
        val amplitude = if (running) 48f else 28f
        val kneeBend = if (running) 38f else 18f
        val phase = TWO_PI * (frame % period) / period
        val legSwing = amplitude * sin(phase)
        return mapOf(
            p.paths.leg1 to p.rest.leg1 + legSwing,
            p.paths.leg1Shin to p.rest.leg1Shin + kneeBend * max(0f, sin(phase)),
            p.paths.leg2 to p.rest.leg2 - legSwing,
            p.paths.leg2Shin to p.rest.leg2Shin + kneeBend * max(0f, sin(phase + Math.PI.toFloat())),
            p.paths.arm1 to p.rest.arm1 - legSwing,
            p.paths.arm2 to p.rest.arm2 + legSwing,
        )
    }

    /** Jump/happy: both legs crouch then extend together, arms lift on the extend. */
    private fun bouncePose(p: RigProfile, frame: Int): Pose {
        val period = 6f
        val phase = TWO_PI * (frame % period) / period
        val squat = 22f * max(0f, sin(phase))
        return mapOf(
            p.paths.leg1Shin to p.rest.leg1Shin + squat,
            p.paths.leg2Shin to p.rest.leg2Shin + squat,
            p.paths.arm1 to p.rest.arm1 - squat,
            p.paths.arm2 to p.rest.arm2 + squat,
        )
    }

    /** Off-balance stumble: torso pitched forward, arms flailing for balance. */
    private fun tripPose(p: RigProfile, frame: Int): Pose {
        val jitter = 15f * sin(TWO_PI * frame / 5f)
        return mapOf(
            p.paths.torsoLower to p.rest.torsoLower - 25f,
            p.paths.arm1 to p.rest.arm1 + 40f + jitter,
            p.paths.arm2 to p.rest.arm2 - 40f - jitter,
            p.paths.leg1 to p.rest.leg1 + 20f,
            p.paths.leg2 to p.rest.leg2 - 10f,
        )
    }

    /**
     * Dangling from the head (dragged/pinched): matches the real pinch01-07.png sprites - body
     * hangs limp with legs pulled together (not spread like standing) and arms close to the
     * torso, the whole body swaying together like a pendulum rather than each limb independently.
     */
    private fun pinchPose(p: RigProfile, frame: Int): Pose {
        val sway = 6f * sin(TWO_PI * frame / 14f)
        val leg1Tuck = blendToward(p.rest.leg1, 270f, 0.5f)
        val leg2Tuck = blendToward(p.rest.leg2, 270f, 0.5f)
        return mapOf(
            p.paths.torsoLower to p.rest.torsoLower + sway,
            p.paths.leg1 to leg1Tuck + sway,
            p.paths.leg2 to leg2Tuck + sway,
            p.paths.leg1Shin to p.rest.leg1Shin * 0.3f,
            p.paths.leg2Shin to p.rest.leg2Shin * 0.3f,
            p.paths.arm1 to p.rest.arm1 + sway * 1.5f,
            p.paths.arm2 to p.rest.arm2 + sway * 1.5f,
        )
    }

    /** Angry: stomps one foot repeatedly - shin lifts back then slams down, arms held tense. */
    private fun angryPose(p: RigProfile, frame: Int): Pose {
        val period = 6f
        val phase = TWO_PI * (frame % period) / period
        val stomp = 30f * max(0f, sin(phase))
        return mapOf(
            p.paths.leg1Shin to p.rest.leg1Shin - stomp,
            p.paths.arm1 to p.rest.arm1 + 25f,
            p.paths.arm2 to p.rest.arm2 - 25f,
            p.paths.torsoLower to p.rest.torsoLower - 8f,
        )
    }

    /** Climbing a wall: limbs bent and reaching like gripping a ledge, alternating as it scurries up. */
    private fun climbPose(p: RigProfile, frame: Int): Pose {
        val period = 8f
        val phase = TWO_PI * (frame % period) / period
        val limbSwing = 25f * sin(phase)
        return mapOf(
            p.paths.leg1 to p.rest.leg1 + 20f + limbSwing,
            p.paths.leg1Shin to p.rest.leg1Shin + 30f,
            p.paths.leg2 to p.rest.leg2 - 20f - limbSwing,
            p.paths.leg2Shin to p.rest.leg2Shin + 30f,
            p.paths.arm1 to p.rest.arm1 - 40f - limbSwing,
            p.paths.arm2 to p.rest.arm2 + 40f + limbSwing,
        )
    }

    /** Tired: slumped sitting, arms hanging - matches desktop's "tired"/couch01 semantics. */
    private fun tiredPose(p: RigProfile): Pose = mapOf(
        p.paths.torsoLower to p.rest.torsoLower - 30f,
        p.paths.arm1 to p.rest.arm1 - 20f,
        p.paths.arm2 to p.rest.arm2 + 20f,
        p.paths.leg1 to p.rest.leg1 + 15f,
        p.paths.leg1Shin to p.rest.leg1Shin + 40f,
        p.paths.leg2 to p.rest.leg2 - 15f,
        p.paths.leg2Shin to p.rest.leg2Shin + 40f,
    )

    /** Asleep: legs straightened together, arms relaxed, a slow "breathing" sway - the view itself gets rotated 90 to lie flat, see CharacterOverlay. */
    private fun sleepPose(p: RigProfile, frame: Int): Pose {
        val breathe = 4f * sin(TWO_PI * frame / 20f)
        return mapOf(
            p.paths.leg1 to p.rest.leg1 + 10f,
            p.paths.leg1Shin to p.rest.leg1Shin * 0.2f,
            p.paths.leg2 to p.rest.leg2 - 10f,
            p.paths.leg2Shin to p.rest.leg2Shin * 0.2f,
            p.paths.arm1 to p.rest.arm1 + breathe,
            p.paths.arm2 to p.rest.arm2 - breathe,
            p.paths.torsoLower to p.rest.torsoLower + breathe * 0.5f,
        )
    }

    // Friendly names an AI-authored keyframe can use (see CharacterState.Keyframe/startCustomAnimation)
    // mapped to the actual bone paths - keeps the raw path lists (child-index lists, meaningless
    // to a model, and different per topology) out of the AI-facing schema entirely.
    private fun nameToPath(paths: BonePathSet) = mapOf(
        "torso" to paths.torsoLower,
        "leg1" to paths.leg1,
        "leg1Shin" to paths.leg1Shin,
        "leg2" to paths.leg2,
        "leg2Shin" to paths.leg2Shin,
        "arm1" to paths.arm1,
        "arm2" to paths.arm2,
    )

    /** Builds a Pose from an AI-authored keyframe's friendly-named angles; unknown names are ignored. */
    private fun customPose(paths: BonePathSet, angles: Map<String, Float>): Pose {
        val map = nameToPath(paths)
        return angles.mapNotNull { (name, angle) -> map[name]?.let { it to angle } }.toMap()
    }

    // Red/Blue/Green/Yellow are a true recolor (identical rig data, confirmed by comparing actual
    // angle values, not just tree shape) using the STANDARD path order. TCO/Orange share that same
    // STANDARD path order but with a different limb orientation (rest angles). TDL/victim share
    // that SAME rest-angle orientation as TCO/Orange, but the tree itself was built with a
    // different child order (torso is root's 1st child instead of 3rd, etc.) - see the ALT_PATHS
    // comment above - despite that, TDL/victim's actual numbers matched TCO_TOPOLOGY_REST exactly.
    // Purple has a genuinely different topology entirely and hasn't been mapped, so it just stands.
    private val PROFILE_BY_ID = mapOf(
        "Red" to RigProfile(STANDARD_PATHS, RED_TOPOLOGY_REST),
        "Blue" to RigProfile(STANDARD_PATHS, RED_TOPOLOGY_REST),
        "Green" to RigProfile(STANDARD_PATHS, RED_TOPOLOGY_REST),
        "Yellow" to RigProfile(STANDARD_PATHS, RED_TOPOLOGY_REST),
        "TCO" to RigProfile(STANDARD_PATHS, TCO_TOPOLOGY_REST),
        "Orange" to RigProfile(STANDARD_PATHS, TCO_TOPOLOGY_REST),
        "TDL" to RigProfile(ALT_PATHS, TCO_TOPOLOGY_REST),
        "victim" to RigProfile(ALT_PATHS, TCO_TOPOLOGY_REST),
    )

    fun forFrameKind(kind: CharacterState.FrameKind, characterId: String): Pose {
        val profile = PROFILE_BY_ID[characterId] ?: return STAND
        return when (kind) {
            is CharacterState.FrameKind.Stand -> standPose(profile, kind.frame)
            CharacterState.FrameKind.Sit -> sitPose(profile)
            is CharacterState.FrameKind.Walk -> walkPose(profile, kind.frame, running = false)
            is CharacterState.FrameKind.Run -> walkPose(profile, kind.frame, running = true)
            is CharacterState.FrameKind.Bounce -> bouncePose(profile, kind.frame)
            is CharacterState.FrameKind.Trip -> tripPose(profile, kind.frame)
            is CharacterState.FrameKind.Fall -> fallPose(profile)
            is CharacterState.FrameKind.Pinch -> pinchPose(profile, kind.frame)
            is CharacterState.FrameKind.Angry -> angryPose(profile, kind.frame)
            is CharacterState.FrameKind.Climb -> climbPose(profile, kind.frame)
            is CharacterState.FrameKind.Sleep -> sleepPose(profile, kind.frame)
            is CharacterState.FrameKind.Tired -> tiredPose(profile)
            is CharacterState.FrameKind.Custom -> customPose(profile.paths, kind.angles)
        }
    }
}
