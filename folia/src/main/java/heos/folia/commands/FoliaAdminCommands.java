package heos.folia.commands;

import heos.folia.utils.FoliaPasswordHasher;
import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;
import heos.folia.storage.FoliaWhitelistData;
import heos.folia.event.FoliaAuthService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

public final class FoliaAdminCommands implements CommandExecutor, TabCompleter {
    private final FoliaStorage storage;
    private final FoliaWhitelistData whitelistData;
    private final FoliaMigrationCommands migrationCommands;
    private final org.bukkit.plugin.Plugin plugin;
    private final FoliaAuthService authService;
    private final FoliaBanCommands banCommands;

    public FoliaAdminCommands(org.bukkit.plugin.Plugin plugin, FoliaStorage storage, FoliaWhitelistData whitelistData, FoliaMigrationCommands migrationCommands, FoliaAuthService authService, FoliaBanCommands banCommands) {
        this.plugin = plugin;
        this.storage = storage;
        this.whitelistData = whitelistData;
        this.migrationCommands = migrationCommands;
        this.authService = authService;
        this.banCommands = banCommands;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }

        String sub = args[0].toLowerCase();

        // Always-available auth subcommands: avoids needing `heos:` prefix when /login or /register conflicts.
        if (sub.equals("login") || sub.equals("register") || sub.equals("changepassword")) {
            return auth(sender, args);
        }

        // Admin-only subcommands
        if (!sender.hasPermission("heos.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission");
            return true;
        }

        return switch (sub) {
            case "resetpassword" -> resetPassword(sender, args);
            case "info" -> info(sender, args);
            case "whitelist" -> whitelist(sender, args);
            case "migrate", "confirm-click" -> migrationCommands.onHeosSubcommand(sender, args);
            case "reload" -> reload(sender, args);
            case "ban", "ban-ip", "unban", "unban-ip", "banlist" -> banCommands.onSubcommand(sender, sub, shiftArgs(args));
            default -> false;
        };
    }

    private boolean auth(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("login")) {
            if (args.length != 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /heos login <password>");
                return true;
            }
            authService.login(player, args[1]);
            return true;
        }
        if (sub.equals("register")) {
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /heos register <password> <confirmPassword>");
                return true;
            }
            authService.register(player, args[1], args[2]);
            return true;
        }
        if (sub.equals("changepassword")) {
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /heos changepassword <oldPassword> <newPassword>");
                return true;
            }
            authService.changePassword(player, args[1], args[2]);
            return true;
        }
        return false;
    }

    private static String[] shiftArgs(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] shifted = new String[args.length - 1];
        System.arraycopy(args, 1, shifted, 0, shifted.length);
        return shifted;
    }

    private boolean resetPassword(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /heos resetpassword <player> <newPassword>");
            return true;
        }
        String username = args[1];
        String password = args[2];
        FoliaPlayerData data = storage.load(username);
        if (!data.isRegistered()) {
            sender.sendMessage(ChatColor.RED + "Player " + username + " is not registered");
            return true;
        }
        data.passwordHash = FoliaPasswordHasher.hashPassword(password);
        storage.save(data);
        sender.sendMessage(ChatColor.GREEN + "Reset password for player " + username);

        Player online = Bukkit.getPlayerExact(username);
        if (online != null) {
            online.sendMessage(ChatColor.YELLOW + "Your password was reset by an administrator");
            online.sendMessage(ChatColor.YELLOW + "New password: " + password);
            online.sendMessage(ChatColor.YELLOW + "Please use /changepassword soon");
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /heos info <player>");
            return true;
        }
        FoliaPlayerData data = storage.load(args[1]);
        if (!data.isRegistered()) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " is not registered");
            return true;
        }
        sender.sendMessage(ChatColor.GRAY + "=================================");
        sender.sendMessage(ChatColor.YELLOW + "Player info: " + data.username);
        sender.sendMessage(ChatColor.GRAY + "UUID: " + (data.uuid == null ? "unknown" : data.uuid));
        sender.sendMessage(ChatColor.GRAY + "Last IP: " + (data.lastIp == null || data.lastIp.isBlank() ? "unknown" : data.lastIp));
        sender.sendMessage(ChatColor.GRAY + "Registered at: " + (data.registeredTime > 0L ? new Date(data.registeredTime) : "unknown"));
        sender.sendMessage(ChatColor.GRAY + "Last login: " + (data.lastLoginTime > 0L ? new Date(data.lastLoginTime) : "unknown"));
        sender.sendMessage(ChatColor.GRAY + "Account type: " + (data.isOnlineAccount ? "premium" : "offline"));
        sender.sendMessage(ChatColor.GRAY + "=================================");
        return true;
    }

    private boolean whitelist(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /heos whitelist <add|remove|list> [player]");
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length != 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /heos whitelist add <player>");
                    return true;
                }
                if (whitelistData.add(args[2])) {
                    sender.sendMessage(ChatColor.GREEN + "Added " + args[2] + " to whitelist");
                } else {
                    sender.sendMessage(ChatColor.RED + "Player is already in whitelist: " + args[2]);
                }
                return true;
            }
            case "remove" -> {
                if (args.length != 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /heos whitelist remove <player>");
                    return true;
                }
                if (whitelistData.remove(args[2])) {
                    sender.sendMessage(ChatColor.GREEN + "Removed " + args[2] + " from whitelist");
                } else {
                    sender.sendMessage(ChatColor.RED + "Player is not in whitelist: " + args[2]);
                }
                return true;
            }
            case "list" -> {
                sender.sendMessage(ChatColor.YELLOW + "Whitelist size: " + whitelistData.usernames.size());
                if (!whitelistData.usernames.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + String.join(", ", whitelistData.usernames));
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("heos.admin")) {
                return filter(List.of("login", "register", "changepassword", "ban", "ban-ip", "unban", "unban-ip", "banlist", "resetpassword", "info", "whitelist", "migrate", "reload"), args[0]);
            }
            return filter(List.of("login", "register", "changepassword"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            if (!sender.hasPermission("heos.admin")) {
                return Collections.emptyList();
            }
            return filter(List.of("add", "remove", "list"), args[1]);
        }
        if ((args.length == 2 && (args[0].equalsIgnoreCase("resetpassword") || args[0].equalsIgnoreCase("info")))
                || (args.length == 3 && args[0].equalsIgnoreCase("whitelist") && !args[1].equalsIgnoreCase("list"))) {
            if (!sender.hasPermission("heos.admin")) {
                return Collections.emptyList();
            }
            String prefix = args[args.length - 1].toLowerCase();
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase().startsWith(prefix)) {
                    names.add(player.getName());
                }
            }
            return names;
        }
        return Collections.emptyList();
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /heos reload");
            return true;
        }
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "Heos config reloaded");
        return true;
    }

    private static List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (value.startsWith(lower)) {
                result.add(value);
            }
        }
        return result;
    }
}
