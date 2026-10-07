package me.venixpll.cheat;

import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.module.impl.ESPModule;
import me.venixpll.cheat.reader.EntityDataReader;
import me.venixpll.cheat.reader.PositionReader;
import me.venixpll.cheat.reader.ViewMatrixReader;
import me.venixpll.overlay.OverlayWindow;

import java.util.List;
import java.util.Locale;

/**
 * Orchestrates two background daemon threads for CS2 memory polling.
 * <p>
 * <h3>Design: two loops at different rates</h3>
 * Player positions and the view matrix must be as fresh as the current render
 * frame to prevent the ESP boxes from visually lagging behind fast-moving
 * players. Health bars, team colours, and names, on the other hand, change at
 * human reaction speeds — re-reading them every frame wastes kernel call
 * budget.
 * <p>
 * <table border="1">
 * <tr>
 * <th>Thread</th>
 * <th>Rate</th>
 * <th>Reads</th>
 * </tr>
 * <tr>
 * <td><b>Fast Position Loop</b></td>
 * <td>Uncapped (yield-only)</td>
 * <td>View matrix + {@code m_vOldOrigin} per player → screen projection</td>
 * </tr>
 * <tr>
 * <td><b>Slow Data Loop</b></td>
 * <td>~10 Hz (100 ms sleep)</td>
 * <td>Full entity list traversal: health, team, name, pawn address</td>
 * </tr>
 * </table>
 * <p>
 * The fast loop publishes results to {@link PlayerCache#players} (read by the
 * renderer).
 * The slow loop publishes to {@link PlayerCache#rawPlayers} (read by the fast
 * loop for
 * pawn addresses). Both fields are {@code volatile}, so reference swaps are
 * visible
 * across threads without additional synchronization.
 */
public class MemoryLoop {

    private static volatile boolean running = true;

    /**
     * Slow loop target interval in milliseconds — ~10 Hz refresh for entity
     * metadata.
     */
    private static final long SLOW_LOOP_INTERVAL_MS = 100L;

    // ── Performance Metrics (Tick timings & Rate) ───────────────────────────
    private static volatile long fastTickNs = 0L;
    private static volatile double fastTickMs = 0.0;
    private static volatile double fastTickAvgMs = 0.0;
    private static volatile double fastTickRateHz = 0.0;

    private static volatile long slowTickNs = 0L;
    private static volatile double slowTickMs = 0.0;
    private static volatile double slowTickAvgMs = 0.0;
    private static volatile double slowTickRateHz = 0.0;

    public static void recordFastTick(long durationNs) {
        fastTickNs = durationNs;
        double ms = durationNs / 1_000_000.0;
        fastTickMs = ms;
        fastTickAvgMs = (fastTickAvgMs == 0.0) ? ms : (fastTickAvgMs * 0.95 + ms * 0.05);
    }

    public static void recordSlowTick(long durationNs) {
        slowTickNs = durationNs;
        double ms = durationNs / 1_000_000.0;
        slowTickMs = ms;
        slowTickAvgMs = (slowTickAvgMs == 0.0) ? ms : (slowTickAvgMs * 0.95 + ms * 0.05);
    }

    public static void setFastTickRateHz(double hz) {
        fastTickRateHz = hz;
    }

    public static void setSlowTickRateHz(double hz) {
        slowTickRateHz = hz;
    }

    public static long getFastTickNs() { return fastTickNs; }
    public static double getFastTickMs() { return fastTickMs; }
    public static double getFastTickAvgMs() { return fastTickAvgMs; }
    public static double getFastTickRateHz() { return fastTickRateHz; }
    public static String getFastTickFormatted() { return formatDuration(fastTickNs); }
    public static String getFastTickFormattedCompact() { return formatDurationCompact(fastTickNs); }

    public static long getSlowTickNs() { return slowTickNs; }
    public static double getSlowTickMs() { return slowTickMs; }
    public static double getSlowTickAvgMs() { return slowTickAvgMs; }
    public static double getSlowTickRateHz() { return slowTickRateHz; }
    public static String getSlowTickFormatted() { return formatDuration(slowTickNs); }
    public static String getSlowTickFormattedCompact() { return formatDurationCompact(slowTickNs); }

    /**
     * Resets performance metrics (useful for testing and engine stop/start).
     */
    public static void resetMetrics() {
        fastTickNs = 0L;
        fastTickMs = 0.0;
        fastTickAvgMs = 0.0;
        fastTickRateHz = 0.0;

        slowTickNs = 0L;
        slowTickMs = 0.0;
        slowTickAvgMs = 0.0;
        slowTickRateHz = 0.0;
    }

    /**
     * Formats a nanosecond duration, automatically scaling between ns, µs, and ms.
     * Examples:
     *   450 ns       -> "450 ns"
     *   45,200 ns    -> "45.2 µs (0.045 ms)"
     *   1,250,000 ns -> "1.25 ms"
     *   1,200,000,000 ns -> "1.20 s"
     *
     * @param nanos Duration in nanoseconds.
     * @return Formatted string with auto-scaled unit.
     */
    public static String formatDuration(long nanos) {
        if (nanos < 0) return "0 ns";
        if (nanos < 1_000L) {
            return nanos + " ns";
        } else if (nanos < 1_000_000L) {
            return String.format(Locale.US, "%.1f µs (%.3f ms)", nanos / 1000.0, nanos / 1_000_000.0);
        } else if (nanos < 1_000_000_000L) {
            return String.format(Locale.US, "%.2f ms", nanos / 1_000_000.0);
        } else {
            return String.format(Locale.US, "%.2f s", nanos / 1_000_000_000.0);
        }
    }

    /**
     * Compact auto-scaled duration string for small UI labels / graphs:
     *   450 ns    -> "450 ns"
     *   45,200 ns -> "45.2 µs"
     *   1.25 ms   -> "1.25 ms"
     *
     * @param nanos Duration in nanoseconds.
     * @return Compact formatted string.
     */
    public static String formatDurationCompact(long nanos) {
        if (nanos < 0) return "0 ns";
        if (nanos < 1_000L) {
            return nanos + " ns";
        } else if (nanos < 1_000_000L) {
            return String.format(Locale.US, "%.1f µs", nanos / 1000.0);
        } else if (nanos < 1_000_000_000L) {
            return String.format(Locale.US, "%.2f ms", nanos / 1_000_000.0);
        } else {
            return String.format(Locale.US, "%.2f s", nanos / 1_000_000_000.0);
        }
    }

    /**
     * Launches both background daemon threads.
     * <p>
     * Resets the {@code running} flag to {@code true} before spawning threads so
     * that the engine can be cleanly restarted after a previous {@link #stop()}
     * call.
     * The fast position thread is given {@link Thread#MAX_PRIORITY} so the OS
     * scheduler favours it over lower-priority work, keeping latency minimal.
     * Call this after {@link CS2Memory} is configured and {@link CS2Offsets} are
     * loaded.
     */
    public static void start() {
        running = true; // reset so restart after stop() works correctly
        resetMetrics();
        // Reset the entity pipeline diagnostic and discovery state so it fires on the new attach.
        EntityDataReader.resetDiagnosticDump();
        EntityDataReader.resetDiscoveredOffset();
        startFastPositionThread();
        startSlowDataThread();
    }

    /**
     * Signals both background threads to exit at their next iteration boundary.
     * The threads are daemons, so the JVM will also terminate them on shutdown.
     */
    public static void stop() {
        running = false;
    }

    // ── Fast Position Thread ────────────────────────────────────────────────

    /**
     * Starts the fast position-sync daemon thread.
     * <p>
     * This thread runs without any artificial sleep — only {@link Thread#yield()}
     * is called at the end of each iteration as a minimal concession to the OS
     * scheduler. In practice, the kernel overhead of issuing one
     * {@code ReadProcessMemory} call per tracked player naturally limits the loop
     * rate to a few hundred to a thousand iterations per second, which is always
     * faster than any realistic display refresh rate.
     * <p>
     * Each iteration:
     * <ol>
     * <li>Read the current 4×4 view-projection matrix via
     * {@link ViewMatrixReader}.</li>
     * <li>For every player in {@link PlayerCache#rawPlayers}, read
     * {@code m_vOldOrigin} (12 bytes, one RPM call) and project to screen via
     * {@link PositionReader}.</li>
     * <li>Atomically publish the updated list to {@link PlayerCache#players} for
     * the render thread.</li>
     * </ol>
     */
    private static void startFastPositionThread() {
        Thread thread = new Thread(() -> {
            System.out.println("[MemoryLoop/Fast] Position sync thread started.");
            int statusState = -1; // -1 = uninitialized, 0 = detached, 1 = attached
            long lastFastRateNs = System.nanoTime();
            long fastTickCounter = 0;

            while (running) {
                try {
                    // ── Attachment guard ──────────────────────────────────────────
                    if (!CS2Memory.isAttached()) {
                        if (CS2Memory.attach()) {
                            if (statusState != 1) {
                                System.out.println("[MemoryLoop/Fast] Attached to CS2.");
                                // Reset entity diagnostic dump and discovery so it runs fresh for this attach.
                                EntityDataReader.resetDiagnosticDump();
                                EntityDataReader.resetDiscoveredOffset();
                                statusState = 1;
                            }
                            PlayerCache.tracking = true;
                        } else {
                            if (statusState != 0)
                                statusState = 0;
                            PlayerCache.tracking = false;
                            Thread.sleep(1000);
                            continue;
                        }
                    } else if (!CS2Memory.isProcessRunning()) {
                        System.out.println("[MemoryLoop] Counter-Strike 2 process has exited. Stopping engine.");
                        MemoryLoop.stop();
                        OverlayWindow.requestClose();
                        break;
                    }

                    long clientBase = CS2Memory.getClientBase();
                    if (clientBase == 0) {
                        CS2Memory.close();
                        continue;
                    }

                    long tickStartNs = System.nanoTime();

                    // ── 1. Read latest view matrix ──────────────────────────────────
                    // Writes a freshly-allocated float[16] into PlayerCache.viewMatrix
                    // via a volatile reference swap — renderer never sees a half-written matrix.
                    ViewMatrixReader.read(clientBase);

                    // ── 2. Snapshot the current matrix reference once ───────────────
                    // Take a local reference so we use the exact same matrix for
                    // all projections in this iteration, even if ViewMatrixReader
                    // swaps in a new one mid-loop.
                    float[] matrix = PlayerCache.viewMatrix;

                    // ── 3. Build immutable render snapshots ─────────────────────────
                    // rawPlayers is set by the slow loop; we take a snapshot reference
                    // so a slow-loop swap mid-iteration doesn't affect us.
                    List<PlayerCache.PlayerData> raw = PlayerCache.rawPlayers;

                    List<PlayerCache.PlayerSnapshot> snapshots = PositionReader.buildSnapshots(
                            raw,
                            matrix,
                            PlayerCache.screenWidth,
                            PlayerCache.screenHeight);

                    // ── 4. Publish both new and legacy lists atomically ─────────────
                    // volatile writes — render thread and legacy modules see the new
                    // reference on their next read without needing a lock.
                    PlayerCache.renderPlayers = snapshots; // immutable; used by ESPModule
                    PlayerCache.players = raw; // mutable; used by RadarHack etc.

                    long tickDurationNs = System.nanoTime() - tickStartNs;
                    recordFastTick(tickDurationNs);

                    fastTickCounter++;
                    long nowNs = System.nanoTime();
                    if (nowNs - lastFastRateNs >= 1_000_000_000L) {
                        fastTickRateHz = (fastTickCounter * 1_000_000_000.0) / (nowNs - lastFastRateNs);
                        fastTickCounter = 0;
                        lastFastRateNs = nowNs;
                    }

                    // Yield the remainder of the time slice so other threads can run.
                    // We do NOT sleep — any sleep granularity (typically 15 ms on
                    // Windows) would cap us well below high-refresh-rate displays.
                    Thread.yield();

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[MemoryLoop/Fast] Error: " + e.getMessage());
                }
            }

            System.out.println("[MemoryLoop/Fast] Position sync thread terminated.");
        }, "Athenis-FastPosition");

        thread.setDaemon(true);
        thread.setPriority(Thread.MAX_PRIORITY);
        thread.start();
    }

    // ── Slow Data Thread ────────────────────────────────────────────────────

    /**
     * Starts the slow entity-data daemon thread.
     * <p>
     * Runs at {@link #SLOW_LOOP_INTERVAL_MS} (100 ms, ~10 Hz) to perform the
     * full CS2 entity list traversal. At each tick it:
     * <ol>
     * <li>Resolves the local player pawn and reads their team number.</li>
     * <li>Delegates the full entity scan to {@link EntityDataReader#readAll},
     * which batch-reads health + team per player in one RPM call each.</li>
     * <li>Atomically publishes the fresh list to {@link PlayerCache#rawPlayers}
     * so the fast loop picks up new pawn addresses on its next iteration.</li>
     * <li>Ticks all registered {@link CheatModule} instances (logic, not
     * rendering).</li>
     * </ol>
     * <p>
     * 100 ms is more than adequate for health bar updates — a player losing health
     * visibly over less than a tenth of a second is imperceptible on the overlay.
     */
    private static void startSlowDataThread() {
        Thread thread = new Thread(() -> {
            System.out.println("[MemoryLoop/Slow] Entity data thread started.");
            long lastSlowRateNs = System.nanoTime();
            long slowTickCounter = 0;

            while (running) {
                try {
                    // Slow loop must wait for the fast loop to attach first.
                    if (!CS2Memory.isAttached()) {
                        Thread.sleep(1000);
                        continue;
                    }

                    long clientBase = CS2Memory.getClientBase();
                    if (clientBase == 0) {
                        Thread.sleep(SLOW_LOOP_INTERVAL_MS);
                        continue;
                    }

                    long tickStartNs = System.nanoTime();

                    // ── 1. Resolve local player pawn and team ────────────────────────
                    // These rarely change so reading them here at 10 Hz is plenty.
                    long localPlayerPawnAddr = clientBase + CS2Offsets.dwLocalPlayerPawn;
                    long localPlayerPawn = CS2Memory.readLong(localPlayerPawnAddr);

                    if (localPlayerPawn == 0) {
                        // Only log this at verbose level to avoid spam when in main menu
                        if (CS2Memory.isVerboseReadFailures()) {
                            System.err.println("[MemoryLoop/Slow] localPlayerPawn is NULL!"
                                    + " Read from clientBase(0x" + Long.toHexString(clientBase)
                                    + ") + dwLocalPlayerPawn(0x" + Long.toHexString(CS2Offsets.dwLocalPlayerPawn)
                                    + ") = 0x" + Long.toHexString(localPlayerPawnAddr));
                        }
                    } else {
                        int localTeam = CS2Memory.readInt(localPlayerPawn + CS2Offsets.m_iTeamNum);
                        int localHealth = CS2Memory.readInt(localPlayerPawn + CS2Offsets.m_iHealth);
                        if (CS2Memory.isVerboseReadFailures()) {
                            System.out.println("[MemoryLoop/Slow] localPlayerPawn=0x"
                                    + Long.toHexString(localPlayerPawn)
                                    + " team=" + localTeam + " health=" + localHealth);
                        }
                        ESPModule.localTeam = localTeam;
                        PlayerCache.localPlayerPawnAddress = localPlayerPawn;
                    }

                    // ── 2. Full entity list traversal ────────────────────────────────
                    // Reads health, team, name, and pawn address for each living player.
                    // Screen coordinates are left at zero — PositionReader fills them.
                    List<PlayerCache.PlayerData> freshData = EntityDataReader.readAll(clientBase, localPlayerPawn);

                    // ── 3. Publish raw metadata for the fast loop ────────────────────
                    // The fast loop reads rawPlayers to get pawn addresses; the volatile
                    // write makes the new list visible across threads immediately.
                    PlayerCache.rawPlayers = freshData;

                    // ── 4. Tick cheat module logic ───────────────────────────────────
                    // onTick() is for background logic (aim assist, trigger checks etc.),
                    // not for rendering. Running it here at 10 Hz is appropriate.
                    for (CheatModule module : ModuleManager.getModules()) {
                        module.onTick();
                    }

                    long tickDurationNs = System.nanoTime() - tickStartNs;
                    recordSlowTick(tickDurationNs);

                    slowTickCounter++;
                    long nowNs = System.nanoTime();
                    if (nowNs - lastSlowRateNs >= 1_000_000_000L) {
                        slowTickRateHz = (slowTickCounter * 1_000_000_000.0) / (nowNs - lastSlowRateNs);
                        slowTickCounter = 0;
                        lastSlowRateNs = nowNs;
                    }

                    Thread.sleep(SLOW_LOOP_INTERVAL_MS);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[MemoryLoop/Slow] Error: " + e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                    }
                }
            }

            System.out.println("[MemoryLoop/Slow] Entity data thread terminated.");
        }, "Athenis-SlowData");

        thread.setDaemon(true);
        thread.start();
    }
}
