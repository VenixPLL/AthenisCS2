package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for the BVH visibility engine using a synthetic scene:
 * a single vertical wall at X = 100 spanning Y ∈ [-200, 200], Z ∈ [0, 300].
 */
class VisCheckTest {

    private static final Vector3 CAMERA = new Vector3(0, 0, 64);

    /** Builds the binary geometry containing the test wall (two triangles). */
    private static byte[] wallGeometryBytes() {
        float[][] tris = {
                // Triangle A
                {100, -200, 0,   100, 200, 0,   100, -200, 300},
                // Triangle B
                {100, 200, 0,    100, 200, 300, 100, -200, 300}
        };
        long totalTris = tris.length;
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(
                (int) (8 + 8 + totalTris * 36)).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putLong(1);          // one mesh
        buf.putLong(totalTris);  // two triangles
        for (float[] t : tris) {
            for (float f : t) buf.putFloat(f);
        }
        return buf.array();
    }

    private static VisCheck newVisCheck() {
        // Empty map name skips patch-file loading so tests stay hermetic.
        return new VisCheck(wallGeometryBytes(), "");
    }

    @Test
    void straightRayThroughWallIsBlocked() {
        VisCheck vc = newVisCheck();
        Vector3 target = new Vector3(200, 0, 64); // directly behind the wall
        assertFalse(vc.isPointVisible(CAMERA, target));
    }

    @Test
    void rayAroundTheWallEdgeIsVisible() {
        VisCheck vc = newVisCheck();
        // Ray to (200, 500, 64) crosses the wall plane at y = 250 — outside the wall.
        Vector3 target = new Vector3(200, 500, 64);
        assertTrue(vc.isPointVisible(CAMERA, target));
    }

    @Test
    void rayAboveTheWallIsVisible() {
        VisCheck vc = newVisCheck();
        // Ray to (200, 0, 800) crosses the wall plane (x=100, t=0.5) at
        // z = 64 + 0.5 * (800 - 64) = 432 — above the wall top (z = 300).
        Vector3 target = new Vector3(200, 0, 800);
        assertTrue(vc.isPointVisible(CAMERA, target));
    }

    @Test
    void sameSidePointsAreAlwaysVisible() {
        VisCheck vc = newVisCheck();
        assertTrue(vc.isPointVisible(CAMERA, new Vector3(50, 0, 64)));
        assertTrue(vc.isPointVisible(new Vector3(-100, -150, 20), new Vector3(-10, 80, 90)));
    }

    @Test
    void coincidentPointsAreTriviallyVisible() {
        VisCheck vc = newVisCheck();
        assertTrue(vc.isPointVisible(CAMERA, new Vector3(0, 0, 64)));
    }

    @Test
    void deletingTrianglesOpensLineOfSight() {
        VisCheck vc = newVisCheck();

        Vector3 target = new Vector3(200, 0, 64);
        assertFalse(vc.isPointVisible(CAMERA, target));

        // Delete both wall triangles in mesh 0.
        assertTrue(vc.deleteTriangle(0, 0));
        assertTrue(vc.deleteTriangle(0, 1));
        assertTrue(vc.isPointVisible(CAMERA, target),
                "line of sight should open after deleting the blocking wall");

        // Restoring re-blocks the view.
        vc.restoreAll();
        assertFalse(vc.isPointVisible(CAMERA, target));
    }

    @Test
    void castRayReportsHitDetails() {
        VisCheck vc = newVisCheck();

        VisCheck.RayHitResult hit = vc.castRay(CAMERA, new Vector3(200, 0, 64));
        assertTrue(hit.blocked);
        assertNotNull(hit.hitTriangle);
        assertEquals(0, hit.hitMeshIndex);
        assertTrue(hit.hitDistance > 0f && hit.hitDistance <= 200f);

        // Hit point must lie on the wall plane x = 100.
        assertNotNull(hit.hitPoint);
        assertEquals(100f, hit.hitPoint.x, 1e-2f);

        // Free ray away from the wall finds nothing.
        VisCheck.RayHitResult miss = vc.castRayFree(
                CAMERA, new Vector3(0f, 1f, 0f), 4096f);
        assertFalse(miss.blocked);
    }

    @Test
    void fullyFilteredMeshDoesNotCrashTraversal() {
        // One mesh whose only triangle exceeds MAX_EDGE_LENGTH → filtered out
        // at load time, leaving an empty mesh. The BVH root must exist (mesh
        // indices align with deleted-triangle keys) but must never NPE.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 + 8 + 36)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putLong(1);   // one mesh
        buf.putLong(1);   // one triangle...
        float[] huge = {0, 0, 10, 1000, 0, 10, 0, 1000, 10}; // ...oversized → filtered
        for (float f : huge) buf.putFloat(f);

        VisCheck vc = new VisCheck(buf.array(), "");
        assertTrue(vc.isPointVisible(CAMERA, new Vector3(200, 0, 64)),
                "empty-mesh BVH root must be safely traversable");
        assertTrue(vc.castRay(CAMERA, new Vector3(200, 0, 64)) != null);
    }

    @Test
    void bvhBuildHandlesEmptyAndTinyMeshes() {
        // Empty mesh buffer → zero BVH roots, no crash.
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.putLong(0);
        VisCheck empty = new VisCheck(buf.array(), "");
        assertTrue(empty.isPointVisible(CAMERA, new Vector3(200, 0, 64)));

        // Single-triangle mesh exercises the leaf-only path of the SAH builder.
        java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(8 + 8 + 36)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        one.putLong(1);
        one.putLong(1);
        float[] tri = {100, -200, 0, 100, 200, 0, 100, -200, 300};
        for (float f : tri) one.putFloat(f);
        VisCheck tiny = new VisCheck(one.array(), "");
        assertFalse(tiny.isPointVisible(CAMERA, new Vector3(200, 0, 64)));
    }
}