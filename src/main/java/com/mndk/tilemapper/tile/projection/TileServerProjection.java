package com.mndk.tilemapper.tile.projection;

import com.mndk.tilemapper.tile.TilePosition;
import com.mndk.tilemapper.util.Bounds2d;

public interface TileServerProjection {

    double[] toGeoCoordinates(TilePosition pos);

    TilePosition toTileCoordinates(double lon, double lat, int zoom);
    double[] toDoubleTileCoordinates(double lon, double lat, int zoom);

    TilePosition[] getAllIntersecting(Bounds2d bounds2d, int zoom);

}
