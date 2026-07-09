package me.venixpll.cheat.module.impl;

import com.sun.jna.Library;
import com.sun.jna.Native;
import imgui.ImColor;
import imgui.ImDrawList;
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
 * <p><b>Classic mode</b>: proportional smooth approach with optional humanise jitter.
 * Mouse deltas: screen_offset_px * hFOV / (screenWidth * 0.022 * sensitivity)
 *
 * <p><b>PID Spring mode</b>: models the mouse as a mass on a spring.
 * The spring pulls the cursor toward the target bone; critically-damped or
 * under-damped tuning produces natural overshoot-then-settle behaviour.
 * State: velocity accumulates across ticks so momentum is preserved.
 * Equation per axis:
 * <pre>
 *   force  = kP * (target - position) - kD * velocity
 *   velocity += force / mass * dt
 *   position += velocity * dt
 * </pre>
 * where position is the accumulated sub-pixel error in mouse-counts.
 *
 * <p>Rate-limit : 500 Hz nanosecond gate (same principle as DragonBurn AimDelay).
 * Sub-pixel  : fractional-px accumulator prevents stalling at zero.
 */
public class AimbotModule extends CheatModule {

    // ── Win32 relative mouse injection ────────────────────────────────────────
    private interface Win32Mouse extends Library {
        Win32Mouse INSTANCE = Native.load("user32", Win32Mouse.class);

        void mouse_event(int dwFlags, int dx, int dy, int dwData, int dwExtraInfo);
    }

    private static final int MOUSEEVENTF_MOVE = 0x0001;

    // ── Bone indices ──────────────────────────────────────────────────────────
    private static final int BONE_HEAD    = 7;
    private static final int BONE_NECK    = 6;
    private static final int BONE_CHEST   = 4;
    private static final int BONE_STOMACH = 2;
    /** Index 4 = "Closest" — resolved per-player at runtime. */
    private static final int[] MODE_BONE = { 7, 6, 4, 2, -1 };

    /** Windows VK codes for each aim-key option (matches aimKey setting order). */
    private static final int[] AIM_KEY_VK = { 0x02, 0x04, 0xA4, 0xA0, 0x58, 0x5A, 0x11 };

    // ── Mode selector ─────────────────────────────────────────────────────────
    /** 0 = Classic, 1 = PID Spring */
    public final ModeSetting aimMode = new ModeSetting(
            "Aim Mode##aimbot", 0, "Classic", "PID Spring");

    // ── Shared settings (always visible) ─────────────────────────────────────
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
    public final BooleanSetting enemyOnly = new BooleanSetting(
            "Enemy Only##aimbot", true);
    public final BooleanSetting useVisCheck = new BooleanSetting(
            "VisCheck##aimbot", true);
    public final BooleanSetting spottedFallback = new BooleanSetting(
            "Spotted Fallback##aimbot", true);
    public final BooleanSetting cancelOnShoot = new BooleanSetting(
            "Cancel on Shoot##aimbot", true);
    /** When true, draws the FOV circle on the overlay. */
    public final BooleanSetting showFov = new BooleanSetting(
            "Show FOV Circle##aimbot", true);

    // ── Classic-mode settings ─────────────────────────────────────────────────
    public final FloatSetting smooth = new FloatSetting(
            "Smooth##aimbot", 6.5f, 1.0f, 30.0f);
    public final FloatSetting sensitivity = new FloatSetting(
            "Sensitivity##aimbot", 1.5f, 0.1f, 10.0f);
    public final BooleanSetting humanize = new BooleanSetting(
            "Humanize##aimbot", true);
    public final FloatSetting humanizeStrength = new FloatSetting(
            "Humanize Strength##aimbot", 3.6f, 0.0f, 20.0f);

    // ── PID Spring settings ───────────────────────────────────────────────────
    /**
     * Spring stiffness (kP) — how hard the spring pulls toward target.
     * Higher = stiffer spring, faster approach and more overshoot.
     */
    public final FloatSetting pidStiffness = new FloatSetting(
            "Stiffness (kP)##aimbot", 75.0f, 1.0f, 149.0f);
    /**
     * Spring damping (kD) — how quickly oscillation decays.
     * Set to 2*sqrt(kP*mass) for critically-damped (no overshoot).
     * Lower values allow natural overshoot-and-settle.
     */
    public final FloatSetting pidDamping = new FloatSetting(
            "Damping (kD)##aimbot", 35.0f, 0.5f, 70.0f);
    /**
     * Virtual mass of the cursor. Higher mass = more sluggish, more overshoot.
     */
    public final FloatSetting pidMass = new FloatSetting(
            "Mass##aimbot", 0.2f, 0.01f, 0.4f);
    /**
     * Maximum force cap (mouse-counts per tick²) — prevents insane initial
     * kick on very large target deltas.
     */
    public final FloatSetting pidMaxForce = new FloatSetting(
            "Max Force##aimbot", 1800.0f, 1.0f, 3600.0f);
    /**
     * Sensitivity used in PID mode to convert screen-px offsets to mouse-counts.
     */
    public final FloatSetting pidSensitivity = new FloatSetting(
            "Sensitivity##aimbot_pid", 0.1f, 0.01f, 0.2f);

    // ── Internal state ────────────────────────────────────────────────────────
    private volatile boolean aimThreadRunning = false;
    private Thread aimThread;
    private final Random rng = new Random();

    // Classic sub-pixel accumulators
    private volatile float prevDeltaX = 0f;
    private volatile float prevDeltaY = 0f;
    private float accumX = 0f;
    private float accumY = 0f;

    // PID Spring state — velocity (mouse-counts / s) and position error (mouse-counts)
    private volatile float springVelX = 0f;
    private volatile float springVelY = 0f;
    // PID sub-pixel accumulator
    private float pidAccumX = 0f;
    private float pidAccumY = 0f;

    private long lastAimNs = 0L;
    private static final long AIM_INTERVAL_NS = 2_000_000L; // 2 ms = 500 Hz cap

    // ── Constructor ───────────────────────────────────────────────────────────
    public AimbotModule() {
        super("Aimbot", ModuleCategory.EXTERNAL, false);

        // Mode selector (always visible)
        addSetting(aimMode);

        // Shared settings (always visible)
        addSetting(targetBone);
        addSetting(activationMode);
        addSetting(aimKey);
        addSetting(fov);
        addSetting(fovMin);
        addSetting(enemyOnly);
        addSetting(useVisCheck);
        addSetting(spottedFallback);
        addSetting(cancelOnShoot);
        addSetting(showFov);

        // Classic mode settings
        addSetting(smooth);
        addSetting(sensitivity);
        addSetting(humanize);
        addSetting(humanizeStrength);

        // PID Spring mode settings (hidden initially — Classic is default)
        addSetting(pidStiffness);
        addSetting(pidDamping);
        addSetting(pidMass);
        addSetting(pidMaxForce);
        addSetting(pidSensitivity);

        // Apply initial visibility
        applyModeVisibility();
    }

    // ── Mode visibility management ────────────────────────────────────────────

    /**
     * Shows Classic settings and hides PID settings, or vice-versa, depending
     * on the current aimMode selection. Called whenever aimMode changes.
     */
    private void applyModeVisibility() {
        boolean pid = aimMode.getValue() == 1;

        // Classic settings: visible when NOT pid
        smooth.setHidden(pid);
        sensitivity.setHidden(pid);
        humanize.setHidden(pid);
        humanizeStrength.setHidden(pid);

        // PID settings: visible only when pid
        pidStiffness.setHidden(!pid);
        pidDamping.setHidden(!pid);
        pidMass.setHidden(!pid);
        pidMaxForce.setHidden(!pid);
        pidSensitivity.setHidden(!pid);
    }

    // ── Module lifecycle ──────────────────────────────────────────────────────
    @Override
    public void onTick() {
        // Keep visibility in sync with current mode selection
        applyModeVisibility();

        if (isEnabled() && !aimThreadRunning)
            startAimThread();
        else if (!isEnabled() && aimThreadRunning) {
            aimThreadRunning = false;
            resetState();
        }
    }

    // ── FOV circle overlay ────────────────────────────────────────────────────

    /**
     * Draws one or two concentric circles at the screen centre to visualise
     * the aimbot's outer FOV limit and the inner dead-zone (FOV Min).
     *
     * <p>The radius in pixels is computed with the same hFOV formula used by
     * the aim thread, so the circle always matches exactly what the bot targets.
     *
     * @param drawList ImGui foreground draw list.
     */
    @Override
    public void onRender(ImDrawList drawList) {
        if (!isEnabled() || !showFov.getValue())
            return;
        if (!PlayerCache.tracking)
            return;

        int sw = Math.max(PlayerCache.screenWidth,  1280);
        int sh = Math.max(PlayerCache.screenHeight, 720);

        // Derive horizontal FOV (same formula as aim thread)
        float ar = (float) sw / (float) sh;
        float vh = (float) Math.atan(Math.tan(Math.toRadians(45.0)) * 0.75);
        float hf = 2.0f * (float) Math.toDegrees(Math.atan(Math.tan(vh) * ar));

        float ppd    = sw / hf;              // pixels per degree
        float fovPx  = fov.getValue()    * ppd;
        float minPx  = fovMin.getValue() * ppd;

        // Screen centre (account for ESP overlay offset if present)
        float cx = sw * 0.5f + ESPModule.espOffsetX;
        float cy = sh * 0.5f + ESPModule.espOffsetY;

        // ── Outer FOV circle ─────────────────────────────────────────────────
        // Shadow for readability on any background
        drawList.addCircle(cx, cy, fovPx + 1f,
                ImColor.rgba(0f, 0f, 0f, 0.55f), 64, 2.0f);
        // Main circle — white with slight cyan tint
        drawList.addCircle(cx, cy, fovPx,
                ImColor.rgba(0.55f, 0.95f, 1.0f, 0.75f), 64, 1.0f);

        // ── Inner FOV-min circle (dead-zone) ──────────────────────────────────
        if (minPx > 1.5f) {
            drawList.addCircle(cx, cy, minPx + 1f,
                    ImColor.rgba(0f, 0f, 0f, 0.40f), 32, 1.5f);
            drawList.addCircle(cx, cy, minPx,
                    ImColor.rgba(1.0f, 0.65f, 0.15f, 0.65f), 32, 0.8f);
        }
    }

    private void resetState() {
        prevDeltaX = 0f;
        prevDeltaY = 0f;
        accumX = 0f;
        accumY = 0f;
        springVelX = 0f;
        springVelY = 0f;
        pidAccumX = 0f;
        pidAccumY = 0f;
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

                    // 2b. Cancel on Shoot check
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

                    // 5. Player list snapshot
                    List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                    if (players.isEmpty()) {
                        Thread.yield();
                        continue;
                    }

                    int boneMode    = targetBone.getValue();
                    float fovDeg    = fov.getValue();
                    float fovMinDeg = fovMin.getValue();
                    boolean chkEnemy = enemyOnly.getValue();
                    boolean chkVis   = useVisCheck.getValue();
                    boolean chkSpot  = spottedFallback.getValue();

                    // 6. Derive horizontal FOV from screen aspect ratio.
                    // Assumes CS2 default cl_fov = 90 at 4:3 (CS:GO/CS2 standard).
                    // vFOV_half(4:3) = atan(tan(45°) * 3/4) = 36.87°
                    // hFOV(AR) = 2 * atan(tan(vFOV_half) * AR)
                    int sw = Math.max(PlayerCache.screenWidth, 1280);
                    int sh = Math.max(PlayerCache.screenHeight, 720);
                    float ar = (float) sw / (float) sh;
                    float vh = (float) Math.atan(Math.tan(Math.toRadians(45.0)) * 0.75);
                    float hf = 2.0f * (float) Math.toDegrees(Math.atan(Math.tan(vh) * ar));

                    float cx = sw * 0.5f;
                    float cy = sh * 0.5f;
                    float ppd = sw / hf;      // screen pixels per degree
                    float fovPx    = fovDeg    * ppd;
                    float fovMinPx = fovMinDeg * ppd;

                    // 7. Screen-space target selection
                    float bestDist = Float.MAX_VALUE;
                    float bestDX   = 0f;
                    float bestDY   = 0f;

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
                        }
                    }

                    // 8. FOV gate
                    if (bestDist == Float.MAX_VALUE || bestDist <= fovMinPx) {
                        resetState();
                        Thread.yield();
                        continue;
                    }

                    // 9. Rate limit — 500 Hz cap
                    long nowNs = System.nanoTime();
                    long dtNs  = nowNs - lastAimNs;
                    if (dtNs < AIM_INTERVAL_NS) {
                        Thread.yield();
                        continue;
                    }
                    lastAimNs = nowNs;
                    float dt = dtNs * 1e-9f; // seconds

                    // 10. Dispatch to Classic or PID spring logic
                    int mx, my;
                    if (aimMode.getValue() == 1) {
                        int[] pidMove = tickPidSpring(bestDX, bestDY, hf, sw, dt);
                        mx = pidMove[0];
                        my = pidMove[1];
                    } else {
                        int[] classicMove = tickClassic(bestDX, bestDY, bestDist, fovPx, hf, sw);
                        mx = classicMove[0];
                        my = classicMove[1];
                    }

                    // 11. Inject relative mouse movement (raw-input compatible)
                    if (mx != 0 || my != 0)
                        Win32Mouse.INSTANCE.mouse_event(MOUSEEVENTF_MOVE, mx, my, 0, 0);

                    Thread.yield();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[Aimbot] Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            aimThreadRunning = false;
            System.out.println("[Aimbot] Thread stopped.");
        }, "Athenis-Aimbot");

        aimThread.setDaemon(true);
        aimThread.setPriority(Thread.MAX_PRIORITY);
        aimThread.start();
    }

    // ── Classic aim tick ──────────────────────────────────────────────────────

    /**
     * One tick of the Classic proportional-smooth algorithm.
     *
     * @param bestDX  screen-pixel offset X toward target
     * @param bestDY  screen-pixel offset Y toward target
     * @param bestDist Euclidean distance to target in screen pixels
     * @param fovPx   FOV radius in screen pixels
     * @param hf      horizontal FOV in degrees
     * @param sw      screen width in pixels
     * @return {intMouseX, intMouseY} ready for mouse_event
     */
    private int[] tickClassic(float bestDX, float bestDY, float bestDist, float fovPx,
                               float hf, int sw) {
        float sens = sensitivity.getValue();
        float mpp  = hf / (sw * 0.022f * sens);

        float rawX = bestDX * mpp;
        float rawY = bestDY * mpp;

        // Smooth — far targets approach fast, close targets fine-tune
        float sf = smooth.getValue();
        if (sf > 1.0f) {
            float dr  = bestDist / fovPx; // 0 = centre, 1 = FOV edge
            float spf = 1.0f + dr;        // 1 = slow, 2 = fast
            rawX /= (sf * spf);
            rawY /= (sf * spf);
        }

        // Humanise
        if (humanize.getValue()) {
            float[] h = humanise(rawX, rawY);
            rawX = h[0];
            rawY = h[1];
        }

        // Sub-pixel accumulation
        accumX += rawX;
        accumY += rawY;
        int mx = (int) accumX;
        int my = (int) accumY;
        accumX -= mx;
        accumY -= my;

        return new int[]{ mx, my };
    }

    // ── PID Spring aim tick ───────────────────────────────────────────────────

    /**
     * One tick of the PID spring integrator.
     *
     * <p>Models the mouse as a mass attached to a spring anchored at the
     * target bone (in mouse-count space).  On each tick we compute the
     * spring force, integrate velocity, integrate position, and emit the
     * integer part of the accumulated position as the actual mouse delta.
     *
     * <p>Under-damped tuning (damping &lt; 2*sqrt(kP*mass)) produces the
     * characteristic overshoot-and-settle behavior requested.
     *
     * @param bestDX  screen-pixel offset X toward target
     * @param bestDY  screen-pixel offset Y toward target
     * @param hf      horizontal FOV in degrees
     * @param sw      screen width in pixels
     * @param dt      time elapsed since last tick in seconds
     * @return {intMouseX, intMouseY} ready for mouse_event
     */
    private int[] tickPidSpring(float bestDX, float bestDY, float hf, int sw, float dt) {
        float sens     = pidSensitivity.getValue();
        float mpp      = hf / (sw * 0.022f * sens);

        // Target in mouse-count space (how far we need to move)
        float targetX  = bestDX * mpp;
        float targetY  = bestDY * mpp;

        float kP       = pidStiffness.getValue();
        float kD       = pidDamping.getValue();
        float mass     = pidMass.getValue();
        float maxForce = pidMaxForce.getValue();

        // Clamp dt to a safe range to avoid instability on lag spikes
        float dtClamped = Math.min(dt, 0.020f); // max 20 ms step

        // Spring force = kP * error - kD * velocity
        // "error" here is the remaining distance to target in mouse-counts
        float forceX = kP * targetX - kD * springVelX;
        float forceY = kP * targetY - kD * springVelY;

        // Cap force magnitude
        float forceMag = (float) Math.sqrt(forceX * forceX + forceY * forceY);
        if (forceMag > maxForce) {
            float scale = maxForce / forceMag;
            forceX *= scale;
            forceY *= scale;
        }

        // Euler integration: v += (F/m) * dt
        springVelX += (forceX / mass) * dtClamped;
        springVelY += (forceY / mass) * dtClamped;

        // Integrate position delta for this tick: delta = v * dt
        float deltaX = springVelX * dtClamped;
        float deltaY = springVelY * dtClamped;

        // Overshoot guard: if delta would overshoot in the opposite direction
        // after the target is very close, let velocity decay naturally — do NOT
        // clamp here; that is what produces the satisfying rebound.

        // Sub-pixel accumulator
        pidAccumX += deltaX;
        pidAccumY += deltaY;
        int mx = (int) pidAccumX;
        int my = (int) pidAccumY;
        pidAccumX -= mx;
        pidAccumY -= my;

        return new int[]{ mx, my };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int resolveBone(int boneMode, PlayerSnapshot p) {
        if (boneMode != 4)
            return MODE_BONE[boneMode];
        float cx = PlayerCache.screenWidth * 0.5f;
        float cy = PlayerCache.screenHeight * 0.5f;
        int bestBone = BONE_HEAD;
        float bestD  = Float.MAX_VALUE;
        for (int b : new int[]{ BONE_HEAD, BONE_NECK, BONE_CHEST, BONE_STOMACH }) {
            if (b >= p.boneX.length || !p.boneVisible[b])
                continue;
            float d = (p.boneX[b] - cx) * (p.boneX[b] - cx)
                    + (p.boneY[b] - cy) * (p.boneY[b] - cy);
            if (d < bestD) {
                bestD    = d;
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
            return new float[]{ rawX, rawY };
        }
        float md = (float) Math.sqrt(rawX * rawX + rawY * rawY);
        float ms = Math.min(md * 0.12f, 3.0f) * str;
        float mx = (float) rng.nextGaussian() * ms;
        float my = (float) rng.nextGaussian() * ms;
        float ps = 0.10f * str;
        float px = -rawY * ps * (float) rng.nextGaussian();
        float py =  rawX * ps * (float) rng.nextGaussian();
        float bl = 0.75f + rng.nextFloat() * 0.20f;
        float sx = rawX * bl + prevDeltaX * (1.0f - bl);
        float sy = rawY * bl + prevDeltaY * (1.0f - bl);
        prevDeltaX = rawX;
        prevDeltaY = rawY;
        return new float[]{ sx + mx + px, sy + my + py };
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