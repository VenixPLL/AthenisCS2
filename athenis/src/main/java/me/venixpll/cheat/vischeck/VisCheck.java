package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import java.util.ArrayList;
import java.util.List;

public class VisCheck {
    private static final int LEAF_THRESHOLD = 4;
    private final OptimizedGeometry geometry = new OptimizedGeometry();
    private final List<BVHNode> bvhNodes = new ArrayList<>();

    public VisCheck(String optimizedGeometryFile) {
        if (!geometry.loadFromFile(optimizedGeometryFile)) {
            System.err.println("Failed to load optimized file: " + optimizedGeometryFile);
        }
        for (List<TriangleCombined> mesh : geometry.meshes) {
            bvhNodes.add(buildBVH(mesh));
        }
    }

    public VisCheck(byte[] bytes) {
        if (!geometry.loadFromBytes(bytes)) {
            System.err.println("Failed to load geometry from bytes");
        }
        for (List<TriangleCombined> mesh : geometry.meshes) {
            bvhNodes.add(buildBVH(mesh));
        }
    }

    public boolean isPointVisible(Vector3 point1, Vector3 point2) {
        Vector3 rayDir = point2.subtract(point1);
        float distance = (float) Math.sqrt(rayDir.dot(rayDir));
        if (distance < 1e-5f) {
            return true; // overlapping points are visible
        }
        Vector3 normRayDir = new Vector3(rayDir.x / distance, rayDir.y / distance, rayDir.z / distance);
        float[] hitDistance = { Float.MAX_VALUE };

        for (BVHNode bvhRoot : bvhNodes) {
            if (intersectBVH(bvhRoot, point1, normRayDir, distance, hitDistance)) {
                if (hitDistance[0] < distance) {
                    return false;
                }
            }
        }
        return true;
    }

    public float rayIntersectsTriangle(Vector3 rayOrigin, Vector3 rayDir, TriangleCombined triangle) {
        float epsilon = 1e-7f;

        Vector3 edge1 = triangle.v1.subtract(triangle.v0);
        Vector3 edge2 = triangle.v2.subtract(triangle.v0);
        Vector3 h = rayDir.cross(edge2);
        float a = edge1.dot(h);

        if (a > -epsilon && a < epsilon) {
            return -1.0f;
        }

        float f = 1.0f / a;
        Vector3 s = rayOrigin.subtract(triangle.v0);
        float u = f * s.dot(h);

        if (u < 0.0f || u > 1.0f) {
            return -1.0f;
        }

        Vector3 q = s.cross(edge1);
        float v = f * rayDir.dot(q);

        if (v < 0.0f || u + v > 1.0f) {
            return -1.0f;
        }

        float t = f * edge2.dot(q);
        if (t > epsilon) {
            return t;
        } else {
            return -1.0f;
        }
    }

    private BVHNode buildBVH(List<TriangleCombined> tris) {
        BVHNode node = new BVHNode();
        if (tris.isEmpty()) {
            return node;
        }

        AABB bounds = tris.get(0).computeAABB();
        AABB nodeBounds = new AABB(
            new Vector3(bounds.min.x, bounds.min.y, bounds.min.z),
            new Vector3(bounds.max.x, bounds.max.y, bounds.max.z)
        );

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
        List<TriangleCombined> leftTris = new ArrayList<>(sortedTris.subList(0, mid));
        List<TriangleCombined> rightTris = new ArrayList<>(sortedTris.subList(mid, sortedTris.size()));

        node.left = buildBVH(leftTris);
        node.right = buildBVH(rightTris);

        return node;
    }

    private boolean intersectBVH(BVHNode node, Vector3 rayOrigin, Vector3 rayDir, float maxDistance, float[] hitDistance) {
        if (!node.bounds.rayIntersects(rayOrigin, rayDir)) {
            return false;
        }

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
            if (node.left != null) {
                hit |= intersectBVH(node.left, rayOrigin, rayDir, maxDistance, hitDistance);
            }
            if (node.right != null) {
                hit |= intersectBVH(node.right, rayOrigin, rayDir, maxDistance, hitDistance);
            }
        }
        return hit;
    }
}
