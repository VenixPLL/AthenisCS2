package me.venixpll.cheat.module.impl.aimbot;

import me.venixpll.cheat.setting.Setting;

import java.util.List;

/**
 * Base abstract class for modular aimbot modes.
 */
public abstract class AimMode {

    private final AimType type;

    public AimMode(AimType type) {
        this.type = type;
    }

    public AimType getType() {
        return type;
    }

    public String getName() {
        return type.getDisplayName();
    }

    /**
     * @return List of all settings belonging to this aim mode.
     */
    public abstract List<Setting<?>> getSettings();

    /**
     * Resets any internal accumulated state (e.g. velocity, sub-pixel accumulators).
     */
    public abstract void reset();

    /**
     * Calculates relative mouse movement deltas for one tick.
     *
     * @param bestDX   Screen-pixel offset X to target
     * @param bestDY   Screen-pixel offset Y to target
     * @param bestDist Distance in screen pixels to target
     * @param fovPx    FOV radius in screen pixels
     * @param hf       Horizontal FOV in degrees
     * @param sw       Screen width in pixels
     * @param dt       Time delta in seconds since last tick
     * @return Array of {dx, dy} relative mouse movement counts
     */
    public abstract int[] tick(float bestDX, float bestDY, float bestDist, float fovPx, float hf, int sw, float dt);
}
