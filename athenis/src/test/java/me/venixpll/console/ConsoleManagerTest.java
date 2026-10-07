package me.venixpll.console;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class ConsoleManagerTest {

    private ConsoleManager console;

    @BeforeEach
    public void setUp() {
        console = ConsoleManager.getInstance();
        console.clear();
    }

    @Test
    public void testLoggingAndListener() {
        AtomicBoolean received = new AtomicBoolean(false);
        java.util.function.Consumer<LogEntry> listener = entry -> {
            if (entry.getMessage().contains("Test message")) {
                received.set(true);
            }
        };

        console.addListener(listener);
        console.log("INFO", "[TestTag] Test message");

        assertTrue(received.get(), "Listener should receive logged message");
        assertFalse(console.getLogs().isEmpty(), "Logs list should not be empty");

        LogEntry last = console.getLogs().get(console.getLogs().size() - 1);
        assertEquals("INFO", last.getLevel());
        assertEquals("[TestTag]", last.getTag());
        assertEquals("Test message", last.getMessage());

        console.removeListener(listener);
    }

    @Test
    public void testCommandExecution() {
        console.executeCommand("help");
        assertFalse(console.getLogs().isEmpty());

        console.executeCommand("echo Hello Console");
        LogEntry last = console.getLogs().get(console.getLogs().size() - 1);
        assertEquals("Hello Console", last.getMessage());
    }

    @Test
    public void testCustomCommandRegistration() {
        AtomicBoolean customExecuted = new AtomicBoolean(false);
        console.registerCommand(new ConsoleCommand() {
            @Override
            public String getName() { return "mytestcmd"; }
            @Override
            public String getDescription() { return "Custom test command"; }
            @Override
            public String getUsage() { return "mytestcmd"; }
            @Override
            public void execute(String[] args, ConsoleManager c) {
                customExecuted.set(true);
                c.log("SUCCESS", "Executed successfully!");
            }
        });

        console.executeCommand("mytestcmd");
        assertTrue(customExecuted.get(), "Custom command should have executed");
    }
}
