package com.mndk.tilemapper.processor;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.tile.TileImageData;
import com.mndk.tilemapper.tile.server.TileServer;
import com.mndk.tilemapper.util.RGBColorDouble;
import net.buildtheearth.terraminusminus.generator.CachedChunkData;
import net.buildtheearth.terraminusminus.generator.ChunkDataLoader;
import net.buildtheearth.terraminusminus.generator.EarthGeneratorSettings;
import net.buildtheearth.terraminusminus.projection.GeographicProjection;
import net.buildtheearth.terraminusminus.projection.OutOfProjectionBoundsException;
import net.buildtheearth.terraminusminus.projection.transform.OffsetProjectionTransform;
import net.buildtheearth.terraminusminus.substitutes.ChunkPos;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Logger;

/**
 * Core processing logic: replaces surface blocks in newly generated chunks
 * with satellite-imagery-derived colors.
 *
 * <p>Heights are obtained from T--'s {@link CachedChunkData} via {@link ChunkDataLoader},
 * with the same {@code terrain_offset} values that T+- uses, ensuring perfect
 * surface-height synchronization.</p>
 */
public class ChunkProcessor {

    private static final Logger LOGGER = Logger.getLogger("TSM");

    private TileServer tileServer;
    private final PluginConfig config;
    private final JavaPlugin plugin;

    private ChunkDataLoader loader;
    private GeographicProjection projection;
    private int tPlusYOffset;
    private Material surfaceBlockMaterial;
    private boolean initialized = false;

    public ChunkProcessor(TileServer tileServer, PluginConfig config, JavaPlugin plugin) {
        this.tileServer = tileServer;
        this.config = config;
        this.plugin = plugin;
    }

    /**
     * Lazy-initialize the ChunkDataLoader by reading T+-'s config.
     * Called once on first chunk process.
     */
    private void ensureInitialized() {
        if (initialized) return;

        File tplusFolder = new File(plugin.getDataFolder().getParentFile(), "Terraplusminus");
        File tplusConfigFile = new File(tplusFolder, "config.yml");

        if (tplusConfigFile.exists()) {
            YamlConfiguration tplusConfig = YamlConfiguration.loadConfiguration(tplusConfigFile);
            int tOffX = tplusConfig.getInt("terrain_offset.x", 0);
            int tOffZ = tplusConfig.getInt("terrain_offset.z", 0);
            this.tPlusYOffset = tplusConfig.getInt("terrain_offset.y", 0);

            // Build EarthGeneratorSettings matching T+-'s setup
            EarthGeneratorSettings settings = EarthGeneratorSettings.parse(
                    EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
            GeographicProjection projection = settings.projection();
            if (tOffX != 0 || tOffZ != 0) {
                projection = new OffsetProjectionTransform(projection, tOffX, tOffZ);
            }
            settings = settings.withProjection(projection);
            this.loader = new ChunkDataLoader(settings);
            this.projection = projection;

            // Read surface_material from T+- config for surface_block_mask
            String surfaceMatName = tplusConfig.getString("surface_material", "GRASS_BLOCK");
            try {
                this.surfaceBlockMaterial = Material.valueOf(surfaceMatName.toUpperCase());
            } catch (IllegalArgumentException e) {
                this.surfaceBlockMaterial = Material.GRASS_BLOCK;
                LOGGER.warning("Invalid surface_material in T+- config: " + surfaceMatName + "; falling back to GRASS_BLOCK");
            }

            LOGGER.info("ChunkProcessor initialized with T+- terrain_offset: x="
                    + tOffX + ", z=" + tOffZ + ", y=" + tPlusYOffset
                    + ", surface_material=" + this.surfaceBlockMaterial.name());
        } else {
            // T+- config not found; use defaults (offsets = 0, surface = grass)
            LOGGER.info("T+- config not found at " + tplusConfigFile.getAbsolutePath()
                    + "; using terrain_offset defaults (all 0), surface_material=GRASS_BLOCK.");
            EarthGeneratorSettings defaultSettings = EarthGeneratorSettings.parse(
                    EarthGeneratorSettings.BTE_DEFAULT_SETTINGS);
            this.loader = new ChunkDataLoader(defaultSettings);
            this.projection = defaultSettings.projection();
            this.tPlusYOffset = 0;
            this.surfaceBlockMaterial = Material.GRASS_BLOCK;
        }

        initialized = true;
    }

    /**
     * Process a single freshly-generated chunk: replace surface blocks with
     * satellite-derived materials.
     */
    public void processChunk(Chunk chunk) {
        ensureInitialized();

        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();

        // Load T-- terrain data for this chunk (blocking join in async context)
        CachedChunkData terraData;
        try {
            terraData = loader.load(new ChunkPos(chunkX, chunkZ)).join();
        } catch (Exception e) {
            // ChunkDataLoader failed ??likely not a Terra world or data unavailable
            return;
        }

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {

                // 1. Surface height ??matches T+- exactly
                int surfaceY = terraData.groundHeight(x, z) + tPlusYOffset;

                // 2. World coordinates with tile offset
                int worldX = (chunkX << 4) + x + config.getTileOffsetX();
                int worldZ = (chunkZ << 4) + z + config.getTileOffsetZ();

                // 3. Convert to geographic coordinates using T-- projection
                double[] geo;
                try {
                    geo = projection.toGeo(worldX, worldZ);
                } catch (OutOfProjectionBoundsException e) {
                    continue; // outside projection bounds, skip column
                }

                // 4. Fetch tile pixel color (async ??blocks current thread)
                TileImageData tile;
                try {
                    tile = tileServer.fetch(geo[0], geo[1], config.getZoom()).join();
                } catch (Exception e) {
                    continue; // skip column on fetch failure
                }
                RGBColorDouble color = tile.getRGBByGeoCoordinate(geo[0], geo[1]);
                if (color == null) continue;

                // 5. Find nearest block
                Material material = BlockColorRegistry.getNearestBlock(color.toRGBColor());
                if (material == null) continue;

                // 6. Apply surface_block_mask: when true, only replace the configured surface block
                //    (reads surface_material from T+- config.yml to determine which block to replace)
                Block targetBlock = chunk.getBlock(x, surfaceY, z);
                Material originalMaterial = targetBlock.getType();

                if (!config.isSurfaceBlockMask() || originalMaterial == surfaceBlockMaterial) {
                    targetBlock.setType(material, false);
                }
            }
        }
    }

    public void setTileServer(TileServer tileServer) {
        this.tileServer = tileServer;
    }
}
