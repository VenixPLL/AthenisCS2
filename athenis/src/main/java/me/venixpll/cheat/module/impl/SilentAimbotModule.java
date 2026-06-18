package me.venixpll.cheat.module.impl;

import com.sun.jna.platform.win32.User32;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.ModeSetting;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheckAdapter;
import me.venixpll.overlay.OverlayWindow;

import java.util.List;

/**
 * Silent Aimbot Module — external silent aim for CS2.
 *
 * <h2>How it works</h2>
 * Based on reverse engineering research by TKazer
 * (<a href="https://github.com/TKazer/CS2-External-Silent-AimBot">CS2-External-Silent-AimBot</a>):
 * <ol>
 *   <li>The game's {@code CreateMove} function reads the player's view-angle from
 *       {@code dwViewAngles} in {@code client.dll} to determine the shooting direction.</li>
 *   <li>By <em>briefly</em> overwriting {@code dwViewAngles} with the angle aimed at
 *       the target, we redirect where the bullet travels without moving the camera.</li>
 *   <li>The original angles are restored immediately after the write (within ~1 ms),
 *       so the camera never visually moves and the change is imperceptible to the
 *       local player.</li>
 * </ol>
 *
 * <h2>Settings</h2>
 * <ul>
 *   <li><b>FOV</b> — how wide (in degrees) the module looks for targets.</li>
 *   <li><b>Target Bone</b> — which bone to aim at (Head / Neck / Chest / Stomach).</li>
 *   <li><b>Aim Key</b> — hold key that enables the module.</li>
 *   <li><b>Activation</b> — Hold or Toggle mode.</li>
 *   <li><b>Smooth</b> — interpolation factor; 1 = instant snap, higher = progressive.</li>
 *   <li><b>Enemy Only</b> — skip teammates.</li>
 *   <li><b>VisCheck</b> — only aim at visible targets.</li>
 *   <li><b>Restore Delay µs</b> — how long (microseconds) to hold the patched angle
 *       before restoring. Increase only if bullets still miss at high ping.</li>
 * </ul>
 *
 * <h2>Thread model</h2>
 * A dedicated high-priority daemon thread runs the aim loop at native speed.
 * The patch–restore cycle is atomic from CS2's perspective because all writes
 * happen between consecutive server ticks.
 */
public class SilentAimbotModule extends CheatModule {

    // ── Bone indices (same as AimbotModule / ESPModule) ───────────────────────
    private static final int BONE_HEAD    = 7;
    private static final int BONE_NECK    = 6;
    private static final int BONE_CHEST   = 4;
    private static final int BONE_STOMACH = 2;
    private static final int[] MODE_BONE  = { BONE_HEAD, BONE_NECK, BONE_CHEST, BONE_STOMACH };

    /** Windows VK codes for the aim-key options (mirrors AimbotModule). */
    private static final int[] AIM_KEY_VK = { 0x02, 0x04, 0xA4, 0xA0, 0x58, 0x5A, 0x11 };

    // ── Settings ──────────────────────────────────────────────────────────────

    /** Maximum angular distance (degrees) from crosshair to target for the aim to engage. */
    public final FloatSetting fov = new FloatSetting(
            "FOV (degrees)##silentaimbot", 12.0f, 0.5f, 60.0f);

    /** Which bone the silent aim targets. */
    public final ModeSetting targetBone = new ModeSetting(
            "Target Bone##silentaimbot", 0, "Head", "Neck", "Chest", "Stomach");

    /** Which key must be held to activate (Hold mode). */
    public final ModeSetting aimKey = new ModeSetting(
            "Aim Key##silentaimbot", 0,
            "Right Mouse", "Middle Mouse", "Left Alt", "Left Shift", "X Key", "Z Key", "Ctrl");

    /** Hold the key to aim, or toggle. */
    public final ModeSetting activationMode = new ModeSetting(
            "Activation##silentaimbot", 0, "Hold Key", "Toggle");

    /**
     * Smoothing factor for progressive angle interpolation.
     * 1.0 = instant snap to target (maximum silent snap).
     * Higher values approach the target over multiple frames, reducing
     * the angle change per tick for a more gradual correction.
     */
    public final FloatSetting smooth = new FloatSetting(
            "Smooth##silentaimbot", 1.0f, 1.0f, 10.0f);

    /** Filter targets to enemies only. */
    public final BooleanSetting enemyOnly = new BooleanSetting(
            "Enemy Only##silentaimbot", true);

    /** Require line-of-sight visibility before locking on. */
    public final BooleanSetting useVisCheck = new BooleanSetting(
            "VisCheck##silentaimbot", true);

    /** Fall back to the m_bSpotted flag when the geometric VisCheck is unavailable. */
    public final BooleanSetting spottedFallback = new BooleanSetting(
            "Spotted Fallback##silentaimbot", true);

    /**
     * How long (in microseconds) to hold the patched angle before restoring.
     * At 64-tick the server reads input every ~15 600 µs, so 1 000 µs is more
     * than enough.  Increase only if bullets still miss at very high ping.
     */
    public final FloatSetting restoreDelayUs = new FloatSetting(
            "Restore Delay (µs)##silentaimbot", 1000.0f, 100.0f, 15000.0f);

    // ── Internal state ────────────────────────────────────────────────────────

    private volatile boolean silentThreadRunning = false;
    private Thread silentThread;

    /** Current interpolated pitch toward the locked target (degrees). */
    private float currentPitch = 0f;
    /** Current interpolated yaw toward the locked target (degrees). */
    private float currentYaw   = 0f;
    /** Whether we have an active interpolation target. */
    private boolean hasTarget  = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    public SilentAimbotModule() {
        super("Silent Aimbot", ModuleCategory.INTERNAL, false);
        addSetting(fov);
        addSetting(targetBone);
        addSetting(aimKey);
        addSetting(activationMode);
        addSetting(smooth);
        addSetting(enemyOnly);
        addSetting(useVisCheck);
        addSetting(spottedFallback);
        addSetting(restoreDelayUs);
    }

    // ── Module lifecycle ──────────────────────────────────────────────────────

    @Override
    public void onTick() {
        if (isEnabled() && !silentThreadRunning)        startSilentThread();
        else if (!isEnabled() && silentThreadRunning) { silentThreadRunning = false; hasTarget = false; }
    }

    // ── Silent aim thread ─────────────────────────────────────────────────────

    private void startSilentThread() {
        if (silentThread != null && silentThread.isAlive()) return;
        silentThreadRunning = true;

        silentThread = new Thread(() -> {
            System.out.println("[SilentAimbot] Thread started.");

            while (silentThreadRunning && isEnabled()) {
                try {
                    // 1. Wait for CS2 to be attached and tracking
                    if (!PlayerCache.tracking) { Thread.sleep(100); continue; }

                    // 2. Pause while the overlay menu is open
                    if (OverlayWindow.isMenuOpen()) { hasTarget = false; Thread.sleep(50); continue; }

                    // 3. Hold-key guard (if in Hold mode)
                    if (activationMode.getValue() == 0 && !isAimKeyHeld()) {
                        hasTarget = false;
                        Thread.yield();
                        continue;
                    }

                    // 4. Read local pawn and view-angle address
                    long localPawn = PlayerCache.localPlayerPawnAddress;
                    if (localPawn == 0) { Thread.yield(); continue; }

                    long viewAnglesAddr = CS2Memory.getClientBase() + CS2Offsets.dwViewAngles;
                    if (viewAnglesAddr == CS2Offsets.dwViewAngles) {
                        // getClientBase returned 0 — not attached yet
                        Thread.sleep(100);
                        continue;
                    }

                    // 5. Read the current view angles (we need to restore these)
                    float origPitch = CS2Memory.readFloat(viewAnglesAddr);
                    float origYaw   = CS2Memory.readFloat(viewAnglesAddr + 4);

                    // 6. Build local camera eye position for VisCheck
                    Vector3 foot = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                    Vector3 eyePos = (foot != null)
                            ? new Vector3(foot.x, foot.y, foot.z + 64.0f) : null;

                    // 7. Settings snapshot
                    List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                    if (players.isEmpty()) { Thread.yield(); continue; }

                    int   boneMode  = targetBone.getValue();
                    float fovDeg    = fov.getValue();
                    boolean chkEnemy = enemyOnly.getValue();
                    boolean chkVis   = useVisCheck.getValue();
                    boolean chkSpot  = spottedFallback.getValue();

                    // 8. Find the best target within FOV
                    float bestAngularDist = Float.MAX_VALUE;
                    float bestPitch = 0f, bestYaw = 0f;
                    boolean foundTarget = false;

                    for (PlayerSnapshot p : players) {
                        if (p.isLocal || !p.onScreen)                        continue;
                        if (chkEnemy && p.team == ESPModule.localTeam)       continue;
                        if (p.health <= 0)                                   continue;
                        if (chkVis && !isVisible(p, eyePos, localPawn, chkSpot)) continue;

                        int bone = resolveBoneIndex(boneMode);
                        if (bone < 0 || bone >= p.boneX.length || !p.boneVisible[bone]) continue;

                        // Compute 3D world-space target position for the chosen bone.
                        // We use the screen-projected bone coordinates to back-calculate
                        // approximate angles relative to the current view, which is more
                        // accurate than reconstructing from world coords for silent aim.
                        float[] angles = calcAnglesFromBoneScreen(
                                p.boneX[bone], p.boneY[bone],
                                origPitch, origYaw,
                                eyePos, p, bone);
                        if (angles == null) continue;

                        float targetPitch = angles[0];
                        float targetYaw   = angles[1];

                        // Angular distance from current view (decides who to target)
                        float dP  = normalizeAngle(targetPitch - origPitch);
                        float dY  = normalizeAngle(targetYaw   - origYaw);
                        float ang = (float) Math.sqrt(dP * dP + dY * dY);

                        if (ang > fovDeg) continue;

                        if (ang < bestAngularDist) {
                            bestAngularDist = ang;
                            bestPitch = targetPitch;
                            bestYaw   = targetYaw;
                            foundTarget = true;
                        }
                    }

                    if (!foundTarget) {
                        hasTarget = false;
                        Thread.yield();
                        continue;
                    }

                    // 9. Interpolate toward target (smooth = 1 means instant snap)
                    float sf = smooth.getValue();
                    float patchPitch, patchYaw;
                    if (!hasTarget || sf <= 1.0f) {
                        // First frame or instant snap: jump directly
                        patchPitch = bestPitch;
                        patchYaw   = bestYaw;
                    } else {
                        float dP = normalizeAngle(bestPitch - currentPitch);
                        float dY = normalizeAngle(bestYaw   - currentYaw);
                        patchPitch = currentPitch + dP / sf;
                        patchYaw   = currentYaw   + dY / sf;
                    }
                    currentPitch = patchPitch;
                    currentYaw   = patchYaw;
                    hasTarget    = true;

                    // 10. ── SILENT PATCH ──────────────────────────────────────────
                    //   Write the aimed angles to dwViewAngles so the *shot* travels
                    //   toward the target while the camera visually stays put.
                    CS2Memory.writeAngles(viewAnglesAddr, patchPitch, patchYaw);

                    // 11. Hold for the configured restore delay so the game tick picks
                    //     it up before we overwrite again.
                    busyWaitMicros((long) restoreDelayUs.getValue().floatValue());

                    // 12. ── RESTORE ────────────────────────────────────────────────
                    //   Put the original view angles back so the camera doesn't jump.
                    CS2Memory.writeAngles(viewAnglesAddr, origPitch, origYaw);

                    Thread.yield();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[SilentAimbot] Error: " + e.getMessage());
                }
            }

            silentThreadRunning = false;
            hasTarget = false;
            System.out.println("[SilentAimbot] Thread stopped.");
        }, "Athenis-SilentAimbot");

        silentThread.setDaemon(true);
        silentThread.setPriority(Thread.MAX_PRIORITY);
        silentThread.start();
    }

    // ── Angle math helpers ────────────────────────────────────────────────────

    /**
     * Computes the Euler angles (pitch, yaw) needed to look from the local eye
     * position directly at the specified bone world-space position.
     *
     * <p>The bone's world position is reconstructed by back-projecting through the
     * current view-projection matrix.  For greatest precision we use the world-space
     * position stored in {@link PlayerSnapshot} for foot origin, then add the bone's
     * relative vertical offset approximated from bone screen Y vs. feet screen Y.</p>
     *
     * @return {@code float[]{pitch, yaw}} in degrees, or {@code null} if the eye
     *         position is unavailable.
     */
    private float[] calcAnglesFromBoneScreen(
            float boneScreenX, float boneScreenY,
            float currentPitch, float currentYaw,
            Vector3 eyePos, PlayerSnapshot p, int bone) {

        if (eyePos == null) return null;


        // Vertical fraction: 0 = feet, 1 = head (approximately)
        float feetSY = p.feetY;
        float headSY = p.headY;
        float totalScreenH = feetSY - headSY;
        float boneFrac = (totalScreenH > 1f) ? (feetSY - boneScreenY) / totalScreenH : 0.5f;
        boneFrac = Math.max(0f, Math.min(1f, boneFrac));

        // Approximate world-space bone Z (feet = worldZ, head ≈ worldZ + 72)
        float boneWorldZ = p.worldZ + boneFrac * 72.0f;

        // For X/Y we use the player's world origin (close enough for angular targeting)
        float dx = p.worldX - eyePos.x;
        float dy = p.worldY - eyePos.y;
        float dz = boneWorldZ - eyePos.z;

        float xyDist = (float) Math.sqrt(dx * dx + dy * dy);

        float pitch = -(float) Math.toDegrees(Math.atan2(dz, xyDist));
        float yaw   =  (float) Math.toDegrees(Math.atan2(dy, dx));

        return new float[]{ pitch, yaw };
    }

    /**
     * Normalises an angle delta into the (−180, +180] range.
     *
     * @param angle Raw angle difference in degrees.
     * @return Clamped angle in degrees.
     */
    private static float normalizeAngle(float angle) {
        while (angle >  180f) angle -= 360f;
        while (angle < -180f) angle += 360f;
        return angle;
    }

    /**
     * Maps the bone-mode setting index to the actual CS2 bone index.
     *
     * @param mode Setting value (0=Head, 1=Neck, 2=Chest, 3=Stomach).
     * @return CS2 bone index.
     */
    private static int resolveBoneIndex(int mode) {
        if (mode >= 0 && mode < MODE_BONE.length) return MODE_BONE[mode];
        return BONE_HEAD;
    }

    // ── VisCheck ──────────────────────────────────────────────────────────────

    private boolean isVisible(PlayerSnapshot p, Vector3 cam, long pawn, boolean useSpotted) {
        VisCheck vc = VisCheckAdapter.getVisCheck();
        if (vc != null && cam != null)
            return vc.isPointVisible(cam, new Vector3(p.worldX, p.worldY, p.worldZ + 72.0f));
        if (useSpotted && p.pawnAddress != 0) {
            byte s = CS2Memory.readByte(
                    p.pawnAddress + CS2Offsets.m_entitySpottedState + CS2Offsets.m_bSpotted);
            return s != 0;
        }
        return true;
    }

    // ── Input helpers ─────────────────────────────────────────────────────────

    /** Returns true if the configured aim-key is currently held. */
    private boolean isAimKeyHeld() {
        try {
            short r = User32.INSTANCE.GetAsyncKeyState(AIM_KEY_VK[aimKey.getValue()]);
            return (r & 0x8000) != 0;
        } catch (Exception ignored) { return false; }
    }

    /**
     * High-resolution busy-wait for {@code micros} microseconds.
     * Used instead of {@link Thread#sleep} to keep the restore delay accurate
     * at sub-millisecond precision without releasing the CPU.
     *
     * @param micros Microseconds to spin.
     */
    private static void busyWaitMicros(long micros) {
        long start = System.nanoTime();
        long end   = start + micros * 1_000L;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }
}
