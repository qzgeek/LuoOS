package heos.folia.utils;

import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class FoliaLoginFailureTracker {
    private final Plugin plugin;
    private final Map<String, FailureState> usernameFailures = new ConcurrentHashMap<>();
    private final Map<String, FailureState> ipFailures = new ConcurrentHashMap<>();

    public FoliaLoginFailureTracker(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isBlocked(String username, String ip) {
        return isUsernameBlocked(username) || isIpBlocked(ip);
    }

    public String blockMessage(String username, String ip) {
        long usernameRemaining = remainingSeconds(usernameFailures.get(normalize(username)));
        long ipRemaining = remainingSeconds(ipFailures.get(normalize(ip)));
        long remaining = Math.max(usernameRemaining, ipRemaining);
        return FoliaMessages.loginFailureLock(Math.max(1L, remaining));
    }

    public boolean recordFailure(String username, String ip) {
        boolean usernameBlocked = false;
        boolean ipBlocked = false;
        if (plugin.getConfig().getBoolean("enableUsernameLoginFailureLock", true)) {
            usernameBlocked = recordFailure(
                    usernameFailures,
                    normalize(username),
                    Math.max(1, plugin.getConfig().getInt("usernameLoginFailureLimit", 5)),
                    Math.max(1, plugin.getConfig().getInt("usernameLoginFailureLockSeconds", 30))
            );
        }
        if (plugin.getConfig().getBoolean("enableIpLoginFailureLock", false) && ip != null && !ip.isEmpty()) {
            ipBlocked = recordFailure(
                    ipFailures,
                    normalize(ip),
                    Math.max(1, plugin.getConfig().getInt("ipLoginFailureLimit", 10)),
                    Math.max(1, plugin.getConfig().getInt("ipLoginFailureLockSeconds", 30))
            );
        }
        return usernameBlocked || ipBlocked;
    }

    public void reset(String username, String ip) {
        usernameFailures.remove(normalize(username));
        if (ip != null && !ip.isEmpty()) {
            ipFailures.remove(normalize(ip));
        }
    }

    private boolean isUsernameBlocked(String username) {
        return plugin.getConfig().getBoolean("enableUsernameLoginFailureLock", true) && isBlocked(usernameFailures.get(normalize(username)));
    }

    private boolean isIpBlocked(String ip) {
        return plugin.getConfig().getBoolean("enableIpLoginFailureLock", false) && ip != null && !ip.isEmpty() && isBlocked(ipFailures.get(normalize(ip)));
    }

    private static boolean recordFailure(Map<String, FailureState> failures, String key, int limit, int lockSeconds) {
        long now = System.currentTimeMillis();
        FailureState state = failures.computeIfAbsent(key, ignored -> new FailureState());
        if (state.lockedUntilMillis > now) {
            return true;
        }
        state.failedAttempts++;
        if (state.failedAttempts >= limit) {
            state.failedAttempts = 0;
            state.lockedUntilMillis = now + lockSeconds * 1000L;
            return true;
        }
        return false;
    }

    private static boolean isBlocked(FailureState state) {
        if (state == null) {
            return false;
        }
        if (state.lockedUntilMillis <= System.currentTimeMillis()) {
            state.lockedUntilMillis = 0L;
            return false;
        }
        return true;
    }

    private static long remainingSeconds(FailureState state) {
        if (state == null) {
            return 0L;
        }
        long remainingMillis = state.lockedUntilMillis - System.currentTimeMillis();
        return remainingMillis <= 0L ? 0L : (remainingMillis + 999L) / 1000L;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ENGLISH);
    }

    private static final class FailureState {
        private int failedAttempts;
        private long lockedUntilMillis;
    }
}
