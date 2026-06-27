package me.venixpll.cheat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;

/**
 * CS2Offsets handles retrieving game offsets either dynamically from the
 * online a2x/cs2-dumper repository or falling back to a locally-cached copy.
 *
 * <h3>Update strategy</h3>
 * <ol>
 * <li>Attempt to download {@code offsets.json}, {@code client_dll.json} and
 * {@code buttons.json} from GitHub.</li>
 * <li>Compute SHA-256 of each downloaded body and compare with the
 * checksums stored in {@code offsets_checksums.json} next to the cached
 * files.</li>
 * <li>If any checksum differs → write the new files to disk, update the
 * checksum file, and parse the fresh data.</li>
 * <li>If checksums are identical → the local cache is already current; parse
 * the cached files (avoids redundant parsing on every launch).</li>
 * <li>If the network is unreachable → fall back to the last cached files on
 * disk. If no cache exists either → use the built-in hardcoded defaults.</li>
 * </ol>
 *
 * Cache directory: {@code %APPDATA%\Athenis\} (Windows) or
 * {@code ~/.config/Athenis/} (other OS).
 */
public class CS2Offsets {

    // ── Global offsets (loaded from offsets.json) ─────────────────────────────
    public static long dwEntityList = 38688144L;
    public static long dwViewMatrix = 36981552L;
    public static long dwLocalPlayerPawn = 36959896L;
    public static long dwLocalPlayerController = 36833056L;
    /** Offset to local player view angle struct {pitch, yaw, roll} in client.dll */
    public static long dwViewAngles = 37034408L;
    public static long dwGlobalVars = 0L;
    public static long dwForceJump = 33972128L;
    public static long dwPlantedC4 = 0L;

    // engine2.dll offsets for server tick count → current game time
    public static long dwNetworkGameClient = 9478560L;
    public static int dwNetworkGameClient_serverTickCount = 588;

    // ── Schema variable offsets (loaded from client_dll.json) ─────────────────
    public static int m_hPlayerPawn = 2316;
    public static int m_hObserverPawn = 2320;
    public static int m_iHealth = 844;
    public static int m_iTeamNum = 1003;
    public static int m_vOldOrigin = 5008;
    public static int m_iszPlayerName = 1780;
    public static int m_fFlags = 0x3EC;
    /** Offset to the recoil punch angle struct {pitch, yaw} inside player pawn */
    public static int m_aimPunchAngle = 5296;
    /**
     * Offset to EntitySpottedState_t::m_bSpotted.
     * When true the game has determined at least one enemy can see this entity.
     */
    public static int m_bSpotted = 0x8;
    public static int m_entitySpottedState = 7224;
    /** Offset to the view angles struct {pitch, yaw, roll} inside player pawn */
    public static int m_angEyeAngles = 0x1518;
    /**
     * Velocity vector of the player pawn (3 floats: vx, vy, vz in units/sec).
     */
    public static int m_vecVelocity = 0x3C8;

    // GameSceneNode & Model/Skeleton offsets
    public static int m_pGameSceneNode = 0x310;
    public static int m_modelState = 0x160;
    /** Absolute world-space origin inside CGameSceneNode. */
    public static int m_vecAbsOrigin = 200; // 0xC8
    public static int m_pChild = 0x40;
    public static int m_pNextSibling = 0x48;
    public static int m_pOwner = 0x30;

    // Weapon services offsets
    public static int m_pWeaponServices = 0x13D8;
    public static int m_hMyWeapons = 0x48;
    public static int m_hActiveWeapon = 0x60;
    public static int m_pClippingWeapon = 0x3DC0;
    public static int m_hHudModelArms = 0x2400;

    // ── Player Flag offsets ───────────────────────────────────────────────────
    // ── Direct fields on the player pawn ──────────────────────────────────────

    /**
     * Maximum flash alpha on the pawn’s screen (C_CSPlayerPawnBase::m_flFlashMaxAlpha).
     * Range: 0.0 (not flashed) to 255.0 (fully blinded).
     * Direct pawn field at offset 0x13FC — no pointer indirection.
     */
    public static int m_flFlashMaxAlpha = 0x13FC;

    /**
     * Duration of the flashbang effect in seconds (C_CSPlayerPawnBase::m_flFlashDuration).
     * 0 when not flashed. Together with m_flFlashMaxAlpha this determines if a player is blind.
     * Direct pawn field at offset 0x1400.
     */
    public static int m_flFlashDuration = 0x1400;

    /**
     * Duration of the progress bar shown during planting/defusing (C_CSPlayerPawnBase::m_iProgressBarDuration).
     * Value is 5 when defusing without a kit and 10 with a kit (in server ticks or seconds),
     * and 0 when no progress bar is active. Non-zero means the player is defusing or planting.
     * Direct pawn field at offset 0x13E0 — no pointer indirection.
     */
    public static int m_iProgressBarDuration = 0x13E0;

    /**
     * Boolean flag on the player pawn indicating if they are currently scoped/zoomed in (C_CSPlayerPawn::m_bIsScoped).
     * Direct pawn field at offset 0x23E8.
     */
    public static int m_bIsScoped = 0x23E8;

    // ── Spectator / Observer offsets ──────────────────────────────────────────

    /**
     * Pointer from player pawn to CPlayer_ObserverServices (C_BasePlayerPawn::m_pObserverServices).
     * Used to access spectated targets. Typical offset: 0x1118 or 0x1120.
     */
    public static int m_pObserverServices = 0x1118;

    /**
     * Handle of the entity currently being spectated (CPlayer_ObserverServices::m_hObserverTarget).
     * Located inside the observer services struct. Typical offset: 0x44.
     */
    public static int m_hObserverTarget = 0x44;

    // ── Direct fields on the player controller ────────────────────────────────

    /**
     * Boolean flag on the player controller indicating if their pawn has a defuse kit (CCSPlayerController::m_bPawnHasDefuser).
     * Direct controller field at offset 2336 (0x920).
     */
    public static int m_bPawnHasDefuser = 2336;

    /**
     * Pointer from CCSPlayerController to CCSPlayerController_InGameMoneyServices.
     * Used to access the player's current cash (m_iAccount).
     */
    public static int m_pInGameMoneyServices_ctrl = 2056; // 0x808

    /**
     * Offset of m_iAccount inside CCSPlayerController_InGameMoneyServices.
     * The player's current in-game cash balance ($).
     */
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

    // Grenade projectile offsets (C_BaseGrenade)
    /**
     * Server-time timestamp (float, seconds) at which a live grenade will detonate.
     * Inherited by all CS2 projectile subclasses (HE, flashbang, smoke, molotov,
     * decoy).
     * Offset within the C_BaseGrenade class layout.
     */
    public static int m_flDetonateTime = 4448; // 0x1160

    // ── Remote URLs ───────────────────────────────────────────────────────────
    private static final String OFFSETS_URL = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/offsets.json";
    private static final String CLIENT_DLL_URL = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/client_dll.json";
    private static final String BUTTONS_URL = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/buttons.json";

    // ── Cache file names ──────────────────────────────────────────────────────
    private static final String CACHE_OFFSETS = "offsets.json";
    private static final String CACHE_CLIENT_DLL = "client_dll.json";
    private static final String CACHE_BUTTONS = "buttons.json";
    private static final String CACHE_CHECKSUMS = "offsets_checksums.json";

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Initialises offsets by attempting to download the latest data from GitHub.
     * Compares checksums to avoid redundant writes. Falls back to cached files
     * or hardcoded defaults when the network is unavailable.
     */
    public static void load() {
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

            // ── Fetch all three JSON files ─────────────────────────────────
            String offsetsJson = fetch(client, OFFSETS_URL);
            String clientDllJson = fetch(client, CLIENT_DLL_URL);
            String buttonsJson = fetch(client, BUTTONS_URL);

            // ── Compute SHA-256 checksums ──────────────────────────────────
            String newOffsetsCsum = sha256(offsetsJson);
            String newClientDllCsum = sha256(clientDllJson);
            String newButtonsCsum = sha256(buttonsJson);

            // ── Compare with previously saved checksums ────────────────────
            JsonObject storedChecksums = loadStoredChecksums(cacheDir);
            boolean offsetsChanged = !newOffsetsCsum.equals(getChecksum(storedChecksums, "offsets"));
            boolean clientDllChanged = !newClientDllCsum.equals(getChecksum(storedChecksums, "client_dll"));
            boolean buttonsChanged = !newButtonsCsum.equals(getChecksum(storedChecksums, "buttons"));

            if (offsetsChanged || clientDllChanged || buttonsChanged) {
                System.out.println("[CS2Offsets] New offsets detected — updating local cache...");
                writeCacheFile(cacheDir, CACHE_OFFSETS, offsetsJson);
                writeCacheFile(cacheDir, CACHE_CLIENT_DLL, clientDllJson);
                writeCacheFile(cacheDir, CACHE_BUTTONS, buttonsJson);
                saveChecksums(cacheDir, newOffsetsCsum, newClientDllCsum, newButtonsCsum);
            } else {
                System.out.println("[CS2Offsets] Offsets are up-to-date (checksums match).");
            }

            // ── Parse the fresh data ───────────────────────────────────────
            parseOffsets(offsetsJson);
            parseClientDll(clientDllJson);
            parseButtons(buttonsJson);
            System.out.println("[CS2Offsets] Successfully loaded latest offsets from a2x/cs2-dumper!");
            logOffsets();
            return;

        } catch (Exception e) {
            System.err.println("[CS2Offsets] Could not download offsets: " + e.getMessage());
        }

        // ── Offline fallback: load from cached files ───────────────────────
        System.out.println("[CS2Offsets] Trying cached offsets from disk...");
        if (loadFromCache(cacheDir)) {
            System.out.println("[CS2Offsets] Loaded offsets from local cache (offline mode).");
            logOffsets();
            return;
        }

        // ── Last resort: built-in hardcoded defaults ───────────────────────
        System.out.println("[CS2Offsets] No cache available — using built-in hardcoded offsets.");
        logOffsets();
    }

    // ── Network helpers ───────────────────────────────────────────────────────

    /**
     * Sends a GET request and returns the response body string.
     *
     * @throws IOException if the status is not 200 or another error occurs.
     */
    private static String fetch(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + url);
        }
        return resp.body();
    }

    // ── Checksum helpers ──────────────────────────────────────────────────────

    /**
     * Returns the SHA-256 hex digest of {@code text} (UTF-8 encoded).
     */
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

    /**
     * Reads the stored checksum JSON from disk and returns it, or an empty object
     * if the file does not exist or cannot be parsed.
     */
    private static JsonObject loadStoredChecksums(Path cacheDir) {
        Path file = cacheDir.resolve(CACHE_CHECKSUMS);
        if (!Files.exists(file))
            return new JsonObject();
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    /**
     * Extracts a named checksum string from the stored object, or {@code ""}
     * if absent.
     */
    private static String getChecksum(JsonObject obj, String key) {
        if (obj.has(key))
            return obj.get(key).getAsString();
        return "";
    }

    /**
     * Persists the three computed checksums to {@code offsets_checksums.json}.
     */
    private static void saveChecksums(Path cacheDir, String offsets, String clientDll, String buttons) {
        JsonObject obj = new JsonObject();
        obj.addProperty("offsets", offsets);
        obj.addProperty("client_dll", clientDll);
        obj.addProperty("buttons", buttons);
        writeCacheFile(cacheDir, CACHE_CHECKSUMS, obj.toString());
    }

    // ── Cache I/O helpers ─────────────────────────────────────────────────────

    /**
     * Returns the platform-appropriate cache directory:
     * {@code %APPDATA%\Athenis\} on Windows, {@code ~/.config/Athenis/} elsewhere.
     */
    private static Path getCacheDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            return Paths.get(appData, "Athenis");
        }
        return Paths.get(System.getProperty("user.home"), ".config", "Athenis");
    }

    /**
     * Writes {@code content} to {@code cacheDir/filename}, creating parent
     * directories as needed. Errors are logged but not rethrown.
     */
    private static void writeCacheFile(Path cacheDir, String filename, String content) {
        try {
            Files.writeString(cacheDir.resolve(filename), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("[CS2Offsets] Failed to write cache file '" + filename + "': " + e.getMessage());
        }
    }

    /**
     * Reads all three cached JSON files from {@code cacheDir} and parses them.
     *
     * @return {@code true} if all files were present and parsed successfully.
     */
    private static boolean loadFromCache(Path cacheDir) {
        Path offsetsFile = cacheDir.resolve(CACHE_OFFSETS);
        Path clientDllFile = cacheDir.resolve(CACHE_CLIENT_DLL);
        Path buttonsFile = cacheDir.resolve(CACHE_BUTTONS);

        if (!Files.exists(offsetsFile) || !Files.exists(clientDllFile) || !Files.exists(buttonsFile)) {
            System.err.println("[CS2Offsets] Cache files missing — cannot use offline fallback.");
            return false;
        }

        try {
            String offsetsJson = Files.readString(offsetsFile, StandardCharsets.UTF_8);
            String clientDllJson = Files.readString(clientDllFile, StandardCharsets.UTF_8);
            String buttonsJson = Files.readString(buttonsFile, StandardCharsets.UTF_8);

            parseOffsets(offsetsJson);
            parseClientDll(clientDllJson);
            parseButtons(buttonsJson);
            return true;
        } catch (Exception e) {
            System.err.println("[CS2Offsets] Failed to parse cached files: " + e.getMessage());
            return false;
        }
    }

    // ── JSON parsers ──────────────────────────────────────────────────────────

    /**
     * Parses the global offsets from offsets.json content.
     *
     * @param json Content of the offsets.json file.
     */
    private static void parseOffsets(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject clientOffsets = obj.getAsJsonObject("client.dll");

        dwEntityList = clientOffsets.get("dwEntityList").getAsLong();
        dwViewMatrix = clientOffsets.get("dwViewMatrix").getAsLong();
        dwLocalPlayerPawn = clientOffsets.get("dwLocalPlayerPawn").getAsLong();
        if (clientOffsets.has("dwLocalPlayerController")) {
            dwLocalPlayerController = clientOffsets.get("dwLocalPlayerController").getAsLong();
        }
        if (clientOffsets.has("dwViewAngles")) {
            dwViewAngles = clientOffsets.get("dwViewAngles").getAsLong();
        }
        if (clientOffsets.has("dwGlobalVars")) {
            dwGlobalVars = clientOffsets.get("dwGlobalVars").getAsLong();
        }
        if (clientOffsets.has("dwPlantedC4")) {
            dwPlantedC4 = clientOffsets.get("dwPlantedC4").getAsLong();
        }

        // engine2.dll offsets for server tick → current game time
        if (obj.has("engine2.dll")) {
            JsonObject engine2Offsets = obj.getAsJsonObject("engine2.dll");
            if (engine2Offsets.has("dwNetworkGameClient")) {
                dwNetworkGameClient = engine2Offsets.get("dwNetworkGameClient").getAsLong();
            }
            if (engine2Offsets.has("dwNetworkGameClient_serverTickCount")) {
                dwNetworkGameClient_serverTickCount = engine2Offsets.get("dwNetworkGameClient_serverTickCount")
                        .getAsInt();
            }
        }
    }

    /**
     * Parses class and field offsets from client_dll.json content.
     *
     * @param json Content of the client_dll.json file.
     */
    private static void parseClientDll(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject classes = obj.getAsJsonObject("client.dll").getAsJsonObject("classes");

        m_hPlayerPawn = classes.getAsJsonObject("CCSPlayerController")
                .getAsJsonObject("fields")
                .get("m_hPlayerPawn").getAsInt();

        if (classes.getAsJsonObject("CCSPlayerController").getAsJsonObject("fields").has("m_hObserverPawn")) {
            m_hObserverPawn = classes.getAsJsonObject("CCSPlayerController")
                    .getAsJsonObject("fields")
                    .get("m_hObserverPawn").getAsInt();
        }

        m_iHealth = classes.getAsJsonObject("C_BaseEntity")
                .getAsJsonObject("fields")
                .get("m_iHealth").getAsInt();

        m_iTeamNum = classes.getAsJsonObject("C_BaseEntity")
                .getAsJsonObject("fields")
                .get("m_iTeamNum").getAsInt();

        m_vOldOrigin = classes.getAsJsonObject("C_BasePlayerPawn")
                .getAsJsonObject("fields")
                .get("m_vOldOrigin").getAsInt();

        m_iszPlayerName = classes.getAsJsonObject("CBasePlayerController")
                .getAsJsonObject("fields")
                .get("m_iszPlayerName").getAsInt();

        if (classes.has("C_BaseEntity") &&
                classes.getAsJsonObject("C_BaseEntity").getAsJsonObject("fields").has("m_fFlags")) {
            m_fFlags = classes.getAsJsonObject("C_BaseEntity")
                    .getAsJsonObject("fields")
                    .get("m_fFlags").getAsInt();
        }

        // Aim punch angle (recoil compensation)
        if (classes.has("C_CSPlayerPawnBase") &&
                classes.getAsJsonObject("C_CSPlayerPawnBase").getAsJsonObject("fields").has("m_aimPunchAngle")) {
            m_aimPunchAngle = classes.getAsJsonObject("C_CSPlayerPawnBase")
                    .getAsJsonObject("fields")
                    .get("m_aimPunchAngle").getAsInt();
        }

        // Spotted state
        if (classes.has("EntitySpottedState_t")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("EntitySpottedState_t")
                    .getAsJsonObject("fields");
            if (fields != null && fields.has("m_bSpotted")) {
                m_bSpotted = fields.get("m_bSpotted").getAsInt();
            } else {
                m_bSpotted = 0x8;
            }
        } else {
            String[] spottedCandidates = { "C_CSPlayerPawn", "C_CSPlayerPawnBase", "C_BaseEntity" };
            for (String cls : spottedCandidates) {
                if (classes.has(cls)) {
                    com.google.gson.JsonObject fields = classes.getAsJsonObject(cls).getAsJsonObject("fields");
                    if (fields != null && fields.has("m_bSpotted")) {
                        m_bSpotted = fields.get("m_bSpotted").getAsInt();
                        break;
                    }
                }
            }
        }

        m_entitySpottedState = classes.getAsJsonObject("C_CSPlayerPawn")
                .getAsJsonObject("fields")
                .get("m_entitySpottedState").getAsInt();

        if (classes.has("C_CSPlayerPawn") &&
                classes.getAsJsonObject("C_CSPlayerPawn").getAsJsonObject("fields").has("m_angEyeAngles")) {
            m_angEyeAngles = classes.getAsJsonObject("C_CSPlayerPawn")
                    .getAsJsonObject("fields")
                    .get("m_angEyeAngles").getAsInt();
        } else if (classes.has("C_CSPlayerPawnBase") &&
                classes.getAsJsonObject("C_CSPlayerPawnBase").getAsJsonObject("fields").has("m_angEyeAngles")) {
            m_angEyeAngles = classes.getAsJsonObject("C_CSPlayerPawnBase")
                    .getAsJsonObject("fields")
                    .get("m_angEyeAngles").getAsInt();
        }

        if (classes.has("C_BasePlayerPawn")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("C_BasePlayerPawn").getAsJsonObject("fields");
            if (fields != null && fields.has("m_pWeaponServices")) {
                m_pWeaponServices = fields.get("m_pWeaponServices").getAsInt();
            }
            if (fields != null && fields.has("m_vecVelocity")) {
                m_vecVelocity = fields.get("m_vecVelocity").getAsInt();
            }
        }

        if (classes.has("CPlayer_WeaponServices")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("CPlayer_WeaponServices")
                    .getAsJsonObject("fields");
            if (fields != null && fields.has("m_hMyWeapons")) {
                m_hMyWeapons = fields.get("m_hMyWeapons").getAsInt();
            }
        }

        if (classes.has("C_BaseEntity")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("C_BaseEntity").getAsJsonObject("fields");
            if (fields != null && fields.has("m_pGameSceneNode")) {
                m_pGameSceneNode = fields.get("m_pGameSceneNode").getAsInt();
            }
        }

        if (classes.has("CSkeletonInstance")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("CSkeletonInstance").getAsJsonObject("fields");
            if (fields != null && fields.has("m_modelState")) {
                m_modelState = fields.get("m_modelState").getAsInt();
            }
        }

        if (classes.has("C_PlantedC4")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("C_PlantedC4").getAsJsonObject("fields");
            if (fields != null) {
                if (fields.has("m_flC4Blow")) {
                    m_flC4Blow = fields.get("m_flC4Blow").getAsInt();
                }
                if (fields.has("m_bBombTicking")) {
                    m_bBombTicking = fields.get("m_bBombTicking").getAsInt();
                }
            }
        }

        // ── Grenade projectile offsets (C_BaseGrenade) ────────────────────────
        // m_flDetonateTime: server-time timestamp at which the grenade will detonate.
        String[] grenadeDetonateCandidates = { "C_BaseCSGrenadeProjectile", "C_BaseGrenade", "C_BaseCSGrenade" };
        for (String cls : grenadeDetonateCandidates) {
            if (classes.has(cls)) {
                com.google.gson.JsonObject fields = classes.getAsJsonObject(cls).getAsJsonObject("fields");
                if (fields != null && fields.has("m_flDetonateTime")) {
                    m_flDetonateTime = fields.get("m_flDetonateTime").getAsInt();
                    break;
                }
            }
        }

        m_pInventoryServices = getField(classes, "CCSPlayerController", "m_pInventoryServices", 0x810);
        m_unMusicID = getField(classes, "CCSPlayerController_InventoryServices", "m_unMusicID", 0x58);
        m_pClippingWeapon = getField(classes, "C_CSPlayerPawn", "m_pClippingWeapon", 0x3DC0);
        m_hHudModelArms = getField(classes, "C_CSPlayerPawn", "m_hHudModelArms", 0x2400);
        m_hOwnerEntity = getField(classes, "C_BaseEntity", "m_hOwnerEntity", 0x528);
        m_hActiveWeapon = getField(classes, "CPlayer_WeaponServices", "m_hActiveWeapon", 0x60);
        m_pChild = getField(classes, "CGameSceneNode", "m_pChild", 0x40);
        m_pNextSibling = getField(classes, "CGameSceneNode", "m_pNextSibling", 0x48);
        m_pOwner = getField(classes, "CGameSceneNode", "m_pOwner", 0x30);
        m_MeshGroupMask = getField(classes, "CModelState", "m_MeshGroupMask", 0x220);
        m_nFallbackPaintKit = getField(classes, "C_EconEntity", "m_nFallbackPaintKit", 0x1850);
        m_AttributeManager = getField(classes, "C_EconEntity", "m_AttributeManager", 0x1378);
        m_Item = getField(classes, "C_AttributeContainer", "m_Item", 0x50);
        m_AttributeList = getField(classes, "C_EconItemView", "m_AttributeList", 0x208);
        m_Attributes = getField(classes, "CAttributeList", "m_Attributes", 0x8);
        m_iItemDefinitionIndex = getField(classes, "C_EconItemView", "m_iItemDefinitionIndex", 0x1BA);
        m_iItemIDHigh = getField(classes, "C_EconItemView", "m_iItemIDHigh", 0x1D0);
        m_nSubclassID = getField(classes, "C_BaseEntity", "m_nSubclassID", 0x3B8);

        // Direct pawn fields from C_CSPlayerPawnBase (flash + progress bar):
        m_flFlashMaxAlpha     = getField(classes, "C_CSPlayerPawnBase", "m_flFlashMaxAlpha",     0x13FC);
        m_flFlashDuration     = getField(classes, "C_CSPlayerPawnBase", "m_flFlashDuration",     0x1400);
        m_iProgressBarDuration = getField(classes, "C_CSPlayerPawnBase", "m_iProgressBarDuration", 0x13E0);

        // Spectator/Observer offsets:
        m_pObserverServices = getField(classes, "C_BasePlayerPawn", "m_pObserverServices", 0x1118);
        m_hObserverTarget   = getField(classes, "CPlayer_ObserverServices", "m_hObserverTarget", 0x44);

        // CCSPlayerController fields (defuse kit flag on controller):
        m_bPawnHasDefuser = getField(classes, "CCSPlayerController", "m_bPawnHasDefuser", 2336);

        // CCSPlayerController_InGameMoneyServices (money)
        m_iAccount = getField(classes, "CCSPlayerController_InGameMoneyServices", "m_iAccount", 64);

        // Pointer offsets on the pawn/controller for component navigation
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

    /**
     * Parses the button offsets from buttons.json content.
     *
     * @param json Content of the buttons.json file.
     */
    private static void parseButtons(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        JsonObject clientButtons = obj.getAsJsonObject("client.dll");
        if (clientButtons.has("jump")) {
            dwForceJump = clientButtons.get("jump").getAsLong();
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    /**
     * Logs the current offsets to console for debugging purposes.
     */
    private static void logOffsets() {
        System.out.println(String.format("  > dwEntityList:          0x%X", dwEntityList));
        System.out.println(String.format("  > dwViewMatrix:          0x%X", dwViewMatrix));
        System.out.println(String.format("  > dwLocalPlayerPawn:     0x%X", dwLocalPlayerPawn));
        System.out.println(String.format("  > dwViewAngles:          0x%X", dwViewAngles));
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
