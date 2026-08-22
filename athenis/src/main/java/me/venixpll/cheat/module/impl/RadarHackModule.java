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
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import java.util.ArrayList;
import me.venixpll.overlay.OverlayWindow;
import me.venixpll.cheat.vischeck.VisCheckAdapter;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.util.List;

/**
 * Radar Hack module — makes all enemies permanently visible on the CS2 minimap
 * and optionally draws a standalone ImGui overlay radar.
 *
 * <h3>Two complementary mechanisms</h3>
 * <ol>
 * <li><b>In-game radar patch</b> — on every slow-loop tick ({@code ~10 Hz})
 * writes {@code 1} to {@code EntitySpottedState_t::m_bSpotted} inside each
 * enemy pawn.
 * This makes the CS2 engine believe an ally has spotted that player, so the
 * game
 * itself renders the enemy dot on its own minimap. No visible artefacts, no
 * overlay needed for this part. Uses writeByte to prevent crashes.</li>
 * <li><b>ImGui overlay mini-radar</b> — drawn as a square overlay positioned in
 * the top-left corner to overlap perfectly with the game's built-in radar.
 * Shows all tracked enemies as dots on a rotated minimap centered on the local
 * player.
 * Yaw-rotated so the player's forward direction always points up.</li>
 * </ol>
 */
public class RadarHackModule extends CheatModule {

        /**
         * When enabled, only enemies are force-spotted; teammates are left unchanged.
         */
        public final BooleanSetting enemyOnly = new BooleanSetting("Enemy Only", true);

        /** Draw the custom ImGui minimap overlay on-screen. */
        public final BooleanSetting showOverlay = new BooleanSetting("Overlay Mini-Radar", true);

        /** Toggleable widget for showing enemy team money balance, current held weapon and health. */
        public final BooleanSetting showEnemyWidget = new BooleanSetting("Enemy Status Widget", true);

        /**
         * Toggle to rotate the radar map matching the player's view yaw orientation.
         */
        public final BooleanSetting rotateRadar = new BooleanSetting("Rotate Radar Map", true);

        /** Radar X Position offset (default top-left to align with CS2 radar). */
        public final FloatSetting radarX = new FloatSetting("Radar X Pos", 20.0f, 0.0f, 1920.0f);

        /** Radar Y Position offset (default top-left to align with CS2 radar). */
        public final FloatSetting radarY = new FloatSetting("Radar Y Pos", 20.0f, 0.0f, 1080.0f);

        /** Radar size (width/height of the square overlay). */
        public final FloatSetting radarSize = new FloatSetting("Radar Size", 200.0f, 50.0f, 500.0f);

        /**
         * Radar coverage in world units per radar pixel. For mini-radar use 5–50; for
         * Square Radar use 30–200+.
         */
        public final FloatSetting radarScale = new FloatSetting("Radar Scale (Zoom)", 20.0f, 1.0f, 60.0f);

        /**
         * Enables "Square Radar" mode — mirrors the CS2 in-game square full-map radar.
         * In this mode the overlay shows all players at their absolute world positions
         * relative to a configurable map centre, without any rotation.
         * The local player is drawn as a white arrow at their actual position.
         */
        public final BooleanSetting squareRadar = new BooleanSetting("Square Radar (Full Map)", false);

        /**
         * Automatically detect the current map and use official overview coordinates
         * for pixel-perfect square radar alignment.
         */
        public final BooleanSetting autoMapRadar = new BooleanSetting("Auto-Sync Map Radar", true);

        /**
         * World X coordinate that maps to the centre of the radar overlay.
         * Adjust per map to align with the CS2 in-game square radar centre.
         * Typical CS2 maps range roughly from -3000 to +3000 Hammer units on each axis.
         */
        public final FloatSetting mapCenterX = new FloatSetting("Map Center X", 0.0f, -5384.0f, 5384.0f);

        /**
         * World Y coordinate that maps to the centre of the radar overlay.
         * Adjust per map to align with the CS2 in-game square radar centre.
         */
        public final FloatSetting mapCenterY = new FloatSetting("Map Center Y", 0.0f, -5384.0f, 5384.0f);

        // ── Map Overview Data Structures ─────────────────────────────────────────
        public static class MapOverviewData {
                public final float posX;
                public final float posY;
                public final float scale;

                public MapOverviewData(float posX, float posY, float scale) {
                        this.posX = posX;
                        this.posY = posY;
                        this.scale = scale;
                }
        }

        private static final Map<String, MapOverviewData> MAPS = new java.util.HashMap<>();
        static {
                try (InputStream stream = RadarHackModule.class.getResourceAsStream("/data/radar_offsets.json")) {
                        if (stream != null) {
                                try (InputStreamReader reader = new InputStreamReader(stream,
                                                java.nio.charset.StandardCharsets.UTF_8)) {
                                        Gson gson = new Gson();
                                        Type type = new TypeToken<java.util.Map<String, MapOverviewData>>() {
                                        }.getType();
                                        Map<String, MapOverviewData> loaded = gson.fromJson(reader, type);
                                        if (loaded != null) {
                                                MAPS.putAll(loaded);
                                                System.out.println("[RadarHackModule] Loaded " + MAPS.size()
                                                                + " map overview data entries from radar_offsets.json");
                                        }
                                }
                        } else {
                                System.err.println(
                                                "[RadarHackModule] Could not find /data/radar_offsets.json in resources!");
                        }
                } catch (Exception e) {
                        System.err.println("[RadarHackModule] Failed to load map overview data from JSON: "
                                        + e.getMessage());
                        e.printStackTrace();
                }
        }

        private String lastMapName = "";
        private volatile MapOverviewData currentMapData = null;
        private boolean mapDetectionLogged = false;
        private long detectedOffset = -1;
        private boolean detectedIsDirect = false;

        // ── Cached values for the renderer ───────────────────────────────────────
        // Written by the slow onTick() thread, read by the render thread.
        // Declared volatile so the render thread always sees the latest values
        // without requiring a lock.

        /** Local player X world coordinate, updated every slow tick. */
        private volatile float localX = 0f;

        /** Local player Y world coordinate, updated every slow tick. */
        private volatile float localY = 0f;

        /** Local player Z world coordinate, updated every slow tick. */
        @SuppressWarnings("unused")
        private volatile float localZ = 0f;

        /** Local player view yaw in degrees, updated every slow tick. */
        private volatile float localYaw = 0f;

        // ── Drag-and-resize state (render thread only) ────────────────────────────
        // These fields track the current drag interaction for the move/resize handles.
        // They are only ever touched from onRender(), so no volatile/lock needed.

        /** Sentinel value: no drag interaction is active. */
        private static final int DRAG_NONE = 0;
        /** User is dragging the radar body to reposition it. */
        private static final int DRAG_MOVE = 1;
        /** User is dragging the bottom-right grip to resize the radar. */
        private static final int DRAG_RESIZE = 2;

        /**
         * Current active drag mode (one of {@link #DRAG_NONE}, {@link #DRAG_MOVE},
         * {@link #DRAG_RESIZE}).
         */
        private int dragMode = DRAG_NONE;

        /** Radar X value at the moment the current drag began. */
        private float dragStartX;
        /** Radar Y value at the moment the current drag began. */
        private float dragStartY;
        /** Radar size at the moment the current drag began. */
        private float dragStartSize;
        /** Mouse X position at the moment the current drag began. */
        private float dragOriginMouseX;
        /** Mouse Y position at the moment the current drag began. */
        private float dragOriginMouseY;

        // ── Resize grip constant ──────────────────────────────────────────────────
        /** Side length in pixels of the bottom-right resize grip square. */
        private static final float GRIP_SIZE = 14f;

        // ─────────────────────────────────────────────────────────────────────────

        public RadarHackModule() {
                super("Radar Hack", ModuleCategory.EXTERNAL, MenuGroup.VISUALS, false);
                addSetting(enemyOnly);
                addSetting(showOverlay);
                addSetting(showEnemyWidget);
                addSetting(rotateRadar);
                addSetting(radarX);
                addSetting(radarY);
                addSetting(radarSize);
                addSetting(radarScale);
                addSetting(squareRadar);
                addSetting(autoMapRadar);
                addSetting(mapCenterX);
                addSetting(mapCenterY);
        }

        // ── Slow tick — memory writes + state caching ─────────────────────────────

        /**
         * Invoked by the slow data thread (~10 Hz).
         * <p>
         * Reads and caches the local player's world position and view yaw.
         * Iterates players list and writes {@code 1} to {@code m_bSpotted} using a
         * single byte write.
         */
        @Override
        public void onTick() {
                long clientBase = CS2Memory.getClientBase();
                if (clientBase == 0)
                        return;

                updateCurrentMap();

                if (!isEnabled())
                        return;

                // ── 1. Cache local player state for the radar renderer ────────────────
                long localPawn = PlayerCache.localPlayerPawnAddress;
                if (localPawn != 0) {
                        // View yaw lives at byte offset +4 inside the 3-float view-angles struct.
                        localYaw = CS2Memory.readFloat(clientBase + CS2Offsets.dwViewAngles + 4);

                        // One RPM call fetches all three origin floats.
                        // readVector returns a zero vector on failure, never null,
                        // but guard defensively so a failed read can't corrupt
                        // the cached radar position mid-frame.
                        Vector3 origin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
                        if (origin != null) {
                                localX = origin.x;
                                localY = origin.y;
                                localZ = origin.z;
                        }
                }
        }

        private void updateCurrentMap() {
                long clientBase = CS2Memory.getClientBase();
                if (clientBase == 0 || CS2Offsets.dwGlobalVars == 0) {
                        currentMapData = null;
                        lastMapName = "";
                        detectedOffset = -1;
                        VisCheckAdapter.update("");
                        return;
                }

                String rawMap = "";

                // If we have a cached offset, try to read it directly first
                if (detectedOffset != -1) {
                        long baseAddr = detectedIsDirect
                                        ? (clientBase + CS2Offsets.dwGlobalVars)
                                        : CS2Memory.readLong(clientBase + CS2Offsets.dwGlobalVars);
                        if (baseAddr != 0) {
                                long ptr = CS2Memory.readLong(baseAddr + detectedOffset);
                                if (ptr != 0) {
                                        String str = CS2Memory.readString(ptr, 64);
                                        if (str != null && !str.isEmpty()) {
                                                String trimmed = str.trim();
                                                if (isValidMapString(trimmed)) {
                                                        rawMap = trimmed;
                                                }
                                        }
                                }
                        }
                        // If direct read failed/invalidated, reset cache to trigger re-scan
                        if (rawMap.isEmpty()) {
                                detectedOffset = -1;
                        }
                }

                // If not cached, perform the scan
                if (detectedOffset == -1) {
                        long base1 = CS2Memory.readLong(clientBase + CS2Offsets.dwGlobalVars);
                        long base2 = clientBase + CS2Offsets.dwGlobalVars;
                        long[] candidateBases = new long[] { base1, base2 };

                        for (int b = 0; b < candidateBases.length; b++) {
                                long baseAddr = candidateBases[b];
                                if (baseAddr == 0)
                                        continue;

                                for (int offset = 0x100; offset <= 0x280; offset += 8) {
                                        long ptr = CS2Memory.readLong(baseAddr + offset);
                                        if (ptr != 0) {
                                                String str = CS2Memory.readString(ptr, 64);
                                                if (str != null && !str.isEmpty()) {
                                                        String trimmed = str.trim();
                                                        if (isValidMapString(trimmed)) {
                                                                rawMap = trimmed;
                                                                detectedOffset = offset;
                                                                detectedIsDirect = (b == 1);
                                                                break;
                                                        }
                                                }
                                        }
                                }
                                if (detectedOffset != -1) {
                                        break;
                                }
                        }
                }

                if (rawMap.isEmpty()) {
                        currentMapData = null;
                        lastMapName = "";
                        VisCheckAdapter.update("");
                        if (!mapDetectionLogged) {
                                System.out.println("[RadarHackModule] Map detection active.");
                                mapDetectionLogged = true;
                        }
                        return;
                }

                String cleanMap = rawMap;
                if (cleanMap.contains("/")) {
                        cleanMap = cleanMap.substring(cleanMap.lastIndexOf("/") + 1);
                }
                if (cleanMap.endsWith(".vpk")) {
                        cleanMap = cleanMap.substring(0, cleanMap.length() - 4);
                }

                if (!cleanMap.equals(lastMapName)) {
                        lastMapName = cleanMap;
                        mapDetectionLogged = false;
                        VisCheckAdapter.update(cleanMap);
                        currentMapData = MAPS.get(cleanMap);
                        if (currentMapData != null) {
                                System.out.println("[RadarHackModule] Detected map: " + cleanMap
                                                + " (posX=" + currentMapData.posX + ", posY=" + currentMapData.posY
                                                + ", scale=" + currentMapData.scale + ") via "
                                                + (detectedIsDirect ? "direct" : "pointer") + " offset 0x"
                                                + Long.toHexString(detectedOffset));
                        } else {
                                System.out.println("[RadarHackModule] Detected unknown map: " + cleanMap
                                                + " via " + (detectedIsDirect ? "direct" : "pointer") + " offset 0x"
                                                + Long.toHexString(detectedOffset));
                        }
                }
        }

        private boolean isValidMapString(String trimmed) {
                if (trimmed.isEmpty() || trimmed.contains("<empty>") || trimmed.contains("empty")) {
                        return false;
                }
                return trimmed.startsWith("maps/") || trimmed.startsWith("de_") || trimmed.startsWith("cs_")
                                || trimmed.contains("workshop") || trimmed.endsWith(".vpk");
        }

        // ── Render — ImGui overlay mini-radar ────────────────────────────────────

        /**
         * Invoked by the render thread every frame.
         * <p>
         * Draws a square minimap overlay aligned to the top-left CS2 radar.
         * When {@code autoAlign} is disabled and the overlay menu is open (INSERT key),
         * interactive drag handles are rendered so the user can reposition and resize
         * the radar without touching sliders.
         */
        @Override
        public void onRender(ImDrawList drawList) {
                if (!isEnabled())
                        return;
                if (!PlayerCache.tracking)
                        return;
                if (lastMapName == null || lastMapName.isEmpty() || lastMapName.equalsIgnoreCase("<empty>"))
                        return;

                if (showEnemyWidget.getValue()) {
                        renderEnemyStatusWidget();
                }

                if (!showOverlay.getValue())
                        return;

                float rx_pos = radarX.getValue();
                float ry_pos = radarY.getValue();
                float size = radarSize.getValue();
                float scale = radarScale.getValue();

                float cx = rx_pos + size / 2.0f;
                float cy = ry_pos + size / 2.0f;

                // Cyan border outline around the square radar frame
                drawList.addRect(rx_pos, ry_pos, rx_pos + size, ry_pos + size,
                                ImColor.rgba(0.00f, 0.71f, 0.85f, 0.45f), 0.0f, 0, 1.2f);

                // ── Mode branch: Square Radar vs Mini-Radar ────────────────────────
                if (squareRadar.getValue()) {
                        renderSquareRadar(drawList, rx_pos, ry_pos, size, cx, cy);
                } else {
                        renderMiniRadar(drawList, rx_pos, ry_pos, size, cx, cy, scale);
                }
        }

        /**
         * Draws the full-map "Square Radar" mode.
         * <p>
         * Mirrors the CS2 in-game square radar that shows the whole map at once:
         * <ul>
         * <li>No rotation — the map is always north-up.</li>
         * <li>The centre of the radar overlay corresponds to
         * ({@link #mapCenterX}, {@link #mapCenterY}) in world space.</li>
         * <li>The local player is drawn as a <b>yellow arrow</b> at their
         * actual world position (they are NOT locked to the centre).</li>
         * <li>All enemies and teammates are drawn as dots at their absolute
         * positions.</li>
         * </ul>
         * Tip: set {@code radarScale} to roughly
         * {@code (mapRadiusHammerUnits) / (radarSizePx / 2)} for the whole map to fit.
         *
         * @param drawList ImGui foreground draw list.
         * @param rx       Radar left-edge X (screen pixels).
         * @param ry       Radar top-edge Y (screen pixels).
         * @param size     Side length of the radar square (pixels).
         * @param cx       Screen-space X of the radar centre.
         * @param cy       Screen-space Y of the radar centre.
         */
        private void renderSquareRadar(ImDrawList drawList,
                        float rx, float ry, float size,
                        float cx, float cy) {

                float scale = radarScale.getValue();
                float mapCX = mapCenterX.getValue();
                float mapCY = mapCenterY.getValue();

                float halfSize = size / 2.0f;
                float dotRadius = 4.0f;
                float padding = dotRadius + 2.0f;

                float minX = cx - halfSize + padding;
                float maxX = cx + halfSize - padding;
                float minY = cy - halfSize + padding;
                float maxY = cy + halfSize - padding;

                // ── Grid lines at map centre ──────────────────────────────────
                // Draw subtle cross-hairs centred on the map origin for spatial reference.
                drawList.addLine(rx, cy, rx + size, cy,
                                ImColor.rgba(1.0f, 1.0f, 1.0f, 0.08f), 0.8f);
                drawList.addLine(cx, ry, cx, ry + size,
                                ImColor.rgba(1.0f, 1.0f, 1.0f, 0.08f), 0.8f);

                drawList.pushClipRect(rx, ry, rx + size, ry + size, true);

                int myTeam = ESPModule.localTeam;

                // ── Local player dot + yaw arrow ─────────────────────────────
                // Convert local player's absolute world position to radar screen coords.
                float lpx, lpy;
                MapOverviewData mapData = currentMapData;
                if (autoMapRadar.getValue() && mapData != null) {
                        lpx = rx + ((localX - mapData.posX) / (1024.0f * mapData.scale)) * size;
                        lpy = ry + ((mapData.posY - localY) / (1024.0f * mapData.scale)) * size;
                } else {
                        lpx = cx + (localX - mapCX) / scale;
                        lpy = cy - (localY - mapCY) / scale; // negate Y: world +Y → screen up
                }

                // Clamp so the local player indicator never leaves the radar boundary.
                if (lpx < minX)
                        lpx = minX;
                else if (lpx > maxX)
                        lpx = maxX;
                if (lpy < minY)
                        lpy = minY;
                else if (lpy > maxY)
                        lpy = maxY;

                // Yaw direction arrow: line from player dot toward their look direction.
                float yawRad = (float) Math.toRadians(localYaw);
                float lcos = (float) Math.cos(yawRad);
                float lsin = (float) Math.sin(yawRad);
                float arrowLen = 12.0f;
                // In CS2: +X = east, +Y = north; screen: right = +x, up = -y → negate Y
                // component.
                float arrowTipX = lpx + lcos * arrowLen;
                float arrowTipY = lpy - lsin * arrowLen;

                // Drop shadow for arrow
                drawList.addLine(lpx + 1f, lpy + 1f, arrowTipX + 1f, arrowTipY + 1f,
                                ImColor.rgba(0f, 0f, 0f, 0.60f), 2.5f);
                // Bright yellow arrow so the local player is easy to spot on the map.
                drawList.addLine(lpx, lpy, arrowTipX, arrowTipY,
                                ImColor.rgba(1.0f, 0.92f, 0.10f, 1.00f), 2.0f);

                // Draw local player as a small white-filled dot.
                float lpDotR = 5.0f;
                drawList.addCircleFilled(lpx, lpy, lpDotR + 1.5f, ImColor.rgba(0f, 0f, 0f, 0.40f));
                drawList.addCircleFilled(lpx, lpy, lpDotR, ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f));
                drawList.addCircle(lpx, lpy, lpDotR, ImColor.rgba(0f, 0f, 0f, 0.80f), 12, 1.0f);

                // ── Enemy / teammate dots ───────────────────────────────────────
                for (PlayerCache.PlayerData player : PlayerCache.players) {
                        if (player.isLocal || player.pawnAddress == 0 || player.position == null)
                                continue;

                        // Absolute world position → radar screen position (no rotation).
                        float px, py;
                        if (autoMapRadar.getValue() && mapData != null) {
                                px = rx + ((player.position.x - mapData.posX) / (1024.0f * mapData.scale)) * size;
                                py = ry + ((mapData.posY - player.position.y) / (1024.0f * mapData.scale)) * size;
                        } else {
                                px = cx + (player.position.x - mapCX) / scale;
                                py = cy - (player.position.y - mapCY) / scale;
                        }

                        // Clamp out-of-range targets to the radar edge (with translucency to hint they
                        // are far).
                        boolean clamped = false;
                        if (px < minX) {
                                px = minX;
                                clamped = true;
                        } else if (px > maxX) {
                                px = maxX;
                                clamped = true;
                        }
                        if (py < minY) {
                                py = minY;
                                clamped = true;
                        } else if (py > maxY) {
                                py = maxY;
                                clamped = true;
                        }

                        boolean isEnemy = (player.team != myTeam);
                        int dotFill;
                        if (player.hasBomb) {
                                // Pulsing orange/gold color for bomb carrier
                                dotFill = clamped
                                                ? ImColor.rgba(1.00f, 0.60f, 0.00f, 0.50f)
                                                : ImColor.rgba(1.00f, 0.60f, 0.00f, 1.00f);
                        } else if (isEnemy) {
                                dotFill = clamped
                                                ? ImColor.rgba(0.95f, 0.28f, 0.28f, 0.45f)
                                                : ImColor.rgba(0.95f, 0.28f, 0.28f, 1.00f);
                        } else {
                                dotFill = clamped
                                                ? ImColor.rgba(0.25f, 0.58f, 1.00f, 0.45f)
                                                : ImColor.rgba(0.25f, 0.58f, 1.00f, 1.00f);
                        }

                        drawList.addCircleFilled(px, py, dotRadius + 2.0f, ImColor.rgba(0f, 0f, 0f, 0.25f));
                        drawList.addCircleFilled(px, py, dotRadius, dotFill);
                        drawList.addCircle(px, py, dotRadius, ImColor.rgba(0f, 0f, 0f, 0.70f), 12, 0.8f);

                        // Pulsing outer ring and text label for bomb carrier
                        if (player.hasBomb) {
                                float pulse = (float) (Math.sin(System.currentTimeMillis() * 0.008) * 0.5 + 0.5);
                                int ringColor = ImColor.rgba(1.0f, 0.4f, 0.0f, 0.3f + pulse * 0.6f);
                                drawList.addCircle(px, py, dotRadius + 3.0f + pulse * 3.0f, ringColor, 16, 1.5f);

                                String bombText = "BOMB";
                                float textX = px + 6.0f;
                                float textY = py - 14.0f;
                                // Draw shadow
                                drawList.addText(textX - 1.0f, textY - 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                drawList.addText(textX + 1.0f, textY - 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                drawList.addText(textX - 1.0f, textY + 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                drawList.addText(textX + 1.0f, textY + 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                // Draw main text
                                drawList.addText(textX, textY, ImColor.rgba(1.0f, 0.6f, 0.0f, 1.0f), bombText);
                        }

                        // Draw look-direction arrow for un-clamped players
                        if (!clamped) {
                                float pYawRad = (float) Math.toRadians(player.yaw);
                                float pcos = (float) Math.cos(pYawRad);
                                float psin = (float) Math.sin(pYawRad);

                                // Static map: North is UP (+Y), East is RIGHT (+X)
                                float r_vx = pcos;
                                float r_vy = psin;

                                float pArrowLen = 12.0f;
                                float adx = r_vx;
                                float ady = -r_vy; // Negate Y for screen space
                                float anx = -ady; // Normal vector
                                float any = adx;

                                float tipX = px + adx * pArrowLen;
                                float tipY = py + ady * pArrowLen;

                                float arrowHeadSize = 3.5f;
                                float backX = tipX - adx * arrowHeadSize;
                                float backY = tipY - ady * arrowHeadSize;

                                float leftX = backX - anx * (arrowHeadSize * 0.7f);
                                float leftY = backY - any * (arrowHeadSize * 0.7f);

                                float rightX = backX + anx * (arrowHeadSize * 0.7f);
                                float rightY = backY + any * (arrowHeadSize * 0.7f);

                                int arrowCol = player.hasBomb ? ImColor.rgba(1.00f, 0.60f, 0.00f, 0.85f)
                                                : (isEnemy ? ImColor.rgba(0.95f, 0.28f, 0.28f, 0.85f)
                                                                : ImColor.rgba(0.25f, 0.58f, 1.00f, 0.85f));
                                drawList.addLine(px, py, backX, backY, arrowCol, 1.2f);
                                drawList.addTriangleFilled(tipX, tipY, leftX, leftY, rightX, rightY, arrowCol);
                        }
                }

                drawList.popClipRect();
        }

        /**
         * Draws the original player-centred mini-radar with optional rotation.
         * <p>
         * The local player is always at the centre. Enemies are drawn with their
         * positions relative to the local player, optionally rotated by the player's
         * yaw so their forward direction always points screen-up.
         *
         * @param drawList ImGui foreground draw list.
         * @param rx_pos   Radar left-edge X (screen pixels).
         * @param ry_pos   Radar top-edge Y (screen pixels).
         * @param size     Side length of the radar square (pixels).
         * @param cx       Screen-space X of the radar centre / local player.
         * @param cy       Screen-space Y of the radar centre / local player.
         * @param scale    World units per radar pixel.
         */
        private void renderMiniRadar(ImDrawList drawList,
                        float rx_pos, float ry_pos, float size,
                        float cx, float cy, float scale) {

                float yawRad = (float) Math.toRadians(localYaw);
                float cos = (float) Math.cos(yawRad);
                float sin = (float) Math.sin(yawRad);
                boolean rotate = rotateRadar.getValue();

                // ── Local Player direction arrow ───────────────────────────────
                float triWidth = 4.0f;
                float triHeight = 6.0f;
                if (rotate) {
                        // Points straight up (since the map rotates beneath the player)
                        drawList.addTriangleFilled(
                                        cx, cy - triHeight,
                                        cx - triWidth, cy + triHeight / 2.0f,
                                        cx + triWidth, cy + triHeight / 2.0f,
                                        ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f));
                        drawList.addTriangle(
                                        cx, cy - triHeight,
                                        cx - triWidth, cy + triHeight / 2.0f,
                                        cx + triWidth, cy + triHeight / 2.0f,
                                        ImColor.rgba(0.0f, 0.0f, 0.0f, 0.8f),
                                        1.0f);
                } else {
                        // Points in the actual world-space look direction (yaw)
                        float fx = cos;
                        float fy = -sin; // Negate Y for screen space
                        float rx_vec = sin;
                        float ry_vec = cos;

                        float tipX = cx + fx * triHeight;
                        float tipY = cy + fy * triHeight;
                        float leftX = cx - fx * (triHeight / 2.0f) - rx_vec * triWidth;
                        float leftY = cy - fy * (triHeight / 2.0f) - ry_vec * triWidth;
                        float rightX = cx - fx * (triHeight / 2.0f) + rx_vec * triWidth;
                        float rightY = cy - fy * (triHeight / 2.0f) + ry_vec * triWidth;

                        drawList.addTriangleFilled(tipX, tipY, leftX, leftY, rightX, rightY,
                                        ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f));
                        drawList.addTriangle(tipX, tipY, leftX, leftY, rightX, rightY,
                                        ImColor.rgba(0.0f, 0.0f, 0.0f, 0.8f), 1.0f);
                }

                // ── Push Clip Rect ───────────────────────────────────────────────────
                // Clips dots and text so they never draw outside of the square boundary
                drawList.pushClipRect(rx_pos, ry_pos, rx_pos + size, ry_pos + size, true);

                // ── Enemy / teammate dots ─────────────────────────────────────────────
                int myTeam = ESPModule.localTeam;
                List<PlayerCache.PlayerData> players = PlayerCache.players;

                float halfSize = size / 2.0f;
                float dotRadius = 4.0f;
                float padding = dotRadius + 2.0f;

                float minX = cx - halfSize + padding;
                float maxX = cx + halfSize - padding;
                float minY = cy - halfSize + padding;
                float maxY = cy + halfSize - padding;

                for (PlayerCache.PlayerData player : players) {
                        if (player.isLocal || player.pawnAddress == 0)
                                continue;
                        if (player.position == null)
                                continue;

                        // World-space offset from local player origin
                        float dx = player.position.x - localX;
                        float dy = player.position.y - localY;

                        float rx, ry;
                        if (rotate) {
                                // Rotate to align world forward direction with screen-up (negative Y)
                                rx = dx * sin - dy * cos;
                                ry = dx * cos + dy * sin;
                        } else {
                                // Static Map: North is UP (+Y world), East is RIGHT (+X world)
                                rx = dx;
                                ry = dy;
                        }

                        // Convert to radar space
                        float px = cx + rx / scale;
                        float py = cy - ry / scale;

                        // Clamp to the square boundary so out-of-range targets sit at the edge
                        boolean clamped = false;
                        if (px < minX) {
                                px = minX;
                                clamped = true;
                        } else if (px > maxX) {
                                px = maxX;
                                clamped = true;
                        }

                        if (py < minY) {
                                py = minY;
                                clamped = true;
                        } else if (py > maxY) {
                                py = maxY;
                                clamped = true;
                        }

                        // Colour: red = enemy, blue = teammate, orange = bomb carrier
                        boolean isEnemy = (player.team != myTeam);
                        int dotFill;
                        if (player.hasBomb) {
                                dotFill = clamped
                                                ? ImColor.rgba(1.00f, 0.60f, 0.00f, 0.50f)
                                                : ImColor.rgba(1.00f, 0.60f, 0.00f, 1.00f);
                        } else if (isEnemy) {
                                dotFill = clamped
                                                ? ImColor.rgba(0.95f, 0.28f, 0.28f, 0.50f) // translucent enemy
                                                : ImColor.rgba(0.95f, 0.28f, 0.28f, 1.00f); // solid enemy
                        } else {
                                dotFill = clamped
                                                ? ImColor.rgba(0.25f, 0.58f, 1.00f, 0.50f) // translucent team
                                                : ImColor.rgba(0.25f, 0.58f, 1.00f, 1.00f); // solid team
                        }

                        // Render Dot: subtle shadow -> fill -> black outline
                        drawList.addCircleFilled(px, py, dotRadius + 2.0f, ImColor.rgba(0f, 0f, 0f, 0.25f));
                        drawList.addCircleFilled(px, py, dotRadius, dotFill);
                        drawList.addCircle(px, py, dotRadius, ImColor.rgba(0f, 0f, 0f, 0.70f), 12, 0.8f);

                        // Pulsing outer ring and text label for bomb carrier
                        if (player.hasBomb) {
                                float pulse = (float) (Math.sin(System.currentTimeMillis() * 0.008) * 0.5 + 0.5);
                                int ringColor = ImColor.rgba(1.0f, 0.4f, 0.0f, 0.3f + pulse * 0.6f);
                                drawList.addCircle(px, py, dotRadius + 3.0f + pulse * 3.0f, ringColor, 16, 1.5f);

                                String bombText = "BOMB";
                                float textX = px + 6.0f;
                                float textY = py - 14.0f;
                                // Draw shadow
                                drawList.addText(textX - 1.0f, textY - 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                drawList.addText(textX + 1.0f, textY - 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                drawList.addText(textX - 1.0f, textY + 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                drawList.addText(textX + 1.0f, textY + 1.0f, ImColor.rgba(0, 0, 0, 200), bombText);
                                // Draw main text
                                drawList.addText(textX, textY, ImColor.rgba(1.0f, 0.6f, 0.0f, 1.0f), bombText);
                        }

                        // Draw look-direction arrow for un-clamped players
                        if (!clamped) {
                                float pYawRad = (float) Math.toRadians(player.yaw);
                                float pcos = (float) Math.cos(pYawRad);
                                float psin = (float) Math.sin(pYawRad);

                                float r_vx, r_vy;
                                if (rotate) {
                                        // sin and cos are already computed for local player's yaw
                                        r_vx = pcos * sin - psin * cos;
                                        r_vy = pcos * cos + psin * sin;
                                } else {
                                        r_vx = pcos;
                                        r_vy = psin;
                                }

                                float pArrowLen = 12.0f;
                                float adx = r_vx;
                                float ady = -r_vy; // Negate Y for screen space
                                float anx = -ady; // Normal vector
                                float any = adx;

                                float tipX = px + adx * pArrowLen;
                                float tipY = py + ady * pArrowLen;

                                float arrowHeadSize = 3.5f;
                                float backX = tipX - adx * arrowHeadSize;
                                float backY = tipY - ady * arrowHeadSize;

                                float leftX = backX - anx * (arrowHeadSize * 0.7f);
                                float leftY = backY - any * (arrowHeadSize * 0.7f);

                                float rightX = backX + anx * (arrowHeadSize * 0.7f);
                                float rightY = backY + any * (arrowHeadSize * 0.7f);

                                int arrowCol = player.hasBomb ? ImColor.rgba(1.00f, 0.60f, 0.00f, 0.85f)
                                                : (isEnemy ? ImColor.rgba(0.95f, 0.28f, 0.28f, 0.85f)
                                                                : ImColor.rgba(0.25f, 0.58f, 1.00f, 0.85f));
                                drawList.addLine(px, py, backX, backY, arrowCol, 1.2f);
                                drawList.addTriangleFilled(tipX, tipY, leftX, leftY, rightX, rightY, arrowCol);
                        }

                }

                drawList.popClipRect();

                // ── "RADAR" label ─────────────────────────────────────────────────────
                String label = squareRadar.getValue() ? "SQUARE RADAR" : "RADAR OVERLAY";
                drawList.addText(rx_pos + 6f, ry_pos + 6f, ImColor.rgba(0.00f, 0.71f, 0.85f, 0.60f), label);

                // ── Drag handles (only when menu is open and autoAlign is off) ─────────
                renderDragHandles(drawList, rx_pos, ry_pos, size);
        }

        /**
         * Renders interactive drag handles for repositioning and resizing the radar
         * overlay.
         * <p>
         * Two handle zones are available:
         * <ul>
         * <li><b>Body (move)</b> — any area inside the radar except the bottom-right
         * grip.
         * Left-click and drag to move the radar to a new screen position.</li>
         * <li><b>Bottom-right grip (resize)</b> — a
         * {@value #GRIP_SIZE}×{@value #GRIP_SIZE} px
         * square at the bottom-right corner. Left-click and drag to change the radar
         * size.</li>
         * </ul>
         * Handles are only shown when the overlay menu is open (INSERT key) and
         * {@link #autoAlign} is disabled; otherwise the overlay is click-through and
         * there is nothing to interact with.
         *
         * @param drawList ImGui foreground draw list for visual affordances.
         * @param rx       Radar left edge X in screen pixels.
         * @param ry       Radar top edge Y in screen pixels.
         * @param size     Current radar side length in pixels.
         */
        private void renderDragHandles(ImDrawList drawList, float rx, float ry, float size) {
                if (!OverlayWindow.isMenuOpen())
                        return;

                ImVec2 mousePos = ImGui.getMousePos();
                float mouseX = mousePos.x;
                float mouseY = mousePos.y;

                // ── Define the two interaction zones ─────────────────────────────────
                // Resize grip occupies the bottom-right corner square.
                float gripX1 = rx + size - GRIP_SIZE;
                float gripY1 = ry + size - GRIP_SIZE;
                float gripX2 = rx + size;
                float gripY2 = ry + size;

                boolean hoverGrip = mouseX >= gripX1 && mouseX <= gripX2
                                && mouseY >= gripY1 && mouseY <= gripY2;

                // Move zone is the full radar body minus the resize grip.
                boolean hoverBody = !hoverGrip
                                && mouseX >= rx && mouseX <= rx + size
                                && mouseY >= ry && mouseY <= ry + size;

                // ── Drag state machine ────────────────────────────────────────────────
                if (ImGui.isMouseReleased(0)) {
                        // Any button release ends the current drag unconditionally.
                        dragMode = DRAG_NONE;

                } else if (dragMode == DRAG_MOVE) {
                        // Continue moving: apply total delta from drag origin to start values.
                        float deltaX = mouseX - dragOriginMouseX;
                        float deltaY = mouseY - dragOriginMouseY;
                        radarX.setValue(dragStartX + deltaX);
                        radarY.setValue(dragStartY + deltaY);

                } else if (dragMode == DRAG_RESIZE) {
                        // Continue resizing: use the larger axis delta to stay square.
                        float deltaX = mouseX - dragOriginMouseX;
                        float deltaY = mouseY - dragOriginMouseY;
                        float delta = Math.max(deltaX, deltaY);
                        radarSize.setValue(dragStartSize + delta);

                } else if (ImGui.isMouseClicked(0)) {
                        // Begin a new drag on the frame the button is first pressed.
                        if (hoverGrip) {
                                dragMode = DRAG_RESIZE;
                                dragStartSize = radarSize.getValue();
                                dragOriginMouseX = mouseX;
                                dragOriginMouseY = mouseY;
                        } else if (hoverBody) {
                                dragMode = DRAG_MOVE;
                                dragStartX = radarX.getValue();
                                dragStartY = radarY.getValue();
                                dragOriginMouseX = mouseX;
                                dragOriginMouseY = mouseY;
                        }
                }

                // ── Visual affordances ────────────────────────────────────────────────

                // Highlight the border when hovered in move mode to signal draggability.
                if (hoverBody || dragMode == DRAG_MOVE) {
                        drawList.addRect(rx, ry, rx + size, ry + size,
                                        ImColor.rgba(0.00f, 0.85f, 1.00f, 0.75f), 0f, 0, 1.8f);
                }

                // Resize grip: solid cyan square, turns yellow on hover/drag.
                boolean gripActive = hoverGrip || dragMode == DRAG_RESIZE;
                int gripColor = gripActive
                                ? ImColor.rgba(1.00f, 0.90f, 0.10f, 0.95f) // bright yellow when active
                                : ImColor.rgba(0.00f, 0.71f, 0.85f, 0.65f); // cyan when idle
                drawList.addRectFilled(gripX1, gripY1, gripX2, gripY2, gripColor, 3.0f);
                // Thin dark border around grip for definition
                drawList.addRect(gripX1, gripY1, gripX2, gripY2,
                                ImColor.rgba(0f, 0f, 0f, 0.55f), 3.0f, 0, 1.0f);

                // Draw resize arrows inside the grip (two diagonal lines → ↘ symbol)
                float arrowPad = 3.5f;
                drawList.addLine(gripX1 + arrowPad, gripY2 - arrowPad,
                                gripX2 - arrowPad, gripY1 + arrowPad,
                                ImColor.rgba(0f, 0f, 0f, 0.80f), 1.2f);
                drawList.addLine(gripX1 + arrowPad + 3f, gripY2 - arrowPad,
                                gripX2 - arrowPad, gripY1 + arrowPad + 3f,
                                ImColor.rgba(0f, 0f, 0f, 0.80f), 1.2f);

                // "DRAG TO MOVE / RESIZE" hint text in the top-left of the radar
                drawList.addText(rx + 6f, ry + size - 18f,
                                ImColor.rgba(1.0f, 1.0f, 1.0f, 0.35f), "drag to move  \u2199resize");
        }

        // ── Enemy Status Widget ───────────────────────────────────────────────

        /**
         * Renders a pretty, compact overlay widget displaying the enemy team's
         * health, active held weapon, and current money balance.
         * Movable by dragging when the cheat overlay menu (INSERT key) is open.
         */
        private void renderEnemyStatusWidget() {
                boolean isMenuOpen = OverlayWindow.isMenuOpen();

                ImGui.setNextWindowPos(20.0f, 240.0f, ImGuiCond.FirstUseEver);

                int windowFlags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize;
                if (!isMenuOpen) {
                        windowFlags |= ImGuiWindowFlags.NoTitleBar
                                        | ImGuiWindowFlags.NoResize
                                        | ImGuiWindowFlags.NoMove
                                        | ImGuiWindowFlags.NoInputs;
                }

                if (ImGui.begin("Enemy Overview", windowFlags)) {
                        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                        List<PlayerSnapshot> enemies = new ArrayList<>();
                        int totalMoney = 0;

                        for (PlayerSnapshot p : players) {
                                if (p.isLocal) continue;
                                if (p.team == ESPModule.localTeam) continue;
                                enemies.add(p);
                                if (p.health > 0) {
                                        totalMoney += p.money;
                                }
                        }

                        int headerColor = ImColor.rgba(1.0f, 0.35f, 0.25f, 1.0f);
                        if (ESPModule.localTeam == 2) {
                                // Local team is Terrorist (2) -> Enemies are Counter-Terrorist (3)
                                headerColor = ImColor.rgba(0.25f, 0.70f, 1.0f, 1.0f);
                        }

                        ImGui.textColored(headerColor, "ENEMY TEAM");
                        ImGui.sameLine();
                        ImGui.textColored(ImColor.rgba(0.2f, 0.95f, 0.55f, 1.0f), String.format(" ($%,d)", totalMoney));

                        if (enemies.isEmpty()) {
                                ImGui.textColored(ImColor.rgba(0.5f, 0.5f, 0.5f, 0.7f), "No Enemies Detected");
                        } else {
                                ImGui.separator();
                                for (PlayerSnapshot enemy : enemies) {
                                        int hp = Math.max(0, enemy.health);
                                        int hpColor;
                                        if (hp > 60) {
                                                hpColor = ImColor.rgba(0.20f, 0.95f, 0.45f, 1.0f);
                                        } else if (hp > 25) {
                                                hpColor = ImColor.rgba(1.0f, 0.80f, 0.20f, 1.0f);
                                        } else if (hp > 0) {
                                                hpColor = ImColor.rgba(1.0f, 0.25f, 0.25f, 1.0f);
                                        } else {
                                                hpColor = ImColor.rgba(0.45f, 0.45f, 0.45f, 0.60f);
                                        }

                                        String name = enemy.name != null && !enemy.name.isEmpty() ? enemy.name : "Enemy " + enemy.index;
                                        if (name.length() > 12) name = name.substring(0, 10) + "..";

                                        String weapon = resolvePlayerWeapon(enemy.pawnAddress);

                                        if (hp > 0) {
                                                ImGui.textColored(hpColor, String.format("%3d HP", hp));
                                                ImGui.sameLine();
                                                ImGui.textColored(ImColor.rgba(0.9f, 0.9f, 0.95f, 1.0f), String.format("%-12s", name));
                                                ImGui.sameLine();
                                                ImGui.textColored(ImColor.rgba(0.95f, 0.85f, 0.4f, 1.0f), String.format("[%s]", weapon));
                                                ImGui.sameLine();
                                                ImGui.textColored(ImColor.rgba(0.3f, 0.95f, 0.6f, 1.0f), String.format("$%d", enemy.money));
                                        } else {
                                                ImGui.textColored(ImColor.rgba(0.45f, 0.45f, 0.45f, 0.6f), " DEAD ");
                                                ImGui.sameLine();
                                                ImGui.textColored(ImColor.rgba(0.45f, 0.45f, 0.45f, 0.6f), String.format("%-12s", name));
                                                ImGui.sameLine();
                                                ImGui.textColored(ImColor.rgba(0.45f, 0.45f, 0.45f, 0.6f), String.format("$%d", enemy.money));
                                        }
                                }
                        }
                }
                ImGui.end();
        }

        private String resolvePlayerWeapon(long pawnAddress) {
                if (pawnAddress == 0) return "Knife";
                long weaponServices = CS2Memory.readLong(pawnAddress + CS2Offsets.m_pWeaponServices);
                if (!isValidPtr(weaponServices)) return "Knife";
                int activeHandle = CS2Memory.readInt(weaponServices + CS2Offsets.m_hActiveWeapon);
                long weaponEntity = getEntityByHandle(activeHandle);
                if (!isValidPtr(weaponEntity)) return "Knife";
                long itemBase = weaponEntity + CS2Offsets.m_AttributeManager + CS2Offsets.m_Item;
                int defIdx = CS2Memory.readShort(itemBase + CS2Offsets.m_iItemDefinitionIndex) & 0xFFFF;
                return getWeaponName(defIdx);
        }

        private static long getEntityByHandle(int handle) {
                if (handle == 0 || handle == -1) return 0;
                long clientBase = CS2Memory.getClientBase();
                long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
                if (entityList == 0) return 0;
                long listEntry = CS2Memory.readLong(entityList + 8L * ((handle & 0x7FFF) >> 9) + 0x10);
                if (listEntry == 0) return 0;
                return CS2Memory.readLong(listEntry + 0x70L * (handle & 0x1FF));
        }

        private static boolean isValidPtr(long ptr) {
                return ptr > 0x10000L && ptr < 0x7FFF_FFFF_FFFFL;
        }

        private static String getWeaponName(int itemDefIndex) {
                switch (itemDefIndex) {
                        case 1:  return "Deagle";
                        case 2:  return "Dualies";
                        case 3:  return "Five-Seven";
                        case 4:  return "Glock";
                        case 7:  return "AK-47";
                        case 8:  return "AUG";
                        case 9:  return "AWP";
                        case 10: return "FAMAS";
                        case 11: return "G3SG1";
                        case 13: return "Galil";
                        case 14: return "M249";
                        case 16: return "M4A4";
                        case 17: return "MAC-10";
                        case 19: return "P90";
                        case 23: return "MP5-SD";
                        case 24: return "UMP-45";
                        case 25: return "XM1014";
                        case 26: return "Bizon";
                        case 27: return "MAG-7";
                        case 28: return "Negev";
                        case 29: return "Sawed-Off";
                        case 30: return "Tec-9";
                        case 31: return "Zeus";
                        case 32: return "P2000";
                        case 33: return "MP7";
                        case 34: return "MP9";
                        case 35: return "Nova";
                        case 36: return "P250";
                        case 38: return "SCAR-20";
                        case 39: return "SG 553";
                        case 40: return "Scout";
                        case 43: return "Flashbang";
                        case 44: return "HE Grenade";
                        case 45: return "Smoke";
                        case 46: return "Molotov";
                        case 47: return "Decoy";
                        case 48: return "Incendiary";
                        case 49: return "C4";
                        case 60: return "M4A1-S";
                        case 61: return "USP-S";
                        case 63: return "CZ75";
                        case 64: return "Revolver";
                        default:
                                if (itemDefIndex >= 500) return "Knife";
                                if (itemDefIndex == 42 || itemDefIndex == 59) return "Knife";
                                return "Weapon";
                }
        }
}
