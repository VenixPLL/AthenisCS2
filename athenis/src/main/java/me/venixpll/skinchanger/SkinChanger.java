package me.venixpll.skinchanger;

import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import imgui.type.ImBoolean;

public class SkinChanger {
    private static volatile boolean running = false;
    private static Thread thread = null;
    private static long regenerateWeaponSkinsSig = 0;
    public static boolean forceUpdate = false;
    private static WeaponsEnum currentWeapon = WeaponsEnum.NONE;
    
    public static final ImBoolean enabled = new ImBoolean(false);
    private static final Map<Long, Long> allocatedBlocks = new ConcurrentHashMap<>();
    private static short originalInstructionValue = 0;
    private static boolean isPatched = false;

    private static final Map<Integer, Map<WeaponsEnum, SkinInfo>> teamSkins = new ConcurrentHashMap<>();
    static {
        teamSkins.put(2, new ConcurrentHashMap<>());
        teamSkins.put(3, new ConcurrentHashMap<>());
    }

    public static void start() {
        if (running) return;
        running = true;
        
        loadConfig();

        thread = new Thread(() -> {
            System.out.println("[SkinChanger] Thread started.");
            
            // Wait until memory is attached
            while (running && !CS2Memory.isAttached()) {
                try { Thread.sleep(500); } catch (InterruptedException ignored) {}
            }
            
            if (!running) return;

            // Perform signature scan
            System.out.println("[SkinChanger] Scanning for RegenerateWeaponSkins signature...");
            regenerateWeaponSkinsSig = CS2Memory.sigScan("client.dll", "48 83 EC ? E8 ? ? ? ? 48 85 C0 0F 84 ? ? ? ? 48 8B 10");
            
            if (regenerateWeaponSkinsSig != 0) {
                System.out.format("[SkinChanger] Found RegenerateWeaponSkins at 0x%X%n", regenerateWeaponSkinsSig);
                short currentVal = CS2Memory.readShort(regenerateWeaponSkinsSig + 0x52);
                short patchVal = (short) (CS2Offsets.m_AttributeManager + CS2Offsets.m_Item + CS2Offsets.m_AttributeList + CS2Offsets.m_Attributes);
                if (currentVal != patchVal) {
                    originalInstructionValue = currentVal;
                } else {
                    // Fallback default value if already patched on previous startup
                    originalInstructionValue = (short) (CS2Offsets.m_iItemDefinitionIndex);
                }
                System.out.format("[SkinChanger] Cached original instruction value: 0x%X%n", originalInstructionValue);
            } else {
                System.err.println("[SkinChanger] Failed to find RegenerateWeaponSkins signature!");
            }

            short patchVal = (short) (CS2Offsets.m_AttributeManager + CS2Offsets.m_Item + CS2Offsets.m_AttributeList + CS2Offsets.m_Attributes);

            while (running) {
                try {
                    Thread.sleep(10);

                    if (!CS2Memory.isAttached() || !CS2Memory.isProcessRunning()) {
                        cleanupAllAllocatedBlocks();
                        isPatched = false;
                        continue;
                    }

                    if (!enabled.get()) {
                        if (isPatched && regenerateWeaponSkinsSig != 0) {
                            boolean restored = CS2Memory.writeShort(regenerateWeaponSkinsSig + 0x52, originalInstructionValue);
                            if (restored) {
                                System.out.println("[SkinChanger] Restored original instruction to RegenerateWeaponSkins+0x52.");
                            }
                            isPatched = false;
                        }
                        cleanupAllAllocatedBlocks();
                        continue;
                    }

                    // Enable skinchanger patch if not yet patched
                    if (!isPatched && regenerateWeaponSkinsSig != 0) {
                        boolean patched = CS2Memory.writeShort(regenerateWeaponSkinsSig + 0x52, patchVal);
                        if (patched) {
                            System.out.format("[SkinChanger] Patched RegenerateWeaponSkins+0x52 with value 0x%X%n", patchVal);
                            isPatched = true;
                        } else {
                            System.err.println("[SkinChanger] Failed to patch RegenerateWeaponSkins!");
                        }
                    }

                    long clientBase = CS2Memory.getClientBase();
                    if (clientBase == 0) {
                        cleanupAllAllocatedBlocks();
                        continue;
                    }

                    long localPlayer = CS2Memory.readLong(clientBase + CS2Offsets.dwLocalPlayerPawn);
                    if (localPlayer == 0) {
                        cleanupAllAllocatedBlocks();
                        continue;
                    }

                    // Check if local player is alive and on a valid team before executing thread calls or reading weapon services
                    int health = CS2Memory.readInt(localPlayer + CS2Offsets.m_iHealth);
                    if (health <= 0 || health > 100) {
                        cleanupAllAllocatedBlocks();
                        continue;
                    }

                    int currentTeam = CS2Memory.readInt(localPlayer + CS2Offsets.m_iTeamNum);
                    if (currentTeam != 2 && currentTeam != 3) {
                        cleanupAllAllocatedBlocks();
                        continue;
                    }

                    updateActiveMenuDef(localPlayer);

                    List<Long> weapons = getWeapons(localPlayer);
                    
                    // Cleanup any weapon that was in our allocatedBlocks but is no longer in current inventory (dropped, destroyed)
                    cleanupDroppedWeapons(weapons);

                    boolean shouldUpdate = false;
                    for (long weapon : weapons) {
                        long item = weapon + CS2Offsets.m_AttributeManager + CS2Offsets.m_Item;

                        int defIndex = CS2Memory.readShort(item + CS2Offsets.m_iItemDefinitionIndex) & 0xFFFF;
                        WeaponsEnum wType = WeaponsEnum.fromId(defIndex);
                        if (wType == WeaponsEnum.NONE) {
                            continue;
                        }

                        SkinInfo skin = getSkin(currentTeam, wType);
                        long attributeListAddr = item + CS2Offsets.m_AttributeList + CS2Offsets.m_Attributes;
                        long prePtr = CS2Memory.readLong(attributeListAddr + 8);

                        if (skin == null || skin.paint == 0) {
                            if (prePtr != 0) {
                                removeBlock(item, prePtr);
                                allocatedBlocks.remove(item);
                                shouldUpdate = true;
                            }
                            continue;
                        }

                        // Check if the skin is already correctly applied
                        boolean needsApply = false;
                        if (prePtr == 0) {
                            needsApply = true;
                        } else {
                            int appliedPaint = (int) CS2Memory.readFloat(prePtr + 0x34);
                            int appliedSeed = (int) CS2Memory.readFloat(prePtr + 72 + 0x34);
                            float appliedWear = CS2Memory.readFloat(prePtr + 144 + 0x34);
                            
                            if (appliedPaint != skin.paint || appliedSeed != skin.seed || Math.abs(appliedWear - skin.wear) > 0.0001f) {
                                needsApply = true;
                            }
                        }

                        if (needsApply) {
                            CS2Memory.writeInt(weapon + CS2Offsets.m_nFallbackPaintKit, skin.paint);
                            CS2Memory.writeInt(item + CS2Offsets.m_iItemIDHigh, -1);

                            long memBlock = EconItemAttributeManager.create(item, skin);
                            if (memBlock != 0) {
                                allocatedBlocks.put(item, memBlock);
                            }
                            shouldUpdate = true;
                        } else if (CS2Memory.readInt(item + CS2Offsets.m_iItemIDHigh) != -1) {
                            CS2Memory.writeInt(item + CS2Offsets.m_iItemIDHigh, -1);
                            shouldUpdate = true;
                        }

                        long mask = skin.bUsesOldModel ? 2L : 1L;
                        long hudWeapon = getHudWeapon(weapon);
                        setMeshMask(weapon, mask);
                        if (hudWeapon != 0) {
                            setMeshMask(hudWeapon, mask);
                        }
                    }

                    if (shouldUpdate || forceUpdate) {
                        updateWeapons(weapons);
                    }

                    forceUpdate = false;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[SkinChanger] Error in loop: " + e.getMessage());
                }
            }
            System.out.println("[SkinChanger] Thread terminated.");
        }, "Athenis-SkinChanger");
        
        thread.setDaemon(true);
        thread.start();
    }

    public static void stop() {
        if (!running) return;
        running = false;
        saveConfig();
        cleanupAllAllocatedBlocks();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    public static WeaponsEnum getCurrentWeapon() {
        return currentWeapon;
    }

    public static void applySkin(int teamId, WeaponsEnum weapon, SkinInfo skin) {
        int resolvedTeam = (teamId == 2 || teamId == 3) ? teamId : 3;
        Map<WeaponsEnum, SkinInfo> skins = teamSkins.computeIfAbsent(resolvedTeam, k -> new ConcurrentHashMap<>());
        if (skin == null || skin.paint == 0) {
            skins.remove(weapon);
        } else {
            skins.put(weapon, new SkinInfo(skin.paint, skin.bUsesOldModel, skin.name, skin.weaponType, skin.wear, skin.seed));
        }
        forceUpdate = true;
        saveConfig();
    }

    public static SkinInfo getSkin(int teamId, WeaponsEnum weapon) {
        int resolvedTeam = (teamId == 2 || teamId == 3) ? teamId : 3;
        Map<WeaponsEnum, SkinInfo> skins = teamSkins.get(resolvedTeam);
        return skins != null ? skins.get(weapon) : null;
    }

    private static void updateActiveMenuDef(long localPlayer) {
        long weaponServices = CS2Memory.readLong(localPlayer + CS2Offsets.m_pWeaponServices);
        if (weaponServices == 0) {
            currentWeapon = WeaponsEnum.NONE;
            return;
        }
        int activeWeaponHandle = CS2Memory.readInt(weaponServices + CS2Offsets.m_hActiveWeapon);
        long activeWeapon = getEntityByHandle(activeWeaponHandle);
        if (activeWeapon == 0) {
            currentWeapon = WeaponsEnum.NONE;
            return;
        }
        long activeItem = activeWeapon + CS2Offsets.m_AttributeManager + CS2Offsets.m_Item;
        int defIdx = CS2Memory.readShort(activeItem + CS2Offsets.m_iItemDefinitionIndex) & 0xFFFF;
        currentWeapon = WeaponsEnum.fromId(defIdx);
    }

    private static List<Long> getWeapons(long playerPawn) {
        List<Long> weapons = new ArrayList<>();
        if (playerPawn == 0) return weapons;
        long weaponServices = CS2Memory.readLong(playerPawn + CS2Offsets.m_pWeaponServices);
        if (weaponServices == 0) return weapons;

        int weaponCount = CS2Memory.readInt(weaponServices + CS2Offsets.m_hMyWeapons);
        if (weaponCount <= 0 || weaponCount > 64) return weapons;

        long weaponEntry = CS2Memory.readLong(weaponServices + CS2Offsets.m_hMyWeapons + 8);
        if (weaponEntry == 0) return weapons;

        for (int i = 0; i < weaponCount; i++) {
            int handle = CS2Memory.readInt(weaponEntry + (4L * i));
            if (handle == 0 || handle == -1) continue;
            long weapon = getEntityByHandle(handle);
            if (weapon != 0) {
                weapons.add(weapon);
            }
        }
        return weapons;
    }

    private static long getEntityByHandle(int handle) {
        if (handle == 0 || handle == -1) return 0;
        long clientBase = CS2Memory.getClientBase();
        long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0) return 0;
        long listEntry = CS2Memory.readLong(entityList + 8L * ((handle & 0x7FFF) >> 9) + 0x10);
        if (listEntry == 0) return 0;
        return CS2Memory.readLong(listEntry + 0x70L * (handle & 0x1FF));
    }

    private static long getHudWeapon(long weapon) {
        long armsBase = getHudArms();
        if (armsBase == 0) return 0;
        long armsNode = CS2Memory.readLong(armsBase + CS2Offsets.m_pGameSceneNode);
        if (armsNode == 0) return 0;

        int iterations = 0;
        long viewModel = CS2Memory.readLong(armsNode + CS2Offsets.m_pChild);
        while (viewModel != 0 && iterations < 100) {
            long owner = CS2Memory.readLong(viewModel + CS2Offsets.m_pOwner);
            if (owner != 0) {
                int handle = CS2Memory.readInt(owner + CS2Offsets.m_hOwnerEntity);
                long ent = getEntityByHandle(handle);
                if (ent == weapon) {
                    return owner;
                }
            }
            viewModel = CS2Memory.readLong(viewModel + CS2Offsets.m_pNextSibling);
            iterations++;
        }
        return 0;
    }

    private static long getHudArms() {
        long clientBase = CS2Memory.getClientBase();
        long localPlayerPawn = CS2Memory.readLong(clientBase + CS2Offsets.dwLocalPlayerPawn);
        if (localPlayerPawn == 0) return 0;
        int handle = CS2Memory.readInt(localPlayerPawn + CS2Offsets.m_hHudModelArms);
        return getEntityByHandle(handle);
    }

    private static void removeBlock(long item, long ptr) {
        long attributeListAddr = item + CS2Offsets.m_AttributeList + CS2Offsets.m_Attributes;
        CS2Memory.writeLong(attributeListAddr, 0L);
        CS2Memory.writeLong(attributeListAddr + 8, 0L);
        CS2Memory.virtualFreeEx(ptr);
    }

    private static void setMeshMask(long ent, long mask) {
        if (ent == 0) return;
        long node = CS2Memory.readLong(ent + CS2Offsets.m_pGameSceneNode);
        if (node == 0) return;
        long model = node + CS2Offsets.m_modelState;
        
        if (CS2Memory.readLong(model + CS2Offsets.m_MeshGroupMask) == mask) {
            return;
        }

        long dirtyAttributes = CS2Memory.readLong(model + 0xD8); // m_pDirtyModelData = 0xD8
        if (dirtyAttributes == 0) return;

        CS2Memory.writeLong(dirtyAttributes + 0x10, mask); // m_DrityMeshGroupMask = 0x10

        boolean updated = false;
        int outerAttempts = 0;
        while (!updated && outerAttempts < 5) {
            for (int i = 0; i < 700; i++) {
                CS2Memory.writeLong(model + CS2Offsets.m_MeshGroupMask, mask);
            }
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            updated = (CS2Memory.readLong(model + CS2Offsets.m_MeshGroupMask) == mask);
            outerAttempts++;
        }
    }

    private static void updateWeapons(List<Long> weapons) {
        if (regenerateWeaponSkinsSig == 0) return;

        CS2Memory.callThread(regenerateWeaponSkinsSig);

        for (long weapon : weapons) {
            if (CS2Memory.readInt(weapon + CS2Offsets.m_nFallbackPaintKit) == -1) {
                continue;
            }

            CS2Memory.writeInt(weapon + CS2Offsets.m_nFallbackPaintKit, -1);
        }
    }

    public static void cleanupAllAllocatedBlocks() {
        if (allocatedBlocks.isEmpty()) return;
        System.out.println("[SkinChanger] Cleaning up all " + allocatedBlocks.size() + " allocated attribute blocks...");
        
        for (Map.Entry<Long, Long> entry : allocatedBlocks.entrySet()) {
            removeBlock(entry.getKey(), entry.getValue());
        }
        allocatedBlocks.clear();
    }

    private static void cleanupDroppedWeapons(List<Long> currentWeapons) {
        if (allocatedBlocks.isEmpty()) return;
        
        List<Long> currentItems = new ArrayList<>();
        for (long weapon : currentWeapons) {
            currentItems.add(weapon + CS2Offsets.m_AttributeManager + CS2Offsets.m_Item);
        }
        
        List<Long> itemsToRemove = new ArrayList<>();
        for (long item : allocatedBlocks.keySet()) {
            if (!currentItems.contains(item)) {
                itemsToRemove.add(item);
            }
        }
        
        for (long item : itemsToRemove) {
            Long ptr = allocatedBlocks.remove(item);
            if (ptr != null) {
                removeBlock(item, ptr);
            }
        }
    }

    public static void saveConfig() {
        try {
            Path dir = getCacheDir();
            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }
            Path file = dir.resolve("skins_config.json");
            JsonObject root = new JsonObject();
            
            for (Map.Entry<Integer, Map<WeaponsEnum, SkinInfo>> teamEntry : teamSkins.entrySet()) {
                String teamKey = teamEntry.getKey() == 2 ? "T" : "CT";
                JsonObject teamObj = new JsonObject();
                for (Map.Entry<WeaponsEnum, SkinInfo> entry : teamEntry.getValue().entrySet()) {
                    JsonObject sObj = new JsonObject();
                    sObj.addProperty("paint", entry.getValue().paint);
                    sObj.addProperty("legacy", entry.getValue().bUsesOldModel);
                    sObj.addProperty("name", entry.getValue().name);
                    sObj.addProperty("wear", entry.getValue().wear);
                    sObj.addProperty("seed", entry.getValue().seed);

                    teamObj.add(entry.getKey().name(), sObj);
                }
                root.add(teamKey, teamObj);
            }
            Files.writeString(file, root.toString());
        } catch (Exception e) {
            System.err.println("[SkinChanger] Failed to save skins config: " + e.getMessage());
        }
    }

    public static void loadConfig() {
        try {
            teamSkins.get(2).clear();
            teamSkins.get(3).clear();

            Path file = getCacheDir().resolve("skins_config.json");
            if (!Files.exists(file)) return;
            String content = Files.readString(file);
            JsonObject root = JsonParser.parseString(content).getAsJsonObject();
            
            boolean oldFormat = false;
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getKey().equals("T") && !entry.getKey().equals("CT") && !entry.getKey().equals("enabled")) {
                    oldFormat = true;
                    break;
                }
            }

            if (oldFormat) {
                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (entry.getKey().equals("enabled")) continue;
                    try {
                        WeaponsEnum weapon = WeaponsEnum.valueOf(entry.getKey());
                        JsonObject sObj = entry.getValue().getAsJsonObject();
                        int paint = sObj.get("paint").getAsInt();
                        boolean legacy = sObj.get("legacy").getAsBoolean();
                        String name = sObj.get("name").getAsString();
                        float wear = sObj.has("wear") ? sObj.get("wear").getAsFloat() : 0.001f;
                        int seed = sObj.has("seed") ? sObj.get("seed").getAsInt() : 1;
                        
                        teamSkins.get(2).put(weapon, new SkinInfo(paint, legacy, name, weapon, wear, seed));
                        teamSkins.get(3).put(weapon, new SkinInfo(paint, legacy, name, weapon, wear, seed));
                    } catch (Exception ignored) {}
                }
                saveConfig();
            } else {
                for (String teamKey : new String[]{"T", "CT"}) {
                    int teamId = teamKey.equals("T") ? 2 : 3;
                    if (root.has(teamKey)) {
                        JsonObject teamObj = root.getAsJsonObject(teamKey);
                        for (Map.Entry<String, JsonElement> entry : teamObj.entrySet()) {
                            try {
                                WeaponsEnum weapon = WeaponsEnum.valueOf(entry.getKey());
                                JsonObject sObj = entry.getValue().getAsJsonObject();
                                int paint = sObj.get("paint").getAsInt();
                                boolean legacy = sObj.get("legacy").getAsBoolean();
                                String name = sObj.get("name").getAsString();
                                float wear = sObj.has("wear") ? sObj.get("wear").getAsFloat() : 0.001f;
                                int seed = sObj.has("seed") ? sObj.get("seed").getAsInt() : 1;
                                teamSkins.get(teamId).put(weapon, new SkinInfo(paint, legacy, name, weapon, wear, seed));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[SkinChanger] Failed to load skins config: " + e.getMessage());
        }
    }

    private static Path getCacheDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isEmpty()) {
            return Paths.get(appData, "Athenis");
        }
        return Paths.get(System.getProperty("user.home"), ".config", "Athenis");
    }
}
