package heos.utils;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import heos.Heos;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;

/**
 * Localized auth-related messages based on config language.
 */
public final class Messages {
    private static final Gson GSON = new Gson();
    private static final Map<String, String> FALLBACK = loadLanguage("en_us");

    private Messages() {
    }

    private static Map<String, String> loadLanguage(String language) {
        try {
            var stream = Messages.class.getResourceAsStream("/data/heos/lang/" + language.toLowerCase(Locale.ENGLISH) + ".json");
            if (stream == null) {
                return Collections.emptyMap();
            }
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                Map<String, String> map = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
                return map == null ? Collections.emptyMap() : map;
            }
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private static String translate(String key) {
        String language = Heos.getConfig() == null ? "en_us" : Heos.getConfig().language;
        Map<String, String> current = loadLanguage(language);
        if (current.containsKey(key)) {
            return current.get(key);
        }
        return FALLBACK.getOrDefault(key, key);
    }

    public static String authPromptLogin() {
        return translate("text.heos.loginInputHint");
    }

    public static String authPromptRegister() {
        return translate("text.heos.registerInputHint");
    }

    public static String offlineNameHint() {
        return translate("text.heos.disallowedUsername").formatted(allowedUsernamePattern());
    }

    public static String offlineNameLogOnly() {
        return "HEOS_OFFLINE_NAME_RULE";
    }

    public static String invalidOfflineNameLog() {
        return translate("text.heos.disallowedUsername").formatted(allowedUsernamePattern());
    }

    public static String loginTimeout() {
        return translate("text.heos.timeExpired");
    }

    public static String premiumWelcome() {
        return translate("text.heos.onlinePlayerLogin");
    }

    public static String authServiceUnavailable() {
        return translate("text.heos.authServiceUnavailable");
    }

    public static String loginInputHint() {
        return translate("text.heos.loginInputHint");
    }

    public static String registerInputHint() {
        return translate("text.heos.registerRequired");
    }

    public static String alreadyLoggedIn() {
        return translate("text.heos.alreadyAuthenticated");
    }

    public static String premiumNoLogin() {
        return translate("text.heos.onlinePlayerLogin");
    }

    public static String premiumNoRegister() {
        return translate("text.heos.onlinePlayerLogin");
    }

    public static String notRegistered() {
        return translate("text.heos.userNotRegistered");
    }

    public static String alreadyRegistered() {
        return translate("text.heos.alreadyRegistered");
    }

    public static String loginSuccess() {
        return translate("text.heos.successfullyAuthenticated");
    }

    public static String wrongPassword() {
        return translate("text.heos.wrongPassword");
    }

    public static String passwordTooShort() {
        return translate("text.heos.minPasswordChars");
    }

    public static String passwordTooLong() {
        return translate("text.heos.maxPasswordChars");
    }

    public static String passwordMismatch() {
        return translate("text.heos.matchPassword");
    }

    public static String registerFailed() {
        return translate("text.heos.registerRequired");
    }

    public static String registerSuccess() {
        return translate("text.heos.registerSuccess");
    }

    public static String keepPasswordSafe() {
        return translate("text.heos.keepPasswordSafe");
    }

    public static boolean isMigrationReason(String reason) {
        if (reason == null) {
            return false;
        }
        String normalized = reason.toLowerCase();
        return normalized.contains("data migration in progress")
                || normalized.contains("migration in progress");
    }

    public static String migrationBanLogOnly() {
        return "HEOS_MIGRATION_BAN";
    }

    public static String loginFailureLock(long seconds) {
        return translate("text.heos.loginFailureLock").formatted(seconds);
    }

    public static String whitelistLogOnly() {
        return "HEOS_WHITELIST";
    }

    public static String whitelistKick() {
        return translate("text.heos.whitelistKick");
    }

    public static String whitelistDeniedLog(String username) {
        return translate("text.heos.whitelistDeniedLog").formatted(username);
    }

    public static String banMessage(String reason, String expiry) {
        return translate("text.heos.banMessage").formatted(reason, expiry);
    }

    public static String banIpMessage(String reason, String expiry) {
        return translate("text.heos.banIpMessage").formatted(reason, expiry);
    }

    public static String migrationBanAttemptLog(String username) {
        return translate("text.heos.playerAlreadyOnline").formatted(username);
    }

    public static String updateSuppressionCrash(String detail) {
        return translate("text.heos.updateSuppressionCrash").formatted(detail);
    }

    public static String unknownPosition() {
        return translate("text.heos.unknownPosition");
    }

    private static String allowedUsernamePattern() {
        if (Heos.getConfig() != null && Heos.getConfig().allowMoreOfflineUsernameCharacters) {
            return translate("text.heos.usernamePatternExtended");
        }
        return translate("text.heos.usernamePatternSimple");
    }
}
