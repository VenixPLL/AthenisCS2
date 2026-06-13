package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class VisCheckTest {

    @Test
    public void testVectorOperations() {
        Vector3 v1 = new Vector3(1.0f, 2.0f, 3.0f);
        Vector3 v2 = new Vector3(4.0f, 5.0f, 6.0f);

        // Dot product: 1*4 + 2*5 + 3*6 = 4 + 10 + 18 = 32
        assertEquals(32.0f, v1.dot(v2), 1e-5f);

        // Cross product:
        // x = 2*6 - 3*5 = 12 - 15 = -3
        // y = 3*4 - 1*6 = 12 - 6 = 6
        // z = 1*5 - 2*4 = 5 - 8 = -3
        Vector3 cross = v1.cross(v2);
        assertEquals(-3.0f, cross.x, 1e-5f);
        assertEquals(6.0f, cross.y, 1e-5f);
        assertEquals(-3.0f, cross.z, 1e-5f);

        // Length squared: 1 + 4 + 9 = 14
        assertEquals(14.0f, v1.lengthSquared(), 1e-5f);

        // Subtraction:
        Vector3 diff = v2.subtract(v1);
        assertEquals(3.0f, diff.x, 1e-5f);
        assertEquals(3.0f, diff.y, 1e-5f);
        assertEquals(3.0f, diff.z, 1e-5f);
    }

    @Test
    public void testRayAABBIntersection() {
        AABB bounds = new AABB(new Vector3(-1.0f, -1.0f, -1.0f), new Vector3(1.0f, 1.0f, 1.0f));

        // Ray passing through center
        assertTrue(bounds.rayIntersects(new Vector3(-5.0f, 0.0f, 0.0f), new Vector3(1.0f, 0.0f, 0.0f)));

        // Ray missing bounds
        assertFalse(bounds.rayIntersects(new Vector3(-5.0f, 5.0f, 0.0f), new Vector3(1.0f, 0.0f, 0.0f)));
    }

    @Test
    public void testRayTriangleIntersection() {
        // Triangle in XY plane at Z=0
        TriangleCombined tri = new TriangleCombined(
            new Vector3(0.0f, 0.0f, 0.0f),
            new Vector3(2.0f, 0.0f, 0.0f),
            new Vector3(0.0f, 2.0f, 0.0f)
        );

        VisCheck visCheck = new VisCheck(""); // empty file path won't build BVH, but we can query rayIntersectsTriangle

        // Ray hitting triangle
        float tHit = visCheck.rayIntersectsTriangle(new Vector3(0.5f, 0.5f, -5.0f), new Vector3(0.0f, 0.0f, 1.0f), tri);
        assertTrue(tHit > 0.0f);
        assertEquals(5.0f, tHit, 1e-5f);

        // Ray missing triangle
        float tMiss = visCheck.rayIntersectsTriangle(new Vector3(2.0f, 2.0f, -5.0f), new Vector3(0.0f, 0.0f, 1.0f), tri);
        assertTrue(tMiss < 0.0f);
    }

    @Test
    public void testOptimizedGeometryRoundtrip(@TempDir Path tempDir) throws IOException {
        // Create a mock .vphys content
        String vphysContent = "m_meshes = [\n" +
                "  {\n" +
                "    m_Triangles = {\n" +
                "      #[\n" +
                "        00000000 01000000 02000000\n" + // Triangle (0, 1, 2) in hex: indices 0, 1, 2
                "      ]\n" +
                "    }\n" +
                "    m_Vertices = {\n" +
                "      #[\n" +
                "        00000000 00000000 00000000\n" + // Vector3 (0, 0, 0)
                "        00000040 00000000 00000000\n" + // Vector3 (2, 0, 0) (2.0f in IEEE 754 float: 0x40000000)
                "        00000000 00000040 00000000\n" + // Vector3 (0, 2, 0)
                "      ]\n" +
                "    }\n" +
                "  }\n" +
                "]";

        Path vphysPath = tempDir.resolve("test_map.vphys");
        Files.writeString(vphysPath, vphysContent);

        // Run parser
        Parser parser = new Parser(vphysPath.toAbsolutePath().toString());
        List<List<TriangleCombined>> meshes = parser.getCombinedList();
        assertEquals(1, meshes.size());
        assertEquals(1, meshes.get(0).size());

        TriangleCombined tri = meshes.get(0).get(0);
        assertEquals(0.0f, tri.v0.x);
        assertEquals(2.0f, tri.v1.x);
        assertEquals(2.0f, tri.v2.y);

        // Save to binary
        Path optPath = tempDir.resolve("test_map.opt");
        OptimizedGeometry geom = new OptimizedGeometry();
        assertTrue(geom.createOptimizedFile(vphysPath.toAbsolutePath().toString(), optPath.toAbsolutePath().toString()));

        // Load back
        OptimizedGeometry loadedGeom = new OptimizedGeometry();
        assertTrue(loadedGeom.loadFromFile(optPath.toAbsolutePath().toString()));
        assertEquals(1, loadedGeom.meshes.size());
        assertEquals(1, loadedGeom.meshes.get(0).size());

        TriangleCombined loadedTri = loadedGeom.meshes.get(0).get(0);
        assertEquals(tri.v0.x, loadedTri.v0.x);
        assertEquals(tri.v1.x, loadedTri.v1.x);
        assertEquals(tri.v2.y, loadedTri.v2.y);
    }

    @Test
    public void testVisCheckVisibility(@TempDir Path tempDir) throws IOException {
        // Create simple mesh: A wall blocking the line of sight along the X-axis
        // Wall at X=0, spanning Y from -5 to 5, Z from -5 to 5.
        // Represented by two triangles.
        // Triangle 1: (0, -5, -5), (0, 5, -5), (0, -5, 5)
        // Triangle 2: (0, 5, -5), (0, 5, 5), (0, -5, 5)
        // Let's encode vertices:
        // Vertex 0: (0, -5, -5) -> 0x00000000, 0xc0a00000, 0xc0a00000  (-5.0f is 0xc0a00000)
        // Vertex 1: (0,  5, -5) -> 0x00000000, 0x40a00000, 0xc0a00000  ( 5.0f is 0x40a00000)
        // Vertex 2: (0, -5,  5) -> 0x00000000, 0xc0a00000, 0x40a00000
        // Vertex 3: (0,  5,  5) -> 0x00000000, 0x40a00000, 0x40a00000
        
        // Triangle 1 indices: 0, 1, 2
        // Triangle 2 indices: 1, 3, 2

        String vphysContent = "m_meshes = [\n" +
                "  {\n" +
                "    m_Triangles = {\n" +
                "      #[\n" +
                "        00000000 01000000 02000000\n" +
                "        01000000 03000000 02000000\n" +
                "      ]\n" +
                "    }\n" +
                "    m_Vertices = {\n" +
                "      #[\n" +
                "        00000000 0000a0c0 0000a0c0\n" +
                "        00000000 0000a040 0000a0c0\n" +
                "        00000000 0000a0c0 0000a040\n" +
                "        00000000 0000a040 0000a040\n" +
                "      ]\n" +
                "    }\n" +
                "  }\n" +
                "]";

        Path vphysPath = tempDir.resolve("wall_map.vphys");
        Files.writeString(vphysPath, vphysContent);

        Path optPath = tempDir.resolve("wall_map.opt");
        OptimizedGeometry geom = new OptimizedGeometry();
        assertTrue(geom.createOptimizedFile(vphysPath.toAbsolutePath().toString(), optPath.toAbsolutePath().toString()));

        // Instantiate VisCheck
        VisCheck visCheck = new VisCheck(optPath.toAbsolutePath().toString());

        // Ray from (-10, 0, 0) to (10, 0, 0) goes straight through the wall. Visible? No.
        assertFalse(visCheck.isPointVisible(new Vector3(-10.0f, 0.0f, 0.0f), new Vector3(10.0f, 0.0f, 0.0f)));

        // Ray from (-10, 10, 0) to (10, 10, 0) misses the wall (wall bounds are Y=-5 to 5). Visible? Yes.
        assertTrue(visCheck.isPointVisible(new Vector3(-10.0f, 10.0f, 0.0f), new Vector3(10.0f, 10.0f, 0.0f)));
    }

    @Test
    public void testVisCheckAdapter() {
        // Test loading real resource file de_dust2.opt
        VisCheckAdapter.update("de_dust2");
        VisCheck visCheck = VisCheckAdapter.getVisCheck();
        assertNotNull(visCheck, "de_dust2.opt should load correctly from resources");

        // Test loading non-existent map
        VisCheckAdapter.update("non_existent_map");
        assertNull(VisCheckAdapter.getVisCheck(), "Non-existent map should result in null VisCheck instance");
    }
}
