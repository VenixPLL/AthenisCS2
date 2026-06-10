package me.venixpll.cheat.setting;

import imgui.ImGui;

/**
 * Setting implementation wrapping color (RGBA) configurations.
 * Manages a float array representing red, green, blue, and alpha values, linked to a color picker.
 */
public class ColorSetting extends Setting<float[]> {
    private final float[] colorArray;

    /**
     * Constructs a new ColorSetting.
     *
     * @param name User-friendly label.
     * @param r    Red channel (0.0 to 1.0).
     * @param g    Green channel (0.0 to 1.0).
     * @param b    Blue channel (0.0 to 1.0).
     * @param a    Alpha channel (0.0 to 1.0).
     */
    public ColorSetting(String name, float r, float g, float b, float a) {
        super(name);
        this.colorArray = new float[]{r, g, b, a};
    }

    /**
     * Retrieves the color float array.
     */
    @Override
    public float[] getValue() {
        return colorArray;
    }

    /**
     * Updates the color float array.
     *
     * @param value A float array of size 4 representing RGBA.
     */
    @Override
    public void setValue(float[] value) {
        if (value != null && value.length >= 4) {
            System.arraycopy(value, 0, colorArray, 0, 4);
        }
    }

    /**
     * Renders a Dear ImGui ColorEdit4 picker widget synchronized with the underlying color array.
     */
    @Override
    public void renderImGui() {
        ImGui.colorEdit4(getName(), colorArray);
    }
}
