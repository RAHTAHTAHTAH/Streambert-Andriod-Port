package com.streambert.mobile;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * SHA-256 helpers for verifying a downloaded update.
 *
 * The updater compares the hash of the bytes it actually wrote to disk with
 * the digest the release publishes, so a truncated download, a corrupted file
 * or an APK swapped on the way (a tampered mirror, a hostile proxy) is
 * rejected before Android is ever asked to install it.
 */
final class Digests {

    private Digests() {}

    /** Lower-case hex SHA-256 of a file. */
    static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    /**
     * True when the file's SHA-256 equals {@code expectedHex}. The comparison
     * is constant-time and accepts the "sha256:&lt;hex&gt;" form GitHub uses.
     */
    static boolean matches(String expectedHex, File file) throws Exception {
        byte[] expected = fromHex(normalize(expectedHex));
        if (expected == null) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return MessageDigest.isEqual(expected, digest.digest());
    }

    /** "SHA256:AA BB" -> "aabb", so we do not depend on how a digest is written. */
    static String normalize(String value) {
        if (value == null) return "";
        String out = value.trim().toLowerCase(Locale.US);
        if (out.startsWith("sha256:")) out = out.substring(7).trim();
        if (out.startsWith("sha-256:")) out = out.substring(8).trim();
        return out;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** Returns null when the value is not 64 hex characters. */
    private static byte[] fromHex(String value) {
        if (value == null || value.length() != 64) return null;
        byte[] out = new byte[32];
        for (int i = 0; i < 32; i++) {
            int hi = Character.digit(value.charAt(i * 2), 16);
            int lo = Character.digit(value.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
