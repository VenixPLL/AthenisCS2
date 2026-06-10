package me.venixpll.cheat.reader;

import com.sun.jna.Memory;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.Vector3;

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
        int batchBaseOffset = CS2Offsets.m_iHealth; // lowest pawn field we need
        int batchSize = Math.min(
                (CS2Offsets.m_iTeamNum - CS2Offsets.m_iHealth) + 4, // span + one int
                PAWN_META_CAP);

        List<PlayerCache.PlayerData> result = new ArrayList<>(16);

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
            // Both values live within a ~163-byte window so a single syscall covers both.
            if (!CS2Memory.readInto(playerPawn + batchBaseOffset, PAWN_META_BUF, batchSize)) continue;

            int health = PAWN_META_BUF.getInt((long) (CS2Offsets.m_iHealth  - batchBaseOffset));
            int team   = PAWN_META_BUF.getInt((long) (CS2Offsets.m_iTeamNum - batchBaseOffset));

            // Filter out dead players and spectators / bots with invalid team numbers.
            if (health <= 0 || health > 100) continue;
            if (team != 2 && team != 3)       continue;

            // Step 5 — read the player name string from the controller (different base address).
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

            // Position and screen coords start at zero; PositionReader fills them next tick.
            PlayerCache.PlayerData player = new PlayerCache.PlayerData(
                    i, health, team, name, new Vector3(), isLocal, playerPawn);
            player.hasBomb = hasBomb;

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
