package heos.folia;

import heos.folia.commands.FoliaAdminCommands;
import heos.folia.commands.FoliaAuthCommands;
import heos.folia.event.FoliaAuthListener;
import heos.folia.event.FoliaAuthService;
import heos.folia.commands.FoliaBanCommands;
import heos.folia.storage.FoliaBanData;
import heos.folia.commands.FoliaMigrationCommands;
import heos.folia.event.FoliaCommandInterceptor;
import heos.folia.integrations.FoliaRecipeSyncService;
import heos.folia.storage.FoliaStorage;
import heos.folia.storage.FoliaWhitelistData;
import heos.folia.utils.FoliaLoginUsernameValidationBypassService;
import heos.folia.utils.FoliaTpsDisplayService;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class HeosFoliaPlugin extends JavaPlugin {
    private FoliaAuthService authService;
    private FoliaStorage storage;
    private FoliaBanData banData;
    private FoliaWhitelistData whitelistData;
    private FoliaTpsDisplayService tpsDisplayService;
    private FoliaRecipeSyncService recipeSyncService;
    private FoliaLoginUsernameValidationBypassService usernameValidationBypassService;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        heos.folia.utils.FoliaMessages.init(this);
        heos.folia.utils.FoliaLogFilterService.installConfiguredFilters(this);
        this.storage = new FoliaStorage(getDataFolder().toPath());
        storage.initialize();
        this.banData = FoliaBanData.load(getDataFolder().toPath(), getLogger());
        this.whitelistData = FoliaWhitelistData.load(getDataFolder().toPath(), getLogger());
        this.tpsDisplayService = new FoliaTpsDisplayService(this);
        this.usernameValidationBypassService = new FoliaLoginUsernameValidationBypassService(this, banData, whitelistData);
        usernameValidationBypassService.install();

        this.authService = new FoliaAuthService(this, storage, tpsDisplayService);
        FoliaBanCommands banCommands = new FoliaBanCommands(banData);
        new heos.folia.utils.FoliaBanCleanupService(this, banData);
        getServer().getPluginManager().registerEvents(new FoliaCommandInterceptor(this, authService, banCommands), this);
        getServer().getPluginManager().registerEvents(new FoliaAuthListener(this, authService, banData, whitelistData), this);
        registerCommands(banCommands);
        boolean recipeViewerSyncEnabled = isRecipeViewerSyncEnabled();
        if (recipeViewerSyncEnabled) {
            this.recipeSyncService = new FoliaRecipeSyncService(this);
        }

        getLogger().info("Heos Folia support enabled");
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

    private void registerCommands(FoliaBanCommands banCommands) {
        FoliaAuthCommands commands = new FoliaAuthCommands(authService);
        bind("login", commands);
        bind("register", commands);
        bind("changepassword", commands);

        bind("ban", banCommands);
        bind("ban-ip", banCommands);
        bind("unban", banCommands);
        bind("unban-ip", banCommands);
        bind("banlist", banCommands);

        FoliaMigrationCommands migrationCommands = new FoliaMigrationCommands(this, storage, banData);
        bind("heos", new FoliaAdminCommands(this, storage, whitelistData, migrationCommands, authService, banCommands));
    }

    private void bind(String name, org.bukkit.command.CommandExecutor commands) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command is missing from plugin.yml: " + name);
            return;
        }
        command.setExecutor(commands);
        if (commands instanceof org.bukkit.command.TabCompleter completer) {
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
