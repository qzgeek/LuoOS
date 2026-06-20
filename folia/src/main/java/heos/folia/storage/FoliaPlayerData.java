package heos.folia.storage;

import java.util.UUID;

public final class FoliaPlayerData {
    public String username;
    public UUID uuid;
    public String passwordHash = "";
    public String lastIp = "";
    public long registeredTime;
    public long lastLoginTime;
    public boolean isOnlineAccount;
    /** Display name with prefix when name conflict exists (e.g. "正版_Player1") */
    public transient String displayName;
    /** Whether this account has a name conflict with another account of different type */
    public transient boolean hasNameConflict;
    /** The conflicting account UUID (for admin reference) */
    public transient UUID conflictPartnerUuid;

    public FoliaPlayerData(String username) {
        this.username = username;
    }

    public FoliaPlayerData(String username, UUID uuid, boolean isOnlineAccount) {
        this.username = username;
        this.uuid = uuid;
        this.isOnlineAccount = isOnlineAccount;
    }

    public boolean isRegistered() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    /** Returns the display name, with prefix if name conflict exists and is active */
    public String effectiveDisplayName() {
        if (displayName != null && !displayName.isEmpty()) {
            return displayName;
        }
        return username;
    }
}
