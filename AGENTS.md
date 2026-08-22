# AGENTS.md — Guide for AI Agents Working on Athenis

> This file gives you (an AI coding agent) the context you'd otherwise have to
> rediscover from scratch. Read it fully before making changes. It encodes the
> architecture, hard-won invariants, threading rules, and known pitfalls of this
> codebase. Also see `IMPROVEMENTS.md` for the audit history and the backlog of
> *not yet implemented* feature ideas.

---

## 1. What This Project Is

**Athenis** is an external Counter-Strike 2 overlay written in Java 21.
It reads CS2 process memory via JNA (`ReadProcessMemory`), renders an ImGui
(GLFW + OpenGL3) transparent click-through overlay on top of the game window,
and ships as a fat JAR wrapped into a Windows `.exe` by launch4j.

- **Repo root:** contains `.gitignore`, `LICENSE`, `README.md`, `IMPROVEMENTS.md`, `AGENTS.md`
- **All source lives under:** `athenis/src/main/java/me/venixpll/`
- **Tests:** `athenis/src/test/java/me/venixpll/`
- **Resources:** `athenis/src/main/resources/` (map physics `.opt`, radar offsets JSON, patch JSONs, icons)

⚠️ **Ethics/legal framing:** educational/research project. keep the educational
disclaimers intact.

---

## 2. Build / Test / Run Commands

Shell is **PowerShell 5.1** on Windows — it does NOT support `&&`. Use `;` or
separate calls. Output capture from long commands is unreliable; redirect to a
file and read it back:

```powershell
cd athenis; mvn package > ..\build_output.txt 2>&1   # full build + tests + exe
Get-Content ..\build_output.txt | Select-Object -Last 40
```

| Task | Command |
|------|---------|
| Compile only | `mvn compile` |
| Run tests | `mvn test` |
| Full build (jar + shaded jar + exe) | `mvn package` |
| Skip tests | `mvn package -DskipTests` |

Artifacts land in `athenis/target/`: `athenis-1.2.jar` (thin), `athenis-1.2-shaded.jar`
(fat), `athenis-1.2.exe` (launch4j-wrapped).

**Test runtime requirements:** tests are pure-JVM and hermetic (no game needed).
They build synthetic map geometry as little-endian byte arrays and feed them to
`new VisCheck(bytes, "")` — the empty map name skips `%APPDATA%` patch loading,
keeping tests side-effect free. Do NOT write tests that touch real user files
(e.g. `ConfigManager.save()` writes to `%APPDATA%\Athenis\settings.json` — that's
why it has no round-trip test; it would need a path-injection seam first).

---

## 3. Threading Model — THE Most Important Thing

Three threads do all the work. Every bug found in two audit rounds was a
threading violation. Internalize this table before touching anything:

| Thread | Name | Rate | Does |
|--------|------|------|------|
| Fast position loop | `Athenis-FastPosition` | uncapped (`Thread.yield()`) | view matrix read, per-player origin+velocity read, screen projection, publishes `PlayerCache.renderPlayers` (immutable snapshots) & `PlayerCache.players` |
| Slow data loop | `Athenis-SlowData` | ~10 Hz (100 ms sleep) | entity-list traversal → `PlayerCache.rawPlayers`; then calls `module.onTick()` for every registered module |
| Render thread | GLFW/ImGui main | vsync-ish | calls `module.onRender(drawList)`; owns ALL ImGui calls and all Win32 window queries |

### Hard rules

1. **Never call ImGui from any thread except the render thread.**
2. **Never block the render thread** with RPM scans over large entity ranges.
   Heavy discovery work belongs in `onTick()` (~10 Hz); render should only draw
   cached state. (This exact mistake existed in grenade ESP and cost ~2000 RPM
   calls + string allocations per frame.)
3. **Published lists must never be mutated after publication.**
   - `PlayerSnapshot` objects are immutable — safe.
   - `PlayerData` objects are pooled and mutable → `EntityDataReader` uses
     **double-buffered pools** (`DATA_POOLS[2]`, alternated per publish) so the
     list published on tick N is never mutated while building tick N+1's list.
     Do not revert this to a single pool.
4. **Cross-thread fields are `volatile`** (e.g. `ESPModule.espOffsetX/Y`,
   `localTeam`, `RadarHackModule.localX/localYaw`). Follow that pattern for any
   new field written on one thread and read on another.
5. **Concurrent collections for cross-thread maps** (`ConcurrentHashMap`,
   `ConcurrentLinkedQueue`) — see `ESPModule.grenadeCache`,
   `VisCheck.deletedTriangles`.

---

## 4. Module System

### Base classes

- `CheatModule` — abstract base. Holds name, `ModuleCategory` (EXTERNAL /
  INTERNAL / DEBUG — execution nature), `MenuGroup` (VISUALS / COMBAT / OTHER /
  SYSTEM — sidebar grouping), enabled state (`ImBoolean`), settings list, bind key.
  Hooks: `onTick()` (slow thread), `onRender(ImDrawList)` (render thread),
  `isDangerous()` (shows red warning in UI).
- `ManagedThreadModule extends CheatModule` — for modules needing a dedicated
  worker thread tied to the enabled state. **Use this instead of hand-rolling threads.**

### ManagedThreadModule contract (do not break)

```java
class MyModule extends ManagedThreadModule {
    public MyModule() {
        super("My Module", ModuleCategory.EXTERNAL, MenuGroup.COMBAT,
              /*defaultEnabled*/ false, "Athenis-MyModule");
    }

    @Override protected void onUpdate() { }          // every tick, before lifecycle mgmt
    @Override protected boolean onWorkerStarting() { return true; } // false aborts spawn (retried next tick)
    @Override protected void runLoop() throws Exception { }  // ONE iteration; return == continue
    @Override protected void onWorkerStopping() { }  // safety cleanup: release keys, restore memory
}
```

- The base class spawns/stops the daemon MAX_PRIORITY worker automatically.
- **Stop-flag generations:** each spawned worker gets its own `AtomicBoolean`.
  Stopping flips only the current flag, so rapid disable→enable can never leave
  two workers running. Don't replace this with a shared boolean — that exact
  race caused duplicate mouse injection once already.
- `onWorkerStopping()` runs synchronously on the tick thread when disabled —
  keep it fast and exception-safe (it's wrapped, but still).
- State that used to be lambda-locals lives as instance fields now; reset it in
  `runLoop()`'s guard paths so restarts start clean (see `SilentAimModule`).

### Registering a module

Modules are registered in **two places** in `LauncherWindow.java` (constructor
and `onStart()`), both guarded by `ModuleManager.getModules().isEmpty()`. Add
yours to both. Settings persist automatically via reflection over
`module.getSettings()` — just `addSetting(...)` in the constructor.

---

## 5. Memory Reading Conventions

All process access goes through static methods on `CS2Memory`:

- Scalar readers return **zero values on failure** (`readInt→0`, `readLong→0`,
  `readFloat→0f`). `readVector` returns a zero vector on failure — but treat it
  as possibly-null anyway and null-check at call sites (one NPE slipped through
  in RadarHackModule once).
- `readInto(address, buffer, size)` fills caller-owned JNA buffers — use this
  pattern with static `Memory` buffers in hot loops to avoid native alloc churn
  (see `EntityDataReader`, reader classes).
- Validate pointers: `p > 0x10000L && p < 0x7FFF_FFFF_FFFFL` (`isValidPtr`).
- Failed kernel reads fire a throttled `READ_FAILURE` status event
  (`CS2Memory.addStatusListener`) surfaced in the launcher UI. Don't swallow
  errors silently in new code — report through this channel if meaningful.
- Offsets come from `CS2Offsets` (auto-fetched from a2x/cs2-dumper at startup,
  bundled fallback offline). Never hardcode offsets; use the constants.
- Entity-handle resolution pattern (used everywhere):
  ```java
  long listEntry = readLong(entityList + 8L * ((handle & 0x7FFF) >> 9) + 16);
  long entity    = readLong(listEntry + 112L * (handle & 0x1FF));
  ```

---

## 6. VisCheck Engine (map raycasting)

- Geometry: Valve `.vphys` → compiled binary `.opt` files (little-endian:
  `[numMeshes:8][per mesh: numTris:8 + 9 floats/tri]`) under
  `src/main/resources/physics/`. Triangles with any edge > `MAX_EDGE_LENGTH`
  (800u) are filtered as displacement terrain **at load time**.
- `VisCheck` builds one BVH per mesh using binned SAH (12 bins). AABBs are
  precomputed once per triangle — don't reintroduce per-comparison allocation.
- `isPointVisible` uses an early-exit "any-hit" traversal (`intersectBVHAny`);
  `castRay`/`castRayFree` use the debug traversal that tracks closest hit
  (needed for hit-point rendering).
- **Invariant: BVH root count must equal `geometry.meshes.size()`**, because
  deleted-triangle keys encode `meshIdx * 10_000_000L + triIdx`. Empty meshes
  get a degenerate AABB root (never skip them).
- Deleted triangles (VisRay Debug module editor) live in a concurrent set;
  patches merge from JAR resources `/patches|/physics/patches/{map}_deleted.json`
  plus `%APPDATA%\Athenis\patches\`.
- Map switching goes through `VisCheckAdapter.update(mapName)` (called from
  RadarHackModule's map detection).

---

## 7. Overlay & Launcher

- `OverlayWindow` (imgui-java `Application`): transparent, undecorated,
  click-through GLFW window sized to the monitor. INSERT (configurable) toggles
  the menu and flips `GLFW_MOUSE_PASSTHROUGH`.
- Game window handle is **cached and re-validated with `IsWindow()`**;
  `FindWindow("SDL_app", "Counter-Strike 2")` runs only when the handle is lost.
  Keep it that way.
- Fullscreen windows get a 1px offset trick (`espOffsetX/Y` in ESPModule) —
  all world→screen draws must add these offsets.
- `LauncherWindow` (Swing): START loads offsets → registers modules → loads
  config → starts `MemoryLoop` → blocks on `Application.launch(...)`. STOP saves
  config and requests overlay close. Subscribes to `CS2Memory` status events.
- Window title is randomized (anti-detection); don't make it deterministic.

---

## 8. Config Persistence

- `ConfigManager` serializes every module's enabled/expanded/bindKey state plus
  all settings to `%APPDATA%\Athenis\settings.json` via Gson.
- Supported setting types: `FloatSetting`, `BooleanSetting`, `ModeSetting`
  (int index), `ColorSetting` (**RGBA float[4] stored as JSON array**).
  If you add a new `Setting` subclass, extend BOTH the save and load loops.
- Saves are atomic (tmp file + `Files.move(ATOMIC_MOVE)` with fallback). Keep it.
- Load skips unknown modules/settings and type-mismatched values gracefully —
  preserve that resilience.

---

## 9. Testing

- Framework: JUnit 5 (`junit-jupiter-api/engine` 5.10.0) + Surefire 3.2.5.
- Existing suites: `Vector3Test` (math), `ScreenProjectorTest` (projection incl.
  NaN/behind-camera rejection), `AABBTest` (ray-box), `OptimizedGeometryTest`
  (binary format round-trip, terrain filtering, truncation), `VisCheckTest`
  (end-to-end occlusion against a synthetic wall, triangle deletion, empty meshes).
- Pattern for engine tests: build geometry bytes with `ByteBuffer`
  (LITTLE_ENDIAN), construct `new VisCheck(bytes, "")`, assert visibility.
- When fixing a bug, add a regression test here first when the logic is pure
  enough to test without the game.

---

## 10. Known Pitfalls (learned the hard way — don't regress)

1. Shared boolean for worker stop → duplicate workers. Use generation flags.
2. Mutating published pooled objects → torn reads. Double-buffer pools.
3. Per-frame entity scans on the render thread → GC churn/frame drops. Cache via tick scan.
4. Unchecked `readVector` results → NPEs in background threads.
5. Non-atomic config writes → corrupted settings.json.
6. Null-bounds BVH roots for filtered-empty meshes → NPE on first ray.
7. Writing 4 bytes to 1-byte game fields corrupts adjacent memory — use
   `writeByte` for booleans like `m_bSpotted`.
8. PowerShell: no `&&`; unreliable output capture → redirect to file and read.
9. `git rm --cached athenis/dependency-reduced-pom.xml` was needed once — the
   shade plugin regenerates it on every build; it's gitignored, keep it that way.

---

## 11. Quick File Map

```
athenis/src/main/java/me/venixpll/
├── Main.java                     # entry point → Swing launcher
├── launcher/LauncherWindow.java  # Swing GUI, module registration, engine lifecycle
├── overlay/
│   ├── OverlayWindow.java        # GLFW overlay, window sync, bind-key polling
│   ├── OverlayMenu.java          # ImGui menu rendering
│   ├── NotificationManager.java  # toast notifications
│   └── PerformanceMonitor.java   # FPS panel
├── cheat/
│   ├── CS2Memory.java            # JNA RPM/WPM + status events
│   ├── CS2Offsets.java           # offset loading (online/fallback)
│   ├── MemoryLoop.java           # fast + slow threads
│   ├── PlayerCache.java          # PlayerData (mutable, pooled) / PlayerSnapshot (immutable)
│   ├── Vector3.java              # immutable-style vec math
│   ├── reader/                   # EntityDataReader (slow), PositionReader (fast), ViewMatrixReader
│   ├── projection/ScreenProjector.java
│   ├── setting/                  # Setting hierarchy (Float/Boolean/Mode/Color)
│   ├── vischeck/                 # BVH engine, Parser, OptimizedGeometry, VPhysToOptConverter CLI
│   └── module/
│       ├── CheatModule.java, ManagedThreadModule.java, ModuleManager.java
│       └── impl/                 # ESP, RadarHack, Aimbot(+aimbot/ strategies), TriggerBot,
│                                 # SilentAim, NoSpread, AutoWeapon, BunnyHop, BombTimer,
│                                 # CrosshairOverlay, Speedometer, SpectatorList,
│                                 # VisRayDebug, DistanceDebug
└── config/ConfigManager.java     # Gson persistence (atomic writes)
```

## 12. Backlog

Unimplemented feature ideas live in `IMPROVEMENTS.md` § Feature Ideas: Sound ESP,
bomb-carrier world ESP, hit markers/killfeed, watermark HUD, config profiles,
stream-proof mode (`WDA_EXCLUDEFROMCAPTURE`), map auto-updater, CI pipeline,
panic key, magic-number extraction. Pick from there before inventing new scope.