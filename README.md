# TileMapper

A Paper plugin that overlays real-world satellite imagery onto the Minecraft terrain surface.
Powered by the [Terra--](https://github.com/SmylerMC/terraminusminus) engine and seamlessly integrates with [TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus).

> This project is a refactored **plugin** port of the original [TerraSatelliteMapper](https://github.com/tf2mandeokyi/TerraSatelliteMapper) **Minecraft Forge mod** (by [@tf2mandeokyi](https://github.com/tf2mandeokyi)), adapted for Paper 1.21.8+ server environments.

---

## Features

- 🛰️ **Satellite terrain** — Fetches real satellite/aerial imagery from any tile server (OpenStreetMap, custom tile URL, etc.) and converts it to Minecraft blocks
- 🌍 **Tile offset** — `tile_offset` config lets you fine-tune image alignment in block coordinates
- 🔄 **Hot reload** — `/tsm reload` applies config changes without server restart

---

## Requirements

- **Paper 1.21.8+** server
- **TerraPlusMinus** plugin (installed at `plugins/TerraPlusMinus/`)
- **Java 21+**

---

## Installation

1. Download the latest `tilemapper-<version>.jar` from [Releases](../../releases)
2. Place the JAR into your server's `plugins/` folder
3. Restart or reload the server
4. Edit `plugins/TileMapper/config.yml` to adjust settings (optional)
5. Run `/tsm status` to verify the plugin is working

---

## Building

```bash
git clone https://github.com/YOUR_USER/TileMapper.git
cd TileMapper
mvn clean package
```

Output: `target/tilemapper-<version>.jar` (~428 KB, shaded with Gson + bStats).

---

## Configuration

`plugins/TileMapper/config.yml`:

```yaml
# Enable / disable the plugin
enabled: true

# Active tile source (must match one of the keys under tile_sources:)
active_source: bing

# Multiple tile sources — add as many as you want
tile_sources:
  bing:
    url: "https://ecn.t0.tiles.virtualearth.net/tiles/a{quadkey}.jpeg?g=1"
    zoom: 20
    offset:
      x: 0
      z: 0
  osm:
    url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    zoom: 14
    offset:
      x: 0
      z: 0

# Maximum concurrent HTTP requests
max_concurrent_requests: 8

# In-memory tile cache size
cache_size: 200

# When true, only replace the configured surface block type — preserves
# road/building markers that T+- generates on the surface from OSM data.
# The target block type is read from T+-'s surface_material config (default: GRASS_BLOCK).
# Set to false to replace any surface block.
surface_block_mask: true

# Config version (do not change)
config_version: "1.0"
```

> **Note**: Surface height is controlled by the `terrain_offset.x/z/y` values in T+-'s `config.yml`. TileMapper reads these automatically to keep both plugins in sync.

---

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/tsm status` | `tilemapper.admin` | Show plugin status, active source and current settings |
| `/tsm sources` | `tilemapper.admin` | List all available tile sources |
| `/tsm source <name>` | `tilemapper.admin` | Switch to a different tile source on the fly |
| `/tsm reload` | `tilemapper.admin` | Hot-reload `config.yml` without restarting |

By default, server operators (OP) have the `tilemapper.admin` permission.

---

## How It Works

```
New chunk generated (ChunkLoadEvent, isNewChunk=true)
       │
       ▼
  ChunkProcessor initializes
       │  ├─ Reads T+- config.yml → terrain_offset
       │  └─ Creates ChunkDataLoader (T-- API)
       │
       ▼
  For each 16×16 column:
       │
       ├─ ChunkDataLoader.groundHeight() + tPlusYOffset
       │     └─ Gets surface Y (identical to T+-)
       │
       ├─ GeographicProjection.toGeo(worldX, worldZ)
       │     └─ MC coordinates → latitude/longitude
       │
       ├─ TileServer.fetch(lat, lon, zoom)
       │     └─ HTTP async request → bicubic interpolation → RGB
       │
       └─ CIE2000 delta-E nearest-neighbor match
             └─ Sets block type
```

### //regen Compatibility

When WorldEdit's `//regen` regenerates a chunk, T+- re-generates the terrain and TileMapper automatically applies satellite imagery on top via `ChunkLoadEvent (isNewChunk=true)`. No extra setup needed.

---

## Dependencies

| Dependency | Purpose | Scope |
|------------|---------|-------|
| [Paper API](https://papermc.io/) 1.21.8 | Bukkit API | provided |
| [Terra--](https://github.com/SmylerMC/terraminusminus) 2.2.0-1.21.8 | Terrain engine, projection transforms | provided |
| [TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus) | Surface generation (provides `terrain_offset`) | runtime |
| [Lombok](https://projectlombok.org/) 1.18.42 | Boilerplate reduction | provided (annotation) |
| [Gson](https://github.com/google/gson) 2.13.2 | JSON parsing (`block_data.json`) | shaded |
| [bStats](https://bstats.org/) 3.1.0 | Anonymous usage metrics | shaded |

---

## Credits

- **Terra--** ([SmylerMC/terraminusminus](https://github.com/SmylerMC/terraminusminus)) developed by [@SmylerMC](https://github.com/SmylerMC) — terrain engine and projection API
- **TerraPlusMinus** ([BTE-Germany/TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus)) — surface generation plugin for Paper
- **TerraSatelliteMapper** ([tf2mandeokyi/TerraSatelliteMapper](https://github.com/tf2mandeokyi/TerraSatelliteMapper)) — original Forge mod by [@tf2mandeokyi](https://github.com/tf2mandeokyi), which this project is ported from

---

## License

This project is licensed under the [MIT License](LICENSE).
