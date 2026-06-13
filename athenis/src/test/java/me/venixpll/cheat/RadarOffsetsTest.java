package me.venixpll.cheat;

import me.venixpll.cheat.module.impl.RadarHackModule;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class RadarOffsetsTest {

    @Test
    public void testMapOffsetsLoaded() throws Exception {
        // Trigger class loading to invoke the static block loading JSON
        Class<?> clazz = RadarHackModule.class;
        
        // Access MAPS field via reflection to verify it populated correctly
        Field mapsField = clazz.getDeclaredField("MAPS");
        mapsField.setAccessible(true);
        Map<?, ?> maps = (Map<?, ?>) mapsField.get(null);
        
        assertNotNull(maps, "MAPS map should not be null");
        assertFalse(maps.isEmpty(), "MAPS map should not be empty");
        
        // Verify de_cache coordinates are corrected
        Object cacheData = maps.get("de_cache");
        assertNotNull(cacheData, "de_cache should be present in MAPS");
        
        Field posXField = cacheData.getClass().getDeclaredField("posX");
        Field posYField = cacheData.getClass().getDeclaredField("posY");
        Field scaleField = cacheData.getClass().getDeclaredField("scale");
        
        posXField.setAccessible(true);
        posYField.setAccessible(true);
        scaleField.setAccessible(true);
        
        float posX = posXField.getFloat(cacheData);
        float posY = posYField.getFloat(cacheData);
        float scale = scaleField.getFloat(cacheData);
        
        assertEquals(-2000.0f, posX, 0.001f, "de_cache posX should be corrected to -2000");
        assertEquals(3250.0f, posY, 0.001f, "de_cache posY should be corrected to 3250");
        assertEquals(5.5f, scale, 0.001f, "de_cache scale should be 5.5");
        
        System.out.println("[RadarOffsetsTest] Verified de_cache: posX=" + posX + ", posY=" + posY + ", scale=" + scale);
    }
}
