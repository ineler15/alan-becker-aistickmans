// Physics/animation-state state machine for the JS-native rig renderer replacing Shimeji on PC -
// a straight port of the Android app's CharacterState.kt (same priority chain: dragged > riding
// mouse > custom animation > sleeping > falling > moving > loop emotion > idle/auto-sleep), since
// that one was already iterated on live until it felt right. Runs in the Electron MAIN process
// (one instance per character) - tick() returns a small JSON-serializable descriptor sent over
// IPC to that character's own renderer window, which turns it into actual bone angles (see
// renderer/poseLibrary.js).

const TICK_MS = 25;
// Speeds/frame-ticks scaled down/up to match the faster 25ms tick (40->25 = 0.625x) so real-time
// walk/run/fall cadence stays the same as before while every animation reads much smoother - the
// renderer now gets a fresh pose 40 times/sec instead of 25.
const WALK_SPEED = 2;
const RUN_SPEED = 4;
const WALK_FRAME_TICKS = 6;
const RUN_FRAME_TICKS = 3;
const FALL_SPEED = 4;
const FALL_FRAME_TICKS = 5;
const FALL_TIMEOUT_MS = 4000;
const SAY_DURATION_MIN_MS = 8000;
const SAY_DURATION_PER_CHAR_MS = 90;
const AWAKE_MS_BEFORE_SLEEP = 20 * 60 * 1000;
const AWAKE_MS_BEFORE_SLEEP_AT_NIGHT = 10 * 60 * 1000;
const SLEEP_DURATION_MS = 5 * 60 * 1000;
const NIGHT_START_HOUR = 22;
const NIGHT_END_HOUR = 7;
const MAX_CUSTOM_KEYFRAMES = 12;
const MIN_KEYFRAME_HOLD_MS = 100;
const MAX_KEYFRAME_HOLD_MS = 3000;
const DEFAULT_KEYFRAME_HOLD_MS = 400;
// Autonomous wander: if nothing (AI decision or drag) has moved this character in a while, walk
// somewhere on its own instead of just idling in place.
const IDLE_WALK_TIMEOUT_MS = 6000;
// Same vocabulary as renderer/face.js's FaceRenderer - duplicated rather than shared since this
// file runs in the main process (no `window`) and that one in the renderer. Independent axes (not
// a bundled "emotion") so the AI can mix any eyes with any mouth.
const EYE_STYLES = ['normal', 'wide', 'angry', 'heart'];
const MOUTH_STYLES = ['neutral', 'smile', 'frown', 'open', 'angry'];

class CharacterState {
  constructor(screenWidth, screenHeight, floorY) {
    this.screenWidth = screenWidth;
    this.screenHeight = screenHeight;
    this.floorY = floorY;

    this.x = Math.round(screenWidth / 2);
    this.y = floorY;
    this.lookRight = true;

    this.beingDragged = false;
    this.falling = false;
    this.fallStartedAt = 0;
    this.moving = false;
    this.running = false;
    this.moveTargetX = 0;

    // ride_mouse: rides along the REAL OS cursor position for a timed duration. See input.js's
    // getMousePosition() and jsCharacterEngine.js's tick(), which fetches it once per tick and
    // passes it in here (only when at least one character is actually riding, to avoid the extra
    // native call otherwise).
    this.ridingMouse = false;
    this.rideMouseUntil = 0;

    this.sleeping = false;
    this.awakeSinceMs = Date.now();
    this.sleepStartedAt = 0;

    this.customAnimation = null;
    this.customIndex = 0;
    this.customKeyframeStartedAt = 0;
    this.customAccumulatedAngles = {};

    this.frame = 0;
    this.frameCounter = 0;
    this.lastActiveAt = Date.now();
    this.loopEmotion = null;
    this.sayUntil = 0;
    this.speechText = null;
    // Facial expression only - fully independent of loopEmotion/customAnimation/moving/falling
    // (those are body pose), so a character can be e.g. walking and happy at the same time. Only
    // ever visible on a character built with hasFace (see customCharacters.js) - a no-op
    // otherwise, same as this project's other "swallowed" actions. Eyes/mouth are independent
    // axes rather than one bundled "emotion" so the AI can mix any pair.
    this.eyeStyle = 'normal';
    this.mouthStyle = 'neutral';
  }

  startMoving(targetX, run) {
    this.lastActiveAt = Date.now();
    this.beingDragged = false;
    this.falling = false;
    this.ridingMouse = false;
    this.loopEmotion = null;
    this.customAnimation = null;
    this.moving = true;
    this.running = !!run;
    this.moveTargetX = Math.min(this.screenWidth, Math.max(0, targetX));
    this.lookRight = this.moveTargetX >= this.x;
  }

  randomTarget(run) {
    this.startMoving(Math.floor(Math.random() * this.screenWidth), run);
  }

  startFalling() {
    this.lastActiveAt = Date.now();
    this.moving = false;
    this.ridingMouse = false;
    this.loopEmotion = null;
    this.customAnimation = null;
    this.falling = true;
    this.fallStartedAt = Date.now();
  }

  // Rides along the real OS cursor position for `seconds` (default 6, capped 1-20) - see
  // jsCharacterEngine.js's tick(), which feeds the current mouse position into tick() below.
  startRideMouse(seconds) {
    this.lastActiveAt = Date.now();
    this.beingDragged = false;
    this.falling = false;
    this.moving = false;
    this.loopEmotion = null;
    this.customAnimation = null;
    if (this.sleeping) this._wakeUp();
    this.ridingMouse = true;
    this.rideMouseUntil = Date.now() + Math.min(20, Math.max(1, Number(seconds) || 6)) * 1000;
  }

  startCustomAnimation(keyframes) {
    if (!Array.isArray(keyframes) || keyframes.length === 0) return;
    this.lastActiveAt = Date.now();
    this.beingDragged = false;
    this.falling = false;
    this.moving = false;
    this.ridingMouse = false;
    this.loopEmotion = null;
    if (this.sleeping) this._wakeUp();
    this.customAnimation = keyframes.slice(0, MAX_CUSTOM_KEYFRAMES).map((k) => ({
      angles: k.angles || {},
      eyes: EYE_STYLES.includes(k.eyes) ? k.eyes : null,
      mouth: MOUTH_STYLES.includes(k.mouth) ? k.mouth : null,
      holdMs: Math.min(MAX_KEYFRAME_HOLD_MS, Math.max(MIN_KEYFRAME_HOLD_MS, k.holdMs || DEFAULT_KEYFRAME_HOLD_MS)),
    }));
    this.customIndex = 0;
    this.customKeyframeStartedAt = Date.now();
    this.customAccumulatedAngles = {};
    // Keyframes that don't specify eyes/mouth keep whatever the previous one set (or the
    // character's standing eyeStyle/mouthStyle if none in the sequence has set one yet) - only
    // applying a keyframe's own value when it actually has one.
    if (this.customAnimation[0].eyes) this.eyeStyle = this.customAnimation[0].eyes;
    if (this.customAnimation[0].mouth) this.mouthStyle = this.customAnimation[0].mouth;
  }

  // Either param can be omitted/invalid to leave that axis alone - e.g. setFace(undefined, 'smile')
  // changes only the mouth, keeping whatever eyes were already set.
  setFace(eyes, mouth) {
    this.lastActiveAt = Date.now();
    if (EYE_STYLES.includes(eyes)) this.eyeStyle = eyes;
    if (MOUTH_STYLES.includes(mouth)) this.mouthStyle = mouth;
  }

  setEmotion(state) {
    this.lastActiveAt = Date.now();
    this.moving = false;
    this.falling = false;
    this.ridingMouse = false;
    this.customAnimation = null;
    if (state === 'sleep') {
      this._startSleeping();
      return;
    }
    // "idle" has to normalize to null (not stay a truthy loopEmotion) - tick()'s loopEmotion
    // branch returns early unconditionally, so an "idle" that stuck around forever would
    // permanently block the auto-sleep check at the bottom of tick() from ever running again.
    this.loopEmotion = state && state !== 'idle' ? state : null;
    this.frame = 0;
    this.frameCounter = 0;
  }

  say(text) {
    this.speechText = text;
    this.sayUntil = Date.now() + Math.max(SAY_DURATION_MIN_MS, text.length * SAY_DURATION_PER_CHAR_MS);
  }

  dragTo(px, py) {
    this.lastActiveAt = Date.now();
    // Clamp to the floor: on PC the OS lets you drag the window past the taskbar/screen edge
    // (unlike Android's touch drag, which stays on-screen), so an unclamped py here could end
    // up past floorY - then the very next tick()'s `this.y >= this.floorY` check in the falling
    // branch fires immediately, snapping the character straight to the floor with no fall
    // animation at all instead of a smooth drop.
    this.x = Math.min(this.screenWidth, Math.max(0, px));
    this.y = Math.min(this.floorY, py);
    this.frameCounter++;
    if (this.frameCounter >= WALK_FRAME_TICKS) {
      this.frameCounter = 0;
      this.frame++;
    }
  }

  onRelease() {
    this.beingDragged = false;
    this.startFalling();
  }

  _isNightNow() {
    const hour = new Date(Date.now()).getHours();
    return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR;
  }

  _shouldForceSleep() {
    const awakeMs = Date.now() - this.awakeSinceMs;
    const threshold = this._isNightNow() ? AWAKE_MS_BEFORE_SLEEP_AT_NIGHT : AWAKE_MS_BEFORE_SLEEP;
    return awakeMs > threshold;
  }

  _startSleeping() {
    this.sleeping = true;
    this.sleepStartedAt = Date.now();
    this.beingDragged = false;
    this.moving = false;
    this.falling = false;
    this.loopEmotion = null;
    this.frame = 0;
    this.frameCounter = 0;
  }

  _wakeUp() {
    this.sleeping = false;
    this.awakeSinceMs = Date.now();
  }

  /**
   * Advances physics one tick; returns a small descriptor {kind, frame, angles?} for the renderer.
   * mousePos (optional {x,y}, already converted to this character's local coordinate space by
   * jsCharacterEngine.js) is only ever read while ridingMouse is active.
   */
  tick(mousePos) {
    if (Date.now() > this.sayUntil) this.speechText = null;

    if (this.beingDragged) {
      if (this.sleeping) this._wakeUp();
      this.customAnimation = null;
      this.ridingMouse = false;
      return { kind: 'pinch', frame: this.frame };
    }

    if (this.ridingMouse) {
      if (Date.now() > this.rideMouseUntil) {
        this.ridingMouse = false;
      } else {
        if (mousePos) {
          // Weighted/heavy follow instead of snapping exactly onto the cursor - a slow lerp reads
          // as being carried by something with real weight/inertia rather than glued to the tip.
          const FOLLOW_RATE = 0.12;
          this.lookRight = mousePos.x >= this.x;
          this.x += (mousePos.x - this.x) * FOLLOW_RATE;
          this.y = Math.min(this.floorY, this.y + (mousePos.y - this.y) * FOLLOW_RATE);
        }
        this.frameCounter++;
        if (this.frameCounter >= WALK_FRAME_TICKS) {
          this.frameCounter = 0;
          this.frame++;
        }
        return { kind: 'pinch', frame: this.frame };
      }
    }

    if (this.customAnimation) {
      const kf = this.customAnimation;
      const elapsed = Date.now() - this.customKeyframeStartedAt;
      if (elapsed > kf[this.customIndex].holdMs) {
        Object.assign(this.customAccumulatedAngles, kf[this.customIndex].angles);
        this.customIndex++;
        this.customKeyframeStartedAt = Date.now();
        // A keyframe without its own eyes/mouth keeps whatever the previous one set instead of
        // resetting to defaults - only overwrite the axis this keyframe actually specifies.
        if (this.customIndex < kf.length) {
          if (kf[this.customIndex].eyes) this.eyeStyle = kf[this.customIndex].eyes;
          if (kf[this.customIndex].mouth) this.mouthStyle = kf[this.customIndex].mouth;
        }
      }
      if (this.customIndex >= kf.length) {
        this.customAnimation = null;
      } else {
        return { kind: 'custom', angles: { ...this.customAccumulatedAngles, ...kf[this.customIndex].angles } };
      }
    }

    if (this.sleeping) {
      if (Date.now() - this.sleepStartedAt > SLEEP_DURATION_MS) {
        this._wakeUp();
      } else {
        this.frameCounter++;
        if (this.frameCounter >= WALK_FRAME_TICKS) {
          this.frameCounter = 0;
          this.frame++;
        }
        return { kind: 'sleep', frame: this.frame };
      }
    }

    if (this.falling) {
      if (Date.now() - this.fallStartedAt > FALL_TIMEOUT_MS || this.y >= this.floorY) {
        this.falling = false;
        this.y = this.floorY;
        return { kind: 'stand', frame: 0 };
      }
      this.y = Math.min(this.floorY, this.y + FALL_SPEED);
      this.frameCounter++;
      if (this.frameCounter >= FALL_FRAME_TICKS) {
        this.frameCounter = 0;
        this.frame++;
      }
      return { kind: 'fall', frame: this.frame };
    }

    if (this.moving) {
      const speed = this.running ? RUN_SPEED : WALK_SPEED;
      const ticksPerFrame = this.running ? RUN_FRAME_TICKS : WALK_FRAME_TICKS;
      if (Math.abs(this.moveTargetX - this.x) <= speed) {
        this.x = this.moveTargetX;
        this.moving = false;
        return { kind: 'stand', frame: 0 };
      }
      this.x += this.moveTargetX > this.x ? speed : -speed;
      this.frameCounter++;
      if (this.frameCounter >= ticksPerFrame) {
        this.frameCounter = 0;
        this.frame++;
      }
      return { kind: this.running ? 'run' : 'walk', frame: this.frame };
    }

    if (this.loopEmotion) {
      const emotion = this.loopEmotion;
      if (emotion === 'sit') return { kind: 'sit' };
      this.frameCounter++;
      if (this.frameCounter >= WALK_FRAME_TICKS) {
        this.frameCounter = 0;
        this.frame++;
      }
      if (emotion === 'happy') return { kind: 'bounce', frame: this.frame };
      if (emotion === 'angry') return { kind: 'angry', frame: this.frame };
      if (emotion === 'tired') return { kind: 'tired' };
      return { kind: 'trip', frame: this.frame };
    }

    if (this._shouldForceSleep()) {
      this._startSleeping();
      return { kind: 'sleep', frame: 0 };
    }

    // Autonomous wander - if nothing (AI decision or drag) has moved this character in a while,
    // walk somewhere on its own instead of standing there indefinitely (covers a stuck/erroring
    // AI provider too, not just a missing key - see IDLE_WALK_TIMEOUT_MS).
    if (Date.now() - this.lastActiveAt > IDLE_WALK_TIMEOUT_MS) {
      this.randomTarget(false);
      this.frame = 0;
      this.frameCounter = 0;
      return { kind: 'walk', frame: 0 };
    }

    // Idle sway instead of a perfectly frozen frame - see poseLibrary.js's standPose(). Reuses
    // the same frame/frameCounter fields every other animated state does.
    this.frameCounter++;
    if (this.frameCounter >= WALK_FRAME_TICKS) {
      this.frameCounter = 0;
      this.frame++;
    }
    return { kind: 'stand', frame: this.frame };
  }
}

module.exports = { CharacterState, TICK_MS };
