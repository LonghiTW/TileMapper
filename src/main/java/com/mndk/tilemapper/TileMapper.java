package com.mndk.tilemapper;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.config.TileSource;
import com.mndk.tilemapper.processor.ChunkProcessor;
import com.mndk.tilemapper.processor.SatelliteBlockPopulator;
import com.mndk.tilemapper.tile.TileCoordTranslator;
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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** false when T+- version is too old (pre-1.6.1). */
    private boolean tplusCompatible = true;

    @Override
    public void onEnable() {
        instance = this;

        // 1. Load config
        this.pluginConfig = new PluginConfig(this);

        // 1b. Check T+- version (≥ 1.6.1 required for getBaseHeightAsync(int,int))
        checkTplusVersion();

        // 2. Initialise blockset directory (plugins/TileMapper/blockset/)
        //    Only custom_blockset.json lives here (auto-downloaded from All.json).
        //    Online blocksets (Default, All, Grayscale) are read from the repo URL at runtime.
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

        // Skip TileServer/Processor/Populator init if T+- version is too old
        if (!tplusCompatible) {
            getLogger().severe("========================================================");
            getLogger().severe("  TileMapper processing disabled due to outdated T+- version.");
            getLogger().severe("  Please update TerraPlusMinus to v1.6.1 or later.");
            getLogger().severe("========================================================");
        } else {
            initTileServerAndProcessors();
        }

        // 6. Register ChunkLoadEvent listener (fallback)
        getServer().getPluginManager().registerEvents(this, this);

        // 7. Register commands
        SatelliteCommand command = new SatelliteCommand(pluginConfig, processor, tileServer);
        command.register(this);

        // 8. bStats metrics
        int pluginId = 0; // TODO: register at https://bstats.org/ and fill in plugin ID
        if (pluginId > 0) {
            new Metrics(this, pluginId);
        }

        // 9. Async version check (GitHub release)
        Bukkit.getScheduler().runTaskAsynchronously(this, this::checkVersion);

        getLogger().info("TileMapper v" + getPluginMeta().getVersion() + " enabled" +
                (tplusCompatible ? "" : " (T+- INCOMPATIBLE — processing disabled)") +
                " (tile offset: " + pluginConfig.getTileOffsetX() + ", " +
                pluginConfig.getTileOffsetZ() + ")");
    }

    /** Initialise tile server, chunk processor and block populator. */
    private void initTileServerAndProcessors() {
        TileSource initSource = pluginConfig.getActiveSource();
        boolean initInvertLat = initSource != null && initSource.invertLat();
        boolean initFlipVertically = initSource != null && initSource.flipVertically();
        this.tileServer = new TileServer(
                new TileCoordTranslator(
                        new WebMercatorTileProjection(), initInvertLat, initFlipVertically),
                buildUrlFunction(),
                pluginConfig.getMaxConcurrentRequests(),
                pluginConfig.getCacheSize()
        );
        this.processor = new ChunkProcessor(tileServer, pluginConfig, this);
        this.populator = new SatelliteBlockPopulator(tileServer, pluginConfig, this);

        for (World world : getServer().getWorlds()) {
            tryRegisterPopulator(world);
        }
        if (!populatorActive) {
            getLogger().info("No world loaded yet — will register BlockPopulator on WorldLoadEvent");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkGenerate(ChunkLoadEvent event) {
        if (!pluginConfig.isEnabled()) return;
        if (!event.isNewChunk()) return;
        if (!tplusCompatible) return;
        // BlockPopulator handles normal generation; ChunkLoadEvent is a fallback for FAWE //regen etc.
        // Dual processing is safe — ChunkProcessor skips blocks already set by the populator (sameSkipped).
        Chunk chunk = event.getChunk();
        processor.processChunk(chunk);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldLoad(WorldLoadEvent event) {
        if (!tplusCompatible) return;
        if (populatorActive) return;
        tryRegisterPopulator(event.getWorld());
    }

    /**
     * Register the BlockPopulator on a world if it uses a RealWorldGenerator (T+-).
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

        if (tplusCompatible) {
            getLogger().info("Configuration reloaded (blockset: " + pluginConfig.getActiveBlockset()
                    + ", " + BlockColorRegistry.getMappingCount() + " block colours).");
        } else {
            getLogger().warning("Configuration reloaded but T+- version is incompatible — processing disabled.");
        }
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
        if (!tplusCompatible) {
            getLogger().warning("Cannot switch tile source: T+- version is incompatible.");
            return false;
        }
        pluginConfig.setActiveSource(name);

        // Shut down old tile server
        if (tileServer != null) {
            tileServer.getExecutorService().shutdown();
        }

        // Rebuild TileServer with new URL function (per-source invertLat & flipVertically)
        TileSource switchSource = pluginConfig.getActiveSource();
        boolean switchInvertLat = switchSource != null && switchSource.invertLat();
        boolean switchFlipVertically = switchSource != null && switchSource.flipVertically();
        this.tileServer = new TileServer(
                new TileCoordTranslator(
                        new WebMercatorTileProjection(), switchInvertLat, switchFlipVertically),
                buildUrlFunction(),
                pluginConfig.getMaxConcurrentRequests(),
                pluginConfig.getCacheSize()
        );
        this.processor.setTileServer(this.tileServer);

        getLogger().info("Switched to tile source: " + name
                + " (invertLat=" + switchInvertLat + ", flipVertically=" + switchFlipVertically + ")");
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

    // ---- T+- version check ----

    /**
     * Check whether the installed TerraPlusMinus version is ≥ 1.6.1.
     * Sets {@link #tplusCompatible} to false if not.
     */
    private void checkTplusVersion() {
        Plugin tplus = getServer().getPluginManager().getPlugin("Terraplusminus");
        if (tplus == null) {
            getLogger().severe("TerraPlusMinus not found! TileMapper cannot function without it.");
            tplusCompatible = false;
            return;
        }
        String version = tplus.getPluginMeta().getVersion();
        if (!isVersionAtLeast(version, "1.6.1")) {
            getLogger().severe("TerraPlusMinus v" + version + " detected — v1.6.1+ is required!");
            getLogger().severe("TileMapper needs RealWorldGenerator.getBaseHeightAsync(int,int),");
            getLogger().severe("which was added in T+- 1.6.1. Please update TerraPlusMinus.");
            tplusCompatible = false;
        } else {
            getLogger().info("TerraPlusMinus v" + version + " detected — OK");
        }
    }

    /** Simple three-segment version comparison (major.minor.patch). */
    private static boolean isVersionAtLeast(String version, String required) {
        try {
            String[] vParts = version.split("-")[0].split("\\.");
            String[] rParts = required.split("\\.");
            int max = Math.max(vParts.length, rParts.length);
            for (int i = 0; i < max; i++) {
                int v = i < vParts.length ? Integer.parseInt(vParts[i]) : 0;
                int r = i < rParts.length ? Integer.parseInt(rParts[i]) : 0;
                if (v != r) return v > r;
            }
            return true;
        } catch (Exception e) {
            return false; // treat unparseable versions as incompatible
        }
    }

    /** Pattern for {random:v1,v2,v3} placeholders. */
    private static final Pattern RANDOM_PATTERN =
            Pattern.compile("\\{random:([^}]+)}");

    public boolean isTplusCompatible() {
        return tplusCompatible;
    }

    private TilePosToUrlFunction buildUrlFunction() {
        return pos -> {
            TileSource source = pluginConfig.getActiveSource();
            String quadKey = com.mndk.tilemapper.util.TileQuadKey.toQuadKey(pos);
            String bingU = com.mndk.tilemapper.util.TileQuadKey.toBingU(pos.x, pos.y, pos.zoom);

            // 1. Resolve {random:v1,v2,v3} — pick one value at random
            String urlStr = pluginConfig.getTileUrl();
            Matcher m = RANDOM_PATTERN.matcher(urlStr);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String[] parts = m.group(1).split(",");
                String chosen = parts[ThreadLocalRandom.current().nextInt(parts.length)];
                m.appendReplacement(sb, Matcher.quoteReplacement(chosen));
            }
            m.appendTail(sb);
            urlStr = sb.toString();

            // 2. Invert zoom if enabled
            int urlZoom;
            if (source != null && source.invertZoom()) {
                urlZoom = source.zoom() - pos.zoom;
            } else {
                urlZoom = pos.zoom;
            }

            // 3. Standard variable substitution
            urlStr = urlStr
                    .replace("{z}", String.valueOf(urlZoom))
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
