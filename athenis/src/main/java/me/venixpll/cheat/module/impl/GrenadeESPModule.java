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
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;

/**
 * GrenadeESPModule.
 *
 * <p>Scans the CS2 entity list for live grenade/flashbang projectiles and
 * renders a beautiful circular countdown timer over each one that follows its
 * trajectory on-screen.
 *
 * <h3>Visual design</h3>
 * <ul>
 *   <li>Dark semi-transparent circle background.</li>
 *   <li>Animated arc drawn with {@code addPolyline} to simulate an ImGui arc;
 *       the arc sweeps from full circle (just thrown) down to nothing (detonating).
 *       Color transitions: green → yellow → red.</li>
 *   <li>Time-remaining seconds rendered in the centre of the circle.</li>
 *   <li>Grenade type label (HE / FLASH / SMOKE / MOLOTOV / DECOY) below the circle.</li>
 * </ul>
 *
 * <h3>Entity discovery</h3>
 * Entity list slots are read from index 1 upward (well beyond the 64 player
 * controller slots) looking for entities whose designer name ends with one of
 * the known grenade projectile suffixes. The world position is resolved via
 * {@code m_pGameSceneNode → m_vecAbsOrigin}.
 *
 * <h3>Timer source</h3>
 * {@code m_flDetonateTime} (in {@link CS2Offsets}) stores the server-time
 * timestamp at which the grenade will detonate. Current time is derived from
 * the engine2.dll tick counter, identical to the approach used by
 * {@link BombTimerModule}.
 */
public class GrenadeESPModule extends CheatModule {

    // ── Settings ──────────────────────────────────────────────────────────────
    /** Show HE grenades */
    public final BooleanSetting showHE       = new BooleanSetting("Show HE Grenade",   true);
    /** Show flashbangs */
    public final BooleanSetting showFlash    = new BooleanSetting("Show Flashbang",    true);
    /** Show smoke grenades */
    public final BooleanSetting showSmoke    = new BooleanSetting("Show Smoke",        true);
    /** Show molotov / incendiary */
    public final BooleanSetting showMolotov  = new BooleanSetting("Show Molotov",      true);
    /** Show decoy grenades */
    public final BooleanSetting showDecoy    = new BooleanSetting("Show Decoy",        true);
    /** Radius of the circular timer widget in pixels (at closest range) */
    public final FloatSetting   circleRadius = new FloatSetting("Circle Radius (px)", 22.0f, 10.0f, 50.0f);
    /** Thickness of the arc stroke (at closest range) */
    public final FloatSetting   arcThickness = new FloatSetting("Arc Thickness (px)",  4.0f,  1.0f, 10.0f);
    /** Background circle fill alpha */
    public final FloatSetting   bgAlpha      = new FloatSetting("Background Alpha",    0.55f,  0.0f,  1.0f);
    /**
     * Maximum render distance in CS2 world units (1 unit ≈ 1 inch).
     * Grenades beyond this range are not drawn at all.
     * Typical map diagonal is ~8000–10000 units; 3000 covers most tactical use.
     */
    public final FloatSetting   maxDistance  = new FloatSetting("Max Distance (units)", 3000f, 200f, 8000f);
    /**
     * Minimum scale factor applied at {@link #maxDistance}.
     * 0.2 means the circle shrinks to 20 % of base size at the far end.
     * This keeps distant grenades visible but unobtrusive.
     */
    public final FloatSetting   minScale     = new FloatSetting("Min Scale",  0.25f, 0.05f, 1.0f);

    // ── Offsets: these are fetched from CS2Offsets so they auto-update ────────
    private static final float  TICK_RATE    = 64.0f;

    // Segment count for the arc polyline (higher = smoother circle)
    private static final int    ARC_SEGMENTS = 48;

    // Pre-allocated screen output buffer
    private final float[] screenOut = new float[2];
    // Pre-allocated arc vertex arrays (2 * (ARC_SEGMENTS + 1) floats for X,Y pairs)
    private final float[] arcVtxX = new float[ARC_SEGMENTS + 2];
    private final float[] arcVtxY = new float[ARC_SEGMENTS + 2];

    private final imgui.ImVec2 textSizeBuf = new imgui.ImVec2();

    // ── Known grenade designer-name fragments ─────────────────────────────────
    // CS2 uses "weapon_<name>_projectile" as the entity class name.
    // We match a short suffix to avoid dealing with full prefixes.
    private static final String[] GRENADE_SUFFIXES = {
        "hegrenade_projectile",
        "flashbang_projectile",
        "smokegrenade_projectile",
        "molotov_projectile",
        "incendiarygrenade_projectile",
        "decoy_projectile"
    };

    /** Grenade type enumeration used for color and label selection. */
    private enum GrenadeType {
        HE, FLASH, SMOKE, MOLOTOV, DECOY, UNKNOWN
    }

    public GrenadeESPModule() {
        super("Grenade ESP", ModuleCategory.EXTERNAL, true);
        addSetting(showHE);
        addSetting(showFlash);
        addSetting(showSmoke);
        addSetting(showMolotov);
        addSetting(showDecoy);
        addSetting(circleRadius);
        addSetting(arcThickness);
        addSetting(bgAlpha);
        addSetting(maxDistance);
        addSetting(minScale);
    }

    @Override
    public void onTick() {}

    // ── Validation helpers ────────────────────────────────────────────────────

    private static boolean isValidPtr(long p) {
        return p > 0x10000L && p < 0x7FFF_FFFF_FFFFL;
    }

    /** Reads the current server time in seconds from engine2.dll tick counter. */
    private static float getServerTime() {
        long engine2Base = CS2Memory.getEngine2Base();
        if (engine2Base == 0) return 0f;
        long networkClient = CS2Memory.readLong(engine2Base + CS2Offsets.dwNetworkGameClient);
        if (!isValidPtr(networkClient)) return 0f;
        int serverTick = CS2Memory.readInt(networkClient + CS2Offsets.dwNetworkGameClient_serverTickCount);
        return serverTick / TICK_RATE;
    }

    // ── Grenade type classification ───────────────────────────────────────────

    private GrenadeType classifyByDesignerName(String name) {
        if (name == null || name.isEmpty()) return GrenadeType.UNKNOWN;
        if (name.contains("hegrenade"))         return GrenadeType.HE;
        if (name.contains("flashbang"))         return GrenadeType.FLASH;
        if (name.contains("smokegrenade"))      return GrenadeType.SMOKE;
        if (name.contains("molotov"))           return GrenadeType.MOLOTOV;
        if (name.contains("incendiary"))        return GrenadeType.MOLOTOV; // same timer logic
        if (name.contains("decoy"))             return GrenadeType.DECOY;
        return GrenadeType.UNKNOWN;
    }

    private boolean isGrenadeEnabled(GrenadeType type) {
        switch (type) {
            case HE:      return showHE.getValue();
            case FLASH:   return showFlash.getValue();
            case SMOKE:   return showSmoke.getValue();
            case MOLOTOV: return showMolotov.getValue();
            case DECOY:   return showDecoy.getValue();
            default:      return false;
        }
    }

    /** Human-readable short label for the grenade type. */
    private String getLabel(GrenadeType type) {
        switch (type) {
            case HE:      return "HE";
            case FLASH:   return "FLASH";
            case SMOKE:   return "SMOKE";
            case MOLOTOV: return "FIRE";
            case DECOY:   return "DECOY";
            default:      return "NADE";
        }
    }

    /** Color for the arc depending on grenade type and time fraction. */
    private int arcColor(GrenadeType type, float fraction) {
        // fraction: 1.0 = full timer, 0.0 = about to detonate
        switch (type) {
            case HE:
                // Red with time-based alpha pulse
                return ImColor.rgba(1.0f, 0.15f + fraction * 0.2f, 0.15f, 1.0f);
            case FLASH:
                // Bright white-blue, pulsing
                return ImColor.rgba(0.55f + fraction * 0.45f, 0.75f, 1.0f, 1.0f);
            case SMOKE:
                // Neutral grey-blue
                return ImColor.rgba(0.6f, 0.75f + fraction * 0.2f, 0.85f, 1.0f);
            case MOLOTOV:
                // Orange/fire — shifts red as time runs out
                return ImColor.rgba(1.0f, 0.35f + fraction * 0.55f, 0.05f, 1.0f);
            case DECOY:
                // Teal/cyan
                return ImColor.rgba(0.1f, 0.85f, 0.75f, 1.0f);
            default:
                return ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f);
        }
    }

    /** Grenade-type-specific total fuse time used to compute fraction. */
    private float maxFuseTime(GrenadeType type) {
        switch (type) {
            case HE:      return 1.5f;  // HE detonates ~1.5 s after throw
            case FLASH:   return 1.5f;
            case SMOKE:   return 2.5f;
            case MOLOTOV: return 3.0f;
            case DECOY:   return 8.0f;
            default:      return 3.0f;
        }
    }

    /**
     * Returns the RGB components of the arc color for this grenade type and time fraction.
     * Same color logic as {@link #arcColor} but returns {@code float[3] {r, g, b}}
     * so the caller can apply a custom alpha (e.g. for distance fade).
     *
     * @param type     Grenade type.
     * @param fraction Time fraction (1.0 = full, 0.0 = detonating).
     * @return {@code float[3]} containing red, green, blue in [0, 1].
     */
    private float[] arcColorRGB(GrenadeType type, float fraction) {
        switch (type) {
            case HE:      return new float[]{ 1.0f, 0.15f + fraction * 0.2f, 0.15f };
            case FLASH:   return new float[]{ 0.55f + fraction * 0.45f, 0.75f, 1.0f };
            case SMOKE:   return new float[]{ 0.6f, 0.75f + fraction * 0.2f, 0.85f };
            case MOLOTOV: return new float[]{ 1.0f, 0.35f + fraction * 0.55f, 0.05f };
            case DECOY:   return new float[]{ 0.1f, 0.85f, 0.75f };
            default:      return new float[]{ 1.0f, 1.0f, 1.0f };
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void onRender(ImDrawList drawList) {
        if (!PlayerCache.tracking) return;

        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return;

        long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0) return;

        float serverTime = getServerTime();
        float[] matrix    = PlayerCache.viewMatrix;
        int     sw        = PlayerCache.screenWidth;
        int     sh        = PlayerCache.screenHeight;
        float   offX      = ESPModule.espOffsetX;
        float   offY      = ESPModule.espOffsetY;
        float   baseRadius    = circleRadius.getValue();
        float   baseThickness = arcThickness.getValue();
        float   maxDist       = maxDistance.getValue();
        float   minScaleVal   = minScale.getValue();

        // ── Read local player world position for distance calculation ──────────
        // We use m_vOldOrigin (the same field PositionReader uses) as it is
        // always available and accurate enough for distance-based LOD.
        float localX = 0f, localY = 0f, localZ = 0f;
        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (localPawn != 0) {
            Vector3 localOrig = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
            if (localOrig != null && Float.isFinite(localOrig.x)) {
                localX = localOrig.x;
                localY = localOrig.y;
                localZ = localOrig.z;
            }
        }

        // Scan a generous range of entity slots — grenade projectiles typically
        // live in high slots (well past the 64 controller slots).
        // We scan 0–2048 covering typical CS2 entity ranges.
        for (int i = 64; i < 2048; i++) {
            try {
                // Resolve entity list chunk entry
                long listEntry = CS2Memory.readLong(entityList + 8L * ((i & 0x7FFF) >> 9) + 16);
                if (listEntry == 0) continue;

                long entity = CS2Memory.readLong(listEntry + 112L * (i & 0x1FF));
                if (!isValidPtr(entity)) continue;

                // Identify entity type via designer name (same pointer chain as weapon_c4)
                String designerName = null;
                long identity = CS2Memory.readLong(entity + 0x10);
                if (isValidPtr(identity)) {
                    long namePtr = CS2Memory.readLong(identity + 0x20);
                    if (isValidPtr(namePtr)) {
                        designerName = CS2Memory.readString(namePtr, 64);
                    }
                }

                if (designerName == null || designerName.isEmpty()) continue;

                // Check if it matches any known grenade suffix
                boolean isGrenade = false;
                for (String suffix : GRENADE_SUFFIXES) {
                    if (designerName.contains(suffix)) {
                        isGrenade = true;
                        break;
                    }
                }
                if (!isGrenade) continue;

                GrenadeType type = classifyByDesignerName(designerName);
                if (!isGrenadeEnabled(type)) continue;

                // ── Read detonation time ───────────────────────────────────────
                // m_flDetonateTime is at CS2Offsets.m_flDetonateTime (4448 in C_BaseGrenade)
                float detonateTime = CS2Memory.readFloat(entity + CS2Offsets.m_flDetonateTime);

                // For grenades still in flight or not yet armed, detonateTime can be 0
                // We allow it through but cap the display timer at maxFuseTime
                if (!Float.isFinite(detonateTime) || detonateTime < 0f) continue;

                float timeLeft = (detonateTime > 0f) ? (detonateTime - serverTime) : maxFuseTime(type);
                // If negative or wildly large, skip (already exploded or bad read)
                if (timeLeft < -0.5f || timeLeft > 30f) continue;
                timeLeft = Math.max(0f, timeLeft);

                // ── Read world position ────────────────────────────────────────
                long sceneNode = CS2Memory.readLong(entity + CS2Offsets.m_pGameSceneNode);
                if (!isValidPtr(sceneNode)) continue;

                Vector3 worldPos = CS2Memory.readVector(sceneNode + CS2Offsets.m_vecAbsOrigin);
                if (worldPos == null
                        || !Float.isFinite(worldPos.x)
                        || !Float.isFinite(worldPos.y)
                        || !Float.isFinite(worldPos.z)) continue;

                // Skip if all zero (entity not yet initialised)
                if (worldPos.x == 0f && worldPos.y == 0f && worldPos.z == 0f) continue;

                // ── Distance-based scale (LOD) ─────────────────────────────────
                // Compute 3D Euclidean distance from local player to the grenade.
                float dx   = worldPos.x - localX;
                float dy   = worldPos.y - localY;
                float dz   = worldPos.z - localZ;
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

                // Beyond the configured max distance: skip entirely.
                if (dist > maxDist) continue;

                // Linear scale: 1.0 at distance ≤ FULL_SCALE_DIST, minScaleVal at maxDist.
                // Anything closer than FULL_SCALE_DIST always gets full size.
                final float FULL_SCALE_DIST = 300f; // units — full size when closer than this
                float distanceScale;
                if (dist <= FULL_SCALE_DIST) {
                    distanceScale = 1.0f;
                } else {
                    // Interpolate: t = 0 at FULL_SCALE_DIST, t = 1 at maxDist
                    float t = (dist - FULL_SCALE_DIST) / (maxDist - FULL_SCALE_DIST);
                    distanceScale = 1.0f - (1.0f - minScaleVal) * t;
                }

                float radius    = baseRadius    * distanceScale;
                float thickness = baseThickness * distanceScale;

                // Also fade alpha proportionally for very distant grenades
                // so they blend out naturally rather than just shrinking.
                float alphaScale = 0.4f + 0.6f * distanceScale; // ranges 1.0 → 0.4

                // ── Project to screen ──────────────────────────────────────────
                boolean projected = ScreenProjector.project(
                        worldPos.x, worldPos.y, worldPos.z + 5f, // slight upward offset
                        screenOut, matrix, sw, sh);
                if (!projected) continue;

                float cx = screenOut[0] + offX;
                float cy = screenOut[1] + offY;

                // ── Draw circular timer ────────────────────────────────────────
                drawGrenadeTimer(drawList, cx, cy, radius, thickness, alphaScale, type, timeLeft, offX, offY);

            } catch (Exception ignored) {
                // Robust: never crash the render loop over a bad entity read
            }
        }
    }

    /**
     * Draws the circular timer widget for a single grenade entity.
     *
     * <p>Layers (back to front):
     * <ol>
     *   <li>Dark filled background circle.</li>
     *   <li>Thin track circle (grey outline showing the full arc path).</li>
     *   <li>Colored animated arc that drains as the timer counts down.</li>
     *   <li>Black drop-shadow time text in the centre.</li>
     *   <li>Colored time text in the centre.</li>
     *   <li>Type label below the circle (HE / FLASH / SMOKE / FIRE / DECOY).</li>
     * </ol>
     *
     * @param drawList   ImGui draw list (foreground).
     * @param cx         Screen X centre of the circle.
     * @param cy         Screen Y centre of the circle.
     * @param radius     Circle radius in pixels (already scaled by distance).
     * @param thickness  Arc stroke thickness in pixels (already scaled by distance).
     * @param alphaScale Overall alpha multiplier in [0.4, 1.0] — 1.0 = nearby, ~0.4 = far.
     * @param type       Grenade type — drives label and color.
     * @param timeLeft   Remaining fuse time in seconds.
     * @param offX       Overlay X offset (for label text).
     * @param offY       Overlay Y offset (for label text).
     */
    private void drawGrenadeTimer(ImDrawList drawList,
                                  float cx, float cy,
                                  float radius, float thickness,
                                  float alphaScale,
                                  GrenadeType type, float timeLeft,
                                  float offX, float offY) {

        // 1. ── Dark filled background ────────────────────────────────────────
        float bgAlphaVal = bgAlpha.getValue() * alphaScale;
        drawList.addCircleFilled(cx, cy, radius + 1, ImColor.rgba(0f, 0f, 0f, bgAlphaVal * 0.85f), 32);

        // 2. ── Grey track (full-circle ghost) ─────────────────────────────
        drawList.addCircle(cx, cy, radius, ImColor.rgba(0.3f, 0.3f, 0.3f, 0.55f * alphaScale), 48, 1.5f);

        // 3. ── Colored arc that drains with the timer ──────────────────────
        float maxTime   = maxFuseTime(type);
        float fraction  = Math.min(1.0f, timeLeft / maxTime); // 1.0 = full, 0.0 = empty

        // Draw the arc as a poly-line: sweep from top (-π/2) clockwise.
        // The arc covers 'fraction' of the full circle (2π).
        double startAngle = -Math.PI / 2.0;          // top of circle
        double sweepAngle = 2.0 * Math.PI * fraction; // clockwise from start

        int segsNeeded = Math.max(3, (int) (ARC_SEGMENTS * fraction) + 1);
        int vtxCount   = 0;

        for (int s = 0; s <= segsNeeded; s++) {
            double angle = startAngle + sweepAngle * ((double) s / segsNeeded);
            arcVtxX[vtxCount] = cx + (float) (Math.cos(angle) * radius);
            arcVtxY[vtxCount] = cy + (float) (Math.sin(angle) * radius);
            vtxCount++;
        }

        // Draw as a sequence of thick line segments
        // arcCol already has full alpha; blend with alphaScale by recreating the color.
        float[] arcRGB = arcColorRGB(type, fraction); // returns float[3] {r,g,b}
        int arcColFaded = ImColor.rgba(arcRGB[0], arcRGB[1], arcRGB[2], alphaScale);
        if (vtxCount >= 2) {
            for (int s = 0; s < vtxCount - 1; s++) {
                drawList.addLine(arcVtxX[s], arcVtxY[s],
                                 arcVtxX[s + 1], arcVtxY[s + 1],
                                 arcColFaded, thickness);
            }
        }

        // Arc end-cap dot to smooth the tip
        if (vtxCount > 0) {
            float capR = thickness * 0.55f;
            drawList.addCircleFilled(arcVtxX[vtxCount - 1], arcVtxY[vtxCount - 1],
                                     capR, arcColFaded, 8);
        }

        // 4. ── Time text in the centre ────────────────────────────────────
        String timeText = String.format("%.1f", timeLeft);
        ImGui.calcTextSize(textSizeBuf, timeText);
        float tx = cx - textSizeBuf.x * 0.5f;
        float ty = cy - textSizeBuf.y * 0.5f;

        // Drop shadow (4-way)
        int shadow = ImColor.rgba(0f, 0f, 0f, alphaScale);
        drawList.addText(tx - 1, ty - 1, shadow, timeText);
        drawList.addText(tx + 1, ty - 1, shadow, timeText);
        drawList.addText(tx - 1, ty + 1, shadow, timeText);
        drawList.addText(tx + 1, ty + 1, shadow, timeText);

        // Main text — white when timer is generous, shifts to bright arc color when critical
        int textCol = (timeLeft < 1.5f)
                ? arcColFaded
                : ImColor.rgba(0.95f, 0.95f, 0.95f, alphaScale);
        drawList.addText(tx, ty, textCol, timeText);

        // 5. ── Type label below the circle ────────────────────────────────
        String label = getLabel(type);
        ImGui.calcTextSize(textSizeBuf, label);
        float lx = cx - textSizeBuf.x * 0.5f;
        float ly = cy + radius + 3;

        drawList.addText(lx - 1, ly + 1, shadow, label);
        drawList.addText(lx + 1, ly + 1, shadow, label);
        drawList.addText(lx, ly, arcColFaded, label);
    }
}
