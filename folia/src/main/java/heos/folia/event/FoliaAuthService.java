package heos.folia.event;

import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;
import heos.folia.utils.FoliaLoginFailureTracker;
import heos.folia.utils.FoliaDisconnects;
import heos.folia.utils.FoliaPasswordHasher;
import heos.folia.utils.FoliaPlayerAccess;
import heos.folia.utils.FoliaTpsDisplayService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import heos.folia.utils.FoliaMessages;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FoliaAuthService {
    private final Plugin plugin;
    private final FoliaStorage storage;
    private final FoliaTpsDisplayService tpsDisplayService;
    private final FoliaLoginFailureTracker failureTracker;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Integer> authenticatedSessionsByIp = new ConcurrentHashMap<>();

    public FoliaAuthService(Plugin plugin, FoliaStorage storage, FoliaTpsDisplayService tpsDisplayService) {
        this.plugin = plugin;
        this.storage = storage;
        this.tpsDisplayService = tpsDisplayService;
        this.failureTracker = new FoliaLoginFailureTracker(plugin);
    }

    public void prepare(Player player) {
        boolean premium = isPremiumUuid(player);
        FoliaPlayerData data = storage.load(player.getName(), premium, separateOnlineOfflineAccounts());
        if (!isAuthenticationEnabled()) {
            Session session = new Session(data, true);
            session.ip = FoliaPlayerAccess.ip(player);
            sessions.put(player.getUniqueId(), session);
            updateLoginProtection(player, false);
            tpsDisplayService.start(player);
            return;
        }
        if (premium) {
            data.uuid = player.getUniqueId();
            data.isOnlineAccount = true;
            data.lastIp = FoliaPlayerAccess.ip(player);
            data.lastLoginTime = System.currentTimeMillis();
            storage.save(data);
            
            Session session = new Session(data, true);
            session.ip = FoliaPlayerAccess.ip(player);
            sessions.put(player.getUniqueId(), session);
            updateLoginProtection(player, false);
            tpsDisplayService.start(player);
            player.sendMessage(ChatColor.GREEN + FoliaMessages.premiumWelcome());
            return;
        }

        boolean registered = data.isRegistered();
        sessions.put(player.getUniqueId(), new Session(data, false));
        updateLoginProtection(player, true);
        player.sendMessage(registered
                ? ChatColor.YELLOW + FoliaMessages.loginInputHint()
                : ChatColor.YELLOW + FoliaMessages.registerInputHint());
        scheduleLoginTimeout(player);
        scheduleLoginReminder(player);
    }

    private boolean isPremiumUuid(Player player) {
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + player.getName()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return !player.getUniqueId().equals(offline);
    }

    public void remove(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        tpsDisplayService.stop(player);
        if (session != null && session.authenticated) {
            decrementIp(session.ip);
        }
    }

    public boolean isAuthenticated(Player player) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.authenticated;
    }

    public boolean shouldBlock(Player player) {
        return isAuthenticationEnabled() && !isAuthenticated(player);
    }

    public boolean isAuthenticationEnabled() {
        return plugin.getConfig().getBoolean("enableAuthentication", true);
    }

    public boolean areOfflinePlayersAllowed() {
        return plugin.getConfig().getBoolean("allowOfflinePlayers", true);
    }

    public boolean separateOnlineOfflineAccounts() {
        return plugin.getConfig().getBoolean("separateOnlineOfflineAccounts", true);
    }

    public boolean canRunCommandWhileLocked(String commandLine) {
        String root = commandLine.split(" ", 2)[0].toLowerCase();
        return root.equals("login") || root.equals("l") || root.equals("register") || root.equals("reg");
    }

    public void login(Player player, String password) {
        Session session = session(player);
        FoliaPlayerData data = session.data;
        String ip = FoliaPlayerAccess.ip(player);
        if (failureTracker.isBlocked(player.getName(), ip)) {
            FoliaDisconnects.disconnect(player, failureTracker.blockMessage(player.getName(), ip), "HEOS_LOGIN_FAILURE_LOCK");
            return;
        }
        if (!data.isRegistered()) {
            player.sendMessage(ChatColor.RED + FoliaMessages.notRegistered());
            return;
        }
        if (!FoliaPasswordHasher.verifyPassword(password, data.passwordHash)) {
            if (failureTracker.recordFailure(player.getName(), ip)) {
                FoliaDisconnects.disconnect(player, failureTracker.blockMessage(player.getName(), ip), "HEOS_LOGIN_FAILURE_LOCK");
                return;
            }
            player.sendMessage(ChatColor.RED + FoliaMessages.wrongPassword());
            return;
        }
        if (!markAuthenticated(player, session)) {
            return;
        }
        failureTracker.reset(player.getName(), ip);
        data.lastIp = ip;
        data.lastLoginTime = System.currentTimeMillis();
        if (FoliaPasswordHasher.needsRehash(data.passwordHash)) {
            data.passwordHash = FoliaPasswordHasher.hashPassword(password);
        }
        storage.save(data);
        player.sendMessage(ChatColor.GREEN + FoliaMessages.loginSuccess());
    }

    public void register(Player player, String password, String confirmPassword) {
        Session session = session(player);
        FoliaPlayerData data = session.data;
        if (data.isRegistered()) {
            player.sendMessage(ChatColor.RED + FoliaMessages.alreadyRegistered());
            return;
        }
        if (!password.equals(confirmPassword)) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordMismatch());
            return;
        }
        int min = plugin.getConfig().getInt("minPasswordLength", 4);
        int max = plugin.getConfig().getInt("maxPasswordLength", 32);
        if (password.length() < min) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooShort().formatted(min));
            return;
        }
        if (password.length() > max) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooLong().formatted(max));
            return;
        }
        data.uuid = player.getUniqueId();
        data.passwordHash = FoliaPasswordHasher.hashPassword(password);
        data.lastIp = ip(player);
        long now = System.currentTimeMillis();
        data.registeredTime = now;
        data.lastLoginTime = now;
        if (!markAuthenticated(player, session)) {
            return;
        }
        storage.save(data);
        player.sendMessage(ChatColor.GREEN + FoliaMessages.registerSuccess());
    }

    public void changePassword(Player player, String oldPassword, String newPassword) {
        Session session = session(player);
        if (!session.authenticated) {
            player.sendMessage(ChatColor.RED + FoliaMessages.authPromptLogin());
            return;
        }
        FoliaPlayerData data = session.data;
        if (!FoliaPasswordHasher.verifyPassword(oldPassword, data.passwordHash)) {
            player.sendMessage(ChatColor.RED + FoliaMessages.wrongPassword());
            return;
        }
        if (oldPassword.equals(newPassword)) {
            player.sendMessage(ChatColor.RED + "New password cannot be the same as the old password");
            return;
        }
        int min = plugin.getConfig().getInt("minPasswordLength", 4);
        int max = plugin.getConfig().getInt("maxPasswordLength", 32);
        if (newPassword.length() < min) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooShort().formatted(min));
            return;
        }
        if (newPassword.length() > max) {
            player.sendMessage(ChatColor.RED + FoliaMessages.passwordTooLong().formatted(max));
            return;
        }
        data.passwordHash = FoliaPasswordHasher.hashPassword(newPassword);
        storage.save(data);
        player.sendMessage(ChatColor.GREEN + FoliaMessages.keepPasswordSafe());
    }

    public void close() {
        storage.close();
        sessions.clear();
    }

    private void scheduleLoginTimeout(Player player) {
        int timeoutSeconds = Math.max(1, plugin.getConfig().getInt("loginTimeout", 60));
        player.getScheduler().runDelayed(plugin, task -> {
            if (player.isOnline() && shouldBlock(player)) {
                FoliaDisconnects.disconnect(player, FoliaMessages.loginTimeout(), "HEOS_LOGIN_TIMEOUT");
            }
        }, null, timeoutSeconds * 20L);
    }

    private void scheduleLoginReminder(Player player) {
        int reminderSeconds = Math.max(1, plugin.getConfig().getInt("loginReminderSeconds", 10));
        player.getScheduler().runDelayed(plugin, task -> {
            if (!player.isOnline() || !shouldBlock(player)) {
                return;
            }
            FoliaPlayerData data = session(player).data;
            player.sendMessage(data.isRegistered()
                    ? ChatColor.YELLOW + FoliaMessages.loginInputHint()
                    : ChatColor.YELLOW + FoliaMessages.registerInputHint());
            scheduleLoginReminder(player);
        }, null, reminderSeconds * 20L);
    }

    private Session session(Player player) {
        boolean premium = isPremiumUuid(player);
        return sessions.computeIfAbsent(player.getUniqueId(), ignored -> new Session(
                storage.load(player.getName(), premium, separateOnlineOfflineAccounts()),
                false
        ));
    }

    private boolean markAuthenticated(Player player, Session session) {
        if (session.authenticated) {
            return true;
        }
        int limit = plugin.getConfig().getInt("maxConcurrentSessionsPerIp", -1);
        String ip = FoliaPlayerAccess.ip(player);
        if (limit >= 0 && authenticatedSessionsByIp.getOrDefault(ip, 0) >= limit) {
            FoliaDisconnects.disconnect(
                    player,
                    plugin.getConfig().getString("sessionLimitKickMessage", "The online session limit for this IP has been reached"),
                    "HEOS_SESSION_LIMIT"
            );
            return false;
        }
        session.ip = ip;
        session.authenticated = true;
        authenticatedSessionsByIp.merge(ip, 1, Integer::sum);
        updateLoginProtection(player, false);
        tpsDisplayService.start(player);
        return true;
    }

    private static void updateLoginProtection(Player player, boolean protectedDuringLogin) {
        player.setInvulnerable(protectedDuringLogin);
        player.setInvisible(protectedDuringLogin);
        player.setCollidable(!protectedDuringLogin);
    }

    private void decrementIp(String ip) {
        if (ip == null || ip.isEmpty()) {
            return;
        }
        authenticatedSessionsByIp.computeIfPresent(ip, (key, value) -> value <= 1 ? null : value - 1);
    }

    private static String ip(Player player) {
        return FoliaPlayerAccess.ip(player);
    }

    private static final class Session {
        private final FoliaPlayerData data;
        private volatile boolean authenticated;
        private volatile String ip = "";

        private Session(FoliaPlayerData data, boolean authenticated) {
            this.data = data;
            this.authenticated = authenticated;
        }
    }
}
