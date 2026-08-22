package me.venixpll.overlay;

import imgui.ImColor;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.setting.Setting;
import me.venixpll.cheat.module.impl.ESPModule;
import com.sun.jna.platform.win32.User32;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Overlay menu — redesigned to match the Athenis menu-design.png spec.
 * <p>
 * Sidebar: "Athenis" logo at top, then category groups (VISUALS, COMBAT,
 * SYSTEM) with icons per item. Selecting a module opens its settings in the
 * right panel. SYSTEM → Settings opens a built-in config page.
 * <p>
 * Architecture is future-proof: to add a new module, assign it a
 * {@link MenuGroup} in its constructor. To add a new category, add a value
 * to {@link MenuGroup}.
 * <p>
 * Compatible with imgui-java 1.86.x.
 */
public class OverlayMenu {

    // ── Window geometry ────────────────────────────────────────────────────────
    private static final float WINDOW_W = 820f;
    private static final float WINDOW_H = 520f;
    private static final float SIDEBAR_W = 190f;
    private static final float TOPBAR_H = 34f;

    // ── Colour palette — exact match with LauncherWindow (Catppuccin Mocha) ───
    // C_BG #0A0B0E
    private static final float[] COL_BG = { 0.039f, 0.043f, 0.055f, 0.97f };
    // C_SURFACE #111318 — sidebar / topbar
    private static final float[] COL_SIDEBAR = { 0.067f, 0.075f, 0.094f, 1.00f };
    // C_SURFACE2 #161B22 — content area
    private static final float[] COL_CONTENT = { 0.086f, 0.106f, 0.133f, 1.00f };
    // Top-bar: same as sidebar surface
    private static final float[] COL_TOPBAR = { 0.067f, 0.075f, 0.094f, 1.00f };

    // C_ACCENT #00B4D8 — cyan (matches launcher exactly)
    private static final float[] COL_ACCENT = { 0.000f, 0.706f, 0.847f, 1.00f };
    // Logo background: slightly darker cyan shade for the header badge
    private static final float[] COL_LOGO_BG = { 0.000f, 0.580f, 0.700f, 1.00f };

    // Active sidebar item background (slightly above C_SURFACE2)
    private static final float[] COL_ITEM_ACT = { 0.095f, 0.118f, 0.148f, 1.00f };
    // Hover sidebar item background
    private static final float[] COL_ITEM_HOV = { 0.110f, 0.135f, 0.165f, 1.00f };

    // C_BORDER #21262D
    private static final float[] COL_BORDER = { 0.129f, 0.149f, 0.176f, 1.00f };

    // C_TEXT_DIM #6C7086
    private static final float[] COL_DIM = { 0.424f, 0.439f, 0.525f, 1.00f };

    // Debug glow green (unchanged)
    private static final float[] COL_DBG_GREEN = { 0.18f, 0.80f, 0.44f, 1.00f };

    // ── Selection state ────────────────────────────────────────────────────────
    /**
     * Index into ModuleManager.getModules() for the selected module, or
     * {@code SYSTEM_SETTINGS_IDX} when the Settings page is active.
     */
    private static int selectedModuleIdx = 0;
    /** Sentinel value: SYSTEM → Settings page is selected. */
    private static final int SYSTEM_SETTINGS_IDX = -1;

    // ── Icons ──────────────────────────────────────────────────────────────────
    /** OpenGL texture IDs, keyed by icon filename (e.g. "eye-48.png"). */
    private static final java.util.HashMap<String, Integer> iconCache = new java.util.HashMap<>();
    private static boolean iconsLoaded = false;

    // ── Misc constants ────────────────────────────────────────────────────────
    private static final int NO_SCROLL = ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse;

    // ─────────────────────────────────────────────────────────────────────────
    /** Main entry point — called every frame when the menu is open. */
    public static void render() {
        if (!iconsLoaded) {
            loadIcons();
            iconsLoaded = true;
        }

        List<CheatModule> modules = ModuleManager.getModules();

        // Clamp module selection
        if (selectedModuleIdx >= modules.size()) {
            selectedModuleIdx = modules.isEmpty() ? SYSTEM_SETTINGS_IDX : 0;
        }

        // ── Outer window ──────────────────────────────────────────────────────
        ImGui.setNextWindowSize(WINDOW_W, WINDOW_H, ImGuiCond.Always);
        ImGui.setNextWindowPos(
                (ImGui.getIO().getDisplaySizeX() - WINDOW_W) * 0.5f,
                (ImGui.getIO().getDisplaySizeY() - WINDOW_H) * 0.5f,
                ImGuiCond.FirstUseEver);

        ImGui.pushStyleColor(imgui.flag.ImGuiCol.WindowBg, 0f, 0f, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 10f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.begin("##AthenisMenu",
                ImGuiWindowFlags.NoTitleBar
                        | ImGuiWindowFlags.NoResize
                        | ImGuiWindowFlags.NoScrollbar
                        | ImGuiWindowFlags.NoScrollWithMouse);
        ImVec2 winPos = ImGui.getWindowPos();

        // ── Full window background ─────────────────────────────────────────────
        ImGui.getWindowDrawList().addRectFilled(
                winPos.x, winPos.y,
                winPos.x + WINDOW_W, winPos.y + WINDOW_H,
                ImColor.rgba(COL_BG[0], COL_BG[1], COL_BG[2], COL_BG[3]),
                10f);

        // ── Sidebar background (left corners only, flag 80) ────────────────────
        ImGui.getWindowDrawList().addRectFilled(
                winPos.x, winPos.y,
                winPos.x + SIDEBAR_W, winPos.y + WINDOW_H,
                ImColor.rgba(COL_SIDEBAR[0], COL_SIDEBAR[1], COL_SIDEBAR[2], 1f),
                10f, 80);

        // ── Thin vertical separator right edge of sidebar ──────────────────────
        ImGui.getWindowDrawList().addLine(
                winPos.x + SIDEBAR_W, winPos.y + TOPBAR_H,
                winPos.x + SIDEBAR_W, winPos.y + WINDOW_H - 1f,
                ImColor.rgba(COL_BORDER[0], COL_BORDER[1], COL_BORDER[2], 0.8f), 1f);

        // ── SIDEBAR ───────────────────────────────────────────────────────────
        ImGui.setCursorPos(0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 0f, 0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0f, 0f, 0f, 0f);
        ImGui.beginChild("##sidebar", SIDEBAR_W, WINDOW_H, false, NO_SCROLL);

        renderSidebarLogo();

        ImGui.setCursorPos(0f, TOPBAR_H);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##sidebar_content", SIDEBAR_W, WINDOW_H - TOPBAR_H, false, 0);

        // Render each non-empty MenuGroup in declaration order
        for (MenuGroup group : MenuGroup.values()) {
            if (group == MenuGroup.SYSTEM)
                continue; // handled separately at bottom
            renderSidebarGroup(modules, group);
        }

        // Push SYSTEM section at the bottom — only "Settings"
        renderSidebarSystemGroup();

        ImGui.endChild(); // ##sidebar_content
        ImGui.popStyleVar(); // WindowPadding

        ImGui.endChild(); // ##sidebar
        ImGui.popStyleColor(); // ChildBg
        ImGui.popStyleVar(2); // ItemSpacing, FramePadding

        // ── MAIN CONTENT AREA ─────────────────────────────────────────────────
        ImGui.setCursorPos(SIDEBAR_W, 0f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0f, 0f, 0f, 0f);
        // Zero ItemSpacing + WindowPadding so no gap appears between topbar and
        // settings children
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##mainarea", WINDOW_W - SIDEBAR_W, WINDOW_H, false, NO_SCROLL);

        // Selected module (null if SYSTEM Settings)
        CheatModule selected = (selectedModuleIdx >= 0 && !modules.isEmpty())
                ? modules.get(selectedModuleIdx)
                : null;

        renderTopBar(selected);

        // ── Settings panel ───────────────────────────────────────────────────
        float panelW = WINDOW_W - SIDEBAR_W;
        float panelH = WINDOW_H - TOPBAR_H;
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg,
                COL_CONTENT[0], COL_CONTENT[1], COL_CONTENT[2], 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ItemSpacing, 8f, 7f);
        ImGui.pushStyleVar(ImGuiStyleVar.FramePadding, 8f, 5f);
        ImGui.beginChild("##settings", panelW, panelH, false, 0);

        if (selected != null) {
            renderModuleSettings(selected);
        } else {
            renderSystemSettingsPage();
        }

        ImGui.endChild();
        ImGui.popStyleVar(3);
        ImGui.popStyleColor(); // ChildBg settings
        ImGui.endChild(); // ##mainarea
        ImGui.popStyleVar(2); // ItemSpacing + WindowPadding mainarea
        ImGui.popStyleColor(); // ChildBg mainarea
        ImGui.end();

        ImGui.popStyleVar(2); // WindowRounding, WindowPadding
        ImGui.popStyleColor(); // WindowBg
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SIDEBAR RENDERING
    // ─────────────────────────────────────────────────────────────────────────

    /** Renders the "Athenis" logo badge at the top of the sidebar. */
    private static void renderSidebarLogo() {
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0f, 0f, 0f, 0f);
        ImGui.beginChild("##logo", SIDEBAR_W, TOPBAR_H, false, NO_SCROLL);

        ImVec2 logoPos = ImGui.getWindowPos();
        // Cyan logo background (left-corners rounded) — same height as TOPBAR_H
        ImGui.getWindowDrawList().addRectFilled(
                logoPos.x, logoPos.y,
                logoPos.x + SIDEBAR_W, logoPos.y + TOPBAR_H,
                ImColor.rgba(COL_LOGO_BG[0], COL_LOGO_BG[1], COL_LOGO_BG[2], 1f),
                10f, 80);

        // "Athenis" text — vertically centred same as PerformanceMonitor title
        float textW = ImGui.calcTextSize("Athenis").x;
        ImGui.setCursorPos((SIDEBAR_W - textW) * 0.5f, 9f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 1f, 1f, 1f, 0.95f);
        ImGui.text("Athenis");
        ImGui.popStyleColor();

        ImGui.endChild();
        ImGui.popStyleColor();
    }

    /**
     * Renders one category group in the sidebar (header label + module items).
     * Groups with no visible modules are silently skipped.
     */
    private static void renderSidebarGroup(List<CheatModule> modules, MenuGroup group) {
        // Count visible items
        int count = 0;
        for (CheatModule mod : modules) {
            if (mod.getMenuGroup() == group)
                count++;
        }
        if (count == 0)
            return;

        // Category header label (e.g. "VISUALS")
        renderCategoryHeader(group.label);

        // Items
        for (int i = 0; i < modules.size(); i++) {
            CheatModule mod = modules.get(i);
            if (mod.getMenuGroup() != group)
                continue;
            renderSidebarItem(modules, i, group);
        }

        ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);
    }

    /** Renders the SYSTEM group — currently only a "Settings" item. */
    private static void renderSidebarSystemGroup() {
        // Push SYSTEM section towards the bottom of the sidebar scrollable area
        float targetY = (WINDOW_H - TOPBAR_H) - 64f;
        if (ImGui.getCursorPosY() < targetY) {
            ImGui.setCursorPosY(targetY);
        } else {
            ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);
        }
        renderCategoryHeader("SYSTEM");
        renderSidebarSystemItem();
        ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);
    }

    /** Small uppercase grey category header. */
    private static void renderCategoryHeader(String label) {
        ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);
        ImGui.setCursorPosX(14f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                COL_DIM[0], COL_DIM[1], COL_DIM[2], 0.85f);
        ImGui.text(label);
        ImGui.popStyleColor();
        ImGui.setCursorPosY(ImGui.getCursorPosY() + 2f);
    }

    private static void renderSidebarItem(List<CheatModule> modules, int idx, MenuGroup group) {
        CheatModule mod = modules.get(idx);
        boolean isActive    = (selectedModuleIdx == idx);
        boolean isEnabled   = mod.isEnabled();
        boolean isDebug     = (mod.getCategory() == ModuleCategory.DEBUG);
        boolean isDangerous = mod.isDangerous();

        float itemH = 34f;

        // Active / hover background
        if (isActive) {
            ImVec2 cp = ImGui.getCursorScreenPos();
            // Left accent bar -- red for dangerous modules, cyan otherwise
            float[] barCol = isDangerous
                    ? new float[]{ 0.90f, 0.20f, 0.20f }
                    : new float[]{ COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2] };
            ImGui.getWindowDrawList().addRectFilled(
                    cp.x, cp.y, cp.x + 3f, cp.y + itemH,
                    ImColor.rgba(barCol[0], barCol[1], barCol[2], 1f), 2f);
            // Row fill
            ImGui.getWindowDrawList().addRectFilled(
                    cp.x + 3f, cp.y, cp.x + SIDEBAR_W, cp.y + itemH,
                    ImColor.rgba(COL_ITEM_ACT[0], COL_ITEM_ACT[1], COL_ITEM_ACT[2], 1f), 0f);
        }

        ImGui.setCursorPosX(0f);
        if (ImGui.selectable("##mod" + idx, isActive, 0, SIDEBAR_W, itemH)) {
            selectedModuleIdx = idx;
        }
        // Hover tint (if not active)
        if (!isActive && ImGui.isItemHovered()) {
            ImVec2 cp = ImGui.getItemRectMin();
            ImGui.getWindowDrawList().addRectFilled(
                    cp.x, cp.y, cp.x + SIDEBAR_W, cp.y + itemH,
                    ImColor.rgba(COL_ITEM_HOV[0], COL_ITEM_HOV[1], COL_ITEM_HOV[2], 0.7f), 0f);
        }

        ImVec2 itemMin = ImGui.getItemRectMin();

        // Icon -- tinted red for dangerous, cyan otherwise
        int iconTexId = getIconId(group.iconName);
        if (iconTexId > 0) {
            float iconY     = itemMin.y + (itemH - 16f) * 0.5f;
            float iconX     = itemMin.x + 12f;
            float iconAlpha = isActive ? 0.92f : 0.42f;
            float[] iconTint = isDangerous
                    ? new float[]{ 0.95f, 0.25f, 0.25f }
                    : new float[]{ COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2] };
            ImGui.getWindowDrawList().addImage(iconTexId,
                    iconX, iconY, iconX + 16f, iconY + 16f,
                    0f, 0f, 1f, 1f,
                    ImColor.rgba(iconTint[0], iconTint[1], iconTint[2], iconAlpha));
        }

        // Status dot
        float dotX = itemMin.x + SIDEBAR_W - 18f;
        float dotY = itemMin.y + itemH * 0.5f;
        if (isDebug && isEnabled) {
            int g1 = ImColor.rgba(COL_DBG_GREEN[0], COL_DBG_GREEN[1], COL_DBG_GREEN[2], 0.15f);
            int g2 = ImColor.rgba(COL_DBG_GREEN[0], COL_DBG_GREEN[1], COL_DBG_GREEN[2], 0.40f);
            int g3 = ImColor.rgba(COL_DBG_GREEN[0], COL_DBG_GREEN[1], COL_DBG_GREEN[2], 1.00f);
            ImGui.getWindowDrawList().addCircleFilled(dotX, dotY, 6f, g1, 12);
            ImGui.getWindowDrawList().addCircleFilled(dotX, dotY, 4f, g2, 12);
            ImGui.getWindowDrawList().addCircleFilled(dotX, dotY, 2.5f, g3, 8);
        } else if (isDangerous && isEnabled) {
            // Pulsing red dot for dangerous+enabled
            ImGui.getWindowDrawList().addCircleFilled(
                    dotX, dotY, 4.5f,
                    ImColor.rgba(0.90f, 0.15f, 0.15f, 0.25f), 10);
            ImGui.getWindowDrawList().addCircleFilled(
                    dotX, dotY, 3f,
                    ImColor.rgba(0.95f, 0.20f, 0.20f, 1.00f), 8);
        } else {
            int dotCol = isEnabled
                    ? (isDangerous
                            ? ImColor.rgba(0.90f, 0.20f, 0.20f, 1f)
                            : ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f))
                    : ImColor.rgba(0.30f, 0.33f, 0.40f, 1f);
            ImGui.getWindowDrawList().addCircleFilled(
                    dotX, dotY, 3f, dotCol, 8);
        }

        // Module name -- red when dangerous, white/grey otherwise
        int textCol;
        if (isDangerous) {
            float r = isEnabled ? (isActive ? 1.00f : 0.85f) : 0.55f;
            textCol = ImColor.rgba(r, isActive ? 0.20f : 0.15f, isActive ? 0.20f : 0.15f, 1f);
        } else if (isDebug && isEnabled) {
            textCol = ImColor.rgba(0.65f, 0.95f, 0.70f, 1f);
        } else {
            float nameBright = isEnabled ? (isActive ? 1f : 0.78f) : 0.38f;
            textCol = ImColor.rgba(nameBright, nameBright, nameBright, 1f);
        }
        ImGui.getWindowDrawList().addText(
                itemMin.x + 34f, itemMin.y + (itemH - ImGui.getTextLineHeight()) * 0.5f,
                textCol, mod.getName());
    }

    /** Renders the SYSTEM → Settings item (not backed by a CheatModule). */
    private static void renderSidebarSystemItem() {
        boolean isActive = (selectedModuleIdx == SYSTEM_SETTINGS_IDX);
        float itemH = 34f;

        if (isActive) {
            ImVec2 cp = ImGui.getCursorScreenPos();
            ImGui.getWindowDrawList().addRectFilled(
                    cp.x, cp.y, cp.x + 3f, cp.y + itemH,
                    ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f), 2f);
            ImGui.getWindowDrawList().addRectFilled(
                    cp.x + 3f, cp.y, cp.x + SIDEBAR_W, cp.y + itemH,
                    ImColor.rgba(COL_ITEM_ACT[0], COL_ITEM_ACT[1], COL_ITEM_ACT[2], 1f), 0f);
        }

        ImGui.setCursorPosX(0f);
        if (ImGui.selectable("##sysSettings", isActive, 0, SIDEBAR_W, itemH)) {
            selectedModuleIdx = SYSTEM_SETTINGS_IDX;
        }
        if (!isActive && ImGui.isItemHovered()) {
            ImVec2 cp = ImGui.getItemRectMin();
            ImGui.getWindowDrawList().addRectFilled(
                    cp.x, cp.y, cp.x + SIDEBAR_W, cp.y + itemH,
                    ImColor.rgba(COL_ITEM_HOV[0], COL_ITEM_HOV[1], COL_ITEM_HOV[2], 0.7f), 0f);
        }

        ImVec2 itemMin = ImGui.getItemRectMin();

        // Settings icon — PNG texture tinted cyan
        int iconTexId = getIconId(MenuGroup.SYSTEM.iconName);
        if (iconTexId > 0) {
            float iconY = itemMin.y + (itemH - 16f) * 0.5f;
            float iconX = itemMin.x + 12f;
            float iconAlpha = isActive ? 0.92f : 0.42f;
            ImGui.getWindowDrawList().addImage(iconTexId,
                    iconX, iconY, iconX + 16f, iconY + 16f,
                    0f, 0f, 1f, 1f,
                    ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], iconAlpha));
        }

        float nameBright = isActive ? 1f : 0.65f;
        ImGui.getWindowDrawList().addText(
                itemMin.x + 34f, itemMin.y + (itemH - ImGui.getTextLineHeight()) * 0.5f,
                ImColor.rgba(nameBright, nameBright, nameBright, 1f),
                "Settings");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOP BAR
    // ─────────────────────────────────────────────────────────────────────────

    /** Renders the top bar with module name breadcrumb. */
    private static void renderTopBar(CheatModule selected) {
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg,
                COL_TOPBAR[0], COL_TOPBAR[1], COL_TOPBAR[2], 1f);
        ImGui.beginChild("##topbar", WINDOW_W - SIDEBAR_W, TOPBAR_H, false, NO_SCROLL);

        // Module name — vertically at y+9 same as PerformanceMonitor title
        ImGui.setCursorPos(18f, 9f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.92f, 0.92f, 0.95f, 1f);
        String mainTitle = (selected != null) ? selected.getName().toUpperCase() : "SETTINGS";
        ImGui.text(mainTitle);
        ImGui.popStyleColor();

        // Breadcrumb: " / category"
        ImGui.sameLine(0f, 8f);
        String crumb = "/  ";
        if (selected != null) {
            crumb += selected.getMenuGroup().label.toLowerCase();
        } else {
            crumb += "system";
        }
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                COL_DIM[0], COL_DIM[1], COL_DIM[2], 1f);
        ImGui.setCursorPosY(9f);
        ImGui.text(crumb);
        ImGui.popStyleColor();

        ImGui.endChild();
        ImGui.popStyleColor();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // SYSTEM SETTINGS PAGE
    // ─────────────────────────────────────────────────────────────────────────

    /** Built-in Settings page (SYSTEM → Settings). */
    private static void renderSystemSettingsPage() {
        float availW = ImGui.getContentRegionAvailX();

        ImGui.spacing();
        ImGui.spacing();

        // Title
        float titleW = ImGui.calcTextSize("Settings").x;
        ImGui.setCursorPosX((availW - titleW) * 0.5f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.92f, 0.92f, 0.92f, 1f);
        ImGui.text("Settings");
        ImGui.popStyleColor();

        // Underline
        ImGui.spacing();
        ImVec2 ul = ImGui.getCursorScreenPos();
        float half = Math.min(80f, availW * 0.3f);
        float cx = ul.x + availW * 0.5f;
        ImGui.getWindowDrawList().addLine(
                cx - half, ul.y, cx + half, ul.y,
                ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 0.7f), 1.5f);
        ImGui.setCursorPosY(ImGui.getCursorPosY() + 6f);
        ImGui.spacing();

        // ── Menu Toggle Key row ───────────────────────────────────────────────
        float settingX = availW * 0.12f;
        ImGui.setCursorPosX(settingX);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                COL_DIM[0], COL_DIM[1], COL_DIM[2], 1f);
        ImGui.text("Menu Toggle Key");
        ImGui.popStyleColor();
        ImGui.setCursorPosX(settingX);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.80f, 0.82f, 0.90f, 1f);
        ImGui.text(vkName(me.venixpll.overlay.OverlayWindow.javaToWindowsKey(
                me.venixpll.overlay.OverlayWindow.toggleKeyJava)));
        ImGui.popStyleColor();
        ImGui.spacing();

        // ── About row ──────────────────────────────────────────────────────────
        ImGui.setCursorPosX(settingX);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                COL_DIM[0], COL_DIM[1], COL_DIM[2], 1f);
        ImGui.text("Version");
        ImGui.popStyleColor();
        ImGui.setCursorPosX(settingX);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.80f, 0.82f, 0.90f, 1f);
        ImGui.text("Athenis v" + me.venixpll.Main.VERSION);
        ImGui.popStyleColor();

        ImGui.dummy(0f, 16f);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MODULE SETTINGS — preserved from previous implementation with no changes
    // to renderModuleSettings / renderESPModuleSettings / renderSingleSetting /
    // renderBindRow / shouldDisable / vkName
    // ─────────────────────────────────────────────────────────────────────────

    private static void renderModuleSettings(CheatModule module) {
        float availW = ImGui.getContentRegionAvailX();
        boolean isDebug = (module.getCategory() == ModuleCategory.DEBUG);

        ImGui.spacing();
        ImGui.spacing();

        // Module title
        float titleW = ImGui.calcTextSize(module.getName()).x;
        ImGui.setCursorPosX((availW - titleW) * 0.5f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.92f, 0.92f, 0.92f, 1f);
        ImGui.text(module.getName());
        ImGui.popStyleColor();
        ImGui.spacing();

        // Underline -- red for dangerous modules
        ImVec2 ul = ImGui.getCursorScreenPos();
        float lineHalf = Math.min(100f, availW * 0.35f);
        float cx = ul.x + availW * 0.5f;
        boolean isDangerous = module.isDangerous();
        float[] underlineCol = isDebug ? COL_DBG_GREEN : (isDangerous ? new float[]{ 0.90f, 0.20f, 0.20f, 1f } : COL_ACCENT);
        ImGui.getWindowDrawList().addLine(
                cx - lineHalf, ul.y, cx + lineHalf, ul.y,
                ImColor.rgba(underlineCol[0], underlineCol[1], underlineCol[2], 0.7f), 1.5f);
        ImGui.setCursorPosY(ImGui.getCursorPosY() + 4f);
        ImGui.spacing();

        // VAC warning banner for dangerous modules
        if (isDangerous) {
            ImGui.spacing();
            float bannerW  = availW * 0.72f;
            ImVec2 bannerPos = ImGui.getCursorScreenPos();
            float bannerH  = 34f;
            float bannerOX = bannerPos.x + (availW * 0.5f) - (bannerW * 0.5f);
            // Dark red background
            ImGui.getWindowDrawList().addRectFilled(
                    bannerOX, bannerPos.y,
                    bannerOX + bannerW, bannerPos.y + bannerH,
                    ImColor.rgba(0.45f, 0.06f, 0.06f, 0.82f), 6f);
            // Left red accent stripe
            ImGui.getWindowDrawList().addRectFilled(
                    bannerOX, bannerPos.y,
                    bannerOX + 3f, bannerPos.y + bannerH,
                    ImColor.rgba(0.95f, 0.18f, 0.18f, 1.00f), 3f);
            // Warning text centred
            String warn = "\u26A0  VAC DETECTED  --  USE AT OWN RISK";
            float warnW = ImGui.calcTextSize(warn).x;
            ImGui.getWindowDrawList().addText(
                    bannerOX + (bannerW - warnW) * 0.5f,
                    bannerPos.y + (bannerH - ImGui.getTextLineHeight()) * 0.5f,
                    ImColor.rgba(1.00f, 0.35f, 0.35f, 1.00f),
                    warn);
            ImGui.dummy(0f, bannerH + 4f);
            ImGui.spacing();
        }

        // Enable checkbox
        float checkLabelW = ImGui.calcTextSize("Enable " + module.getName()).x + 24f;
        float checkX = (availW - checkLabelW) * 0.5f;
        if (checkX < 8f)
            checkX = 8f;
        ImGui.setCursorPosX(checkX);

        float[] cbAccent = isDebug ? COL_DBG_GREEN : COL_ACCENT;
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.CheckMark, cbAccent[0], cbAccent[1], cbAccent[2], 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBg, 0.18f, 0.18f, 0.18f, 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgHovered, 0.23f, 0.23f, 0.23f, 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgActive,
                cbAccent[0] * 0.45f, cbAccent[1] * 0.45f, cbAccent[2] * 0.45f, 1f);
        if (ImGui.checkbox("Enable " + module.getName(), module.getEnabledWrapper())) {
            NotificationManager.push(module.getName(), module.isEnabled());
        }
        ImGui.popStyleColor(4);

        ImGui.spacing();
        renderBindRow(module, availW);

        if (module.getSettings().isEmpty()) {
            ImGui.spacing();
            ImGui.spacing();
            float noSetW = ImGui.calcTextSize("No settings available.").x;
            ImGui.setCursorPosX((availW - noSetW) * 0.5f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                    COL_DIM[0], COL_DIM[1], COL_DIM[2], 1f);
            ImGui.text("No settings available.");
            ImGui.popStyleColor();
            ImGui.dummy(0f, 16f);
            return;
        }

        ImGui.spacing();
        ImGui.spacing();

        float settingW = availW * 0.48f;
        if (settingW < 160f)
            settingW = 160f;
        float settingX = availW * 0.16f;
        if (settingX < 8f)
            settingX = 8f;

        if (module instanceof ESPModule) {
            renderESPModuleSettings((ESPModule) module, settingX, settingW, isDebug);
        } else {
            for (Setting<?> setting : module.getSettings()) {
                if (setting.isHidden())
                    continue;
                renderSingleSetting(module, setting, settingX, settingW, isDebug);
            }
        }

        ImGui.dummy(0f, 16f);
    }

    private static void renderSingleSetting(CheatModule module, Setting<?> setting,
            float settingX, float settingW, boolean isDebug) {
        boolean disabled = shouldDisable(module, setting);
        if (disabled)
            ImGui.beginDisabled(true);

        ImGui.setCursorPosX(settingX);
        ImGui.setNextItemWidth(settingW);

        float[] sAccent = isDebug ? COL_DBG_GREEN : COL_ACCENT;
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.CheckMark, sAccent[0], sAccent[1], sAccent[2], 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.SliderGrab, sAccent[0], sAccent[1], sAccent[2], 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.SliderGrabActive,
                Math.min(sAccent[0] * 1.15f, 1f), Math.min(sAccent[1] * 1.15f, 1f),
                Math.min(sAccent[2] * 1.15f, 1f), 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBg, 0.18f, 0.18f, 0.18f, 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgHovered, 0.23f, 0.23f, 0.23f, 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.FrameBgActive,
                sAccent[0] * 0.4f, sAccent[1] * 0.4f, sAccent[2] * 0.4f, 1f);
        setting.renderImGui();
        ImGui.popStyleColor(6);

        if (disabled)
            ImGui.endDisabled();
        ImGui.spacing();
    }

    private static void renderESPModuleSettings(ESPModule esp, float settingX, float settingW, boolean isDebug) {
        ImGui.setCursorPosX(settingX);
        if (ImGui.beginTabBar("##ESPModeTabs")) {
            if (ImGui.beginTabItem("Player ESP")) {
                ImGui.spacing();
                ImGui.spacing();
                renderSingleSetting(esp, esp.playerEsp, settingX, settingW, isDebug);
                ImGui.spacing();
                renderSingleSetting(esp, esp.boxEsp, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.skeletonEsp, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.invisibleBonesOnly, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.healthEsp, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.nameEsp, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.teamCheck, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.extrapolationBias, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.enemyColor, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.teamColor, settingX, settingW, isDebug);
                ImGui.spacing();
                float[] hAccent = isDebug ? COL_DBG_GREEN : COL_ACCENT;
                ImGui.setCursorPosX(settingX);
                ImGui.textColored(hAccent[0], hAccent[1], hAccent[2], 0.85f, "Player Flags:");
                ImGui.spacing();
                renderSingleSetting(esp, esp.flagsEsp, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.flagBlind, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.flagScoped, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.flagDefusing, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.flagKit, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.flagMoney, settingX, settingW, isDebug);
                ImGui.spacing();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Grenade ESP")) {
                ImGui.spacing();
                ImGui.spacing();
                renderSingleSetting(esp, esp.grenadeEsp, settingX, settingW, isDebug);
                ImGui.spacing();
                renderSingleSetting(esp, esp.showHE, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.showFlash, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.showSmoke, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.showMolotov, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.showDecoy, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.maxDistance, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.minScale, settingX, settingW, isDebug);
                ImGui.spacing();
                ImGui.endTabItem();
            }
            if (ImGui.beginTabItem("Damage ESP")) {
                ImGui.spacing();
                ImGui.spacing();
                renderSingleSetting(esp, esp.damageEsp, settingX, settingW, isDebug);
                ImGui.spacing();
                renderSingleSetting(esp, esp.showDamage, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.showFloating, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.showTeammates, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.damageColor, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.shotsColor, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.textScale, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.crosshairRadius, settingX, settingW, isDebug);
                ImGui.spacing();
                renderSingleSetting(esp, esp.showHitmarker, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.hitmarkerColor, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.killMarkerColor, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.hitmarkerSize, settingX, settingW, isDebug);
                ImGui.spacing();
                renderSingleSetting(esp, esp.showKillfeed, settingX, settingW, isDebug);
                renderSingleSetting(esp, esp.killfeedDuration, settingX, settingW, isDebug);
                ImGui.spacing();
                ImGui.endTabItem();
            }
            ImGui.endTabBar();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // KEYBIND ROW — unchanged from previous implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Renders the keybind row for a module:
     * – a "Set Bind" button (turns red when listening) that starts capture mode,
     * – the current bind label (VK hex / key name), and
     * – a "Clear" button to remove the bind.
     *
     * <p>
     * When listening is active, every Windows VK in range 1–254 is polled
     * via {@code GetAsyncKeyState} every GUI frame. The first key that reads
     * as pressed is captured and assigned; listening then stops automatically.
     * Escape cancels without changing the bind.
     *
     * @param module The module to configure.
     * @param availW Available width of the settings panel.
     */
    private static void renderBindRow(CheatModule module, float availW) {
        // Capture mode: scan all VKs for a press
        if (module.isListeningForBind()) {
            if ((User32.INSTANCE.GetAsyncKeyState(0x1B) & 0x8000) != 0) {
                module.setListeningForBind(false);
            } else {
                for (int vk = 1; vk <= 254; vk++) {
                    if ((User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0) {
                        module.setBindKey(vk);
                        module.setListeningForBind(false);
                        break;
                    }
                }
            }
        }

        float btnW = 80f;
        float clearW = 50f;
        float spacing = 8f;
        float labelW = 110f;
        float rowW = btnW + spacing + labelW + spacing + clearW;
        float startX = (availW - rowW) * 0.5f;
        if (startX < 8f)
            startX = 8f;

        boolean listening = module.isListeningForBind();
        boolean isDebug = (module.getCategory() == ModuleCategory.DEBUG);
        float[] baseColor = isDebug ? COL_DBG_GREEN : COL_ACCENT;
        float[] btnR = listening ? new float[] { 0.80f, 0.16f, 0.16f } : baseColor;

        ImGui.setCursorPosX(startX);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button,
                btnR[0], btnR[1], btnR[2], 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonHovered,
                Math.min(btnR[0] * 1.15f, 1f), Math.min(btnR[1] * 1.15f, 1f),
                Math.min(btnR[2] * 1.15f, 1f), 1f);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ButtonActive,
                btnR[0] * 0.80f, btnR[1] * 0.80f, btnR[2] * 0.80f, 1f);
        ImGui.pushStyleVar(ImGuiStyleVar.FrameRounding, 5f);
        String btnLabel = listening ? "Press key..." : "Set Bind";
        if (ImGui.button(btnLabel + "##bind_" + module.getName(), btnW, 0f)) {
            for (CheatModule m : ModuleManager.getModules()) {
                if (m != module)
                    m.setListeningForBind(false);
            }
            module.setListeningForBind(!listening);
        }
        ImGui.popStyleVar();
        ImGui.popStyleColor(3);

        ImGui.sameLine(0f, spacing);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text,
                COL_DIM[0], COL_DIM[1], COL_DIM[2], 1f);
        int vk = module.getBindKey();
        String keyLabel = listening ? "(waiting...)" : (vk == -1 ? "None" : vkName(vk));
        ImGui.setNextItemWidth(labelW);
        float labelTextW = ImGui.calcTextSize(keyLabel).x;
        float labelOffX = (labelW - labelTextW) * 0.5f;
        ImGui.setCursorPosX(ImGui.getCursorPosX() + Math.max(0f, labelOffX));
        ImGui.text(keyLabel);
        ImGui.popStyleColor();

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
    // HELPERS — unchanged from previous implementation
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns true when a setting should be greyed out based on its parent
     * module's state (e.g. ESP sub-settings disabled when master ESP is off).
     */
    private static boolean shouldDisable(CheatModule module, Setting<?> setting) {
        if (module instanceof me.venixpll.cheat.module.impl.ESPModule) {
            me.venixpll.cheat.module.impl.ESPModule esp = (me.venixpll.cheat.module.impl.ESPModule) module;
            String name = setting.getName();

            if (name.equals("Show Player ESP") || name.equals("Show Grenade ESP") || name.equals("Show Damage ESP"))
                return false;

            if (name.equals("Render Box") || name.equals("Render Skeleton")
                    || name.equals("Show Health Indicators") || name.equals("Show Player Names")
                    || name.equals("Enemy-Only Team Filter") || name.equals("Show Player Flags")
                    || name.startsWith("Flag:") || name.equals("Extrapolation (ms)")
                    || name.equals("Enemy Color") || name.equals("Team Color"))
                return !esp.playerEsp.getValue();

            if (name.equals("Show HE Grenade") || name.equals("Show Flashbang")
                    || name.equals("Show Smoke") || name.equals("Show Molotov")
                    || name.equals("Show Decoy") || name.equals("Max Distance (units)")
                    || name.equals("Min Scale"))
                return !esp.grenadeEsp.getValue();

            if (name.equals("Show Damage Card") || name.equals("Show Floating Numbers")
                    || name.equals("Show Teammates Damage") || name.startsWith("Damage Color")
                    || name.startsWith("Shots Color") || name.startsWith("Text Scale")
                    || name.startsWith("Crosshair Radius")
                    || name.equals("Show Hit Marker") || name.startsWith("Hit Marker Color")
                    || name.startsWith("Kill Marker Color") || name.startsWith("Hit Marker Size")
                    || name.equals("Show Kill Feed") || name.startsWith("Kill Feed Duration"))
                return !esp.damageEsp.getValue();
        }

        if (!(module instanceof me.venixpll.cheat.module.impl.RadarHackModule))
            return false;
        me.venixpll.cheat.module.impl.RadarHackModule r = (me.venixpll.cheat.module.impl.RadarHackModule) module;
        if (!r.squareRadar.getValue() || !r.autoMapRadar.getValue())
            return false;
        String n = setting.getName();
        return n.equals("Map Center X") || n.equals("Map Center Y") || n.equals("Radar Scale (Zoom)");
    }

    /**
     * Returns a human-readable name for the given Windows Virtual-Key code.
     * Falls back to {@code "VK_0xNN"} for uncommon keys.
     *
     * @param vk Windows Virtual-Key code.
     * @return Human-readable key name string.
     */
    private static String vkName(int vk) {
        if (vk >= 0x41 && vk <= 0x5A)
            return String.valueOf((char) vk);
        if (vk >= 0x30 && vk <= 0x39)
            return String.valueOf((char) vk);
        if (vk >= 0x70 && vk <= 0x7B)
            return "F" + (vk - 0x6F);
        if (vk >= 0x60 && vk <= 0x69)
            return "Num" + (vk - 0x60);
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
            case 0xDD -> "]";
            case 0xDE -> "'";
            default -> String.format("VK_0x%02X", vk);
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ICON LOADING
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Loads all icon PNGs from the classpath ({@code assets/icons/}) into
     * OpenGL textures via imgui-java / LWJGL GL11.
     */
    private static void loadIcons() {
        String[] iconFiles = {
                "eye-48.png", // VISUALS
                "lightning-50.png", // COMBAT
                "globe-50.png", // OTHER
                "settings-48.png", // SYSTEM
                "keyboard-48.png", // spare
        };

        for (String name : iconFiles) {
            int texId = loadTextureFromClasspath("assets/icons/" + name);
            if (texId > 0) {
                iconCache.put(name, texId);
            }
        }
    }

    /**
     * Loads a PNG from the classpath and uploads it as an OpenGL 2D texture,
     * converting it to a white-mask (RGB=255) with alpha mask so ImGui addImage
     * tinting works smoothly.
     */
    private static int loadTextureFromClasspath(String resourcePath) {
        try {
            InputStream is = OverlayMenu.class.getClassLoader().getResourceAsStream(resourcePath);
            if (is == null)
                return 0;

            BufferedImage img = ImageIO.read(is);
            is.close();
            if (img == null)
                return 0;

            int w = img.getWidth();
            int h = img.getHeight();

            int[] pixels = new int[w * h];
            img.getRGB(0, 0, w, h, pixels, 0, w);

            // Determine if non-transparent pixels in icon are bright or dark
            boolean isWhiteOnTransparent = false;
            for (int p : pixels) {
                int a = (p >> 24) & 0xFF;
                int r = (p >> 16) & 0xFF;
                if (a > 100 && r > 200) {
                    isWhiteOnTransparent = true;
                    break;
                }
            }

            ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4);
            for (int pixel : pixels) {
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = (pixel) & 0xFF;
                int a = (pixel >> 24) & 0xFF;

                int alphaMask;
                if (isWhiteOnTransparent) {
                    alphaMask = a;
                } else {
                    int lum = (int) (0.299f * r + 0.587f * g + 0.114f * b);
                    alphaMask = (a * (255 - lum)) / 255;
                }

                buf.put((byte) 0xFF); // R = white
                buf.put((byte) 0xFF); // G = white
                buf.put((byte) 0xFF); // B = white
                buf.put((byte) alphaMask); // A = mask
            }
            buf.flip();

            int texId = org.lwjgl.opengl.GL11.glGenTextures();
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texId);
            org.lwjgl.opengl.GL11.glTexParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexParameteri(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D,
                    org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER,
                    org.lwjgl.opengl.GL11.GL_LINEAR);
            org.lwjgl.opengl.GL11.glTexImage2D(
                    org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA,
                    w, h, 0,
                    org.lwjgl.opengl.GL11.GL_RGBA,
                    org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE,
                    buf);
            return texId;

        } catch (Exception e) {
            System.err.println("[OverlayMenu] Failed to load icon: " + resourcePath + " — " + e.getMessage());
            return 0;
        }
    }

    /** Returns the cached OpenGL texture ID for the given icon filename, or 0. */
    private static int getIconId(String iconName) {
        return iconCache.getOrDefault(iconName, 0);
    }
}
