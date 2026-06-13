package me.venixpll.cheat.vischeck;

import java.util.ArrayList;
import java.util.List;

public class BVHNode {
    public AABB bounds;
    public BVHNode left;
    public BVHNode right;
    public List<TriangleCombined> triangles = new ArrayList<>();

    public boolean isLeaf() {
        return left == null && right == null;
    }
}
