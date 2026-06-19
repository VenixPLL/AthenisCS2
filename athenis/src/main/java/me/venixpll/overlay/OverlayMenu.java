package me.venixpll.overlay;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.Setting;
import me.venixpll.cheat.vischeck.VisCheckAdapter;
import me.venixpll.cheat.vischeck.VisCheck;

/**
 * Interface coordinator for the interactive configuration menu window.
 * Dynamically queries all registered cheat modules and renders checkboxes, sliders,
 * and inputs for settings registered in each module.
 */
public class OverlayMenu {

    // ── VisCheck debug widget state (kept between frames) ─────────────────────
    private static final ImBoolean visDebugEnabled  = new ImBoolean(VisCheck.DEBUG);
    private static final int[]     visDebugThrottle = { (int) VisCheck.DEBUG_THROTTLE_MS };

    /**
     * Constructs and draws the configurations GUI panel on screen dynamically.
     */
    public static void render() {
        // Window 1: External Menu
        ImGui.setNextWindowPos(100f, 100f, ImGuiCond.FirstUseEver);
        ImGui.begin("External",
                ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize);

        // Dynamically loop over all registered modules matching category EXTERNAL
        for (CheatModule module : ModuleManager.getModules()) {
            if (module.getCategory() == ModuleCategory.EXTERNAL) {
                renderModule(module);
            }
        }

        ImGui.separator();
        ImGui.text(String.format("Render FPS: %d", (int) ImGui.getIO().getFramerate()));

        // ── VisCheck Debug section ────────────────────────────────────────────
        ImGui.separator();
        ImGui.text("VisCheck Debug");
        ImGui.indent();

        // Sync checkbox state from the live flag (another thread may have changed it)
        visDebugEnabled.set(VisCheck.DEBUG);
        if (ImGui.checkbox("Enable Ray Debug Logging##viscDebug", visDebugEnabled)) {
            VisCheckAdapter.setDebug(visDebugEnabled.get());
        }

        if (VisCheck.DEBUG) {
            // Throttle slider (0 = always print, up to 5000 ms)
            visDebugThrottle[0] = (int) VisCheck.DEBUG_THROTTLE_MS;
            ImGui.setNextItemWidth(160f);
            if (ImGui.sliderInt("Log Throttle (ms)##viscThrottle", visDebugThrottle, 0, 5000)) {
                VisCheckAdapter.setDebugThrottleMs(visDebugThrottle[0]);
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("How often debug lines are printed.\n0 = print every single ray cast.");
            }

            // Loaded map info
            String map = VisCheckAdapter.getLoadedMap();
            ImGui.textDisabled("Map: " + (map.isEmpty() ? "(none)" : map));

            // Print stats button
            if (ImGui.button("Print Stats##viscStats")) {
                VisCheckAdapter.printStats();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Dumps cumulative ray-cast counters to console.");
            }

            ImGui.sameLine();

            // Reset stats button
            if (ImGui.button("Reset Stats##viscReset")) {
                VisCheck.resetStats();
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Clears all lifetime ray-cast counters.");
            }
        }

        ImGui.unindent();
        // ─────────────────────────────────────────────────────────────────────

        ImGui.end();

        // Window 2: Internal Menu (Danger)
        ImGui.setNextWindowPos(480f, 100f, ImGuiCond.FirstUseEver);
        ImGui.begin("Internal (Danger)",
                ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize);

        // Dynamically loop over all registered modules matching category INTERNAL
        for (CheatModule module : ModuleManager.getModules()) {
            if (module.getCategory() == ModuleCategory.INTERNAL) {
                renderModule(module);
            }
        }

        ImGui.end();
    }

    private static void renderModule(CheatModule module) {
        // Render enabled state checkbox for the module itself
        ImGui.checkbox("Enable " + module.getName(), module.getEnabledWrapper());

        // Dropdown expand/collapse button next to the checkbox if the module has settings
        if (!module.getSettings().isEmpty()) {
            ImGui.sameLine();
            String arrow = module.isSettingsExpanded() ? "v" : ">";
            if (ImGui.button(arrow + "##expand_" + module.getName())) {
                module.setSettingsExpanded(!module.isSettingsExpanded());
            }
            if (ImGui.isItemHovered()) {
                ImGui.setTooltip("Show/Hide Settings");
            }
        }

        // If module is enabled, expanded, and exposes registered settings, render them
        if (module.isEnabled() && module.isSettingsExpanded() && !module.getSettings().isEmpty()) {
            ImGui.indent();
            for (Setting<?> setting : module.getSettings()) {
                boolean disabled = false;
                if (module instanceof me.venixpll.cheat.module.impl.RadarHackModule) {
                    me.venixpll.cheat.module.impl.RadarHackModule radarMod = (me.venixpll.cheat.module.impl.RadarHackModule) module;
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
}
