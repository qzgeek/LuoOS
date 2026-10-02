package heos.folia.bot;

import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;
import heos.folia.storage.FoliaWhitelistRepository;

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
    private final FoliaWhitelistRepository whitelistRepository;
    private final BotStatusService statusService;
    private SmtpCodeService mail;
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
    private final java.util.Random random = new java.security.SecureRandom();

    boolean debugLog = false;
    public void setDebugLog(boolean d) { this.debugLog = d; }
    /** 邮件通道，用于把重置后的密码发送到用户QQ邮箱；未配置时拒绝改密。 */
    public void setMailService(SmtpCodeService service) { this.mail = service; }

    // --- Patterns ---
    private static final Pattern APPLY = Pattern.compile("^(申请白名单|白名单|添加白名单)\\s*(\\S+)$");
    private static final Pattern DELETE = Pattern.compile("^(删除白名单|移除白名单)\\s+(\\S+)$");
    private static final Pattern QUERY_SIMPLE = Pattern.compile("^(查询白名单|查询|查看|查看白名单)$");
    private static final Pattern QUERY_ARGS  = Pattern.compile("^(查询白名单|查询|查看|查看白名单)\\s+(.+)$");
    private static final Pattern HELP = Pattern.compile("^(help|帮助|命令|菜单|HELP|Help)$");
    private static final Pattern STATUS = Pattern.compile("^(服务器还活着吗|服务器状态)$");
    private static final Pattern BAN_CMD = Pattern.compile("^(封禁|ban)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNBAN_CMD = Pattern.compile("^(解封|unban)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BAN_LIST = Pattern.compile("^(封禁列表|查看封禁列表|banlist|封神榜)$", Pattern.CASE_INSENSITIVE);
    // Admin delete: 删除 @QQ [ID]
    private static final Pattern ADMIN_DELETE = Pattern.compile("^删除\\s+(.+)$");
    // Bot list: 看看人机
    private static final Pattern BOT_LIST = Pattern.compile("^(看看人机|在线人机|人机列表)$");
    // Reset password via QQ private message: 重置密码 <账号名>
    private static final Pattern RESET_PASSWORD = Pattern.compile("^(重置密码)\\s+(\\S+)$");

    // Per-QQ cooldown for password resets (anti-abuse)
    private final Map<Long, Long> lastResetPassword = new ConcurrentHashMap<>();
    private static final long RESET_PASSWORD_COOLDOWN_MS = 60_000L;

    // Password chars without confusables (0/O, 1/l/I)
    private static final String PASSWORD_CHARS = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789";

    // Deny emoji
    private static final int EMOJI_DENY = 15;

    public BotCommandHandler(Logger logger, BotDb botDb, FoliaStorage storage,
                             FoliaWhitelistRepository whitelistRepository, BotStatusService statusService,
                             int maxPerQq, String allowedIdChars, int maxIdLength,
                             long[] allowedGroups, String statusTrigger,
                             int rateMax, int rateWindowSec,
                             int delayMinMs, int delayMaxMs) {
        this.logger = logger;
        this.botDb = botDb;
        this.storage = storage;
        this.whitelistRepository = whitelistRepository;
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
        // 支持 '|' 分隔的多个状态卡片触发词。
        this.statusTrigger = statusTrigger == null ? "" : statusTrigger;
        this.rateMax = rateMax;
        this.rateWindowMs = rateWindowSec * 1000L;
        this.delayMinMs = delayMinMs;
        this.delayMaxMs = delayMaxMs;
    }

    /** 状态卡片触发词匹配（支持 card-cmd 列表拼成的多选）。 */
    private boolean isStatusTrigger(String text) {
        if (text == null || statusTrigger.isEmpty()) return false;
        for (String trigger : statusTrigger.split("\\|")) {
            String value = trigger.trim();
            if (!value.isEmpty() && value.equalsIgnoreCase(text.trim())) return true;
        }
        return false;
    }

    /**
     * 拟人化回复延迟，仅用于传统 OneBot 通道。
     * 官方 QQ 通道不做人为延迟：平台本身有被动回复窗口，额外 sleep 只会让用户觉得慢。
     */
    private void delayReply(OneBotEvent event) {
        if (event != null && event.isOfficial()) return;
        if (delayMaxMs <= 0) return;
        int delay = delayMinMs + random.nextInt(Math.max(1, delayMaxMs - delayMinMs + 1));
        try { Thread.sleep(delay); } catch (InterruptedException ignored) {}
    }

    public void handle(OneBotEvent event) {
        // Notice events (群成员退群/被踢/进群) — whitelist freeze & restore
        if ("notice".equals(event.postType())) {
            handleNotice(event);
            return;
        }
        if (!"message".equals(event.postType()) || !"group".equals(event.messageType())) return;

        long groupId = event.groupId();
        if (!isAllowed(groupId)) return;

        long qq = event.userId();
        String text = event.rawMessage().trim();
        if (text.isEmpty()) return;

        String role = event.senderRole();
        boolean isAdmin = "admin".equals(role) || "owner".equals(role);

        if (debugLog) logger.info("[BotHandler] QQ" + qq + " group=" + groupId + " role=" + role + ": " + text);

        // Global rate limit (admins bypass, silent ignore)
        if (!isAdmin && !checkRate(groupId)) {
            if (event.isOfficial()) event.reply("操作过于频繁，请稍后再试。");
            return;
        }

        // Apply reply delay for recognized commands
        boolean isCommand = (STATUS.matcher(text).matches() || isStatusTrigger(text)) || HELP.matcher(text).matches()
                || BOT_LIST.matcher(text).matches()
                || APPLY.matcher(text).matches() || DELETE.matcher(text).matches()
                || RESET_PASSWORD.matcher(text).matches()
                || QUERY_SIMPLE.matcher(text).matches() || QUERY_ARGS.matcher(text).matches();
        if (isCommand || isAdmin) {
            delayReply(event);
        }

        // --- Status (rate-limited for non-admin) ---
        if (STATUS.matcher(text).matches() || isStatusTrigger(text)) {
            handleStatus(event);
            return;
        }

        // --- Help ---
        if (HELP.matcher(text).matches()) { handleHelp(event); return; }

        // --- Bot list ---
        if (BOT_LIST.matcher(text).matches()) { handleBots(event); return; }

        // 公开状态/菜单/人机列表不需要QQ身份；账号与管理命令必须验证。
        if (event.isOfficial() && (!event.officialIdentityVerified() || qq <= 0)) {
            event.reply("尚未通过邮箱验证。请@机器人发送：绑定QQ <QQ号>，再发送：验证码 <邮箱验证码>。");
            return;
        }

        // --- Admin-only commands ---
        if (isAdmin) {
            // Ban list
            if (BAN_LIST.matcher(text).matches()) { handleBanList(event); return; }
            // Ban
            if (handleBan(qq, text, event)) return;
            // Unban
            if (handleUnban(qq, text, event)) return;
            // Admin delete（只发“删除”时给出用法，而不是当作未知命令）
            Matcher adm = ADMIN_DELETE.matcher(text);
            if (adm.matches()) { handleAdminDelete(qq, adm.group(1), event); return; }
            if (event.isOfficial() && text.equals("删除")) {
                event.missingTarget("删除 <QQ号> [游戏ID]，例如：删除 12345678 玩家名");
                return;
            }
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

        // Reset password (QQ must own the account; new password via private message)
        m = RESET_PASSWORD.matcher(text);
        if (m.matches()) { handleResetPassword(qq, m.group(2).trim(), event); return; }

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
        if (event.isOfficial()) {
            event.reply("未知命令或参数不完整，请发送：帮助。");
        }
    }

    // ======================== Notice events (退群/被踢 → 冻结白名单) ========================

    /**
     * Handle group member count changes:
     *   group_decrease (sub_type: leave/kick) → freeze the member's whitelist
     *   group_increase                       → restore the member's whitelist
     */
    private void handleNotice(OneBotEvent event) {
        long groupId = event.groupId();
        if (!isAllowed(groupId)) return;
        String noticeType = event.noticeType();
        long targetQq = event.userId();
        String subType = event.subType();

        if ("group_decrease".equals(noticeType)) {
            // kick_me = the bot itself was removed — ignore
            if ("kick_me".equals(subType)) return;
            freezeQqWhitelist(targetQq, groupId, event);
        } else if ("group_increase".equals(noticeType)) {
            restoreQqWhitelist(targetQq, groupId, event);
        }
    }

    /** Drop all whitelist entries of a QQ from the server whitelist and mark them frozen. */
    private void freezeQqWhitelist(long qq, long groupId, OneBotEvent event) {
        try {
            List<BotDb.WhitelistEntry> entries = botDb.getWhitelistEntries(qq);
            if (entries.isEmpty()) return;
            org.bukkit.Bukkit.getGlobalRegionScheduler().run(
                    org.bukkit.Bukkit.getPluginManager().getPlugin("luoos"),
                    task -> {
                        for (BotDb.WhitelistEntry e : entries) {
                            try {
                                org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(e.playerName);
                                if (op != null) op.setWhitelisted(false);
                            } catch (Exception ex) {
                                logger.warning("[BotHandler] freeze unwhitelist failed for " + e.playerName + ": " + ex.getMessage());
                            }
                        }
                        // Kick any online players under this QQ — frozen means not allowed to play
                        for (var p : org.bukkit.Bukkit.getOnlinePlayers()) {
                            for (BotDb.WhitelistEntry e : entries) {
                                if (e.playerName.equalsIgnoreCase(p.getName())) {
                                    p.kickPlayer("你已退出QQ群，白名单已被冻结，重新进群后自动恢复。");
                                    break;
                                }
                            }
                        }
                    });
            int n = botDb.freezeWhitelist(qq);
            logger.info("[BotHandler] QQ" + qq + " left/kicked from group " + groupId + " — frozen " + n + " whitelist entries");
            event.sendGroupMessage(groupId, "QQ" + qq + " 已退群/被移出群，其名下 " + n + " 个白名单账号已冻结，重新进群后自动恢复。");
        } catch (Exception e) {
            logger.warning("[BotHandler] freeze failed for QQ" + qq + ": " + e.getMessage());
        }
    }

    /** Restore frozen whitelist entries of a QQ when it rejoins the group. */
    private void restoreQqWhitelist(long qq, long groupId, OneBotEvent event) {
        try {
            List<BotDb.WhitelistEntry> entries = botDb.getWhitelistEntries(qq);
            List<BotDb.WhitelistEntry> frozen = new ArrayList<>();
            for (BotDb.WhitelistEntry e : entries) if (e.frozen) frozen.add(e);
            if (frozen.isEmpty()) return;
            int n = botDb.unfreezeWhitelist(qq);
            org.bukkit.Bukkit.getGlobalRegionScheduler().run(
                    org.bukkit.Bukkit.getPluginManager().getPlugin("luoos"),
                    task -> {
                        for (BotDb.WhitelistEntry e : frozen) {
                            try {
                                org.bukkit.OfflinePlayer op = org.bukkit.Bukkit.getOfflinePlayer(e.playerName);
                                if (op != null) op.setWhitelisted(true);
                            } catch (Exception ex) {
                                logger.warning("[BotHandler] restore whitelist failed for " + e.playerName + ": " + ex.getMessage());
                            }
                        }
                    });
            logger.info("[BotHandler] QQ" + qq + " rejoined group " + groupId + " — restored " + n + " whitelist entries");
            event.sendGroupMessage(groupId, "QQ" + qq + " 已重新进群，其名下 " + n + " 个白名单账号已恢复。");
        } catch (Exception e) {
            logger.warning("[BotHandler] restore failed for QQ" + qq + ": " + e.getMessage());
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
                Runtime rt = Runtime.getRuntime();
                String fallback = "状态图片发送失败，以下为文字状态：\n" + statusService.formatStatusText(
                        statusService.ping(), null, (rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory());
                event.replyImageWithFallback("base64://" + b64, fallback);
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
        boolean official = event.isOfficial();
        StringBuilder sb = new StringBuilder();

        if (official) {
            sb.append("LuoOS Bot 命令帮助（官方QQ通道）\n")
              .append("────────────────────────────\n")
              .append("群内每条命令都需要 @机器人。\n")
              .append("帮助、状态、人机列表无需绑定；\n")
              .append("其余操作需先完成邮箱验证：\n")
              .append("  1. 发送「绑定QQ <QQ号>」\n")
              .append("  2. 收取 <QQ号>@qq.com 的邮件\n")
              .append("  3. 发送「验证码 <验证码>」\n")
              .append("────────────────────────────\n")
              .append("【玩家命令】\n")
              .append("   申请白名单 <游戏ID>   申请白名单\n")
              .append("   删除白名单 <游戏ID>   删除自己的白名单\n")
              .append("   查询白名单            查看自己的白名单\n")
              .append("   查询白名单 <QQ号>     查看指定QQ的白名单\n")
              .append("   重置密码 <账号名>     重置密码（发到QQ邮箱）\n")
              .append("   服务器状态            查看服务器状态卡片\n")
              .append("   看看人机              查看在线人机列表\n")
              .append("   帮助                  显示本帮助\n")
              .append("────────────────────────────\n")
              .append("【管理员命令】\n")
              .append("   封禁 <QQ号> [时长]    封禁用户（如 1h、3天）\n")
              .append("   解封 <QQ号>           解禁用户\n")
              .append("   删除 <QQ号> [游戏ID]  删除该QQ的白名单\n")
              .append("   封禁列表              查看封禁列表\n")
              .append("────────────────────────────\n")
              .append("注意：官方接口不提供被@成员的QQ号，\n")
              .append("      请直接填写QQ号，不要用@。\n")
              .append("────────────────────────────\n")
              .append("Write by 黔中极客 / LuoOS Bot v0.10");
            event.reply(sb.toString());
            event.react(true);
            return;
        }

        // 传统 OneBot 通道
        sb.append("LuoOS Bot 命令帮助\n")
          .append("────────────────────────────\n")
          .append("【玩家命令】\n")
          .append("   申请白名单 <游戏ID>       申请白名单\n")
          .append("   删除白名单 <游戏ID>       删除自己的白名单\n")
          .append("   查询白名单                查看自己的白名单\n")
          .append("   查询白名单 <QQ号/ID>      查看指定对象的白名单\n")
          .append("   重置密码 <账号名>         重置密码（私聊发送新密码）\n")
          .append("   服务器状态                查看服务器状态卡片\n")
          .append("   看看人机                  查看在线人机列表\n")
          .append("   帮助                      显示本帮助\n")
          .append("────────────────────────────\n")
          .append("【管理员命令】\n")
          .append("   封禁 <@某人|QQ号> [时长]  封禁用户（如 1h、3天）\n")
          .append("   解封 <@某人|QQ号>         解禁用户\n")
          .append("   删除 <@某人|QQ号> [ID]    删除该用户的白名单\n")
          .append("   封禁列表                  查看封禁列表\n")
          .append("────────────────────────────\n")
          .append("Write by 黔中极客 / LuoOS Bot v0.10");
        event.reply(sb.toString());
        event.react(true);
    }

    // ======================== Bot list ========================

    private void handleBots(OneBotEvent event) {
        var online = org.bukkit.Bukkit.getOnlinePlayers();
        List<String> bots = new ArrayList<>();
        for (var p : online) {
            String name = p.getName();
            // Identify bots by common prefixes
            if (name.startsWith("BOT_") || name.startsWith("bot_") || name.startsWith("Bot_")
                    || name.startsWith("假人_") || name.contains("[Bot]")) {
                bots.add(name);
            }
        }
        if (bots.isEmpty()) {
            event.replyAt("当前没有在线的人机。");
        } else {
            event.replyAt("在线人机 (" + bots.size() + "):\n" + String.join("\n", bots));
        }
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
        if (botDb.isNameTaken(playerId)) {
            logger.info("[BotHandler] handleApply: name already taken by another QQ: " + playerId);
            event.replyAt("该游戏 ID \"" + playerId + "\" 已被其他人申请，请更换 ID 后重试。");
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
        if (!botDb.addWhitelist(qq, playerId, uuid)) {
            event.replyAt("白名单写入失败，请稍后重试。");
            event.react(false);
            return;
        }
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
        if (!botDb.removeWhitelist(qq, playerId)) {
            event.reply("删除失败：记录不存在或数据库异常。"); return;
        }
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

    // ======================== Password reset via QQ ========================

    /**
     * 重置密码 <账号名> — the QQ must own the account (qq_whitelist binding).
     *
     * 两个通道的送达方式不同：
     *   官方QQ：官方接口不支持可靠的私聊投递（且群/单聊 OpenID 不通用），
     *           因此把新密码发送到 <QQ号>@qq.com 邮箱。
     *   传统QQ：沿用私聊发送（OneBot 的临时会话可直接送达）。
     *
     * 两种方式都必须确认送达成功后才算完成；投递失败一律回滚密码，
     * 避免玩家出现「密码已改但收不到新密码」而被锁死。
     * 密码不会出现在群消息或日志中。
     */
    private void handleResetPassword(long qq, String accountName, OneBotEvent event) {
        if (botDb.isBlacklisted(qq)) { event.reactDeny(); return; }
        if (!idPattern.matcher(accountName).matches()) { event.react(false); return; }

        // Per-QQ cooldown to prevent abuse
        long now = System.currentTimeMillis();
        Long last = lastResetPassword.get(qq);
        if (last != null && now - last < RESET_PASSWORD_COOLDOWN_MS) {
            event.reactDeny();
            return;
        }

        // Verify the QQ actually owns this account (case-insensitive)
        String ownedName = findOwnedAccountName(qq, accountName);
        if (ownedName == null) {
            logger.info("[BotHandler] reset-password denied: QQ" + qq + " does not own '" + accountName + "'");
            event.react(false);
            return;
        }

        var data = storage.load(ownedName);
        if (data == null || !data.isRegistered()) {
            logger.info("[BotHandler] reset-password: account '" + ownedName + "' not registered");
            event.react(false);
            return;
        }

        boolean official = event.isOfficial();
        // 各自检查所需通道是否可用，避免改完密码才发现送不出去
        if (official && mail == null) {
            logger.warning("[BotHandler] reset-password: SMTP not configured, refusing to reset");
            event.reply("重置失败：邮件服务未配置，密码未修改。请联系管理员。");
            event.react(false);
            return;
        }

        lastResetPassword.put(qq, now);

        String oldHash = data.passwordHash;
        String newPassword = generatePassword(12);
        data.passwordHash = heos.folia.utils.FoliaPasswordHasher.hashPassword(newPassword);
        storage.save(data);

        final FoliaPlayerData savedData = data;
        final String displayName = data.effectiveDisplayName();
        final String oldHashFinal = oldHash;

        if (official) {
            // 官方QQ：邮件送达
            try {
                mail.sendPasswordReset(qq + "@qq.com", displayName, newPassword);
                logger.info("[BotHandler] QQ" + qq + " reset password for '" + ownedName + "' (sent via email)");
                event.replyAt("账号 [" + displayName + "] 密码已重置，新密码已发送到 " + qq + "@qq.com，请查收。");
                event.react(true);
            } catch (Exception e) {
                savedData.passwordHash = oldHashFinal;
                storage.save(savedData);
                logger.warning("[BotHandler] reset-password: email to QQ" + qq + " FAILED — rolled back");
                event.replyAt("重置失败：无法发送邮件到 " + qq + "@qq.com，密码未修改，请稍后重试。");
                event.react(false);
            }
            return;
        }

        // 传统QQ：私聊送达
        event.sendPrivateThen(qq,
                "你的 LuoOS 账号 [" + displayName + "] 密码已重置。\n"
                        + "新密码: " + newPassword + "\n"
                        + "登录: 游戏内输入 /login " + newPassword + "\n"
                        + "改密: 登录后请尽快用 /changepassword <旧密码> <新密码> 修改",
                sent -> {
                    if (sent) {
                        logger.info("[BotHandler] QQ" + qq + " reset password for '" + ownedName + "' (sent via private msg)");
                        event.replyAt("账号 [" + displayName + "] 密码已重置，新密码已通过私聊发送，请查收。");
                        event.react(true);
                    } else {
                        savedData.passwordHash = oldHashFinal;
                        storage.save(savedData);
                        logger.warning("[BotHandler] reset-password: private msg to QQ" + qq + " FAILED — rolled back");
                        event.replyAt("重置失败：无法向你的QQ发送私聊消息，请检查是否开启了「允许陌生人私聊」。");
                        event.react(false);
                    }
                });
    }

    /** Case-insensitive lookup of the exact stored account name owned by this QQ. */
    private String findOwnedAccountName(long qq, String accountName) {
        return whitelistRepository.findOwnedAccountName(qq, accountName);
    }

    /** Random password from unambiguous chars (no 0/O, 1/l/I). */
    private String generatePassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * query(null)  = show own whitelist
     * query(QQ#)   = show that QQ's whitelist
     * query("name") = show which QQ owns that game ID (reverse lookup)
     */
    private void handleQuery(long qq, String arg, OneBotEvent event) {
        if (arg == null || arg.isEmpty()) {
            showWhitelist(qq, "你", event);
            return;
        }

        // Try extract QQ from @mention first
        long targetQq = extractTargetQq(arg, event);
        if (targetQq > 0) {
            showWhitelist(targetQq, "QQ" + targetQq, event);
            return;
        }

        // Check if arg is wrapped in quotes (Chinese or English) → treat as player ID
        boolean isQuoted = arg.matches("^[\"'\\u201c\\u201d\\u2018\\u2019].*[\"'\\u201c\\u201d\\u2018\\u2019]$");

        // Try numeric (only if not quoted)
        if (!isQuoted) {
            try {
                targetQq = Long.parseLong(arg.replaceAll("[^0-9]", ""));
                if (targetQq > 10000) {
                    showWhitelist(targetQq, "QQ" + targetQq, event);
                    return;
                }
            } catch (NumberFormatException ignored) {}
        }

        // Strip CQ codes and quotes
        String name = arg.replaceAll("\\[CQ:[^]]+\\]", "").trim();
        name = name.replaceAll("^[\"'\\u201c\\u201d\\u2018\\u2019]", "").replaceAll("[\"'\\u201c\\u201d\\u2018\\u2019]$", "");

        // Reverse lookup is owned by the repository; the bot only formats it.
        List<String> found = new ArrayList<>();
        for (FoliaWhitelistRepository.WhitelistOwner owner : whitelistRepository.findOwners(name)) {
            String uid = owner.playerUuid;
            found.add((owner.qq == FoliaWhitelistRepository.ADMIN_SOURCE_QQ ? "管理员" : "QQ" + owner.qq)
                    + (owner.frozen ? " (已冻结)" : "")
                    + (uid != null && !uid.isEmpty() ? " (UUID:" + uid.substring(0, Math.min(8, uid.length())) + "...)" : ""));
        }
        if (found.isEmpty()) event.replyAt("未找到 " + name + " 的白名单记录");
        else event.replyAt("游戏ID " + name + " 的绑定信息:\n" + String.join("\n", found));
        event.react(true);
    }

    private void showWhitelist(long targetQq, String label, OneBotEvent event) {
        var entries = botDb.getWhitelistEntries(targetQq);
        int count = entries.size();
        if (entries.isEmpty()) {
            event.replyAt(label + "还没有添加白名单 (0/" + maxPerQq + ")");
        } else {
            StringBuilder sb = new StringBuilder(label + "的白名单 (" + count + "/" + maxPerQq + "):\n");
            for (int i = 0; i < entries.size(); i++) {
                BotDb.WhitelistEntry e = entries.get(i);
                sb.append(i + 1).append(". ").append(e.playerName);
                if (e.frozen) sb.append(" (已冻结)");
                sb.append("\n");
            }
            event.replyAt(sb.toString());
        }
        event.react(true);
    }

    // ======================== Admin: ban ========================

    private boolean handleBan(long adminQq, String text, OneBotEvent event) {
        Matcher m = BAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractTargetQq(text, event);
        if (targetQq == 0) { event.missingTarget("封禁 <QQ号> [时长]，例如：封禁 12345678 1h"); return true; }
        String dur = parseDuration(text);
        event.react(botDb.blacklist(targetQq, dur.equals("permanent") ? null : parseDurationSeconds(dur), "QQ ban"));
        return true;
    }

    // ======================== Admin: unban ========================

    private boolean handleUnban(long adminQq, String text, OneBotEvent event) {
        Matcher m = UNBAN_CMD.matcher(text);
        if (!m.matches()) return false;
        long targetQq = extractTargetQq(text, event);
        if (targetQq == 0) { event.missingTarget("解封 <QQ号>，例如：解封 12345678"); return true; }
        event.react(botDb.unblacklist(targetQq));
        return true;
    }

    // ======================== Admin: delete ========================

    private void handleAdminDelete(long adminQq, String args, OneBotEvent event) {
        long targetQq = extractTargetQq(args, event);
        if (targetQq == 0) { event.missingTarget("删除 <QQ号> [游戏ID]，例如：删除 12345678 玩家名"); return; }
        // Extract player ID from args (after @mention or QQ number)
        String playerId = args.replaceAll("\\[CQ:at,[^]]+\\]", "").replaceAll("@\\S+", "").trim();
        playerId = playerId.replaceFirst("^" + targetQq + "(?:\\s+|$)", "").trim();
        if (!playerId.isEmpty()) {
            if (!idPattern.matcher(playerId).matches()) { event.reply("游戏ID格式错误，未删除任何记录。"); return; }
            event.react(botDb.removeWhitelist(targetQq, playerId));
        } else {
            var all = botDb.getWhitelist(targetQq);
            if (all.isEmpty()) { event.reply("该QQ没有可删除的白名单。"); return; }
            int deleted = 0;
            for (String name : all) if (botDb.removeWhitelist(targetQq, name)) deleted++;
            event.reply("已删除 " + deleted + "/" + all.size() + " 条白名单。"
                    + (deleted == all.size() ? "" : "部分删除失败，请检查数据库后重试。"));
            event.react(deleted == all.size());
        }
    }

    // ======================== Admin: ban list ========================

    private void handleBanList(OneBotEvent event) {
        try {
            StringBuilder sb = new StringBuilder("封禁列表:\n");
            int count = 0;
            for (FoliaWhitelistRepository.BlacklistEntry entry : whitelistRepository.getBlacklistEntries(50)) {
                sb.append(++count).append(". QQ").append(entry.qq);
                if (entry.reason != null && !entry.reason.isEmpty()) sb.append(" (").append(entry.reason).append(")");
                if (entry.expiry != null && entry.expiry > System.currentTimeMillis()) {
                    sb.append(" [剩余").append(formatDuration((entry.expiry - System.currentTimeMillis()) / 1000)).append("]");
                } else if (entry.expiry == null || entry.expiry == 0) {
                    sb.append(" [永久]");
                }
                sb.append("\n");
            }
            if (count == 0) sb.append("(无)");
            delayReply(event);
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
        if (m.find()) {
            try { return Long.parseLong(m.group(1)); } catch (NumberFormatException ignored) { return 0; }
        }
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
