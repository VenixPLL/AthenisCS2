# Athenis — Codebase Analysis & Improvement Suggestions

> Generated from a full review of the memory layer, overlay/UI, module system,
> VisCheck engine, and build configuration.

> [!NOTE]
> **Implementation status:** all issues and refactoring improvements listed below
> have been implemented (repo hygiene, `ManagedThreadModule` lifecycle refactor,
> SAH BVH + early-exit traversal, memory status reporting, window-handle caching,
> README/LICENSE/.gitignore fixes, and a 37-test JUnit 5 suite). The *Feature
> Ideas* section is intentionally left for future work.

---

## 🔁 Second-Pass Audit (post-refactor) — all fixed

A full re-audit after the first implementation round found 7 additional bugs
(including two introduced by the refactor itself). All are now fixed:

| # | Severity | Finding | Fix |
|---|----------|---------|-----|
| 1 | HIGH | `ManagedThreadModule` worker-restart race: rapid disable→enable could spawn a second worker while the old one still ran (shared boolean re-set to `true`) → duplicate mouse injection | Per-generation `AtomicBoolean` stop flags — each worker owns its flag; old workers always observe `false` |
| 2 | HIGH | `VisCheck.buildBVH`: a mesh with all triangles displacement-filtered produced a null-bounds BVH root → NPE on first ray | Degenerate zero-area AABB assigned to empty roots (+ regression test) |
| 3 | HIGH | `RadarHackModule.onTick`: unguarded `readVector(...)` dereference → NPE in slow data thread | Null-check before caching origin |
| 4 | HIGH | `ConfigManager` never persisted `ColorSetting` values — all user color customization lost on restart | RGBA serialized as JSON array, symmetric load with type-mismatch guard |
| 5 | MEDIUM | Non-atomic `settings.json` write could corrupt config on crash/power loss | Write to `.tmp` then `Files.move(ATOMIC_MOVE)` with fallback |
| 6 | HIGH | `EntityDataReader` recycled pooled `PlayerData` objects in place while fast thread still iterated the published list → torn reads | Double-buffered slot pools alternating per publish |
| 7 | HIGH (perf) | `ESPModule.renderGrenadeESP` scanned ~2000 entity slots per render frame with string allocations — largest GC/frame-time source | Grenade discovery moved to ~10 Hz tick scan into a concurrent cache; render loop touches only cached entries |

---

## ✅ Current Strengths

- **Solid concurrency architecture** — two-thread memory loop (fast uncapped position
  thread + slow ~10 Hz entity thread) with immutable `PlayerSnapshot` volatile-swap
  publishing. A genuinely good design for external overlays.
- **Clean module framework** — `CheatModule` base class with `onTick()`/`onRender()`
  hooks, dual classification (`ModuleCategory` for execution nature vs `MenuGroup`
  for UI grouping), and danger flagging via `isDangerous()`.
- **Well-designed aimbot subsystem** — strategy pattern (`AimMode`/`AimType`) is the
  best-structured part of the codebase: pluggable, self-contained settings, `reset()` lifecycle.
- **Impressive VisCheck engine** — custom BVH + Möller–Trumbore raycasting against
  compiled map geometry (`.opt`), with runtime patch merging from JAR resources +
  `%APPDATA%\Athenis\patches\`.
- **Good developer experience** — auto offset fetching from a2x/cs2-dumper with
  offline fallback, Gson config persistence, launch4j `.exe` packaging, fat JAR build.

---

## 🐛 Issues Found

### Build & repository hygiene

1. **No tests exist.** `athenis/src/test` does not exist; JUnit dependencies are
   declared in `pom.xml` but never used.
2. **`athenis/dependency-reduced-pom.xml` is committed.** It is a generated artifact
   of maven-shade-plugin and should not be tracked:
   ```bash
   echo "dependency-reduced-pom.xml" >> .gitignore
   git rm --cached athenis/dependency-reduced-pom.xml
   ```
3. **`.gitignore` is minimal** (`/s2v`, `/athenis/target`). Missing entries:
   `.idea/`, `*.iml`, `.vscode/`, `*.jar`, `dependency-reduced-pom.xml`, `*.log`.
4. **No LICENSE file**, despite the "educational purposes only" disclaimer in the README.
5. **README is out of sync with the code:**
   - Documents `DamageESPModule` and `GrenadeESPModule` — neither exists under
     `cheat/module/impl/`.
   - Claims Silent Aimbot was *removed* — but `SilentAimModule.java` still exists,
     alongside undocumented modules: `AutoWeaponModule`, `NoSpreadModule`,
     `SpeedometerModule`, `DistanceDebugModule`.
   - The "Project Structure" tree no longer matches the real source tree
     (e.g., missing `PerformanceMonitor.java`, `MenuGroup.java`, `ModuleCategory.java`,
     `aimbot/` subpackage).

### Code-level issues

6. **Duplicated thread lifecycle boilerplate.** Every threaded module hand-rolls the
   identical pattern — `volatile boolean xxxThreadRunning`, private `Thread xxxThread`,
   start/stop logic inside `onTick()`, daemon + max priority + `InterruptedException`
   handling — duplicated across Aimbot, AutoWeapon, BunnyHop, TriggerBot, etc.
   There are no `onEnable()/onDisable()` lifecycle hooks on `CheatModule`.
7. **BVH build & traversal performance** (`VisCheck.java`):
   - Build comparator calls `computeAABB()` twice *per comparison*, each allocating
     2 `Vector3`s + 1 `AABB` → millions of short-lived objects during build (heavy GC churn).
   - No Surface Area Heuristic (SAH) — plain median split gives poor quality on skewed distributions.
   - Traversal visits both children unconditionally with no early exit, even though
     `isPointVisible` only needs a boolean "occluded before target" answer.
8. **Silent error handling in the memory layer.** Read failures are uniformly
   swallowed; a stale process handle after CS2 restarts is not surfaced to the UI.
9. **Fragile window discovery.** `FindWindow("SDL_app", "Counter-Strike 2")` polled
   every 250 ms from the render loop breaks if Valve changes the window class/title;
   handle caching + re-validation would be more robust.

---

## 🔧 Refactoring Improvements (prioritized)

| Priority | Suggestion |
|----------|------------|
| High | Add `onEnable()`/`onDisable()` lifecycle hooks to `CheatModule`; extract the shared background-thread start/stop pattern into a reusable helper (e.g., `ManagedThreadModule`). Removes ~5 copies of identical code. |
| High | BVH optimization: precompute AABBs once per node before sorting; add SAH-based splitting; add an "any-hit" early-exit traversal mode for boolean vischecks. Significant FPS win. |
| High | Fix README: remove phantom modules, document the real ones, regenerate the structure tree. |
| Medium | Centralize memory-read error reporting — e.g., a health/status callback so the launcher shows "CS2 restarted — reattach required" instead of failing silently. |
| Medium | Cache the game window handle and re-validate it instead of polling `FindWindow`. |
| Medium | Add unit tests for pure logic: `Vector3`, `ScreenProjector` math, `AABB`/ray intersection, `OptimizedGeometry` serialize/deserialize round-trip, config load/save. |
| Low | Extract magic numbers (tick rates, buffer sizes, thresholds) into named constants or config. |

---

## ➕ Feature Ideas

1. ~~**Sound ESP** — read sound events and render directional indicators for footsteps~~ —
   ✅ **Implemented** as an ESPModule submodule (like Grenade/Damage ESP).
   External overlays cannot tap CS2's audio mixer, so footsteps are synthesized
   from the high-frequency position/velocity pipeline: the slow tick accumulates
   per-enemy horizontal stride distance (audible only when groundborne and faster
   than walk speed) and emits pings that render as directional,
   proximity-weighted wedges on a ring around the crosshair (enemy-only filter,
   meters distance labels, configurable radius/duration/color). Pure math lives
   in `module/impl/helpers/SoundIndicatorMath`, covered by `SoundIndicatorMathTest`.
2. **Bomb carrier ESP + defuse kit indicator** — C4 timer already exists; extend
   carrier highlighting from radar-only to world ESP.
3. ~~**Hit marker & custom kill feed overlay**~~ — ✅ **Implemented.** Extends the
   damage-tracking approach with hit confirmation markers and a killfeed panel.
   As part of this, damage attribution was rewritten to be shot-gated
   (`m_iShotsFired` transitions + expanded-bounding-box crosshair test +
   vanish-based kill synthesis), fixing missed hits, missing kills, and false
   credits of other players' damage.
4. **Watermark/session HUD** — configurable panel showing FPS, ping, round time, money.
5. **Config profiles** — multiple named configs (per weapon/map) with hotkey switching,
   built on top of the existing `ConfigManager`.
6. **Stream-proof mode** — exclude the overlay from OBS/screen capture via
   `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)` — one Win32 call, high value.
7. **Map geometry auto-updater** — wrap the existing `VPhysToOptConverter` CLI in a
   tool that pulls latest `.vphys` data and regenerates `.opt` files automatically.
8. **CI pipeline** — GitHub Actions workflow running `mvn package` on push, attaching
   the fat JAR/exe as release artifacts.
9. **Panic key** — single hotkey that instantly disables all dangerous modules and
   hides the menu (safety UX).

---

## Recommended Implementation Order

1. Repo hygiene pass (gitignore, untrack shade artifact, fix README)
2. Module lifecycle refactor (`onEnable`/`onDisable`)
3. BVH early-exit optimization
4. Unit tests for math/geometry/config classes
5. Quick-win features: stream-proof mode, panic key
6. Larger features: sound ESP, config profiles, CI pipeline
