package me.venixpll.cheat.module.impl.helpers;

/**
 * Pure decision logic for the Damage ESP hit-attribution pipeline.
 * <p>
 * External overlays cannot observe the game's server-side damage events, so
 * damage must be attributed heuristically. This class encodes the two-signal
 * rule that keeps the attribution reliable:
 * <ol>
 * <li><b>Shot gate</b> — the local player must have fired recently (detected
 * via {@code m_iShotsFired} transitions on the local pawn). Without a recent
 * shot there is no plausible way the local player caused the HP drop, so any
 * delta observed is someone else's damage (teammates, grenades, other fights)
 * and must be ignored. This also automatically suppresses false credits while
 * spectating after death.</li>
 * <li><b>Crosshair proximity</b> — the victim must have been observed under
 * the crosshair at (or slightly before) the moment of the shot. This filters
 * stray shots into walls coinciding with teammate damage on a nearby enemy.</li>
 * </ol>
 * <p>
 * Kept free of any memory/ImGui dependencies so it can be unit-tested.
 */
public final class HitAttribution {

    private HitAttribution() {}

    /**
     * Validates an observed health drop.
     *
     * @param delta previous health minus current health.
     * @return {@code true} when the drop represents real weapon damage
     *         (1..100 inclusive — 100 must count so full-HP one-shot kills
     *         are not silently discarded).
     */
    public static boolean isValidDelta(int delta) {
        return delta >= 1 && delta <= 100;
    }

    /**
     * Decides whether an observed health drop belongs to the local player.
     *
     * @param now               current wall-clock timestamp (ms).
     * @param lastShotMs        timestamp of the most recent detected local shot,
     *                          or -1 when no shot has ever been detected.
     * @param shotWindowMs      maximum age of a shot that can still explain the
     *                          drop (covers network replication + tick sampling).
     * @param crosshairTsMs     timestamp at which the victim was last seen under
     *                          the crosshair, or -1 when never.
     * @param proximityGraceMs  how far before the shot a proximity sample may
     *                          predate it and still count (tick-alignment slack).
     * @return {@code true} when both the shot gate and the proximity condition hold.
     */
    public static boolean isMine(long now, long lastShotMs, long shotWindowMs,
                                 long crosshairTsMs, long proximityGraceMs) {
        if (lastShotMs < 0) return false;
        long shotAge = now - lastShotMs;
        if (shotAge < 0 || shotAge > shotWindowMs) return false;
        if (crosshairTsMs < 0) return false;
        return crosshairTsMs >= lastShotMs - proximityGraceMs;
    }
}