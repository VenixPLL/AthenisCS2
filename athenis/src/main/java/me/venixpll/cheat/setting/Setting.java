package me.venixpll.cheat.setting;

/**
 * Abstract container representing a configurable cheat option.
 * Implements Javadoc-documented configuration values and rendering routines.
 *
 * @param <T> The native Java type representing the value (e.g. Boolean, Float, float[]).
 */
public abstract class Setting<T> {
    private final String name;

    /**
     * Constructs a new setting option.
     *
     * @param name User-friendly label of the setting option.
     */
    public Setting(String name) {
        this.name = name;
    }

    /**
     * Gets the user-friendly label of this setting.
     *
     * @return Label string.
     */
    public String getName() {
        return name;
    }

    /**
     * Retrieves the current native Java value.
     *
     * @return The current configuration value.
     */
    public abstract T getValue();

    /**
     * Updates the current configuration parameter.
     *
     * @param value The new configuration parameter.
     */
    public abstract void setValue(T value);

    /**
     * Invokes the JNI render hook for Dear ImGui.
     * Translates setting status dynamically to widgets (checkboxes, sliders, inputs).
     */
    public abstract void renderImGui();
}
