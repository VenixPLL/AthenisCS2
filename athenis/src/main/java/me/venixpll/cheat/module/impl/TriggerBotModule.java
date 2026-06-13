package me.venixpll.cheat.module.impl;

import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheckAdapter;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.List;

/**
 * TriggerBot Module.
 * <p>
 * Automatically fires a left-click when the local player's crosshair is over
 * an enemy's head hitbox. Detection runs on a dedicated high-priority daemon
 * thread that stays tight-looped (yield-only) to minimise reaction latency.
 * <p>
 * <h3>Detection method</h3>
 * The crosshair in screen space is always at the centre of the game viewport
 * {@code (screenWidth/2, screenHeight/2)}.  The head position is taken from the
 * latest immutable {@link PlayerSnapshot} list — the same extrapolated,
 * race-condition-free data used by the ESP renderer.  A hit is registered when
 * the Euclidean distance from crosshair to head-centre is ≤ {@link #headRadius}.
 * <p>
 * <h3>Click simulation</h3>
 * Uses {@link Robot#mousePress} / {@link Robot#mouseRelease} (backed by
 * Win32 {@code SendInput} on Windows) — no custom JNI, no extra DLLs.
 */
public class TriggerBotModule extends CheatModule {

    /**
     * Acceptable crosshair-to-head distance in screen pixels.
     * Increase if the bot misses; decrease to require more precise aim.
     */
    public final FloatSetting headRadius = new FloatSetting(
            "Head Hitbox (px)", 18.0f, 4.0f, 60.0f);

    /**
     * Pause between detecting a target and firing the click, in milliseconds.
     * A small value (10–30 ms) adds human-like latency.  Set to 0 for instant.
     */
    public final FloatSetting reactionDelay = new FloatSetting(
            "Reaction Delay (ms)", 10.0f, 0.0f, 100.0f);

    /**
     * How long the left mouse button is held down, in milliseconds.
     * Shorter values result in a quicker tap; longer values simulate a held shot.
     */
    public final FloatSetting clickDuration = new FloatSetting(
            "Click Duration (ms)", 40.0f, 10.0f, 200.0f);

    /**
     * Post-click cooldown in milliseconds before the next trigger is checked.
     * Prevents rapid-fire double-shots.
     */
    public final FloatSetting cooldown = new FloatSetting(
            "Cooldown (ms)", 80.0f, 20.0f, 500.0f);

    /**
     * When {@code true}, the triggerbot only fires at players on the opposing team.
     * Set {@code false} to fire at any player (including teammates — use carefully).
     */
    public final BooleanSetting enemyOnly = new BooleanSetting("Enemy Only", true);

    /**
     * When {@code true}, the triggerbot will only fire if the target's head is visible via VisCheck.
     */
    public final BooleanSetting useVisCheck = new BooleanSetting("Use VisCheck Filter", true);

    // ── Internals ─────────────────────────────────────────────────────────────

    private volatile boolean triggerThreadRunning = false;
    private Thread triggerThread;

    /**
     * Shared {@link Robot} instance. Created once on first enable and reused to
     * avoid OS-level initialisation overhead on every click.
     */
    private Robot robot;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Constructs the TriggerBot module and registers its settings.
     * Disabled by default — the user must enable it in the overlay menu.
     */
    public TriggerBotModule() {
        super("TriggerBot", false);
        addSetting(headRadius);
        addSetting(reactionDelay);
        addSetting(clickDuration);
        addSetting(cooldown);
        addSetting(enemyOnly);
        addSetting(useVisCheck);
    }

    /**
     * Called by the slow data thread at ~10 Hz.
     * Manages the lifecycle of the dedicated trigger thread in response to the
     * module's enabled state.
     */
    @Override
    public void onTick() {
        if (isEnabled() && !triggerThreadRunning) {
            startTriggerThread();
        } else if (!isEnabled() && triggerThreadRunning) {
            triggerThreadRunning = false;   // thread exits on next iteration
        }
    }

    // ── Thread ────────────────────────────────────────────────────────────────

    private void startTriggerThread() {
        if (triggerThread != null && triggerThread.isAlive()) return;

        // Initialise Robot here on the first start so any AWTException is logged
        // rather than silently swallowed during module construction.
        if (robot == null) {
            try {
                robot = new Robot();
            } catch (Exception e) {
                System.err.println("[TriggerBot] Failed to create Robot: " + e.getMessage());
                return;
            }
        }

        triggerThreadRunning = true;
        triggerThread = new Thread(() -> {
            System.out.println("[TriggerBot] Trigger thread started.");

            while (triggerThreadRunning && isEnabled()) {
                try {
                    if (!PlayerCache.tracking) {
                        Thread.sleep(100);
                        continue;
                    }

                    List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                    if (players.isEmpty()) {
                        Thread.yield();
                        continue;
                    }

                    // Crosshair is at the exact centre of the game viewport in
                    // screen-projection space (same coordinate system as headX/Y).
                    float cx = PlayerCache.screenWidth  * 0.5f;
                    float cy = PlayerCache.screenHeight * 0.5f;

                    float r  = headRadius.getValue();
                    float r2 = r * r;   // compare squared distances — no sqrt needed

                    boolean onHead = false;

                    // Read local camera position once per tick
                    Vector3 localCamera = null;
                    VisCheck visCheck = VisCheckAdapter.getVisCheck();
                    if (useVisCheck.getValue() && visCheck != null) {
                        long localPawn = PlayerCache.localPlayerPawnAddress;
                        if (localPawn != 0) {
                            Vector3 localOrigin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                            if (localOrigin != null) {
                                localCamera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);
                            }
                        }
                    }

                    for (PlayerSnapshot p : players) {
                        if (p.isLocal || !p.onScreen) continue;
                        if (enemyOnly.getValue() && p.team == ESPModule.localTeam) continue;
                        if (useVisCheck.getValue() && visCheck != null && localCamera != null) {
                            Vector3 targetHead = new Vector3(p.worldX, p.worldY, p.worldZ + 72.0f);
                            if (!visCheck.isPointVisible(localCamera, targetHead)) {
                                continue;
                            }
                        }

                        // Distance from crosshair to projected head position.
                        // headX/headY are the top-of-box projection; the visual head
                        // centre is a few pixels below — add a small Y bias.
                        float biasPx = (p.feetY - p.headY) * 0.05f;  // ~5% of box height
                        float dx = cx - p.headX;
                        float dy = cy - (p.headY + biasPx);

                        if (dx * dx + dy * dy <= r2) {
                            onHead = true;
                            break;
                        }
                    }

                    if (onHead) {
                        // ── Reaction delay ────────────────────────────────────
                        int delay = reactionDelay.getValue().intValue();
                        if (delay > 0) Thread.sleep(delay);

                        // Re-check: target may have moved during reaction delay.
                        if (!stillOnHead(cx, cy, r2)) {
                            Thread.yield();
                            continue;
                        }

                        // ── Fire click ────────────────────────────────────────
                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        Thread.sleep((long) clickDuration.getValue().floatValue());
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

                        // ── Post-click cooldown ───────────────────────────────
                        Thread.sleep((long) cooldown.getValue().floatValue());
                    }

                    // Yield-only — no sleep when idle so detection latency is minimal.
                    Thread.yield();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[TriggerBot] Error: " + e.getMessage());
                }
            }

            triggerThreadRunning = false;
            System.out.println("[TriggerBot] Trigger thread stopped.");
        }, "Athenis-TriggerBot");

        triggerThread.setDaemon(true);
        triggerThread.setPriority(Thread.MAX_PRIORITY);
        triggerThread.start();
    }

    /**
     * Re-checks whether the crosshair is still on an enemy head after the
     * reaction delay has elapsed.  Called just before firing to avoid wasting
     * a shot on a target that has already moved away.
     *
     * @param cx Crosshair X (screen centre).
     * @param cy Crosshair Y (screen centre).
     * @param r2 Squared hit-radius threshold.
     * @return {@code true} if at least one enemy head is still within range.
     */
    private boolean stillOnHead(float cx, float cy, float r2) {
        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
        VisCheck visCheck = VisCheckAdapter.getVisCheck();
        Vector3 localCamera = null;
        if (useVisCheck.getValue() && visCheck != null) {
            long localPawn = PlayerCache.localPlayerPawnAddress;
            if (localPawn != 0) {
                Vector3 localOrigin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                if (localOrigin != null) {
                    localCamera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);
                }
            }
        }

        for (PlayerSnapshot p : players) {
            if (p.isLocal || !p.onScreen) continue;
            if (enemyOnly.getValue() && p.team == ESPModule.localTeam) continue;
            if (useVisCheck.getValue() && visCheck != null && localCamera != null) {
                Vector3 targetHead = new Vector3(p.worldX, p.worldY, p.worldZ + 72.0f);
                if (!visCheck.isPointVisible(localCamera, targetHead)) {
                    continue;
                }
            }
            float biasPx = (p.feetY - p.headY) * 0.05f;
            float dx = cx - p.headX;
            float dy = cy - (p.headY + biasPx);
            if (dx * dx + dy * dy <= r2) return true;
        }
        return false;
    }
}
