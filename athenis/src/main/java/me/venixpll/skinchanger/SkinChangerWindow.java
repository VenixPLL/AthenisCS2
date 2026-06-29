package me.venixpll.skinchanger;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import java.util.List;

public class SkinChangerWindow {
    private static WeaponsEnum selectedWeapon = WeaponsEnum.NONE;
    private static int selectedSkinIdx = 0;
    private static int selectedTeam = 3; // 3 = CT, 2 = T

    private static boolean isWeaponValidForTeam(int team, WeaponsEnum w) {
        return w != WeaponsEnum.NONE;
    }

    public static void render() {
        ImGui.setNextWindowSize(380, 320, ImGuiCond.FirstUseEver);

        // Push colors matching the unified launcher/overlay theme
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.WindowBg, 0.039f, 0.043f, 0.055f, 0.97f);     // C_BG
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.TitleBg, 0.067f, 0.075f, 0.094f, 1.00f);       // C_SURFACE
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.TitleBgActive, 0.067f, 0.075f, 0.094f, 1.00f); // C_SURFACE
        ImGui.pushStyleColor(imgui.flag.ImGuiCol.Border, 0.129f, 0.149f, 0.176f, 1.00f);        // C_BORDER

        // Open a separate ImGui window without NoSavedSettings so position is saved
        if (ImGui.begin("Skin Changer (Edits Memory)")) {

            if (ImGui.checkbox("Enable Skin Changer", SkinChanger.enabled)) {
                if (!SkinChanger.enabled.get()) {
                    SkinChanger.cleanupAllAllocatedBlocks();
                } else {
                    SkinChanger.forceUpdate = true;
                }
                SkinChanger.saveConfig();
            }
            ImGui.separator();

            // Render Team Selector tabs
            if (ImGui.beginTabBar("SkinChangerTeams")) {
                if (ImGui.beginTabItem("Counter-Terrorists")) {
                    if (selectedTeam != 3) {
                        selectedTeam = 3;
                        selectedWeapon = WeaponsEnum.NONE; // reset to let auto-selection / default knife take over
                    }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Terrorists")) {
                    if (selectedTeam != 2) {
                        selectedTeam = 2;
                        selectedWeapon = WeaponsEnum.NONE; // reset
                    }
                    ImGui.endTabItem();
                }
                ImGui.endTabBar();
            }

            WeaponsEnum heldWeapon = SkinChanger.getCurrentWeapon();

            // Auto-select held weapon if valid for the active team
            if (heldWeapon != WeaponsEnum.NONE && isWeaponValidForTeam(selectedTeam, heldWeapon)
                    && selectedWeapon == WeaponsEnum.NONE) {
                selectedWeapon = heldWeapon;
                selectedSkinIdx = 0;
            }

            ImGui.text("Currently Held: " + (heldWeapon == WeaponsEnum.NONE ? "None" : heldWeapon.getDisplayName()));
            ImGui.separator();

            // Weapon Selector
            String weaponPreview = selectedWeapon == WeaponsEnum.NONE ? "Select Weapon"
                    : selectedWeapon.getDisplayName();
            if (ImGui.beginCombo("Weapon", weaponPreview)) {
                for (WeaponsEnum w : WeaponsEnum.values()) {
                    if (!isWeaponValidForTeam(selectedTeam, w))
                        continue;
                    boolean isSelected = (selectedWeapon == w);
                    if (ImGui.selectable(w.getDisplayName(), isSelected)) {
                        selectedWeapon = w;
                        selectedSkinIdx = 0; // reset skin selection
                    }
                    if (isSelected) {
                        ImGui.setItemDefaultFocus();
                    }
                }
                ImGui.endCombo();
            }

            // Skin Selector
            if (selectedWeapon != WeaponsEnum.NONE) {
                List<SkinInfo> skins = SkinDatabase.getSkinsForWeapon(selectedWeapon);
                if (skins.isEmpty()) {
                    ImGui.textDisabled("No skins found in database.");
                } else {
                    // Find currently configured skin index for the selected weapon and team
                    SkinInfo activeSkin = SkinChanger.getSkin(selectedTeam, selectedWeapon);
                    selectedSkinIdx = 0; // Default to Default Skin (index 0)
                    if (activeSkin != null) {
                        for (int i = 0; i < skins.size(); i++) {
                            if (skins.get(i).paint == activeSkin.paint) {
                                selectedSkinIdx = i;
                                break;
                            }
                        }
                    }

                    if (selectedSkinIdx >= skins.size()) {
                        selectedSkinIdx = 0;
                    }

                    String skinPreview = skins.get(selectedSkinIdx).name;
                    if (ImGui.beginCombo("Skin", skinPreview)) {
                        for (int i = 0; i < skins.size(); i++) {
                            boolean isSelected = (selectedSkinIdx == i);
                            if (ImGui.selectable(skins.get(i).name, isSelected)) {
                                selectedSkinIdx = i;
                                // Apply the skin
                                SkinChanger.applySkin(selectedTeam, selectedWeapon, skins.get(i));
                            }
                            if (isSelected) {
                                ImGui.setItemDefaultFocus();
                            }
                        }
                        ImGui.endCombo();
                    }

                    if (activeSkin != null && activeSkin.paint != 0) {
                        ImGui.spacing();

                        float[] wearVal = new float[] { activeSkin.wear };
                        if (ImGui.sliderFloat("Wear Factor", wearVal, 0.0001f, 1.0f, "%.4f")) {
                            activeSkin.wear = wearVal[0];
                            SkinChanger.applySkin(selectedTeam, selectedWeapon, activeSkin);
                        }

                        int[] seedVal = new int[] { activeSkin.seed };
                        if (ImGui.sliderInt("Pattern Seed", seedVal, 1, 1000)) {
                            activeSkin.seed = seedVal[0];
                            SkinChanger.applySkin(selectedTeam, selectedWeapon, activeSkin);
                        }
                    }
                }
            } else {
                ImGui.textDisabled("Select a weapon to view skins.");
            }
        }
        ImGui.end();
        ImGui.popStyleColor(4);
    }
}
