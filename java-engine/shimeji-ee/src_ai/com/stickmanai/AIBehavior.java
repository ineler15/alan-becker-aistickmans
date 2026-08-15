package com.stickmanai;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.group_finity.mascot.Mascot;
import com.group_finity.mascot.behavior.Behavior;
import com.group_finity.mascot.exception.CantBeAliveException;
import com.group_finity.mascot.image.ImagePairLoader;
import com.group_finity.mascot.image.ImagePairs;

/**
 * Behavior for a mascot controlled by the external AI ("brain") process.
 * Unlike every other mascot's Behavior, this one is never built from
 * behaviors.xml/Configuration - it is assigned directly in
 * Main.createMascot() for each AI-driven character id and keeps full control
 * of the mascot for its whole lifetime (drag/throw is intentionally
 * disabled, see mousePressed).
 *
 * One instance exists per AI-driven character (e.g. "victim", "Blue"); each
 * owns its own CommandWatcher (its own ai-command-<id>.json/ai-status-<id>.json
 * pair) so several friends can be driven independently at once. Sprites are
 * the ones already shipped under img/<imageSet> - this class only decides
 * which existing frame to show and where to place the mascot, it never draws
 * or edits any artwork.
 */
public class AIBehavior implements Behavior {

    private static final Logger log = Logger.getLogger(AIBehavior.class.getName());

    private static final Point ANCHOR = new Point(64, 128);
    private static final Point ANCHOR_SIT = new Point(64, 126);

    private static final int WALK_SPEED = 3; // pixels per 40ms tick (~75px/sec)
    private static final int RUN_SPEED = 7; // pixels per 40ms tick (~175px/sec)
    private static final int WALK_FRAME_TICKS = 4;
    private static final int RUN_FRAME_TICKS = 2;
    private static final int FALL_SPEED = 6; // pixels per 40ms tick, faster than walking
    private static final int FALL_FRAME_TICKS = 3;
    private static final long DRAG_TIMEOUT_MS = 15000;
    private static final long FALL_TIMEOUT_MS = 4000;
    private static final long SAY_DURATION_MIN_MS = 8000;
    private static final long SAY_DURATION_PER_CHAR_MS = 90;

    private final String characterId;
    private final String poseStand;
    private final String poseSit;
    private final String poseJump;
    private final String poseSleep;
    private final String poseTired;
    private final String[] poseWalk;
    private final String[] poseGrab;
    private final String[] poseBounce;
    private final String[] poseDance;
    private final String[] poseTrip;
    private final String[] poseRun;
    private final String[] poseFall;

    private final CommandWatcher watcher;

    private Mascot mascot;
    private SpeechBubble bubble;

    private String lastCommandId = null;
    private boolean moving = false;
    private boolean running = false;
    private Point moveTarget = null;
    private int walkFrame = 0;
    private int walkFrameCounter = 0;
    private String pose = "stand";
    private long sayUntil = 0;
    private boolean ridingCursor = false;
    private long rideCursorUntil = 0;
    private boolean beingDragged = false;
    private long dragStartedAt = 0;
    private boolean falling = false;
    private long fallStartedAt = 0;
    // Non-null while a looping "emotion" animation (bounce/dance/trip/run/fall) is playing,
    // so next() knows to keep cycling its frames instead of sitting on a single image.
    private String[] loopFrames = null;

    /**
     * @param characterId unique id used for this character's command/status files (e.g. "victim", "Blue")
     * @param imageSet name of the sprite folder under img/ to use (usually the same as characterId)
     */
    public AIBehavior(String characterId, String imageSet) {
        this.characterId = characterId;
        String imageSetPath = "/" + imageSet;
        this.poseStand = imageSetPath + "/stand01.png";
        this.poseSit = imageSetPath + "/sit01.png";
        this.poseJump = imageSetPath + "/jump01.png";
        this.poseSleep = imageSetPath + "/lay01.png";
        this.poseTired = imageSetPath + "/couch01.png";
        this.poseWalk = new String[] {
                imageSetPath + "/walk01.png", imageSetPath + "/walk02.png", imageSetPath + "/walk03.png",
                imageSetPath + "/walk04.png", imageSetPath + "/walk05.png"
        };
        this.poseGrab = new String[] {
                imageSetPath + "/pinch01.png", imageSetPath + "/pinch02.png", imageSetPath + "/pinch03.png",
                imageSetPath + "/pinch04.png", imageSetPath + "/pinch05.png", imageSetPath + "/pinch06.png",
                imageSetPath + "/pinch07.png"
        };
        this.poseBounce = new String[] {
                imageSetPath + "/bounce01.png", imageSetPath + "/bounce02.png",
                imageSetPath + "/bounce03.png", imageSetPath + "/bounce04.png"
        };
        this.poseDance = new String[20];
        for (int i = 0; i < poseDance.length; i++) {
            poseDance[i] = imageSetPath + "/dance" + String.format("%02d", i + 1) + ".png";
        }
        this.poseTrip = new String[] {
                imageSetPath + "/trip01.png", imageSetPath + "/trip02.png", imageSetPath + "/trip03.png",
                imageSetPath + "/trip04.png", imageSetPath + "/trip05.png", imageSetPath + "/trip06.png"
        };
        this.poseRun = new String[] {
                imageSetPath + "/run01.png", imageSetPath + "/run02.png", imageSetPath + "/run03.png",
                imageSetPath + "/run04.png", imageSetPath + "/run05.png", imageSetPath + "/run06.png",
                imageSetPath + "/run07.png", imageSetPath + "/run08.png", imageSetPath + "/run09.png",
                imageSetPath + "/run10.png", imageSetPath + "/run11.png", imageSetPath + "/run12.png"
        };
        this.poseFall = new String[] { imageSetPath + "/fall01.png", imageSetPath + "/fall02.png" };
        this.watcher = CommandWatcher.forId(characterId);
    }

    @Override
    public void init(Mascot mascot) throws CantBeAliveException {
        this.mascot = mascot;
        try {
            loadImages();
            mascot.setImage(ImagePairs.getImage(poseStand, mascot.isLookRight()));

            // Main.createMascot() spawns every mascot off-screen at (-4000,-4000)
            // before assigning its behavior - move this one somewhere visible.
            // Use the work area (not the raw screen) so it lands on the real
            // desktop floor instead of sinking into the taskbar.
            int workLeft = mascot.getEnvironment().getWorkArea().getLeft();
            int workRight = mascot.getEnvironment().getWorkArea().getRight();
            int workBottom = mascot.getEnvironment().getWorkArea().getBottom();
            mascot.setAnchor(new Point(workLeft + (workRight - workLeft) / 2, workBottom));

            bubble = new SpeechBubble();

            log.log(Level.INFO, "AI mascot ready ({0})", mascot);
        } catch (Exception e) {
            log.log(Level.WARNING, "AI mascot: error during init", e);
        }
    }

    private void loadImages() {
        int scaling = getScaling();
        tryLoad(poseStand, ANCHOR, scaling);
        tryLoad(poseSit, ANCHOR_SIT, scaling);
        tryLoad(poseJump, ANCHOR, scaling);
        tryLoad(poseSleep, ANCHOR, scaling);
        tryLoad(poseTired, ANCHOR, scaling);
        for (String p : poseWalk) tryLoad(p, ANCHOR, scaling);
        for (String p : poseGrab) tryLoad(p, ANCHOR, scaling);
        for (String p : poseBounce) tryLoad(p, ANCHOR, scaling);
        for (String p : poseDance) tryLoad(p, ANCHOR, scaling);
        for (String p : poseTrip) tryLoad(p, ANCHOR, scaling);
        for (String p : poseRun) tryLoad(p, ANCHOR, scaling);
        for (String p : poseFall) tryLoad(p, ANCHOR, scaling);
    }

    private int getScaling() {
        try {
            return Integer.parseInt(com.group_finity.mascot.Main.getInstance().getProperties().getProperty("Scaling", "1"));
        } catch (Exception e) {
            return 1;
        }
    }

    private void tryLoad(String path, Point anchor, int scaling) {
        try {
            ImagePairLoader.load(path, null, anchor, scaling);
        } catch (Exception e) {
            log.log(Level.WARNING, "AI mascot: could not load sprite " + path, e);
        }
    }

    @Override
    public synchronized void next() throws CantBeAliveException {
        // This mascot must never die from an unexpected exception the way a
        // normal XML-driven behavior would (Mascot.tick() only guards against
        // CantBeAliveException) - any failure here should just skip a tick.
        try {
            Map<String, Object> cmd = watcher.getCommand();
            if (cmd != null) {
                Object id = cmd.get("id");
                String idStr = id == null ? null : String.valueOf(id);
                if (idStr != null && !idStr.equals(lastCommandId)) {
                    lastCommandId = idStr;
                    handleCommand(cmd);
                }
            }

            if (beingDragged) {
                // Safety net: if the mouse is released outside this tiny window's bounds,
                // Windows never sends us the release event and we'd be stuck "held" forever -
                // so give up on the drag after a while regardless.
                if (System.currentTimeMillis() - dragStartedAt > DRAG_TIMEOUT_MS) {
                    beingDragged = false;
                    startFalling();
                } else {
                    rideCursor();
                }
            } else if (ridingCursor) {
                if (System.currentTimeMillis() > rideCursorUntil) {
                    ridingCursor = false;
                    startFalling();
                } else {
                    rideCursor();
                }
            } else if (falling) {
                stepFalling();
            } else if (moving) {
                stepMovement();
            } else if (loopFrames != null) {
                stepLoopAnimation();
            }

            if (bubble != null) {
                bubble.followMascot(mascot);
                if (System.currentTimeMillis() > sayUntil) {
                    bubble.hide();
                }
            }

            publishStatus();
        } catch (Exception e) {
            log.log(Level.WARNING, "AI mascot: error during tick, skipping", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleCommand(Map<String, Object> cmd) {
        String tool = String.valueOf(cmd.get("tool"));
        Object rawArgs = cmd.get("args");
        Map<String, Object> args = rawArgs instanceof Map ? (Map<String, Object>) rawArgs : new HashMap<String, Object>();

        // Any new command takes back control from the ride-the-cursor state, except
        // ride_mouse itself (handled below), which (re)starts it.
        ridingCursor = false;

        if ("walk_to".equals(tool)) {
            // Always stay on the ground floor - there's no climbing yet, so honoring an
            // arbitrary requested y (e.g. the middle of a window up on screen) would have
            // the character walk floating through the air instead of on solid ground.
            double x = toDouble(args.get("x"), mascot.getAnchor().x);
            boolean run = Boolean.parseBoolean(String.valueOf(args.get("run")));
            startMoving(new Point((int) x, mascot.getEnvironment().getWorkArea().getBottom()), run);
        } else if ("move_random".equals(tool)) {
            startMoving(randomPoint(), Boolean.parseBoolean(String.valueOf(args.get("run"))));
        } else if ("ride_mouse".equals(tool)) {
            double seconds = toDouble(args.get("seconds"), 6);
            moving = false;
            pose = "grabbed";
            ridingCursor = true;
            rideCursorUntil = System.currentTimeMillis() + (long) (seconds * 1000);
            rideCursor();
        } else if ("set_animation".equals(tool)) {
            applyStaticPose(String.valueOf(args.get("state")));
            if (args.get("caption") != null) {
                showSpeech(String.valueOf(args.get("caption")));
            }
        } else if ("say".equals(tool)) {
            Object text = args.get("text");
            if (text != null) {
                showSpeech(String.valueOf(text));
            }
        } else {
            log.log(Level.WARNING, "AI mascot: unknown command tool {0}", tool);
        }
    }

    private double toDouble(Object value, double fallback) {
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private void startMoving(Point target, boolean runThere) {
        moveTarget = target;
        moving = true;
        running = runThere;
        loopFrames = null;
        walkFrame = 0;
        walkFrameCounter = 0;
        pose = runThere ? "run" : "walk";
    }

    private Point randomPoint() {
        int left = mascot.getEnvironment().getWorkArea().getLeft();
        int right = mascot.getEnvironment().getWorkArea().getRight();
        int bottom = mascot.getEnvironment().getWorkArea().getBottom();
        int x = left + (int) (Math.random() * Math.max(1, right - left));
        return new Point(x, bottom);
    }

    /**
     * Maps a requested emotion/state name to one of the sprite sets already shipped for
     * this character - these figures have no face, so "emotion" reads entirely through
     * body language/pose (bouncing = happy, tripping = flustered, running = scared, etc).
     */
    private void applyStaticPose(String state) {
        moving = false;
        loopFrames = null;
        walkFrame = 0;
        walkFrameCounter = 0;

        if ("sit".equals(state)) {
            pose = "sit";
            mascot.setImage(ImagePairs.getImage(poseSit, mascot.isLookRight()));
        } else if ("jump".equals(state)) {
            pose = "jump";
            mascot.setImage(ImagePairs.getImage(poseJump, mascot.isLookRight()));
        } else if ("sleep".equals(state)) {
            pose = "sleep";
            mascot.setImage(ImagePairs.getImage(poseSleep, mascot.isLookRight()));
        } else if ("tired".equals(state)) {
            pose = "tired";
            mascot.setImage(ImagePairs.getImage(poseTired, mascot.isLookRight()));
        } else if ("happy".equals(state)) {
            pose = "happy";
            loopFrames = poseBounce;
        } else if ("dance".equals(state)) {
            pose = "dance";
            loopFrames = poseDance;
        } else if ("trip".equals(state) || "confused".equals(state)) {
            pose = "trip";
            loopFrames = poseTrip;
        } else if ("scared".equals(state)) {
            pose = "scared";
            loopFrames = poseRun;
        } else if ("sad".equals(state)) {
            pose = "sad";
            loopFrames = poseFall;
        } else {
            // idle/think/talk/point/wave fall back to the standing pose for now - every
            // character ships stand01.png, so this never fails to render.
            pose = "stand";
            mascot.setImage(ImagePairs.getImage(poseStand, mascot.isLookRight()));
        }
    }

    private void stepLoopAnimation() {
        walkFrameCounter++;
        if (walkFrameCounter >= WALK_FRAME_TICKS) {
            walkFrameCounter = 0;
            walkFrame = (walkFrame + 1) % loopFrames.length;
        }
        mascot.setImage(ImagePairs.getImage(loopFrames[walkFrame], mascot.isLookRight()));
    }

    private void showSpeech(String text) {
        long duration = Math.max(SAY_DURATION_MIN_MS, text.length() * SAY_DURATION_PER_CHAR_MS);
        sayUntil = System.currentTimeMillis() + duration;
        if (bubble != null) bubble.show(text, mascot);
    }

    /**
     * Snaps the mascot onto the live cursor position, like it's being held/pinched by it.
     * ("dangle" turned out to just be a sit-on-a-ledge pose in this pack, and jump01 read as
     * flying rather than held - "pinch" is the actual grabbed-and-dangling pose.)
     */
    private void rideCursor() {
        Point cursor;
        try {
            cursor = MouseInfo.getPointerInfo().getLocation();
        } catch (Exception e) {
            return; // no display/pointer info available this tick - just skip it
        }
        // Anchor (64,128) sits at the sprite's feet, but a pinched character hangs BELOW
        // the cursor, held from the head - so the feet need to land ~120px (scaled) below
        // the cursor instead of right on it (matches vanilla Shimeji's Dragged.tick()).
        mascot.setAnchor(new Point(cursor.x, cursor.y + 120 * getScaling()));

        walkFrameCounter++;
        if (walkFrameCounter >= WALK_FRAME_TICKS) {
            walkFrameCounter = 0;
            walkFrame = (walkFrame + 1) % poseGrab.length;
        }
        mascot.setImage(ImagePairs.getImage(poseGrab[walkFrame], mascot.isLookRight()));
    }

    private void stepMovement() {
        int speed = running ? RUN_SPEED : WALK_SPEED;
        String[] frames = running ? poseRun : poseWalk;
        int frameTicks = running ? RUN_FRAME_TICKS : WALK_FRAME_TICKS;

        Point anchor = mascot.getAnchor();
        double dx = moveTarget.x - anchor.x;
        double dy = moveTarget.y - anchor.y;
        double dist = Math.sqrt(dx * dx + dy * dy);

        if (dist <= speed) {
            mascot.setAnchor(new Point(moveTarget.x, moveTarget.y));
            moving = false;
            applyStaticPose("idle");
            return;
        }

        mascot.setLookRight(dx >= 0);

        int stepX = (int) Math.round(dx / dist * speed);
        int stepY = (int) Math.round(dy / dist * speed);
        mascot.setAnchor(new Point(anchor.x + stepX, anchor.y + stepY));

        walkFrameCounter++;
        if (walkFrameCounter >= frameTicks) {
            walkFrameCounter = 0;
            walkFrame = (walkFrame + 1) % frames.length;
        }
        mascot.setImage(ImagePairs.getImage(frames[walkFrame], mascot.isLookRight()));
    }

    private void publishStatus() {
        Map<String, Object> status = new HashMap<String, Object>();
        status.put("id", characterId);
        status.put("x", mascot.getAnchor().x);
        status.put("y", mascot.getAnchor().y);
        status.put("moving", moving);
        status.put("lookRight", mascot.isLookRight());
        status.put("pose", pose);
        status.put("commandId", lastCommandId);
        watcher.queueStatus(status);
    }

    @Override
    public void mousePressed(MouseEvent e) throws CantBeAliveException {
        // Unlike a normal Shimeji, grabbing this one never hands control off to a
        // random XML "thrown/fall" behavior - it just follows the cursor while held,
        // and the AI (AIBehavior) picks back up exactly where it left off on release.
        // Only the left button starts a drag - Mascot forwards right-click presses here
        // too (isPopupTrigger() only becomes true on release on Windows), and a right-click
        // isn't meant to grab it, just to be a no-op menu request we already suppress.
        if (e.getButton() != MouseEvent.BUTTON1) return;
        moving = false;
        ridingCursor = false;
        beingDragged = true;
        dragStartedAt = System.currentTimeMillis();
        pose = "grabbed";
    }

    @Override
    public void mouseReleased(MouseEvent e) throws CantBeAliveException {
        if (!beingDragged) return;
        beingDragged = false;
        startFalling();
    }

    /** Dropped mid-air (release, or ride_mouse expiring) - fall to the floor instead of
     * snapping there instantly. */
    private void startFalling() {
        moving = false;
        loopFrames = null;
        falling = true;
        fallStartedAt = System.currentTimeMillis();
        walkFrame = 0;
        walkFrameCounter = 0;
        pose = "fall";
    }

    private void stepFalling() {
        Point anchor = mascot.getAnchor();
        int groundY = mascot.getEnvironment().getWorkArea().getBottom();

        // A hard time cap in addition to the position check - getWorkArea() can shift on a
        // multi-monitor setup depending on which screen the anchor's x currently lands on,
        // so a purely position-based check could in theory never converge.
        boolean timedOut = System.currentTimeMillis() - fallStartedAt > FALL_TIMEOUT_MS;
        if (timedOut || anchor.y >= groundY - FALL_SPEED) {
            mascot.setAnchor(new Point(anchor.x, groundY));
            falling = false;
            applyStaticPose("idle");
            return;
        }

        mascot.setAnchor(new Point(anchor.x, anchor.y + FALL_SPEED));
        walkFrameCounter++;
        if (walkFrameCounter >= FALL_FRAME_TICKS) {
            walkFrameCounter = 0;
            walkFrame = (walkFrame + 1) % poseFall.length;
        }
        mascot.setImage(ImagePairs.getImage(poseFall[walkFrame], mascot.isLookRight()));
    }

    @Override
    public boolean isHidden() {
        return true;
    }
}
