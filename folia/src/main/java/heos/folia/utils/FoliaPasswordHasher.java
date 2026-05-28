package heos.folia.utils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

public final class FoliaPasswordHasher {
    private static final int LEGACY_ITERATIONS = 10000;
    private static final int CURRENT_ITERATIONS = 310000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final SecureRandom RANDOM = new SecureRandom();

    private FoliaPasswordHasher() {
    }

    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            RANDOM.nextBytes(salt);
            byte[] hash = pbkdf2(password.toCharArray(), salt, CURRENT_ITERATIONS);
            return CURRENT_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to hash password", exception);
        }
    }

    public static boolean verifyPassword(String password, String storedHash) {
        try {
            ParsedHash parsed = parse(storedHash);
            if (parsed == null) {
                return false;
            }
            return slowEquals(parsed.hash, pbkdf2(password.toCharArray(), parsed.salt, parsed.iterations));
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean needsRehash(String storedHash) {
        ParsedHash parsed = parse(storedHash);
        return parsed == null || parsed.iterations < CURRENT_ITERATIONS;
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LENGTH);
        return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
    }

    private static ParsedHash parse(String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return null;
        }
        String[] parts = storedHash.split(":");
        try {
            if (parts.length == 2) {
                return new ParsedHash(LEGACY_ITERATIONS, Base64.getDecoder().decode(parts[0]), Base64.getDecoder().decode(parts[1]));
            }
            if (parts.length == 3) {
                return new ParsedHash(Integer.parseInt(parts[0]), Base64.getDecoder().decode(parts[1]), Base64.getDecoder().decode(parts[2]));
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static boolean slowEquals(byte[] a, byte[] b) {
        int diff = a.length ^ b.length;
        for (int i = 0; i < a.length && i < b.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private record ParsedHash(int iterations, byte[] salt, byte[] hash) {
    }
}
