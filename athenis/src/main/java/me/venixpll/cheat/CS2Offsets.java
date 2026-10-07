package me.venixpll.cheat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * CS2Offsets handles retrieving game offsets either dynamically from the
 * online a2x/cs2-dumper repository or falling back to a locally-cached copy.
 */
public class CS2Offsets {

    // ── Global offsets (loaded from offsets.json) ───────────────────────────────
    public static long dwEntityList = 40982568L; // 0x2715828
    public static long dwGameEntitySystem = 40982568L;
    public static long dwViewMatrix = 39217424L; // 0x2566910
    public static long dwLocalPlayerPawn = 39192216L; // 0x2560698
    public static long dwLocalPlayerController = 39026696L; // 0x2538008
    public static long dwViewAngles = 39282664L; // 0x25767E8
    public static long dwCSGOInput = 39280992L; // 0x2576160
    public static long dwGlobalVars = 35831448L; // 0x222BE98
    public static long dwForceJump = 0L;
    public static long dwPlantedC4 = 38570192L; // 0x24C88D0;
    public static long dwSensitivity = 0x255f998L;
    public static long dwSensitivity_sensitivity = 0x58L;

    // engine2.dll offsets for server tick count → current game time
    public static long dwNetworkGameClient = 9547712L;
    public static int dwNetworkGameClient_serverTickCount = 588;

    // ── Schema variable offsets (loaded from client_dll.json) ────────────────────
    public static int m_hPlayerPawn = 2348; // 0x92C
    public static int m_hObserverPawn = 2352; // 0x930
    public static int m_bPawnIsAlive = 2356; // 0x934
    public static int m_iPawnHealth = 2360; // 0x938
    public static int m_iPawnArmor = 2364; // 0x93C
    public static int m_bPawnHasDefuser = 2368; // 0x940
    public static int m_bPawnHasHelmet = 2369; // 0x941
    public static int m_iHealth = 844; // 0x34C
    public static int m_iTeamNum = 999; // 0x3E7
    public static int m_vOldOrigin = 5284; // 0x14A4
    public static int m_iszPlayerName = 1780; // 0x6FC
    public static int m_fFlags = 0x3EC;

    /** Offset to the recoil punch angle struct {pitch, yaw} inside player pawn */
    public static int m_aimPunchAngle = 0x1574;
    public static int m_pAimPunchServices = 5304; // 0x14B8

    public static int m_bSpotted = 0x8;
    public static int m_entitySpottedState = 7992; // 0x1F38
    public static int m_angEyeAngles = 0x1518;
    public static int m_vecVelocity = 1072; // 0x430

    // GameSceneNode & Model/Skeleton offsets
    public static int m_pGameSceneNode = 816; // 0x330
    public static int m_modelState = 320; // 0x140
    public static int m_vecAbsOrigin = 200; // 0xC8
    public static int m_pChild = 0x40;
    public static int m_pNextSibling = 0x48;
    public static int m_pOwner = 0x30;

    // Weapon services offsets
    public static int m_pWeaponServices = 4848; // 0x12F0
    public static int m_hMyWeapons = 0x48;
    public static int m_hActiveWeapon = 0x60;
    public static int m_pClippingWeapon = 0x3DC0;
    public static int m_hHudModelArms = 0x2400;

    // Direct pawn fields
    public static int m_flFlashMaxAlpha = 0x13FC;
    public static int m_flFlashDuration = 0x1400;
    public static int m_iProgressBarDuration = 0x13E0;
    public static int m_iShotsFired = 0x1488;
    public static int m_bIsScoped = 0x23E8;

    // Spectator / Observer offsets
    public static int m_pObserverServices = 0x1118;
    public static int m_hObserverTarget = 0x44;

    // CCSPlayerController_InGameMoneyServices (money)
    public static int m_pInGameMoneyServices_ctrl = 2056; // 0x808
    public static int m_iAccount = 64; // 0x40

    // Attribute offsets
    public static int m_pInventoryServices = 0x810;
    public static int m_unMusicID = 0x58;
    public static int m_nFallbackPaintKit = 0x1850;
    public static int m_AttributeManager = 0x1378;
    public static int m_Item = 0x50;
    public static int m_AttributeList = 0x208;
    public static int m_Attributes = 0x8;
    public static int m_iItemDefinitionIndex = 0x1BA;
    public static int m_iItemIDHigh = 0x1D0;
    public static int m_MeshGroupMask = 0x220;
    public static int m_hOwnerEntity = 0x528;
    public static int m_nSubclassID = 0x3B8;

    // Planted C4 offsets
    public static int m_flC4Blow = 0xEB4;
    public static int m_bBombTicking = 0xEC0;
    public static int m_flDetonateTime = 4448; // 0x1160

    // ── Remote URLs ─────────────────────────────────────────────────────────────
    private static final String OFFSETS_URL    = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/offsets.json";
    private static final String CLIENT_DLL_URL = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/client_dll.json";
    private static final String BUTTONS_URL    = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/buttons.json";

    // ── Cache file names ────────────────────────────────────────────────────────
    private static final String CACHE_OFFSETS    = "offsets.json";
    private static final String CACHE_CLIENT_DLL = "client_dll.json";
    private static final String CACHE_BUTTONS    = "buttons.json";
    private static final String CACHE_CHECKSUMS  = "offsets_checksums.json";

    public static void load() {
        if (me.venixpll.config.ConfigManager.offsetsFolder != null && !me.venixpll.config.ConfigManager.offsetsFolder.trim().isEmpty()) {
            if (loadFromFolder(me.venixpll.config.ConfigManager.offsetsFolder)) {
                return;
            }
        }

        System.out.println("[CS2Offsets] Attempting to download latest offsets from GitHub...");

        Path cacheDir = getCacheDir();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException ignored) {
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            String offsetsJson    = fetch(client, OFFSETS_URL);
            String clientDllJson  = fetch(client, CLIENT_DLL_URL);
            String buttonsJson    = fetch(client, BUTTONS_URL);

            String newOffsetsCsum    = sha256(offsetsJson);
            String newClientDllCsum  = sha256(clientDllJson);
            String newButtonsCsum    = sha256(buttonsJson);

            JsonObject storedChecksums = loadStoredChecksums(cacheDir);
            boolean offsetsChanged    = !newOffsetsCsum.equals(getChecksum(storedChecksums, "offsets"));
            boolean clientDllChanged  = !newClientDllCsum.equals(getChecksum(storedChecksums, "client_dll"));
            boolean buttonsChanged    = !newButtonsCsum.equals(getChecksum(storedChecksums, "buttons"));

            if (offsetsChanged || clientDllChanged || buttonsChanged) {
                System.out.println("[CS2Offsets] New offsets detected — updating local cache...");
                writeCacheFile(cacheDir, CACHE_OFFSETS,    offsetsJson);
                writeCacheFile(cacheDir, CACHE_CLIENT_DLL, clientDllJson);
                writeCacheFile(cacheDir, CACHE_BUTTONS,    buttonsJson);
                saveChecksums(cacheDir, newOffsetsCsum, newClientDllCsum, newButtonsCsum);
            } else {
                System.out.println("[CS2Offsets] Offsets are up-to-date (checksums match).");
            }

            parseOffsets(offsetsJson);
            parseClientDll(clientDllJson);
            parseButtons(buttonsJson);
            System.out.println("[CS2Offsets] Successfully loaded latest offsets from a2x/cs2-dumper!");
            logOffsets();

            if (!sanityCheck(cacheDir)) {
                return;
            }
            return;

        } catch (Exception e) {
            System.err.println("[CS2Offsets] Could not download offsets: " + e.getMessage());
        }

        System.out.println("[CS2Offsets] Trying cached offsets from disk...");
        if (loadFromCache(cacheDir)) {
            System.out.println("[CS2Offsets] Loaded offsets from local cache (offline mode).");
            logOffsets();
            sanityCheck(cacheDir);
            return;
        }

        System.out.println("[CS2Offsets] No cache available — using built-in hardcoded offsets.");
        logOffsets();
    }

    private static boolean sanityCheck(Path cacheDir) {
        final long MIN_SANE_OFFSET = 0x100_000L;
        final long MAX_SANE_OFFSET = 0x400_000_000L;

        boolean sane = true;
        StringBuilder issues = new StringBuilder();

        if (dwEntityList < MIN_SANE_OFFSET || dwEntityList > MAX_SANE_OFFSET) {
            issues.append("\n  dwEntityList=0x").append(Long.toHexString(dwEntityList)).append(" is out of range");
            sane = false;
        }
        if (dwLocalPlayerPawn < MIN_SANE_OFFSET || dwLocalPlayerPawn > MAX_SANE_OFFSET) {
            issues.append("\n  dwLocalPlayerPawn=0x").append(Long.toHexString(dwLocalPlayerPawn)).append(" is out of range");
            sane = false;
        }
        if (dwViewMatrix < MIN_SANE_OFFSET || dwViewMatrix > MAX_SANE_OFFSET) {
            issues.append("\n  dwViewMatrix=0x").append(Long.toHexString(dwViewMatrix)).append(" is out of range");
            sane = false;
        }

        if (!sane) {
            System.err.println("[CS2Offsets] *** SANITY CHECK FAILED — offsets look stale or corrupted! ***" + issues);
            try {
                Files.deleteIfExists(cacheDir.resolve(CACHE_CHECKSUMS));
                Files.deleteIfExists(cacheDir.resolve(CACHE_OFFSETS));
                Files.deleteIfExists(cacheDir.resolve(CACHE_CLIENT_DLL));
                Files.deleteIfExists(cacheDir.resolve(CACHE_BUTTONS));
            } catch (IOException ignored) {}
        } else {
            System.out.println("[CS2Offsets] Sanity check passed. dwEntityList=0x" + Long.toHexString(dwEntityList)
                    + " dwLocalPlayerPawn=0x" + Long.toHexString(dwLocalPlayerPawn));
        }
        return sane;
    }

    public static boolean loadFromFolder(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty()) return false;
        try {
            Path folder = Paths.get(pathStr);
            Path offsetsFile   = folder.resolve(CACHE_OFFSETS);
            Path clientDllFile = folder.resolve(CACHE_CLIENT_DLL);
            Path buttonsFile   = folder.resolve(CACHE_BUTTONS);

            if (!Files.exists(offsetsFile) || !Files.exists(clientDllFile) || !Files.exists(buttonsFile)) {
                return false;
            }

            parseOffsets(Files.readString(offsetsFile, StandardCharsets.UTF_8));
            parseClientDll(Files.readString(clientDllFile, StandardCharsets.UTF_8));
            parseButtons(Files.readString(buttonsFile, StandardCharsets.UTF_8));
            logOffsets();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String fetch(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonObject loadStoredChecksums(Path cacheDir) {
        Path file = cacheDir.resolve(CACHE_CHECKSUMS);
        if (!Files.exists(file)) return new JsonObject();
        try {
            return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    private static String getChecksum(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    private static void saveChecksums(Path cacheDir, String offsets, String clientDll, String buttons) {
        JsonObject obj = new JsonObject();
        obj.addProperty("offsets", offsets);
        obj.addProperty("client_dll", clientDll);
        obj.addProperty("buttons", buttons);
        writeCacheFile(cacheDir, CACHE_CHECKSUMS, obj.toString());
    }

    private static Path getCacheDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            return Paths.get(appData, "Athenis");
        }
        return Paths.get(System.getProperty("user.home"), ".config", "Athenis");
    }

    private static void writeCacheFile(Path cacheDir, String filename, String content) {
        try {
            Files.writeString(cacheDir.resolve(filename), content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    private static boolean loadFromCache(Path cacheDir) {
        Path offsetsFile   = cacheDir.resolve(CACHE_OFFSETS);
        Path clientDllFile = cacheDir.resolve(CACHE_CLIENT_DLL);
        Path buttonsFile   = cacheDir.resolve(CACHE_BUTTONS);

        if (!Files.exists(offsetsFile) || !Files.exists(clientDllFile) || !Files.exists(buttonsFile)) {
            return false;
        }

        try {
            parseOffsets(Files.readString(offsetsFile, StandardCharsets.UTF_8));
            parseClientDll(Files.readString(clientDllFile, StandardCharsets.UTF_8));
            parseButtons(Files.readString(buttonsFile, StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void parseOffsets(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject clientOffsets = obj.getAsJsonObject("client.dll");

        dwEntityList       = clientOffsets.get("dwEntityList").getAsLong();
        if (clientOffsets.has("dwGameEntitySystem")) {
            dwGameEntitySystem = clientOffsets.get("dwGameEntitySystem").getAsLong();
        }
        dwViewMatrix       = clientOffsets.get("dwViewMatrix").getAsLong();
        dwLocalPlayerPawn  = clientOffsets.get("dwLocalPlayerPawn").getAsLong();
        if (clientOffsets.has("dwLocalPlayerController")) {
            dwLocalPlayerController = clientOffsets.get("dwLocalPlayerController").getAsLong();
        }
        if (clientOffsets.has("dwViewAngles")) {
            dwViewAngles = clientOffsets.get("dwViewAngles").getAsLong();
        }
        if (clientOffsets.has("dwCSGOInput")) {
            dwCSGOInput = clientOffsets.get("dwCSGOInput").getAsLong();
        }
        if (clientOffsets.has("dwGlobalVars")) {
            dwGlobalVars = clientOffsets.get("dwGlobalVars").getAsLong();
        }
        if (clientOffsets.has("dwPlantedC4")) {
            dwPlantedC4 = clientOffsets.get("dwPlantedC4").getAsLong();
        }
        if (clientOffsets.has("dwSensitivity")) {
            dwSensitivity = clientOffsets.get("dwSensitivity").getAsLong();
        }
        if (clientOffsets.has("dwSensitivity_sensitivity")) {
            dwSensitivity_sensitivity = clientOffsets.get("dwSensitivity_sensitivity").getAsLong();
        }

        if (obj.has("engine2.dll")) {
            JsonObject engine2Offsets = obj.getAsJsonObject("engine2.dll");
            if (engine2Offsets.has("dwNetworkGameClient")) {
                dwNetworkGameClient = engine2Offsets.get("dwNetworkGameClient").getAsLong();
            }
            if (engine2Offsets.has("dwNetworkGameClient_serverTickCount")) {
                dwNetworkGameClient_serverTickCount = engine2Offsets.get("dwNetworkGameClient_serverTickCount").getAsInt();
            }
        }
    }

    private static void parseClientDll(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject classes = obj.getAsJsonObject("client.dll").getAsJsonObject("classes");

        m_hPlayerPawn = getField(classes, "CCSPlayerController", "m_hPlayerPawn", 2348);
        m_hObserverPawn = getField(classes, "CCSPlayerController", "m_hObserverPawn", 2352);
        m_bPawnIsAlive = getField(classes, "CCSPlayerController", "m_bPawnIsAlive", 2356);
        m_iPawnHealth = getField(classes, "CCSPlayerController", "m_iPawnHealth", 2360);
        m_iPawnArmor = getField(classes, "CCSPlayerController", "m_iPawnArmor", 2364);
        m_bPawnHasDefuser = getField(classes, "CCSPlayerController", "m_bPawnHasDefuser", 2368);
        m_bPawnHasHelmet = getField(classes, "CCSPlayerController", "m_bPawnHasHelmet", 2369);

        m_iHealth = getField(classes, "C_BaseEntity", "m_iHealth", 844);
        m_iTeamNum = getField(classes, "C_BaseEntity", "m_iTeamNum", 999);
        m_vOldOrigin = getField(classes, "C_BasePlayerPawn", "m_vOldOrigin", 5284);
        m_iszPlayerName = getField(classes, "CBasePlayerController", "m_iszPlayerName", 1780);
        m_fFlags = getField(classes, "C_BaseEntity", "m_fFlags", 0x3EC);

        m_pAimPunchServices = getField(classes, "C_CSPlayerPawn", "m_pAimPunchServices",
                              getField(classes, "C_CSPlayerPawnBase", "m_pAimPunchServices", 5304));
        m_aimPunchAngle     = getField(classes, "C_CSPlayerPawn", "m_aimPunchAngle",
                              getField(classes, "C_CSPlayerPawnBase", "m_aimPunchAngle", 0x1574));

        m_entitySpottedState = getField(classes, "C_CSPlayerPawn", "m_entitySpottedState", 7992);
        m_bSpotted = 0x8;

        m_angEyeAngles = getField(classes, "C_CSPlayerPawn", "m_angEyeAngles",
                         getField(classes, "C_CSPlayerPawnBase", "m_angEyeAngles", 0x1518));

        m_pWeaponServices = getField(classes, "C_BasePlayerPawn", "m_pWeaponServices", 4848);
        m_vecVelocity = getField(classes, "C_BaseEntity", "m_vecVelocity", 1072);
        m_hMyWeapons = getField(classes, "CPlayer_WeaponServices", "m_hMyWeapons", 0x48);
        m_pGameSceneNode = getField(classes, "C_BaseEntity", "m_pGameSceneNode", 816);
        m_modelState = getField(classes, "CSkeletonInstance", "m_modelState", 320);

        m_flC4Blow = getField(classes, "C_PlantedC4", "m_flC4Blow", 0xEB4);
        m_bBombTicking = getField(classes, "C_PlantedC4", "m_bBombTicking", 0xEC0);
        m_flDetonateTime = getField(classes, "C_BaseCSGrenadeProjectile", "m_flDetonateTime",
                           getField(classes, "C_BaseGrenade", "m_flDetonateTime", 4448));

        m_pInventoryServices = getField(classes, "CCSPlayerController", "m_pInventoryServices", 0x810);
        m_unMusicID          = getField(classes, "CCSPlayerController_InventoryServices", "m_unMusicID", 0x58);
        m_pClippingWeapon    = getField(classes, "C_CSPlayerPawn", "m_pClippingWeapon", 0x3DC0);
        m_hHudModelArms      = getField(classes, "C_CSPlayerPawn", "m_hHudModelArms", 0x2400);
        m_hOwnerEntity       = getField(classes, "C_BaseEntity", "m_hOwnerEntity", 0x528);
        m_hActiveWeapon      = getField(classes, "CPlayer_WeaponServices", "m_hActiveWeapon", 0x60);
        m_pChild             = getField(classes, "CGameSceneNode", "m_pChild", 0x40);
        m_pNextSibling       = getField(classes, "CGameSceneNode", "m_pNextSibling", 0x48);
        m_pOwner             = getField(classes, "CGameSceneNode", "m_pOwner", 0x30);
        m_MeshGroupMask      = getField(classes, "CModelState", "m_MeshGroupMask", 0x220);
        m_nFallbackPaintKit  = getField(classes, "C_EconEntity", "m_nFallbackPaintKit", 0x1850);
        m_AttributeManager   = getField(classes, "C_EconEntity", "m_AttributeManager", 0x1378);
        m_Item               = getField(classes, "C_AttributeContainer", "m_Item", 0x50);
        m_AttributeList      = getField(classes, "C_EconItemView", "m_AttributeList", 0x208);
        m_Attributes         = getField(classes, "CAttributeList", "m_Attributes", 0x8);
        m_iItemDefinitionIndex = getField(classes, "C_EconItemView", "m_iItemDefinitionIndex", 0x1BA);
        m_iItemIDHigh        = getField(classes, "C_EconItemView", "m_iItemIDHigh", 0x1D0);
        m_nSubclassID        = getField(classes, "C_BaseEntity", "m_nSubclassID", 0x3B8);

        m_flFlashMaxAlpha      = getField(classes, "C_CSPlayerPawnBase", "m_flFlashMaxAlpha",     0x13FC);
        m_flFlashDuration      = getField(classes, "C_CSPlayerPawnBase", "m_flFlashDuration",     0x1400);
        m_iProgressBarDuration = getField(classes, "C_CSPlayerPawnBase", "m_iProgressBarDuration", 0x13E0);
        m_iShotsFired          = getField(classes, "C_CSPlayerPawn", "m_iShotsFired",
                                 getField(classes, "C_CSPlayerPawnBase", "m_iShotsFired", 7308));

        m_pObserverServices = getField(classes, "C_BasePlayerPawn", "m_pObserverServices", 0x1118);
        m_hObserverTarget   = getField(classes, "CPlayer_ObserverServices", "m_hObserverTarget", 0x44);

        m_iAccount = getField(classes, "CCSPlayerController_InGameMoneyServices", "m_iAccount", 64);
        m_pInGameMoneyServices_ctrl = getField(classes, "CCSPlayerController", "m_pInGameMoneyServices", 2056);
    }

    private static int getField(JsonObject classes, String className, String fieldName, int defaultVal) {
        if (classes.has(className)) {
            JsonObject c = classes.getAsJsonObject(className);
            if (c.has("fields")) {
                JsonObject fields = c.getAsJsonObject("fields");
                if (fields.has(fieldName)) {
                    return fields.get(fieldName).getAsInt();
                }
            }
        }
        return defaultVal;
    }

    private static void parseButtons(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject clientButtons = obj.getAsJsonObject("client.dll");
        if (clientButtons.has("jump")) {
            dwForceJump = clientButtons.get("jump").getAsLong();
        }
    }

    private static void logOffsets() {
        System.out.println(String.format("  > dwEntityList:          0x%X", dwEntityList));
        System.out.println(String.format("  > dwViewMatrix:          0x%X", dwViewMatrix));
        System.out.println(String.format("  > dwLocalPlayerPawn:     0x%X", dwLocalPlayerPawn));
        System.out.println(String.format("  > dwViewAngles:          0x%X", dwViewAngles));
        System.out.println(String.format("  > dwCSGOInput:           0x%X", dwCSGOInput));
        System.out.println(String.format("  > dwGlobalVars:          0x%X", dwGlobalVars));
        System.out.println(String.format("  > dwForceJump:           0x%X", dwForceJump));
        System.out.println(String.format("  > m_hPlayerPawn:         0x%X", m_hPlayerPawn));
        System.out.println(String.format("  > m_iHealth:             0x%X", m_iHealth));
        System.out.println(String.format("  > m_iTeamNum:            0x%X", m_iTeamNum));
        System.out.println(String.format("  > m_vOldOrigin:          0x%X", m_vOldOrigin));
        System.out.println(String.format("  > m_iszPlayerName:       0x%X", m_iszPlayerName));
        System.out.println(String.format("  > m_fFlags:              0x%X", m_fFlags));
        System.out.println(String.format("  > m_aimPunchAngle:       0x%X", m_aimPunchAngle));
        System.out.println(String.format("  > m_bSpotted:            0x%X", m_bSpotted));
        System.out.println(String.format("  > m_pWeaponServices:     0x%X", m_pWeaponServices));
        System.out.println(String.format("  > m_hMyWeapons:          0x%X", m_hMyWeapons));
        System.out.println(String.format("  > m_vecVelocity:         0x%X", m_vecVelocity));
        System.out.println(String.format("  > m_pGameSceneNode:      0x%X", m_pGameSceneNode));
        System.out.println(String.format("  > m_modelState:          0x%X", m_modelState));
        System.out.println(String.format("  > dwPlantedC4:           0x%X", dwPlantedC4));
        System.out.println(String.format("  > m_flC4Blow:            0x%X", m_flC4Blow));
        System.out.println(String.format("  > m_bBombTicking:        0x%X", m_bBombTicking));
        System.out.println(String.format("  > m_flDetonateTime:      0x%X", m_flDetonateTime));
        System.out.println(String.format("  > m_flFlashMaxAlpha:     0x%X", m_flFlashMaxAlpha));
        System.out.println(String.format("  > m_flFlashDuration:     0x%X", m_flFlashDuration));
        System.out.println(String.format("  > m_iProgressBarDuration:0x%X", m_iProgressBarDuration));
        System.out.println(String.format("  > m_bIsScoped:           0x%X", m_bIsScoped));
        System.out.println(String.format("  > m_bPawnHasDefuser:     0x%X", m_bPawnHasDefuser));
        System.out.println(String.format("  > m_pObserverServices:   0x%X", m_pObserverServices));
        System.out.println(String.format("  > m_hObserverTarget:     0x%X", m_hObserverTarget));
        System.out.println(String.format("  > m_pInGameMoneySvc:     0x%X", m_pInGameMoneyServices_ctrl));
        System.out.println(String.format("  > m_iAccount:            0x%X", m_iAccount));
    }
}
