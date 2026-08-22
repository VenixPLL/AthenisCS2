package me.venixpll.cheat;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Vector3Test {

    private static final float EPS = 1e-5f;

    @Test
    void defaultConstructorIsZero() {
        Vector3 v = new Vector3();
        assertEquals(0f, v.x);
        assertEquals(0f, v.y);
        assertEquals(0f, v.z);
    }

    @Test
    void addReturnsComponentwiseSum() {
        Vector3 a = new Vector3(1, 2, 3);
        Vector3 b = new Vector3(10, 20, 30);
        Vector3 r = a.add(b);
        assertEquals(11f, r.x, EPS);
        assertEquals(22f, r.y, EPS);
        assertEquals(33f, r.z, EPS);
        // Originals unchanged (immutability of the operation)
        assertEquals(1f, a.x);
        assertEquals(20f, b.y);
    }

    @Test
    void subtractReturnsComponentwiseDifference() {
        Vector3 a = new Vector3(5, 7, 9);
        Vector3 b = new Vector3(1, 2, 3);
        Vector3 r = a.subtract(b);
        assertEquals(4f, r.x, EPS);
        assertEquals(5f, r.y, EPS);
        assertEquals(6f, r.z, EPS);
    }

    @Test
    void dotProductMatchesDefinition() {
        Vector3 a = new Vector3(1, 2, 3);
        Vector3 b = new Vector3(4, -5, 6);
        assertEquals(4f - 10f + 18f, a.dot(b), EPS);
    }

    @Test
    void dotOfPerpendicularVectorsIsZero() {
        Vector3 a = new Vector3(1, 0, 0);
        Vector3 b = new Vector3(0, 1, 0);
        assertEquals(0f, a.dot(b), EPS);
    }

    @Test
    void crossProductFollowsRightHandRule() {
        Vector3 x = new Vector3(1, 0, 0);
        Vector3 y = new Vector3(0, 1, 0);
        Vector3 z = x.cross(y);
        assertEquals(0f, z.x, EPS);
        assertEquals(0f, z.y, EPS);
        assertEquals(1f, z.z, EPS); // X × Y = Z
    }

    @Test
    void crossProductIsAnticommutative() {
        Vector3 a = new Vector3(2, 3, 4);
        Vector3 b = new Vector3(-1, 5, 2);
        Vector3 ab = a.cross(b);
        Vector3 ba = b.cross(a);
        assertEquals(-ab.x, ba.x, EPS);
        assertEquals(-ab.y, ba.y, EPS);
        assertEquals(-ab.z, ba.z, EPS);
    }

    @Test
    void distanceUsesEuclideanMetric() {
        Vector3 a = new Vector3(0, 0, 0);
        Vector3 b = new Vector3(3, 4, 0);
        assertEquals(5f, a.distance(b), EPS); // classic 3-4-5 triangle
    }

    @Test
    void distanceIn3D() {
        Vector3 a = new Vector3(1, 2, 3);
        Vector3 b = new Vector3(1, 2, 3);
        assertEquals(0f, a.distance(b), EPS);

        Vector3 c = new Vector3(2, 4, 6);
        // sqrt(1 + 4 + 9) = sqrt(14)
        assertEquals((float) Math.sqrt(14f), a.distance(c), EPS);
    }

    @Test
    void lengthSquaredMatchesManualComputation() {
        Vector3 v = new Vector3(2, -3, 6);
        assertEquals(4f + 9f + 36f, v.lengthSquared(), EPS);
    }
}