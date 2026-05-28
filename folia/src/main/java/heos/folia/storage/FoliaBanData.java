package heos.folia.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public final class FoliaBanData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public List<BanEntry> playerBans = new ArrayList<>();
    public List<IpBanEntry> ipBans = new ArrayList<>();

    private transient Path file;
    private transient Logger logger;

    public static FoliaBanData load(Path root, Logger logger) {
        Path file = root.resolve("banned_player.json");
        try {
            Files.createDirectories(root);
            if (!Files.exists(file)) {
                FoliaBanData data = new FoliaBanData();
                data.attach(file, logger);
                data.save();
                return data;
            }
            try (Reader reader = Files.newBufferedReader(file)) {
                FoliaBanData data = GSON.fromJson(reader, FoliaBanData.class);
                if (data == null) {
                    data = new FoliaBanData();
                }
                data.attach(file, logger);
                data.ensureLists();
                data.removeExpired();
                return data;
            }
        } catch (IOException exception) {
            logger.warning("Failed to load Heos bans: " + exception.getMessage());
            FoliaBanData data = new FoliaBanData();
            data.attach(file, logger);
            return data;
        }
    }

    public synchronized BanEntry getPlayerBan(String username, UUID uuid) {
        removeExpired();
        for (BanEntry ban : playerBans) {
            if (ban.username.equalsIgnoreCase(username) || (uuid != null && uuid.equals(ban.uuid))) {
                return ban;
            }
        }
        return null;
    }

    public synchronized IpBanEntry getIpBan(String ip) {
        removeExpired();
        for (IpBanEntry ban : ipBans) {
            if (ban.ip.equals(ip)) {
                return ban;
            }
        }
        return null;
    }

    public synchronized void addPlayerBan(String username, UUID uuid, String reason, long expiryTime, String bannedBy) {
        removeExpired();
        playerBans.removeIf(ban -> ban.username.equalsIgnoreCase(username) || (uuid != null && uuid.equals(ban.uuid)));
        playerBans.add(new BanEntry(username, uuid, reason, System.currentTimeMillis(), expiryTime, bannedBy));
        save();
    }

    public synchronized void addIpBan(String ip, String reason, long expiryTime, String bannedBy) {
        removeExpired();
        ipBans.removeIf(ban -> ban.ip.equals(ip));
        ipBans.add(new IpBanEntry(ip, reason, System.currentTimeMillis(), expiryTime, bannedBy));
        save();
    }

    public synchronized boolean removePlayerBan(String username) {
        boolean removed = playerBans.removeIf(ban -> ban.username.equalsIgnoreCase(username));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized boolean removeIpBan(String ip) {
        boolean removed = ipBans.removeIf(ban -> ban.ip.equals(ip));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized boolean removeExpired() {
        ensureLists();
        boolean removed = playerBans.removeIf(BanEntry::isExpired) | ipBans.removeIf(IpBanEntry::isExpired);
        if (removed) {
            save();
        }
        return removed;
    }

    synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException exception) {
            logger.warning("Failed to save Heos bans: " + exception.getMessage());
        }
    }

    private void attach(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    private void ensureLists() {
        if (playerBans == null) {
            playerBans = new ArrayList<>();
        }
        if (ipBans == null) {
            ipBans = new ArrayList<>();
        }
    }

    public static final class BanEntry {
        public String username;
        public UUID uuid;
        public String reason;
        public long bannedTime;
        public long expiryTime;
        public String bannedBy;

        BanEntry(String username, UUID uuid, String reason, long bannedTime, long expiryTime, String bannedBy) {
            this.username = username;
            this.uuid = uuid;
            this.reason = reason;
            this.bannedTime = bannedTime;
            this.expiryTime = expiryTime;
            this.bannedBy = bannedBy;
        }

        boolean isExpired() {
            return expiryTime != -1L && System.currentTimeMillis() > expiryTime;
        }
    }

    public static final class IpBanEntry {
        public String ip;
        public String reason;
        public long bannedTime;
        public long expiryTime;
        public String bannedBy;

        IpBanEntry(String ip, String reason, long bannedTime, long expiryTime, String bannedBy) {
            this.ip = ip;
            this.reason = reason;
            this.bannedTime = bannedTime;
            this.expiryTime = expiryTime;
            this.bannedBy = bannedBy;
        }

        boolean isExpired() {
            return expiryTime != -1L && System.currentTimeMillis() > expiryTime;
        }
    }
}
