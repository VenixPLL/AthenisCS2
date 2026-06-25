package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.projection.ScreenProjector;

/**
 * BombTimerModule.
 *
 * <p>Renders a countdown above the planted C4 bomb.
 *
 * <ul>
 *   <li><b>On-screen</b> (bomb visible): circular timer widget matching the
 *       Grenade ESP visual style — dark filled background, animated arc that
 *       drains from green → orange → red, time text in the centre and a
 *       "BOMB" label below.</li>
 *   <li><b>Off-screen</b> (bomb not in view): a styled digital timer banner
 *       at the top-centre of the overlay: rounded pill background with a
 *       pulsing colored bar below it and a drop-shadowed time text.</li>
 * </ul>
 *
 * <h3>Pointer chain</h3>
 * <pre>
 *  client.dll + dwPlantedC4 - 8      → planted flag byte (1 = planted)
 *  *(client.dll + dwPlantedC4)       → c4ListPtr  (CUtlVector base)
 *  *(c4ListPtr)                      → plantedC4  (C_PlantedC4*)
 *  *(plantedC4 + m_bBombTicking)     → ticking bool
 *  *(plantedC4 + m_flC4Blow)         → explosion game-timestamp (float)
 *  *(plantedC4 + m_pGameSceneNode)   → CGameSceneNode*
 *  *(sceneNode  + m_vecAbsOrigin)    → Vector3 world position
 *
 *  currentTime = serverTickCount / 64.0f  (from engine2.dll)
 * </pre>
 */
public class BombTimerModule extends CheatModule {

    // ── Constants ─────────────────────────────────────────────────────────────
    private static final float TICK_RATE   = 64.0f;
    private static final float BOMB_FUSE   = 40.0f; // default CS2 bomb timer (s)
    private static final int   ARC_SEGMENTS = 48;

    // ── Visual constants (not exposed as settings) ────────────────────────────
    private static final float CIRCLE_RADIUS = 28.0f;
    private static final float ARC_THICKNESS =  5.0f;
    private static final float BG_ALPHA      =  0.60f;

    // ── Pre-allocated buffers (avoid GC pressure in render loop) ──────────────
    private final imgui.ImVec2 textSizeBuf = new imgui.ImVec2();
    private final float[]      screenOut   = new float[2];
    private final float[]      arcVtxX     = new float[ARC_SEGMENTS + 2];
    private final float[]      arcVtxY     = new float[ARC_SEGMENTS + 2];

    public BombTimerModule() {
        super("Bomb Timer", ModuleCategory.EXTERNAL, false);
    }

    @Override
    public void onTick() {}

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean isValidPtr(long p) {
        return p > 0x10000L && p < 0x7FFF_FFFF_FFFFL;
    }

    /** Absolute server time in seconds derived from the engine2.dll tick counter. */
    private static float getServerTime() {
        long engine2Base = CS2Memory.getEngine2Base();
        if (engine2Base == 0) return 0f;
        long networkClient = CS2Memory.readLong(engine2Base + CS2Offsets.dwNetworkGameClient);
        if (!isValidPtr(networkClient)) return 0f;
        int serverTick = CS2Memory.readInt(networkClient + CS2Offsets.dwNetworkGameClient_serverTickCount);
        return serverTick / TICK_RATE;
    }

    /**
     * Returns the arc color RGB components for the given time fraction.
     * Fraction 1.0 = plenty of time (green), 0.0 = critical (red).
     */
    private static float[] bombArcRGB(float fraction) {
        if (fraction > 0.5f) {
            // green → yellow
            float t = (fraction - 0.5f) * 2.0f; // 0..1
            return new float[]{ 1.0f - t * 0.6f, 1.0f, 0.0f };
        } else {
            // yellow → red
            float t = fraction * 2.0f; // 1..0
            return new float[]{ 1.0f, t * 0.85f, 0.0f };
        }
    }

    // ── Main render ───────────────────────────────────────────────────────────

    @Override
    public void onRender(ImDrawList drawList) {
        if (!PlayerCache.tracking) return;

        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return;

        // ── 1. Planted flag ───────────────────────────────────────────────────
        if (CS2Memory.readByte(clientBase + CS2Offsets.dwPlantedC4 - 8) == 0) return;

        // ── 2. CUtlVector → C_PlantedC4 entity (double-deref) ────────────────
        long c4ListPtr = CS2Memory.readLong(clientBase + CS2Offsets.dwPlantedC4);
        if (!isValidPtr(c4ListPtr)) return;
        long plantedC4 = CS2Memory.readLong(c4ListPtr);
        if (!isValidPtr(plantedC4)) return;

        // ── 3. Ticking check ──────────────────────────────────────────────────
        if (CS2Memory.readByte(plantedC4 + CS2Offsets.m_bBombTicking) == 0) return;

        // ── 4. Time-left calculation ──────────────────────────────────────────
        float blowTime    = CS2Memory.readFloat(plantedC4 + CS2Offsets.m_flC4Blow);
        float currentTime = getServerTime();
        float timeLeft    = blowTime - currentTime;

        if (!Float.isFinite(blowTime) || blowTime < 1.0f) return;
        if (timeLeft <= 0.0f || timeLeft > 45.0f) return;

        // ── 5. Bomb world position: entity → m_pGameSceneNode → m_vecAbsOrigin ─
        long sceneNode = CS2Memory.readLong(plantedC4 + CS2Offsets.m_pGameSceneNode);

        Vector3 bombPos = null;
        if (isValidPtr(sceneNode)) {
            bombPos = CS2Memory.readVector(sceneNode + CS2Offsets.m_vecAbsOrigin);
        }

        boolean validPos = bombPos != null
                && Float.isFinite(bombPos.x) && bombPos.x != 0f
                && Float.isFinite(bombPos.y)
                && Float.isFinite(bombPos.z);

        // ── 6. Screen projection ──────────────────────────────────────────────
        boolean projected = false;
        float   cx = 0, cy = 0;

        if (validPos) {
            projected = ScreenProjector.project(
                    bombPos.x, bombPos.y, bombPos.z + 20.0f,
                    screenOut,
                    PlayerCache.viewMatrix, PlayerCache.screenWidth, PlayerCache.screenHeight);
        }

        if (projected) {
            cx = screenOut[0] + ESPModule.espOffsetX;
            cy = screenOut[1] + ESPModule.espOffsetY;
        }

        // ── 7. Dispatch renderer ──────────────────────────────────────────────
        if (projected) {
            drawOnScreenCircle(drawList, cx, cy, timeLeft);
        } else {
            drawOffScreenBanner(drawList, timeLeft);
        }
    }

    // ── On-screen: circular timer (Grenade ESP style) ─────────────────────────

    /**
     * Draws the circular bomb timer widget directly above the bomb model.
     * Layers (back to front):
     * <ol>
     *   <li>Dark filled background circle + subtle glow ring.</li>
     *   <li>Grey track circle showing the full arc path.</li>
     *   <li>Colored animated arc that drains as the timer counts down.</li>
     *   <li>Center time text with drop shadow.</li>
     *   <li>"BOMB" label below the circle.</li>
     * </ol>
     */
    private void drawOnScreenCircle(ImDrawList drawList, float cx, float cy, float timeLeft) {
        float radius    = CIRCLE_RADIUS;
        float thickness = ARC_THICKNESS;

        // Time fraction: 1.0 = fresh plant, 0.0 = about to detonate
        float fraction = Math.min(1.0f, timeLeft / BOMB_FUSE);
        float[] rgb    = bombArcRGB(fraction);

        // 1. Outer glow / halo (very subtle)
        int glowCol = ImColor.rgba(rgb[0], rgb[1], rgb[2], 0.12f);
        drawList.addCircleFilled(cx, cy, radius + thickness + 3f, glowCol, 48);

        // 2. Dark filled background circle
        drawList.addCircleFilled(cx, cy, radius + 1f,
                ImColor.rgba(0.04f, 0.04f, 0.08f, BG_ALPHA), 48);

        // 3. Grey track (ghost full arc)
        drawList.addCircle(cx, cy, radius,
                ImColor.rgba(0.35f, 0.35f, 0.35f, 0.50f), 48, 1.5f);

        // 4. Colored arc draining clockwise from the top
        double startAngle = -Math.PI / 2.0;
        double sweepAngle =  2.0 * Math.PI * fraction;

        int segsNeeded = Math.max(3, (int) (ARC_SEGMENTS * fraction) + 1);
        int vtxCount   = 0;

        for (int s = 0; s <= segsNeeded; s++) {
            double angle  = startAngle + sweepAngle * ((double) s / segsNeeded);
            arcVtxX[vtxCount] = cx + (float) (Math.cos(angle) * radius);
            arcVtxY[vtxCount] = cy + (float) (Math.sin(angle) * radius);
            vtxCount++;
        }

        int arcCol = ImColor.rgba(rgb[0], rgb[1], rgb[2], 1.0f);
        if (vtxCount >= 2) {
            for (int s = 0; s < vtxCount - 1; s++) {
                drawList.addLine(arcVtxX[s], arcVtxY[s],
                                 arcVtxX[s + 1], arcVtxY[s + 1],
                                 arcCol, thickness);
            }
        }

        // Arc end-cap dot
        if (vtxCount > 0) {
            drawList.addCircleFilled(arcVtxX[vtxCount - 1], arcVtxY[vtxCount - 1],
                                     thickness * 0.55f, arcCol, 8);
        }

        // Thin highlight ring just inside the arc for a premium look
        drawList.addCircle(cx, cy, radius - thickness - 1f,
                ImColor.rgba(1f, 1f, 1f, 0.06f), 48, 1.0f);

        // 5. Time text in centre
        String timeText = String.format("%.1f", timeLeft);
        ImGui.calcTextSize(textSizeBuf, timeText);
        float tx = cx - textSizeBuf.x * 0.5f;
        float ty = cy - textSizeBuf.y * 0.5f;

        // Drop shadow (4-way)
        int shadow = ImColor.rgba(0f, 0f, 0f, 1.0f);
        drawList.addText(tx - 1, ty - 1, shadow, timeText);
        drawList.addText(tx + 1, ty - 1, shadow, timeText);
        drawList.addText(tx - 1, ty + 1, shadow, timeText);
        drawList.addText(tx + 1, ty + 1, shadow, timeText);

        // Main text — white normally, arc color when critical (<5 s)
        int textCol = (timeLeft < 5.0f) ? arcCol : ImColor.rgba(0.96f, 0.96f, 0.96f, 1.0f);
        drawList.addText(tx, ty, textCol, timeText);

        // 6. "BOMB" label below the circle
        String label = "BOMB";
        ImGui.calcTextSize(textSizeBuf, label);
        float lx = cx - textSizeBuf.x * 0.5f;
        float ly = cy + radius + 4f;

        drawList.addText(lx - 1, ly + 1, shadow, label);
        drawList.addText(lx + 1, ly + 1, shadow, label);
        drawList.addText(lx, ly, arcCol, label);
    }

    // ── Off-screen: styled digital banner ────────────────────────────────────

    /**
     * Draws a premium-looking digital timer banner at the top-centre of the
     * overlay when the bomb is not visible on screen.
     *
     * <p>Layers:
     * <ol>
     *   <li>Rounded pill background with a subtle gradient-like border.</li>
     *   <li>Pulsing colored bar at the very top edge of the screen.</li>
     *   <li>Drop-shadowed "BOMB" label and time text inside the pill.</li>
     * </ol>
     */
    private void drawOffScreenBanner(ImDrawList drawList, float timeLeft) {
        float fraction = Math.min(1.0f, timeLeft / BOMB_FUSE);
        float[] rgb    = bombArcRGB(fraction);
        int     arcCol = ImColor.rgba(rgb[0], rgb[1], rgb[2], 1.0f);

        float offX = ESPModule.espOffsetX;
        float offY = ESPModule.espOffsetY;
        float sw   = PlayerCache.screenWidth;

        // Pill geometry
        float pillW    = 160f;
        float pillH    = 44f;
        float pillX    = offX + sw / 2.0f - pillW / 2.0f;
        float pillY    = offY + 14f;
        float pillR    = pillH / 2.0f; // corner rounding = semi-circle ends

        // 1. Outer glow border (colored)
        int borderGlow = ImColor.rgba(rgb[0], rgb[1], rgb[2], 0.35f);
        drawList.addRectFilled(pillX - 2, pillY - 2,
                               pillX + pillW + 2, pillY + pillH + 2,
                               borderGlow, pillR + 2);

        // 2. Dark pill background
        int bgCol = ImColor.rgba(0.04f, 0.04f, 0.10f, 0.88f);
        drawList.addRectFilled(pillX, pillY,
                               pillX + pillW, pillY + pillH,
                               bgCol, pillR);

        // 3. Subtle inner highlight (thin bright strip at top of pill)
        int highlightCol = ImColor.rgba(1f, 1f, 1f, 0.07f);
        drawList.addRectFilled(pillX + 4, pillY + 2,
                               pillX + pillW - 4, pillY + pillH * 0.35f,
                               highlightCol, pillR);

        // 4. Pulsing progress bar at top edge of screen (4 px tall)
        float alpha = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() * 0.006);
        int   barCol = ImColor.rgba(rgb[0], rgb[1], rgb[2], alpha);
        drawList.addRectFilled(offX, offY,
                               offX + sw * fraction, offY + 4f,
                               barCol);
        // Dark remainder of bar
        drawList.addRectFilled(offX + sw * fraction, offY,
                               offX + sw, offY + 4f,
                               ImColor.rgba(0.1f, 0.1f, 0.1f, 0.4f));

        // 5. "BOMB" label inside pill (small, upper portion)
        int shadow = ImColor.rgba(0, 0, 0, 220);

        String label = "BOMB";
        ImGui.calcTextSize(textSizeBuf, label);
        float lx = pillX + pillW / 2.0f - textSizeBuf.x / 2.0f;
        float ly = pillY + 5f;

        drawList.addText(lx - 1, ly + 1, shadow, label);
        drawList.addText(lx + 1, ly + 1, shadow, label);
        drawList.addText(lx, ly, arcCol, label);

        // 6. Time text (larger, lower portion of pill)
        String timeText = String.format("%.1f s", timeLeft);
        ImGui.calcTextSize(textSizeBuf, timeText);
        float tx = pillX + pillW / 2.0f - textSizeBuf.x / 2.0f;
        float ty = pillY + 22f;

        drawList.addText(tx - 1, ty - 1, shadow, timeText);
        drawList.addText(tx + 1, ty - 1, shadow, timeText);
        drawList.addText(tx - 1, ty + 1, shadow, timeText);
        drawList.addText(tx + 1, ty + 1, shadow, timeText);

        // White when enough time, arc color when critical
        int textCol = (timeLeft < 5.0f)
                ? arcCol
                : ImColor.rgba(0.96f, 0.96f, 0.96f, 1.0f);
        drawList.addText(tx, ty, textCol, timeText);
    }
}
