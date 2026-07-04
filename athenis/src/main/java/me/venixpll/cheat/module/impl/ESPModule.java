package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheckAdapter;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.reader.PositionReader;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;
import me.venixpll.cheat.setting.FloatSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * ESP (Extra Sensory Perception) Module.
 * Responsible for rendering 2D bounding boxes, health status bars, and names on top of active players.
 * <p>
 * <h3>Position accuracy</h3>
 * CS2's {@code m_vOldOrigin} is always 1 server tick (~15.6 ms at 64 Hz) behind the
 * actual player position. {@link PositionReader} compensates for this by reading
 * {@code m_vecVelocity} and extrapolating the position forward before projecting.
 * The {@link #extrapolationBias} setting lets you fine-tune the extrapolation amount
 * to perfectly align boxes with players at your specific tick rate and hardware latency.
 */
public class ESPModule extends CheatModule {

    /** Toggle to show/hide player bounding boxes */
    public final BooleanSetting boxEsp    = new BooleanSetting("Render Box", true);
    /** Toggle to show/hide player skeletons */
    public final BooleanSetting skeletonEsp = new BooleanSetting("Render Skeleton", true);
    /** Toggle to show/hide player health bars */
    public final BooleanSetting healthEsp = new BooleanSetting("Show Health Indicators", true);
    /** Toggle to show/hide player names */
    public final BooleanSetting nameEsp   = new BooleanSetting("Show Player Names", true);
    /** Filter out teammate ESP options */
    public final BooleanSetting teamCheck = new BooleanSetting("Enemy-Only Team Filter", true);

    // ── Player Flags ──────────────────────────────────────────────────────────────
    /** Master toggle for the Player Flags feature. */
    public final BooleanSetting flagsEsp       = new BooleanSetting("Show Player Flags",   true);
    /** Show a “Blind” badge when the target is currently flashed. */
    public final BooleanSetting flagBlind      = new BooleanSetting("Flag: Blind",          true);
    /** Show a “Scoped” badge when the target has their weapon zoomed in. */
    public final BooleanSetting flagScoped     = new BooleanSetting("Flag: Scoped",         true);
    /** Show a “Defusing” badge when the target is actively defusing the bomb. */
    public final BooleanSetting flagDefusing   = new BooleanSetting("Flag: Defusing",       true);
    /** Show a “Kit” badge when the target is carrying a defuse kit. */
    public final BooleanSetting flagKit        = new BooleanSetting("Flag: Kit",             true);
    /** Show the target’s current in-game money balance. */
    public final BooleanSetting flagMoney      = new BooleanSetting("Flag: Money",           true);

    // ── Fake Chams ────────────────────────────────────────────────────────────
    /**
     * When enabled, renders a filled solid-color silhouette over every tracked
     * player to simulate real "chams" (coloured model overlays).
     * The fill is drawn in two layers:
     *   1. A filled bounding-box rectangle for the body mass.
     *   2. Thickened filled bone segments (wide lines) to suggest limbs.
     * Visible players get a brighter, more opaque tint on top.
     */
    public final BooleanSetting fakeChams      = new BooleanSetting("Fake Chams", false);
    /** Base fill color for the chams silhouette (non-visible players). */
    public final ColorSetting   chamsColor     = new ColorSetting("Chams Color",     0.08f, 1.0f, 0.45f, 0.55f);
    /** Alternate fill color used when the player is directly visible to local. */
    public final ColorSetting   chamsVisColor  = new ColorSetting("Chams Vis Color", 1.0f,  0.3f, 0.05f, 0.80f);
    /** Width (px) of each bone segment drawn as a thick line for the limb fill. */
    public final FloatSetting   chamsLimbWidth = new FloatSetting("Chams Limb Width", 8.0f, 2.0f, 24.0f);

    /** Bone connections for drawing the skeleton */
    private static final int[][] BONE_CONNECTIONS = {
        // Spine / Body
        {7, 6},   // Head -> Neck
        {6, 4},   // Neck -> Spine2
        {4, 2},   // Spine2 -> Spine0
        {2, 1},   // Spine0 -> Pelvis

        // Left arm
        {6, 9},   // Neck -> L Shoulder
        {9, 10},  // L Shoulder -> L Elbow
        {10, 11}, // L Elbow -> L Hand

        // Right arm
        {6, 13},  // Neck -> R Shoulder
        {13, 14}, // R Shoulder -> R Elbow
        {14, 15}, // R Elbow -> R Hand

        // Left leg
        {1, 17},  // Pelvis -> L Hip
        {17, 18}, // L Hip -> L Knee
        {18, 19}, // L Knee -> L Foot (Heel)

        // Right leg
        {1, 20},  // Pelvis -> R Hip
        {20, 21}, // R Hip -> R Knee
        {21, 22}  // R Knee -> R Foot (Heel)
    };

    /**
     * Forward extrapolation time in milliseconds.
     * <p>
     * Compensates for {@code m_vOldOrigin} being 1 tick stale plus RPM and render
     * pipeline latency. Increase if boxes lag behind moving players; decrease if
     * boxes run ahead of them.
     * <ul>
     *   <li><b>0 ms</b> — no compensation (raw m_vOldOrigin, always behind)</li>
     *   <li><b>20 ms</b> — default; good for 64 Hz server tick</li>
     *   <li><b>8 ms</b> — try for 128 Hz servers</li>
     *   <li><b>30–40 ms</b> — if boxes still lag, increase here</li>
     * </ul>
     */
    public final FloatSetting extrapolationBias = new FloatSetting(
            "Extrapolation (ms)", 20.0f, 0.0f, 60.0f);

    /** Color representation for rendering enemy players */
    public final ColorSetting enemyColor = new ColorSetting("Enemy Color", 1.0f, 0.2f, 0.2f, 1.0f);
    /** Color representation for rendering teammate players */
    public final ColorSetting teamColor  = new ColorSetting("Team Color",  0.2f, 0.6f, 1.0f, 1.0f);

    /** Overlay offset X calculated dynamically when adjusting window borders */
    public static volatile float espOffsetX = 0f;
    /** Overlay offset Y calculated dynamically when adjusting window borders */
    public static volatile float espOffsetY = 0f;

    /** Team number of the local player, used to perform relationship filter checks */
    public static volatile int localTeam = 0;

    /** Pre-allocated buffer for measuring text bounds without allocating objects */
    private final imgui.ImVec2 textSizeBuf = new imgui.ImVec2();

    /**
     * Instantiates the ESP module and registers settings.
     */
    public ESPModule() {
        super("ESP Overlay", ModuleCategory.EXTERNAL, true);
        addSetting(boxEsp);
        addSetting(skeletonEsp);
        addSetting(healthEsp);
        addSetting(nameEsp);
        addSetting(teamCheck);
        addSetting(extrapolationBias);
        addSetting(enemyColor);
        addSetting(teamColor);
        addSetting(fakeChams);
        addSetting(chamsColor);
        addSetting(chamsVisColor);
        addSetting(chamsLimbWidth);
        // ── Player Flags
        addSetting(flagsEsp);
        addSetting(flagBlind);
        addSetting(flagScoped);
        addSetting(flagDefusing);
        addSetting(flagKit);
        addSetting(flagMoney);
    }

    /**
     * Iterates through active players on-screen and draws bounding boxes, names, and
     * health indicators with no added lag — raw projected coordinates from the
     * immutable snapshot are used directly.
     * <p>
     * The only motion compensation is the forward extrapolation already baked into the
     * snapshot by {@link PositionReader} (velocity × latency applied in world space
     * before projection). This is the correct approach — any screen-space smoothing
     * would reintroduce the visual lag the user reported.
     *
     * @param drawList The ImGui foreground draw list.
     */
    @Override
    public void onRender(ImDrawList drawList) {
        if (!PlayerCache.tracking) return;

        // Push the current extrapolation amount to PositionReader so it takes effect
        // on the next fast-loop iteration. Converting ms → seconds.
        PositionReader.EXTRAPOLATION_SECONDS = extrapolationBias.getValue() / 1000.0f;

        // Read the immutable snapshot list — a single volatile read, fully consistent.
        List<PlayerSnapshot> currentPlayers = PlayerCache.renderPlayers;
        if (currentPlayers.isEmpty()) return;

        for (PlayerSnapshot player : currentPlayers) {
            if (player.isLocal || !player.onScreen) continue;

            boolean isEnemy = (player.team != localTeam);
            if (teamCheck.getValue() && !isEnemy) continue;

            // Raw snapshot coordinates — already validated by ScreenProjector.
            float feetX = player.feetX;
            float feetY = player.feetY;
            float headY = player.headY;

            // Last-resort guard: native ImGui draw calls crash the JVM if passed NaN
            // or Infinity. This should never trigger if ScreenProjector is correct,
            // but is cheap insurance against any edge case.
            if (!Float.isFinite(feetX) || !Float.isFinite(feetY) || !Float.isFinite(headY)) continue;

            // ── Bounding box geometry ─────────────────────────────────────────
            float height = feetY - headY;
            float width  = height / 2.0f;
            float minX   = feetX - width / 2.0f + espOffsetX;
            float minY   = headY + espOffsetY;
            float maxX   = minX  + width;
            float maxY   = feetY + espOffsetY;

            // ── VisCheck Visibility check ─────────────────────────────────────
            boolean isVis = false;
            VisCheck visCheck = VisCheckAdapter.getVisCheck();
            if (visCheck != null) {
                long localPawn = PlayerCache.localPlayerPawnAddress;
                if (localPawn != 0) {
                    Vector3 localOrigin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                    if (localOrigin != null) {
                        Vector3 localCamera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);
                        Vector3 targetHead = new Vector3(player.worldX, player.worldY, player.worldZ + 72.0f);
                        isVis = visCheck.isPointVisible(localCamera, targetHead);
                    }
                }
            }

            // ── Select color ──────────────────────────────────────────────────
            float[] enemyCol = enemyColor.getValue();
            float[] teamCol  = teamColor.getValue();
            int colorInt;
            if (visCheck != null && isVis) {
                colorInt = isEnemy
                        ? ImColor.rgba(1.0f, 0.92f, 0.016f, 1.0f) // Vibrant Yellow for visible enemies
                        : ImColor.rgba(0.2f, 0.75f, 1.0f, 1.0f);  // Bright Light Blue for visible teammates
            } else {
                colorInt = isEnemy
                        ? ImColor.rgba(enemyCol[0], enemyCol[1], enemyCol[2], enemyCol[3])
                        : ImColor.rgba(teamCol[0],  teamCol[1],  teamCol[2],  teamCol[3]);
            }

            // ── Fake Chams silhouette ─────────────────────────────────────────
            if (fakeChams.getValue()) {
                float[] chamsCol    = (isVis && visCheck != null) ? chamsVisColor.getValue() : chamsColor.getValue();
                int     chamsFill   = ImColor.rgba(chamsCol[0], chamsCol[1], chamsCol[2], chamsCol[3]);
                float   limbW       = chamsLimbWidth.getValue();

                // 1. Filled body rectangle — lower 80 % of the bounding box
                //    (excludes the head zone so bones show through naturally).
                float bodyTop = minY + height * 0.10f;
                drawList.addRectFilled(minX, bodyTop, maxX, maxY, chamsFill);

                // 2. Thickened bone segments drawn as fat lines to simulate limbs.
                if (player.boneX.length > 0) {
                    for (int[] connection : BONE_CONNECTIONS) {
                        int b1 = connection[0];
                        int b2 = connection[1];
                        if (b1 < player.boneX.length && b2 < player.boneX.length
                                && player.boneVisible[b1] && player.boneVisible[b2]) {
                            float lx1 = player.boneX[b1] + espOffsetX;
                            float ly1 = player.boneY[b1] + espOffsetY;
                            float lx2 = player.boneX[b2] + espOffsetX;
                            float ly2 = player.boneY[b2] + espOffsetY;
                            drawList.addLine(lx1, ly1, lx2, ly2, chamsFill, limbW);
                        }
                    }

                    // Filled head circle
                    if (7 < player.boneX.length && player.boneVisible[7]) {
                        float hcx = player.boneX[7] + espOffsetX;
                        float hcy = player.boneY[7] + espOffsetY;
                        float hr  = Math.max(4.0f, Math.min(14.0f, height / 10.0f));
                        drawList.addCircleFilled(hcx, hcy, hr, chamsFill, 24);
                    }
                }

                // 3. Bright rim outline — only when box rendering is active.
                if (boxEsp.getValue()) {
                    int rimColor = ImColor.rgba(chamsCol[0], chamsCol[1], chamsCol[2], Math.min(1.0f, chamsCol[3] + 0.3f));
                    drawList.addRect(minX, bodyTop, maxX, maxY, rimColor, 0.0f, 0, 1.5f);
                }
            }

            // ── Bounding box with high-contrast outlines ─────────────────────
            if (boxEsp.getValue()) {
                drawList.addRect(minX - 1, minY - 1, maxX + 1, maxY + 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX + 1, minY + 1, maxX - 1, maxY - 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX,     minY,     maxX,     maxY,     colorInt,                   0.0f, 0, 1.0f);
            }

            // ── Skeleton (BoneESP) ───────────────────────────────────────────
            if (skeletonEsp.getValue() && player.boneX.length > 0) {
                // Precompute bone vischeck block status
                boolean[] boneVisBlocked = new boolean[player.boneX.length];
                if (visCheck != null) {
                    long localPawn = PlayerCache.localPlayerPawnAddress;
                    if (localPawn != 0) {
                        Vector3 localOrigin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                        if (localOrigin != null) {
                            Vector3 localCamera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);
                            for (int i = 0; i < player.boneX.length; i++) {
                                if (player.boneVisible[i] && i < player.boneWorldX.length) {
                                    float bx = player.boneWorldX[i];
                                    float by = player.boneWorldY[i];
                                    float bz = player.boneWorldZ[i];
                                    if (bx != 0f || by != 0f || bz != 0f) {
                                        boneVisBlocked[i] = !visCheck.isPointVisible(localCamera, new Vector3(bx, by, bz));
                                    }
                                }
                            }
                        }
                    }
                }

                for (int[] connection : BONE_CONNECTIONS) {
                    int bone1 = connection[0];
                    int bone2 = connection[1];

                    if (bone1 < player.boneX.length && bone2 < player.boneX.length) {
                        if (player.boneVisible[bone1] && player.boneVisible[bone2]) {
                            float x1 = player.boneX[bone1] + espOffsetX;
                            float y1 = player.boneY[bone1] + espOffsetY;
                            float x2 = player.boneX[bone2] + espOffsetX;
                            float y2 = player.boneY[bone2] + espOffsetY;

                            // If blocked, draw connection in muted red (enemy) or muted blue (team)
                            boolean isBlocked = boneVisBlocked[bone1] || boneVisBlocked[bone2];
                            int lineCol;
                            if (isBlocked) {
                                lineCol = isEnemy
                                        ? ImColor.rgba(1.0f, 0.2f, 0.2f, 0.65f) // Blocked enemy = muted red
                                        : ImColor.rgba(0.15f, 0.40f, 0.75f, 0.60f); // Blocked teammate = muted blue
                            } else {
                                lineCol = colorInt;
                            }

                            // Draw high-contrast outlines
                            drawList.addLine(x1 - 1, y1 - 1, x2 - 1, y2 - 1, ImColor.rgba(0, 0, 0, 120), 1.5f);
                            drawList.addLine(x1 + 1, y1 + 1, x2 + 1, y2 + 1, ImColor.rgba(0, 0, 0, 120), 1.5f);
                            drawList.addLine(x1,     y1,     x2,     y2,     lineCol,                    1.5f);
                        }
                    }
                }

                // Draw head circle if HEAD bone is visible (index 7)
                if (7 < player.boneX.length && player.boneVisible[7]) {
                    float headCX = player.boneX[7] + espOffsetX;
                    float headCY = player.boneY[7] + espOffsetY;
                    float headRadius = Math.max(3.0f, Math.min(12.0f, height / 12.0f));

                    boolean isHeadBlocked = boneVisBlocked[7];
                    int headCol;
                    if (isHeadBlocked) {
                        headCol = isEnemy
                                ? ImColor.rgba(1.0f, 0.2f, 0.2f, 0.80f) // Blocked enemy head = muted red
                                : ImColor.rgba(0.15f, 0.40f, 0.75f, 0.75f); // Blocked teammate head = muted blue
                    } else {
                        headCol = colorInt;
                    }

                    // High-contrast outlines for the head circle
                    drawList.addCircle(headCX, headCY, headRadius - 0.5f, ImColor.rgba(0, 0, 0, 150), 16, 1.5f);
                    drawList.addCircle(headCX, headCY, headRadius + 0.5f, ImColor.rgba(0, 0, 0, 150), 16, 1.5f);
                    drawList.addCircle(headCX, headCY, headRadius,        headCol,                    16, 1.5f);
                }
            }

            // ── Segmented health bar (dynamic green-to-red) ───────────────────
            if (healthEsp.getValue()) {
                float barMinX = minX - 6;
                drawList.addRectFilled(barMinX - 1, minY - 1, barMinX + 3, maxY + 1, ImColor.rgba(0, 0, 0, 180));

                float healthPercent = Math.max(0.0f, Math.min(1.0f, player.health / 100.0f));
                float healthHeight  = height * healthPercent;
                float healthMinY    = maxY - healthHeight;

                float r = 1.0f - healthPercent;
                float g = healthPercent;
                int barColor = ImColor.rgba(r, g, 0.0f, 1.0f);

                drawList.addRectFilled(barMinX, healthMinY, barMinX + 2, maxY, barColor);
            }

            // ── Name + health text with drop shadow ───────────────────────────
            if (nameEsp.getValue()) {
                String text = player.name + " [" + player.health + "]";
                ImGui.calcTextSize(textSizeBuf, text);
                float textWidth = textSizeBuf.x;
                float textX = feetX - textWidth / 2.0f + espOffsetX;
                float textY = headY - 15 + espOffsetY;

                int textColor = (visCheck != null && isVis) ? colorInt : ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f);

                drawList.addText(textX - 1, textY - 1, ImColor.rgba(0, 0, 0, 255), text);
                drawList.addText(textX + 1, textY - 1, ImColor.rgba(0, 0, 0, 255), text);
                drawList.addText(textX - 1, textY + 1, ImColor.rgba(0, 0, 0, 255), text);
                drawList.addText(textX + 1, textY + 1, ImColor.rgba(0, 0, 0, 255), text);
                drawList.addText(textX,     textY,     textColor, text);
            }
            // ── Player Flags (above the bounding box) ────────────────────────────
            if (flagsEsp.getValue()) {
                renderFlags(drawList, player, minX, maxX, minY, feetX, headY);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Player Flag rendering
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Constant height of each flag badge pill in pixels.
     * The pill’s width is driven by the text it contains plus horizontal padding.
     */
    private static final float FLAG_H    = 11.0f;
    /** Vertical gap between consecutive flag badges. */
    private static final float FLAG_GAP  = 2.0f;
    /** Horizontal padding inside each pill on both sides. */
    private static final float FLAG_PAD  = 4.0f;
    /** Corner radius for the rounded pill shape. */
    private static final float FLAG_ROUNDING = 3.0f;

    // Pre-allocated badge text buffer
    private final imgui.ImVec2 flagSizeBuf = new imgui.ImVec2();

    /**
     * Draws the active flag badges (Blind / Scoped / Defusing / Kit / Money)
     * as a vertical column of pill-shaped labels stacked above the bounding box top edge.
     *
     * <p>Each badge is drawn as a semi-transparent filled rounded rectangle with a
     * contrasting drop-shadow text and a brighter label on top.</p>
     *
     * @param drawList  ImGui foreground draw list.
     * @param player    Immutable player snapshot supplying flag state.
     * @param minX      Left edge of the player bounding box.
     * @param maxX      Right edge of the player bounding box.
     * @param minY      Top edge of the player bounding box (smallest Y = highest on screen).
     * @param centerX   Horizontal centre of the bounding box (feet X).
     * @param headY     Screen Y of the head (topmost projected point).
     */
    private void renderFlags(ImDrawList drawList,
                             PlayerSnapshot player,
                             float minX, float maxX,
                             float minY, float centerX, float headY) {

        // Build the list of active badges in priority order.
        List<FlagEntry> badges = new ArrayList<>(5);

        // Flashbang blind indicator (duration left > 0.1s and max alpha > 10.0)
        if (flagBlind.getValue() && player.flashDuration > 0.1f && player.flashMaxAlpha > 10.0f) {
            // Intensity label: "BLIND!" when heavily flashed (remaining duration > 2.0s)
            String label = player.flashDuration > 2.0f ? "BLIND!" : "BLIND";
            // Vivid cyan — eye-catching, distinct from all other badge colours.
            badges.add(new FlagEntry(label, ImColor.rgba(0.0f, 0.95f, 1.0f, 0.88f)));
        }
        // Scoped indicator
        if (flagScoped.getValue() && player.isScoped) {
            // Purple-magenta — easy to remember as “scope” colour.
            badges.add(new FlagEntry("SCOPED", ImColor.rgba(0.80f, 0.20f, 1.0f, 0.88f)));
        }
        // Defusing / Planting indicator
        if (flagDefusing.getValue() && player.isDefusingOrPlanting) {
            if (player.team == 3) {
                // CT is defusing the bomb
                badges.add(new FlagEntry("DEFUSING", ImColor.rgba(1.0f, 0.45f, 0.0f, 0.92f)));
            } else if (player.team == 2) {
                // T is planting the bomb
                badges.add(new FlagEntry("PLANTING", ImColor.rgba(1.0f, 0.30f, 0.10f, 0.92f)));
            }
        }
        // Kit indicator
        if (flagKit.getValue() && player.hasKit) {
            // Warm gold — associated with utility / kit items.
            badges.add(new FlagEntry("KIT", ImColor.rgba(1.0f, 0.85f, 0.0f, 0.88f)));
        }
        // Money indicator
        if (flagMoney.getValue() && player.money > 0) {
            // Green — universally associated with money.
            String label = "$" + player.money;
            badges.add(new FlagEntry(label, ImColor.rgba(0.25f, 0.92f, 0.35f, 0.88f)));
        }

        if (badges.isEmpty()) return;

        // Stack badges upward from the top of the name text.
        // nameEsp places its text at (headY - 15); start just above that.
        float cursor = headY - 16.0f + espOffsetY; // top of first badge

        for (int bi = badges.size() - 1; bi >= 0; bi--) {
            FlagEntry badge = badges.get(bi);

            // Measure the text so we can size the pill correctly.
            ImGui.calcTextSize(flagSizeBuf, badge.label);
            float tw = flagSizeBuf.x;
            float pillW = tw + FLAG_PAD * 2.0f;

            // Centre the pill horizontally over the bounding box.
            float pillX = centerX + espOffsetX - pillW / 2.0f;
            float pillY = cursor - FLAG_H;

            // —— Background pill (dark, semi-transparent) ———————————————————
            drawList.addRectFilled(
                    pillX, pillY,
                    pillX + pillW, cursor,
                    ImColor.rgba(0.0f, 0.0f, 0.0f, 0.60f),
                    FLAG_ROUNDING);

            // —— Coloured pill border ————————————————————————————————
            drawList.addRect(
                    pillX, pillY,
                    pillX + pillW, cursor,
                    badge.color,
                    FLAG_ROUNDING, 0, 1.0f);

            // —— Label text with drop-shadow ————————————————————————————
            float tx = pillX + FLAG_PAD;
            float ty = pillY + (FLAG_H - flagSizeBuf.y) / 2.0f;
            int shadow = ImColor.rgba(0, 0, 0, 200);
            drawList.addText(tx - 1, ty,     shadow,      badge.label);
            drawList.addText(tx + 1, ty,     shadow,      badge.label);
            drawList.addText(tx,     ty - 1, shadow,      badge.label);
            drawList.addText(tx,     ty + 1, shadow,      badge.label);
            drawList.addText(tx,     ty,     badge.color, badge.label);

            cursor = pillY - FLAG_GAP; // next badge sits above this one
        }
    }

    /** Immutable record holding the display text and colour for a single flag badge. */
    private static final class FlagEntry {
        final String label;
        final int    color; // ImColor.rgba packed int
        FlagEntry(String label, int color) { this.label = label; this.color = color; }
    }
}
