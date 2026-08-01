package me.venixpll.cheat.module.impl.aimbot;

/**
 * Defines available aimbot mode types.
 */
public enum AimType {
    CLASSIC("Classic"),
    PID_SPRING("PID Spring");

    private final String displayName;

    AimType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * @return Array of display names for all aim types, suitable for ModeSetting choices.
     */
    public static String[] getDisplayNames() {
        AimType[] types = values();
        String[] names = new String[types.length];
        for (int i = 0; i < types.length; i++) {
            names[i] = types[i].displayName;
        }
        return names;
    }
}
