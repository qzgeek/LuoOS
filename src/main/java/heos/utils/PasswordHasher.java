package heos.utils;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;

/**
 * Password hashing utility using PBKDF2
 */
public class PasswordHasher {
    private static final int LEGACY_ITERATIONS = 10000;
    private static final int CURRENT_ITERATIONS = 310000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    
    /**
     * Hashes a password with a random salt
     * @param password Plain text password
     * @return Hashed password in format: salt:hash
     */
    public static String hashPassword(String password) {
        try {
            // Generate random salt
            SecureRandom random = new SecureRandom();
            byte[] salt = new byte[16];
            random.nextBytes(salt);
            
            // Hash password
            byte[] hash = pbkdf2(password.toCharArray(), salt, CURRENT_ITERATIONS, KEY_LENGTH);
            
            return CURRENT_ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            HeosLogger.error("Failed to hash password", e);
            return null;
        }
    }
    
    /**
     * Verifies a password against a hash
     * @param password Plain text password to verify
     * @param storedHash Stored hash in format: salt:hash
     * @return true if password matches
     */
    public static boolean verifyPassword(String password, String storedHash) {
        try {
            ParsedHash parsedHash = parseHash(storedHash);
            if (parsedHash == null) {
                return false;
            }
            
            byte[] testHash = pbkdf2(password.toCharArray(), parsedHash.salt, parsedHash.iterations, KEY_LENGTH);
            
            return slowEquals(parsedHash.hash, testHash);
        } catch (Exception e) {
            HeosLogger.error("Failed to verify password", e);
            return false;
        }
    }

    public static boolean needsRehash(String storedHash) {
        ParsedHash parsedHash = parseHash(storedHash);
        return parsedHash == null || parsedHash.iterations < CURRENT_ITERATIONS;
    }
    
    /**
     * PBKDF2 implementation
     */
    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) 
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
        SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
        return skf.generateSecret(spec).getEncoded();
    }

    private static ParsedHash parseHash(String storedHash) {
        if (storedHash == null || storedHash.isEmpty()) {
            return null;
        }
        String[] parts = storedHash.split(":");
        try {
            if (parts.length == 2) {
                return new ParsedHash(
                        LEGACY_ITERATIONS,
                        Base64.getDecoder().decode(parts[0]),
                        Base64.getDecoder().decode(parts[1])
                );
            }
            if (parts.length == 3) {
                return new ParsedHash(
                        Integer.parseInt(parts[0]),
                        Base64.getDecoder().decode(parts[1]),
                        Base64.getDecoder().decode(parts[2])
                );
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }
    
    /**
     * Constant-time comparison to prevent timing attacks
     */
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



