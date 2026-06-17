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
| **Aimbot** | Screen-space aim assist using player bone projections. Features custom target bones (Head, Neck, Chest, Stomach, Closest), dynamic FOV, smoothing, sensitivity settings, randomized mouse drift humanization, enemy-only/VisCheck filtering, and overlay menu open protection. |
| **Silent Aimbot** | External silent aim. Temporarily patches view angles (`dwViewAngles`) during shooting ticks and restores them within a microsecond-level delay. Features customizable FOV, target bone, smoothing, restore delay, and overlay menu open protection. |
| **TriggerBot** | Auto-fires a left click when the crosshair lands on an enemy's hitbox. Configurable reaction delay, click duration, post-shot cooldown, and hitbox radius. Integrates local velocity check (Stop When Moving) and overlay menu open protection. |
| **BunnyHop** | Automatically sends jump signals to CS2 when Space is held down. Adjusts jump/release delays and checks player flags (standing/crouching) to jump precisely when hitting the ground. |
| **Bomb Timer** | Renders a countdown above the planted C4. When off-screen, displays a fixed top-center banner with a pulsing alert bar and warning indicator. |
| **VisCheck Map Physics** | Real-time raycasting collision detection using a custom Bounding Volume Hierarchy (BVH) tree. Resolves map-specific `.opt` files from `/physics/` resources to check line-of-sight between the local player and target pawn. Highlights visible enemies in **yellow** on ESP. Includes real-time debug controls, logging throttle adjustments, and runtime performance stats directly in the menu. |

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
| Target Zone | `Head` | Body region that triggers a shot (Head, Body, Legs, All) |
| One-Shot Mode | ❌ | Ignore target after shot until crosshair leaves and re-enters |
| Reaction Delay (ms) | `10.0` | Pause between target detection and simulated mouse click |
| Click Duration (ms) | `40.0` | Mouse button down duration |
| Cooldown (ms) | `100.0` | Cooldown period before allowing the next shot |
| Bone Radius (% box) | `0.06` | Hitbox radius as a fraction of the player's screen box height |
| Enemy Only | ✅ | Ignore teammates |
| VisCheck Filter | ✅ | Require geometric line-of-sight visibility before firing |
| Stop When Moving | ❌ | Disable firing if local player velocity exceeds threshold |
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
| Humanize | ❌ | Introduce randomized human-like mouse offset noise |
| Humanize Strength | `20.0` | Scaling factor for humanized mouse drift |
| Enemy Only | ✅ | Filter targeting to opponents only |
| VisCheck | ✅ | Ensure line-of-sight visibility before locking onto a bone |
| Spotted Fallback | ✅ | Fall back to client `m_bSpotted` flag if map geometry is missing |

### Silent Aimbot

| Setting | Default | Description |
|---------|---------|-------------|
| FOV (degrees) | `12.0` | Engagement field of view |
| Target Bone | `Head` | Aim destination (Head, Neck, Chest, Stomach) |
| Aim Key | `Right Mouse` | Hotkey to engage silent aim |
| Activation | `Hold Key` | Activation type (Hold Key / Toggle) |
| Smooth | `1.0` | Angle smoothing factor (1.0 = instant silent snap) |
| Enemy Only | ✅ | Target enemies only |
| VisCheck | ✅ | Require line-of-sight visibility |
| Spotted Fallback | ✅ | Fall back to spotted flag |
| Restore Delay (µs) | `1000.0` | Delay in microseconds before restoring the original camera angle |

### BunnyHop

| Setting | Default | Description |
|---------|---------|-------------|
| Plus Jump Delay (ms) | `10.0` | Delay before sending the jump command |
| Minus Jump Delay (ms)| `10.0` | Release loop/cooldown delay |

### VisCheck Debug

Adjustable in the bottom section of the configuration menu:
- **Enable Ray Debug Logging**: Print ray-cast collision debugging directly to console.
- **Log Throttle (ms)**: Throttle console print frequency (0 = print every raycast).
- **Print Stats / Reset Stats**: Dump or clear lifetime map raycast statistics in console.

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
│   │   │       ├── AimbotModule.java    # Screen-space aimbot
│   │   │       ├── SilentAimbotModule.java # Silent view-angle aimbot
│   │   │       ├── TriggerBotModule.java# Hitbox-accurate triggerbot
│   │   │       ├── BunnyHopModule.java  # Auto-jump module
│   │   │       └── BombTimerModule.java # Planted C4 timer
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
   (ESPModule, RadarHackModule)
```

---

## License

This project is released for **educational purposes**. No license is granted for use in online multiplayer matches.
