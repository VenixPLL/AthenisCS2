package me.venixpll.overlay;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessMemoryMonitorTest {

    private ProcessMemoryMonitor monitor;
    private List<String> capturedWarnings;
    private List<Boolean> capturedFirstFlags;

    @BeforeEach
    public void setUp() {
        monitor = ProcessMemoryMonitor.getInstance();
        monitor.stop();
        monitor.reset();
        monitor.setThresholdBytes(ProcessMemoryMonitor.DEFAULT_THRESHOLD_BYTES);
        monitor.setGrowthStepBytes(ProcessMemoryMonitor.DEFAULT_GROWTH_STEP_BYTES);
        monitor.setCooldownMs(ProcessMemoryMonitor.DEFAULT_COOLDOWN_MS);

        capturedWarnings = new ArrayList<>();
        capturedFirstFlags = new ArrayList<>();

        monitor.addListener((currentBytes, previousBytes, isFirstWarning, message) -> {
            capturedWarnings.add(message);
            capturedFirstFlags.add(isFirstWarning);
        });
    }

    @Test
    public void testInitialStateAndDefaults() {
        assertEquals(1024L * 1024L * 1024L, monitor.getThresholdBytes());
        assertEquals(50L * 1024L * 1024L, monitor.getGrowthStepBytes());
        assertEquals(30_000L, monitor.getCooldownMs());
        assertFalse(monitor.hasExceeded());
        assertEquals(0L, monitor.getLastWarnedBytes());
    }

    @Test
    public void testNoWarningBelow1GB() {
        long t0 = 1000L;
        // 500 MB
        boolean w1 = monitor.checkMemory(500L * 1024L * 1024L, t0);
        assertFalse(w1);
        assertFalse(monitor.hasExceeded());
        assertTrue(capturedWarnings.isEmpty());

        // Exactly 1 GB
        boolean w2 = monitor.checkMemory(1024L * 1024L * 1024L, t0 + 1000);
        assertFalse(w2);
        assertFalse(monitor.hasExceeded());
        assertTrue(capturedWarnings.isEmpty());
    }

    @Test
    public void testWarningWhenExceeds1GB() {
        long t0 = 1000L;
        long mem = 1050L * 1024L * 1024L; // 1050 MB > 1024 MB

        boolean warned = monitor.checkMemory(mem, t0);
        assertTrue(warned);
        assertTrue(monitor.hasExceeded());
        assertEquals(mem, monitor.getLastWarnedBytes());
        assertEquals(1, capturedWarnings.size());
        assertTrue(capturedFirstFlags.get(0));
        assertTrue(capturedWarnings.get(0).contains("exceeded 1 GB threshold"));
    }

    @Test
    public void testNoWarningWhenMemoryDoesNotGrow() {
        long t0 = 1000L;
        long mem = 1050L * 1024L * 1024L;

        // First warning
        monitor.checkMemory(mem, t0);
        assertEquals(1, capturedWarnings.size());

        // Check 10 seconds later with same memory
        boolean w2 = monitor.checkMemory(mem, t0 + 10_000L);
        assertFalse(w2);
        assertEquals(1, capturedWarnings.size());

        // Check 35 seconds later (cooldown passed) with same memory
        boolean w3 = monitor.checkMemory(mem, t0 + 35_000L);
        assertFalse(w3);
        assertEquals(1, capturedWarnings.size());

        // Check 40 seconds later with slightly decreased memory (1040 MB)
        boolean w4 = monitor.checkMemory(1040L * 1024L * 1024L, t0 + 40_000L);
        assertFalse(w4);
        assertEquals(1, capturedWarnings.size());
    }

    @Test
    public void testWarningWhenMemoryGrowsAfterCooldown() {
        long t0 = 1000L;
        long mem1 = 1050L * 1024L * 1024L;

        // Initial warning
        assertTrue(monitor.checkMemory(mem1, t0));
        assertEquals(1, capturedWarnings.size());

        // Memory grows to 1120 MB (+70 MB, exceeds 50 MB growth step) but only 5s passed
        long mem2 = 1120L * 1024L * 1024L;
        assertFalse(monitor.checkMemory(mem2, t0 + 5_000L), "Should not warn before cooldown");
        assertEquals(1, capturedWarnings.size());

        // Same grown memory checked after 31s (cooldown elapsed)
        assertTrue(monitor.checkMemory(mem2, t0 + 31_000L), "Should warn when cooldown elapsed and memory grew");
        assertEquals(2, capturedWarnings.size());
        assertFalse(capturedFirstFlags.get(1), "Subsequent warning should not be flagged as first");
        assertTrue(capturedWarnings.get(1).contains("grew to 1120 MB"));
        assertEquals(mem2, monitor.getLastWarnedBytes());
    }

    @Test
    public void testRepeatedGrowthSteps() {
        long t0 = 1000L;

        // 1. Initial trigger at 1050 MB
        assertTrue(monitor.checkMemory(1050L * 1024L * 1024L, t0));

        // 2. Growth to 1110 MB at t0 + 31s
        assertTrue(monitor.checkMemory(1110L * 1024L * 1024L, t0 + 31_000L));

        // 3. Growth to 1180 MB at t0 + 62s
        assertTrue(monitor.checkMemory(1180L * 1024L * 1024L, t0 + 62_000L));

        // 4. Growth to 1250 MB at t0 + 95s
        assertTrue(monitor.checkMemory(1250L * 1024L * 1024L, t0 + 95_000L));

        assertEquals(4, capturedWarnings.size());
        assertTrue(capturedFirstFlags.get(0));
        assertFalse(capturedFirstFlags.get(1));
        assertFalse(capturedFirstFlags.get(2));
        assertFalse(capturedFirstFlags.get(3));
    }

    @Test
    public void testRecoveryBelow1GBAndRetrigger() {
        long t0 = 1000L;

        // Exceeds 1 GB
        assertTrue(monitor.checkMemory(1050L * 1024L * 1024L, t0));
        assertTrue(monitor.hasExceeded());
        assertEquals(1, capturedWarnings.size());

        // Drops below 1 GB (e.g. GC runs, now 800 MB)
        assertFalse(monitor.checkMemory(800L * 1024L * 1024L, t0 + 10_000L));
        assertFalse(monitor.hasExceeded(), "hasExceeded should reset when memory drops below 1 GB");
        assertEquals(0L, monitor.getLastWarnedBytes());

        // Rises above 1 GB again (1060 MB)
        assertTrue(monitor.checkMemory(1060L * 1024L * 1024L, t0 + 20_000L));
        assertTrue(monitor.hasExceeded());
        assertEquals(2, capturedWarnings.size());
        assertTrue(capturedFirstFlags.get(1), "New warning after recovery should be marked as first warning");
    }

    @Test
    public void testRealProcessMemoryMeasurement() {
        long bytes = monitor.getProcessMemoryBytes();
        assertTrue(bytes > 0, "getProcessMemoryBytes() should return positive byte count");
        long mb = bytes / (1024 * 1024);
        assertTrue(mb >= 10, "Process memory should be at least 10 MB in a running JVM");
    }

    @Test
    public void testBackgroundThreadLifecycle() throws InterruptedException {
        assertFalse(monitor.isRunning());
        monitor.start();
        assertTrue(monitor.isRunning());
        // Idempotent start
        monitor.start();
        assertTrue(monitor.isRunning());

        monitor.stop();
        assertFalse(monitor.isRunning());
    }
}
