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
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT player_name FROM qq_whitelist WHERE qq = ?");
            ps.setLong(1, qq);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) players.add(rs.getString("player_name"));
        } catch (Exception e) {
            logger.warning("[BotDb] getWhitelist: " + e.getMessage());
        }
        return players;
    }

    public int getWhitelistCount(long qq) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM qq_whitelist WHERE qq = ?");
            ps.setLong(1, qq);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        } catch (Exception e) {
            logger.warning("[BotDb] count: " + e.getMessage());
        }
        return 0;
    }

    public boolean hasWhitelist(long qq, String playerName) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM qq_whitelist WHERE qq = ? AND player_name = ?");
            ps.setLong(1, qq);
            ps.setString(2, playerName);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (Exception e) {
            return false;
        }
    }

    public void addWhitelist(long qq, String playerName, String playerUuid) {
        try {
            var conn = storage.getConnection();
            String sql = storage.isMySQL()
                    ? "INSERT IGNORE INTO qq_whitelist (qq, player_name, player_uuid, added_at) VALUES (?, ?, ?, ?)"
                    : "INSERT OR IGNORE INTO qq_whitelist (qq, player_name, player_uuid, added_at) VALUES (?, ?, ?, ?)";
            PreparedStatement ps = conn.prepareStatement(sql);
            ps.setLong(1, qq);
            ps.setString(2, playerName);
            ps.setString(3, playerUuid);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warning("[BotDb] addWhitelist: " + e.getMessage());
        }
    }

    public void removeWhitelist(long qq, String playerName) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM qq_whitelist WHERE qq = ? AND player_name = ?");
            ps.setLong(1, qq);
            ps.setString(2, playerName);
            ps.executeUpdate();
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
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT player_name, player_uuid, frozen FROM qq_whitelist WHERE qq = ? ORDER BY added_at");
            ps.setLong(1, qq);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                boolean frozen = false;
                try { frozen = rs.getInt("frozen") == 1; } catch (Exception ignored) {}
                entries.add(new WhitelistEntry(rs.getString("player_name"), rs.getString("player_uuid"), frozen));
            }
        } catch (Exception e) {
            logger.warning("[BotDb] getWhitelistEntries: " + e.getMessage());
        }
        return entries;
    }

    /** Mark all whitelist entries of a QQ as frozen. Returns number of entries affected. */
    public int freezeWhitelist(long qq) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE qq_whitelist SET frozen = 1 WHERE qq = ?");
            ps.setLong(1, qq);
            return ps.executeUpdate();
        } catch (Exception e) {
            logger.warning("[BotDb] freezeWhitelist: " + e.getMessage());
        }
        return 0;
    }

    /** Clear the frozen flag for all whitelist entries of a QQ. Returns number of entries affected. */
    public int unfreezeWhitelist(long qq) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "UPDATE qq_whitelist SET frozen = 0 WHERE qq = ?");
            ps.setLong(1, qq);
            return ps.executeUpdate();
        } catch (Exception e) {
            logger.warning("[BotDb] unfreezeWhitelist: " + e.getMessage());
        }
        return 0;
    }

    // ---- Blacklist ----

    public boolean isBlacklisted(long qq) {
        try {
            var conn = storage.getConnection();
            PreparedStatement ps = conn.prepareStatement(
                    "SELECT expiry FROM qq_blacklist WHERE qq = ?");
            ps.setLong(1, qq);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                long expiry = rs.getLong("expiry");
                if (rs.wasNull()) return true; // permanent
                return expiry > System.currentTimeMillis();
            }
        } catch (Exception e) {}
        return false;
    }

    public void blacklist(long qq, Long durationSeconds, String reason) {
        try {
            var conn = storage.getConnection();
            Long expiry = durationSeconds != null ? System.currentTimeMillis() + durationSeconds * 1000 : null;
            PreparedStatement ps = conn.prepareStatement(
                    "INSERT OR REPLACE INTO qq_blacklist (qq, reason, banned_at, expiry) VALUES (?, ?, ?, ?)");
            ps.setLong(1, qq);
            ps.setString(2, reason);
            ps.setLong(3, System.currentTimeMillis());
            if (expiry != null) ps.setLong(4, expiry);
            else ps.setNull(4, java.sql.Types.BIGINT);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warning("[BotDb] blacklist: " + e.getMessage());
        }
    }

    public void unblacklist(long qq) {
        try {
            var conn = storage.getConnection();
            conn.prepareStatement("DELETE FROM qq_blacklist WHERE qq = ?").executeUpdate();
            // Need to set the parameter... let me fix
            PreparedStatement ps = conn.prepareStatement("DELETE FROM qq_blacklist WHERE qq = ?");
            ps.setLong(1, qq);
            ps.executeUpdate();
        } catch (Exception e) {
            logger.warning("[BotDb] unblacklist: " + e.getMessage());
        }
    }
}
