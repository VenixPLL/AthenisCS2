package me.venixpll.overlay;

import imgui.ImColor;
import imgui.ImDrawList;

import java.util.ArrayList;
import java.util.List;

/**
 * NotificationManager — top-right corner toast notifications.
 *
 * <h3>Usage</h3>
 * <pre>
 *   // Push a new notification (thread-safe):
 *   NotificationManager.push("ESP", true);   // "ESP  Enabled"
 *   NotificationManager.push("ESP", false);  // "ESP  Disabled"
 *
 *   // Call once per frame inside OverlayWindow.process():
 *   NotificationManager.render(ImGui.getForegroundDrawList(), screenW, screenH);
 * </pre>
 *
 * <h3>Stacking behaviour</h3>
 * <ul>
 *   <li>Newest notification always appends at the bottom of the stack.</li>
 *   <li>Re-pushing the same module updates the existing entry in-place (resets
 *       the timer and flips the state) so its slot never jumps.</li>
 *   <li>When a notification expires, those below it smoothly slide upward to
 *       fill the gap — each pill animates its Y toward the slot above.</li>
 * </ul>
 *
 * <h3>Per-notification animation</h3>
 * <ol>
 *   <li>Slide in from the right (180 ms, smoothstep).</li>
 *   <li>Hold (2 200 ms).</li>
 *   <li>Fade out (350 ms).</li>
 * </ol>
 *
 * <h3>Visual style</h3>
 * Dark rounded pill (Catppuccin Mocha #161B22), 4-px accent bar
 * (cyan for Enabled, soft red for Disabled), subtle border + glow + shadow.
 */
public final class NotificationManager {

    // ── Timing (ms) ───────────────────────────────────────────────────────────
    private static final long  SLIDE_IN_MS = 180L;
    private static final long  HOLD_MS     = 2200L;
    private static final long  FADE_OUT_MS = 350L;
    private static final long  TOTAL_MS    = SLIDE_IN_MS + HOLD_MS + FADE_OUT_MS;

    // ── Slot-slide animation ──────────────────────────────────────────────────
    /** How quickly each pill chases its target Y slot (pixels per second). */
    private static final float SLOT_SLIDE_SPEED = 400f; // px/s

    // ── Geometry ──────────────────────────────────────────────────────────────
    private static final float PILL_W   = 210f;
    private static final float PILL_H   = 52f;
    private static final float PILL_R   = 8f;
    private static final float ACCENT_W = 4f;
    private static final float MARGIN_R = 18f; // right screen margin
    private static final float MARGIN_T = 18f; // top screen margin
    private static final float GAP      = 8f;  // gap between pills

    // ── Catppuccin Mocha-inspired palette ─────────────────────────────────────
    private static final float[] C_BG       = { 0.086f, 0.106f, 0.133f }; // #161B22
    private static final float[] C_ENABLED  = { 0.000f, 0.706f, 0.847f }; // #00B4D8
    private static final float[] C_DISABLED = { 0.850f, 0.250f, 0.250f }; // soft red
    private static final float[] C_TEXT     = { 0.920f, 0.920f, 0.940f }; // bright
    private static final float[] C_BORDER   = { 0.129f, 0.149f, 0.176f }; // #21262D

    // ─────────────────────────────────────────────────────────────────────────

    /** One live notification entry. */
    private static final class Notification {
        String  moduleName;
        boolean enabled;
        String  customMessage;
        long    createdMs;

        /**
         * Animated Y position of this pill's top edge, in screen pixels.
         * Starts at the target slot's Y and smoothly tracks the slot as the
         * queue compacts when earlier entries expire.
         */
        float   currentY;

        Notification(String moduleName, boolean enabled, float startY) {
            this(moduleName, enabled, null, startY);
        }

        Notification(String moduleName, boolean enabled, String customMessage, float startY) {
            this.moduleName = moduleName;
            this.enabled    = enabled;
            this.customMessage = customMessage;
            this.createdMs  = System.currentTimeMillis();
            this.currentY   = startY;
        }

        /** Resets this entry in-place (rapid toggle of the same module). */
        void reset(boolean newEnabled) {
            this.enabled   = newEnabled;
            this.createdMs = System.currentTimeMillis();
        }

        long age()       { return System.currentTimeMillis() - createdMs; }
        boolean expired(){ return age() >= TOTAL_MS; }

        /** Alpha: 1.0 during slide+hold, ramps 1→0 during fade. */
        float alpha() {
            long t = age();
            if (t < SLIDE_IN_MS + HOLD_MS) return 1.0f;
            float p = (float)(t - SLIDE_IN_MS - HOLD_MS) / FADE_OUT_MS;
            return Math.max(0f, 1.0f - p);
        }

        /**
         * Horizontal slide offset.
         * Returns pixels by which the pill is shifted rightward (0 = fully on-screen).
         * Uses smoothstep easing so the pill decelerates as it arrives.
         */
        float slideOffsetX() {
            long t = age();
            if (t >= SLIDE_IN_MS) return 0f;
            float p = 1.0f - (float) t / SLIDE_IN_MS; // 1→0
            float e = p * p * (3f - 2f * p);           // smoothstep
            return (PILL_W + MARGIN_R) * e;
        }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private static final List<Notification> queue   = new ArrayList<>();
    private static       long               lastFrameMs = System.currentTimeMillis();

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Queues or refreshes a module-toggle notification.
     * Thread-safe; may be called from the bind-key polling thread or the GUI
     * render thread.
     *
     * <p>If the same module already has a visible notification, its timer is
     * reset in-place and the enabled state flipped — this keeps its slot
     * position stable so the stack never jumps.
     *
     * @param moduleName Display name of the toggled module.
     * @param enabled    {@code true} if the module was just enabled.
     */
    public static synchronized void push(String moduleName, boolean enabled) {
        push(moduleName, enabled, null);
    }

    /**
     * Queues or refreshes a module-toggle notification with a custom message.
     * Thread-safe.
     *
     * @param moduleName    Display name or title of the notification.
     * @param enabled       Whether to use the "enabled" accent (cyan) or "disabled" (red).
     * @param customMessage A custom state label to display.
     */
    public static synchronized void push(String moduleName, boolean enabled, String customMessage) {
        // Try to update an existing entry for this module.
        for (Notification n : queue) {
            if (n.moduleName.equals(moduleName)) {
                n.reset(enabled);
                n.customMessage = customMessage;
                return;
            }
        }
        // New module: append at the bottom. Its starting Y is just below the
        // last pill so it slides in from the right at the correct slot.
        float startY = slotY(queue.size());
        queue.add(new Notification(moduleName, enabled, customMessage, startY));
    }

    /**
     * Renders all active notifications and advances slot animations.
     * Must be called exactly once per frame from the ImGui render thread.
     *
     * @param drawList    ImGui foreground draw list.
     * @param screenWidth Current overlay width in pixels.
     * @param screenHeight Current overlay height (unused but kept for symmetry).
     */
    public static synchronized void render(ImDrawList drawList,
                                           float screenWidth,
                                           float screenHeight) {
        // ── Delta time for slot-slide animation ───────────────────────────────
        long now    = System.currentTimeMillis();
        float dt    = Math.min((now - lastFrameMs) / 1000f, 0.1f); // cap at 100 ms
        lastFrameMs = now;

        // ── Remove expired entries ────────────────────────────────────────────
        queue.removeIf(Notification::expired);
        if (queue.isEmpty()) return;

        // ── Advance each pill's Y toward its target slot ──────────────────────
        for (int i = 0; i < queue.size(); i++) {
            Notification n = queue.get(i);
            float targetY  = slotY(i);
            float diff     = targetY - n.currentY;
            float maxStep  = SLOT_SLIDE_SPEED * dt;
            if (Math.abs(diff) <= maxStep) {
                n.currentY = targetY; // snap when close enough
            } else {
                n.currentY += Math.signum(diff) * maxStep;
            }
        }

        // ── Draw ──────────────────────────────────────────────────────────────
        for (Notification n : queue) {
            drawPill(drawList, n, screenWidth);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Returns the target Y (top edge of pill) for the given slot index,
     * measured from the top of the screen.
     */
    private static float slotY(int slot) {
        return MARGIN_T + slot * (PILL_H + GAP);
    }

    /** Draws a single notification pill at its current animated position. */
    private static void drawPill(ImDrawList dl, Notification n, float screenWidth) {
        float alpha  = n.alpha();
        float slideX = n.slideOffsetX();

        float pillX = screenWidth - MARGIN_R - PILL_W + slideX;
        float pillY = n.currentY;

        float[] accent = n.enabled ? C_ENABLED : C_DISABLED;

        // 1. Drop shadow
        dl.addRectFilled(
                pillX + 3f, pillY + 4f,
                pillX + PILL_W + 3f, pillY + PILL_H + 4f,
                ImColor.rgba(0f, 0f, 0f, 0.35f * alpha), PILL_R);

        // 2. Pill background
        dl.addRectFilled(
                pillX, pillY,
                pillX + PILL_W, pillY + PILL_H,
                ImColor.rgba(C_BG[0], C_BG[1], C_BG[2], 0.97f * alpha), PILL_R);

        // 3. Border
        dl.addRect(
                pillX, pillY,
                pillX + PILL_W, pillY + PILL_H,
                ImColor.rgba(C_BORDER[0], C_BORDER[1], C_BORDER[2], 0.80f * alpha),
                PILL_R, 0, 1f);

        // 4. Left accent bar — drawn as three rects that together form a
        //    vertically-clipped bar flush with the pill's left rounded edge.
        int accentCol = ImColor.rgba(accent[0], accent[1], accent[2], alpha);
        // middle strip
        dl.addRectFilled(
                pillX, pillY + PILL_R,
                pillX + ACCENT_W, pillY + PILL_H - PILL_R,
                accentCol);
        // top cap (overpaints the pill's top-left arc)
        dl.addRectFilled(
                pillX, pillY,
                pillX + ACCENT_W, pillY + PILL_R + 1f,
                accentCol);
        // bottom cap
        dl.addRectFilled(
                pillX, pillY + PILL_H - PILL_R,
                pillX + ACCENT_W, pillY + PILL_H,
                accentCol);

        // 5. Subtle accent glow
        dl.addRectFilled(
                pillX, pillY,
                pillX + 30f, pillY + PILL_H,
                ImColor.rgba(accent[0], accent[1], accent[2], 0.10f * alpha), PILL_R);

        // 6. Text
        float textX    = pillX + ACCENT_W + 10f;
        float nameY    = pillY + 10f;
        float subY     = pillY + 29f;
        int   shadow   = ImColor.rgba(0f, 0f, 0f, 0.75f * alpha);
        int   mainText = ImColor.rgba(C_TEXT[0], C_TEXT[1], C_TEXT[2], alpha);
        int   stateCol = ImColor.rgba(accent[0], accent[1], accent[2], alpha);

        dl.addText(textX + 1f, nameY + 1f, shadow, n.moduleName);
        dl.addText(textX,      nameY,      mainText, n.moduleName);

        String label = n.customMessage != null ? n.customMessage : (n.enabled ? "Enabled" : "Disabled");
        dl.addText(textX + 1f, subY + 1f, shadow, label);
        dl.addText(textX,      subY,      stateCol, label);
    }

    private NotificationManager() {}
}
