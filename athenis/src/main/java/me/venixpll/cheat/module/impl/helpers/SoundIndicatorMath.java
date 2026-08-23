package me.venixpll.cheat.module.impl.helpers;

/**
 * Pure math for the Sound ESP directional indicators (no ImGui, no process
 * memory access — fully unit-testable, same philosophy as {@link HitAttribution}).
 */
public final class SoundIndicatorMath {

    private SoundIndicatorMath() {
    }

    /**
     * Bearing of a world-space offset relative to the local view yaw, expressed
     * the way the indicator ring is drawn: {@code 0} = straight ahead (top of
     * the ring), {@code +PI/2} = directly right, {@code -PI/2} = directly left,
     * {@code ±PI} = directly behind.
     *
     * <p>Uses the same rotation convention as RadarHackModule's rotated radar:
     * Source forward for view yaw θ is {@code (cos θ, sin θ)} and screen-right
     * is {@code (sin θ, -cos θ)}.</p>
     */
    public static float bearingRadians(float dx, float dy, float yawDeg) {
        double yawRad = Math.toRadians(yawDeg);
        float cos = (float) Math.cos(yawRad);
        float sin = (float) Math.sin(yawRad);
        float forward = dx * cos + dy * sin;
        float right   = dx * sin - dy * cos;
        return (float) Math.atan2(right, forward);
    }

    /** Planar distance of a world-space offset, in world units. */
    public static float distanceUnits(float dx, float dy) {
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    /** Converts world units to meters (CS2: 1 unit = 1 inch). */
    public static float unitsToMeters(float units) {
        return units * 0.0254f;
    }

    /**
     * Indicator alpha envelope for a ping of {@code ageMs} age and
     * {@code durationMs} lifetime: quick linear fade-in over {@code fadeInMs},
     * full brightness until {@code fadeStartFraction} of the lifetime has
     * elapsed, then a smoothstep fade-out. Result is clamped to [0, 1].
     */
    public static float envelope(long ageMs, long durationMs, long fadeInMs, float fadeStartFraction) {
        if (durationMs <= 0L || ageMs <= 0L || ageMs >= durationMs) return 0f;
        float fadeIn = fadeInMs <= 0L ? 1f : Math.min(1f, ageMs / (float) fadeInMs);
        float t = ageMs / (float) durationMs;
        float fadeOut = 1f;
        if (t > fadeStartFraction) {
            float ft = (t - fadeStartFraction) / (1f - fadeStartFraction);
            fadeOut = 1f - ft * ft * (3f - 2f * ft);
        }
        float a = fadeIn * fadeOut;
        return a < 0f ? 0f : Math.min(a, 1f);
    }

    /** Ease-out cubic, {@code t} clamped to [0, 1] — used for the ripple drift. */
    public static float easeOutCubic(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float c = 1f - t;
        return 1f - c * c * c;
    }

    /** Clamps to [0, 1]. */
    public static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }
}