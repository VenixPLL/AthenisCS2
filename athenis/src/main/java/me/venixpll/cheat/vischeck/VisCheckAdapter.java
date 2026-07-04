package me.venixpll.cheat.vischeck;

import java.io.InputStream;

public class VisCheckAdapter {
    private static volatile VisCheck currentVisCheck = null;
    private static volatile String loadedMap = "";

    public static VisCheck getVisCheck() {
        return currentVisCheck;
    }

    public static String getLoadedMap() {
        return loadedMap;
    }

    // ── Debug helpers ─────────────────────────────────────────────────────────

    /**
     * Enable or disable VisCheck ray debug logging.
     * Rate-limited by VisCheck.DEBUG_THROTTLE_MS (default 500 ms).
     */
    public static void setDebug(boolean enabled) {
        VisCheck.DEBUG = enabled;
        System.out.println("[VisCheckAdapter] Debug logging " + (enabled ? "ENABLED" : "DISABLED")
                + "  (throttle=" + VisCheck.DEBUG_THROTTLE_MS + " ms)");
    }

    public static boolean isDebugEnabled() {
        return VisCheck.DEBUG;
    }

    /**
     * Change how frequently debug lines are printed (0 = always print, no throttle).
     */
    public static void setDebugThrottleMs(long ms) {
        VisCheck.DEBUG_THROTTLE_MS = ms;
        System.out.println("[VisCheckAdapter] Debug throttle set to " + ms + " ms");
    }

    /**
     * Print lifetime ray-cast statistics for the currently loaded map.
     */
    public static void printStats() {
        VisCheck.printStats();
    }

    // ── Map loading ───────────────────────────────────────────────────────────

    public static synchronized void update(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            currentVisCheck = null;
            loadedMap = "";
            return;
        }

        if (mapName.equals(loadedMap)) {
            return;
        }

        // Reset per-map counters before loading the new geometry
        VisCheck.resetStats();

        loadedMap = mapName;
        String resourcePath = "/physics/" + mapName + ".opt";
        InputStream stream = VisCheckAdapter.class.getResourceAsStream(resourcePath);

        if (stream == null) {
            System.out.println("[VisCheckAdapter] No optimized geometry found for map: " + mapName
                    + " (looked for " + resourcePath + "). VisCheck disabled.");
            currentVisCheck = null;
            return;
        }

        try {
            System.out.println("[VisCheckAdapter] Loading optimized geometry for map: " + mapName);
            byte[] bytes = stream.readAllBytes();
            stream.close();

            VisCheck vis = new VisCheck(bytes, mapName);
            currentVisCheck = vis;
            System.out.println("[VisCheckAdapter] Successfully loaded VisCheck for " + mapName);
        } catch (Exception e) {
            System.err.println("[VisCheckAdapter] Error loading optimized geometry for map "
                    + mapName + ": " + e.getMessage());
            currentVisCheck = null;
        }
    }
}
