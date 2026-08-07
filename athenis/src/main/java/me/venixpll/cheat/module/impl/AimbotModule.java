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
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.module.impl.aimbot.AimMode;
import me.venixpll.cheat.module.impl.aimbot.AimType;
import me.venixpll.cheat.module.impl.aimbot.ClassicAimMode;
import me.venixpll.cheat.module.impl.aimbot.HumanAimMode;
import me.venixpll.cheat.module.impl.aimbot.PidSpringAimMode;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.ModeSetting;
import me.venixpll.cheat.setting.Setting;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheckAdapter;
import me.venixpll.overlay.OverlayWindow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Aimbot Module — screen-space aim assist for CS2.
 *
 * <p>Supports modular aimbot modes (e.g. Classic, PID Spring, Human) implemented via
 * individual {@link AimMode} strategy classes.
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

    // ── Aim Modes Registry ───────────────────────────────────────────────────
    private final List<AimMode> aimModes = new ArrayList<>();
    private final ClassicAimMode classicMode = new ClassicAimMode();
    private final PidSpringAimMode pidSpringMode = new PidSpringAimMode();
    private final HumanAimMode humanMode = new HumanAimMode();

    // ── Mode selector ─────────────────────────────────────────────────────────
    public final ModeSetting aimMode;

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

    // ── Mode settings aliases (for direct access & backward compatibility) ─────
    public final FloatSetting smooth = classicMode.smooth;
    public final FloatSetting sensitivity = classicMode.sensitivity;
    public final BooleanSetting humanize = classicMode.humanize;
    public final FloatSetting humanizeStrength = classicMode.humanizeStrength;

    public final FloatSetting pidStiffness = pidSpringMode.pidStiffness;
    public final FloatSetting pidDamping = pidSpringMode.pidDamping;
    public final FloatSetting pidMass = pidSpringMode.pidMass;
    public final FloatSetting pidMaxForce = pidSpringMode.pidMaxForce;
    public final FloatSetting pidSensitivity = pidSpringMode.pidSensitivity;

    // ── Internal state ────────────────────────────────────────────────────────
    private volatile boolean aimThreadRunning = false;
    private Thread aimThread;

    private long lastAimNs = 0L;
    private static final long AIM_INTERVAL_NS = 2_000_000L; // 2 ms = 500 Hz cap

    // ── Constructor ───────────────────────────────────────────────────────────
    public AimbotModule() {
        super("Aimbot", ModuleCategory.EXTERNAL, MenuGroup.COMBAT, false);

        // Register default aim modes
        registerAimModeInternal(classicMode);
        registerAimModeInternal(pidSpringMode);
        registerAimModeInternal(humanMode);

        // Mode selector (always visible)
        aimMode = new ModeSetting("Aim Mode##aimbot", 0, AimType.getDisplayNames());
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

        // Register settings for each registered aim mode
        for (AimMode mode : aimModes) {
            for (Setting<?> setting : mode.getSettings()) {
                addSetting(setting);
            }
        }

        // Apply initial visibility
        applyModeVisibility();
    }

    /**
     * Registers a new AimMode into the aimbot module dynamically.
     */
    public void registerAimMode(AimMode mode) {
        if (mode == null || aimModes.contains(mode)) return;
        registerAimModeInternal(mode);
        for (Setting<?> setting : mode.getSettings()) {
            addSetting(setting);
        }
        applyModeVisibility();
    }

    private void registerAimModeInternal(AimMode mode) {
        aimModes.add(mode);
    }

    public List<AimMode> getAimModes() {
        return Collections.unmodifiableList(aimModes);
    }

    public AimMode getActiveAimMode() {
        int index = aimMode.getValue();
        if (index >= 0 && index < aimModes.size()) {
            return aimModes.get(index);
        }
        return aimModes.isEmpty() ? null : aimModes.get(0);
    }

    // ── Mode visibility management ────────────────────────────────────────────

    /**
     * Shows settings belonging to the active AimMode and hides settings of unselected modes.
     * Called whenever aimMode selection changes on tick.
     */
    private void applyModeVisibility() {
        int selectedIndex = aimMode.getValue();
        for (int i = 0; i < aimModes.size(); i++) {
            boolean isSelected = (i == selectedIndex);
            for (Setting<?> setting : aimModes.get(i).getSettings()) {
                setting.setHidden(!isSelected);
            }
        }
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
        for (AimMode mode : aimModes) {
            mode.reset();
        }
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

                    // 10. Dispatch to active AimMode
                    AimMode activeMode = getActiveAimMode();
                    if (activeMode != null) {
                        int[] move = activeMode.tick(bestDX, bestDY, bestDist, fovPx, hf, sw, dt);
                        int mx = move[0];
                        int my = move[1];

                        // 11. Inject relative mouse movement (raw-input compatible)
                        if (mx != 0 || my != 0)
                            Win32Mouse.INSTANCE.mouse_event(MOUSEEVENTF_MOVE, mx, my, 0, 0);
                    }

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