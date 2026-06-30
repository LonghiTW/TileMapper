package com.mndk.tilemapper.processor;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.tile.TileImageData;
import com.mndk.tilemapper.tile.server.TileServer;
import com.mndk.tilemapper.util.RGBColorDouble;
import de.btegermany.terraplusminus.data.TerraConnector;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Core processing logic: replaces surface blocks in newly generated chunks
 * with satellite-imagery-derived colors.
 *
 * <p>Uses T+-'s {@link RealWorldGenerator} to obtain the exact surface Y through
 * the same {@code CachedChunkData.groundHeight() + yOffset} logic that T+- itself
 * uses during chunk generation. This guarantees Y-level accuracy even with
 * negative terrain offsets.</p>
 */
public class ChunkProcessor {

    private static final Logger LOGGER = Logger.getLogger("TSM");

    private TileServer tileServer;
    private final PluginConfig config;
    private final JavaPlugin plugin;

    private Material surfaceBlockMaterial;
    private boolean initialized = false;

    public ChunkProcessor(TileServer tileServer, PluginConfig config, JavaPlugin plugin) {
        this.tileServer = tileServer;
        this.config = config;
        this.plugin = plugin;
    }

    private void ensureInitialized() {
        if (initialized) return;

        File tplusFolder = new File(plugin.getDataFolder().getParentFile(), "Terraplusminus");
        File tplusConfigFile = new File(tplusFolder, "config.yml");

        if (tplusConfigFile.exists()) {
            YamlConfiguration tplusConfig = YamlConfiguration.loadConfiguration(tplusConfigFile);
            String surfaceMatName = tplusConfig.getString("surface_material", "GRASS_BLOCK");
            try {
                this.surfaceBlockMaterial = Material.valueOf(surfaceMatName.toUpperCase());
            } catch (IllegalArgumentException e) {
                this.surfaceBlockMaterial = Material.GRASS_BLOCK;
                LOGGER.warning("Invalid surface_material in T+- config: " + surfaceMatName + "; falling back to GRASS_BLOCK");
            }
            LOGGER.info("ChunkProcessor initialized, surface_material=" + this.surfaceBlockMaterial.name());
        } else {
            LOGGER.info("T+- config not found; using defaults: surface_material=GRASS_BLOCK.");
            this.surfaceBlockMaterial = Material.GRASS_BLOCK;
        }

        initialized = true;
    }

    /**
     * Process a single freshly-generated chunk: replace surface blocks with
     * satellite-derived materials.
     *
     * <p>Runs synchronously on the server main thread to avoid any timing issues
     * with FAWE async chunk regeneration. Uses T+-'s own
     * {@code CachedChunkData.groundHeight() + yOffset} for exact Y placement.</p>
     */
    public void processChunk(Chunk chunk) {
        ensureInitialized();

        int chunkX = chunk.getX();
        int chunkZ = chunk.getZ();
        World world = chunk.getWorld();

        // --- Get surface Y from T+-'s own CachedChunkData ---
        int yOffset;
        Object cachedChunkData;

        try {
            ChunkGenerator gen = world.getGenerator();
            if (!(gen instanceof RealWorldGenerator rwg)) {
                return;
            }
            yOffset = rwg.getYOffset();

            CompletableFuture<?> future = rwg.getBaseHeightAsync(chunkX, chunkZ);
            cachedChunkData = future.join();
        } catch (Throwable t) {
            if (t instanceof NoSuchMethodError) {
                LOGGER.severe("TerraPlusMinus version is too old! getBaseHeightAsync(int,int) not found.");
                LOGGER.severe("TileMapper requires TerraPlusMinus 1.6.1+ — please update the plugin.");
            } else {
                LOGGER.warning("Failed to get height data: " + t.getMessage());
            }
            return;
        }

        Method groundHeightMethod;
        try {
            groundHeightMethod = cachedChunkData.getClass()
                    .getMethod("groundHeight", int.class, int.class);
        } catch (NoSuchMethodException e) {
            LOGGER.severe("Cannot find groundHeight: " + e.getMessage());
            return;
        }

        int applied = 0, noSurface = 0, outside = 0, tileFail = 0;
        int noColor = 0, noBlock = 0, maskSkipped = 0, sameSkipped = 0;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int blockX = (chunkX << 4) + x;
                int blockZ = (chunkZ << 4) + z;

                // Surface Y via T+- groundHeight + yOffset
                int surfaceY;
                try {
                    int gh = (int) groundHeightMethod.invoke(cachedChunkData, x, z);
                    surfaceY = gh + yOffset;
                } catch (Exception e) {
                    noSurface++;
                    continue;
                }

                // Geographic coordinates for tile lookup (with tile offset)
                double[] geo;
                try {
                    geo = TerraConnector.toGeo(
                            blockX + config.getTileOffsetX(),
                            blockZ + config.getTileOffsetZ());
                } catch (Exception e) {
                    outside++;
                    continue;
                }

                // Fetch tile & get color (blocks main thread briefly)
                TileImageData tile;
                try {
                    tile = tileServer.fetch(geo[0], geo[1], config.getZoom()).join();
                } catch (Exception e) {
                    if (tileFail == 0) {
                        LOGGER.warning("Tile fetch failed at [" + chunkX + "," + chunkZ +
                                "] geo=(" + String.format("%.4f", geo[0]) + ", " + String.format("%.4f", geo[1]) + ")");
                    }
                    tileFail++;
                    continue;
                }
                RGBColorDouble color = tile.getRGBByGeoCoordinate(geo[0], geo[1]);
                if (color == null) { noColor++; continue; }

                Material material = BlockColorRegistry.getNearestBlock(color.toRGBColor());
                if (material == null) { noBlock++; continue; }

                // Apply immediately to the fresh chunk
                Block block = chunk.getBlock(x, surfaceY, z);
                Material original = block.getType();

                if (material == original) { sameSkipped++; continue; }
                if (config.isSurfaceBlockMask() && original != surfaceBlockMaterial) { maskSkipped++; continue; }

                block.setType(material, false);
                applied++;
                if (surfaceY < minY) minY = surfaceY;
                if (surfaceY > maxY) maxY = surfaceY;
            }
        }

        int totalErrors = noSurface + outside + tileFail + noColor + noBlock + maskSkipped + sameSkipped;
        String logMsg = "Chunk [" + chunkX + ", " + chunkZ + "] applied " + applied +
                " changes (Y: " + (applied > 0 ? minY + "~" + maxY : "N/A") + ")" +
                " (noSurface=" + noSurface +
                ", outside=" + outside +
                ", tileFail=" + tileFail +
                ", noColor=" + noColor +
                ", noBlock=" + noBlock +
                ", mask=" + maskSkipped +
                ", same=" + sameSkipped + ")";
        if (applied > 0 && totalErrors == 0) {
            LOGGER.fine(logMsg);  // Normal operation — hide from default console
        } else if (applied > 0) {
            LOGGER.info(logMsg);  // Has errors — keep visible
        } else {
            LOGGER.warning(logMsg);  // Zero changes — warn
        }
    }

    public void setTileServer(TileServer tileServer) {
        this.tileServer = tileServer;
    }

}
