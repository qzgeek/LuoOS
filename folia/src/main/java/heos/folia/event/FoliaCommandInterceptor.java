package heos.folia.event;

import heos.folia.commands.FoliaBanCommands;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Locale;

public final class FoliaCommandInterceptor implements Listener {
    private final Plugin plugin;
    private final FoliaAuthService authService;
    private final FoliaBanCommands banCommands;

    public FoliaCommandInterceptor(Plugin plugin, FoliaAuthService authService, FoliaBanCommands banCommands) {
        this.plugin = plugin;
        this.authService = authService;
        this.banCommands = banCommands;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean("enableUnprefixedCommandHijack", true)) {
            return;
        }
        ParsedCommand parsed = parse(event.getMessage());
        if (parsed == null || !execute(event.getPlayer(), parsed.root, parsed.args)) {
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    void onServerCommand(ServerCommandEvent event) {
        if (!plugin.getConfig().getBoolean("enableUnprefixedCommandHijack", true)) {
            return;
        }
        ParsedCommand parsed = parse(event.getCommand());
        if (parsed == null || !execute(event.getSender(), parsed.root, parsed.args)) {
            return;
        }
        event.setCancelled(true);
    }

    private boolean execute(CommandSender sender, String root, String[] args) {
        return switch (root) {
            case "login", "l" -> login(sender, args);
            case "register", "reg" -> register(sender, args);
            case "changepassword", "changepw" -> changePassword(sender, args);
            case "ban", "ban-ip", "unban", "unban-ip", "banlist" -> banCommands.onSubcommand(sender, root, args);
            case "pardon" -> banCommands.onSubcommand(sender, "unban", args);
            case "pardon-ip" -> banCommands.onSubcommand(sender, "unban-ip", args);
            default -> false;
        };
    }

    private boolean login(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /login <password>");
            return true;
        }
        authService.login(player, args[0]);
        return true;
    }

    private boolean register(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /register <password> <confirmPassword>");
            return true;
        }
        authService.register(player, args[0], args[1]);
        return true;
    }

    private boolean changePassword(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player");
            return true;
        }
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /changepassword <oldPassword> <newPassword>");
            return true;
        }
        authService.changePassword(player, args[0], args[1]);
        return true;
    }

    private static ParsedCommand parse(String commandLine) {
        String normalized = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        normalized = normalized.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        String[] split = normalized.split("\\s+");
        String root = split[0].toLowerCase(Locale.ROOT);
        String[] args = split.length == 1 ? new String[0] : Arrays.copyOfRange(split, 1, split.length);
        return new ParsedCommand(root, args);
    }

    private record ParsedCommand(String root, String[] args) {
    }
}
