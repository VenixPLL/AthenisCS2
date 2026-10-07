package me.venixpll.console;

import me.venixpll.Main;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.MemoryLoop;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleManager;
import me.venixpll.cheat.module.impl.ESPModule;
import me.venixpll.config.ConfigManager;
import me.venixpll.overlay.PerformanceMonitor;
import me.venixpll.overlay.ProcessMemoryMonitor;

import java.awt.event.KeyEvent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central manager for console logs, command execution, and listeners across
 * both the LauncherWindow and the in-game OverlayMenu console.
 */
public final class ConsoleManager {

    private static final ConsoleManager INSTANCE = new ConsoleManager();

    public static ConsoleManager getInstance() {
        return INSTANCE;
    }

    private static final int MAX_LOGS = 2500;

    private final List<LogEntry> logs = new CopyOnWriteArrayList<>();
    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, ConsoleCommand> commands = new ConcurrentHashMap<>();
    private final List<String> commandHistory = new CopyOnWriteArrayList<>();

    private ConsoleManager() {
        registerDefaultCommands();
    }

    // ── Logging API ──────────────────────────────────────────────────────────

    /**
     * Appends a log line to the console buffer and notifies all registered listeners.
     *
     * @param level  Log level ("INFO", "WARN", "ERROR", "INPUT", "SUCCESS", "DEBUG").
     * @param rawMsg Raw message content.
     */
    public void log(String level, String rawMsg) {
        if (rawMsg == null) return;
        LogEntry entry = new LogEntry(level, rawMsg);
        addEntry(entry);
    }

    /**
     * Appends a log line with an explicit tag to the console buffer.
     *
     * @param level   Log level.
     * @param tag     Tag label (e.g. "[CS2Memory]").
     * @param message Log message.
     */
    public void log(String level, String tag, String message) {
        LogEntry entry = new LogEntry(level, tag, message);
        addEntry(entry);
    }

    private void addEntry(LogEntry entry) {
        logs.add(entry);
        if (logs.size() > MAX_LOGS) {
            // Trim oldest entries
            int removeCount = logs.size() - MAX_LOGS;
            for (int i = 0; i < removeCount && !logs.isEmpty(); i++) {
                logs.remove(0);
            }
        }
        for (Consumer<LogEntry> listener : listeners) {
            try {
                listener.accept(entry);
            } catch (Exception ignored) {
            }
        }
    }

    public List<LogEntry> getLogs() {
        return Collections.unmodifiableList(logs);
    }

    public void clear() {
        logs.clear();
        log("INFO", "[Console] Console cleared.");
    }

    public void addListener(Consumer<LogEntry> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<LogEntry> listener) {
        listeners.remove(listener);
    }

    public List<String> getCommandHistory() {
        return Collections.unmodifiableList(commandHistory);
    }

    // ── Command Handling ─────────────────────────────────────────────────────

    public void registerCommand(ConsoleCommand command) {
        commands.put(command.getName().toLowerCase(), command);
    }

    public ConsoleCommand getCommand(String name) {
        if (name == null) return null;
        return commands.get(name.toLowerCase());
    }

    public Collection<ConsoleCommand> getAllCommands() {
        return Collections.unmodifiableCollection(commands.values());
    }

    /**
     * Executes a raw command line input, logs it with an INPUT prompt, and runs the handler.
     *
     * @param commandLine The raw string entered by the user.
     */
    public void executeCommand(String commandLine) {
        if (commandLine == null || commandLine.trim().isEmpty()) {
            return;
        }
        String trimmed = commandLine.trim();

        // Echo the user input to console with cyan INPUT level
        log("INPUT", "> " + trimmed);

        // Record history (avoid duplicates at end)
        if (commandHistory.isEmpty() || !commandHistory.get(commandHistory.size() - 1).equalsIgnoreCase(trimmed)) {
            commandHistory.add(trimmed);
        }

        // Tokenize command line with quote support
        String[] tokens = tokenize(trimmed);
        if (tokens.length == 0) return;

        String cmdName = tokens[0].toLowerCase();
        String[] args = new String[tokens.length - 1];
        System.arraycopy(tokens, 1, args, 0, args.length);

        ConsoleCommand cmd = commands.get(cmdName);
        if (cmd != null) {
            try {
                cmd.execute(args, this);
            } catch (Exception ex) {
                log("ERROR", "[Console] Command error: " + ex.getMessage());
            }
        } else {
            log("ERROR", "[Console] Unknown command: '" + cmdName + "'. Type 'help' for available commands.");
        }
    }

    private static String[] tokenize(String input) {
        List<String> list = new ArrayList<>();
        Matcher m = Pattern.compile("([^\\\"\\s]\\S*|\".+?\")\\s*").matcher(input);
        while (m.find()) {
            String token = m.group(1);
            if (token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2) {
                token = token.substring(1, token.length() - 1);
            }
            list.add(token);
        }
        return list.toArray(new String[0]);
    }

    private CheatModule findModule(String name) {
        if (name == null || name.isEmpty()) return null;
        String normalized = name.replace(" ", "").toLowerCase();
        for (CheatModule m : ModuleManager.getModules()) {
            String mNorm = m.getName().replace(" ", "").toLowerCase();
            if (mNorm.equals(normalized) || mNorm.contains(normalized)) {
                return m;
            }
        }
        return null;
    }

    // ── Default Commands Registration ───────────────────────────────────────

    private void registerDefaultCommands() {
        // Help
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "help"; }
            @Override
            public String getDescription() { return "Displays all available console commands."; }
            @Override
            public String getUsage() { return "help [command]"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                if (args.length > 0) {
                    ConsoleCommand target = commands.get(args[0].toLowerCase());
                    if (target != null) {
                        console.log("INFO", "Command: " + target.getName());
                        console.log("INFO", "  Usage: " + target.getUsage());
                        console.log("INFO", "  Desc:  " + target.getDescription());
                    } else {
                        console.log("ERROR", "Command '" + args[0] + "' not found.");
                    }
                    return;
                }
                console.log("INFO", "─── Available Athenis Commands ───");
                List<ConsoleCommand> sorted = new ArrayList<>(commands.values());
                sorted.sort(Comparator.comparing(ConsoleCommand::getName));
                for (ConsoleCommand c : sorted) {
                    console.log("INFO", String.format("  %-12s - %s", c.getName(), c.getDescription()));
                }
                console.log("INFO", "Type 'help <command>' for specific syntax.");
            }
        });

        // Clear
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "clear"; }
            @Override
            public String getDescription() { return "Clears the console log window."; }
            @Override
            public String getUsage() { return "clear"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                console.clear();
            }
        });

        // Status
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "status"; }
            @Override
            public String getDescription() { return "Prints CS2 attachment status and memory metrics."; }
            @Override
            public String getUsage() { return "status"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                console.log("INFO", "─── Athenis Engine Status ───");
                console.log("INFO", "  Attached:      " + (CS2Memory.isAttached() ? "YES (PID " + CS2Memory.getProcessId() + ")" : "NO"));
                console.log("INFO", "  Client Base:   0x" + Long.toHexString(CS2Memory.getClientBase()));
                console.log("INFO", "  Engine Base:   0x" + Long.toHexString(CS2Memory.getEngine2Base()));
                console.log("INFO", "  Local Team:    " + (ESPModule.localTeam == 2 ? "Terrorists (T)" : (ESPModule.localTeam == 3 ? "Counter-Terrorists (CT)" : "None / Spectator")));
                console.log("INFO", "  Screen Size:   " + (int)PlayerCache.screenWidth + "x" + (int)PlayerCache.screenHeight);
                console.log("INFO", "  Active Mod:    " + ModuleManager.getModules().stream().filter(CheatModule::isEnabled).count() + " / " + ModuleManager.getModules().size());
                console.log("INFO", "  Process Mem:   " + (ProcessMemoryMonitor.getInstance().getProcessMemoryBytes() / (1024 * 1024)) + " MB");
                console.log("INFO", "  Fast Tick:     " + MemoryLoop.getFastTickFormatted() + " (" + String.format(Locale.US, "%.1f", MemoryLoop.getFastTickRateHz()) + " Hz)");
                console.log("INFO", "  Slow Tick:     " + MemoryLoop.getSlowTickFormatted() + " (" + String.format(Locale.US, "%.1f", MemoryLoop.getSlowTickRateHz()) + " Hz)");
            }
        });

        // Perf
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "perf"; }
            @Override
            public String getDescription() { return "Displays real-time performance metrics (CPU, RAM, fast/slow thread ticks)."; }
            @Override
            public String getUsage() { return "perf"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                ProcessMemoryMonitor pmm = ProcessMemoryMonitor.getInstance();
                Runtime rt = Runtime.getRuntime();
                long heapUsedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
                long heapMaxMb = rt.maxMemory() / (1024 * 1024);
                long procMb = pmm.getProcessMemoryBytes() / (1024 * 1024);

                console.log("INFO", "─── Athenis Performance Metrics ───");
                console.log("INFO", String.format(Locale.US, "  Process RAM:   %d MB", procMb));
                console.log("INFO", String.format(Locale.US, "  JVM Heap:      %d / %d MB", heapUsedMb, heapMaxMb));
                console.log("INFO", String.format(Locale.US, "  CPU (Process): %.1f %%", PerformanceMonitor.getLatestProcessCpu() * 100f));
                console.log("INFO", String.format(Locale.US, "  CPU (System):  %.1f %%", PerformanceMonitor.getLatestSystemCpu() * 100f));
                console.log("INFO", String.format(Locale.US, "  Fast Thread:   %s (Avg: %.3f ms, %.1f Hz)",
                        MemoryLoop.getFastTickFormatted(),
                        MemoryLoop.getFastTickAvgMs(),
                        MemoryLoop.getFastTickRateHz()));
                console.log("INFO", String.format(Locale.US, "  Slow Thread:   %s (Avg: %.3f ms, %.1f Hz)",
                        MemoryLoop.getSlowTickFormatted(),
                        MemoryLoop.getSlowTickAvgMs(),
                        MemoryLoop.getSlowTickRateHz()));
            }
        });

        // Modules / List
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "modules"; }
            @Override
            public String getDescription() { return "Lists all registered cheat modules and their states."; }
            @Override
            public String getUsage() { return "modules"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                console.log("INFO", "─── Registered Cheat Modules ───");
                for (CheatModule m : ModuleManager.getModules()) {
                    String state = m.isEnabled() ? "[ENABLED]" : "[DISABLED]";
                    String keyText = m.getBindKey() > 0 ? ("Key: " + KeyEvent.getKeyText(m.getBindKey())) : "No bind";
                    console.log(m.isEnabled() ? "SUCCESS" : "INFO", String.format("  %-22s %-12s (Group: %-8s, %s)", m.getName(), state, m.getMenuGroup().label, keyText));
                }
            }
        });

        // Toggle
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "toggle"; }
            @Override
            public String getDescription() { return "Toggles a module on or off."; }
            @Override
            public String getUsage() { return "toggle <module_name>"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                if (args.length == 0) {
                    console.log("ERROR", "Usage: " + getUsage());
                    return;
                }
                String targetName = String.join(" ", args);
                CheatModule m = findModule(targetName);
                if (m != null) {
                    m.setEnabled(!m.isEnabled());
                    console.log("SUCCESS", "[Module] " + m.getName() + " is now " + (m.isEnabled() ? "ENABLED" : "DISABLED"));
                } else {
                    console.log("ERROR", "[Module] Could not find module matching '" + targetName + "'");
                }
            }
        });

        // Enable
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "enable"; }
            @Override
            public String getDescription() { return "Enables a specific module."; }
            @Override
            public String getUsage() { return "enable <module_name>"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                if (args.length == 0) {
                    console.log("ERROR", "Usage: " + getUsage());
                    return;
                }
                CheatModule m = findModule(String.join(" ", args));
                if (m != null) {
                    m.setEnabled(true);
                    console.log("SUCCESS", "[Module] " + m.getName() + " is now ENABLED");
                } else {
                    console.log("ERROR", "[Module] Module not found.");
                }
            }
        });

        // Disable
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "disable"; }
            @Override
            public String getDescription() { return "Disables a specific module."; }
            @Override
            public String getUsage() { return "disable <module_name>"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                if (args.length == 0) {
                    console.log("ERROR", "Usage: " + getUsage());
                    return;
                }
                CheatModule m = findModule(String.join(" ", args));
                if (m != null) {
                    m.setEnabled(false);
                    console.log("INFO", "[Module] " + m.getName() + " is now DISABLED");
                } else {
                    console.log("ERROR", "[Module] Module not found.");
                }
            }
        });

        // Bind
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "bind"; }
            @Override
            public String getDescription() { return "Binds a module to a key code or key name (e.g. 'bind esp f1', 'bind aimbot 0' to clear)."; }
            @Override
            public String getUsage() { return "bind <module_name> <key_name_or_code>"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                if (args.length < 2) {
                    console.log("ERROR", "Usage: " + getUsage());
                    return;
                }
                String keyArg = args[args.length - 1];
                StringBuilder modName = new StringBuilder();
                for (int i = 0; i < args.length - 1; i++) {
                    if (i > 0) modName.append(" ");
                    modName.append(args[i]);
                }
                CheatModule m = findModule(modName.toString());
                if (m == null) {
                    console.log("ERROR", "[Module] Module '" + modName + "' not found.");
                    return;
                }
                int vk = 0;
                try {
                    vk = Integer.parseInt(keyArg);
                } catch (NumberFormatException e) {
                    // Try parsing key name
                    for (int i = 0; i < 256; i++) {
                        String text = KeyEvent.getKeyText(i);
                        if (text.equalsIgnoreCase(keyArg)) {
                            vk = i;
                            break;
                        }
                    }
                }
                m.setBindKey(vk);
                ConfigManager.save();
                console.log("SUCCESS", "[Bind] " + m.getName() + " bound to " + (vk > 0 ? KeyEvent.getKeyText(vk) + " (VK " + vk + ")" : "NONE"));
            }
        });

        // Savecfg
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "savecfg"; }
            @Override
            public String getDescription() { return "Saves the current configuration to disk."; }
            @Override
            public String getUsage() { return "savecfg"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                ConfigManager.save();
                console.log("SUCCESS", "[Config] Settings successfully saved to disk.");
            }
        });

        // Loadcfg
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "loadcfg"; }
            @Override
            public String getDescription() { return "Reloads settings from disk."; }
            @Override
            public String getUsage() { return "loadcfg"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                ConfigManager.load();
                console.log("SUCCESS", "[Config] Settings successfully reloaded from disk.");
            }
        });

        // Offsets
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "offsets"; }
            @Override
            public String getDescription() { return "Dumps the primary CS2 netvar and pointer offsets."; }
            @Override
            public String getUsage() { return "offsets"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                console.log("INFO", "─── CS2 Offsets ───");
                console.log("INFO", "  dwEntityList:           0x" + Long.toHexString(CS2Offsets.dwEntityList));
                console.log("INFO", "  dwLocalPlayerPawn:      0x" + Long.toHexString(CS2Offsets.dwLocalPlayerPawn));
                console.log("INFO", "  dwLocalPlayerController:0x" + Long.toHexString(CS2Offsets.dwLocalPlayerController));
                console.log("INFO", "  dwViewMatrix:           0x" + Long.toHexString(CS2Offsets.dwViewMatrix));
                console.log("INFO", "  dwViewAngles:           0x" + Long.toHexString(CS2Offsets.dwViewAngles));
                console.log("INFO", "  dwPlantedC4:            0x" + Long.toHexString(CS2Offsets.dwPlantedC4));
            }
        });

        // Panic
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "panic"; }
            @Override
            public String getDescription() { return "Disables all active cheat modules immediately."; }
            @Override
            public String getUsage() { return "panic"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                for (CheatModule m : ModuleManager.getModules()) {
                    if (m.isEnabled()) {
                        m.setEnabled(false);
                    }
                }
                console.log("WARN", "[Panic] All cheat modules disabled.");
            }
        });

        // Echo
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "echo"; }
            @Override
            public String getDescription() { return "Prints given text to the console."; }
            @Override
            public String getUsage() { return "echo <text>"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                console.log("INFO", String.join(" ", args));
            }
        });

        // Hexview
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "hexview"; }
            @Override
            public String getDescription() { return "Opens the Real-Time Memory Hex Viewer window."; }
            @Override
            public String getUsage() { return "hexview [address]"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                me.venixpll.overlay.MemoryHexViewer.setOpen(true);
                if (args.length > 0) {
                    me.venixpll.overlay.MemoryHexViewer.resolveAddress(String.join(" ", args));
                }
                console.log("SUCCESS", "[MemoryViewer] Hex viewer opened.");
            }
        });

        // Version
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "version"; }
            @Override
            public String getDescription() { return "Displays Athenis application version."; }
            @Override
            public String getUsage() { return "version"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                console.log("INFO", "Athenis CS2 Overlay build " + Main.VERSION + " (Java " + System.getProperty("java.version") + ")");
            }
        });

        // Memory
        registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "memory"; }
            @Override
            public String getDescription() { return "Displays Athenis process memory usage and monitor status."; }
            @Override
            public String getUsage() { return "memory"; }
            @Override
            public void execute(String[] args, ConsoleManager console) {
                ProcessMemoryMonitor mon = ProcessMemoryMonitor.getInstance();
                long curMb = mon.getProcessMemoryBytes() / (1024 * 1024);
                long threshMb = mon.getThresholdBytes() / (1024 * 1024);
                long stepMb = mon.getGrowthStepBytes() / (1024 * 1024);
                console.log("INFO", "─── Athenis Process Memory ───");
                console.log("INFO", "  Current Memory:  " + curMb + " MB");
                console.log("INFO", "  Warn Threshold:  " + threshMb + " MB (1 GB)");
                console.log("INFO", "  Growth Step:     " + stepMb + " MB");
                console.log("INFO", "  Exceeded 1GB:    " + (mon.hasExceeded() ? "YES" : "NO"));
            }
        });
    }
}
