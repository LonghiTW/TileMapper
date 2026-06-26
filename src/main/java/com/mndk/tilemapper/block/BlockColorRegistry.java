package com.mndk.tilemapper.block;

import com.mndk.tilemapper.util.CIELabColor;
import com.mndk.tilemapper.util.RGBColorDouble;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Loads block color data from {@code block_data.json} and provides
 * nearest-color block lookup using CIE2000 delta-E.
 */
public class BlockColorRegistry {

    private static final Logger LOGGER = Logger.getLogger("TSM");

    /** Mapping from CIELabColor ??Material */
    private static final List<Map.Entry<CIELabColor, Material>> colorMappings = new ArrayList<>();

    // Note: block_data.json is sourced from modern Minecraft assets (v26.1.2),
    // so its "id" values are already modern texture filenames. No ID_RENAMES needed.

    private static boolean initialized = false;

    /**
     * Load block color data from the bundled block_data.json resource.
     * Call once during plugin onEnable().
     */
    public static void init(Class<?> resourceClass) {
        if (initialized) return;

        long start = System.currentTimeMillis();

        try (InputStream is = resourceClass.getClassLoader().getResourceAsStream("block_data.json")) {
            if (is == null) {
                LOGGER.severe("block_data.json not found in resources! Block color mapping disabled.");
                return;
            }

            Gson gson = new Gson();
            JsonObject root = gson.fromJson(new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8)), JsonObject.class);
            JsonArray blocks = root.getAsJsonArray("blocks");

            int loaded = 0;
            int skipped = 0;

            for (int i = 0; i < blocks.size(); i++) {
                JsonObject entry = blocks.get(i).getAsJsonObject();
                String id = entry.get("id").getAsString();

                // Resolve Material from block ID
                Material material = resolveMaterial(id);
                if (material == null) {
                    skipped++;
                    continue;
                }

                // Read pre-computed CIELAB values from JSON
                JsonArray lab = entry.getAsJsonArray("lab");
                double l = lab.get(0).getAsDouble();   // L* (0-100)
                double a = lab.get(1).getAsDouble();   // a* (~-128 to ~127)
                double b = lab.get(2).getAsDouble();   // b* (~-128 to ~127)

                colorMappings.add(new AbstractMap.SimpleEntry<>(
                        new CIELabColor(l, a, b), material
                ));
                loaded++;
            }

            long elapsed = System.currentTimeMillis() - start;
            LOGGER.info("Loaded " + loaded + " block color mappings (" + skipped + " skipped) in " + elapsed + "ms");

        } catch (IOException e) {
            LOGGER.severe("Failed to load block_data.json: " + e.getMessage());
        }

        initialized = true;
    }

    /**
     * Resolve a block ID string to a Bukkit Material.
     * The block_data.json uses modern Minecraft texture filenames as IDs,
     * which in most cases match the registry name directly.
     * Some entries (face textures, state variants) won't resolve
     * since they aren't standalone Materials — that's expected.
     */
    private static Material resolveMaterial(String id) {
        NamespacedKey key = NamespacedKey.minecraft(id);
        Material material = Registry.MATERIAL.get(key);
        if (material != null && material.isBlock()) {
            return material;
        }

        LOGGER.fine("Skipped block ID (not a standalone Material): " + id);
        return null;
    }

    /**
     * Find the Material whose pre-computed color best matches the given RGB value.
     *
     * @param rgb RGB color as int (0xRRGGBB)
     * @return the closest matching Material, or null if no mappings loaded
     */
    public static Material getNearestBlock(int rgb) {
        if (colorMappings.isEmpty()) return null;

        CIELabColor targetColor = new CIELabColor(new RGBColorDouble(rgb));

        double minDistance = Double.POSITIVE_INFINITY;
        Material result = null;

        for (Map.Entry<CIELabColor, Material> entry : colorMappings) {
            double distance = targetColor.getCIE2000DiffSq(entry.getKey());
            if (distance < minDistance) {
                minDistance = distance;
                result = entry.getValue();
            }
        }

        return result;
    }

    /** Returns the number of loaded color mappings */
    public static int getMappingCount() {
        return colorMappings.size();
    }

    /** Reset for testing or reload */
    public static void reset() {
        colorMappings.clear();
        initialized = false;
    }
}
