package com.mndk.tilemapper.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

public class PluginConfig {

    private static final Map<String, TileSource> DEFAULT_SOURCES = new LinkedHashMap<>();
    static {
        DEFAULT_SOURCES.put("osm", new TileSource(
                "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 16, 0, 0));
        DEFAULT_SOURCES.put("bing", new TileSource(
                "https://t.ssl.ak.dynamic.tiles.virtualearth.net/comp/ch/{u}?it=A&shading=hill", 20, 0, 0));
        DEFAULT_SOURCES.put("yandex", new TileSource(
                "https://core-sat.maps.yandex.net/tiles?l=sat&x={x}&y={y}&z={z}", 20, 0, 0));
    }

    private final JavaPlugin plugin;

    private boolean enabled;
    private Map<String, TileSource> tileSources;
    private String activeSourceName;
    private int maxConcurrentRequests;
    private int cacheSize;
    private boolean surfaceBlockMask;
    private String activeBlockset;

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
        surfaceBlockMask = config.getBoolean("surface_block_mask", false);

        // Load tile sources from config
        tileSources = new LinkedHashMap<>();
        ConfigurationSection sourcesSection = config.getConfigurationSection("tile_sources");
        if (sourcesSection != null) {
            for (String key : sourcesSection.getKeys(false)) {
                String url = sourcesSection.getString(key + ".url");
                int zoom = sourcesSection.getInt(key + ".zoom", 14);
                double ox = sourcesSection.getDouble(key + ".offset.x", 0);
                double oz = sourcesSection.getDouble(key + ".offset.z", 0);
                boolean invertZoom = sourcesSection.getBoolean(key + ".invert_zoom", false);
                boolean invertLat = sourcesSection.getBoolean(key + ".invert_lat", false);
                boolean flipVertically = sourcesSection.getBoolean(key + ".flip_vert", false);
                if (url != null && !url.isEmpty()) {
                    tileSources.put(key, new TileSource(url, zoom, ox, oz, invertZoom, invertLat, flipVertically));
                }
            }
        }
        // Fallback to built-in defaults if none defined in config
        if (tileSources.isEmpty()) {
            tileSources.putAll(DEFAULT_SOURCES);
        }

        activeSourceName = config.getString("active_source", "osm");
        // Validate active source exists
        if (!tileSources.containsKey(activeSourceName)) {
            activeSourceName = tileSources.keySet().iterator().next();
        }

        activeBlockset = config.getString("active_blockset", "Default");
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

    /**
     * Switch active blockset and persist to config file.
     */
    public void setActiveBlockset(String name) {
        activeBlockset = name;
        plugin.getConfig().set("active_blockset", name);
        plugin.saveConfig();
    }

    // ---- Delegating getters (active source) ----

    public String getTileUrl() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.url() : DEFAULT_SOURCES.get("osm").url();
    }

    public int getZoom() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.zoom() : DEFAULT_SOURCES.get("osm").zoom();
    }

    public double getTileOffsetX() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.offsetX() : 0;
    }

    public double getTileOffsetZ() {
        TileSource src = tileSources.get(activeSourceName);
        return src != null ? src.offsetZ() : 0;
    }

    // ---- Direct getters ----

    public boolean isEnabled() { return enabled; }
    public Map<String, TileSource> getTileSources() { return tileSources; }
    public String getActiveSourceName() { return activeSourceName; }

    /** Returns the currently active {@link TileSource}, or null if somehow missing. */
    public TileSource getActiveSource() {
        return tileSources.get(activeSourceName);
    }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public int getCacheSize() { return cacheSize; }
    public boolean isSurfaceBlockMask() { return surfaceBlockMask; }
    public String getActiveBlockset() { return activeBlockset; }
    public JavaPlugin getPlugin() { return plugin; }
}
