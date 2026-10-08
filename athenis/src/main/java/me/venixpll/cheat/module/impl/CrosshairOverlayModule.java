package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import me.venixpll.cheat.CS2Memory;
import me.venixpll.cheat.CS2Offsets;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.MenuGroup;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;
import me.venixpll.cheat.setting.FloatSetting;
import me.venixpll.cheat.setting.ModeSetting;

import java.util.Set;

/**
 * CrosshairOverlayModule — Crosshair Overlay.
 *
 * <p>Renders a configurable crosshair at the center of the screen.
 *
 * <h3>Activation modes</h3>
 * <ul>
 *   <li><b>Sniper Only</b> – crosshair is shown only while the local player
 *       holds a weapon that has no native in-game crosshair (AWP, SSG 08,
 *       SCAR-20, G3SG1, or any knife).</li>
 *   <li><b>Always</b> – crosshair is shown at all times regardless of weapon.</li>
 * </ul>
 *
 * <h3>Pointer chain for weapon detection</h3>
 * <pre>
 *  localPlayerPawn
 *    + m_pWeaponServices          → CPlayer_WeaponServices*
 *    + m_hActiveWeapon            → entity handle (int)
 *  EntityList lookup              → C_CSWeaponBase*
 *    + m_AttributeManager
 *    + m_Item
 *    + m_iItemDefinitionIndex     → item def ID (short)
 * </pre>
 *
 * <h3>CS2 item definition IDs for no-crosshair weapons</h3>
 * <ul>
 *   <li>AWP        = 9</li>
 *   <li>G3SG1      = 11</li>
 *   <li>SSG 08     = 40</li>
 *   <li>SCAR-20    = 59</li>
 *   <li>All knives = IDs ≥ 500, plus legacy knife IDs 42 and 59 (already
 *       listed above for SCAR-20; the overlap is intentional).</li>
 * </ul>
 */
public class CrosshairOverlayModule extends CheatModule {

    // ── No-crosshair weapon definition IDs ───────────────────────────────────

    /**
     * Set of CS2 item definition indices that correspond to weapons which have
     * <em>no native crosshair</em> (snipers and knives).
     * <p>Any ID ≥ 500 is treated as a knife regardless of this set.
     */
    private static final Set<Integer> NO_CROSSHAIR_IDS = Set.of(
            9,   // AWP
            11,  // G3SG1
            40,  // SSG 08 (Scout)
            59   // SCAR-20  (also coincides with the legacy knife slot, so ≥500 guard is the primary knife check)
    );

    // ── Crosshair style indices ───────────────────────────────────────────────
    private static final int STYLE_CROSS        = 0;
    private static final int STYLE_DOT          = 1;
    private static final int STYLE_CIRCLE_CROSS = 2;
    private static final int STYLE_T_SHAPE      = 3;

    // ── Settings ──────────────────────────────────────────────────────────────

    /**
     * When to draw the crosshair:
     * <ul>
     *   <li>0 – Sniper Only (weapons without a native crosshair)</li>
     *   <li>1 – Always</li>
     * </ul>
     */
    public final ModeSetting mode = new ModeSetting(
            "Mode##crosshair", 0, "Sniper Only", "Always");

    /**
     * Visual shape of the rendered crosshair:
     * <ul>
     *   <li>0 – Cross        (classic 4-line)</li>
     *   <li>1 – Dot          (center circle only)</li>
     *   <li>2 – Circle+Cross (ring with inner lines)</li>
     *   <li>3 – T-Shape      (no top line)</li>
     * </ul>
     */
    public final ModeSetting style = new ModeSetting(
            "Style##crosshair", 1, "Cross", "Dot", "Circle + Cross", "T-Shape");

    /** Length of each arm of the crosshair in pixels. */
    public final FloatSetting size = new FloatSetting(
            "Size##crosshair", 8.0f, 1.0f, 30.0f);

    /** Gap between the crosshair center and the start of each arm in pixels. */
    public final FloatSetting gap = new FloatSetting(
            "Gap##crosshair", 4.0f, 0.0f, 20.0f);

    /** Line thickness of the crosshair arms in pixels. */
    public final FloatSetting thickness = new FloatSetting(
            "Thickness##crosshair", 1.5f, 0.5f, 5.0f);

    /** Primary color of the crosshair. */
    public final ColorSetting color = new ColorSetting(
            "Color##crosshair", 0.0f, 1.0f, 0.45f, 1.0f);

    /** Whether to draw a 1-px black outline behind each line for contrast. */
    public final BooleanSetting outline = new BooleanSetting(
            "Outline##crosshair", true);

    /** Color of the outline shadow drawn behind each line. */
    public final ColorSetting outlineColor = new ColorSetting(
            "Outline Color##crosshair", 0.0f, 0.0f, 0.0f, 0.85f);

    /**
     * Whether to render a filled dot at the exact center of the crosshair.
     * Applicable to the Cross and T-Shape styles.
     */
    public final BooleanSetting dot = new BooleanSetting(
            "Center Dot##crosshair", true);

    /** Radius of the optional center dot in pixels. */
    public final FloatSetting dotSize = new FloatSetting(
            "Dot Size##crosshair", 2.0f, 0.5f, 6.0f);

    /** Overall opacity multiplier applied on top of the color alpha channel. */
    public final FloatSetting alpha = new FloatSetting(
            "Opacity##crosshair", 1.0f, 0.0f, 1.0f);

    // ── Internal state ────────────────────────────────────────────────────────

    /**
     * Set to {@code true} by {@link #onTick()} when the local player is
     * holding a no-crosshair weapon. Consumed by {@link #onRender}.
     * <p>Declared {@code volatile} so that the memory-polling thread's write
     * is immediately visible to the render thread.
     */
    private volatile boolean isSniperWeapon = false;

    // ── Constructor ───────────────────────────────────────────────────────────

    public CrosshairOverlayModule() {
        super("Crosshair Overlay", ModuleCategory.EXTERNAL, MenuGroup.VISUALS, true);
        addSetting(mode);
        addSetting(style);
        addSetting(size);
        addSetting(gap);
        addSetting(thickness);
        addSetting(color);
        addSetting(outline);
        addSetting(outlineColor);
        addSetting(dot);
        addSetting(dotSize);
        addSetting(alpha);
    }

    // ── onTick — memory polling (background thread) ────────────────────────────

    /**
     * Resolves the local player's active weapon definition index and updates
     * {@link #isSniperWeapon} accordingly.
     *
     * <p>Only runs the full pointer chain when the module is enabled and the
     * mode is set to <em>Sniper Only</em> — always-mode short-circuits here
     * because it does not need weapon information.
     */
    @Override
    public void onTick() {
        if (!isEnabled()) return;
        if (mode.getValue() == 1) {
            // "Always" mode — no weapon check needed.
            isSniperWeapon = false;
            return;
        }

        long localPawn = PlayerCache.localPlayerPawnAddress;
        if (localPawn == 0) {
            isSniperWeapon = false;
            return;
        }

        isSniperWeapon = resolveIsNoCrosshairWeapon(localPawn);
    }

    // ── onRender — ImGui draw thread ──────────────────────────────────────────

    /**
     * Renders the crosshair overlay on the ImGui foreground draw list.
     *
     * <p>The crosshair is always drawn at the exact screen center, adjusted by
     * the ESP overlay offset so it stays aligned with the game view when the
     * window is repositioned.
     *
     * @param drawList ImGui foreground draw list provided by the overlay engine.
     */
    @Override
    public void onRender(ImDrawList drawList) {
        if (!PlayerCache.tracking) return;

        // Determine whether to draw based on the selected mode.
        int modeIdx = mode.getValue();
        if (modeIdx == 0 /* Sniper Only */ && !isSniperWeapon) return;

        // ── Screen center ─────────────────────────────────────────────────────
        float cx = PlayerCache.screenWidth  * 0.5f + ESPModule.espOffsetX;
        float cy = PlayerCache.screenHeight * 0.5f + ESPModule.espOffsetY;

        // ── Resolved drawing parameters ───────────────────────────────────────
        float[] col   = color.getValue();
        float[] olCol = outlineColor.getValue();
        float   a     = alpha.getValue();

        int mainColor    = ImColor.rgba(col[0],   col[1],   col[2],   col[3]   * a);
        int shadowColor  = ImColor.rgba(olCol[0], olCol[1], olCol[2], olCol[3] * a);

        float th   = thickness.getValue();
        float sz   = size.getValue();
        float gp   = gap.getValue();
        boolean showOutline = outline.getValue();
        boolean showDot     = dot.getValue();
        float   ds          = dotSize.getValue();

        int selectedStyle = style.getValue();

        switch (selectedStyle) {
            case STYLE_CROSS        -> drawCross(drawList, cx, cy, sz, gp, th, mainColor, shadowColor, showOutline, showDot, ds);
            case STYLE_DOT          -> drawDotOnly(drawList, cx, cy, sz, mainColor, shadowColor, showOutline);
            case STYLE_CIRCLE_CROSS -> drawCircleCross(drawList, cx, cy, sz, gp, th, mainColor, shadowColor, showOutline, showDot, ds);
            case STYLE_T_SHAPE      -> drawTShape(drawList, cx, cy, sz, gp, th, mainColor, shadowColor, showOutline, showDot, ds);
        }
    }

    // ── Crosshair drawing helpers ─────────────────────────────────────────────

    /**
     * Draws a classic 4-line cross crosshair (top / bottom / left / right).
     * Each arm starts at {@code gap} pixels from center and extends {@code size}
     * pixels further out.
     *
     * @param dl          ImGui draw list.
     * @param cx          Screen-space center X.
     * @param cy          Screen-space center Y.
     * @param sz          Arm length in pixels.
     * @param gp          Gap between center and arm start in pixels.
     * @param th          Line thickness in pixels.
     * @param mainCol     Primary crosshair color (packed RGBA int).
     * @param shadowCol   Outline/shadow color (packed RGBA int).
     * @param showOutline Whether to draw the outline pass.
     * @param showDot     Whether to draw the center dot.
     * @param ds          Center dot radius in pixels.
     */
    private void drawCross(ImDrawList dl,
                           float cx, float cy,
                           float sz, float gp, float th,
                           int mainCol, int shadowCol,
                           boolean showOutline, boolean showDot, float ds) {
        float end  = gp + sz;
        float outTh = th + 2f;

        if (showOutline) {
            // Horizontal shadow
            dl.addLine(cx - end, cy, cx - gp, cy, shadowCol, outTh);
            dl.addLine(cx + gp, cy, cx + end, cy, shadowCol, outTh);
            // Vertical shadow
            dl.addLine(cx, cy - end, cx, cy - gp, shadowCol, outTh);
            dl.addLine(cx, cy + gp, cx, cy + end, shadowCol, outTh);
        }
        // Horizontal main
        dl.addLine(cx - end, cy, cx - gp, cy, mainCol, th);
        dl.addLine(cx + gp, cy, cx + end, cy, mainCol, th);
        // Vertical main
        dl.addLine(cx, cy - end, cx, cy - gp, mainCol, th);
        dl.addLine(cx, cy + gp, cx, cy + end, mainCol, th);

        if (showDot) {
            if (showOutline) {
                dl.addCircleFilled(cx, cy, ds + 1f, shadowCol, 12);
            }
            dl.addCircleFilled(cx, cy, ds, mainCol, 12);
        }
    }

    /**
     * Draws a single filled circle as the entire crosshair.
     *
     * @param dl          ImGui draw list.
     * @param cx          Screen-space center X.
     * @param cy          Screen-space center Y.
     * @param sz          Circle radius in pixels.
     * @param mainCol     Primary dot color (packed RGBA int).
     * @param shadowCol   Outline color (packed RGBA int).
     * @param showOutline Whether to draw the outline ring.
     */
    private void drawDotOnly(ImDrawList dl,
                             float cx, float cy,
                             float sz, int mainCol, int shadowCol,
                             boolean showOutline) {
        float r = sz * 0.5f;
        if (showOutline) {
            dl.addCircleFilled(cx, cy, r + 1.5f, shadowCol, 16);
        }
        dl.addCircleFilled(cx, cy, r, mainCol, 16);
    }

    /**
     * Draws a circle ring plus a cross inside it.
     * The cross arms are clamped to the inner part of the circle (start at
     * {@code gap} from center, end at {@code size} — the ring's radius).
     *
     * @param dl          ImGui draw list.
     * @param cx          Screen-space center X.
     * @param cy          Screen-space center Y.
     * @param sz          Circle radius AND cross arm outer end in pixels.
     * @param gp          Gap between center and arm start in pixels.
     * @param th          Line thickness of cross arms in pixels.
     * @param mainCol     Primary color (packed RGBA int).
     * @param shadowCol   Outline color (packed RGBA int).
     * @param showOutline Whether to draw outline passes.
     * @param showDot     Whether to draw the center dot.
     * @param ds          Center dot radius in pixels.
     */
    private void drawCircleCross(ImDrawList dl,
                                 float cx, float cy,
                                 float sz, float gp, float th,
                                 int mainCol, int shadowCol,
                                 boolean showOutline, boolean showDot, float ds) {
        float circleR = sz + gp; // ring sits outside the gap
        float outTh   = th + 2f;

        // ── Outline pass ──────────────────────────────────────────────────────
        if (showOutline) {
            dl.addCircle(cx, cy, circleR + 1f, shadowCol, 36, th + 2f);
            // Inner cross arms (gap → sz)
            dl.addLine(cx - sz, cy, cx - gp, cy, shadowCol, outTh);
            dl.addLine(cx + gp, cy, cx + sz, cy, shadowCol, outTh);
            dl.addLine(cx, cy - sz, cx, cy - gp, shadowCol, outTh);
            dl.addLine(cx, cy + gp, cx, cy + sz, shadowCol, outTh);
        }

        // ── Main pass ─────────────────────────────────────────────────────────
        dl.addCircle(cx, cy, circleR, mainCol, 36, th);
        dl.addLine(cx - sz, cy, cx - gp, cy, mainCol, th);
        dl.addLine(cx + gp, cy, cx + sz, cy, mainCol, th);
        dl.addLine(cx, cy - sz, cx, cy - gp, mainCol, th);
        dl.addLine(cx, cy + gp, cx, cy + sz, mainCol, th);

        if (showDot) {
            if (showOutline) {
                dl.addCircleFilled(cx, cy, ds + 1f, shadowCol, 12);
            }
            dl.addCircleFilled(cx, cy, ds, mainCol, 12);
        }
    }

    /**
     * Draws a T-shaped crosshair (left / right / bottom — no top line).
     * Favored by sniper players who want a simple aiming reference below center.
     *
     * @param dl          ImGui draw list.
     * @param cx          Screen-space center X.
     * @param cy          Screen-space center Y.
     * @param sz          Arm length in pixels.
     * @param gp          Gap between center and arm start in pixels.
     * @param th          Line thickness in pixels.
     * @param mainCol     Primary color (packed RGBA int).
     * @param shadowCol   Outline color (packed RGBA int).
     * @param showOutline Whether to draw the outline pass.
     * @param showDot     Whether to draw the center dot.
     * @param ds          Center dot radius in pixels.
     */
    private void drawTShape(ImDrawList dl,
                            float cx, float cy,
                            float sz, float gp, float th,
                            int mainCol, int shadowCol,
                            boolean showOutline, boolean showDot, float ds) {
        float end  = gp + sz;
        float outTh = th + 2f;

        if (showOutline) {
            // Horizontal shadow
            dl.addLine(cx - end, cy, cx - gp, cy, shadowCol, outTh);
            dl.addLine(cx + gp, cy, cx + end, cy, shadowCol, outTh);
            // Bottom-only vertical shadow
            dl.addLine(cx, cy + gp, cx, cy + end, shadowCol, outTh);
        }
        // Horizontal main
        dl.addLine(cx - end, cy, cx - gp, cy, mainCol, th);
        dl.addLine(cx + gp, cy, cx + end, cy, mainCol, th);
        // Bottom-only vertical main
        dl.addLine(cx, cy + gp, cx, cy + end, mainCol, th);

        if (showDot) {
            if (showOutline) {
                dl.addCircleFilled(cx, cy, ds + 1f, shadowCol, 12);
            }
            dl.addCircleFilled(cx, cy, ds, mainCol, 12);
        }
    }

    // ── Weapon detection helper ───────────────────────────────────────────────

    /**
     * Returns {@code true} when the given local player pawn is holding a weapon
     * that does not display a native CS2 crosshair (sniper rifles or knives).
     *
     * <p>Pointer chain:
     * <ol>
     *   <li>{@code pawn + m_pWeaponServices} → {@code CPlayer_WeaponServices*}</li>
     *   <li>{@code weaponServices + m_hActiveWeapon} → entity handle (int)</li>
     *   <li>Entity handle resolved via {@link #getEntityByHandle}</li>
     *   <li>{@code entity + m_AttributeManager + m_Item + m_iItemDefinitionIndex}
     *       → item definition index (short)</li>
     * </ol>
     *
     * @param localPawn Raw memory address of the local player pawn.
     * @return {@code true} if the active weapon has no native crosshair.
     */
    private static boolean resolveIsNoCrosshairWeapon(long localPawn) {
        long weaponServices = CS2Memory.readLong(localPawn + CS2Offsets.m_pWeaponServices);
        if (!isValidPtr(weaponServices)) return false;

        int  activeHandle = CS2Memory.readInt(weaponServices + CS2Offsets.m_hActiveWeapon);
        long weaponEntity = getEntityByHandle(activeHandle);
        if (!isValidPtr(weaponEntity)) return false;

        long itemBase = weaponEntity + CS2Offsets.m_AttributeManager + CS2Offsets.m_Item;
        int  defIdx   = CS2Memory.readShort(itemBase + CS2Offsets.m_iItemDefinitionIndex) & 0xFFFF;

        // Knives: any item definition index ≥ 500
        if (defIdx >= 500) return true;

        return NO_CROSSHAIR_IDS.contains(defIdx);
    }

    /**
     * Resolves an entity handle to its raw memory address using the CS2 entity
     * list double-pointer structure.
     *
     * @param handle Entity handle integer (as read from memory).
     * @return Raw entity memory address, or {@code 0} if the handle is invalid.
     */
    private static long getEntityByHandle(int handle) {
        if (handle == 0 || handle == -1) return 0;
        long clientBase  = CS2Memory.getClientBase();
        long entityList  = CS2Memory.readLong(clientBase + CS2Offsets.dwEntityList);
        if (entityList == 0) return 0;
        long listEntry   = CS2Memory.readLong(entityList + 8L * ((handle & 0x7FFF) >> 9) + 0x10);
        if (listEntry == 0) return 0;
        return CS2Memory.readLong(listEntry + 0x70L * (handle & 0x1FF));
    }

    /**
     * Guards against obviously-invalid pointer values (kernel/null ranges).
     *
     * @param ptr Memory address to validate.
     * @return {@code true} if the pointer looks like a valid user-space address.
     */
    private static boolean isValidPtr(long ptr) {
        return ptr > 0x10000L && ptr < 0x7FFF_FFFF_FFFFL;
    }
}
