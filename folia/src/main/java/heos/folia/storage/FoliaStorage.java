package heos.folia.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Logger;

public final class FoliaStorage {
    private static final Gson GSON = new GsonBuilder().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOGGER = Logger.getLogger("Heos");
    private static final String TABLE = "players";
    private static final String BIND_TABLE = "bindings";

    private final Path root;
    private Connection connection;
    private SecretKeySpec key;

    public FoliaStorage(Path root) {
        this.root = root;
    }

    public synchronized void initialize() {
        if (connection != null) {
            return;
        }
        try {
            Files.createDirectories(root);
            Class.forName("org.sqlite.JDBC");
            connection = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("player_data.db").toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=NORMAL");

                // Main players table — UUID is the primary lookup key
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                                + "uuid TEXT NOT NULL PRIMARY KEY,"
                                + "username TEXT NOT NULL,"
                                + "username_lower TEXT NOT NULL,"
                                + "last_ip TEXT NULL,"
                                + "data TEXT NOT NULL"
                                + ");");
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_players_username_lower ON " + TABLE + "(username_lower);");

                // Bindings table for account linking
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS " + BIND_TABLE + " ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "old_uuid TEXT NOT NULL,"
                                + "new_uuid TEXT NOT NULL UNIQUE,"
                                + "old_name TEXT NOT NULL,"
                                + "new_name TEXT NOT NULL,"
                                + "status TEXT NOT NULL DEFAULT 'pending',"
                                + "created_at INTEGER NOT NULL"
                                + ");");
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_bindings_old_uuid ON " + BIND_TABLE + "(old_uuid);");
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_bindings_new_uuid ON " + BIND_TABLE + "(new_uuid);");
                statement.executeUpdate(
                        "CREATE INDEX IF NOT EXISTS idx_bindings_status ON " + BIND_TABLE + "(status);");
            }
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to initialize Heos Folia storage", exception);
        }
    }

    // === Player Data (UUID-based) ===

    /** Load player data by UUID. Returns null if not found. */
    public synchronized FoliaPlayerData load(UUID uuid) {
        initialize();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, username, last_ip, data FROM " + TABLE + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                FoliaPlayerData data = GSON.fromJson(decrypt(resultSet.getString("data")), FoliaPlayerData.class);
                if (data == null) {
                    data = new FoliaPlayerData(resultSet.getString("username"));
                }
                data.uuid = UUID.fromString(resultSet.getString("uuid"));
                data.username = resultSet.getString("username");
                data.lastIp = resultSet.getString("last_ip");
                return data;
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to load player data for UUID " + uuid + ": " + exception.getMessage());
            return null;
        }
    }

    /** Load player data by username. For commands that reference by name. */
    public synchronized FoliaPlayerData load(String username) {
        initialize();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, username, last_ip, data FROM " + TABLE + " WHERE username_lower = ?")) {
            statement.setString(1, username.toLowerCase(Locale.ENGLISH));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                // If there are multiple rows with same username_lower, return null (ambiguous)
                // Caller should check count first
                FoliaPlayerData data = GSON.fromJson(decrypt(resultSet.getString("data")), FoliaPlayerData.class);
                if (data == null) {
                    data = new FoliaPlayerData(resultSet.getString("username"));
                }
                data.uuid = UUID.fromString(resultSet.getString("uuid"));
                data.username = resultSet.getString("username");
                data.lastIp = resultSet.getString("last_ip");
                return data;
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to load player data for " + username + ": " + exception.getMessage());
            return null;
        }
    }

    /** Load all player data rows matching a username (for conflict detection). */
    public synchronized List<FoliaPlayerData> loadAllByName(String username) {
        initialize();
        List<FoliaPlayerData> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT uuid, username, last_ip, data FROM " + TABLE + " WHERE username_lower = ?")) {
            statement.setString(1, username.toLowerCase(Locale.ENGLISH));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    FoliaPlayerData data = GSON.fromJson(decrypt(resultSet.getString("data")), FoliaPlayerData.class);
                    if (data == null) {
                        data = new FoliaPlayerData(resultSet.getString("username"));
                    }
                    data.uuid = UUID.fromString(resultSet.getString("uuid"));
                    data.username = resultSet.getString("username");
                    data.lastIp = resultSet.getString("last_ip");
                    results.add(data);
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to load player data for name " + username + ": " + exception.getMessage());
        }
        return results;
    }

    /** Count rows with the same username_lower (for conflict detection). */
    public synchronized int countByName(String username) {
        initialize();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + TABLE + " WHERE username_lower = ?")) {
            statement.setString(1, username.toLowerCase(Locale.ENGLISH));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getInt(1);
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to count player data for " + username + ": " + exception.getMessage());
        }
        return 0;
    }

    public synchronized void save(FoliaPlayerData data) {
        initialize();
        if (data.uuid == null) {
            LOGGER.warning("Cannot save player data with null UUID for " + data.username);
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + TABLE + " (uuid, username, username_lower, last_ip, data) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT(uuid) DO UPDATE SET username = excluded.username, username_lower = excluded.username_lower, last_ip = excluded.last_ip, data = excluded.data")) {
            statement.setString(1, data.uuid.toString());
            statement.setString(2, data.username);
            statement.setString(3, data.username.toLowerCase(Locale.ENGLISH));
            statement.setString(4, data.lastIp);
            statement.setString(5, encrypt(GSON.toJson(data)));
            statement.executeUpdate();
        } catch (Exception exception) {
            LOGGER.warning("Failed to save player data for " + data.username + ": " + exception.getMessage());
        }
    }

    public synchronized boolean delete(UUID uuid) {
        initialize();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE uuid = ?")) {
            statement.setString(1, uuid.toString());
            return statement.executeUpdate() > 0;
        } catch (Exception exception) {
            LOGGER.warning("Failed to delete player data for UUID " + uuid + ": " + exception.getMessage());
            return false;
        }
    }

    // === Account Bindings ===

    public static final class BindingEntry {
        public final long id;
        public final UUID oldUuid;
        public final UUID newUuid;
        public final String oldName;
        public final String newName;
        public final String status; // "pending" or "active"
        public final long createdAt;

        public BindingEntry(long id, UUID oldUuid, UUID newUuid, String oldName, String newName, String status, long createdAt) {
            this.id = id;
            this.oldUuid = oldUuid;
            this.newUuid = newUuid;
            this.oldName = oldName;
            this.newName = newName;
            this.status = status;
            this.createdAt = createdAt;
        }
    }

    /** Request a binding: newPlayer wants to bind to oldPlayer */
    public synchronized BindingEntry requestBinding(UUID newUuid, String newName, String oldName) {
        initialize();
        long now = System.currentTimeMillis();
        // The oldName here is just the username — we need oldUUID to be filled in later
        // We'll use a placeholder; the accept step fills it in
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO " + BIND_TABLE + " (old_uuid, new_uuid, old_name, new_name, status, created_at) VALUES (?, ?, ?, ?, 'pending', ?)")) {
            // old_uuid is not known yet — use a sentinel
            String placeholderOldUuid = "pending:" + oldName.toLowerCase(Locale.ENGLISH);
            statement.setString(1, placeholderOldUuid);
            statement.setString(2, newUuid.toString());
            statement.setString(3, oldName);
            statement.setString(4, newName);
            statement.setLong(5, now);
            statement.executeUpdate();
        } catch (Exception exception) {
            LOGGER.warning("Failed to create binding request: " + exception.getMessage());
            return null;
        }
        // Retrieve the inserted row
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, old_uuid, new_uuid, old_name, new_name, status, created_at FROM " + BIND_TABLE
                        + " WHERE new_uuid = ? AND status = 'pending' ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, newUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return readBindingEntry(rs);
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to read binding request: " + exception.getMessage());
        }
        return null;
    }

    /** Accept a binding: old player confirms they want to bind to newPlayer. Both must match. */
    public synchronized BindingEntry acceptBinding(UUID oldUuid, String oldName, String newName) {
        initialize();
        // Find pending binding where old_name matches and new_name matches
        String placeholderOldUuid = "pending:" + oldName.toLowerCase(Locale.ENGLISH);
        BindingEntry found = null;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, old_uuid, new_uuid, old_name, new_name, status, created_at FROM " + BIND_TABLE
                        + " WHERE old_uuid = ? AND new_name = ? AND status = 'pending' ORDER BY id DESC LIMIT 1")) {
            statement.setString(1, placeholderOldUuid);
            statement.setString(2, newName);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    found = readBindingEntry(rs);
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to find binding for accept: " + exception.getMessage());
            return null;
        }
        if (found == null) {
            return null;
        }
        // Update to active with real old_uuid
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE " + BIND_TABLE + " SET old_uuid = ?, status = 'active' WHERE id = ?")) {
            statement.setString(1, oldUuid.toString());
            statement.setLong(2, found.id);
            statement.executeUpdate();
        } catch (Exception exception) {
            LOGGER.warning("Failed to accept binding: " + exception.getMessage());
            return null;
        }
        return getBindingById(found.id);
    }

    /** Get the active binding target UUID for a given new UUID (returns old UUID or null). */
    public synchronized UUID getBoundUuid(UUID newUuid) {
        initialize();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT old_uuid FROM " + BIND_TABLE + " WHERE new_uuid = ? AND status = 'active'")) {
            statement.setString(1, newUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return UUID.fromString(rs.getString("old_uuid"));
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to look up binding for UUID " + newUuid + ": " + exception.getMessage());
        }
        return null;
    }

    /** Get the new UUID bound to an old UUID (reverse lookup). */
    public synchronized List<BindingEntry> getActiveBindingsForOldUuid(UUID oldUuid) {
        initialize();
        List<BindingEntry> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, old_uuid, new_uuid, old_name, new_name, status, created_at FROM " + BIND_TABLE
                        + " WHERE old_uuid = ? AND status = 'active'")) {
            statement.setString(1, oldUuid.toString());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(readBindingEntry(rs));
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to list bindings for old UUID " + oldUuid + ": " + exception.getMessage());
        }
        return results;
    }

    /** Get pending bindings for a player name (as the old account). */
    public synchronized List<BindingEntry> getPendingBindingsForOldName(String oldName) {
        initialize();
        List<BindingEntry> results = new ArrayList<>();
        String placeholder = "pending:" + oldName.toLowerCase(Locale.ENGLISH);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, old_uuid, new_uuid, old_name, new_name, status, created_at FROM " + BIND_TABLE
                        + " WHERE old_uuid = ? AND status = 'pending'")) {
            statement.setString(1, placeholder);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(readBindingEntry(rs));
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to list pending bindings for " + oldName + ": " + exception.getMessage());
        }
        return results;
    }

    /** Check if a player name has a pending bind request against them. */
    public synchronized boolean hasPendingBindingForOldName(String oldName) {
        initialize();
        String placeholder = "pending:" + oldName.toLowerCase(Locale.ENGLISH);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + BIND_TABLE + " WHERE old_uuid = ? AND status = 'pending'")) {
            statement.setString(1, placeholder);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to check pending bindings for " + oldName + ": " + exception.getMessage());
        }
        return false;
    }

    /** Revoke a binding by its ID (admin action). */
    public synchronized boolean revokeBinding(long bindingId) {
        initialize();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + BIND_TABLE + " WHERE id = ?")) {
            statement.setLong(1, bindingId);
            return statement.executeUpdate() > 0;
        } catch (Exception exception) {
            LOGGER.warning("Failed to revoke binding " + bindingId + ": " + exception.getMessage());
            return false;
        }
    }

    /** List all active bindings. */
    public synchronized List<BindingEntry> listAllBindings() {
        initialize();
        List<BindingEntry> results = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, old_uuid, new_uuid, old_name, new_name, status, created_at FROM " + BIND_TABLE + " ORDER BY id")) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    results.add(readBindingEntry(rs));
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to list all bindings: " + exception.getMessage());
        }
        return results;
    }

    private BindingEntry getBindingById(long id) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id, old_uuid, new_uuid, old_name, new_name, status, created_at FROM " + BIND_TABLE + " WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) {
                    return readBindingEntry(rs);
                }
            }
        } catch (Exception exception) {
            LOGGER.warning("Failed to get binding by id " + id + ": " + exception.getMessage());
        }
        return null;
    }

    private static BindingEntry readBindingEntry(ResultSet rs) throws Exception {
        String oldUuidStr = rs.getString("old_uuid");
        UUID oldUuid = null;
        if (oldUuidStr != null && !oldUuidStr.startsWith("pending:")) {
            oldUuid = UUID.fromString(oldUuidStr);
        }
        // If it starts with "pending:", oldUuid stays null
        return new BindingEntry(
                rs.getLong("id"),
                oldUuid,
                UUID.fromString(rs.getString("new_uuid")),
                rs.getString("old_name"),
                rs.getString("new_name"),
                rs.getString("status"),
                rs.getLong("created_at")
        );
    }

    public synchronized void close() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception exception) {
            LOGGER.warning("Failed to close Heos Folia storage: " + exception.getMessage());
        } finally {
            connection = null;
        }
    }

    // === Encryption ===

    private String encrypt(String plainText) throws Exception {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, combined, 0, nonce.length);
        System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private String decrypt(String encryptedText) throws Exception {
        if (encryptedText.contains(":")) {
            String[] parts = encryptedText.split(":", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("Invalid encrypted data");
            }
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            byte[] encrypted = Base64.getDecoder().decode(parts[1]);
            return decrypt(nonce, encrypted);
        }
        byte[] combined = Base64.getDecoder().decode(encryptedText);
        if (combined.length <= 12) {
            throw new IllegalArgumentException("Invalid encrypted data");
        }
        byte[] nonce = new byte[12];
        byte[] encrypted = new byte[combined.length - nonce.length];
        System.arraycopy(combined, 0, nonce, 0, nonce.length);
        System.arraycopy(combined, nonce.length, encrypted, 0, encrypted.length);
        return decrypt(nonce, encrypted);
    }

    private String decrypt(byte[] nonce, byte[] encrypted) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, nonce));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private SecretKeySpec key() throws Exception {
        if (key != null) {
            return key;
        }
        Path keyPath = root.resolve("secret.key");
        if (Files.exists(keyPath)) {
            byte[] keyBytes = Files.readAllBytes(keyPath);
            key = new SecretKeySpec(keyBytes, "AES");
            return key;
        }
        byte[] keyBytes = new byte[32];
        RANDOM.nextBytes(keyBytes);
        Files.write(keyPath, keyBytes);
        key = new SecretKeySpec(keyBytes, "AES");
        return key;
    }
}
