package me.venixpll.cheat.module.impl;

import com.sun.jna.Library;
import com.sun.jna.Native;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.module.ManagedThreadModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.overlay.OverlayWindow;

/**
 * Standalone Recoil Control System (RCS) & NoSpread Module for CS2.
 * Realtime hardware mouse compensation countering CS2 weapon recoil punch.
 */
public class NoSpreadModule extends ManagedThreadModule {

    // ── Win32 relative mouse injection ────────────────────────────────────────
    private interface Win32Mouse extends Library {
        Win32Mouse INSTANCE = Native.load("user32", Win32Mouse.class);

        void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);
    }

    private static final int MOUSEEVENTF_MOVE = 0x0001;

    // ── Settings ─────────────────────────────────────────────────────────────
    public final BooleanSetting recoilCompensate = new BooleanSetting(
            "Recoil Compensation##nospread", true);

    public final FloatSetting pitchScale = new FloatSetting(
            "Pitch Scale##nospread", 1.0f, 0.1f, 2.0f);

    public final FloatSetting yawScale = new FloatSetting(
            "Yaw Scale##nospread", 1.0f, 0.1f, 2.0f);

    public final FloatSetting sensitivity = new FloatSetting(
            "In-Game Sensitivity##nospread", 2.0f, 0.1f, 10.0f);

    public final FloatSetting rcsStrength = new FloatSetting(
            "RCS Strength##nospread", 50.0f, 10.0f, 100.0f);

    public final FloatSetting startBullet = new FloatSetting(
            "Start Bullet##nospread", 0.0f, 0.0f, 5.0f);

    public final BooleanSetting invertPitch = new BooleanSetting(
            "Invert Pitch Direction##nospread", false);

    public final BooleanSetting invertYaw = new BooleanSetting(
            "Invert Yaw Direction##nospread", false);

    public final BooleanSetting debugLog = new BooleanSetting(
            "Debug Log to Console##nospread", true);

    // ── Worker state (lives across loop iterations) ──────────────────────────
    private float lastPunchPitch = 0f;
    private float lastPunchYaw   = 0f;
    private boolean wasFiring    = false;

    private float accumX = 0f;
    private float accumY = 0f;
    private long lastLogTime = 0;

    public NoSpreadModule() {
        super("No Spread", ModuleCategory.EXTERNAL, MenuGroup.COMBAT, false, "Athenis-NoSpread");
        addSetting(recoilCompensate);
        addSetting(pitchScale);
        addSetting(yawScale);
        addSetting(sensitivity);
        addSetting(rcsStrength);
        addSetting(startBullet);
        addSetting(invertPitch);
        addSetting(invertYaw);
        addSetting(debugLog);
    }

    /**
     * Marks this module as VAC-detectable: it writes RCS compensation values
     * into the local player's view angles every shot. Returning true here is
     * also what makes the module a target of the panic key's
     * "dangerous modules" scope.
     */
    @Override
    public boolean isDangerous() {
        return true;
    }

    // ── Worker loop ───────────────────────────────────────────────────────────

    @Override
    protected void runLoop() throws Exception {
        Thread.sleep(4);

        if (!PlayerCache.tracking || OverlayWindow.isMenuOpen()) {
            wasFiring = false;
            lastPunchPitch = 0f;
            lastPunchYaw   = 0f;
            accumX = 0f;
            accumY = 0f;
            return;
        }

        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (localPawn == 0) return;

        boolean attackDown = isLmbDown();
        int shotsFired = CS2Memory.readInt(localPawn + CS2Offsets.m_iShotsFired);

        float[] punch = readPunchAngle(localPawn);
        float curPunchPitch = punch[0]; // x = pitch (negative when gun kicks up)
        float curPunchYaw   = punch[1]; // y = yaw (positive when gun sways right)

        // Strict active firing check: ONLY process RCS while LMB is held
        if (!attackDown || !recoilCompensate.getValue()) {
            if (wasFiring && debugLog.getValue()) {
                System.out.println("[NoSpread Debug] >>> SPRAY STOPPED <<<");
            }
            wasFiring = false;
            lastPunchPitch = 0f;
            lastPunchYaw   = 0f;
            accumX = 0f;
            accumY = 0f;
            return;
        }

        // Log periodic state during active spray
        long now = System.currentTimeMillis();
        if (debugLog.getValue() && (now - lastLogTime > 250)) {
            lastLogTime = now;
            System.out.println(String.format(
                    "[NoSpread Debug] LMB=%b | ShotsFired=%d | Punch=(%.3f, %.3f)",
                    attackDown, shotsFired, curPunchPitch, curPunchYaw
            ));
        }

        // When spray starts (or resumes after brief tap), initialize punch baseline
        // to current memory punch so leftover punch doesn't cause a massive jump.
        if (!wasFiring) {
            lastPunchPitch = curPunchPitch;
            lastPunchYaw   = curPunchYaw;
            accumX = 0f;
            accumY = 0f;
            wasFiring = true;
            if (debugLog.getValue()) {
                System.out.println("[NoSpread Debug] >>> SPRAY STARTED <<< Baseline Punch=(" + curPunchPitch + ", " + curPunchYaw + ")");
            }
            return;
        }

        int minBullet = (int) (float) startBullet.getValue();
        if (shotsFired >= minBullet) {
            float deltaPitch = curPunchPitch - lastPunchPitch;
            float deltaYaw   = curPunchYaw   - lastPunchYaw;

            // Sanity check on delta: ignore massive jumps caused by weapon reset / spectator swap / stale punch
            if (Math.abs(deltaPitch) < 3.0f && Math.abs(deltaYaw) < 3.0f) {
                float pScale = Math.max(0.01f, pitchScale.getValue());
                float yScale = Math.max(0.01f, yawScale.getValue());
                float sens   = Math.max(0.1f, sensitivity.getValue());
                float mult   = rcsStrength.getValue();

                // Correct Win32 mouse movement direction:
                // CS2 pitch punch is negative when weapon kicks UP -> moveY must be POSITIVE to pull mouse DOWN
                // CS2 yaw punch is positive when weapon sways RIGHT -> moveX must be NEGATIVE to pull mouse LEFT
                float moveY = (-deltaPitch * 2.0f / (pScale * sens)) * mult;
                float moveX = (-deltaYaw   * 2.0f / (yScale * sens)) * mult;

                if (invertPitch.getValue()) moveY = -moveY;
                if (invertYaw.getValue())   moveX = -moveX;

                accumX += moveX;
                accumY += moveY;

                int mx = Math.round(accumX);
                int my = Math.round(accumY);

                if (mx != 0 || my != 0) {
                    accumX -= mx;
                    accumY -= my;
                    Win32Mouse.INSTANCE.mouse_event(MOUSEEVENTF_MOVE, mx, my, 0, 0);
                    if (debugLog.getValue()) {
                        System.out.println(String.format(
                                "[NoSpread Debug] mouse_event(%d, %d) | deltaPunch=(%.4f, %.4f)",
                                mx, my, deltaPitch, deltaYaw
                        ));
                    }
                }
            } else if (debugLog.getValue()) {
                System.out.println(String.format(
                        "[NoSpread Debug] Ignored massive deltaPunch=(%.4f, %.4f)",
                        deltaPitch, deltaYaw
                ));
            }
        }

        lastPunchPitch = curPunchPitch;
        lastPunchYaw   = curPunchYaw;
    }

    private float[] readPunchAngle(long localPawn) {
        if (localPawn == 0) return new float[]{0f, 0f};

        // 1. Try via m_pAimPunchServices component pointer
        if (CS2Offsets.m_pAimPunchServices != 0) {
            long punchServices = CS2Memory.readLong(localPawn + CS2Offsets.m_pAimPunchServices);
            if (punchServices > 0x10000L && punchServices < 0x7FFFFFFFFFFF_FFFFL) {
                float px = CS2Memory.readFloat(punchServices + 80); // m_predictableBaseAngle.x (pitch)
                float py = CS2Memory.readFloat(punchServices + 84); // m_predictableBaseAngle.y (yaw)
                if (Math.abs(px) < 89.0f && Math.abs(py) < 180.0f && (Math.abs(px) > 1e-6f || Math.abs(py) > 1e-6f)) {
                    return new float[]{ px, py };
                }
                float upx = CS2Memory.readFloat(punchServices + 164); // m_unpredictableBaseAngle.x
                float upy = CS2Memory.readFloat(punchServices + 168);
                if (Math.abs(upx) < 89.0f && Math.abs(upy) < 180.0f && (Math.abs(upx) > 1e-6f || Math.abs(upx) > 1e-6f)) {
                    return new float[]{ upx, upy };
                }
            }
        }

        // 2. Try direct offset m_aimPunchAngle
        if (CS2Offsets.m_aimPunchAngle != 0) {
            float px = CS2Memory.readFloat(localPawn + CS2Offsets.m_aimPunchAngle);
            float py = CS2Memory.readFloat(localPawn + CS2Offsets.m_aimPunchAngle + 4);
            if (Math.abs(px) < 89.0f && Math.abs(py) < 180.0f && (Math.abs(px) > 1e-6f || Math.abs(py) > 1e-6f)) {
                return new float[]{ px, py };
            }
        }

        // 3. Fallback candidates on localPawn
        int[] candidates = { 0x1574, 0x14D0, 0x1740, 0x14E0 };
        for (int offset : candidates) {
            float px = CS2Memory.readFloat(localPawn + offset);
            float py = CS2Memory.readFloat(localPawn + offset + 4);
            if (Math.abs(px) < 89.0f && Math.abs(py) < 180.0f && (Math.abs(px) > 1e-6f || Math.abs(py) > 1e-6f)) {
                return new float[]{ px, py };
            }
        }

        return new float[]{ 0f, 0f };
    }

    private boolean isLmbDown() {
        try {
            return (com.sun.jna.platform.win32.User32.INSTANCE.GetAsyncKeyState(0x01) & 0x8000) != 0;
        } catch (Exception ignored) { return false; }
    }
}
