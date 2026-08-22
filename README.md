# Athenis CS2

**External Counter-Strike 2 Overlay** written in Java. Renders with Dear ImGui (GLFW/OpenGL3) & reads memory using JNA.

[![Java Version](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://adoptium.net/)
[![Platform](https://img.shields.io/badge/Platform-Windows-blue.svg)]()
[![Build](https://img.shields.io/badge/Build-Maven-red.svg)]()
[![Status](https://img.shields.io/badge/Status-Active-brightgreen.svg)]()

---

> [!CAUTION]
> **For educational and research purposes only.**  
> Using cheating software in online multiplayer games violates the game's Terms of Service and may result in a permanent VAC ban. The authors take no responsibility for misuse.

---

## Recent Updates

<details open>
<summary><b>Click to toggle recent changelog details (last ~20 commits)</b></summary>

Here are the key changes introduced in the latest version:

- **Managed module threading**: All worker-thread modules (Aimbot, TriggerBot, BunnyHop, AutoWeapon, SilentAim, NoSpread) now share a single `ManagedThreadModule` base class with proper enable/disable lifecycle hooks and safety cleanup.
- **BVH engine overhaul**: Triangle AABBs are precomputed once at build time and splits are chosen with binned Surface Area Heuristic (SAH); visibility checks use an early-exit "any-hit" traversal for significantly faster occlusion queries.
- **Centralized memory status reporting**: `CS2Memory` exposes a status listener (`ATTACHED` / `DETACHED` / `READ_FAILURE` / `PROCESS_EXITED`) surfaced live in the launcher UI instead of failing silently.
- **VisCheck Map Editor & Debugger**: Built the interactive `VisRay Debug` module to visualize ray-casts, hit coordinates, and intersecting map triangles. Includes the ability to delete/restore triangles in real-time.
- **Runtime Geometry Patch Merging**: The physics engine now automatically merges "hard" patches (bundled inside the `.jar` resource path under `/physics/patches/` and `/patches/`) with dynamic user-configured local patches (saved in `%APPDATA%\Athenis\patches\`).
- **New Map Collision Meshes**: Added optimized map geometry `.opt` files for `de_anubis` and `de_vertigo`.
- **Spectator List & Crosshair Modules**: Integrated a real-time spectator tracker panel and a customizable crosshair overlay (modes: Sniper Only or Always).
- **Refactoring & UI Enhancements**:
  - Organized UI layout by creating a dedicated **Debug** section in the menu.
  - Cached game window handle with `IsWindow()` re-validation instead of re-enumerating windows every frame batch.
  - Cleaned up build configs (removed ProGuard, optimized Maven dependency configuration).
  - Improved visibility-checking algorithm for better accuracy.
  - Fixed radar offset calibrations for `de_cache`.
</details>

---

## Features

| Category | Module | Description |
|---|---|---|
| **Visuals** | **ESP Overlay** | 2D bounding boxes with health bars, player names. Features enemy-only filter, configurable colors, and forward position extrapolation to compensate for tick lag. Integrates **VisCheck** to filter visible/hidden targets. |
| | **Radar Hack** | Minimap overlay radar with per-map automatic alignment offsets, customizable rotation, zoom, and C4 carrier highlighting. |
| | **Bomb Timer** | Screen overlay timer for planted C4 with a pulsing alert bar and warning banner when off-screen. |
| | **Crosshair** | Configurable center-screen crosshair overlay supporting multiple styles (Cross, Dot, Circle + Cross, T-Shape) and activation modes (Sniper Only, Always). |
| | **Speedometer** | Movable HUD showing local-player movement speed (u/s) with a real-time 2-second velocity graph and 250 u/s reference line. |
| **Combat** | **Aimbot** | Screen-space aim assist using bone projections. Includes dynamic FOV, smoothing, pluggable aim modes (Classic, PID Spring, Human), custom target bones (Head, Neck, Chest, Stomach, Closest), flashbang check, and VisCheck filtering. |
| | **TriggerBot** | Automatically fires when the crosshair crosses an enemy hitbox. Features customizable reaction delay, click duration, shot cooldown, one-shot mode, and Stop-When-Moving safety check. |
| | **Silent Aim** | External view-angle patch: pre-computes target angles each frame and redirects only the first shot per left-click via a single memory write. ⚠️ VAC-detectable. |
| | **No Spread (RCS)** | Recoil Control System compensating weapon punch angles in real time via hardware mouse movement, with pitch/yaw scaling and inversion options. |
| | **Auto Weapon** | Automatically fires Zeus/Knife when the nearest enemy is within the weapon's effective range. |
| | **BunnyHop** | Automated bunnyhop script that sends precise jump signals when Spacebar is held down. |
| **Developer / Debug** | **VisCheck Map Physics** | Real-time line-of-sight check engine using custom map geometry `.opt` files compiled from Valve `.vphys` data. Highlights visible enemies in yellow. |
| | **VisRay Debug** | Visual debugger displaying active raycasts, hit points, and target triangles. Includes a real-time mesh editor to delete/restore geometry triangles on-the-fly and save local/runtime merge patches. |
| | **Distance Debug** | Perspective-scaled obstacle marker at the crosshair with distance readout (meters/units) and player highlight/distance labels. |
| | **Spectator List** | A dedicated UI panel tracking and listing players who are currently spectating the local player's point of view. |

### Architecture Highlights

- **Two-thread memory loop** — a fast uncapped position thread and a slow (~10 Hz) entity-data thread minimize kernel call budget.
- **Immutable render snapshots** — `PlayerSnapshot` objects are built per-frame and published via a volatile reference swap, eliminating renderer race conditions.
- **VisCheck BVH Raycasting** — performs real-time ray-triangle collision tracing against 3D map geometries using a custom Bounding Volume Hierarchy (BVH) tree and the Möller–Trumbore intersection algorithm.
- **Forward position extrapolation** — reads `m_vecVelocity` alongside `m_vOldOrigin` and projects `pos + vel × latency` to compensate for the inherent 1-tick lag of external overlays.
- **Auto offset updates** — offsets are fetched at startup from [a2x/cs2-dumper](https://github.com/a2x/cs2-dumper) and fall back to bundled defaults when offline.
- **Persistent config** — all module settings are saved to `%APPDATA%\Athenis\settings.json` via Gson.

---

## Requirements

| Requirement | Version                       |
| -------------| -------------------------------|
| **OS**      | Windows 10/11 (64-bit)        |
| **Java**    | JDK 21+ (Temurin recommended) |
| **Maven**   | 3.8+                          |
| **Game**    | Counter-Strike 2 (Steam)      |

> [!IMPORTANT]
> The overlay **must be run as Administrator** so that `OpenProcess` / `ReadProcessMemory` can access the CS2 process memory.

---

## Building

```bash
# Clone the repository
git clone https://github.com/VenixPLL/AthenisCS2.git
cd AthenisCS2/athenis

# Build a fat JAR (includes all dependencies)
mvn package -DskipTests
```

The output JAR is written to `athenis/target/athenis-1.0-SNAPSHOT.jar`.

---

## Running

```bash
# Right-click → "Run as Administrator" or use an elevated terminal:
java -jar athenis/target/athenis-1.0-SNAPSHOT.jar
```

1. The **Athenis Launcher** window will appear.
2. Start CS2 and load into a match (or practice server).
3. Click **START** in the launcher — the transparent overlay appears over the game.
4. Press **INSERT** in-game to toggle the configuration menu.
5. Click **STOP** in the launcher (or close the overlay) to detach cleanly.

> [!TIP]
> Run CS2 in **Windowed Fullscreen** (borderless) mode for the overlay to align correctly.

---

## Configuration

All settings are adjusted live in the **in-game menu** (INSERT key) and persisted automatically.

### ESP Overlay
| Setting | Default | Description |
|---------|---------|-------------|
| Show Box Outline | Yes | Draw 2D bounding box |
| Show Health Indicators | Yes | Left-side health bar (green → red) |
| Show Player Names | Yes | Name + HP above box |
| Enemy-Only Team Filter | Yes | Skip teammates |
| Extrapolation (ms) | `20` | Forward prediction to counter tick lag. Increase if boxes lag; decrease if they lead. |
| Enemy Color | Red | RGB color for enemy boxes |
| Team Color | Blue | RGB color for teammate boxes |

### Silent Aim
| Setting | Default | Description |
|---------|---------|-------------|
| Target Bone | `Head` | Bone to redirect shots toward (Head, Neck, Chest, Stomach, Closest) |
| Hold Key | `None (LMB only)` | Optional modifier key required while clicking (Right Mouse, Left Alt, Left Shift, X, Z, Ctrl) |
| FOV (degrees) | `5.0` | Field-of-view cone used for target selection |
| Enemy Only | Yes | Filter targeting to opponents only |
| VisCheck | Yes | Require geometric line-of-sight before selecting a bone |
| Spotted Fallback | Yes | Fall back to client `m_bSpotted` flag if map geometry is missing |
| Flashbang Check | Yes | Pause angle patching while blinded by a flashbang |
| Show FOV Circle | Yes | Draw the FOV circle on the overlay |

> [!WARNING]
> This module writes directly to `dwViewAngles` in CS2 memory and is VAC-detectable. Use only on non-VAC servers.

### No Spread (Recoil Control)
| Setting | Default | Description |
|---------|---------|-------------|
| Recoil Compensation | Yes | Master toggle for RCS mouse compensation |
| Pitch Scale | `1.0` | Multiplier applied to vertical compensation |
| Yaw Scale | `1.0` | Multiplier applied to horizontal compensation |
| In-Game Sensitivity | `2.0` | Game sensitivity used in the compensation math |
| RCS Strength | `50.0` | Overall strength multiplier |
| Start Bullet | `0` | Bullet index from which compensation begins |
| Invert Pitch / Yaw Direction | No | Flip compensation direction per axis |
| Debug Log to Console | Yes | Print spray state and applied corrections to console |

### Auto Weapon
| Setting | Default | Description |
|---------|---------|-------------|
| Enemy Only | Yes | Only auto-fire at opponents |
| Zeus Range (units) | `155.0` | Effective range for the Zeus taser |
| Knife Range (units) | `65.0` | Effective range for knife slashes |
| Click Duration (ms) | `60.0` | Simulated LMB hold duration |
| Post-Fire Cooldown (ms) | `350.0` | Cooldown between auto-fire events |

### TriggerBot
| Setting | Default | Description |
|---------|---------|-------------|
| Target Zone | `Head` | Body region that triggers a shot (Head, Body, Legs, All) |
| One-Shot Mode | No | Ignore target after shot until crosshair leaves and re-enters |
| Reaction Delay (ms) | `10.0` | Pause between target detection and simulated mouse click |
| Click Duration (ms) | `40.0` | Mouse button down duration |
| Cooldown (ms) | `100.0` | Cooldown period before allowing the next shot |
| Bone Radius (% box) | `0.06` | Hitbox radius as a fraction of the player's screen box height |
| Enemy Only | Yes | Ignore teammates |
| VisCheck Filter | Yes | Require geometric line-of-sight visibility before firing |
| Stop When Moving | No | Disable firing if local player velocity exceeds threshold |
| Max Move Speed (u/s) | `50.0` | Velocity threshold for the movement check |

### Aimbot
| Setting | Default | Description |
|---------|---------|-------------|
| Target Bone | `Head` | Bone to target (Head, Neck, Chest, Stomach, Closest) |
| Activation | `Hold Key` | Activation behavior (Hold Key / Toggle) |
| Aim Key | `Right Mouse` | Hotkey to trigger aimbot (Right Mouse, Middle Mouse, Left Alt, Left Shift, X Key, Z Key, Ctrl) |
| FOV (degrees) | `8.0` | Field of view limit |
| FOV Min | `0.3` | Minimum field of view threshold to prevent jitter near center |
| Smooth | `6.0` | Smoothing factor (1.0 = instant snap, higher = slower movement) |
| Sensitivity | `1.0` | Game mouse sensitivity modifier |
| Humanize | No | Introduce randomized human-like mouse offset noise |
| Humanize Strength | `20.0` | Scaling factor for humanized mouse drift |
| Enemy Only | Yes | Filter targeting to opponents only |
| VisCheck | Yes | Ensure line-of-sight visibility before locking onto a bone |
| Spotted Fallback | Yes | Fall back to client `m_bSpotted` flag if map geometry is missing |

### Crosshair Overlay
| Setting | Default | Description |
|---------|---------|-------------|
| Mode | `Sniper Only` | Activation mode (Sniper Only: AWPs/Scouts/knives, Always: all weapons) |
| Style | `Cross` | Visual crosshair style (Cross, Dot, Circle + Cross, T-Shape) |
| Size | `8.0` | Length of each crosshair arm in pixels |
| Gap | `4.0` | Distance between center and start of each arm in pixels |
| Thickness | `1.5` | Thickness of the crosshair lines in pixels |
| Color | Green | Primary color of the crosshair |
| Outline | Yes | Renders a 1px black outline shadow behind lines for visibility |
| Center Dot | Yes | Displays a filled dot at the exact center (for Cross and T-Shape) |
| Dot Size | `2.0` | Radius of the center dot in pixels |
| Opacity | `1.0` | Overall alpha opacity multiplier |

### BunnyHop
| Setting | Default | Description |
|---------|---------|-------------|
| Plus Jump Delay (ms) | `10.0` | Delay before sending the jump command |
| Minus Jump Delay (ms)| `10.0` | Release loop/cooldown delay |

### VisRay Debug
| Setting | Default | Description |
|---------|---------|-------------|
| Ray Mode | `Enemy` | Targets for debugging ray-casts (Enemy, Crosshair) |
| Crosshair Range | `4096.0` | Maximum raycast range when debugging via crosshair direction |
| Show Ray Line | Yes | Render lines tracing the casted ray |
| Show Hit Triangle | Yes | Render the exact hit triangle mesh in red/blue |
| Show Hit Point | Yes | Render a small sphere at the collision intersection |
| Show All Deleted | Yes | Renders all currently deleted triangles in gray |
| Nearest Enemy Only | Yes | Restrict enemy-raycast debugging to the closest player |
| Delete Bind / Restore Bind / Save Bind | `F` / `H` / `S` | Keybind configurations to delete the hit triangle, restore it, or save changes |

---

## Project Structure

```
athenis/
├── src/main/java/me/venixpll/
│   ├── Main.java                        # Entry point
│   ├── launcher/
│   │   └── LauncherWindow.java          # Swing launcher GUI + system tray
│   ├── overlay/
│   │   ├── OverlayWindow.java           # GLFW transparent overlay & window sync
│   │   ├── OverlayMenu.java             # ImGui configuration menu
│   │   ├── NotificationManager.java     # System notification manager
│   │   └── PerformanceMonitor.java      # FPS / frame-time monitor panel
│   ├── cheat/
│   │   ├── CS2Memory.java               # JNA ReadProcessMemory wrapper + status events
│   │   ├── CS2Offsets.java              # Dynamic offset loader (a2x/cs2-dumper)
│   │   ├── MemoryLoop.java              # Fast + slow background threads
│   │   ├── PlayerCache.java             # PlayerData / PlayerSnapshot bridge
│   │   ├── Vector3.java                 # 3D vector math
│   │   ├── vischeck/
│   │   │   ├── VisCheck.java            # Visibility check engine (SAH BVH + any-hit rays)
│   │   │   ├── VisCheckAdapter.java     # JAR resource loader & life cycle manager
│   │   │   ├── Parser.java              # Mesh geometry loader
│   │   │   ├── BVHNode.java             # Bounding Volume Hierarchy tree node
│   │   │   ├── AABB.java                # Bounding box & ray-box intersections
│   │   │   ├── Triangle.java            # Mesh index representation
│   │   │   ├── TriangleCombined.java    # 3D triangle coordinates
│   │   │   ├── OptimizedGeometry.java   # Binary opt serializer and deserializer
│   │   │   └── VPhysToOptConverter.java # CLI tool to compile Valve .vphys to .opt
│   │   ├── module/
│   │   │   ├── CheatModule.java         # Module base class
│   │   │   ├── ManagedThreadModule.java # Worker-thread lifecycle base class
│   │   │   ├── ModuleManager.java       # Module registry
│   │   │   ├── MenuGroup.java           # Sidebar grouping enum
│   │   │   ├── ModuleCategory.java      # Execution-category enum
│   │   │   └── impl/
│   │   │       ├── ESPModule.java       # ESP renderer
│   │   │       ├── RadarHackModule.java # Minimap radar
│   │   │       ├── BombTimerModule.java # Planted C4 timer
│   │   │       ├── CrosshairOverlayModule.java # Center-screen crosshairs
│   │   │       ├── SpeedometerModule.java # Velocity HUD with graph
│   │   │       ├── AimbotModule.java    # Screen-space aimbot (+ aimbot/ mode strategies)
│   │   │       ├── TriggerBotModule.java# Hitbox-accurate triggerbot
│   │   │       ├── SilentAimModule.java # External view-angle patch
│   │   │       ├── NoSpreadModule.java  # Recoil control system
│   │   │       ├── AutoWeaponModule.java# Zeus/knife auto-fire
│   │   │       ├── BunnyHopModule.java  # Auto-jump module
│   │   │       ├── SpectatorListModule.java # Active spectators panel
│   │   │       ├── VisRayDebugModule.java # Interactive map raycast/mesh patch debugger
│   │   │       └── DistanceDebugModule.java # Crosshair distance/perspective debugger
│   │   ├── reader/
│   │   │   ├── EntityDataReader.java    # Slow entity list traversal
│   │   │   ├── PositionReader.java      # Fast origin + velocity reader
│   │   │   └── ViewMatrixReader.java    # View-projection matrix reader
│   │   ├── projection/
│   │   │   └── ScreenProjector.java     # World → screen projection math
│   │   └── setting/
│   │       ├── Setting.java             # Abstract setting base
│   │       ├── BooleanSetting.java      # Checkbox
│   │       ├── FloatSetting.java        # Slider
│   │       ├── ColorSetting.java        # Color picker
│   │       └── ModeSetting.java         # Multi-option selector (dropdown/radio)
│   └── config/
│       └── ConfigManager.java           # JSON config persistence (Gson)
└── pom.xml
```

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| [imgui-java](https://github.com/SpaiR/imgui-java) | 1.86.10 | Dear ImGui rendering (GLFW + OpenGL 3) |
| [JNA](https://github.com/java-native-access/jna) | 5.13.0 | Win32 API (`ReadProcessMemory`, `OpenProcess`) |
| [JNA Platform](https://github.com/java-native-access/jna) | 5.13.0 | Win32 structs and helpers |
| [Gson](https://github.com/google/gson) | 2.10.1 | JSON offset loading + config persistence |

---

## How It Works

```
CS2 Process Memory
        │
        │  ReadProcessMemory (JNA)
        ▼
┌─────────────────────┐        ┌──────────────────────┐
│  Fast Position Loop  │        │   Slow Data Loop      │
│  (uncapped, yield)   │        │   (~10 Hz, 100 ms)    │
│                      │        │                        │
│  1. ViewMatrix read  │        │  1. Entity list scan   │
│  2. m_vOldOrigin +  │        │  2. Health / team /    │
│     m_vecVelocity    │        │     name per player    │
│  3. Extrapolate pos  │◄───────│  3. rawPlayers swap    │
│  4. Project → px     │        │  4. Module onTick()    │
│  5. renderPlayers ←  │        └──────────────────────┘
│     (volatile swap)  │
└─────────┬───────────┘
          │  volatile List<PlayerSnapshot>
          ▼
   ImGui Render Thread
   (ESPModule, RadarHackModule, etc.)
```

---

## License

See [LICENSE](LICENSE) — released strictly for **educational purposes**. No license is granted for use in online multiplayer matches.
