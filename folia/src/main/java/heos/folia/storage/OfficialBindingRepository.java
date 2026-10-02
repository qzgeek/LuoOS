package heos.folia.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/** Verified identities and untrusted email challenges deliberately live in separate tables. */
public final class OfficialBindingRepository {
    public static final int MAX_ATTEMPTS = 5;
    public static final long REQUEST_COOLDOWN_MS = 60_000L;
    private final Object lock;
    private final Supplier<Connection> connections;
    private final boolean mysql;
    private final Logger logger;
    private boolean ready;

    public OfficialBindingRepository(Object lock, Supplier<Connection> connections, boolean mysql, Logger logger) {
        this.lock = lock;
        this.connections = connections;
        this.mysql = mysql;
        this.logger = logger;
    }

    @FunctionalInterface public interface MailDelivery { void send() throws Exception; }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws Exception; }

    public void initialize() {
        synchronized (lock) {
            ready = false;
            try {
                Connection c = connections.get();
                if (!c.getAutoCommit()) throw new SQLException("Existing transaction");
                // MySQL DDL commits implicitly: create empty tables BEFORE the atomic data migration.
                String engine = mysql ? " ENGINE=InnoDB" : "";
                try (var s = c.createStatement()) {
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS qq_official_bindings (openid VARCHAR(128) PRIMARY KEY, qq BIGINT NOT NULL UNIQUE, code_hash VARCHAR(128), expires_at BIGINT, created_at BIGINT NOT NULL)" + engine);
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS qq_official_binding_archive (openid VARCHAR(128), qq BIGINT, code_hash VARCHAR(128), expires_at BIGINT, created_at BIGINT, archived_at BIGINT NOT NULL)" + engine);
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS qq_official_pending (openid VARCHAR(128) PRIMARY KEY, qq BIGINT NOT NULL, code_hash VARCHAR(128) NOT NULL, expires_at BIGINT NOT NULL, attempts INTEGER NOT NULL, created_at BIGINT NOT NULL)" + engine);
                    s.executeUpdate("CREATE TABLE IF NOT EXISTS qq_official_request_limits (request_key VARCHAR(160) PRIMARY KEY, next_at BIGINT NOT NULL, token VARCHAR(36) NOT NULL)" + engine);
                }
                if (mysql) {
                    try (var s = c.createStatement(); var rs = s.executeQuery("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('qq_official_bindings','qq_official_binding_archive','qq_official_pending','qq_official_request_limits')")) {
                        int count = 0;
                        while (rs.next()) {
                            count++;
                            if (!"InnoDB".equalsIgnoreCase(rs.getString(1))) throw new SQLException("Official binding tables require InnoDB");
                        }
                        if (count != 4) throw new SQLException("Missing official binding tables");
                    }
                }
                transaction(cn -> {
                    // Obtain write locks before copying; rollback preserves BOTH source and evidence.
                    execute(cn, "UPDATE qq_official_bindings SET created_at = created_at WHERE code_hash IS NOT NULL");
                    execute(cn, "INSERT INTO qq_official_binding_archive (openid,qq,code_hash,expires_at,created_at,archived_at) SELECT openid,qq,code_hash,expires_at,created_at,? FROM qq_official_bindings WHERE code_hash IS NOT NULL", System.currentTimeMillis());
                    execute(cn, "DELETE FROM qq_official_bindings WHERE code_hash IS NOT NULL");
                    return true;
                });
                ready = true;
            } catch (Exception e) {
                throw new IllegalStateException("Official binding migration failed; identities disabled", e);
            }
        }
    }

    public Optional<Long> officialQq(String openid) {
        synchronized (lock) {
            if (!ready) return Optional.empty();
            try (var ps = statement(connections.get(), "SELECT qq FROM qq_official_bindings WHERE openid = ? AND code_hash IS NULL", openid);
                 var rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty();
            } catch (Exception e) { failure("read"); return Optional.empty(); }
        }
    }

    public boolean bindingExistsForQq(long qq) {
        synchronized (lock) {
            if (!ready) return true; // Fail closed for callers checking availability.
            try { return exists(connections.get(), "SELECT 1 FROM qq_official_bindings WHERE qq = ? AND code_hash IS NULL", qq); }
            catch (Exception e) { failure("availability"); return true; }
        }
    }

    /** 绑定申请的失败原因；用于给用户可读提示，而不是笼统的“失败”。 */
    public enum RequestResult { OK, ALREADY_BOUND, RATE_LIMITED, MAIL_FAILED, STORAGE_FAILED, INVALID }

    /** Reserves only a rate-limit ticket, sends mail, then stores a challenge. Never writes identity. */
    public boolean requestCode(String openid, long qq, String codeHash, long expiresAt, long now, MailDelivery delivery) {
        return requestCodeDetailed(openid, qq, codeHash, expiresAt, now, delivery) == RequestResult.OK;
    }

    /** 与 requestCode 相同，但返回具体失败原因。 */
    public RequestResult requestCodeDetailed(String openid, long qq, String codeHash, long expiresAt, long now, MailDelivery delivery) {
        if (openid == null || openid.isBlank() || openid.length() > 128 || qq <= 0
                || codeHash == null || codeHash.isBlank() || expiresAt <= now) return RequestResult.INVALID;
        String ticket = UUID.randomUUID().toString();
        boolean reserved = run("reserve", c -> {
            if (exists(c, "SELECT 1 FROM qq_official_bindings WHERE openid = ? OR qq = ?", openid, qq)) return false;
            reserve(c, "openid:" + openid, now, ticket);
            reserve(c, "qq:" + qq, now, ticket);
            execute(c, "DELETE FROM qq_official_pending WHERE openid = ?", openid);
            return true;
        });
        if (!reserved) {
            // 区分“已绑定”和“冷却中/数据库异常”，避免用户无法判断该等还是该换号。
            boolean bound = run("probe bound", c -> exists(c, "SELECT 1 FROM qq_official_bindings WHERE openid = ? OR qq = ?", openid, qq));
            return bound ? RequestResult.ALREADY_BOUND : RequestResult.RATE_LIMITED;
        }
        // No storage lock or SQL transaction is held during network I/O. Failed mail leaves no new challenge.
        try { delivery.send(); }
        catch (Exception e) {
            // 保留真实原因，否则发信失败只能看到一句笼统警告，无法排查。
            failure("mail delivery", e);
            return RequestResult.MAIL_FAILED;
        }
        boolean stored = run("save challenge", c -> {
            // A slow, older mail request cannot overwrite a more recent request or a confirmed identity.
            execute(c, "UPDATE qq_official_request_limits SET next_at = next_at WHERE request_key = ?", "openid:" + openid);
            if (!exists(c, "SELECT 1 FROM qq_official_request_limits WHERE request_key = ? AND token = ?", "openid:" + openid, ticket)
                    || expiresAt <= System.currentTimeMillis()
                    || exists(c, "SELECT 1 FROM qq_official_bindings WHERE openid = ? OR qq = ?", openid, qq)) return false;
            execute(c, "INSERT INTO qq_official_pending (openid,qq,code_hash,expires_at,attempts,created_at) VALUES (?,?,?,?,0,?)", openid, qq, codeHash, expiresAt, now);
            return true;
        });
        return stored ? RequestResult.OK : RequestResult.STORAGE_FAILED;
    }

    private void reserve(Connection c, String key, long now, String ticket) throws SQLException {
        execute(c, (mysql ? "INSERT IGNORE" : "INSERT OR IGNORE") + " INTO qq_official_request_limits (request_key,next_at,token) VALUES (?,0,'')", key);
        if (execute(c, "UPDATE qq_official_request_limits SET next_at = ?, token = ? WHERE request_key = ? AND next_at <= ?", now + REQUEST_COOLDOWN_MS, ticket, key, now) != 1)
            throw new SQLException("Request cooldown");
    }

    public boolean confirmCode(String openid, String codeHash, long now) {
        return run("confirm", c -> {
            // This first write serializes attempts even across independent SQLite/MySQL connections.
            int changed = execute(c, "UPDATE qq_official_pending SET attempts = attempts + 1 WHERE openid = ? AND expires_at > ? AND attempts < ?", openid, now, MAX_ATTEMPTS);
            if (changed != 1) return false;
            Long qq = null;
            try (var ps = statement(c, "SELECT qq FROM qq_official_pending WHERE openid = ? AND code_hash = ? AND expires_at > ?", openid, codeHash, now);
                 var rs = ps.executeQuery()) { if (rs.next()) qq = rs.getLong(1); }
            if (qq == null) return false; // Commit the failed attempt, not an identity.
            if (exists(c, "SELECT 1 FROM qq_official_bindings WHERE openid = ? OR qq = ?", openid, qq)) return false;
            execute(c, "INSERT INTO qq_official_bindings (openid,qq,code_hash,expires_at,created_at) VALUES (?,?,NULL,NULL,?)", openid, qq, now);
            if (execute(c, "DELETE FROM qq_official_pending WHERE openid = ?", openid) != 1) throw new SQLException("Challenge not consumed");
            return true;
        });
    }

    private boolean run(String operation, Work<Boolean> work) {
        synchronized (lock) {
            if (!ready) return false;
            try { return transaction(work); }
            catch (Exception e) { failure(operation); return false; }
        }
    }

    private <T> T transaction(Work<T> work) throws Exception {
        Connection c = connections.get();
        if (!c.getAutoCommit()) throw new SQLException("Existing transaction");
        c.setAutoCommit(false);
        try {
            T result = work.run(c);
            c.commit();
            return result;
        } catch (Exception e) {
            try { c.rollback(); }
            catch (SQLException rollback) {
                e.addSuppressed(rollback);
                try { c.close(); } catch (SQLException close) { e.addSuppressed(close); }
            }
            throw e;
        } finally {
            // Never enable autocommit after failed rollback (it could commit partial work).
            if (!c.isClosed()) c.setAutoCommit(true);
        }
    }

    private static PreparedStatement statement(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        } catch (SQLException e) { ps.close(); throw e; }
    }
    private static int execute(Connection c, String sql, Object... args) throws SQLException {
        try (var ps = statement(c, sql, args)) { return ps.executeUpdate(); }
    }
    private static boolean exists(Connection c, String sql, Object... args) throws SQLException {
        try (var ps = statement(c, sql, args); var rs = ps.executeQuery()) { return rs.next(); }
    }
    private void failure(String operation) { logger.warning("[OfficialBindingRepository] " + operation + " rejected or failed; no identity granted"); }

    /** 带原因的失败日志；只输出异常类型与消息，不含凭据。 */
    private void failure(String operation, Throwable cause) {
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        logger.warning("[OfficialBindingRepository] " + operation + " rejected or failed; no identity granted — "
                + root.getClass().getSimpleName() + ": " + root.getMessage());
    }
}
