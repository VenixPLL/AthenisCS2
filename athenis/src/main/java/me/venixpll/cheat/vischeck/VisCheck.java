package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class VisCheck {

    // ── Ray hit result (for visual debug) ────────────────────────────────────

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

    private static final int LEAF_THRESHOLD = 4;
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
     * Load deleted triangles from a previously saved JSON patch file.
     * Adds entries to the existing deletion set (does not clear first).
     */
    public void loadDeletedFromFile(String filePath) {
        try {
            String text = new String(
                    java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)),
                    java.nio.charset.StandardCharsets.UTF_8);
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
            System.out.println("[VisCheck] Loaded " + count + " deleted triangles from " + filePath);
        } catch (Exception e) {
            System.err.println("[VisCheck] Failed to load deletions: " + e.getMessage());
        }
    }

    // ── Debug configuration ───────────────────────────────────────────────────
    /** Master toggle: set to true to enable all debug output. */
    public static volatile boolean DEBUG = false;

    /**
     * Minimum milliseconds between debug log lines for the same result category.
     * Prevents log spam at high tick rates. 0 = always print.
     */
    public static volatile long DEBUG_THROTTLE_MS = 500L;

    // ── Internal throttle state ───────────────────────────────────────────────
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

    // ── Constructor (file path) ───────────────────────────────────────────────
    public VisCheck(String optimizedGeometryFile, String mapName) {
        this.mapName = mapName;
        if (!geometry.loadFromFile(optimizedGeometryFile)) {
            System.err.println("[VisCheck] Failed to load optimized file: " + optimizedGeometryFile);
        }
        buildBVHForAllMeshes();
        logLoadStats(optimizedGeometryFile);

        // Auto-load deleted patch
        if (mapName != null && !mapName.isEmpty()) {
            String path = getSavePath(mapName);
            if (new java.io.File(path).exists()) {
                loadDeletedFromFile(path);
            }
        }
    }

    // ── Constructor (bytes) ───────────────────────────────────────────────────
    public VisCheck(byte[] bytes, String mapName) {
        this.mapName = mapName;
        if (!geometry.loadFromBytes(bytes)) {
            System.err.println("[VisCheck] Failed to load geometry from bytes");
        }
        buildBVHForAllMeshes();
        logLoadStats("<in-memory bytes, " + bytes.length + " B>");

        // Auto-load deleted patch
        if (mapName != null && !mapName.isEmpty()) {
            String path = getSavePath(mapName);
            if (new java.io.File(path).exists()) {
                loadDeletedFromFile(path);
            }
        }
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

    // ── Core visibility check ─────────────────────────────────────────────────

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

        for (int meshIdx = 0; meshIdx < bvhNodes.size(); meshIdx++) {
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
                if (intersectBVH(bvhRoot, point1, normRayDir, distance, hitDistance, meshIdx)) {
                    if (hitDistance[0] < distance) {
                        blocked = true;
                    }
                }
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

    // ── Debug logging helpers ─────────────────────────────────────────────────

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
                sb.append("│  Hit triangle : (tracking unavailable — non-debug BVH path)%n");
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

    // ── Triangle–Ray Möller–Trumbore ──────────────────────────────────────────

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

    // ── BVH builder ───────────────────────────────────────────────────────────

    private BVHNode buildBVH(List<TriangleCombined> tris) {
        BVHNode node = new BVHNode();
        if (tris.isEmpty())
            return node;

        AABB bounds = tris.get(0).computeAABB();
        AABB nodeBounds = new AABB(
                new Vector3(bounds.min.x, bounds.min.y, bounds.min.z),
                new Vector3(bounds.max.x, bounds.max.y, bounds.max.z));

        for (int i = 1; i < tris.size(); ++i) {
            AABB triAABB = tris.get(i).computeAABB();
            nodeBounds.min.x = Math.min(nodeBounds.min.x, triAABB.min.x);
            nodeBounds.min.y = Math.min(nodeBounds.min.y, triAABB.min.y);
            nodeBounds.min.z = Math.min(nodeBounds.min.z, triAABB.min.z);
            nodeBounds.max.x = Math.max(nodeBounds.max.x, triAABB.max.x);
            nodeBounds.max.y = Math.max(nodeBounds.max.y, triAABB.max.y);
            nodeBounds.max.z = Math.max(nodeBounds.max.z, triAABB.max.z);
        }
        node.bounds = nodeBounds;

        if (tris.size() <= LEAF_THRESHOLD) {
            node.triangles = tris;
            return node;
        }

        Vector3 diff = nodeBounds.max.subtract(nodeBounds.min);
        int axis = (diff.x > diff.y && diff.x > diff.z) ? 0 : ((diff.y > diff.z) ? 1 : 2);

        List<TriangleCombined> sortedTris = new ArrayList<>(tris);
        sortedTris.sort((a, b) -> {
            AABB aabbA = a.computeAABB();
            AABB aabbB = b.computeAABB();
            float centerA, centerB;
            if (axis == 0) {
                centerA = (aabbA.min.x + aabbA.max.x) / 2.0f;
                centerB = (aabbB.min.x + aabbB.max.x) / 2.0f;
            } else if (axis == 1) {
                centerA = (aabbA.min.y + aabbA.max.y) / 2.0f;
                centerB = (aabbB.min.y + aabbB.max.y) / 2.0f;
            } else {
                centerA = (aabbA.min.z + aabbA.max.z) / 2.0f;
                centerB = (aabbB.min.z + aabbB.max.z) / 2.0f;
            }
            return Float.compare(centerA, centerB);
        });

        int mid = sortedTris.size() / 2;
        node.left = buildBVH(new ArrayList<>(sortedTris.subList(0, mid)));
        node.right = buildBVH(new ArrayList<>(sortedTris.subList(mid, sortedTris.size())));
        return node;
    }

    // ── BVH traversal (production — no tracking overhead) ────────────────────

    private boolean intersectBVH(BVHNode node, Vector3 rayOrigin, Vector3 rayDir,
            float maxDistance, float[] hitDistance, int meshIdx) {
        if (!node.bounds.rayIntersects(rayOrigin, rayDir))
            return false;

        boolean hit = false;
        if (node.isLeaf()) {
            for (TriangleCombined tri : node.triangles) {
                // Skip deleted triangles in production mode
                if (deletedTriangles.contains(encodeTriKey(meshIdx, tri.index))) continue;
                float t = rayIntersectsTriangle(rayOrigin, rayDir, tri);
                if (t > 0.0f && t < maxDistance && t < hitDistance[0]) {
                    hitDistance[0] = t;
                    hit = true;
                }
            }
        } else {
            if (node.left != null)
                hit |= intersectBVH(node.left, rayOrigin, rayDir, maxDistance, hitDistance, meshIdx);
            if (node.right != null)
                hit |= intersectBVH(node.right, rayOrigin, rayDir, maxDistance, hitDistance, meshIdx);
        }
        return hit;
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
        } else {
            if (node.left != null)
                intersectBVHDebugRecursive(node.left, rayOrigin, rayDir, maxDistance, hitDistance, result, meshIdx);
            if (node.right != null)
                intersectBVHDebugRecursive(node.right, rayOrigin, rayDir, maxDistance, hitDistance, result, meshIdx);
        }
    }

    // ── Public debug utilities ────────────────────────────────────────────────

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
     * pair
     * regardless of the throttle, without modifying global counters.
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

        System.out.println("\n╔══ [VisCheck][TRACE:" + label + "] ══════════════════════════════════════╗");
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
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
    }
}
