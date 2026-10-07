package me.venixpll.cheat;

import me.venixpll.console.ConsoleManager;
import me.venixpll.console.LogEntry;
import me.venixpll.overlay.PerformanceMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class MemoryLoopPerformanceTest {

    @BeforeEach
    public void setUp() {
        MemoryLoop.resetMetrics();
    }

    @Test
    public void testRecordFastTick() {
        assertEquals(0L, MemoryLoop.getFastTickNs());
        assertEquals(0.0, MemoryLoop.getFastTickMs());
        assertEquals(0.0, MemoryLoop.getFastTickAvgMs());

        // 500,000 ns = 0.5 ms
        MemoryLoop.recordFastTick(500_000L);
        assertEquals(500_000L, MemoryLoop.getFastTickNs());
        assertEquals(0.5, MemoryLoop.getFastTickMs(), 0.0001);
        assertEquals(0.5, MemoryLoop.getFastTickAvgMs(), 0.0001);

        // 1,000,000 ns = 1.0 ms
        MemoryLoop.recordFastTick(1_000_000L);
        assertEquals(1_000_000L, MemoryLoop.getFastTickNs());
        assertEquals(1.0, MemoryLoop.getFastTickMs(), 0.0001);
        // EMA: 0.5 * 0.95 + 1.0 * 0.05 = 0.475 + 0.05 = 0.525
        assertEquals(0.525, MemoryLoop.getFastTickAvgMs(), 0.0001);
    }

    @Test
    public void testRecordSlowTick() {
        assertEquals(0L, MemoryLoop.getSlowTickNs());
        assertEquals(0.0, MemoryLoop.getSlowTickMs());
        assertEquals(0.0, MemoryLoop.getSlowTickAvgMs());

        // 5,000,000 ns = 5.0 ms
        MemoryLoop.recordSlowTick(5_000_000L);
        assertEquals(5_000_000L, MemoryLoop.getSlowTickNs());
        assertEquals(5.0, MemoryLoop.getSlowTickMs(), 0.0001);
        assertEquals(5.0, MemoryLoop.getSlowTickAvgMs(), 0.0001);

        // 10,000,000 ns = 10.0 ms
        MemoryLoop.recordSlowTick(10_000_000L);
        assertEquals(10_000_000L, MemoryLoop.getSlowTickNs());
        assertEquals(10.0, MemoryLoop.getSlowTickMs(), 0.0001);
        // EMA: 5.0 * 0.95 + 10.0 * 0.05 = 4.75 + 0.50 = 5.25
        assertEquals(5.25, MemoryLoop.getSlowTickAvgMs(), 0.0001);
    }

    @Test
    public void testTickRates() {
        MemoryLoop.setFastTickRateHz(120.5);
        assertEquals(120.5, MemoryLoop.getFastTickRateHz(), 0.001);

        MemoryLoop.setSlowTickRateHz(9.8);
        assertEquals(9.8, MemoryLoop.getSlowTickRateHz(), 0.001);
    }

    @Test
    public void testResetMetrics() {
        MemoryLoop.recordFastTick(1_000_000L);
        MemoryLoop.recordSlowTick(2_000_000L);
        MemoryLoop.setFastTickRateHz(60.0);
        MemoryLoop.setSlowTickRateHz(10.0);

        MemoryLoop.resetMetrics();
        assertEquals(0L, MemoryLoop.getFastTickNs());
        assertEquals(0.0, MemoryLoop.getFastTickMs());
        assertEquals(0.0, MemoryLoop.getFastTickAvgMs());
        assertEquals(0.0, MemoryLoop.getFastTickRateHz());

        assertEquals(0L, MemoryLoop.getSlowTickNs());
        assertEquals(0.0, MemoryLoop.getSlowTickMs());
        assertEquals(0.0, MemoryLoop.getSlowTickAvgMs());
        assertEquals(0.0, MemoryLoop.getSlowTickRateHz());
    }

    @Test
    public void testFormatDurationAutoScaling() {
        // Negative / zero boundary
        assertEquals("0 ns", MemoryLoop.formatDuration(-10L));
        assertEquals("0 ns", MemoryLoop.formatDuration(0L));

        // Sub-microsecond (< 1,000 ns) -> "X ns"
        assertEquals("500 ns", MemoryLoop.formatDuration(500L));
        assertEquals("999 ns", MemoryLoop.formatDuration(999L));

        // Microsecond range (< 1,000,000 ns) -> "X.X µs (X.XXX ms)"
        String us = MemoryLoop.formatDuration(45_200L);
        assertTrue(us.contains("45.2 µs"), "Expected 45.2 µs in: " + us);
        assertTrue(us.contains("0.045 ms"), "Expected 0.045 ms in: " + us);

        // Millisecond range (< 1,000,000,000 ns) -> "X.XX ms"
        assertEquals("1.25 ms", MemoryLoop.formatDuration(1_250_000L));
        assertEquals("12.50 ms", MemoryLoop.formatDuration(12_500_000L));

        // Second range (>= 1,000,000,000 ns) -> "X.XX s"
        assertEquals("1.50 s", MemoryLoop.formatDuration(1_500_000_000L));
    }

    @Test
    public void testFormatDurationCompactAutoScaling() {
        assertEquals("0 ns", MemoryLoop.formatDurationCompact(-5L));
        assertEquals("0 ns", MemoryLoop.formatDurationCompact(0L));
        assertEquals("750 ns", MemoryLoop.formatDurationCompact(750L));
        assertEquals("45.2 µs", MemoryLoop.formatDurationCompact(45_200L));
        assertEquals("1.25 ms", MemoryLoop.formatDurationCompact(1_250_000L));
        assertEquals("2.00 s", MemoryLoop.formatDurationCompact(2_000_000_000L));
    }

    @Test
    public void testPerformanceMonitorUtilities() {
        // Lerp
        assertEquals(0.0f, PerformanceMonitor.lerp(0f, 10f, 0.0f), 0.001f);
        assertEquals(5.0f, PerformanceMonitor.lerp(0f, 10f, 0.5f), 0.001f);
        assertEquals(10.0f, PerformanceMonitor.lerp(0f, 10f, 1.0f), 0.001f);

        // Array max
        float[] sampleData = new float[]{1.5f, 4.2f, 0.3f, 8.9f, 2.1f};
        assertEquals(8.9f, PerformanceMonitor.arrayMax(sampleData), 0.001f);

        // Color for fraction
        int colMin = PerformanceMonitor.colorForFraction(0.0f);
        int colMid = PerformanceMonitor.colorForFraction(0.5f);
        int colMax = PerformanceMonitor.colorForFraction(1.0f);
        assertNotEquals(colMin, colMax);
        assertNotEquals(colMid, colMax);

        // Sample execution
        MemoryLoop.recordFastTick(250_000L);
        MemoryLoop.recordSlowTick(4_500_000L);
        PerformanceMonitor.sample();

        assertEquals(0.25f, PerformanceMonitor.getLatestFastTickMs(), 0.01f);
        assertEquals(4.5f, PerformanceMonitor.getLatestSlowTickMs(), 0.01f);
        assertTrue(PerformanceMonitor.getFastTickHistoryMs().length > 0);
        assertTrue(PerformanceMonitor.getSlowTickHistoryMs().length > 0);
        assertTrue(PerformanceMonitor.getProcRamMb().length > 0);
    }

    @Test
    public void testConsoleCommandsPerfAndStatus() {
        ConsoleManager console = ConsoleManager.getInstance();
        MemoryLoop.recordFastTick(150_000L);
        MemoryLoop.recordSlowTick(8_000_000L);
        MemoryLoop.setFastTickRateHz(240.0);
        MemoryLoop.setSlowTickRateHz(10.0);

        // Execute status command
        int beforeStatus = console.getLogs().size();
        console.executeCommand("status");
        List<LogEntry> logsAfterStatus = console.getLogs();
        assertTrue(logsAfterStatus.size() > beforeStatus);

        boolean foundFastTick = false;
        for (LogEntry entry : logsAfterStatus) {
            if (entry.getMessage().contains("Fast Tick:")) {
                foundFastTick = true;
                break;
            }
        }
        assertTrue(foundFastTick, "status command should include Fast Tick");

        // Execute perf command
        int beforePerf = console.getLogs().size();
        console.executeCommand("perf");
        List<LogEntry> logsAfterPerf = console.getLogs();
        assertTrue(logsAfterPerf.size() > beforePerf);

        boolean foundPerfHeader = false;
        boolean foundProcessRam = false;
        for (LogEntry entry : logsAfterPerf) {
            if (entry.getMessage().contains("Athenis Performance Metrics")) {
                foundPerfHeader = true;
            }
            if (entry.getMessage().contains("Process RAM:")) {
                foundProcessRam = true;
            }
        }
        assertTrue(foundPerfHeader, "perf command should display performance header");
        assertTrue(foundProcessRam, "perf command should display process RAM");
    }
}
