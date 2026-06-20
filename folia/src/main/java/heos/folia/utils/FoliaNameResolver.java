package heos.folia.utils;

import heos.folia.storage.FoliaPlayerData;
import heos.folia.storage.FoliaStorage;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Detects name conflicts between online and offline accounts sharing the same username.
 * When a conflict exists, assigns display name prefixes ("正版_" / "离线_").
 */
public final class FoliaNameResolver {
    private final FoliaStorage storage;

    public FoliaNameResolver(FoliaStorage storage) {
        this.storage = storage;
    }

    /**
     * Check for name conflict and set displayName on the player data if needed.
     * Call this after loading player data and before displaying to users.
     *
     * @return the data with displayName populated (same object)
     */
    public FoliaPlayerData resolve(FoliaPlayerData data) {
        if (data == null || data.username == null) {
            return data;
        }

        List<FoliaPlayerData> all = storage.loadAllByName(data.username);
        if (all.size() < 2) {
            data.hasNameConflict = false;
            data.displayName = data.username;
            return data;
        }

        // Check if there are accounts of different types (online + offline)
        boolean hasOnline = false;
        boolean hasOffline = false;
        UUID otherUuid = null;

        for (FoliaPlayerData other : all) {
            if (other.uuid != null && other.uuid.equals(data.uuid)) {
                continue; // skip self
            }
            if (other.isOnlineAccount) {
                hasOnline = true;
            } else {
                hasOffline = true;
            }
            if (otherUuid == null) {
                otherUuid = other.uuid;
            }
        }

        // Conflict only when BOTH types exist for the same name
        if (hasOnline && hasOffline) {
            data.hasNameConflict = true;
            data.conflictPartnerUuid = otherUuid;
            String prefix = data.isOnlineAccount ? FoliaMessages.namePrefixOnline() : FoliaMessages.namePrefixOffline();
            data.displayName = prefix + data.username;
        } else {
            data.hasNameConflict = false;
            data.displayName = data.username;
        }

        return data;
    }

    /**
     * Get the display name (with prefix if conflicted) for a player by name.
     * Returns the original name if no conflict.
     */
    public String displayName(String username, boolean isOnlineAccount) {
        List<FoliaPlayerData> all = storage.loadAllByName(username);
        if (all.size() < 2) {
            return username;
        }
        boolean hasOnline = false;
        boolean hasOffline = false;
        for (FoliaPlayerData data : all) {
            if (data.isOnlineAccount) {
                hasOnline = true;
            } else {
                hasOffline = true;
            }
        }
        if (hasOnline && hasOffline) {
            String prefix = isOnlineAccount ? FoliaMessages.namePrefixOnline() : FoliaMessages.namePrefixOffline();
            return prefix + username;
        }
        return username;
    }

    /**
     * Find a player by prefixed name (e.g. "正版_Player1" or "离线_Player1").
     * Returns null if not found.
     */
    public FoliaPlayerData findByPrefixedName(String prefixedName) {
        String onlinePrefix = FoliaMessages.namePrefixOnline();
        String offlinePrefix = FoliaMessages.namePrefixOffline();

        boolean isOnline = false;
        String rawName;
        if (prefixedName.startsWith(onlinePrefix)) {
            isOnline = true;
            rawName = prefixedName.substring(onlinePrefix.length());
        } else if (prefixedName.startsWith(offlinePrefix)) {
            isOnline = false;
            rawName = prefixedName.substring(offlinePrefix.length());
        } else {
            return null; // no prefix
        }

        List<FoliaPlayerData> all = storage.loadAllByName(rawName);
        for (FoliaPlayerData data : all) {
            if (data.isOnlineAccount == isOnline) {
                return data;
            }
        }
        return null;
    }
}
