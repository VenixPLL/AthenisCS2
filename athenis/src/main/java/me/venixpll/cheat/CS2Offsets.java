package me.venixpll.cheat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * CS2Offsets handles retrieving game offsets either dynamically from the
 * online a2x/cs2-dumper repository or falling back to offline defaults.
 */
public class CS2Offsets {
    // Global offsets (loaded from offsets.json)
    public static long dwEntityList = 38688144L;
    public static long dwViewMatrix = 36981552L;
    public static long dwLocalPlayerPawn = 36959896L;
    /** Offset to local player view angle struct {pitch, yaw, roll} in client.dll */
    public static long dwViewAngles = 37034408L;
    public static long dwGlobalVars = 0L;

    // Schema variable offsets (loaded from client_dll.json)
    public static int m_hPlayerPawn = 2316;
    public static int m_iHealth = 844;
    public static int m_iTeamNum = 1003;
    public static int m_vOldOrigin = 5008;
    public static int m_iszPlayerName = 1780;
    /** Offset to the recoil punch angle struct {pitch, yaw} inside player pawn */
    public static int m_aimPunchAngle = 5296;
    /**
     * Offset to the EntitySpottedState_t::m_bSpotted boolean inside C_CSPlayerPawn.
     * When true the game has determined at least one enemy can see this entity.
     * Used as a simple visibility proxy for the aim assist visible-only filter.
     */
    public static int m_bSpotted = 0x8;

    public static int m_entitySpottedState = 7224;
    /** Offset to the view angles struct {pitch, yaw, roll} inside player pawn */
    public static int m_angEyeAngles = 0x1518;

    /**
     * Velocity vector of the player pawn (3 floats: vx, vy, vz in units/sec).
     * Used by PositionReader to extrapolate the player's current world position
     * forward by the pipeline read latency, compensating for m_vOldOrigin lag.
     * Fallback: 0x3C8 (typical offset in recent CS2 builds).
     */
    public static int m_vecVelocity = 0x3C8;

    // Weapon services offsets
    public static int m_pWeaponServices = 0x11A0;
    public static int m_hMyWeapons = 0x40;


    private static final String OFFSETS_URL = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/offsets.json";
    private static final String CLIENT_DLL_URL = "https://raw.githubusercontent.com/a2x/cs2-dumper/main/output/client_dll.json";

    /**
     * Initializes offsets by attempting to download them dynamically from GitHub.
     * Falls back to built-in hardcoded offsets if the network or parsing fails.
     */
    public static void load() {
        System.out.println("[CS2Offsets] Attempting to download latest offsets online...");
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

            // Fetch offsets.json
            HttpRequest offsetsRequest = HttpRequest.newBuilder().uri(URI.create(OFFSETS_URL)).build();
            HttpResponse<String> offsetsResponse = client.send(offsetsRequest, HttpResponse.BodyHandlers.ofString());

            // Fetch client_dll.json
            HttpRequest clientDllRequest = HttpRequest.newBuilder().uri(URI.create(CLIENT_DLL_URL)).build();
            HttpResponse<String> clientDllResponse = client.send(clientDllRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (offsetsResponse.statusCode() == 200 && clientDllResponse.statusCode() == 200) {
                parseOffsets(offsetsResponse.body());
                parseClientDll(clientDllResponse.body());
                System.out.println("[CS2Offsets] Successfully loaded latest offsets from a2x/cs2-dumper!");
                logOffsets();
                return;
            }
        } catch (Exception e) {
            System.err.println("[CS2Offsets] Could not download online offsets: " + e.getMessage());
        }

        System.out.println("[CS2Offsets] Falling back to pre-packaged offline offsets.");
        logOffsets();
    }

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
        if (clientOffsets.has("dwViewAngles")) {
            dwViewAngles = clientOffsets.get("dwViewAngles").getAsLong();
        }
        if (clientOffsets.has("dwGlobalVars")) {
            dwGlobalVars = clientOffsets.get("dwGlobalVars").getAsLong();
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

        // Aim punch angle (recoil compensation for silent aim)
        if (classes.has("C_CSPlayerPawnBase") &&
                classes.getAsJsonObject("C_CSPlayerPawnBase").getAsJsonObject("fields").has("m_aimPunchAngle")) {
            m_aimPunchAngle = classes.getAsJsonObject("C_CSPlayerPawnBase")
                    .getAsJsonObject("fields")
                    .get("m_aimPunchAngle").getAsInt();
        }

        // Spotted state — read from EntitySpottedState_t inside client_dll.json
        if (classes.has("EntitySpottedState_t")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("EntitySpottedState_t").getAsJsonObject("fields");
            if (fields != null && fields.has("m_bSpotted")) {
                m_bSpotted = fields.get("m_bSpotted").getAsInt();
            } else {
                m_bSpotted = 0x8;
            }
        } else {
            // Fallback candidate search
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
            // Velocity offset — lives in C_BasePlayerPawn alongside m_vOldOrigin
            if (fields != null && fields.has("m_vecVelocity")) {
                m_vecVelocity = fields.get("m_vecVelocity").getAsInt();
            }
        }

        if (classes.has("CPlayer_WeaponServices")) {
            com.google.gson.JsonObject fields = classes.getAsJsonObject("CPlayer_WeaponServices").getAsJsonObject("fields");
            if (fields != null && fields.has("m_hMyWeapons")) {
                m_hMyWeapons = fields.get("m_hMyWeapons").getAsInt();
            }
        }
    }

    /**
     * Logs the current offsets to console for debugging purposes.
     */
    private static void logOffsets() {
        System.out.println(String.format("  > dwEntityList: 0x%X", dwEntityList));
        System.out.println(String.format("  > dwViewMatrix: 0x%X", dwViewMatrix));
        System.out.println(String.format("  > dwLocalPlayerPawn: 0x%X", dwLocalPlayerPawn));
        System.out.println(String.format("  > dwViewAngles: 0x%X", dwViewAngles));
        System.out.println(String.format("  > dwGlobalVars: 0x%X", dwGlobalVars));
        System.out.println(String.format("  > m_hPlayerPawn: 0x%X", m_hPlayerPawn));
        System.out.println(String.format("  > m_iHealth: 0x%X", m_iHealth));
        System.out.println(String.format("  > m_iTeamNum: 0x%X", m_iTeamNum));
        System.out.println(String.format("  > m_vOldOrigin: 0x%X", m_vOldOrigin));
        System.out.println(String.format("  > m_iszPlayerName: 0x%X", m_iszPlayerName));
        System.out.println(String.format("  > m_aimPunchAngle: 0x%X", m_aimPunchAngle));
        System.out.println(String.format("  > m_bSpotted: 0x%X", m_bSpotted));
        System.out.println(String.format("  > m_pWeaponServices: 0x%X", m_pWeaponServices));
        System.out.println(String.format("  > m_hMyWeapons: 0x%X", m_hMyWeapons));
        System.out.println(String.format("  > m_vecVelocity: 0x%X", m_vecVelocity));
    }
}
