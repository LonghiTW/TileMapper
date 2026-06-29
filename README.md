# TileMapper

A Paper plugin that overlays real-world map tiles onto the Minecraft terrain surface.
Seamlessly integrates with [TerraPlusMinus](https://github.com/BTE-Germany/TerraPlusMinus) which provides the terrain engine and projection transforms.

> This project is a refactored **plugin** port of the original [TerraSatelliteMapper](https://github.com/tf2mandeokyi/TerraSatelliteMapper) **Minecraft Forge mod** (by [@tf2mandeokyi](https://github.com/tf2mandeokyi)), adapted for Paper 1.21.8+ server environments.

---

## Features

- �️ **Tile-based terrain** — Fetches map tiles from any tile server (satellite, street map, aerial, etc.) and converts them to Minecraft blocks
- 🌍 **Tile offset** — `tile_offset` config lets you fine-tune image alignment in block coordinates
- 🔄 **Hot reload** — `/tsm reload` applies config changes without server restart

---

## Requirements

- **Paper 1.21.8+** server
- **TerraPlusMinus** plugin
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
git clone https://github.com/LonghiTW/TerraSatelliteMapper.git
cd TerraSatelliteMapper
scripts/setup        # download TerraPlusMinus compile-time dependency
mvn clean package    # requires JDK 21+
```

> **Note:** The setup script downloads TerraPlusMinus JAR into `libs/`. This file is tracked in Git so contributors don't need extra steps.

Output: `target/tilemapper-<version>.jar` (~428 KB, shaded with Gson + bStats).

---

## Configuration

`plugins/TileMapper/config.yml`:

```yaml
# Enable / disable the plugin
enabled: true

# Active blockset file (without .json).
# "custom_blockset" = local file in blockset/custom_blockset.json
#   (initially downloaded from All.json; you may edit or replace it freely).
# Any other name (Default, All, Grayscale) =
# read live from the repository (no local file needed).
active_blockset: "Default"

# Active tile source (must match one of the keys under tile_sources:)
active_source: osm

# Multiple tile sources — add as many as you want
tile_sources:
  osm:
    url: "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
    zoom: 18
    offset:
      x: 0
      z: 0
  bing:
    url: "https://t.ssl.ak.dynamic.tiles.virtualearth.net/comp/ch/{u}?it=A&shading=hill"
    zoom: 20
    offset:
      x: 0
      z: 0
  yandex:
    url: "https://core-sat.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}"
    zoom: 20
    offset:
      x: 0
      z: 0
  tw:
    url: "https://wmts.nlsc.gov.tw/wmts/PHOTO2/default/GoogleMapsCompatible/{z}/{y}/{x}.png"
    zoom: 20
    offset:
      x: -0.625
      z: 0.3125

# Maximum concurrent HTTP requests
max_concurrent_requests: 2

# In-memory tile cache size
cache_size: 1000

# When true, only replace the configured surface block type — preserves
# road/building markers that T+- generates on the surface from OSM data.
# The target block type is read from T+-'s surface_material config (default: GRASS_BLOCK).
# Set to false to replace any surface block.
surface_block_mask: false

# Config version (do not change)
config_version: "1.0"
```

> **Note**: Surface mask uses the `surface_material` value from T+-'s `config.yml` (default: `GRASS_BLOCK`).
> The surface Y offset is automatically obtained via `RealWorldGenerator.getYOffset()` — no manual config needed.

---

## Blockset — Custom Block Color Mapping

`plugins/TileMapper/blockset/` holds all block color data files, giving you full control over which blocks are used for tile image reproduction.

### How it works

- **Config reference**: `active_blockset` field in `config.yml` selects which blockset to use
- **Online blocksets** (`All`, `Default`, `Grayscale`) are read **live from the repository** — no local file needed, no manual updates required
- **Local blockset**: `custom_blockset.json` — a default local file downloaded from `All.json` on first launch. Edit this file to use your own palette, or replace it entirely with any valid blockset JSON you like.
- **Hot-switch**: Use `/tsm blockset <name>` to switch between any online blockset or a local file

### Available blocksets

Blocksets are hosted on the [GitHub repository](https://github.com/LonghiTW/TileMapper/tree/main/tool/blockset) and read live at runtime — no local download required (except for `custom_blockset`):

| File | Source | Description |
|------|--------|-------------|
| `custom_blockset` | local `blockset/custom_blockset.json` | Default local file (auto-downloaded from `All.json` on first launch; freely editable or replaceable) |
| `All` | repository (online) | All available blocks (full spectrum) |
| `Default` | repository (online) | Curated palette — good all-round balance |
| `Grayscale` | repository (online) | Pure grey blocks — activates **luminance-only matching** |

### JSON format

Blockset files are flat JSON arrays. Each entry has at least `id` (block registry name) and `rgb` (sRGB 8-bit color).
The optional `lab` field stores pre-computed Oklab values for faster loading.

```json
[
  {"id": "stone", "rgb": [126, 126, 126]},
  {"id": "dirt", "rgb": [134, 96, 67]}
]
```

> The `id` field must be a valid Minecraft registry name (e.g. `stone`, `oak_planks`).
> Entries with unrecognized block IDs are silently skipped.

### Lab vs RGB sanity check

On first load, the plugin checks whether the stored Oklab values match the RGB source.
If they match (within `d² < 0.01`), the stored Oklab values are used directly.
If they don't — indicating the file was generated for a different color space — the plugin
**automatically falls back** to computing Oklab from `rgb` at runtime for all entries.
This means you can safely add your own entries with just `id` + `rgb` — they'll work correctly.

### Example layout

```
plugins/TileMapper/blockset/
  custom_blockset.json    ← your local blockset file
```

You are free to add any other `.json` files to this folder — just reference them by name in `active_blockset` (without the `.json` extension).
To restore the default `custom_blockset.json`, delete the `blockset/` folder and restart the server — the plugin will re-download `All.json` from the repository.

> **Color space**: TileMapper uses **Oklab** with Euclidean distance for perceptually-uniform block color matching.

### Generating a custom blockset

A Python tool is available at [`tool/assets2blockset.py`](tool/assets2blockset.py)
that extracts block colors from Minecraft's official assets texture pack
(using `textures/block`), computes Oklab values, and generates
a complete blockset JSON file with palette-based deduplication.
Derived from [hueblocks/jar2blockset](https://github.com/1280px/hueblocks/tree/master/jar2blockset).

```bash
# Download the matching asset pack and run:
python tool/assets2blockset.py --version 1.21.8 --output my_blockset.json
```

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
