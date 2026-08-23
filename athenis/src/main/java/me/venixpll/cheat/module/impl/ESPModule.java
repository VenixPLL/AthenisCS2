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
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.module.impl.helpers.HitAttribution;
import me.venixpll.cheat.module.impl.helpers.SoundIndicatorMath;
import me.venixpll.cheat.projection.ScreenProjector;
import me.venixpll.cheat.reader.PositionReader;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;
import me.venixpll.cheat.setting.FloatSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
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

    // ── Hit Marker Settings ───────────────────────────────────────────────────
    /** Master toggle for the animated hit-marker X at screen center. */
    public final BooleanSetting showHitmarker = new BooleanSetting("Show Hit Marker", true);
    /** Color of the hit marker on regular (non-lethal) hits. */
    public final ColorSetting hitmarkerColor = new ColorSetting(
            "Hit Marker Color##dmgesp", 1.0f, 1.0f, 1.0f, 1.0f);
    /** Color of the hit marker when the hit was a killing blow. */
    public final ColorSetting killMarkerColor = new ColorSetting(
            "Kill Marker Color##dmgesp", 1.0f, 0.15f, 0.15f, 1.0f);
    /** Base radius of the hit marker strokes in pixels. */
    public final FloatSetting hitmarkerSize = new FloatSetting(
            "Hit Marker Size##dmgesp", 18f, 8f, 40f);

    // ── Kill Feed Settings ────────────────────────────────────────────────────
    /** Master toggle for the custom kill feed panel (top-right). */
    public final BooleanSetting showKillfeed = new BooleanSetting("Show Kill Feed", true);
    /** How long a kill feed row stays visible, in seconds. */
    public final FloatSetting killfeedDuration = new FloatSetting(
            "Kill Feed Duration##dmgesp", 6f, 3f, 12f);

    // ── Sound ESP Settings ────────────────────────────────────────────────────
    /** Master toggle: directional footstep indicators around the crosshair. */
    public final BooleanSetting soundEsp = new BooleanSetting("Show Sound ESP", true);
    /** When on, only enemies produce indicators — teammates stay silent. */
    public final BooleanSetting soundEnemyOnly = new BooleanSetting("Enemy Only##soundesp", true);
    /** Color of the directional wedges. */
    public final ColorSetting soundColor = new ColorSetting("Sound Color##soundesp", 1.0f, 0.30f, 0.25f, 1.0f);
    /** Radius of the indicator ring around the crosshair, in pixels. */
    public final FloatSetting soundRingRadius = new FloatSetting("Indicator Radius##soundesp", 120f, 50f, 320f);
    /** How long a footstep indicator stays visible, in seconds. */
    public final FloatSetting soundDuration = new FloatSetting("Indicator Duration##soundesp", 1.6f, 0.5f, 4.0f);
    /** Footsteps farther away than this many world units are not indicated. */
    public final FloatSetting soundMaxDistance = new FloatSetting("Max Hearing Distance##soundesp", 1600f, 300f, 5000f);
    /** Print the distance in meters next to each wedge. */
    public final BooleanSetting soundShowDistance = new BooleanSetting("Show Distance Label##soundesp", true);

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

    /**
     * Active grenade projectiles discovered by the slow-tick scan, keyed by
     * entity address. Refreshed at ~10 Hz by {@link #tickGrenadeScan()} so the
     * render thread never has to walk the full 2000-slot entity list or
     * allocate a designer-name String per entity per frame — it only reads
     * detonation time / position for the handful of cached grenades.
     * <p>Written by the slow data thread, read by the render thread → concurrent map.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, GrenadeType> grenadeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── Damage ESP Configuration/State ────────────────────────────────────────
    private static final long ACCUMULATE_WINDOW_MS = 2_000L;
    private static final long FADE_MS              = 700L;

    /**
     * Max age of a detected local shot that may still explain an observed HP
     * drop. Covers network replication delay (~1-2 server ticks) plus the
     * slow-loop sampling jitter (~100 ms).
     */
    private static final long SHOT_ATTRIBUTION_WINDOW_MS = 400L;
    /** How far a crosshair-proximity sample may predate the shot and still count. */
    private static final long PROXIMITY_GRACE_MS         = 200L;
    /**
     * A vanished enemy must stay absent for this long before its disappearance
     * is resolved. Absorbs single-tick entity-traversal glitches so transient
     * read failures never fabricate kills.
     */
    private static final long VANISH_CONFIRM_MS          = 100L;

    private static final long HITMARKER_LIFE_MS  = 450L;
    private static final long KILLMARKER_LIFE_MS = 700L;

    private static final int  KILLFEED_MAX_ENTRIES = 5;
    private static final long KILLFEED_FADE_MS     = 800L;

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

    /**
     * Per-enemy tracking state for the damage pipeline, keyed by entity index.
     * Owned exclusively by the slow data thread inside {@link #tickDamageESP()}.
     */
    private static final class TrackedEnemy {
        int    lastHealth;
        long   pawnAddress;
        String lastName       = "";
        long   lastSeenMs;
        long   lastCrosshairMs = -1L;
        long   absentSinceMs   = -1L;
    }

    private final HashMap<Integer, TrackedEnemy> tracked = new HashMap<>();

    private volatile int  accumulatedDamage = 0;
    private volatile int  shotCount         = 0;
    private volatile int  enemyHealthLeft   = 0;
    private volatile long lastHitMs         = -1L;

    // ── Shot detection / hit marker / kill feed state ─────────────────────────
    /** Last sampled {@code m_iShotsFired} value on the local pawn (-1 = unsampled). */
    private int prevShotsFired = -1;
    /** Timestamp of the most recent detected local shot (-1 = none). */
    private volatile long lastShotMs = -1L;
    /** Hit-marker trigger timestamp (-1 = none). Slow thread writes, render reads. */
    private volatile long hitMarkerMs = -1L;
    /** Whether the current hit marker represents a killing blow. */
    private volatile boolean hitMarkerKill = false;

    /** One custom kill feed row. */
    private static final class KillEntry {
        final String weapon;
        final String victim;
        final long   birthMs;

        KillEntry(String weapon, String victim, long birthMs) {
            this.weapon  = weapon;
            this.victim  = victim;
            this.birthMs = birthMs;
        }
    }

    /** Kill events produced by the slow tick, drained by the render thread. */
    private final ConcurrentLinkedQueue<KillEntry> pendingKills =
            new ConcurrentLinkedQueue<>();
    /** Render-thread-owned display list of active feed rows. */
    private final ArrayList<KillEntry> activeKills = new ArrayList<>();

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

    // ── Sound ESP Configuration/State ─────────────────────────────────────────
    /**
     * Footstep indicators are synthesized from the high-frequency position/
     * velocity pipeline instead of CS2's audio mixer (not exposed through
     * dumper offsets): the game plays footsteps whenever a player moves on the
     * ground faster than walking speed, so an enemy above
     * {@link #FOOTSTEP_MIN_SPEED} is audible and every
     * {@link #FOOTSTEP_STRIDE_UNITS} units of horizontal travel ≈ one step
     * (250 u/s run → ~0.36 s cadence, matching the real animation cycle).
     */
    /** Horizontal speed above which CS2 plays audible footsteps (max walk ≈ 130 u/s). */
    private static final float FOOTSTEP_MIN_SPEED    = 140f;
    /** Horizontal units of travel that make up one audible footstep. */
    private static final float FOOTSTEP_STRIDE_UNITS = 90f;
    /** |velZ| above this counts as airborne — no ground footsteps while jumping/falling. */
    private static final float AIRBORNE_Z_SPEED      = 100f;
    /** Position jumps larger than this between ticks are teleports, not strides. */
    private static final float STRIDE_TELEPORT_UNITS = 256f;
    /** Slow-tick trackers idle longer than this are pruned (left/died/round end). */
    private static final long  STRIDE_TTL_MS         = 1_500L;
    /** Hard cap on simultaneously rendered indicators (oldest dropped first). */
    private static final int   MAX_ACTIVE_SOUNDS     = 24;
    /** Triangles per wedge arc (angular resolution). */
    private static final int   WEDGE_SEGMENTS        = 12;
    /** Fade-in window for a freshly emitted ping, in ms. */
    private static final long  PING_FADE_IN_MS       = 80L;
    /** Lifetime fraction after which a ping starts fading out. */
    private static final float PING_FADE_START       = 0.45f;
    /** The indicator ring drifts outward by up to this factor over a ping's life. */
    private static final float PING_DRIFT_FACTOR     = 0.10f;
    /** World units → meters (1 unit = 1 inch). */
    private static final float UNITS_TO_METERS       = 0.0254f;

    /** Per-enemy footstep stride accumulation. Owned by the slow data thread. */
    private static final class StrideTracker {
        float   lastX;
        float   lastY;
        float   accumUnits;
        long    lastSeenMs;
    }

    /**
     * One synthesized footstep event. Produced by the slow tick thread and
     * drained by the render thread — same handoff pattern as
     * {@link #pendingFloaters} and {@link #pendingKills}.
     */
    static final class SoundPing {
        final float worldX;
        final float worldY;
        final long  birthMs;

        SoundPing(float worldX, float worldY, long birthMs) {
            this.worldX = worldX;
            this.worldY = worldY;
            this.birthMs = birthMs;
        }
    }

    private final HashMap<Integer, StrideTracker> strideTrackers = new HashMap<>();
    private final ConcurrentLinkedQueue<SoundPing> pendingPings =
            new ConcurrentLinkedQueue<>();
    /** Render-thread-owned display list of active footstep indicators. */
    private final ArrayList<SoundPing> activePings = new ArrayList<>();

    /** Reusable wedge vertex buffers (render thread only). */
    private final float[] wedgeInX  = new float[WEDGE_SEGMENTS + 1];
    private final float[] wedgeInY  = new float[WEDGE_SEGMENTS + 1];
    private final float[] wedgeOutX = new float[WEDGE_SEGMENTS + 1];
    private final float[] wedgeOutY = new float[WEDGE_SEGMENTS + 1];
    /** Scratch for local origin/yaw resolution ({@link #resolveLocalView}). */
    private final float[] localViewOut = new float[3];
    private final imgui.ImVec2 sndTextSizeBuf = new imgui.ImVec2();

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Instantiates the ESP module and registers settings.
     */
    public ESPModule() {
        super("ESP Overlay", ModuleCategory.EXTERNAL, MenuGroup.VISUALS, true);

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

        // ── Hit Marker Settings ─────────────────────────────────────────────
        addSetting(showHitmarker);
        addSetting(hitmarkerColor);
        addSetting(killMarkerColor);
        addSetting(hitmarkerSize);

        // ── Kill Feed Settings ──────────────────────────────────────────────
        addSetting(showKillfeed);
        addSetting(killfeedDuration);

        // ── Sound ESP Settings ──────────────────────────────────────────────
        addSetting(soundEsp);
        addSetting(soundEnemyOnly);
        addSetting(soundColor);
        addSetting(soundRingRadius);
        addSetting(soundDuration);
        addSetting(soundMaxDistance);
        addSetting(soundShowDistance);
    }

    private static boolean isValidPtr(long p) {
        return p > 0x10000L && p < 0x7FFF_FFFF_FFFFL;
    }

    @Override
    public void onTick() {
        if (!isEnabled()) return;
        if (soundEsp.getValue()) {
            tickSoundESP();
        } else if (!strideTrackers.isEmpty() || !pendingPings.isEmpty()) {
            strideTrackers.clear();
            pendingPings.clear();
        }
        if (damageEsp.getValue()) {
            tickDamageESP();
        }
        if (grenadeEsp.getValue()) {
            tickGrenadeScan();
        } else if (!grenadeCache.isEmpty()) {
            grenadeCache.clear();
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

        if (soundEsp.getValue()) {
            renderSoundESP(drawList);
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

    /**
     * Slow-tick (~10 Hz) discovery pass over the entity list. Finds active
     * grenade projectile entities and caches their address + type so the
     * render thread only touches a handful of memory reads per frame instead
     * of scanning ~2000 slots with string allocations.
     */
    private void tickGrenadeScan() {
        grenadeCache.clear();

        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return;

        long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0) return;

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

                grenadeCache.put(entity, type);

            } catch (Exception ignored) {
            }
        }
    }

    private void renderGrenadeESP(ImDrawList drawList) {
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

        // Iterate only the cached grenades (typically 0–5 entries) — the heavy
        // entity-list walk happens once per slow tick in tickGrenadeScan().
        for (Map.Entry<Long, GrenadeType> entry : grenadeCache.entrySet()) {
            try {
                long entity = entry.getKey();
                GrenadeType type = entry.getValue();
                if (!isValidPtr(entity)) continue;

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

    /**
     * Slow-tick (~10 Hz) damage pipeline:
     * <ol>
     * <li>Detect local weapon fire via {@code m_iShotsFired} transitions.</li>
     * <li>Track crosshair proximity and health deltas per enemy; attribute a
     * delta to the local player only when both the shot gate and the proximity
     * condition hold (see {@link HitAttribution}).</li>
     * <li>Resolve vanished enemies — {@code EntityDataReader} filters dead
     * players out of the snapshot list, so killing blows surface as
     * disappearances rather than HP→0 transitions.</li>
     * </ol>
     */
    private void tickDamageESP() {
        boolean wantCard   = showDamage.getValue();
        boolean wantFloat  = showFloating.getValue();
        boolean wantMarker = showHitmarker.getValue();
        boolean wantFeed   = showKillfeed.getValue();
        if (!wantCard && !wantFloat && !wantMarker && !wantFeed) {
            tracked.clear();
            return;
        }

        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
        if (players == null || players.isEmpty()) {
            tracked.clear();
            return;
        }

        long now = System.currentTimeMillis();
        int localTeamVal = localTeam;
        float radius = crosshairRadius.getValue();
        float cx = PlayerCache.screenWidth / 2.0f + espOffsetX;
        float cy = PlayerCache.screenHeight / 2.0f + espOffsetY;
        boolean trackTeammates = showTeammates.getValue();

        // ── 1. Local shot detection ──────────────────────────────────────────
        updateShotTimestamp(now);

        // ── 2. Proximity tracking + health-delta attribution ─────────────────
        for (PlayerSnapshot p : players) {
            if (p.isLocal || (!trackTeammates && p.team == localTeamVal)) continue;

            TrackedEnemy t = tracked.get(p.index);
            if (t == null) {
                // First sighting — establish baseline only, never attribute on it.
                t = new TrackedEnemy();
                t.lastHealth   = p.health;
                t.pawnAddress  = p.pawnAddress;
                t.lastName     = p.name != null ? p.name : "";
                t.lastSeenMs   = now;
                tracked.put(p.index, t);
                continue;
            }

            // Expanded-bounding-box test — stays correct when a close-range
            // model extends far past the screen center (the old midpoint-
            // distance check silently discarded those hits).
            if (p.onScreen && isUnderCrosshair(p, cx, cy, radius)) {
                t.lastCrosshairMs = now;
            }

            int curHp = p.health;
            int delta = t.lastHealth - curHp;
            if (HitAttribution.isValidDelta(delta)
                    && HitAttribution.isMine(now, lastShotMs,
                         SHOT_ATTRIBUTION_WINDOW_MS, t.lastCrosshairMs, PROXIMITY_GRACE_MS)) {

                applyAttributedDamage(delta, curHp, now);

                if (wantFloat && p.onScreen
                        && activeFloaters.size() + pendingFloaters.size() < MAX_FLOATERS) {
                    spawnFloater(p, delta, now);
                }
            }

            t.lastHealth = curHp;
            if (p.pawnAddress != 0) t.pawnAddress = p.pawnAddress;
            if (p.name != null && !p.name.isEmpty()) t.lastName = p.name;
            t.lastSeenMs     = now;
            t.absentSinceMs  = -1L;
        }

        // ── 3. Vanish handling — kills are invisible to the snapshot list ────
        // A killing blow makes EntityDataReader drop the victim entirely, so
        // the HP→0 transition is never observed here. Resolve vanished enemies:
        // confirm death via one direct pawn read, or synthesize the kill from
        // the shot gate. Multiple simultaneous vanishers indicate a round
        // restart / mass event rather than a duel — only hard-confirmed deaths
        // count there, preventing round-end phantom kills.
        int vanishers = 0;
        for (Map.Entry<Integer, TrackedEnemy> e : tracked.entrySet()) {
            if (!isPresent(players, e.getKey())) vanishers++;
        }

        Iterator<Map.Entry<Integer, TrackedEnemy>> it = tracked.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, TrackedEnemy> e = it.next();
            TrackedEnemy t = e.getValue();
            if (isPresent(players, e.getKey())) continue;

            if (t.absentSinceMs < 0L) {
                t.absentSinceMs = now;               // arm confirmation window
                continue;
            }
            if (now - t.absentSinceMs < VANISH_CONFIRM_MS) continue;

            boolean hardConfirmed = false;
            if (t.lastHealth > 0) {
                // Dead pawns usually persist briefly with m_iHealth == 0.
                // readInt returns 0 on failure too — treated as death as well,
                // but then only via the single-vanisher synthesis path below.
                int hp = CS2Memory.readInt(t.pawnAddress + CS2Offsets.m_iHealth);
                hardConfirmed = (hp == 0);
            }
            boolean mine = HitAttribution.isMine(now, lastShotMs,
                    SHOT_ATTRIBUTION_WINDOW_MS, t.lastCrosshairMs, PROXIMITY_GRACE_MS);

            if (mine && t.lastHealth > 0 && (hardConfirmed || vanishers == 1)) {
                applyAttributedDamage(t.lastHealth, 0, now);
                registerKill(t.lastName, now);
            }
            it.remove();
        }
    }

    /**
     * Samples {@code m_iShotsFired} on the local pawn and stamps
     * {@link #lastShotMs} on any increase. Sampling at ~10 Hz is sufficient:
     * the counter stays elevated for the recoil-recovery duration (hundreds of
     * ms) after firing, far longer than one sampling interval.
     */
    private void updateShotTimestamp(long now) {
        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (!isValidPtr(localPawn)) {
            prevShotsFired = -1;
            return;
        }
        int shots = CS2Memory.readInt(localPawn + CS2Offsets.m_iShotsFired);
        if (prevShotsFired >= 0 && shots > prevShotsFired) {
            lastShotMs = now;
        }
        prevShotsFired = shots;
    }

    /**
     * Expanded-bounding-box crosshair test: true when the screen center lies
     * within the player's screen-space bounding box inflated by {@code radius}.
     * Unlike a midpoint-distance test this remains correct for large
     * close-range models whose box center sits far off the aim point.
     */
    private static boolean isUnderCrosshair(PlayerSnapshot p, float cx, float cy, float radius) {
        float top    = Math.min(p.headY, p.feetY);
        float bottom = Math.max(p.headY, p.feetY);
        float height = bottom - top;
        if (height <= 0f) height = 1f;
        float halfW = Math.max(height * 0.25f, 4f); // matches renderer: width/2 = height/4

        return cx >= p.feetX - halfW - radius
            && cx <= p.feetX + halfW + radius
            && cy >= top - radius
            && cy <= bottom + radius;
    }

    private static boolean isPresent(List<PlayerSnapshot> players, int index) {
        for (PlayerSnapshot p : players) {
            if (p.index == index) return true;
        }
        return false;
    }

    /** Records an attributed hit: damage-card accumulation + hit-marker trigger. */
    private void applyAttributedDamage(int delta, int hpLeft, long now) {
        if (showDamage.getValue()) {
            if (lastHitMs >= 0 && (now - lastHitMs) > (ACCUMULATE_WINDOW_MS + FADE_MS)) {
                accumulatedDamage = 0;
                shotCount         = 0;
            }
            accumulatedDamage += delta;
            shotCount++;
            enemyHealthLeft = Math.max(0, hpLeft);
            lastHitMs       = now;
        }
        if (showHitmarker.getValue()) {
            hitMarkerMs   = now;
            hitMarkerKill = hpLeft <= 0;
        }
    }

    private void spawnFloater(PlayerSnapshot p, int delta, long now) {
        float modelH = p.feetY - p.headY;
        if (modelH <= 0f) modelH = 1f;
        float modelHW = Math.max(modelH * 0.18f, 8f);

        float spawnX = p.feetX + (rand.nextFloat() * 2f - 1f) * modelHW;
        float spawnY = p.headY + modelH * 0.15f + rand.nextFloat() * modelH * 0.55f;
        float drift  = (rand.nextFloat() * 2f - 1f) * 28f;

        float relX      = (spawnX - p.feetX) / modelH;
        float relY      = (spawnY - p.feetY) / modelH;
        float relDriftX = drift / modelH;

        pendingFloaters.add(new FloatingNumber(p.index, relX, relY, relDriftX, delta, now));
    }

    /** Enqueues a custom kill feed row with the currently held weapon's name. */
    private void registerKill(String victimName, long now) {
        if (!showKillfeed.getValue()) return;
        String weapon = resolveActiveWeaponName();
        while (pendingKills.size() >= KILLFEED_MAX_ENTRIES) pendingKills.poll();
        pendingKills.add(new KillEntry(weapon, victimName != null && !victimName.isEmpty()
                ? victimName : "?", now));
    }

    /**
     * Resolves the display name of the locally held weapon at kill time:
     * pawn → WeaponServices → ActiveWeapon handle → entity → designer name.
     * Uses the same identity/name-pointer pattern as the grenade scan.
     */
    private String resolveActiveWeaponName() {
        try {
            long localPawn = PlayerCache.localPlayerPawnAddress;
            if (!isValidPtr(localPawn)) return null;

            long weaponServices = CS2Memory.readLong(localPawn + CS2Offsets.m_pWeaponServices);
            if (!isValidPtr(weaponServices)) return null;

            int handle = CS2Memory.readInt(weaponServices + CS2Offsets.m_hActiveWeapon);
            if (handle == 0 || handle == -1) return null;

            long clientBase = CS2Memory.getClientBase();
            if (clientBase == 0) return null;
            long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
            if (entityList == 0) return null;

            long listEntry = CS2Memory.readLong(entityList + 8L * ((handle & 0x7FFF) >> 9) + 16);
            if (listEntry == 0) return null;

            long weaponEntity = CS2Memory.readLong(listEntry + 112L * (handle & 0x1FF));
            if (!isValidPtr(weaponEntity)) return null;

            long identity = CS2Memory.readLong(weaponEntity + 0x10);
            if (!isValidPtr(identity)) return null;

            long namePtr = CS2Memory.readLong(identity + 0x20);
            if (!isValidPtr(namePtr)) return null;

            String designerName = CS2Memory.readString(namePtr, 64);
            if (designerName == null || designerName.isEmpty()) return null;
            return prettifyWeaponName(designerName);
        } catch (Exception e) {
            return null;
        }
    }

    /** "weapon_ak47" → "AK47", "m4a1_silencer" → "M4A1 SILENCER". */
    private static String prettifyWeaponName(String designerName) {
        String n = designerName;
        if (n.startsWith("weapon_")) n = n.substring(7);
        n = n.replace('_', ' ').trim();
        return n.toUpperCase();
    }

    private void renderDamageESP(ImDrawList dl) {
        long now = System.currentTimeMillis();

        if (showHitmarker.getValue()) {
            renderHitMarker(dl, now);
        }

        if (showKillfeed.getValue()) {
            renderKillFeed(dl, now);
        }

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

    // ── Hit Marker Rendering ──────────────────────────────────────────────────

    /**
     * Draws the animated hit-marker X at screen center. State arrives from the
     * slow tick thread via volatiles; all drawing happens on the render thread.
     */
    private void renderHitMarker(ImDrawList dl, long now) {
        long hm = hitMarkerMs;
        if (hm < 0) return;

        boolean kill = hitMarkerKill;
        long life    = kill ? KILLMARKER_LIFE_MS : HITMARKER_LIFE_MS;
        long elapsed = now - hm;
        if (elapsed < 0 || elapsed >= life) return;

        float t     = elapsed / (float) life;
        float alpha = 1.0f - t * t * (3f - 2f * t); // smoothstep fade-out
        if (alpha <= 0.02f) return;

        float baseSize  = hitmarkerSize.getValue();
        float inner     = baseSize * (0.45f + 0.35f * t);          // center gap grows slightly
        float outer     = baseSize * (1.00f + 0.45f * t) + (kill ? 10f : 0f);
        float thickness = kill ? 3.0f : 2.2f;

        float[] hc  = kill ? killMarkerColor.getValue() : hitmarkerColor.getValue();
        int   col   = ImColor.rgba(hc[0], hc[1], hc[2], hc[3] * alpha);
        int   colBg = ImColor.rgba(0f, 0f, 0f, 0.70f * alpha);

        float cx = PlayerCache.screenWidth / 2.0f + espOffsetX;
        float cy = PlayerCache.screenHeight / 2.0f + espOffsetY;
        float invSqrt2 = 0.70710678f;

        for (int i = 0; i < 4; i++) {
            float dx = (i == 0 || i == 3) ? -1f : 1f;
            float dy = (i < 2)            ? -1f : 1f;

            float sx = cx + dx * inner * invSqrt2;
            float sy = cy + dy * inner * invSqrt2;
            float ex = cx + dx * outer * invSqrt2;
            float ey = cy + dy * outer * invSqrt2;

            dl.addLine(sx - 1f, sy + 1f, ex - 1f, ey + 1f, colBg, thickness);
            dl.addLine(sx + 1f, sy - 1f, ex + 1f, ey - 1f, colBg, thickness);
            dl.addLine(sx, sy, ex, ey, col, thickness);
        }
    }

    // ── Kill Feed Rendering ───────────────────────────────────────────────────

    /**
     * Renders the custom kill feed panel in the top-right corner. Rows are
     * produced by the slow tick into {@link #pendingKills} and drained here on
     * the render thread — same producer/consumer pattern as floating numbers.
     */
    private void renderKillFeed(ImDrawList dl, long now) {
        KillEntry k;
        while ((k = pendingKills.poll()) != null) activeKills.add(k);

        float durationMs = killfeedDuration.getValue() * 1000f;

        activeKills.removeIf(e -> now - e.birthMs >= durationMs);
        while (activeKills.size() > KILLFEED_MAX_ENTRIES) activeKills.remove(0);
        if (activeKills.isEmpty()) return;

        float rowH      = ImGui.getFontSize() + 12f;
        float padX      = 10f;
        float rightEdge = PlayerCache.screenWidth + espOffsetX - 24f;
        float y         = 20f + espOffsetY;

        // Newest entry on top → iterate backwards.
        for (int i = activeKills.size() - 1; i >= 0; i--) {
            KillEntry e = activeKills.get(i);
            float remain = durationMs - (now - e.birthMs);
            float alpha  = remain < KILLFEED_FADE_MS
                    ? Math.max(0f, remain / KILLFEED_FADE_MS)
                    : 1.0f;
            if (alpha <= 0.02f) continue;

            String killerText = "You";
            String weaponText = e.weapon != null ? "[" + e.weapon + "]" : "";
            String victimText = e.victim;

            ImGui.calcTextSize(sz, killerText);
            float killerW = sz.x;
            ImGui.calcTextSize(sz, weaponText);
            float weaponW = weaponText.isEmpty() ? 0f : sz.x;
            ImGui.calcTextSize(sz, victimText);
            float victimW = sz.x;

            float textW = killerW + (weaponW > 0f ? weaponW + 16f : 8f) + victimW;
            float rowW  = textW + padX * 2f;
            float rowX  = rightEdge - rowW;

            dl.addRectFilled(rowX, y, rowX + rowW, y + rowH,
                    ImColor.rgba(0.03f, 0.05f, 0.08f, 0.80f * alpha), 4f);
            dl.addRect(rowX, y, rowX + rowW, y + rowH,
                    ImColor.rgba(0.20f, 0.22f, 0.28f, 0.75f * alpha), 4f, 0, 1f);

            float tx = rowX + padX;
            float ty = y + (rowH - ImGui.getFontSize()) * 0.5f;

            int   shadow = ImColor.rgba(0f, 0f, 0f, 0.85f * alpha);
            float[] dc   = damageColor.getValue();
            float[] sc   = shotsColor.getValue();

            // Killer
            dl.addText(tx + 1, ty + 1, shadow, killerText);
            dl.addText(tx, ty, ImColor.rgba(dc[0], dc[1], dc[2], dc[3] * alpha), killerText);
            tx += killerW + 8f;

            // Weapon
            if (weaponW > 0f) {
                dl.addText(tx + 1, ty + 1, shadow, weaponText);
                dl.addText(tx, ty, ImColor.rgba(sc[0], sc[1], sc[2], sc[3] * 0.9f * alpha), weaponText);
                tx += weaponW + 8f;
            }

            // Victim
            dl.addText(tx + 1, ty + 1, shadow, victimText);
            dl.addText(tx, ty, ImColor.rgba(0.92f, 0.94f, 0.97f, 0.95f * alpha), victimText);

            y += rowH + 6f;
        }
    }

    // ── Sound ESP: footstep synthesis + directional rendering ────────────────

    /**
     * Slow-tick (~10 Hz) footstep synthesis. Reads only the fast loop's
     * published snapshots — never touches RPM directly. An enemy is audible
     * while groundborne ({@code |velZ|} small) and moving faster than walking
     * speed; every {@link #FOOTSTEP_STRIDE_UNITS} units of accumulated
     * horizontal travel emits one {@link SoundPing} at their world position.
     * The tracker map is owned exclusively by the slow data thread.
     */
    private void tickSoundESP() {
        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
        if (players == null || players.isEmpty()) {
            strideTrackers.clear();
            return;
        }

        long now = System.currentTimeMillis();
        float maxDist   = soundMaxDistance.getValue();
        float maxDistSq = maxDist * maxDist;
        boolean enemyOnly = soundEnemyOnly.getValue();
        int myTeam = localTeam;

        // Local origin for distance gating — already present in the snapshot list.
        float lx = 0f, ly = 0f;
        boolean haveLocal = false;
        for (PlayerSnapshot p : players) {
            if (p.isLocal) {
                lx = p.worldX;
                ly = p.worldY;
                haveLocal = Float.isFinite(lx) && Float.isFinite(ly);
                break;
            }
        }
        if (!haveLocal) return;

        for (PlayerSnapshot p : players) {
            if (p.isLocal || p.health <= 0) continue;
            if (enemyOnly && p.team == myTeam) continue;

            // Airborne (jump apex / falling / ladder pop) — no ground footsteps.
            if (Math.abs(p.velZ) > AIRBORNE_Z_SPEED) continue;

            float speedSq = p.velX * p.velX + p.velY * p.velY;
            StrideTracker st = strideTrackers.get(p.index);

            if (speedSq < FOOTSTEP_MIN_SPEED * FOOTSTEP_MIN_SPEED) {
                // Silent movement (standing / walking / crouch-walking) — drop
                // any accumulated stride so resuming cannot emit stale steps.
                if (st != null) st.accumUnits = 0f;
                continue;
            }

            if (st == null) {
                st = new StrideTracker();
                st.lastX = p.worldX;
                st.lastY = p.worldY;
                st.lastSeenMs = now;
                strideTrackers.put(p.index, st);
                continue; // first sighting only establishes the baseline
            }
            st.lastSeenMs = now;

            float dx = p.worldX - st.lastX;
            float dy = p.worldY - st.lastY;
            st.lastX = p.worldX;
            st.lastY = p.worldY;

            float moved = (float) Math.sqrt(dx * dx + dy * dy);
            if (moved > STRIDE_TELEPORT_UNITS) {
                st.accumUnits = 0f; // teleport / respawn — not a footstep
                continue;
            }
            st.accumUnits += moved;

            if (st.accumUnits >= FOOTSTEP_STRIDE_UNITS) {
                st.accumUnits -= FOOTSTEP_STRIDE_UNITS;
                float sdx = p.worldX - lx;
                float sdy = p.worldY - ly;
                if (sdx * sdx + sdy * sdy <= maxDistSq) {
                    pendingPings.add(new SoundPing(p.worldX, p.worldY, now));
                }
            }
        }

        strideTrackers.values().removeIf(t -> now - t.lastSeenMs > STRIDE_TTL_MS);
    }

    /**
     * Resolves the local player's world origin and view yaw into
     * {@code out = {x, y, yawDeg}}. Prefers the fast loop's local snapshot and
     * falls back to the same RPM reads the radar uses. Render-thread helper.
     */
    private boolean resolveLocalView(float[] out) {
        for (PlayerSnapshot p : PlayerCache.renderPlayers) {
            if (p == null || !p.isLocal) continue;
            if (Float.isFinite(p.yaw) && Float.isFinite(p.worldX) && Float.isFinite(p.worldY)) {
                out[0] = p.worldX;
                out[1] = p.worldY;
                out[2] = p.yaw;
                return true;
            }
            break;
        }
        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (!isValidPtr(localPawn)) return false;
        Vector3 origin = CS2Memory.readVector(localPawn + CS2Offsets.m_vOldOrigin);
        if (origin == null) return false;
        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return false;
        float yaw = CS2Memory.readFloat(clientBase + CS2Offsets.dwViewAngles + 4);
        if (!Float.isFinite(yaw)) return false;
        out[0] = origin.x;
        out[1] = origin.y;
        out[2] = yaw;
        return true;
    }

    /**
     * Renders directional footstep indicators on a ring around the crosshair.
     * Bearings are recomputed every frame against the CURRENT local origin/yaw,
     * so wedges stay glued to the sound's world direction while the view turns.
     */
    private void renderSoundESP(ImDrawList dl) {
        long now = System.currentTimeMillis();
        long durMs = (long) (soundDuration.getValue() * 1000f);
        if (durMs <= 0L) return;

        SoundPing ping;
        while ((ping = pendingPings.poll()) != null) {
            activePings.add(ping);
        }
        activePings.removeIf(p -> now - p.birthMs >= durMs);
        while (activePings.size() > MAX_ACTIVE_SOUNDS) activePings.remove(0);
        if (activePings.isEmpty()) return;

        if (!resolveLocalView(localViewOut)) return;
        float lx  = localViewOut[0];
        float ly  = localViewOut[1];
        float yaw = localViewOut[2];

        float cx      = PlayerCache.screenWidth / 2.0f + espOffsetX;
        float cy      = PlayerCache.screenHeight / 2.0f + espOffsetY;
        float baseR   = soundRingRadius.getValue();
        float maxDist = Math.max(1f, soundMaxDistance.getValue());
        float[] col   = soundColor.getValue();
        boolean showDist = soundShowDistance.getValue();

        for (int i = 0; i < activePings.size(); i++) {
            SoundPing p = activePings.get(i);
            long age = now - p.birthMs;
            float alpha = SoundIndicatorMath.envelope(age, durMs, PING_FADE_IN_MS, PING_FADE_START);
            if (alpha <= 0.02f) continue;

            float dx   = p.worldX - lx;
            float dy   = p.worldY - ly;
            float dist = SoundIndicatorMath.distanceUnits(dx, dy);
            float bearing = SoundIndicatorMath.bearingRadians(dx, dy, yaw);
            float prox = 1f - SoundIndicatorMath.clamp01(dist / maxDist);
            float t    = SoundIndicatorMath.clamp01(age / (float) durMs);

            drawSoundWedge(dl, cx, cy, baseR, bearing, t, alpha, prox, dist, col, showDist);
        }
    }

    /**
     * One footstep indicator: filled translucent pie wedge + triple-layer arc
     * (glow / body / bright core) + leading dot + optional distance label.
     * Closer sources render wider, thicker and brighter. Vertex buffers are
     * pre-allocated; screen mapping matches the radar (bearing 0 = up/ahead).
     */
    private void drawSoundWedge(ImDrawList dl, float cx, float cy, float baseR,
                                float bearing, float t, float alpha, float prox,
                                float dist, float[] col, boolean showDist) {
        // Ripple: ring drifts slightly outward as the ping ages.
        float r = baseR * (1f + PING_DRIFT_FACTOR * SoundIndicatorMath.easeOutCubic(t));

        // Proximity emphasis: closer → wider span, thicker stroke, whiter core.
        float spanRad   = (float) Math.toRadians(16f + 14f * prox);
        float thickness = 3.0f + 2.5f * prox;
        float lift      = 0.30f + 0.25f * prox;
        float cr = Math.min(1f, col[0] + (1f - col[0]) * lift);
        float cg = Math.min(1f, col[1] + (1f - col[1]) * lift);
        float cb = Math.min(1f, col[2] + (1f - col[2]) * lift);

        int colFill = ImColor.rgba(col[0], col[1], col[2], 0.20f * alpha);
        int colGlow = ImColor.rgba(col[0], col[1], col[2], 0.16f * alpha);
        int colBody = ImColor.rgba(cr, cg, cb, 0.85f * alpha);
        int colCore = ImColor.rgba(1f, 1f, 1f, (0.35f + 0.45f * prox) * alpha);

        float rIn = r * 0.55f;
        int n = WEDGE_SEGMENTS;
        for (int s = 0; s <= n; s++) {
            float ang = bearing - spanRad * 0.5f + spanRad * (s / (float) n);
            float sn  = (float) Math.sin(ang);
            float cs  = (float) Math.cos(ang);
            wedgeInX[s]  = cx + sn * rIn;
            wedgeInY[s]  = cy - cs * rIn;
            wedgeOutX[s] = cx + sn * r;
            wedgeOutY[s] = cy - cs * r;
        }

        for (int s = 0; s < n; s++) {
            dl.addTriangleFilled(wedgeInX[s], wedgeInY[s],
                                 wedgeOutX[s], wedgeOutY[s],
                                 wedgeOutX[s + 1], wedgeOutY[s + 1], colFill);
            dl.addTriangleFilled(wedgeInX[s], wedgeInY[s],
                                 wedgeOutX[s + 1], wedgeOutY[s + 1],
                                 wedgeInX[s + 1], wedgeInY[s + 1], colFill);
        }

        for (int s = 0; s < n; s++) {
            dl.addLine(wedgeOutX[s], wedgeOutY[s], wedgeOutX[s + 1], wedgeOutY[s + 1], colGlow, thickness * 3.2f);
        }
        for (int s = 0; s < n; s++) {
            dl.addLine(wedgeOutX[s], wedgeOutY[s], wedgeOutX[s + 1], wedgeOutY[s + 1], colBody, thickness);
        }
        for (int s = 0; s < n; s++) {
            dl.addLine(wedgeOutX[s], wedgeOutY[s], wedgeOutX[s + 1], wedgeOutY[s + 1], colCore, 1.6f);
        }

        // Leading dot at the wedge center.
        float midSin = (float) Math.sin(bearing);
        float midCos = (float) Math.cos(bearing);
        dl.addCircleFilled(cx + midSin * r, cy - midCos * r, 3.2f + 1.8f * prox, colBody, 12);

        // Optional distance label just outside the ring along the bearing.
        if (showDist) {
            String txt = String.format("%.0fm", dist * UNITS_TO_METERS);
            ImGui.calcTextSize(sndTextSizeBuf, txt);
            float lr = r + 14f;
            float tx = cx + midSin * lr - sndTextSizeBuf.x * 0.5f;
            float ty = cy - midCos * lr - sndTextSizeBuf.y * 0.5f;
            int shadow = ImColor.rgba(0f, 0f, 0f, 0.85f * alpha);
            dl.addText(tx - 1, ty + 1, shadow, txt);
            dl.addText(tx + 1, ty + 1, shadow, txt);
            dl.addText(tx - 1, ty - 1, shadow, txt);
            dl.addText(tx + 1, ty - 1, shadow, txt);
            dl.addText(tx, ty, colBody, txt);
        }
    }
}
