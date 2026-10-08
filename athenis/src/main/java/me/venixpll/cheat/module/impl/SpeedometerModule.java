package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.overlay.OverlayWindow;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Speedometer Module.
 *
 * <p>Displays a movable overlay HUD component showing the local player's
 * current movement speed (u/s) alongside a real-time velocity graph
 * tracking the last 2 seconds of speed history.
 *
 * <p>The window is movable by clicking and dragging when the cheat menu
 * (INSERT key) is open. When the menu is closed, the window locks into place
 * as a clean, non-intrusive HUD overlay.
 */
public class SpeedometerModule extends CheatModule {

    private static final long HISTORY_DURATION_MS = 2000L; // 2 seconds max
    private static final float GRAPH_WIDTH = 220.0f;
    private static final float GRAPH_HEIGHT = 60.0f;
    private static final float BASE_MAX_SPEED = 300.0f; // 250 u/s standard max run + headroom

    private static class SpeedSample {
        final long timestamp;
        final float speed;

        SpeedSample(long timestamp, float speed) {
            this.timestamp = timestamp;
            this.speed = speed;
        }
    }

    private final Deque<SpeedSample> history = new ArrayDeque<>();

    public SpeedometerModule() {
        super("Speedometer", ModuleCategory.EXTERNAL, MenuGroup.VISUALS, true);
    }

    @Override
    public boolean isDangerous() {
        return false;
    }

    @Override
    public void onRender(ImDrawList foregroundDrawList) {
        if (!isEnabled()) return;

        boolean isMenuOpen = OverlayWindow.isMenuOpen();
        boolean isTracking = PlayerCache.tracking;

        // Skip rendering when detached from CS2 unless the overlay menu is open
        if (!isTracking && !isMenuOpen) return;

        // ── 1. Read local player velocity ──────────────────────────────────────
        float currentSpeed = 0.0f;
        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (localPawn != 0) {
            Vector3 vel = CS2Memory.readVector(localPawn + CS2Offsets.m_vecVelocity);
            if (vel != null && Float.isFinite(vel.x) && Float.isFinite(vel.y)) {
                currentSpeed = (float) Math.sqrt(vel.x * vel.x + vel.y * vel.y);
            }
        }

        // ── 2. Update 2-second rolling history buffer ──────────────────────────
        long now = System.currentTimeMillis();
        history.addLast(new SpeedSample(now, currentSpeed));

        long cutoff = now - HISTORY_DURATION_MS;
        while (!history.isEmpty() && history.peekFirst().timestamp < cutoff) {
            history.pollFirst();
        }

        // ── 3. Configure ImGui Window Flags & Position ─────────────────────────
        ImGui.setNextWindowPos(50.0f, 250.0f, ImGuiCond.FirstUseEver);

        int windowFlags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize;
        if (!isMenuOpen) {
            windowFlags |= ImGuiWindowFlags.NoTitleBar
                        | ImGuiWindowFlags.NoResize
                        | ImGuiWindowFlags.NoMove
                        | ImGuiWindowFlags.NoInputs;
        }

        if (ImGui.begin("Speedometer", windowFlags)) {
            // Header text: Speed Value & Unit
            int speedColor;
            if (currentSpeed > 250.5f) {
                // Strafe / Bhop speed boost: Emerald Green
                speedColor = ImColor.rgba(0.2f, 0.95f, 0.55f, 1.0f);
            } else if (currentSpeed > 10.0f) {
                // Standard movement: Cyan
                speedColor = ImColor.rgba(0.0f, 0.85f, 1.0f, 1.0f);
            } else {
                // Stopped / Idle: Muted Gray
                speedColor = ImColor.rgba(0.7f, 0.75f, 0.8f, 0.85f);
            }

            ImGui.textColored(speedColor, String.format("%.1f u/s", currentSpeed));

            if (isMenuOpen) {
                ImGui.sameLine();
                ImGui.textColored(ImColor.rgba(0.5f, 0.5f, 0.5f, 0.7f), "[Drag Title to Move]");
            }

            // Reserve layout space for graph
            float startX = ImGui.getCursorScreenPos().x;
            float startY = ImGui.getCursorScreenPos().y + 4.0f;
            ImGui.dummy(GRAPH_WIDTH, GRAPH_HEIGHT);

            ImDrawList dl = ImGui.getWindowDrawList();

            // ── 4. Render Velocity Graph ───────────────────────────────────────
            // Background container
            int bgCol = ImColor.rgba(0.04f, 0.05f, 0.08f, 0.80f);
            int borderCol = ImColor.rgba(0.25f, 0.30f, 0.40f, 0.50f);
            dl.addRectFilled(startX, startY, startX + GRAPH_WIDTH, startY + GRAPH_HEIGHT, bgCol, 5.0f);
            dl.addRect(startX, startY, startX + GRAPH_WIDTH, startY + GRAPH_HEIGHT, borderCol, 5.0f, 0, 1.0f);

            // Determine maximum speed scale (minimum 300 u/s)
            float maxSpeed = BASE_MAX_SPEED;
            for (SpeedSample sample : history) {
                if (sample.speed > maxSpeed) {
                    maxSpeed = sample.speed * 1.1f;
                }
            }

            // Reference line at 250 u/s (standard max running speed)
            float refY = startY + GRAPH_HEIGHT - ((250.0f / maxSpeed) * GRAPH_HEIGHT);
            if (refY >= startY && refY <= startY + GRAPH_HEIGHT) {
                int refLineCol = ImColor.rgba(1.0f, 1.0f, 1.0f, 0.20f);
                dl.addLine(startX + 2.0f, refY, startX + GRAPH_WIDTH - 2.0f, refY, refLineCol, 1.0f);
            }

            // Plot history points
            if (history.size() >= 2) {
                int count = history.size();
                float[] xPts = new float[count];
                float[] yPts = new float[count];

                int idx = 0;
                for (SpeedSample sample : history) {
                    float timeFrac = (float) (sample.timestamp - cutoff) / (float) HISTORY_DURATION_MS;
                    timeFrac = Math.clamp(timeFrac, 0.0f, 1.0f);

                    float speedFrac = Math.clamp(sample.speed / maxSpeed, 0.0f, 1.0f);

                    xPts[idx] = startX + (timeFrac * GRAPH_WIDTH);
                    yPts[idx] = startY + GRAPH_HEIGHT - (speedFrac * GRAPH_HEIGHT);
                    idx++;
                }

                int graphLineCol = ImColor.rgba(0.0f, 0.90f, 1.0f, 0.95f);
                int fillCol = ImColor.rgba(0.0f, 0.85f, 1.0f, 0.15f);

                // Render filled area below graph line & polyline graph segments
                for (int i = 1; i < count; i++) {
                    float x1 = xPts[i - 1];
                    float y1 = yPts[i - 1];
                    float x2 = xPts[i];
                    float y2 = yPts[i];

                    // Filled vertical quad underneath segment
                    dl.addRectFilled(x1, Math.min(y1, y2), x2, startY + GRAPH_HEIGHT - 1.0f, fillCol);

                    // Polyline segment
                    dl.addLine(x1, y1, x2, y2, graphLineCol, 2.0f);
                }

                // Glowing point indicator on current value
                float lastX = xPts[count - 1];
                float lastY = yPts[count - 1];
                dl.addCircleFilled(lastX, lastY, 3.5f, ImColor.rgba(1.0f, 1.0f, 1.0f, 1.0f), 10);
                dl.addCircleFilled(lastX, lastY, 2.0f, speedColor, 8);
            }
        }
        ImGui.end();
    }
}