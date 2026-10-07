package me.venixpll.overlay;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import me.venixpll.console.ConsoleManager;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ProcessMemoryMonitor monitors the Athenis process memory usage in real-time.
 * <p>
 * Emits a low memory warning when process memory exceeds 1 GB (1024 MB),
 * and subsequently throws / emits warnings periodically as memory continues to grow.
 * Warnings are delivered via:
 * <ul>
 *   <li>In-game overlay toast notifications ({@link NotificationManager})</li>
 *   <li>Central console logs ({@link ConsoleManager}) with {@code WARN} level</li>
 *   <li>Standard error stream ({@link System#err})</li>
 *   <li>Registered {@link MemoryWarningListener} callbacks</li>
 * </ul>
 */
public final class ProcessMemoryMonitor {

    private static final ProcessMemoryMonitor INSTANCE = new ProcessMemoryMonitor();

    public static ProcessMemoryMonitor getInstance() {
        return INSTANCE;
    }

    // ── Default thresholds & timings ──────────────────────────────────────────
    /** 1 GB threshold (1024 MB = 1,073,741,824 bytes). */
    public static final long DEFAULT_THRESHOLD_BYTES = 1024L * 1024L * 1024L;

    /** Minimum growth required between subsequent warnings (50 MB). */
    public static final long DEFAULT_GROWTH_STEP_BYTES = 50L * 1024L * 1024L;

    /** Minimum interval between repeated warnings ("every once in a while", 30 seconds). */
    public static final long DEFAULT_COOLDOWN_MS = 30_000L;

    /** Background sampling rate (2 seconds). */
    public static final long DEFAULT_CHECK_INTERVAL_MS = 2_000L;

    // ── Win32 Psapi JNA definition ───────────────────────────────────────────
    public interface PsapiExt extends StdCallLibrary {
        PsapiExt INSTANCE = Native.load("psapi", PsapiExt.class, W32APIOptions.DEFAULT_OPTIONS);

        @Structure.FieldOrder({
                "cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
                "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage",
                "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage",
                "PagefileUsage", "PeakPagefileUsage"
        })
        class PROCESS_MEMORY_COUNTERS extends Structure {
            public int cb;
            public int PageFaultCount;
            public BaseTSD.SIZE_T PeakWorkingSetSize;
            public BaseTSD.SIZE_T WorkingSetSize;
            public BaseTSD.SIZE_T QuotaPeakPagedPoolUsage;
            public BaseTSD.SIZE_T QuotaPagedPoolUsage;
            public BaseTSD.SIZE_T QuotaPeakNonPagedPoolUsage;
            public BaseTSD.SIZE_T QuotaNonPagedPoolUsage;
            public BaseTSD.SIZE_T PagefileUsage;
            public BaseTSD.SIZE_T PeakPagefileUsage;

            public PROCESS_MEMORY_COUNTERS() {
                cb = size();
            }
        }

        boolean GetProcessMemoryInfo(HANDLE hProcess, PROCESS_MEMORY_COUNTERS counters, int cb);
    }

    // ── Listener interface ───────────────────────────────────────────────────
    @FunctionalInterface
    public interface MemoryWarningListener {
        /**
         * Invoked whenever a low memory warning is thrown.
         *
         * @param currentBytes    Current process memory in bytes.
         * @param previousBytes   Previous warned memory in bytes (0 if first warning).
         * @param isFirstWarning  {@code true} if this is the initial > 1 GB warning.
         * @param message         Human-readable description of the warning.
         */
        void onLowMemoryWarning(long currentBytes, long previousBytes, boolean isFirstWarning, String message);
    }

    private final List<MemoryWarningListener> listeners = new CopyOnWriteArrayList<>();

    // ── Monitor configuration & state ────────────────────────────────────────
    private volatile long thresholdBytes = DEFAULT_THRESHOLD_BYTES;
    private volatile long growthStepBytes = DEFAULT_GROWTH_STEP_BYTES;
    private volatile long cooldownMs = DEFAULT_COOLDOWN_MS;
    private volatile long checkIntervalMs = DEFAULT_CHECK_INTERVAL_MS;

    private volatile boolean running = false;
    private Thread monitorThread = null;

    private boolean hasExceeded = false;
    private long lastWarnedBytes = 0L;
    private long lastWarningTimeMs = 0L;

    private ProcessMemoryMonitor() {
    }

    // ── Lifecycle management ─────────────────────────────────────────────────

    /**
     * Starts the background monitoring daemon thread.
     */
    public synchronized void start() {
        if (running) return;
        running = true;
        monitorThread = new Thread(this::monitorLoop, "Athenis-MemoryMonitor");
        monitorThread.setDaemon(true);
        monitorThread.setPriority(Thread.MIN_PRIORITY);
        monitorThread.start();
    }

    /**
     * Stops the background monitoring daemon thread.
     */
    public synchronized void stop() {
        running = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    private void monitorLoop() {
        while (running) {
            try {
                checkMemory();
                Thread.sleep(checkIntervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // Keep the watchdog alive despite unexpected measurement errors
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }
    }

    // ── Memory Measurement ───────────────────────────────────────────────────

    /**
     * Obtains the current process memory in bytes.
     * <p>
     * On Windows, queries {@code GetProcessMemoryInfo} for the process Working Set
     * and Commit charge (Pagefile usage). Falls back to {@code com.sun.management.OperatingSystemMXBean}
     * and JVM runtime memory if OS counters are unavailable.
     *
     * @return Process memory in bytes.
     */
    public long getProcessMemoryBytes() {
        long mem = 0L;

        // 1. Windows Psapi GetProcessMemoryInfo (Working Set & Commit charge)
        try {
            PsapiExt.PROCESS_MEMORY_COUNTERS counters = new PsapiExt.PROCESS_MEMORY_COUNTERS();
            if (PsapiExt.INSTANCE.GetProcessMemoryInfo(Kernel32.INSTANCE.GetCurrentProcess(), counters, counters.size())) {
                long ws = counters.WorkingSetSize.longValue();
                long pf = counters.PagefileUsage.longValue();
                mem = Math.max(ws, pf);
            }
        } catch (Throwable ignored) {
        }

        // 2. Fall back or compare with com.sun.management.OperatingSystemMXBean
        if (mem <= 0) {
            try {
                java.lang.management.OperatingSystemMXBean raw = ManagementFactory.getOperatingSystemMXBean();
                if (raw instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                    long committed = sunBean.getCommittedVirtualMemorySize();
                    if (committed > 0) {
                        mem = committed;
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. Fall back to JVM Runtime memory or take max with JVM usage
        long jvmTotal = Runtime.getRuntime().totalMemory();
        long jvmUsed = jvmTotal - Runtime.getRuntime().freeMemory();
        if (mem <= 0) {
            mem = jvmTotal;
        } else {
            mem = Math.max(mem, jvmUsed);
        }

        return mem;
    }

    // ── Check & Warning Trigger ──────────────────────────────────────────────

    /**
     * Checks current process memory against the warning threshold and growth steps.
     *
     * @return {@code true} if a warning was emitted.
     */
    public boolean checkMemory() {
        long currentBytes = getProcessMemoryBytes();
        long now = System.currentTimeMillis();
        return checkMemory(currentBytes, now);
    }

    /**
     * Core evaluation logic: determines whether a warning should be thrown.
     *
     * @param currentBytes Current memory in bytes.
     * @param nowMs        Current timestamp in milliseconds.
     * @return {@code true} if a warning was thrown.
     */
    public synchronized boolean checkMemory(long currentBytes, long nowMs) {
        if (currentBytes <= thresholdBytes) {
            if (hasExceeded) {
                // Process memory dropped back below 1 GB (e.g. GC cycle or cache cleared)
                hasExceeded = false;
                lastWarnedBytes = 0L;
                lastWarningTimeMs = 0L;
            }
            return false;
        }

        // Memory exceeds threshold (e.g. > 1 GB)
        if (!hasExceeded) {
            // First time exceeding 1 GB
            hasExceeded = true;
            lastWarnedBytes = currentBytes;
            lastWarningTimeMs = nowMs;

            long mb = currentBytes / (1024 * 1024);
            String toast = "Usage: " + mb + " MB (> 1 GB)";
            String console = "Low memory warning: process memory exceeded 1 GB threshold (currently " + mb + " MB).";

            dispatchWarning(currentBytes, 0L, true, toast, console);
            return true;
        }

        // Already exceeded 1 GB: check if it has grown and cooldown has elapsed
        boolean grew = currentBytes >= (lastWarnedBytes + growthStepBytes);
        boolean cooldownPassed = (nowMs - lastWarningTimeMs >= cooldownMs);

        // If memory dropped significantly while still over 1 GB, adjust watermark downward
        if (currentBytes < lastWarnedBytes - growthStepBytes) {
            lastWarnedBytes = currentBytes;
        }

        if (grew && cooldownPassed) {
            long delta = currentBytes - lastWarnedBytes;
            long deltaMb = delta / (1024 * 1024);
            long mb = currentBytes / (1024 * 1024);

            long prevBytes = lastWarnedBytes;
            lastWarnedBytes = currentBytes;
            lastWarningTimeMs = nowMs;

            String toast = "Grew to " + mb + " MB (+" + deltaMb + " MB)";
            String console = "Low memory warning: process memory grew to " + mb + " MB (+" + deltaMb + " MB).";

            dispatchWarning(currentBytes, prevBytes, false, toast, console);
            return true;
        }

        return false;
    }

    private void dispatchWarning(long currentBytes, long previousBytes, boolean isFirst, String toastMessage, String consoleMessage) {
        // 1. Overlay toast notification
        NotificationManager.push("Low Memory", false, toastMessage);

        // 2. Central Console log (WARN level, rendered in yellow in both launcher and overlay console)
        ConsoleManager.getInstance().log("WARN", "[Memory] " + consoleMessage);

        // 3. Stderr
        System.err.println("[Memory] " + consoleMessage);

        // 4. External listeners
        for (MemoryWarningListener listener : listeners) {
            try {
                listener.onLowMemoryWarning(currentBytes, previousBytes, isFirst, consoleMessage);
            } catch (Exception ignored) {
            }
        }
    }

    // ── Listener & Configuration Accessors ───────────────────────────────────

    public void addListener(MemoryWarningListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(MemoryWarningListener listener) {
        listeners.remove(listener);
    }

    public synchronized void reset() {
        hasExceeded = false;
        lastWarnedBytes = 0L;
        lastWarningTimeMs = 0L;
    }

    public long getThresholdBytes() {
        return thresholdBytes;
    }

    public void setThresholdBytes(long thresholdBytes) {
        this.thresholdBytes = thresholdBytes;
    }

    public long getGrowthStepBytes() {
        return growthStepBytes;
    }

    public void setGrowthStepBytes(long growthStepBytes) {
        this.growthStepBytes = growthStepBytes;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public void setCooldownMs(long cooldownMs) {
        this.cooldownMs = cooldownMs;
    }

    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    public void setCheckIntervalMs(long checkIntervalMs) {
        this.checkIntervalMs = checkIntervalMs;
    }

    public synchronized boolean hasExceeded() {
        return hasExceeded;
    }

    public synchronized long getLastWarnedBytes() {
        return lastWarnedBytes;
    }

    public synchronized long getLastWarningTimeMs() {
        return lastWarningTimeMs;
    }
}
