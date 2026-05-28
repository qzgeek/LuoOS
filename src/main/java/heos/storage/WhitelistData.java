package heos.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import heos.utils.HeosLogger;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Whitelist data storage
 */
public class WhitelistData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WHITELIST_FILE = "heos_whitelist.json";

    public Set<String> usernames = new HashSet<>();

    public boolean isWhitelisted(String username) {
        ensureUsernames();
        return usernames.contains(normalize(username));
    }

    public boolean add(String username) {
        ensureUsernames();
        boolean added = usernames.add(normalize(username));
        save();
        return added;
    }

    public boolean remove(String username) {
        ensureUsernames();
        boolean removed = usernames.remove(normalize(username));
        save();
        return removed;
    }

    public static WhitelistData load() {
        try {
            File file = StoragePaths.file(WHITELIST_FILE);
            migrateLegacyWhitelistFile();
            if (!file.exists()) {
                WhitelistData data = new WhitelistData();
                data.save();
                return data;
            }

            try (FileReader reader = new FileReader(file)) {
                WhitelistData data = GSON.fromJson(reader, WhitelistData.class);
                if (data == null) {
                    WhitelistData defaultData = new WhitelistData();
                    defaultData.save();
                    return defaultData;
                }
                if (data.normalizeLoadedUsernames()) {
                    data.save();
                }
                return data;
            }
        } catch (IOException e) {
            HeosLogger.error("Failed to load whitelist data", e);
            return new WhitelistData();
        }
    }

    public static void migrateLegacyWhitelistFile() {
        File target = StoragePaths.file(WHITELIST_FILE);
        File legacy = new File(heos.Heos.gameDirectory.toFile(), WHITELIST_FILE);
        if (target.exists() || !legacy.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        if (!legacy.renameTo(target)) {
            try (FileReader reader = new FileReader(legacy); FileWriter writer = new FileWriter(target)) {
                char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    writer.write(buffer, 0, read);
                }
            } catch (IOException e) {
                HeosLogger.error("Failed to migrate whitelist data", e);
            }
        }
    }

    public void save() {
        try {
            File file = StoragePaths.file(WHITELIST_FILE);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(file)) {
                GSON.toJson(this, writer);
            }
            HeosLogger.debug("Saved whitelist data to " + file.getPath() + " with " + usernames.size() + " players");
        } catch (IOException e) {
            HeosLogger.error("Failed to save whitelist data", e);
        }
    }

    private void ensureUsernames() {
        if (usernames == null) {
            usernames = new HashSet<>();
        }
    }

    private boolean normalizeLoadedUsernames() {
        ensureUsernames();
        Set<String> normalized = new HashSet<>();
        for (String username : usernames) {
            if (username != null && !username.isBlank()) {
                normalized.add(normalize(username));
            }
        }
        if (!normalized.equals(usernames)) {
            usernames = normalized;
            return true;
        }
        return false;
    }

    private static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
