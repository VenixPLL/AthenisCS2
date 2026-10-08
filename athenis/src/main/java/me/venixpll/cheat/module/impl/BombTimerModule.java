package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.projection.ScreenProjector;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.overlay.OverlayWindow;

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
 *       that can be freely dragged to any position on screen. The banner
 *       defaults to top-centre and can be repositioned while the overlay
 *       menu (INSERT key) is open by clicking and dragging it.</li>
 * </ul>
 *
 * <h3>Pointer chain (updated after CS2 mid-2026 patch)</h3>
 * <pre>
 *  client.dll + dwPlantedC4 - 8      → planted flag byte (non-zero = planted)
 *  *(client.dll + dwPlantedC4)       → plantedC4  (C_PlantedC4*)   ← single deref now
 *  *(plantedC4 + m_bBombTicking)     → ticking bool
 *  *(plantedC4 + m_flC4Blow)         → explosion game-timestamp (float)
 *  *(plantedC4 + m_pGameSceneNode)   → CGameSceneNode*
 *  *(sceneNode  + m_vecAbsOrigin)    → Vector3 world position
 *
 *  NOTE: Prior to the patch, dwPlantedC4 pointed at a CUtlVector whose
 *  m_pMemory[0] held the C_PlantedC4*. The extra level of indirection was
 *  removed by Valve — one readLong is now sufficient.
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

    // ── Banner pill geometry constants ────────────────────────────────────────
    private static final float PILL_W = 160f;
    private static final float PILL_H =  44f;

    // ── Off-screen banner position settings (persisted via ConfigManager) ─────

    /**
     * X coordinate of the banner's left edge.
     * Default centres the pill on a 1920-wide screen; bounded 0 – 1920.
     */
    public final FloatSetting bannerX = new FloatSetting(
            "Banner X", 1587.0f, 0f, 1920f);

    /**
     * Y coordinate of the banner's top edge.
     * Default places the pill near the top of a 1080-high screen; bounded 0 – 1060.
     */
    public final FloatSetting bannerY = new FloatSetting(
            "Banner Y", 5.0f, 0f, 1060f);

    // ── Pre-allocated buffers (avoid GC pressure in render loop) ──────────────
    private final imgui.ImVec2 textSizeBuf = new imgui.ImVec2();
    private final float[]      screenOut   = new float[2];
    private final float[]      arcVtxX     = new float[ARC_SEGMENTS + 2];
    private final float[]      arcVtxY     = new float[ARC_SEGMENTS + 2];

    // ── Drag state (render thread only — no volatile/lock needed) ─────────────

    /** Sentinel: no drag in progress. */
    private static final int DRAG_NONE = 0;
    /** User is dragging the banner to a new position. */
    private static final int DRAG_MOVE = 1;

    /** Current drag mode. */
    private int   dragMode          = DRAG_NONE;
    /** Banner X at the moment the drag started. */
    private float dragStartX        = 0f;
    /** Banner Y at the moment the drag started. */
    private float dragStartY        = 0f;
    /** Mouse X at the moment the drag started. */
    private float dragOriginMouseX  = 0f;
    /** Mouse Y at the moment the drag started. */
    private float dragOriginMouseY  = 0f;

    public BombTimerModule() {
        super("Bomb Timer", ModuleCategory.EXTERNAL, MenuGroup.VISUALS, true);
        addSetting(bannerX);
        addSetting(bannerY);
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
        if (engine2Base == 0) {
            System.out.println("[BombTimer][getServerTime] engine2Base=0 — engine2.dll not found!");
            return 0f;
        }
        long networkClient = CS2Memory.readLong(engine2Base + CS2Offsets.dwNetworkGameClient);
        if (!isValidPtr(networkClient)) {
            System.out.printf("[BombTimer][getServerTime] networkClient=0x%X  invalid! dwNetworkGameClient offset=0x%X%n",
                    networkClient, CS2Offsets.dwNetworkGameClient);
            return 0f;
        }
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
        // ── Menu-open preview: show a draggable mock banner even when no bomb
        //    is planted so the user can reposition the widget freely. ──────────
        if (OverlayWindow.isMenuOpen()) {
            drawMockBanner(drawList);
            return;
        }

        if (!PlayerCache.tracking) return;

        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return;

        // ── 1. Planted flag ───────────────────────────────────────────────────
        //  client.dll + dwPlantedC4 - 8 holds a non-zero byte when a bomb is planted.
        if (CS2Memory.readByte(clientBase + CS2Offsets.dwPlantedC4 - 8) == 0) return;

        // ── 2. C_PlantedC4 entity pointer (single deref) ─────────────────────
        //  After a CS2 update dwPlantedC4 changed from pointing at a CUtlVector
        //  to pointing directly at the C_PlantedC4* entity.  One readLong is now
        //  sufficient — the old double-deref via CUtlVector is no longer correct.
        long plantedC4 = CS2Memory.readLong(clientBase + CS2Offsets.dwPlantedC4);
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

    // ── Off-screen: styled digital banner (draggable) ─────────────────────────

    /**
     * Draws a premium-looking digital timer banner at the stored position when
     * the bomb is not visible on screen.
     *
     * <p>While the overlay menu is open (INSERT key) the banner shows a drag
     * affordance and can be repositioned freely by clicking and dragging it.
     * The new position is stored in {@link #bannerX} / {@link #bannerY} and
     * persisted automatically by ConfigManager on exit.
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

        // Resolve pill position from persisted settings
        float pillW = PILL_W;
        float pillH = PILL_H;
        float pillX = offX + bannerX.getValue();
        float pillY = offY + bannerY.getValue();
        float pillR = pillH / 2.0f; // corner rounding = semi-circle ends

        // ── Handle drag interaction ───────────────────────────────────────────
        handleBannerDrag(pillX, pillY, pillW, pillH);

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

        // 7. Drag affordance overlay (only when menu is open)
        if (OverlayWindow.isMenuOpen()) {
            drawBannerDragAffordance(drawList, pillX, pillY, pillW, pillH, pillR);
        }
    }

    /**
     * Updates the drag state machine for the off-screen banner.
     * Must be called every frame before drawing the pill so that the position
     * settings are updated before the pill geometry is calculated.
     *
     * <p>Drag is only processed when the overlay menu is open; otherwise the
     * window is click-through and mouse events are never received.
     *
     * @param pillX  Current pill left edge (screen pixels, including espOffset).
     * @param pillY  Current pill top edge (screen pixels, including espOffset).
     * @param pillW  Pill width in pixels.
     * @param pillH  Pill height in pixels.
     */
    private void handleBannerDrag(float pillX, float pillY, float pillW, float pillH) {
        if (!OverlayWindow.isMenuOpen()) {
            // Menu closed → always reset drag so a stale drag does not resume.
            dragMode = DRAG_NONE;
            return;
        }

        ImVec2 mousePos = ImGui.getMousePos();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        boolean hoverPill = mouseX >= pillX && mouseX <= pillX + pillW
                         && mouseY >= pillY && mouseY <= pillY + pillH;

        if (ImGui.isMouseReleased(0)) {
            // Any release ends the drag.
            dragMode = DRAG_NONE;

        } else if (dragMode == DRAG_MOVE) {
            // Continue moving: apply cumulative delta from drag origin.
            float deltaX = mouseX - dragOriginMouseX;
            float deltaY = mouseY - dragOriginMouseY;
            bannerX.setValue(dragStartX + deltaX);
            bannerY.setValue(dragStartY + deltaY);

        } else if (ImGui.isMouseClicked(0) && hoverPill) {
            // Begin drag on the frame the LMB is first pressed inside the pill.
            dragMode = DRAG_MOVE;
            dragStartX = bannerX.getValue();
            dragStartY = bannerY.getValue();
            dragOriginMouseX = mouseX;
            dragOriginMouseY = mouseY;
        }
    }

    /**
     * Draws a semi-transparent drag-hint overlay on top of the pill while the
     * menu is open.  The hint consists of:
     * <ul>
     *   <li>A highlighted border (bright cyan) to signal interactivity.</li>
     *   <li>A small "⠿ drag" label inside the pill.</li>
     * </ul>
     *
     * @param drawList ImGui foreground draw list.
     * @param pillX    Pill left edge (screen pixels).
     * @param pillY    Pill top edge (screen pixels).
     * @param pillW    Pill width.
     * @param pillH    Pill height.
     * @param pillR    Corner radius.
     */
    private void drawBannerDragAffordance(ImDrawList drawList,
            float pillX, float pillY, float pillW, float pillH, float pillR) {

        ImVec2 mousePos = ImGui.getMousePos();
        float mouseX = mousePos.x;
        float mouseY = mousePos.y;

        boolean hoverPill = mouseX >= pillX && mouseX <= pillX + pillW
                         && mouseY >= pillY && mouseY <= pillY + pillH;

        // Highlight border when hovered or actively dragging
        if (hoverPill || dragMode == DRAG_MOVE) {
            drawList.addRect(pillX - 2, pillY - 2,
                             pillX + pillW + 2, pillY + pillH + 2,
                             ImColor.rgba(0.00f, 0.85f, 1.00f, 0.80f),
                             pillR + 2, 0, 1.8f);
        } else {
            // Subtle dashed outline to indicate draggability even when not hovered
            drawList.addRect(pillX - 2, pillY - 2,
                             pillX + pillW + 2, pillY + pillH + 2,
                             ImColor.rgba(0.00f, 0.71f, 0.85f, 0.40f),
                             pillR + 2, 0, 1.0f);
        }

        // Small hint label at the bottom-centre of the pill
        String hint = dragMode == DRAG_MOVE ? "dragging..." : "\u22BF drag to move";
        ImGui.calcTextSize(textSizeBuf, hint);
        float hx = pillX + pillW / 2.0f - textSizeBuf.x / 2.0f;
        float hy = pillY + pillH - textSizeBuf.y - 2f;
        drawList.addText(hx, hy,
                ImColor.rgba(1.0f, 1.0f, 1.0f, dragMode == DRAG_MOVE ? 0.75f : 0.35f),
                hint);
    }

    // ── Mock banner (shown while menu is open, no active bomb required) ─────────

    /**
     * Draws a greyed-out, static preview of the off-screen timer banner at the
     * current stored position so the user can reposition it without needing an
     * active bomb.
     *
     * <p>The pill uses muted, monochrome colours to make it visually distinct
     * from a live timer. Drag interaction is fully active so the user can move
     * it the same way as the real banner.
     *
     * @param drawList ImGui foreground draw list.
     */
    private void drawMockBanner(ImDrawList drawList) {
        float offX = ESPModule.espOffsetX;
        float offY = ESPModule.espOffsetY;

        float pillW = PILL_W;
        float pillH = PILL_H;
        float pillX = offX + bannerX.getValue();
        float pillY = offY + bannerY.getValue();
        float pillR = pillH / 2.0f;

        // Process drag so the banner can be moved right away
        handleBannerDrag(pillX, pillY, pillW, pillH);

        // Recalculate pill position after potential drag update
        pillX = offX + bannerX.getValue();
        pillY = offY + bannerY.getValue();

        // 1. Muted outer glow border
        drawList.addRectFilled(pillX - 2, pillY - 2,
                               pillX + pillW + 2, pillY + pillH + 2,
                               ImColor.rgba(0.55f, 0.55f, 0.55f, 0.25f),
                               pillR + 2);

        // 2. Dark pill background (same shade as real banner)
        drawList.addRectFilled(pillX, pillY,
                               pillX + pillW, pillY + pillH,
                               ImColor.rgba(0.04f, 0.04f, 0.10f, 0.88f),
                               pillR);

        // 3. Subtle inner highlight
        drawList.addRectFilled(pillX + 4, pillY + 2,
                               pillX + pillW - 4, pillY + pillH * 0.35f,
                               ImColor.rgba(1f, 1f, 1f, 0.07f),
                               pillR);

        // 4. "BOMB" label (muted white)
        int muted  = ImColor.rgba(0.65f, 0.65f, 0.65f, 0.80f);
        int shadow = ImColor.rgba(0, 0, 0, 180);

        String label = "BOMB";
        ImGui.calcTextSize(textSizeBuf, label);
        float lx = pillX + pillW / 2.0f - textSizeBuf.x / 2.0f;
        float ly = pillY + 5f;
        drawList.addText(lx - 1, ly + 1, shadow, label);
        drawList.addText(lx + 1, ly + 1, shadow, label);
        drawList.addText(lx, ly, muted, label);

        // 5. Placeholder time text
        String timeText = "-- s";
        ImGui.calcTextSize(textSizeBuf, timeText);
        float tx = pillX + pillW / 2.0f - textSizeBuf.x / 2.0f;
        float ty = pillY + 22f;
        drawList.addText(tx - 1, ty - 1, shadow, timeText);
        drawList.addText(tx + 1, ty - 1, shadow, timeText);
        drawList.addText(tx - 1, ty + 1, shadow, timeText);
        drawList.addText(tx + 1, ty + 1, shadow, timeText);
        drawList.addText(tx, ty, muted, timeText);

        // 6. Drag affordance (always shown — the whole point of the mock)
        drawBannerDragAffordance(drawList, pillX, pillY, pillW, pillH, pillR);
    }
}
