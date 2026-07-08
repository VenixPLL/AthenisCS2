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
import me.venixpll.cheat.projection.ScreenProjector;
import me.venixpll.cheat.reader.PositionReader;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;
import me.venixpll.cheat.setting.FloatSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Unified ESP (Extra Sensory Perception) Module.
 * Consolidates Player ESP, Grenade ESP, and Damage ESP under one module
 * with distinct top-level toggles to control each independently.
 */
public class ESPModule extends CheatModule {

    // ── Master Toggles ────────────────────────────────────────────────────────
    public final BooleanSetting playerEsp  = new BooleanSetting("Show Player ESP",  true);
    public final BooleanSetting grenadeEsp = new BooleanSetting("Show Grenade ESP", true);
    public final BooleanSetting damageEsp  = new BooleanSetting("Show Damage ESP",  true);

    // ── Player ESP Settings ───────────────────────────────────────────────────
    /** Toggle to show/hide player bounding boxes */
    public final BooleanSetting boxEsp    = new BooleanSetting("Render Box", true);
    /** Toggle to show/hide player skeletons */
    public final BooleanSetting skeletonEsp = new BooleanSetting("Render Skeleton", true);
    /** Toggle to render only invisible bone parts */
    public final BooleanSetting invisibleBonesOnly = new BooleanSetting("Invisible Bones Only", false);
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

    // ── Grenade ESP Settings ──────────────────────────────────────────────────
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
    /** Maximum render distance in CS2 world units (1 unit ≈ 1 inch). */
    public final FloatSetting   maxDistance  = new FloatSetting("Max Distance (units)", 3000f, 200f, 8000f);
    /** Minimum scale factor applied at maxDistance. */
    public final FloatSetting   minScale     = new FloatSetting("Min Scale",  0.25f, 0.05f, 1.0f);

    // ── Damage ESP Settings ───────────────────────────────────────────────────
    public final BooleanSetting showDamage = new BooleanSetting(
            "Show Damage Card", true);

    public final BooleanSetting showFloating = new BooleanSetting(
            "Show Floating Numbers", true);

    public final BooleanSetting showTeammates = new BooleanSetting(
            "Show Teammates Damage", false);

    public final ColorSetting damageColor = new ColorSetting(
            "Damage Color##dmgesp", 1.0f, 0.38f, 0.0f, 1.0f);

    public final ColorSetting shotsColor = new ColorSetting(
            "Shots Color##dmgesp", 0.75f, 0.78f, 0.85f, 1.0f);

    public final FloatSetting textScale = new FloatSetting(
            "Text Scale##dmgesp", 2.0f, 0.8f, 4.0f);

    public final FloatSetting crosshairRadius = new FloatSetting(
            "Crosshair Radius px##dmgesp", 300f, 50f, 800f);

    // ── Player ESP Configuration/State ────────────────────────────────────────
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

    /** Forward extrapolation time in milliseconds. */
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

    // ── Grenade ESP Configuration/State ───────────────────────────────────────
    private static final float CIRCLE_RADIUS = 22.0f;
    private static final float ARC_THICKNESS  = 4.0f;
    private static final float BG_ALPHA       = 0.55f;
    private static final float TICK_RATE    = 64.0f;
    private static final int    ARC_SEGMENTS = 48;

    private final float[] screenOut = new float[2];
    private final float[] arcVtxX = new float[ARC_SEGMENTS + 2];
    private final float[] arcVtxY = new float[ARC_SEGMENTS + 2];
    private final imgui.ImVec2 nadeTextSizeBuf = new imgui.ImVec2();

    private static final String[] GRENADE_SUFFIXES = {
        "hegrenade_projectile",
        "flashbang_projectile",
        "smokegrenade_projectile",
        "molotov_projectile",
        "incendiarygrenade_projectile",
        "decoy_projectile"
    };

    private enum GrenadeType {
        HE, FLASH, SMOKE, MOLOTOV, DECOY, UNKNOWN
    }

    // ── Damage ESP Configuration/State ────────────────────────────────────────
    private static final long ACCUMULATE_WINDOW_MS     = 2_000L;
    private static final long FADE_MS                  = 700L;
    private static final long CROSSHAIR_ATTR_WINDOW_MS = 1_000L;

    private static final float CARD_W     = 230f;
    private static final float CARD_R     = 10f;
    private static final float PAD_H      = 14f;
    private static final float PAD_V      = 11f;
    private static final float ACCENT_H   = 3f;
    private static final float BAR_H      = 7f;
    private static final float BAR_R      = 3.5f;
    private static final float ROW_GAP    = 9f;
    private static final float SHADOW_OFF = 5f;
    private static final float CARD_OFFSET_Y = 54f;

    private static final long  FLOAT_LIFE_MS   = 1_500L;
    private static final float FLOAT_RISE_PX   = 60f;
    private static final int   MAX_FLOATERS    = 25;
    private static final float FLOAT_FONT_SCALE = 1.35f;

    private final Map<Integer, Integer> lastHealth      = new HashMap<>();
    private final Map<Integer, Long>    lastCrosshairMs = new HashMap<>();

    private volatile int  accumulatedDamage = 0;
    private volatile int  shotCount         = 0;
    private volatile int  enemyHealthLeft   = 0;
    private volatile long lastHitMs         = -1L;

    private static final class FloatingNumber {
        final int   playerIndex;
        final float relX;
        final float relY;
        final float relDriftX;
        final int   damage;
        final long  birthMs;

        FloatingNumber(int playerIndex, float relX, float relY, float relDriftX, int damage, long birthMs) {
            this.playerIndex = playerIndex;
            this.relX        = relX;
            this.relY        = relY;
            this.relDriftX   = relDriftX;
            this.damage      = damage;
            this.birthMs     = birthMs;
        }
    }

    private final ConcurrentLinkedQueue<FloatingNumber> pendingFloaters =
            new ConcurrentLinkedQueue<>();
    private final ArrayList<FloatingNumber> activeFloaters = new ArrayList<>();
    private final Random rand = new Random();
    private final imgui.ImVec2 sz = new imgui.ImVec2();

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Instantiates the ESP module and registers settings.
     */
    public ESPModule() {
        super("ESP Overlay", ModuleCategory.EXTERNAL, true);

        // ── Master Toggles ──────────────────────────────────────────────────
        addSetting(playerEsp);
        addSetting(grenadeEsp);
        addSetting(damageEsp);

        // ── Player ESP Settings ─────────────────────────────────────────────
        addSetting(boxEsp);
        addSetting(skeletonEsp);
        addSetting(invisibleBonesOnly);
        addSetting(healthEsp);
        addSetting(nameEsp);
        addSetting(teamCheck);
        addSetting(extrapolationBias);
        addSetting(enemyColor);
        addSetting(teamColor);
        addSetting(flagsEsp);
        addSetting(flagBlind);
        addSetting(flagScoped);
        addSetting(flagDefusing);
        addSetting(flagKit);
        addSetting(flagMoney);

        // ── Grenade ESP Settings ────────────────────────────────────────────
        addSetting(showHE);
        addSetting(showFlash);
        addSetting(showSmoke);
        addSetting(showMolotov);
        addSetting(showDecoy);
        addSetting(maxDistance);
        addSetting(minScale);

        // ── Damage ESP Settings ─────────────────────────────────────────────
        addSetting(showDamage);
        addSetting(showFloating);
        addSetting(showTeammates);
        addSetting(damageColor);
        addSetting(shotsColor);
        addSetting(textScale);
        addSetting(crosshairRadius);
    }

    private static boolean isValidPtr(long p) {
        return p > 0x10000L && p < 0x7FFF_FFFF_FFFFL;
    }

    @Override
    public void onTick() {
        if (!isEnabled()) return;
        if (damageEsp.getValue()) {
            tickDamageESP();
        }
    }

    @Override
    public void onRender(ImDrawList drawList) {
        if (!PlayerCache.tracking) return;

        if (playerEsp.getValue()) {
            renderPlayerESP(drawList);
        }

        if (grenadeEsp.getValue()) {
            renderGrenadeESP(drawList);
        }

        if (damageEsp.getValue()) {
            renderDamageESP(drawList);
        }
    }

    // ── Player ESP Rendering ──────────────────────────────────────────────────

    private void renderPlayerESP(ImDrawList drawList) {
        // Push the current extrapolation amount to PositionReader so it takes effect
        // on the next fast-loop iteration. Converting ms → seconds.
        PositionReader.EXTRAPOLATION_SECONDS = extrapolationBias.getValue() / 1000.0f;

        List<PlayerSnapshot> currentPlayers = PlayerCache.renderPlayers;
        if (currentPlayers.isEmpty()) return;

        for (PlayerSnapshot player : currentPlayers) {
            if (player.isLocal || !player.onScreen) continue;

            boolean isEnemy = (player.team != localTeam);
            if (teamCheck.getValue() && !isEnemy) continue;

            float feetX = player.feetX;
            float feetY = player.feetY;
            float headY = player.headY;

            if (!Float.isFinite(feetX) || !Float.isFinite(feetY) || !Float.isFinite(headY)) continue;

            float height = feetY - headY;
            float width  = height / 2.0f;
            float minX   = feetX - width / 2.0f + espOffsetX;
            float minY   = headY + espOffsetY;
            float maxX   = minX  + width;
            float maxY   = feetY + espOffsetY;

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

            float[] enemyCol = enemyColor.getValue();
            float[] teamCol  = teamColor.getValue();
            int colorInt;
            if (visCheck != null && isVis) {
                colorInt = isEnemy
                        ? ImColor.rgba(1.0f, 0.92f, 0.016f, 1.0f)
                        : ImColor.rgba(0.2f, 0.75f, 1.0f, 1.0f);
            } else {
                colorInt = isEnemy
                        ? ImColor.rgba(enemyCol[0], enemyCol[1], enemyCol[2], enemyCol[3])
                        : ImColor.rgba(teamCol[0],  teamCol[1],  teamCol[2],  teamCol[3]);
            }

            if (boxEsp.getValue()) {
                drawList.addRect(minX - 1, minY - 1, maxX + 1, maxY + 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX + 1, minY + 1, maxX - 1, maxY - 1, ImColor.rgba(0, 0, 0, 150), 0.0f, 0, 1.0f);
                drawList.addRect(minX,     minY,     maxX,     maxY,     colorInt,                   0.0f, 0, 1.0f);
            }

            if (skeletonEsp.getValue() && player.boneX.length > 0) {
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

                            boolean isBlocked = boneVisBlocked[bone1] || boneVisBlocked[bone2];
                            if (invisibleBonesOnly.getValue() && !isBlocked) {
                                continue;
                            }

                            int lineCol;
                            if (isBlocked) {
                                lineCol = isEnemy
                                        ? ImColor.rgba(1.0f, 0.2f, 0.2f, 0.65f)
                                        : ImColor.rgba(0.15f, 0.40f, 0.75f, 0.60f);
                            } else {
                                lineCol = colorInt;
                            }

                            drawList.addLine(x1 - 1, y1 - 1, x2 - 1, y2 - 1, ImColor.rgba(0, 0, 0, 120), 1.5f);
                            drawList.addLine(x1 + 1, y1 + 1, x2 + 1, y2 + 1, ImColor.rgba(0, 0, 0, 120), 1.5f);
                            drawList.addLine(x1,     y1,     x2,     y2,     lineCol,                    1.5f);
                        }
                    }
                }

                if (7 < player.boneX.length && player.boneVisible[7]) {
                    float headCX = player.boneX[7] + espOffsetX;
                    float headCY = player.boneY[7] + espOffsetY;
                    float headRadius = Math.max(3.0f, Math.min(12.0f, height / 12.0f));

                    boolean isHeadBlocked = boneVisBlocked[7];
                    if (!invisibleBonesOnly.getValue() || isHeadBlocked) {
                        int headCol;
                        if (isHeadBlocked) {
                            headCol = isEnemy
                                    ? ImColor.rgba(1.0f, 0.2f, 0.2f, 0.80f)
                                    : ImColor.rgba(0.15f, 0.40f, 0.75f, 0.75f);
                        } else {
                            headCol = colorInt;
                        }

                        drawList.addCircle(headCX, headCY, headRadius - 0.5f, ImColor.rgba(0, 0, 0, 150), 16, 1.5f);
                        drawList.addCircle(headCX, headCY, headRadius + 0.5f, ImColor.rgba(0, 0, 0, 150), 16, 1.5f);
                        drawList.addCircle(headCX, headCY, headRadius,        headCol,                    16, 1.5f);
                    }
                }
            }

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

            if (flagsEsp.getValue()) {
                renderFlags(drawList, player, minX, maxX, minY, feetX, headY);
            }
        }
    }

    private static final float FLAG_H    = 11.0f;
    private static final float FLAG_GAP  = 2.0f;
    private static final float FLAG_PAD  = 4.0f;
    private static final float FLAG_ROUNDING = 3.0f;
    private final imgui.ImVec2 flagSizeBuf = new imgui.ImVec2();

    private void renderFlags(ImDrawList drawList,
                             PlayerSnapshot player,
                             float minX, float maxX,
                             float minY, float centerX, float headY) {

        List<FlagEntry> badges = new ArrayList<>(5);

        if (flagBlind.getValue() && player.flashDuration > 0.1f && player.flashMaxAlpha > 10.0f) {
            String label = player.flashDuration > 2.0f ? "BLIND!" : "BLIND";
            badges.add(new FlagEntry(label, ImColor.rgba(0.0f, 0.95f, 1.0f, 0.88f)));
        }
        if (flagScoped.getValue() && player.isScoped) {
            badges.add(new FlagEntry("SCOPED", ImColor.rgba(0.80f, 0.20f, 1.0f, 0.88f)));
        }
        if (flagDefusing.getValue() && player.isDefusingOrPlanting) {
            if (player.team == 3) {
                badges.add(new FlagEntry("DEFUSING", ImColor.rgba(1.0f, 0.45f, 0.0f, 0.92f)));
            } else if (player.team == 2) {
                badges.add(new FlagEntry("PLANTING", ImColor.rgba(1.0f, 0.30f, 0.10f, 0.92f)));
            }
        }
        if (flagKit.getValue() && player.hasKit) {
            badges.add(new FlagEntry("KIT", ImColor.rgba(1.0f, 0.85f, 0.0f, 0.88f)));
        }
        if (flagMoney.getValue() && player.money > 0) {
            String label = "$" + player.money;
            badges.add(new FlagEntry(label, ImColor.rgba(0.25f, 0.92f, 0.35f, 0.88f)));
        }

        if (badges.isEmpty()) return;

        float cursor = headY - 16.0f + espOffsetY;

        for (int bi = badges.size() - 1; bi >= 0; bi--) {
            FlagEntry badge = badges.get(bi);

            ImGui.calcTextSize(flagSizeBuf, badge.label);
            float tw = flagSizeBuf.x;
            float pillW = tw + FLAG_PAD * 2.0f;

            float pillX = centerX + espOffsetX - pillW / 2.0f;
            float pillY = cursor - FLAG_H;

            drawList.addRectFilled(
                    pillX, pillY,
                    pillX + pillW, cursor,
                    ImColor.rgba(0.0f, 0.0f, 0.0f, 0.60f),
                    FLAG_ROUNDING);

            drawList.addRect(
                    pillX, pillY,
                    pillX + pillW, cursor,
                    badge.color,
                    FLAG_ROUNDING, 0, 1.0f);

            float tx = pillX + FLAG_PAD;
            float ty = pillY + (FLAG_H - flagSizeBuf.y) / 2.0f;
            int shadow = ImColor.rgba(0, 0, 0, 200);
            drawList.addText(tx - 1, ty,     shadow,      badge.label);
            drawList.addText(tx + 1, ty,     shadow,      badge.label);
            drawList.addText(tx,     ty - 1, shadow,      badge.label);
            drawList.addText(tx,     ty + 1, shadow,      badge.label);
            drawList.addText(tx,     ty,     badge.color, badge.label);

            cursor = pillY - FLAG_GAP;
        }
    }

    private static final class FlagEntry {
        final String label;
        final int    color;
        FlagEntry(String label, int color) { this.label = label; this.color = color; }
    }

    // ── Grenade ESP Rendering ─────────────────────────────────────────────────

    private void renderGrenadeESP(ImDrawList drawList) {
        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return;

        long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0) return;

        float serverTime = 0f;
        long engine2Base = CS2Memory.getEngine2Base();
        if (engine2Base != 0) {
            long networkClient = CS2Memory.readLong(engine2Base + CS2Offsets.dwNetworkGameClient);
            if (isValidPtr(networkClient)) {
                int serverTick = CS2Memory.readInt(networkClient + CS2Offsets.dwNetworkGameClient_serverTickCount);
                serverTime = serverTick / TICK_RATE;
            }
        }

        float[] matrix    = PlayerCache.viewMatrix;
        int     sw        = PlayerCache.screenWidth;
        int     sh        = PlayerCache.screenHeight;
        float   offX      = espOffsetX;
        float   offY      = espOffsetY;
        float   baseRadius    = CIRCLE_RADIUS;
        float   baseThickness = ARC_THICKNESS;
        float   maxDist       = maxDistance.getValue();
        float   minScaleVal   = minScale.getValue();

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

        for (int i = 64; i < 2048; i++) {
            try {
                long listEntry = CS2Memory.readLong(entityList + 8L * ((i & 0x7FFF) >> 9) + 16);
                if (listEntry == 0) continue;

                long entity = CS2Memory.readLong(listEntry + 112L * (i & 0x1FF));
                if (!isValidPtr(entity)) continue;

                String designerName = null;
                long identity = CS2Memory.readLong(entity + 0x10);
                if (isValidPtr(identity)) {
                    long namePtr = CS2Memory.readLong(identity + 0x20);
                    if (isValidPtr(namePtr)) {
                        designerName = CS2Memory.readString(namePtr, 64);
                    }
                }

                if (designerName == null || designerName.isEmpty()) continue;

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

                float detonateTime = CS2Memory.readFloat(entity + CS2Offsets.m_flDetonateTime);
                if (!Float.isFinite(detonateTime) || detonateTime < 0f) continue;

                float timeLeft = (detonateTime > 0f) ? (detonateTime - serverTime) : maxFuseTime(type);
                if (timeLeft < -0.5f || timeLeft > 30f) continue;
                timeLeft = Math.max(0f, timeLeft);

                long sceneNode = CS2Memory.readLong(entity + CS2Offsets.m_pGameSceneNode);
                if (!isValidPtr(sceneNode)) continue;

                Vector3 worldPos = CS2Memory.readVector(sceneNode + CS2Offsets.m_vecAbsOrigin);
                if (worldPos == null
                        || !Float.isFinite(worldPos.x)
                        || !Float.isFinite(worldPos.y)
                        || !Float.isFinite(worldPos.z)) continue;

                if (worldPos.x == 0f && worldPos.y == 0f && worldPos.z == 0f) continue;

                float dx   = worldPos.x - localX;
                float dy   = worldPos.y - localY;
                float dz   = worldPos.z - localZ;
                float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

                if (dist > maxDist) continue;

                final float FULL_SCALE_DIST = 300f;
                float distanceScale;
                if (dist <= FULL_SCALE_DIST) {
                    distanceScale = 1.0f;
                } else {
                    float t = (dist - FULL_SCALE_DIST) / (maxDist - FULL_SCALE_DIST);
                    distanceScale = 1.0f - (1.0f - minScaleVal) * t;
                }

                float radius    = baseRadius    * distanceScale;
                float thickness = baseThickness * distanceScale;
                float alphaScale = 0.4f + 0.6f * distanceScale;

                boolean projected = ScreenProjector.project(
                        worldPos.x, worldPos.y, worldPos.z + 5f,
                        screenOut, matrix, sw, sh);
                if (!projected) continue;

                float cx = screenOut[0] + offX;
                float cy = screenOut[1] + offY;

                drawGrenadeTimer(drawList, cx, cy, radius, thickness, alphaScale, type, timeLeft, offX, offY);

            } catch (Exception ignored) {
            }
        }
    }

    private GrenadeType classifyByDesignerName(String name) {
        if (name == null || name.isEmpty()) return GrenadeType.UNKNOWN;
        if (name.contains("hegrenade"))         return GrenadeType.HE;
        if (name.contains("flashbang"))         return GrenadeType.FLASH;
        if (name.contains("smokegrenade"))      return GrenadeType.SMOKE;
        if (name.contains("molotov"))           return GrenadeType.MOLOTOV;
        if (name.contains("incendiary"))        return GrenadeType.MOLOTOV;
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

    private float maxFuseTime(GrenadeType type) {
        switch (type) {
            case HE:      return 1.5f;
            case FLASH:   return 1.5f;
            case SMOKE:   return 2.5f;
            case MOLOTOV: return 3.0f;
            case DECOY:   return 8.0f;
            default:      return 3.0f;
        }
    }

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

    private void drawGrenadeTimer(ImDrawList drawList,
                                  float cx, float cy,
                                  float radius, float thickness,
                                  float alphaScale,
                                  GrenadeType type, float timeLeft,
                                  float offX, float offY) {

        float bgAlphaVal = BG_ALPHA * alphaScale;
        drawList.addCircleFilled(cx, cy, radius + 1, ImColor.rgba(0f, 0f, 0f, bgAlphaVal * 0.85f), 32);
        drawList.addCircle(cx, cy, radius, ImColor.rgba(0.3f, 0.3f, 0.3f, 0.55f * alphaScale), 48, 1.5f);

        float maxTime   = maxFuseTime(type);
        float fraction  = Math.min(1.0f, timeLeft / maxTime);

        double startAngle = -Math.PI / 2.0;
        double sweepAngle = 2.0 * Math.PI * fraction;

        int segsNeeded = Math.max(3, (int) (ARC_SEGMENTS * fraction) + 1);
        int vtxCount   = 0;

        for (int s = 0; s <= segsNeeded; s++) {
            double angle = startAngle + sweepAngle * ((double) s / segsNeeded);
            arcVtxX[vtxCount] = cx + (float) (Math.cos(angle) * radius);
            arcVtxY[vtxCount] = cy + (float) (Math.sin(angle) * radius);
            vtxCount++;
        }

        float[] arcRGB = arcColorRGB(type, fraction);
        int arcColFaded = ImColor.rgba(arcRGB[0], arcRGB[1], arcRGB[2], alphaScale);
        if (vtxCount >= 2) {
            for (int s = 0; s < vtxCount - 1; s++) {
                drawList.addLine(arcVtxX[s], arcVtxY[s],
                                 arcVtxX[s + 1], arcVtxY[s + 1],
                                 arcColFaded, thickness);
            }
        }

        if (vtxCount > 0) {
            float capR = thickness * 0.55f;
            drawList.addCircleFilled(arcVtxX[vtxCount - 1], arcVtxY[vtxCount - 1],
                                     capR, arcColFaded, 8);
        }

        String timeText = String.format("%.1f", timeLeft);
        ImGui.calcTextSize(nadeTextSizeBuf, timeText);
        float tx = cx - nadeTextSizeBuf.x * 0.5f;
        float ty = cy - nadeTextSizeBuf.y * 0.5f;

        int shadow = ImColor.rgba(0f, 0f, 0f, alphaScale);
        drawList.addText(tx - 1, ty - 1, shadow, timeText);
        drawList.addText(tx + 1, ty - 1, shadow, timeText);
        drawList.addText(tx - 1, ty + 1, shadow, timeText);
        drawList.addText(tx + 1, ty + 1, shadow, timeText);

        int textCol = (timeLeft < 1.5f)
                ? arcColFaded
                : ImColor.rgba(0.95f, 0.95f, 0.95f, alphaScale);
        drawList.addText(tx, ty, textCol, timeText);

        String label = getLabel(type);
        ImGui.calcTextSize(nadeTextSizeBuf, label);
        float lx = cx - nadeTextSizeBuf.x * 0.5f;
        float ly = cy + radius + 3;

        drawList.addText(lx - 1, ly + 1, shadow, label);
        drawList.addText(lx + 1, ly + 1, shadow, label);
        drawList.addText(lx, ly, arcColFaded, label);
    }

    // ── Damage ESP Rendering and Ticking ──────────────────────────────────────

    private void tickDamageESP() {
        boolean wantCard    = showDamage.getValue();
        boolean wantFloat   = showFloating.getValue();
        if (!wantCard && !wantFloat) return;

        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
        if (players == null || players.isEmpty()) {
            lastHealth.clear();
            lastCrosshairMs.clear();
            return;
        }

        long  now       = System.currentTimeMillis();
        int   localTeamVal = localTeam;
        float radius    = crosshairRadius.getValue();
        float radSq     = radius * radius;
        float cx        = PlayerCache.screenWidth  / 2.0f + espOffsetX;
        float cy        = PlayerCache.screenHeight / 2.0f + espOffsetY;
        boolean trackTeammates = showTeammates.getValue();

        for (PlayerSnapshot p : players) {
            if (p.isLocal || (!trackTeammates && p.team == localTeamVal) || !p.onScreen) continue;
            float dx = p.feetX - cx;
            float dy = (p.feetY + p.headY) * 0.5f - cy;
            if (dx * dx + dy * dy <= radSq) lastCrosshairMs.put(p.index, now);
        }

        for (PlayerSnapshot p : players) {
            if (p.isLocal || (!trackTeammates && p.team == localTeamVal)) continue;

            int key   = p.index;
            int curHp = p.health;

            if (lastHealth.containsKey(key)) {
                int delta = lastHealth.get(key) - curHp;
                if (delta > 0 && delta < 100) {
                    Long ts    = lastCrosshairMs.get(key);
                    boolean aimed = ts != null && (now - ts) <= CROSSHAIR_ATTR_WINDOW_MS;

                    if (aimed) {
                        if (wantCard) {
                            if (lastHitMs >= 0
                                    && (now - lastHitMs) > (ACCUMULATE_WINDOW_MS + FADE_MS)) {
                                accumulatedDamage = 0;
                                shotCount         = 0;
                            }
                            accumulatedDamage += delta;
                            shotCount++;
                            enemyHealthLeft = Math.max(0, curHp);
                            lastHitMs       = now;
                        }

                        if (wantFloat && p.onScreen
                                && activeFloaters.size() + pendingFloaters.size() < MAX_FLOATERS) {

                            float modelH  = p.feetY - p.headY;
                            if (modelH <= 0f) modelH = 1f;
                            float modelHW = Math.max(modelH * 0.18f, 8f);

                            float spawnX = p.feetX
                                    + (rand.nextFloat() * 2f - 1f) * modelHW;
                            float spawnY = p.headY
                                    + modelH * 0.15f
                                    + rand.nextFloat() * modelH * 0.55f;

                            float drift = (rand.nextFloat() * 2f - 1f) * 28f;

                            float relX = (spawnX - p.feetX) / modelH;
                            float relY = (spawnY - p.feetY) / modelH;
                            float relDriftX = drift / modelH;

                            pendingFloaters.add(
                                    new FloatingNumber(p.index, relX, relY, relDriftX, delta, now));
                        }
                    }
                }
            }
            lastHealth.put(key, curHp);
        }

        lastHealth.entrySet().removeIf(e -> {
            for (PlayerSnapshot p : players) if (p.index == e.getKey()) return false;
            return true;
        });
        lastCrosshairMs.entrySet().removeIf(e -> {
            for (PlayerSnapshot p : players) if (p.index == e.getKey()) return false;
            return true;
        });
    }

    private void renderDamageESP(ImDrawList dl) {
        long now = System.currentTimeMillis();

        if (showFloating.getValue()) {
            FloatingNumber fn;
            while ((fn = pendingFloaters.poll()) != null) activeFloaters.add(fn);

            float[] dc      = damageColor.getValue();
            float   fSize   = ImGui.getFontSize() * FLOAT_FONT_SCALE;

            activeFloaters.removeIf(f -> {
                long  elapsed = now - f.birthMs;
                if (elapsed >= FLOAT_LIFE_MS) return true;

                PlayerSnapshot target = null;
                for (PlayerSnapshot p : PlayerCache.renderPlayers) {
                    if (p.index == f.playerIndex) {
                        target = p;
                        break;
                    }
                }

                if (target == null || target.health <= 0) return true;
                if (!target.onScreen) return false;

                float t = elapsed / (float) FLOAT_LIFE_MS;

                float alpha;
                if (t < 0.30f) {
                    alpha = 1.0f;
                } else {
                    float ft = (t - 0.30f) / 0.70f;
                    alpha = 1.0f - ft * ft * (3f - 2f * ft);
                }
                if (alpha <= 0.01f) return false;

                float scaleMul = t < 0.20f
                        ? 1.4f - 2.0f * t
                        : 1.0f;
                float curFontSz = fSize * scaleMul;

                float modelH = target.feetY - target.headY;
                if (modelH <= 0f) modelH = 1f;

                float curX = target.feetX + (f.relX + f.relDriftX * t) * modelH;
                float curY = target.feetY + (f.relY - (FLOAT_RISE_PX / 300f) * t) * modelH;

                String txt = "-" + f.damage;
                ImGui.calcTextSize(sz, txt);
                float tw = sz.x * (curFontSz / ImGui.getFontSize());

                float tx = curX - tw / 2f;
                float ty = curY;

                int colFill = ImColor.rgba(dc[0], dc[1], dc[2], dc[3] * alpha);
                int colShdw = ImColor.rgba(0f, 0f, 0f, 0.80f * alpha);

                dl.addText(ImGui.getFont(), curFontSz, tx - 1, ty + 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx + 1, ty + 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx - 1, ty - 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx + 1, ty - 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx, ty, colFill, txt);

                return false;
            });
        }

        if (!showDamage.getValue()) return;
        if (lastHitMs < 0) return;

        long elapsed = now - lastHitMs;

        float alpha;
        if (elapsed < ACCUMULATE_WINDOW_MS) {
            alpha = 1.0f;
        } else if (elapsed < ACCUMULATE_WINDOW_MS + FADE_MS) {
            float t = (float)(elapsed - ACCUMULATE_WINDOW_MS) / FADE_MS;
            alpha = 1.0f - t * t * (3f - 2f * t);
        } else {
            return;
        }
        if (alpha <= 0.01f) return;

        int   damage = accumulatedDamage;
        int   shots  = shotCount;
        int   hpLeft = enemyHealthLeft;
        float scale  = textScale.getValue();

        float[] dc = damageColor.getValue();
        float[] sc = shotsColor.getValue();

        int colDmg    = ImColor.rgba(dc[0], dc[1], dc[2], dc[3] * alpha);
        int colShadow = ImColor.rgba(0f, 0f, 0f, 0.80f * alpha);
        int colShots  = ImColor.rgba(sc[0], sc[1], sc[2], sc[3] * alpha * 0.85f);

        float hpPct  = Math.max(0f, Math.min(1f, hpLeft / 100f));
        float barR   = 1f - hpPct;
        float barG   = hpPct;
        int   colBar = ImColor.rgba(barR, barG, 0f, alpha);
        int   colHp  = ImColor.rgba(barR + 0.1f, barG + 0.1f, 0.1f, alpha);

        float cx = PlayerCache.screenWidth  / 2.0f + espOffsetX;
        float cy = PlayerCache.screenHeight / 2.0f + espOffsetY;

        String dmgText = "-" + damage;
        String hpText  = hpLeft + " HP";
        String subText = shots == 1 ? "in 1 shot" : "in " + shots + " shots";
        float  fontSize = ImGui.getFontSize() * scale;

        ImGui.calcTextSize(sz, dmgText);
        float dmgW = sz.x * scale, dmgH = sz.y * scale;

        ImGui.calcTextSize(sz, hpText);
        float hpTextW = sz.x, hpTextH = sz.y;

        ImGui.calcTextSize(sz, subText);
        float subW = sz.x, subH = sz.y;

        float cardH = ACCENT_H + PAD_V + dmgH + ROW_GAP + BAR_H + ROW_GAP + subH + PAD_V;
        float cardX = cx - CARD_W / 2f;
        float cardY = cy + CARD_OFFSET_Y;

        dl.addRectFilled(
            cardX + SHADOW_OFF, cardY + SHADOW_OFF,
            cardX + CARD_W + SHADOW_OFF, cardY + cardH + SHADOW_OFF,
            ImColor.rgba(0f, 0f, 0f, 0.40f * alpha), CARD_R);

        dl.addRectFilled(cardX, cardY, cardX + CARD_W, cardY + cardH,
            ImColor.rgba(0.05f, 0.07f, 0.10f, 0.93f * alpha), CARD_R);

        dl.addRect(cardX, cardY, cardX + CARD_W, cardY + cardH,
            ImColor.rgba(0.18f, 0.20f, 0.26f, 0.75f * alpha), CARD_R, 0, 1f);

        dl.addRectFilled(cardX + CARD_R, cardY, cardX + CARD_W - CARD_R, cardY + ACCENT_H, colDmg);
        dl.addRectFilled(cardX,          cardY, cardX + CARD_R,           cardY + ACCENT_H, colDmg);
        dl.addRectFilled(cardX + CARD_W - CARD_R, cardY, cardX + CARD_W,  cardY + ACCENT_H, colDmg);
        dl.addRectFilled(cardX, cardY + ACCENT_H, cardX + CARD_W, cardY + ACCENT_H + 12f,
            ImColor.rgba(dc[0], dc[1], dc[2], 0.08f * alpha), 0f);

        float dmgX = cardX + (CARD_W - dmgW) / 2f;
        float dmgY = cardY + ACCENT_H + PAD_V;
        dl.addText(ImGui.getFont(), fontSize, dmgX - 1, dmgY + 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX + 1, dmgY + 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX - 1, dmgY - 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX + 1, dmgY - 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX, dmgY, colDmg, dmgText);

        float barY   = dmgY + dmgH + ROW_GAP;
        float barX   = cardX + PAD_H;
        float barW   = CARD_W - PAD_H * 2f - hpTextW - 8f;

        dl.addRectFilled(barX, barY, barX + barW, barY + BAR_H,
            ImColor.rgba(0.15f, 0.15f, 0.18f, 0.90f * alpha), BAR_R);
        if (hpPct > 0.001f) {
            int colBarL = ImColor.rgba(
                Math.min(1f, barR + 0.15f), Math.min(1f, barG + 0.15f), 0.05f, alpha);
            dl.addRectFilledMultiColor(
                barX, barY, barX + barW * hpPct, barY + BAR_H,
                colBarL, colBar, colBar, colBarL);
        }
        dl.addRect(barX, barY, barX + barW, barY + BAR_H,
            ImColor.rgba(0.25f, 0.27f, 0.32f, 0.60f * alpha), BAR_R, 0, 0.8f);

        float hpLX = barX + barW + 6f;
        float hpLY = barY + (BAR_H - hpTextH) / 2f;
        dl.addText(hpLX + 1, hpLY + 1, ImColor.rgba(0f, 0f, 0f, 0.60f * alpha), hpText);
        dl.addText(hpLX, hpLY, colHp, hpText);

        float subX = cardX + (CARD_W - subW) / 2f;
        float subY = barY + BAR_H + ROW_GAP;
        dl.addText(subX + 1, subY + 1, ImColor.rgba(0f, 0f, 0f, 0.65f * alpha), subText);
        dl.addText(subX, subY, colShots, subText);
    }
}
