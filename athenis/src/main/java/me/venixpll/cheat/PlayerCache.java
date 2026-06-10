package me.venixpll.cheat;

import java.util.List;

/**
 * PlayerCache acts as a thread-safe data bridge between the background
 * memory-polling thread and the main rendering thread.
 */
public class PlayerCache {
    /**
     * PlayerData holds cached state information for a single player in the game.
     */
    public static class PlayerData {
        public int index;
        public int health;
        public int team;
        public String name;
        public final Vector3 position = new Vector3();
        
        public boolean isLocal;
        public boolean onScreen;
        
        // Projected screen coordinates (feet/base of box)
        public float feetX;
        public float feetY;
        
        // Projected screen coordinates (head/top of box)
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
     * Screen-ready player snapshot published by the fast position loop every iteration.
     * Contains up-to-date world positions and projected screen coordinates alongside
     * the health / team / name data carried over from the last slow-loop update.
     * <p>
     * This is the list the render thread reads — it always reflects the most recent
     * position sync without blocking on the slower entity traversal.
     */
    public static volatile List<PlayerData> players = new java.util.ArrayList<>();
    
    // Cached game view matrix
    public static final float[] viewMatrix = new float[16];
    
    // Overlay screen width matching CS2 window
    public static volatile int screenWidth = 1920;
    
    // Overlay screen height matching CS2 window
    public static volatile int screenHeight = 1080;
    
    // Status flag indicating whether the overlay is aligned and tracking CS2
    public static volatile boolean tracking = false;

    // Raw pawn memory address of the local player (used for view-angle read/write)
    public static volatile long localPlayerPawnAddress = 0L;
}
