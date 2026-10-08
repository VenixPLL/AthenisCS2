package me.venixpll.cheat.module.impl.aimbot;

import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.Setting;

import java.util.Arrays;
import java.util.List;

/**
 * PID Spring mode — models mouse as a mass on a spring anchored at target bone.
 */
public class PidSpringAimMode extends AimMode {

    public final FloatSetting pidStiffness = new FloatSetting(
            "Stiffness (kP)##aimbot", 75.632f, 1.0f, 149.0f);
    public final FloatSetting pidDamping = new FloatSetting(
            "Damping (kD)##aimbot", 35.256f, 0.5f, 70.0f);
    public final FloatSetting pidMass = new FloatSetting(
            "Mass##aimbot", 0.215f, 0.01f, 0.4f);
    public final FloatSetting pidMaxForce = new FloatSetting(
            "Max Force##aimbot", 1815.86f, 1.0f, 3600.0f);
    public final FloatSetting pidSensitivity = new FloatSetting(
            "Sensitivity##aimbot_pid", 0.06f, 0.01f, 0.2f);

    private volatile float springVelX = 0f;
    private volatile float springVelY = 0f;
    private float pidAccumX = 0f;
    private float pidAccumY = 0f;

    public PidSpringAimMode() {
        super(AimType.PID_SPRING);
    }

    @Override
    public List<Setting<?>> getSettings() {
        return Arrays.asList(pidStiffness, pidDamping, pidMass, pidMaxForce, pidSensitivity);
    }

    @Override
    public void reset() {
        springVelX = 0f;
        springVelY = 0f;
        pidAccumX = 0f;
        pidAccumY = 0f;
    }

    @Override
    public int[] tick(float bestDX, float bestDY, float bestDist, float fovPx, float hf, int sw, float dt) {
        float sens     = pidSensitivity.getValue();
        float mpp      = hf / (sw * 0.022f * sens);

        float targetX  = bestDX * mpp;
        float targetY  = bestDY * mpp;

        float kP       = pidStiffness.getValue();
        float kD       = pidDamping.getValue();
        float mass     = pidMass.getValue();
        float maxForce = pidMaxForce.getValue();

        float dtClamped = Math.min(dt, 0.020f);

        float forceX = kP * targetX - kD * springVelX;
        float forceY = kP * targetY - kD * springVelY;

        float forceMag = (float) Math.sqrt(forceX * forceX + forceY * forceY);
        if (forceMag > maxForce) {
            float scale = maxForce / forceMag;
            forceX *= scale;
            forceY *= scale;
        }

        springVelX += (forceX / mass) * dtClamped;
        springVelY += (forceY / mass) * dtClamped;

        float deltaX = springVelX * dtClamped;
        float deltaY = springVelY * dtClamped;

        pidAccumX += deltaX;
        pidAccumY += deltaY;
        int mx = (int) pidAccumX;
        int my = (int) pidAccumY;
        pidAccumX -= mx;
        pidAccumY -= my;

        return new int[]{ mx, my };
    }
}
