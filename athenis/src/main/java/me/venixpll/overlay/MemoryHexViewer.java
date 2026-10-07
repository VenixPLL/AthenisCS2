package me.venixpll.overlay;

import imgui.ImColor;
import imgui.ImGui;
import imgui.ImVec2;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Interactive Real-Time Memory Hex Viewer & Inspector (Cheat Engine Style).
 *
 * <p>Features:
 * <ul>
 *   <li>Live memory streaming of arbitrary CS2 addresses and symbol offsets</li>
 *   <li>Cheat Engine style real-time byte change detection & decaying red highlight</li>
 *   <li>Interactive byte selection & multi-type value inspector (Int8, Int16, Int32, Int64, Float, Double, ASCII)</li>
 *   <li>Live memory writing / patching back to CS2 memory space</li>
 *   <li>Quick jump to key game structures (Local Pawn, Controller, EntityList, ViewMatrix, etc.)</li>
 * </ul>
 */
public final class MemoryHexViewer {

    private static boolean open = false;
    private static final ImBoolean openWrapper = new ImBoolean(false);

    // ── Navigation & Query State ──────────────────────────────────────────────
    private static final ImString addressInput = new ImString("client.dll + dwEntityList", 128);
    private static long resolvedAddress = 0L;
    private static int viewSize = 256; // 64, 128, 256, 512, 1024
    private static boolean liveStreaming = true;

    // ── Live Memory Buffers & Change Highlighting ─────────────────────────────
    private static byte[] currentBytes = new byte[0];
    private static byte[] prevBytes = new byte[0];
    private static long[] changeTimestamps = new long[0];
    private static long lastReadTime = 0L;

    // ── Selection & Value Inspector ───────────────────────────────────────────
    private static int selectedByteOffset = 0;
    private static final ImString editHexInput = new ImString(32);
    private static final ImString editIntInput = new ImString(32);
    private static final ImString editFloatInput = new ImString(32);
    private static String writeStatusMsg = "";
    private static long writeStatusTime = 0L;

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final float[] COL_BG = { 0.04f, 0.05f, 0.065f, 0.98f };
    private static final float[] COL_ACCENT = { 0.00f, 0.71f, 0.85f }; // Cyan
    private static final float[] COL_DIM = { 0.42f, 0.44f, 0.53f };
    private static final float[] COL_TEXT = { 0.82f, 0.85f, 0.95f };
    private static final float[] COL_HIGHLIGHT = { 0.98f, 0.32f, 0.32f }; // Cheat Engine Red

    public static boolean isOpen() {
        return open;
    }

    public static void setOpen(boolean state) {
        open = state;
        openWrapper.set(state);
        if (!state) {
            currentBytes = new byte[0];
            prevBytes = new byte[0];
            changeTimestamps = new long[0];
        }
    }

    public static void toggle() {
        setOpen(!open);
    }

    public static ImBoolean getOpenWrapper() {
        openWrapper.set(open);
        return openWrapper;
    }

    /**
     * Resolves the target memory address from text input or symbolic names.
     */
    public static long resolveAddress(String input) {
        if (input == null || input.trim().isEmpty()) return 0L;
        String s = input.trim().toLowerCase();

        long clientBase = CS2Memory.getClientBase();
        long engineBase = CS2Memory.getEngine2Base();

        // Direct symbolic jumps
        if (s.equals("localpawn") || s.equals("pawn") || s.equals("localplayer")) {
            if (clientBase != 0 && CS2Offsets.dwLocalPlayerPawn != 0) {
                return CS2Memory.readLong(clientBase + CS2Offsets.dwLocalPlayerPawn);
            }
        }
        if (s.equals("localcontroller") || s.equals("controller")) {
            if (clientBase != 0 && CS2Offsets.dwLocalPlayerController != 0) {
                return CS2Memory.readLong(clientBase + CS2Offsets.dwLocalPlayerController);
            }
        }
        if (s.equals("entitylist") || s.equals("dwentitylist")) {
            return clientBase + CS2Offsets.dwEntityList;
        }
        if (s.equals("viewmatrix") || s.equals("dwviewmatrix")) {
            return clientBase + CS2Offsets.dwViewMatrix;
        }
        if (s.equals("plantedc4") || s.equals("dwplantedc4")) {
            return clientBase + CS2Offsets.dwPlantedC4;
        }
        if (s.equals("client.dll") || s.equals("client")) {
            return clientBase;
        }
        if (s.equals("engine2.dll") || s.equals("engine2")) {
            return engineBase;
        }

        // Relative expressions (e.g. "client.dll + dwEntityList" or "client.dll + 0x180")
        if (s.startsWith("client.dll") || s.startsWith("client")) {
            int plusIdx = s.indexOf('+');
            if (plusIdx > 0) {
                String offsetPart = s.substring(plusIdx + 1).trim();
                return clientBase + parseOffset(offsetPart);
            }
            return clientBase;
        }
        if (s.startsWith("engine2.dll") || s.startsWith("engine2")) {
            int plusIdx = s.indexOf('+');
            if (plusIdx > 0) {
                String offsetPart = s.substring(plusIdx + 1).trim();
                return engineBase + parseOffset(offsetPart);
            }
            return engineBase;
        }

        return parseOffset(s);
    }

    private static long parseOffset(String s) {
        s = s.trim().toLowerCase();
        if (s.equals("dwentitylist")) return CS2Offsets.dwEntityList;
        if (s.equals("dwlocalplayerpawn")) return CS2Offsets.dwLocalPlayerPawn;
        if (s.equals("dwlocalplayercontroller")) return CS2Offsets.dwLocalPlayerController;
        if (s.equals("dwviewmatrix")) return CS2Offsets.dwViewMatrix;
        if (s.equals("dwviewangles")) return CS2Offsets.dwViewAngles;
        if (s.equals("dwplantedc4")) return CS2Offsets.dwPlantedC4;

        if (s.startsWith("0x")) s = s.substring(2);
        try {
            return Long.parseUnsignedLong(s, 16);
        } catch (Exception e) {
            try {
                return Long.parseLong(s);
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }

    /**
     * Main render entry point — renders the ImGui Memory Viewer window.
     */
    public static void render() {
        if (!open) return;

        openWrapper.set(open);
        ImGui.setNextWindowSize(780f, 520f, ImGuiCond.FirstUseEver);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.WindowBg, COL_BG[0], COL_BG[1], COL_BG[2], COL_BG[3]);
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Border, 0.15f, 0.18f, 0.24f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 8f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10f, 10f);

        String statusBadge = CS2Memory.isAttached()
                ? " [Attached: PID " + CS2Memory.getProcessId() + "]"
                : " [Detached]";

        openWrapper.set(true);
        if (ImGui.begin("Memory Hex Inspector##AthenisHexViewer", openWrapper, ImGuiWindowFlags.None)) {
            open = openWrapper.get();

            // ── Top Navigation & Address Bar ─────────────────────────────────
            renderToolbar();

            ImGui.separator();
            ImGui.spacing();

            // Read live memory if streaming
            updateMemoryBuffer();

            // ── Main Content: Left Hex Grid, Right/Bottom Value Inspector ────
            float availH = ImGui.getContentRegionAvailY();
            float hexGridH = Math.max(220f, availH - 140f);

            renderHexGrid(hexGridH);

            ImGui.spacing();
            ImGui.separator();
            ImGui.spacing();

            renderValueInspector();
        }
        ImGui.end();

        if (!openWrapper.get()) {
            setOpen(false);
        }

        ImGui.popStyleVar(2);
        ImGui.popStyleColor(2);
    }

    private static void renderToolbar() {
        // Quick Jump Combo
        String[] jumps = {
                "Quick Jump...",
                "Local Player Pawn",
                "Local Controller",
                "Entity List",
                "View Matrix",
                "client.dll Base",
                "engine2.dll Base",
                "Planted C4"
        };
        ImGui.setNextItemWidth(140f);
        if (ImGui.beginCombo("##QuickJump", jumps[0])) {
            if (ImGui.selectable(jumps[1])) jumpTo("localpawn");
            if (ImGui.selectable(jumps[2])) jumpTo("localcontroller");
            if (ImGui.selectable(jumps[3])) jumpTo("client.dll + dwEntityList");
            if (ImGui.selectable(jumps[4])) jumpTo("client.dll + dwViewMatrix");
            if (ImGui.selectable(jumps[5])) jumpTo("client.dll");
            if (ImGui.selectable(jumps[6])) jumpTo("engine2.dll");
            if (ImGui.selectable(jumps[7])) jumpTo("client.dll + dwPlantedC4");
            ImGui.endCombo();
        }

        ImGui.sameLine(0f, 8f);

        // Address Input Box
        ImGui.setNextItemWidth(220f);
        if (ImGui.inputTextWithHint("##HexAddressInput", "0x... / symbol", addressInput, ImGuiInputTextFlags.EnterReturnsTrue)) {
            resolvedAddress = resolveAddress(addressInput.get());
        }

        ImGui.sameLine(0f, 6f);
        if (ImGui.button("Go##HexGo")) {
            resolvedAddress = resolveAddress(addressInput.get());
        }

        ImGui.sameLine(0f, 8f);

        // Stepping buttons
        if (ImGui.button("< -16B")) {
            resolvedAddress = Math.max(0, resolvedAddress - 16);
            addressInput.set(String.format("0x%X", resolvedAddress));
        }
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("> +16B")) {
            resolvedAddress += 16;
            addressInput.set(String.format("0x%X", resolvedAddress));
        }

        ImGui.sameLine(0f, 10f);

        // Size Selector Combo
        int[] sizes = { 64, 128, 256, 512, 1024 };
        ImGui.setNextItemWidth(80f);
        if (ImGui.beginCombo("##HexSize", viewSize + " B")) {
            for (int s : sizes) {
                boolean isSel = (viewSize == s);
                if (ImGui.selectable(s + " B", isSel)) {
                    viewSize = s;
                }
            }
            ImGui.endCombo();
        }

        ImGui.sameLine(0f, 10f);

        // Live streaming toggle button
        if (liveStreaming) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.15f, 0.50f, 0.25f, 1.0f);
            if (ImGui.button("Live [ON]##LiveToggle")) {
                liveStreaming = false;
            }
            ImGui.popStyleColor();
        } else {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Button, 0.50f, 0.20f, 0.20f, 1.0f);
            if (ImGui.button("Freeze [PAUSED]##LiveToggle")) {
                liveStreaming = true;
            }
            ImGui.popStyleColor();
        }
    }

    private static void jumpTo(String targetExpr) {
        addressInput.set(targetExpr);
        resolvedAddress = resolveAddress(targetExpr);
        selectedByteOffset = 0;
    }

    private static void updateMemoryBuffer() {
        if (!open || (!liveStreaming && currentBytes.length == viewSize)) {
            return;
        }

        if (resolvedAddress == 0L) {
            resolvedAddress = resolveAddress(addressInput.get());
        }

        if (resolvedAddress == 0L || !CS2Memory.isAttached()) {
            return;
        }

        byte[] raw = CS2Memory.readBytes(resolvedAddress, viewSize);
        if (raw.length == 0) {
            return;
        }

        long now = System.currentTimeMillis();

        if (prevBytes.length != raw.length) {
            prevBytes = new byte[raw.length];
            System.arraycopy(raw, 0, prevBytes, 0, raw.length);
            changeTimestamps = new long[raw.length];
        } else {
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] != prevBytes[i]) {
                    changeTimestamps[i] = now;
                    prevBytes[i] = raw[i];
                }
            }
        }

        currentBytes = raw;
        lastReadTime = now;
    }

    private static void renderHexGrid(float height) {
        float availW = ImGui.getContentRegionAvailX();

        ImGui.pushStyleColor(imgui.flag.ImGuiCol.ChildBg, 0.03f, 0.035f, 0.045f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.ChildRounding, 4f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 6f, 6f);
        ImGui.beginChild("##HexGridChild", availW, height, true, 0);

        if (currentBytes.length == 0) {
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, COL_DIM[0], COL_DIM[1], COL_DIM[2], 1.0f);
            ImGui.text(resolvedAddress == 0L
                    ? "Enter an address above or choose a Quick Jump target."
                    : String.format("Unable to read memory at 0x%X (Process not attached or memory unmapped).", resolvedAddress));
            ImGui.popStyleColor();
            ImGui.endChild();
            ImGui.popStyleVar(2);
            ImGui.popStyleColor();
            return;
        }

        // ── Header Row (Offset columns) ──────────────────────────────────────
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 0.85f);
        ImGui.text("  Offset         00 01 02 03  04 05 06 07   08 09 0A 0B  0C 0D 0E 0F    Decoded Text");
        ImGui.popStyleColor();
        ImGui.separator();

        long now = System.currentTimeMillis();
        int rows = (currentBytes.length + 15) / 16;

        for (int r = 0; r < rows; r++) {
            int rowOffset = r * 16;
            long rowAddr = resolvedAddress + rowOffset;

            // Address label
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1.0f);
            ImGui.text(String.format("0x%012X:", rowAddr));
            ImGui.popStyleColor();

            ImGui.sameLine(0f, 12f);

            // Hex Bytes (16 bytes, grouped in 4-byte chunks)
            for (int b = 0; b < 16; b++) {
                int byteIdx = rowOffset + b;
                if (byteIdx >= currentBytes.length) break;

                byte val = currentBytes[byteIdx];
                int unsignedVal = val & 0xFF;

                // Spacing between chunks
                if (b == 4 || b == 12) {
                    ImGui.sameLine(0f, 6f);
                } else if (b == 8) {
                    ImGui.sameLine(0f, 12f);
                } else if (b > 0) {
                    ImGui.sameLine(0f, 4f);
                }

                // Change Highlighting (Decay over 1200ms)
                long changeTime = (byteIdx < changeTimestamps.length) ? changeTimestamps[byteIdx] : 0L;
                long elapsed = now - changeTime;
                boolean isRecentChange = (elapsed < 1200L);
                boolean isSelected = (byteIdx == selectedByteOffset);

                if (isSelected) {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.0f, 1.0f, 0.8f, 1.0f);
                } else if (isRecentChange) {
                    float factor = 1.0f - (elapsed / 1200.0f);
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, COL_HIGHLIGHT[0], COL_HIGHLIGHT[1] + (0.5f * (1f - factor)), COL_HIGHLIGHT[2], 1.0f);
                } else if (val == 0) {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, COL_DIM[0], COL_DIM[1], COL_DIM[2], 0.65f);
                } else {
                    ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, COL_TEXT[0], COL_TEXT[1], COL_TEXT[2], 1.0f);
                }

                String hexStr = String.format("%02X", unsignedVal);
                if (ImGui.selectable(hexStr + "##b_" + byteIdx, isSelected, 0, 18f, 0f)) {
                    selectedByteOffset = byteIdx;
                    editHexInput.set(String.format("%02X", unsignedVal));
                    editIntInput.set(String.valueOf(readIntAt(byteIdx)));
                    editFloatInput.set(String.valueOf(readFloatAt(byteIdx)));
                }

                ImGui.popStyleColor();
            }

            // ASCII representation column
            ImGui.sameLine(0f, 20f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.70f, 0.75f, 0.85f, 0.9f);
            StringBuilder ascii = new StringBuilder(16);
            for (int b = 0; b < 16; b++) {
                int byteIdx = rowOffset + b;
                if (byteIdx >= currentBytes.length) break;
                char c = (char) (currentBytes[byteIdx] & 0xFF);
                if (c >= 32 && c <= 126) {
                    ascii.append(c);
                } else {
                    ascii.append('.');
                }
            }
            ImGui.text(ascii.toString());
            ImGui.popStyleColor();
        }

        ImGui.endChild();
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    private static void renderValueInspector() {
        if (currentBytes.length == 0) return;

        int offset = Math.min(selectedByteOffset, currentBytes.length - 1);
        long targetAddr = resolvedAddress + offset;

        // Decoded values
        byte bVal = currentBytes[offset];
        short sVal = readShortAt(offset);
        int iVal = readIntAt(offset);
        long lVal = readLongAt(offset);
        float fVal = readFloatAt(offset);
        double dVal = readDoubleAt(offset);

        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, COL_ACCENT[0], COL_ACCENT[1], COL_ACCENT[2], 1.0f);
        ImGui.text(String.format("SELECTED: 0x%X (+0x%X)", targetAddr, offset));
        ImGui.popStyleColor();

        ImGui.sameLine(0f, 20f);
        ImGui.text(String.format("Int8: %d (0x%02X)", (int) bVal, (bVal & 0xFF)));
        ImGui.sameLine(0f, 14f);
        ImGui.text(String.format("Int16: %d", sVal));
        ImGui.sameLine(0f, 14f);
        ImGui.text(String.format("Int32: %d", iVal));
        ImGui.sameLine(0f, 14f);
        ImGui.text(String.format("Int64/Ptr: 0x%012X", lVal));
        ImGui.sameLine(0f, 14f);
        ImGui.text(String.format("Float: %.4f", fVal));

        ImGui.spacing();

        // ── Interactive Memory Writer Row ────────────────────────────────────
        ImGui.text("Edit Value:");
        ImGui.sameLine(0f, 8f);

        // Hex Byte Write
        ImGui.setNextItemWidth(50f);
        ImGui.inputTextWithHint("##EditHex", "Hex", editHexInput);
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Write Hex Byte##WHex")) {
            try {
                int hex = Integer.parseInt(editHexInput.get().trim(), 16);
                boolean ok = CS2Memory.writeByte(targetAddr, (byte) hex);
                setWriteStatus(ok, "Byte 0x" + Integer.toHexString(hex).toUpperCase());
            } catch (Exception e) {
                setWriteStatus(false, "Invalid Hex");
            }
        }

        ImGui.sameLine(0f, 12f);

        // Int32 Write
        ImGui.setNextItemWidth(90f);
        ImGui.inputTextWithHint("##EditInt", "Int32", editIntInput);
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Write Int32##WInt")) {
            try {
                int iv = Integer.parseInt(editIntInput.get().trim());
                boolean ok = CS2Memory.writeInt(targetAddr, iv);
                setWriteStatus(ok, "Int32 " + iv);
            } catch (Exception e) {
                setWriteStatus(false, "Invalid Int");
            }
        }

        ImGui.sameLine(0f, 12f);

        // Float Write
        ImGui.setNextItemWidth(90f);
        ImGui.inputTextWithHint("##EditFloat", "Float", editFloatInput);
        ImGui.sameLine(0f, 4f);
        if (ImGui.button("Write Float##WFloat")) {
            try {
                float fv = Float.parseFloat(editFloatInput.get().trim());
                boolean ok = CS2Memory.writeFloat(targetAddr, fv);
                setWriteStatus(ok, "Float " + fv);
            } catch (Exception e) {
                setWriteStatus(false, "Invalid Float");
            }
        }

        // Status feedback text
        if (!writeStatusMsg.isEmpty() && (System.currentTimeMillis() - writeStatusTime < 3000L)) {
            ImGui.sameLine(0f, 12f);
            ImGui.pushStyleColor(imgui.flag.ImGuiCol.Text, 0.4f, 0.9f, 0.5f, 1.0f);
            ImGui.text(writeStatusMsg);
            ImGui.popStyleColor();
        }
    }

    private static void setWriteStatus(boolean ok, String desc) {
        writeStatusMsg = ok ? "Successfully wrote: " + desc : "Failed writing: " + desc;
        writeStatusTime = System.currentTimeMillis();
    }

    private static short readShortAt(int offset) {
        if (offset + 2 > currentBytes.length) return 0;
        return ByteBuffer.wrap(currentBytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    private static int readIntAt(int offset) {
        if (offset + 4 > currentBytes.length) return 0;
        return ByteBuffer.wrap(currentBytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static long readLongAt(int offset) {
        if (offset + 8 > currentBytes.length) return 0L;
        return ByteBuffer.wrap(currentBytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static float readFloatAt(int offset) {
        if (offset + 4 > currentBytes.length) return 0.0f;
        return ByteBuffer.wrap(currentBytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getFloat();
    }

    private static double readDoubleAt(int offset) {
        if (offset + 8 > currentBytes.length) return 0.0;
        return ByteBuffer.wrap(currentBytes, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getDouble();
    }
}
