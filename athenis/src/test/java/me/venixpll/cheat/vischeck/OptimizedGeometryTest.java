package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

class OptimizedGeometryTest {

    /**
     * Serialises meshes into the little-endian binary format produced by
     * {@code createOptimizedFile}: [numMeshes:8][per mesh: numTris:8 + 9 floats per tri].
     */
    private static byte[] encode(float[][][] meshes) {
        long totalTris = 0;
        for (float[][] m : meshes) totalTris += m.length;
        ByteBuffer buf = ByteBuffer.allocate(
                (int) (8 + meshes.length * 8L + totalTris * 36)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putLong(meshes.length);
        for (float[][] mesh : meshes) {
            buf.putLong(mesh.length);
            for (float[] tri : mesh) { // 9 floats: v0.xyz v1.xyz v2.xyz
                for (float f : tri) buf.putFloat(f);
            }
        }
        return buf.array();
    }

    @Test
    void roundTripPreservesTriangleCoordinates() {
        // One mesh with two small triangles (all edges well under MAX_EDGE_LENGTH).
        float[][][] source = {{
                {0, 0, 10,  100, 0, 10,  0, 100, 10},
                {100, 100, 20,  200, 100, 20,  100, 200, 20}
        }};

        OptimizedGeometry geo = new OptimizedGeometry();
        assertTrue(geo.loadFromBytes(encode(source)));

        assertEquals(1, geo.meshes.size());
        assertEquals(2, geo.meshes.get(0).size());

        TriangleCombined t0 = geo.meshes.get(0).get(0);
        assertArrayEquals(new float[]{0f, 0f, 10f}, new float[]{t0.v0.x, t0.v0.y, t0.v0.z}, 1e-4f);
        assertArrayEquals(new float[]{100f, 0f, 10f}, new float[]{t0.v1.x, t0.v1.y, t0.v1.z}, 1e-4f);
        assertArrayEquals(new float[]{0f, 100f, 10f}, new float[]{t0.v2.x, t0.v2.y, t0.v2.z}, 1e-4f);

        // Indices are assigned sequentially within a mesh.
        assertEquals(0, geo.meshes.get(0).get(0).index);
        assertEquals(1, geo.meshes.get(0).get(1).index);
    }

    @Test
    void multipleMeshesArePreserved() {
        float[][][] source = {
                {{0, 0, 5, 50, 0, 5, 0, 50, 5}},
                {{10, 10, 15, 60, 10, 15, 10, 60, 15},
                 {20, 20, 25, 70, 20, 25, 20, 70, 25}}
        };

        OptimizedGeometry geo = new OptimizedGeometry();
        assertTrue(geo.loadFromBytes(encode(source)));

        assertEquals(2, geo.meshes.size());
        assertEquals(1, geo.meshes.get(0).size());
        assertEquals(2, geo.meshes.get(1).size());
    }

    @Test
    void terrainTrianglesWithHugeEdgesAreFiltered() {
        // Edge length 1000 > MAX_EDGE_LENGTH (800) → must be dropped at load time.
        float[][][] source = {{
                {0, 0, 10,  1000, 0, 10,  0, 1000, 10},   // huge — filtered
                {0, 0, 10,  100, 0, 10,  0, 100, 10}      // fine — kept
        }};

        OptimizedGeometry geo = new OptimizedGeometry();
        assertTrue(geo.loadFromBytes(encode(source)));

        assertEquals(1, geo.meshes.size());
        assertEquals(1, geo.meshes.get(0).size(), "oversized terrain triangle should be filtered");
    }

    @Test
    void truncatedBufferFailsGracefully() {
        byte[] full = encode(new float[][][]{{{0, 0, 5, 50, 0, 5, 0, 50, 5}}});
        // Cut 10 bytes off the end — lands inside the triangle vertex data.
        byte[] truncated = Arrays.copyOf(full, full.length - 10);

        OptimizedGeometry geo = new OptimizedGeometry();
        assertFalse(geo.loadFromBytes(truncated));
    }

    @Test
    void emptyBufferFailsGracefully() {
        OptimizedGeometry geo = new OptimizedGeometry();
        assertFalse(geo.loadFromBytes(new byte[0]));
    }

    @Test
    void maxEdgeLengthConstantIsSane() {
        // Guard against accidental changes to the displacement filter threshold.
        assertTrue(OptimizedGeometry.MAX_EDGE_LENGTH >= 256f,
                "threshold too low — would filter valid wall geometry");
        assertTrue(OptimizedGeometry.MAX_EDGE_LENGTH <= 1024f,
                "threshold too high — would keep displacement terrain");
    }
}