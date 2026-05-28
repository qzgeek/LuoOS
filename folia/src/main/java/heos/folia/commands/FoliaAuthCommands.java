package heos.folia.commands;

import heos.folia.event.FoliaAuthService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class FoliaAuthCommands implements CommandExecutor, TabCompleter {
    private final FoliaAuthService authService;

    public FoliaAuthCommands(FoliaAuthService authService) {
        this.authService = authService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("This command can only be used by a player");
            return true;
        }

        switch (command.getName().toLowerCase()) {
            case "login" -> {
                if (args.length != 1) {
                    return false;
                }
                authService.login(player, args[0]);
                return true;
            }
            case "register" -> {
                if (args.length != 2) {
                    return false;
                }
                authService.register(player, args[0], args[1]);
                return true;
            }
            case "changepassword" -> {
                if (args.length != 2) {
                    return false;
                }
                authService.changePassword(player, args[0], args[1]);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
