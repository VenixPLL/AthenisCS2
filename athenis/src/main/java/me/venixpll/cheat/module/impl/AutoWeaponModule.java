package me.venixpll.cheat.module.impl;

import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.overlay.OverlayWindow;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.util.List;

/**
 * AutoWeapon Module.
 *
 * <p>Automatically fires the currently held weapon when the nearest eligible
 * enemy is within the weapon's effective range.  Currently supported weapons:
 * <ul>
 *   <li><b>Zeus x27 (Taser)</b> - item definition index {@code 31}.
 *       Default effective range: 155 units.</li>
 *   <li><b>Knife</b> - any item definition index {@code >= 500}.
 *       Default effective range: 65 units.</li>
 * </ul>
 *
 * <h3>Weapon identification pointer chain</h3>
 * <pre>
 * pawn
 *  + m_pWeaponServices  -> CPlayer_WeaponServices*
 *    + m_hActiveWeapon  -> entity handle (int)
 *      resolved via entity-list double-pointer
 *        + m_AttributeManager + m_Item + m_iItemDefinitionIndex -> short
 * </pre>
 */
public class AutoWeaponModule extends CheatModule {

    // ---- CS2 item-definition index constants --------------------------------
    /** Zeus x27 taser item definition index. */
    private static final int WEAPON_ZEUS_ID = 31;
    /** Knife weapon IDs start at or above this threshold. */
    private static final int KNIFE_ID_MIN   = 500;

    /** Enum describing which supported weapon type is currently held. */
    private enum HeldWeapon { NONE, ZEUS, KNIFE }

    // ---- Settings -----------------------------------------------------------

    /**
     * When enabled the module only targets players on the opposing team.
     * Disable to target any player (teammates included - use with caution).
     */
    public final BooleanSetting enemyOnly = new BooleanSetting(
            "Enemy Only##autoweapon", true);

    /**
     * Maximum world-unit distance at which the Zeus taser will auto-fire.
     * Zeus in-game range is ~170 units; the default 155 leaves a reaction margin.
     */
    public final FloatSetting zeusRange = new FloatSetting(
            "Zeus Range (units)##autoweapon", 155.0f, 50.0f, 250.0f);

    /**
     * Maximum world-unit distance at which the Knife will auto-fire.
     * The primary knife slash reaches ~70 units; the default is 65.
     */
    public final FloatSetting knifeRange = new FloatSetting(
            "Knife Range (units)##autoweapon", 65.0f, 30.0f, 150.0f);

    /**
     * Duration (ms) the simulated LMB press is held down before releasing.
     */
    public final FloatSetting clickDuration = new FloatSetting(
            "Click Duration (ms)##autoweapon", 60.0f, 10.0f, 300.0f);

    /**
     * Cooldown (ms) applied after each auto-fire event before the module can
     * fire again.  Prevents multi-click spam on a single target.
     */
    public final FloatSetting cooldown = new FloatSetting(
            "Post-Fire Cooldown (ms)##autoweapon", 350.0f, 50.0f, 2000.0f);

    // ---- Internal state ----------------------------------------------------
    private volatile boolean fireThreadRunning = false;
    private Thread fireThread;
    private Robot robot;

    // ---- Constructor -------------------------------------------------------

    public AutoWeaponModule() {
        super("Auto Weapon", ModuleCategory.EXTERNAL, MenuGroup.COMBAT, false);
        addSetting(enemyOnly);
        addSetting(zeusRange);
        addSetting(knifeRange);
        addSetting(clickDuration);
        addSetting(cooldown);
    }

    // ---- Lifecycle ---------------------------------------------------------

    @Override
    public void onTick() {
        if (isEnabled() && !fireThreadRunning) {
            startFireThread();
        } else if (!isEnabled() && fireThreadRunning) {
            fireThreadRunning = false;
        }
    }

    // ---- Fire Thread -------------------------------------------------------

    private void startFireThread() {
        if (fireThread != null && fireThread.isAlive())
            return;

        if (robot == null) {
            try {
                robot = new Robot();
            } catch (Exception e) {
                System.err.println("[AutoWeapon] Robot init failed: " + e.getMessage());
                return;
            }
        }

        fireThreadRunning = true;
        fireThread = new Thread(() -> {
            System.out.println("[AutoWeapon] Thread started.");

            while (fireThreadRunning && isEnabled()) {
                try {
                    // Pause while the overlay menu is open.
                    if (OverlayWindow.isMenuOpen()) {
                        Thread.sleep(50);
                        continue;
                    }

                    if (!PlayerCache.tracking) {
                        Thread.sleep(100);
                        continue;
                    }

                    long localPawn = PlayerCache.localPlayerPawnAddress;
                    if (localPawn == 0) {
                        Thread.sleep(50);
                        continue;
                    }

                    // 1. Identify the weapon currently held.
                    HeldWeapon weapon = resolveHeldWeapon(localPawn);
                    if (weapon == HeldWeapon.NONE) {
                        Thread.sleep(50);
                        continue;
                    }

                    float maxRange = (weapon == HeldWeapon.ZEUS)
                            ? zeusRange.getValue()
                            : knifeRange.getValue();

                    // 2. Read local player world position.
                    float localX = CS2Memory.readFloat(localPawn + CS2Offsets.m_vOldOrigin);
                    float localY = CS2Memory.readFloat(localPawn + CS2Offsets.m_vOldOrigin + 4);
                    float localZ = CS2Memory.readFloat(localPawn + CS2Offsets.m_vOldOrigin + 8);

                    // 3. Find nearest eligible target within the effective range.
                    List<PlayerSnapshot> players = PlayerCache.renderPlayers;
                    float closestDist = Float.MAX_VALUE;
                    boolean targetFound = false;

                    for (PlayerSnapshot p : players) {
                        if (p.isLocal)
                            continue;
                        if (p.health <= 0)
                            continue;

                        // Enemy-only filter.
                        if (enemyOnly.getValue() && p.team == ESPModule.localTeam)
                            continue;

                        float dx = p.worldX - localX;
                        float dy = p.worldY - localY;
                        float dz = p.worldZ - localZ;
                        float dist3D = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

                        if (dist3D <= maxRange && dist3D < closestDist) {
                            closestDist = dist3D;
                            targetFound = true;
                        }
                    }

                    // 4. Auto-fire if a target is within range.
                    if (targetFound) {
                        System.out.printf("[AutoWeapon] Firing %s at %.1f units%n",
                                weapon.name(), closestDist);

                        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                        Thread.sleep((long) clickDuration.getValue().floatValue());
                        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);

                        Thread.sleep((long) cooldown.getValue().floatValue());
                    } else {
                        Thread.yield();
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[AutoWeapon] Error: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            fireThreadRunning = false;
            System.out.println("[AutoWeapon] Thread stopped.");
        }, "Athenis-AutoWeapon");

        fireThread.setDaemon(true);
        fireThread.setPriority(Thread.MAX_PRIORITY);
        fireThread.start();
    }

    // ---- Helpers ------------------------------------------------------------

    /**
     * Walks the CS2 weapon-services pointer chain to determine which supported
     * weapon the local player is currently holding.
     *
     * @param localPawn Raw memory address of the local player pawn.
     * @return The recognised {@link HeldWeapon}, or {@link HeldWeapon#NONE}.
     */
    private HeldWeapon resolveHeldWeapon(long localPawn) {
        long weaponServices = CS2Memory.readLong(localPawn + CS2Offsets.m_pWeaponServices);
        if (!isValidPtr(weaponServices))
            return HeldWeapon.NONE;

        int  activeHandle = CS2Memory.readInt(weaponServices + CS2Offsets.m_hActiveWeapon);
        long weaponEntity = getEntityByHandle(activeHandle);
        if (!isValidPtr(weaponEntity))
            return HeldWeapon.NONE;

        long itemBase = weaponEntity + CS2Offsets.m_AttributeManager + CS2Offsets.m_Item;
        int  defIdx   = CS2Memory.readShort(itemBase + CS2Offsets.m_iItemDefinitionIndex) & 0xFFFF;

        if (defIdx == WEAPON_ZEUS_ID)
            return HeldWeapon.ZEUS;
        if (defIdx >= KNIFE_ID_MIN)
            return HeldWeapon.KNIFE;

        return HeldWeapon.NONE;
    }

    /**
     * Resolves a CS2 entity handle to its raw memory address using the
     * entity-list double-pointer structure (mirrors CrosshairOverlayModule).
     *
     * @param handle Entity handle integer as read from memory.
     * @return Raw entity address, or {@code 0} if invalid.
     */
    private static long getEntityByHandle(int handle) {
        if (handle == 0 || handle == -1)
            return 0;
        long clientBase = CS2Memory.getClientBase();
        long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0)
            return 0;
        long listEntry = CS2Memory.readLong(entityList + 8L * ((handle & 0x7FFF) >> 9) + 0x10);
        if (listEntry == 0)
            return 0;
        return CS2Memory.readLong(listEntry + 0x70L * (handle & 0x1FF));
    }

    /**
     * Guards against obviously invalid pointer values (null / kernel range).
     *
     * @param ptr Memory address to validate.
     * @return {@code true} if the address is in the valid user-space range.
     */
    private static boolean isValidPtr(long ptr) {
        return ptr > 0x10000L && ptr < 0x7FFF_FFFF_FFFFL;
    }
}