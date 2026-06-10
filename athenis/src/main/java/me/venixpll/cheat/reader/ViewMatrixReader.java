package me.venixpll.cheat.reader;

import com.sun.jna.Memory;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;

/**
 * Reads the CS2 4×4 perspective projection (view) matrix from process memory.
 * <p>
 * Owns a single pre-allocated 64-byte native {@link Memory} buffer that is reused
 * on every call, eliminating the JNA heap allocation that would otherwise happen
 * each tick.  Because the matrix is on the hot path (read every frame to keep
 * screen projections accurate), this zero-copy approach is important for
 * maintaining low-latency position sync.
 * <p>
 * <b>Thread safety:</b> The static buffer is not synchronized.  This class must
 * only be called from the fast position-sync thread.
 */
public final class ViewMatrixReader {

    /** Pre-allocated 64-byte buffer reused for every matrix read (16 floats × 4 bytes). */
    private static final Memory BUFFER = new Memory(64);

    private ViewMatrixReader() {}

    /**
     * Reads the current 4×4 view-projection matrix from CS2 memory into
     * {@link PlayerCache#viewMatrix} using a single, zero-allocation RPM call.
     * <p>
     * Must be invoked on every fast-loop iteration so that
     * {@link PositionReader} always projects player positions with an
     * up-to-date camera transform.
     *
     * @param clientBase Cached base address of {@code client.dll}.
     */
    public static void read(long clientBase) {
        if (CS2Memory.readInto(clientBase + CS2Offsets.dwViewMatrix, BUFFER, 64)) {
            // Allocate a fresh array and swap the volatile reference atomically.
            // The renderer always reads a fully-written matrix this way — it can
            // never observe a matrix being written to concurrently.
            float[] fresh = new float[16];
            BUFFER.read(0, fresh, 0, 16);
            PlayerCache.viewMatrix = fresh;
        }
    }
}
