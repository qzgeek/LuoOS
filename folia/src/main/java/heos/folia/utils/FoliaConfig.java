package heos.folia.utils;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置读取统一入口。
 *
 * 背景：新版配置把设置分组到 setting / account / account-binder / qq_bot /
 * status-card 之下，而代码中大量读取点仍使用旧版扁平键名（如 maintenance、
 * bot.enabled）。若只在个别类里做兼容，其余读取点会静默取到硬编码默认值
 * ——不报错、但配置不生效，极难排查。
 *
 * 因此这里集中维护「旧扁平键 -> 新分组键」映射，任何模块都应通过本类读取配置，
 * 不要再直接调用 getConfig().get*()。
 */
public final class FoliaConfig {

    /** 旧扁平键 -> 新版分组键。新增配置项时必须同步登记。 */
    private static final Map<String, String> ALIASES = buildAliases();

    private static Map<String, String> buildAliases() {
        Map<String, String> a = new LinkedHashMap<>();
        // 基础设置
        a.put("language", "setting.language");
        a.put("debug", "setting.debug");
        // 登录认证
        a.put("enableAuthentication", "account.enableAuthentication");
        a.put("allowMoreOfflineUsernameCharacters", "account.allowMoreOfflineUsernameCharacters");
        a.put("allowOfflinePlayers", "account.allowOfflinePlayers");
        a.put("separateOnlineOfflineAccounts", "account.separateOnlineOfflineAccounts");
        a.put("enableUnprefixedCommandHijack", "account.enableUnprefixedCommandHijack");
        a.put("loginTimeout", "account.loginTimeout");
        a.put("loginReminderSeconds", "account.loginReminderSeconds");
        a.put("minPasswordLength", "account.minPasswordLength");
        a.put("maxPasswordLength", "account.maxPasswordLength");
        a.put("loginBypassNames", "account.loginBypassNames");
        a.put("loginBypassIps", "account.loginBypassIps");
        a.put("allowed_id_chars", "account.allowed_id_chars");
        a.put("max_id_length", "account.max_id_length");
        a.put("enableWhitelist", "account.enableWhitelist");
        // 会话与安全限制
        a.put("maxConcurrentSessionsPerIp", "account.maxConcurrentSessionsPerIp");
        a.put("sessionLimitKickMessage", "account.sessionLimitKickMessage");
        a.put("enableUsernameLoginFailureLock", "account.enableUsernameLoginFailureLock");
        a.put("usernameLoginFailureLimit", "account.usernameLoginFailureLimit");
        a.put("usernameLoginFailureLockSeconds", "account.usernameLoginFailureLockSeconds");
        a.put("enableIpLoginFailureLock", "account.enableIpLoginFailureLock");
        a.put("ipLoginFailureLimit", "account.ipLoginFailureLimit");
        a.put("ipLoginFailureLockSeconds", "account.ipLoginFailureLockSeconds");
        // 账号绑定
        a.put("enableAccountBinding", "account-binder.enable");
        a.put("bindingStorage", "account-binder.bindingStorage");
        a.put("mysql.url", "account-binder.mysql.url");
        a.put("mysql.user", "account-binder.mysql.user");
        a.put("mysql.password", "account-binder.mysql.password");
        // 私人机器人
        a.put("bot.enabled", "qq_bot.private-bot.enable");
        a.put("bot.host", "qq_bot.private-bot.host");
        a.put("bot.port", "qq_bot.private-bot.port");
        a.put("bot.access_token", "qq_bot.private-bot.access_token");
        a.put("bot.qq_groups", "qq_bot.private-bot.qq_groups");
        a.put("bot.max_per_qq", "qq_bot.private-bot.max_per_qq");
        a.put("bot.status_trigger", "qq_bot.private-bot.status_trigger");
        a.put("bot.rate_limit_max", "qq_bot.private-bot.rate-limit-max");
        a.put("bot.rate_limit_window", "qq_bot.private-bot.rate-limit-window");
        a.put("bot.reply_delay_min_ms", "qq_bot.private-bot.reply-delay-min-ms");
        a.put("bot.reply_delay_max_ms", "qq_bot.private-bot.reply-delay-max-ms");
        a.put("bot.debug_log", "qq_bot.private-bot.debug_log");
        a.put("bot.allowed_id_chars", "account.allowed_id_chars");
        a.put("bot.max_id_length", "account.max_id_length");
        // 官方机器人
        a.put("official_qq.enabled", "qq_bot.official-bot.enable");
        a.put("official_qq.app_id", "qq_bot.official-bot.app_id");
        a.put("official_qq.app_secret", "qq_bot.official-bot.app_secret");
        a.put("official_qq.groups", "qq_bot.official-bot.groups");
        a.put("official_qq.code_digits", "qq_bot.official-bot.code_digits");
        a.put("official_qq.code_expire_minutes", "qq_bot.official-bot.code_expire_minutes");
        a.put("official_qq.api_base", "qq_bot.official-bot.api_base");
        a.put("official_qq.token_base", "qq_bot.official-bot.token_base");
        a.put("official_qq.gateway", "qq_bot.official-bot.gateway");
        a.put("official_qq.debug_log", "qq_bot.official-bot.debug_log");
        a.put("official_qq.smtp.host", "qq_bot.official-bot.smtp.host");
        a.put("official_qq.smtp.port", "qq_bot.official-bot.smtp.port");
        a.put("official_qq.smtp.username", "qq_bot.official-bot.smtp.username");
        a.put("official_qq.smtp.password", "qq_bot.official-bot.smtp.password");
        a.put("official_qq.smtp.from", "qq_bot.official-bot.smtp.from");
        a.put("official_qq.smtp.starttls", "qq_bot.official-bot.smtp.starttls");
        // 状态卡片
        a.put("bot.mc_host", "status-card.server-host");
        a.put("bot.mc_port", "status-card.server-port");
        a.put("bot.mc_display_name", "status-card.server-display-name");
        a.put("bot.mc_description", "status-card.server-description");
        a.put("bot.mc_display_ip", "status-card.server-display-ip");
        a.put("bot.motd_bg_mask_alpha", "status-card.motd_bg_mask_alpha");
        // TPS
        a.put("enableAutoLogTps", "enableAutoLogTps");
        a.put("autoLogTpsDelayTicks", "autoLogTpsDelayTicks");
        // 配方同步
        a.put("enableRecipeViewerSync", "enableRecipeViewerSync");
        // 封禁
        a.put("enableCustomBan", "enableCustomBan");
        // 数据迁移
        a.put("enablePlayerDataMigration", "enablePlayerDataMigration");
        a.put("migrationBanSeconds", "migrationBanSeconds");
        // 维护模式
        a.put("maintenance", "maintenance");
        // 资源世界
        a.put("resourceWorld.enabled", "resourceWorld.enabled");
        a.put("resourceWorld.refreshDayOfMonth", "resourceWorld.refreshDayOfMonth");
        a.put("resourceWorld.refreshHour", "resourceWorld.refreshHour");
        a.put("resourceWorld.nether", "resourceWorld.nether");
        a.put("resourceWorld.end", "resourceWorld.end");
        a.put("resourceWorld.currentSeed", "resourceWorld.currentSeed");
        a.put("resourceWorld.nextRefreshTime", "resourceWorld.nextRefreshTime");
        return Map.copyOf(a);
    }

    private FoliaConfig() {
    }

    /** 把旧扁平键解析为实际配置键：优先新分组键，回退旧键，最后才用默认值。 */
    private static String resolve(FileConfiguration config, String key) {
        String grouped = ALIASES.get(key);
        if (grouped != null && config.contains(grouped)) return grouped;
        if (config.contains(key)) return key;
        // 兜底：若分组键与扁平键同名（如顶层功能键），直接使用
        if (grouped != null && grouped.equals(key)) return key;
        return grouped != null ? grouped : key;
    }

    public static String getString(Plugin plugin, String key, String fallback) {
        FileConfiguration c = plugin.getConfig();
        return c.getString(resolve(c, key), fallback);
    }

    public static int getInt(Plugin plugin, String key, int fallback) {
        FileConfiguration c = plugin.getConfig();
        return c.getInt(resolve(c, key), fallback);
    }

    public static boolean getBoolean(Plugin plugin, String key, boolean fallback) {
        FileConfiguration c = plugin.getConfig();
        Object value = c.get(resolve(c, key));
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    public static long getLong(Plugin plugin, String key, long fallback) {
        FileConfiguration c = plugin.getConfig();
        Object value = c.get(resolve(c, key));
        if (value instanceof Number n) return n.longValue();
        try {
            return value == null ? fallback : Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static List<String> getStringList(Plugin plugin, String key) {
        FileConfiguration c = plugin.getConfig();
        List<String> list = c.getStringList(resolve(c, key));
        return list == null ? List.of() : list;
    }

    /** 该配置键是否在文件中真实存在（用于判断用户是否显式配置过）。 */
    public static boolean contains(Plugin plugin, String key) {
        FileConfiguration c = plugin.getConfig();
        return c.contains(resolve(c, key));
    }

    /** 供自检使用：当前登记的全部旧键 -> 新键映射。 */
    public static Map<String, String> aliases() {
        return ALIASES;
    }
}
