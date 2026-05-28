package heos.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import heos.Heos;
import heos.storage.StoragePaths;
import heos.utils.HeosLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Heos configuration
 */
public class HeosConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE = "heos_config.json";
    private static final Map<String, String> DEFAULT_COMMENTS = createDefaultComments();
    private static final String[] OBSOLETE_FIELDS = {
            "whitelistKickMessage",
            "enableDebugLogging",
            "logPlayerLogin",
            "logPlayerRegister",
            "logPasswordChange",
            "logAdminActions",
            "banMessageFormat",
            "banIpMessageFormat",
            "passwordPolicy",
            "whitelistNotify",
            "sessionReuseCheck",
            "strictNameCheck",
            "allowCrossProtocolPlayers",
            "crossProtocolKickMessage",
            "enableDiagnosticLogging",
            "diagnosticTpsLogDelayTicks",
            "enableCrossProtocolPacketTrace",
            "crossProtocolPacketTraceLimitPerSecond"
    };

    @SerializedName("_comments")
    public Map<String, String> comments = createDefaultComments();

    // Authentication settings
    public boolean enableAuthentication = true;
    public String language = "zh_cn";
    public boolean allowOfflinePlayers = true;
    public boolean allowMoreOfflineUsernameCharacters = true;
    public boolean separateOnlineOfflineAccounts = true;
    public int loginTimeout = 60; // seconds
    public boolean enablePlayerDataMigration = false;
    public int migrationBanSeconds = 30; // seconds
    public int minPasswordLength = 4;
    public int maxPasswordLength = 32;

    // Whitelist settings
    public boolean enableWhitelist = false;

    // Session limit settings
    public int maxConcurrentSessionsPerIp = -1; // -1 to disable
    public String sessionLimitKickMessage = "The online session limit for this IP has been reached";

    // Login failure protection
    public boolean enableUsernameLoginFailureLock = true;
    public int usernameLoginFailureLimit = 5;
    public int usernameLoginFailureLockSeconds = 30;
    public boolean enableIpLoginFailureLock = false;
    public int ipLoginFailureLimit = 10;
    public int ipLoginFailureLockSeconds = 30;

    // Logging settings
    public boolean enableAutoLogTps = true;
    public int autoLogTpsDelayTicks = 20;
    @SerializedName("\u65e5\u5fd7\u8fc7\u6ee4")
    public boolean enableLogFilter = true;
    public boolean enableRecipeViewerSync = true;

    // Ban settings
    public boolean enableCustomBan = false;

    public static HeosConfig load() {
        File configFile = StoragePaths.file(CONFIG_FILE);
        StoragePaths.ensureRoot();

        if (!configFile.exists()) {
            HeosLogger.info("Config file not found, creating default config at " + configFile.getPath());
            HeosConfig config = new HeosConfig();
            config.save();
            return config;
        }

        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            HeosConfig config = GSON.fromJson(json, HeosConfig.class);
            if (config == null) {
                HeosLogger.warn("Failed to parse config, using default");
                HeosConfig defaultConfig = new HeosConfig();
                defaultConfig.save();
                return defaultConfig;
            }
            boolean commentsChanged = config.refreshComments();
            if (hasObsoleteFields(json) || commentsChanged) {
                config.save();
            }
            HeosLogger.info("Loaded config from " + configFile.getPath());
            return config;
        } catch (IOException e) {
            HeosLogger.error("Failed to load config", e);
            HeosConfig config = new HeosConfig();
            config.save();
            return config;
        }
    }

    public static void migrateLegacyConfig() {
        File legacyFile = new File(Heos.gameDirectory.toFile(), CONFIG_FILE);
        File targetFile = StoragePaths.file(CONFIG_FILE);
        if (!legacyFile.exists() || targetFile.exists()) {
            return;
        }
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (legacyFile.renameTo(targetFile)) {
            HeosLogger.info("Migrated config file to " + targetFile.getPath());
        }
    }

    public void save() {
        File configFile = StoragePaths.file(CONFIG_FILE);
        File parent = configFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            HeosLogger.error("Failed to create config directory: " + parent.getPath());
            return;
        }

        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(this, writer);
            HeosLogger.info("Saved config to " + configFile.getPath());
        } catch (IOException e) {
            HeosLogger.error("Failed to save config", e);
        }
    }

    private static boolean hasObsoleteFields(JsonObject json) {
        for (String field : OBSOLETE_FIELDS) {
            if (json.has(field)) {
                return true;
            }
        }
        return false;
    }

    private boolean refreshComments() {
        if (DEFAULT_COMMENTS.equals(comments)) {
            return false;
        }
        comments = createDefaultComments();
        return true;
    }

    private static Map<String, String> createDefaultComments() {
        Map<String, String> comments = new LinkedHashMap<>();
        comments.put("enableAuthentication", "是否启用登录/注册认证。关闭后玩家无需使用 /login 或 /register。");
        comments.put("language", "语言文件代码，例如 zh_cn 或 en_us。多数提示文本会从对应语言文件读取。");
        comments.put("allowOfflinePlayers", "在线模式服务器中是否允许非正版玩家进入。关闭后只允许通过 Mojang 正版验证的玩家进入。");
        comments.put("allowMoreOfflineUsernameCharacters", "允许离线玩家名称使用更多常规网站用户名字符，例如中文、其他语言字母和数字。关闭后仅允许 A-Z、a-z、0-9、_、+、-、.，长度仍为 3-16。");
        comments.put("separateOnlineOfflineAccounts", "是否分离同名正版账号和离线账号的 Heos 登录数据，避免账号类型切换时覆盖注册信息。");
        comments.put("loginTimeout", "玩家进入服务器后必须完成登录/注册的时间限制，单位为秒。");
        comments.put("enablePlayerDataMigration", "是否启用离线/正版玩家数据迁移功能。只在需要迁移玩家数据时开启。");
        comments.put("migrationBanSeconds", "数据迁移期间临时阻止相关玩家登录的时间，单位为秒。");
        comments.put("minPasswordLength", "注册密码允许的最短长度。");
        comments.put("maxPasswordLength", "注册密码允许的最长长度。");
        comments.put("enableWhitelist", "是否启用 Heos 自带白名单检查。");
        comments.put("maxConcurrentSessionsPerIp", "同一个 IP 最多允许同时在线的玩家数量。-1 表示不限制。");
        comments.put("sessionLimitKickMessage", "同 IP 在线数量超过 maxConcurrentSessionsPerIp 时踢出玩家显示的消息。它是管理员可自定义文案，不会自动跟随语言文件。");
        comments.put("enableUsernameLoginFailureLock", "是否在同一用户名连续登录失败过多时临时锁定该用户名。");
        comments.put("usernameLoginFailureLimit", "触发用户名临时锁定前允许的连续失败次数。");
        comments.put("usernameLoginFailureLockSeconds", "用户名触发失败锁定后的持续时间，单位为秒。");
        comments.put("enableIpLoginFailureLock", "是否在同一 IP 连续登录失败过多时临时锁定该 IP。");
        comments.put("ipLoginFailureLimit", "触发 IP 临时锁定前允许的连续失败次数。");
        comments.put("ipLoginFailureLockSeconds", "IP 触发失败锁定后的持续时间，单位为秒。");
        comments.put("enableAutoLogTps", "是否在玩家列表页脚显示服务器 TPS 信息。");
        comments.put("autoLogTpsDelayTicks", "TPS 信息刷新间隔，单位为 tick。20 tick 约等于 1 秒。");
        comments.put("日志过滤", "是否启用日志过滤，隐藏登录、注册、改密等敏感命令内容。");
        comments.put("enableCustomBan", "是否启用 Heos 自带封禁系统和封禁命令。");
        comments.put("enableRecipeViewerSync", "Enable JEI and REI recipe viewer sync for joined players.");
        return comments;
    }
}
