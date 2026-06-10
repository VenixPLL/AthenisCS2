package me.venixpll.cheat.setting;

import imgui.ImGui;
import imgui.type.ImBoolean;

/**
 * Setting implementation wrapping boolean configurations.
 * Utilizes ImBoolean wrapper to link with Dear ImGui checkbox widgets.
 */
public class BooleanSetting extends Setting<Boolean> {
    private final ImBoolean valueWrapper;

    /**
     * Constructs a new BooleanSetting.
     *
     * @param name         User-friendly label.
     * @param defaultValue Default setting state.
     */
    public BooleanSetting(String name, boolean defaultValue) {
        super(name);
        this.valueWrapper = new ImBoolean(defaultValue);
    }

    /**
     * Retrieves current boolean value.
     */
    @Override
    public Boolean getValue() {
        return valueWrapper.get();
    }

    /**
     * Updates boolean value.
     */
    @Override
    public void setValue(Boolean value) {
        valueWrapper.set(value);
    }

    /**
     * Retrieves the internal ImBoolean wrapper instance.
     *
     * @return Underlying ImBoolean instance.
     */
    public ImBoolean getWrapper() {
        return valueWrapper;
    }

    /**
     * Renders a Dear ImGui Checkbox widget synchronized with the underlying boolean wrapper.
     */
    @Override
    public void renderImGui() {
        ImGui.checkbox(getName(), valueWrapper);
    }
}
