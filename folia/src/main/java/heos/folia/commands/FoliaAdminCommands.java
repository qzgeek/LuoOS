package heos.folia.commands;

import heos.folia.utils.FoliaPasswordHasher;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.utils.ResourceWorldManager;
import heos.folia.utils.PlayerStatsTracker;
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
import java.io.File;
import java.util.UUID;

public final class FoliaAdminCommands implements CommandExecutor, TabCompleter {
    private final FoliaStorage storage;
    private final FoliaWhitelistData whitelistData;
    private final FoliaMigrationCommands migrationCommands;
    private final org.bukkit.plugin.Plugin plugin;
    private final FoliaAuthService authService;
    private final FoliaBanCommands banCommands;
    private final FoliaBindCommands bindCommands;
    private final FoliaNameResolver nameResolver;
    private final ResourceWorldManager resourceWorldManager;
    private final PlayerStatsTracker statsTracker;

    public FoliaAdminCommands(org.bukkit.plugin.Plugin plugin, FoliaStorage storage,
                              FoliaWhitelistData whitelistData,
                              FoliaMigrationCommands migrationCommands,
                              FoliaAuthService authService,
                              FoliaBanCommands banCommands,
                              FoliaBindCommands bindCommands,
                              ResourceWorldManager resourceWorldManager,
                              PlayerStatsTracker statsTracker) {
        this.plugin = plugin;
        this.storage = storage;
        this.whitelistData = whitelistData;
        this.migrationCommands = migrationCommands;
        this.authService = authService;
        this.banCommands = banCommands;
        this.bindCommands = bindCommands;
        this.nameResolver = authService.getNameResolver();
        this.resourceWorldManager = resourceWorldManager;
        this.statsTracker = statsTracker;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Refresh command suggestions on every use (catches mid-session OP changes)
        if (sender instanceof Player player) {
            player.updateCommands();
        }
        if (args.length == 0) {
            showHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("login") || sub.equals("register") || sub.equals("changepassword")) {
            return auth(sender, args);
        }

        if (sub.equals("bind")) {
            return bindCommands.onCommand(sender, command, label, shiftArgs(args));
        }

        if (sub.equals("resource")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "仅玩家可使用此命令。");
                return true;
            }
            resourceWorldManager.teleportToResource(player);
            return true;
        }

        if (sub.equals("stats")) { showStats(sender, args); return true; }

        if (!sender.hasPermission("luoos.admin")) {
            // Non-admin trying admin command or unknown subcommand — show help
            showHelp(sender);
            return true;
        }

        return switch (sub) {
            case "resetpassword" -> resetPassword(sender, args);
            case "info" -> info(sender, args);
            case "whitelist" -> whitelist(sender, args);
            case "migrate", "confirm-click" -> migrationCommands.onHeosSubcommand(sender, args);
            case "migrate-authme" -> migrateAuthMe(sender, args);
            case "migrate-authme-tsv" -> migrateAuthMeTsv(sender, args);
            case "reload" -> reload(sender, args);
            case "maintenance" -> maintenance(sender, args);
            case "statstop" -> { showStatsTop(sender, args); yield true; }
            case "papi_test" -> { showPapiTest(sender, args); yield true; }
            case "resourcerefresh" -> {
                resourceWorldManager.refreshResourceWorlds();
                sender.sendMessage(ChatColor.GREEN + "已触发资源世界刷新。");
                yield true;
            }
            case "resourcecreate" -> {
                resourceWorldManager.createResourceWorlds();
                sender.sendMessage(ChatColor.GREEN + "已创建新的资源世界。");
                yield true;
            }
            case "testcard" -> {
                if (!(plugin instanceof heos.folia.HeosFoliaPlugin hp)) {
                    sender.sendMessage(ChatColor.RED + "内部错误");
                    yield true;
                }
                var ss = hp.statusService;
                var bs = hp.getBotServer();
                if (ss == null || bs == null) {
                    sender.sendMessage(ChatColor.RED + "Bot 未启用");
                    yield true;
                }
                java.util.List<String> fakes = java.util.List.of(
                    "黔中极客", "小明", "方块达人", "建筑大师", "红石工程师",
                    "生存玩家", "矿工老王", "附魔师", "药水酿造师", "末影龙杀手",
                    "凋零骷髅猎手", "下界探险家", "海底守护者", "丛林冒险家",
                    "沙漠旅人", "冰原行者", "山地攀岩者", "蘑菇岛定居者",
                    "古城考古家", "深海潜水员", "天空之城建筑师", "地下铁匠",
                    "炼金术士", "召唤师", "闪电侠", "烈焰人杀手",
                    "村民交易大师", "流浪商人", "Steve", "Alex",
                    "Herobrine", "Notch", "Dream", "Technoblade"
                );
                try {
                    byte[] png = ss.renderTestCard(fakes);
                    if (png != null) {
                        String b64 = java.util.Base64.getEncoder().encodeToString(png);
                        String json = String.format(
                            "{\"action\":\"send_group_msg\",\"params\":{\"group_id\":1013432126,\"message\":\"[CQ:image,file=base64://%s]\"}}",
                            b64);
                        bs.broadcastRaw(json);
                        sender.sendMessage(ChatColor.GREEN + "测试卡片已发送！");
                    }
                } catch (Exception ex) {
                    sender.sendMessage(ChatColor.RED + "发送失败: " + ex.getMessage());
                }
                yield true;
            }
            case "ban", "ban-ip", "unban", "unban-ip", "banlist" -> banCommands.onSubcommand(sender, sub, shiftArgs(args));
            default -> { showHelp(sender); yield true; }
        };
    }

    private void showHelp(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== LuoOS v0.07 ===");
        sender.sendMessage(ChatColor.WHITE + "/los login <密码>" + ChatColor.GRAY + " - 登录");
        sender.sendMessage(ChatColor.WHITE + "/los register <密码> <确认>" + ChatColor.GRAY + " - 注册");
        sender.sendMessage(ChatColor.WHITE + "/los changepassword <旧> <新>" + ChatColor.GRAY + " - 改密");
        sender.sendMessage(ChatColor.WHITE + "/los bind" + ChatColor.GRAY + " - 账号绑定管理");
        if (sender.hasPermission("luoos.admin")) {
            sender.sendMessage(ChatColor.GRAY + "--- 管理命令 ---");
            sender.sendMessage(ChatColor.WHITE + "/los info <玩家>" + ChatColor.GRAY + " - 查看信息");
            sender.sendMessage(ChatColor.WHITE + "/los resetpassword <玩家|@p|@r> <密码>" + ChatColor.GRAY + " - 重置密码");
            sender.sendMessage(ChatColor.WHITE + "/los whitelist add/remove/list" + ChatColor.GRAY + " - 白名单");
            sender.sendMessage(ChatColor.WHITE + "/los migrate <源> <目标>" + ChatColor.GRAY + " - 数据迁移");
            sender.sendMessage(ChatColor.WHITE + "/los migrate-authme <路径>" + ChatColor.GRAY + " - AuthMe迁移");
            sender.sendMessage(ChatColor.WHITE + "/los reload" + ChatColor.GRAY + " - 重载配置");
            sender.sendMessage(ChatColor.WHITE + "/los maintenance <on|off|status>" + ChatColor.GRAY + " - 维护模式");
            sender.sendMessage(ChatColor.WHITE + "/ban /ban-ip /unban /banlist" + ChatColor.GRAY + " - 封禁管理");
        }
    }

    private boolean auth(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by a player");
            return true;
        }

        String sub = args[0].toLowerCase();
        if (sub.equals("login")) {
            if (args.length != 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /los login <password>");
                return true;
            }
            authService.login(player, args[1]);
            return true;
        }
        if (sub.equals("register")) {
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /los register <password> <confirmPassword>");
                return true;
            }
            authService.register(player, args[1], args[2]);
            return true;
        }
        if (sub.equals("changepassword")) {
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /los changepassword <oldPassword> <newPassword>");
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

    private FoliaPlayerData resolvePlayer(String nameArg, CommandSender sender) {
        // Handle vanilla selectors
        if (nameArg.startsWith("@")) {
            Player selected = resolveSelector(nameArg, sender);
            if (selected != null) {
                return authService.getPlayerData(selected.getUniqueId());
            }
            sender.sendMessage(ChatColor.RED + "未找到匹配的玩家。");
            return null;
        }

        // Try prefixed name first
        FoliaPlayerData prefixed = nameResolver.findByPrefixedName(nameArg);
        if (prefixed != null) {
            return prefixed;
        }

        // Load by plain name
        List<FoliaPlayerData> all = storage.loadAllByName(nameArg);
        if (all.size() > 1) {
            // Ambiguous — show hint
            sender.sendMessage(ChatColor.RED + ambiguousNameMsg(nameArg));
            sender.sendMessage(ChatColor.YELLOW + heos.folia.utils.FoliaMessages.nameAmbiguousHint());
            for (FoliaPlayerData data : all) {
                nameResolver.resolve(data);
                sender.sendMessage(ChatColor.GRAY + "  - " + data.effectiveDisplayName()
                        + " (" + (data.isOnlineAccount ? "premium" : "offline") + ") "
                        + (data.uuid != null ? data.uuid.toString().substring(0, 8) : "?"));
            }
            return null;
        }
        if (all.size() == 1) {
            nameResolver.resolve(all.get(0));
            return all.get(0);
        }
        return null;
    }

    /** Resolve vanilla @p/@r/@a selectors to a single player. */
    private Player resolveSelector(String selector, CommandSender sender) {
        var online = List.copyOf(Bukkit.getOnlinePlayers());
        if (online.isEmpty()) return null;
        return switch (selector) {
            case "@p" -> sender instanceof Player sp
                    ? online.stream().min((a, b) -> Double.compare(
                            a.getLocation().distanceSquared(sp.getLocation()),
                            b.getLocation().distanceSquared(sp.getLocation()))).orElse(null)
                    : online.get(0);
            case "@r" -> online.get(new java.util.Random().nextInt(online.size()));
            case "@a" -> online.get(0); // first online player
            default -> Bukkit.getPlayer(selector.substring(1)); // @name fallback
        };
    }

    private boolean resetPassword(CommandSender sender, String[] args) {
        if (args.length != 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /los resetpassword <player> <newPassword>");
            return true;
        }
        FoliaPlayerData data = resolvePlayer(args[1], sender);
        if (data == null) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " not found or name is ambiguous.");
            return true;
        }
        if (!data.isRegistered()) {
            sender.sendMessage(ChatColor.RED + "Player " + data.effectiveDisplayName() + " is not registered");
            return true;
        }
        String password = args[2];
        data.passwordHash = FoliaPasswordHasher.hashPassword(password);
        storage.save(data);
        sender.sendMessage(ChatColor.GREEN + "Reset password for player " + data.effectiveDisplayName());

        Player online = Bukkit.getPlayer(data.uuid);
        if (online != null) {
            online.sendMessage(ChatColor.YELLOW + "Your password was reset by an administrator");
            online.sendMessage(ChatColor.YELLOW + "New password: " + password);
            online.sendMessage(ChatColor.YELLOW + "Please use /changepassword soon");
        }
        return true;
    }

    private boolean info(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /los info <player>");
            return true;
        }
        FoliaPlayerData data = resolvePlayer(args[1], sender);
        if (data == null) {
            sender.sendMessage(ChatColor.RED + "Player " + args[1] + " not found or name is ambiguous.");
            return true;
        }
        if (!data.isRegistered()) {
            sender.sendMessage(ChatColor.RED + "Player " + data.effectiveDisplayName() + " is not registered");
            return true;
        }
        sender.sendMessage(ChatColor.GRAY + "=================================");
        sender.sendMessage(ChatColor.YELLOW + "Player info: " + data.effectiveDisplayName());
        if (data.hasNameConflict) {
            sender.sendMessage(ChatColor.GRAY + "Original name: " + data.username);
        }
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
            sender.sendMessage(ChatColor.RED + "Usage: /los whitelist <add|remove|list> [player]");
            return true;
        }
        switch (args[1].toLowerCase()) {
            case "add" -> {
                if (args.length != 3) {
                    sender.sendMessage(ChatColor.RED + "Usage: /los whitelist add <player>");
                    return true;
                }
                // Try resolve to UUID for precise whitelisting
                FoliaPlayerData data = resolvePlayer(args[2], sender);
                if (data != null && data.uuid != null) {
                    if (whitelistData.add(data.uuid)) {
                        sender.sendMessage(ChatColor.GREEN + "Added " + data.effectiveDisplayName() + " (UUID) to whitelist");
                        return true;
                    }
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
                    sender.sendMessage(ChatColor.RED + "Usage: /los whitelist remove <player>");
                    return true;
                }
                FoliaPlayerData data = resolvePlayer(args[2], sender);
                boolean removed = false;
                if (data != null && data.uuid != null) {
                    removed = whitelistData.removeByUuid(data.uuid);
                }
                if (!removed) {
                    removed = whitelistData.remove(args[2]);
                }
                if (removed) {
                    sender.sendMessage(ChatColor.GREEN + "Removed " + args[2] + " from whitelist");
                } else {
                    sender.sendMessage(ChatColor.RED + "Player is not in whitelist: " + args[2]);
                }
                return true;
            }
            case "list" -> {
                sender.sendMessage(ChatColor.YELLOW + "Whitelist size: " + whitelistData.usernames.size()
                        + (whitelistData.uuids != null ? " + " + whitelistData.uuids.size() + " UUIDs" : ""));
                if (!whitelistData.usernames.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Names: " + String.join(", ", whitelistData.usernames));
                }
                if (whitelistData.uuids != null && !whitelistData.uuids.isEmpty()) {
                    List<String> shortUuids = new ArrayList<>();
                    for (String id : whitelistData.uuids) {
                        shortUuids.add(id.substring(0, Math.min(id.length(), 8)));
                    }
                    sender.sendMessage(ChatColor.GRAY + "UUIDs: " + String.join(", ", shortUuids));
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
            List<String> subs = new ArrayList<>();
            subs.add("login"); subs.add("register"); subs.add("changepassword");
            subs.add("bind");
            subs.add("resource");
            subs.add("stats");
            if (sender.hasPermission("luoos.admin")) {
                subs.add("ban"); subs.add("ban-ip"); subs.add("unban"); subs.add("unban-ip");
                subs.add("banlist"); subs.add("resetpassword"); subs.add("info");
                subs.add("whitelist"); subs.add("migrate"); subs.add("migrate-authme"); subs.add("migrate-authme-tsv"); subs.add("reload");
                subs.add("maintenance");
                subs.add("statstop"); subs.add("papi_test");
            }
            return filter(subs, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            if (!sender.hasPermission("luoos.admin")) {
                return Collections.emptyList();
            }
            return filter(List.of("add", "remove", "list"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("bind")) {
            return bindCommands.onTabComplete(sender, command, alias, shiftArgs(args));
        }
        if ((args.length == 2 && (args[0].equalsIgnoreCase("resetpassword") || args[0].equalsIgnoreCase("info")))
                || (args.length == 3 && args[0].equalsIgnoreCase("whitelist") && !args[1].equalsIgnoreCase("list"))) {
            if (!sender.hasPermission("luoos.admin")) {
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

    private boolean migrateAuthMe(CommandSender sender, String[] args) {
        String authMeDir = args.length >= 2 ? args[1] : "plugins/AuthMe";
        File dir = new File(authMeDir);
        if (!dir.exists() || !new File(dir, "config.yml").exists()) {
            sender.sendMessage(ChatColor.RED + "AuthMe config not found at " + dir.getAbsolutePath());
            sender.sendMessage(ChatColor.GRAY + "Usage: /los migrate-authme [path-to-AuthMe-plugin-dir]");
            return true;
        }
        sender.sendMessage(ChatColor.YELLOW + "正在从 " + authMeDir + " 迁移 AuthMe 数据...");
        try {
            heos.folia.utils.AuthMeMigrator migrator = new heos.folia.utils.AuthMeMigrator(
                    java.util.logging.Logger.getLogger("LuoOS-AuthMe"), authService.getStorage());
            int count = migrator.migrate(authMeDir);
            sender.sendMessage(ChatColor.GREEN + "迁移完成！共迁移 " + count + " 个账号。");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "迁移失败: " + e.getMessage());
        }
        return true;
    }

    private boolean migrateAuthMeTsv(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /los migrate-authme-tsv <tsv-file>");
            return true;
        }
        String path = args[1];
        sender.sendMessage(ChatColor.YELLOW + "正在从 " + path + " 导入 AuthMe TSV 数据...");
        try {
            heos.folia.utils.AuthMeMigrator migrator = new heos.folia.utils.AuthMeMigrator(
                    java.util.logging.Logger.getLogger("LuoOS-AuthMe"), authService.getStorage());
            int count = migrator.migrateFromTsv(path);
            sender.sendMessage(ChatColor.GREEN + "导入完成！共迁移 " + count + " 个账号。");
        } catch (Exception e) {
            sender.sendMessage(ChatColor.RED + "导入失败: " + e.getMessage());
        }
        return true;
    }

    private boolean reload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(ChatColor.RED + "用法: /los reload");
            return true;
        }
        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "LuoOS 配置已重载");
        return true;
    }

    private boolean maintenance(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "用法: /los maintenance <on|off|status>");
            return true;
        }
        boolean current = plugin.getConfig().getBoolean("maintenance", false);
        switch (args[1].toLowerCase()) {
            case "on" -> {
                plugin.getConfig().set("maintenance", true);
                plugin.saveConfig();
                sender.sendMessage(ChatColor.GREEN + "维护模式已开启。仅管理员可进入服务器。");
                Bukkit.broadcastMessage(ChatColor.YELLOW + "[LuoOS] 服务器已进入维护模式，新玩家将无法加入。");
            }
            case "off" -> {
                plugin.getConfig().set("maintenance", false);
                plugin.saveConfig();
                sender.sendMessage(ChatColor.GREEN + "维护模式已关闭。所有玩家可正常进入。");
                Bukkit.broadcastMessage(ChatColor.GREEN + "[LuoOS] 服务器维护模式已结束，欢迎回来！");
            }
            case "status" -> sender.sendMessage(ChatColor.YELLOW + "维护模式: "
                    + (current ? ChatColor.RED + "开启中" : ChatColor.GREEN + "已关闭"));
            default -> sender.sendMessage(ChatColor.RED + "用法: /los maintenance <on|off|status>");
        }
        return true;
    }

    // ============ Stats ============

    private void showStats(CommandSender sender, String[] args) {
        UUID target;
        if (args.length >= 2) {
            Player p = Bukkit.getPlayer(args[1]);
            if (p != null) {
                target = p.getUniqueId();
            } else {
                sender.sendMessage(ChatColor.RED + "玩家未在线: " + args[1]);
                return;
            }
        } else if (sender instanceof Player pl) {
            target = pl.getUniqueId();
        } else {
            sender.sendMessage(ChatColor.RED + "用法: /los stats [玩家]");
            return;
        }

        var e = statsTracker.getStats(target);
        if (e == null) {
            sender.sendMessage(ChatColor.GRAY + "该玩家暂无统计数据。");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "=== " + e.name() + " 的统计 ===");
        sender.sendMessage(ChatColor.WHITE + "在线时长: " + ChatColor.AQUA + PlayerStatsTracker.formatPlayTime(e.playTime()));
        sender.sendMessage(ChatColor.WHITE + "挖掘方块: " + ChatColor.AQUA + String.format("%,d", e.blocksMined()));
        sender.sendMessage(ChatColor.WHITE + "放置方块: " + ChatColor.AQUA + String.format("%,d", e.blocksPlaced()));
        sender.sendMessage(ChatColor.WHITE + "聊天字数: " + ChatColor.AQUA + String.format("%,d", e.chatChars()));
    }

    private void showStatsTop(CommandSender sender, String[] args) {
        String col = "play_time_seconds";
        if (args.length >= 2 && PlayerStatsTracker.isValidColumn(args[1])) {
            col = args[1];
        }
        var list = statsTracker.getTop(col, 10);
        if (list.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "暂无统计数据。");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "=== " + PlayerStatsTracker.labelOf(col) + " 排行榜 ===");
        int rank = 1;
        for (var e : list) {
            long val = PlayerStatsTracker.valueOf(e, col);
            String valStr = col.equals("play_time_seconds") ? PlayerStatsTracker.formatPlayTime(val) : String.format("%,d", val);
            sender.sendMessage(ChatColor.WHITE + "" + rank + ". " + ChatColor.AQUA + e.name()
                    + ChatColor.GRAY + " - " + ChatColor.YELLOW + valStr);
            rank++;
        }
        sender.sendMessage(ChatColor.GRAY + "用法: /los statstop [play_time_seconds|blocks_mined|blocks_placed|chat_chars|entities_killed] [时间:7d/1w/1m/1q/1y]");
    }

    private void showPapiTest(CommandSender sender, String[] args) {
        UUID pid;
        if (sender instanceof Player pl) pid = pl.getUniqueId();
        else { sender.sendMessage(ChatColor.RED + "仅玩家可使用。"); return; }

        // Try to resolve via PAPI
        var papi = (me.clip.placeholderapi.PlaceholderAPI) null;
        try { papi = me.clip.placeholderapi.PlaceholderAPI.class.getDeclaredConstructor().newInstance(); } catch (Exception ignored) {}

        String[] stats = {"play_time_seconds", "blocks_mined", "blocks_placed", "chat_chars", "entities_killed"};
        String[] times = {"", "1d", "7d", "30d", "1w", "1m", "1q", "1y"};
        String[] timeLabels = {"总计", "1天", "7天", "30天", "1周", "1月", "1季", "1年"};

        sender.sendMessage(ChatColor.GOLD + "========== PAPI 占位符测试 ==========");
        sender.sendMessage(ChatColor.GRAY + "当前玩家: " + sender.getName());

        for (int i = 0; i < stats.length; i++) {
            String st = stats[i];
            sender.sendMessage(ChatColor.YELLOW + "--- " + PlayerStatsTracker.labelOf(st) + " ---");
            for (int j = 0; j < times.length; j++) {
                String tm = times[j];
                String placeholder = tm.isEmpty() ? ("luoos_stat_" + st) : ("luoos_stat_" + st + "_" + tm);
                String full = "%" + placeholder + "%";
                String val = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                        (org.bukkit.OfflinePlayer) sender, full);
                String label = timeLabels[j];
                sender.sendMessage(ChatColor.WHITE + "  " + label + ": " + ChatColor.AQUA + val
                        + ChatColor.DARK_GRAY + "  (" + full + ")");
            }
            // Rank
            String rankPH = "%luoos_stat_rank_" + st + "%";
            String rankVal = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                    (org.bukkit.OfflinePlayer) sender, rankPH);
            sender.sendMessage(ChatColor.WHITE + "  排名: " + ChatColor.GREEN + rankVal
                    + ChatColor.DARK_GRAY + "  (" + rankPH + ")");
            // Top 3 (all-time)
            for (int r = 1; r <= 3; r++) {
                String namePH = "%luoos_stat_top_name_" + st + "_" + r + "%";
                String valPH = "%luoos_stat_top_value_" + st + "_" + r + "%";
                String nv = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                        (org.bukkit.OfflinePlayer) sender, namePH);
                String vv = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                        (org.bukkit.OfflinePlayer) sender, valPH);
                sender.sendMessage(ChatColor.GRAY + "  #" + r + " " + ChatColor.WHITE + nv
                        + ChatColor.GRAY + " - " + ChatColor.YELLOW + vv);
            }
            // Top 1 with time range (7d) — name + value
            String tNamePH = "%luoos_stat_top_name_" + st + "_7d_1%";
            String tValPH = "%luoos_stat_top_value_" + st + "_7d_1%";
            String tn = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                    (org.bukkit.OfflinePlayer) sender, tNamePH);
            String tv = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(
                    (org.bukkit.OfflinePlayer) sender, tValPH);
            sender.sendMessage(ChatColor.DARK_GRAY + "  7天榜#1: " + ChatColor.WHITE + tn
                    + ChatColor.GRAY + " - " + ChatColor.YELLOW + tv
                    + ChatColor.DARK_GRAY + "  (%luoos_stat_top_value_" + st + "_7d_1%)");
        }
        sender.sendMessage(ChatColor.GOLD + "======================================");
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

    private static String ambiguousNameMsg(String name) {
        return heos.folia.utils.FoliaMessages.nameAmbiguous(name);
    }
}
