package com.mndk.tilemapper.util;

import com.mndk.tilemapper.tile.TilePosition;

public class TileQuadKey {

    public static String toQuadKey(TilePosition tilePos) {
        StringBuilder quadKey = new StringBuilder();
        for (int i = tilePos.zoom; i > 0; i--) {
            char digit = '0';
            int mask = 1 << (i - 1);
            if ((tilePos.x & mask) != 0) digit++;
            if ((tilePos.y & mask) != 0) digit += 2;
            quadKey.append(digit);
        }
        return quadKey.toString();
    }

    /**
     * Convert tile coordinates to Bing Maps dynamic tile {@code {u}} format.
     *
     * <p>{@code {u}} is a modified quadkey: the first 2 base-4 digits are encoded
     * as a single letter 'a'&#8239;–&#8239;'p', followed by the remaining quadkey digits.
     * This is required by Bing's dynamic tile REST service
     * ({@code /comp/ch/{u}}).</p>
     *
     * @param tileX  tile X coordinate
     * @param tileY  tile Y coordinate
     * @param zoom   zoom level
     * @return the {@code {u}} value for use in Bing tile URLs
     */
    public static String toBingU(int tileX, int tileY, int zoom) {
        // Build quadkey
        StringBuilder quadKey = new StringBuilder();
        for (int i = zoom; i > 0; i--) {
            char digit = '0';
            int mask = 1 << (i - 1);
            if ((tileX & mask) != 0) digit++;
            if ((tileY & mask) != 0) digit += 2;
            quadKey.append(digit);
        }
        String qk = quadKey.toString();

        // Edge cases: zoom 0 or 1 → prepend 'a'
        if (qk.length() < 2) {
            if (qk.isEmpty()) return "a";
            return "a" + qk;
        }
        // First 2 base-4 digits → letter a-p (0-15)
        int idx = Integer.parseInt(qk.substring(0, 2), 4);
        return (char) ('a' + idx) + qk.substring(2);
    }
}
