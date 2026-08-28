package com.example.mediaparser.subtitle;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class GeminiKeyStore {
    private static final String PREFS = "gemini_cloud_settings";
    private static final String PREF_IV = "api_key_iv";
    private static final String PREF_DATA = "api_key_ciphertext";
    private static final String ALIAS = "mediaparser_gemini_api_key_v1";

    private GeminiKeyStore() {}

    public static void save(Context context, String apiKey) throws Exception {
        String clean = apiKey == null ? "" : apiKey.trim();
        if (clean.isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(clean.getBytes(StandardCharsets.UTF_8));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(PREF_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply();
    }

    public static String load(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String iv64 = prefs.getString(PREF_IV, "");
            String data64 = prefs.getString(PREF_DATA, "");
            if (iv64 == null || iv64.isBlank() || data64 == null || data64.isBlank()) return "";
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            SecretKey key = (SecretKey) ks.getKey(ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(iv64, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(data64, Base64.NO_WRAP)), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static boolean hasKey(Context context) {
        return !load(context).isBlank();
    }

    public static String masked(Context context) {
        String key = load(context);
        if (key.isBlank()) return "";
        if (key.length() <= 8) return "••••••••";
        return key.substring(0, Math.min(4, key.length())) + "••••" + key.substring(key.length() - 4);
    }

    public static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        java.security.Key existing = ks.getKey(ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
