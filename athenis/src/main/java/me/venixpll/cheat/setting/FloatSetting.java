package me.venixpll.cheat.setting;

import imgui.ImGui;

/**
 * Setting implementation wrapping float configurations.
 * Restricts values between custom minimum/maximum boundaries and renders an ImGui slider float widget.
 */
public class FloatSetting extends Setting<Float> {
    private final float[] valueArray;
    private final float min;
    private final float max;

    /**
     * Constructs a new FloatSetting.
     *
     * @param name         User-friendly label.
     * @param defaultValue Default starting value.
     * @param min          Minimum allowed slider boundary value.
     * @param max          Maximum allowed slider boundary value.
     */
    public FloatSetting(String name, float defaultValue, float min, float max) {
        super(name);
        this.valueArray = new float[]{defaultValue};
        this.min = min;
        this.max = max;
    }

    /**
     * Retrieves current float value.
     */
    @Override
    public Float getValue() {
        return valueArray[0];
    }

    /**
     * Updates float value, enforcing bounds.
     */
    @Override
    public void setValue(Float value) {
        valueArray[0] = Math.max(min, Math.min(max, value));
    }

    /**
     * Renders a Dear ImGui SliderFloat widget synchronized with the underlying float array.
     */
    @Override
    public void renderImGui() {
        ImGui.sliderFloat(getName(), valueArray, min, max);
    }
}
