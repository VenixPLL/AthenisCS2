package me.venixpll.cheat.reader;

import com.sun.jna.Memory;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * High-performance reader for CS2 player entities and metadata.
 * Traverses CGameEntitySystem chunks to discover both controllers and pawns.
 */
public final class EntityDataReader {

    // ── Pre-allocated native Memory buffers ──────────────────────────────────
    private static final int    PAWN_META_CAP = 256;
    private static final Memory PAWN_META_BUF = new Memory(PAWN_META_CAP);
    private static final Memory LONG_BUF      = new Memory(8);
    private static final Memory INT_BUF       = new Memory(4);
    private static final Memory NAME_BUF      = new Memory(64);

    /** Entity-list slot stride in bytes (sizeof CEntityIdentity). Default 120 (0x78). */
    private static int entityStride = 120;

    private EntityDataReader() {}

    // ── Diagnostic state ─────────────────────────────────────────────────────
    private static final AtomicBoolean diagnosticDumpDone = new AtomicBoolean(false);

    public static void resetDiagnosticDump() {
        diagnosticDumpDone.set(false);
    }

    // ── Pre-allocated PlayerData pool ────────────────────────────────────────
    private static final int MAX_POOL = 64;
    private static final PlayerCache.PlayerData[][] DATA_POOLS = new PlayerCache.PlayerData[2][MAX_POOL];
    private static int activePool = 0;

    static {
        for (int p = 0; p < DATA_POOLS.length; p++) {
            for (int i = 0; i < MAX_POOL; i++) {
                DATA_POOLS[p][i] = new PlayerCache.PlayerData(0, 0, 0, "", null, false, 0L);
            }
        }
    }

    // ── Discovered CGameEntitySystem base address ─────────────────────────────
    private static volatile long discoveredEntitySystem = 0;

    public static void resetDiscoveredOffset() {
        discoveredEntitySystem = 0;
    }

    /**
     * Traverses the CS2 entity list and populates {@link PlayerCache#rawPlayers}.
     */
    public static List<PlayerCache.PlayerData> readAll(long clientBase, long localPlayerPawn) {

        long entitySystem = resolveEntitySystem(clientBase, localPlayerPawn);
        if (entitySystem == 0) {
            return new ArrayList<>();
        }

        if (!diagnosticDumpDone.getAndSet(true)) {
            runDiagnosticDump(clientBase, localPlayerPawn, entitySystem);
        }

        List<PlayerCache.PlayerData> result = new ArrayList<>(16);

        int poolIdx = activePool ^ 1;
        activePool = poolIdx;
        PlayerCache.PlayerData[] pool = DATA_POOLS[poolIdx];
        int poolSlot = 0;

        // Strategy A: Iterate Player Controllers (Slots 1..64)
        for (int i = 1; i <= 64; i++) {
            long chunkAddr = entitySystem + 0x10 + 8L * ((i & 0x7FFF) >> 9);
            long listEntry = readLong(chunkAddr);
            if (listEntry <= 0x10000L || listEntry > 0x7FFFFFFFFFFFL) continue;

            long ctrlIdentity = listEntry + (long) entityStride * (i & 0x1FF);
            long ctrl = readLong(ctrlIdentity);
            if (ctrl <= 0x10000L || ctrl > 0x7FFFFFFFFFFFL) continue;

            // Resolve pawn from controller
            int pawnHandle = readInt(ctrl + CS2Offsets.m_hPlayerPawn);
            if (pawnHandle == 0 || pawnHandle == -1 || (pawnHandle & 0x7FFF) >= 0x7FFF) {
                pawnHandle = readInt(ctrl + CS2Offsets.m_hObserverPawn);
            }
            if (pawnHandle == 0 || pawnHandle == -1 || (pawnHandle & 0x7FFF) >= 0x7FFF) {
                pawnHandle = readInt(ctrl + 0x6BC);
            }

            long pawn = 0;
            int health = 0;
            int team = 0;

            if (pawnHandle != 0 && pawnHandle != -1 && (pawnHandle & 0x7FFF) < 0x7FFF) {
                int pawnIndex = pawnHandle & 0x7FFF;
                long pChunk = readLong(entitySystem + 0x10 + 8L * (pawnIndex >> 9));
                if (pChunk > 0x10000L && pChunk < 0x7FFFFFFFFFFFL) {
                    long pIdentity = pChunk + (long) entityStride * (pawnIndex & 0x1FF);
                    pawn = readLong(pIdentity);
                    if (pawn > 0x10000L && pawn < 0x7FFFFFFFFFFFL) {
                        health = readInt(pawn + CS2Offsets.m_iHealth);
                        team = readInt(pawn + CS2Offsets.m_iTeamNum);
                    }
                }
            }

            // Fallbacks from controller metadata
            if (health <= 0 || health > 100) {
                health = readInt(ctrl + CS2Offsets.m_iPawnHealth);
            }
            if (team != 2 && team != 3) {
                team = readInt(ctrl + CS2Offsets.m_iTeamNum);
            }

            if (health <= 0 || health > 100) continue;
            if (team != 2 && team != 3)       continue;

            String name = readName(ctrl + CS2Offsets.m_iszPlayerName);
            if (name.isEmpty()) name = readName(ctrl + 0x748);
            if (name.isEmpty()) name = "Player " + i;

            boolean isLocal = (pawn != 0 && pawn == localPlayerPawn) || (ctrl == readLong(clientBase + CS2Offsets.dwLocalPlayerController));

            // Bomb check
            boolean hasBomb = false;
            if (pawn != 0) {
                long weaponServices = readLong(pawn + CS2Offsets.m_pWeaponServices);
                if (weaponServices > 0x10000L && weaponServices < 0x7FFFFFFFFFFFL) {
                    int myWeaponsSize = readInt(weaponServices + CS2Offsets.m_hMyWeapons);
                    if (myWeaponsSize > 0 && myWeaponsSize < 64) {
                        long myWeaponsMemory = readLong(weaponServices + CS2Offsets.m_hMyWeapons + 8);
                        if (myWeaponsMemory > 0x10000L && myWeaponsMemory < 0x7FFFFFFFFFFFL) {
                            for (int w = 0; w < myWeaponsSize; w++) {
                                int weaponHandle = readInt(myWeaponsMemory + w * 4L);
                                if (weaponHandle == 0 || weaponHandle == -1) continue;
                                int wIndex = weaponHandle & 0x7FFF;
                                long wListEntry = readLong(entitySystem + 0x10 + 8L * (wIndex >> 9));
                                if (wListEntry <= 0x10000L) continue;
                                long weaponEntity = readLong(wListEntry + (long) entityStride * (wIndex & 0x1FF));
                                if (weaponEntity <= 0x10000L) continue;
                                long identity = readLong(weaponEntity + 0x10);
                                if (identity > 0x10000L) {
                                    long designerNamePtr = readLong(identity + 0x20);
                                    if (designerNamePtr > 0x10000L) {
                                        String designerName = CS2Memory.readString(designerNamePtr, 32);
                                        if (designerName != null && designerName.contains("weapon_c4")) {
                                            hasBomb = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            int flashMaxAlphaRaw = pawn != 0 ? readInt(pawn + CS2Offsets.m_flFlashMaxAlpha) : 0;
            int flashDurationRaw = pawn != 0 ? readInt(pawn + CS2Offsets.m_flFlashDuration) : 0;
            float flashMaxAlpha = Float.intBitsToFloat(flashMaxAlphaRaw);
            float flashDuration = Float.intBitsToFloat(flashDurationRaw);
            if (!Float.isFinite(flashMaxAlpha) || flashMaxAlpha < 0f) flashMaxAlpha = 0f;
            if (!Float.isFinite(flashDuration) || flashDuration < 0f) flashDuration = 0f;

            int progressBarDuration = pawn != 0 ? readInt(pawn + CS2Offsets.m_iProgressBarDuration) : 0;
            boolean isDefusingOrPlanting = progressBarDuration > 0;

            int scopedByte = pawn != 0 ? readInt(pawn + CS2Offsets.m_bIsScoped) : 0;
            boolean isScoped = (scopedByte & 0xFF) != 0;

            int hasDefuserByte = readInt(ctrl + CS2Offsets.m_bPawnHasDefuser);
            boolean hasKit = (hasDefuserByte & 0xFF) != 0;

            int money = 0;
            long moneyServices = readLong(ctrl + CS2Offsets.m_pInGameMoneyServices_ctrl);
            if (moneyServices > 0x10000L) {
                money = readInt(moneyServices + CS2Offsets.m_iAccount);
                if (money < 0 || money > 99999) money = 0;
            }

            PlayerCache.PlayerData player = poolSlot < MAX_POOL ? pool[poolSlot++] :
                    new PlayerCache.PlayerData(i, health, team, name, null, isLocal, pawn);

            player.index = i;
            player.health = health;
            player.team = team;
            player.name = name;
            player.isLocal = isLocal;
            player.pawnAddress = pawn;
            player.controllerAddress = ctrl;
            player.hasBomb = hasBomb;
            player.flashMaxAlpha = flashMaxAlpha;
            player.flashDuration = flashDuration;
            player.isScoped = isScoped;
            player.isDefusingOrPlanting = isDefusingOrPlanting;
            player.hasKit = hasKit;
            player.money = money;

            result.add(player);
        }

        // Strategy B: If few players found, sweep chunk 0 entity identities directly for player pawns
        if (result.size() < 2) {
            long chunk0 = readLong(entitySystem + 0x10);
            if (chunk0 > 0x10000L && chunk0 < 0x7FFFFFFFFFFFL) {
                for (int s = 1; s < 512; s++) {
                    long identity = chunk0 + (long) entityStride * s;
                    long ent = readLong(identity);
                    if (ent <= 0x10000L || ent > 0x7FFFFFFFFFFFL) continue;

                    // Check if ent is already tracked as pawn or ctrl
                    boolean alreadyPresent = false;
                    for (PlayerCache.PlayerData p : result) {
                        if (p.pawnAddress == ent || p.controllerAddress == ent) {
                            alreadyPresent = true;
                            break;
                        }
                    }
                    if (alreadyPresent) continue;

                    int hp = readInt(ent + CS2Offsets.m_iHealth);
                    int tm = readInt(ent + CS2Offsets.m_iTeamNum);
                    if (hp > 0 && hp <= 100 && (tm == 2 || tm == 3)) {
                        PlayerCache.PlayerData player = poolSlot < MAX_POOL ? pool[poolSlot++] :
                                new PlayerCache.PlayerData(s, hp, tm, "Player " + s, null, ent == localPlayerPawn, ent);
                        player.index = s;
                        player.health = hp;
                        player.team = tm;
                        player.name = "Player " + s;
                        player.isLocal = (ent == localPlayerPawn);
                        player.pawnAddress = ent;
                        result.add(player);
                    }
                }
            }
        }

        if (result.size() > 0) {
            PlayerCache.rawPlayers = result;
        }

        return result;
    }

    // ── Entity System Resolution ─────────────────────────────────────────────

    private static long resolveEntitySystem(long clientBase, long localPlayerPawn) {
        if (discoveredEntitySystem > 0) {
            long testChunk = readLong(discoveredEntitySystem + 0x10);
            if (testChunk > 0x10000L && testChunk < 0x7FFFFFFFFFFFL) {
                return discoveredEntitySystem;
            }
            discoveredEntitySystem = 0;
        }

        // Candidate offsets
        long[] candidateOffsets = {
            CS2Offsets.dwEntityList,
            0x2715828L,
            0x2449378L,
            0x2515a18L,
            0x2449368L,
            0x2715818L
        };

        for (long off : candidateOffsets) {
            long directCandidate = clientBase + off;
            int count = evaluateCandidate(directCandidate);
            if (count > 0) {
                discoveredEntitySystem = directCandidate;
                CS2Offsets.dwEntityList = off;
                System.out.println("[EntityDataReader] [DIRECT-SYSTEM] Verified CGameEntitySystem at clientBase+0x"
                        + Long.toHexString(off) + " (sys=0x" + Long.toHexString(directCandidate)
                        + ") found " + count + " active players (stride=" + entityStride + ")");
                return directCandidate;
            }

            long ptrVal = readLong(directCandidate);
            if (ptrVal > 0x10000L && ptrVal < 0x7FFFFFFFFFFFL) {
                count = evaluateCandidate(ptrVal);
                if (count > 0) {
                    discoveredEntitySystem = ptrVal;
                    CS2Offsets.dwEntityList = off;
                    System.out.println("[EntityDataReader] [POINTER-SYSTEM] Verified CGameEntitySystem at clientBase+0x"
                            + Long.toHexString(off) + " -> 0x" + Long.toHexString(ptrVal)
                            + " found " + count + " active players (stride=" + entityStride + ")");
                    return ptrVal;
                }
            }
        }

        // Full client.dll data segment scan
        long scanStart = clientBase + 0x2000000L;
        long scanEnd   = clientBase + 0x3000000L;
        for (long addr = scanStart; addr < scanEnd; addr += 8) {
            long cand = readLong(addr);
            if (cand > 0x10000L && cand < 0x7FFFFFFFFFFFL) {
                int count = evaluateCandidate(cand);
                if (count > 0) {
                    discoveredEntitySystem = cand;
                    long offset = addr - clientBase;
                    CS2Offsets.dwEntityList = offset;
                    System.out.println("[EntityDataReader] [SCAN-FOUND] Verified CGameEntitySystem at client.dll+0x"
                            + Long.toHexString(offset) + " -> 0x" + Long.toHexString(cand)
                            + " with " + count + " active players");
                    return cand;
                }
            }
        }

        return 0;
    }

    private static int evaluateCandidate(long sys) {
        if (sys <= 0x10000L || sys > 0x7FFFFFFFFFFFL) return 0;
        long chunk0 = readLong(sys + 0x10);
        if (chunk0 <= 0x10000L || chunk0 > 0x7FFFFFFFFFFFL) return 0;

        for (int stride : new int[] { 120, 112, 128 }) {
            int validPlayers = 0;
            for (int s = 1; s <= 64; s++) {
                long ctrl = readLong(chunk0 + (long) stride * s);
                if (ctrl <= 0x10000L || ctrl > 0x7FFFFFFFFFFFL) continue;

                int hPawn = readInt(ctrl + CS2Offsets.m_hPlayerPawn);
                if (hPawn == 0 || hPawn == -1 || (hPawn & 0x7FFF) >= 0x7FFF) {
                    hPawn = readInt(ctrl + CS2Offsets.m_hObserverPawn);
                }
                if (hPawn != 0 && hPawn != -1 && (hPawn & 0x7FFF) < 0x7FFF) {
                    int pIdx = hPawn & 0x7FFF;
                    long pChunk = readLong(sys + 0x10 + 8L * (pIdx >> 9));
                    if (pChunk > 0x10000L && pChunk < 0x7FFFFFFFFFFFL) {
                        long pawn = readLong(pChunk + (long) stride * (pIdx & 0x1FF));
                        if (pawn > 0x10000L && pawn < 0x7FFFFFFFFFFFL) {
                            int hp = readInt(pawn + CS2Offsets.m_iHealth);
                            int tm = readInt(pawn + CS2Offsets.m_iTeamNum);
                            if (hp > 0 && hp <= 100 && (tm == 2 || tm == 3)) {
                                validPlayers++;
                            }
                        }
                    }
                }
            }
            if (validPlayers > 0) {
                entityStride = stride;
                return validPlayers;
            }
        }
        return 0;
    }

    // ── Diagnostic dump ──────────────────────────────────────────────────────

    private static void runDiagnosticDump(long clientBase, long localPlayerPawn, long entitySystem) {
        System.out.println("[EntityDataReader] ─── Entity pipeline diagnostic dump ───");
        System.out.println("[EntityDataReader]  clientBase            = 0x" + Long.toHexString(clientBase));
        System.out.println("[EntityDataReader]  dwEntityList offset   = 0x" + Long.toHexString(CS2Offsets.dwEntityList));
        System.out.println("[EntityDataReader]  entitySystem          = 0x" + Long.toHexString(entitySystem));
        System.out.println("[EntityDataReader]  entityStride          = " + entityStride);
        System.out.println("[EntityDataReader]  localPlayerPawn       = 0x" + Long.toHexString(localPlayerPawn));

        long chunk0 = readLong(entitySystem + 0x10);
        System.out.println("[EntityDataReader]  chunk0 (sys + 0x10)   = 0x" + Long.toHexString(chunk0));

        if (chunk0 != 0) {
            int found = 0;
            for (int s = 1; s <= 64; s++) {
                long ctrl = readLong(chunk0 + (long) entityStride * s);
                if (ctrl != 0) {
                    found++;
                    int hPawn = readInt(ctrl + CS2Offsets.m_hPlayerPawn);
                    String name = readName(ctrl + CS2Offsets.m_iszPlayerName);
                    System.out.println("[EntityDataReader]   slot " + s + " ctrl=0x" + Long.toHexString(ctrl)
                            + " hPawn=0x" + Integer.toHexString(hPawn) + " name=\"" + name + "\"");
                    if (hPawn != 0 && hPawn != -1 && (hPawn & 0x7FFF) < 0x7FFF) {
                        int pIdx = hPawn & 0x7FFF;
                        long pChunk = readLong(entitySystem + 0x10 + 8L * (pIdx >> 9));
                        long pawn = readLong(pChunk + (long) entityStride * (pIdx & 0x1FF));
                        int hp = readInt(pawn + CS2Offsets.m_iHealth);
                        int team = readInt(pawn + CS2Offsets.m_iTeamNum);
                        System.out.println("[EntityDataReader]          pawn=0x" + Long.toHexString(pawn) + " hp=" + hp + " team=" + team);
                    }
                }
            }
            System.out.println("[EntityDataReader]  Total non-null controllers in chunk 0: " + found + " / 64");
        }
        System.out.println("[EntityDataReader] ─── End diagnostic dump ───");
    }

    // ── Private read helpers ─────────────────────────────────────────────────

    private static long readLong(long address) {
        return CS2Memory.readInto(address, LONG_BUF, 8) ? LONG_BUF.getLong(0) : 0L;
    }

    private static int readInt(long address) {
        return CS2Memory.readInto(address, INT_BUF, 4) ? INT_BUF.getInt(0) : 0;
    }

    private static String readName(long address) {
        if (!CS2Memory.readInto(address, NAME_BUF, 64)) return "";
        long possiblePtr = NAME_BUF.getLong(0);
        if (possiblePtr > 0x10000L && possiblePtr < 0x7FFFFFFFFFFFL) {
            String remoteStr = CS2Memory.readString(possiblePtr, 32);
            if (remoteStr != null && !remoteStr.trim().isEmpty()) {
                return sanitizeString(remoteStr);
            }
        }
        byte[] bytes = NAME_BUF.getByteArray(0, 32);
        int len = 0;
        while (len < bytes.length && bytes[len] != 0 && bytes[len] >= 32 && bytes[len] < 127) len++;
        return sanitizeString(new String(bytes, 0, len, StandardCharsets.UTF_8));
    }

    private static String sanitizeString(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        StringBuilder sb = new StringBuilder();
        for (char c : trimmed.toCharArray()) {
            if (c >= 32 && c <= 126) sb.append(c);
        }
        return sb.toString();
    }
}
