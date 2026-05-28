package heos.folia.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

public final class FoliaWhitelistData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Set<String> usernames = new HashSet<>();

    private transient Path file;
    private transient Logger logger;

    public static FoliaWhitelistData load(Path root, Logger logger) {
        Path file = root.resolve("heos_whitelist.json");
        try {
            Files.createDirectories(root);
            if (!Files.exists(file)) {
                FoliaWhitelistData data = new FoliaWhitelistData();
                data.attach(file, logger);
                data.save();
                return data;
            }
            try (Reader reader = Files.newBufferedReader(file)) {
                FoliaWhitelistData data = GSON.fromJson(reader, FoliaWhitelistData.class);
                if (data == null) {
                    data = new FoliaWhitelistData();
                }
                data.attach(file, logger);
                data.normalizeLoaded();
                return data;
            }
        } catch (IOException exception) {
            logger.warning("Failed to load Heos whitelist: " + exception.getMessage());
            FoliaWhitelistData data = new FoliaWhitelistData();
            data.attach(file, logger);
            return data;
        }
    }

    public synchronized boolean isWhitelisted(String username) {
        ensure();
        return usernames.contains(normalize(username));
    }

    public synchronized boolean add(String username) {
        ensure();
        boolean added = usernames.add(normalize(username));
        if (added) {
            save();
        }
        return added;
    }

    public synchronized boolean remove(String username) {
        ensure();
        boolean removed = usernames.remove(normalize(username));
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
            logger.warning("Failed to save Heos whitelist: " + exception.getMessage());
        }
    }

    private void attach(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    private void normalizeLoaded() {
        ensure();
        Set<String> normalized = new HashSet<>();
        for (String username : usernames) {
            if (username != null && !username.isBlank()) {
                normalized.add(normalize(username));
            }
        }
        if (!normalized.equals(usernames)) {
            usernames = normalized;
            save();
        }
    }

    private void ensure() {
        if (usernames == null) {
            usernames = new HashSet<>();
        }
    }

    private static String normalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }
}
