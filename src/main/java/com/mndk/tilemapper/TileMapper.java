package com.mndk.tilemapper;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.processor.ChunkProcessor;
import com.mndk.tilemapper.tile.TilePosToUrlFunction;
import com.mndk.tilemapper.tile.projection.WebMercatorTileProjection;
import com.mndk.tilemapper.tile.server.TileServer;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URL;

public class TileMapper extends JavaPlugin implements Listener {

    public static TileMapper instance;

    private PluginConfig pluginConfig;
    private TileServer tileServer;
    private ChunkProcessor processor;

    @Override
    public void onEnable() {
        instance = this;

        // 1. 載入 config
        this.pluginConfig = new PluginConfig(this);

        // 2. 初始化 BlockColorRegistry（載入 block_data.json）
        BlockColorRegistry.init(getClass());

        // 3. 初始化 TileServer
        this.tileServer = new TileServer(
                new WebMercatorTileProjection(false),
                buildUrlFunction(),
                pluginConfig.getMaxConcurrentRequests(),
                pluginConfig.getCacheSize()
        );

        // 4. 初始化 ChunkProcessor
        this.processor = new ChunkProcessor(tileServer, pluginConfig, this);

        // 5. 註冊 ChunkLoadEvent 監聽（新 chunk 生成時觸發）
        getServer().getPluginManager().registerEvents(this, this);

        // 6. 註冊指令
        SatelliteCommand command = new SatelliteCommand(pluginConfig, processor, tileServer);
        command.register(this);

        // 7. bStats 指標
        int pluginId = 0; // TODO: register at https://bstats.org/ and fill in plugin ID
        if (pluginId > 0) {
            new Metrics(this, pluginId);
        }

        getLogger().info("TileMapper enabled (tile offset: " +
                pluginConfig.getTileOffsetX() + ", " + pluginConfig.getTileOffsetZ() + ")");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkGenerate(ChunkLoadEvent event) {
        if (!pluginConfig.isEnabled()) return;
        if (!event.isNewChunk()) return;
        Chunk chunk = event.getChunk();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            processor.processChunk(chunk);
        });
    }

    @Override
    public void onDisable() {
        if (tileServer != null) {
            tileServer.getExecutorService().shutdown();
        }
    }

    /** For reload command */
    public void reloadPlugin() {
        pluginConfig.reload();
        getLogger().info("Configuration reloaded.");
    }

    /**
     * Switch to a different tile source by name.
     * Rebuilds the TileServer with the new URL function.
     * @return true if the source was found and switched
     */
    public boolean switchTileSource(String name) {
        if (!pluginConfig.getTileSources().containsKey(name)) {
            return false;
        }
        pluginConfig.setActiveSource(name);

        // Shut down old tile server
        if (tileServer != null) {
            tileServer.getExecutorService().shutdown();
        }

        // Rebuild TileServer with new URL function
        this.tileServer = new TileServer(
                new WebMercatorTileProjection(false),
                buildUrlFunction(),
                pluginConfig.getMaxConcurrentRequests(),
                pluginConfig.getCacheSize()
        );
        this.processor.setTileServer(this.tileServer);

        getLogger().info("Switched to tile source: " + name);
        return true;
    }

    private TilePosToUrlFunction buildUrlFunction() {
        return pos -> {
            String quadKey = com.mndk.tilemapper.util.TileQuadKey.toQuadKey(pos);
            String urlStr = pluginConfig.getTileUrl()
                    .replace("{z}", String.valueOf(pos.zoom))
                    .replace("{x}", String.valueOf(pos.x))
                    .replace("{y}", String.valueOf(pos.y))
                    .replace("{quadkey}", quadKey);
            return new URL(urlStr);
        };
    }

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    public ChunkProcessor getProcessor() {
        return processor;
    }

    public TileServer getTileServer() {
        return tileServer;
    }
}
