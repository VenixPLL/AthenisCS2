package me.venixpll.cheat.projection;

import me.venixpll.cheat.Vector3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScreenProjectorTest {

    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;

    /**
     * Minimal perspective matrix where clip-W equals world-Z:
     *   w = z,  x_clip = x,  y_clip = y.
     * Row-major layout used by CS2 dwViewMatrix:
     *   [m0 m1 m2 m3 / m4 m5 m6 m7 / .. / m12 m13 m14 m15]
     */
    private static float[] simpleMatrix() {
        float[] m = new float[16];
        m[0] = 1f;              // x row
        m[5] = 1f;              // y row
        m[14] = 1f;             // w = z
        return m;
    }

    @Test
    void originAtDepthProjectsToScreenCenter() {
        float[] out = new float[2];
        boolean ok = ScreenProjector.project(new Vector3(0, 0, 10), out, simpleMatrix(), WIDTH, HEIGHT);

        assertTrue(ok);
        assertEquals(WIDTH * 0.5f, out[0], 1e-3f);
        assertEquals(HEIGHT * 0.5f, out[1], 1e-3f);
    }

    @Test
    void positiveXMapsRightAndPositiveYMapsUp() {
        float[] out = new float[2];

        assertTrue(ScreenProjector.project(new Vector3(5, 0, 10), out, simpleMatrix(), WIDTH, HEIGHT));
        // nx = 0.5 → right half of screen
        assertEquals(WIDTH * 0.75f, out[0], 1e-3f);
        assertEquals(HEIGHT * 0.5f, out[1], 1e-3f);

        assertTrue(ScreenProjector.project(new Vector3(0, 5, 10), out, simpleMatrix(), WIDTH, HEIGHT));
        // ny = 0.5 → Y flipped → upper half of screen
        assertEquals(WIDTH * 0.5f, out[0], 1e-3f);
        assertEquals(HEIGHT * 0.25f, out[1], 1e-3f);
    }

    @Test
    void rejectsPointsBehindCamera() {
        float[] out = new float[2];
        // w = z = -10 → behind near plane
        assertFalse(ScreenProjector.project(new Vector3(0, 0, -10), out, simpleMatrix(), WIDTH, HEIGHT));
    }

    @Test
    void rejectsNearZeroW() {
        float[] out = new float[2];
        // w = 0.005 < 0.01 threshold
        assertFalse(ScreenProjector.project(new Vector3(0, 0, 0.005f), out, simpleMatrix(), WIDTH, HEIGHT));
    }

    @Test
    void rejectsNaNMatrixValues() {
        float[] bad = simpleMatrix();
        bad[14] = Float.NaN; // poisons w
        float[] out = new float[2];
        assertFalse(ScreenProjector.project(new Vector3(0, 0, 10), out, bad, WIDTH, HEIGHT));

        float[] badX = simpleMatrix();
        badX[0] = Float.NaN; // poisons clip-x only
        assertFalse(ScreenProjector.project(new Vector3(0, 0, 10), out, badX, WIDTH, HEIGHT));
    }

    @Test
    void floatCoordinateOverloadMatchesVectorOverload() {
        float[] a = new float[2];
        float[] b = new float[2];
        Vector3 p = new Vector3(3.5f, -2.25f, 42f);

        assertTrue(ScreenProjector.project(p, a, simpleMatrix(), WIDTH, HEIGHT));
        assertTrue(ScreenProjector.project(p.x, p.y, p.z, b, simpleMatrix(), WIDTH, HEIGHT));
        assertEquals(a[0], b[0], 1e-6f);
        assertEquals(a[1], b[1], 1e-6f);
    }
}