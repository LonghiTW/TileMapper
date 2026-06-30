# TileMapper

A Paper plugin that overlays real-world map tiles onto the Minecraft terrain surface using the BTE projection.
Seamlessly integrates with [TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus) which provides the terrain engine and generation pipeline.

> This project is a refactored **plugin** port of the original [TerraSatelliteMapper](https://github.com/tf2mandeokyi/TerraSatelliteMapper) **Minecraft Forge mod** (by [@tf2mandeokyi](https://github.com/tf2mandeokyi)), adapted for Paper 1.21.8+ server environments.

---

## Features

- �️ **Tile-based terrain** — Fetches map tiles from any tile server (satellite, street map, aerial, etc.) and converts them to Minecraft blocks
- 🌍 **Tile offset** — Per-source `offset` config lets you fine-tune image alignment in block coordinates
- 🔄 **Hot reload** — `/tsm reload` applies config changes without server restart

---

## Requirements

- **Paper 1.21.8+** server
- **TerraPlusMinus** plugin (v1.6.1 or later required)
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
git clone https://github.com/LonghiTW/TileMapper.git
cd TileMapper
scripts/setup        # download TerraPlusMinus compile-time dependency
mvn clean package    # requires JDK 21+
```

> **Note:** The setup script downloads TerraPlusMinus JAR into `libs/`. This file is tracked in Git so contributors don't need extra steps.

Output: `target/tilemapper-1.1.0.jar` (~428 KB, shaded with Gson + bStats).

---

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/tsm status` | `tilemapper.admin` | Show plugin status, active source and current settings |
| `/tsm sources` | `tilemapper.admin` | List all available tile sources |
| `/tsm source <name>` | `tilemapper.admin` | Switch to a different tile source on the fly |
| `/tsm blocksets` | `tilemapper.admin` | List all available blockset files |
| `/tsm blockset <name>` | `tilemapper.admin` | Switch to a different blockset |
| `/tsm reload` | `tilemapper.admin` | Hot-reload `config.yml` without restarting |

By default, server operators (OP) have the `tilemapper.admin` permission.

---

## Configuration

Full configuration documentation has moved to the [project Wiki](https://github.com/LonghiTW/TileMapper/wiki/Configuration).

> Quick reference: `plugins/TileMapper/config.yml` controls tile sources, zoom, offsets, blockset selection, and more.
> See the [Wiki page](https://github.com/LonghiTW/TileMapper/wiki/Configuration) for a complete reference with all available options including `invert_zoom`, `invert_lat`, `{random}` subdomain rotation, and more.

---

## Blockset — Custom Block Color Mapping

Full blockset documentation has moved to the [project Wiki](https://github.com/LonghiTW/TileMapper/wiki/Blockset).

> Quick reference: `plugins/TileMapper/blockset/` holds block colour data. `active_blockset` in `config.yml` selects which one to use.
> See the [Wiki page](https://github.com/LonghiTW/TileMapper/wiki/Blockset) for JSON format, available blocksets, Lab/RGB checks, and the Python generation tool.

---

## How It Works

```
New chunk generated — SatelliteBlockPopulator.populate() runs in T+- generation pipeline
       │
       ▼
  For each 16×16 column:
       │
       ├─ Read surface_material from T+- config.yml (for block mask)
       │
       ├─ CachedChunkData.groundHeight() + RealWorldGenerator.getYOffset()
       │     └─ Gets exact surface Y (identical to T+-)
       │
       ├─ TerraConnector.toGeo(worldX + tileOffsetX, worldZ + tileOffsetZ)
       │     └─ MC coordinates → latitude/longitude
       │
       ├─ TileServer.fetch(lat, lon, zoom)
       │     └─ HTTP async request → bicubic interpolation → RGB
       │
       └─ Oklab Euclidean distance nearest-neighbor match
             └─ Sets block type
```

---

## Dependencies

| Dependency | Purpose | Scope |
|------------|---------|-------|
| [Paper API](https://papermc.io/) 1.21.8 | Bukkit API | provided |
| [TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus) | Terrain engine, surface generation, projection transforms | runtime |
| [Lombok](https://projectlombok.org/) 1.18.42 | Boilerplate reduction | provided (annotation) |
| [Gson](https://github.com/google/gson) 2.13.2 | JSON parsing (blockset files) | shaded |
| [bStats](https://bstats.org/) 3.1.0 | Anonymous usage metrics | shaded |

---

## Credits

- **TerraSatelliteMapper** ([tf2mandeokyi/TerraSatelliteMapper](https://github.com/tf2mandeokyi/TerraSatelliteMapper)) — original Forge mod by [@tf2mandeokyi](https://github.com/tf2mandeokyi), which this project is ported from
- **Terra--** ([SmylerMC/terraminusminus](https://github.com/SmylerMC/terraminusminus)) developed by [@SmylerMC](https://github.com/SmylerMC) — the underlying terrain engine and projection API (bundled within TerraPlusMinus)
- **TerraPlusMinus** ([BTE-Germany/TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus)) — surface generation plugin for Paper, bundles the terrain engine
- **hueblocks** ([1280px/hueblocks](https://github.com/1280px/hueblocks)) — the `jar2blockset` pipeline that `assets2blockset.py` is derived from

---

## License

This project is licensed under the [MIT License](https://github.com/LonghiTW/TileMapper/blob/main/LICENSE.txt).
