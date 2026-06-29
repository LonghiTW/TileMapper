package com.mndk.tilemapper.block;

import com.mndk.tilemapper.util.OklabColor;
import com.mndk.tilemapper.util.RGBColorDouble;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/**
 * Loads block colour data from a single blockset JSON file and provides
 * nearest-colour block lookup using Oklab Euclidean distance.
 *
 * <h3>Blockset file format</h3>
 * A flat JSON array of objects, each with at least {@code "id"} and either or both:
 * <ul>
 *   <li>{@code "rgb": [R, G, B]} — sRGB 8-bit integers (0-255)</li>
 *   <li>{@code "lab": [L, a, b]} — pre-computed Oklab values (strings or numbers)</li>
 * </ul>
 *
 * <h3>Lab vs RGB priority</h3>
 * <p>On first load, the plugin performs a sanity check on the first entry that
 * has <em>both</em> {@code lab} and {@code rgb}: it computes Oklab from {@code rgb}
 * and compares with the stored {@code lab}. If they match (Euclidean distance &lt; 0.01),
 * the stored {@code lab} values are trusted and used directly. Otherwise,
 * <em>all</em> entries fall back to computing Oklab from {@code rgb} at runtime.</p>
 */
public class BlockColorRegistry {

    private static final Logger LOGGER = Logger.getLogger("TSM");

    /**
     * Canonical blockset files provided by the repository.
     * These are downloaded from blockset_source_url on first launch and
     * should never be modified by the user — they serve as read-only references.
     * The user's editable copy is custom_blockset.json (a copy of All.json).
     */
    private static final String[] BUNDLED_BLOCKSETS = {
            "All", "Default", "Grayscale"
    };

    /** Threshold for the lab-vs-rgb sanity check (Euclidean distance in Oklab). */
    private static final double LAB_SANITY_THRESHOLD = 0.01;

    /** Mapping from OklabColor ??Material */
    private static final List<Map.Entry<OklabColor, Material>> colorMappings = new ArrayList<>();

    private static boolean initialized = false;
    private static String currentBlocksetName = null;

    /**
     * When true, colour matching compares only the L (lightness) component.
     * Activated automatically when the active blockset is {@code "Grayscale"}.
     */
    private static boolean luminanceOnly = false;

    /**
     * Load block colour data from a blockset file or URL.
     *
     * <ul>
     *   <li>{@code "custom_blockset"} — loaded from {@code blocksetFolder/custom_blockset.json}
     *        (the user's local editable copy).</li>
     *   <li>Any other name — loaded live from {@code baseUrl + name + ".json"}
     *        (the repository's canonical read-only files).</li>
     * </ul>
     *
     * @param blocksetFolder    the local {@code blockset/} directory
     * @param baseUrl           repository base URL ending with {@code /}
     * @param activeBlockset    blockset name (without {@code .json})
     */
    public static void init(File blocksetFolder, String baseUrl, String activeBlockset) {
        if (initialized) return;
        long start = System.currentTimeMillis();

        if (!blocksetFolder.exists()) {
            blocksetFolder.mkdirs();
        }

        // Detect luminance-only mode (Grayscale blockset)
        if ("Grayscale".equalsIgnoreCase(activeBlockset)) {
            luminanceOnly = true;
            LOGGER.info("Grayscale blockset detected — using luminance-only (L) matching");
        }

        int loaded;
        if ("custom_blockset".equals(activeBlockset)) {
            // Blockset is read from the local file that the user can edit
            File targetFile = new File(blocksetFolder, "custom_blockset.json");
            if (!targetFile.exists()) {
                LOGGER.warning("custom_blockset.json not found in "
                        + blocksetFolder.getAbsolutePath() + " — no block colours loaded");
                initialized = true;
                currentBlocksetName = activeBlockset;
                return;
            }
            loaded = loadFile(targetFile);
        } else {
            // All other blocksets are read live from the repository
            String url = baseUrl + activeBlockset + ".json";
            loaded = loadFromUrl(url);
            if (loaded == 0) {
                LOGGER.warning("Failed to load blockset '" + activeBlockset + "' from " + url
                        + " — no block colours loaded");
                initialized = true;
                currentBlocksetName = activeBlockset;
                return;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Loaded " + loaded + " block colours from '" + activeBlockset
                + "' (" + (colorMappings.size() - loaded) + " skipped) in " + elapsed + "ms");

        currentBlocksetName = activeBlockset;
        initialized = true;
    }

    // ========== File loading ==========

    /** Load entries from a JSON file on disk. */
    private static int loadFile(File file) {
        Gson gson = new Gson();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            JsonElement rootEl = gson.fromJson(reader, JsonElement.class);
            return parseJsonArray(rootEl, file.getName());
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warning("Failed to read " + file.getName() + ": " + e.getMessage());
            return 0;
        }
    }

    /** Load entries from a URL (live from the repository). */
    private static int loadFromUrl(String urlString) {
        Gson gson = new Gson();
        try {
            java.net.URL url = new java.net.URL(urlString);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "TileMapper/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                JsonElement rootEl = gson.fromJson(reader, JsonElement.class);
                return parseJsonArray(rootEl, urlString);
            }
        } catch (IOException | JsonSyntaxException e) {
            LOGGER.warning("Failed to load blockset from " + urlString + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Shared JSON array parsing logic used by both {@link #loadFile(File)}
     * and {@link #loadFromUrl(String)}.
     */
    private static int parseJsonArray(JsonElement rootEl, String sourceName) {
        if (rootEl == null || !rootEl.isJsonArray()) {
            LOGGER.warning("Blockset '" + sourceName + "' is empty or not a JSON array");
            return 0;
        }

        JsonArray arr = rootEl.getAsJsonArray();

        // ---- Pre-scan: sanity check lab vs rgb ----
        boolean trustLab = true;
        boolean hasLabCheck = false;

        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            if (!el.isJsonObject()) continue;
            JsonObject entry = el.getAsJsonObject();
            if (entry.has("lab") && entry.has("rgb")) {
                OklabColor fromLab = parseLab(entry);
                int[] rgbArr = parseRgb(entry);
                if (fromLab != null && rgbArr != null) {
                    int rgbInt = (rgbArr[0] << 16) | (rgbArr[1] << 8) | rgbArr[2];
                    OklabColor fromRgb = new OklabColor(new RGBColorDouble(rgbInt));
                    double diff = fromLab.distanceSq(fromRgb);
                    if (diff >= LAB_SANITY_THRESHOLD) {
                        trustLab = false;
                        LOGGER.fine("  " + sourceName + ": lab vs rgb mismatch (d\u00b2="
                                + String.format("%.4f", diff) + "), will compute from rgb");
                    } else {
                        LOGGER.fine("  " + sourceName + ": lab sanity check passed (d\u00b2="
                                + String.format("%.4f", diff) + "), using stored lab");
                    }
                    hasLabCheck = true;
                    break;
                }
            }
        }

        if (!hasLabCheck) {
            LOGGER.fine("  " + sourceName + ": no entry with both lab+rgb found");
        }

        // ---- Load entries ----
        int loaded = 0;
        int skipped = 0;

        for (int i = 0; i < arr.size(); i++) {
            JsonElement el = arr.get(i);
            // Skip non-object entries (e.g. comment strings)
            if (!el.isJsonObject()) {
                skipped++;
                continue;
            }
            JsonObject entry = el.getAsJsonObject();

            // Require at least "id"
            if (!entry.has("id")) {
                skipped++;
                continue;
            }
            String id = entry.get("id").getAsString();

            Material material = resolveMaterial(id);
            if (material == null) {
                skipped++;
                continue;
            }

            // Determine Oklab value: prefer lab (if trusted) else compute from rgb
            OklabColor oklab = null;

            if (trustLab) {
                oklab = parseLab(entry);
            }

            if (oklab == null) {
                // Fallback: compute from rgb
                int[] rgbArr = parseRgb(entry);
                if (rgbArr != null) {
                    int rgbInt = (rgbArr[0] << 16) | (rgbArr[1] << 8) | rgbArr[2];
                    oklab = new OklabColor(new RGBColorDouble(rgbInt));
                }
            }

            if (oklab == null) {
                skipped++;
                continue;
            }

            colorMappings.add(new AbstractMap.SimpleEntry<>(oklab, material));
            loaded++;
        }

        if (skipped > 0) {
            LOGGER.fine("  " + sourceName + ": loaded " + loaded + ", skipped " + skipped);
        }
        return loaded;
    }

    /** Parse the "lab" array from a JSON entry. Returns null if missing or invalid. */
    private static OklabColor parseLab(JsonObject entry) {
        if (!entry.has("lab")) return null;
        JsonElement labEl = entry.get("lab");
        if (!labEl.isJsonArray()) return null;
        JsonArray labArr = labEl.getAsJsonArray();
        if (labArr.size() < 3) return null;
        try {
            double l = parseJsonNumber(labArr.get(0));
            double a = parseJsonNumber(labArr.get(1));
            double b = parseJsonNumber(labArr.get(2));
            return new OklabColor(l, a, b);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Parse a JsonElement as double (handles both strings and numbers). */
    private static double parseJsonNumber(JsonElement el) {
        if (el.isJsonPrimitive()) {
            JsonPrimitive prim = el.getAsJsonPrimitive();
            if (prim.isNumber()) return prim.getAsDouble();
            if (prim.isString()) return Double.parseDouble(prim.getAsString());
        }
        throw new NumberFormatException("Not a number: " + el);
    }

    /** Parse the "rgb" array from a JSON entry. Returns null if missing or invalid. */
    private static int[] parseRgb(JsonObject entry) {
        if (!entry.has("rgb")) return null;
        JsonElement rgbEl = entry.get("rgb");
        if (!rgbEl.isJsonArray()) return null;
        JsonArray rgbArr = rgbEl.getAsJsonArray();
        if (rgbArr.size() < 3) return null;
        try {
            return new int[]{
                    rgbArr.get(0).getAsInt(),
                    rgbArr.get(1).getAsInt(),
                    rgbArr.get(2).getAsInt()
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ========== Online download ==========

    /**
     * Download {@code All.json} from the repository and save it as
     * {@code custom_blockset.json} — the user's editable working copy.
     *
     * @param baseUrl  repository base URL ending with {@code /}
     * @param targetFile  destination file (typically {@code custom_blockset.json})
     * @return true if the download succeeded
     */
    public static boolean downloadDefaultBlockset(String baseUrl, File targetFile) {
        String url = baseUrl + "All.json";
        return downloadFile(url, targetFile);
    }

    /** Low-level single file download. */
    private static boolean downloadFile(String url, File targetFile) {
        try {
            if (targetFile.getParentFile() != null) {
                targetFile.getParentFile().mkdirs();
            }
            java.net.URL downloadUrl = new java.net.URL(url);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) downloadUrl.openConnection();
            conn.setRequestProperty("User-Agent", "TileMapper/1.0");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            try (InputStream is = conn.getInputStream()) {
                Files.copy(is, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            LOGGER.warning("Failed to download " + url + ": " + e.getMessage());
            return false;
        }
    }

    /** Returns the names of all bundled (online) blocksets. */
    public static String[] getBundledBlocksets() {
        return BUNDLED_BLOCKSETS;
    }

    // ========== Material resolution ==========

    private static Material resolveMaterial(String id) {
        NamespacedKey key = NamespacedKey.minecraft(id);
        Material material = Registry.MATERIAL.get(key);
        if (material != null && material.isBlock()) {
            return material;
        }
        return null;
    }

    // ========== Colour matching ==========

    /**
     * Find the Material whose colour best matches the given RGB value.
     *
     * <p>Normally uses full Oklab Euclidean distance for perceptually uniform
     * comparison. When the active blockset is {@code "Grayscale"}, only the
     * L (lightness) component is compared — this turns the matcher into a
     * pure luminance quantizer, independent of the input hue.</p>
     *
     * @param rgb RGB colour as int (0xRRGGBB)
     * @return the closest matching Material, or null if no mappings loaded
     */
    public static Material getNearestBlock(int rgb) {
        if (colorMappings.isEmpty()) return null;

        OklabColor targetColor = new OklabColor(new RGBColorDouble(rgb));

        double minDistance = Double.POSITIVE_INFINITY;
        Material result = null;

        if (luminanceOnly) {
            // Pure L-only matching — ignores a,b differences
            for (Map.Entry<OklabColor, Material> entry : colorMappings) {
                double dl = targetColor.l - entry.getKey().l;
                double distance = dl * dl;
                if (distance < minDistance) {
                    minDistance = distance;
                    result = entry.getValue();
                }
            }
        } else {
            // Full Oklab Euclidean distance
            for (Map.Entry<OklabColor, Material> entry : colorMappings) {
                double distance = targetColor.distanceSq(entry.getKey());
                if (distance < minDistance) {
                    minDistance = distance;
                    result = entry.getValue();
                }
            }
        }

        return result;
    }

    /** Returns the name of the currently loaded blockset. */
    public static String getCurrentBlockset() {
        return currentBlocksetName;
    }

    /** Returns the number of loaded colour mappings. */
    public static int getMappingCount() {
        return colorMappings.size();
    }

    /** Reset for testing or reload. */
    public static void reset() {
        colorMappings.clear();
        initialized = false;
        currentBlocksetName = null;
        luminanceOnly = false;
    }
}
