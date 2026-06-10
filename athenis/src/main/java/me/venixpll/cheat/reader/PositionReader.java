package me.venixpll.cheat.reader;

import com.sun.jna.Memory;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.projection.ScreenProjector;

import java.util.List;

/**
 * High-frequency reader for player world-space positions.
 * <p>
 * Runs on the fast position-sync loop <em>without any artificial sleep</em>, so
 * that ESP screen coordinates are always as fresh as the current render frame.
 * This is the only reader that runs at full speed; {@link EntityDataReader}
 * runs
 * at ~10 Hz for the heavier entity traversal.
 * <p>
 * <h3>Per-player cost</h3>
 * For each known player pawn address, exactly <strong>one</strong>
 * {@code ReadProcessMemory} call reads the 12-byte {@code m_vOldOrigin} vector.
 * Head position is derived by adding a constant Z offset — no extra RPM call
 * needed.
 * Both points are then projected through {@link ScreenProjector} in pure Java
 * math.
 * <p>
 * <h3>No health / team reads here</h3>
 * Those fields change slowly (at human reaction speeds) and are read by
 * {@link EntityDataReader} at ~10 Hz. Mixing them into this tight loop would
 * increase kernel-call count without any visible benefit.
 * <p>
 * <b>Thread safety:</b> All static buffers and projection output arrays are
 * single-owner. This class must only be called from the fast position-sync
 * thread.
 */
public final class PositionReader {

    /**
     * Pre-allocated 12-byte buffer (3 × float) for reading a single origin vector.
     */
    private static final Memory POS_BUF = new Memory(12);

    /**
     * Reusable projection output arrays — avoids allocating a new {@code float[2]}
     * on every player projection inside the tight inner loop.
     */
    private static final float[] FEET_OUT = new float[2];
    private static final float[] HEAD_OUT = new float[2];

    /**
     * Approximate Z offset (in CS2 source units) added to the foot origin to
     * estimate the head position. Used to compute the top edge of the bounding box.
     */
    private static final float HEAD_Z_OFFSET = 72f;

    private PositionReader() {
    }

    /**
     * Updates the world position and screen-space bounding-box corners for every
     * player in the supplied list, writing results directly into each
     * {@link PlayerCache.PlayerData} object.
     * <p>
     * For each entry with a valid pawn address, a single 12-byte RPM call fetches
     * the current {@code m_vOldOrigin} vector. The head coordinate is estimated by
     * adding {@link #HEAD_Z_OFFSET} to the Z axis. Both points are projected to
     * pixel space via {@link ScreenProjector} and stored in the player data so
     * the render thread can draw them immediately on the next frame.
     * <p>
     * Players whose RPM call fails, or whose projected coordinates fall behind the
     * camera plane, are marked {@code onScreen = false} and the ESP renderer will
     * skip them cleanly.
     * <p>
     * This method is designed to be called in a tight loop with
     * {@code Thread.yield()}
     * as the only concession to the scheduler — intentionally no
     * {@code Thread.sleep}.
     *
     * @param players Snapshot of the raw player list from
     *                {@link PlayerCache#rawPlayers}.
     * @param matrix  Latest 4×4 view-projection matrix from
     *                {@link PlayerCache#viewMatrix}.
     * @param width   Current viewport width in pixels.
     * @param height  Current viewport height in pixels.
     */
    public static void updatePositions(List<PlayerCache.PlayerData> players,
            float[] matrix, int width, int height) {
        for (PlayerCache.PlayerData player : players) {

            if (player.pawnAddress == 0) {
                player.onScreen = false;
                continue;
            }

            // One RPM call — reads the 3-float origin vector (12 bytes) for this player.
            if (!CS2Memory.readInto(player.pawnAddress + CS2Offsets.m_vOldOrigin, POS_BUF, 12)) {
                player.onScreen = false;
                continue;
            }

            float px = POS_BUF.getFloat(0);
            float py = POS_BUF.getFloat(4);
            float pz = POS_BUF.getFloat(8);

            // Update pre-allocated Vector3 objects in-place to avoid GC churn
            player.position.x = px;
            player.position.y = py;
            player.position.z = pz;

            player.headWorldPos.x = px;
            player.headWorldPos.y = py;
            player.headWorldPos.z = pz + HEAD_Z_OFFSET;

            // Read individual player's view yaw (float, m_angEyeAngles + 4 bytes)
            player.yaw = CS2Memory.readFloat(player.pawnAddress + CS2Offsets.m_angEyeAngles + 4);

            boolean feetVisible = ScreenProjector.project(player.position, FEET_OUT, matrix, width, height);
            boolean headVisible = ScreenProjector.project(player.headWorldPos, HEAD_OUT, matrix, width, height);

            if (feetVisible && headVisible) {
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
