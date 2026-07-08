package me.venixpll.cheat.reader;

import com.sun.jna.Memory;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerData;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.projection.ScreenProjector;

import java.util.List;

/**
 * High-frequency reader for player world-space positions.
 * <p>
 * Runs on the fast position-sync loop <em>without any artificial sleep</em>, so
 * that ESP screen coordinates are always as fresh as the current render frame.
 * <p>
 * <h3>Forward extrapolation — compensating for m_vOldOrigin lag</h3>
 * {@code m_vOldOrigin} is the position recorded at the <em>previous</em> server
 * tick (~15.6 ms behind at 64 Hz tick rate). Added to that is the time for the
 * RPM (ReadProcessMemory) call itself and the overlay render pipeline. For a
 * player running at 250 units/s this translates to a consistent screen-space
 * displacement that grows with proximity and movement speed.
 * <p>
 * To compensate, this reader also reads {@code m_vecVelocity} (one extra RPM
 * call,
 * 12 bytes) and advances the position forward by {@link #EXTRAPOLATION_SECONDS}
 * before projecting. The result lands very close to where the player actually
 * is on the screen at render time.
 * <p>
 * <b>Thread safety:</b> All static buffers and projection output arrays are
 * single-owner. This class must only be called from the fast position-sync
 * thread.
 */
public final class PositionReader {

    /**
     * Pre-allocated 12-byte buffer for reading a 3-float vector (origin or
     * velocity).
     */
    private static final Memory POS_BUF = new Memory(12);
    private static final Memory VEL_BUF = new Memory(12);

    /** Reusable projection output arrays. */
    private static final float[] FEET_OUT = new float[2];
    private static final float[] HEAD_OUT = new float[2];
    private static final float[] BONE_OUT = new float[2];

    /** Pre-allocated 896-byte buffer for reading 28 bones (each 32 bytes). */
    private static final Memory BONE_BUF = new Memory(896);

    /**
     * Approximate Z offset (CS2 source units) from foot origin to head.
     * Used to estimate the top edge of the ESP bounding box.
     */
    private static final float HEAD_Z_OFFSET = 72f;

    /**
     * How many seconds to extrapolate the player's position forward.
     * <p>
     * Accounts for:
     * <ul>
     * <li>m_vOldOrigin being 1 server tick behind (64 Hz → ~15.6 ms)</li>
     * <li>RPM overhead for reading origin + velocity (~1–3 ms)</li>
     * <li>Overlay render pipeline overhead (~1–2 ms)</li>
     * </ul>
     * Total target: ~20 ms = 0.020 seconds. Tune this constant if the boxes
     * consistently lead or lag — increase to push boxes further ahead, decrease
     * to pull them back.
     */
    public static volatile float EXTRAPOLATION_SECONDS = 0.020f;

    private PositionReader() {
    }

    // ── Object-pool double-buffer ─────────────────────────────────────────────
    //
    // Instead of allocating a new PlayerSnapshot on every fast-loop iteration
    // (which can run 500–1 000+ times/second), we maintain TWO pre-allocated
    // PlayerSnapshot[64] arrays plus matching bone arrays.  The writer fills
    // one pool side, then publishes a shallow-copy list of just the active
    // entries via Arrays.asList(Arrays.copyOf(pool, count)).
    //
    // The shallow-copy array is tiny (count × 8 bytes ≈ 128 bytes for 16
    // players) and is GC'd as soon as the next publish replaces it.
    // The PlayerSnapshot OBJECTS themselves are never re-allocated — only
    // their fields are updated in-place.
    //
    // This eliminates:
    //   • new PlayerSnapshot(...)   — was the #1 allocation source (1.1M/session)
    //   • new float[28]/boolean[28] — was the #2 allocation source (bone arrays)
    //   • new ArrayList<>()         — one per fast-loop iteration

    /** Maximum concurrent tracked players (CS2 server limit). */
    private static final int MAX_PLAYERS = 64;

    // Pool A and Pool B — writer alternates between them each iteration
    private static final PlayerCache.PlayerSnapshot[] POOL_A = new PlayerCache.PlayerSnapshot[MAX_PLAYERS];
    private static final PlayerCache.PlayerSnapshot[] POOL_B = new PlayerCache.PlayerSnapshot[MAX_PLAYERS];

    // Pre-allocated bone arrays per pool slot — 28 bones per player
    private static final float[][]   BONES_X_A   = new float[MAX_PLAYERS][28];
    private static final float[][]   BONES_Y_A   = new float[MAX_PLAYERS][28];
    private static final boolean[][] BONES_VIS_A = new boolean[MAX_PLAYERS][28];
    private static final float[][]   BONES_WX_A  = new float[MAX_PLAYERS][28];
    private static final float[][]   BONES_WY_A  = new float[MAX_PLAYERS][28];
    private static final float[][]   BONES_WZ_A  = new float[MAX_PLAYERS][28];

    private static final float[][]   BONES_X_B   = new float[MAX_PLAYERS][28];
    private static final float[][]   BONES_Y_B   = new float[MAX_PLAYERS][28];
    private static final boolean[][] BONES_VIS_B = new boolean[MAX_PLAYERS][28];
    private static final float[][]   BONES_WX_B  = new float[MAX_PLAYERS][28];
    private static final float[][]   BONES_WY_B  = new float[MAX_PLAYERS][28];
    private static final float[][]   BONES_WZ_B  = new float[MAX_PLAYERS][28];

    /**
     * Which pool is the "write" side: 0 → write to A; 1 → write to B.
     * Toggled after every successful publish.
     */
    private static int poolIndex = 0;

    static {
        for (int i = 0; i < MAX_PLAYERS; i++) {
            POOL_A[i] = new PlayerCache.PlayerSnapshot();
            POOL_B[i] = new PlayerCache.PlayerSnapshot();
        }
    }

    /**
     * Reads world positions + velocities, applies forward extrapolation, projects
     * to screen space, and populates an immutable-view snapshot list for the
     * current render frame — <em>zero heap allocations on the hot path</em>.
     * <p>
     * Uses a double-buffer pool: one buffer is written while the previously
     * published buffer is safely being read by the renderer.  After filling,
     * the write buffer is published atomically via
     * {@link PlayerCache#renderPlayers}.
     *
     * @param rawList Snapshot of the raw player list from {@link PlayerCache#rawPlayers}.
     * @param matrix  Latest 4×4 view-projection matrix from {@link PlayerCache#viewMatrix}.
     * @param width   Current viewport width in pixels.
     * @param height  Current viewport height in pixels.
     * @return The newly published list (same reference as {@link PlayerCache#renderPlayers}).
     */
    public static List<PlayerSnapshot> buildSnapshots(List<PlayerData> rawList,
            float[] matrix,
            int width, int height) {

        // Select the write-side pool for this iteration.
        final boolean useA = (poolIndex == 0);
        final PlayerCache.PlayerSnapshot[] pool        = useA ? POOL_A       : POOL_B;
        final float[][]   bonesX   = useA ? BONES_X_A   : BONES_X_B;
        final float[][]   bonesY   = useA ? BONES_Y_A   : BONES_Y_B;
        final boolean[][] bonesVis = useA ? BONES_VIS_A : BONES_VIS_B;
        final float[][]   bonesWX  = useA ? BONES_WX_A  : BONES_WX_B;
        final float[][]   bonesWY  = useA ? BONES_WY_A  : BONES_WY_B;
        final float[][]   bonesWZ  = useA ? BONES_WZ_A  : BONES_WZ_B;

        float dt = EXTRAPOLATION_SECONDS;
        int count = 0;

        for (PlayerData player : rawList) {
            if (count >= MAX_PLAYERS) break;
            PlayerCache.PlayerSnapshot snap = pool[count];

            if (player.pawnAddress == 0) {
                snap.update(player, 0, 0, 0, 0, false, 0, 0, 0,
                            null, null, null, null, null, null);
                count++;
                continue;
            }

            // ── Read origin (m_vOldOrigin) ────────────────────────────────────
            if (!CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vOldOrigin, POS_BUF, 12)) {
                snap.update(player, 0, 0, 0, 0, false, 0, 0, 0,
                            null, null, null, null, null, null);
                count++;
                continue;
            }
            float px = POS_BUF.getFloat(0);
            float py = POS_BUF.getFloat(4);
            float pz = POS_BUF.getFloat(8);

            // ── Read velocity (m_vecVelocity) ─────────────────────────────────
            float vx = 0, vy = 0, vz = 0;
            if (CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vecVelocity, VEL_BUF, 12)) {
                float rv = VEL_BUF.getFloat(0);
                float ry = VEL_BUF.getFloat(4);
                float rz = VEL_BUF.getFloat(8);
                final float MAX_SPEED = 3000f;
                if (Float.isFinite(rv) && Float.isFinite(ry) && Float.isFinite(rz)
                        && Math.abs(rv) < MAX_SPEED
                        && Math.abs(ry) < MAX_SPEED
                        && Math.abs(rz) < MAX_SPEED) {
                    vx = rv;
                    vy = ry;
                    vz = rz;
                }
            }

            // Sanity-check origin itself (guard against garbage RPM reads)
            if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) {
                snap.update(player, 0, 0, 0, 0, false, 0, 0, 0,
                            null, null, null, null, null, null);
                count++;
                continue;
            }

            // ── Extrapolate position forward ──────────────────────────────────
            float ex = px + vx * dt;
            float ey = py + vy * dt;
            float ez = pz + vz * dt;

            // Update PlayerData in-place for legacy modules (RadarHack etc.)
            player.position.x = ex;
            player.position.y = ey;
            player.position.z = ez;
            player.headWorldPos.x = ex;
            player.headWorldPos.y = ey;
            player.headWorldPos.z = ez + HEAD_Z_OFFSET;
            player.yaw = CS2Memory.readFloat(player.pawnAddress + CS2Offsets.m_angEyeAngles + 4);

            // ── Project extrapolated feet + head to screen space ──────────────
            boolean feetVisible = ScreenProjector.project(player.position, FEET_OUT, matrix, width, height);
            boolean headVisible = ScreenProjector.project(player.headWorldPos, HEAD_OUT, matrix, width, height);

            if (feetVisible && headVisible) {
                player.feetX = FEET_OUT[0];
                player.feetY = FEET_OUT[1];
                player.headX = HEAD_OUT[0];
                player.headY = HEAD_OUT[1];
                player.onScreen = true;

                // Use pre-allocated bone arrays for this pool slot — no new float[]/boolean[]
                float[]   slotBonesX   = bonesX[count];
                float[]   slotBonesY   = bonesY[count];
                boolean[] slotBonesVis = bonesVis[count];
                float[]   slotBonesWX  = bonesWX[count];
                float[]   slotBonesWY  = bonesWY[count];
                float[]   slotBonesWZ  = bonesWZ[count];

                boolean hasBones = false;
                long gameSceneNode = CS2Memory.readLong(player.pawnAddress + CS2Offsets.m_pGameSceneNode);
                if (gameSceneNode != 0) {
                    long boneArray = CS2Memory.readLong(gameSceneNode + CS2Offsets.m_modelState + 0x80);
                    if (boneArray != 0 && CS2Memory.readInto(boneArray, BONE_BUF, 896)) {
                        hasBones = true;
                        for (int i = 0; i < 28; i++) {
                            float bx = BONE_BUF.getFloat(i * 32);
                            float by = BONE_BUF.getFloat(i * 32 + 4);
                            float bz = BONE_BUF.getFloat(i * 32 + 8);

                            slotBonesWX[i] = bx;
                            slotBonesWY[i] = by;
                            slotBonesWZ[i] = bz;

                            boolean projected = ScreenProjector.project(bx, by, bz, BONE_OUT, matrix, width, height);
                            if (projected) {
                                slotBonesX[i]   = BONE_OUT[0];
                                slotBonesY[i]   = BONE_OUT[1];
                                slotBonesVis[i] = true;
                            } else {
                                slotBonesVis[i] = false;
                            }
                        }
                    }
                }

                snap.update(player,
                        FEET_OUT[0], FEET_OUT[1],
                        HEAD_OUT[0], HEAD_OUT[1],
                        true, vx, vy, vz,
                        hasBones ? slotBonesX   : null,
                        hasBones ? slotBonesY   : null,
                        hasBones ? slotBonesVis : null,
                        hasBones ? slotBonesWX  : null,
                        hasBones ? slotBonesWY  : null,
                        hasBones ? slotBonesWZ  : null);
            } else {
                player.onScreen = false;
                snap.update(player, 0, 0, 0, 0, false, vx, vy, vz,
                            null, null, null, null, null, null);
            }
            count++;
        }

        // Publish a shallow-copy array-backed list of exactly `count` entries.
        // Arrays.copyOf creates a tiny new array of object references (count × 8 bytes)
        // but does NOT create new PlayerSnapshot objects — pool slots are reused.
        // The renderer holds its own reference to this list and can safely iterate it
        // even while the next fast-loop iteration writes to the OTHER pool (POOL_B/POOL_A).
        // This avoids the ConcurrentModificationException that `list.clear()` caused.
        poolIndex ^= 1;
        return java.util.Arrays.asList(
                java.util.Arrays.copyOf(pool, count));
    }

    /**
     * Legacy in-place update kept for binary compatibility.
     * 
     * @deprecated Use {@link #buildSnapshots} to get immutable snapshots.
     */
    @Deprecated
    public static void updatePositions(List<PlayerData> players,
            float[] matrix, int width, int height) {
        float dt = EXTRAPOLATION_SECONDS;
        for (PlayerData player : players) {
            if (player.pawnAddress == 0) {
                player.onScreen = false;
                continue;
            }
            if (!CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vOldOrigin, POS_BUF, 12)) {
                player.onScreen = false;
                continue;
            }
            float px = POS_BUF.getFloat(0), py = POS_BUF.getFloat(4), pz = POS_BUF.getFloat(8);
            float vx = 0, vy = 0, vz = 0;
            if (CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vecVelocity, VEL_BUF, 12)) {
                vx = VEL_BUF.getFloat(0);
                vy = VEL_BUF.getFloat(4);
                vz = VEL_BUF.getFloat(8);
            }
            float ex = px + vx * dt, ey = py + vy * dt, ez = pz + vz * dt;
            player.position.x = ex;
            player.position.y = ey;
            player.position.z = ez;
            player.headWorldPos.x = ex;
            player.headWorldPos.y = ey;
            player.headWorldPos.z = ez + HEAD_Z_OFFSET;
            player.yaw = CS2Memory.readFloat(player.pawnAddress + CS2Offsets.m_angEyeAngles + 4);
            boolean fv = ScreenProjector.project(player.position, FEET_OUT, matrix, width, height);
            boolean hv = ScreenProjector.project(player.headWorldPos, HEAD_OUT, matrix, width, height);
            if (fv && hv) {
                player.feetX = FEET_OUT[0];
                player.feetY = FEET_OUT[1];
                player.headX = HEAD_OUT[0];
                player.headY = HEAD_OUT[1];
                player.onScreen = true;
            } else {
                player.onScreen = false;
            }
        }
    }
}
