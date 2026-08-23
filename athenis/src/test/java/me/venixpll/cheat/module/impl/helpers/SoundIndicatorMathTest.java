package me.venixpll.cheat.module.impl.helpers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure Sound ESP math ({@link SoundIndicatorMath}).
 * Encodes the Source-engine yaw conventions shared with RadarHackModule's
 * rotated radar so the ring indicators can never silently flip left/right.
 */
class SoundIndicatorMathTest {

    private static final double EPS = 1e-4;

    @Test
    void bearingStraightAheadIsZero() {
        // Yaw 0 faces world +X; enemy 500 units ahead must bear 0 (ring top).
        assertEquals(0.0, SoundIndicatorMath.bearingRadians(500f, 0f, 0f), EPS);
    }

    @Test
    void bearingRightIsHalfPi() {
        // Screen-right of yaw 0 is world -Y (right = (sin, -cos)).
        assertEquals(Math.PI / 2, SoundIndicatorMath.bearingRadians(0f, -500f, 0f), EPS);
    }

    @Test
    void bearingLeftIsNegativeHalfPi() {
        assertEquals(-Math.PI / 2, SoundIndicatorMath.bearingRadians(0f, 500f, 0f), EPS);
    }

    @Test
    void bearingBehindIsPi() {
        assertEquals(Math.PI, Math.abs(SoundIndicatorMath.bearingRadians(-500f, 0f, 0f)), EPS);
    }

    @Test
    void bearingRotatesWithViewYaw() {
        // Source yaw is counter-clockwise: at yaw = 90 degrees forward = world +Y.
        assertEquals(0.0, SoundIndicatorMath.bearingRadians(0f, 500f, 90f), EPS);
        // A source at world +X now sits 90 degrees to the right.
        assertEquals(Math.PI / 2, SoundIndicatorMath.bearingRadians(500f, 0f, 90f), EPS);
    }

    @Test
    void distanceAndMetersConversion() {
        assertEquals(500.0, SoundIndicatorMath.distanceUnits(300f, 400f), EPS);
        assertEquals(2.54, SoundIndicatorMath.unitsToMeters(100f), EPS);
    }

    @Test
    void envelopeFadesInPlateauThenFadesOut() {
        assertTrue(SoundIndicatorMath.envelope(0L, 1000L, 80L, 0.45f) < 0.2f);
        assertEquals(1.0, SoundIndicatorMath.envelope(200L, 1000L, 80L, 0.45f), EPS);
        assertEquals(1.0, SoundIndicatorMath.envelope(400L, 1000L, 80L, 0.45f), EPS);
        assertTrue(SoundIndicatorMath.envelope(700L, 1000L, 80L, 0.45f) < 1.0f);
        assertTrue(SoundIndicatorMath.envelope(700L, 1000L, 80L, 0.45f) > 0.0f);
        assertEquals(0.0, SoundIndicatorMath.envelope(1000L, 1000L, 80L, 0.45f), EPS);
        assertEquals(0.0, SoundIndicatorMath.envelope(1500L, 1000L, 80L, 0.45f), EPS);
    }

    @Test
    void envelopeNeverExitsUnitRange() {
        for (long age = 0; age <= 2000; age += 25) {
            float e = SoundIndicatorMath.envelope(age, 1600L, 80L, 0.45f);
            assertTrue(e >= 0f && e <= 1f, "envelope out of range at age " + age);
        }
    }

    @Test
    void easeOutCubicIsMonotonicAndBounded() {
        float prev = -1f;
        for (int i = 0; i <= 20; i++) {
            float e = SoundIndicatorMath.easeOutCubic(i / 20f);
            assertTrue(e >= prev, "easeOutCubic decreased at " + i);
            assertTrue(e >= 0f && e <= 1f);
            prev = e;
        }
    }
}