package heos.utils;

import heos.Heos;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginFailureTracker {
    private static final Map<String, FailureState> USERNAME_FAILURES = new ConcurrentHashMap<>();
    private static final Map<String, FailureState> IP_FAILURES = new ConcurrentHashMap<>();

    private LoginFailureTracker() {
    }

    public static boolean isBlocked(String username, String ip) {
        return isUsernameBlocked(username) || isIpBlocked(ip);
    }

    public static String blockMessage(String username, String ip) {
        long usernameRemaining = remainingSeconds(USERNAME_FAILURES.get(normalize(username)));
        long ipRemaining = remainingSeconds(IP_FAILURES.get(normalize(ip)));
        long remaining = Math.max(usernameRemaining, ipRemaining);
        return Messages.loginFailureLock(Math.max(1L, remaining));
    }

    public static boolean recordFailure(String username, String ip) {
        boolean usernameBlocked = false;
        boolean ipBlocked = false;
        if (Heos.getConfig().enableUsernameLoginFailureLock) {
            usernameBlocked = recordFailure(
                    USERNAME_FAILURES,
                    normalize(username),
                    Math.max(1, Heos.getConfig().usernameLoginFailureLimit),
                    Math.max(1, Heos.getConfig().usernameLoginFailureLockSeconds)
            );
        }
        if (Heos.getConfig().enableIpLoginFailureLock && ip != null && !ip.isEmpty()) {
            ipBlocked = recordFailure(
                    IP_FAILURES,
                    normalize(ip),
                    Math.max(1, Heos.getConfig().ipLoginFailureLimit),
                    Math.max(1, Heos.getConfig().ipLoginFailureLockSeconds)
            );
        }
        return usernameBlocked || ipBlocked;
    }

    public static void reset(String username, String ip) {
        USERNAME_FAILURES.remove(normalize(username));
        if (ip != null && !ip.isEmpty()) {
            IP_FAILURES.remove(normalize(ip));
        }
    }

    private static boolean isUsernameBlocked(String username) {
        return Heos.getConfig().enableUsernameLoginFailureLock && isBlocked(USERNAME_FAILURES.get(normalize(username)));
    }

    private static boolean isIpBlocked(String ip) {
        return Heos.getConfig().enableIpLoginFailureLock && ip != null && !ip.isEmpty() && isBlocked(IP_FAILURES.get(normalize(ip)));
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
        if (remainingMillis <= 0L) {
            return 0L;
        }
        return (remainingMillis + 999L) / 1000L;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ENGLISH);
    }

    private static final class FailureState {
        private int failedAttempts;
        private long lockedUntilMillis;
    }
}
