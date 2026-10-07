package me.venixpll.console;

/**
 * Functional interface / contract for console commands.
 */
public interface ConsoleCommand {

    /** The primary name / trigger for the command (e.g. "help", "toggle"). */
    String getName();

    /** Human-readable description of what this command does. */
    String getDescription();

    /** Usage syntax example (e.g. "toggle <module_name>"). */
    String getUsage();

    /**
     * Executes the command with the provided tokenized arguments.
     *
     * @param args The command arguments (excluding the command name itself).
     * @param console The console manager to output replies or query state.
     */
    void execute(String[] args, ConsoleManager console);
}
