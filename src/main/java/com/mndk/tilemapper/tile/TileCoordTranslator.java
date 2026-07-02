package com.mndk.tilemapper.tile;

import com.mndk.tilemapper.tile.projection.TileServerProjection;
import com.mndk.tilemapper.util.Bounds2d;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Coordinate translation layer that wraps a {@link TileServerProjection}
 * and applies latitude inversion and vertical flip transformations.
 * <p>
 * Inspired by BTETerraRenderer's {@code FlatTileCoordTranslator}.
 * The wrapped projection remains pure — all inversion logic lives here.
 */
@Getter
public class TileCoordTranslator {

    private static final MatrixRow[] CORNER_MATRIX = {
            new MatrixRow(0, 1, 0, 1), // top left
            new MatrixRow(1, 1, 1, 1), // top right
            new MatrixRow(1, 0, 1, 0), // bottom right
            new MatrixRow(0, 0, 0, 0)  // bottom left
    };
    private static final MatrixRow[] LAT_INVERTED_CORNER_MATRIX = {
            new MatrixRow(0, 1, 0, 0), // top left
            new MatrixRow(1, 1, 1, 0), // top right
            new MatrixRow(1, 0, 1, 1), // bottom right
            new MatrixRow(0, 0, 0, 1)  // bottom left
    };

    private final TileServerProjection projection;
    private final boolean invertLatitude;
    private final boolean flipVertically;

    public TileCoordTranslator(TileServerProjection projection) {
        this(projection, false, false);
    }

    public TileCoordTranslator(TileServerProjection projection,
                               boolean invertLatitude, boolean flipVertically) {
        this.projection = projection;
        this.invertLatitude = invertLatitude;
        this.flipVertically = flipVertically;
    }

    /**
     * Converts tile coordinates to geographic coordinates (WGS84).
     * If latitude inversion is enabled, the latitude is negated after projection.
     */
    public double[] toGeoCoordinates(TilePosition pos) {
        double[] coord = projection.toGeoCoordinates(pos);
        if (invertLatitude) coord[1] = -coord[1];
        return coord;
    }

    /**
     * Converts geographic coordinates to tile coordinates.
     * If latitude inversion is enabled, the input latitude is negated before projection.
     */
    public TilePosition toTileCoordinates(double lon, double lat, int zoom) {
        return projection.toTileCoordinates(lon, invertLatitude ? -lat : lat, zoom);
    }

    /**
     * Converts geographic coordinates to fractional (double-precision) tile coordinates.
     * If latitude inversion is enabled, the input latitude is negated before projection.
     */
    public double[] toDoubleTileCoordinates(double lon, double lat, int zoom) {
        return projection.toDoubleTileCoordinates(lon, invertLatitude ? -lat : lat, zoom);
    }

    /**
     * Returns all tile positions intersecting the given geographic bounds.
     * Uses the translator's own {@link #toTileCoordinates} to respect inversion settings.
     */
    public TilePosition[] getAllIntersecting(Bounds2d bounds2d, int zoom) {
        TilePosition t1 = this.toTileCoordinates(bounds2d.minX(), bounds2d.minZ(), zoom);
        TilePosition t2 = this.toTileCoordinates(bounds2d.maxX(), bounds2d.maxZ(), zoom);
        int minX = Math.min(t1.x, t2.x), maxX = Math.max(t1.x, t2.x);
        int minY = Math.min(t1.y, t2.y), maxY = Math.max(t1.y, t2.y);
        int width = maxX - minX + 1, height = maxY - minY + 1;
        TilePosition[] result = new TilePosition[width * height];
        for (int y = minY, dy = 0; y <= maxY; ++y, ++dy) {
            for (int x = minX, dx = 0; x <= maxX; ++x, ++dx) {
                result[dy * width + dx] = new TilePosition(x, y, zoom);
            }
        }
        return result;
    }

    /**
     * Returns the corner matrix row for the given corner index.
     * The matrix selection depends on {@code invertLatitude ^ flipVertically},
     * matching the XOR behaviour of BTETerraRenderer's FlatTileCoordTranslator.
     */
    public MatrixRow getCornerMatrixRow(int i) {
        return invertLatitude ^ flipVertically ? LAT_INVERTED_CORNER_MATRIX[i] : CORNER_MATRIX[i];
    }

    @Getter
    @RequiredArgsConstructor
    public static class MatrixRow {
        private final int x, y, u, v;
    }
}
