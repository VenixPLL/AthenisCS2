package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.overlay.OverlayWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Spectator List Module.
 * Detects who is currently spectating the local player and displays them.
 */
public class SpectatorListModule extends CheatModule {

    public SpectatorListModule() {
        super("Spectator List", ModuleCategory.EXTERNAL, MenuGroup.VISUALS, true);
    }

    private static long getEntityByHandle(long entityList, int handle) {
        if (handle == 0 || handle == -1) return 0;
        long listEntry = CS2Memory.readLong(entityList + 8L * ((handle & 0x7FFF) >> 9) + 16);
        if (listEntry == 0) return 0;
        return CS2Memory.readLong(listEntry + 112L * (handle & 0x1FF));
    }

    @Override
    public void onRender(ImDrawList drawList) {
        if (!isEnabled()) return;

        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return;

        long localPawn = CS2Memory.readLong(clientBase + CS2Offsets.dwLocalPlayerPawn);
        if (localPawn == 0) return;

        long entityList = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0) return;

        long localController = CS2Memory.readLong(clientBase + CS2Offsets.dwLocalPlayerController);

        List<SpectatorInfo> spectators = new ArrayList<>();

        for (int i = 1; i <= 64; i++) {
            long listEntry = CS2Memory.readLong(entityList + 8L * ((i & 0x7FFF) >> 9) + 16);
            if (listEntry == 0) continue;

            long playerController = CS2Memory.readLong(listEntry + 112L * (i & 0x1FF));
            if (playerController == 0) continue;

            // Skip local player
            if (playerController == localController) continue;

            // Resolve target handles
            int observerPawnHandle = CS2Memory.readInt(playerController + CS2Offsets.m_hObserverPawn);
            int playerPawnHandle = CS2Memory.readInt(playerController + CS2Offsets.m_hPlayerPawn);

            // Skip players who are currently alive (a living player cannot be spectating)
            if (playerPawnHandle != 0 && playerPawnHandle != -1) {
                long plyPawn = getEntityByHandle(entityList, playerPawnHandle);
                if (plyPawn != 0) {
                    int health = CS2Memory.readInt(plyPawn + CS2Offsets.m_iHealth);
                    if (health > 0 && health <= 100) {
                        continue;
                    }
                }
            }

            long observerServices = 0;

            if (observerPawnHandle != 0 && observerPawnHandle != -1) {
                long obsPawn = getEntityByHandle(entityList, observerPawnHandle);
                if (obsPawn != 0) {
                    observerServices = CS2Memory.readLong(obsPawn + CS2Offsets.m_pObserverServices);
                }
            }

            if (observerServices == 0 && playerPawnHandle != 0 && playerPawnHandle != -1) {
                long plyPawn = getEntityByHandle(entityList, playerPawnHandle);
                if (plyPawn != 0) {
                    observerServices = CS2Memory.readLong(plyPawn + CS2Offsets.m_pObserverServices);
                }
            }

            if (observerServices == 0) continue;

            int observerTargetHandle = CS2Memory.readInt(observerServices + CS2Offsets.m_hObserverTarget);
            if (observerTargetHandle == 0 || observerTargetHandle == -1) continue;

            long observerTargetPawn = getEntityByHandle(entityList, observerTargetHandle);
            if (observerTargetPawn == 0) continue;

            if (observerTargetPawn == localPawn) {
                String name = CS2Memory.readString(playerController + CS2Offsets.m_iszPlayerName, 32);
                if (name == null || name.trim().isEmpty()) {
                    name = "Spectator " + i;
                }
                int team = CS2Memory.readInt(playerController + CS2Offsets.m_iTeamNum);
                spectators.add(new SpectatorInfo(name, team));
            }
        }

        // Draw ImGui Spectator List Window
        boolean isMenuOpen = OverlayWindow.isMenuOpen();
        if (spectators.isEmpty() && !isMenuOpen) {
            return; // Don't show window if no spectators and menu is closed
        }

        int windowFlags = ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.AlwaysAutoResize;
        if (!isMenuOpen) {
            windowFlags |= ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove;
        }

        ImGui.begin("Spectators", windowFlags);

        if (isMenuOpen && spectators.isEmpty()) {
            ImGui.textColored(ImColor.rgba(0.5f, 0.5f, 0.5f, 0.8f), "No Spectators");
        } else {
            for (SpectatorInfo spec : spectators) {
                int color;
                if (spec.team == 2) {
                    // T Team: Orange-Red
                    color = ImColor.rgba(1.0f, 0.4f, 0.2f, 1.0f);
                } else if (spec.team == 3) {
                    // CT Team: Blue/Cyan
                    color = ImColor.rgba(0.2f, 0.7f, 1.0f, 1.0f);
                } else {
                    // Spectator / Other: Muted gray-white
                    color = ImColor.rgba(0.8f, 0.8f, 0.8f, 0.8f);
                }
                ImGui.textColored(color, spec.name);
            }
        }

        ImGui.end();
    }

    private static class SpectatorInfo {
        final String name;
        final int team;

        SpectatorInfo(String name, int team) {
            this.name = name;
            this.team = team;
        }
    }
}
