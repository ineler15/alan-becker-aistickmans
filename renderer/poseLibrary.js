// Port of the Android app's PoseLibrary.kt to plain JS for the PC rig-renderer windows. Same
// bone paths, same per-character rest angles, same pose formulas - see android_rig_renderer memory
// for how these were derived/verified. Loaded as a plain script (not a module) into each
// character's renderer window, exposes a global `PoseLibrary.forDescriptor(descriptor, characterId)`.

(function () {
  // Two topologies exist among the characters mapped so far: STANDARD (root -> [leg1, leg2,
  // torso-chain], torso-chain's neck hub -> [arm1, arm2, head-stalk] - Red/Blue/Green/Yellow/
  // TCO/Orange) and ALT (root -> [torso-chain, leg2, leg1], torso-chain's neck hub ->
  // [head-stalk, arm2, arm1] - TDL/victim). Same rig family, same rest angles even, just
  // serialized with a different child order.
  const STANDARD_PATHS = {
    leg1: [0], leg1Shin: [0, 0],
    leg2: [1], leg2Shin: [1, 0],
    torso: [2],
    arm1: [2, 0, 0, 0], arm2: [2, 0, 0, 1],
  };

  const ALT_PATHS = {
    leg1: [2], leg1Shin: [2, 0],
    leg2: [1], leg2Shin: [1, 0],
    torso: [0],
    arm1: [0, 0, 0, 2], arm2: [0, 0, 0, 1],
  };

  const RED_TOPOLOGY_REST = {
    leg1: 246.8, leg1Shin: 24.71,
    leg2: 294.53, leg2Shin: -26.79,
    torsoLower: 88.71,
    arm1: -207.92, arm2: -154.29,
  };

  const TCO_TOPOLOGY_REST = {
    leg1: -66.8, leg1Shin: -384.7,
    leg2: -114.5, leg2Shin: -333.2,
    torsoLower: 89.4,
    arm1: -143.3, arm2: -212.7,
  };

  // Red/Blue/Green/Yellow are a true recolor (identical rig data) using the STANDARD path order.
  // TCO/Orange share that same STANDARD path order but with a different limb orientation (rest
  // angles). TDL/victim share that same rest-angle orientation as TCO/Orange, but the tree itself
  // was built with a different child order - see ALT_PATHS above.
  const PROFILE_BY_ID = {
    Red: { paths: STANDARD_PATHS, rest: RED_TOPOLOGY_REST },
    Blue: { paths: STANDARD_PATHS, rest: RED_TOPOLOGY_REST },
    Green: { paths: STANDARD_PATHS, rest: RED_TOPOLOGY_REST },
    Yellow: { paths: STANDARD_PATHS, rest: RED_TOPOLOGY_REST },
    TCO: { paths: STANDARD_PATHS, rest: TCO_TOPOLOGY_REST },
    Orange: { paths: STANDARD_PATHS, rest: TCO_TOPOLOGY_REST },
    TDL: { paths: ALT_PATHS, rest: TCO_TOPOLOGY_REST },
    victim: { paths: ALT_PATHS, rest: TCO_TOPOLOGY_REST },
  };

  // Kept for backward compat with anything still reading a single flat bone-path map (only valid
  // for STANDARD-topology characters).
  const BONE_PATHS = STANDARD_PATHS;
  const REST_BY_ID = Object.fromEntries(Object.entries(PROFILE_BY_ID).map(([id, p]) => [id, p.rest]));

  const TWO_PI = 2 * Math.PI;

  function blendToward(from, to, t) {
    let diff = (to - from) % 360;
    if (diff > 180) diff -= 360;
    if (diff < -180) diff += 360;
    return from + diff * t;
  }

  function byPath(paths, map) {
    const out = new Map();
    for (const [name, angle] of Object.entries(map)) {
      if (paths[name]) out.set(paths[name].join(','), angle);
    }
    return out;
  }

  // Idle sway instead of a perfectly frozen stand - a slow "breathing" tilt on the torso and a
  // slight opposite arm sway, subtle enough to still read as standing still but enough that the
  // character doesn't look frozen/dead between decisions.
  function standPose(paths, rest, frame) {
    const sway = 3 * Math.sin((TWO_PI * frame) / 30);
    return byPath(paths, {
      torso: rest.torsoLower + sway,
      arm1: rest.arm1 + sway * 0.6,
      arm2: rest.arm2 - sway * 0.6,
    });
  }

  function sitPose(paths, rest) {
    return byPath(paths, {
      leg1: rest.leg1 - 56.8,
      leg1Shin: rest.leg1Shin + 65.29,
      leg2: rest.leg2 + 55.47,
      leg2Shin: rest.leg2Shin - 63.21,
      torso: rest.torsoLower + 6.29,
    });
  }

  function fallPose(paths, rest) {
    return byPath(paths, {
      torso: rest.torsoLower - 40,
      arm1: rest.arm1 - 60,
      arm2: rest.arm2 + 60,
      leg1: rest.leg1 + 30,
      leg2: rest.leg2 - 30,
    });
  }

  function walkPose(paths, rest, frame, running) {
    const period = running ? 6 : 8;
    const amplitude = running ? 48 : 28;
    const kneeBend = running ? 38 : 18;
    const phase = (TWO_PI * (frame % period)) / period;
    const legSwing = amplitude * Math.sin(phase);
    return byPath(paths, {
      leg1: rest.leg1 + legSwing,
      leg1Shin: rest.leg1Shin + kneeBend * Math.max(0, Math.sin(phase)),
      leg2: rest.leg2 - legSwing,
      leg2Shin: rest.leg2Shin + kneeBend * Math.max(0, Math.sin(phase + Math.PI)),
      arm1: rest.arm1 - legSwing,
      arm2: rest.arm2 + legSwing,
    });
  }

  function bouncePose(paths, rest, frame) {
    const period = 6;
    const phase = (TWO_PI * (frame % period)) / period;
    const squat = 22 * Math.max(0, Math.sin(phase));
    return byPath(paths, {
      leg1Shin: rest.leg1Shin + squat,
      leg2Shin: rest.leg2Shin + squat,
      arm1: rest.arm1 - squat,
      arm2: rest.arm2 + squat,
    });
  }

  function tripPose(paths, rest, frame) {
    const jitter = 15 * Math.sin((TWO_PI * frame) / 5);
    return byPath(paths, {
      torso: rest.torsoLower - 25,
      arm1: rest.arm1 + 40 + jitter,
      arm2: rest.arm2 - 40 - jitter,
      leg1: rest.leg1 + 20,
      leg2: rest.leg2 - 10,
    });
  }

  function pinchPose(paths, rest, frame) {
    const sway = 6 * Math.sin((TWO_PI * frame) / 14);
    const leg1Tuck = blendToward(rest.leg1, 270, 0.5);
    const leg2Tuck = blendToward(rest.leg2, 270, 0.5);
    return byPath(paths, {
      torso: rest.torsoLower + sway,
      leg1: leg1Tuck + sway,
      leg2: leg2Tuck + sway,
      leg1Shin: rest.leg1Shin * 0.3,
      leg2Shin: rest.leg2Shin * 0.3,
      arm1: rest.arm1 + sway * 1.5,
      arm2: rest.arm2 + sway * 1.5,
    });
  }

  function angryPose(paths, rest, frame) {
    const period = 6;
    const phase = (TWO_PI * (frame % period)) / period;
    const stomp = 30 * Math.max(0, Math.sin(phase));
    return byPath(paths, {
      leg1Shin: rest.leg1Shin - stomp,
      arm1: rest.arm1 + 25,
      arm2: rest.arm2 - 25,
      torso: rest.torsoLower - 8,
    });
  }

  function tiredPose(paths, rest) {
    return byPath(paths, {
      torso: rest.torsoLower - 30,
      arm1: rest.arm1 - 20,
      arm2: rest.arm2 + 20,
      leg1: rest.leg1 + 15,
      leg1Shin: rest.leg1Shin + 40,
      leg2: rest.leg2 - 15,
      leg2Shin: rest.leg2Shin + 40,
    });
  }

  function sleepPose(paths, rest, frame) {
    const breathe = 4 * Math.sin((TWO_PI * frame) / 20);
    return byPath(paths, {
      leg1: rest.leg1 + 10,
      leg1Shin: rest.leg1Shin * 0.2,
      leg2: rest.leg2 - 10,
      leg2Shin: rest.leg2Shin * 0.2,
      arm1: rest.arm1 + breathe,
      arm2: rest.arm2 - breathe,
      torso: rest.torsoLower + breathe * 0.5,
    });
  }

  // Friendly names an AI-authored keyframe can use (see set_custom_animation) mapped to the
  // actual bone paths - keeps the raw path lists (child-index lists, meaningless to a model, and
  // different per topology) out of the AI-facing schema entirely.
  function customPose(paths, angles) {
    return byPath(paths, angles || {});
  }

  /** descriptor: {kind, frame?, angles?} from CharacterState.tick(). Returns Map<pathKey, angle>. */
  function forDescriptor(descriptor, characterId) {
    const profile = PROFILE_BY_ID[characterId];
    if (!profile) return new Map();
    const { paths, rest } = profile;
    switch (descriptor.kind) {
      case 'stand':
        return standPose(paths, rest, descriptor.frame || 0);
      case 'sit':
        return sitPose(paths, rest);
      case 'walk':
        return walkPose(paths, rest, descriptor.frame || 0, false);
      case 'run':
        return walkPose(paths, rest, descriptor.frame || 0, true);
      case 'bounce':
        return bouncePose(paths, rest, descriptor.frame || 0);
      case 'trip':
        return tripPose(paths, rest, descriptor.frame || 0);
      case 'fall':
        return fallPose(paths, rest);
      case 'pinch':
        return pinchPose(paths, rest, descriptor.frame || 0);
      case 'angry':
        return angryPose(paths, rest, descriptor.frame || 0);
      case 'sleep':
        return sleepPose(paths, rest, descriptor.frame || 0);
      case 'tired':
        return tiredPose(paths, rest);
      case 'custom':
        return customPose(paths, descriptor.angles);
      default:
        return new Map();
    }
  }

  window.PoseLibrary = { forDescriptor, BONE_PATHS, REST_BY_ID, PROFILE_BY_ID };
})();
