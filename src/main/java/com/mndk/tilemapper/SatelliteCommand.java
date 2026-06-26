package com.mndk.tilemapper;

import com.mndk.tilemapper.block.BlockColorRegistry;
import com.mndk.tilemapper.config.PluginConfig;
import com.mndk.tilemapper.processor.ChunkProcessor;
import com.mndk.tilemapper.tile.server.TileServer;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.mndk.tilemapper.config.TileSource;

public class SatelliteCommand implements TabExecutor {

    private final PluginConfig config;
    private final ChunkProcessor processor;
    private final TileServer tileServer;

    public SatelliteCommand(PluginConfig config, ChunkProcessor processor, TileServer tileServer) {
        this.config = config;
        this.processor = processor;
        this.tileServer = tileServer;
    }

    public void register(JavaPlugin plugin) {
        PluginCommand cmd = plugin.getCommand("tsm");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /tsm <reload|status|sources|source>");
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
            default:
                sender.sendMessage(ChatColor.RED + "Unknown subcommand: " + args[0]);
                sender.sendMessage(ChatColor.RED + "Available: reload, status, sources, source <name>");
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
        sender.sendMessage(ChatColor.GOLD + "=== TileMapper Status ===");
        sender.sendMessage(ChatColor.YELLOW + "Enabled: " + (config.isEnabled() ? "yes" : "no"));
        sender.sendMessage(ChatColor.YELLOW + "Active Source: " + config.getActiveSourceName());
        sender.sendMessage(ChatColor.YELLOW + "Tile URL: " + config.getTileUrl());
        sender.sendMessage(ChatColor.YELLOW + "Zoom: " + config.getZoom());
        sender.sendMessage(ChatColor.YELLOW + "Tile Offset: X=" + config.getTileOffsetX()
                + ", Z=" + config.getTileOffsetZ());
        sender.sendMessage(ChatColor.YELLOW + "Max Requests: " + config.getMaxConcurrentRequests());
        sender.sendMessage(ChatColor.YELLOW + "Cache Size: " + config.getCacheSize());
        sender.sendMessage(ChatColor.YELLOW + "Surface Block Mask: " + config.isSurfaceBlockMask());
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "status", "sources", "source");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("source")) {
            return List.copyOf(config.getTileSources().keySet());
        }
        return Collections.emptyList();
    }
}
