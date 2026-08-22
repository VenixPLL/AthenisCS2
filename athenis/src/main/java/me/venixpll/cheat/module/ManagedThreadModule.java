package me.venixpll.cheat.module;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Base class for modules that run a dedicated background worker thread whose
 * lifetime is tied to the module's enabled state.
 *
 * <p>This class centralizes the thread-lifecycle boilerplate that was previously
 * duplicated in every threaded module (volatile running flag, daemon thread with
 * max priority, start-on-enable / stop-on-disable polling in {@code onTick()},
 * interrupted-flag handling and per-iteration exception logging).
 *
 * <h3>Lifecycle</h3>
 * <ol>
 *   <li>{@link #onUpdate()} — called every tick before lifecycle management
 *       (override for per-tick housekeeping such as UI state sync).</li>
 *   <li>{@link #onWorkerStarting()} — called once before the thread spawns;
 *       return {@code false} to abort startup (e.g. resource init failure).</li>
 *   <li>{@link #runLoop()} — executed repeatedly until the module is disabled
 *       or the thread is interrupted. Exceptions are caught and logged so one
 *       bad iteration never kills the worker.</li>
 *   <li>{@link #onWorkerStopping()} — called once when the worker is asked to
 *       stop; use for safety cleanup (releasing keys, restoring patched memory).</li>
 * </ol>
 *
 * <h3>Stop-flag generations</h3>
 * Every spawned worker receives its <em>own</em> {@link AtomicBoolean} stop
 * flag. Stopping flips only the current generation's flag, so a rapid
 * disable→enable cycle can never leave an old worker running: the old worker
 * observes its own (already-set) flag and exits even though a new worker was
 * started with a fresh flag. This eliminates the duplicate-worker race that a
 * single shared boolean would have.
 */
public abstract class ManagedThreadModule extends CheatModule {

    private final String threadName;

    private Thread workerThread;

    /** Stop flag of the current worker generation (replaced on every spawn). */
    private volatile AtomicBoolean workerStopFlag;

    /**
     * Constructs a managed-thread module.
     *
     * @param name           Unique user-friendly name of the module.
     * @param category       The safety/execution category.
     * @param menuGroup      The sidebar category group this module appears in.
     * @param defaultEnabled Initial state of the module.
     * @param threadName     Name assigned to the spawned worker thread.
     */
    protected ManagedThreadModule(String name, ModuleCategory category, MenuGroup menuGroup,
                                  boolean defaultEnabled, String threadName) {
        super(name, category, menuGroup, defaultEnabled);
        this.threadName = threadName;
    }

    /**
     * Drives the worker lifecycle from the slow data-loop tick:
     * spawns the worker when enabled, stops it when disabled.
     */
    @Override
    public final void onTick() {
        onUpdate();

        if (isEnabled()) {
            ensureWorkerStarted();
        } else if (workerStopFlag != null && !workerStopFlag.get()) {
            requestWorkerStop();
        }
    }

    /**
     * Called every tick before lifecycle management.
     * Override for per-tick housekeeping (e.g. syncing setting visibility).
     */
    protected void onUpdate() {
    }

    /**
     * One iteration of the worker loop. Invoked repeatedly on the worker
     * thread while the module is enabled. Use {@code return} where the old
     * inline loops used {@code continue}.
     *
     * @throws InterruptedException when the worker is being stopped mid-sleep;
     *         the base class converts this into a clean thread exit.
     * @throws Exception any other failure is logged and the loop continues.
     */
    protected abstract void runLoop() throws Exception;

    /**
     * Called once on the tick thread before the worker thread spawns.
     * Override to initialize resources (e.g. AWT Robot).
     *
     * @return {@code false} to abort worker startup this tick
     *         (startup will be retried on subsequent ticks).
     */
    protected boolean onWorkerStarting() {
        return true;
    }

    /**
     * Called once when the worker is asked to stop (module disabled).
     * Override to perform safety cleanup — releasing held keys, restoring
     * patched memory, etc. Exceptions are caught and logged.
     */
    protected void onWorkerStopping() {
    }

    /**
     * Starts the worker thread if no worker is currently alive.
     * Synchronized to prevent double-spawn from concurrent ticks.
     */
    private synchronized void ensureWorkerStarted() {
        if (workerThread != null && workerThread.isAlive())
            return;
        if (!onWorkerStarting())
            return;

        AtomicBoolean stopFlag = new AtomicBoolean(false);
        workerStopFlag = stopFlag;
        workerThread = new Thread(() -> runWorker(stopFlag), threadName);
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.MAX_PRIORITY);
        workerThread.start();
    }

    /**
     * Requests the current worker to stop: flips its generation flag, runs
     * cleanup hooks and interrupts the thread so blocking sleeps wake up
     * promptly. Synchronized to avoid racing with
     * {@link #ensureWorkerStarted()}.
     */
    private synchronized void requestWorkerStop() {
        AtomicBoolean flag = workerStopFlag;
        if (flag != null) {
            flag.set(true);
        }

        try {
            onWorkerStopping();
        } catch (Exception e) {
            System.err.println("[" + getName() + "] Cleanup error: " + e.getMessage());
        }

        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
        }
        // Deliberately do NOT clear workerThread here: ensureWorkerStarted()
        // relies on isAlive() to avoid double-spawning while the old worker
        // is still winding down. The reference is replaced on the next start.
    }

    /**
     * Worker entry point: runs the loop until this generation's stop flag is
     * set or the module is disabled, guaranteeing log output on exit.
     *
     * @param stopFlag This worker's own generation flag (never shared).
     */
    private void runWorker(AtomicBoolean stopFlag) {
        System.out.println("[" + getName() + "] Thread started.");
        try {
            while (!stopFlag.get() && isEnabled()) {
                try {
                    runLoop();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[" + getName() + "] Error: " + e.getMessage());
                }
            }
        } finally {
            System.out.println("[" + getName() + "] Thread stopped.");
        }
    }
}