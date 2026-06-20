package heos.folia.commands;

import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaNameResolver;
import heos.folia.storage.FoliaStorage;
import heos.folia.utils.FoliaMessages;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public final class FoliaBindCommands implements CommandExecutor, TabCompleter {
    private final FoliaAccountBinding accountBinding;
    private final FoliaNameResolver nameResolver;
    private final FoliaStorage storage;

    public FoliaBindCommands(FoliaAccountBinding accountBinding, FoliaNameResolver nameResolver, FoliaStorage storage) {
        this.accountBinding = accountBinding;
        this.nameResolver = nameResolver;
        this.storage = storage;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players.");
            return true;
        }

        if (args.length == 0) {
            showUsage(player);
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "request" -> handleRequest(player, args);
            case "accept" -> handleAccept(player, args);
            case "deny" -> handleDeny(player, args);
            case "status" -> handleStatus(player);
            case "list" -> handleList(sender, args);
            case "revoke" -> handleRevoke(sender, args);
            default -> showUsage(player);
        }
        return true;
    }

    private void handleRequest(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + FoliaMessages.bindUsageRequest());
            return;
        }
        String oldName = args[1];
        FoliaAccountBinding.BindResult result = accountBinding.requestBinding(
                player.getUniqueId(), player.getName(), oldName);
        player.sendMessage(result.success ? ChatColor.GREEN + result.message : ChatColor.RED + result.message);
    }

    private void handleAccept(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + FoliaMessages.bindUsageAccept());
            return;
        }
        String newName = args[1];
        FoliaAccountBinding.BindResult result = accountBinding.acceptBinding(
                player.getUniqueId(), player.getName(), newName);
        player.sendMessage(result.success ? ChatColor.GREEN + result.message : ChatColor.RED + result.message);
    }

    private void handleDeny(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(ChatColor.RED + FoliaMessages.bindUsageDeny());
            return;
        }
        // Deny = we find pending requests for this old name and remove them
        // Since we don't have a direct deny in storage yet, we'll just tell them no pending
        List<FoliaStorage.BindingEntry> pending = storage.getPendingBindingsForOldName(player.getName());
        if (pending.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + FoliaMessages.bindNoPendingForYou());
            return;
        }
        String newName = args[1];
        for (FoliaStorage.BindingEntry entry : pending) {
            if (entry.newName.equalsIgnoreCase(newName)) {
                storage.revokeBinding(entry.id);
                player.sendMessage(ChatColor.GREEN + FoliaMessages.bindDenied(newName));
                return;
            }
        }
        player.sendMessage(ChatColor.RED + FoliaMessages.bindNoPendingRequest(newName));
    }

    private void handleStatus(Player player) {
        List<FoliaStorage.BindingEntry> pending = storage.getPendingBindingsForOldName(player.getName());
        if (pending.isEmpty()) {
            // Check if player has pending requests THEY sent
            List<FoliaStorage.BindingEntry> all = storage.listAllBindings();
            boolean hasOwnPending = false;
            for (FoliaStorage.BindingEntry entry : all) {
                if ("pending".equals(entry.status) && entry.newUuid != null
                        && entry.newUuid.equals(player.getUniqueId())) {
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                    player.sendMessage(ChatColor.YELLOW + FoliaMessages.bindRequestSent(entry.oldName)
                            + " (" + sdf.format(new Date(entry.createdAt)) + ")");
                    hasOwnPending = true;
                }
            }
            if (!hasOwnPending) {
                player.sendMessage(ChatColor.YELLOW + FoliaMessages.bindNoPendingForYou());
            }
            return;
        }
        player.sendMessage(ChatColor.YELLOW + "=== 待处理的绑定请求 ===");
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (FoliaStorage.BindingEntry entry : pending) {
            player.sendMessage(ChatColor.WHITE + "来自: " + ChatColor.AQUA + entry.newName +
                    ChatColor.GRAY + " (" + sdf.format(new Date(entry.createdAt)) + ")");
            player.sendMessage(ChatColor.GRAY + "  使用 " + ChatColor.WHITE + "/heos bind accept " + entry.newName +
                    ChatColor.GRAY + " 同意绑定");
            player.sendMessage(ChatColor.GRAY + "  使用 " + ChatColor.WHITE + "/heos bind deny " + entry.newName +
                    ChatColor.GRAY + " 拒绝绑定");
        }
    }

    private void handleList(CommandSender sender, String[] args) {
        if (!sender.hasPermission("heos.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied.");
            return;
        }
        List<FoliaStorage.BindingEntry> all = storage.listAllBindings();
        if (all.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "No bindings found.");
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + FoliaMessages.bindHeader());
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (FoliaStorage.BindingEntry entry : all) {
            String statusStr = "active".equals(entry.status) ? ChatColor.GREEN + "active" : ChatColor.YELLOW + "pending";
            sender.sendMessage(String.format(ChatColor.WHITE + "#%d " + ChatColor.AQUA + "%s " + ChatColor.GRAY + "-> " + ChatColor.AQUA + "%s " + ChatColor.WHITE + "[%s] " + ChatColor.DARK_GRAY + "%s",
                    entry.id,
                    entry.newName,
                    entry.oldName != null ? entry.oldName : "?",
                    statusStr,
                    sdf.format(new Date(entry.createdAt))));
        }
    }

    private void handleRevoke(CommandSender sender, String[] args) {
        if (!sender.hasPermission("heos.admin")) {
            sender.sendMessage(ChatColor.RED + "Permission denied.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + FoliaMessages.bindUsageRevoke());
            return;
        }
        try {
            long id = Long.parseLong(args[1]);
            boolean removed = storage.revokeBinding(id);
            sender.sendMessage(removed ? ChatColor.GREEN + FoliaMessages.bindRevoked(id) : ChatColor.RED + "Binding not found.");
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Invalid binding ID.");
        }
    }

    private void showUsage(Player player) {
        player.sendMessage(ChatColor.YELLOW + "=== HEOS 账号绑定 ===");
        player.sendMessage(ChatColor.WHITE + "/heos bind request <旧账号名>" + ChatColor.GRAY + " - 请求绑定到旧账号");
        player.sendMessage(ChatColor.WHITE + "/heos bind accept <新账号名>" + ChatColor.GRAY + " - 同意绑定请求");
        player.sendMessage(ChatColor.WHITE + "/heos bind deny <新账号名>" + ChatColor.GRAY + " - 拒绝绑定请求");
        player.sendMessage(ChatColor.WHITE + "/heos bind status" + ChatColor.GRAY + " - 查看待处理的绑定");
        if (player.hasPermission("heos.admin")) {
            player.sendMessage(ChatColor.WHITE + "/heos bind list" + ChatColor.GRAY + " - 列出所有绑定");
            player.sendMessage(ChatColor.WHITE + "/heos bind revoke <ID>" + ChatColor.GRAY + " - 撤销绑定");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            for (String sub : List.of("request", "accept", "deny", "status", "list", "revoke")) {
                if (sub.startsWith(prefix)) completions.add(sub);
            }
        }
        return completions;
    }
}
