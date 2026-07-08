package me.venixpll.overlay;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.util.List;

/**
 * Separate ImGui window displayed while the overlay menu is open.
 * Shows rolling line-graphs of:
 *   &bull; JVM Heap Used (MB) vs Heap Committed (MB)
 *   &bull; CPU load &mdash; process + system (via com.sun.management extensions)
 *
 * Sampling is throttled to ~60 Hz via a simple nanoTime gate so it never
 * runs faster than the render loop. No background thread is spawned.
 */
public final class PerformanceMonitor {

    // ── Window geometry ────────────────────────────────────────────────────
    private static final float WIN_W = 340f;
    private static final float WIN_H = 260f;

    // ── Graph constants ────────────────────────────────────────────────────
    /** Number of samples kept in the rolling history (≈ 2 s at 60 fps). */
    private static final int   HISTORY    = 120;
    private static final float GRAPH_H    = 72f;
    private static final float GRAPH_PAD_X = 12f;
    private static final float GRAPH_PAD_Y =  8f;

    // ── Ring-buffer sample storage ─────────────────────────────────────────
    private static final float[] heapUsedMb = new float[HISTORY];
    private static final float[] heapMaxMb  = new float[HISTORY];
    private static final float[] cpuProcess = new float[HISTORY]; // 0-1
    private static final float[] cpuSystem  = new float[HISTORY]; // 0-1
    /** Next write position in the ring buffer. */
    private static int head = 0;

    // ── MXBeans (cached at class-load) ────────────────────────────────────
    private static final MemoryMXBean       MEM_BEAN =
            ManagementFactory.getMemoryMXBean();
    private static final OperatingSystemMXBean OS_BEAN  =
            ManagementFactory.getOperatingSystemMXBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS =
            ManagementFactory.getGarbageCollectorMXBeans();

    // ── Sampling throttle ─────────────────────────────────────────────────
    private static long lastSampleNs = 0L;
    private static final long SAMPLE_INTERVAL_NS = 16_000_000L; // ~60 Hz

    // ── Palette — Catppuccin Mocha (same as OverlayMenu) ─────────────────
    private static final float[] COL_BG      = { 0.039f, 0.043f, 0.055f, 0.97f };
    private static final float[] COL_SURFACE = { 0.067f, 0.075f, 0.094f, 1.00f };
    private static final float[] COL_ACCENT  = { 0.000f, 0.706f, 0.847f, 1.00f }; // cyan
    private static final float[] COL_WARN    = { 1.000f, 0.600f, 0.100f, 1.00f }; // amber
    private static final float[] COL_OK      = { 0.180f, 0.800f, 0.440f, 1.00f }; // green
    private static final float[] COL_DIM     = { 0.424f, 0.439f, 0.525f, 1.00f };
    private static final float[] COL_SEP     = { 0.129f, 0.149f, 0.176f, 1.00f };

    private PerformanceMonitor() {}

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Call every frame while the overlay menu is open.
     * Samples JVM / OS metrics and renders the performance window.
     */
    public static void render() {
        // ── Throttled sampling ─────────────────────────────────────────────
        long nowNs = System.nanoTime();
        if (nowNs - lastSampleNs >= SAMPLE_INTERVAL_NS) {
            lastSampleNs = nowNs;
            sample();
        }

        // ── Window position — right of the main menu ───────────────────────
        float screenW = ImGui.getIO().getDisplaySizeX();
        float screenH = ImGui.getIO().getDisplaySizeY();
        float menuX   = (screenW - 700f) * 0.5f;
        float menuY   = (screenH - 480f) * 0.5f;

        ImGui.setNextWindowSize(WIN_W, WIN_H, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowPos(menuX + 700f + 12f, menuY, ImGuiCond.FirstUseEver);

        ImGui.pushStyleColor(imgui.flag.ImGuiCol.WindowBg, 0f, 0f, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 10f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding,  0f, 0f);

        boolean visible = ImGui.begin("##PerfMon",
                ImGuiWindowFlags.NoTitleBar
                | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoScrollbar
                | ImGuiWindowFlags.NoScrollWithMouse
                | ImGuiWindowFlags.NoFocusOnAppearing);

        if (visible) {
            drawContents(ImGui.getWindowPos(), ImGui.getWindowDrawList());
        }

        ImGui.end();
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Drawing
    // ─────────────────────────────────────────────────────────────────────

    private static void drawContents(ImVec2 wp, ImDrawList dl) {
        // ── Window background ──────────────────────────────────────────────
        dl.addRectFilled(wp.x, wp.y, wp.x + WIN_W, wp.y + WIN_H,
                ImColor.rgba(COL_BG[0], COL_BG[1], COL_BG[2], COL_BG[3]), 10f);

        // ── Title bar ─────────────────────────────────────────────────────
        dl.addRectFilled(wp.x, wp.y, wp.x + WIN_W, wp.y + 34f,
                ImColor.rgba(COL_SURFACE[0], COL_SURFACE[1], COL_SURFACE[2], 1f),
                10f, 48 /* ImDrawFlags_RoundCornersTop: TopLeft(16) + TopRight(32) */);

        // Left accent bar
        dl.addRectFilled(wp.x, wp.y, wp.x + 4f, wp.y + 34f,
                ImColor.rgba(COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1f), 2f);

        dl.addText(wp.x + 14f, wp.y + 9f,
                ImColor.rgba(0.85f, 0.85f, 0.85f, 1f), "Performance Monitor");

        // CPU badge in title
        float latestCpuProc = getLatest(cpuProcess);
        float latestCpuSys  = getLatest(cpuSystem);
        float latestHeap    = getLatest(heapUsedMb);
        float latestHeapMax = getLatest(heapMaxMb);

        String cpuBadge = String.format("%.0f%%", latestCpuProc * 100f);
        dl.addText(wp.x + WIN_W - 40f, wp.y + 9f,
                colorForFraction(latestCpuProc), cpuBadge);

        // Separator
        dl.addLine(wp.x, wp.y + 34f, wp.x + WIN_W, wp.y + 34f,
                ImColor.rgba(COL_SEP[0], COL_SEP[1], COL_SEP[2], 1f), 1f);

        // ── Memory graph ───────────────────────────────────────────────────
        float cursorY = wp.y + 42f;
        cursorY = renderGraph(dl, wp.x, cursorY,
                "JVM Heap", "MB",
                heapUsedMb, heapMaxMb,
                latestHeap, latestHeapMax,
                COL_ACCENT, COL_SURFACE,
                latestHeap / Math.max(latestHeapMax, 1f));

        cursorY += 6f;
        dl.addLine(wp.x + 12f, cursorY, wp.x + WIN_W - 12f, cursorY,
                ImColor.rgba(COL_SEP[0], COL_SEP[1], COL_SEP[2], 0.5f), 1f);
        cursorY += 8f;

        // ── CPU graph ─────────────────────────────────────────────────────
        renderGraph(dl, wp.x, cursorY,
                "CPU", "%",
                cpuProcess, cpuSystem,
                latestCpuProc * 100f, latestCpuSys * 100f,
                COL_ACCENT, COL_WARN,
                latestCpuProc);
    }

    /**
     * Renders a labelled two-series line graph.
     *
     * @param primary         primary data series (drawn on top, filled below)
     * @param secondary       secondary series (drawn underneath, lighter fill)
     * @param latestPrimary   latest value of primary (for label)
     * @param latestSecondary latest value of secondary (for label)
     * @param fractionForColor 0-1 value used to pick green/amber/red label color
     * @return Y position immediately below the rendered block
     */
    private static float renderGraph(ImDrawList dl,
            float x, float y,
            String label, String unit,
            float[] primary, float[] secondary,
            float latestPrimary, float latestSecondary,
            float[] colPrimary, float[] colSecondary,
            float fractionForColor) {

        float graphW = WIN_W - GRAPH_PAD_X * 2f;

        // ── Label + current-value row ──────────────────────────────────────
        int labelColor = ImColor.rgba(COL_DIM[0], COL_DIM[1], COL_DIM[2], 1f);
        dl.addText(x + GRAPH_PAD_X, y, labelColor, label);

        String valStr;
        if (unit.equals("MB")) {
            valStr = String.format("%.0f / %.0f MB", latestPrimary, latestSecondary);
        } else {
            valStr = String.format("%.0f%% proc  %.0f%% sys",
                    latestPrimary, latestSecondary);
        }
        int valColor = colorForFraction(fractionForColor);
        float valW = valStr.length() * 6.8f; // approximate font width
        dl.addText(x + WIN_W - GRAPH_PAD_X - valW, y, valColor, valStr);

        y += 16f;

        // ── Graph background ───────────────────────────────────────────────
        dl.addRectFilled(x + GRAPH_PAD_X, y,
                x + GRAPH_PAD_X + graphW, y + GRAPH_H,
                ImColor.rgba(COL_SURFACE[0], COL_SURFACE[1], COL_SURFACE[2], 1f), 5f);

        // ── Horizontal grid at 25 / 50 / 75 % ─────────────────────────────
        int gridCol = ImColor.rgba(COL_SEP[0], COL_SEP[1], COL_SEP[2], 0.4f);
        for (int pct = 25; pct <= 75; pct += 25) {
            float gy = y + GRAPH_H * (1f - pct / 100f);
            dl.addLine(x + GRAPH_PAD_X, gy, x + GRAPH_PAD_X + graphW, gy, gridCol, 1f);
        }

        // ── Determine the scale ceiling ────────────────────────────────────
        // For MB graphs: use the max committed heap as ceiling (stable axis).
        // For CPU graphs: fixed 1.0 (= 100 %).
        float maxVal = unit.equals("MB") ? Math.max(arrayMax(heapMaxMb), 1f) : 1f;

        // ── Draw secondary series first (behind primary) ───────────────────
        if (secondary != null) {
            drawFill(dl, secondary, x + GRAPH_PAD_X, y, graphW, maxVal, colSecondary, 0.12f);
            drawLine(dl, secondary, x + GRAPH_PAD_X, y, graphW, maxVal, colSecondary, 1.5f);
        }

        // ── Draw primary series ────────────────────────────────────────────
        drawFill(dl,  primary, x + GRAPH_PAD_X, y, graphW, maxVal, colPrimary, 0.20f);
        drawLine(dl,  primary, x + GRAPH_PAD_X, y, graphW, maxVal, colPrimary, 2.0f);

        // ── Current-value dot at right edge ───────────────────────────────
        float latest  = getLatest(primary);
        float dotY    = y + GRAPH_H * (1f - Math.min(1f, latest / maxVal));
        float dotX    = x + GRAPH_PAD_X + graphW;
        // Outer glow
        dl.addCircleFilled(dotX, dotY, 5f,
                ImColor.rgba(colPrimary[0], colPrimary[1], colPrimary[2], 0.25f), 8);
        // Core dot
        dl.addCircleFilled(dotX, dotY, 3f,
                ImColor.rgba(colPrimary[0], colPrimary[1], colPrimary[2], 1f), 8);

        // ── Border ────────────────────────────────────────────────────────
        dl.addRect(x + GRAPH_PAD_X, y,
                x + GRAPH_PAD_X + graphW, y + GRAPH_H,
                ImColor.rgba(COL_SEP[0], COL_SEP[1], COL_SEP[2], 0.7f), 5f, 0, 1f);

        return y + GRAPH_H + GRAPH_PAD_Y;
    }

    private static void drawLine(ImDrawList dl, float[] data,
            float x, float y, float graphW,
            float maxVal, float[] col, float thickness) {
        int n = data.length;
        float step = graphW / (n - 1);
        int lineCol = ImColor.rgba(col[0], col[1], col[2], 1f);
        float px0 = 0, py0 = 0;
        for (int i = 0; i < n; i++) {
            int idx = (head + i) % n;
            float px = x + i * step;
            float py = y + GRAPH_H * (1f - Math.min(1f, data[idx] / maxVal));
            if (i > 0) dl.addLine(px0, py0, px, py, lineCol, thickness);
            px0 = px;
            py0 = py;
        }
    }

    private static void drawFill(ImDrawList dl, float[] data,
            float x, float y, float graphW,
            float maxVal, float[] col, float alpha) {
        int n    = data.length;
        float step = graphW / (n - 1);
        int fillCol = ImColor.rgba(col[0], col[1], col[2], alpha);
        float bot = y + GRAPH_H;
        for (int i = 0; i < n - 1; i++) {
            int idxA = (head + i)     % n;
            int idxB = (head + i + 1) % n;
            float pxA = x + i * step;
            float pxB = x + (i + 1) * step;
            float pyA = y + GRAPH_H * (1f - Math.min(1f, data[idxA] / maxVal));
            float pyB = y + GRAPH_H * (1f - Math.min(1f, data[idxB] / maxVal));
            dl.addQuadFilled(pxA, pyA, pxB, pyB, pxB, bot, pxA, bot, fillCol);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sampling
    // ─────────────────────────────────────────────────────────────────────

    private static void sample() {
        // Heap
        long usedBytes = MEM_BEAN.getHeapMemoryUsage().getUsed();
        long cmmtBytes = MEM_BEAN.getHeapMemoryUsage().getCommitted();
        heapUsedMb[head] = usedBytes / (1024f * 1024f);
        heapMaxMb[head]  = cmmtBytes / (1024f * 1024f);

        // CPU via com.sun.management extension (available on all HotSpot JVMs)
        double proc = 0.0, sys = 0.0;
        if (OS_BEAN instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean sunOs =
                    (com.sun.management.OperatingSystemMXBean) OS_BEAN;
            proc = sunOs.getProcessCpuLoad();
            sys  = sunOs.getCpuLoad();
            if (proc < 0) proc = 0;
            if (sys  < 0) sys  = 0;
        }
        cpuProcess[head] = (float) proc;
        cpuSystem[head]  = (float) sys;

        head = (head + 1) % HISTORY;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────

    private static float getLatest(float[] arr) {
        return arr[(head - 1 + HISTORY) % HISTORY];
    }

    private static float arrayMax(float[] arr) {
        float m = 0f;
        for (float v : arr) if (v > m) m = v;
        return m;
    }

    /**
     * Maps a 0-1 fraction to a colour gradient:
     * 0 = green, 0.5 = amber, 1 = red.
     */
    private static int colorForFraction(float f) {
        f = Math.min(1f, Math.max(0f, f));
        if (f < 0.5f) {
            float t = f * 2f;
            return ImColor.rgba(
                    lerp(COL_OK[0], COL_WARN[0], t),
                    lerp(COL_OK[1], COL_WARN[1], t),
                    lerp(COL_OK[2], COL_WARN[2], t), 1f);
        } else {
            float t = (f - 0.5f) * 2f;
            return ImColor.rgba(
                    lerp(COL_WARN[0], 1f,         t),
                    lerp(COL_WARN[1], 0f,         t),
                    lerp(COL_WARN[2], 0f,         t), 1f);
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
