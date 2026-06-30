package com.mndk.tilemapper;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.config.TileSource;
import com.mndk.tilemapper.player.SelectionTileGenerator;
import com.mndk.tilemapper.processor.ChunkProcessor;
import com.mndk.tilemapper.tile.server.TileServer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class SatelliteCommand extends Command {

    private final PluginConfig config;
    private final ChunkProcessor processor;
    private final TileServer tileServer;

    public SatelliteCommand(PluginConfig config, ChunkProcessor processor, TileServer tileServer) {
        super("tsm",
                "TileMapper admin command",
                "/tsm <reload|status|sources|source|blocksets|blockset|generate>",
                List.of());
        this.config = config;
        this.processor = processor;
        this.tileServer = tileServer;
    }

    public void register(JavaPlugin plugin) {
        plugin.getServer().getCommandMap().register(plugin.getName(), this);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <reload|status|sources|source|blocksets|blockset>");
            return false;
        }

        switch (args[0].toLowerCase()) {
            case "reload":
                return handleReload(sender);
            case "status":
                return handleStatus(sender);
            case "sources":
                return handleListSources(sender);
            case "source":
                return handleSwitchSource(sender, args);
            case "blocksets":
                return handleListBlocksets(sender);
            case "blockset":
                return handleSwitchBlockset(sender, args);
            case "generate":
                return handleGenerate(sender, args);
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + args[0]);
                sender.sendMessage(ChatColor.RED + "Available: reload, status, sources, source <name>, blocksets, blockset <name>, generate [blockset] [source] [xoffset zoffset]");
                return false;
        }
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("tilemapper.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run this command");
            return true;
        }

        TileMapper plugin = TileMapper.instance;
        if (plugin != null) {
            plugin.reloadPlugin();
            sender.sendMessage(ChatColor.GREEN + "TileMapper configuration reloaded.");
        }
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        TileMapper plugin = TileMapper.instance;
        sender.sendMessage(ChatColor.GOLD + "=== TileMapper Status ===");
        sender.sendMessage(ChatColor.YELLOW + "Version: " + plugin.getPluginMeta().getVersion());
        sender.sendMessage(ChatColor.YELLOW + "Enabled: " + (config.isEnabled() ? "yes" : "no"));
        sender.sendMessage(ChatColor.YELLOW + "T+- Compatible: " + (plugin.isTplusCompatible() ? "yes" : ChatColor.RED + "NO — requires v1.6.1+"));
        if (!plugin.isTplusCompatible()) {
            sender.sendMessage(ChatColor.RED + "  ↳ Tile processing is DISABLED. Update TerraPlusMinus to v1.6.1+ and restart.");
        }
        sender.sendMessage(ChatColor.YELLOW + "Active Source: " + config.getActiveSourceName());
        sender.sendMessage(ChatColor.YELLOW + "Tile URL: " + config.getTileUrl());
        sender.sendMessage(ChatColor.YELLOW + "Zoom: " + config.getZoom());
        sender.sendMessage(ChatColor.YELLOW + "Tile Offset: X=" + config.getTileOffsetX()
                + ", Z=" + config.getTileOffsetZ());
        sender.sendMessage(ChatColor.YELLOW + "Max Requests: " + config.getMaxConcurrentRequests());
        sender.sendMessage(ChatColor.YELLOW + "Cache Size: " + config.getCacheSize());
        sender.sendMessage(ChatColor.YELLOW + "Surface Block Mask: " + config.isSurfaceBlockMask());
        sender.sendMessage(ChatColor.YELLOW + "Active Blockset: " + BlockColorRegistry.getCurrentBlockset());
        sender.sendMessage(ChatColor.YELLOW + "Blocks Loaded: " + BlockColorRegistry.getMappingCount());
        return true;
    }

    private boolean handleListSources(CommandSender sender) {
        if (!sender.hasPermission("tilemapper.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run this command");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "=== Available Tile Sources ===");
        String active = config.getActiveSourceName();
        for (Map.Entry<String, TileSource> entry : config.getTileSources().entrySet()) {
            String prefix = entry.getKey().equals(active)
                    ? ChatColor.GREEN + "> " + entry.getKey() + ChatColor.RESET + " (active)"
                    : "  " + entry.getKey();
            TileSource src = entry.getValue();
            sender.sendMessage(prefix + " \u2014 zoom " + src.zoom()
                    + ", offset " + src.offsetX() + "/" + src.offsetZ());
        }
        sender.sendMessage(ChatColor.GRAY + "Use /tsm source <name> to switch");
        return true;
    }

    private boolean handleSwitchSource(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tilemapper.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run this command");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /tsm source <name>");
            return true;
        }
        String name = args[1];
        boolean success = TileMapper.instance.switchTileSource(name);
        if (success) {
            sender.sendMessage(ChatColor.GREEN + "Switched to tile source: " + name);
        } else {
            sender.sendMessage(ChatColor.RED + "Tile source not found: " + name
                    + ". Use /tsm sources to list available sources.");
        }
        return true;
    }

    private boolean handleListBlocksets(CommandSender sender) {
        if (!sender.hasPermission("tilemapper.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run this command");
            return true;
        }
        sender.sendMessage(ChatColor.GOLD + "=== Available Blocksets ===");
        String active = BlockColorRegistry.getCurrentBlockset();

        // Local editable copy
        String localPrefix = "custom_blockset".equals(active)
                ? ChatColor.GREEN + "> custom_blockset" + ChatColor.RESET + " (active, local)"
                : "  custom_blockset (local)";
        sender.sendMessage(localPrefix);

        // Online read-only blocksets from the repository
        for (String name : BlockColorRegistry.getBundledBlocksets()) {
            String prefix = name.equals(active)
                    ? ChatColor.GREEN + "> " + name + ChatColor.RESET + " (active, online)"
                    : "  " + name + " (online)";
            sender.sendMessage(prefix);
        }

        sender.sendMessage(ChatColor.GRAY + "Use /tsm blockset <name> to switch");
        return true;
    }

    private boolean handleSwitchBlockset(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tilemapper.admin")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run this command");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /tsm blockset <name>");
            return true;
        }

        String name = args[1];
        boolean success = TileMapper.instance.switchBlockset(name);
        if (success) {
            sender.sendMessage(ChatColor.GREEN + "Switched to blockset: " + name);
        } else {
            sender.sendMessage(ChatColor.RED + "Blockset not found: " + name
                    + ". Use /tsm blocksets to list available blocksets.");
        }
        return true;
    }

    /**
     * Handle the /tsm generate command.
     * Syntax: /tsm generate [blockset] [source] [xoffset zoffset]
     * All parameters are optional — defaults come from server config.
     */
    private boolean handleGenerate(CommandSender sender, String[] args) {
        if (!(sender instanceof org.bukkit.entity.Player)) {
            sender.sendMessage(ChatColor.RED + "Only players can use this command");
            return true;
        }
        if (!sender.hasPermission("tilemapper.player")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to run this command");
            return true;
        }

        // Parse optional arguments
        String blockset = null;
        String source = null;
        Double xoff = null, zoff = null;

        if (args.length >= 2) blockset = args[1];
        if (args.length >= 3) source = args[2];
        if (args.length >= 5) {
            try {
                xoff = Double.parseDouble(args[3]);
                zoff = Double.parseDouble(args[4]);
            } catch (NumberFormatException e) {
                sender.sendMessage(ChatColor.RED + "Invalid offset values. Usage: /tsm generate [blockset] [source] [xoffset zoffset]");
                return true;
            }
        } else if (args.length == 4) {
            sender.sendMessage(ChatColor.RED + "Offset requires both X and Z values. Usage: /tsm generate [blockset] [source] <xoffset zoffset>");
            return true;
        }

        SelectionTileGenerator.generate(
                (org.bukkit.entity.Player) sender,
                blockset, source, xoff, zoff,
                config, TileMapper.instance
        );
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        if (args.length == 1) {
            java.util.List<String> cmds = new java.util.ArrayList<>(
                    List.of("reload", "status", "sources", "source", "blocksets", "blockset", "generate"));
            // Only show generate to players with tilemapper.player
            if (!sender.hasPermission("tilemapper.player")) {
                cmds.remove("generate");
            }
            return cmds;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("source")) {
            return List.copyOf(config.getTileSources().keySet());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("blockset")) {
            java.util.List<String> suggestions = new java.util.ArrayList<>();
            suggestions.add("custom_blockset");
            java.util.Collections.addAll(suggestions, BlockColorRegistry.getBundledBlocksets());
            java.util.Collections.sort(suggestions);
            return suggestions;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("generate")) {
            // Suggest blocksets
            java.util.List<String> suggestions = new java.util.ArrayList<>();
            java.util.Collections.addAll(suggestions, BlockColorRegistry.getBundledBlocksets());
            suggestions.add("custom_blockset");
            java.util.Collections.sort(suggestions);
            return suggestions;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("generate")) {
            // Suggest tile sources
            return List.copyOf(config.getTileSources().keySet());
        }
        return super.tabComplete(sender, alias, args);
    }
}
