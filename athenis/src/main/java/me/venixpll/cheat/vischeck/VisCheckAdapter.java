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

    public static synchronized void update(String mapName) {
        if (mapName == null || mapName.isEmpty()) {
            currentVisCheck = null;
            loadedMap = "";
            return;
        }

        if (mapName.equals(loadedMap)) {
            return;
        }

        loadedMap = mapName;
        String resourcePath = "/physics/" + mapName + ".opt";
        InputStream stream = VisCheckAdapter.class.getResourceAsStream(resourcePath);

        if (stream == null) {
            System.out.println("[VisCheckAdapter] No optimized geometry found for map: " + mapName + " (looked for " + resourcePath + "). VisCheck disabled.");
            currentVisCheck = null;
            return;
        }

        try {
            System.out.println("[VisCheckAdapter] Loading optimized geometry for map: " + mapName);
            byte[] bytes = stream.readAllBytes();
            stream.close();

            VisCheck vis = new VisCheck(bytes);
            currentVisCheck = vis;
            System.out.println("[VisCheckAdapter] Successfully loaded VisCheck for " + mapName);
        } catch (Exception e) {
            System.err.println("[VisCheckAdapter] Error loading optimized geometry for map " + mapName + ": " + e.getMessage());
            currentVisCheck = null;
        }
    }
}
