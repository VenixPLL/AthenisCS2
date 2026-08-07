package me.venixpll.cheat.module;

/**
 * Defines the sidebar category group that a module (or built-in page) belongs
 * to in the OverlayMenu.
 *
 * <p>This is intentionally separate from {@link ModuleCategory} which describes
 * the <em>execution</em> nature of a module (external / internal / debug).
 * {@code MenuGroup} only controls visual grouping in the left sidebar.
 *
 * <p>Adding a new sidebar section in the future requires only:
 * <ol>
 *   <li>Adding a new constant here, and</li>
 *   <li>Assigning it to the relevant modules / pages.</li>
 * </ol>
 */
public enum MenuGroup {

    /** Visual information modules — ESP, Radar, Crosshair, etc. */
    VISUALS("VISUALS", "eye-48.png"),

    /** Mechanical advantage modules — Aimbot, TriggerBot, BunnyHop, etc. */
    COMBAT("COMBAT", "lightning-50.png"),

    /** Miscellaneous / utility modules. */
    OTHER("OTHER", "globe-50.png"),

    /** Built-in system pages: Settings, Keybinds, etc. */
    SYSTEM("SYSTEM", "settings-48.png");

    // ─────────────────────────────────────────────────────────────────────────

    /** Display label shown in the sidebar above the group. */
    public final String label;

    /**
     * Filename of the icon inside {@code resources/assets/icons/}
     * used for every item belonging to this group.
     */
    public final String iconName;

    MenuGroup(String label, String iconName) {
        this.label    = label;
        this.iconName = iconName;
    }
}
