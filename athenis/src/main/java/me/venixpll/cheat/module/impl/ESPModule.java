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
import me.venixpll.cheat.reader.PositionReader;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;
import me.venixpll.cheat.setting.FloatSetting;

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
        super("ESP Overlay", true);
        addSetting(boxEsp);
        addSetting(skeletonEsp);
        addSetting(healthEsp);
        addSetting(nameEsp);
        addSetting(teamCheck);
        addSetting(extrapolationBias);
        addSetting(enemyColor);
        addSetting(teamColor);
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
                // Vibrant Yellow for visible players
                colorInt = ImColor.rgba(1.0f, 0.92f, 0.016f, 1.0f);
            } else {
                colorInt = isEnemy
                        ? ImColor.rgba(enemyCol[0], enemyCol[1], enemyCol[2], enemyCol[3])
                        : ImColor.rgba(teamCol[0],  teamCol[1],  teamCol[2],  teamCol[3]);
            }

            // ── Bounding box with high-contrast outlines ─────────────────────
            if (boxEsp.getValue()) {
                drawList.addRect(minX - 1, minY - 1, maxX + 1, maxY + 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX + 1, minY + 1, maxX - 1, maxY - 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX,     minY,     maxX,     maxY,     colorInt,                   0.0f, 0, 1.0f);
            }

            // ── Skeleton (BoneESP) ───────────────────────────────────────────
            if (skeletonEsp.getValue() && player.boneX.length > 0) {
                for (int[] connection : BONE_CONNECTIONS) {
                    int bone1 = connection[0];
                    int bone2 = connection[1];

                    if (bone1 < player.boneX.length && bone2 < player.boneX.length) {
                        if (player.boneVisible[bone1] && player.boneVisible[bone2]) {
                            float x1 = player.boneX[bone1] + espOffsetX;
                            float y1 = player.boneY[bone1] + espOffsetY;
                            float x2 = player.boneX[bone2] + espOffsetX;
                            float y2 = player.boneY[bone2] + espOffsetY;

                            // Draw high-contrast outlines
                            drawList.addLine(x1 - 1, y1 - 1, x2 - 1, y2 - 1, ImColor.rgba(0, 0, 0, 120), 1.5f);
                            drawList.addLine(x1 + 1, y1 + 1, x2 + 1, y2 + 1, ImColor.rgba(0, 0, 0, 120), 1.5f);
                            drawList.addLine(x1,     y1,     x2,     y2,     colorInt,                   1.5f);
                        }
                    }
                }

                // Draw head circle if HEAD bone is visible (index 7)
                if (7 < player.boneX.length && player.boneVisible[7]) {
                    float headCX = player.boneX[7] + espOffsetX;
                    float headCY = player.boneY[7] + espOffsetY;
                    float headRadius = Math.max(3.0f, Math.min(12.0f, height / 12.0f));

                    // High-contrast outlines for the head circle
                    drawList.addCircle(headCX, headCY, headRadius - 0.5f, ImColor.rgba(0, 0, 0, 150), 16, 1.5f);
                    drawList.addCircle(headCX, headCY, headRadius + 0.5f, ImColor.rgba(0, 0, 0, 150), 16, 1.5f);
                    drawList.addCircle(headCX, headCY, headRadius,        colorInt,                   16, 1.5f);
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
        }
    }
}
