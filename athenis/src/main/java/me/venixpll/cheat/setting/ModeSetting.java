package me.venixpll.cheat.setting;

import imgui.ImGui;
import imgui.type.ImInt;

/**
 * A setting backed by a fixed array of string choices rendered as an ImGui Combo widget.
 * The active value is the index of the currently selected option.
 */
public class ModeSetting extends Setting<Integer> {

    private final String[] options;
    private final ImInt   selected;

    /**
     * @param name         Display label.
     * @param defaultIndex Index of the default selection.
     * @param options      Array of option strings shown in the combo.
     */
    public ModeSetting(String name, int defaultIndex, String... options) {
        super(name);
        this.options  = options;
        this.selected = new ImInt(defaultIndex);
    }

    @Override
    public Integer getValue() {
        return selected.get();
    }

    @Override
    public void setValue(Integer value) {
        if (value >= 0 && value < options.length) {
            selected.set(value);
        }
    }

    /** Convenience: returns the label of the currently selected option. */
    public String getSelectedLabel() {
        return options[selected.get()];
    }

    @Override
    public void renderImGui() {
        ImGui.combo(getName(), selected, options);
    }
}
