package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
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

import java.util.List;

/**
 * Silent Aim Module -- external view-angle patch for CS2.
 *
 * <h3>Timing design</h3>
 * <p>Target angles are pre-computed every frame independently of LMB state.
 * On the LMB rising edge a single WriteProcessMemory call writes the
 * pre-cached angles -- no search or math in the critical path.
 * Only the FIRST shot per click is redirected; holding LMB does NOT
 * lock the aim (avoids the aimlock look on auto-fire).
 * Angles are restored when LMB is released.
 *
 * <h3>Detection risk</h3>
 * <p>This module writes directly to dwViewAngles in CS2 memory.
 * It is VAC-detected and should only be used on non-VAC servers.
 */
public class SilentAimModule extends CheatModule {

    // ── Bone indices ─────────────────────────────────────────────────────────
    private static final int BONE_HEAD    = 7;
    private static final int BONE_NECK    = 6;
    private static final int BONE_CHEST   = 4;
    private static final int BONE_STOMACH = 2;
    private static final int[] MODE_BONE = { 7, 6, 4, 2, -1 };

    // ── Hold-key VK table ────────────────────────────────────────────────────
    private static final int[] HOLD_KEY_VK = { 0x02, 0xA4, 0xA0, 0x58, 0x5A, 0x11 };

    // ── Settings ─────────────────────────────────────────────────────────────

    public final ModeSetting targetBone = new ModeSetting(
            "Target Bone##silentaim", 0, "Head", "Neck", "Chest", "Stomach", "Closest");

    public final ModeSetting holdKey = new ModeSetting(
            "Hold Key##silentaim", 0,
            "None (LMB only)", "Right Mouse", "Left Alt", "Left Shift", "X Key", "Z Key", "Ctrl");

    public final FloatSetting fov = new FloatSetting(
            "FOV (degrees)##silentaim", 5.0f, 0.5f, 45.0f);

    public final BooleanSetting enemyOnly = new BooleanSetting(
            "Enemy Only##silentaim", true);

    public final BooleanSetting useVisCheck = new BooleanSetting(
            "VisCheck##silentaim", true);

    public final BooleanSetting spottedFallback = new BooleanSetting(
            "Spotted Fallback##silentaim", true);

    public final BooleanSetting flashCheck = new BooleanSetting(
            "Flashbang Check##silentaim", true);

    public final BooleanSetting showFov = new BooleanSetting(
            "Show FOV Circle##silentaim", true);

    // ── Thread state ─────────────────────────────────────────────────────────
    private volatile boolean threadRunning = false;
    private Thread workerThread;

    // ── Constructor ──────────────────────────────────────────────────────────
    public SilentAimModule() {
        super("Silent Aim", ModuleCategory.EXTERNAL, MenuGroup.COMBAT, false);
        addSetting(targetBone);
        addSetting(holdKey);
        addSetting(fov);
        addSetting(enemyOnly);
        addSetting(useVisCheck);
        addSetting(spottedFallback);
        addSetting(flashCheck);
        addSetting(showFov);
    }

    /** Marks this module as VAC-detected so the overlay shows a red warning. */
    @Override
    public boolean isDangerous() {
        return true;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        if (isEnabled() && !threadRunning) {
            startWorkerThread();
        } else if (!isEnabled() && threadRunning) {
            threadRunning = false;
        }
    }

    // ── FOV circle ───────────────────────────────────────────────────────────

    @Override
    public void onRender(ImDrawList drawList) {
        if (!isEnabled() || !showFov.getValue()) return;
        if (!PlayerCache.tracking) return;

        int sw = Math.max(PlayerCache.screenWidth,  1280);
        int sh = Math.max(PlayerCache.screenHeight, 720);
        float ar   = (float) sw / (float) sh;
        float vh   = (float) Math.atan(Math.tan(Math.toRadians(45.0)) * 0.75);
        float hf   = 2.0f * (float) Math.toDegrees(Math.atan(Math.tan(vh) * ar));
        float fovPx = fov.getValue() * (sw / hf);
        float cx = sw * 0.5f + ESPModule.espOffsetX;
        float cy = sh * 0.5f + ESPModule.espOffsetY;

        drawList.addCircle(cx, cy, fovPx + 1f, ImColor.rgba(0f, 0f, 0f, 0.55f), 64, 2.0f);
        drawList.addCircle(cx, cy, fovPx, ImColor.rgba(0.95f, 0.20f, 0.20f, 0.75f), 64, 1.0f);
    }

    // ── Worker thread ────────────────────────────────────────────────────────

    private void startWorkerThread() {
        if (workerThread != null && workerThread.isAlive()) return;
        threadRunning = true;

        workerThread = new Thread(() -> {
            System.out.println("[SilentAim] Thread started.");

            // ── Pre-computed target cache (updated every frame) ───────────
            float cachedPitch = 0f;
            float cachedYaw   = 0f;
            boolean hasTarget = false;

            // ── Shot state ────────────────────────────────────────────────
            boolean lastLmbDown   = false;
            boolean anglesPatched = false;
            float   savedPitch    = 0f;
            float   savedYaw      = 0f;

            while (threadRunning && isEnabled()) {
                try {
                    // ── 1. Guards ─────────────────────────────────────────
                    if (!PlayerCache.tracking) {
                        hasTarget = false;
                        lastLmbDown = false;
                        if (anglesPatched) {
                            long cb = CS2Memory.getClientBase();
                            if (cb != 0) CS2Memory.writeAngles(cb + CS2Offsets.dwViewAngles, savedPitch, savedYaw);
                            anglesPatched = false;
                        }
                        Thread.sleep(100);
                        continue;
                    }
                    if (OverlayWindow.isMenuOpen()) {
                        hasTarget = false;
                        lastLmbDown = false;
                        Thread.sleep(50);
                        continue;
                    }

                    long clientBase = CS2Memory.getClientBase();
                    if (clientBase == 0) { Thread.yield(); continue; }

                    long localPawn = PlayerCache.localPlayerPawnAddress;

                    // ── 2. Flashbang check ────────────────────────────────
                    boolean flashed = false;
                    if (flashCheck.getValue() && localPawn != 0) {
                        float fDur   = CS2Memory.readFloat(localPawn + CS2Offsets.m_flFlashDuration);
                        float fAlpha = CS2Memory.readFloat(localPawn + CS2Offsets.m_flFlashMaxAlpha);
                        flashed = fDur > 0.1f && fAlpha > 50.0f;
                    }

                    // ── 3. PRE-COMPUTE angles every frame ─────────────────
                    // All heavy work (target search, origin RPM, atan2) happens
                    // here, decoupled from the LMB press. When the click arrives,
                    // cachedPitch/cachedYaw are already ready for instant write.
                    hasTarget = false;
                    if (!flashed && localPawn != 0) {
                        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                        if (!players.isEmpty()) {
                            int sw = Math.max(PlayerCache.screenWidth, 1280);
                            int sh = Math.max(PlayerCache.screenHeight, 720);
                            float ar   = (float) sw / (float) sh;
                            float vh   = (float) Math.atan(Math.tan(Math.toRadians(45.0)) * 0.75);
                            float hf   = 2.0f * (float) Math.toDegrees(Math.atan(Math.tan(vh) * ar));
                            float fovPx = fov.getValue() * (sw / hf);
                            float cx    = sw * 0.5f;
                            float cy    = sh * 0.5f;
                            int boneMode     = targetBone.getValue();
                            boolean chkEnemy = enemyOnly.getValue();
                            boolean chkVis   = useVisCheck.getValue();
                            boolean chkSpot  = spottedFallback.getValue();

                            Vector3 foot = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                            Vector3 cam  = (foot != null)
                                    ? new Vector3(foot.x, foot.y, foot.z + 64.0f) : null;

                            PlayerSnapshot bestTarget = null;
                            float bestDist = Float.MAX_VALUE;

                            for (PlayerSnapshot p : players) {
                                if (p.isLocal || !p.onScreen) continue;
                                if (chkEnemy && p.team == ESPModule.localTeam) continue;
                                if (p.health <= 0) continue;
                                float[] bx = p.boneX; float[] by = p.boneY; boolean[] bv = p.boneVisible;
                                if (bx == null || by == null || bv == null) continue;
                                int bone = resolveBone(boneMode, p);
                                if (bone < 0 || bone >= bx.length || bone >= by.length
                                        || bone >= bv.length || !bv[bone]) continue;
                                if (chkVis && !isBoneVisible(p, bone, cam, chkSpot)) continue;
                                float sdx  = bx[bone] - cx;
                                float sdy  = by[bone] - cy;
                                float dist = (float) Math.sqrt(sdx * sdx + sdy * sdy);
                                if (dist >= fovPx) continue;
                                if (dist < bestDist) { bestDist = dist; bestTarget = p; }
                            }

                            if (bestTarget != null) {
                                int boneIdx = resolveBone(boneMode, bestTarget);
                                float[] py = computeAngles(localPawn, bestTarget, boneIdx);
                                if (py != null) {
                                    cachedPitch = py[0];
                                    cachedYaw   = py[1];
                                    hasTarget   = true;
                                }
                            }
                        }
                    }

                    // ── 4. Key state ──────────────────────────────────────
                    boolean holdOk = isHoldKeyHeld();
                    boolean lmbNow = holdOk && isLmbDown();
                    boolean risingEdge = lmbNow && !lastLmbDown;

                    // ── 5. FIRST SHOT ONLY: write on rising edge ──────────
                    // We do NOT keep writing while LMB is held. That would look
                    // like aimlock on auto-fire. Only the initial press redirects.
                    if (risingEdge && hasTarget && !anglesPatched) {
                        long viewAddr = clientBase + CS2Offsets.dwViewAngles;
                        savedPitch    = CS2Memory.readFloat(viewAddr);
                        savedYaw      = CS2Memory.readFloat(viewAddr + 4);
                        // Single WPM -- all computation was done above, this is instant
                        CS2Memory.writeAngles(viewAddr, cachedPitch, cachedYaw);
                        anglesPatched = true;
                    }

                    // ── 6. Restore when LMB is released ──────────────────
                    if (anglesPatched && !lmbNow) {
                        CS2Memory.writeAngles(clientBase + CS2Offsets.dwViewAngles,
                                savedPitch, savedYaw);
                        anglesPatched = false;
                    }

                    lastLmbDown = lmbNow;
                    Thread.yield();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[SilentAim] Error: " + e.getMessage());
                }
            }

            // Safety cleanup
            try {
                long cb = CS2Memory.getClientBase();
                if (anglesPatched && cb != 0)
                    CS2Memory.writeAngles(cb + CS2Offsets.dwViewAngles, savedPitch, savedYaw);
            } catch (Exception ignored) {}

            threadRunning = false;
            System.out.println("[SilentAim] Thread stopped.");
        }, "Athenis-SilentAim");

        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.MAX_PRIORITY);
        workerThread.start();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int resolveBone(int boneMode, PlayerSnapshot p) {
        if (boneMode != 4) return MODE_BONE[boneMode];
        float cx = PlayerCache.screenWidth  * 0.5f;
        float cy = PlayerCache.screenHeight * 0.5f;
        int bestBone = BONE_HEAD; float bestD = Float.MAX_VALUE;
        float[] bx = p.boneX; float[] by = p.boneY; boolean[] bv = p.boneVisible;
        if (bx == null || by == null || bv == null) return BONE_HEAD;
        for (int b : new int[]{ BONE_HEAD, BONE_NECK, BONE_CHEST, BONE_STOMACH }) {
            if (b < 0 || b >= bx.length || b >= by.length || b >= bv.length || !bv[b]) continue;
            float d = (bx[b] - cx) * (bx[b] - cx) + (by[b] - cy) * (by[b] - cy);
            if (d < bestD) { bestD = d; bestBone = b; }
        }
        return bestBone;
    }

    private float[] computeAngles(long localPawn, PlayerSnapshot target, int bone) {
        float bwx, bwy, bwz;
        if (target.boneWorldX != null && bone >= 0 && bone < target.boneWorldX.length
                && (target.boneWorldX[bone] != 0.0f || target.boneWorldY[bone] != 0.0f
                    || target.boneWorldZ[bone] != 0.0f)) {
            bwx = target.boneWorldX[bone];
            bwy = target.boneWorldY[bone];
            bwz = target.boneWorldZ[bone];
        } else {
            bwx = target.worldX; bwy = target.worldY; bwz = target.worldZ + 72.0f;
        }
        Vector3 origin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
        if (origin == null) return null;
        float dx = bwx - origin.x;
        float dy = bwy - origin.y;
        float dz = bwz - (origin.z + 64.0f);
        float dist2D = (float) Math.sqrt(dx * dx + dy * dy);
        if (dist2D < 0.001f) return null;
        float pitch = (float) -Math.toDegrees(Math.atan2(dz, dist2D));
        float yaw   = (float)  Math.toDegrees(Math.atan2(dy, dx));
        while (yaw >  180.0f) yaw -= 360.0f;
        while (yaw < -180.0f) yaw += 360.0f;
        if (pitch >  89.0f) pitch =  89.0f;
        if (pitch < -89.0f) pitch = -89.0f;
        return new float[]{ pitch, yaw };
    }

    private boolean isBoneVisible(PlayerSnapshot p, int bone, Vector3 cam, boolean useSpotted) {
        VisCheck vc = VisCheckAdapter.getVisCheck();
        if (vc != null && cam != null && bone >= 0
                && p.boneWorldX != null && bone < p.boneWorldX.length) {
            float bx = p.boneWorldX[bone]; float by = p.boneWorldY[bone]; float bz = p.boneWorldZ[bone];
            if (bx != 0.0f || by != 0.0f || bz != 0.0f)
                return vc.isPointVisible(cam, new Vector3(bx, by, bz));
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

    private boolean isLmbDown() {
        try {
            return (com.sun.jna.platform.win32.User32.INSTANCE.GetAsyncKeyState(0x01) & 0x8000) != 0;
        } catch (Exception ignored) { return false; }
    }

    private boolean isHoldKeyHeld() {
        int mode = holdKey.getValue();
        if (mode == 0) return true;
        try {
            return (com.sun.jna.platform.win32.User32.INSTANCE.GetAsyncKeyState(HOLD_KEY_VK[mode - 1]) & 0x8000) != 0;
        } catch (Exception ignored) { return false; }
    }
}