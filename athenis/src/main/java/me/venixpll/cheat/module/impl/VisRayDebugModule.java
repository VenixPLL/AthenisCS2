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
import me.venixpll.cheat.projection.ScreenProjector;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.ModeSetting;
import me.venixpll.cheat.vischeck.TriangleCombined;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheck.RayHitResult;
import me.venixpll.cheat.vischeck.VisCheck.DeletedTriangleInfo;
import me.venixpll.cheat.vischeck.VisCheckAdapter;

import java.util.List;

/**
 * VisRayDebugModule - visual overlay for VisCheck ray-cast debugging with
 * triangle deletion editing.
 */
public class VisRayDebugModule extends CheatModule {

    // ── Win32 key state ───────────────────────────────────────────────────────
    private interface User32 extends Library {
        User32 INSTANCE = Native.load("user32", User32.class);
        short GetAsyncKeyState(int vKey);
    }

    private static final int[] KEYS_VK =
        { 0x46, 0x47, 0x48, 0x58, 0x5A, 0x05, 0x06, 0x2D, 0x2E, 0x52, 0x53 };
    private static final String[] KEY_NAMES =
        { "F", "G", "H", "X", "Z", "Mouse4", "Mouse5", "Insert", "Delete", "R", "S" };

    // ── Settings ──────────────────────────────────────────────────────────────
    public final ModeSetting rayMode = new ModeSetting(
            "Ray Mode##visray", 0, "Enemy", "Crosshair");
    public final FloatSetting crosshairDist = new FloatSetting(
            "Crosshair Range##visray", 4096f, 64f, 16384f);
    public final BooleanSetting showRay = new BooleanSetting(
            "Show Ray Line##visray", true);
    public final BooleanSetting showTriangle = new BooleanSetting(
            "Show Hit Triangle##visray", true);
    public final BooleanSetting showHitPoint = new BooleanSetting(
            "Show Hit Point##visray", true);
    public final BooleanSetting showAllDeleted = new BooleanSetting(
            "Show All Deleted##visray", true);
    public final BooleanSetting showInfoPanel = new BooleanSetting(
            "Show Info Panel##visray", true);
    public final BooleanSetting nearestOnly = new BooleanSetting(
            "Nearest Enemy Only##visray", true);
    public final ModeSetting deleteKey = new ModeSetting(
            "Delete Bind##visray", 0, KEY_NAMES);
    public final ModeSetting saveKey = new ModeSetting(
            "Save Bind##visray", 10, KEY_NAMES); // Default index 10: "S"
    public final ModeSetting restoreKey = new ModeSetting(
            "Restore Bind##visray", 9, KEY_NAMES); // Default index 9: "R"

    // ── Reusable projection scratch buffers ───────────────────────────────────
    private final float[] scrA      = new float[2];
    private final float[] scrB      = new float[2];
    private final float[] scrC      = new float[2];
    private final float[] scrHP     = new float[2];
    private final float[] scrOrigin = new float[2];

    // ── Edge detectors for key binds ──────────────────────────────────────────
    private boolean deleteKeyWasDown = false;
    private boolean saveKeyWasDown = false;
    private boolean restoreKeyWasDown = false;

    // ── Last save path for display in HUD ─────────────────────────────────────
    private volatile String lastSaveMsg = "";
    private volatile long   lastSaveMsgTime = 0L;

    public VisRayDebugModule() {
        super("VisRay Debug", ModuleCategory.EXTERNAL, false);
        addSetting(rayMode);
        addSetting(crosshairDist);
        addSetting(showRay);
        addSetting(showTriangle);
        addSetting(showHitPoint);
        addSetting(showAllDeleted);
        addSetting(showInfoPanel);
        addSetting(nearestOnly);
        addSetting(deleteKey);
        addSetting(saveKey);
        addSetting(restoreKey);
    }

    @Override
    public void onRender(ImDrawList drawList) {
        if (!isEnabled()) return;
        if (!PlayerCache.tracking) return;

        VisCheck vis = VisCheckAdapter.getVisCheck();
        if (vis == null) {
            renderNoGeometryHUD(drawList);
            return;
        }

        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (localPawn == 0) return;

        Vector3 localOrigin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
        if (localOrigin == null) return;

        Vector3 camera = new Vector3(localOrigin.x, localOrigin.y, localOrigin.z + 64.0f);

        float[] matrix = PlayerCache.viewMatrix;
        int sw = PlayerCache.screenWidth;
        int sh = PlayerCache.screenHeight;

        RayHitResult hit;

        if (rayMode.getValue() == 1) {
            hit = castCrosshairRay(vis, localPawn, camera);
        } else {
            hit = castEnemyRay(vis, camera, localOrigin, matrix, sw, sh);
        }

        if (hit == null) return;

        // ── Delete key logic ──────────────────────────────────────────────────
        handleDeleteKey(vis, hit);

        // ── Render ────────────────────────────────────────────────────────────
        float offX = ESPModule.espOffsetX;
        float offY = ESPModule.espOffsetY;

        Vector3 rayEnd = (hit.blocked && hit.hitPoint != null)
                ? hit.hitPoint
                : null; // fallback to screen center if not blocked

        if (showRay.getValue())      drawRay(drawList, camera, rayEnd, hit, matrix, sw, sh, offX, offY);
        if (showHitPoint.getValue()) drawHitPoint(drawList, hit, matrix, sw, sh, offX, offY);
        if (showTriangle.getValue()) drawTriangle(drawList, hit, vis, matrix, sw, sh, offX, offY);
        if (showAllDeleted.getValue()) drawAllDeletedTriangles(drawList, vis, matrix, sw, sh, offX, offY);
        if (showInfoPanel.getValue()) drawInfoPanel(drawList, hit, camera, vis, offX, offY);
    }

    // ── Ray casting helpers ───────────────────────────────────────────────────

    private RayHitResult castCrosshairRay(VisCheck vis, long localPawn, Vector3 camera) {
        float pitch = CS2Memory.readFloat(localPawn + CS2Offsets.m_angEyeAngles);
        float yaw   = CS2Memory.readFloat(localPawn + CS2Offsets.m_angEyeAngles + 4);
        if (!Float.isFinite(pitch) || !Float.isFinite(yaw)) return null;

        double pitchRad = Math.toRadians(pitch);
        double yawRad   = Math.toRadians(yaw);
        float cosPitch  = (float) Math.cos(pitchRad);
        float normX     = (float) (cosPitch * Math.cos(yawRad));
        float normY     = (float) (cosPitch * Math.sin(yawRad));
        float normZ     = (float) -Math.sin(pitchRad);

        Vector3 normDir = new Vector3(normX, normY, normZ);
        return vis.castRayFree(camera, normDir, crosshairDist.getValue());
    }

    private RayHitResult castEnemyRay(VisCheck vis, Vector3 camera, Vector3 localOrigin,
                                      float[] matrix, int sw, int sh) {
        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
        if (players.isEmpty()) return null;

        if (nearestOnly.getValue()) {
            PlayerSnapshot nearest = null;
            float nearestDistSq = Float.MAX_VALUE;
            for (PlayerSnapshot p : players) {
                if (p.isLocal || !p.onScreen) continue;
                if (p.team == ESPModule.localTeam) continue;
                float dx = p.worldX - localOrigin.x;
                float dy = p.worldY - localOrigin.y;
                float dz = p.worldZ - localOrigin.z;
                float d  = dx*dx + dy*dy + dz*dz;
                if (d < nearestDistSq) { nearestDistSq = d; nearest = p; }
            }
            if (nearest == null) return null;
            Vector3 head = new Vector3(nearest.worldX, nearest.worldY, nearest.worldZ + 72.0f);
            return vis.castRay(camera, head);
        } else {
            for (PlayerSnapshot p : players) {
                if (p.isLocal || !p.onScreen) continue;
                if (p.team == ESPModule.localTeam) continue;
                Vector3 head = new Vector3(p.worldX, p.worldY, p.worldZ + 72.0f);
                return vis.castRay(camera, head);
            }
            return null;
        }
    }

    // ── Bind key handling ─────────────────────────────────────────────────────

    private void handleDeleteKey(VisCheck vis, RayHitResult hit) {
        int vk = KEYS_VK[deleteKey.getValue()];
        boolean isDown = (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;

        if (isDown && !deleteKeyWasDown) {
            if (hit.blocked && hit.hitMeshIndex >= 0 && hit.hitTriangleIndex >= 0) {
                boolean added = vis.deleteTriangle(hit.hitMeshIndex, hit.hitTriangleIndex);
                if (added) {
                    System.out.printf("[VisRayDebug] Deleted triangle mesh=%d tri=%d%n",
                            hit.hitMeshIndex, hit.hitTriangleIndex);
                }
            }
        }
        deleteKeyWasDown = isDown;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    private void drawRay(ImDrawList dl, Vector3 camera, Vector3 rayEnd,
                         RayHitResult hit, float[] matrix, int sw, int sh,
                         float offX, float offY) {
        boolean camOk = ScreenProjector.project(camera, scrOrigin, matrix, sw, sh);
        if (!camOk) return;

        float x1 = scrOrigin[0] + offX, y1 = scrOrigin[1] + offY;

        if (rayEnd != null) {
            boolean endOk = ScreenProjector.project(rayEnd, scrHP, matrix, sw, sh);
            if (endOk) {
                float x2 = scrHP[0] + offX, y2 = scrHP[1] + offY;
                int rayColor = ImColor.rgba(1.0f, 0.55f, 0.0f, 0.85f); // orange = blocked
                dl.addLine(x1-1, y1-1, x2-1, y2-1, ImColor.rgba(0, 0, 0, 130), 3.0f);
                dl.addLine(x1, y1, x2, y2, rayColor, 1.8f);
            }
        } else {
            int cx = sw / 2;
            int cy = sh / 2;
            int rayColor = ImColor.rgba(0.15f, 1.0f, 0.35f, 0.85f);
            dl.addLine(x1-1, y1-1, cx, cy, ImColor.rgba(0, 0, 0, 130), 3.0f);
            dl.addLine(x1, y1, cx + offX, cy + offY, rayColor, 1.8f);
        }
    }

    private void drawHitPoint(ImDrawList dl, RayHitResult hit,
                              float[] matrix, int sw, int sh, float offX, float offY) {
        if (!hit.blocked || hit.hitPoint == null) return;
        if (!ScreenProjector.project(hit.hitPoint, scrHP, matrix, sw, sh)) return;

        float hx = scrHP[0] + offX;
        float hy = scrHP[1] + offY;
        float sz = 7.0f;

        dl.addCircle(hx, hy, sz + 4, ImColor.rgba(1.0f, 0.15f, 0.15f, 0.30f), 16, 6.0f);
        dl.addCircle(hx, hy, sz + 2, ImColor.rgba(1.0f, 0.15f, 0.15f, 0.55f), 16, 3.0f);

        int hitCol = ImColor.rgba(1.0f, 0.15f, 0.15f, 1.0f);
        dl.addLine(hx-sz-1, hy-sz-1, hx+sz+1, hy+sz+1, ImColor.rgba(0,0,0,200), 3.5f);
        dl.addLine(hx+sz+1, hy-sz-1, hx-sz-1, hy+sz+1, ImColor.rgba(0,0,0,200), 3.5f);
        dl.addLine(hx-sz, hy-sz, hx+sz, hy+sz, hitCol, 2.2f);
        dl.addLine(hx+sz, hy-sz, hx-sz, hy+sz, hitCol, 2.2f);
        dl.addCircleFilled(hx, hy, 3.0f, ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f), 8);
    }

    private void drawTriangle(ImDrawList dl, RayHitResult hit, VisCheck vis,
                              float[] matrix, int sw, int sh, float offX, float offY) {
        if (!hit.blocked || hit.hitTriangle == null) return;

        boolean isDeleted = (hit.hitMeshIndex >= 0 && hit.hitTriangleIndex >= 0)
                && vis.isDeleted(hit.hitMeshIndex, hit.hitTriangleIndex);

        TriangleCombined tri = hit.hitTriangle;
        boolean v0ok = ScreenProjector.project(tri.v0, scrA, matrix, sw, sh);
        boolean v1ok = ScreenProjector.project(tri.v1, scrB, matrix, sw, sh);
        boolean v2ok = ScreenProjector.project(tri.v2, scrC, matrix, sw, sh);

        int edgeCol;
        int fillCol;
        if (isDeleted) {
            edgeCol = ImColor.rgba(1.0f, 0.15f, 0.15f, 0.95f);
            fillCol = ImColor.rgba(1.0f, 0.0f, 0.0f, 0.20f);
        } else {
            edgeCol = ImColor.rgba(0.0f, 0.95f, 1.0f, 0.92f);
            fillCol = ImColor.rgba(0.0f, 0.90f, 1.0f, 0.13f);
        }
        int edgeShadow = ImColor.rgba(0, 0, 0, 180);

        if (v0ok && v1ok && v2ok) {
            float ax = scrA[0]+offX, ay = scrA[1]+offY;
            float bx = scrB[0]+offX, by = scrB[1]+offY;
            float cx = scrC[0]+offX, cy = scrC[1]+offY;

            dl.addTriangleFilled(ax, ay, bx, by, cx, cy, fillCol);
            dl.addLine(ax-1, ay-1, bx-1, by-1, edgeShadow, 2.5f);
            dl.addLine(bx-1, by-1, cx-1, cy-1, edgeShadow, 2.5f);
            dl.addLine(cx-1, cy-1, ax-1, ay-1, edgeShadow, 2.5f);
            dl.addLine(ax, ay, bx, by, edgeCol, 1.8f);
            dl.addLine(bx, by, cx, cy, edgeCol, 1.8f);
            dl.addLine(cx, cy, ax, ay, edgeCol, 1.8f);
            dl.addCircleFilled(ax, ay, 3.5f, edgeCol, 8);
            dl.addCircleFilled(bx, by, 3.5f, edgeCol, 8);
            dl.addCircleFilled(cx, cy, 3.5f, edgeCol, 8);
        } else {
            if (v0ok && v1ok) dl.addLine(scrA[0]+offX, scrA[1]+offY, scrB[0]+offX, scrB[1]+offY, edgeCol, 1.8f);
            if (v1ok && v2ok) dl.addLine(scrB[0]+offX, scrB[1]+offY, scrC[0]+offX, scrC[1]+offY, edgeCol, 1.8f);
            if (v0ok && v2ok) dl.addLine(scrA[0]+offX, scrA[1]+offY, scrC[0]+offX, scrC[1]+offY, edgeCol, 1.8f);
        }
    }

    // ── Info HUD panel ────────────────────────────────────────────────────────

    private void drawInfoPanel(ImDrawList dl, RayHitResult hit,
                               Vector3 camera, VisCheck vis,
                               float offX, float offY) {
        float px = 12.0f + offX;
        float py = PlayerCache.screenHeight * 0.33f + offY;

        String mapName  = vis.mapName != null ? vis.mapName : "";
        long   casts    = VisCheck.totalRayCasts.get();
        long   blocked  = VisCheck.totalBlocked.get();
        float  blockPct = casts > 0 ? (blocked * 100.0f / casts) : 0f;
        int    deleted  = vis.deletedTriangles.size();
        String mode     = rayMode.getValue() == 1 ? "Crosshair" : "Enemy";

        String delKeyName = KEY_NAMES[deleteKey.getValue()];
        String saveKeyName = KEY_NAMES[saveKey.getValue()];
        String restoreKeyName = KEY_NAMES[restoreKey.getValue()];

        boolean triIsDeleted = hit.blocked && hit.hitMeshIndex >= 0 && hit.hitTriangleIndex >= 0
                && vis.isDeleted(hit.hitMeshIndex, hit.hitTriangleIndex);

        String[] lines = {
            "[ VisRay Debug ]  Mode: " + mode,
            "Map : " + (mapName.isEmpty() ? "(none)" : mapName),
            String.format("Cam : (%.0f, %.0f, %.0f)", camera.x, camera.y, camera.z),
            String.format("Ray : %.1f units", hit.rayDistance),
            hit.blocked
                ? String.format("BLOCKED  wall %.1f u  mesh=%d  tri=%d%s",
                    hit.hitDistance, hit.hitMeshIndex, hit.hitTriangleIndex,
                    triIsDeleted ? "  [DELETED]" : "")
                : "VISIBLE  -- no geometry hit",
            (hit.blocked && hit.hitPoint != null)
                ? String.format("Hit : (%.0f, %.0f, %.0f)",
                    hit.hitPoint.x, hit.hitPoint.y, hit.hitPoint.z)
                : null,
            (hit.hitTriangle != null)
                ? String.format("v0  : (%.1f, %.1f, %.1f)",
                    hit.hitTriangle.v0.x, hit.hitTriangle.v0.y, hit.hitTriangle.v0.z)
                : null,
            (hit.hitTriangle != null)
                ? String.format("v1  : (%.1f, %.1f, %.1f)",
                    hit.hitTriangle.v1.x, hit.hitTriangle.v1.y, hit.hitTriangle.v1.z)
                : null,
            (hit.hitTriangle != null)
                ? String.format("v2  : (%.1f, %.1f, %.1f)",
                    hit.hitTriangle.v2.x, hit.hitTriangle.v2.y, hit.hitTriangle.v2.z)
                : null,
            String.format("Deleted: %d  [%s=del] [%s=restore] [%s=save]",
                    deleted, delKeyName, restoreKeyName, saveKeyName),
            lastSaveMsg.isEmpty() || (System.currentTimeMillis() - lastSaveMsgTime > 4000L)
                ? String.format("Stats : %d casts | %.1f%% blocked", casts, blockPct)
                : lastSaveMsg,
        };

        float lineH  = 14.0f;
        int   count  = 0;
        for (String l : lines) if (l != null) count++;

        float panelW = 390.0f;
        float panelH = count * lineH + 10.0f;

        dl.addRectFilled(px-5, py-5, px+panelW, py+panelH,
                ImColor.rgba(0.04f, 0.04f, 0.08f, 0.87f), 6.0f);
        dl.addRect(px-5, py-5, px+panelW, py+panelH,
                ImColor.rgba(0.0f, 0.85f, 1.0f, 0.52f), 6.0f, 0, 1.2f);

        float cy = py;
        for (String line : lines) {
            if (line == null) continue;
            int col;
            if (line.startsWith("[ VisRay"))      col = ImColor.rgba(0.0f, 0.95f, 1.0f, 1.0f);
            else if (line.startsWith("BLOCKED"))   col = ImColor.rgba(1.0f, 0.28f, 0.18f, 1.0f);
            else if (line.startsWith("VISIBLE"))   col = ImColor.rgba(0.25f, 1.0f, 0.45f, 1.0f);
            else if (line.startsWith("Map"))       col = ImColor.rgba(0.75f, 0.75f, 0.75f, 0.9f);
            else if (line.startsWith("Deleted"))   col = ImColor.rgba(1.0f, 0.60f, 0.20f, 1.0f);
            else if (line.startsWith("Stats") || line.startsWith("Saved") || line.startsWith("Restored"))
                                                   col = ImColor.rgba(0.85f, 0.85f, 0.50f, 0.9f);
            else                                   col = ImColor.rgba(1.0f, 1.0f, 1.0f, 0.92f);

            dl.addText(px+1, cy+1, ImColor.rgba(0, 0, 0, 210), line);
            dl.addText(px, cy, col, line);
            cy += lineH;
        }

        handlePanelKeys(vis, mapName);
    }

    // ── Panel keyboard shortcuts ──────────────────────────────────────────────

    private void handlePanelKeys(VisCheck vis, String mapName) {
        // Restore key bind check
        int restoreVk = KEYS_VK[restoreKey.getValue()];
        boolean restoreDown = (User32.INSTANCE.GetAsyncKeyState(restoreVk) & 0x8000) != 0;
        if (restoreDown && !restoreKeyWasDown) {
            vis.restoreAll();
            showSaveMsg("Restored all deletions");
        }
        restoreKeyWasDown = restoreDown;

        // Save key bind check
        int saveVk = KEYS_VK[saveKey.getValue()];
        boolean saveDown = (User32.INSTANCE.GetAsyncKeyState(saveVk) & 0x8000) != 0;
        if (saveDown && !saveKeyWasDown) {
            if (!mapName.isEmpty()) {
                String savePath = VisCheck.getSavePath(mapName);
                vis.saveDeletedToFile(savePath, mapName);
                showSaveMsg("Saved " + vis.deletedTriangles.size() + " deletions");
            } else {
                showSaveMsg("No map loaded - cannot save");
            }
        }
        saveKeyWasDown = saveDown;
    }

    private void showSaveMsg(String msg) {
        lastSaveMsg    = msg;
        lastSaveMsgTime = System.currentTimeMillis();
    }

    // ── Fallback HUD ─────────────────────────────────────────────────────────

    private void renderNoGeometryHUD(ImDrawList dl) {
        if (!showInfoPanel.getValue()) return;
        float px = 12.0f + ESPModule.espOffsetX;
        float py = PlayerCache.screenHeight * 0.33f + ESPModule.espOffsetY;
        dl.addRectFilled(px-5, py-5, px+310, py+32,
                ImColor.rgba(0.04f, 0.04f, 0.08f, 0.87f), 6.0f);
        dl.addRect(px-5, py-5, px+310, py+32,
                ImColor.rgba(1.0f, 0.45f, 0.1f, 0.55f), 6.0f, 0, 1.0f);
        dl.addText(px+1, py+1, ImColor.rgba(0,0,0,200), "[ VisRay Debug ]  no geometry loaded");
        dl.addText(px, py, ImColor.rgba(0.0f, 0.95f, 1.0f, 1.0f), "[ VisRay Debug ]  no geometry loaded");
        dl.addText(px, py+14, ImColor.rgba(0.8f, 0.5f, 0.5f, 0.9f), "Load a map with a .opt file to enable");
    }

    // ── Render all deleted triangles ──────────────────────────────────────────

    private void drawAllDeletedTriangles(ImDrawList dl, VisCheck vis, float[] matrix,
                                         int sw, int sh, float offX, float offY) {
        List<DeletedTriangleInfo> deletedList = vis.getDeletedTriangles();
        if (deletedList.isEmpty()) return;

        int edgeCol = ImColor.rgba(1.0f, 0.15f, 0.15f, 0.80f); // red edges
        int fillCol = ImColor.rgba(1.0f, 0.0f, 0.0f, 0.15f);  // semi-transparent red fill
        int shadow  = ImColor.rgba(0, 0, 0, 150);

        for (DeletedTriangleInfo info : deletedList) {
            TriangleCombined tri = info.triangle;
            boolean v0ok = ScreenProjector.project(tri.v0, scrA, matrix, sw, sh);
            boolean v1ok = ScreenProjector.project(tri.v1, scrB, matrix, sw, sh);
            boolean v2ok = ScreenProjector.project(tri.v2, scrC, matrix, sw, sh);

            if (v0ok && v1ok && v2ok) {
                float ax = scrA[0] + offX, ay = scrA[1] + offY;
                float bx = scrB[0] + offX, by = scrB[1] + offY;
                float cx = scrC[0] + offX, cy = scrC[1] + offY;

                // Draw filled triangle
                dl.addTriangleFilled(ax, ay, bx, by, cx, cy, fillCol);

                // Draw outlines with shadow
                dl.addLine(ax - 1, ay - 1, bx - 1, by - 1, shadow, 2.0f);
                dl.addLine(bx - 1, by - 1, cx - 1, cy - 1, shadow, 2.0f);
                dl.addLine(cx - 1, cy - 1, ax - 1, ay - 1, shadow, 2.0f);

                dl.addLine(ax, ay, bx, by, edgeCol, 1.2f);
                dl.addLine(bx, by, cx, cy, edgeCol, 1.2f);
                dl.addLine(cx, cy, ax, ay, edgeCol, 1.2f);
            }
        }
    }
}