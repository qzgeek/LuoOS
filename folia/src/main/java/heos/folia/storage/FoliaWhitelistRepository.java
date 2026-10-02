package heos.folia.storage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.Optional;

/**
 * Single data-access boundary for the QQ whitelist and QQ blacklist tables.
 *
 * Feature modules must not issue SQL against qq_whitelist/qq_blacklist directly.
 * This class owns SQL, connection locking, result-set cleanup, normalization and
 * the semantics used by the bot, admin commands and login checks.
 */
public final class FoliaWhitelistRepository {
    public static final long ADMIN_SOURCE_QQ = 0L;

    private final FoliaStorage storage;
    private final Logger logger;
    private final OfficialBindingRepository officialBindings;

    public FoliaWhitelistRepository(FoliaStorage storage, Logger logger) {
        this.storage = storage;
        this.logger = logger;
        storage.initialize();
        officialBindings = new OfficialBindingRepository(storage, storage::getConnection, storage.isMySQL(), logger);
    }

    public List<String> getWhitelist(long qq) {
        List<String> result = new ArrayList<>();
        query("getWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT player_name FROM qq_whitelist WHERE qq = ? ORDER BY added_at")) {
                ps.setLong(1, qq);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(rs.getString("player_name"));
                }
            }
            return null;
        });
        return result;
    }

    public boolean isNameTaken(String playerName) {
        String name = normalize(playerName);
        return query("isNameTaken", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT 1 FROM qq_whitelist WHERE LOWER(player_name) = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        }, false);
    }

    public int getWhitelistCount(long qq) {
        return query("getWhitelistCount", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT COUNT(*) FROM qq_whitelist WHERE qq = ?")) {
                ps.setLong(1, qq);
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
            }
        }, 0);
    }

    public boolean hasWhitelist(long qq, String playerName) {
        return query("hasWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT 1 FROM qq_whitelist WHERE qq = ? AND LOWER(player_name) = ?")) {
                ps.setLong(1, qq);
                ps.setString(2, normalize(playerName));
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        }, false);
    }

    /** Adds a QQ-owned or administrator-owned entry. Returns false on duplicate. */
    public boolean addWhitelist(long qq, String playerName, String playerUuid) {
        if (!isValidName(playerName)) return false;
        return update("addWhitelist", () -> {
            String sql = storage.isMySQL()
                    ? "INSERT IGNORE INTO qq_whitelist (qq, player_name, player_uuid, added_at, frozen) VALUES (?, ?, ?, ?, 0)"
                    : "INSERT OR IGNORE INTO qq_whitelist (qq, player_name, player_uuid, added_at, frozen) VALUES (?, ?, ?, ?, 0)";
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                ps.setLong(1, qq);
                ps.setString(2, playerName);
                if (playerUuid == null || playerUuid.isBlank()) ps.setNull(3, java.sql.Types.VARCHAR);
                else ps.setString(3, playerUuid);
                ps.setLong(4, System.currentTimeMillis());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public boolean addAdminWhitelist(String playerName, String playerUuid) {
        return addWhitelist(ADMIN_SOURCE_QQ, playerName, playerUuid);
    }

    public boolean removeWhitelist(long qq, String playerName) {
        return update("removeWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "DELETE FROM qq_whitelist WHERE qq = ? AND LOWER(player_name) = ?")) {
                ps.setLong(1, qq);
                ps.setString(2, normalize(playerName));
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** Removes by player name regardless of whether the entry came from QQ or admin. */
    public boolean removeAnyWhitelist(String playerName) {
        return update("removeAnyWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "DELETE FROM qq_whitelist WHERE LOWER(player_name) = ?")) {
                ps.setString(1, normalize(playerName));
                return ps.executeUpdate() > 0;
            }
        });
    }

    public List<WhitelistEntry> getWhitelistEntries(long qq) {
        List<WhitelistEntry> result = new ArrayList<>();
        query("getWhitelistEntries", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT qq, player_name, player_uuid, frozen FROM qq_whitelist WHERE qq = ? ORDER BY added_at")) {
                ps.setLong(1, qq);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(readEntry(rs));
                }
            }
            return null;
        });
        return result;
    }

    public List<WhitelistEntry> getAllWhitelistEntries() {
        List<WhitelistEntry> result = new ArrayList<>();
        query("getAllWhitelistEntries", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT qq, player_name, player_uuid, frozen FROM qq_whitelist ORDER BY added_at");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) result.add(readEntry(rs));
            }
            return null;
        });
        return result;
    }

    public int freezeWhitelist(long qq) {
        return updateInt("freezeWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "UPDATE qq_whitelist SET frozen = 1 WHERE qq = ?")) {
                ps.setLong(1, qq);
                return ps.executeUpdate();
            }
        });
    }

    public int unfreezeWhitelist(long qq) {
        return updateInt("unfreezeWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "UPDATE qq_whitelist SET frozen = 0 WHERE qq = ?")) {
                ps.setLong(1, qq);
                return ps.executeUpdate();
            }
        });
    }

    public String findOwnedAccountName(long qq, String playerName) {
        return query("findOwnedAccountName", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT player_name FROM qq_whitelist WHERE qq = ? AND LOWER(player_name) = ?")) {
                ps.setLong(1, qq);
                ps.setString(2, normalize(playerName));
                try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
            }
        }, null);
    }

    public List<WhitelistOwner> findOwners(String playerName) {
        List<WhitelistOwner> result = new ArrayList<>();
        query("findOwners", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT qq, player_uuid, frozen FROM qq_whitelist WHERE LOWER(player_name) = ? ORDER BY added_at")) {
                ps.setString(1, normalize(playerName));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(new WhitelistOwner(
                            rs.getLong("qq"), rs.getString("player_uuid"), rs.getInt("frozen") == 1));
                }
            }
            return null;
        });
        return result;
    }

    public boolean hasEntries() {
        return query("hasEntries", () -> {
            try (PreparedStatement ps = connection().prepareStatement("SELECT 1 FROM qq_whitelist LIMIT 1");
                 ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }, false);
    }

    public boolean isActiveWhitelist(String playerName) {
        return query("isActiveWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT 1 FROM qq_whitelist WHERE LOWER(player_name) = ? AND (frozen = 0 OR frozen IS NULL)")) {
                ps.setString(1, normalize(playerName));
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        }, false);
    }

    public boolean isFrozenWhitelist(String playerName) {
        return query("isFrozenWhitelist", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT 1 FROM qq_whitelist WHERE LOWER(player_name) = ? AND frozen = 1")) {
                ps.setString(1, normalize(playerName));
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        }, false);
    }

    public boolean isBlacklisted(long qq) {
        return query("isBlacklisted", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT expiry FROM qq_blacklist WHERE qq = ?")) {
                ps.setLong(1, qq);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    long expiry = rs.getLong(1);
                    return rs.wasNull() || expiry > System.currentTimeMillis();
                }
            }
        }, false);
    }

    public boolean blacklist(long qq, Long durationSeconds, String reason) {
        return update("blacklist", () -> {
            Long expiry = durationSeconds == null ? null : System.currentTimeMillis() + durationSeconds * 1000L;
            String sql = storage.isMySQL()
                    ? "INSERT INTO qq_blacklist (qq, reason, banned_at, expiry) VALUES (?, ?, ?, ?) ON DUPLICATE KEY UPDATE reason=VALUES(reason), banned_at=VALUES(banned_at), expiry=VALUES(expiry)"
                    : "INSERT OR REPLACE INTO qq_blacklist (qq, reason, banned_at, expiry) VALUES (?, ?, ?, ?)";
            try (PreparedStatement ps = connection().prepareStatement(sql)) {
                ps.setLong(1, qq);
                ps.setString(2, reason);
                ps.setLong(3, System.currentTimeMillis());
                if (expiry == null) ps.setNull(4, java.sql.Types.BIGINT); else ps.setLong(4, expiry);
                ps.executeUpdate();
                return true;
            }
        });
    }

    public boolean unblacklist(long qq) {
        return update("unblacklist", () -> {
            try (PreparedStatement ps = connection().prepareStatement("DELETE FROM qq_blacklist WHERE qq = ?")) {
                ps.setLong(1, qq);
                ps.executeUpdate();
                return true;
            }
        });
    }

    public void createOfficialBindingTable() {
        officialBindings.initialize();
    }

    public Optional<Long> officialQq(String openid) {
        return officialBindings.officialQq(openid);
    }

    public boolean officialBindingExistsForQq(long qq) {
        return officialBindings.bindingExistsForQq(qq);
    }

    public boolean requestOfficialCode(String openid, long qq, String codeHash, long expiresAt,
                                       OfficialBindingRepository.MailDelivery delivery) {
        return officialBindings.requestCode(openid, qq, codeHash, expiresAt, System.currentTimeMillis(), delivery);
    }

    public boolean confirmOfficialCode(String openid, String codeHash, long now) {
        return officialBindings.confirmCode(openid, codeHash, now);
    }

    public String blacklistReasonForPlayer(String playerName) {
        return query("blacklistReasonForPlayer", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT b.reason, b.expiry FROM qq_blacklist b INNER JOIN qq_whitelist w ON b.qq = w.qq "
                            + "WHERE LOWER(w.player_name) = ? AND (b.expiry IS NULL OR b.expiry = 0 OR b.expiry > ?)")) {
                ps.setString(1, normalize(playerName));
                ps.setLong(2, System.currentTimeMillis());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    String reason = rs.getString("reason");
                    return reason == null || reason.isBlank() ? "" : reason;
                }
            }
        }, null);
    }

    public boolean isBlacklistedPlayer(String playerName) {
        return blacklistReasonForPlayer(playerName) != null;
    }

    public List<BlacklistEntry> getBlacklistEntries(int limit) {
        List<BlacklistEntry> result = new ArrayList<>();
        query("getBlacklistEntries", () -> {
            try (PreparedStatement ps = connection().prepareStatement(
                    "SELECT qq, reason, banned_at, expiry FROM qq_blacklist ORDER BY banned_at DESC LIMIT ?")) {
                ps.setInt(1, Math.max(1, Math.min(limit, 500)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) result.add(new BlacklistEntry(rs.getLong("qq"), rs.getString("reason"),
                            rs.getLong("banned_at"), rs.getObject("expiry", Long.class)));
                }
            }
            return null;
        });
        return result;
    }

    private WhitelistEntry readEntry(ResultSet rs) throws Exception {
        return new WhitelistEntry(rs.getLong("qq"), rs.getString("player_name"),
                rs.getString("player_uuid"), rs.getInt("frozen") == 1);
    }

    private java.sql.Connection connection() {
        return storage.getConnection();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValidName(String name) {
        return name != null && !name.isBlank();
    }

    @FunctionalInterface private interface SqlSupplier<T> { T get() throws Exception; }
    @FunctionalInterface private interface SqlUpdate { boolean run() throws Exception; }
    @FunctionalInterface private interface SqlInt { int run() throws Exception; }

    private <T> T query(String operation, SqlSupplier<T> action, T fallback) {
        try {
            synchronized (storage) { return action.get(); }
        } catch (Exception e) {
            logger.warning("[WhitelistRepository] " + operation + ": " + e.getMessage());
            return fallback;
        }
    }

    private void query(String operation, SqlSupplier<Void> action) {
        query(operation, action, null);
    }

    private boolean update(String operation, SqlUpdate action) {
        return query(operation, action::run, false);
    }

    private int updateInt(String operation, SqlInt action) {
        return query(operation, action::run, 0);
    }

    public static final class WhitelistEntry {
        public final long qq;
        public final String playerName;
        public final String playerUuid;
        public final boolean frozen;

        public WhitelistEntry(long qq, String playerName, String playerUuid, boolean frozen) {
            this.qq = qq;
            this.playerName = playerName;
            this.playerUuid = playerUuid;
            this.frozen = frozen;
        }
    }

    public static final class WhitelistOwner {
        public final long qq;
        public final String playerUuid;
        public final boolean frozen;

        public WhitelistOwner(long qq, String playerUuid, boolean frozen) {
            this.qq = qq;
            this.playerUuid = playerUuid;
            this.frozen = frozen;
        }
    }

    public static final class BlacklistEntry {
        public final long qq;
        public final String reason;
        public final long bannedAt;
        public final Long expiry;

        public BlacklistEntry(long qq, String reason, long bannedAt, Long expiry) {
            this.qq = qq;
            this.reason = reason;
            this.bannedAt = bannedAt;
            this.expiry = expiry;
        }
    }
}
