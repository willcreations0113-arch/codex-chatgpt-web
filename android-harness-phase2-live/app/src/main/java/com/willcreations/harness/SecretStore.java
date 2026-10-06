package com.willcreations.harness;

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

public final class SecretStore {
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    // Keep the original alias so existing OpenAI keys remain decryptable after upgrade.
    private static final String KEY_ALIAS = "will_harness_openai_key_v1";
    private static final String PREFS = "secrets";
    private static final String LEGACY_OPENAI_CIPHER = "openai_cipher";
    private static final String LEGACY_OPENAI_IV = "openai_iv";

    private final SharedPreferences prefs;

    public SecretStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void saveSecret(String name, String value) throws Exception {
        String id = normalize(name);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Secret is empty");
        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.trim().getBytes(StandardCharsets.UTF_8));
        prefs.edit()
                .putString(cipherKey(id), Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString(ivKey(id), Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }

    public synchronized String loadSecret(String name) throws Exception {
        String id = normalize(name);
        String cipherText = prefs.getString(cipherKey(id), null);
        String ivText = prefs.getString(ivKey(id), null);

        if ("openai".equals(id) && (cipherText == null || ivText == null)) {
            cipherText = prefs.getString(LEGACY_OPENAI_CIPHER, null);
            ivText = prefs.getString(LEGACY_OPENAI_IV, null);
        }
        if (cipherText == null || ivText == null) return "";

        SecretKey key = getOrCreateKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.decode(ivText, Base64.NO_WRAP)));
        byte[] plain = cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP));
        return new String(plain, StandardCharsets.UTF_8);
    }

    public boolean hasSecret(String name) {
        String id = normalize(name);
        if (prefs.contains(cipherKey(id)) && prefs.contains(ivKey(id))) return true;
        return "openai".equals(id) && prefs.contains(LEGACY_OPENAI_CIPHER) && prefs.contains(LEGACY_OPENAI_IV);
    }

    public synchronized void clearSecret(String name) {
        String id = normalize(name);
        prefs.edit().remove(cipherKey(id)).remove(ivKey(id)).apply();
    }

    public void saveApiKey(String providerId, String apiKey) throws Exception { saveSecret(providerId, apiKey); }
    public String loadApiKey(String providerId) throws Exception { return loadSecret(providerId); }
    public boolean hasApiKey(String providerId) { return hasSecret(providerId); }
    public void clearApiKey(String providerId) { clearSecret(providerId); }

    // Backward-compatible methods used by older builds.
    public void saveOpenAiKey(String apiKey) throws Exception { saveApiKey("openai", apiKey); }
    public String loadOpenAiKey() throws Exception { return loadApiKey("openai"); }
    public boolean hasOpenAiKey() { return hasApiKey("openai"); }

    private String normalize(String providerId) {
        String id = providerId == null ? "" : providerId.trim().toLowerCase();
        if (id.isEmpty()) throw new IllegalArgumentException("Secret ID is empty");
        return id.replaceAll("[^a-z0-9_]+", "_");
    }

    private String cipherKey(String id) { return "provider_" + id + "_cipher"; }
    private String ivKey(String id) { return "provider_" + id + "_iv"; }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance(ANDROID_KEYSTORE);
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
