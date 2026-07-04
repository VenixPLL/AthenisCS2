package me.venixpll.overlay;

import imgui.ImColor;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.setting.Setting;
import com.sun.jna.platform.win32.User32;
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
                if (ImGui.checkbox("Enable " + module.getName(), module.getEnabledWrapper())) {
                        // Checkbox was just toggled — fire a notification
                        NotificationManager.push(module.getName(), module.isEnabled());
                }

                ImGui.popStyleColor(4);

                // ── Keybind row ───────────────────────────────────────────────────────
                ImGui.spacing();
                renderBindRow(module, availW);

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

        // ── Bottom padding — keeps the last widget off the panel edge ─────────
                ImGui.dummy(0f, 16f);
        }

        // ─────────────────────────────────────────────────────────────────────────
        /**
         * Renders the keybind row for a module:
         * – a "Set Bind" button (turns red when listening) that starts capture mode,
         * – the current bind label (VK hex / key name), and
         * – a "Clear" button to remove the bind.
         *
         * <p>When listening is active, every Windows VK in range 1–254 is polled
         * via {@code GetAsyncKeyState} every GUI frame. The first key that reads
         * as pressed is captured and assigned; listening then stops automatically.
         * Escape cancels without changing the bind.
         *
         * @param module The module to configure.
         * @param availW Available width of the settings panel.
         */
        private static void renderBindRow(CheatModule module, float availW) {
                // ── Capture mode: scan all VKs for a press ────────────────────────────
                if (module.isListeningForBind()) {
                        // ESC cancels capture
                        if ((User32.INSTANCE.GetAsyncKeyState(0x1B) & 0x8000) != 0) {
                                module.setListeningForBind(false);
                        } else {
                                for (int vk = 1; vk <= 254; vk++) {
                                        // Skip keys that are not useful for binds or would
                                        // interfere: LMB(1), RMB(2), Shift(16), Ctrl(17),
                                        // Alt(18) are allowed but ESCAPE is handled above.
                                        if ((User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0) {
                                                module.setBindKey(vk);
                                                module.setListeningForBind(false);
                                                break;
                                        }
                                }
                        }
                }

                // ── Layout: [Set Bind]  <key label>  [Clear] ─────────────────────────
                // Compute widths and X so the row is centred in the panel.
                float btnW    = 80f;
                float clearW  = 50f;
                float spacing = 8f;
                float labelW  = 110f;
                float rowW    = btnW + spacing + labelW + spacing + clearW;
                float startX  = (availW - rowW) * 0.5f;
                if (startX < 8f) startX = 8f;

                // ── "Set Bind" button ─────────────────────────────────────────────────
                boolean listening = module.isListeningForBind();

                // Red while listening, accent cyan otherwise
                float[] btnR = listening
                        ? new float[]{ 0.80f, 0.16f, 0.16f }
                        : new float[]{ COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2] };
                ImGui.setCursorPosX(startX);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button,
                        btnR[0], btnR[1], btnR[2], 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered,
                        Math.min(btnR[0] * 1.15f, 1f),
                        Math.min(btnR[1] * 1.15f, 1f),
                        Math.min(btnR[2] * 1.15f, 1f), 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive,
                        btnR[0] * 0.80f, btnR[1] * 0.80f, btnR[2] * 0.80f, 1f);
                ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5f);
                String btnLabel = listening ? "Press key..." : "Set Bind";
                if (ImGui.button(btnLabel + "##bind_" + module.getName(), btnW, 0f)) {
                        // Cancel any other module's listen state first
                        for (CheatModule m : ModuleManager.getModules()) {
                                if (m != module) m.setListeningForBind(false);
                        }

                        module.setListeningForBind(!listening);
                }

                ImGui.popStyleVar();
                ImGui.popStyleColor(3);

                // ── Current bind label ────────────────────────────────────────────────
                ImGui.sameLine(0f, spacing);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                        COL_TEXT_DIM[0], COL_TEXT_DIM[1], COL_TEXT_DIM[2], 1f);
                int vk = module.getBindKey();
                String keyLabel = listening
                        ? "(waiting...)"
                        : (vk == -1 ? "None" : vkName(vk));

                // Right-pad the label to stable width so the Clear button doesn't shift
                ImGui.setNextItemWidth(labelW);
                float labelTextW = ImGui.calcTextSize(keyLabel).x;
                float labelOffX  = (labelW - labelTextW) * 0.5f;
                ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0f, labelOffX));
                ImGui.text(keyLabel);
                ImGui.popStyleColor();

                // ── "Clear" button ────────────────────────────────────────────────────
                // Align to a fixed position after the label
                ImGui.sameLine(startX + btnW + spacing + labelW + spacing, 0f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.22f, 0.22f, 0.22f, 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered, 0.30f, 0.30f, 0.30f, 1f);
                ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive, 0.16f, 0.16f, 0.16f, 1f);
                ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5f);
                if (ImGui.button("Clear##bindclear_" + module.getName(), clearW, 0f)) {
                        module.setBindKey(-1);
                        module.setListeningForBind(false);
                }

                ImGui.popStyleVar();
                ImGui.popStyleColor(3);
        }

        // ─────────────────────────────────────────────────────────────────────────
        /**
         * Returns a human-readable name for the given Windows Virtual-Key code.
         * Falls back to {@code "VK_0xNN"} for uncommon keys so the label is always
         * non-empty.
         *
         * @param vk Windows Virtual-Key code.
         * @return Human-readable key name string.
         */
        private static String vkName(int vk) {
                if (vk >= 0x41 && vk <= 0x5A) return String.valueOf((char) vk); // A-Z
                if (vk >= 0x30 && vk <= 0x39) return String.valueOf((char) vk); // 0-9
                if (vk >= 0x70 && vk <= 0x7B) return "F" + (vk - 0x6F);        // F1-F12
                if (vk >= 0x60 && vk <= 0x69) return "Num" + (vk - 0x60);      // Numpad 0-9
                return switch (vk) {
                        case 0x01 -> "LMB";
                        case 0x02 -> "RMB";
                        case 0x04 -> "MMB";
                        case 0x05 -> "X1";
                        case 0x06 -> "X2";
                        case 0x08 -> "Backspace";
                        case 0x09 -> "Tab";
                        case 0x0D -> "Enter";
                        case 0x10 -> "Shift";
                        case 0x11 -> "Ctrl";
                        case 0x12 -> "Alt";
                        case 0x14 -> "CapsLock";
                        case 0x1B -> "Escape";
                        case 0x20 -> "Space";
                        case 0x21 -> "PgUp";
                        case 0x22 -> "PgDn";
                        case 0x23 -> "End";
                        case 0x24 -> "Home";
                        case 0x25 -> "Left";
                        case 0x26 -> "Up";
                        case 0x27 -> "Right";
                        case 0x28 -> "Down";
                        case 0x2D -> "Insert";
                        case 0x2E -> "Delete";
                        case 0x6A -> "Num*";
                        case 0x6B -> "Num+";
                        case 0x6D -> "Num-";
                        case 0x6E -> "Num.";
                        case 0x6F -> "Num/";
                        case 0xA0 -> "LShift";
                        case 0xA1 -> "RShift";
                        case 0xA2 -> "LCtrl";
                        case 0xA3 -> "RCtrl";
                        case 0xA4 -> "LAlt";
                        case 0xA5 -> "RAlt";
                        case 0xBA -> ";";
                        case 0xBB -> "=";
                        case 0xBC -> ",";
                        case 0xBD -> "-";
                        case 0xBE -> ".";
                        case 0xBF -> "/";
                        case 0xC0 -> "`";
                        case 0xDB -> "[";
                        case 0xDC -> "\\";
                        case 0xDD -> "]";
                        case 0xDE -> "'";
                        default   -> String.format("VK_0x%02X", vk);
                };
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
