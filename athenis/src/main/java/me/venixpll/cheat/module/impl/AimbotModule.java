package me.venixpll.cheat.module.impl;

import com.sun.jna.Library;
import com.sun.jna.Native;
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
import java.util.Random;

/**
 * Aimbot Module — screen-space aim assist for CS2.
 *
 * Uses p.boneX / p.boneY (game-projected bone screen coordinates) instead of
 * approximate world-space reconstruction, so the crosshair lands exactly on
 * the visible bone regardless of player stance, crouch or animation.
 *
 * Conversion: screen_offset_px * hFOV / (screenWidth * 0.022 * sensitivity)
 * = mouse counts for mouse_event(MOUSEEVENTF_MOVE)
 *
 * Rate-limit : 500 Hz nanosecond gate (same principle as DragonBurn AimDelay).
 * Sub-pixel : fractional-px accumulator prevents stalling at zero.
 */
public class AimbotModule extends CheatModule {

    // ── Win32 relative mouse injection ────────────────────────────────────────
    private interface Win32Mouse extends Library {
        Win32Mouse INSTANCE = Native.load("user32", Win32Mouse.class);

        void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);
    }

    private static final int MOUSEEVENTF_MOVE = 0x0001;

    // ── Bone indices ──────────────────────────────────────────────────────────
    private static final int BONE_HEAD = 7;
    private static final int BONE_NECK = 6;
    private static final int BONE_CHEST = 4;
    private static final int BONE_STOMACH = 2;
    /** Index 4 = "Closest" — resolved per-player at runtime. */
    private static final int[] MODE_BONE = { 7, 6, 4, 2, -1 };

    /** Windows VK codes for each aim-key option (matches aimKey setting order). */
    private static final int[] AIM_KEY_VK = { 0x02, 0x04, 0xA4, 0xA0, 0x58, 0x5A, 0x11 };

    // ── Settings ──────────────────────────────────────────────────────────────
    public final ModeSetting targetBone = new ModeSetting(
            "Target Bone##aimbot", 0, "Head", "Neck", "Chest", "Stomach", "Closest");
    public final ModeSetting activationMode = new ModeSetting(
            "Activation##aimbot", 1, "Hold Key", "Toggle");
    public final ModeSetting aimKey = new ModeSetting(
            "Aim Key##aimbot", 0,
            "Right Mouse", "Middle Mouse", "Left Alt", "Left Shift", "X Key", "Z Key", "Ctrl");
    public final FloatSetting fov = new FloatSetting(
            "FOV (degrees)##aimbot", 0.7f, 0.5f, 45.0f);
    public final FloatSetting fovMin = new FloatSetting(
            "FOV Min##aimbot", 0.01f, 0.01f, 3.0f);
    public final FloatSetting smooth = new FloatSetting(
            "Smooth##aimbot", 6.5f, 1.0f, 30.0f);
    public final FloatSetting sensitivity = new FloatSetting(
            "Sensitivity##aimbot", 1.5f, 0.1f, 10.0f);
    public final BooleanSetting humanize = new BooleanSetting(
            "Humanize##aimbot", true);
    public final FloatSetting humanizeStrength = new FloatSetting(
            "Humanize Strength##aimbot", 3.6f, 0.0f, 20.0f);
    public final BooleanSetting enemyOnly = new BooleanSetting(
            "Enemy Only##aimbot", true);
    public final BooleanSetting useVisCheck = new BooleanSetting(
            "VisCheck##aimbot", true);
    public final BooleanSetting spottedFallback = new BooleanSetting(
            "Spotted Fallback##aimbot", true);
    public final BooleanSetting cancelOnShoot = new BooleanSetting(
            "Cancel on Shoot##aimbot", true);
    public final BooleanSetting overshoot = new BooleanSetting(
            "Overshoot##aimbot", false);
    public final FloatSetting overshootScale = new FloatSetting(
            "Overshoot Scale##aimbot", 1.2f, 1.0f, 2.0f);
    public final FloatSetting overshootDuration = new FloatSetting(
            "Overshoot Duration (ms)##aimbot", 150f, 50f, 500f);
    public final BooleanSetting overshootDistanceCheck = new BooleanSetting(
            "Overshoot Distance Check##aimbot", false);
    public final FloatSetting overshootMaxDistance = new FloatSetting(
            "Overshoot Max Distance##aimbot", 500f, 100f, 3000f);

    // ── Internal state ────────────────────────────────────────────────────────
    private volatile boolean aimThreadRunning = false;
    private Thread aimThread;
    private final Random rng = new Random();
    private volatile float prevDeltaX = 0f;
    private volatile float prevDeltaY = 0f;
    private float accumX = 0f;
    private float accumY = 0f;
    private long lastAimNs = 0L;
    private static final long AIM_INTERVAL_NS = 2_000_000L; // 2 ms = 500 Hz cap
    private long overshootTargetAddress = 0L;
    private long overshootStartTime = 0L;
    private float overshootDirX = 0f;
    private float overshootDirY = 0f;
    private float initialDistance = 0f;
    private boolean overshootCompleted = true;

    // ── Constructor ───────────────────────────────────────────────────────────
    public AimbotModule() {
        super("Aimbot", ModuleCategory.EXTERNAL, false);
        addSetting(targetBone);
        addSetting(activationMode);
        addSetting(aimKey);
        addSetting(fov);
        addSetting(fovMin);
        addSetting(smooth);
        addSetting(sensitivity);
        addSetting(humanize);
        addSetting(humanizeStrength);
        addSetting(enemyOnly);
        addSetting(useVisCheck);
        addSetting(spottedFallback);
        addSetting(cancelOnShoot);
        addSetting(overshoot);
        addSetting(overshootScale);
        addSetting(overshootDuration);
        addSetting(overshootDistanceCheck);
        addSetting(overshootMaxDistance);
    }

    // ── Module lifecycle ──────────────────────────────────────────────────────
    @Override
    public void onTick() {
        if (isEnabled() && !aimThreadRunning)
            startAimThread();
        else if (!isEnabled() && aimThreadRunning) {
            aimThreadRunning = false;
            resetState();
        }
    }

    private void resetState() {
        prevDeltaX = 0f;
        prevDeltaY = 0f;
        accumX = 0f;
        accumY = 0f;
        resetOvershoot();
    }

    private void resetOvershoot() {
        overshootTargetAddress = 0L;
        overshootStartTime = 0L;
        overshootDirX = 0f;
        overshootDirY = 0f;
        initialDistance = 0f;
        overshootCompleted = true;
    }

    // ── Aim thread ────────────────────────────────────────────────────────────
    private void startAimThread() {
        if (aimThread != null && aimThread.isAlive())
            return;
        aimThreadRunning = true;

        aimThread = new Thread(() -> {
            System.out.println("[Aimbot] Thread started.");

            while (aimThreadRunning && isEnabled()) {
                try {
                    // 1. Wait for CS2 attachment
                    if (!PlayerCache.tracking) {
                        Thread.sleep(100);
                        continue;
                    }

                    // 1a. Pause while overlay menu is open
                    if (OverlayWindow.isMenuOpen()) {
                        resetState();
                        Thread.sleep(50);
                        continue;
                    }

                    // 2. Hold-key guard
                    if (activationMode.getValue() == 0 && !isAimKeyHeld()) {
                        resetState();
                        Thread.yield();
                        continue;
                    }

                    // 2b. Cancel on Shoot check with vertical mouse jitter
                    if (cancelOnShoot.getValue() && isLeftMouseHeld()) {
                        resetState();
                        Thread.yield();
                        continue;
                    }

                    // 3. Local pawn
                    long localPawn = PlayerCache.localPlayerPawnAddress;
                    if (localPawn == 0) {
                        Thread.yield();
                        continue;
                    }

                    // 4. VisCheck camera (foot origin + eye height)
                    Vector3 foot = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                    Vector3 localCamera = (foot != null)
                            ? new Vector3(foot.x, foot.y, foot.z + 64.0f)
                            : null;

                    // 5. Player list + settings snapshot
                    List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                    if (players.isEmpty()) {
                        Thread.yield();
                        continue;
                    }

                    int boneMode = targetBone.getValue();
                    float fovDeg = fov.getValue();
                    float fovMinDeg = fovMin.getValue();
                    boolean chkEnemy = enemyOnly.getValue();
                    boolean chkVis = useVisCheck.getValue();
                    boolean chkSpot = spottedFallback.getValue();

                    // 6. Derive horizontal FOV from screen aspect ratio.
                    // Assumes CS2 default cl_fov = 90 at 4:3 (CS:GO/CS2 standard).
                    // vFOV_half(4:3) = atan(tan(45°) * 3/4) = 36.87°
                    // hFOV(AR) = 2 * atan(tan(vFOV_half) * AR)
                    // Gives: 4:3 -> 90°, 16:9 -> 106.26°, 16:10 -> 100.39°
                    int sw = Math.max(PlayerCache.screenWidth, 1280);
                    int sh = Math.max(PlayerCache.screenHeight, 720);
                    float ar = (float) sw / (float) sh;
                    float vh = (float) Math.atan(Math.tan(Math.toRadians(45.0)) * 0.75);
                    float hf = 2.0f * (float) Math.toDegrees(Math.atan(Math.tan(vh) * ar));

                    float cx = sw * 0.5f;
                    float cy = sh * 0.5f;
                    float ppd = sw / hf; // screen pixels per degree
                    float fovPx = fovDeg * ppd;
                    float fovMinPx = fovMinDeg * ppd;

                    // mouse counts per screen pixel:
                    // hFOV / (screenWidth * 0.022 * sensitivity)
                    float sens = sensitivity.getValue();
                    float mpp = hf / (sw * 0.022f * sens);

                    // 7. Screen-space target selection
                    float bestDist = Float.MAX_VALUE;
                    float bestDX = 0f;
                    float bestDY = 0f;
                    long bestTargetAddress = 0L;
                    float targetDistToLocal = 0f;

                    for (PlayerSnapshot p : players) {
                        if (p.isLocal || !p.onScreen)
                            continue;
                        if (chkEnemy && p.team == ESPModule.localTeam)
                            continue;
                        if (p.health <= 0)
                            continue;
                        int bone = resolveBone(boneMode, p);
                        if (bone < 0 || bone >= p.boneX.length || !p.boneVisible[bone])
                            continue;

                        if (chkVis && !isBoneVisible(p, bone, localCamera, localPawn, chkSpot))
                            continue;

                        float sdx = p.boneX[bone] - cx;
                        float sdy = p.boneY[bone] - cy;
                        float dist = (float) Math.sqrt(sdx * sdx + sdy * sdy);
                        if (dist >= fovPx)
                            continue;

                        if (dist < bestDist) {
                            bestDist = dist;
                            bestDX = sdx;
                            bestDY = sdy;
                            bestTargetAddress = p.pawnAddress;
                            if (foot != null) {
                                targetDistToLocal = foot.distance(new Vector3(p.worldX, p.worldY, p.worldZ));
                            } else {
                                targetDistToLocal = 0f;
                            }
                        }
                    }

                    // 8. FOV gate
                    if (bestDist == Float.MAX_VALUE || bestDist <= fovMinPx) {
                        resetState();
                        Thread.yield();
                        continue;
                    }

                    // 8.5. Overshoot logic
                    if (overshoot.getValue()) {
                        boolean withinDistance = true;
                        if (overshootDistanceCheck.getValue()) {
                            withinDistance = (targetDistToLocal <= overshootMaxDistance.getValue());
                        }

                        if (withinDistance) {
                            long now = System.currentTimeMillis();
                            if (bestTargetAddress != overshootTargetAddress) {
                                overshootTargetAddress = bestTargetAddress;
                                overshootStartTime = now;
                                overshootCompleted = false;

                                float len = (float) Math.sqrt(bestDX * bestDX + bestDY * bestDY);
                                if (len > 0) {
                                    overshootDirX = bestDX / len;
                                    overshootDirY = bestDY / len;
                                    initialDistance = len;
                                } else {
                                    overshootDirX = 0f;
                                    overshootDirY = 0f;
                                    initialDistance = 0f;
                                }
                            }

                            if (!overshootCompleted) {
                                long elapsed = now - overshootStartTime;
                                float duration = overshootDuration.getValue();
                                if (elapsed >= duration) {
                                    overshootCompleted = true;
                                } else {
                                    float progress = (float) elapsed / duration;
                                    float factor = 6.75f * progress * (1.0f - progress) * (1.0f - progress);
                                    float overshootDist = (overshootScale.getValue() - 1.0f) * initialDistance;
                                    float offsetAmount = overshootDist * factor;

                                    bestDX += offsetAmount * overshootDirX;
                                    bestDY += offsetAmount * overshootDirY;
                                }
                            }
                        } else {
                            resetOvershoot();
                        }
                    } else {
                        resetOvershoot();
                    }

                    // 9. Rate limit — 500 Hz cap
                    long nowNs = System.nanoTime();
                    if (nowNs - lastAimNs < AIM_INTERVAL_NS) {
                        Thread.yield();
                        continue;
                    }
                    lastAimNs = nowNs;

                    // 10. Screen offset -> mouse counts
                    float rawX = bestDX * mpp;
                    float rawY = bestDY * mpp;

                    // 11. Smooth — far targets approach fast, close targets fine-tune
                    float sf = smooth.getValue();
                    if (sf > 1.0f) {
                        float dr = bestDist / fovPx; // 0 = centre, 1 = FOV edge
                        float spf = 1.0f + dr; // 1 = slow, 2 = fast
                        rawX /= (sf * spf);
                        rawY /= (sf * spf);
                    }

                    // 12. Humanise
                    if (humanize.getValue()) {
                        float[] h = humanise(rawX, rawY);
                        rawX = h[0];
                        rawY = h[1];
                    }

                    // 13. Sub-pixel accumulation (carry fractional remainder)
                    accumX += rawX;
                    accumY += rawY;
                    int mx = (int) accumX;
                    int my = (int) accumY;
                    accumX -= mx;
                    accumY -= my;

                    // 14. Inject relative mouse movement (raw-input compatible)
                    if (mx != 0 || my != 0)
                        Win32Mouse.INSTANCE.mouse_event(MOUSEEVENTF_MOVE, mx, my, 0, 0);

                    Thread.yield();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[Aimbot] Error: " + e.getMessage());
                }
            }

            aimThreadRunning = false;
            System.out.println("[Aimbot] Thread stopped.");
        }, "Athenis-Aimbot");

        aimThread.setDaemon(true);
        aimThread.setPriority(Thread.MAX_PRIORITY);
        aimThread.start();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int resolveBone(int boneMode, PlayerSnapshot p) {
        if (boneMode != 4)
            return MODE_BONE[boneMode];
        float cx = PlayerCache.screenWidth * 0.5f;
        float cy = PlayerCache.screenHeight * 0.5f;
        int bestBone = BONE_HEAD;
        float bestD = Float.MAX_VALUE;
        for (int b : new int[] { BONE_HEAD, BONE_NECK, BONE_CHEST, BONE_STOMACH }) {
            if (b >= p.boneX.length || !p.boneVisible[b])
                continue;
            float d = (p.boneX[b] - cx) * (p.boneX[b] - cx)
                    + (p.boneY[b] - cy) * (p.boneY[b] - cy);
            if (d < bestD) {
                bestD = d;
                bestBone = b;
            }
        }
        return bestBone;
    }

    private boolean isBoneVisible(PlayerSnapshot p, int bone, Vector3 cam, long pawn, boolean useSpotted) {
        VisCheck vc = VisCheckAdapter.getVisCheck();
        if (vc != null && cam != null && bone >= 0 && bone < p.boneWorldX.length) {
            float bx = p.boneWorldX[bone];
            float by = p.boneWorldY[bone];
            float bz = p.boneWorldZ[bone];
            if (bx != 0.0f || by != 0.0f || bz != 0.0f) {
                return vc.isPointVisible(cam, new Vector3(bx, by, bz));
            }
        }
        if (vc != null && cam != null)
            return vc.isPointVisible(cam, new Vector3(p.worldX, p.worldY, p.worldZ + 72.0f));
        if (useSpotted && p.pawnAddress != 0) {
            byte s = CS2Memory.readByte(
                    p.pawnAddress + CS2Offsets.m_entitySpottedState + CS2Offsets.m_bSpotted);
            return s != 0;
        }
        return true;
    }

    private float[] humanise(float rawX, float rawY) {
        float str = humanizeStrength.getValue() / 100.0f;
        if (str <= 0f) {
            prevDeltaX = rawX;
            prevDeltaY = rawY;
            return new float[] { rawX, rawY };
        }
        float md = (float) Math.sqrt(rawX * rawX + rawY * rawY);
        float ms = Math.min(md * 0.12f, 3.0f) * str;
        float mx = (float) rng.nextGaussian() * ms;
        float my = (float) rng.nextGaussian() * ms;
        float ps = 0.10f * str;
        float px = -rawY * ps * (float) rng.nextGaussian();
        float py = rawX * ps * (float) rng.nextGaussian();
        float bl = 0.75f + rng.nextFloat() * 0.20f;
        float sx = rawX * bl + prevDeltaX * (1.0f - bl);
        float sy = rawY * bl + prevDeltaY * (1.0f - bl);
        prevDeltaX = rawX;
        prevDeltaY = rawY;
        return new float[] { sx + mx + px, sy + my + py };
    }

    private boolean isAimKeyHeld() {
        try {
            short r = com.sun.jna.platform.win32.User32.INSTANCE
                    .GetAsyncKeyState(AIM_KEY_VK[aimKey.getValue()]);
            return (r & 0x8000) != 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isLeftMouseHeld() {
        try {
            // VK_LBUTTON = 0x01
            short r = com.sun.jna.platform.win32.User32.INSTANCE.GetAsyncKeyState(0x01);
            return (r & 0x8000) != 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}