package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class Parser {
    private final List<List<TriangleCombined>> combinedList = new ArrayList<>();

    @FunctionalInterface
    private interface ElementParser<T> {
        List<T> parse(byte[] bytes);
    }

    public Parser(String path) throws IOException {
        List<String> lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);

        // Fetch triangles and vertices in parallel matching the C++ thread model
        CompletableFuture<List<List<Triangle>>> trianglesFuture = CompletableFuture.supplyAsync(() ->
                parseSection(lines, "m_Triangles", Parser::parseTriangles)
        );
        CompletableFuture<List<List<Vector3>>> verticesFuture = CompletableFuture.supplyAsync(() ->
                parseSection(lines, "m_Vertices", Parser::parseVertices)
        );

        List<List<Triangle>> trianglesList = trianglesFuture.join();
        List<List<Vector3>> verticesList = verticesFuture.join();

        int numMeshes = Math.min(trianglesList.size(), verticesList.size());
        for (int i = 0; i < numMeshes; ++i) {
            List<Triangle> triangles = trianglesList.get(i);
            List<Vector3> vertices = verticesList.get(i);
            List<TriangleCombined> combined = new ArrayList<>(triangles.size());

            for (Triangle triangle : triangles) {
                if (triangle.a >= 0 && triangle.a < vertices.size() &&
                    triangle.b >= 0 && triangle.b < vertices.size() &&
                    triangle.c >= 0 && triangle.c < vertices.size()) {
                    TriangleCombined t = new TriangleCombined();
                    t.v0 = vertices.get(triangle.a);
                    t.v1 = vertices.get(triangle.b);
                    t.v2 = vertices.get(triangle.c);
                    t.index = combined.size();
                    combined.add(t);
                }
            }
            combinedList.add(combined);
        }
    }

    public List<List<TriangleCombined>> getCombinedList() {
        return combinedList;
    }

    private static byte[] hexStringToBytes(String hex) {
        StringBuilder cleanHex = new StringBuilder(hex.length());
        for (int i = 0; i < hex.length(); i++) {
            char c = hex.charAt(i);
            if (!Character.isWhitespace(c)) {
                cleanHex.append(c);
            }
        }

        int len = cleanHex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(cleanHex.charAt(i), 16) << 4)
                                 + Character.digit(cleanHex.charAt(i + 1), 16));
        }
        return data;
    }

    private static List<Triangle> parseTriangles(byte[] bytes) {
        List<Triangle> list = new ArrayList<>(bytes.length / 12);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 12) {
            int a = buffer.getInt();
            int b = buffer.getInt();
            int c = buffer.getInt();
            list.add(new Triangle(a, b, c));
        }
        return list;
    }

    private static List<Vector3> parseVertices(byte[] bytes) {
        List<Vector3> list = new ArrayList<>(bytes.length / 12);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.remaining() >= 12) {
            float x = buffer.getFloat();
            float y = buffer.getFloat();
            float z = buffer.getFloat();
            list.add(new Vector3(x, y, z));
        }
        return list;
    }

    private static <T> List<List<T>> parseSection(List<String> lines, String sectionName, ElementParser<T> elementParser) {
        List<List<T>> elementsLists = new ArrayList<>();
        boolean inMeshSection = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.contains("m_meshes")) {
                inMeshSection = true;
            }

            if (inMeshSection && line.contains(sectionName)) {
                if (i + 1 < lines.size()) {
                    String nextLine = lines.get(i + 1);
                    if (nextLine.contains("#[")) {
                        i++; // Skip standard header line containing #[
                        StringBuilder hexBuilder = new StringBuilder();
                        while (++i < lines.size()) {
                            String hexLine = lines.get(i);
                            if (hexLine.contains("]")) {
                                break;
                            }
                            hexBuilder.append(hexLine);
                        }
                        byte[] bytes = hexStringToBytes(hexBuilder.toString());
                        elementsLists.add(elementParser.parse(bytes));
                    }
                }
            }
        }
        return elementsLists;
    }
}
