package heos.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import heos.Heos;
import heos.utils.HeosLogger;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Player data storage backed by server/heos/player_data.db.
 */
public class PlayerData {
    private static final Gson GSON = new GsonBuilder().create();
    private static final String DB_FILE = "player_data.db";
    private static final String TABLE = "players";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static Connection connection;

    public String username;
    public UUID uuid;
    public String passwordHash;
    public String lastIp;
    public boolean isOnlineAccount;
    public long registeredTime;
    public long lastLoginTime;
    private transient String storageKey;

    public PlayerData(String username) {
        this.username = username;
        this.passwordHash = "";
        this.lastIp = "";
        this.isOnlineAccount = false;
        this.registeredTime = 0;
        this.lastLoginTime = 0;
        this.storageKey = cacheKey(username, false);
    }

    public boolean isRegistered() {
        return passwordHash != null && !passwordHash.isEmpty();
    }

    public void save() {
        synchronized (PlayerData.class) {
            initializeStorage();
            try {
                String data = encrypt(GSON.toJson(this), SecretKeyManager.currentKey());
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO " + TABLE + " (username, username_lower, uuid, last_ip, data) VALUES (?, ?, ?, ?, ?) "
                                + "ON CONFLICT(username_lower) DO UPDATE SET username = excluded.username, uuid = excluded.uuid, last_ip = excluded.last_ip, data = excluded.data")) {
                    statement.setString(1, username);
                    statement.setString(2, storageKey());
                    statement.setString(3, uuid == null ? null : uuid.toString());
                    statement.setString(4, lastIp);
                    statement.setString(5, data);
                    statement.executeUpdate();
                }
                HeosLogger.debug("Saved player data for " + username);
            } catch (Exception e) {
                HeosLogger.error("Failed to save player data for " + username, e);
            }
        }
    }

    public static synchronized PlayerData load(String username) {
        return load(username, false);
    }

    public static synchronized PlayerData load(String username, boolean onlineAccount) {
        initializeStorage();
        String key = cacheKey(username, onlineAccount);
        PlayerData data = loadByKey(username, key);
        if (data != null) {
            data.storageKey = key;
            return data;
        }

        if (Heos.getConfig().separateOnlineOfflineAccounts) {
            PlayerData legacyData = loadByKey(username, username.toLowerCase(Locale.ENGLISH));
            if (legacyData != null && legacyData.isOnlineAccount == onlineAccount) {
                legacyData.storageKey = key;
                return legacyData;
            }
        }

        PlayerData emptyData = new PlayerData(username);
        emptyData.isOnlineAccount = onlineAccount;
        emptyData.storageKey = key;
        return emptyData;
    }

    private static PlayerData loadByKey(String username, String key) {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT username, uuid, last_ip, data FROM " + TABLE + " WHERE username_lower = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                PlayerData data = GSON.fromJson(decrypt(resultSet.getString("data"), SecretKeyManager.currentKey()), PlayerData.class);
                if (data == null) {
                    data = new PlayerData(username);
                }
                data.username = resultSet.getString("username");
                String uuidString = resultSet.getString("uuid");
                data.uuid = uuidString == null || uuidString.isEmpty() ? data.uuid : UUID.fromString(uuidString);
                String lastIp = resultSet.getString("last_ip");
                if (lastIp != null) {
                    data.lastIp = lastIp;
                }
                HeosLogger.debug("Loaded player data for " + username);
                return data;
            }
        } catch (Exception e) {
            HeosLogger.error("Failed to load player data for " + username, e);
            return null;
        }
    }

    public static synchronized boolean exists(String username) {
        initializeStorage();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + TABLE + " WHERE username_lower IN (?, ?, ?) LIMIT 1")) {
            statement.setString(1, username.toLowerCase(Locale.ENGLISH));
            statement.setString(2, cacheKey(username, true));
            statement.setString(3, cacheKey(username, false));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        } catch (SQLException e) {
            HeosLogger.error("Failed to check player data for " + username, e);
            return false;
        }
    }

    public static synchronized boolean delete(String username) {
        initializeStorage();
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + TABLE + " WHERE username_lower IN (?, ?, ?)")) {
            statement.setString(1, username.toLowerCase(Locale.ENGLISH));
            statement.setString(2, cacheKey(username, true));
            statement.setString(3, cacheKey(username, false));
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            HeosLogger.error("Failed to delete player data for " + username, e);
            return false;
        }
    }

    public static synchronized void initializeStorage() {
        if (connection != null) {
            return;
        }
        try {
            Class.forName("org.sqlite.JDBC");
            StoragePaths.ensureRoot();
            File dbFile = StoragePaths.file(DB_FILE);
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.executeUpdate(
                        "CREATE TABLE IF NOT EXISTS " + TABLE + " ("
                                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                                + "username TEXT NOT NULL,"
                                + "username_lower TEXT NOT NULL UNIQUE,"
                                + "uuid TEXT NULL,"
                                + "last_ip TEXT NULL,"
                                + "data TEXT NOT NULL"
                                + ");");
            }
            migrateHardcodedKeyData();
            migrateLegacyJsonData();
        } catch (Exception e) {
            HeosLogger.error("Failed to initialize player database", e);
        }
    }

    public static synchronized void closeStorage() {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException e) {
            HeosLogger.error("Failed to close player database", e);
        } finally {
            connection = null;
        }
    }

    private static void migrateLegacyJsonData() {
        File legacyDir = new File(Heos.gameDirectory.toFile(), "heos_data");
        if (!legacyDir.isDirectory()) {
            return;
        }
        File[] files = legacyDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null || files.length == 0) {
            return;
        }
        int migrated = 0;
        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                PlayerData data = GSON.fromJson(reader, PlayerData.class);
                if (data != null && data.username != null && !data.username.isEmpty()) {
                    data.save();
                    migrated++;
                }
            } catch (Exception e) {
                HeosLogger.error("Failed to migrate legacy player data file " + file, e);
            }
        }
        if (migrated > 0) {
            HeosLogger.info("Migrated " + migrated + " legacy player data files into " + DB_FILE);
        }
    }

    private static void migrateHardcodedKeyData() {
        List<LegacyEncryptedRow> rowsToMigrate = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT id, data FROM " + TABLE)) {
            SecretKeySpec currentKey = SecretKeyManager.currentKey();
            SecretKeySpec legacyKey = SecretKeyManager.legacyKey();
            while (resultSet.next()) {
                long id = resultSet.getLong("id");
                String encryptedData = resultSet.getString("data");
                if (canDecrypt(encryptedData, currentKey)) {
                    continue;
                }

                String plainText;
                try {
                    plainText = decrypt(encryptedData, legacyKey);
                } catch (Exception e) {
                    HeosLogger.warn("Player data row " + id + " could not be decrypted with current or legacy key");
                    continue;
                }

                rowsToMigrate.add(new LegacyEncryptedRow(id, plainText));
            }
        } catch (Exception e) {
            HeosLogger.error("Failed to migrate player data encryption key", e);
        }

        if (rowsToMigrate.isEmpty()) {
            return;
        }

        int migrated = 0;
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + TABLE + " SET data = ? WHERE id = ?")) {
            SecretKeySpec currentKey = SecretKeyManager.currentKey();
            for (LegacyEncryptedRow row : rowsToMigrate) {
                update.setString(1, encrypt(row.plainText, currentKey));
                update.setLong(2, row.id);
                update.executeUpdate();
                migrated++;
            }
        } catch (Exception e) {
            HeosLogger.error("Failed to rewrite legacy-encrypted player data", e);
        }

        if (migrated > 0) {
            HeosLogger.info("Migrated " + migrated + " player data rows to the local secret key");
        }
    }

    private static boolean canDecrypt(String encryptedText, SecretKeySpec key) {
        try {
            decrypt(encryptedText, key);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String encrypt(String plainText, SecretKeySpec key) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    private static String decrypt(String encryptedText, SecretKeySpec key) throws Exception {
        byte[] combined = Base64.getDecoder().decode(encryptedText);
        byte[] iv = new byte[12];
        byte[] encrypted = new byte[combined.length - iv.length];
        System.arraycopy(combined, 0, iv, 0, iv.length);
        System.arraycopy(combined, iv.length, encrypted, 0, encrypted.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    public static String cacheKey(String username, boolean onlineAccount) {
        String normalized = username.toLowerCase(Locale.ENGLISH);
        if (!Heos.getConfig().separateOnlineOfflineAccounts) {
            return normalized;
        }
        return (onlineAccount ? "online:" : "offline:") + normalized;
    }

    private String storageKey() {
        if (storageKey == null || storageKey.isEmpty()) {
            storageKey = cacheKey(username, isOnlineAccount);
        }
        return storageKey;
    }

    private record LegacyEncryptedRow(long id, String plainText) {
    }
}
