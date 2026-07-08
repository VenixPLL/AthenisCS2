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
 * <b>Thread safety:</b> The static buffers are single-owner; this class must
 * only be called from the fast position-sync thread.
 */
public final class ViewMatrixReader {

    /** Pre-allocated 64-byte buffer reused for every matrix read (16 floats × 4 bytes). */
    private static final Memory BUFFER = new Memory(64);

    /**
     * Double-buffer for the view matrix — eliminates {@code new float[16]} allocation
     * on every fast-loop iteration.
     * <p>
     * The writer (fast position thread) always fills the buffer that is NOT currently
     * referenced by {@link PlayerCache#viewMatrix}, then atomically publishes the new
     * reference via the volatile field.  The renderer reads the previously-published
     * buffer; it is fully written and will not change until the next swap.
     * <p>
     * Indexed by {@link #bufIndex}: 0 → {@code MATRIX_A}, 1 → {@code MATRIX_B}.
     */
    private static final float[] MATRIX_A = new float[16];
    private static final float[] MATRIX_B = new float[16];

    /** Index of the buffer to write into next (0 or 1). Toggled after each successful read. */
    private static int bufIndex = 0;

    private ViewMatrixReader() {}

    /**
     * Reads the current 4×4 view-projection matrix from CS2 memory and publishes it
     * to {@link PlayerCache#viewMatrix} using a double-buffer swap — zero heap
     * allocations on the hot path.
     * <p>
     * Must be invoked on every fast-loop iteration so that
     * {@link PositionReader} always projects player positions with an
     * up-to-date camera transform.
     *
     * @param clientBase Cached base address of {@code client.dll}.
     */
    public static void read(long clientBase) {
        if (CS2Memory.readInto(clientBase + CS2Offsets.dwViewMatrix, BUFFER, 64)) {
            // Pick the write-side buffer and fill it from the native read buffer.
            // No allocation here — MATRIX_A / MATRIX_B are pre-allocated at class load.
            float[] writeTarget = (bufIndex == 0) ? MATRIX_A : MATRIX_B;
            BUFFER.read(0, writeTarget, 0, 16);
            // Atomically publish the fully-written buffer to the renderer.
            PlayerCache.viewMatrix = writeTarget;
            // Toggle so the next write uses the other buffer, keeping the
            // just-published one stable for any concurrent renderer reads.
            bufIndex ^= 1;
        }
    }
}
