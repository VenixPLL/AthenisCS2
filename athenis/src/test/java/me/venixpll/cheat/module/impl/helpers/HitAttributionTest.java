package me.venixpll.cheat.module.impl.helpers;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the Damage ESP hit-attribution decision logic.
 * <p>
 * These encode the two-signal rule (recent local shot + victim under the
 * crosshair) that replaced the old "enemy near crosshair within 1 s" heuristic,
 * which both missed real hits and credited other players' damage.
 */
class HitAttributionTest {

    private static final long WINDOW = 400L;   // SHOT_ATTRIBUTION_WINDOW_MS
    private static final long GRACE  = 200L;   // PROXIMITY_GRACE_MS

    // ── isValidDelta ──────────────────────────────────────────────────────────

    @Test
    void zeroDeltaIsNotDamage() {
        assertFalse(HitAttribution.isValidDelta(0));
    }

    @Test
    void negativeDeltaIsHealingNotDamage() {
        assertFalse(HitAttribution.isValidDelta(-1));
        assertFalse(HitAttribution.isValidDelta(-100));
    }

    @Test
    void minimalDamageCounts() {
        assertTrue(HitAttribution.isValidDelta(1));
    }

    /**
     * A full-HP one-shot kill produces delta == exactly 100. The old code used
     * {@code delta < 100} and silently discarded every such hit.
     */
    @Test
    void fullHealthOneShotKillCounts() {
        assertTrue(HitAttribution.isValidDelta(100));
    }

    @Test
    void overkillImpossibleValuesAreRejected() {
        assertFalse(HitAttribution.isValidDelta(101));
        assertFalse(HitAttribution.isValidDelta(5000));
    }

    // ── isMine: shot gate ─────────────────────────────────────────────────────

    @Test
    void noShotEverFiredNeverAttributes() {
        // Enemy HP dropped while we never fired — must be someone else's damage.
        assertFalse(HitAttribution.isMine(1_000L, -1L, WINDOW, 950L, GRACE));
    }

    @Test
    void shotOlderThanWindowDoesNotAttribute() {
        // Shot 401 ms ago, drop observed now — too old to explain it.
        assertFalse(HitAttribution.isMine(1_000L, 599L, WINDOW, 700L, GRACE));
    }

    @Test
    void shotExactlyAtWindowBoundaryAttributes() {
        // Boundary inclusive: shot exactly WINDOW ms ago still explains the drop.
        assertTrue(HitAttribution.isMine(1_000L, 600L, WINDOW, 650L, GRACE));
    }

    @Test
    void futureShotTimestampRejected() {
        // Defensive: a malformed future timestamp must not attribute.
        assertFalse(HitAttribution.isMine(1_000L, 1_500L, WINDOW, 950L, GRACE));
    }

    // ── isMine: proximity gate ────────────────────────────────────────────────

    @Test
    void victimNeverUnderCrosshairDoesNotAttribute() {
        // We fired recently but this enemy was never in our crosshair —
        // e.g. teammate damaged an enemy standing next to our target.
        assertFalse(HitAttribution.isMine(1_000L, 900L, WINDOW, -1L, GRACE));
    }

    @Test
    void proximityPredatingShotBeyondGraceDoesNotAttribute() {
        // Victim was under crosshair long before we fired — we aimed away
        // before shooting (spray into wall while teammate trades).
        assertFalse(HitAttribution.isMine(1_000L, 900L, WINDOW, 600L, GRACE));
    }

    @Test
    void proximityJustBeforeShotWithinGraceAttributes() {
        // Tick-alignment slack: proximity sample landed slightly before the
        // shot stamp but within the grace window.
        assertTrue(HitAttribution.isMine(1_000L, 900L, WINDOW, 750L, GRACE));
    }

    @Test
    void proximityAfterShotAttributes() {
        // Normal case: aim at enemy → fire → damage lands shortly after.
        assertTrue(HitAttribution.isMine(1_000L, 850L, WINDOW, 900L, GRACE));
    }

    @Test
    void fullRealisticHitSequenceAttributes() {
        // t=1000 fire detected, t=1050 enemy seen under crosshair,
        // t=1200 HP drop observed (replication + sampling delay).
        long shot = 1_000L;
        assertTrue(HitAttribution.isMine(1_200L, shot, WINDOW, 1_050L, GRACE));
    }

    @Test
    void spectatingAfterDeathDoesNotAttribute() {
        // Local player dead for 10 s (no shots possible), teammates fighting:
        // enemy near screen center takes damage → must NOT be credited.
        assertFalse(HitAttribution.isMine(10_000L, 2_000L, WINDOW, 9_950L, GRACE));
    }
}