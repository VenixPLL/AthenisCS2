package me.venixpll.cheat.module.impl.aimbot;

import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.Setting;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Classic proportional smooth approach with optional humanise jitter.
 */
public class ClassicAimMode extends AimMode {

    public final FloatSetting smooth = new FloatSetting(
            "Smooth##aimbot", 6.5f, 1.0f, 30.0f);
    public final FloatSetting sensitivity = new FloatSetting(
            "Sensitivity##aimbot", 1.5f, 0.1f, 10.0f);
    public final BooleanSetting humanize = new BooleanSetting(
            "Humanize##aimbot", true);
    public final FloatSetting humanizeStrength = new FloatSetting(
            "Humanize Strength##aimbot", 3.6f, 0.0f, 20.0f);

    private final Random rng = new Random();
    private volatile float prevDeltaX = 0f;
    private volatile float prevDeltaY = 0f;
    private float accumX = 0f;
    private float accumY = 0f;

    public ClassicAimMode() {
        super(AimType.CLASSIC);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(smooth, sensitivity, humanize, humanizeStrength);
    }

    @Override
    public void reset() {
        prevDeltaX = 0f;
        prevDeltaY = 0f;
        accumX = 0f;
        accumY = 0f;
    }

    @Override
    public int[] tick(float bestDX, float bestDY, float bestDist, float fovPx, float hf, int sw, float dt) {
        float sens = sensitivity.getValue();
        float mpp  = hf / (sw * 0.022f * sens);

        float rawX = bestDX * mpp;
        float rawY = bestDY * mpp;

        // Smooth — far targets approach fast, close targets fine-tune
        float sf = smooth.getValue();
        if (sf > 1.0f) {
            float dr  = bestDist / fovPx; // 0 = centre, 1 = FOV edge
            float spf = 1.0f + dr;        // 1 = slow, 2 = fast
            rawX /= (sf * spf);
            rawY /= (sf * spf);
        }

        // Humanise
        if (humanize.getValue()) {
            float[] h = humanise(rawX, rawY);
            rawX = h[0];
            rawY = h[1];
        }

        // Sub-pixel accumulation
        accumX += rawX;
        accumY += rawY;
        int mx = (int) accumX;
        int my = (int) accumY;
        accumX -= mx;
        accumY -= my;

        return new int[]{ mx, my };
    }

    private float[] humanise(float rawX, float rawY) {
        float str = humanizeStrength.getValue() / 100.0f;
        if (str <= 0f) {
            prevDeltaX = rawX;
            prevDeltaY = rawY;
            return new float[]{ rawX, rawY };
        }
        float md = (float) Math.sqrt(rawX * rawX + rawY * rawY);
        float ms = Math.min(md * 0.12f, 3.0f) * str;
        float mx = (float) rng.nextGaussian() * ms;
        float my = (float) rng.nextGaussian() * ms;
        float ps = 0.10f * str;
        float px = -rawY * ps * (float) rng.nextGaussian();
        float py =  rawX * ps * (float) rng.nextGaussian();
        float bl = 0.75f + rng.nextFloat() * 0.20f;
        float sx = rawX * bl + prevDeltaX * (1.0f - bl);
        float sy = rawY * bl + prevDeltaY * (1.0f - bl);
        prevDeltaX = rawX;
        prevDeltaY = rawY;
        return new float[]{ sx + mx + px, sy + my + py };
    }
}
