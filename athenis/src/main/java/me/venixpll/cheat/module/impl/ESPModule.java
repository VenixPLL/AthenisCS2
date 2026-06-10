package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;

import java.util.List;

/**
 * ESP (Extra Sensory Perception) Module.
 * Responsible for rendering 2D bounding boxes, health status bars, and names on top of active players.
 */
public class ESPModule extends CheatModule {
    /** Toggle to show/hide player bounding boxes */
    public final BooleanSetting boxEsp = new BooleanSetting("Show Box Outline", true);
    /** Toggle to show/hide player health bars */
    public final BooleanSetting healthEsp = new BooleanSetting("Show Health Indicators", true);
    /** Toggle to show/hide player names */
    public final BooleanSetting nameEsp = new BooleanSetting("Show Player Names", true);
    /** Filter out teammate ESP options */
    public final BooleanSetting teamCheck = new BooleanSetting("Enemy-Only Team Filter", true);

    /** Color representation for rendering enemy players */
    public final ColorSetting enemyColor = new ColorSetting("Enemy Color", 1.0f, 0.2f, 0.2f, 1.0f);
    /** Color representation for rendering teammate players */
    public final ColorSetting teamColor = new ColorSetting("Team Color", 0.2f, 0.6f, 1.0f, 1.0f);

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
        addSetting(healthEsp);
        addSetting(nameEsp);
        addSetting(teamCheck);
        addSetting(enemyColor);
        addSetting(teamColor);
    }

    /**
     * Iterates through active players on-screen and draws bounding boxes, names, and health indicators.
     *
     * @param drawList The ImGui foreground draw list.
     */
    @Override
    public void onRender(ImDrawList drawList) {
        if (!PlayerCache.tracking) return;

        List<PlayerCache.PlayerData> currentPlayers = PlayerCache.players;

        for (PlayerCache.PlayerData player : currentPlayers) {
            // Do not draw box on local player or off-screen players
            if (player.isLocal || !player.onScreen)
                continue;

            boolean isEnemy = (player.team != localTeam);
            if (teamCheck.getValue() && !isEnemy)
                continue;

            // Select color based on relationship
            float[] enemyCol = enemyColor.getValue();
            float[] teamCol = teamColor.getValue();
            int colorInt = isEnemy ? ImColor.rgba(enemyCol[0], enemyCol[1], enemyCol[2], enemyCol[3])
                    : ImColor.rgba(teamCol[0], teamCol[1], teamCol[2], teamCol[3]);

            float height = player.feetY - player.headY;
            float width = height / 2.0f;
            float minX = player.feetX - width / 2.0f + espOffsetX;
            float minY = player.headY + espOffsetY;
            float maxX = minX + width;
            float maxY = player.feetY + espOffsetY;

            // Draw bounding box with high contrast black outlines
            if (boxEsp.getValue()) {
                drawList.addRect(minX - 1, minY - 1, maxX + 1, maxY + 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX + 1, minY + 1, maxX - 1, maxY - 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX, minY, maxX, maxY, colorInt, 0.0f, 0, 1.0f);
            }

            // Draw segmented health bar (dynamic green-to-red transition)
            if (healthEsp.getValue()) {
                float barMinX = minX - 6;
                // Black outline background
                drawList.addRectFilled(barMinX - 1, minY - 1, barMinX + 3, maxY + 1, ImColor.rgba(0, 0, 0, 180));

                float healthPercent = Math.max(0.0f, Math.min(1.0f, player.health / 100.0f));
                float healthHeight = height * healthPercent;
                float healthMinY = maxY - healthHeight;

                float r = 1.0f - healthPercent;
                float g = healthPercent;
                int barColor = ImColor.rgba(r, g, 0.0f, 1.0f);

                // Draw status bar
                drawList.addRectFilled(barMinX, healthMinY, barMinX + 2, maxY, barColor);
            }

            // Draw name indicator with drop shadow
            if (nameEsp.getValue()) {
                String text = player.name + " [" + player.health + "]";
                ImGui.calcTextSize(textSizeBuf, text);
                float textWidth = textSizeBuf.x;
                float textX = player.feetX - textWidth / 2.0f + espOffsetX;
                float textY = player.headY - 15 + espOffsetY;

                // Simple drop-shadow text outlines
                drawList.addText(textX - 1, textY - 1, ImColor.rgba(0, 0, 0, 255), text);
                drawList.addText(textX + 1, textY - 1, ImColor.rgba(0, 0, 0, 255), text);
                drawList.addText(textX - 1, textY + 1, ImColor.rgba(0, 0, 0, 255), text);
                drawList.addText(textX + 1, textY + 1, ImColor.rgba(0, 0, 0, 255), text);

                drawList.addText(textX, textY, ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f), text);
            }
        }
    }
}
