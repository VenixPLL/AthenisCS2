package me.venixpll.cheat.module;

import imgui.ImDrawList;
import imgui.type.ImBoolean;
import me.venixpll.cheat.setting.Setting;

import java.util.ArrayList;
import java.util.List;

/**
 * Base abstract class representing a modular cheat feature.
 * Provides Javadocs and hooks for background memory ticks and screen-space
 * overlay drawing.
 */
public abstract class CheatModule {
    private final String name;
    private final ModuleCategory category;
    private final MenuGroup menuGroup;
    private final ImBoolean enabledWrapper;
    private final List<Setting<?>> settings = new ArrayList<>();
    private boolean settingsExpanded = true;

    /**
     * Virtual-key code (Windows VK) of the keybind that toggles this module.
     * A value of {@code -1} means no bind is set.
     */
    private int bindKey = -1;

    /**
     * When {@code true} the GUI is waiting for the next key-press to assign
     * as this module's toggle bind.
     */
    private boolean listeningForBind = false;

    /**
     * Constructs a new CheatModule with an explicit sidebar menu group.
     *
     * @param name           Unique user-friendly name of the module.
     * @param category       The safety/execution category (External/Internal).
     * @param menuGroup      The sidebar category group this module appears in.
     * @param defaultEnabled Initial state of the module.
     */
    public CheatModule(String name, ModuleCategory category, MenuGroup menuGroup, boolean defaultEnabled) {
        this.name = name;
        this.category = category;
        this.menuGroup = menuGroup;
        this.enabledWrapper = new ImBoolean(defaultEnabled);
    }

    /**
     * Constructs a new CheatModule, defaulting the sidebar group to
     * {@link MenuGroup#OTHER} for backward compatibility.
     *
     * @param name           Unique user-friendly name of the module.
     * @param category       The safety/execution category (External/Internal).
     * @param defaultEnabled Initial state of the module.
     */
    public CheatModule(String name, ModuleCategory category, boolean defaultEnabled) {
        this(name, category, MenuGroup.OTHER, defaultEnabled);
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
     * Gets the sidebar menu group this module belongs to.
     *
     * @return The {@link MenuGroup} for sidebar grouping.
     */
    public MenuGroup getMenuGroup() {
        return menuGroup;
    }

    /**
     * Checks if the settings section is expanded in the GUI.
     *
     * @return True if expanded, false if collapsed.
     */
    public boolean isSettingsExpanded() {
        return settingsExpanded;
    }

    /**
     * Sets the expanded state of the settings section in the GUI.
     *
     * @param settingsExpanded The new expanded state.
     */
    public void setSettingsExpanded(boolean settingsExpanded) {
        this.settingsExpanded = settingsExpanded;
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
     * Gets the Windows Virtual-Key code assigned as the toggle keybind for
     * this module, or {@code -1} if no bind is set.
     *
     * @return Windows VK code, or {@code -1}.
     */
    public int getBindKey() {
        return bindKey;
    }

    /**
     * Sets the Windows Virtual-Key code that will toggle this module.
     * Pass {@code -1} to clear the bind.
     *
     * @param bindKey Windows VK code, or {@code -1} to clear.
     */
    public void setBindKey(int bindKey) {
        this.bindKey = bindKey;
    }

    /**
     * Returns whether the GUI is currently waiting for a key-press to assign
     * as this module's toggle bind.
     *
     * @return {@code true} if listening for a new bind key.
     */
    public boolean isListeningForBind() {
        return listeningForBind;
    }

    /**
     * Sets whether the GUI should wait for the next key-press to assign as
     * this module's toggle bind.
     *
     * @param listening {@code true} to start listening; {@code false} to stop.
     */
    public void setListeningForBind(boolean listening) {
        this.listeningForBind = listening;
    }

    /**
     * Invoked periodically by the background memory-reading thread.
     * Use this hook for passive memory manipulation or status checks.
     */
    public void onTick() {
    }

    /**
     * Invoked on the main rendering thread.
     * Use this hook to draw on the ImGui overlay screen.
     *
     * @param drawList The ImGui foreground draw list.
     */
    public void onRender(ImDrawList drawList) {
    }
}
