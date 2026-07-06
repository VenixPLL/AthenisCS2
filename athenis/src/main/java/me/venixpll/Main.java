package me.venixpll;

import me.venixpll.launcher.LauncherWindow;

import javax.swing.*;
import java.io.InputStream;
import java.util.Properties;

/**
 * Application entry point.
 * <p>
 * Simply launches the {@link LauncherWindow} on the Swing Event Dispatch Thread.
 * All bootstrap logic (offset loading, module registration, memory thread startup,
 * and overlay creation) is driven from the launcher GUI via its START button.
 */
public class Main {

    /**
     * The current version of the program, dynamically loaded from project properties.
     */
    public static final String VERSION = loadVersion();

    private static String loadVersion() {
        try (InputStream is = Main.class.getResourceAsStream("/version.properties")) {
            if (is != null) {
                Properties prop = new Properties();
                prop.load(is);
                return prop.getProperty("version", "unknown");
            }
        } catch (Exception e) {
            System.exit(-1);
        }
        return "unknown";
    }

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