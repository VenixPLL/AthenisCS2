package me.venixpll.cheat.projection;

import me.venixpll.cheat.Vector3;

/**
 * Stateless utility for projecting 3D world-space coordinates onto 2D screen space.
 * <p>
 * Uses the CS2 perspective projection matrix (dwViewMatrix) — a row-major 4×4 float
 * array where rows 0, 1 carry the clip-space X and Y transforms and row 3 carries W.
 * <p>
 * This class holds no state and may be called from any thread simultaneously.
 */
public final class ScreenProjector {

    private ScreenProjector() {}

    /**
     * Projects a 3D world-space position onto 2D pixel screen coordinates using
     * the supplied row-major 4×4 perspective projection matrix.
     * <p>
     * Performs a standard clip-space perspective divide, then maps normalized
     * device coordinates (NDC) to pixel space. Screen Y is flipped so that
     * higher world Z values map to lower pixel Y (top of the screen).
     *
     * @param worldPos  The 3D world coordinate to project.
     * @param screenOut A caller-supplied 2-element array that receives
     *                  {@code [pixelX, pixelY]} on success.  Contents are
     *                  undefined if the method returns {@code false}.
     * @param matrix    Flat 16-element float array representing the 4×4
     *                  view-projection matrix read from {@code dwViewMatrix}.
     * @param width     Viewport width in pixels.
     * @param height    Viewport height in pixels.
     * @return {@code true} when the point projects in front of the near clip plane
     *         (w ≥ 0.01f); {@code false} when it is behind the camera and must not
     *         be drawn.
     */
    public static boolean project(Vector3 worldPos,
                                  float[] screenOut,
                                  float[] matrix,
                                  int width, int height) {
        // Compute the clip-space W component.
        // Points with w < 0.01 are behind or on the camera near plane — reject them.
        float w = matrix[12] * worldPos.x
                + matrix[13] * worldPos.y
                + matrix[14] * worldPos.z
                + matrix[15];
        if (w < 0.01f) return false;

        // Compute clip-space X and Y from the view-projection matrix rows 0 and 1.
        float x = matrix[0] * worldPos.x + matrix[1] * worldPos.y
                + matrix[2]  * worldPos.z + matrix[3];
        float y = matrix[4] * worldPos.x + matrix[5] * worldPos.y
                + matrix[6]  * worldPos.z + matrix[7];

        // Perspective divide: transform clip coords to NDC in the range [-1, +1].
        float nx = x / w;
        float ny = y / w;

        // Map NDC to pixel space.
        // NDC X [-1..+1] → [0..width]; NDC Y is flipped because screen Y grows downward.
        screenOut[0] = (width  * 0.5f) + (nx * width  * 0.5f);
        screenOut[1] = (height * 0.5f) - (ny * height * 0.5f);
        return true;
    }
}
