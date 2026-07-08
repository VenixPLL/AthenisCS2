package me.venixpll.cheat;

import java.util.Collections;
import java.util.List;

/**
 * PlayerCache acts as a thread-safe data bridge between the background
 * memory-polling thread and the main rendering thread.
 */
public class PlayerCache {

    /**
     * PlayerData holds cached state information for a single player in the game.
     * Written exclusively by the slow entity-traversal thread and the fast
     * position thread. Never read directly by the renderer — use
     * {@link PlayerSnapshot} instead.
     */
    public static class PlayerData {
        public int index;
        public int health;
        public int team;
        public String name;
        public final Vector3 position = new Vector3();

        public boolean isLocal;
        public boolean onScreen;

        // Projected screen coordinates (feet/base of box) — kept for legacy modules
        public float feetX;
        public float feetY;

        // Projected screen coordinates (head/top of box) — kept for legacy modules
        public float headX;
        public float headY;

        // 3D world-space position of the head bone (used for angle calculation)
        public final Vector3 headWorldPos = new Vector3();
        // Raw pawn memory address (needed for reading aim-punch per player)
        public long pawnAddress;
        // View yaw angle of this player
        public float yaw;
        // True if this player is carrying the C4 bomb
        public boolean hasBomb;

        // ── Player Flags ───────────────────────────────────────────────────────────────
        /** m_flFlashMaxAlpha: 0.0 = not flashed, 255.0 = fully blinded. Direct pawn field. */
        public float flashMaxAlpha;
        /** m_flFlashDuration: seconds the flash lasts; 0 when not flashed. Direct pawn field. */
        public float flashDuration;
        /** True when the player is currently looking through a weapon scope (m_nZoomLevel > 0). */
        public boolean isScoped;
        /**
         * True when m_iProgressBarDuration > 0, which means the player is actively
         * defusing or planting the bomb (the progress bar is visible on their HUD).
         */
        public boolean isDefusingOrPlanting;
        /** True when the player is carrying a defuse kit (m_bHasDefuser). */
        public boolean hasKit;
        /** Player’s current cash balance in-game ($). */
        public int money;
        /** Raw controller address – used to fetch money from InGameMoneyServices. */
        public long controllerAddress;

        /**
         * Standard constructor for player data snapshot.
         */
        public PlayerData(int index, int health, int team, String name, Vector3 position, boolean isLocal, long pawnAddress) {
            this.index = index;
            this.health = health;
            this.team = team;
            this.name = name;
            if (position != null) {
                this.position.x = position.x;
                this.position.y = position.y;
                this.position.z = position.z;
            }
            this.isLocal = isLocal;
            this.onScreen = false;
            this.pawnAddress = pawnAddress;
            this.hasBomb = false;
            this.flashMaxAlpha       = 0f;
            this.flashDuration       = 0f;
            this.isScoped            = false;
            this.isDefusingOrPlanting = false;
            this.hasKit              = false;
            this.money               = 0;
            this.controllerAddress   = 0L;
        }
    }

    /**
     * Mutable snapshot of a single player's render-ready state, built by the
     * fast position loop and consumed by the renderer.
     * <p>
     * Fields are updated in-place via {@link #update} each fast-loop iteration,
     * eliminating the constant {@code new PlayerSnapshot(...)} allocation that
     * was the primary source of the long-session memory pressure.
     * <p>
     * <b>Thread safety:</b> Only the fast position-sync thread writes these
     * fields.  The renderer and module threads only read them, and always
     * obtain a reference via the volatile {@link PlayerCache#renderPlayers}
     * swap so they always see a fully-populated, logically consistent snapshot.
     */
    public static final class PlayerSnapshot {
        public int index;
        public int health;
        public int team;
        public String name;
        public boolean isLocal;
        public boolean onScreen;

        /** Screen-space coordinates of the feet (bottom of bounding box). */
        public float feetX;
        public float feetY;
        /** Screen-space coordinates of the head (top of bounding box). */
        public float headX;
        public float headY;

        /** World-space foot position — kept for future use (distance calc, etc.). */
        public float worldX;
        public float worldY;
        public float worldZ;

        public float yaw;
        public boolean hasBomb;
        public long pawnAddress;

        /** m_flFlashMaxAlpha: 0.0 = not flashed, 255.0 = fully blinded. Direct pawn field. */
        public float flashMaxAlpha;
        /** m_flFlashDuration: seconds the flash lasts; 0 when not flashed. Direct pawn field. */
        public float flashDuration;
        /** True when the player is currently looking through a weapon scope. */
        public boolean isScoped;
        /**
         * True when m_iProgressBarDuration > 0 (defusing or planting).
         */
        public boolean isDefusingOrPlanting;
        /** True when the player is carrying a defuse kit. */
        public boolean hasKit;
        /** Player's current cash balance ($). */
        public int money;

        /** Velocity vector in world-space units/sec, read alongside origin. */
        public float velX;
        public float velY;
        public float velZ;

        // Bone coordinates in screen space
        public float[] boneX;
        public float[] boneY;
        public boolean[] boneVisible;

        // Bone coordinates in 3D world space
        public float[] boneWorldX;
        public float[] boneWorldY;
        public float[] boneWorldZ;

        static final float[]   EMPTY_FLOAT = new float[0];
        static final boolean[] EMPTY_BOOL  = new boolean[0];

        /**
         * Creates a blank (pooled) snapshot.  All fields are at their Java
         * default values; call {@link #update} before publishing to readers.
         */
        public PlayerSnapshot() {}

        /**
         * Updates all fields in-place from {@code src} and the freshly
         * projected screen coordinates.  Called by {@link PositionReader} to
         * reuse this object instead of allocating a new one.
         */
        public void update(PlayerData src,
                           float feetX, float feetY,
                           float headX, float headY,
                           boolean onScreen,
                           float velX, float velY, float velZ,
                           float[] boneX, float[] boneY, boolean[] boneVisible,
                           float[] boneWorldX, float[] boneWorldY, float[] boneWorldZ) {
            this.index       = src.index;
            this.health      = src.health;
            this.team        = src.team;
            this.name        = src.name;
            this.isLocal     = src.isLocal;
            this.onScreen    = onScreen;
            this.feetX       = feetX;
            this.feetY       = feetY;
            this.headX       = headX;
            this.headY       = headY;
            this.worldX      = src.position.x;
            this.worldY      = src.position.y;
            this.worldZ      = src.position.z;
            this.yaw         = src.yaw;
            this.hasBomb     = src.hasBomb;
            this.pawnAddress = src.pawnAddress;
            this.velX        = velX;
            this.velY        = velY;
            this.velZ        = velZ;
            this.boneX       = boneX       != null ? boneX       : EMPTY_FLOAT;
            this.boneY       = boneY       != null ? boneY       : EMPTY_FLOAT;
            this.boneVisible = boneVisible != null ? boneVisible : EMPTY_BOOL;
            this.boneWorldX  = boneWorldX  != null ? boneWorldX  : EMPTY_FLOAT;
            this.boneWorldY  = boneWorldY  != null ? boneWorldY  : EMPTY_FLOAT;
            this.boneWorldZ  = boneWorldZ  != null ? boneWorldZ  : EMPTY_FLOAT;
            // ── Player Flags ──────────────────────────────────────────────────
            this.flashMaxAlpha        = src.flashMaxAlpha;
            this.flashDuration        = src.flashDuration;
            this.isScoped             = src.isScoped;
            this.isDefusingOrPlanting = src.isDefusingOrPlanting;
            this.hasKit               = src.hasKit;
            this.money                = src.money;
        }

        // ── Legacy constructors kept for source compatibility ─────────────────
        // These allocate bone arrays and call update() so existing call sites
        // outside PositionReader continue to compile without changes.

        /** @deprecated Use the pooled path in {@link PositionReader} instead. */
        @Deprecated
        public PlayerSnapshot(PlayerData src, float feetX, float feetY,
                              float headX, float headY, boolean onScreen,
                              float velX, float velY, float velZ) {
            update(src, feetX, feetY, headX, headY, onScreen, velX, velY, velZ,
                   null, null, null, null, null, null);
        }

        /** @deprecated Use the pooled path in {@link PositionReader} instead. */
        @Deprecated
        public PlayerSnapshot(PlayerData src, float feetX, float feetY,
                              float headX, float headY, boolean onScreen,
                              float velX, float velY, float velZ,
                              float[] boneX, float[] boneY, boolean[] boneVisible) {
            update(src, feetX, feetY, headX, headY, onScreen, velX, velY, velZ,
                   boneX, boneY, boneVisible, null, null, null);
        }

        /** @deprecated Use the pooled path in {@link PositionReader} instead. */
        @Deprecated
        public PlayerSnapshot(PlayerData src, float feetX, float feetY,
                              float headX, float headY, boolean onScreen,
                              float velX, float velY, float velZ,
                              float[] boneX, float[] boneY, boolean[] boneVisible,
                              float[] boneWorldX, float[] boneWorldY, float[] boneWorldZ) {
            update(src, feetX, feetY, headX, headY, onScreen, velX, velY, velZ,
                   boneX, boneY, boneVisible, boneWorldX, boneWorldY, boneWorldZ);
        }
    }

    /**
     * Raw player metadata snapshot published by the slow entity-traversal loop (~10 Hz).
     * Contains health, team, name, and pawn addresses; positions and screen coordinates
     * start at zero and are filled in by the fast position loop on its next iteration.
     * <p>
     * Written exclusively by the slow data thread; read by the fast position thread to
     * obtain pawn addresses for per-frame origin reads.  Never written by the renderer.
     */
    public static volatile List<PlayerData> rawPlayers = new java.util.ArrayList<>();

    /**
     * Screen-ready player snapshot (legacy mutable list) published by the fast
     * position loop every iteration. Kept for compatibility with modules that
     * still reference {@code PlayerData} directly (e.g. RadarHackModule).
     */
    public static volatile List<PlayerData> players = new java.util.ArrayList<>();

    /**
     * Immutable render-ready snapshot list published by the fast position loop every
     * iteration. The renderer reads this once per frame; the volatile reference swap
     * ensures it always sees a fully-consistent, never half-written snapshot list.
     * <p>
     * This is what ESPModule reads. Never mutate entries — they are final objects.
     */
    public static volatile List<PlayerSnapshot> renderPlayers = Collections.emptyList();

    /**
     * Current view-projection matrix from CS2, stored as a {@code volatile}
     * reference. {@link me.venixpll.cheat.reader.ViewMatrixReader} writes a
     * freshly-allocated {@code float[16]} each tick and swaps the reference
     * atomically, so the renderer always reads a completely written matrix
     * and never catches it mid-update.
     */
    public static volatile float[] viewMatrix = new float[16];

    /** Overlay screen width matching CS2 window. */
    public static volatile int screenWidth = 1920;

    /** Overlay screen height matching CS2 window. */
    public static volatile int screenHeight = 1080;

    /** Status flag indicating whether the overlay is aligned and tracking CS2. */
    public static volatile boolean tracking = false;

    /** Raw pawn memory address of the local player (used for view-angle read/write). */
    public static volatile long localPlayerPawnAddress = 0L;
}
