package me.venixpll.skinchanger;

import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;

public class EconItemAttributeManager {

    public static long create(long itemAddress, SkinInfo skin) {
        if (skin.paint == 0) {
            return 0;
        }

        long attributeListAddr = itemAddress + CS2Offsets.m_AttributeList + CS2Offsets.m_Attributes;
        long preSize = CS2Memory.readLong(attributeListAddr);
        long prePtr = CS2Memory.readLong(attributeListAddr + 8);

        long memBlock = prePtr;
        if (preSize == 0 || prePtr == 0) {
            // Allocate block in CS2 process memory
            // 3 attributes: Paint (6), Pattern (7), Wear (8). Each is 72 bytes. Total 216 bytes.
            memBlock = CS2Memory.virtualAllocEx(216);
            if (memBlock == 0) {
                return 0;
            }
            // Zero out the allocated memory block to prevent any garbage values
            CS2Memory.writeBytes(memBlock, new byte[216]);
        }

        // Attribute 1: Paint (index 6)
        CS2Memory.writeShort(memBlock + 0x30, (short) 6);
        CS2Memory.writeFloat(memBlock + 0x34, (float) skin.paint);
        CS2Memory.writeFloat(memBlock + 0x38, (float) skin.paint);

        // Attribute 2: Pattern (index 7)
        CS2Memory.writeShort(memBlock + 72 + 0x30, (short) 7);
        CS2Memory.writeFloat(memBlock + 72 + 0x34, (float) skin.seed);
        CS2Memory.writeFloat(memBlock + 72 + 0x38, (float) skin.seed);

        // Attribute 3: Wear (index 8)
        CS2Memory.writeShort(memBlock + 144 + 0x30, (short) 8);
        CS2Memory.writeFloat(memBlock + 144 + 0x34, skin.wear);
        CS2Memory.writeFloat(memBlock + 144 + 0x38, skin.wear);

        if (preSize == 0 || prePtr == 0) {
            // Write the custom vector back to the item
            CS2Memory.writeLong(attributeListAddr, 3L);         // size = 3
            CS2Memory.writeLong(attributeListAddr + 8, memBlock); // ptr = memBlock
        }

        return memBlock;
    }

    public static void remove(long itemAddress) {
        long attributeListAddr = itemAddress + CS2Offsets.m_AttributeList + CS2Offsets.m_Attributes;
        long size = CS2Memory.readLong(attributeListAddr);
        long ptr = CS2Memory.readLong(attributeListAddr + 8);

        if (size == 0 || ptr == 0) {
            return;
        }

        // Clear vector
        CS2Memory.writeLong(attributeListAddr, 0L);
        CS2Memory.writeLong(attributeListAddr + 8, 0L);

        // Free memory inside CS2 process
        CS2Memory.virtualFreeEx(ptr);
    }
}
