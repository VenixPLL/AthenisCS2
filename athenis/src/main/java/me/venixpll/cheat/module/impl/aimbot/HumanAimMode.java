package me.venixpll.cheat.module.impl.aimbot;

import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.Setting;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Human Aim Mode — features Bezier curve trajectory generation, kinematic velocity
 * profiling (ease-in, peak mid-speed, ease-out deceleration near target), and a
 * micro-correction system that randomizes the target hitbox pixel offset per snap.
 */
public class HumanAimMode extends AimMode {

    public final FloatSetting smooth = new FloatSetting(
            "Smooth##aimbot_human", 8.0f, 1.0f, 30.0f);
    public final FloatSetting sensitivity = new FloatSetting(
            "Sensitivity##aimbot_human", 1.5f, 0.1f, 10.0f);
    public final FloatSetting curveArc = new FloatSetting(
            "Curve Arc##aimbot_human", 4.0f, 0.0f, 15.0f);
    public final BooleanSetting microCorrection = new BooleanSetting(
            "Micro-Correction##aimbot_human", true);
    public final FloatSetting microRange = new FloatSetting(
            "Micro-Range (px)##aimbot_human", 3.0f, 0.5f, 10.0f);

    private final Random rng = new Random();

    // Kinematic & trajectory state
    private float accumX = 0f;
    private float accumY = 0f;
    private float initialDist = 0f;
    private float curveDirection = 1.0f; // +1 or -1 lateral arc direction

    // Micro-correction pixel offset (randomized per target snap)
    private float microOffsetX = 0f;
    private float microOffsetY = 0f;

    public HumanAimMode() {
        super(AimType.HUMAN);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(smooth, sensitivity, curveArc, microCorrection, microRange);
    }

    @Override
    public void reset() {
        accumX = 0f;
        accumY = 0f;
        initialDist = 0f;
        microOffsetX = 0f;
        microOffsetY = 0f;
    }

    private void generateMicroCorrection() {
        if (!microCorrection.getValue()) {
            microOffsetX = 0f;
            microOffsetY = 0f;
            return;
        }
        float maxR = microRange.getValue();
        float angle = rng.nextFloat() * 2.0f * (float) Math.PI;
        float r = (float) Math.sqrt(rng.nextFloat()) * maxR; // Uniform disk sampling
        microOffsetX = r * (float) Math.cos(angle);
        microOffsetY = r * (float) Math.sin(angle);
    }

    @Override
    public int[] tick(float bestDX, float bestDY, float bestDist, float fovPx, float hf, int sw, float dt) {
        float sens = sensitivity.getValue();
        float mpp  = hf / (sw * 0.022f * sens);

        // Apply micro-correction target offset if starting a snap or if unitialized
        if (initialDist <= 0f || Math.abs(bestDist - initialDist) > initialDist * 0.6f) {
            initialDist = bestDist;
            generateMicroCorrection();
            curveDirection = rng.nextBoolean() ? 1.0f : -1.0f;
        }

        // Target delta with micro-correction offset
        float targetDX = bestDX + microOffsetX;
        float targetDY = bestDY + microOffsetY;

        float rawTargetX = targetDX * mpp;
        float rawTargetY = targetDY * mpp;
        float currDist   = (float) Math.sqrt(targetDX * targetDX + targetDY * targetDY);

        if (currDist <= 0.001f) {
            return new int[]{ 0, 0 };
        }

        // Progress along target approach (0.0 at start, 1.0 at target)
        float progress = Math.max(0.0f, Math.min(1.0f, 1.0f - (currDist / Math.max(initialDist, currDist))));

        // ── Kinematic Velocity Profile (Ease-In / Cruising / Ease-Out Deceleration) ──
        float kinematicSpeed;
        if (progress < 0.25f) {
            // Ease-in (smooth acceleration at start)
            kinematicSpeed = 0.35f + 0.65f * (progress / 0.25f);
        } else if (progress < 0.70f) {
            // Fast mid-way cruising
            kinematicSpeed = 1.0f;
        } else {
            // Ease-out (decelerate right before reaching target)
            float remaining = (1.0f - progress) / 0.30f;
            kinematicSpeed = 0.20f + 0.80f * (remaining * remaining);
        }

        // ── Bezier Curve Arc (Perpendicular Lateral Offset) ──
        float dirX = rawTargetX / (currDist * mpp);
        float dirY = rawTargetY / (currDist * mpp);
        // Perpendicular unit vector
        float perpX = -dirY;
        float perpY =  dirX;

        // Quadratic Bezier arc envelope: max arc at mid-way (progress ~ 0.5)
        float arcMagnitude = (float) Math.sin(progress * Math.PI) * curveArc.getValue() * curveDirection * mpp;
        float arcX = perpX * arcMagnitude;
        float arcY = perpY * arcMagnitude;

        // Combine forward target velocity with Bezier curve lateral offset
        float sf = smooth.getValue();
        float moveX = (rawTargetX * kinematicSpeed + arcX) / sf;
        float moveY = (rawTargetY * kinematicSpeed + arcY) / sf;

        // Sub-pixel accumulation
        accumX += moveX;
        accumY += moveY;
        int mx = (int) accumX;
        int my = (int) accumY;
        accumX -= mx;
        accumY -= my;

        return new int[]{ mx, my };
    }
}
