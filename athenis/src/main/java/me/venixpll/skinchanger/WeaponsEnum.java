package me.venixpll.skinchanger;

public enum WeaponsEnum {
    NONE(0, "None"),
    DEAGLE(1, "Desert Eagle"),
    ELITE(2, "Dual Berettas"),
    FIVESEVEN(3, "Five-SeveN"),
    GLOCK(4, "Glock-18"),
    AK47(7, "AK-47"),
    AUG(8, "AUG"),
    AWP(9, "AWP"),
    FAMAS(10, "FAMAS"),
    G3SG1(11, "G3SG1"),
    GALIL(13, "Galil AR"),
    M249(14, "M249"),
    M4A4(16, "M4A4"),
    MAC10(17, "MAC-10"),
    P90(19, "P90"),
    MP5SD(23, "MP5-SD"),
    UMP45(24, "UMP-45"),
    XM1014(25, "XM1014"),
    BIZON(26, "PP-Bizon"),
    MAG7(27, "MAG-7"),
    NEGEV(28, "Negev"),
    SAWEDOFF(29, "Sawed-Off"),
    TEC9(30, "Tec-9"),
    ZEUS(31, "Zeus x27"),
    P2000(32, "P2000"),
    MP7(33, "MP7"),
    MP9(34, "MP9"),
    NOVA(35, "Nova"),
    P250(36, "P250"),
    SCAR20(38, "SCAR-20"),
    SG556(39, "SG 553"),
    SSG08(40, "SSG 08"),
    M4A1_S(60, "M4A1-S"),
    USP_S(61, "USP-S"),
    CZ75(63, "CZ75-Auto"),
    REVOLVER(64, "R8 Revolver");

    private final int id;
    private final String displayName;

    WeaponsEnum(int id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public int getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static WeaponsEnum fromId(int id) {
        for (WeaponsEnum w : values()) {
            if (w.id == id) return w;
        }
        return NONE;
    }
}
