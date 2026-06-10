package me.venixpll.cheat.module;

import java.util.ArrayList;
import java.util.List;

/**
 * Registry and orchestrator for active cheat modules.
 * Holds registered modules and exposes search/lookup functionality.
 */
public class ModuleManager {
    private static final List<CheatModule> modules = new ArrayList<>();

    /**
     * Registers a new cheat module to the system registry.
     *
     * @param module The module instance to add.
     */
    public static void registerModule(CheatModule module) {
        modules.add(module);
    }

    /**
     * Gets all currently registered modules.
     *
     * @return Immutable list clone of registered modules.
     */
    public static List<CheatModule> getModules() {
        return modules;
    }

    /**
     * Searches for a registered module matching the specified class type.
     *
     * @param clazz Target class representing the cheat module.
     * @param <T>   Type of the module.
     * @return Found module instance, or null if not registered.
     */
    @SuppressWarnings("unchecked")
    public static <T extends CheatModule> T getModule(Class<T> clazz) {
        for (CheatModule module : modules) {
            if (clazz.isInstance(module)) {
                return (T) module;
            }
        }
        return null;
    }
}
