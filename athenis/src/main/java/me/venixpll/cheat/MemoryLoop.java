package me.venixpll.cheat;

import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.module.impl.ESPModule;
import me.venixpll.cheat.reader.EntityDataReader;
import me.venixpll.cheat.reader.PositionReader;
import me.venixpll.cheat.reader.ViewMatrixReader;

import java.util.List;
import me.venixpll.overlay.OverlayWindow;

/**
 * Orchestrates two background daemon threads for CS2 memory polling.
 * <p>
 * <h3>Design: two loops at different rates</h3>
 * Player positions and the view matrix must be as fresh as the current render
 * frame to prevent the ESP boxes from visually lagging behind fast-moving
 * players.  Health bars, team colours, and names, on the other hand, change at
 * human reaction speeds — re-reading them every frame wastes kernel call budget.
 * <p>
 * <table border="1">
 *   <tr><th>Thread</th><th>Rate</th><th>Reads</th></tr>
 *   <tr>
 *     <td><b>Fast Position Loop</b></td>
 *     <td>Uncapped (yield-only)</td>
 *     <td>View matrix + {@code m_vOldOrigin} per player → screen projection</td>
 *   </tr>
 *   <tr>
 *     <td><b>Slow Data Loop</b></td>
 *     <td>~10 Hz (100 ms sleep)</td>
 *     <td>Full entity list traversal: health, team, name, pawn address</td>
 *   </tr>
 * </table>
 * <p>
 * The fast loop publishes results to {@link PlayerCache#players} (read by the renderer).
 * The slow loop publishes to {@link PlayerCache#rawPlayers} (read by the fast loop for
 * pawn addresses).  Both fields are {@code volatile}, so reference swaps are visible
 * across threads without additional synchronization.
 */
public class MemoryLoop {

    private static volatile boolean running = true;

    /** Slow loop target interval in milliseconds — ~10 Hz refresh for entity metadata. */
    private static final long SLOW_LOOP_INTERVAL_MS = 100L;

    /**
     * Launches both background daemon threads.
     * <p>
     * Resets the {@code running} flag to {@code true} before spawning threads so
     * that the engine can be cleanly restarted after a previous {@link #stop()} call.
     * The fast position thread is given {@link Thread#MAX_PRIORITY} so the OS
     * scheduler favours it over lower-priority work, keeping latency minimal.
     * Call this after {@link CS2Memory} is configured and {@link CS2Offsets} are loaded.
     */
    public static void start() {
        running = true;   // reset so restart after stop() works correctly
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

    // ── Fast Position Thread ───────────────────────────────────────────────────

    /**
     * Starts the fast position-sync daemon thread.
     * <p>
     * This thread runs without any artificial sleep — only {@link Thread#yield()}
     * is called at the end of each iteration as a minimal concession to the OS
     * scheduler.  In practice, the kernel overhead of issuing one
     * {@code ReadProcessMemory} call per tracked player naturally limits the loop
     * rate to a few hundred to a thousand iterations per second, which is always
     * faster than any realistic display refresh rate.
     * <p>
     * Each iteration:
     * <ol>
     *   <li>Read the current 4×4 view-projection matrix via {@link ViewMatrixReader}.</li>
     *   <li>For every player in {@link PlayerCache#rawPlayers}, read
     *       {@code m_vOldOrigin} (12 bytes, one RPM call) and project to screen via
     *       {@link PositionReader}.</li>
     *   <li>Atomically publish the updated list to {@link PlayerCache#players} for
     *       the render thread.</li>
     * </ol>
     */
    private static void startFastPositionThread() {
        Thread thread = new Thread(() -> {
            System.out.println("[MemoryLoop/Fast] Position sync thread started.");
            int statusState = -1; // -1 = uninitialized, 0 = detached, 1 = attached

            while (running) {
                try {
                    // ── Attachment guard ──────────────────────────────────────
                    if (!CS2Memory.isAttached()) {
                        if (CS2Memory.attach()) {
                            if (statusState != 1) {
                                System.out.println("[MemoryLoop/Fast] Attached to CS2.");
                                statusState = 1;
                            }
                            PlayerCache.tracking = true;
                        } else {
                            if (statusState != 0) statusState = 0;
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

                    // ── 1. Read latest view matrix ────────────────────────────
                    // Writes a freshly-allocated float[16] into PlayerCache.viewMatrix
                    // via a volatile reference swap — renderer never sees a half-written matrix.
                    ViewMatrixReader.read(clientBase);

                    // ── 2. Snapshot the current matrix reference once ──────────
                    // Take a local reference so we use the exact same matrix for
                    // all projections in this iteration, even if ViewMatrixReader
                    // swaps in a new one mid-loop.
                    float[] matrix = PlayerCache.viewMatrix;

                    // ── 3. Build immutable render snapshots ───────────────────
                    // rawPlayers is set by the slow loop; we take a snapshot reference
                    // so a slow-loop swap mid-iteration doesn't affect us.
                    List<PlayerCache.PlayerData> raw = PlayerCache.rawPlayers;

                    List<PlayerCache.PlayerSnapshot> snapshots = PositionReader.buildSnapshots(
                            raw,
                            matrix,
                            PlayerCache.screenWidth,
                            PlayerCache.screenHeight);

                    // ── 4. Publish both new and legacy lists atomically ────────
                    // volatile writes — render thread and legacy modules see the new
                    // reference on their next read without needing a lock.
                    PlayerCache.renderPlayers = snapshots;   // immutable; used by ESPModule
                    PlayerCache.players = raw;                // mutable; used by RadarHack etc.

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

    // ── Slow Data Thread ───────────────────────────────────────────────────────

    /**
     * Starts the slow entity-data daemon thread.
     * <p>
     * Runs at {@link #SLOW_LOOP_INTERVAL_MS} (100 ms, ~10 Hz) to perform the
     * full CS2 entity list traversal.  At each tick it:
     * <ol>
     *   <li>Resolves the local player pawn and reads their team number.</li>
     *   <li>Delegates the full entity scan to {@link EntityDataReader#readAll},
     *       which batch-reads health + team per player in one RPM call each.</li>
     *   <li>Atomically publishes the fresh list to {@link PlayerCache#rawPlayers}
     *       so the fast loop picks up new pawn addresses on its next iteration.</li>
     *   <li>Ticks all registered {@link CheatModule} instances (logic, not rendering).</li>
     * </ol>
     * <p>
     * 100 ms is more than adequate for health bar updates — a player losing health
     * visibly over less than a tenth of a second is imperceptible on the overlay.
     */
    private static void startSlowDataThread() {
        Thread thread = new Thread(() -> {
            System.out.println("[MemoryLoop/Slow] Entity data thread started.");
            int debugTicks = 0;

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

                    // ── 1. Resolve local player pawn and team ─────────────────
                    // These rarely change so reading them here at 10 Hz is plenty.
                    long localPlayerPawn = CS2Memory.readLong(clientBase + CS2Offsets.dwLocalPlayerPawn);
                    if (localPlayerPawn != 0) {
                        int localTeam = CS2Memory.readInt(localPlayerPawn + CS2Offsets.m_iTeamNum);
                        ESPModule.localTeam                 = localTeam;
                        PlayerCache.localPlayerPawnAddress  = localPlayerPawn;
                    }

                    // ── 2. Full entity list traversal ─────────────────────────
                    // Reads health, team, name, and pawn address for each living player.
                    // Screen coordinates are left at zero — PositionReader fills them.
                    List<PlayerCache.PlayerData> freshData =
                            EntityDataReader.readAll(clientBase, localPlayerPawn);

                    // ── 3. Publish raw metadata for the fast loop ─────────────
                    // The fast loop reads rawPlayers to get pawn addresses; the volatile
                    // write makes the new list visible across threads immediately.
                    PlayerCache.rawPlayers = freshData;

                    // ── 4. Tick cheat module logic ────────────────────────────
                    // onTick() is for background logic (aim assist, trigger checks etc.),
                    // not for rendering.  Running it here at 10 Hz is appropriate.
                    for (CheatModule module : ModuleManager.getModules()) {
                        module.onTick();
                    }

                    // Periodic diagnostics — log once every ~3 seconds (30 ticks × 100 ms).
                    debugTicks++;
                    if (debugTicks % 30 == 0) {
                        System.out.println(String.format(
                                "[MemoryLoop/Slow] Tracking %d player(s). clientBase=0x%X",
                                freshData.size(), clientBase));
                    }

                    Thread.sleep(SLOW_LOOP_INTERVAL_MS);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[MemoryLoop/Slow] Error: " + e.getMessage());
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                }
            }

            System.out.println("[MemoryLoop/Slow] Entity data thread terminated.");
        }, "Athenis-SlowData");

        thread.setDaemon(true);
        thread.start();
    }
}
