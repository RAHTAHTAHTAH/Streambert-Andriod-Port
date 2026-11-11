package com.streambert.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.Charset;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Encrypted-at-rest storage for the few secrets the app keeps: the TMDB API
 * token, the GitHub token used for Gist sync, and the parental PIN record.
 *
 * <p>Values live in the same {@code streambert_secure} preferences file as
 * before, but each one is AES-256-GCM encrypted with a key that is generated
 * inside the Android Keystore and never leaves it. The key material is not
 * exportable and is bound to this app on this device, so a preferences file
 * copied off the device (root, adb backup, a buggy OEM sync) contains no
 * readable token.</p>
 *
 * <p>Values written by older builds were stored in the clear. They are still
 * readable, and are transparently re-written encrypted the first time they are
 * read, so an existing install keeps its tokens without the user doing
 * anything.</p>
 */
final class SecureStore {

    private static final String PREFS = "streambert_secure";
    private static final String KEY_ALIAS = "streambert.secrets.v1";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    /** Prefix marking an encrypted value; anything else is legacy plaintext. */
    private static final String VERSION = "v1:";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private SecureStore() {}

    static String get(Context context, String key) {
        if (context == null || key == null || key.isEmpty()) return "";
        SharedPreferences prefs = prefs(context);
        String stored = prefs.getString(key, null);
        if (stored == null || stored.isEmpty()) return "";

        if (!stored.startsWith(VERSION)) {
            // Written by a build that stored secrets in the clear. Hand it back
            // and immediately replace it with an encrypted copy.
            set(context, key, stored);
            return stored;
        }

        try {
            byte[] raw = Base64.decode(stored.substring(VERSION.length()), Base64.NO_WRAP);
            if (raw.length <= IV_BYTES) throw new IllegalArgumentException("short value");
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(raw, 0, iv, 0, IV_BYTES);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
            return new String(plain, UTF8);
        } catch (Exception e) {
            // A value we cannot decrypt (key invalidated, file garbled) is
            // useless; drop it rather than failing every future read.
            prefs.edit().remove(key).apply();
            return "";
        }
    }

    static void set(Context context, String key, String value) {
        if (context == null || key == null || key.isEmpty()) return;
        SharedPreferences prefs = prefs(context);
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = cipher.getIV();
            byte[] sealed = cipher.doFinal(value.getBytes(UTF8));
            byte[] out = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(sealed, 0, out, iv.length, sealed.length);
            prefs.edit()
                    .putString(key, VERSION + Base64.encodeToString(out, Base64.NO_WRAP))
                    .apply();
        } catch (Exception e) {
            // Never keep a plaintext copy as a fallback.
            prefs.edit().remove(key).apply();
        }
    }

    /** True when a readable value is stored under this key. */
    static boolean has(Context context, String key) {
        return !get(context, key).isEmpty();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static synchronized SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
