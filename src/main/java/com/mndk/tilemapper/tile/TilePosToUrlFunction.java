package com.mndk.tilemapper.tile;

import java.net.MalformedURLException;
import java.net.URL;

@FunctionalInterface
public interface TilePosToUrlFunction {
    URL get(TilePosition pos) throws MalformedURLException;
}
