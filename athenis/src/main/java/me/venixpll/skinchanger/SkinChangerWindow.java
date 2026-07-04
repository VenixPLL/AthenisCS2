package me.venixpll.skinchanger;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiCol;
import imgui.type.ImString;

import java.util.ArrayList;
import java.util.List;

public class SkinChangerWindow {
    private static WeaponsEnum selectedWeapon = WeaponsEnum.NONE;
    private static int selectedTeam = 3; // 3 = CT, 2 = T
    private static String searchFilter = "";

    private static boolean isWeaponValidForTeam(int team, WeaponsEnum w) {
        return w != WeaponsEnum.NONE;
    }

    public static void render() {
        ImGui.setNextWindowSize(640, 480, ImGuiCond.FirstUseEver);

        // Push colors matching the unified launcher/overlay theme (Catppuccin Mocha-inspired with cyan accent)
        ImGui.pushStyleColor(ImGuiCol.WindowBg,             0.039f, 0.043f, 0.055f, 0.97f);
        ImGui.pushStyleColor(ImGuiCol.TitleBg,              0.067f, 0.075f, 0.094f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.TitleBgActive,        0.067f, 0.075f, 0.094f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.Border,               0.129f, 0.149f, 0.176f, 1.00f);
        
        ImGui.pushStyleColor(ImGuiCol.FrameBg,              0.015f, 0.017f, 0.022f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.FrameBgHovered,       0.025f, 0.027f, 0.035f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.FrameBgActive,        0.035f, 0.037f, 0.045f, 1.00f);
        
        ImGui.pushStyleColor(ImGuiCol.CheckMark,            0.000f, 0.706f, 0.847f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.SliderGrab,           0.000f, 0.706f, 0.847f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.SliderGrabActive,     0.000f, 0.820f, 0.980f, 1.00f);
        
        ImGui.pushStyleColor(ImGuiCol.Header,               0.000f, 0.706f, 0.847f, 0.45f);
        ImGui.pushStyleColor(ImGuiCol.HeaderHovered,        0.000f, 0.706f, 0.847f, 0.65f);
        ImGui.pushStyleColor(ImGuiCol.HeaderActive,         0.000f, 0.706f, 0.847f, 0.85f);
        
        ImGui.pushStyleColor(ImGuiCol.Button,               0.067f, 0.075f, 0.094f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered,        0.000f, 0.706f, 0.847f, 0.70f);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive,         0.000f, 0.706f, 0.847f, 1.00f);
        
        ImGui.pushStyleColor(ImGuiCol.Tab,                  0.067f, 0.075f, 0.094f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.TabHovered,           0.000f, 0.706f, 0.847f, 0.70f);
        ImGui.pushStyleColor(ImGuiCol.TabActive,            0.000f, 0.706f, 0.847f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.TabUnfocused,         0.039f, 0.043f, 0.055f, 1.00f);
        ImGui.pushStyleColor(ImGuiCol.TabUnfocusedActive,   0.000f, 0.706f, 0.847f, 0.70f);

        // Open window
        if (ImGui.begin("Skin Changer")) {
            // Layout: Left column is Weapon List, Right column is Configuration
            
            // Left Column (Weapon List)
            ImGui.beginChild("WeaponListColumn", 180, 0, true);
            for (WeaponsEnum w : WeaponsEnum.values()) {
                if (w == WeaponsEnum.NONE) continue;
                
                boolean isSelected = (selectedWeapon == w);
                if (ImGui.selectable(w.getDisplayName(), isSelected)) {
                    selectedWeapon = w;
                }
            }
            ImGui.endChild();

            ImGui.sameLine();

            // Right Column (Configuration)
            ImGui.beginChild("ConfigColumn", 0, 0, false);
            
            // Enabled checkbox
            if (ImGui.checkbox("Enabled", SkinChanger.enabled)) {
                if (!SkinChanger.enabled.get()) {
                    SkinChanger.cleanupAllAllocatedBlocks();
                } else {
                    SkinChanger.forceUpdate = true;
                }
                SkinChanger.saveConfig();
            }

            ImGui.spacing();

            // Team tabs
            if (ImGui.beginTabBar("SkinChangerTeams")) {
                if (ImGui.beginTabItem("Counter-Terrorists")) {
                    if (selectedTeam != 3) {
                        selectedTeam = 3;
                    }
                    ImGui.endTabItem();
                }
                if (ImGui.beginTabItem("Terrorists")) {
                    if (selectedTeam != 2) {
                        selectedTeam = 2;
                    }
                    ImGui.endTabItem();
                }
                ImGui.endTabBar();
            }

            WeaponsEnum heldWeapon = SkinChanger.getCurrentWeapon();
            // Auto-select held weapon if valid and selectedWeapon is not set
            if (heldWeapon != WeaponsEnum.NONE && isWeaponValidForTeam(selectedTeam, heldWeapon)
                    && selectedWeapon == WeaponsEnum.NONE) {
                selectedWeapon = heldWeapon;
            }

            ImGui.textDisabled("Currently Held: " + (heldWeapon == WeaponsEnum.NONE ? "None" : heldWeapon.getDisplayName()));
            ImGui.separator();

            if (selectedWeapon != WeaponsEnum.NONE) {
                SkinInfo activeSkin = SkinChanger.getSkin(selectedTeam, selectedWeapon);
                if (activeSkin == null) {
                    activeSkin = new SkinInfo(0, false, "Default Skin", selectedWeapon);
                }

                // Seed input box: [0] Seed
                imgui.type.ImInt seedVal = new imgui.type.ImInt(activeSkin.seed);
                ImGui.setNextItemWidth(100);
                if (ImGui.inputInt("##SeedInput", seedVal)) {
                    activeSkin.seed = Math.max(1, seedVal.get());
                    if (activeSkin.paint != 0) {
                        SkinChanger.applySkin(selectedTeam, selectedWeapon, activeSkin);
                    }
                }
                ImGui.sameLine();
                ImGui.text("Seed");

                // Wear slider
                float[] wearVal = new float[] { activeSkin.wear };
                ImGui.setNextItemWidth(-1);
                if (ImGui.sliderFloat("##WearSlider", wearVal, 0.0001f, 1.0f, "Wear %.10f")) {
                    activeSkin.wear = wearVal[0];
                    if (activeSkin.paint != 0) {
                        SkinChanger.applySkin(selectedTeam, selectedWeapon, activeSkin);
                    }
                }

                // Search skin input
                ImString searchStr = new ImString(searchFilter, 128);
                ImGui.setNextItemWidth(-1);
                if (ImGui.inputTextWithHint("##SkinSearchInput", "Search skin...", searchStr)) {
                    searchFilter = searchStr.get();
                }

                // Skins list
                List<SkinInfo> skins = SkinDatabase.getSkinsForWeapon(selectedWeapon);
                List<SkinInfo> filteredSkins = new ArrayList<>();
                for (SkinInfo s : skins) {
                    if (searchFilter.isEmpty() || s.name.toLowerCase().contains(searchFilter.toLowerCase())) {
                        filteredSkins.add(s);
                    }
                }

                ImGui.beginChild("FilteredSkinsList", 0, 180, true);
                for (int i = 0; i < filteredSkins.size(); i++) {
                    SkinInfo s = filteredSkins.get(i);
                    boolean isSelected = (activeSkin.paint == s.paint);
                    if (ImGui.selectable(s.name, isSelected)) {
                        SkinInfo newSkin = new SkinInfo(s.paint, s.bUsesOldModel, s.name, selectedWeapon, activeSkin.wear, activeSkin.seed);
                        newSkin.quality = activeSkin.quality;
                        newSkin.nameTag = activeSkin.nameTag;
                        SkinChanger.applySkin(selectedTeam, selectedWeapon, newSkin);
                    }
                    if (isSelected) {
                        ImGui.setItemDefaultFocus();
                    }
                }
                ImGui.endChild();

                ImGui.spacing();

                // Quality combo box: [Normal] Quality
                String[] qualities = {"Normal", "Genuine", "Vintage", "Star", "Tournament", "Customized"};
                String activeQuality = activeSkin.quality != null ? activeSkin.quality : "Normal";
                ImGui.setNextItemWidth(150);
                if (ImGui.beginCombo("##QualityCombo", activeQuality)) {
                    for (String q : qualities) {
                        boolean isSelected = q.equals(activeQuality);
                        if (ImGui.selectable(q, isSelected)) {
                            activeSkin.quality = q;
                            if (activeSkin.paint != 0) {
                                SkinChanger.applySkin(selectedTeam, selectedWeapon, activeSkin);
                            }
                        }
                    }
                    ImGui.endCombo();
                }
                ImGui.sameLine();
                ImGui.text("Quality");

                // Name tag input box
                ImString nameTagStr = new ImString(activeSkin.nameTag != null ? activeSkin.nameTag : "", 64);
                ImGui.setNextItemWidth(-1);
                if (ImGui.inputTextWithHint("##NameTagInput", "Name tag", nameTagStr)) {
                    activeSkin.nameTag = nameTagStr.get();
                    if (activeSkin.paint != 0) {
                        SkinChanger.applySkin(selectedTeam, selectedWeapon, activeSkin);
                    }
                }

                ImGui.spacing();

                // Update button
                if (ImGui.button("Update")) {
                    SkinChanger.forceUpdate = true;
                }

            } else {
                ImGui.textDisabled("Select a weapon from the left list to configure skins.");
            }

            ImGui.endChild();
        }
        ImGui.end();
        
        ImGui.popStyleColor(21);
    }
}
