package me.venixpll.cheat.module.impl;

import imgui.ImColor;
import imgui.ImDrawList;
import imgui.ImGui;
import me.venixpll.cheat.PlayerCache;
import me.venixpll.cheat.PlayerCache.PlayerSnapshot;
import me.venixpll.cheat.module.CheatModule;
import me.venixpll.cheat.module.ModuleCategory;
import me.venixpll.cheat.setting.BooleanSetting;
import me.venixpll.cheat.setting.ColorSetting;
import me.venixpll.cheat.setting.FloatSetting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Damage ESP Module.
 *
 * <p>Two visual layers:
 * <ol>
 *   <li><b>HUD Card</b> – centred below the crosshair; shows total damage,
 *       enemy/teammate HP remaining (bar + label), and shot count.</li>
 *   <li><b>Floating Numbers</b> – damage integers that spawn directly on the
 *       player model at a randomised position, float upward, and fade out.
 *       Each individual hit spawns its own floater.</li>
 * </ol>
 *
 * <h3>Attribution — screen-space FOV approach</h3>
 * When a player's on-screen centre is within {@link #crosshairRadius} pixels
 * of the crosshair, we record the timestamp.  Any health drop detected within
 * {@link #CROSSHAIR_ATTR_WINDOW_MS} ms is attributed to us.
 */
public class DamageESPModule extends CheatModule {

    // ── Timing ────────────────────────────────────────────────────────────────
    private static final long ACCUMULATE_WINDOW_MS     = 2_000L;
    private static final long FADE_MS                  = 700L;
    private static final long CROSSHAIR_ATTR_WINDOW_MS = 1_000L;

    // ── Card geometry ─────────────────────────────────────────────────────────
    private static final float CARD_W     = 230f;
    private static final float CARD_R     = 10f;
    private static final float PAD_H      = 14f;
    private static final float PAD_V      = 11f;
    private static final float ACCENT_H   = 3f;
    private static final float BAR_H      = 7f;
    private static final float BAR_R      = 3.5f;
    private static final float ROW_GAP    = 9f;
    private static final float SHADOW_OFF = 5f;
    private static final float CARD_OFFSET_Y = 54f;

    // ── Floating number config ─────────────────────────────────────────────────
    /** Total lifetime of each floating damage number (ms). */
    private static final long  FLOAT_LIFE_MS   = 1_500L;
    /** Total vertical distance (px) each number floats upward over its lifetime. */
    private static final float FLOAT_RISE_PX   = 60f;
    /** Max number of simultaneous floaters to prevent visual overload. */
    private static final int   MAX_FLOATERS    = 25;
    /** Scale of floating numbers relative to the default ImGui font. */
    private static final float FLOAT_FONT_SCALE = 1.35f;

    // ── Settings ──────────────────────────────────────────────────────────────
    public final BooleanSetting showDamage = new BooleanSetting(
            "Show Damage Card", true);

    public final BooleanSetting showFloating = new BooleanSetting(
            "Show Floating Numbers", true);

    public final BooleanSetting showTeammates = new BooleanSetting(
            "Show Teammates Damage", false);

    public final ColorSetting damageColor = new ColorSetting(
            "Damage Color##dmgesp", 1.0f, 0.38f, 0.0f, 1.0f);

    public final ColorSetting shotsColor = new ColorSetting(
            "Shots Color##dmgesp", 0.75f, 0.78f, 0.85f, 1.0f);

    public final FloatSetting textScale = new FloatSetting(
            "Text Scale##dmgesp", 2.0f, 0.8f, 4.0f);

    public final FloatSetting crosshairRadius = new FloatSetting(
            "Crosshair Radius px##dmgesp", 300f, 50f, 800f);

    // ── Per-player state (slow-loop thread only) ──────────────────────────────
    private final Map<Integer, Integer> lastHealth      = new HashMap<>();
    private final Map<Integer, Long>    lastCrosshairMs = new HashMap<>();

    // ── HUD card accumulator (volatile – render thread reads) ─────────────────
    private volatile int  accumulatedDamage = 0;
    private volatile int  shotCount         = 0;
    private volatile int  enemyHealthLeft   = 0;
    private volatile long lastHitMs         = -1L;

    // ── Floating numbers ──────────────────────────────────────────────────────

    /**
     * Immutable data for a single floating damage number spawned on the enemy model.
     * Created by the slow-loop thread, consumed by the render thread via
     * {@link #pendingFloaters}.
     */
    private static final class FloatingNumber {
        /** Screen X at spawn (with random model-space offset already applied). */
        final float startX;
        /** Screen Y at spawn. */
        final float startY;
        /** Subtle horizontal drift in pixels over the full lifetime. */
        final float driftX;
        /** Damage value to display. */
        final int   damage;
        /** {@link System#currentTimeMillis()} when this was created. */
        final long  birthMs;

        FloatingNumber(float startX, float startY, float driftX, int damage, long birthMs) {
            this.startX  = startX;
            this.startY  = startY;
            this.driftX  = driftX;
            this.damage  = damage;
            this.birthMs = birthMs;
        }
    }

    /**
     * Thread-safe pipe: slow-loop thread enqueues new floaters here,
     * render thread drains them each frame into {@link #activeFloaters}.
     */
    private final ConcurrentLinkedQueue<FloatingNumber> pendingFloaters =
            new ConcurrentLinkedQueue<>();

    /**
     * Render-thread-only list of currently animating floaters.
     * Populated by draining {@link #pendingFloaters} each frame.
     */
    private final ArrayList<FloatingNumber> activeFloaters = new ArrayList<>();

    /** Random source – only accessed from the slow-loop thread via onTick(). */
    private final Random rand = new Random();

    // ── ImGui scratch ─────────────────────────────────────────────────────────
    private final imgui.ImVec2 sz = new imgui.ImVec2();

    // ─────────────────────────────────────────────────────────────────────────

    public DamageESPModule() {
        super("Damage ESP", ModuleCategory.EXTERNAL, true);
        addSetting(showDamage);
        addSetting(showFloating);
        addSetting(showTeammates);
        addSetting(damageColor);
        addSetting(shotsColor);
        addSetting(textScale);
        addSetting(crosshairRadius);
    }

    // ── Slow-loop tick ────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        if (!isEnabled()) return;
        boolean wantCard    = showDamage.getValue();
        boolean wantFloat   = showFloating.getValue();
        if (!wantCard && !wantFloat) return;

        List<PlayerSnapshot> players = PlayerCache.renderPlayers;
        if (players == null || players.isEmpty()) {
            lastHealth.clear();
            lastCrosshairMs.clear();
            return;
        }

        long  now       = System.currentTimeMillis();
        int   localTeam = ESPModule.localTeam;
        float radius    = crosshairRadius.getValue();
        float radSq     = radius * radius;
        float cx        = PlayerCache.screenWidth  / 2.0f + ESPModule.espOffsetX;
        float cy = PlayerCache.screenHeight / 2.0f + ESPModule.espOffsetY;
        boolean trackTeammates = showTeammates.getValue();

        // Pass 1 – crosshair proximity update
        for (PlayerSnapshot p : players) {
            if (p.isLocal || (!trackTeammates && p.team == localTeam) || !p.onScreen) continue;
            float dx = p.feetX - cx;
            float dy = (p.feetY + p.headY) * 0.5f - cy;
            if (dx * dx + dy * dy <= radSq) lastCrosshairMs.put(p.index, now);
        }

        // Pass 2 – health delta + hit attribution
        for (PlayerSnapshot p : players) {
            if (p.isLocal || (!trackTeammates && p.team == localTeam)) continue;

            int key   = p.index;
            int curHp = p.health;

            if (lastHealth.containsKey(key)) {
                int delta = lastHealth.get(key) - curHp;
                if (delta > 0 && delta < 100) {
                    Long ts    = lastCrosshairMs.get(key);
                    boolean aimed = ts != null && (now - ts) <= CROSSHAIR_ATTR_WINDOW_MS;

                    if (aimed) {
                        // ── Update HUD card accumulator ───────────────────────
                        if (wantCard) {
                            if (lastHitMs >= 0
                                    && (now - lastHitMs) > (ACCUMULATE_WINDOW_MS + FADE_MS)) {
                                accumulatedDamage = 0;
                                shotCount         = 0;
                            }
                            accumulatedDamage += delta;
                            shotCount++;
                            enemyHealthLeft = Math.max(0, curHp);
                            lastHitMs       = now;
                        }

                        // ── Spawn floating number on model ────────────────────
                        if (wantFloat && p.onScreen
                                && activeFloaters.size() + pendingFloaters.size() < MAX_FLOATERS) {

                            // Model bounding box in screen space
                            float modelH  = p.feetY - p.headY;
                            float modelHW = Math.max(modelH * 0.18f, 8f); // half-width estimate

                            // Random position within the model torso (~middle 60% vertically)
                            float spawnX = p.feetX
                                    + (rand.nextFloat() * 2f - 1f) * modelHW;
                            float spawnY = p.headY
                                    + modelH * 0.15f
                                    + rand.nextFloat() * modelH * 0.55f;

                            // Slight horizontal drift (left or right)
                            float drift = (rand.nextFloat() * 2f - 1f) * 28f;

                            pendingFloaters.add(
                                    new FloatingNumber(spawnX, spawnY, drift, delta, now));
                        }
                    }
                }
            }
            lastHealth.put(key, curHp);
        }

        // Purge stale per-player state
        lastHealth.entrySet().removeIf(e -> {
            for (PlayerSnapshot p : players) if (p.index == e.getKey()) return false;
            return true;
        });
        lastCrosshairMs.entrySet().removeIf(e -> {
            for (PlayerSnapshot p : players) if (p.index == e.getKey()) return false;
            return true;
        });
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void onRender(ImDrawList dl) {
        if (!isEnabled()) return;

        long now = System.currentTimeMillis();

        // ── Floating damage numbers ───────────────────────────────────────────
        if (showFloating.getValue()) {
            // Drain pending (produced by slow-loop thread) into our render-thread list
            FloatingNumber fn;
            while ((fn = pendingFloaters.poll()) != null) activeFloaters.add(fn);

            float[] dc      = damageColor.getValue();
            float   fSize   = ImGui.getFontSize() * FLOAT_FONT_SCALE;
            int     colShad = ImColor.rgba(0f, 0f, 0f, 0.75f);

            activeFloaters.removeIf(f -> {
                long  elapsed = now - f.birthMs;
                if (elapsed >= FLOAT_LIFE_MS) return true;

                float t = elapsed / (float) FLOAT_LIFE_MS;

                // Alpha: full opacity for first 30 %, then ease-out fade
                float alpha;
                if (t < 0.30f) {
                    alpha = 1.0f;
                } else {
                    float ft = (t - 0.30f) / 0.70f;
                    alpha = 1.0f - ft * ft * (3f - 2f * ft); // smoothstep
                }
                if (alpha <= 0.01f) return false;

                // Scale: small "pop" on spawn — 1.4 → 1.0 over first 20 %
                float scaleMul = t < 0.20f
                        ? 1.4f - 2.0f * t           // 1.4 → 1.0 as t goes 0→0.2
                        : 1.0f;
                float curFontSz = fSize * scaleMul;

                // Position: float up + slight drift
                float curX = f.startX + f.driftX * t;
                float curY = f.startY - FLOAT_RISE_PX * t;

                String txt = "-" + f.damage;
                ImGui.calcTextSize(sz, txt);
                float tw = sz.x * (curFontSz / ImGui.getFontSize());

                float tx = curX - tw / 2f;
                float ty = curY;

                int colFill = ImColor.rgba(dc[0], dc[1], dc[2], dc[3] * alpha);
                int colShdw = ImColor.rgba(0f, 0f, 0f, 0.80f * alpha);

                dl.addText(ImGui.getFont(), curFontSz, tx - 1, ty + 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx + 1, ty + 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx - 1, ty - 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx + 1, ty - 1, colShdw, txt);
                dl.addText(ImGui.getFont(), curFontSz, tx, ty, colFill, txt);

                return false;
            });
        }

        // ── HUD card ──────────────────────────────────────────────────────────
        if (!showDamage.getValue()) return;
        if (lastHitMs < 0) return;

        long elapsed = now - lastHitMs;

        float alpha;
        if (elapsed < ACCUMULATE_WINDOW_MS) {
            alpha = 1.0f;
        } else if (elapsed < ACCUMULATE_WINDOW_MS + FADE_MS) {
            float t = (float)(elapsed - ACCUMULATE_WINDOW_MS) / FADE_MS;
            alpha = 1.0f - t * t * (3f - 2f * t);
        } else {
            return;
        }
        if (alpha <= 0.01f) return;

        int   damage = accumulatedDamage;
        int   shots  = shotCount;
        int   hpLeft = enemyHealthLeft;
        float scale  = textScale.getValue();

        float[] dc = damageColor.getValue();
        float[] sc = shotsColor.getValue();

        int colDmg    = ImColor.rgba(dc[0], dc[1], dc[2], dc[3] * alpha);
        int colShadow = ImColor.rgba(0f, 0f, 0f, 0.80f * alpha);
        int colShots  = ImColor.rgba(sc[0], sc[1], sc[2], sc[3] * alpha * 0.85f);

        float hpPct  = Math.max(0f, Math.min(1f, hpLeft / 100f));
        float barR   = 1f - hpPct;
        float barG   = hpPct;
        int   colBar = ImColor.rgba(barR, barG, 0f, alpha);
        int   colHp  = ImColor.rgba(barR + 0.1f, barG + 0.1f, 0.1f, alpha);

        float cx = PlayerCache.screenWidth  / 2.0f + ESPModule.espOffsetX;
        float cy = PlayerCache.screenHeight / 2.0f + ESPModule.espOffsetY;

        // Measure text
        String dmgText = "-" + damage;
        String hpText  = hpLeft + " HP";
        String subText = shots == 1 ? "in 1 shot" : "in " + shots + " shots";
        float  fontSize = ImGui.getFontSize() * scale;

        ImGui.calcTextSize(sz, dmgText);
        float dmgW = sz.x * scale, dmgH = sz.y * scale;

        ImGui.calcTextSize(sz, hpText);
        float hpTextW = sz.x, hpTextH = sz.y;

        ImGui.calcTextSize(sz, subText);
        float subW = sz.x, subH = sz.y;

        float cardH = ACCENT_H + PAD_V + dmgH + ROW_GAP + BAR_H + ROW_GAP + subH + PAD_V;
        float cardX = cx - CARD_W / 2f;
        float cardY = cy + CARD_OFFSET_Y;

        // Shadow
        dl.addRectFilled(
            cardX + SHADOW_OFF, cardY + SHADOW_OFF,
            cardX + CARD_W + SHADOW_OFF, cardY + cardH + SHADOW_OFF,
            ImColor.rgba(0f, 0f, 0f, 0.40f * alpha), CARD_R);

        // Background
        dl.addRectFilled(cardX, cardY, cardX + CARD_W, cardY + cardH,
            ImColor.rgba(0.05f, 0.07f, 0.10f, 0.93f * alpha), CARD_R);

        // Border
        dl.addRect(cardX, cardY, cardX + CARD_W, cardY + cardH,
            ImColor.rgba(0.18f, 0.20f, 0.26f, 0.75f * alpha), CARD_R, 0, 1f);

        // Top accent bar
        dl.addRectFilled(cardX + CARD_R, cardY, cardX + CARD_W - CARD_R, cardY + ACCENT_H, colDmg);
        dl.addRectFilled(cardX,          cardY, cardX + CARD_R,           cardY + ACCENT_H, colDmg);
        dl.addRectFilled(cardX + CARD_W - CARD_R, cardY, cardX + CARD_W,  cardY + ACCENT_H, colDmg);
        // Glow bleed
        dl.addRectFilled(cardX, cardY + ACCENT_H, cardX + CARD_W, cardY + ACCENT_H + 12f,
            ImColor.rgba(dc[0], dc[1], dc[2], 0.08f * alpha), 0f);

        // Damage number
        float dmgX = cardX + (CARD_W - dmgW) / 2f;
        float dmgY = cardY + ACCENT_H + PAD_V;
        dl.addText(ImGui.getFont(), fontSize, dmgX - 1, dmgY + 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX + 1, dmgY + 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX - 1, dmgY - 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX + 1, dmgY - 1, colShadow, dmgText);
        dl.addText(ImGui.getFont(), fontSize, dmgX, dmgY, colDmg, dmgText);

        // Health bar
        float barY   = dmgY + dmgH + ROW_GAP;
        float barX   = cardX + PAD_H;
        float barW   = CARD_W - PAD_H * 2f - hpTextW - 8f;

        dl.addRectFilled(barX, barY, barX + barW, barY + BAR_H,
            ImColor.rgba(0.15f, 0.15f, 0.18f, 0.90f * alpha), BAR_R);
        if (hpPct > 0.001f) {
            int colBarL = ImColor.rgba(
                Math.min(1f, barR + 0.15f), Math.min(1f, barG + 0.15f), 0.05f, alpha);
            dl.addRectFilledMultiColor(
                barX, barY, barX + barW * hpPct, barY + BAR_H,
                colBarL, colBar, colBar, colBarL);
        }
        dl.addRect(barX, barY, barX + barW, barY + BAR_H,
            ImColor.rgba(0.25f, 0.27f, 0.32f, 0.60f * alpha), BAR_R, 0, 0.8f);

        // HP label
        float hpLX = barX + barW + 6f;
        float hpLY = barY + (BAR_H - hpTextH) / 2f;
        dl.addText(hpLX + 1, hpLY + 1, ImColor.rgba(0f, 0f, 0f, 0.60f * alpha), hpText);
        dl.addText(hpLX, hpLY, colHp, hpText);

        // Sub-label
        float subX = cardX + (CARD_W - subW) / 2f;
        float subY = barY + BAR_H + ROW_GAP;
        dl.addText(subX + 1, subY + 1, ImColor.rgba(0f, 0f, 0f, 0.65f * alpha), subText);
        dl.addText(subX, subY, colShots, subText);
    }
}