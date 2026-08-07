package me.venixpll.cheat.module.impl;

import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.ModeSetting;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheckAdapter;
import me.venixpll.overlay.OverlayWindow;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.List;

/**
 * TriggerBot Module.
 *
 * <p>
 * Fires a left-click when the crosshair overlaps an enemy's hitbox.
 * Hitboxes are computed from real bone screen-positions (from the skeleton ESP)
 * rather than a configurable pixel radius, giving pixel-accurate targeting.
 *
 * <h3>Targeting modes</h3>
 * <ul>
 * <li><b>Head</b> – bone 7 (head bone), ±radius derived from box height/12</li>
 * <li><b>Body</b> – bones 4, 2, 6 (chest, stomach, neck)</li>
 * <li><b>Legs</b> – bones 17, 18, 19, 20, 21, 22 (hips to feet)</li>
 * <li><b>All</b> – any of the above</li>
 * </ul>
 *
 * <h3>One-shot mode</h3>
 * After firing, the bot enters a cooldown during which it ignores the same
 * target (tracked by player index). This prevents burst-fire on a single
 * target when the crosshair stays on them after the shot.
 */
public class TriggerBotModule extends CheatModule {

    // ── Bone index constants (must match ESPModule / PositionReader) ───────────
    private static final int BONE_HEAD = 7;
    private static final int BONE_NECK = 6;
    private static final int BONE_CHEST = 4; // Spine2
    private static final int BONE_STOMACH = 2; // Spine0
    private static final int BONE_PELVIS = 1;
    private static final int BONE_L_HIP = 17;
    private static final int BONE_L_KNEE = 18;
    private static final int BONE_L_FOOT = 19;
    private static final int BONE_R_HIP = 20;
    private static final int BONE_R_KNEE = 21;
    private static final int BONE_R_FOOT = 22;

    /** Bones checked for each targeting mode. */
    private static final int[][] MODE_BONES = {
            { BONE_HEAD }, // 0 = Head
            { BONE_NECK, BONE_CHEST, BONE_STOMACH }, // 1 = Body
            { BONE_PELVIS, BONE_L_HIP, BONE_L_KNEE, BONE_L_FOOT,
                    BONE_R_HIP, BONE_R_KNEE, BONE_R_FOOT }, // 2 = Legs
            { BONE_HEAD, BONE_NECK, BONE_CHEST, BONE_STOMACH, BONE_PELVIS,
                    BONE_L_HIP, BONE_L_KNEE, BONE_L_FOOT,
                    BONE_R_HIP, BONE_R_KNEE, BONE_R_FOOT } // 3 = All
    };

    // ── Settings ───────────────────────────────────────────────────────────────

    /** Which body region triggers a shot. */
    public final ModeSetting targetMode = new ModeSetting(
            "Target Zone##triggerbot", 0, "Head", "Body", "Legs", "All");

    /**
     * When enabled: after a shot the module ignores the shot player for
     * {@code cooldown} ms and will not shoot again until the crosshair leaves
     * and re-enters a target. Ideal for AWP / Scout.
     */
    public final BooleanSetting oneShotMode = new BooleanSetting("One-Shot Mode##triggerbot", false);

    /** Pause between crosshair-on-target detection and click, in milliseconds. */
    public final FloatSetting reactionDelay = new FloatSetting(
            "Reaction Delay (ms)##triggerbot", 10.0f, 0.0f, 150.0f);

    /** Duration the mouse button is held down, in milliseconds. */
    public final FloatSetting clickDuration = new FloatSetting(
            "Click Duration (ms)##triggerbot", 40.0f, 5.0f, 200.0f);

    /** Post-click cooldown before the next shot is allowed, in milliseconds. */
    public final FloatSetting cooldown = new FloatSetting(
            "Cooldown (ms)##triggerbot", 100.0f, 20.0f, 1000.0f);

    /**
     * Per-bone hit radius as a fraction of the player's screen box height.
     * 0.06 = 6 % of box height — matches the visual bone circle size in the ESP.
     * Only used for body/leg bones; the head radius comes from ESPModule's formula.
     */
    public final FloatSetting boneRadiusFrac = new FloatSetting(
            "Bone Radius (% box)##triggerbot", 0.06f, 0.02f, 0.20f);

    /** Only fire at opponents. */
    public final BooleanSetting enemyOnly = new BooleanSetting("Enemy Only##triggerbot", true);

    /** Require VisCheck line-of-sight before firing. */
    public final BooleanSetting useVisCheck = new BooleanSetting("VisCheck Filter##triggerbot", true);

    /**
     * When enabled, the triggerbot will NOT fire if the local player is moving
     * faster than {@link #maxMoveSpeed} units/sec. Useful for rifles where
     * accuracy is penalised while moving.
     */
    public final BooleanSetting stopWhenMoving = new BooleanSetting("Stop When Moving##triggerbot", false);

    /**
     * Maximum local-player ground speed (units/sec) allowed while firing.
     * At 64-tick CS2: walking ≈ 130, running ≈ 250. Default 50 = essentially
     * stationary (allows the tiny drift while standing).
     */
    public final FloatSetting maxMoveSpeed = new FloatSetting(
            "Max Move Speed (u/s)##triggerbot", 50.0f, 0.0f, 300.0f);

    // ── Internals ─────────────────────────────────────────────────────────────

    private volatile boolean triggerThreadRunning = false;
    private Thread triggerThread;
    private Robot robot;

    /**
     * Index of the last player shot (one-shot mode: skip until crosshair leaves).
     */
    private volatile int lastShotIndex = -1;

    // ── Constructor ───────────────────────────────────────────────────────────

    public TriggerBotModule() {
        super("TriggerBot", ModuleCategory.EXTERNAL, MenuGroup.COMBAT, false);
        addSetting(targetMode);
        addSetting(oneShotMode);
        addSetting(reactionDelay);
        addSetting(clickDuration);
        addSetting(cooldown);
        addSetting(boneRadiusFrac);
        addSetting(enemyOnly);
        addSetting(useVisCheck);
        addSetting(stopWhenMoving);
        addSetting(maxMoveSpeed);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        if (isEnabled() && !triggerThreadRunning) {
            startTriggerThread();
        } else if (!isEnabled() && triggerThreadRunning) {
            triggerThreadRunning = false;
        }
    }

    // ── Thread ────────────────────────────────────────────────────────────────

    private void startTriggerThread() {
        if (triggerThread != null && triggerThread.isAlive())
            return;

        if (robot == null) {
            try {
                robot = new Robot();
            } catch (Exception e) {
                System.err.println("[TriggerBot] Robot init failed: " + e.getMessage());
                return;
            }
        }

        triggerThreadRunning = true;
        triggerThread = new Thread(() -> {
            System.out.println("[TriggerBot] Thread started.");

            while (triggerThreadRunning && isEnabled()) {
                try {
                    if (!PlayerCache.tracking) {
                        Thread.sleep(100);
                        continue;
                    }

                    // Pause while overlay menu is open
                    if (OverlayWindow.isMenuOpen()) {
                        Thread.sleep(50);
                        continue;
                    }

                    List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                    if (players.isEmpty()) {
                        Thread.yield();
                        continue;
                    }

                    float cx = PlayerCache.screenWidth * 0.5f;
                    float cy = PlayerCache.screenHeight * 0.5f;

                    // ── Velocity check: skip if local player is moving too fast ────
                    if (stopWhenMoving.getValue()) {
                        float threshold = maxMoveSpeed.getValue();
                        // Approximate local speed from the local player snapshot
                        float localSpeed = 0f;
                        for (PlayerSnapshot lp : players) {
                            if (!lp.isLocal)
                                continue;
                            float vx = lp.velX, vy = lp.velY; // horizontal only
                            localSpeed = (float) Math.sqrt(vx * vx + vy * vy);
                            break;
                        }
                        if (localSpeed > threshold) {
                            Thread.yield();
                            continue;
                        }
                    }

                    // Build VisCheck camera once per iteration
                    Vector3 localCamera = buildLocalCamera();

                    // Snapshot current settings (avoid re-reading volatile fields in loop)
                    int modeIdx = targetMode.getValue();
                    int[] bones = MODE_BONES[modeIdx];
                    float radFrac = boneRadiusFrac.getValue();
                    boolean oneShot = oneShotMode.getValue();

                    int hitPlayerIndex = -1;

                    for (PlayerSnapshot p : players) {
                        if (p.isLocal || !p.onScreen)
                            continue;
                        if (enemyOnly.getValue() && p.team == ESPModule.localTeam)
                            continue;

                        // One-shot: skip the last-shot player until crosshair leaves
                        if (oneShot && p.index == lastShotIndex)
                            continue;

                        // Check each bone for this targeting mode
                        int aimedBone = getAimedBone(p, cx, cy, bones, radFrac);
                        if (aimedBone != -1) {
                            // VisCheck ONLY the specific bone aimed at
                            if (useVisCheck.getValue() && !isBoneVisible(p, aimedBone, localCamera))
                                continue;

                            hitPlayerIndex = p.index;
                            break;
                        }
                    }

                    // One-shot: clear lastShotIndex when crosshair leaves all targets
                    if (oneShot && hitPlayerIndex == -1) {
                        lastShotIndex = -1;
                    }

                    if (hitPlayerIndex != -1) {
                        // Reaction delay
                        int delay = reactionDelay.getValue().intValue();
                        if (delay > 0)
                            Thread.sleep(delay);

                        // Re-verify after delay
                        int modeIdx2 = targetMode.getValue();
                        int[] bones2 = MODE_BONES[modeIdx2];
                        float radFrac2 = boneRadiusFrac.getValue();
                        boolean stillOn = false;
                        for (PlayerSnapshot p : PlayerCache.renderPlayers) {
                            if (p.index != hitPlayerIndex || p.isLocal || !p.onScreen)
                                continue;
                            int aimedBone2 = getAimedBone(p, cx, cy, bones2, radFrac2);
                            if (aimedBone2 != -1) {
                                if (useVisCheck.getValue() && !isBoneVisible(p, aimedBone2, buildLocalCamera()))
                                    continue;
                                stillOn = true;
                                break;
                            }
                        }

                        if (stillOn) {
                            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                            Thread.sleep((long) clickDuration.getValue().floatValue());
                            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

                            if (oneShot)
                                lastShotIndex = hitPlayerIndex;

                            Thread.sleep((long) cooldown.getValue().floatValue());
                        }
                    }

                    Thread.yield();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[TriggerBot] Error: " + e.getMessage()); 
                    e.printStackTrace();
                }
            }

            triggerThreadRunning = false;
            System.out.println("[TriggerBot] Thread stopped.");
        }, "Athenis-TriggerBot");

        triggerThread.setDaemon(true);
        triggerThread.setPriority(Thread.MAX_PRIORITY);
        triggerThread.start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int getAimedBone(PlayerSnapshot p,
            float cx, float cy,
            int[] bones, float radFrac) {
        if (p.boneX.length == 0)
            return -1;

        float boxHeight = p.feetY - p.headY;

        for (int bone : bones) {
            if (bone >= p.boneX.length)
                continue;
            if (!p.boneVisible[bone])
                continue;

            float bx = p.boneX[bone];
            float by = p.boneY[bone];

            float r;
            if (bone == BONE_HEAD) {
                // Mirror exactly the visual head-circle radius from ESPModule
                r = Math.max(3.0f, Math.min(12.0f, boxHeight / 12.0f));
            } else {
                r = boxHeight * radFrac;
            }

            float dx = cx - bx;
            float dy = cy - by;
            if (dx * dx + dy * dy <= r * r)
                return bone;
        }
        return -1;
    }

    /**
     * Reads the local player's eye camera position for VisCheck, or null if
     * unavailable.
     */
    private Vector3 buildLocalCamera() {
        VisCheck vc = VisCheckAdapter.getVisCheck();
        if (!useVisCheck.getValue() || vc == null)
            return null;
        long pawn = PlayerCache.localPlayerPawnAddress;
        if (pawn == 0)
            return null;
        Vector3 origin = CS2Memory.readVector(pawn + CS2Offsets.m_vOldOrigin);
        if (origin == null)
            return null;
        return new Vector3(origin.x, origin.y, origin.z + 64.0f);
    }

    /**
     * Returns true if the target player passes the VisCheck from the local camera.
     */
    private boolean isBoneVisible(PlayerSnapshot p, int bone, Vector3 localCamera) {
        if (!useVisCheck.getValue())
            return true;
        VisCheck vc = VisCheckAdapter.getVisCheck();
        if (vc == null || localCamera == null)
            return true;
        // Use world origin + eye height as the target point
        Vector3 targetEye = new Vector3(p.worldX, p.worldY, p.worldZ + 72.0f);
        return vc.isPointVisible(localCamera, targetEye);
    }
}
