package me.venixpll;

import me.venixpll.launcher.LauncherWindow;

import javax.swing.*;

/**
 * Application entry point.
 * <p>
 * Simply launches the {@link LauncherWindow} on the Swing Event Dispatch Thread.
 * All bootstrap logic (offset loading, module registration, memory thread startup,
 * and overlay creation) is driven from the launcher GUI via its START button.
 */
public class Main {

    /**
     * Program entry point.
     *
     * @param args Command-line arguments (not used).
     */
    public static void main(String[] args) {
        // Show the launcher GUI on the EDT — all further work originates from there.
        SwingUtilities.invokeLater(LauncherWindow::new);
    }
}