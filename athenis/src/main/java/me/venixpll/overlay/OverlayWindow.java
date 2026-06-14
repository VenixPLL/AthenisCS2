package me.venixpll.overlay;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.RECT;
import imgui.ImGui;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiConfigFlags;
import java.awt.event.KeyEvent;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.module.impl.ESPModule;
import me.venixpll.config.ConfigManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWWindowPosCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallback;

/**
 * Overlay window container extending the ImGui Application lifecycle wrapper.
 * Configures the undecorated transparent overlay and maps window alignments to the target game instance.
 */
public class OverlayWindow extends Application {
    private boolean lastInsertDown = false;

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

    /** Configurable Java keycode to toggle the menu, default is KeyEvent.VK_INSERT (155) */
    public static int toggleKeyJava = KeyEvent.VK_INSERT;

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
            case KeyEvent.VK_INSERT: return 0x2D;
            case KeyEvent.VK_DELETE: return 0x2E;
            case KeyEvent.VK_BACK_SPACE: return 0x08;
            case KeyEvent.VK_TAB: return 0x09;
            case KeyEvent.VK_ENTER: return 0x0D;
            case KeyEvent.VK_SHIFT: return 0x10;
            case KeyEvent.VK_CONTROL: return 0x11;
            case KeyEvent.VK_ALT: return 0x12;
            case KeyEvent.VK_PAUSE: return 0x13;
            case KeyEvent.VK_CAPS_LOCK: return 0x14;
            case KeyEvent.VK_ESCAPE: return 0x1B;
            case KeyEvent.VK_SPACE: return 0x20;
            case KeyEvent.VK_PAGE_UP: return 0x21;
            case KeyEvent.VK_PAGE_DOWN: return 0x22;
            case KeyEvent.VK_END: return 0x23;
            case KeyEvent.VK_HOME: return 0x24;
            case KeyEvent.VK_LEFT: return 0x25;
            case KeyEvent.VK_UP: return 0x26;
            case KeyEvent.VK_RIGHT: return 0x27;
            case KeyEvent.VK_DOWN: return 0x28;
            case KeyEvent.VK_COMMA: return 0xBC;
            case KeyEvent.VK_PERIOD: return 0xBE;
            case KeyEvent.VK_SLASH: return 0xBF;
            case KeyEvent.VK_SEMICOLON: return 0xBA;
            case KeyEvent.VK_EQUALS: return 0xBB;
            case KeyEvent.VK_OPEN_BRACKET: return 0xDB;
            case KeyEvent.VK_BACK_SLASH: return 0xDC;
            case KeyEvent.VK_CLOSE_BRACKET: return 0xDD;
            case KeyEvent.VK_MINUS: return 0xBD;
            case KeyEvent.VK_BACK_QUOTE: return 0xC0;
            default: return javaKey; // fallback
        }
    }

    /** Toggle state controlling whether the configuration menu panel is drawn. */
    public static boolean menuOpen = false;

    /**
     * When {@code true} the overlay will call
     * {@link GLFW#glfwSetWindowShouldClose} on the next {@link #process()} tick
     * and cleanly exit the render loop.  Set via {@link #requestClose()}.
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
     * @return {@code true} if the menu is open and the overlay accepts mouse events.
     */
    public static boolean isMenuOpen() {
        return menuOpen;
    }

    /**
     * Configures underlying GLFW window properties (transparency, floating status, mouse click-through).
     *
     * @param config The application Configuration instance.
     */
    @Override
    protected void configure(final Configuration config) {
        config.setTitle("Overlay");

        // Native GLFW initialization check
        GLFW.glfwInit();

        // Query primary monitor resolution and size window to match, leaving a 1px vertical gap
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

        // Styling configuration
        ImGui.styleColorsDark();
        imgui.ImGuiStyle style = ImGui.getStyle();
        style.setWindowRounding(8.0f);
        style.setFrameRounding(4.0f);
        style.setGrabRounding(4.0f);

        // Transparency clear color setting
        getColorBg().set(0.0f, 0.0f, 0.0f, 0.0f);

        // Disable saving configuration files to current workspace
        ImGui.getIO().setIniFilename(null);
        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
    }

    /**
     * Primary rendering hook executed per frame inside the ImGui rendering thread loop.
     */
    @Override
    public void process() {
        // If a close was requested (e.g. by the launcher STOP button), signal GLFW
        // to close the window on this tick so the render loop exits cleanly.
        if (closeRequested) {
            long handle = GLFW.glfwGetCurrentContext();
            if (handle != 0) GLFW.glfwSetWindowShouldClose(handle, true);
            closeRequested = false;
            return;
        }

        // Handle alignment adjustments and key inputs
        updateWindowPosition();

        // Render cheat modules
        for (CheatModule module : ModuleManager.getModules()) {
            if (module.isEnabled()) {
                module.onRender(ImGui.getForegroundDrawList());
            }
        }

        // Render GUI Menu
        if (menuOpen) {
            OverlayMenu.render();
        }
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
     * Syncs overlay location with CS2 window, updating click-through state and positioning.
     */
    private void updateWindowPosition() {
        long windowHandle = GLFW.glfwGetCurrentContext();
        if (windowHandle == 0) return;

        // Toggle configuration menu via configured key
        int winKey = javaToWindowsKey(toggleKeyJava);
        boolean insertDown = (User32.INSTANCE.GetAsyncKeyState(winKey) & 0x8000) != 0;
        if (insertDown && !lastInsertDown) {
            menuOpen = !menuOpen;
            System.out.println("[OverlayWindow] Menu toggled: " + (menuOpen ? "Visible" : "Hidden"));

            // Change click-through attributes dynamically based on menu state
            GLFW.glfwSetWindowAttrib(
                    windowHandle,
                    GLFW.GLFW_MOUSE_PASSTHROUGH,
                    menuOpen ? GLFW.GLFW_FALSE : GLFW.GLFW_TRUE
            );

            if (menuOpen) {
                GLFW.glfwFocusWindow(windowHandle);
            }
        }
        lastInsertDown = insertDown;

        // ── Throttled CS2 window geometry query (every 250 ms) ────────────────
        // FindWindow + GetWindowRect involve Win32 kernel calls; running them at
        // full render rate (60-165+ fps) wastes CPU for data that changes far less
        // frequently.  Cached geometry is used between checks.
        long now = System.currentTimeMillis();
        if (now - lastWindowCheckMs >= WINDOW_CHECK_INTERVAL_MS) {
            lastWindowCheckMs = now;

            HWND gameHwnd = User32.INSTANCE.FindWindow("SDL_app", "Counter-Strike 2");
            if (gameHwnd != null) {
                RECT rect = new RECT();
                User32.INSTANCE.GetWindowRect(gameHwnd, rect);
                cachedGameX = rect.left;
                cachedGameY = rect.top;
                cachedGameW = rect.right  - rect.left;
                cachedGameH = rect.bottom - rect.top;
                cachedGameFound = true;
            } else {
                cachedGameFound = false;
            }
        }

        if (!cachedGameFound) return;

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
