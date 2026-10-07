package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class OptimizedGeometry {
    public List<List<TriangleCombined>> meshes = new ArrayList<>();

    /**
     * Maximum allowed length of any single edge of a triangle (in game units).
     * Triangles exceeding this are terrain / displacement surfaces that should
     * never block line-of-sight. de_mirage has displacement edges of 1900-3300
     * units; valid wall geometry in CS2 is typically well under 512 units.
     */
    public static float MAX_EDGE_LENGTH = 800.0f;

    /** Returns the squared length of the longest edge of a triangle. */
    private static float maxEdgeLengthSq(TriangleCombined tri) {
        float d01 = edgeLenSq(tri.v0, tri.v1);
        float d12 = edgeLenSq(tri.v1, tri.v2);
        float d20 = edgeLenSq(tri.v2, tri.v0);
        return Math.max(d01, Math.max(d12, d20));
    }

    private static float edgeLenSq(Vector3 a, Vector3 b) {
        float dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns true if the triangle should be kept (all edges within limit). */
    private static boolean isValidWallTriangle(TriangleCombined tri) {
        float threshold = MAX_EDGE_LENGTH * MAX_EDGE_LENGTH;
        return maxEdgeLengthSq(tri) <= threshold;
    }

    public boolean createOptimizedFile(String rawFile, String optimizedFile) {
        try {
            Parser parser = new Parser(rawFile);
            this.meshes = parser.getCombinedList();

            // Filter out displacement/terrain triangles before writing
            List<List<TriangleCombined>> filteredMeshes = new ArrayList<>();
            int totalDropped = 0;
            for (List<TriangleCombined> mesh : meshes) {
                List<TriangleCombined> filtered = new ArrayList<>(mesh.size());
                for (TriangleCombined tri : mesh) {
                    if (isValidWallTriangle(tri)) {
                        filtered.add(tri);
                    } else {
                        totalDropped++;
                    }
                }
                filteredMeshes.add(filtered);
            }
            if (totalDropped > 0) {
                System.out.printf("[OptimizedGeometry] Filtered %d terrain/displacement triangles" +
                        " (max edge > %.0f units) from %s%n", totalDropped, MAX_EDGE_LENGTH, rawFile);
            }
            this.meshes = filteredMeshes;

            // Calculate exact buffer size needed
            long totalTris = 0;
            for (List<TriangleCombined> mesh : meshes) {
                totalTris += mesh.size();
            }
            int bufferSize = (int) (8 + meshes.size() * 8 + totalTris * 36);

            ByteBuffer buffer = ByteBuffer.allocate(bufferSize).order(ByteOrder.LITTLE_ENDIAN);
            buffer.putLong(meshes.size()); // size_t numMeshes
            for (List<TriangleCombined> mesh : meshes) {
                buffer.putLong(mesh.size()); // size_t numTris
                for (TriangleCombined tri : mesh) {
                    buffer.putFloat(tri.v0.x);
                    buffer.putFloat(tri.v0.y);
                    buffer.putFloat(tri.v0.z);
                    buffer.putFloat(tri.v1.x);
                    buffer.putFloat(tri.v1.y);
                    buffer.putFloat(tri.v1.z);
                    buffer.putFloat(tri.v2.x);
                    buffer.putFloat(tri.v2.y);
                    buffer.putFloat(tri.v2.z);
                }
            }

            Files.write(Paths.get(optimizedFile), buffer.array());
            return true;
        } catch (IOException e) {
            System.err.println("Error creating optimized file: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadFromBytes(byte[] fileBytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(fileBytes).order(ByteOrder.LITTLE_ENDIAN);

            meshes.clear();
            if (buffer.remaining() < 8)
                return false;
            long numMeshes = buffer.getLong(); // size_t

            int totalDropped = 0;
            for (long i = 0; i < numMeshes; ++i) {
                if (buffer.remaining() < 8)
                    return false;
                long numTris = buffer.getLong(); // size_t

                List<TriangleCombined> mesh = new ArrayList<>((int) numTris);
                for (long j = 0; j < numTris; ++j) {
                    if (buffer.remaining() < 36)
                        return false;
                    Vector3 v0 = new Vector3(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
                    Vector3 v1 = new Vector3(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
                    Vector3 v2 = new Vector3(buffer.getFloat(), buffer.getFloat(), buffer.getFloat());
                    TriangleCombined tri = new TriangleCombined(v0, v1, v2);
                    // Filter terrain/displacement triangles — these have edges of
                    // 1000-3000+ units and should never block line-of-sight.
                    if (isValidWallTriangle(tri)) {
                        tri.index = mesh.size();
                        mesh.add(tri);
                    } else {
                        totalDropped++;
                    }
                }
                if (mesh instanceof ArrayList) {
                    ((ArrayList<?>) mesh).trimToSize();
                }
                meshes.add(mesh);
            }
            if (totalDropped > 0) {
                System.out.printf("[OptimizedGeometry] Filtered %d terrain triangles" +
                        " (max edge > %.0f units) at load time%n", totalDropped, MAX_EDGE_LENGTH);
            }
            return true;
        } catch (Exception e) {
            System.err.println("Error reading optimized geometry from bytes: " + e.getMessage());
            return false;
        }
    }

    public boolean loadFromFile(String optimizedFile) {
        try {
            byte[] fileBytes = Files.readAllBytes(Paths.get(optimizedFile));
            return loadFromBytes(fileBytes);
        } catch (IOException e) {
            System.err.println("Error reading optimized file: " + e.getMessage());
            return false;
        }
    }
}
