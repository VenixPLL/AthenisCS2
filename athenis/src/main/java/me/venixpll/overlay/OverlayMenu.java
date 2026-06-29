package me.venixpll.overlay;

import imgui.ImColor;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.setting.Setting;
import me.venixpll.cheat.vischeck.VisCheck;
import me.venixpll.cheat.vischeck.VisCheckAdapter;

import java.util.List;

/**
 * Overlay menu — module list in the left sidebar, selected module's settings
 * rendered in the centre content panel.
 * <p>
 * Compatible with imgui-java 1.86.x (no ImGuiChildFlags, uses plain 0 / false).
 */
public class OverlayMenu {

        // ── Selection state ────────────────────────────────────────────────────────
        /** Index into ModuleManager.getModules() of the currently selected module. */
        private static int selectedModuleIdx = 0;

        // ── VisCheck debug state ───────────────────────────────────────────────────
        private static final ImBoolean visDebugEnabled = new ImBoolean(VisCheck.DEBUG);
        private static final int[] visDebugThrottle = { (int) VisCheck.DEBUG_THROTTLE_MS };

        // ── Window geometry ────────────────────────────────────────────────────────
        private static final float WINDOW_W = 700f;
        private static final float WINDOW_H = 480f;
        private static final float SIDEBAR_W = 160f;
        private static final float TOPBAR_H = 48f;

        // ── Colour palette — matches the Launcher (Catppuccin Mocha-inspired) ─────
        // C_BG        #0A0B0E
        private static final float[] COL_BG      = { 0.039f, 0.043f, 0.055f, 0.97f };
        // C_SURFACE   #111318  — sidebar / topbar
        private static final float[] COL_SIDEBAR = { 0.067f, 0.075f, 0.094f, 1.00f };
        private static final float[] COL_TOPBAR  = { 0.067f, 0.075f, 0.094f, 1.00f };
        // C_SURFACE2  #161B22  — content area
        private static final float[] COL_CONTENT = { 0.086f, 0.106f, 0.133f, 1.00f };
        // Accent      #00B4D8  — cyan (matches C_ACCENT in Launcher)
        private static final float[] COL_ACCENT  = { 0.000f, 0.706f, 0.847f, 1.00f };
        // Hover / active tabs — slightly above C_SURFACE2
        private static final float[] COL_TAB_HOVER  = { 0.110f, 0.135f, 0.165f, 1.00f };
        private static final float[] COL_TAB_ACTIVE = { 0.095f, 0.118f, 0.148f, 1.00f };
        // C_BORDER    #21262D
        private static final float[] COL_SEPARATOR = { 0.129f, 0.149f, 0.176f, 1.00f };
        // C_TEXT_DIM  #6C7086
        private static final float[] COL_TEXT_DIM  = { 0.424f, 0.439f, 0.525f, 1.00f };

        // ── Reusable child-window flags (1.86.x compatible) ───────────────────────
        private static final int NO_SCROLL_FLAGS = ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse;

        // ─────────────────────────────────────────────────────────────────────────

        /**
         * Main entry point — called every frame when the menu is open.
         */
        public static void render() {
                List<CheatModule> modules = ModuleManager.getModules();
                if (modules.isEmpty())
                        return;

                // Clamp selection in case modules change at runtime
                if (selectedModuleIdx >= modules.size())
                        selectedModuleIdx = 0;
                CheatModule selected = modules.get(selectedModuleIdx);

                // ── Outer window setup ────────────────────────────────────────────────
                ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.Always);
                ImGui.setNextWindowPos(
                                (ImGui.getIO().getDisplaySizeX() - WINDOW_W) * 0.5f,
                                (ImGui.getIO().getDisplaySizeY() - WINDOW_H) * 0.5f,
                                ImGuiCond.FirstUseEver);

                // Make WindowBg fully transparent — we paint the background ourselves
                // so we have full control over the rounded corners.
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.WindowBg, 0f, 0f, 0f, 0f);
                ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 10f);
                ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);

                ImGui.begin("##AthenisMenu",
                                ImGuiWindowFlags.NoTitleBar
                                                | ImGuiWindowFlags.NoResize
                                                | ImGuiWindowFlags.NoScrollbar
                                                | ImGuiWindowFlags.NoScrollWithMouse);

                ImVec2 winPos = ImGui.getWindowPos();

                // ── Full window background — manually drawn rounded rect ───────────────
                // Drawing this ourselves (instead of relying on WindowBg) lets us keep
                // perfect rounded corners without any child windows clipping them.
                ImGui.getWindowDrawList().addRectFilled(
                                winPos.x, winPos.y,
                                winPos.x + WINDOW_W, winPos.y + WINDOW_H,
                                ImColor.rgba(COL_BG[0], COL_BG[1], COL_BG[2], COL_BG[3]),
                                10f);

                // ── Sidebar background — left corners only ───────────────────────────
                // ImDrawFlags_RoundCornersLeft = RoundCornersTopLeft(16) |
                // RoundCornersBottomLeft(64) = 80
                // (imgui-java 1.86+ uses the new ImDrawFlags scheme; legacy 0-15 values are
                // rejected)
                ImGui.getWindowDrawList().addRectFilled(
                                winPos.x, winPos.y,
                                winPos.x + SIDEBAR_W, winPos.y + WINDOW_H,
                                ImColor.rgba(COL_SIDEBAR[0], COL_SIDEBAR[1], COL_SIDEBAR[2], 1f),
                                10f, 80); // 80 = ImDrawFlags_RoundCornersLeft

                // ── SIDEBAR ───────────────────────────────────────────────────────────
                ImGui.setCursorPos(0f, 0f);
                ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
                ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, 0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0f, 0f, 0f, 0f);

                ImGui.beginChild("##sidebar", SIDEBAR_W, WINDOW_H, false, NO_SCROLL_FLAGS);

                // ── "Athenis" logo block at the top ────────────────────────────────────
                // Background: transparent so the outer rounded rect shows through the
                // top-left corner — only the orange badge area is drawn manually below.
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0f, 0f, 0f, 0f);
                ImGui.beginChild("##logo", SIDEBAR_W, 56f, false, NO_SCROLL_FLAGS);

                // Draw the orange badge with left-only rounding to match sidebar shape
                // 80 = ImDrawFlags_RoundCornersLeft (TopLeft=16 | BotLeft=64)
                ImVec2 logoPos = ImGui.getWindowPos();
                ImGui.getWindowDrawList().addRectFilled(
                                logoPos.x, logoPos.y,
                                logoPos.x + SIDEBAR_W, logoPos.y + 56f,
                                ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f),
                                10f, 80); // left corners only

                // "Athenis" centred in the badge
                float logoTextW = ImGui.calcTextSize("Athenis").x;
                ImGui.setCursorPos((SIDEBAR_W - logoTextW) * 0.5f, 19f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1f, 1f, 1f, 1f);
                ImGui.text("Athenis");
                ImGui.popStyleColor(); // Text
                ImGui.endChild();
                ImGui.popStyleColor(); // ChildBg logo

                ImGui.setCursorPosY(ImGui.getCursorPosY() + 6f);

                // ── Module list ───────────────────────────────────────────────────────
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Header,
                                COL_TAB_ACTIVE[0], COL_TAB_ACTIVE[1], COL_TAB_ACTIVE[2], 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.HeaderHovered,
                                COL_TAB_HOVER[0], COL_TAB_HOVER[1], COL_TAB_HOVER[2], 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.HeaderActive,
                                COL_TAB_ACTIVE[0], COL_TAB_ACTIVE[1], COL_TAB_ACTIVE[2], 1f);

                for (int i = 0; i < modules.size(); i++) {
                        CheatModule mod = modules.get(i);
                        boolean isActive = (selectedModuleIdx == i);
                        boolean isEnabled = mod.isEnabled();

                        // Draw active highlight + orange left accent bar behind selectable
                        if (isActive) {
                                ImVec2 cp = ImGui.getCursorScreenPos();
                                ImGui.getWindowDrawList().addRectFilled(
                                                cp.x, cp.y, cp.x + 4f, cp.y + 34f,
                                                ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f), 2f);
                                ImGui.getWindowDrawList().addRectFilled(
                                                cp.x, cp.y, cp.x + SIDEBAR_W, cp.y + 34f,
                                                ImColor.rgba(COL_TAB_ACTIVE[0], COL_TAB_ACTIVE[1], COL_TAB_ACTIVE[2],
                                                                1f),
                                                0f);
                        }

                        ImGui.setCursorPosX(0f);
                        if (ImGui.selectable("##mod" + i, isActive, 0, SIDEBAR_W, 34f)) {
                                selectedModuleIdx = i;
                        }

                        // Overlay the module name text on top of the selectable
                        ImVec2 itemMin = ImGui.getItemRectMin();
                        float nameBrightness = isEnabled ? (isActive ? 1f : 0.80f) : 0.42f;
                        // Small enabled indicator dot
                        int dotColor = isEnabled
                                        ? ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f)
                                        : ImColor.rgba(0.35f, 0.35f, 0.35f, 1f);
                        ImGui.getWindowDrawList().addCircleFilled(
                                        itemMin.x + 16f, itemMin.y + 17f, 3.5f, dotColor, 8);
                        // Module name
                        ImGui.getWindowDrawList().addText(
                                        itemMin.x + 28f, itemMin.y + 9f,
                                        ImColor.rgba(nameBrightness, nameBrightness, nameBrightness, 1f),
                                        mod.getName());
                }

                ImGui.popStyleColor(3); // Header, HeaderHovered, HeaderActive

                // FPS counter pinned to bottom of sidebar
                ImGui.setCursorPos(8f, WINDOW_H - 28f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                                COL_TEXT_DIM[0], COL_TEXT_DIM[1], COL_TEXT_DIM[2], 1f);
                ImGui.text(String.format("FPS: %d", (int) ImGui.getIO().getFramerate()));
                ImGui.popStyleColor();

                ImGui.endChild(); // ##sidebar
                ImGui.popStyleColor(); // ChildBg
                ImGui.popStyleVar(2); // ItemSpacing, FramePadding

                // ── MAIN CONTENT AREA ─────────────────────────────────────────────────
                ImGui.setCursorPos(SIDEBAR_W, 0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0f, 0f, 0f, 0f);
                ImGui.beginChild("##mainarea", WINDOW_W - SIDEBAR_W, WINDOW_H,
                                false, NO_SCROLL_FLAGS);

                // ── TOP BAR ───────────────────────────────────────────────────────────
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg,
                                COL_TOPBAR[0], COL_TOPBAR[1], COL_TOPBAR[2], 1f);
                ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8f, 4f);
                ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 10f, 7f);

                ImGui.beginChild("##topbar", WINDOW_W - SIDEBAR_W, TOPBAR_H,
                                false, NO_SCROLL_FLAGS);
                ImGui.setCursorPos(12f, 10f);

                // "Global" orange button
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button,
                                COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered,
                                Math.min(COL_ACCENT[0] * 1.15f, 1f),
                                Math.min(COL_ACCENT[1] * 1.15f, 1f),
                                Math.min(COL_ACCENT[2] * 1.15f, 1f), 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive,
                                COL_ACCENT[0] * 0.85f, COL_ACCENT[1] * 0.85f, COL_ACCENT[2] * 0.85f, 1f);
                ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5f);
                ImGui.button("Global");
                ImGui.popStyleVar(); // FrameRounding
                ImGui.popStyleColor(3);

                // Active module name breadcrumb
                ImGui.sameLine(0f, 10f);
                ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.75f, 0.75f, 0.75f, 1f);
                ImGui.text(selected.getName());
                ImGui.popStyleColor();

                ImGui.endChild(); // ##topbar
                ImGui.popStyleColor(); // ChildBg topbar
                ImGui.popStyleVar(2); // ItemSpacing, FramePadding

                // Thin separator under the top bar
                ImVec2 sepPos = ImGui.getCursorScreenPos();
                ImGui.getWindowDrawList().addLine(
                                sepPos.x, sepPos.y,
                                sepPos.x + WINDOW_W - SIDEBAR_W, sepPos.y,
                                ImColor.rgba(COL_SEPARATOR[0], COL_SEPARATOR[1], COL_SEPARATOR[2], 1f), 1f);
                ImGui.setCursorPosY(ImGui.getCursorPosY() + 1f);

                // ── SETTINGS PANEL (centre) ────────────────────────────────────────────
                float panelW = WINDOW_W - SIDEBAR_W - 2f;
                float panelH = WINDOW_H - TOPBAR_H - 2f;

                ImGui.setCursorPosX(1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg,
                                COL_CONTENT[0], COL_CONTENT[1], COL_CONTENT[2], 1f);
                ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 0f);
                ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8f, 7f);
                ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8f, 5f);

                // scrollable so settings don't clip if many
                ImGui.beginChild("##settings", panelW, panelH, false, 0);

                renderModuleSettings(selected);

                ImGui.endChild(); // ##settings
                ImGui.popStyleVar(3);
                ImGui.popStyleColor(); // ChildBg settings

                ImGui.endChild(); // ##mainarea
                ImGui.popStyleColor(); // ChildBg mainarea

                ImGui.end();

                // Pop outer window style
                ImGui.popStyleVar(2); // WindowRounding, WindowPadding
                ImGui.popStyleColor(); // WindowBg
        }

        // ─────────────────────────────────────────────────────────────────────────

        /**
         * Renders the enable checkbox and all settings for the given module,
         * centred horizontally with generous vertical spacing.
         */
        private static void renderModuleSettings(CheatModule module) {
                float availW = ImGui.getContentRegionAvailX();

                // ── Vertical breathing room at the top ────────────────────────────────
                ImGui.spacing();
                ImGui.spacing();

                // ── Module title ──────────────────────────────────────────────────────
                float titleW = ImGui.calcTextSize(module.getName()).x;
                ImGui.setCursorPosX((availW - titleW) * 0.5f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.92f, 0.92f, 0.92f, 1f);
                ImGui.text(module.getName());
                ImGui.popStyleColor();

                ImGui.spacing();

                // Thin orange underline below the title
                ImVec2 ul = ImGui.getCursorScreenPos();
                float lineHalf = Math.min(100f, availW * 0.35f);
                float cx = ul.x + availW * 0.5f;
                ImGui.getWindowDrawList().addLine(
                                cx - lineHalf, ul.y,
                                cx + lineHalf, ul.y,
                                ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 0.7f), 1.5f);
                ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);

                ImGui.spacing();

                // ── Enable / Disable checkbox centred ─────────────────────────────────
                float checkLabelW = ImGui.calcTextSize("Enable " + module.getName()).x + 24f;
                float checkX = (availW - checkLabelW) * 0.5f;
                if (checkX < 8f)
                        checkX = 8f;
                ImGui.setCursorPosX(checkX);

                ImGui.pushStyleColor(imgui.flag.ImGuiCol.CheckMark,
                                COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBg, 0.18f, 0.18f, 0.18f, 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgHovered, 0.23f, 0.23f, 0.23f, 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgActive,
                                COL_ACCENT[0] * 0.45f, COL_ACCENT[1] * 0.45f, COL_ACCENT[2] * 0.45f, 1f);
                ImGui.checkbox("Enable " + module.getName(), module.getEnabledWrapper());
                ImGui.popStyleColor(4);

                // ── Settings list ─────────────────────────────────────────────────────
                if (module.getSettings().isEmpty()) {
                        ImGui.spacing();
                        ImGui.spacing();
                        float noSetW = ImGui.calcTextSize("No settings available.").x;
                        ImGui.setCursorPosX((availW - noSetW) * 0.5f);
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                                        COL_TEXT_DIM[0], COL_TEXT_DIM[1], COL_TEXT_DIM[2], 1f);
                        ImGui.text("No settings available.");
                        ImGui.popStyleColor();
                        ImGui.dummy(0f, 16f); // bottom padding
                        return;
                }

                ImGui.spacing();
                ImGui.spacing();

                // Settings width = 60% of panel, centred
                float settingW = availW * 0.60f;
                if (settingW < 160f)
                        settingW = 160f;
                float settingX = (availW - settingW) * 0.5f;
                if (settingX < 8f)
                        settingX = 8f;

                for (Setting<?> setting : module.getSettings()) {
                        boolean disabled = shouldDisable(module, setting);
                        if (disabled)
                                ImGui.beginDisabled(true);

                        // Centre each setting widget
                        ImGui.setCursorPosX(settingX);
                        ImGui.setNextItemWidth(settingW);

                        // Orange slider grabs
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.CheckMark,
                                        COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f);
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.SliderGrab,
                                        COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f);
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.SliderGrabActive,
                                        Math.min(COL_ACCENT[0] * 1.15f, 1f),
                                        Math.min(COL_ACCENT[1] * 1.15f, 1f),
                                        Math.min(COL_ACCENT[2] * 1.15f, 1f), 1f);
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBg, 0.18f, 0.18f, 0.18f, 1f);
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgHovered, 0.23f, 0.23f, 0.23f, 1f);
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgActive,
                                        COL_ACCENT[0] * 0.4f, COL_ACCENT[1] * 0.4f, COL_ACCENT[2] * 0.4f, 1f);

                        setting.renderImGui();

                        ImGui.popStyleColor(6);

                        if (disabled)
                                ImGui.endDisabled();

                        ImGui.spacing();
                }

                // ── VisCheck debug block (inline at bottom) ───────────────────────────
                boolean isVisCheckRelevant = module.getClass().getSimpleName().contains("ESP");
                if (isVisCheckRelevant) {
                        ImGui.spacing();
                        ImGui.setCursorPosX(settingX);

                        // Thin separator
                        ImVec2 sp = ImGui.getCursorScreenPos();
                        ImGui.getWindowDrawList().addLine(
                                        sp.x, sp.y, sp.x + settingW, sp.y,
                                        ImColor.rgba(COL_SEPARATOR[0], COL_SEPARATOR[1], COL_SEPARATOR[2], 1f), 1f);
                        ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);

                        ImGui.setCursorPosX(settingX);
                        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                                        COL_TEXT_DIM[0], COL_TEXT_DIM[1], COL_TEXT_DIM[2], 1f);
                        ImGui.text("VisCheck Debug");
                        ImGui.popStyleColor();
                        ImGui.spacing();

                        ImGui.setCursorPosX(settingX);
                        ImGui.setNextItemWidth(settingW);
                        visDebugEnabled.set(VisCheck.DEBUG);
                        if (ImGui.checkbox("Enable Ray Debug##viscDebug", visDebugEnabled)) {
                                VisCheckAdapter.setDebug(visDebugEnabled.get());
                        }

                        if (VisCheck.DEBUG) {
                                ImGui.setCursorPosX(settingX);
                                ImGui.setNextItemWidth(settingW);
                                visDebugThrottle[0] = (int) VisCheck.DEBUG_THROTTLE_MS;
                                if (ImGui.sliderInt("Log Throttle (ms)##viscThrottle", visDebugThrottle, 0, 5000)) {
                                        VisCheckAdapter.setDebugThrottleMs(visDebugThrottle[0]);
                                }
                                if (ImGui.isItemHovered()) {
                                        ImGui.setTooltip("0 = print every ray cast.");
                                }

                                ImGui.setCursorPosX(settingX);
                                String map = VisCheckAdapter.getLoadedMap();
                                ImGui.textDisabled("Map: " + (map.isEmpty() ? "(none)" : map));

                                ImGui.setCursorPosX(settingX);
                                if (ImGui.button("Print Stats##viscStats"))
                                        VisCheckAdapter.printStats();
                                ImGui.sameLine();
                                if (ImGui.button("Reset Stats##viscReset"))
                                        VisCheck.resetStats();
                        }
                }

                // ── Bottom padding — keeps the last widget off the panel edge ─────────
                ImGui.dummy(0f, 16f);
        }

        /**
         * Returns true when a RadarHack setting should be greyed out because the
         * auto-map mode is overriding manual positioning.
         */
        private static boolean shouldDisable(CheatModule module, Setting<?> setting) {
                if (!(module instanceof me.venixpll.cheat.module.impl.RadarHackModule))
                        return false;
                me.venixpll.cheat.module.impl.RadarHackModule r = (me.venixpll.cheat.module.impl.RadarHackModule) module;
                if (!r.squareRadar.getValue() || !r.autoMapRadar.getValue())
                        return false;
                String n = setting.getName();
                return n.equals("Map Center X") || n.equals("Map Center Y") || n.equals("Radar Scale (Zoom)");
        }
}
