package me.venixpll.cheat.reader;

import com.sun.jna.Memory;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerData;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.projection.ScreenProjector;

import java.util.ArrayList;
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
 * RPM (ReadProcessMemory) call itself and the overlay render pipeline.  For a
 * player running at 250 units/s this translates to a consistent screen-space
 * displacement that grows with proximity and movement speed.
 * <p>
 * To compensate, this reader also reads {@code m_vecVelocity} (one extra RPM call,
 * 12 bytes) and advances the position forward by {@link #EXTRAPOLATION_SECONDS}
 * before projecting.  The result lands very close to where the player actually
 * is on the screen at render time.
 * <p>
 * <b>Thread safety:</b> All static buffers and projection output arrays are
 * single-owner. This class must only be called from the fast position-sync thread.
 */
public final class PositionReader {

    /** Pre-allocated 12-byte buffer for reading a 3-float vector (origin or velocity). */
    private static final Memory POS_BUF = new Memory(12);
    private static final Memory VEL_BUF = new Memory(12);

    /** Reusable projection output arrays. */
    private static final float[] FEET_OUT = new float[2];
    private static final float[] HEAD_OUT = new float[2];

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
     *   <li>m_vOldOrigin being 1 server tick behind (64 Hz → ~15.6 ms)</li>
     *   <li>RPM overhead for reading origin + velocity (~1–3 ms)</li>
     *   <li>Overlay render pipeline overhead (~1–2 ms)</li>
     * </ul>
     * Total target: ~20 ms = 0.020 seconds.  Tune this constant if the boxes
     * consistently lead or lag — increase to push boxes further ahead, decrease
     * to pull them back.
     */
    public static volatile float EXTRAPOLATION_SECONDS = 0.020f;

    private PositionReader() {}

    /**
     * Reads world positions + velocities, applies forward extrapolation, projects
     * to screen space, and builds an immutable {@link PlayerSnapshot} list for the
     * current render frame.
     * <p>
     * The extrapolation step is:
     * <pre>
     *   extrapolatedPos = m_vOldOrigin + m_vecVelocity × EXTRAPOLATION_SECONDS
     * </pre>
     * This moves the projected ESP box to where the player will approximately be
     * when the frame is displayed on screen, compensating for the inherent read lag
     * of an external overlay.
     *
     * @param rawList Snapshot of the raw player list from {@link PlayerCache#rawPlayers}.
     * @param matrix  Latest 4×4 view-projection matrix from {@link PlayerCache#viewMatrix}.
     * @param width   Current viewport width in pixels.
     * @param height  Current viewport height in pixels.
     * @return A fresh, fully-populated immutable snapshot list for this frame.
     */
    public static List<PlayerSnapshot> buildSnapshots(List<PlayerData> rawList,
                                                      float[] matrix,
                                                      int width, int height) {
        List<PlayerSnapshot> out = new ArrayList<>(rawList.size());
        float dt = EXTRAPOLATION_SECONDS;

        for (PlayerData player : rawList) {
            if (player.pawnAddress == 0) {
                out.add(new PlayerSnapshot(player, 0, 0, 0, 0, false, 0, 0, 0));
                continue;
            }

            // ── Read origin (m_vOldOrigin) ────────────────────────────────────
            if (!CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vOldOrigin, POS_BUF, 12)) {
                out.add(new PlayerSnapshot(player, 0, 0, 0, 0, false, 0, 0, 0));
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
                // Sanity-check: reject NaN, Infinity, or physically impossible speeds.
                // CS2 max ground speed ~300 u/s; explosions/teleports up to ~3000 u/s.
                // Anything beyond that is a garbage read from a wrong/shifted offset.
                final float MAX_SPEED = 3000f;
                if (Float.isFinite(rv) && Float.isFinite(ry) && Float.isFinite(rz)
                        && Math.abs(rv) < MAX_SPEED
                        && Math.abs(ry) < MAX_SPEED
                        && Math.abs(rz) < MAX_SPEED) {
                    vx = rv; vy = ry; vz = rz;
                }
                // If validation fails we keep vx=vy=vz=0 → no extrapolation, safe.
            }

            // Sanity-check origin itself (guard against garbage RPM reads)
            if (!Float.isFinite(px) || !Float.isFinite(py) || !Float.isFinite(pz)) {
                out.add(new PlayerSnapshot(player, 0, 0, 0, 0, false, 0, 0, 0));
                continue;
            }

            // ── Extrapolate position forward ──────────────────────────────────
            // Move the origin ahead by (velocity × pipeline_latency) to compensate
            // for m_vOldOrigin being ~1 tick stale plus RPM/render overhead.
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
            boolean feetVisible = ScreenProjector.project(player.position,     FEET_OUT, matrix, width, height);
            boolean headVisible = ScreenProjector.project(player.headWorldPos,  HEAD_OUT, matrix, width, height);

            if (feetVisible && headVisible) {
                player.feetX    = FEET_OUT[0];
                player.feetY    = FEET_OUT[1];
                player.headX    = HEAD_OUT[0];
                player.headY    = HEAD_OUT[1];
                player.onScreen = true;
                out.add(new PlayerSnapshot(player,
                        FEET_OUT[0], FEET_OUT[1],
                        HEAD_OUT[0], HEAD_OUT[1],
                        true, vx, vy, vz));
            } else {
                player.onScreen = false;
                out.add(new PlayerSnapshot(player, 0, 0, 0, 0, false, vx, vy, vz));
            }
        }

        return out;
    }

    /**
     * Legacy in-place update kept for binary compatibility.
     * @deprecated Use {@link #buildSnapshots} to get immutable snapshots.
     */
    @Deprecated
    public static void updatePositions(List<PlayerData> players,
            float[] matrix, int width, int height) {
        float dt = EXTRAPOLATION_SECONDS;
        for (PlayerData player : players) {
            if (player.pawnAddress == 0) { player.onScreen = false; continue; }
            if (!CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vOldOrigin, POS_BUF, 12)) {
                player.onScreen = false; continue;
            }
            float px = POS_BUF.getFloat(0), py = POS_BUF.getFloat(4), pz = POS_BUF.getFloat(8);
            float vx = 0, vy = 0, vz = 0;
            if (CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vecVelocity, VEL_BUF, 12)) {
                vx = VEL_BUF.getFloat(0); vy = VEL_BUF.getFloat(4); vz = VEL_BUF.getFloat(8);
            }
            float ex = px + vx * dt, ey = py + vy * dt, ez = pz + vz * dt;
            player.position.x = ex; player.position.y = ey; player.position.z = ez;
            player.headWorldPos.x = ex; player.headWorldPos.y = ey;
            player.headWorldPos.z = ez + HEAD_Z_OFFSET;
            player.yaw = CS2Memory.readFloat(player.pawnAddress + CS2Offsets.m_angEyeAngles + 4);
            boolean fv = ScreenProjector.project(player.position,     FEET_OUT, matrix, width, height);
            boolean hv = ScreenProjector.project(player.headWorldPos,  HEAD_OUT, matrix, width, height);
            if (fv && hv) {
                player.feetX = FEET_OUT[0]; player.feetY = FEET_OUT[1];
                player.headX = HEAD_OUT[0]; player.headY = HEAD_OUT[1];
                player.onScreen = true;
            } else { player.onScreen = false; }
        }
    }
}
