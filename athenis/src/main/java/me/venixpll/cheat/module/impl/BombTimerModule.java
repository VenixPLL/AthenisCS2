package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.Vector3;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.projection.ScreenProjector;

/**
 * BombTimerModule.
 *
 * Renders a countdown above the planted C4 bomb.
 * When the bomb is on-screen the text appears directly above the model.
 * When it is off-screen (player looking away) the timer is shown at the
 * top-centre of the overlay with a pulsing bar so it is always visible.
 *
 * <h3>Pointer chain</h3>
 * <pre>
 *  client.dll + dwPlantedC4 - 8      → planted flag byte (1 = planted)
 *  *(client.dll + dwPlantedC4)       → c4ListPtr  (CUtlVector base)
 *  *(c4ListPtr)                      → plantedC4  (C_PlantedC4*)
 *  *(plantedC4 + m_bBombTicking)     → ticking bool
 *  *(plantedC4 + m_flC4Blow)         → explosion game-timestamp (float)
 *  *(plantedC4 + m_pGameSceneNode)   → CGameSceneNode*
 *  *(sceneNode  + m_vecAbsOrigin)    → Vector3 world position
 *
 *  currentTime = serverTickCount / 64.0f  (from engine2.dll)
 * </pre>
 */
public class BombTimerModule extends CheatModule {

    private static final float TICK_RATE = 64.0f;

    private final imgui.ImVec2 textSizeBuf = new imgui.ImVec2();
    private final float[] screenOut = new float[2];

    public BombTimerModule() {
        super("Bomb Timer", false);
    }

    @Override
    public void onTick() {}

    private static boolean isValidPtr(long p) {
        return p > 0x10000L && p < 0x7FFF_FFFF_FFFFL;
    }

    /** Absolute server time in seconds from engine2.dll tick counter. */
    private static float getServerTime() {
        long engine2Base = CS2Memory.getEngine2Base();
        if (engine2Base == 0) return 0f;
        long networkClient = CS2Memory.readLong(engine2Base + CS2Offsets.dwNetworkGameClient);
        if (!isValidPtr(networkClient)) return 0f;
        int serverTick = CS2Memory.readInt(networkClient + CS2Offsets.dwNetworkGameClient_serverTickCount);
        return serverTick / TICK_RATE;
    }

    @Override
    public void onRender(ImDrawList drawList) {
        if (!PlayerCache.tracking) return;

        long clientBase = CS2Memory.getClientBase();
        if (clientBase == 0) return;

        // ── 1. Planted flag ───────────────────────────────────────────────────
        if (CS2Memory.readByte(clientBase + CS2Offsets.dwPlantedC4 - 8) == 0) return;

        // ── 2. CUtlVector → C_PlantedC4 entity (double-deref) ────────────────
        long c4ListPtr = CS2Memory.readLong(clientBase + CS2Offsets.dwPlantedC4);
        if (!isValidPtr(c4ListPtr)) return;
        long plantedC4 = CS2Memory.readLong(c4ListPtr);
        if (!isValidPtr(plantedC4)) return;

        // ── 3. Ticking check ──────────────────────────────────────────────────
        if (CS2Memory.readByte(plantedC4 + CS2Offsets.m_bBombTicking) == 0) return;

        // ── 4. Time-left calculation ──────────────────────────────────────────
        float blowTime    = CS2Memory.readFloat(plantedC4 + CS2Offsets.m_flC4Blow);
        float currentTime = getServerTime();
        float timeLeft    = blowTime - currentTime;

        if (!Float.isFinite(blowTime) || blowTime < 1.0f) return;
        if (timeLeft <= 0.0f || timeLeft > 45.0f) return;

        // ── 5. Bomb world position: entity → m_pGameSceneNode → m_vecAbsOrigin ─
        long sceneNode = CS2Memory.readLong(plantedC4 + CS2Offsets.m_pGameSceneNode);

        Vector3 bombPos = null;
        if (isValidPtr(sceneNode)) {
            bombPos = CS2Memory.readVector(sceneNode + CS2Offsets.m_vecAbsOrigin);
        }

        boolean validPos = bombPos != null
                && Float.isFinite(bombPos.x) && bombPos.x != 0f
                && Float.isFinite(bombPos.y)
                && Float.isFinite(bombPos.z);

        // ── 6. Screen projection ──────────────────────────────────────────────
        float textX, textY;
        boolean projected = false;

        if (validPos) {
            projected = ScreenProjector.project(
                    bombPos.x, bombPos.y, bombPos.z + 20.0f,
                    screenOut,
                    PlayerCache.viewMatrix, PlayerCache.screenWidth, PlayerCache.screenHeight);
        }

        if (projected) {
            textX = screenOut[0] + ESPModule.espOffsetX;
            textY = screenOut[1] + ESPModule.espOffsetY;
        } else {
            // Off-screen fallback: fixed top-centre banner
            textX = PlayerCache.screenWidth  / 2.0f + ESPModule.espOffsetX;
            textY = 60.0f                           + ESPModule.espOffsetY;
        }

        // ── 7. Draw text ──────────────────────────────────────────────────────
        String text = String.format("BOMB: %.1f s", timeLeft);
        ImGui.calcTextSize(textSizeBuf, text);
        textX -= textSizeBuf.x / 2.0f;

        // Thick black outline for maximum contrast
        int shadow = ImColor.rgba(0, 0, 0, 255);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx == 0 && dy == 0) continue;
                drawList.addText(textX + dx, textY + dy, shadow, text);
            }
        }

        // Main text: yellow (safe) → orange → red (critical last 10 s)
        float frac = Math.min(1.0f, timeLeft / 10.0f);
        drawList.addText(textX, textY, ImColor.rgba(1.0f, frac * 0.85f, 0.0f, 1.0f), text);

        // Off-screen: pulsing alert bar at top edge
        if (!projected) {
            float alpha = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() * 0.006);
            drawList.addRectFilled(
                    ESPModule.espOffsetX,
                    ESPModule.espOffsetY,
                    PlayerCache.screenWidth + ESPModule.espOffsetX,
                    4.0f + ESPModule.espOffsetY,
                    ImColor.rgba(1.0f, frac * 0.85f, 0.0f, alpha));
        }
    }
}
