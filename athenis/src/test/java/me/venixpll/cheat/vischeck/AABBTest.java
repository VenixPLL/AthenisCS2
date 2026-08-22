package me.venixpll.cheat.vischeck;

import me.venixpll.cheat.Vector3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AABBTest {

    private AABB box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return new AABB(new Vector3(minX, minY, minZ), new Vector3(maxX, maxY, maxZ));
    }

    @Test
    void rayThroughBoxIntersects() {
        AABB box = box(-1, -1, 5, 1, 1, 10);
        Vector3 origin = new Vector3(0, 0, 0);
        Vector3 dir = new Vector3(0, 0, 1); // straight through the box
        assertTrue(box.rayIntersects(origin, dir));
    }

    @Test
    void rayMissingBoxDoesNotIntersect() {
        AABB box = box(-1, -1, 5, 1, 1, 10);
        Vector3 origin = new Vector3(0, 0, 0);
        Vector3 dir = new Vector3(0, 1, 0); // parallel alongside the box
        assertFalse(box.rayIntersects(origin, dir));
    }

    @Test
    void rayPointingAwayDoesNotIntersect() {
        AABB box = box(-1, -1, 5, 1, 1, 10);
        Vector3 origin = new Vector3(0, 0, 0);
        Vector3 dir = new Vector3(0, 0, -1); // opposite direction
        assertFalse(box.rayIntersects(origin, dir));
    }

    @Test
    void diagonalRayIntersects() {
        AABB box = box(2, 2, 2, 4, 4, 4);
        Vector3 origin = new Vector3(0, 0, 0);
        Vector3 dir = new Vector3(1, 1, 1);
        assertTrue(box.rayIntersects(origin, dir));
    }

    @Test
    void negativeDirectionComponentsAreHandled() {
        // Ray travelling in -X must still hit a box located at negative X.
        AABB box = box(-10, -1, -1, -5, 1, 1);
        Vector3 origin = new Vector3(0, 0, 0);
        Vector3 dir = new Vector3(-1, 0, 0);
        assertTrue(box.rayIntersects(origin, dir));
    }

    @Test
    void originInsideBoxIntersects() {
        AABB box = box(-5, -5, -5, 5, 5, 5);
        Vector3 origin = new Vector3(0, 0, 0);
        Vector3 dir = new Vector3(0.3f, -0.7f, 0.1f);
        assertTrue(box.rayIntersects(origin, dir));
    }
}