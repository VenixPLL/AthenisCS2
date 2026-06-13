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

    public boolean createOptimizedFile(String rawFile, String optimizedFile) {
        try {
            Parser parser = new Parser(rawFile);
            this.meshes = parser.getCombinedList();

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
                    mesh.add(new TriangleCombined(v0, v1, v2));
                }
                meshes.add(mesh);
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
