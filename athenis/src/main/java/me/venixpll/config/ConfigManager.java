package me.venixpll.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.Setting;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Persists all registered {@link CheatModule} settings (enabled state +
 * every {@link FloatSetting} / {@link BooleanSetting} value) to a JSON file
 * so the user's configuration survives program restarts.
 *
 * <h3>File location</h3>
 * <pre>%APPDATA%\Athenis\settings.json</pre>
 * Falls back to {@code %USERPROFILE%\.athenis\settings.json} if {@code APPDATA}
 * is not defined.
 *
 * <h3>JSON structure</h3>
 * <pre>
 * {
 *   "modules": {
 *     "Radar Hack": {
 *       "enabled": true,
 *       "settings": {
 *         "Radar X Pos":  24.0,
 *         "Radar Size":   200.0,
 *         "Rotate Radar Map": true,
 *         ...
 *       }
 *     },
 *     "ESP Overlay": { ... }
 *   }
 * }
 * </pre>
 */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Returns the settings file, creating its parent directories if needed. */
    private static File resolveFile() {
        String appData = System.getenv("APPDATA");
        Path dir = appData != null
                ? Paths.get(appData, "Athenis")
                : Paths.get(System.getProperty("user.home"), ".athenis");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir.resolve("settings.json").toFile();
    }

    /**
     * Serialises every registered module's enabled state and all its settings
     * values to {@link #resolveFile()}.
     * <p>
     * Called from {@code LauncherWindow.onStop()} and {@code OverlayWindow.postRun()}
     * so settings are saved both on STOP-button click and on manual GLFW window close.
     */
    public static void save() {
        try {
            JsonObject root    = new JsonObject();
            JsonObject modules = new JsonObject();

            for (CheatModule module : ModuleManager.getModules()) {
                JsonObject moduleObj  = new JsonObject();
                JsonObject settingObj = new JsonObject();

                moduleObj.addProperty("enabled", module.isEnabled());

                for (Setting<?> setting : module.getSettings()) {
                    if (setting instanceof FloatSetting) {
                        settingObj.addProperty(setting.getName(), ((FloatSetting) setting).getValue());
                    } else if (setting instanceof BooleanSetting) {
                        settingObj.addProperty(setting.getName(), ((BooleanSetting) setting).getValue());
                    }
                }

                moduleObj.add("settings", settingObj);
                modules.add(module.getName(), moduleObj);
            }

            root.add("modules", modules);

            try (FileWriter writer = new FileWriter(resolveFile())) {
                GSON.toJson(root, writer);
            }

            System.out.println("[ConfigManager] Settings saved to: " + resolveFile().getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ConfigManager] Failed to save settings: " + e.getMessage());
        }
    }

    /**
     * Deserialises the settings file produced by {@link #save()} and applies
     * each value to the matching registered module/setting.
     * <p>
     * Unknown module names and unknown setting names are silently skipped so
     * stale keys from old versions do not crash startup.
     * <p>
     * Called from {@code LauncherWindow.onStart()} immediately after modules are
     * registered, before the memory threads start.
     */
    public static void load() {
        File file = resolveFile();
        if (!file.exists()) {
            System.out.println("[ConfigManager] No settings file found — using defaults.");
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null || !root.has("modules")) return;

            JsonObject modules = root.getAsJsonObject("modules");

            for (CheatModule module : ModuleManager.getModules()) {
                if (!modules.has(module.getName())) continue;
                JsonObject moduleObj = modules.getAsJsonObject(module.getName());

                // Restore enabled state
                if (moduleObj.has("enabled")) {
                    module.setEnabled(moduleObj.get("enabled").getAsBoolean());
                }

                // Restore individual settings
                if (!moduleObj.has("settings")) continue;
                JsonObject settingObj = moduleObj.getAsJsonObject("settings");

                for (Setting<?> setting : module.getSettings()) {
                    JsonElement el = settingObj.get(setting.getName());
                    if (el == null) continue;

                    if (setting instanceof FloatSetting) {
                        ((FloatSetting) setting).setValue(el.getAsFloat());
                    } else if (setting instanceof BooleanSetting) {
                        ((BooleanSetting) setting).setValue(el.getAsBoolean());
                    }
                }
            }

            System.out.println("[ConfigManager] Settings loaded from: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("[ConfigManager] Failed to load settings: " + e.getMessage());
        }
    }

    private ConfigManager() {}
}
