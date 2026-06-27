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
import heos.folia.event.FoliaAuthListener;
import heos.folia.event.FoliaAuthService;
import heos.folia.event.FoliaCommandInterceptor;
import heos.folia.integrations.FoliaRecipeSyncService;
import heos.folia.storage.FoliaAccountBinding;
import heos.folia.storage.FoliaBanData;
import heos.folia.storage.FoliaStorage;
import heos.folia.storage.FoliaWhitelistData;
import heos.folia.utils.FoliaLoginUsernameValidationBypassService;
import heos.folia.utils.FoliaNameResolver;
import heos.folia.utils.FoliaTpsDisplayService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeosFoliaPlugin extends JavaPlugin {
    private FoliaAuthService authService;
    private FoliaStorage storage;
    private FoliaBanData banData;
    private FoliaWhitelistData whitelistData;
    private FoliaAccountBinding accountBinding;
    private FoliaNameResolver nameResolver;
    private FoliaTpsDisplayService tpsDisplayService;
    private FoliaRecipeSyncService recipeSyncService;
    private FoliaLoginUsernameValidationBypassService bypassService;
    private OneBotServer botServer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        heos.folia.utils.FoliaMessages.init(this);
        heos.folia.utils.FoliaLogFilterService.installConfiguredFilters(this);

        // Storage with optional MySQL
        this.storage = new FoliaStorage(getDataFolder().toPath());
        String bindingStorage = getConfig().getString("bindingStorage", "sqlite");
        if ("mysql".equalsIgnoreCase(bindingStorage)) {
            String url = getConfig().getString("mysql.url", "");
            String user = getConfig().getString("mysql.user", "");
            String pass = getConfig().getString("mysql.password", "");
            if (!url.isEmpty()) {
                storage.configureMySQL(url, user, pass);
                getLogger().info("Account binding storage: MySQL");
            }
        }
        storage.initialize();

        this.banData = FoliaBanData.load(getDataFolder().toPath(), getLogger());
        this.whitelistData = FoliaWhitelistData.load(getDataFolder().toPath(), getLogger());
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
        FoliaAdminCommands adminCommands = new FoliaAdminCommands(this, storage, whitelistData,
                migrationCommands, authService, banCommands, bindCommands);

        getServer().getPluginManager().registerEvents(
                new FoliaCommandInterceptor(this, authService, banCommands), this);
        getServer().getPluginManager().registerEvents(
                new FoliaAuthListener(this, authService, banData, whitelistData, storage), this);
        registerCommands(banCommands, adminCommands);

        if (isRecipeViewerSyncEnabled()) {
            this.recipeSyncService = new FoliaRecipeSyncService(this);
        }

        this.bypassService = new FoliaLoginUsernameValidationBypassService(
                this, banData, whitelistData, accountBinding, storage);
        bypassService.install();

        // OneBot QQ bot
        if (getConfig().getBoolean("bot.enabled", false)) {
            String botHost = getConfig().getString("bot.host", "0.0.0.0");
            int botPort = getConfig().getInt("bot.port", 10100);
            String botToken = getConfig().getString("bot.access_token", "");
            long[] groups = getConfig().getLongList("bot.qq_groups").stream().mapToLong(Long::longValue).toArray();
            int maxPerQq = getConfig().getInt("bot.max_per_qq", 3);
            String idChars = getConfig().getString("bot.allowed_id_chars", "a-zA-Z0-9_-");
            int maxIdLen = getConfig().getInt("bot.max_id_length", 16);

            String statusTrigger = getConfig().getString("bot.status_trigger", "服务器还活着吗");
            int rateMax = getConfig().getInt("bot.rate_limit_max", 3);
            int rateWindow = getConfig().getInt("bot.rate_limit_window", 60);
            int delayMin = getConfig().getInt("bot.reply_delay_min_ms", 1000);
            int delayMax = getConfig().getInt("bot.reply_delay_max_ms", 2000);

            String mcHost = getConfig().getString("bot.mc_host", "127.0.0.1");
            int mcPort = getConfig().getInt("bot.mc_port", 25565);
            String mcName = getConfig().getString("bot.mc_display_name", "LuoOS服务器");
            String mcDesc = getConfig().getString("bot.mc_description", "欢迎来到LuoOS");
            String mcDisplayIp = getConfig().getString("bot.mc_display_ip", mcHost + ":" + mcPort);
            BotStatusService statusService = new BotStatusService(getLogger(), mcHost, mcPort, mcName, mcDesc, mcDisplayIp, getDataFolder());

            BotDb botDb = new BotDb(getLogger(), storage);
            try {
                BotCommandHandler botHandler = new BotCommandHandler(
                        getLogger(), botDb, storage, statusService,
                        maxPerQq, idChars, maxIdLen, groups,
                        statusTrigger, rateMax, rateWindow,
                        delayMin, delayMax);

                botServer = new OneBotServer(getLogger(), botHost, botPort, botToken);
                boolean botDebug = getConfig().getBoolean("bot.debug_log", false);
                botServer.setDebugLog(botDebug);
                botHandler.setDebugLog(botDebug);
                botServer.setEventHandler(event -> botHandler.handle(event));
                new Thread(botServer::startServer, "LuoOS-Bot").start();
                getLogger().info("OneBot server started on ws://" + botHost + ":" + botPort);
            } catch (Exception e) {
                getLogger().severe("Failed to start OneBot server: " + e.getMessage());
                e.printStackTrace();
            }
        }

        // Auto-detect and PERMANENTLY disable AuthMe to prevent authentication conflicts.
        // LuoOS provides its own complete auth system (login/register/password management).
        // Having both AuthMe and LuoOS enabled causes duplicate login/register prompts
        // and inconsistent authentication state.
        if (getConfig().getBoolean("enableAuthentication", true)) {
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

        getLogger().info("Heos Folia enabled (UUID-based + Account Binding + Group Concurrency)");
        getLogger().info("Account binding: " + getConfig().getBoolean("enableAccountBinding", true));
        getLogger().info("Binding storage: " + bindingStorage);
        getLogger().info("Auth: " + getConfig().getBoolean("enableAuthentication", true)
                + ", TPS: " + getConfig().getBoolean("enableAutoLogTps", true));
        getLogger().info("Offline: " + (getConfig().getBoolean("allowOfflinePlayers", true) ? "Enabled" : "Disabled"));
    }

    @Override
    public void onDisable() {
        if (authService != null) authService.close();
        if (tpsDisplayService != null) tpsDisplayService.close();
        if (recipeSyncService != null) recipeSyncService.close();
        if (bypassService != null) bypassService.close();
        if (botServer != null) botServer.stopServer();
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
        return getConfig().getBoolean("enableRecipeViewerSync", true)
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
