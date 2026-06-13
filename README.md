# Athenis CS2

> **External CS2 game overlay written in Java** — ESP, Radar, TriggerBot.  
> Uses `ReadProcessMemory` via JNA and renders with Dear ImGui (GLFW/OpenGL).

---

> [!CAUTION]
> **For educational and research purposes only.**  
> Using cheating software in online multiplayer games violates the game's Terms of Service and may result in a permanent VAC ban. The authors take no responsibility for misuse.

---

## Features

| Module | Description |
|--------|-------------|
| **ESP Overlay** | 2D bounding boxes with health bars and player names. Enemy-only filter, configurable colors, forward position extrapolation to compensate for server tick lag. Integrates **VisCheck** to highlight visible players. |
| **Radar Hack** | Minimap radar with per-map auto-alignment, zoom, rotation, C4 carrier highlight. |
| **TriggerBot** | Auto-fires a left click when the crosshair lands on an enemy's head hitbox. Configurable reaction delay, click duration, post-shot cooldown, and hitbox radius. |
| **VisCheck Map Physics** | Real-time raycasting collision detection using a custom Bounding Volume Hierarchy (BVH) tree. Resolves map-specific `.opt` files from `/physics/` resources to check line-of-sight between the local player and target pawn. Highlights visible enemies in **yellow** on ESP. Disables itself when the map's `.opt` file is missing. |

### Architecture highlights

- **Two-thread memory loop** — a fast uncapped position thread and a slow (~10 Hz) entity-data thread minimize kernel call budget.
- **Immutable render snapshots** — `PlayerSnapshot` objects are built per-frame and published via a volatile reference swap, eliminating renderer race conditions.
- **VisCheck BVH Raycasting** — performs real-time ray-triangle collision tracing against 3D map geometries using a custom Bounding Volume Hierarchy (BVH) tree and the Möller–Trumbore intersection algorithm.
- **Forward position extrapolation** — reads `m_vecVelocity` alongside `m_vOldOrigin` and projects `pos + vel × latency` to compensate for the inherent 1-tick lag of external overlays.
- **Auto offset updates** — offsets are fetched at startup from [a2x/cs2-dumper](https://github.com/a2x/cs2-dumper) and fall back to bundled defaults when offline.
- **Persistent config** — all module settings are saved to `%APPDATA%\Athenis\settings.json` via Gson.

---

## Requirements

| Requirement | Version |
|------------|---------|
| **OS** | Windows 10/11 (64-bit) |
| **Java** | JDK 17+ (Temurin recommended) |
| **Maven** | 3.8+ |
| **Game** | Counter-Strike 2 (Steam) |

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
| Show Box Outline | ✅ | Draw 2D bounding box |
| Show Health Indicators | ✅ | Left-side health bar (green → red) |
| Show Player Names | ✅ | Name + HP above box |
| Enemy-Only Team Filter | ✅ | Skip teammates |
| Extrapolation (ms) | `20` | Forward prediction to counter tick lag. Increase if boxes lag; decrease if they lead. |
| Enemy Color | Red | RGB color for enemy boxes |
| Team Color | Blue | RGB color for teammate boxes |

### TriggerBot

| Setting | Default | Description |
|---------|---------|-------------|
| Head Hitbox (px) | `18` | Crosshair-to-head detection radius in screen pixels |
| Reaction Delay (ms) | `10` | Pause before clicking (adds human-like latency) |
| Click Duration (ms) | `40` | LMB hold duration |
| Cooldown (ms) | `80` | Minimum time between shots |
| Enemy Only | ✅ | Only fire at opponents |
| Use VisCheck Filter | ✅ | Only fire if the target's head is visible via VisCheck |

---

## Project Structure

```
athenis/
├── src/main/java/me/venixpll/
│   ├── Main.java                        # Entry point
│   ├── launcher/
│   │   └── LauncherWindow.java          # Swing launcher GUI
│   ├── overlay/
│   │   ├── OverlayWindow.java           # GLFW transparent overlay
│   │   └── OverlayMenu.java             # ImGui configuration menu
│   ├── cheat/
│   │   ├── CS2Memory.java               # JNA ReadProcessMemory wrapper
│   │   ├── CS2Offsets.java              # Dynamic offset loader (a2x/cs2-dumper)
│   │   ├── MemoryLoop.java              # Fast + slow background threads
│   │   ├── PlayerCache.java             # PlayerData / PlayerSnapshot bridge
│   │   ├── Vector3.java                 # 3D vector math
│   │   ├── vischeck/
│   │   │   ├── VisCheck.java            # Visibility check engine
│   │   │   ├── VisCheckAdapter.java     # JAR resource loader & life cycle manager
│   │   │   ├── BVHNode.java             # Bounding Volume Hierarchy tree node
│   │   │   ├── AABB.java                # Bounding box & ray-box intersections
│   │   │   ├── Triangle.java            # Mesh index representation
│   │   │   ├── TriangleCombined.java    # 3D triangle coordinates
│   │   │   ├── OptimizedGeometry.java   # Binary opt serializer and deserializer
│   │   │   └── VPhysToOptConverter.java # CLI tool to compile Valve .vphys to .opt
│   │   ├── module/
│   │   │   ├── CheatModule.java         # Module base class
│   │   │   ├── ModuleManager.java       # Module registry
│   │   │   └── impl/
│   │   │       ├── ESPModule.java       # ESP renderer
│   │   │       ├── RadarHackModule.java # Minimap radar
│   │   │       └── TriggerBotModule.java# Head triggerbot
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
│   │       └── ColorSetting.java        # Color picker
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
   (ESPModule, RadarHackModule)
```

---

## License

This project is released for **educational purposes**. No license is granted for use in online multiplayer matches.
