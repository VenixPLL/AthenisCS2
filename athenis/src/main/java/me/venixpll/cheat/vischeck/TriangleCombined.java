package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;

public class TriangleCombined {
    public Vector3 v0;
    public Vector3 v1;
    public Vector3 v2;
    public int index = -1;

    public TriangleCombined() {
        this.v0 = new Vector3();
        this.v1 = new Vector3();
        this.v2 = new Vector3();
    }

    public TriangleCombined(Vector3 v0, Vector3 v1, Vector3 v2) {
        this.v0 = v0;
        this.v1 = v1;
        this.v2 = v2;
    }

    public AABB computeAABB() {
        float minX = Math.min(v0.x, Math.min(v1.x, v2.x));
        float minY = Math.min(v0.y, Math.min(v1.y, v2.y));
        float minZ = Math.min(v0.z, Math.min(v1.z, v2.z));

        float maxX = Math.max(v0.x, Math.max(v1.x, v2.x));
        float maxY = Math.max(v0.y, Math.max(v1.y, v2.y));
        float maxZ = Math.max(v0.z, Math.max(v1.z, v2.z));

        return new AABB(new Vector3(minX, minY, minZ), new Vector3(maxX, maxY, maxZ));
    }
}
