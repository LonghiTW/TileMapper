package com.mndk.tilemapper;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.processor.ChunkProcessor;
import com.mndk.tilemapper.processor.SatelliteBlockPopulator;
import com.mndk.tilemapper.tile.TilePosToUrlFunction;
import com.mndk.tilemapper.tile.projection.WebMercatorTileProjection;
import com.mndk.tilemapper.tile.server.TileServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import de.btegermany.terraplusminus.gen.RealWorldGenerator;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class TileMapper extends JavaPlugin implements Listener {

    /** Hardcoded base URL for canonical blockset files on GitHub. */
    public static final String BLOCKSET_BASE_URL =
            "https://raw.githubusercontent.com/LonghiTW/TileMapper/refs/heads/main/tool/blockset/";

    public static TileMapper instance;

    private PluginConfig pluginConfig;
    private TileServer tileServer;
    private ChunkProcessor processor;
    private SatelliteBlockPopulator populator;
    private boolean populatorActive = false;

    @Override
    public void onEnable() {
        instance = this;

        // 1. 載入 config
        this.pluginConfig = new PluginConfig(this);

        // 2. 初始化 blockset 資料夾 (plugins/TileMapper/blockset/)
        //    僅 custom_blockset.json 存在於此 — 從 GitHub 下載 All.json 存放。
        //    其他 blocksets（Default、All、Grayscale…）直接從 repo URL 讀取。
        File blocksetFolder = new File(getDataFolder(), "blockset");
        if (!blocksetFolder.exists()) {
            blocksetFolder.mkdirs();
        }

        File customFile = new File(blocksetFolder, "custom_blockset.json");
        if (!customFile.exists()) {
            boolean downloaded = BlockColorRegistry.downloadDefaultBlockset(BLOCKSET_BASE_URL, customFile);
            if (downloaded) {
                getLogger().info("Downloaded custom_blockset.json from " + BLOCKSET_BASE_URL);
            }
        }

        BlockColorRegistry.init(blocksetFolder, BLOCKSET_BASE_URL, pluginConfig.getActiveBlockset());

        // 3. 初始化 TileServer
        this.tileServer = new TileServer(
                new WebMercatorTileProjection(false),
                buildUrlFunction(),
                pluginConfig.getMaxConcurrentRequests(),
                pluginConfig.getCacheSize()
        );

        // 4. 初始化 ChunkProcessor (fallback for ChunkLoadEvent)
        this.processor = new ChunkProcessor(tileServer, pluginConfig, this);

        // 5. 初始化 BlockPopulator
        this.populator = new SatelliteBlockPopulator(tileServer, pluginConfig, this);

        // 嘗試註冊到已載入的世界（通常此時還沒有世界，但 best-effort）
        for (World world : getServer().getWorlds()) {
            tryRegisterPopulator(world);
        }
        if (!populatorActive) {
            getLogger().info("No world loaded yet — will register BlockPopulator on WorldLoadEvent");
        }

        // 6. 註冊 ChunkLoadEvent 監聽（作為備援）
        getServer().getPluginManager().registerEvents(this, this);

        // 7. 註冊指令
        SatelliteCommand command = new SatelliteCommand(pluginConfig, processor, tileServer);
        command.register(this);

        // 8. bStats 指標
        int pluginId = 0; // TODO: register at https://bstats.org/ and fill in plugin ID
        if (pluginId > 0) {
            new Metrics(this, pluginId);
        }

        // 9. 非同步版本檢查
        Bukkit.getScheduler().runTaskAsynchronously(this, this::checkVersion);

        getLogger().info("TileMapper enabled (tile offset: " +
                pluginConfig.getTileOffsetX() + ", " + pluginConfig.getTileOffsetZ() + ")");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkGenerate(ChunkLoadEvent event) {
        if (!pluginConfig.isEnabled()) return;
        if (!event.isNewChunk()) return;
        // 如果 BlockPopulator 已成功註冊，主流程由它處理，此處跳過避免重複
        if (populatorActive) return;
        // Fallback: 若 BlockPopulator 未註冊（例如非 T+- 世界），改由 ChunkLoadEvent 處理
        Chunk chunk = event.getChunk();
        processor.processChunk(chunk);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldLoad(WorldLoadEvent event) {
        if (populatorActive) return; // 已經有註冊成功的 populator
        tryRegisterPopulator(event.getWorld());
    }

    /**
     * 嘗試將 BlockPopulator 註冊到指定世界（僅限 T+- 世界）。
     */
    private void tryRegisterPopulator(World world) {
        if (world.getGenerator() instanceof RealWorldGenerator) {
            world.getPopulators().add(populator);
            this.populatorActive = true;
            getLogger().info("Registered SatelliteBlockPopulator for world '" + world.getName() + "'");
        }
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

        // Reload blockset: reset BlockColorRegistry and re-init from active blockset
        BlockColorRegistry.reset();
        File blocksetFolder = new File(getDataFolder(), "blockset");
        BlockColorRegistry.init(blocksetFolder, BLOCKSET_BASE_URL, pluginConfig.getActiveBlockset());

        getLogger().info("Configuration reloaded (blockset: " + pluginConfig.getActiveBlockset()
                + ", " + BlockColorRegistry.getMappingCount() + " block colours).");
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

    /**
     * Switch to a different blockset by name, reloading BlockColorRegistry.
     * @return true if the blockset was found and loaded
     */
    public boolean switchBlockset(String name) {
        File blocksetFolder = new File(getDataFolder(), "blockset");

        // Only custom_blockset needs to exist locally
        if ("custom_blockset".equals(name)) {
            File targetFile = new File(blocksetFolder, "custom_blockset.json");
            if (!targetFile.exists()) {
                getLogger().warning("custom_blockset.json not found in blockset/");
                return false;
            }
        }

        pluginConfig.setActiveBlockset(name);
        BlockColorRegistry.reset();
        BlockColorRegistry.init(blocksetFolder, BLOCKSET_BASE_URL, name);

        getLogger().info("Switched to blockset: " + name
                + " (" + BlockColorRegistry.getMappingCount() + " block colours)");
        return true;
    }

    private TilePosToUrlFunction buildUrlFunction() {
        return pos -> {
            String quadKey = com.mndk.tilemapper.util.TileQuadKey.toQuadKey(pos);
            String bingU = com.mndk.tilemapper.util.TileQuadKey.toBingU(pos.x, pos.y, pos.zoom);
            String urlStr = pluginConfig.getTileUrl()
                    .replace("{z}", String.valueOf(pos.zoom))
                    .replace("{x}", String.valueOf(pos.x))
                    .replace("{y}", String.valueOf(pos.y))
                    .replace("{quadkey}", quadKey)
                    .replace("{u}", bingU);
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

    /**
     * Check GitHub Releases for a newer version and log a warning if found.
     * Runs asynchronously — failures are silently ignored.
     */
    private void checkVersion() {
        try {
            URL url = new URL("https://api.github.com/repos/LonghiTW/TileMapper/releases/latest");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "TileMapper");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            String currentVersion = getPluginMeta().getVersion();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()))) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                String latestTag = json.get("tag_name").getAsString();
                String latestVersion = latestTag.startsWith("v")
                        ? latestTag.substring(1) : latestTag;

                if (!currentVersion.equals(latestVersion)) {
                    getLogger().warning("=" .repeat(56));
                    getLogger().warning("  New version available: " + latestTag
                            + " (current: v" + currentVersion + ")");
                    getLogger().warning("  Download: https://github.com/LonghiTW/TileMapper/releases");
                    getLogger().warning("=" .repeat(56));
                }
            }
        } catch (Exception e) {
            // Silently ignore — version check is non-critical
            getLogger().fine("Version check failed: " + e.getMessage());
        }
    }
}
