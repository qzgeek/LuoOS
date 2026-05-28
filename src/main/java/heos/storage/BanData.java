package heos.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import heos.utils.HeosLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ban data storage
 */
public class BanData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BAN_FILE = "banned_player.json";
    
    public List<BanEntry> playerBans = new ArrayList<>();
    public List<IpBanEntry> ipBans = new ArrayList<>();
    
    public static class BanEntry {
        public String username;
        public UUID uuid;
        public String reason;
        public long bannedTime;
        public long expiryTime; // -1 for permanent
        public String bannedBy;
        
        public BanEntry(String username, UUID uuid, String reason, long expiryTime, String bannedBy) {
            this.username = username;
            this.uuid = uuid;
            this.reason = reason;
            this.bannedTime = System.currentTimeMillis();
            this.expiryTime = expiryTime;
            this.bannedBy = bannedBy;
        }
        
        public boolean isExpired() {
            return expiryTime != -1 && System.currentTimeMillis() > expiryTime;
        }
        
        public boolean isPermanent() {
            return expiryTime == -1;
        }
    }
    
    public static class IpBanEntry {
        public String ip;
        public String reason;
        public long bannedTime;
        public long expiryTime; // -1 for permanent
        public String bannedBy;
        
        public IpBanEntry(String ip, String reason, long expiryTime, String bannedBy) {
            this.ip = ip;
            this.reason = reason;
            this.bannedTime = System.currentTimeMillis();
            this.expiryTime = expiryTime;
            this.bannedBy = bannedBy;
        }
        
        public boolean isExpired() {
            return expiryTime != -1 && System.currentTimeMillis() > expiryTime;
        }
        
        public boolean isPermanent() {
            return expiryTime == -1;
        }
    }
    
    /**
     * Loads ban data from disk
     */
    public static BanData load() {
        try {
            File banFile = StoragePaths.file(BAN_FILE);
            migrateLegacyBanFile();

            if (!banFile.exists()) {
                return new BanData();
            }

            try (FileReader reader = new FileReader(banFile)) {
                BanData data = GSON.fromJson(reader, BanData.class);
                if (data == null) {
                    return new BanData();
                }

                data.ensureLists();
                data.removeExpiredBans();

                HeosLogger.info("Loaded " + data.playerBans.size() + " player bans and " + data.ipBans.size() + " IP bans from " + banFile.getPath());
                return data;
            }
        } catch (IOException e) {
            HeosLogger.error("Failed to load ban data", e);
            return new BanData();
        }
    }
    
    /**
     * Saves ban data to disk
     */
    public void save() {
        try {
            File banFile = StoragePaths.file(BAN_FILE);
            File parent = banFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(banFile)) {
                GSON.toJson(this, writer);
            }
            HeosLogger.debug("Saved ban data");
        } catch (IOException e) {
            HeosLogger.error("Failed to save ban data", e);
        }
    }

    private void ensureLists() {
        if (playerBans == null) {
            playerBans = new ArrayList<>();
        }
        if (ipBans == null) {
            ipBans = new ArrayList<>();
        }
    }

    /**
     * Removes expired bans and persists the cleanup immediately.
     */
    public boolean removeExpiredBans() {
        ensureLists();
        boolean removedPlayers = playerBans.removeIf(BanEntry::isExpired);
        boolean removedIps = ipBans.removeIf(IpBanEntry::isExpired);
        boolean removed = removedPlayers || removedIps;
        if (removed) {
            save();
            HeosLogger.info("Removed expired ban records");
        }
        return removed;
    }

    public static void migrateLegacyBanFile() {
        File banFile = StoragePaths.file(BAN_FILE);
        File legacyFile = new File(heos.Heos.gameDirectory.toFile(), "heos_bans.json");
        if (!banFile.exists() && legacyFile.exists()) {
            File parent = banFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            if (!legacyFile.renameTo(banFile)) {
                try (FileReader reader = new FileReader(legacyFile); FileWriter writer = new FileWriter(banFile)) {
                    char[] buffer = new char[8192];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        writer.write(buffer, 0, read);
                    }
                } catch (IOException e) {
                    HeosLogger.error("Failed to migrate legacy ban file", e);
                }
            }
        }
    }
    
    /**
     * Checks if player is banned
     */
    public BanEntry getPlayerBan(String username, UUID uuid) {
        removeExpiredBans();
        
        for (BanEntry ban : playerBans) {
            if (ban.username.equalsIgnoreCase(username) || (uuid != null && uuid.equals(ban.uuid))) {
                if (!ban.isExpired()) {
                    return ban;
                }
            }
        }
        return null;
    }
    
    /**
     * Checks if IP is banned
     */
    public IpBanEntry getIpBan(String ip) {
        removeExpiredBans();
        
        for (IpBanEntry ban : ipBans) {
            if (ban.ip.equals(ip)) {
                if (!ban.isExpired()) {
                    return ban;
                }
            }
        }
        return null;
    }
    
    /**
     * Adds a player ban
     */
    public void addPlayerBan(String username, UUID uuid, String reason, long expiryTime, String bannedBy) {
        removeExpiredBans();
        // Remove existing ban
        playerBans.removeIf(ban -> ban.username.equalsIgnoreCase(username) || (uuid != null && uuid.equals(ban.uuid)));
        
        playerBans.add(new BanEntry(username, uuid, reason, expiryTime, bannedBy));
        save();
    }
    
    /**
     * Adds an IP ban
     */
    public void addIpBan(String ip, String reason, long expiryTime, String bannedBy) {
        removeExpiredBans();
        // Remove existing ban
        ipBans.removeIf(ban -> ban.ip.equals(ip));
        
        ipBans.add(new IpBanEntry(ip, reason, expiryTime, bannedBy));
        save();
    }
    
    /**
     * Removes a player ban
     */
    public boolean removePlayerBan(String username) {
        boolean removed = playerBans.removeIf(ban -> ban.username.equalsIgnoreCase(username));
        if (removed) {
            save();
        }
        return removed;
    }
    
    /**
     * Removes an IP ban
     */
    public boolean removeIpBan(String ip) {
        boolean removed = ipBans.removeIf(ban -> ban.ip.equals(ip));
        if (removed) {
            save();
        }
        return removed;
    }
}


