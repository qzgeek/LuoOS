package heos.folia;

import heos.folia.commands.FoliaAdminCommands;
import heos.folia.commands.FoliaAuthCommands;
import heos.folia.commands.FoliaBanCommands;
import heos.folia.commands.FoliaBindCommands;
import heos.folia.commands.FoliaBindUI;
import heos.folia.commands.FoliaMigrationCommands;
import heos.folia.bot.OneBotServer;
import heos.folia.bot.BotCommandHandler;
import heos.folia.bot.BotStatusService;
import heos.folia.bot.BotDb;
import heos.folia.bot.OfficialQQBot;
import heos.folia.bot.SmtpCodeService;
import heos.folia.event.FoliaAuthListener;
import heos.folia.event.FoliaAuthService;
import heos.folia.event.FoliaCommandInterceptor;
import heos.folia.integrations.FoliaRecipeSyncService;
import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaBanData;
import heos.folia.storage.FoliaStorage;
import heos.folia.storage.FoliaWhitelistData;
import heos.folia.storage.FoliaWhitelistRepository;
import heos.folia.utils.FoliaLoginUsernameValidationBypassService;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.utils.FoliaTpsDisplayService;
import heos.folia.utils.ResourceWorldManager;
import heos.folia.utils.PlayerStatsTracker;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

public final class HeosFoliaPlugin extends JavaPlugin {
    private FoliaAuthService authService;
    private FoliaStorage storage;
    private FoliaBanData banData;
    private FoliaWhitelistData whitelistData;
    private FoliaWhitelistRepository whitelistRepository;
    private FoliaAccountBinding accountBinding;
    private FoliaNameResolver nameResolver;
    private FoliaTpsDisplayService tpsDisplayService;
    private FoliaRecipeSyncService recipeSyncService;
    private FoliaLoginUsernameValidationBypassService bypassService;
    private OneBotServer botServer;
    private OfficialQQBot officialQQBot;
    public BotStatusService statusService;
    private ResourceWorldManager resourceWorldManager;
    public OneBotServer getBotServer() { return botServer; }
    PlayerStatsTracker statsTracker;

    /**
     * 私人（OneBot）机器人是否启用。
     * 需要总开关 qq_bot.enable 与通道开关 qq_bot.private-bot.enable 同时为真；
     * 兼容旧配置中直接用 bot.enabled 控制 OneBot 的写法。
     */
    private boolean privateBotEnabled() {
        if (!cfgBool("qq_bot.enable", null, true)) return false;
        return cfgBool("qq_bot.private-bot.enable", "bot.enabled", false);
    }

    /** 官方QQ机器人是否启用（总开关 + 通道开关）。 */
    private boolean officialBotEnabled() {
        return cfgBool("qq_bot.enable", null, true)
                && cfgBool("qq_bot.official-bot.enable", "official_qq.enabled", false);
    }

    /**
     * 状态卡片触发词。传统机器人与官方机器人各自独立配置：
     *   qq_bot.private-bot.card-cmd / qq_bot.official-bot.card-cmd
     * 兼容旧键 bot.card-cmd 与 bot.status_trigger。
     */
    private String statusTriggerFor(String channel) {
        java.util.List<String> commands = cfgStringList("qq_bot." + channel + ".card-cmd", "bot." + channel + ".card-cmd");
        if (commands.isEmpty()) commands = cfgStringList("bot.card-cmd", null);
        if (!commands.isEmpty()) {
            String joined = String.join("|", commands);
            getLogger().info("状态卡片触发词[" + channel + "]：" + joined);
            return joined;
        }
        String legacy = cfgString("qq_bot." + channel + ".status_trigger", "bot.status_trigger", "");
        return legacy.isBlank() ? "服务器还活着吗" : legacy;
    }

    /**
     * 新版配置把设置分组到 setting/account/account-binder/qq_bot/status-card 下，
     * 而插件内部仍按旧扁平键读取。这里为新旧两组键建立别名，
     * 两种写法都可用，避免升级后出现“键读不到、功能静默失效”。
     */
    private static final Map<String, String> CONFIG_ALIASES = createConfigAliases();

    private static Map<String, String> createConfigAliases() {
        Map<String, String> a = new java.util.LinkedHashMap<>();
        a.put("language", "setting.language");
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
        a.put("enableAccountBinding", "account-binder.enable");
        a.put("bindingStorage", "account-binder.bindingStorage");
        a.put("mysql.url", "account-binder.mysql.url");
        a.put("mysql.user", "account-binder.mysql.user");
        a.put("mysql.password", "account-binder.mysql.password");
        a.put("bot.enabled", "qq_bot.private-bot.enable");
        a.put("bot.host", "qq_bot.private-bot.host");
        a.put("bot.port", "qq_bot.private-bot.port");
        a.put("bot.access_token", "qq_bot.private-bot.access_token");
        a.put("bot.qq_groups", "qq_bot.private-bot.qq_groups");
        a.put("bot.max_per_qq", "qq_bot.private-bot.max_per_qq");
        a.put("bot.allowed_id_chars", "account.allowed_id_chars");
        a.put("bot.max_id_length", "account.max_id_length");
        a.put("bot.status_trigger", "qq_bot.private-bot.status_trigger");
        a.put("bot.rate_limit_max", "qq_bot.private-bot.rate-limit-max");
        a.put("bot.rate_limit_window", "qq_bot.private-bot.rate-limit-window");
        a.put("bot.reply_delay_min_ms", "qq_bot.private-bot.reply-delay-min-ms");
        a.put("bot.reply_delay_max_ms", "qq_bot.private-bot.reply-delay-max-ms");
        a.put("bot.debug_log", "qq_bot.private-bot.debug_log");
        a.put("bot.mc_host", "status-card.server-host");
        a.put("bot.mc_port", "status-card.server-port");
        a.put("bot.mc_display_name", "status-card.server-display-name");
        a.put("bot.mc_description", "status-card.server-description");
        a.put("bot.mc_display_ip", "status-card.server-display-ip");
        a.put("bot.motd_bg_mask_alpha", "status-card.motd_bg_mask_alpha");
        a.put("official_qq.enabled", "qq_bot.official-bot.enable");
        a.put("official_qq.app_id", "qq_bot.official-bot.app_id");
        a.put("official_qq.app_secret", "qq_bot.official-bot.app_secret");
        a.put("official_qq.groups", "qq_bot.official-bot.groups");
        a.put("official_qq.code_digits", "qq_bot.official-bot.code_digits");
        a.put("official_qq.code_expire_minutes", "qq_bot.official-bot.code_expire_minutes");
        a.put("official_qq.api_base", "qq_bot.official-bot.api_base");
        a.put("official_qq.token_base", "qq_bot.official-bot.token_base");
        a.put("official_qq.gateway", "qq_bot.official-bot.gateway");
        a.put("official_qq.debug_log", "qq_bot.private-bot.debug_log");
        a.put("bot.official_card-cmd", "qq_bot.official-bot.card-cmd");
        a.put("official_qq.smtp.host", "qq_bot.official-bot.smtp.host");
        a.put("official_qq.smtp.port", "qq_bot.official-bot.smtp.port");
        a.put("official_qq.smtp.username", "qq_bot.official-bot.smtp.username");
        a.put("official_qq.smtp.password", "qq_bot.official-bot.smtp.password");
        a.put("official_qq.smtp.from", "qq_bot.official-bot.smtp.from");
        a.put("official_qq.smtp.starttls", "qq_bot.official-bot.smtp.starttls");
        return java.util.Collections.unmodifiableMap(a);
    }

    /**
     * 读取配置项，同时兼容新版分组键与旧版扁平键。
     *
     * 不使用 setDefaults/MemoryConfiguration：Bukkit 的默认值视图在嵌套路径上
     * 不可靠（曾导致 port 回落到硬编码默认值、app_secret 读成空串），
     * 因此这里显式按顺序取值，分组键优先、扁平键兜底。
     */
    private Object configValue(String grouped, String flat) {
        if (grouped != null && getConfig().contains(grouped)) return getConfig().get(grouped);
        if (flat != null && getConfig().contains(flat)) return getConfig().get(flat);
        return null;
    }

    private String cfgString(String grouped, String flat, String fallback) {
        Object value = configValue(grouped, flat);
        return value == null ? fallback : String.valueOf(value);
    }

    private int cfgInt(String grouped, String flat, int fallback) {
        Object value = configValue(grouped, flat);
        if (value instanceof Number n) return n.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value).trim()); }
        catch (NumberFormatException e) { return fallback; }
    }

    private boolean cfgBool(String grouped, String flat, boolean fallback) {
        Object value = configValue(grouped, flat);
        if (value instanceof Boolean b) return b;
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    /**
     * 按发件邮箱域名推断 SMTP 主机，作为配置缺失时的兜底。
     * 曾出现默认值硬编码 smtp.qq.com，而账号是 126 邮箱，导致认证 535。
     */
    private static String defaultSmtpHost(String username) {
        String domain = username == null ? "" : username.substring(username.indexOf('@') + 1).toLowerCase();
        return switch (domain) {
            case "", "qq.com" -> "smtp.qq.com";
            default -> "smtp." + domain;
        };
    }

    private long[] cfgLongList(String grouped, String flat) {
        for (String key : new String[]{grouped, flat}) {
            if (key != null && getConfig().contains(key)) {
                java.util.List<Long> list = getConfig().getLongList(key);
                if (list != null && !list.isEmpty()) {
                    long[] out = new long[list.size()];
                    for (int i = 0; i < out.length; i++) out[i] = list.get(i);
                    return out;
                }
            }
        }
        return new long[0];
    }

    private java.util.List<String> cfgStringList(String grouped, String flat) {
        for (String key : new String[]{grouped, flat}) {
            if (key != null && getConfig().contains(key)) {
                java.util.List<String> list = getConfig().getStringList(key);
                if (list != null && !list.isEmpty()) return list;
            }
        }
        return java.util.List.of();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();


        heos.folia.utils.FoliaMessages.init(this);
        heos.folia.utils.FoliaLogFilterService.installConfiguredFilters(this);

        // Storage with optional MySQL
        this.storage = new FoliaStorage(getDataFolder().toPath());
        String bindingStorage = cfgString("account-binder.bindingStorage", "bindingStorage", "sqlite");
        if ("mysql".equalsIgnoreCase(bindingStorage)) {
            String url = cfgString("account-binder.mysql.url", "mysql.url", "");
            String user = cfgString("account-binder.mysql.user", "mysql.user", "");
            String pass = cfgString("account-binder.mysql.password", "mysql.password", "");
            if (!url.isEmpty()) {
                storage.configureMySQL(url, user, pass);
                getLogger().info("Account binding storage: MySQL");
            }
        }
        storage.initialize();

        this.banData = FoliaBanData.load(getDataFolder().toPath(), getLogger());
        this.whitelistData = FoliaWhitelistData.load(getDataFolder().toPath(), getLogger());
        this.whitelistRepository = new FoliaWhitelistRepository(storage, getLogger());
        this.nameResolver = new FoliaNameResolver(storage);
        this.accountBinding = new FoliaAccountBinding(storage, getLogger());
        this.tpsDisplayService = new FoliaTpsDisplayService(this);
        this.authService = new FoliaAuthService(this, storage, nameResolver, accountBinding, tpsDisplayService);

        FoliaBanCommands banCommands = new FoliaBanCommands(banData, nameResolver);
        new heos.folia.utils.FoliaBanCleanupService(this, banData);

        FoliaMigrationCommands migrationCommands = new FoliaMigrationCommands(this, storage, banData, nameResolver);
        FoliaBindUI bindUI = new FoliaBindUI(storage, this);
        getServer().getPluginManager().registerEvents(bindUI, this);
        FoliaBindCommands bindCommands = new FoliaBindCommands(accountBinding, storage, bindUI);

        // Player stats tracker
        this.statsTracker = new PlayerStatsTracker(this, storage);
        getServer().getPluginManager().registerEvents(
                new heos.folia.event.PlayerStatsListener(statsTracker), this);

        // Auto-upgrade schema from old LuoOS versions
        runLegacyUpgrade();

        // Resource world manager
        this.resourceWorldManager = new ResourceWorldManager(this);

        FoliaAdminCommands adminCommands = new FoliaAdminCommands(this, storage, whitelistData, whitelistRepository,
                migrationCommands, authService, banCommands, bindCommands, resourceWorldManager, statsTracker);
        getServer().getPluginManager().registerEvents(
                new FoliaCommandInterceptor(this, authService, banCommands), this);
        getServer().getPluginManager().registerEvents(
                new FoliaAuthListener(this, authService, banData, whitelistData, storage, whitelistRepository), this);
        registerCommands(banCommands, adminCommands);

        if (isRecipeViewerSyncEnabled()) {
            this.recipeSyncService = new FoliaRecipeSyncService(this);
        }

        this.bypassService = new FoliaLoginUsernameValidationBypassService(
                this, banData, whitelistData, accountBinding, storage, whitelistRepository);
        bypassService.install();

        // OneBot QQ bot：以 qq_bot.private-bot.enable 为准，兼容旧键 bot.enabled。
        if (privateBotEnabled()) {
            String botHost = cfgString("qq_bot.private-bot.host", "bot.host", "0.0.0.0");
            int botPort = cfgInt("qq_bot.private-bot.port", "bot.port", 35013);
            String botToken = cfgString("qq_bot.private-bot.access_token", "bot.access_token", "");
            long[] groups = cfgLongList("qq_bot.private-bot.qq_groups", "bot.qq_groups");
            int maxPerQq = cfgInt("qq_bot.private-bot.max_per_qq", "bot.max_per_qq", 3);
            String idChars = cfgString("account.allowed_id_chars", "bot.allowed_id_chars", "a-zA-Z0-9_-");
            int maxIdLen = cfgInt("account.max_id_length", "bot.max_id_length", 16);

            String statusTrigger = statusTriggerFor("private-bot");
            int rateMax = cfgInt("qq_bot.private-bot.rate-limit-max", "bot.rate_limit_max", 3);
            int rateWindow = cfgInt("qq_bot.private-bot.rate-limit-window", "bot.rate_limit_window", 60);
            int delayMin = cfgInt("qq_bot.private-bot.reply-delay-min-ms", "bot.reply_delay_min_ms", 1000);
            int delayMax = cfgInt("qq_bot.private-bot.reply-delay-max-ms", "bot.reply_delay_max_ms", 2000);

            String mcHost = cfgString("status-card.server-host", "bot.mc_host", "127.0.0.1");
            int mcPort = cfgInt("status-card.server-port", "bot.mc_port", 25565);
            String mcName = cfgString("status-card.server-display-name", "bot.mc_display_name", "LuoOS服务器");
            String mcDesc = cfgString("status-card.server-description", "bot.mc_description", "欢迎来到LuoOS");
            String mcDisplayIp = cfgString("status-card.server-display-ip", "bot.mc_display_ip", mcHost + ":" + mcPort);
            BotStatusService statusService = new BotStatusService(getLogger(), mcHost, mcPort, mcName, mcDesc,
                    mcDisplayIp, getDataFolder(), cfgInt("status-card.motd_bg_mask_alpha", "bot.motd_bg_mask_alpha", 140));
            this.statusService = statusService;

            BotDb botDb = new BotDb(getLogger(), whitelistRepository);
            try {
                BotCommandHandler botHandler = new BotCommandHandler(
                        getLogger(), botDb, storage, whitelistRepository, statusService,
                        maxPerQq, idChars, maxIdLen, groups,
                        statusTrigger, rateMax, rateWindow,
                        delayMin, delayMax);

                botServer = new OneBotServer(getLogger(), botHost, botPort, botToken);
                boolean botDebug = cfgBool("qq_bot.private-bot.debug_log", "bot.debug_log", false);
                botServer.setDebugLog(botDebug);
                botHandler.setDebugLog(botDebug);
                botHandler.setMailService(new SmtpCodeService(
                        cfgString("qq_bot.official-bot.smtp.host", "official_qq.smtp.host", defaultSmtpHost(cfgString("qq_bot.official-bot.smtp.username", "official_qq.smtp.username", ""))),
                        cfgInt("qq_bot.official-bot.smtp.port", "official_qq.smtp.port", 465),
                        cfgString("qq_bot.official-bot.smtp.username", "official_qq.smtp.username", ""),
                        cfgString("qq_bot.official-bot.smtp.password", "official_qq.smtp.password", ""),
                        cfgString("qq_bot.official-bot.smtp.from", "official_qq.smtp.from",
                                cfgString("qq_bot.official-bot.smtp.username", "official_qq.smtp.username", "")),
                        cfgBool("qq_bot.official-bot.smtp.starttls", "official_qq.smtp.starttls", false)));
                botServer.setEventHandler(event -> botHandler.handle(event));
                new Thread(botServer::startServer, "LuoOS-Bot").start();
                getLogger().info("OneBot server started on ws://" + botHost + ":" + botPort);
            } catch (Exception e) {
                getLogger().severe("Failed to start OneBot server: " + e.getMessage());
                e.printStackTrace();
            }
        }

        if (officialBotEnabled()) {
            try {
                String smtpUser = cfgString("qq_bot.official-bot.smtp.username", "official_qq.smtp.username", "");
                SmtpCodeService smtp = new SmtpCodeService(
                        cfgString("qq_bot.official-bot.smtp.host", "official_qq.smtp.host", defaultSmtpHost(smtpUser)),
                        cfgInt("qq_bot.official-bot.smtp.port", "official_qq.smtp.port", 465), smtpUser,
                        cfgString("qq_bot.official-bot.smtp.password", "official_qq.smtp.password", ""),
                        cfgString("qq_bot.official-bot.smtp.from", "official_qq.smtp.from", smtpUser),
                        cfgBool("qq_bot.official-bot.smtp.starttls", "official_qq.smtp.starttls", false));
                if (statusService == null) {
                    String mcHost = cfgString("status-card.server-host", "bot.mc_host", "127.0.0.1");
                    int mcPort = cfgInt("status-card.server-port", "bot.mc_port", 25565);
                    statusService = new BotStatusService(getLogger(), mcHost, mcPort,
                            cfgString("status-card.server-display-name", "bot.mc_display_name", "LuoOS服务器"),
                            cfgString("status-card.server-description", "bot.mc_description", "欢迎来到LuoOS"),
                            cfgString("status-card.server-display-ip", "bot.mc_display_ip", mcHost + ":" + mcPort),
                            getDataFolder(), cfgInt("status-card.motd_bg_mask_alpha", "bot.motd_bg_mask_alpha", 140));
                }
                BotDb botDb = new BotDb(getLogger(), whitelistRepository);
                BotCommandHandler handler = new BotCommandHandler(getLogger(), botDb, storage, whitelistRepository,
                        statusService, cfgInt("qq_bot.private-bot.max_per_qq", "bot.max_per_qq", 3),
                        cfgString("account.allowed_id_chars", "bot.allowed_id_chars", "a-zA-Z0-9_-"),
                        cfgInt("account.max_id_length", "bot.max_id_length", 16), new long[0],
                        // 官Q与传统机器人共用同一份状态卡片触发词。
                        // 官Q不做人为延迟。
                        statusTriggerFor("official-bot"), cfgInt("qq_bot.private-bot.rate-limit-max", "bot.rate_limit_max", 3),
                        cfgInt("qq_bot.private-bot.rate-limit-window", "bot.rate_limit_window", 60), 0, 0);
                handler.setDebugLog(cfgBool("qq_bot.official-bot.debug_log", "official_qq.debug_log", false));
                handler.setMailService(smtp);
                officialQQBot = new OfficialQQBot(getLogger(), cfgString("qq_bot.official-bot.app_id", "official_qq.app_id", ""),
                        cfgString("qq_bot.official-bot.app_secret", "official_qq.app_secret", ""),
                        cfgString("qq_bot.official-bot.api_base", "official_qq.api_base", "https://api.bot.qq.com"),
                        cfgString("qq_bot.official-bot.token_base", "official_qq.token_base", "https://api.bot.qq.com"),
                        cfgString("qq_bot.official-bot.gateway", "official_qq.gateway", ""), whitelistRepository, smtp,
                        cfgInt("qq_bot.official-bot.code_digits", "official_qq.code_digits", 6),
                        cfgInt("qq_bot.official-bot.code_expire_minutes", "official_qq.code_expire_minutes", 10),
                        // 官Q不限制群聊：groups 留空即允许所有群，避免平台OpenID变动导致失效。
                        java.util.Set.of(), handler::handle);
                officialQQBot.debugLog = cfgBool("qq_bot.official-bot.debug_log", "official_qq.debug_log", false);
                new Thread(officialQQBot::start, "LuoOS-Official-QQ").start();
            } catch (Exception e) { getLogger().severe("Failed to start official QQ bot: " + e.getMessage()); }
        }

        // Auto-detect and PERMANENTLY disable AuthMe to prevent authentication conflicts.
        // LuoOS provides its own complete auth system (login/register/password management).
        // Having both AuthMe and LuoOS enabled causes duplicate login/register prompts
        // and inconsistent authentication state.
        if (cfgBool("account.enableAuthentication", "enableAuthentication", true)) {
            org.bukkit.plugin.Plugin authMe = getServer().getPluginManager().getPlugin("AuthMe");
            if (authMe != null && authMe.isEnabled()) {
                getLogger().warning("========================================");
                getLogger().warning("AuthMe detected! LuoOS provides its own authentication system.");
                getLogger().warning("AuthMe will be permanently disabled to prevent conflicts.");
                getLogger().warning("Players should use /los login and /los register instead.");
                getLogger().warning("========================================");

                // Unload AuthMe from the current session
                getServer().getPluginManager().disablePlugin(authMe);

                // Rename AuthMe jar to prevent it from loading on next restart
                java.io.File pluginsDir = getDataFolder().getParentFile();
                java.io.File[] authMeJars = pluginsDir.listFiles((dir, name) ->
                        name.startsWith("AuthMe") && name.endsWith(".jar"));
                if (authMeJars != null) {
                    for (java.io.File jar : authMeJars) {
                        java.io.File disabled = new java.io.File(jar.getParentFile(), jar.getName() + ".disabled_by_luoos");
                        if (jar.renameTo(disabled)) {
                            getLogger().info("Permanently disabled: " + jar.getName() + " -> " + disabled.getName());
                        } else {
                            getLogger().warning("Failed to rename: " + jar.getName() + " — please remove it manually");
                        }
                    }
                }
                getLogger().info("AuthMe has been disabled. Use /los migrate-authme to import AuthMe accounts.");
            }
        }

        // Start resource world schedule
        resourceWorldManager.start();

        // Register PAPI expansion
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new heos.folia.utils.LuoOSPlaceholderExpansion(statsTracker, resourceWorldManager).register();
            getLogger().info("PlaceholderAPI expansion registered");
        }

        getLogger().info("Heos Folia enabled (UUID-based + Account Binding + Group Concurrency)");
        getLogger().info("Account binding: " + cfgBool("account-binder.enable", "enableAccountBinding", true));
        getLogger().info("Binding storage: " + bindingStorage);
        getLogger().info("Auth: " + cfgBool("account.enableAuthentication", "enableAuthentication", true)
                + ", TPS: " + cfgBool("enableAutoLogTps", null, true));
        getLogger().info("Offline: " + (cfgBool("account.allowOfflinePlayers", "allowOfflinePlayers", true) ? "Enabled" : "Disabled"));
    }

    @Override
    public void onDisable() {
        if (authService != null) authService.close();
        if (tpsDisplayService != null) tpsDisplayService.close();
        if (recipeSyncService != null) recipeSyncService.close();
        if (bypassService != null) bypassService.close();
        if (botServer != null) botServer.stopServer();
        if (officialQQBot != null) officialQQBot.stop();
        if (resourceWorldManager != null) resourceWorldManager.close();
        getLogger().info("Heos Folia disabled.");
    }

    // ============ Legacy Upgrade ============

    private void runLegacyUpgrade() {
        java.io.File flag = new java.io.File(getDataFolder(), ".upgraded_v08");
        if (flag.exists()) return;
        getLogger().info("[Upgrade] Checking legacy data...");

        // Add entities_killed column if old DB missing it
        java.io.File db = new java.io.File(getDataFolder(), "player_data.db");
        if (db.exists()) try {
            synchronized (storage) {
                var conn = storage.getConnection();
                if (conn != null) {
                    try { conn.createStatement().execute("SELECT entities_killed FROM player_stats LIMIT 0"); }
                    catch (Exception e) {
                        conn.createStatement().execute("ALTER TABLE player_stats ADD COLUMN entities_killed BIGINT DEFAULT 0");
                        getLogger().info("[Upgrade] Added entities_killed column.");
                    }
                    try { conn.createStatement().execute("SELECT 1 FROM player_stats_daily LIMIT 0"); }
                    catch (Exception e) {
                        conn.createStatement().execute("CREATE TABLE IF NOT EXISTS player_stats_daily ("
                            + "uuid TEXT, date TEXT, play_time_seconds BIGINT DEFAULT 0, blocks_mined BIGINT DEFAULT 0,"
                            + "blocks_placed BIGINT DEFAULT 0, chat_chars BIGINT DEFAULT 0, entities_killed BIGINT DEFAULT 0,"
                            + "PRIMARY KEY (uuid, date))");
                        getLogger().info("[Upgrade] Created player_stats_daily table.");
                    }
                }
            }
        } catch (Exception ignored) {}

        // Migrate HEOS data dir to LuoOS
        java.io.File heosDir = new java.io.File(getDataFolder().getParent(), "heos");
        java.io.File heosDb = new java.io.File(heosDir, "player_data.db");
        if (heosDb.exists() && !db.exists()) try {
            java.nio.file.Files.copy(heosDb.toPath(), db.toPath());
            getLogger().info("[Upgrade] HEOS player_data.db migrated.");
        } catch (Exception e) {
            getLogger().warning("[Upgrade] HEOS migration failed: " + e.getMessage());
        }

        try { flag.createNewFile(); } catch (Exception ignored) {}
        getLogger().info("[Upgrade] Complete.");
    }
    private void registerCommands(FoliaBanCommands banCommands, FoliaAdminCommands adminCommands) {
        FoliaAuthCommands cmds = new FoliaAuthCommands(authService);
        bind("login", cmds); bind("register", cmds); bind("changepassword", cmds);
        bind("ban", banCommands); bind("ban-ip", banCommands);
        bind("unban", banCommands); bind("unban-ip", banCommands); bind("banlist", banCommands);
        bind("los", adminCommands);
    }

    private void bind(String name, org.bukkit.command.CommandExecutor exec) {
        PluginCommand cmd = getCommand(name);
        if (cmd == null) { getLogger().warning("Missing command: " + name); return; }
        cmd.setExecutor(exec);
        if (exec instanceof org.bukkit.command.TabCompleter tc) cmd.setTabCompleter(tc);
    }

    private boolean isRecipeViewerSyncEnabled() {
        return cfgBool("enableRecipeViewerSync", null, true)
                && compareVersions(getServer().getBukkitVersion().split("-", 2)[0], "1.21.2") >= 0;
    }

    private static int compareVersions(String a, String b) {
        String[] ap = a.split("\\."), bp = b.split("\\.");
        for (int i = 0; i < Math.max(ap.length, bp.length); i++) {
            int av = i < ap.length ? tryParse(ap[i]) : 0;
            int bv = i < bp.length ? tryParse(bp[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private static int tryParse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}
