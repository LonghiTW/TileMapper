package com.mndk.tilemapper.util;

/**
 * Simplified 2D bounds value object, replacing Terra++'s {@code Bounds2d}.
 * Only contains the four doubles that TileMapper actually uses.
 */
public record Bounds2d(double minX, double maxX, double minZ, double maxZ) {

    public static Bounds2d of(double x0, double x1, double z0, double z1) {
        return new Bounds2d(
                Math.min(x0, x1), Math.max(x0, x1),
                Math.min(z0, z1), Math.max(z0, z1)
        );
    }
}
