package heos.storage;

import heos.utils.HeosLogger;

import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class SecretKeyManager {
    private static final String KEY_FILE = "secret.key";
    private static final String LEGACY_KEY_SEED = "heos-player-data-v1";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static SecretKeySpec currentKey;

    private SecretKeyManager() {
    }

    public static synchronized SecretKeySpec currentKey() throws IOException {
        if (currentKey == null) {
            currentKey = loadOrGenerateKey();
        }
        return currentKey;
    }

    public static SecretKeySpec legacyKey() throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return new SecretKeySpec(digest.digest(LEGACY_KEY_SEED.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "AES");
    }

    private static SecretKeySpec loadOrGenerateKey() throws IOException {
        StoragePaths.ensureRoot();
        File keyFile = StoragePaths.file(KEY_FILE);
        if (keyFile.exists()) {
            byte[] keyBytes = Files.readAllBytes(keyFile.toPath());
            if (keyBytes.length != 32) {
                throw new IOException("Invalid Heos secret key length: " + keyBytes.length);
            }
            return new SecretKeySpec(keyBytes, "AES");
        }

        byte[] keyBytes = new byte[32];
        RANDOM.nextBytes(keyBytes);
        Files.write(keyFile.toPath(), keyBytes, StandardOpenOption.CREATE_NEW);
        HeosLogger.info("Generated local player data encryption key at " + keyFile.getAbsolutePath());
        return new SecretKeySpec(keyBytes, "AES");
    }
}
