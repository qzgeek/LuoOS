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
    public transient String storageKey;

    public FoliaPlayerData(String username) {
        this.username = username;
    }

    public boolean isRegistered() {
        return passwordHash != null && !passwordHash.isEmpty();
    }
}
