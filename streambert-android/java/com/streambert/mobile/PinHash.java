package com.streambert.mobile;

import android.util.Base64;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Salted PBKDF2 record for the parental PIN and its recovery code.
 *
 * <p>The plaintext PIN is never written anywhere: the app stores (and compares
 * against) {@code pbkdf2$<iterations>$<salt>$<hash>}. A fresh random salt is
 * used for every record, the comparison is constant-time, and the whole record
 * additionally sits inside {@link SecureStore}, so reading the file off the
 * device gives an attacker neither the PIN nor a usable hash without the
 * Keystore key.</p>
 *
 * <p>This is not a defence against someone guessing offline: a four-digit PIN
 * is only 10,000 possibilities. What it does guarantee is that a leaked
 * preferences file, a screenshot of the settings card, or a log line cannot
 * reveal the PIN the user actually types.</p>
 */
final class PinHash {

    /** Syntactically a PIN record, e.g. when an older build stored the PIN. */
    private static final String PREFIX = "pbkdf2$";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA1";
    private static final int ITERATIONS = 100000;
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private PinHash() {}

    /** True when {@code stored} is already a PBKDF2 record. */
    static boolean isHash(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /** Builds a new salted record for a secret (PIN digits or recovery code). */
    static String create(String secret) {
        String value = secret == null ? "" : secret;
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = derive(value, salt, ITERATIONS);
        return PREFIX + ITERATIONS + "$"
                + Base64.encodeToString(salt, Base64.NO_WRAP) + "$"
                + Base64.encodeToString(hash, Base64.NO_WRAP);
    }

    /**
     * Constant-time check of a candidate against a stored record. When the
     * stored value is not a record (an older build kept the PIN itself), it is
     * compared as plain text so existing installs keep working until the value
     * is migrated.
     */
    static boolean verify(String candidate, String stored) {
        if (stored == null || stored.isEmpty()) return false;
        String value = candidate == null ? "" : candidate;
        if (!isHash(stored)) return constantTimeEquals(value.getBytes(UTF8), stored.getBytes(UTF8));
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.decode(parts[2], Base64.NO_WRAP);
            byte[] expected = Base64.decode(parts[3], Base64.NO_WRAP);
            return MessageDigest.isEqual(expected, derive(value, salt, iterations));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(String value, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    value.toCharArray(), salt, iterations, KEY_BITS);
            try {
                return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
            } finally {
                spec.clearPassword();
            }
        } catch (Exception e) {
            return new byte[0];
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
