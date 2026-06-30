package com.mndk.tilemapper.player;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.config.TileSource;
import com.mndk.tilemapper.tile.TilePosition;
import com.mndk.tilemapper.tile.projection.TileServerProjection;
import com.mndk.tilemapper.tile.projection.WebMercatorTileProjection;
import com.mndk.tilemapper.util.RGBColorDouble;
import com.mndk.tilemapper.util.TileQuadKey;
import de.btegermany.terraplusminus.data.TerraConnector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates a flat tile plane at the top Y of a WorldEdit selection,
 * using satellite imagery block colour matching.
 *
 * <p>This is a simplified utility for on-demand generation from a
 * player's WorldEdit selection. Unlike the automatic
 * {@link com.mndk.tilemapper.processor.ChunkProcessor} which runs per-chunk
 * during worldgen, this runs on-demand within a WorldEdit selection and
 * does not require T+- ground height data.</p>
 */
public class SelectionTileGenerator {

    private static final Logger LOGGER = Logger.getLogger("TSM");
    private static final Pattern RANDOM_PATTERN = Pattern.compile("\\{random:([^}]+)}");

    /** Maximum columns allowed per generation (1000 x 1000). */
    private static final int MAX_COLUMNS = 1_000_000;

    /**
     * Generate tiles in the player's WorldEdit selection.
     * Runs asynchronously — block changes are applied on the main thread.
     *
     * @param player       the player running the command
     * @param blocksetName blockset override (null = use server current)
     * @param sourceName   source name override (null = use server current)
     * @param optOffsetX   X offset override (null = use source default)
     * @param optOffsetZ   Z offset override (null = use source default)
     * @param config       server plugin config
     * @param plugin       plugin instance (for scheduler access)
     */
    @SuppressWarnings("deprecation")
    public static void generate(Player player, String blocksetName, String sourceName,
                                Double optOffsetX, Double optOffsetZ,
                                PluginConfig config, JavaPlugin plugin) {

        // --- Resolve parameters ---
        String effBlockset = blocksetName != null ? blocksetName : config.getActiveBlockset();

        TileSource source;
        if (sourceName != null) {
            source = config.getTileSources().get(sourceName);
            if (source == null) {
                player.sendMessage(ChatColor.RED + "Tile source not found: " + sourceName);
                return;
            }
        } else {
            source = config.getActiveSource();
        }
        if (source == null) {
            player.sendMessage(ChatColor.RED + "No active tile source!");
            return;
        }

        double effOffsetX = optOffsetX != null ? optOffsetX : source.offsetX();
        double effOffsetZ = optOffsetZ != null ? optOffsetZ : source.offsetZ();
        int zoom = source.zoom();
        TileServerProjection projection = new WebMercatorTileProjection(source.invertLat());
        String urlTemplate = source.url();

        // --- Get WorldEdit selection (pure reflection for FAWE classloader compat) ---
        org.bukkit.plugin.Plugin wePlugin = Bukkit.getPluginManager().getPlugin("WorldEdit");
        if (wePlugin == null) {
            wePlugin = Bukkit.getPluginManager().getPlugin("FastAsyncWorldEdit");
        }
        if (wePlugin == null) {
            player.sendMessage(ChatColor.RED + "WorldEdit/FAWE not found on this server!");
            return;
        }

        // Load all WE classes through FAWE's own classloader — TileMapper's classloader
        // cannot see them because FAWE bundles its own WorldEdit internally.
        ClassLoader weCL;
        try {
            weCL = wePlugin.getClass().getClassLoader();
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Failed to access WorldEdit classloader: " + e.getMessage());
            return;
        }

        // Variables that escape the try block below (need to be declared before for scoping)
        Object region;
        Object minPoint, maxPoint;
        int maxY;
        int minX, minZ, maxX_val, maxZ_val;
        Class<?> f_clsRegion, f_clsBV3;
        Object f_region;
        try {
            // Load all needed WE types via FAWE's classloader
            Class<?> clsWorldEdit       = weCL.loadClass("com.sk89q.worldedit.WorldEdit");
            Class<?> clsSessionManager  = weCL.loadClass("com.sk89q.worldedit.session.SessionManager");
            Class<?> clsBukkitAdapter   = weCL.loadClass("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Class<?> clsActor           = weCL.loadClass("com.sk89q.worldedit.extension.platform.Actor");
            Class<?> clsLocalSession    = weCL.loadClass("com.sk89q.worldedit.LocalSession");
            Class<?> clsWorld           = weCL.loadClass("com.sk89q.worldedit.world.World");
            Class<?> clsRegion          = weCL.loadClass("com.sk89q.worldedit.regions.Region");
            Class<?> clsBV3             = weCL.loadClass("com.sk89q.worldedit.math.BlockVector3");

            // WorldEdit.getInstance()
            Object weInstance = clsWorldEdit.getMethod("getInstance").invoke(null);

            // .getSessionManager()
            Object sessionManager = clsSessionManager.cast(
                    clsWorldEdit.getMethod("getSessionManager").invoke(weInstance));

            // BukkitAdapter.adapt(player) → WE Player (which is an Actor)
            Object wePlayer = clsBukkitAdapter.getMethod("adapt", Player.class).invoke(null, player);

            // sessionManager.get(Actor) → LocalSession
            // FAWE 2.x+ 的 SessionManager.get() 使用 SessionOwner 作為參數型別，
            // 而非 Actor (儘管 Actor extends SessionOwner)。
            // Java 反射 getMethod() 要求精確型別匹配，不支援繼承，所以必須找對的型別。
            Method getSessionMethod = findGetSessionMethod(clsSessionManager, clsActor, weCL);
            Object session = getSessionMethod.invoke(sessionManager, wePlayer);

            // session.getSelectionWorld()
            Method getSelWorld = clsLocalSession.getMethod("getSelectionWorld");
            Object selWorld = getSelWorld.invoke(session);
            if (selWorld == null) {
                player.sendMessage(ChatColor.RED + "Make a WorldEdit selection first!");
                return;
            }

            // session.getSelection(World) → Region (throws IncompleteRegionException if no selection)
            Method getSelection = clsLocalSession.getMethod("getSelection", clsWorld);
            try {
                region = getSelection.invoke(session, selWorld);
            } catch (java.lang.reflect.InvocationTargetException e) {
                // IncompleteRegionException = no full selection defined
                player.sendMessage(ChatColor.RED + "Make a WorldEdit selection first! (selection incomplete)");
                return;
            }

            // region.getMinimumPoint(), getMaximumPoint() → BlockVector3
            minPoint = clsRegion.getMethod("getMinimumPoint").invoke(region);
            maxPoint = clsRegion.getMethod("getMaximumPoint").invoke(region);

            // BlockVector3.y() — try y() first, fallback to getY()
            Method yMethod;
            try { yMethod = clsBV3.getMethod("y"); } catch (NoSuchMethodException e) {
                yMethod = clsBV3.getMethod("getY");
            }
            maxY = (int) yMethod.invoke(maxPoint);

            // BlockVector3.x() / z()
            Method xM, zM;
            try { xM = clsBV3.getMethod("x"); } catch (NoSuchMethodException e) { xM = clsBV3.getMethod("getX"); }
            try { zM = clsBV3.getMethod("z"); } catch (NoSuchMethodException e) { zM = clsBV3.getMethod("getZ"); }
            minX = (int) xM.invoke(minPoint);
            minZ = (int) zM.invoke(minPoint);
            maxX_val = (int) xM.invoke(maxPoint);
            maxZ_val = (int) zM.invoke(maxPoint);

            // Store references needed for later reflection calls (after the try block)
            // Store references needed for later calls (after the try block)
            f_clsRegion = clsRegion;
            f_clsBV3 = clsBV3;
            f_region = region;

        } catch (Exception e) {
            LOGGER.severe("Failed to get WorldEdit selection for " + player.getName() + ": " + e);
            player.sendMessage(ChatColor.RED + "Failed to get WorldEdit selection: " + e.getMessage());
            return;
        }

        // --- All WE data extracted; do blockset switch & column precompute ---
        long totalColumns = (long) (maxX_val - minX + 1) * (maxZ_val - minZ + 1);
        if (totalColumns <= 0) {
            player.sendMessage(ChatColor.RED + "Empty selection!");
            return;
        }
        if (totalColumns > MAX_COLUMNS) {
            player.sendMessage(ChatColor.RED + "Selection too large (" + totalColumns
                    + " columns, max " + MAX_COLUMNS + "). Reduce your selection.");
            return;
        }

        String origBlockset = null;
        File blocksetFolder = new File(plugin.getDataFolder(), "blockset");
        if (blocksetName != null && !blocksetName.equals(config.getActiveBlockset())) {
            origBlockset = config.getActiveBlockset();
            BlockColorRegistry.reset();
            BlockColorRegistry.init(blocksetFolder,
                    com.mndk.tilemapper.TileMapper.BLOCKSET_BASE_URL, blocksetName);
            player.sendMessage(ChatColor.YELLOW + "Using blockset: " + blocksetName);
        }

        player.sendMessage(ChatColor.GOLD + "Generating tiles... (area: ~" + totalColumns + " columns, Y=" + maxY + ")");

        // --- Pre-compute column list on main thread ---
        List<int[]> columns = new ArrayList<>();
        try {
            Method containsMethod = f_clsRegion.getMethod("contains", f_clsBV3);
            Method atMethod = f_clsBV3.getMethod("at", int.class, int.class, int.class);
            for (int wx = minX; wx <= maxX_val; wx++) {
                for (int wz = minZ; wz <= maxZ_val; wz++) {
                    Object vec = atMethod.invoke(null, wx, maxY, wz);
                    if ((boolean) containsMethod.invoke(f_region, vec)) {
                        columns.add(new int[]{wx, wz});
                    }
                }
            }
        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Failed to build column list: " + e.getMessage());
            return;
        }
        if (columns.isEmpty()) {
            player.sendMessage(ChatColor.RED + "No columns in selection!");
            return;
        }

        // --- Run tile processing asynchronously ---
        Player finalPlayer = player;
        String finalUrlTemplate = urlTemplate;
        int finalZoom = zoom;
        TileServerProjection finalProjection = projection;
        double finalOffsetX = effOffsetX;
        double finalOffsetZ = effOffsetZ;
        String finalOrigBlockset = origBlockset;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // Local tile cache (TilePosition → BufferedImage)
            Map<TilePosition, BufferedImage> tileCache = new HashMap<>();
            // Results: [worldX, worldZ, material][]
            List<Object[]> results = new ArrayList<>();
            int tileErrors = 0;
            int total = columns.size();
            int columnsProcessed = 0;
            int lastReportPct = 0;

            for (int[] col : columns) {
                int wx = col[0];
                int wz = col[1];

                // 1. Convert world coordinates to geographic (lon, lat)
                double[] geo;
                try {
                    geo = TerraConnector.toGeo(wx + finalOffsetX, wz + finalOffsetZ);
                } catch (Exception e) {
                    tileErrors++;
                    continue;
                }

                // 2. Determine which tile this falls in
                TilePosition tilePos = finalProjection.toTileCoordinates(geo[0], geo[1], finalZoom);

                // 3. Get tile image (cache hit or download)
                BufferedImage image = tileCache.get(tilePos);
                if (image == null) {
                    try {
                        image = downloadTile(finalUrlTemplate, tilePos, source);
                        tileCache.put(tilePos, image);
                    } catch (IOException e) {
                        tileErrors++;
                        continue;
                    }
                }

                // 4. Get pixel colour at the exact geographic coordinate
                double[] coords = finalProjection.toDoubleTileCoordinates(geo[0], geo[1], finalZoom);
                double fracX = coords[0] - tilePos.x;
                double fracY = coords[1] - tilePos.y;
                if (fracX < 0 || fracX > 1 || fracY < 0 || fracY > 1) {
                    tileErrors++;
                    continue;
                }
                int pixelX = Math.min((int) (fracX * image.getWidth()), image.getWidth() - 1);
                int pixelY = Math.min((int) (fracY * image.getHeight()), image.getHeight() - 1);
                RGBColorDouble color = new RGBColorDouble(image.getRGB(pixelX, pixelY));

                // 5. Match to nearest block
                Material material = BlockColorRegistry.getNearestBlock(color.toRGBColor());
                if (material == null) {
                    tileErrors++;
                    continue;
                }

                results.add(new Object[]{wx, wz, material});
                columnsProcessed++;

                // 6. Progress report every 5%
                int pct = (int) ((double) (columnsProcessed + tileErrors) / total * 100);
                if (pct >= lastReportPct + 5) {
                    lastReportPct = pct;
                    int cp = columnsProcessed;
                    int te = tileErrors;
                    Bukkit.getScheduler().runTask(plugin, () ->
                            finalPlayer.sendMessage(ChatColor.GRAY + "Progress: " + pct
                                    + "% (" + cp + " matched, " + te + " errors)"));
                }
            }

            // --- Switch back to main thread for block placement ---
            int finalTileErrors = tileErrors;
            List<Object[]> finalResults = results;
            Bukkit.getScheduler().runTask(plugin, () -> {
                int applied = 0;
                for (Object[] entry : finalResults) {
                    int bx = (int) entry[0];
                    int bz = (int) entry[1];
                    Material mat = (Material) entry[2];
                    org.bukkit.block.Block block = finalPlayer.getWorld().getBlockAt(bx, maxY, bz);
                    if (block.getType() != mat) {
                        block.setType(mat, false);
                        applied++;
                    }
                }

                // Restore original blockset if we temporarily switched
                if (finalOrigBlockset != null) {
                    BlockColorRegistry.reset();
                    BlockColorRegistry.init(blocksetFolder,
                            com.mndk.tilemapper.TileMapper.BLOCKSET_BASE_URL, finalOrigBlockset);
                    finalPlayer.sendMessage(ChatColor.YELLOW + "Restored blockset: " + finalOrigBlockset);
                }

                finalPlayer.sendMessage(ChatColor.GREEN + "Generation complete! "
                        + applied + " blocks placed"
                        + (finalTileErrors > 0 ? " (" + finalTileErrors + " tile/colour errors)" : "")
                        + ".");
            });
        });
    }

    // ---- Helper methods ----

    /**
     * Download a tile image from the resolved URL.
     */
    private static BufferedImage downloadTile(String urlTemplate, TilePosition pos, TileSource source)
            throws IOException {
        String urlStr = resolveUrl(urlTemplate, pos, source);
        try (InputStream is = new URL(urlStr).openStream()) {
            BufferedImage image = ImageIO.read(is);
            if (image == null) {
                throw new IOException("ImageIO.read returned null for " + urlStr);
            }
            return image;
        }
    }

    /**
     * Build the final tile URL from a template and tile position,
     * handling {z}/{x}/{y}/{quadkey}/{u}/{random:...} substitutions
     * and per-source invert_zoom.
     */
    static String resolveUrl(String urlTemplate, TilePosition pos, TileSource source) {
        String quadKey = TileQuadKey.toQuadKey(pos);
        String bingU = TileQuadKey.toBingU(pos.x, pos.y, pos.zoom);

        // 1. Resolve {random:v1,v2,...} placeholders
        Matcher m = RANDOM_PATTERN.matcher(urlTemplate);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String[] parts = m.group(1).split(",");
            String chosen = parts[ThreadLocalRandom.current().nextInt(parts.length)];
            m.appendReplacement(sb, Matcher.quoteReplacement(chosen));
        }
        m.appendTail(sb);
        urlTemplate = sb.toString();

        // 2. Invert zoom if enabled
        int urlZoom;
        if (source.invertZoom()) {
            urlZoom = source.zoom() - pos.zoom;
        } else {
            urlZoom = pos.zoom;
        }

        // 3. Standard variable substitution
        return urlTemplate
                .replace("{z}", String.valueOf(urlZoom))
                .replace("{x}", String.valueOf(pos.x))
                .replace("{y}", String.valueOf(pos.y))
                .replace("{quadkey}", quadKey)
                .replace("{u}", bingU);
    }

    // ---- Reflection helpers ----

    /**
     * Find the {@code SessionManager.get(…)} method with the correct parameter type.
     *
     * <p>FAWE 2.x+ declares {@code get(SessionOwner)} whereas standard WorldEdit may
     * declare {@code get(Actor)}.  Since {@code Actor extends SessionOwner}, source
     * callers can pass either, but Java reflection requires exact type match.
     * This method tries known types and falls back to a dynamic scan.</p>
     */
    private static Method findGetSessionMethod(
            Class<?> sessionManagerClass, Class<?> actorClass, ClassLoader weCL
    ) throws NoSuchMethodException {
        // 1. Try SessionOwner (FAWE 2.x)
        try {
            Class<?> clsSessionOwner = weCL.loadClass("com.sk89q.worldedit.session.SessionOwner");
            return sessionManagerClass.getMethod("get", clsSessionOwner);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {}

        // 2. Try Actor (standard WorldEdit)
        try {
            return sessionManagerClass.getMethod("get", actorClass);
        } catch (NoSuchMethodException ignored) {}

        // 3. Ultimate fallback — any single-parameter "get" method that accepts the player
        for (Method m : sessionManagerClass.getMethods()) {
            if ("get".equals(m.getName()) && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new NoSuchMethodException("Cannot find SessionManager.get() method");
    }
}
