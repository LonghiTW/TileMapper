package com.mndk.tilemapper.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class PluginConfig {

    private static final Map<String, TileSource> DEFAULT_SOURCES = new LinkedHashMap<>();
    static {
        DEFAULT_SOURCES.put("bing", new TileSource(
                "https://ecn.t0.tiles.virtualearth.net/tiles/a{quadkey}.jpeg?g=1", 20, 0, 0));
        DEFAULT_SOURCES.put("osm", new TileSource(
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 14, 0, 0));
    }

    private final JavaPlugin plugin;

    private boolean enabled;
    private Map<String, TileSource> tileSources;
    private String activeSourceName;
    private int maxConcurrentRequests;
    private int cacheSize;
    private boolean surfaceBlockMask;

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        enabled = config.getBoolean("enabled", true);
        maxConcurrentRequests = config.getInt("max_concurrent_requests", 2);
        cacheSize = config.getInt("cache_size", 1000);
        surfaceBlockMask = config.getBoolean("surface_block_mask", true);

        // Load tile sources from config
        tileSources = new LinkedHashMap<>();
        ConfigurationSection sourcesSection = config.getConfigurationSection("tile_sources");
        if (sourcesSection != null) {
            for (String key : sourcesSection.getKeys(false)) {
                String url = sourcesSection.getString(key + ".url");
                int zoom = sourcesSection.getInt(key + ".zoom", 14);
                int ox = sourcesSection.getInt(key + ".offset.x", 0);
                int oz = sourcesSection.getInt(key + ".offset.z", 0);
                if (url != null && !url.isEmpty()) {
                    tileSources.put(key, new TileSource(url, zoom, ox, oz));
                }
            }
        }
        // Fallback to built-in defaults if none defined in config
        if (tileSources.isEmpty()) {
            tileSources.putAll(DEFAULT_SOURCES);
        }

        activeSourceName = config.getString("active_source", "bing");
        // Validate active source exists
        if (!tileSources.containsKey(activeSourceName)) {
            activeSourceName = tileSources.keySet().iterator().next();
        }
    }

    /**
     * Switch active tile source and persist to config file.
     * @return true if the source exists and was switched
     */
    public boolean setActiveSource(String name) {
        if (!tileSources.containsKey(name)) return false;
        activeSourceName = name;
        plugin.getConfig().set("active_source", name);
        plugin.saveConfig();
        return true;
    }

    // ---- Delegating getters (active source) ----

    public String getTileUrl() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.url() : DEFAULT_SOURCES.get("bing").url();
    }

    public int getZoom() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.zoom() : DEFAULT_SOURCES.get("bing").zoom();
    }

    public int getTileOffsetX() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.offsetX() : 0;
    }

    public int getTileOffsetZ() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.offsetZ() : 0;
    }

    // ---- Direct getters ----

    public boolean isEnabled() { return enabled; }
    public Map<String, TileSource> getTileSources() { return tileSources; }
    public String getActiveSourceName() { return activeSourceName; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public int getCacheSize() { return cacheSize; }
    public boolean isSurfaceBlockMask() { return surfaceBlockMask; }
    public JavaPlugin getPlugin() { return plugin; }
}
