package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class VisCheck {

    // ── Ray hit result (for visual debug) ────────────────────────

    /**
     * Carries the result of a full ray-cast including the closest blocking
     * triangle and world-space hit point. Used by VisRayDebugModule to project
     * the wall intersection onto the screen overlay.
     */
    public static final class RayHitResult {
        /** True when a triangle was found between camera and target. */
        public final boolean blocked;
        /** World-space point where the ray first hit geometry. */
        public final Vector3 hitPoint;
        /** The blocking triangle (null when not blocked). */
        public final TriangleCombined hitTriangle;
        /** Index of the BVH mesh containing the hit triangle. */
        public final int hitMeshIndex;
        /** Index of the triangle within geometry.meshes.get(hitMeshIndex). */
        public final int hitTriangleIndex;
        /** Distance from origin to the hit point (Float.MAX_VALUE if no hit). */
        public final float hitDistance;
        /** Total ray length from camera to target. */
        public final float rayDistance;

        public RayHitResult(boolean blocked, Vector3 hitPoint, TriangleCombined hitTriangle,
                            int hitMeshIndex, int hitTriangleIndex,
                            float hitDistance, float rayDistance) {
            this.blocked          = blocked;
            this.hitPoint         = hitPoint;
            this.hitTriangle      = hitTriangle;
            this.hitMeshIndex     = hitMeshIndex;
            this.hitTriangleIndex = hitTriangleIndex;
            this.hitDistance      = hitDistance;
            this.rayDistance      = rayDistance;
        }
    }

    /**
     * Threshold for leaf nodes: leaves hold up to 16 triangles.
     * Keeps tree depth low and dramatically reduces the total number of BVH nodes
     * (cutting tree memory by ~75% vs a threshold of 4) while maintaining fast SIMD/cache-friendly
     * sequential ray-triangle testing at leaf level.
     */
    private static final int LEAF_THRESHOLD = 16;

    /** Number of bins used for Surface Area Heuristic (SAH) split evaluation. */
    private static final int SAH_BINS = 12;
    /** Relative cost of traversing one BVH node (tunable). */
    private static final float SAH_TRAVERSAL_COST = 1.0f;
    /** Relative cost of one triangle intersection test (tunable). */
    private static final float SAH_INTERSECT_COST = 2.0f;

    private final OptimizedGeometry geometry = new OptimizedGeometry();
    private final List<BVHNode> bvhNodes = new ArrayList<>();

    /**
     * Set of deleted triangle IDs encoded as {@code meshIdx * 10_000_000L + triIdx}.
     * Triangles in this set are skipped by castRay() and castRayFree() so they
     * no longer block line-of-sight. They can also be highlighted in red by
     * VisRayDebugModule and exported to a patch file.
     * <p>Thread-safe: protected by the VisCheck instance lock on write; volatile
     * snapshot read is fine for the renderer.</p>
     */
    public final java.util.concurrent.ConcurrentHashMap.KeySetView<Long, Boolean>
            deletedTriangles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Encode a (meshIdx, triIdx) pair into a single long key for the deleted-set. */
    public static long encodeTriKey(int meshIdx, int triIdx) {
        return (long) meshIdx * 10_000_000L + triIdx;
    }

    /**
     * Mark the triangle at (meshIdx, triIdx) as deleted.
     * It will no longer block ray-casts and will be highlighted red by the debug overlay.
     * @return true if the triangle was not already deleted.
     */
    public boolean deleteTriangle(int meshIdx, int triIdx) {
        return deletedTriangles.add(encodeTriKey(meshIdx, triIdx));
    }

    /**
     * Restore all deleted triangles — clears the deletion set so all geometry
     * blocks ray-casts again.
     */
    public void restoreAll() {
        deletedTriangles.clear();
        System.out.println("[VisCheck] All deletions restored.");
    }

    /** @return true if the triangle at (meshIdx, triIdx) has been deleted. */
    public boolean isDeleted(int meshIdx, int triIdx) {
        return deletedTriangles.contains(encodeTriKey(meshIdx, triIdx));
    }

    /** Helper class carrying a deleted triangle and its mesh index. */
    public static final class DeletedTriangleInfo {
        public final TriangleCombined triangle;
        public final int meshIndex;
        public final int triangleIndex;

        public DeletedTriangleInfo(TriangleCombined triangle, int meshIndex, int triangleIndex) {
            this.triangle = triangle;
            this.meshIndex = meshIndex;
            this.triangleIndex = triangleIndex;
        }
    }

    /** @return list of all currently deleted triangles with their mesh index. */
    public List<DeletedTriangleInfo> getDeletedTriangles() {
        List<DeletedTriangleInfo> list = new ArrayList<>();
        for (long key : deletedTriangles) {
            int meshIdx = (int) (key / 10_000_000L);
            int triIdx  = (int) (key % 10_000_000L);
            if (meshIdx >= 0 && meshIdx < geometry.meshes.size()) {
                List<TriangleCombined> mesh = geometry.meshes.get(meshIdx);
                if (triIdx >= 0 && triIdx < mesh.size()) {
                    list.add(new DeletedTriangleInfo(mesh.get(triIdx), meshIdx, triIdx));
                }
            }
        }
        return list;
    }

    /**
     * Save the deleted triangle list to a UTF-8 JSON file.
     * Format: {"map":"...", "deleted":[{"mesh":0,"tri":5}, ...]}
     * @param filePath  Absolute path to write (will overwrite if exists).
     * @param mapName   Map name to embed in the file header.
     */
    public void saveDeletedToFile(String filePath, String mapName) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"map\": \"").append(mapName).append("\",\n");
        sb.append("  \"deleted\": [\n");
        boolean first = true;
        for (long key : deletedTriangles) {
            int mesh = (int) (key / 10_000_000L);
            int tri  = (int) (key % 10_000_000L);
            if (!first) sb.append(",\n");
            sb.append("    {\"mesh\": ").append(mesh).append(", \"tri\": ").append(tri).append("}");
            first = false;
        }
        sb.append("\n  ]\n}\n");
        try {
            java.nio.file.Files.write(
                    java.nio.file.Paths.get(filePath),
                    sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("[VisCheck] Saved " + deletedTriangles.size() +
                    " deleted triangles to " + filePath);
        } catch (Exception e) {
            System.err.println("[VisCheck] Failed to save deletions: " + e.getMessage());
        }
    }

    /**
     * Load deleted triangles from raw JSON string.
     */
    public void loadDeletedFromText(String text, String sourceLabel) {
        try {
            // Simple regex-free parser: find all {"mesh":N,"tri":M} pairs
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\{\\s*\"mesh\"\\s*:\\s*(\\d+)\\s*,\\s*\"tri\"\\s*:\\s*(\\d+)\\s*\\}")
                    .matcher(text);
            int count = 0;
            while (m.find()) {
                int mesh = Integer.parseInt(m.group(1));
                int tri  = Integer.parseInt(m.group(2));
                deletedTriangles.add(encodeTriKey(mesh, tri));
                count++;
            }
            if (count > 0) {
                System.out.println("[VisCheck] Loaded " + count + " deleted triangles from " + sourceLabel);
            }
        } catch (Exception e) {
            System.err.println("[VisCheck] Failed to parse deletions from " + sourceLabel + ": " + e.getMessage());
        }
    }

    /**
     * Load deleted triangles from a previously saved JSON patch file.
     * Adds entries to the existing deletion set (does not clear first).
     */
    public void loadDeletedFromFile(String filePath) {
        try {
            String text = new String(
                    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
                    java.nio.charset.StandardCharsets.UTF_8);
            loadDeletedFromText(text, filePath);
        } catch (Exception e) {
            System.err.println("[VisCheck] Failed to load deletions file: " + e.getMessage());
        }
    }

    /**
     * Load static deletions patch from jar resources (if present).
     * Looks in:
     * 1. /physics/patches/{mapName}_deleted.json
     * 2. /patches/{mapName}_deleted.json
     */
    public void loadStaticPatch(String mapName) {
        String[] possiblePaths = {
            "/physics/patches/" + mapName + "_deleted.json",
            "/patches/" + mapName + "_deleted.json"
        };
        for (String path : possiblePaths) {
            try (java.io.InputStream is = VisCheck.class.getResourceAsStream(path)) {
                if (is != null) {
                    byte[] bytes = is.readAllBytes();
                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                    loadDeletedFromText(text, "resource:" + path);
                    break; // stop at first match
                }
            } catch (Exception e) {
                System.err.println("[VisCheck] Failed to load resource patch " + path + ": " + e.getMessage());
            }
        }
    }

    // ── Debug configuration ──────────────────────────────────────
    /** Master toggle: set to true to enable all debug output. */
    public static volatile boolean DEBUG = false;

    /**
     * Minimum milliseconds between debug log lines for the same result category.
     * Prevents log spam at high tick rates. 0 = always print.
     */
    public static volatile long DEBUG_THROTTLE_MS = 500L;

    // ── Internal throttle state ──────────────────────────────────
    private static final AtomicLong lastDebugBlockedMs = new AtomicLong(0L);
    private static final AtomicLong lastDebugVisibleMs = new AtomicLong(0L);
    public static final AtomicLong totalRayCasts = new AtomicLong(0L);
    public static final AtomicLong totalBlocked = new AtomicLong(0L);
    public static final AtomicLong totalVisible = new AtomicLong(0L);

    public final String mapName;

    public static String getSavePath(String mapName) {
        String appData = System.getenv("APPDATA");
        if (appData == null) appData = System.getProperty("user.home");
        String dir = appData + java.io.File.separator + "Athenis" + java.io.File.separator + "patches";
        new java.io.File(dir).mkdirs();
        return dir + java.io.File.separator + mapName + "_deleted.json";
    }

    // ── Constructor (file path) ──────────────────────────────────
    public VisCheck(String optimizedGeometryFile, String mapName) {
        this.mapName = mapName;
        if (!geometry.loadFromFile(optimizedGeometryFile)) {
            System.err.println("[VisCheck] Failed to load optimized file: " + optimizedGeometryFile);
        }
        buildBVHForAllMeshes();
        logLoadStats(optimizedGeometryFile);

        // Auto-load deleted patches (merge static resources and dynamic files)
        if (mapName != null && !mapName.isEmpty()) {
            loadStaticPatch(mapName);
            String path = getSavePath(mapName);
            if (new java.io.File(path).exists()) {
                loadDeletedFromFile(path);
            }
        }

        // Post-build GC to reclaim temporary build buffers
        System.gc();
    }

    // ── Constructor (bytes) ──────────────────────────────────────
    public VisCheck(byte[] bytes, String mapName) {
        this.mapName = mapName;
        if (!geometry.loadFromBytes(bytes)) {
            System.err.println("[VisCheck] Failed to load geometry from bytes");
        }
        buildBVHForAllMeshes();
        logLoadStats("<in-memory bytes, " + bytes.length + " B>");

        // Auto-load deleted patches (merge static resources and dynamic files)
        if (mapName != null && !mapName.isEmpty()) {
            loadStaticPatch(mapName);
            String path = getSavePath(mapName);
            if (new java.io.File(path).exists()) {
                loadDeletedFromFile(path);
            }
        }

        // Post-build GC to reclaim temporary build buffers and byte array
        System.gc();
    }

    private void buildBVHForAllMeshes() {
        for (List<TriangleCombined> mesh : geometry.meshes) {
            bvhNodes.add(buildBVH(mesh));
        }
    }

    private void logLoadStats(String source) {
        int totalTris = 0;
        for (List<TriangleCombined> mesh : geometry.meshes)
            totalTris += mesh.size();
        System.out.printf("[VisCheck] Loaded geometry from %s — meshes=%d  triangles=%d  BVH-roots=%d%n",
                source, geometry.meshes.size(), totalTris, bvhNodes.size());
    }

    // ── Core visibility check ────────────────────────────────────

    /**
     * Full ray-cast that always collects hit information regardless of DEBUG flag.
     * Used by the visual debug overlay to project the wall hit point to screen.
     * Slightly more expensive than {@link #isPointVisible} (always tracks hit tri).
     *
     * @param camera  Ray origin (local player eye position).
     * @param target  Ray end point (enemy head / chest).
     * @return A {@link RayHitResult} containing blocked status, hit point, and triangle.
     */
    public RayHitResult castRay(Vector3 camera, Vector3 target) {
        Vector3 rayDir = target.subtract(camera);
        float distance = (float) Math.sqrt(rayDir.dot(rayDir));

        if (distance < 1e-5f) {
            return new RayHitResult(false, camera, null, -1, -1, 0f, 0f);
        }

        Vector3 normDir = new Vector3(
                rayDir.x / distance,
                rayDir.y / distance,
                rayDir.z / distance);

        return castRayInternal(camera, normDir, distance);
    }

    /**
     * Casts an unbounded ray from {@code origin} in direction {@code normDir}
     * up to {@code maxDistance} units. Used for the crosshair-look ray where
     * there is no specific target point — the ray just probes the geometry.
     *
     * @param origin      Ray start (eye position).
     * @param normDir     Normalised ray direction.
     * @param maxDistance Max probe distance in game units.
     * @return A {@link RayHitResult} with the closest geometry hit, or an
     *         un-blocked result if no triangle was found within range.
     */
    public RayHitResult castRayFree(Vector3 origin, Vector3 normDir, float maxDistance) {
        return castRayInternal(origin, normDir, maxDistance);
    }

    /** Shared implementation for both castRay and castRayFree. */
    private RayHitResult castRayInternal(Vector3 origin, Vector3 normDir, float maxDist) {
        float[]           hitDist = { Float.MAX_VALUE };
        TriangleCombined[] hitTri  = { null };
        int[]              hitMesh = { -1 };
        int[]              hitTriIdx = { -1 };
        boolean blocked = false;

        for (int meshIdx = 0; meshIdx < bvhNodes.size(); meshIdx++) {
            DebugIntersectResult res = intersectBVHDebug(
                    bvhNodes.get(meshIdx), origin, normDir, maxDist, hitDist, meshIdx);
            if (res.hit && hitDist[0] < maxDist) {
                blocked = true;
                hitTri[0]    = res.closestTriangle;
                hitMesh[0]   = meshIdx;
                hitTriIdx[0] = res.closestTriangleIndex;
            }
        }

        Vector3 hitPoint = null;
        if (blocked && hitDist[0] < Float.MAX_VALUE) {
            hitPoint = new Vector3(
                    origin.x + normDir.x * hitDist[0],
                    origin.y + normDir.y * hitDist[0],
                    origin.z + normDir.z * hitDist[0]);
        }

        return new RayHitResult(blocked, hitPoint, hitTri[0],
                hitMesh[0], hitTriIdx[0], hitDist[0],
                blocked ? hitDist[0] : maxDist);
    }

    /**
     * Checks whether point1 (sender/camera) can see point2 (target head/chest).
     * With DEBUG=true, prints comprehensive ray information on every call
     * (rate-limited by DEBUG_THROTTLE_MS to avoid log spam).
     */
    public boolean isPointVisible(Vector3 point1, Vector3 point2) {
        Vector3 rayDir = point2.subtract(point1);
        float distance = (float) Math.sqrt(rayDir.dot(rayDir));

        if (distance < 1e-5f) {
            if (DEBUG)
                debugOverlap(point1, point2);
            return true;
        }

        Vector3 normRayDir = new Vector3(
                rayDir.x / distance,
                rayDir.y / distance,
                rayDir.z / distance);

        // Per-BVH hit tracking for debug detail
        float[] hitDistance = { Float.MAX_VALUE };
        TriangleCombined[] hitTriangle = { null };
        int[] hitMeshIndex = { -1 };
        int[] bvhNodesVisited = { 0 };
        int[] trianglesTestedArr = { 0 };

        boolean blocked = false;

        for (int meshIdx = 0; meshIdx < bvhNodes.size() && !blocked; meshIdx++) {
            BVHNode bvhRoot = bvhNodes.get(meshIdx);

            // Debug-aware traversal
            if (DEBUG) {
                DebugIntersectResult res = intersectBVHDebug(
                        bvhRoot, point1, normRayDir, distance, hitDistance, meshIdx);
                bvhNodesVisited[0] += res.nodesVisited;
                trianglesTestedArr[0] += res.trianglesTested;
                if (res.hit && hitDistance[0] < distance) {
                    blocked = true;
                    hitTriangle[0] = res.closestTriangle;
                    hitMeshIndex[0] = meshIdx;
                }
            } else {
                // Fast path: boolean "any-hit" traversal with early exit —
                // stops at the first occluder closer than the target.
                blocked = intersectBVHAny(bvhRoot, point1, normRayDir, distance, meshIdx);
            }
        }

        // Counters
        totalRayCasts.incrementAndGet();
        if (blocked)
            totalBlocked.incrementAndGet();
        else
            totalVisible.incrementAndGet();

        if (DEBUG) {
            emitDebugLog(point1, point2, normRayDir, distance,
                    blocked, hitDistance[0], hitTriangle[0], hitMeshIndex[0],
                    bvhNodesVisited[0], trianglesTestedArr[0]);
        }

        return !blocked;
    }

    // ── Debug logging helpers ────────────────────────────────────

    private void debugOverlap(Vector3 p1, Vector3 p2) {
        System.out.printf("[VisCheck][DEBUG] OVERLAP — sender and target at same position: %s%n",
                vec3Str(p1));
    }

    private void emitDebugLog(Vector3 sender, Vector3 target,
            Vector3 normDir, float distance,
            boolean blocked, float hitDist,
            TriangleCombined hitTri, int hitMeshIdx,
            int nodesVisited, int trisTested) {

        long now = System.currentTimeMillis();
        AtomicLong lastPrinted = blocked ? lastDebugBlockedMs : lastDebugVisibleMs;
        long last = lastPrinted.get();
        if (DEBUG_THROTTLE_MS > 0 && now - last < DEBUG_THROTTLE_MS)
            return;
        if (!lastPrinted.compareAndSet(last, now))
            return; // atomic throttle

        long casts = totalRayCasts.get();
        long blk = totalBlocked.get();
        long vis = totalVisible.get();
        float hitPct = casts > 0 ? (blk * 100.0f / casts) : 0f;

        StringBuilder sb = new StringBuilder(512);
        sb.append("\n┌─── [VisCheck] RAY DEBUG ─────────────────────────────────────────\n");
        sb.append(String.format("│  Result       : %s%n", blocked ? "BLOCKED (not visible)" : "VISIBLE"));
        sb.append(String.format("│  Sender (cam) : %s%n", vec3Str(sender)));
        sb.append(String.format("│  Target (head): %s%n", vec3Str(target)));
        sb.append(String.format("│  Ray direction: %s%n", vec3Str(normDir)));
        sb.append(String.format("│  Ray length   : %.4f units%n", distance));

        if (blocked) {
            // Hit point = sender + normDir * hitDist
            float hpx = sender.x + normDir.x * hitDist;
            float hpy = sender.y + normDir.y * hitDist;
            float hpz = sender.z + normDir.z * hitDist;
            sb.append(String.format("│  Hit distance : %.4f units (%.1f%% of ray)%n",
                    hitDist, hitDist / distance * 100f));
            sb.append(String.format("│  Hit position : (%.4f, %.4f, %.4f)%n", hpx, hpy, hpz));
            sb.append(String.format("│  Hit mesh idx : %d%n", hitMeshIdx));

            if (hitTri != null) {
                sb.append(String.format("│  Hit triangle :%n"));
                sb.append(String.format("│    v0         : %s%n", vec3Str(hitTri.v0)));
                sb.append(String.format("│    v1         : %s%n", vec3Str(hitTri.v1)));
                sb.append(String.format("│    v2         : %s%n", vec3Str(hitTri.v2)));
                sb.append(String.format("│    Normal     : %s%n", triangleNormal(hitTri)));
                sb.append(String.format("│    AABB       : min=%s  max=%s%n",
                        vec3Str(hitTri.computeAABB().min), vec3Str(hitTri.computeAABB().max)));
            } else {
                sb.append("│  Hit triangle : (tracking unavailable — non-debug BVH path)\n");
            }
        }

        sb.append(String.format("│  BVH meshes   : %d  nodes visited: %d  tris tested: %d%n",
                bvhNodes.size(), nodesVisited, trisTested));
        sb.append(String.format("│  Lifetime stats: casts=%d  blocked=%d  visible=%d  block%%=%.1f%%%n",
                casts, blk, vis, hitPct));
        sb.append("└──────────────────────────────────────────────────────────────────");

        System.out.println(sb);
    }

    /** Returns a compact String representation of a Vector3. */
    private static String vec3Str(Vector3 v) {
        if (v == null)
            return "(null)";
        return String.format("(%.4f, %.4f, %.4f)", v.x, v.y, v.z);
    }

    /** Computes and formats the surface normal of a triangle. */
    private static String triangleNormal(TriangleCombined t) {
        Vector3 e1 = t.v1.subtract(t.v0);
        Vector3 e2 = t.v2.subtract(t.v0);
        Vector3 n = e1.cross(e2);
        float len = (float) Math.sqrt(n.dot(n));
        if (len > 1e-7f)
            n = new Vector3(n.x / len, n.y / len, n.z / len);
        return vec3Str(n);
    }

    // ── Triangle–Ray Möller–Trumbore ─────────────────────────────

    public float rayIntersectsTriangle(Vector3 rayOrigin, Vector3 rayDir, TriangleCombined triangle) {
        final float epsilon = 1e-7f;

        Vector3 edge1 = triangle.v1.subtract(triangle.v0);
        Vector3 edge2 = triangle.v2.subtract(triangle.v0);
        Vector3 h = rayDir.cross(edge2);
        float a = edge1.dot(h);

        if (a > -epsilon && a < epsilon)
            return -1.0f; // parallel

        float f = 1.0f / a;
        Vector3 s = rayOrigin.subtract(triangle.v0);
        float u = f * s.dot(h);
        if (u < 0.0f || u > 1.0f)
            return -1.0f;

        Vector3 q = s.cross(edge1);
        float v = f * rayDir.dot(q);
        if (v < 0.0f || u + v > 1.0f)
            return -1.0f;

        float t = f * edge2.dot(q);
        return (t > epsilon) ? t : -1.0f;
    }

    // ── Flat triangle bounds & reusable build scratch buffers ────

    /**
     * Stores triangle bounding boxes in flat parallel primitive float arrays.
     * Eliminates millions of temporary AABB and Vector3 object allocations during BVH building.
     */
    private static final class FlatBoxes {
        final float[] minX;
        final float[] minY;
        final float[] minZ;
        final float[] maxX;
        final float[] maxY;
        final float[] maxZ;

        FlatBoxes(int n) {
            minX = new float[n];
            minY = new float[n];
            minZ = new float[n];
            maxX = new float[n];
            maxY = new float[n];
            maxZ = new float[n];
        }

        void set(int i, TriangleCombined tri) {
            float x0 = tri.v0.x, x1 = tri.v1.x, x2 = tri.v2.x;
            float y0 = tri.v0.y, y1 = tri.v1.y, y2 = tri.v2.y;
            float z0 = tri.v0.z, z1 = tri.v1.z, z2 = tri.v2.z;

            minX[i] = Math.min(x0, Math.min(x1, x2));
            minY[i] = Math.min(y0, Math.min(y1, y2));
            minZ[i] = Math.min(z0, Math.min(z1, z2));

            maxX[i] = Math.max(x0, Math.max(x1, x2));
            maxY[i] = Math.max(y0, Math.max(y1, y2));
            maxZ[i] = Math.max(z0, Math.max(z1, z2));
        }

        float centroid(int i, int axis) {
            switch (axis) {
                case 0: return (minX[i] + maxX[i]) * 0.5f;
                case 1: return (minY[i] + maxY[i]) * 0.5f;
                default: return (minZ[i] + maxZ[i]) * 0.5f;
            }
        }
    }

    /**
     * Pre-allocated scratch buffers reused across recursive buildNode calls.
     * Prevents tens of millions of short-lived array and AABB allocations during SAH splits.
     */
    private static final class BVHBuildScratch {
        final int[] binCount = new int[SAH_BINS];
        final float[] binMinX = new float[SAH_BINS];
        final float[] binMinY = new float[SAH_BINS];
        final float[] binMinZ = new float[SAH_BINS];
        final float[] binMaxX = new float[SAH_BINS];
        final float[] binMaxY = new float[SAH_BINS];
        final float[] binMaxZ = new float[SAH_BINS];

        final float[] leftArea = new float[SAH_BINS - 1];
        final int[] leftCount = new int[SAH_BINS - 1];
        final float[] rightArea = new float[SAH_BINS - 1];
        final int[] rightCount = new int[SAH_BINS - 1];

        void clearBins() {
            java.util.Arrays.fill(binCount, 0);
        }
    }

    // ── BVH builder ──────────────────────────────────────────────

    /**
     * Builds a BVH over the given mesh.
     *
     * <p>Triangle bounds are precomputed into a single {@link FlatBoxes} primitive buffer.
     * Splitting uses binned Surface Area Heuristic (SAH) evaluated with a reused {@link BVHBuildScratch}
     * buffer, creating zero temporary heap objects during recursive node splitting.</p>
     */
    private BVHNode buildBVH(List<TriangleCombined> tris) {
        BVHNode root = new BVHNode();
        int n = tris.size();
        if (n == 0) {
            root.bounds = new AABB(new Vector3(), new Vector3());
            return root;
        }

        TriangleCombined[] trisArr = tris.toArray(new TriangleCombined[0]);
        FlatBoxes boxes = new FlatBoxes(n);
        for (int i = 0; i < n; i++) {
            boxes.set(i, trisArr[i]);
        }

        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;

        BVHBuildScratch scratch = new BVHBuildScratch();
        buildNode(root, trisArr, boxes, order, 0, n, scratch);
        return root;
    }

    /**
     * Recursively builds one BVH node covering {@code order[from, to)}.
     */
    private void buildNode(BVHNode node, TriangleCombined[] tris, FlatBoxes boxes,
                           int[] order, int from, int to, BVHBuildScratch scratch) {
        // 1. Compute node bounds from child range.
        int first = order[from];
        float bminX = boxes.minX[first], bminY = boxes.minY[first], bminZ = boxes.minZ[first];
        float bmaxX = boxes.maxX[first], bmaxY = boxes.maxY[first], bmaxZ = boxes.maxZ[first];
        for (int i = from + 1; i < to; i++) {
            int idx = order[i];
            bminX = Math.min(bminX, boxes.minX[idx]);
            bminY = Math.min(bminY, boxes.minY[idx]);
            bminZ = Math.min(bminZ, boxes.minZ[idx]);
            bmaxX = Math.max(bmaxX, boxes.maxX[idx]);
            bmaxY = Math.max(bmaxY, boxes.maxY[idx]);
            bmaxZ = Math.max(bmaxZ, boxes.maxZ[idx]);
        }
        node.bounds = new AABB(new Vector3(bminX, bminY, bminZ), new Vector3(bmaxX, bmaxY, bmaxZ));

        int count = to - from;
        if (count <= LEAF_THRESHOLD) {
            List<TriangleCombined> leafTris = new ArrayList<>(count);
            for (int i = from; i < to; i++) {
                leafTris.add(tris[order[i]]);
            }
            node.triangles = leafTris;
            return;
        }

        // 2. Find the best split plane via binned SAH over all three axes.
        int bestAxis = -1;
        float bestPlane = 0f;
        float bestCost = Float.MAX_VALUE;

        for (int axis = 0; axis < 3; axis++) {
            float amin, amax;
            switch (axis) {
                case 0:  amin = bminX; amax = bmaxX; break;
                case 1:  amin = bminY; amax = bmaxY; break;
                default: amin = bminZ; amax = bmaxZ; break;
            }
            float extent = amax - amin;
            if (extent < 1e-6f)
                continue; // degenerate on this axis

            scratch.clearBins();
            float scale = SAH_BINS / extent;

            for (int i = from; i < to; i++) {
                int idx = order[i];
                float c = boxes.centroid(idx, axis);
                int bin = (int) ((c - amin) * scale);
                if (bin < 0) bin = 0;
                if (bin >= SAH_BINS) bin = SAH_BINS - 1;

                if (scratch.binCount[bin] == 0) {
                    scratch.binMinX[bin] = boxes.minX[idx];
                    scratch.binMinY[bin] = boxes.minY[idx];
                    scratch.binMinZ[bin] = boxes.minZ[idx];
                    scratch.binMaxX[bin] = boxes.maxX[idx];
                    scratch.binMaxY[bin] = boxes.maxY[idx];
                    scratch.binMaxZ[bin] = boxes.maxZ[idx];
                } else {
                    scratch.binMinX[bin] = Math.min(scratch.binMinX[bin], boxes.minX[idx]);
                    scratch.binMinY[bin] = Math.min(scratch.binMinY[bin], boxes.minY[idx]);
                    scratch.binMinZ[bin] = Math.min(scratch.binMinZ[bin], boxes.minZ[idx]);
                    scratch.binMaxX[bin] = Math.max(scratch.binMaxX[bin], boxes.maxX[idx]);
                    scratch.binMaxY[bin] = Math.max(scratch.binMaxY[bin], boxes.maxY[idx]);
                    scratch.binMaxZ[bin] = Math.max(scratch.binMaxZ[bin], boxes.maxZ[idx]);
                }
                scratch.binCount[bin]++;
            }

            // Sweep left→right accumulating left-side areas/counts...
            float accMinX = 0f, accMinY = 0f, accMinZ = 0f;
            float accMaxX = 0f, accMaxY = 0f, accMaxZ = 0f;
            int cntLeft = 0;
            for (int s = 0; s < SAH_BINS - 1; s++) {
                if (scratch.binCount[s] > 0) {
                    if (cntLeft == 0) {
                        accMinX = scratch.binMinX[s]; accMinY = scratch.binMinY[s]; accMinZ = scratch.binMinZ[s];
                        accMaxX = scratch.binMaxX[s]; accMaxY = scratch.binMaxY[s]; accMaxZ = scratch.binMaxZ[s];
                    } else {
                        accMinX = Math.min(accMinX, scratch.binMinX[s]);
                        accMinY = Math.min(accMinY, scratch.binMinY[s]);
                        accMinZ = Math.min(accMinZ, scratch.binMinZ[s]);
                        accMaxX = Math.max(accMaxX, scratch.binMaxX[s]);
                        accMaxY = Math.max(accMaxY, scratch.binMaxY[s]);
                        accMaxZ = Math.max(accMaxZ, scratch.binMaxZ[s]);
                    }
                    cntLeft += scratch.binCount[s];
                }
                scratch.leftArea[s] = (cntLeft == 0) ? 0f : boxSurfaceArea(accMinX, accMinY, accMinZ, accMaxX, accMaxY, accMaxZ);
                scratch.leftCount[s] = cntLeft;
            }

            // ...and right→left accumulating right-side areas/counts.
            accMinX = 0f; accMinY = 0f; accMinZ = 0f;
            accMaxX = 0f; accMaxY = 0f; accMaxZ = 0f;
            int cntRight = 0;
            for (int s = SAH_BINS - 1; s > 0; s--) {
                if (scratch.binCount[s] > 0) {
                    if (cntRight == 0) {
                        accMinX = scratch.binMinX[s]; accMinY = scratch.binMinY[s]; accMinZ = scratch.binMinZ[s];
                        accMaxX = scratch.binMaxX[s]; accMaxY = scratch.binMaxY[s]; accMaxZ = scratch.binMaxZ[s];
                    } else {
                        accMinX = Math.min(accMinX, scratch.binMinX[s]);
                        accMinY = Math.min(accMinY, scratch.binMinY[s]);
                        accMinZ = Math.min(accMinZ, scratch.binMinZ[s]);
                        accMaxX = Math.max(accMaxX, scratch.binMaxX[s]);
                        accMaxY = Math.max(accMaxY, scratch.binMaxY[s]);
                        accMaxZ = Math.max(accMaxZ, scratch.binMaxZ[s]);
                    }
                    cntRight += scratch.binCount[s];
                }
                scratch.rightArea[s - 1] = (cntRight == 0) ? 0f : boxSurfaceArea(accMinX, accMinY, accMinZ, accMaxX, accMaxY, accMaxZ);
                scratch.rightCount[s - 1] = cntRight;
            }

            // Evaluate cost of every candidate split.
            float invParentArea = 1.0f / Math.max(surfaceArea(node.bounds), 1e-9f);
            for (int s = 0; s < SAH_BINS - 1; s++) {
                if (scratch.leftCount[s] == 0 || scratch.rightCount[s] == 0)
                    continue;
                float cost = SAH_TRAVERSAL_COST
                        + SAH_INTERSECT_COST * invParentArea
                        * (scratch.leftCount[s] * scratch.leftArea[s] + scratch.rightCount[s] * scratch.rightArea[s]);
                if (cost < bestCost) {
                    bestCost = cost;
                    bestAxis = axis;
                    bestPlane = amin + (s + 1) / (float) SAH_BINS * extent;
                }
            }
        }

        // 3. Partition the index range around the chosen plane.
        int mid;
        if (bestAxis == -1) {
            mid = from + count / 2;
        } else {
            mid = partition(order, boxes, from, to, bestAxis, bestPlane);
            if (mid <= from || mid >= to) {
                mid = medianSplit(order, boxes, from, to, bestAxis);
            }
        }

        node.left = new BVHNode();
        node.right = new BVHNode();
        buildNode(node.left, tris, boxes, order, from, mid, scratch);
        buildNode(node.right, tris, boxes, order, mid, to, scratch);
    }

    // ── BVH builder helpers ──────────────────────────────────────

    private static float boxSurfaceArea(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        float dx = maxX - minX;
        float dy = maxY - minY;
        float dz = maxZ - minZ;
        return 2.0f * (dx * dy + dy * dz + dz * dx);
    }

    /** Surface area of an axis-aligned box (half-area would suffice for SAH). */
    private static float surfaceArea(AABB box) {
        float dx = box.max.x - box.min.x;
        float dy = box.max.y - box.min.y;
        float dz = box.max.z - box.min.z;
        return 2.0f * (dx * dy + dy * dz + dz * dx);
    }

    /**
     * Partitions {@code order[from, to)} in place so that triangles whose
     * centroid is left of {@code plane} come first. Returns the split index.
     */
    private static int partition(int[] order, FlatBoxes boxes,
            int from, int to, int axis, float plane) {
        int lo = from, hi = to - 1;
        while (lo <= hi) {
            float c = boxes.centroid(order[lo], axis);
            if (c < plane) {
                lo++;
            } else {
                int tmp = order[lo];
                order[lo] = order[hi];
                order[hi] = tmp;
                hi--;
            }
        }
        return lo;
    }

    /**
     * Fallback split: sorts {@code order[from, to)} by centroid along
     * {@code axis} and returns the median index. Used when the SAH partition
     * degenerates (all centroids on one side of the plane).
     */
    private static int medianSplit(int[] order, FlatBoxes boxes,
            int from, int to, int axis) {
        for (int i = from + 1; i < to; i++) {
            int cur = order[i];
            float cc = boxes.centroid(cur, axis);
            int j = i - 1;
            while (j >= from && boxes.centroid(order[j], axis) > cc) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = cur;
        }
        return from + (to - from) / 2;
    }

    // ── BVH traversal (production — no tracking overhead) ────────

    /**
     * Boolean "any-hit" traversal with early exit: returns {@code true} as
     * soon as any non-deleted triangle is intersected closer than
     * {@code maxDistance}. Used by {@link #isPointVisible} where only the
     * occluded/not-occluded answer matters — no closest-hit bookkeeping.
     */
    private boolean intersectBVHAny(BVHNode node, Vector3 rayOrigin, Vector3 rayDir,
            float maxDistance, int meshIdx) {
        if (!node.bounds.rayIntersects(rayOrigin, rayDir))
            return false;

        if (node.isLeaf()) {
            if (node.triangles != null) {
                for (TriangleCombined tri : node.triangles) {
                    if (deletedTriangles.contains(encodeTriKey(meshIdx, tri.index))) continue;
                    float t = rayIntersectsTriangle(rayOrigin, rayDir, tri);
                    if (t > 0.0f && t < maxDistance)
                        return true; // early exit — first occluder wins
                }
            }
            return false;
        }

        if (node.left != null && intersectBVHAny(node.left, rayOrigin, rayDir, maxDistance, meshIdx))
            return true;
        return node.right != null && intersectBVHAny(node.right, rayOrigin, rayDir, maxDistance, meshIdx);
    }

    // ── BVH traversal (debug — tracks nodes visited, tris tested, closest tri) ─

    /** Lightweight struct returned by the debug traversal. */
    private static final class DebugIntersectResult {
        boolean hit = false;
        int nodesVisited = 0;
        int trianglesTested = 0;
        TriangleCombined closestTriangle = null;
        int closestTriangleIndex = -1;
    }

    private DebugIntersectResult intersectBVHDebug(BVHNode node, Vector3 rayOrigin, Vector3 rayDir,
            float maxDistance, float[] hitDistance,
            int meshIdx) {
        DebugIntersectResult result = new DebugIntersectResult();
        intersectBVHDebugRecursive(node, rayOrigin, rayDir, maxDistance, hitDistance, result, meshIdx);
        return result;
    }

    private void intersectBVHDebugRecursive(BVHNode node, Vector3 rayOrigin, Vector3 rayDir,
            float maxDistance, float[] hitDistance,
            DebugIntersectResult result, int meshIdx) {
        result.nodesVisited++;
        if (!node.bounds.rayIntersects(rayOrigin, rayDir))
            return;

        if (node.isLeaf()) {
            if (node.triangles != null) {
                for (TriangleCombined tri : node.triangles) {
                    result.trianglesTested++;
                    // Skip triangles that have been manually deleted
                    if (deletedTriangles.contains(encodeTriKey(meshIdx, tri.index))) continue;
                    float t = rayIntersectsTriangle(rayOrigin, rayDir, tri);
                    if (t > 0.0f && t < maxDistance && t < hitDistance[0]) {
                        hitDistance[0] = t;
                        result.hit = true;
                        result.closestTriangle = tri;
                        result.closestTriangleIndex = tri.index;
                    }
                }
            }
        } else {
            if (node.left != null)
                intersectBVHDebugRecursive(node.left, rayOrigin, rayDir, maxDistance, hitDistance, result, meshIdx);
            if (node.right != null)
                intersectBVHDebugRecursive(node.right, rayOrigin, rayDir, maxDistance, hitDistance, result, meshIdx);
        }
    }

    // ── Public debug utilities ───────────────────────────────────

    /**
     * Prints a full stats snapshot to stdout — call this from your debug
     * console or a key-bind to see cumulative ray-cast statistics.
     */
    public static void printStats() {
        long c = totalRayCasts.get();
        long b = totalBlocked.get();
        long v = totalVisible.get();
        System.out.printf("[VisCheck] Stats → casts=%d  blocked=%d  visible=%d  block%%=%.2f%%%n",
                c, b, v, c > 0 ? (b * 100.0f / c) : 0f);
    }

    /** Resets all lifetime counters (useful when switching maps). */
    public static void resetStats() {
        totalRayCasts.set(0);
        totalBlocked.set(0);
        totalVisible.set(0);
        lastDebugBlockedMs.set(0);
        lastDebugVisibleMs.set(0);
        System.out.println("[VisCheck] Stats reset.");
    }

    /**
     * Convenience: prints a single manual debug trace for a specific sender/target
     * pair regardless of the throttle, without modifying global counters.
     * Useful for one-off angle checks from a console command.
     */
    public void debugTrace(Vector3 sender, Vector3 target, String label) {
        Vector3 rayDir = target.subtract(sender);
        float distance = (float) Math.sqrt(rayDir.dot(rayDir));
        if (distance < 1e-5f) {
            System.out.printf("[VisCheck][TRACE:%s] Sender == Target at %s — trivially visible.%n",
                    label, vec3Str(sender));
            return;
        }
        Vector3 normDir = new Vector3(rayDir.x / distance, rayDir.y / distance, rayDir.z / distance);

        float[] hitDist = { Float.MAX_VALUE };
        TriangleCombined[] hitTri = { null };
        int[] hitMesh = { -1 };
        int nodesVisited = 0, trisTested = 0;
        boolean blocked = false;

        for (int meshIdx = 0; meshIdx < bvhNodes.size(); meshIdx++) {
            DebugIntersectResult res = intersectBVHDebug(
                    bvhNodes.get(meshIdx), sender, normDir, distance, hitDist, meshIdx);
            nodesVisited += res.nodesVisited;
            trisTested += res.trianglesTested;
            if (res.hit && hitDist[0] < distance) {
                blocked = true;
                hitTri[0] = res.closestTriangle;
                hitMesh[0] = meshIdx;
            }
        }

        System.out.println("\n╔══ [VisCheck][TRACE:" + label + "] ═══════════════════════════════════════╗");
        System.out.printf("║  Result       : %s%n", blocked ? "BLOCKED" : "VISIBLE");
        System.out.printf("║  Sender       : %s%n", vec3Str(sender));
        System.out.printf("║  Target       : %s%n", vec3Str(target));
        System.out.printf("║  Ray direction: %s%n", vec3Str(normDir));
        System.out.printf("║  Ray length   : %.4f units%n", distance);
        if (blocked) {
            float hpx = sender.x + normDir.x * hitDist[0];
            float hpy = sender.y + normDir.y * hitDist[0];
            float hpz = sender.z + normDir.z * hitDist[0];
            System.out.printf("║  Hit distance : %.4f units (%.1f%% of ray)%n",
                    hitDist[0], hitDist[0] / distance * 100f);
            System.out.printf("║  Hit position : (%.4f, %.4f, %.4f)%n", hpx, hpy, hpz);
            System.out.printf("║  Hit mesh     : %d%n", hitMesh[0]);
            if (hitTri[0] != null) {
                System.out.printf("║  Triangle v0  : %s%n", vec3Str(hitTri[0].v0));
                System.out.printf("║  Triangle v1  : %s%n", vec3Str(hitTri[0].v1));
                System.out.printf("║  Triangle v2  : %s%n", vec3Str(hitTri[0].v2));
                System.out.printf("║  Normal       : %s%n", triangleNormal(hitTri[0]));
                AABB tb = hitTri[0].computeAABB();
                System.out.printf("║  AABB min     : %s%n", vec3Str(tb.min));
                System.out.printf("║  AABB max     : %s%n", vec3Str(tb.max));
            }
        }
        System.out.printf("║  BVH meshes   : %d  nodes visited: %d  tris tested: %d%n",
                bvhNodes.size(), nodesVisited, trisTested);
        System.out.println("╚═════════════════════════════════════════════════════════════════╝");
    }
}
