package me.venixpll.overlay;

import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.setting.Setting;

/**
 * Interface coordinator for the interactive configuration menu window.
 * Dynamically queries all registered cheat modules and renders checkboxes, sliders,
 * and inputs for settings registered in each module.
 */
public class OverlayMenu {

    /**
     * Constructs and draws the configurations GUI panel on screen dynamically.
     */
    public static void render() {
        ImGui.begin("Athenis CS2 Menu",
                ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize);

        // Dynamically loop over all registered modules
        for (CheatModule module : ModuleManager.getModules()) {
            // Render enabled state checkbox for the module itself
            ImGui.checkbox("Enable " + module.getName(), module.getEnabledWrapper());

            // If module is enabled and exposes registered settings, render them
            if (module.isEnabled() && !module.getSettings().isEmpty()) {
                ImGui.indent();
                for (Setting<?> setting : module.getSettings()) {
                    boolean disabled = false;
                    if (module instanceof me.venixpll.cheat.module.impl.RadarHackModule) {
                        me.venixpll.cheat.module.impl.RadarHackModule radarMod = (me.venixpll.cheat.module.impl.RadarHackModule) module;
                        if (radarMod.autoAlign.getValue()) {
                            String sName = setting.getName();
                            if (sName.equals("Radar X Pos") || sName.equals("Radar Y Pos") || sName.equals("Radar Size")) {
                                disabled = true;
                            }
                        }
                        if (radarMod.squareRadar.getValue() && radarMod.autoMapRadar.getValue()) {
                            String sName = setting.getName();
                            if (sName.equals("Map Center X") || sName.equals("Map Center Y") || sName.equals("Radar Scale (Zoom)")) {
                                disabled = true;
                            }
                        }
                    }

                    if (disabled) {
                        ImGui.beginDisabled(true);
                    }
                    setting.renderImGui();
                    if (disabled) {
                        ImGui.endDisabled();
                    }
                }
                ImGui.unindent();
            }
            ImGui.spacing();
        }

        ImGui.separator();
        ImGui.text(String.format("Render FPS: %d", (int) ImGui.getIO().getFramerate()));

        ImGui.end();
    }
}
