package me.venixpll.cheat.module.impl;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.FloatSetting;

/**
 * BunnyHop Module.
 * <p>
 * Automatically sends jump signals to CS2 when Space is held down.
 * Checks player flags (standing/crouching) to jump precisely when hitting the
 * ground.
 */
public class BunnyHopModule extends CheatModule {

    /** Delay in milliseconds before executing the jump action */
    public final FloatSetting plusJumpDelay = new FloatSetting(
            "Plus Jump Delay (ms)", 10.0f, 0.0f, 100.0f);

    /** Loop / release delay in milliseconds */
    public final FloatSetting minusJumpDelay = new FloatSetting(
            "Minus Jump Delay (ms)", 10.0f, 0.0f, 100.0f);

    private volatile boolean bhopThreadRunning = false;
    private Thread bhopThread;

    private static final int VK_SPACE = 0x20;
    private static final int FORCE_JUMP_ACTIVE = 65537;
    private static final int FORCE_JUMP_INACTIVE = 256;

    // Flag states matching the C++ source
    private static final int STANDING = 65665;
    private static final int CROUCHING = 65667;

    public BunnyHopModule() {
        super("BunnyHop", ModuleCategory.EXTERNAL, false);
        addSetting(plusJumpDelay);
        addSetting(minusJumpDelay);
    }

    @Override
    public void onTick() {
        if (isEnabled() && !bhopThreadRunning) {
            startBhopThread();
        } else if (!isEnabled() && bhopThreadRunning) {
            bhopThreadRunning = false;
        }
    }

    private void startBhopThread() {
        if (bhopThread != null && bhopThread.isAlive())
            return;

        bhopThreadRunning = true;
        bhopThread = new Thread(() -> {
            System.out.println("[BunnyHop] Thread started.");

            while (bhopThreadRunning && isEnabled()) {
                try {
                    if (!CS2Memory.isAttached() || !PlayerCache.tracking) {
                        Thread.sleep(100);
                        continue;
                    }

                    if (!isCS2Active()) {
                        long forceJumpAddress = CS2Memory.getClientBase() + CS2Offsets.dwForceJump;
                        if (forceJumpAddress != CS2Memory.getClientBase()) {
                            CS2Memory.writeInt(forceJumpAddress, FORCE_JUMP_INACTIVE);
                        }
                        Thread.sleep(10);
                        continue;
                    }

                    long localPlayerPawn = CS2Memory.readLong(CS2Memory.getClientBase() + CS2Offsets.dwLocalPlayerPawn);
                    if (localPlayerPawn != 0) {
                        int fFlags = CS2Memory.readInt(localPlayerPawn + CS2Offsets.m_fFlags);
                        boolean spacePressed = (User32.INSTANCE.GetAsyncKeyState(VK_SPACE) & 0x8000) != 0;
                        long forceJumpAddress = CS2Memory.getClientBase() + CS2Offsets.dwForceJump;

                        if (spacePressed) {
                            if (fFlags == STANDING || fFlags == CROUCHING) {
                                int plusDelay = plusJumpDelay.getValue().intValue();
                                if (plusDelay > 0) {
                                    Thread.sleep(plusDelay);
                                }
                                CS2Memory.writeInt(forceJumpAddress, FORCE_JUMP_ACTIVE);
                            } else {
                                CS2Memory.writeInt(forceJumpAddress, FORCE_JUMP_INACTIVE);
                            }
                        } else {
                            // Ensure jump is released when not holding space
                            CS2Memory.writeInt(forceJumpAddress, FORCE_JUMP_INACTIVE);
                        }
                    }

                    int minusDelay = minusJumpDelay.getValue().intValue();
                    Thread.sleep(Math.max(1, minusDelay));

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[BunnyHop] Error in thread: " + e.getMessage());
                }
            }

            // Final cleanup when thread stops
            try {
                if (CS2Memory.isAttached()) {
                    long forceJumpAddress = CS2Memory.getClientBase() + CS2Offsets.dwForceJump;
                    if (forceJumpAddress != CS2Memory.getClientBase()) {
                        CS2Memory.writeInt(forceJumpAddress, FORCE_JUMP_INACTIVE);
                    }
                }
            } catch (Exception ignored) {
            }
            bhopThreadRunning = false;
            System.out.println("[BunnyHop] Thread stopped.");
        }, "Athenis-BunnyHop");

        bhopThread.setDaemon(true);
        bhopThread.setPriority(Thread.MAX_PRIORITY);
        bhopThread.start();
    }

    private boolean isCS2Active() {
        try {
            HWND foregroundWindow = User32.INSTANCE.GetForegroundWindow();
            if (foregroundWindow == null)
                return false;

            char[] className = new char[512];
            User32.INSTANCE.GetClassName(foregroundWindow, className, 512);
            String classNameStr = new String(className).trim();

            // CS2 uses SDL_app as its window class name
            if (classNameStr.startsWith("SDL_app")) {
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
