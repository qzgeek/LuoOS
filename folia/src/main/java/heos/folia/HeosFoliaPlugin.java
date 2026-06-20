package heos.folia;

import heos.folia.commands.FoliaAdminCommands;
import heos.folia.commands.FoliaAuthCommands;
import heos.folia.commands.FoliaBanCommands;
import heos.folia.commands.FoliaBindCommands;
import heos.folia.commands.FoliaMigrationCommands;
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
    private FoliaLoginUsernameValidationBypassService usernameValidationBypassService;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Initialize messages (language files)
        heos.folia.utils.FoliaMessages.init(this);
        heos.folia.utils.FoliaLogFilterService.installConfiguredFilters(this);

        // Initialize storage and data layers
        this.storage = new FoliaStorage(getDataFolder().toPath());
        storage.initialize();
        this.banData = FoliaBanData.load(getDataFolder().toPath(), getLogger());
        this.whitelistData = FoliaWhitelistData.load(getDataFolder().toPath(), getLogger());

        // Name resolver (conflict detection)
        this.nameResolver = new FoliaNameResolver(storage);

        // Account binding
        this.accountBinding = new FoliaAccountBinding(storage, getLogger());

        // TPS display
        this.tpsDisplayService = new FoliaTpsDisplayService(this);

        // Auth service (UUID-based)
        this.authService = new FoliaAuthService(this, storage, nameResolver, accountBinding, tpsDisplayService);

        // Ban commands (with name resolution)
        FoliaBanCommands banCommands = new FoliaBanCommands(banData, nameResolver);
        new heos.folia.utils.FoliaBanCleanupService(this, banData);

        // Migration commands
        FoliaMigrationCommands migrationCommands = new FoliaMigrationCommands(this, storage, banData, nameResolver);

        // Bind commands
        FoliaBindCommands bindCommands = new FoliaBindCommands(accountBinding, nameResolver, storage);

        // Admin commands (routing hub)
        FoliaAdminCommands adminCommands = new FoliaAdminCommands(this, storage, whitelistData, migrationCommands, authService, banCommands, bindCommands);

        // Command interceptor
        getServer().getPluginManager().registerEvents(new FoliaCommandInterceptor(this, authService, banCommands), this);

        // Auth listener
        getServer().getPluginManager().registerEvents(new FoliaAuthListener(this, authService, banData, whitelistData), this);

        // Register commands
        registerCommands(banCommands, adminCommands);

        // Recipe viewer sync (1.21.2+)
        boolean recipeViewerSyncEnabled = isRecipeViewerSyncEnabled();
        if (recipeViewerSyncEnabled) {
            this.recipeSyncService = new FoliaRecipeSyncService(this);
        }

        // Login bypass (Netty hook for UUID remapping + character restriction removal)
        this.usernameValidationBypassService = new FoliaLoginUsernameValidationBypassService(
                this, banData, whitelistData, accountBinding);
        usernameValidationBypassService.install();

        getLogger().info("Heos Folia support enabled (UUID-based + Account Binding)");
        getLogger().info("Account binding: " + getConfig().getBoolean("enableAccountBinding", true));
        getLogger().info("Unprefixed command hijack: " + getConfig().getBoolean("enableUnprefixedCommandHijack", true));
        getLogger().info("Authentication: " + getConfig().getBoolean("enableAuthentication", true)
                + ", TPS footer: " + getConfig().getBoolean("enableAutoLogTps", true));
        getLogger().info("Offline players: " + (getConfig().getBoolean("allowOfflinePlayers", true) ? "Enabled" : "Disabled"));
        getLogger().info("Recipe viewer sync: " + recipeViewerSyncEnabled);
    }

    @Override
    public void onDisable() {
        if (authService != null) {
            authService.close();
        }
        if (tpsDisplayService != null) {
            tpsDisplayService.close();
        }
        if (recipeSyncService != null) {
            recipeSyncService.close();
        }
        if (usernameValidationBypassService != null) {
            usernameValidationBypassService.close();
        }
    }

    private void registerCommands(FoliaBanCommands banCommands, FoliaAdminCommands adminCommands) {
        FoliaAuthCommands commands = new FoliaAuthCommands(authService);
        bind("login", commands);
        bind("register", commands);
        bind("changepassword", commands);

        bind("ban", banCommands);
        bind("ban-ip", banCommands);
        bind("unban", banCommands);
        bind("unban-ip", banCommands);
        bind("banlist", banCommands);

        bind("heos", adminCommands);
    }

    private void bind(String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command is missing from plugin.yml: " + name);
            return;
        }
        command.setExecutor(executor);
        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }

    private boolean isRecipeViewerSyncEnabled() {
        return getConfig().getBoolean("enableRecipeViewerSync", true)
                && compareMinecraftVersions(minecraftVersion(), "1.21.2") >= 0;
    }

    private String minecraftVersion() {
        return getServer().getBukkitVersion().split("-", 2)[0];
    }

    private static int compareMinecraftVersions(String left, String right) {
        String[] leftParts = left.split("\\.");
        String[] rightParts = right.split("\\.");
        int size = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < size; index++) {
            int leftPart = versionPart(leftParts, index);
            int rightPart = versionPart(rightParts, index);
            if (leftPart != rightPart) {
                return Integer.compare(leftPart, rightPart);
            }
        }
        return 0;
    }

    private static int versionPart(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
