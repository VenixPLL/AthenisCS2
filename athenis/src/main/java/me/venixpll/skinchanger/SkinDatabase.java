package me.venixpll.skinchanger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;


public class SkinDatabase {
    private static final List<SkinInfo> weaponSkins = new ArrayList<>();
    private static final Map<String, WeaponsEnum> WEAPON_MAP = new HashMap<>();

    static {
        WEAPON_MAP.put("AK-47", WeaponsEnum.AK47);
        WEAPON_MAP.put("AUG", WeaponsEnum.AUG);
        WEAPON_MAP.put("AWP", WeaponsEnum.AWP);
        WEAPON_MAP.put("PP-Bizon", WeaponsEnum.BIZON);
        WEAPON_MAP.put("CZ75-Auto", WeaponsEnum.CZ75);
        WEAPON_MAP.put("Desert Eagle", WeaponsEnum.DEAGLE);
        WEAPON_MAP.put("Dual Berettas", WeaponsEnum.ELITE);
        WEAPON_MAP.put("FAMAS", WeaponsEnum.FAMAS);
        WEAPON_MAP.put("Five-SeveN", WeaponsEnum.FIVESEVEN);
        WEAPON_MAP.put("G3SG1", WeaponsEnum.G3SG1);
        WEAPON_MAP.put("Galil AR", WeaponsEnum.GALIL);
        WEAPON_MAP.put("Glock-18", WeaponsEnum.GLOCK);
        WEAPON_MAP.put("P2000", WeaponsEnum.P2000);
        WEAPON_MAP.put("M249", WeaponsEnum.M249);
        WEAPON_MAP.put("M4A1-S", WeaponsEnum.M4A1_S);
        WEAPON_MAP.put("M4A4", WeaponsEnum.M4A4);
        WEAPON_MAP.put("MAC-10", WeaponsEnum.MAC10);
        WEAPON_MAP.put("MAG-7", WeaponsEnum.MAG7);
        WEAPON_MAP.put("MP5-SD", WeaponsEnum.MP5SD);
        WEAPON_MAP.put("MP7", WeaponsEnum.MP7);
        WEAPON_MAP.put("MP9", WeaponsEnum.MP9);
        WEAPON_MAP.put("Negev", WeaponsEnum.NEGEV);
        WEAPON_MAP.put("Nova", WeaponsEnum.NOVA);
        WEAPON_MAP.put("XM1014", WeaponsEnum.XM1014);
        WEAPON_MAP.put("USP-S", WeaponsEnum.USP_S);
        WEAPON_MAP.put("Tec-9", WeaponsEnum.TEC9);
        WEAPON_MAP.put("Zeus x27", WeaponsEnum.ZEUS);
        WEAPON_MAP.put("SSG 08", WeaponsEnum.SSG08);
        WEAPON_MAP.put("SG 553", WeaponsEnum.SG556);
        WEAPON_MAP.put("SCAR-20", WeaponsEnum.SCAR20);
        WEAPON_MAP.put("Sawed-Off", WeaponsEnum.SAWEDOFF);
        WEAPON_MAP.put("R8 Revolver", WeaponsEnum.REVOLVER);
        WEAPON_MAP.put("P90", WeaponsEnum.P90);
        WEAPON_MAP.put("P250", WeaponsEnum.P250);
        WEAPON_MAP.put("UMP-45", WeaponsEnum.UMP45);
    }

    private static WeaponsEnum getDefPerString(String name) {
        for (Map.Entry<String, WeaponsEnum> entry : WEAPON_MAP.entrySet()) {
            if (name.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return WeaponsEnum.NONE;
    }

    private static File resolveCacheFile() {
        String appData = System.getenv("APPDATA");
        Path dir = appData != null
                ? Paths.get(appData, "Athenis")
                : Paths.get(System.getProperty("user.home"), ".athenis");
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {}
        return dir.resolve("skins_cache.json").toFile();
    }

    private static void parseAndLoadSkins(String jsonBody) {
        JsonArray arr = JsonParser.parseString(jsonBody).getAsJsonArray();
        synchronized (weaponSkins) {
            weaponSkins.clear();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                int paintIndex = 0;
                if (obj.has("paint_index")) {
                    try {
                        paintIndex = obj.get("paint_index").getAsInt();
                    } catch (Exception ignored) {}
                }
                if (paintIndex == 0) continue;

                String name = "";
                if (obj.has("name")) {
                    name = obj.get("name").getAsString();
                }
                WeaponsEnum wType = getDefPerString(name);
                if (wType == WeaponsEnum.NONE) {
                    continue;
                }

                boolean legacy = false;
                if (obj.has("legacy_model")) {
                    legacy = obj.get("legacy_model").getAsBoolean();
                }

                weaponSkins.add(new SkinInfo(paintIndex, legacy, name, wType));
            }
        }
        System.out.println("[SkinDatabase] Successfully parsed " + weaponSkins.size() + " weapon skins!");
    }

    private static boolean loadFromCache() {
        File cacheFile = resolveCacheFile();
        if (!cacheFile.exists()) {
            System.out.println("[SkinDatabase] No cached skins database found.");
            return false;
        }
        try {
            System.out.println("[SkinDatabase] Loading skins database from local cache: " + cacheFile.getAbsolutePath());
            String jsonBody = Files.readString(cacheFile.toPath());
            parseAndLoadSkins(jsonBody);
            return true;
        } catch (Exception e) {
            System.err.println("[SkinDatabase] Failed to read or parse cached skins database: " + e.getMessage());
            return false;
        }
    }

    public static void initialize() {
        new Thread(() -> {
            try {
                System.out.println("[SkinDatabase] Fetching skins database from ByMykel's CSGO-API...");
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create("https://raw.githubusercontent.com/ByMykel/CSGO-API/main/public/api/en/skins.json"))
                        .timeout(Duration.ofSeconds(15))
                        .build();

                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    String jsonBody = resp.body();
                    // Save to local cache first
                    try {
                        File cacheFile = resolveCacheFile();
                        try (FileWriter writer = new FileWriter(cacheFile)) {
                            writer.write(jsonBody);
                        }
                        System.out.println("[SkinDatabase] Saved skin database cache locally to " + cacheFile.getAbsolutePath());
                    } catch (Exception cacheEx) {
                        System.err.println("[SkinDatabase] Failed to save skin database cache: " + cacheEx.getMessage());
                    }
                    parseAndLoadSkins(jsonBody);
                    return;
                } else {
                    System.err.println("[SkinDatabase] API response status code: " + resp.statusCode());
                }
            } catch (Exception e) {
                System.err.println("[SkinDatabase] Failed to download skins JSON: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getName()));
            }

            // Attempt to load from cache
            if (loadFromCache()) {
                return;
            }

            // Fallback to basic common skins if offline/failed and cache file is not present
            loadFallbacks();
        }, "Athenis-SkinDBLoader").start();
    }

    private static void loadFallbacks() {
        System.out.println("[SkinDatabase] Loading offline fallback skins...");
        synchronized (weaponSkins) {
            weaponSkins.clear();
            // AWP
            weaponSkins.add(new SkinInfo(344, true, "AWP | Dragon Lore", WeaponsEnum.AWP));
            weaponSkins.add(new SkinInfo(475, true, "AWP | Asiimov", WeaponsEnum.AWP));
            weaponSkins.add(new SkinInfo(1029, false, "AWP | Desert Hydra", WeaponsEnum.AWP));
            // AK-47
            weaponSkins.add(new SkinInfo(675, false, "AK-47 | The Empress", WeaponsEnum.AK47));
            weaponSkins.add(new SkinInfo(180, true, "AK-47 | Fire Serpent", WeaponsEnum.AK47));
            weaponSkins.add(new SkinInfo(44, true, "AK-47 | Case Hardened", WeaponsEnum.AK47));
            // M4A1-S
            weaponSkins.add(new SkinInfo(681, true, "M4A1-S | Nightmare", WeaponsEnum.M4A1_S));
            weaponSkins.add(new SkinInfo(949, false, "M4A1-S | Printstream", WeaponsEnum.M4A1_S));
            // M4A4
            weaponSkins.add(new SkinInfo(309, true, "M4A4 | Howl", WeaponsEnum.M4A4));
            weaponSkins.add(new SkinInfo(695, false, "M4A4 | Neo-Noir", WeaponsEnum.M4A4));
            // USP-S
            weaponSkins.add(new SkinInfo(653, true, "USP-S | Neo-Noir", WeaponsEnum.USP_S));
            weaponSkins.add(new SkinInfo(509, false, "USP-S | Kill Confirmed", WeaponsEnum.USP_S));
            // Glock-18
            weaponSkins.add(new SkinInfo(38, false, "Glock-18 | Fade", WeaponsEnum.GLOCK));
            // Desert Eagle
            weaponSkins.add(new SkinInfo(711, false, "Desert Eagle | Code Red", WeaponsEnum.DEAGLE));
            weaponSkins.add(new SkinInfo(351, false, "Desert Eagle | Conspiracy", WeaponsEnum.DEAGLE));


        }
    }

    public static List<SkinInfo> getSkinsForWeapon(WeaponsEnum type) {
        List<SkinInfo> result = new ArrayList<>();
        result.add(new SkinInfo(0, false, "Default Skin", type));
        synchronized (weaponSkins) {
            for (SkinInfo skin : weaponSkins) {
                if (skin.weaponType == type) {
                    result.add(skin);
                }
            }
        }
        return result;
    }
}
