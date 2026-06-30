package com.mndk.tilemapper.config;

public record TileSource(String url, int zoom, double offsetX, double offsetZ,
                         boolean invertZoom, boolean invertLat) {

    /** Backward-compatible constructor — defaults invertZoom & invertLat to false. */
    public TileSource(String url, int zoom, double offsetX, double offsetZ) {
        this(url, zoom, offsetX, offsetZ, false, false);
    }
}
