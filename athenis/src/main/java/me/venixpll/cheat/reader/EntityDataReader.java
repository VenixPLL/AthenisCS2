package me.venixpll.cheat.reader;

import com.sun.jna.Memory;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Performs the full CS2 entity-list traversal at a reduced frequency (~10 Hz) to
 * refresh per-player metadata: health, team number, display name, and pawn address.
 * <p>
 * <strong>What this reader does NOT do:</strong> read world positions or compute screen
 * projections.  Position data changes every frame and is handled entirely by
 * {@link PositionReader} on the fast loop.  Separating the concerns means the slow
 * traversal (up to 64 entity slots × multiple pointer hops) never blocks fresh
 * position data from reaching the renderer.
 * <p>
 * <h3>Batched RPM strategy</h3>
 * Instead of issuing separate {@code ReadProcessMemory} calls for {@code m_iHealth}
 * and {@code m_iTeamNum}, a single call reads a contiguous block of pawn memory that
 * covers both fields.  Individual values are then extracted with Java buffer reads
 * (no kernel transition).  This reduces RPM calls from ~6 per player to ~4 per player
 * for the slow path.
 * <p>
 * <b>Thread safety:</b> All static buffers are single-owner; this class must only be
 * called from the dedicated slow-data thread.
 */
public final class EntityDataReader {

    // ── Pre-allocated native Memory buffers ───────────────────────────────────
    // Each buffer is sized once at class-load and reused on every slow-loop tick.
    // This eliminates the constant native heap alloc/free cycle that JNA's
    // per-call Memory(N) constructor would otherwise cause.

    /**
     * Buffer for the pawn metadata batch-read that covers both {@code m_iHealth}
     * and {@code m_iTeamNum} in one RPM call.
     * <p>
     * 256 bytes provides a comfortable margin above the typical span of
     * ~163 bytes (offset 844 → 1007) and absorbs minor CS2 update drift
     * without requiring a buffer resize.
     */
    private static final int    PAWN_META_CAP = 256;
    private static final Memory PAWN_META_BUF = new Memory(PAWN_META_CAP);

    /** 8-byte buffer for reading 64-bit pointer values (entity list entries, pawn addresses). */
    private static final Memory LONG_BUF = new Memory(8);

    /** 4-byte buffer for reading the integer pawn handle stored in the player controller. */
    private static final Memory INT_BUF  = new Memory(4);

    /** 32-byte buffer for reading a null-terminated UTF-8 player name string. */
    private static final Memory NAME_BUF = new Memory(32);

    private EntityDataReader() {}

    // ── Pre-allocated PlayerData pool ───────────────────────────────────────────
    // The slow data loop runs at ~10 Hz. Each tick previously allocated a new
    // PlayerData object (and inside it two new Vector3 instances) per player.
    // We instead maintain a fixed pool of 64 slots whose fields are reset and
    // reused each tick. The returned ArrayList is a fresh small list (just
    // object references, ~128 bytes for 16 players) so the fast loop's iterator
    // is never invalidated by the slow loop clearing a shared list.

    private static final int MAX_POOL = 64;
    private static final PlayerCache.PlayerData[] DATA_POOL = new PlayerCache.PlayerData[MAX_POOL];

    static {
        for (int i = 0; i < MAX_POOL; i++) {
            DATA_POOL[i] = new PlayerCache.PlayerData(0, 0, 0, "", null, false, 0L);
        }
    }

    /**
     * Traverses the CS2 entity list (controller slots 1–64) and returns a fresh
     * {@link PlayerCache.PlayerData} list populated with health, team, name, and pawn
     * address for every living player that passes basic sanity filters.
     * <p>
     * Screen coordinates ({@code feetX / feetY / headX / headY}) are intentionally
     * left at zero — {@link PositionReader} fills them in on the very next fast-loop
     * iteration, which runs continuously without sleeping.
     * <p>
     * The {@code m_iHealth} + {@code m_iTeamNum} pair is read in a single
     * {@code ReadProcessMemory} call by computing the contiguous byte range at
     * runtime so that dynamically-loaded {@link CS2Offsets} values are always respected.
     *
     * @param clientBase      Cached base address of {@code client.dll}.
     * @param localPlayerPawn Pawn address of the local player — used to set the
     *                        {@code isLocal} flag on the matching entry.
     * @return Ordered list of all valid, living players found in the entity list;
     *         empty if the entity list pointer is null or no players qualify.
     */
    public static List<PlayerCache.PlayerData> readAll(long clientBase, long localPlayerPawn) {
        long entityList = readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0) return new ArrayList<>();

        // Compute the contiguous pawn metadata range at call-time so that any
        // offset changes applied by CS2Offsets.load() are automatically respected.
        int batchBaseOffset = CS2Offsets.m_iHealth;
        int batchSize = Math.min(
                (CS2Offsets.m_iTeamNum - CS2Offsets.m_iHealth) + 4,
                PAWN_META_CAP);

        // Fresh list of references each tick — the fast loop holds its own reference
        // so the next slow-loop tick can safely create a new list without invalidating
        // any iterator the fast loop may be holding.
        List<PlayerCache.PlayerData> result = new ArrayList<>(16);
        int poolSlot = 0;

        for (int i = 1; i <= 64; i++) {

            // Step 1 — resolve the entity list chunk entry for this slot index.
            long listEntry = readLong(entityList + 8L * ((i & 0x7FFF) >> 9) + 16);
            if (listEntry == 0) continue;

            // Step 2 — read the player controller pointer from the chunk.
            long playerController = readLong(listEntry + 112L * (i & 0x1FF));
            if (playerController == 0) continue;

            // Step 3 — resolve pawn address via the handle stored on the controller.
            int pawnHandle = readInt(playerController + CS2Offsets.m_hPlayerPawn);
            if (pawnHandle == 0) continue;

            long pawnListEntry = readLong(entityList + 8L * ((pawnHandle & 0x7FFF) >> 9) + 16);
            if (pawnListEntry == 0) continue;

            long playerPawn = readLong(pawnListEntry + 112L * (pawnHandle & 0x1FF));
            if (playerPawn == 0) continue;

            // Step 4 — batch-read pawn metadata block (health + team) in ONE RPM call.
            if (!CS2Memory.readInto(playerPawn + batchBaseOffset, PAWN_META_BUF, batchSize)) continue;

            int health = PAWN_META_BUF.getInt((long) (CS2Offsets.m_iHealth  - batchBaseOffset));
            int team   = PAWN_META_BUF.getInt((long) (CS2Offsets.m_iTeamNum - batchBaseOffset));

            // Filter out dead players and spectators / bots with invalid team numbers.
            if (health <= 0 || health > 100) continue;
            if (team != 2 && team != 3)       continue;

            // Step 5 — read the player name string from the controller.
            String name = readName(playerController + CS2Offsets.m_iszPlayerName);

            boolean isLocal = (playerPawn == localPlayerPawn);

            // Step 6 — check if player has bomb
            boolean hasBomb = false;
            long weaponServices = readLong(playerPawn + CS2Offsets.m_pWeaponServices);
            if (weaponServices != 0) {
                int myWeaponsSize = readInt(weaponServices + CS2Offsets.m_hMyWeapons);
                if (myWeaponsSize > 0 && myWeaponsSize < 100) {
                    long myWeaponsMemory = readLong(weaponServices + CS2Offsets.m_hMyWeapons + 8);
                    if (myWeaponsMemory != 0) {
                        for (int w = 0; w < myWeaponsSize; w++) {
                            int weaponHandle = readInt(myWeaponsMemory + w * 4L);
                            if (weaponHandle == 0 || weaponHandle == -1) continue;

                            long weaponListEntry = readLong(entityList + 8L * ((weaponHandle & 0x7FFF) >> 9) + 16);
                            if (weaponListEntry == 0) continue;

                            long weaponEntity = readLong(weaponListEntry + 112L * (weaponHandle & 0x1FF));
                            if (weaponEntity == 0) continue;

                            long identity = readLong(weaponEntity + 0x10);
                            if (identity != 0) {
                                long designerNamePtr = readLong(identity + 0x20);
                                if (designerNamePtr != 0) {
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

            // ── Step 7 — Player Flags ─────────────────────────────────────────

            // 7a. Blind
            float flashMaxAlpha = 0f;
            float flashDuration = 0f;
            int flashMaxAlphaRaw = readInt(playerPawn + CS2Offsets.m_flFlashMaxAlpha);
            int flashDurationRaw = readInt(playerPawn + CS2Offsets.m_flFlashDuration);
            flashMaxAlpha = Float.intBitsToFloat(flashMaxAlphaRaw);
            flashDuration = Float.intBitsToFloat(flashDurationRaw);
            if (!Float.isFinite(flashMaxAlpha) || flashMaxAlpha < 0f) flashMaxAlpha = 0f;
            if (!Float.isFinite(flashDuration) || flashDuration < 0f) flashDuration = 0f;

            // 7b. Defusing/Planting
            int progressBarDuration = readInt(playerPawn + CS2Offsets.m_iProgressBarDuration);
            boolean isDefusingOrPlanting = progressBarDuration > 0;

            // 7c. Scoped
            int scopedByte = readInt(playerPawn + CS2Offsets.m_bIsScoped);
            boolean isScoped = (scopedByte & 0xFF) != 0;

            // 7d. Kit
            int hasDefuserByte = readInt(playerController + CS2Offsets.m_bPawnHasDefuser);
            boolean hasKit = (hasDefuserByte & 0xFF) != 0;

            // 7e. Money
            int money = 0;
            long moneyServices = readLong(playerController + CS2Offsets.m_pInGameMoneyServices_ctrl);
            if (moneyServices != 0) {
                money = readInt(moneyServices + CS2Offsets.m_iAccount);
                if (money < 0 || money > 99999) money = 0;
            }

            // ── Reuse a pre-allocated PlayerData slot from the pool ────────────
            // This avoids `new PlayerData(...)` and the `new Vector3()` inside it.
            PlayerCache.PlayerData player;
            if (poolSlot < MAX_POOL) {
                player = DATA_POOL[poolSlot++];
                // Reset all fields in-place — position Vector3 objects are reused.
                player.index    = i;
                player.health   = health;
                player.team     = team;
                player.name     = name;
                player.position.x = 0f;
                player.position.y = 0f;
                player.position.z = 0f;
                player.isLocal  = isLocal;
                player.onScreen = false;
                player.feetX    = 0f;
                player.feetY    = 0f;
                player.headX    = 0f;
                player.headY    = 0f;
                player.headWorldPos.x = 0f;
                player.headWorldPos.y = 0f;
                player.headWorldPos.z = 0f;
                player.pawnAddress  = playerPawn;
                player.yaw          = 0f;
                player.hasBomb      = hasBomb;
                player.flashMaxAlpha        = flashMaxAlpha;
                player.flashDuration        = flashDuration;
                player.isScoped             = isScoped;
                player.isDefusingOrPlanting = isDefusingOrPlanting;
                player.hasKit               = hasKit;
                player.money                = money;
                player.controllerAddress    = playerController;
            } else {
                // Fallback for edge cases beyond MAX_POOL (should never happen in CS2)
                player = new PlayerCache.PlayerData(i, health, team, name, null, isLocal, playerPawn);
                player.hasBomb              = hasBomb;
                player.flashMaxAlpha        = flashMaxAlpha;
                player.flashDuration        = flashDuration;
                player.isScoped             = isScoped;
                player.isDefusingOrPlanting = isDefusingOrPlanting;
                player.hasKit               = hasKit;
                player.money                = money;
                player.controllerAddress    = playerController;
            }

            result.add(player);
        }

        return result;
    }


    // ── Private buffer-reusing read helpers ───────────────────────────────────
    // These helpers call CS2Memory.readInto() with the class-owned buffers so no
    // Memory object is allocated or freed during the entity traversal inner loop.

    /**
     * Reads an 8-byte pointer from {@code address} into the pre-allocated
     * {@link #LONG_BUF}.  Returns {@code 0} on failure.
     */
    private static long readLong(long address) {
        return CS2Memory.readInto(address, LONG_BUF, 8) ? LONG_BUF.getLong(0) : 0L;
    }

    /**
     * Reads a 4-byte integer from {@code address} into the pre-allocated
     * {@link #INT_BUF}.  Returns {@code 0} on failure.
     */
    private static int readInt(long address) {
        return CS2Memory.readInto(address, INT_BUF, 4) ? INT_BUF.getInt(0) : 0;
    }

    /**
     * Reads up to 32 bytes from {@code address} into {@link #NAME_BUF} and returns
     * the null-terminated content decoded as a UTF-8 string.  Returns an empty
     * string on failure or when the first byte is already null.
     */
    private static String readName(long address) {
        if (!CS2Memory.readInto(address, NAME_BUF, 32)) return "";
        byte[] bytes = NAME_BUF.getByteArray(0, 32);
        int len = 0;
        while (len < bytes.length && bytes[len] != 0) len++;
        return new String(bytes, 0, len, StandardCharsets.UTF_8);
    }
}
