package me.venixpll.skinchanger;

public class SkinInfo {
    public int paint;
    public boolean bUsesOldModel;
    public String name;
    public WeaponsEnum weaponType;
    public float wear;
    public int seed;
    public SkinInfo(int paint, boolean bUsesOldModel, String name, WeaponsEnum weaponType) {
        this(paint, bUsesOldModel, name, weaponType, 0.001f, 1);
    }

    public SkinInfo(int paint, boolean bUsesOldModel, String name, WeaponsEnum weaponType, float wear, int seed) {
        this.paint = paint;
        this.bUsesOldModel = bUsesOldModel;
        this.name = name;
        this.weaponType = weaponType;
        this.wear = wear;
        this.seed = seed;
    }
}
