package com.mndk.tilemapper.config;

public record TileSource(String url, int zoom, double offsetX, double offsetZ,
                         boolean invertZoom, boolean invertLat, boolean flipVertically) {

    /** Backward-compatible constructor — defaults all flags to false. */
    public TileSource(String url, int zoom, double offsetX, double offsetZ) {
        this(url, zoom, offsetX, offsetZ, false, false, false);
    }

    /** Transitional constructor — defaults flipVertically to false. */
    public TileSource(String url, int zoom, double offsetX, double offsetZ,
                      boolean invertZoom, boolean invertLat) {
        this(url, zoom, offsetX, offsetZ, invertZoom, invertLat, false);
    }
}
