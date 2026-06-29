package com.mndk.tilemapper.processor;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.tile.TileImageData;
import com.mndk.tilemapper.tile.server.TileServer;
import com.mndk.tilemapper.util.RGBColorDouble;
import de.btegermany.terraplusminus.data.TerraConnector;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * A {@link BlockPopulator} that replaces surface blocks with satellite-derived
 * materials during chunk generation — same pipeline stage as T+-'s TreePopulator.
 */
public class SatelliteBlockPopulator extends BlockPopulator {

    private static final Logger LOGGER = Logger.getLogger("TSM");

    private final TileServer tileServer;
    private final PluginConfig config;
    private final JavaPlugin plugin;
    private final double tileOffsetX;
    private final double tileOffsetZ;

    private Method groundHeightMethod;
    private Method getBaseHeightAsyncMethod;
    private Material surfaceBlockMaterial;
    private boolean initDone = false;

    public SatelliteBlockPopulator(TileServer tileServer, PluginConfig config, JavaPlugin plugin) {
        this.tileServer = tileServer;
        this.config = config;
        this.plugin = plugin;
        this.tileOffsetX = config.getTileOffsetX();
        this.tileOffsetZ = config.getTileOffsetZ();
    }

    private void ensureInit() {
        if (initDone) return;
        try {
            Class<?> clazz = Class.forName("net.buildtheearth.terraminusminus.generator.CachedChunkData");
            this.groundHeightMethod = clazz.getMethod("groundHeight", int.class, int.class);
            this.getBaseHeightAsyncMethod = RealWorldGenerator.class.getMethod("getBaseHeightAsync", int.class, int.class);
            File tplusFolder = new File(plugin.getDataFolder().getParentFile(), "Terraplusminus");
            File tplusConfigFile = new File(tplusFolder, "config.yml");
            if (tplusConfigFile.exists()) {
                YamlConfiguration tplusConfig = YamlConfiguration.loadConfiguration(tplusConfigFile);
                String matName = tplusConfig.getString("surface_material", "GRASS_BLOCK");
                try {
                    this.surfaceBlockMaterial = Material.valueOf(matName.toUpperCase());
                } catch (IllegalArgumentException e) {
                    this.surfaceBlockMaterial = Material.GRASS_BLOCK;
                }
            } else {
                this.surfaceBlockMaterial = Material.GRASS_BLOCK;
            }
        } catch (Exception e) {
            LOGGER.severe("SatelliteBlockPopulator init failed: " + e.getMessage());
        }
        initDone = true;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
        if (!config.isEnabled()) return;
        ensureInit();
        if (groundHeightMethod == null) return;
        World world = Bukkit.getWorld(worldInfo.getName());
        if (world == null) return;
        if (!(world.getGenerator() instanceof RealWorldGenerator rwg)) return;
        int yOffset = rwg.getYOffset();
        Object cachedChunkData;
        try {
            Object future = getBaseHeightAsyncMethod.invoke(rwg, chunkX, chunkZ);
            cachedChunkData = ((java.util.concurrent.CompletableFuture<?>) future).join();
        } catch (Exception e) {
            return;
        }
        int applied = 0, maskSkipped = 0, sameSkipped = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int surfaceY;
                try {
                    surfaceY = (int) groundHeightMethod.invoke(cachedChunkData, x, z) + yOffset;
                } catch (Exception e) { continue; }
                double bx = (chunkX << 4) + x + tileOffsetX;
                double bz = (chunkZ << 4) + z + tileOffsetZ;
                double[] geo;
                try { geo = TerraConnector.toGeo(bx, bz); } catch (Exception e) { continue; }
                TileImageData tile;
                try { tile = tileServer.fetch(geo[0], geo[1], config.getZoom()).join(); } catch (Exception e) { continue; }
                RGBColorDouble color = tile.getRGBByGeoCoordinate(geo[0], geo[1]);
                if (color == null) continue;
                Material material = BlockColorRegistry.getNearestBlock(color.toRGBColor());
                if (material == null) continue;
                int wx = (chunkX << 4) + x;
                int wz = (chunkZ << 4) + z;
                Material original = limitedRegion.getBlockData(wx, surfaceY, wz).getMaterial();
                if (material == original) { sameSkipped++; continue; }
                if (config.isSurfaceBlockMask() && original != surfaceBlockMaterial) { maskSkipped++; continue; }
                limitedRegion.setBlockData(wx, surfaceY, wz, material.createBlockData());
                applied++;
            }
        }
        if (applied > 0) {
            LOGGER.fine("Populated chunk [" + chunkX + ", " + chunkZ + "] " + applied + " blocks (mask=" + maskSkipped + " same=" + sameSkipped + ")");
        }
    }
}
