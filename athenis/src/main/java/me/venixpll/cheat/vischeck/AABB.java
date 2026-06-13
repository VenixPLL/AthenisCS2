package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;

public class AABB {
    public Vector3 min;
    public Vector3 max;

    public AABB() {
        this.min = new Vector3();
        this.max = new Vector3();
    }

    public AABB(Vector3 min, Vector3 max) {
        this.min = min;
        this.max = max;
    }

    public boolean rayIntersects(Vector3 rayOrigin, Vector3 rayDir) {
        float tmin = -Float.MAX_VALUE;
        float tmax = Float.MAX_VALUE;

        // X-axis
        float invDirX = 1.0f / rayDir.x;
        float t0x = (min.x - rayOrigin.x) * invDirX;
        float t1x = (max.x - rayOrigin.x) * invDirX;
        if (invDirX < 0.0f) {
            float temp = t0x;
            t0x = t1x;
            t1x = temp;
        }
        tmin = Math.max(tmin, t0x);
        tmax = Math.min(tmax, t1x);

        // Y-axis
        float invDirY = 1.0f / rayDir.y;
        float t0y = (min.y - rayOrigin.y) * invDirY;
        float t1y = (max.y - rayOrigin.y) * invDirY;
        if (invDirY < 0.0f) {
            float temp = t0y;
            t0y = t1y;
            t1y = temp;
        }
        tmin = Math.max(tmin, t0y);
        tmax = Math.min(tmax, t1y);

        // Z-axis
        float invDirZ = 1.0f / rayDir.z;
        float t0z = (min.z - rayOrigin.z) * invDirZ;
        float t1z = (max.z - rayOrigin.z) * invDirZ;
        if (invDirZ < 0.0f) {
            float temp = t0z;
            t0z = t1z;
            t1z = temp;
        }
        tmin = Math.max(tmin, t0z);
        tmax = Math.min(tmax, t1z);

        return tmax >= tmin && tmax >= 0.0f;
    }
}
