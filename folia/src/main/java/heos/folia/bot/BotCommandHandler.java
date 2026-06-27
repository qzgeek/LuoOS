package heos.folia.bot;

import heos.folia.storage.FoliaStorage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

/**
 * QQ group command handler — LuoOS Bot.
 *
 * Player commands (anyone):
 *   help/帮助/菜单
 *   服务器还活着吗/服务器状态
 *   申请白名单/白名单/添加白名单 &lt;ID&gt;
 *   删除白名单/移除白名单 &lt;ID&gt;
 *   查询白名单/查询/查看/查看白名单 [name/QQ]
 *
 * Admin commands (admin/owner):
 *   封禁/ban @QQ [duration]
 *   解封/unban @QQ
 *   删除 @QQ &lt;ID&gt;
 *   封禁列表/查看封禁列表
 */
public class BotCommandHandler {
    private final Logger logger;
    private final BotDb botDb;
    private final FoliaStorage storage;
    private final BotStatusService statusService;
    private final int maxPerQq;
    private final Pattern idPattern;
    private final long[] allowedGroups;

    private final Map<Long, long[]> rateMap = new ConcurrentHashMap<>();
    private final int rateMax;
    private final long rateWindowMs;
    private final String statusTrigger;

    // Reply delay range (ms)
    private final int delayMinMs;
    private final int delayMaxMs;
    private final java.util.Random random = new java.util.Random();

    // --- Patterns ---
    private static final Pattern APPLY = Pattern.compile("^(申请白名单|白名单|添加白名单)\\s*(\\S+)$");
    private static final Pattern DELETE = Pattern.compile("^(删除白名单|移除白名单)\\s+(\\S+)$");
    private static final Pattern QUERY_SIMPLE = Pattern.compile("^(查询白名单|查询|查看|查看白名单)$");
    private static final Pattern QUERY_ARGS  = Pattern.compile("^(查询白名单|查询|查看|查看白名单)\\s+(.+)$");
    private static final Pattern HELP = Pattern.compile("^(help|帮助|命令|菜单|HELP|Help)$");
    private static final Pattern STATUS = Pattern.compile("^(服务器还活着吗|服务器状态)$");
    private static final Pattern BAN_CMD = Pattern.compile("^(封禁|ban)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNBAN_CMD = Pattern.compile("^(解封|unban)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAN_LIST = Pattern.compile("^(封禁列表|查看封禁列表|banlist)$", Pattern.CASE_INSENSITIVE);
    // Admin delete: 删除 @QQ [ID]
    private static final Pattern ADMIN_DELETE = Pattern.compile("^删除\\s+(.+)$");

    // Deny emoji
    private static final int EMOJI_DENY = 15;

    public BotCommandHandler(Logger logger, BotDb botDb, FoliaStorage storage, BotStatusService statusService,
                             int maxPerQq, String allowedIdChars, int maxIdLength,
                             long[] allowedGroups, String statusTrigger,
                             int rateMax, int rateWindowSec,
                             int delayMinMs, int delayMaxMs) {
        this.logger = logger;
        this.botDb = botDb;
        this.storage = storage;
        this.statusService = statusService;
        this.maxPerQq = maxPerQq;
        // Sanitize regex: preserve range hyphens (a-z etc.), move standalone '-' to end
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < allowedIdChars.length(); i++) {
            char c = allowedIdChars.charAt(i);
            if (c == '-' && i > 0 && i + 1 < allowedIdChars.length()
                    && Character.isLetterOrDigit(allowedIdChars.charAt(i - 1))
                    && Character.isLetterOrDigit(allowedIdChars.charAt(i + 1))) {
                kept.append(c);
            } else if (c != '-') {
                kept.append(c);
            }
        }
        String idChars = kept.toString();
        if (allowedIdChars.contains("-")) idChars = idChars + "-";
        try {
            this.idPattern = Pattern.compile("^[" + idChars + "]{1," + maxIdLength + "}$");
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid allowed_id_chars: '" + allowedIdChars + "'", e);
        }
        this.allowedGroups = allowedGroups;
        this.statusTrigger = statusTrigger;
        this.rateMax = rateMax;
        this.rateWindowMs = rateWindowSec * 1000L;
        this.delayMinMs = delayMinMs;
        this.delayMaxMs = delayMaxMs;
    }

    private void delayReply() {
        if (delayMaxMs <= 0) return;
        int delay = delayMinMs + random.nextInt(Math.max(1, delayMaxMs - delayMinMs + 1));
        try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
    }

    public void handle(OneBotEvent event) {
        if (!"message".equals(event.postType()) || !"group".equals(event.messageType())) return;

        long groupId = event.groupId();
        if (!isAllowed(groupId)) return;

        long qq = event.userId();
        String text = event.rawMessage().trim();
        if (text.isEmpty()) return;

        String role = event.senderRole();
        boolean isAdmin = "admin".equals(role) || "owner".equals(role);

        logger.info("[BotHandler] QQ" + qq + " group=" + groupId + " role=" + role + ": " + text);

        // Global rate limit (admins bypass, silent ignore)
        if (!isAdmin && !checkRate(groupId)) {
            return;
        }

        // Apply reply delay for recognized commands
        boolean isCommand = STATUS.matcher(text).matches() || HELP.matcher(text).matches()
                || APPLY.matcher(text).matches() || DELETE.matcher(text).matches()
                || QUERY_SIMPLE.matcher(text).matches() || QUERY_ARGS.matcher(text).matches();
        if (isCommand || isAdmin) {
            delayReply();
        }

        // --- Status (rate-limited for non-admin) ---
        if (STATUS.matcher(text).matches()) {
            handleStatus(event);
            return;
        }

        // --- Help ---
        if (HELP.matcher(text).matches()) { handleHelp(event); return; }

        // --- Admin-only commands ---
        if (isAdmin) {
            // Ban list
            if (BAN_LIST.matcher(text).matches()) { handleBanList(event); return; }
            // Ban
            if (handleBan(qq, text, event)) return;
            // Unban
            if (handleUnban(qq, text, event)) return;
            // Admin delete
            Matcher adm = ADMIN_DELETE.matcher(text);
            if (adm.matches()) { handleAdminDelete(qq, adm.group(1), event); return; }
        } else {
            // Non-admin tried admin command → deny silently
            if (BAN_CMD.matcher(text).matches() || UNBAN_CMD.matcher(text).matches()
                    || BAN_LIST.matcher(text).matches() || ADMIN_DELETE.matcher(text).matches()) {
                event.reactDeny();
                return;
            }
        }

        // --- Blacklist check for apply/delete ---
        if (botDb.isBlacklisted(qq) && (APPLY.matcher(text).matches() || DELETE.matcher(text).matches())) {
            event.reactDeny();
            return;
        }

        // --- Player commands ---
        Matcher m = APPLY.matcher(text);
        if (m.matches()) { handleApply(qq, m.group(2), event); return; }

        m = DELETE.matcher(text);
        if (m.matches()) { handleSelfDelete(qq, m.group(2), event); return; }

        // Query (with or without args)
        m = QUERY_ARGS.matcher(text);
        if (m.matches()) { handleQuery(qq, m.group(2).trim(), event); return; }
        if (QUERY_SIMPLE.matcher(text).matches()) { handleQuery(qq, null, event); return; }

        // Unknown — only log if it looks like a command attempt
        if (text.length() < 30 && (text.startsWith("白名单") || text.startsWith("申请") || text.startsWith("添加")
                || text.startsWith("删除") || text.startsWith("移除") || text.startsWith("查询")
                || text.startsWith("查看") || text.startsWith("封禁") || text.startsWith("解封")
                || text.startsWith("服务器") || text.startsWith("help") || text.startsWith("HELP"))) {
            logger.info("[BotHandler] Unknown command: " + text);
        }
    }

    // ======================== Helpers ========================

    private boolean isAllowed(long groupId) {
        if (allowedGroups.length == 0) return true;
        for (long g : allowedGroups) if (g == groupId) return true;
        return false;
    }

    private boolean checkRate(long groupId) {
        long now = System.currentTimeMillis();
        long[] times = rateMap.computeIfAbsent(groupId, k -> new long[0]);
        int valid = 0;
        for (long t : times) if (now - t < rateWindowMs) valid++;
        if (valid >= rateMax) return false;
        long[] newTimes = new long[valid + 1];
        int idx = 0;
        for (long t : times) if (now - t < rateWindowMs) newTimes[idx++] = t;
        newTimes[idx] = now;
        rateMap.put(groupId, newTimes);
        return true;
    }

    // ======================== Status ========================

    private void handleStatus(OneBotEvent event) {
        try {
            byte[] pngBytes = statusService.renderLocal();
            if (pngBytes != null && pngBytes.length > 0) {
                String b64 = java.util.Base64.getEncoder().encodeToString(pngBytes);
                event.replyImage("base64://" + b64);
            } else {
                BotStatusService.ServerStatus info = statusService.ping();
                Runtime rt = Runtime.getRuntime();
                double mem = (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory();
                event.reply(statusService.formatStatusText(info, null, mem));
            }
            event.react(true);
        } catch (Exception e) {
            event.reply("查询失败: " + e.getMessage());
            event.react(false);
        }
    }

    // ======================== Help ========================

    private void handleHelp(OneBotEvent event) {
        String txt = "LuoOS Bot 命令帮助\n\n"
            + "申请白名单/白名单/添加白名单 <ID>  申请白名单\n"
            + "删除白名单/移除白名单 <ID>        删除自己的白名单\n"
            + "查询白名单/查询/查看 [name/QQ]    查看白名单\n"
            + "服务器还活着吗/服务器状态         查看服务器状态\n"
            + "help/帮助/菜单                    显示此帮助\n\n"
            + "——管理员——\n"
            + "封禁/ban @QQ [时长]              封禁用户\n"
            + "解封/unban @QQ                   解禁用户\n"
            + "删除 @QQ <ID>                    删除指定用户的白名单\n"
            + "封禁列表/查看封禁列表             查看封禁列表\n\n"
            + "Write by 黔中极客 / LuoOS Bot v0.06";
        event.reply(txt);
        event.react(true);
    }

    // ======================== Whitelist apply ========================

    private void handleApply(long qq, String playerId, OneBotEvent event) {
        logger.info("[BotHandler] handleApply: qq=" + qq + " playerId=" + playerId);
        if (!idPattern.matcher(playerId).matches()) {
            logger.info("[BotHandler] handleApply: idPattern rejected '" + playerId + "'");
            event.react(false); return;
        }
        if (botDb.hasWhitelist(qq, playerId)) {
            logger.info("[BotHandler] handleApply: already whitelisted qq=" + qq + " " + playerId);
            event.react(false); return;
        }
        int count = botDb.getWhitelistCount(qq);
        if (count >= maxPerQq) {
            logger.info("[BotHandler] handleApply: limit reached qq=" + qq + " count=" + count);
            event.react(false); return;
        }
        String uuid = null;
        var data = storage.load(playerId);
        if (data != null) uuid = data.uuid.toString();
        botDb.addWhitelist(qq, playerId, uuid);
        try {
            org.bukkit.Bukkit.getGlobalRegionScheduler().run(
                    org.bukkit.Bukkit.getPluginManager().getPlugin("luoos"),
                    task -> {
                        org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
                        if (op != null) op.setWhitelisted(true);
                    });
        } catch (Exception e) {
            logger.warning("[BotHandler] Whitelist sync failed: " + e.getMessage());
        }
        event.react(true);
        logger.info("[BotHandler] QQ" + qq + " applied whitelist: " + playerId);
    }

    // ======================== Whitelist self-delete ========================

    private void handleSelfDelete(long qq, String playerId, OneBotEvent event) {
        if (!botDb.hasWhitelist(qq, playerId)) { event.react(false); return; }
        botDb.removeWhitelist(qq, playerId);
        try {
            org.bukkit.Bukkit.getGlobalRegionScheduler().run(
                    org.bukkit.Bukkit.getPluginManager().getPlugin("luoos"),
                    task -> {
                        org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(playerId);
                        if (op != null) op.setWhitelisted(false);
                    });
        } catch (Exception e) {
            logger.warning("[BotHandler] Whitelist unsync failed: " + e.getMessage());
        }
        event.react(true);
    }

    // ======================== Query (self / other / reverse) ========================

    /**
     * query(null)  = show own whitelist
     * query(QQ#)   = show that QQ's whitelist
     * query("name") = show which QQ owns that game ID (reverse lookup)
     */
    private void handleQuery(long qq, String arg, OneBotEvent event) {
        if (arg == null || arg.isEmpty()) {
            // Self query
            showWhitelist(qq, "你", event);
            return;
        }

        // Try numeric = QQ lookup
        try {
            long targetQq = Long.parseLong(arg);
            showWhitelist(targetQq, "QQ" + targetQq, event);
            return;
        } catch (NumberFormatException ignored) {}

        // Strip quotes if present
        String name = arg.replaceAll("^[\"'\u201c\u201d\u2018\u2019]", "").replaceAll("[\"'\u201c\u201d\u2018\u2019]$", "");

        // Reverse lookup: find QQ(s) that own this game ID
        try {
            var conn = storage.getConnection();
            var ps = conn.prepareStatement("SELECT qq, player_uuid FROM qq_whitelist WHERE LOWER(player_name) = ?");
            ps.setString(1, name.toLowerCase());
            var rs = ps.executeQuery();
            List<String> found = new ArrayList<>();
            while (rs.next()) {
                long ownerQq = rs.getLong("qq");
                String uid = rs.getString("player_uuid");
                found.add("QQ" + ownerQq + (uid != null && !uid.isEmpty() ? " (UUID:" + uid.substring(0, 8) + "...)" : ""));
            }
            if (found.isEmpty()) {
                event.replyAt("未找到 " + name + " 的白名单记录");
            } else {
                event.replyAt("游戏ID " + name + " 的绑定信息:\n" + String.join("\n", found));
            }
            event.react(true);
        } catch (Exception e) {
            logger.warning("[BotHandler] Reverse query failed: " + e.getMessage());
            event.replyAt("查询失败");
            event.react(false);
        }
    }

    private void showWhitelist(long targetQq, String label, OneBotEvent event) {
        var players = botDb.getWhitelist(targetQq);
        int count = players.size();
        if (players.isEmpty()) {
            event.replyAt(label + "还没有添加白名单 (0/" + maxPerQq + ")");
        } else {
            StringBuilder sb = new StringBuilder(label + "的白名单 (" + count + "/" + maxPerQq + "):\n");
            for (int i = 0; i < players.size(); i++)
                sb.append(i + 1).append(". ").append(players.get(i)).append("\n");
            event.replyAt(sb.toString());
        }
        event.react(true);
    }

    // ======================== Admin: ban ========================

    private boolean handleBan(long adminQq, String text, OneBotEvent event) {
        Matcher m = BAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractTargetQq(text, event);
        if (targetQq == 0) { event.react(false); return true; }
        String dur = parseDuration(text);
        botDb.blacklist(targetQq, dur.equals("permanent") ? null : parseDurationSeconds(dur), "QQ ban");
        event.react(true);
        return true;
    }

    // ======================== Admin: unban ========================

    private boolean handleUnban(long adminQq, String text, OneBotEvent event) {
        Matcher m = UNBAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractTargetQq(text, event);
        if (targetQq == 0) { event.react(false); return true; }
        botDb.unblacklist(targetQq);
        event.react(true);
        return true;
    }

    // ======================== Admin: delete ========================

    private void handleAdminDelete(long adminQq, String args, OneBotEvent event) {
        long targetQq = extractTargetQq(args, event);
        if (targetQq == 0) { event.react(false); return; }
        // Extract player ID from args (after @mention or QQ number)
        String playerId = extractPlayer(args);
        if (playerId != null && !playerId.isEmpty()) {
            botDb.removeWhitelist(targetQq, playerId);
        } else {
            // Delete all
            var all = botDb.getWhitelist(targetQq);
            for (String p : all) botDb.removeWhitelist(targetQq, p);
        }
        event.react(true);
    }

    // ======================== Admin: ban list ========================

    private void handleBanList(OneBotEvent event) {
        try {
            var conn = storage.getConnection();
            var ps = conn.prepareStatement(
                    "SELECT qq, reason, banned_at, expiry FROM qq_blacklist ORDER BY banned_at DESC LIMIT 50");
            var rs = ps.executeQuery();
            StringBuilder sb = new StringBuilder("封禁列表:\n");
            int count = 0;
            while (rs.next()) {
                long qq = rs.getLong("qq");
                String reason = rs.getString("reason");
                long expiry = rs.getLong("expiry");
                sb.append(++count).append(". QQ").append(qq);
                if (reason != null && !reason.isEmpty()) sb.append(" (").append(reason).append(")");
                if (expiry > 0 && expiry > System.currentTimeMillis()) {
                    long remain = (expiry - System.currentTimeMillis()) / 1000;
                    sb.append(" [剩余").append(formatDuration(remain)).append("]");
                } else if (expiry == 0) {
                    sb.append(" [永久]");
                }
                sb.append("\n");
            }
            if (count == 0) sb.append("(无)");
            delayReply();
            event.reply(sb.toString());
            event.react(true);
        } catch (Exception e) {
            logger.warning("[BotHandler] Ban list failed: " + e.getMessage());
            event.reply("查询失败");
            event.react(false);
        }
    }

    // ======================== Helpers ========================

    private long extractTargetQq(String text, OneBotEvent event) {
        if (event.raw.has("message")) {
            var arr = event.raw.getAsJsonArray("message");
            for (var seg : arr) {
                var s = seg.getAsJsonObject();
                if ("at".equals(s.get("type").getAsString())) {
                    try { return s.getAsJsonObject("data").get("qq").getAsLong(); }
                    catch (Exception ignored) {}
                }
            }
        }
        Matcher m = Pattern.compile("\\b(\\d{5,})\\b").matcher(text);
        if (m.find()) return Long.parseLong(m.group(1));
        return 0;
    }

    private String extractPlayer(String text) {
        // Remove @mention / QQ numbers, remaining word is player ID
        String cleaned = text.replaceAll("@\\S+", "").replaceAll("\\b\\d{5,}\\b", "").trim();
        if (!cleaned.isEmpty()) return cleaned.split("\\s+")[0];
        return null;
    }

    private String parseDuration(String text) {
        Matcher m = Pattern.compile("(\\d+)\\s*(秒|分钟|小时|天|s|m|h|d|min)", Pattern.CASE_INSENSITIVE).matcher(text);
        long total = 0;
        while (m.find()) {
            long val = Long.parseLong(m.group(1));
            String unit = m.group(2).toLowerCase();
            total += switch (unit) {
                case "秒", "s" -> val;
                case "分钟", "min", "m" -> val * 60;
                case "小时", "h" -> val * 3600;
                case "天", "d" -> val * 86400;
                default -> 0;
            };
        }
        return total > 0 ? String.valueOf(total) : "permanent";
    }

    private long parseDurationSeconds(String dur) {
        try { return Long.parseLong(dur); } catch (Exception e) { return 0; }
    }

    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "秒";
        if (seconds < 3600) return (seconds / 60) + "分钟";
        if (seconds < 86400) return (seconds / 3600) + "小时";
        return (seconds / 86400) + "天";
    }
}
