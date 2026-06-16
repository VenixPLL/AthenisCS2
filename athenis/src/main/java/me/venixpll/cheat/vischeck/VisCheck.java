package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class VisCheck {
    private static final int LEAF_THRESHOLD = 4;
    private final OptimizedGeometry geometry = new OptimizedGeometry();
    private final List<BVHNode> bvhNodes = new ArrayList<>();

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
    private static final AtomicLong totalRayCasts = new AtomicLong(0L);
    private static final AtomicLong totalBlocked = new AtomicLong(0L);
    private static final AtomicLong totalVisible = new AtomicLong(0L);

    // ── Constructor (file path) ───────────────────────────────────────────────
    public VisCheck(String optimizedGeometryFile) {
        if (!geometry.loadFromFile(optimizedGeometryFile)) {
            System.err.println("[VisCheck] Failed to load optimized file: " + optimizedGeometryFile);
        }
        buildBVHForAllMeshes();
        logLoadStats(optimizedGeometryFile);
    }

    // ── Constructor (bytes) ───────────────────────────────────────────────────
    public VisCheck(byte[] bytes) {
        if (!geometry.loadFromBytes(bytes)) {
            System.err.println("[VisCheck] Failed to load geometry from bytes");
        }
        buildBVHForAllMeshes();
        logLoadStats("<in-memory bytes, " + bytes.length + " B>");
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
                if (intersectBVH(bvhRoot, point1, normRayDir, distance, hitDistance)) {
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
            float maxDistance, float[] hitDistance) {
        if (!node.bounds.rayIntersects(rayOrigin, rayDir))
            return false;

        boolean hit = false;
        if (node.isLeaf()) {
            for (TriangleCombined tri : node.triangles) {
                float t = rayIntersectsTriangle(rayOrigin, rayDir, tri);
                if (t > 0.0f && t < maxDistance && t < hitDistance[0]) {
                    hitDistance[0] = t;
                    hit = true;
                }
            }
        } else {
            if (node.left != null)
                hit |= intersectBVH(node.left, rayOrigin, rayDir, maxDistance, hitDistance);
            if (node.right != null)
                hit |= intersectBVH(node.right, rayOrigin, rayDir, maxDistance, hitDistance);
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
    }

    private DebugIntersectResult intersectBVHDebug(BVHNode node, Vector3 rayOrigin, Vector3 rayDir,
            float maxDistance, float[] hitDistance,
            int meshIdx) {
        DebugIntersectResult result = new DebugIntersectResult();
        intersectBVHDebugRecursive(node, rayOrigin, rayDir, maxDistance, hitDistance, result);
        return result;
    }

    private void intersectBVHDebugRecursive(BVHNode node, Vector3 rayOrigin, Vector3 rayDir,
            float maxDistance, float[] hitDistance,
            DebugIntersectResult result) {
        result.nodesVisited++;
        if (!node.bounds.rayIntersects(rayOrigin, rayDir))
            return;

        if (node.isLeaf()) {
            for (TriangleCombined tri : node.triangles) {
                result.trianglesTested++;
                float t = rayIntersectsTriangle(rayOrigin, rayDir, tri);
                if (t > 0.0f && t < maxDistance && t < hitDistance[0]) {
                    hitDistance[0] = t;
                    result.hit = true;
                    result.closestTriangle = tri;
                }
            }
        } else {
            if (node.left != null)
                intersectBVHDebugRecursive(node.left, rayOrigin, rayDir, maxDistance, hitDistance, result);
            if (node.right != null)
                intersectBVHDebugRecursive(node.right, rayOrigin, rayDir, maxDistance, hitDistance, result);
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
