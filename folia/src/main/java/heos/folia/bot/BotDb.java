package heos.folia.bot;

import heos.folia.storage.FoliaStorage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * QQ whitelist/blacklist operations using the shared LuoOS database.
 * Whitelist entries also serve as QQ-MC account bindings.
 *
 * THREAD SAFETY / TRANSACTION HYGIENE:
 * 1. Every SQL block is wrapped in {@code synchronized (storage)} — the same
 *    monitor FoliaStorage uses for load()/saveSync(), so the shared single
 *    SQLite connection is never used by two threads at once.
 * 2. Every ResultSet/PreparedStatement is closed (try-with-resources). Leaving
 *    a ResultSet open keeps the connection inside a read transaction (snapshot);
 *    the next write on the SAME connection then fails with SQLITE_BUSY_SNAPSHOT
 *    ("Another database connection has already written..." — misleading message,
 *    it's actually the same connection's stale snapshot). This silently lost
 *    whitelist INSERTs and password-reset saves.
 */
public class BotDb {
    private final Logger logger;
    private final FoliaStorage storage;

    public BotDb(Logger logger, FoliaStorage storage) {
        this.logger = logger;
        this.storage = storage;
        initTables();
    }

    private void initTables() {
        storage.initialize();
        // Tables are created in FoliaStorage.createTables()
    }

    // ---- Whitelist (also QQ→MC binding) ----

    public List<String> getWhitelist(long qq) {
        List<String> players = new ArrayList<>();
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "SELECT player_name FROM qq_whitelist WHERE qq = ?")) {
                    ps.setLong(1, qq);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) players.add(rs.getString("player_name"));
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] getWhitelist: " + e.getMessage());
        }
        return players;
    }

    /** Check whether a player name is already claimed by ANY QQ (including frozen entries). */
    public boolean isNameTaken(String playerName) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "SELECT 1 FROM qq_whitelist WHERE player_name = ?")) {
                    ps.setString(1, playerName);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] isNameTaken: " + e.getMessage());
            return false; // on error, allow — DB index will catch the dup
        }
    }

    public int getWhitelistCount(long qq) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "SELECT COUNT(*) FROM qq_whitelist WHERE qq = ?")) {
                    ps.setLong(1, qq);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) return rs.getInt(1);
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] count: " + e.getMessage());
        }
        return 0;
    }

    public boolean hasWhitelist(long qq, String playerName) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "SELECT 1 FROM qq_whitelist WHERE qq = ? AND player_name = ?")) {
                    ps.setLong(1, qq);
                    ps.setString(2, playerName);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next();
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void addWhitelist(long qq, String playerName, String playerUuid) {
        try {
            synchronized (storage) {
                String sql = storage.isMySQL()
                        ? "INSERT IGNORE INTO qq_whitelist (qq, player_name, player_uuid, added_at) VALUES (?, ?, ?, ?)"
                        : "INSERT OR IGNORE INTO qq_whitelist (qq, player_name, player_uuid, added_at) VALUES (?, ?, ?, ?)";
                try (PreparedStatement ps = storage.getConnection().prepareStatement(sql)) {
                    ps.setLong(1, qq);
                    ps.setString(2, playerName);
                    ps.setString(3, playerUuid);
                    ps.setLong(4, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] addWhitelist: " + e.getMessage());
        }
    }

    public void removeWhitelist(long qq, String playerName) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "DELETE FROM qq_whitelist WHERE qq = ? AND player_name = ?")) {
                    ps.setLong(1, qq);
                    ps.setString(2, playerName);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] removeWhitelist: " + e.getMessage());
        }
    }

    // ---- Whitelist freeze (退群/踢群冻结，重新进群恢复) ----

    /** One whitelist entry: player + freeze flag. */
    public static final class WhitelistEntry {
        public final String playerName;
        public final String playerUuid;
        public final boolean frozen;

        WhitelistEntry(String playerName, String playerUuid, boolean frozen) {
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.frozen = frozen;
        }
    }

    /** All whitelist entries of a QQ, including the frozen flag (for display + freeze ops). */
    public List<WhitelistEntry> getWhitelistEntries(long qq) {
        List<WhitelistEntry> entries = new ArrayList<>();
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "SELECT player_name, player_uuid, frozen FROM qq_whitelist WHERE qq = ? ORDER BY added_at")) {
                    ps.setLong(1, qq);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            boolean frozen = false;
                            try { frozen = rs.getInt("frozen") == 1; } catch (Exception ignored) {}
                            entries.add(new WhitelistEntry(rs.getString("player_name"), rs.getString("player_uuid"), frozen));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] getWhitelistEntries: " + e.getMessage());
        }
        return entries;
    }

    /** Mark all whitelist entries of a QQ as frozen. Returns number of entries affected. */
    public int freezeWhitelist(long qq) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "UPDATE qq_whitelist SET frozen = 1 WHERE qq = ?")) {
                    ps.setLong(1, qq);
                    return ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] freezeWhitelist: " + e.getMessage());
        }
        return 0;
    }

    /** Clear the frozen flag for all whitelist entries of a QQ. Returns number of entries affected. */
    public int unfreezeWhitelist(long qq) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "UPDATE qq_whitelist SET frozen = 0 WHERE qq = ?")) {
                    ps.setLong(1, qq);
                    return ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] unfreezeWhitelist: " + e.getMessage());
        }
        return 0;
    }

    // ---- Blacklist ----

    public boolean isBlacklisted(long qq) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "SELECT expiry FROM qq_blacklist WHERE qq = ?")) {
                    ps.setLong(1, qq);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            long expiry = rs.getLong("expiry");
                            if (rs.wasNull()) return true; // permanent
                            return expiry > System.currentTimeMillis();
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return false;
    }

    public void blacklist(long qq, Long durationSeconds, String reason) {
        try {
            synchronized (storage) {
                Long expiry = durationSeconds != null ? System.currentTimeMillis() + durationSeconds * 1000 : null;
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "INSERT OR REPLACE INTO qq_blacklist (qq, reason, banned_at, expiry) VALUES (?, ?, ?, ?)")) {
                    ps.setLong(1, qq);
                    ps.setString(2, reason);
                    ps.setLong(3, System.currentTimeMillis());
                    if (expiry != null) ps.setLong(4, expiry);
                    else ps.setNull(4, java.sql.Types.BIGINT);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] blacklist: " + e.getMessage());
        }
    }

    public void unblacklist(long qq) {
        try {
            synchronized (storage) {
                try (PreparedStatement ps = storage.getConnection().prepareStatement(
                        "DELETE FROM qq_blacklist WHERE qq = ?")) {
                    ps.setLong(1, qq);
                    ps.executeUpdate();
                }
            }
        } catch (Exception e) {
            logger.warning("[BotDb] unblacklist: " + e.getMessage());
        }
    }
}
