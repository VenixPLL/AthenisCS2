package me.venixpll.overlay;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import com.sun.jna.win32.W32APIOptions;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiConfigFlags;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.module.impl.ESPModule;
import me.venixpll.config.ConfigManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowPosCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;

import java.awt.event.KeyEvent;

/**
 * Overlay window container extending the ImGui Application lifecycle wrapper.
 * Configures the undecorated transparent overlay and maps window alignments to
 * the target game instance.
 */
public class OverlayWindow extends Application {
    private boolean lastInsertDown = false;

    /**
     * Tracks the last-known "down" state for each module's bind key so we can
     * detect rising edges (key-just-pressed) without requiring ImGui focus.
     * Keyed by module name for stable identity across render frames.
     */
    private final java.util.Map<String, Boolean> lastBindKeyDown = new java.util.HashMap<>();

    /**
     * Timestamp of the last CS2 window position/size query (milliseconds).
     * Used to throttle the Win32 FindWindow + GetWindowRect pair, which runs
     * on the render thread and does not need to fire every frame.
     */
    private long lastWindowCheckMs = 0L;

    /**
     * Interval between CS2 window geometry checks in milliseconds.
     * 250 ms (4 Hz) is more than enough to track window moves or resizes.
     */
    private static final long WINDOW_CHECK_INTERVAL_MS = 250L;

    /** Last known CS2 window geometry, cached between checks. */
    private int cachedGameX = 0, cachedGameY = 0, cachedGameW = 1920, cachedGameH = 1080;
    private boolean cachedGameFound = false;

    /**
     * Cached Win32 handle of the CS2 window. Reused between geometry checks and
     * re-validated with {@code IsWindow} — {@code FindWindow} is only invoked
     * again when the cached handle becomes invalid (game closed/restarted) or
     * was never resolved. This avoids a full window-enumeration every 250 ms
     * and is resilient to Valve changing the window title.
     */
    private HWND cachedGameHwnd = null;

    /**
     * Configurable Java keycode to toggle the menu, default is KeyEvent.VK_INSERT
     * (155)
     */
    public static int toggleKeyJava = KeyEvent.VK_SLASH; // 47 ('/')

    /**
     * Stream-proof mode: excludes the overlay window from screen capture
     * (OBS, Discord, screenshots) via SetWindowDisplayAffinity.
     * Toggled from SYSTEM -> Settings in the overlay menu; persisted in
     * settings.json and re-applied on startup.
     */
    public static volatile boolean streamProof = false;

    /**
     * Windows Virtual-Key code that fires the panic switch. {@code -1}
     * disables the panic key entirely. Default: VK_DELETE (0x2E).
     */
    public static int panicKeyVK = 0x2E;

    /**
     * Panic scope selector: {@code true} disables ALL enabled non-debug
     * modules; {@code false} only modules whose {@code isDangerous()} flag
     * is set (the default, matching the safety-first intent).
     */
    public static boolean panicDisableAllModules = true;

    /** Randomized GLFW window title (set in configure) used to locate our own HWND. */
    private static String overlayWindowTitle = null;

    /** Cached Win32 handle of the overlay window itself (revalidated with IsWindow). */
    private static HWND cachedOverlayHwnd = null;

    /** Last observed down-state of the panic key (rising-edge detection). */
    private boolean lastPanicKeyDown = false;

    /** Stream-proof bookkeeping so the affinity call only fires on state change. */
    private boolean streamProofAppliedState = false;
    private boolean streamProofInitDone = false;

    /**
     * Maps Java AWT KeyEvent codes to Windows Virtual Key (VK) codes.
     */
    public static int javaToWindowsKey(int javaKey) {
        if (javaKey >= KeyEvent.VK_A && javaKey <= KeyEvent.VK_Z) {
            return javaKey; // A-Z are same
        }
        if (javaKey >= KeyEvent.VK_0 && javaKey <= KeyEvent.VK_9) {
            return javaKey; // 0-9 are same
        }
        if (javaKey >= KeyEvent.VK_F1 && javaKey <= KeyEvent.VK_F12) {
            return javaKey; // F1-F12 are same
        }
        if (javaKey >= KeyEvent.VK_NUMPAD0 && javaKey <= KeyEvent.VK_NUMPAD9) {
            return javaKey - KeyEvent.VK_NUMPAD0 + 0x60; // Numpad 0-9
        }
        switch (javaKey) {
            case KeyEvent.VK_INSERT:
                return 0x2D;
            case KeyEvent.VK_DELETE:
                return 0x2E;
            case KeyEvent.VK_BACK_SPACE:
                return 0x08;
            case KeyEvent.VK_TAB:
                return 0x09;
            case KeyEvent.VK_ENTER:
                return 0x0D;
            case KeyEvent.VK_SHIFT:
                return 0x10;
            case KeyEvent.VK_CONTROL:
                return 0x11;
            case KeyEvent.VK_ALT:
                return 0x12;
            case KeyEvent.VK_PAUSE:
                return 0x13;
            case KeyEvent.VK_CAPS_LOCK:
                return 0x14;
            case KeyEvent.VK_ESCAPE:
                return 0x1B;
            case KeyEvent.VK_SPACE:
                return 0x20;
            case KeyEvent.VK_PAGE_UP:
                return 0x21;
            case KeyEvent.VK_PAGE_DOWN:
                return 0x22;
            case KeyEvent.VK_END:
                return 0x23;
            case KeyEvent.VK_HOME:
                return 0x24;
            case KeyEvent.VK_LEFT:
                return 0x25;
            case KeyEvent.VK_UP:
                return 0x26;
            case KeyEvent.VK_RIGHT:
                return 0x27;
            case KeyEvent.VK_DOWN:
                return 0x28;
            case KeyEvent.VK_COMMA:
                return 0xBC;
            case KeyEvent.VK_PERIOD:
                return 0xBE;
            case KeyEvent.VK_SLASH:
                return 0xBF;
            case KeyEvent.VK_SEMICOLON:
                return 0xBA;
            case KeyEvent.VK_EQUALS:
                return 0xBB;
            case KeyEvent.VK_OPEN_BRACKET:
                return 0xDB;
            case KeyEvent.VK_BACK_SLASH:
                return 0xDC;
            case KeyEvent.VK_CLOSE_BRACKET:
                return 0xDD;
            case KeyEvent.VK_MINUS:
                return 0xBD;
            case KeyEvent.VK_BACK_QUOTE:
                return 0xC0;
            default:
                return javaKey; // fallback
        }
    }

    /** Toggle state controlling whether the configuration menu panel is drawn. */
    public static boolean menuOpen = false;

    /**
     * When {@code true} the overlay will call
     * {@link GLFW#glfwSetWindowShouldClose} on the next {@link #process()} tick
     * and cleanly exit the render loop. Set via {@link #requestClose()}.
     */
    private static volatile boolean closeRequested = false;

    /**
     * Signals the overlay window to close gracefully on its next render tick.
     * Called by the launcher's STOP button so the engine can be halted without
     * requiring the user to manually close the transparent overlay window.
     */
    public static void requestClose() {
        closeRequested = true;
    }

    /**
     * Returns whether the configuration menu panel is currently visible and
     * the overlay is accepting mouse input (not in click-through mode).
     * <p>
     * Used by modules (e.g. {@code RadarHackModule}) to decide whether
     * interactive drag handles should be drawn and processed.
     *
     * @return {@code true} if the menu is open and the overlay accepts mouse
     *         events.
     */
    public static boolean isMenuOpen() {
        return menuOpen;
    }

    /**
     * Configures underlying GLFW window properties (transparency, floating status,
     * mouse click-through).
     *
     * @param config The application Configuration instance.
     */
    @Override
    protected void configure(final Configuration config) {
        // Generate a random string for the window title to evade detection
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        int length = 8 + rnd.nextInt(9); // random size between 8 and 16 chars
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        config.setTitle(sb.toString());
        overlayWindowTitle = sb.toString();

        // Native GLFW initialization check
        GLFW.glfwInit();

        // Query primary monitor resolution and size window to match, leaving a 1px
        // vertical gap
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor != 0) {
            GLFWVidMode vidmode = GLFW.glfwGetVideoMode(monitor);
            if (vidmode != null) {
                config.setWidth(vidmode.width());
                config.setHeight(vidmode.height() - 1);
            } else {
                config.setWidth(1920);
                config.setHeight(1079);
            }
        } else {
            config.setWidth(1920);
            config.setHeight(1079);
        }

        // Apply native flags for transparent, undecorated click-through overlay
        GLFW.glfwWindowHint(GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_TRANSPARENT_FRAMEBUFFER, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_FLOATING, GLFW.GLFW_TRUE);

        // Start as click-through overlay
        GLFW.glfwWindowHint(GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE);
    }

    /**
     * Initializes ImGui settings, styles, and flags.
     *
     * @param config The application Configuration instance.
     */
    @Override
    protected void initImGui(final Configuration config) {
        super.initImGui(config);

        // ── Base dark style ───────────────────────────────────────────────────
        ImGui.styleColorsDark();
        imgui.ImGuiStyle style = ImGui.getStyle();

        // Rounding
        style.setWindowRounding(10.0f);
        style.setChildRounding(8.0f);
        style.setFrameRounding(5.0f);
        style.setGrabRounding(4.0f);
        style.setPopupRounding(6.0f);
        style.setScrollbarRounding(4.0f);
        style.setTabRounding(4.0f);

        // Spacing / padding
        style.setWindowPadding(10.0f, 10.0f);
        style.setFramePadding(8.0f, 5.0f);
        style.setItemSpacing(6.0f, 5.0f);
        style.setScrollbarSize(10.0f);

        // ── Cyan-on-dark palette — matches the Launcher (Catppuccin Mocha-inspired) ──
        // Accent: #00B4D8 (cyan) → (0.000, 0.706, 0.847)
        final float aR = 0.000f, aG = 0.706f, aB = 0.847f;
        // C_BG:       #0A0B0E → (0.039, 0.043, 0.055)
        final float bg1R = 0.039f, bg1G = 0.043f, bg1B = 0.055f;
        // C_SURFACE2: #161B22 → (0.086, 0.106, 0.133)  — content / child panels
        final float bg2R = 0.086f, bg2G = 0.106f, bg2B = 0.133f;
        // C_SURFACE:  #111318 → (0.067, 0.075, 0.094)  — sidebar / topbar
        final float bg3R = 0.067f, bg3G = 0.075f, bg3B = 0.094f;
        // C_BORDER:   #21262D → (0.129, 0.149, 0.176)
        final float brR = 0.129f, brG = 0.149f, brB = 0.176f;

        // Window background (transparent — the per-window color is set by OverlayMenu)
        style.setColor(imgui.flag.ImGuiCol.WindowBg, bg1R, bg1G, bg1B, 0.97f);
        style.setColor(imgui.flag.ImGuiCol.ChildBg, bg2R, bg2G, bg2B, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.PopupBg, bg3R, bg3G, bg3B, 0.98f);

        // Borders — use the launcher's C_BORDER shade
        style.setColor(imgui.flag.ImGuiCol.Border, brR, brG, brB, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.BorderShadow, 0.00f, 0.00f, 0.00f, 0.00f);

        // Frame (checkboxes, sliders, inputs)
        style.setColor(imgui.flag.ImGuiCol.FrameBg, bg2R + 0.03f, bg2G + 0.03f, bg2B + 0.03f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.FrameBgHovered, bg2R + 0.06f, bg2G + 0.06f, bg2B + 0.06f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.FrameBgActive, aR * 0.30f, aG * 0.30f, aB * 0.30f, 1.00f);

        // Title bar (unused — menu has NoTitleBar, but keep for any sub-windows)
        style.setColor(imgui.flag.ImGuiCol.TitleBg, bg3R, bg3G, bg3B, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.TitleBgActive, bg3R, bg3G, bg3B, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.TitleBgCollapsed, bg3R, bg3G, bg3B, 0.75f);

        // Scrollbar
        style.setColor(imgui.flag.ImGuiCol.ScrollbarBg, bg3R, bg3G, bg3B, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.ScrollbarGrab, brR, brG, brB, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.ScrollbarGrabHovered, brR + 0.05f, brG + 0.05f, brB + 0.05f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.ScrollbarGrabActive, aR, aG, aB, 1.00f);

        // Checkmark & slider grab — cyan accent
        style.setColor(imgui.flag.ImGuiCol.CheckMark, aR, aG, aB, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.SliderGrab, aR, aG, aB, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.SliderGrabActive, Math.min(aR * 1.15f, 1f), Math.min(aG * 1.15f, 1f),
                Math.min(aB * 1.15f, 1f), 1.00f);

        // Buttons
        style.setColor(imgui.flag.ImGuiCol.Button, bg2R + 0.03f, bg2G + 0.03f, bg2B + 0.03f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.ButtonHovered, bg2R + 0.07f, bg2G + 0.07f, bg2B + 0.07f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.ButtonActive, aR, aG, aB, 1.00f);

        // Header (selectables)
        style.setColor(imgui.flag.ImGuiCol.Header, bg2R, bg2G, bg2B, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.HeaderHovered, bg2R + 0.05f, bg2G + 0.05f, bg2B + 0.05f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.HeaderActive, aR * 0.40f, aG * 0.40f, aB * 0.40f, 1.00f);

        // Separator
        style.setColor(imgui.flag.ImGuiCol.Separator, brR, brG, brB, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.SeparatorHovered, aR, aG, aB, 0.78f);
        style.setColor(imgui.flag.ImGuiCol.SeparatorActive, aR, aG, aB, 1.00f);

        // Tab bar
        style.setColor(imgui.flag.ImGuiCol.Tab, bg2R, bg2G, bg2B, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.TabHovered, bg2R + 0.05f, bg2G + 0.05f, bg2B + 0.05f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.TabActive, aR * 0.55f, aG * 0.55f, aB * 0.55f, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.TabUnfocused, bg3R, bg3G, bg3B, 1.00f);
        style.setColor(imgui.flag.ImGuiCol.TabUnfocusedActive, bg2R + 0.02f, bg2G + 0.02f, bg2B + 0.02f, 1.00f);

        // Text — launcher's C_TEXT / C_TEXT_DIM
        style.setColor(imgui.flag.ImGuiCol.Text, 0.804f, 0.839f, 0.957f, 1.00f);  // #CDD6F4
        style.setColor(imgui.flag.ImGuiCol.TextDisabled, 0.424f, 0.439f, 0.525f, 1.00f); // #6C7086

        // Transparency clear color — must stay fully transparent so the overlay is
        // see-through
        getColorBg().set(0.0f, 0.0f, 0.0f, 0.0f);

        // ── Load custom font (Segoe UI with Cyrillic range) ───────────────────
        final ImFontAtlas fontAtlas = ImGui.getIO().getFonts();
        final ImFontConfig fontConfig = new ImFontConfig();
        java.io.File fontFile = new java.io.File("C:/Windows/Fonts/segoeui.ttf");
        if (!fontFile.exists()) {
            fontFile = new java.io.File("C:/Windows/Fonts/arial.ttf");
        }
        if (fontFile.exists()) {
            fontAtlas.addFontFromFileTTF(fontFile.getAbsolutePath(), 15.0f, fontConfig,
                    fontAtlas.getGlyphRangesCyrillic());
        }
        fontConfig.destroy();

        // Enable ImGui ini file persistence in Athenis app data directory
        String appData = System.getenv("APPDATA");
        java.nio.file.Path dir = appData != null
                ? java.nio.file.Paths.get(appData, "Athenis")
                : java.nio.file.Paths.get(System.getProperty("user.home"), ".config", "Athenis");
        try {
            java.nio.file.Files.createDirectories(dir);
        } catch (Exception ignored) {
        }
        ImGui.getIO().setIniFilename(dir.resolve("imgui.ini").toAbsolutePath().toString());
        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);

        // Start process memory monitoring watchdog
        ProcessMemoryMonitor.getInstance().start();

        // Push startup notification
        NotificationManager.push("Athenis", true, "Started successfully");
    }

    /**
     * Primary rendering hook executed per frame inside the ImGui rendering thread
     * loop.
     */
    @Override
    public void process() {
        // If a close was requested (e.g. by the launcher STOP button), signal GLFW
        // to close the window on this tick so the render loop exits cleanly.
        if (closeRequested) {
            long handle = GLFW.glfwGetCurrentContext();
            if (handle != 0)
                GLFW.glfwSetWindowShouldClose(handle, true);
            closeRequested = false;
            return;
        }

        // ── Stream-proof mode: apply capture exclusion on state change ────────
        if (!streamProofInitDone || streamProof != streamProofAppliedState) {
            streamProofInitDone = true;
            streamProofAppliedState = streamProof;
            applyStreamProof();
        }

        // Handle alignment adjustments and key inputs
        updateWindowPosition();

        // Poll module toggle binds every frame (only when menu is NOT open to
        // avoid accidental triggers while the user is rebinding keys).
        if (!menuOpen) {
            pollModuleBindKeys();
        }

        // Render cheat modules
        for (CheatModule module : ModuleManager.getModules()) {
            if (module.isEnabled()) {
                module.onRender(ImGui.getForegroundDrawList());
            }
        }

        // Render GUI Menu
        if (menuOpen) {
            OverlayMenu.render();
            PerformanceMonitor.render();
            MemoryHexViewer.render();
        }

        // Draw Watermark in top-left corner
        drawWatermark();

        // Draw notifications in top-right corner
        NotificationManager.render(
                ImGui.getForegroundDrawList(),
                PlayerCache.screenWidth,
                PlayerCache.screenHeight);
    }

    /**
     * Polls the Windows async key state for every registered module's bind key
     * and toggles the module on a rising edge (key just pressed).
     *
     * <p>Uses {@code GetAsyncKeyState} — the same mechanism used for the INSERT
     * menu toggle — so it works even when the overlay window does not have
     * keyboard focus (i.e. while CS2 is the foreground window).
     */
    private void pollModuleBindKeys() {
        for (CheatModule module : ModuleManager.getModules()) {
            int vk = module.getBindKey();
            if (vk == -1) continue; // no bind set

            boolean down = (User32.INSTANCE.GetAsyncKeyState(vk) & 0x8000) != 0;
            boolean wasDown = lastBindKeyDown.getOrDefault(module.getName(), false);

            if (down && !wasDown) {
                // Rising edge → toggle module and notify
                boolean newState = !module.isEnabled();
                module.setEnabled(newState);
                NotificationManager.push(module.getName(), newState);
            }

            lastBindKeyDown.put(module.getName(), down);
        }
    }

    // ── Stream-proof mode (capture exclusion) ─────────────────────────────────

    /** WDA_NONE — normal display affinity (window is capturable). */
    private static final int WDA_NONE = 0x00000000;
    /** WDA_EXCLUDEFROMCAPTURE — window removed from screen capture (Win10 2004+). */
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x00000011;

    /** Minimal user32 surface for SetWindowDisplayAffinity (not in JNA's User32). */
    private interface User32Ex extends com.sun.jna.win32.StdCallLibrary {
        User32Ex INSTANCE = Native.load("user32", User32Ex.class,
                W32APIOptions.DEFAULT_OPTIONS);

        boolean SetWindowDisplayAffinity(HWND hWnd, int dwAffinity);
    }

    /**
     * Applies or removes capture exclusion on the overlay window according to
     * {@link #streamProof}. Runs on the render thread only. On systems older
     * than Windows 10 2004 the call fails and the flag is reverted with a
     * notification instead of silently doing nothing.
     */
    public static void applyStreamProof() {
        if (cachedOverlayHwnd == null || !User32.INSTANCE.IsWindow(cachedOverlayHwnd)) {
            cachedOverlayHwnd = (overlayWindowTitle != null)
                    ? User32.INSTANCE.FindWindow(null, overlayWindowTitle)
                    : null;
        }
        if (cachedOverlayHwnd == null) return;

        boolean want = streamProof;
        boolean ok = User32Ex.INSTANCE.SetWindowDisplayAffinity(cachedOverlayHwnd,
                want ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE);
        if (!ok && want) {
            streamProof = false;
            NotificationManager.push("Stream-proof", false, "Unsupported on this Windows version");
        } else {
            NotificationManager.push("Stream-proof", want);
        }
    }

    // ── Panic key ────────────────────────────────────────────────────────────

    /**
     * Instantly disables targeted modules, closes the menu and restores
     * click-through so the overlay becomes fully inert.
     *
     * <p>Scope: with {@link #panicDisableAllModules} set, every enabled
     * non-DEBUG module is switched off; otherwise only modules flagged
     * dangerous via {@code isDangerous()}.</p>
     */
    private void triggerPanic(long windowHandle) {
        int disabledCount = 0;
        for (CheatModule module : ModuleManager.getModules()) {
            if (module.getCategory() == ModuleCategory.DEBUG) continue;
            if (!module.isEnabled()) continue;
            if (panicDisableAllModules || module.isDangerous()) {
                module.setEnabled(false);
                disabledCount++;
            }
        }

        if (menuOpen) {
            menuOpen = false;
            GLFW.glfwSetWindowAttrib(windowHandle, GLFW.GLFW_MOUSE_PASSTHROUGH, GLFW.GLFW_TRUE);
        }

        NotificationManager.push("PANIC", false,
                disabledCount + " module" + (disabledCount == 1 ? "" : "s") + " disabled");
    }

    /** @return {@code true} when any registered module is waiting for a key bind. */
    private static boolean anyModuleBindListening() {
        for (CheatModule module : ModuleManager.getModules()) {
            if (module.isListeningForBind()) return true;
        }
        return false;
    }

    /**
     * Renders a styled watermark "Athenis v(version)" in the top-left corner
     * of the screen with a clean drop shadow and anti-aliased look.
     */
    private void drawWatermark() {
        imgui.ImDrawList drawList = ImGui.getForegroundDrawList();

        // Coordinates for the watermark: 15px margin from top-left
        float posX = 10.0f;
        float posY = 10.0f;

        String text = "Athenis v" + me.venixpll.Main.VERSION;

        // Draw drop shadow: offset by 1.5px on X and Y, semi-transparent black
        int shadowColor = ImGui.getColorU32(0.0f, 0.0f, 0.0f, 0.75f);
        drawList.addText(posX + 1.5f, posY + 1.5f, shadowColor, text);

        // Draw main text: accent cyan #00B4D8 matching the launcher and menu palette
        int mainColor = ImGui.getColorU32(0.0f, 0.706f, 0.847f, 1.0f);
        drawList.addText(posX, posY, mainColor, text);
    }

    /**
     * Shuts down external JNA hooks and closes process handle references.
     */
    @Override
    protected void postRun() {
        // Persist all module settings so the user's configuration survives restarts.
        // This fires both on manual GLFW window close and after STOP button triggers.
        ConfigManager.save();
        CS2Memory.close();
        System.out.println("[OverlayWindow] Context destroyed.");
    }

    /**
     * Syncs overlay location with CS2 window, updating click-through state and
     * positioning.
     */
    private void updateWindowPosition() {
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0)
            return;

        // Toggle configuration menu via configured key
        int winKey = javaToWindowsKey(toggleKeyJava);
        boolean insertDown = (User32.INSTANCE.GetAsyncKeyState(winKey) & 0x8000) != 0;
        if (insertDown && !lastInsertDown) {
            menuOpen = !menuOpen;

            // Change click-through attributes dynamically based on menu state
            GLFW.glfwSetWindowAttrib(
                    windowHandle,
                    GLFW.GLFW_MOUSE_PASSTHROUGH,
                    menuOpen ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE);

            if (menuOpen) {
                GLFW.glfwFocusWindow(windowHandle);
            }
        }
        lastInsertDown = insertDown;

        // ── Panic key: rising-edge detection, same mechanism as menu toggle ───
        // Skipped entirely while any bind-capture is listening so rebinding a
        // key can never fire the panic switch.
        if (!OverlayMenu.panicKeyListening && !anyModuleBindListening() && panicKeyVK != -1) {
            boolean panicDown = (User32.INSTANCE.GetAsyncKeyState(panicKeyVK) & 0x8000) != 0;
            if (panicDown && !lastPanicKeyDown) {
                triggerPanic(windowHandle);
            }
            lastPanicKeyDown = panicDown;
        } else {
            lastPanicKeyDown = false;
        }

        // ── Throttled CS2 window geometry query (every 250 ms) ────────────────
        // GetWindowRect involves a Win32 kernel call; running it at full render
        // rate (60-165+ fps) wastes CPU for data that changes far less often.
        // Cached geometry is used between checks. The window handle itself is
        // cached across checks and re-validated with IsWindow(), so the more
        // expensive FindWindow enumeration only runs when the handle is lost.
        long now = System.currentTimeMillis();
        if (now - lastWindowCheckMs >= WINDOW_CHECK_INTERVAL_MS) {
            lastWindowCheckMs = now;

            // Re-use the cached handle when still valid; re-resolve otherwise.
            if (cachedGameHwnd == null || !User32.INSTANCE.IsWindow(cachedGameHwnd)) {
                cachedGameHwnd = User32.INSTANCE.FindWindow("SDL_app", "Counter-Strike 2");
            }

            if (cachedGameHwnd != null) {
                RECT rect = new RECT();
                User32.INSTANCE.GetWindowRect(cachedGameHwnd, rect);
                cachedGameX = rect.left;
                cachedGameY = rect.top;
                cachedGameW = rect.right - rect.left;
                cachedGameH = rect.bottom - rect.top;
                cachedGameFound = true;
            } else {
                cachedGameFound = false;
            }
        }

        if (!cachedGameFound)
            return;

        {
            int x = cachedGameX;
            int y = cachedGameY;
            int w = cachedGameW;
            int h = cachedGameH;

            // Cache dimensions
            PlayerCache.screenWidth = Math.max(w, 100);
            PlayerCache.screenHeight = Math.max(h, 100);

            // Fetch current overlay geometry properties
            int[] ox = new int[1];
            int[] oy = new int[1];
            int[] ow = new int[1];
            int[] oh = new int[1];
            GLFW.glfwGetWindowPos(windowHandle, ox, oy);
            GLFW.glfwGetWindowSize(windowHandle, ow, oh);

            long monitor = GLFW.glfwGetPrimaryMonitor();
            int monitorWidth = 1920;
            int monitorHeight = 1080;
            if (monitor != 0) {
                GLFWVidMode vidmode = GLFW.glfwGetVideoMode(monitor);
                if (vidmode != null) {
                    monitorWidth = vidmode.width();
                    monitorHeight = vidmode.height();
                }
            }

            int targetX = x;
            int targetY = y;
            int targetWidth = w;
            int targetHeight = h;

            int newX = targetX;
            int newY = targetY;
            int newWidth = targetWidth;
            int newHeight = targetHeight;

            // Sprawdzenie, czy okno docelowe zajmuje dokładnie cały ekran
            if (targetWidth == monitorWidth && targetHeight == monitorHeight) {
                // Zwiększamy wymiary o 1 piksel i przesuwamy o 1 piksel,
                // co zapobiega traktowaniu okna przez system Windows jako "fullscreen"
                newWidth = targetWidth + 1;
                newHeight = targetHeight + 1;
                newX = targetX - 1;
                newY = targetY - 1;
            }

            ESPModule.espOffsetX = (float) (targetX - newX);
            ESPModule.espOffsetY = (float) (targetY - newY);

            // Relocate/Resize only when geometry differs, temporarily disabling
            // GLFW callbacks to prevent recursive assertion crashes.
            if (ox[0] != newX || oy[0] != newY) {
                GLFWWindowPosCallback oldPosCallback = GLFW.glfwSetWindowPosCallback(windowHandle, null);
                try {
                    GLFW.glfwSetWindowPos(windowHandle, newX, newY);
                } finally {
                    GLFW.glfwSetWindowPosCallback(windowHandle, oldPosCallback);
                }
            }
            if (ow[0] != newWidth || oh[0] != newHeight) {
                GLFWWindowSizeCallback oldSizeCallback = GLFW.glfwSetWindowSizeCallback(windowHandle, null);
                try {
                    GLFW.glfwSetWindowSize(windowHandle, newWidth, newHeight);
                } finally {
                    GLFW.glfwSetWindowSizeCallback(windowHandle, oldSizeCallback);
                }
            }
        } // end cached geometry block
    }
}
