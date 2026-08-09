package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.projection.ScreenProjector;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.ModeSetting;
import me.venixpll.cheat.vischeck.TriangleCombined;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheck.RayHitResult;
import me.venixpll.cheat.vischeck.VisCheckAdapter;

import java.util.List;

/**
 * DistanceDebugModule - Visual distance & perspective obstacle debugging module.
 * <ul>
 *   <li>Renders a perspective-scaled square marker directly at the crosshair cursor.</li>
 *   <li>Scales the crosshair square dynamically with 3D distance to visually communicate perspective to the blocking vertex.</li>
 *   <li>Marks every visible player (teammates & enemies) when cursor is on them without obstacles.</li>
 *   <li>Displays distance to blocking obstacles and visible players directly under the crosshair.</li>
 * </ul>
 */
public class DistanceDebugModule extends CheatModule {

    // ── Settings ──────────────────────────────────────────────────────────────
    public final BooleanSetting targetAllPlayers = new BooleanSetting(
            "Target All Players (Teammates & Enemies)##distdbg", true);

    public final BooleanSetting showWallBox = new BooleanSetting(
            "Show Cursor Obstacle Marker##distdbg", true);

    public final BooleanSetting drawConnectLine = new BooleanSetting(
            "Connect Line to 3D Blocking Vertex##distdbg", true);

    public final FloatSetting maxWallDist = new FloatSetting(
            "Max Wall Range##distdbg", 8192f, 512f, 16384f);

    public final FloatSetting wallBoxSize = new FloatSetting(
            "Base Marker Size##distdbg", 14.0f, 4.0f, 32.0f);

    public final BooleanSetting perspectiveScaling = new BooleanSetting(
            "Enable Perspective Scaling##distdbg", true);

    public final BooleanSetting highlightPlayer = new BooleanSetting(
            "Highlight Target Player##distdbg", true);

    public final BooleanSetting showEnemyDistance = new BooleanSetting(
            "Show Distance under Crosshair##distdbg", true);

    public final ModeSetting distanceUnit = new ModeSetting(
            "Distance Unit##distdbg", 0, "Meters", "Units", "Both");

    public final ColorSetting wallBoxColor = new ColorSetting(
            "Obstacle Marker Color##distdbg", 0.0f, 0.85f, 1.0f, 0.95f);

    public final ColorSetting playerHighlightColor = new ColorSetting(
            "Player Highlight Color##distdbg", 0.2f, 0.95f, 0.35f, 1.0f);

    public final ColorSetting distanceTextColor = new ColorSetting(
            "Distance Text Color##distdbg", 1.0f, 1.0f, 1.0f, 1.0f);

    private final float[] scrHitBuf = new float[2];

    public DistanceDebugModule() {
        super("Distance Debug", ModuleCategory.DEBUG, MenuGroup.OTHER, false);
        addSetting(targetAllPlayers);
        addSetting(showWallBox);
        addSetting(drawConnectLine);
        addSetting(maxWallDist);
        addSetting(wallBoxSize);
        addSetting(perspectiveScaling);
        addSetting(highlightPlayer);
        addSetting(showEnemyDistance);
        addSetting(distanceUnit);
        addSetting(wallBoxColor);
        addSetting(playerHighlightColor);
        addSetting(distanceTextColor);
    }

    @Override
    public void onRender(ImDrawList drawList) {
        if (!isEnabled()) return;
        if (!PlayerCache.tracking) return;

        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (localPawn == 0) return;

        Vector3 localOrigin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
        if (localOrigin == null) return;

        Vector3 camera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);
        float[] matrix = PlayerCache.viewMatrix;
        int sw = PlayerCache.screenWidth;
        int sh = PlayerCache.screenHeight;
        float offX = ESPModule.espOffsetX;
        float offY = ESPModule.espOffsetY;

        float cx = sw / 2.0f + offX;
        float cy = sh / 2.0f + offY;

        VisCheck visCheck = VisCheckAdapter.getVisCheck();

        // 1. Mark nearest wall / obstacle at crosshair position with perspective square at cursor
        if (showWallBox.getValue() && visCheck != null) {
            renderCrosshairWallMarker(drawList, visCheck, localPawn, camera, matrix, sw, sh, cx, cy, offX, offY);
        }

        // 2. Check player targeting (every player, teammates and enemies)
        renderPlayerTargeting(drawList, visCheck, camera, matrix, sw, sh, cx, cy, offX, offY);
    }

    private void renderCrosshairWallMarker(ImDrawList dl, VisCheck vis, long localPawn, Vector3 camera,
                                           float[] matrix, int sw, int sh, float cx, float cy,
                                           float offX, float offY) {
        float pitch = CS2Memory.readFloat(localPawn + CS2Offsets.m_angEyeAngles);
        float yaw   = CS2Memory.readFloat(localPawn + CS2Offsets.m_angEyeAngles + 4);
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) return;

        double pitchRad = Math.toRadians(pitch);
        double yawRad   = Math.toRadians(yaw);
        float cosPitch  = (float) Math.cos(pitchRad);
        float normX     = (float) (cosPitch * Math.cos(yawRad));
        float normY     = (float) (cosPitch * Math.sin(yawRad));
        float normZ     = (float) -Math.sin(pitchRad);

        Vector3 normDir = new Vector3(normX, normY, normZ);
        RayHitResult hit = vis.castRayFree(camera, normDir, maxWallDist.getValue());

        if (hit != null && hit.blocked && hit.hitPoint != null) {
            drawPerspectiveObstacleMarker(dl, hit, cx, cy, camera, matrix, sw, sh, offX, offY, null);
        }
    }

    private void renderPlayerTargeting(ImDrawList dl, VisCheck visCheck, Vector3 camera,
                                      float[] matrix, int sw, int sh, float cx, float cy,
                                      float offX, float offY) {
        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
        if (players.isEmpty()) return;

        PlayerSnapshot bestTarget = null;
        float bestDistSq = Float.MAX_VALUE;
        float best3DDist = 0f;
        boolean bestIsVisible = false;
        RayHitResult bestBlockHit = null;

        for (PlayerSnapshot p : players) {
            if (p.isLocal || !p.onScreen) continue;

            // "Mark every visible players, not just enemies" -> check team filter setting
            boolean isEnemy = (p.team != ESPModule.localTeam);
            if (!targetAllPlayers.getValue() && !isEnemy) continue;

            float feetX = p.feetX + offX;
            float feetY = p.feetY + offY;
            float headY = p.headY + offY;

            if (!Float.isFinite(feetX) || !Float.isFinite(feetY) || !Float.isFinite(headY)) continue;

            float height = feetY - headY;
            float width  = height / 2.0f;
            float minX   = feetX - width / 2.0f;
            float maxX   = minX + width;
            float minY   = headY;
            float maxY   = feetY;

            // Expanded hit bounds (20% padding) for smooth crosshair acquisition
            float padX = width * 0.20f;
            float padY = height * 0.15f;

            boolean crosshairOnPlayer = (cx >= (minX - padX) && cx <= (maxX + padX)
                                      && cy >= (minY - padY) && cy <= (maxY + padY));

            if (!crosshairOnPlayer) continue;

            Vector3 headPos   = new Vector3(p.worldX, p.worldY, p.worldZ + 72.0f);
            Vector3 torsoPos  = new Vector3(p.worldX, p.worldY, p.worldZ + 55.0f);

            RayHitResult hitHead = (visCheck != null) ? visCheck.castRay(camera, headPos) : null;
            RayHitResult hitTorso = (visCheck != null) ? visCheck.castRay(camera, torsoPos) : null;

            boolean headBlocked = (hitHead != null && hitHead.blocked);
            boolean torsoBlocked = (hitTorso != null && hitTorso.blocked);
            boolean visible = !headBlocked || !torsoBlocked;

            float dx = (feetX - cx);
            float dy = ((headY + feetY) * 0.5f - cy);
            float screenDistSq = dx * dx + dy * dy;

            if (screenDistSq < bestDistSq) {
                bestDistSq = screenDistSq;
                bestTarget = p;
                bestIsVisible = visible;
                bestBlockHit = headBlocked ? hitHead : hitTorso;

                float worldDx = p.worldX - camera.x;
                float worldDy = p.worldY - camera.y;
                float worldDz = p.worldZ - camera.z;
                best3DDist = (float) Math.sqrt(worldDx * worldDx + worldDy * worldDy + worldDz * worldDz);
            }
        }

        if (bestTarget == null) return;

        // 1. If line-of-sight is blocked by an obstacle, render perspective marker at cursor & connect line to blocking vertex
        if (!bestIsVisible && bestBlockHit != null && bestBlockHit.blocked && showWallBox.getValue()) {
            drawPerspectiveObstacleMarker(dl, bestBlockHit, cx, cy, camera, matrix, sw, sh, offX, offY, bestTarget.name);
        }

        // 2. Highlight player when cursor is on them WITHOUT obstacles
        if (bestIsVisible && highlightPlayer.getValue()) {
            drawPlayerHighlight(dl, bestTarget, offX, offY);
        }

        // 3. Under the crosshair show the distance to the player when visible
        if (bestIsVisible && showEnemyDistance.getValue()) {
            drawCrosshairDistance(dl, cx, cy, best3DDist, bestTarget.name, bestTarget.team != ESPModule.localTeam);
        }
    }

    /**
     * Renders the square marker directly at the crosshair cursor position, scaled with 3D perspective distance
     * to the blocking vertex so you can visually gauge perspective & distance.
     * Optionally draws a thin connecting line to the exact projected 3D blocking vertex.
     */
    private void drawPerspectiveObstacleMarker(ImDrawList dl, RayHitResult hit, float cx, float cy,
                                               Vector3 camera, float[] matrix, int sw, int sh,
                                               float offX, float offY, String blockedTargetName) {
        if (hit == null || !hit.blocked || hit.hitPoint == null) return;

        // Locate closest vertex of the blocking triangle plane for precision
        Vector3 targetVertex = hit.hitPoint;
        if (hit.hitTriangle != null) {
            TriangleCombined tri = hit.hitTriangle;
            float d0 = tri.v0.distance(hit.hitPoint);
            float d1 = tri.v1.distance(hit.hitPoint);
            float d2 = tri.v2.distance(hit.hitPoint);

            if (d0 <= d1 && d0 <= d2) targetVertex = tri.v0;
            else if (d1 <= d0 && d1 <= d2) targetVertex = tri.v1;
            else targetVertex = tri.v2;
        }

        float distUnits = camera.distance(targetVertex);
        float baseSize = wallBoxSize.getValue();
        float scaledSize = baseSize;

        if (perspectiveScaling.getValue()) {
            // Perspective scaling formula: reference distance (350u) / distance
            float refDist = 350.0f;
            float scale = refDist / Math.max(60.0f, distUnits);
            scale = Math.clamp(scale, 0.35f, 2.5f);
            scaledSize = baseSize * scale;
        }

        float[] c = wallBoxColor.getValue();

        // 1. Render perspective square marker AT THE CURSOR POSITION (cx, cy)
        drawSquareMarkerAtPos(dl, cx, cy, scaledSize, c);

        // 2. Project the 3D blocking vertex to screen and draw connecting line if enabled
        if (drawConnectLine.getValue() && ScreenProjector.project(targetVertex, scrHitBuf, matrix, sw, sh)) {
            float vx = scrHitBuf[0] + offX;
            float vy = scrHitBuf[1] + offY;

            int lineCol = ImColor.rgba(c[0], c[1], c[2], c[3] * 0.65f);
            int shadowCol = ImColor.rgba(0, 0, 0, 0.70f);

            // Line from cursor square to exact 3D vertex position
            dl.addLine(cx - 1, cy - 1, vx - 1, vy - 1, shadowCol, 2.0f);
            dl.addLine(cx, cy, vx, vy, lineCol, 1.2f);

            // Small dot at exact 3D vertex position
            dl.addCircleFilled(vx, vy, 3.0f, lineCol, 8);
        }

        // 3. Render distance badge under the cursor square marker showing distance to blocking vertex
        float distMeters = distUnits * 0.0254f;
        String distStr;
        int unitSel = distanceUnit.getValue();
        if (unitSel == 0) {
            distStr = String.format("%.1fm", distMeters);
        } else if (unitSel == 1) {
            distStr = String.format("%.0fu", distUnits);
        } else {
            distStr = String.format("%.1fm (%.0fu)", distMeters, distUnits);
        }

        String label = (blockedTargetName != null && !blockedTargetName.isEmpty())
                ? "[Blocked: " + blockedTargetName + "] " + distStr
                : "Block: " + distStr;

        imgui.ImVec2 textSize = new imgui.ImVec2();
        ImGui.calcTextSize(textSize, label);

        float textX = cx - (textSize.x * 0.5f);
        float textY = cy + (scaledSize * 0.5f) + 6.0f;

        float padH = 5.0f;
        float padV = 2.0f;

        int textCol = ImColor.rgba(c[0], c[1], c[2], 1.0f);
        int bgCol = ImColor.rgba(0.04f, 0.05f, 0.08f, 0.85f);
        int borderCol = ImColor.rgba(c[0], c[1], c[2], 0.55f);

        dl.addRectFilled(textX - padH, textY - padV, textX + textSize.x + padH, textY + textSize.y + padV, bgCol, 3.0f);
        dl.addRect(textX - padH, textY - padV, textX + textSize.x + padH, textY + textSize.y + padV, borderCol, 3.0f, 0, 1.0f);
        dl.addText(textX + 1, textY + 1, ImColor.rgba(0, 0, 0, 220), label);
        dl.addText(textX, textY, textCol, label);
    }

    private void drawSquareMarkerAtPos(ImDrawList dl, float px, float py, float size, float[] color) {
        float halfSz = size * 0.5f;

        int boxCol = ImColor.rgba(color[0], color[1], color[2], color[3]);
        int shadowCol = ImColor.rgba(0, 0, 0, 0.85f);
        int bgFillCol = ImColor.rgba(color[0], color[1], color[2], color[3] * 0.25f);

        // Square box at specified screen position (cx, cy)
        dl.addRectFilled(px - halfSz, py - halfSz, px + halfSz, py + halfSz, bgFillCol, 2.0f);
        dl.addRect(px - halfSz - 1, py - halfSz - 1, px + halfSz + 1, py + halfSz + 1, shadowCol, 2.0f, 0, 2.0f);
        dl.addRect(px - halfSz, py - halfSz, px + halfSz, py + halfSz, boxCol, 2.0f, 0, 1.5f);
        dl.addCircleFilled(px, py, 2.0f, boxCol, 8);
    }

    private void drawPlayerHighlight(ImDrawList dl, PlayerSnapshot p, float offX, float offY) {
        float feetX = p.feetX + offX;
        float feetY = p.feetY + offY;
        float headY = p.headY + offY;
        float height = feetY - headY;
        float width  = height / 2.0f;
        float minX   = feetX - width / 2.0f;
        float minY   = headY;
        float maxX   = minX + width;
        float maxY   = feetY;

        float[] c = playerHighlightColor.getValue();
        int highlightCol = ImColor.rgba(c[0], c[1], c[2], c[3]);
        int fillCol = ImColor.rgba(c[0], c[1], c[2], 0.22f);
        int shadowCol = ImColor.rgba(0, 0, 0, 0.85f);

        // Highlight box with glow & fill around target player
        dl.addRectFilled(minX, minY, maxX, maxY, fillCol, 4.0f);
        dl.addRect(minX - 1, minY - 1, maxX + 1, maxY + 1, shadowCol, 4.0f, 0, 3.0f);
        dl.addRect(minX, minY, maxX, maxY, highlightCol, 4.0f, 0, 2.0f);

        // Corner accents
        float cornerLen = Math.min(width, height) * 0.25f;
        int cornerCol = ImColor.rgba(1.0f, 1.0f, 1.0f, 0.95f);
        // Top-left
        dl.addLine(minX, minY, minX + cornerLen, minY, cornerCol, 2.5f);
        dl.addLine(minX, minY, minX, minY + cornerLen, cornerCol, 2.5f);
        // Top-right
        dl.addLine(maxX, minY, maxX - cornerLen, minY, cornerCol, 2.5f);
        dl.addLine(maxX, minY, maxX, minY + cornerLen, cornerCol, 2.5f);
        // Bottom-left
        dl.addLine(minX, maxY, minX + cornerLen, maxY, cornerCol, 2.5f);
        dl.addLine(minX, maxY, minX, maxY - cornerLen, cornerCol, 2.5f);
        // Bottom-right
        dl.addLine(maxX, maxY, maxX - cornerLen, maxY, cornerCol, 2.5f);
        dl.addLine(maxX, maxY, maxX, maxY - cornerLen, cornerCol, 2.5f);
    }

    private void drawCrosshairDistance(ImDrawList dl, float cx, float cy, float distanceUnits,
                                       String targetName, boolean isEnemy) {
        float distanceMeters = distanceUnits * 0.0254f; // CS2 1 unit = 1 inch = 0.0254m

        String distText;
        int unitSel = distanceUnit.getValue();
        if (unitSel == 0) { // Meters
            distText = String.format("%.1fm", distanceMeters);
        } else if (unitSel == 1) { // Units
            distText = String.format("%.0fu", distanceUnits);
        } else { // Both
            distText = String.format("%.1fm (%.0fu)", distanceMeters, distanceUnits);
        }

        String teamPrefix = isEnemy ? "[Enemy] " : "[Team] ";
        String label = (targetName != null && !targetName.isEmpty())
                ? teamPrefix + targetName + " - " + distText
                : distText;

        imgui.ImVec2 textSize = new imgui.ImVec2();
        ImGui.calcTextSize(textSize, label);

        float textX = cx - (textSize.x * 0.5f);
        float textY = cy + 24.0f; // Positioned directly under crosshair

        float padH = 6.0f;
        float padV = 3.0f;

        float[] tc = distanceTextColor.getValue();
        int textCol = ImColor.rgba(tc[0], tc[1], tc[2], tc[3]);
        int bgCol = ImColor.rgba(0.06f, 0.07f, 0.10f, 0.85f);
        int borderCol = ImColor.rgba(tc[0], tc[1], tc[2], 0.60f);

        // Background card & text
        dl.addRectFilled(textX - padH, textY - padV, textX + textSize.x + padH, textY + textSize.y + padV, bgCol, 4.0f);
        dl.addRect(textX - padH, textY - padV, textX + textSize.x + padH, textY + textSize.y + padV, borderCol, 4.0f, 0, 1.0f);

        // Text shadow & foreground
        dl.addText(textX + 1, textY + 1, ImColor.rgba(0, 0, 0, 220), label);
        dl.addText(textX, textY, textCol, label);
    }
}
