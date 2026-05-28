package heos.integrations;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import heos.utils.HeosLogger;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mojang API integration for checking premium accounts
 */
public class MojangApi {
    private static final String MOJANG_API = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final long FOUND_CACHE_MILLIS = Duration.ofHours(6).toMillis();
    private static final long NOT_FOUND_CACHE_MILLIS = Duration.ofMinutes(5).toMillis();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    private static final Map<String, CachedLookup> CACHE = new ConcurrentHashMap<>();

    public enum LookupResultType {
        FOUND,
        NOT_FOUND,
        ERROR
    }

    public static class LookupResult {
        public final LookupResultType type;
        public final UUID uuid;

        public LookupResult(LookupResultType type, UUID uuid) {
            this.type = type;
            this.uuid = uuid;
        }
    }

    /**
     * Gets Mojang account lookup result
     */
    public static LookupResult lookupAccount(String username) {
        if (!isValidMojangUsername(username)) {
            return new LookupResult(LookupResultType.NOT_FOUND, null);
        }
        String cacheKey = username.toLowerCase(Locale.ENGLISH);
        LookupResult cached = validCached(cacheKey);
        if (cached != null) {
            return cached;
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(MOJANG_API + username))
            .timeout(REQUEST_TIMEOUT)
            .GET()
            .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == HttpURLConnection.HTTP_OK) {
                return cache(cacheKey, parseProfile(username, response.body()), FOUND_CACHE_MILLIS);
            }
            if (status == HttpURLConnection.HTTP_NO_CONTENT || status == HttpURLConnection.HTTP_NOT_FOUND) {
                return cache(cacheKey, new LookupResult(LookupResultType.NOT_FOUND, null), NOT_FOUND_CACHE_MILLIS);
            }

            HeosLogger.warn("Unexpected Mojang API status for " + username + ": " + status);
            return cachedOrError(cacheKey);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            HeosLogger.error("Failed to check Mojang account for " + username, e);
            return cachedOrError(cacheKey);
        } catch (RuntimeException e) {
            HeosLogger.error("Failed to parse Mojang account response for " + username, e);
            return cachedOrError(cacheKey);
        }
    }

    private static LookupResult validCached(String cacheKey) {
        CachedLookup cached = CACHE.get(cacheKey);
        if (cached == null || cached.expiresAtMillis < System.currentTimeMillis()) {
            return null;
        }
        return cached.result;
    }

    private static LookupResult cachedOrError(String cacheKey) {
        CachedLookup cached = CACHE.get(cacheKey);
        if (cached != null) {
            return cached.result;
        }
        return new LookupResult(LookupResultType.ERROR, null);
    }

    private static LookupResult cache(String cacheKey, LookupResult result, long ttlMillis) {
        CACHE.put(cacheKey, new CachedLookup(result, System.currentTimeMillis() + ttlMillis));
        return result;
    }

    private static LookupResult parseProfile(String username, String responseBody) {
        JsonObject json = JsonParser.parseString(responseBody).getAsJsonObject();
        String compactUuid = json.has("id") ? json.get("id").getAsString() : null;
        if (compactUuid == null || !compactUuid.matches("^[0-9a-fA-F]{32}$")) {
            HeosLogger.warn("Mojang API response for " + username + " contained an invalid id: " + responseBody);
            return new LookupResult(LookupResultType.ERROR, null);
        }
        return new LookupResult(LookupResultType.FOUND, expandUuid(compactUuid));
    }

    private static UUID expandUuid(String compactUuid) {
        String dashedUuid = compactUuid.substring(0, 8)
            + "-" + compactUuid.substring(8, 12)
            + "-" + compactUuid.substring(12, 16)
            + "-" + compactUuid.substring(16, 20)
            + "-" + compactUuid.substring(20);
        return UUID.fromString(dashedUuid);
    }

    /**
     * Checks if username is valid for Mojang account (alphanumeric and underscore only)
     */
    public static boolean isValidMojangUsername(String username) {
        return username != null && username.matches("^[a-zA-Z0-9_]{3,16}$");
    }

    /**
     * Checks if username is allowed for offline accounts.
     */
    public static boolean isAllowedOfflineUsername(String username) {
        return isAllowedOfflineUsername(username, false);
    }

    /**
     * Checks if username is allowed for offline accounts.
     */
    public static boolean isAllowedOfflineUsername(String username, boolean allowMoreCharacters) {
        if (username == null) {
            return false;
        }
        int length = username.codePointCount(0, username.length());
        if (length < 3 || length > 16) {
            return false;
        }
        if (!allowMoreCharacters) {
            return username.matches("^[a-zA-Z0-9_+\\-.]{3,16}$");
        }
        return username.codePoints().allMatch(MojangApi::isAllowedExtendedOfflineUsernameCodePoint);
    }

    private static boolean isAllowedExtendedOfflineUsernameCodePoint(int codePoint) {
        return Character.isLetterOrDigit(codePoint)
                || Character.getType(codePoint) == Character.NON_SPACING_MARK
                || codePoint == '_'
                || codePoint == '+'
                || codePoint == '-'
                || codePoint == '.';
    }

    private record CachedLookup(LookupResult result, long expiresAtMillis) {
    }
}
