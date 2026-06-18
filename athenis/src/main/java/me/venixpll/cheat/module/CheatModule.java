package me.venixpll.cheat.module;

import imgui.ImDrawList;
import imgui.type.ImBoolean;
import me.venixpll.cheat.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Base abstract class representing a modular cheat feature.
 * Provides Javadocs and hooks for background memory ticks and screen-space overlay drawing.
 */
public abstract class CheatModule {
    private final String name;
    private final ModuleCategory category;
    private final ImBoolean enabledWrapper;
    private final List<Setting<?>> settings = new ArrayList<>();

    /**
     * Constructs a new CheatModule.
     *
     * @param name           Unique user-friendly name of the module.
     * @param category       The safety/execution category (External/Internal).
     * @param defaultEnabled Initial state of the module.
     */
    public CheatModule(String name, ModuleCategory category, boolean defaultEnabled) {
        this.name = name;
        this.category = category;
        this.enabledWrapper = new ImBoolean(defaultEnabled);
    }

    /**
     * Gets the user-friendly name of this module.
     *
     * @return User-friendly name.
     */
    public String getName() {
        return name;
    }

    /**
     * Gets the category of this module.
     *
     * @return The module category.
     */
    public ModuleCategory getCategory() {
        return category;
    }

    /**
     * Checks if this module is currently enabled.
     *
     * @return True if active, false otherwise.
     */
    public boolean isEnabled() {
        return enabledWrapper.get();
    }

    /**
     * Sets the enabled state of this module.
     *
     * @param enabled The new active state.
     */
    public void setEnabled(boolean enabled) {
        this.enabledWrapper.set(enabled);
    }

    /**
     * Gets the internal ImBoolean enabled state wrapper.
     *
     * @return The ImBoolean state wrapper.
     */
    public ImBoolean getEnabledWrapper() {
        return enabledWrapper;
    }

    /**
     * Registers a configuration setting with this module.
     *
     * @param setting The setting to register.
     */
    protected void addSetting(Setting<?> setting) {
        settings.add(setting);
    }

    /**
     * Retrieves all registered configuration settings for this module.
     *
     * @return List of registered settings.
     */
    public List<Setting<?>> getSettings() {
        return settings;
    }

    /**
     * Invoked periodically by the background memory-reading thread.
     * Use this hook for passive memory manipulation or status checks.
     */
    public void onTick() {}

    /**
     * Invoked on the main rendering thread.
     * Use this hook to draw on the ImGui overlay screen.
     *
     * @param drawList The ImGui foreground draw list.
     */
    public void onRender(ImDrawList drawList) {}
}
