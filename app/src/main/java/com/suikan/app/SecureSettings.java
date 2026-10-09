package com.suikan.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecureSettings {
    private final SharedPreferences prefs;
    SecureSettings(Context c) { prefs = c.getSharedPreferences("settings", Context.MODE_PRIVATE); }
    String endpoint() { return prefs.getString("endpoint", ""); }
    String model() { return prefs.getString("model", ""); }
    int outputTokens() { return prefs.getInt("output_tokens", Http.DEFAULT_OUTPUT_TOKENS); }
    boolean disableDeepSeekThinking() { return prefs.getBoolean("disable_deepseek_thinking", true); }
    boolean lightGlmThinking() { return prefs.getBoolean("light_glm_thinking", true); }
    String lastInput() { return prefs.getString("last_input", ""); }
    void lastInput(String value) { prefs.edit().putString("last_input", value).apply(); }
    String summaryDepth(){return SummaryDepth.normalize(prefs.getString("summary_depth",SummaryDepth.STANDARD));}
    void summaryDepth(String value){prefs.edit().putString("summary_depth",SummaryDepth.normalize(value)).apply();}
    private SecretKey secret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        String alias = "suikan-model-key";
        if (!ks.containsAlias(alias)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) ks.getKey(alias, null);
    }
    String key() throws Exception {
        String blob = prefs.getString("key_blob", ""); if (blob.isEmpty()) return "";
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(prefs.getString("key_iv", ""), Base64.NO_WRAP)));
        return new String(c.doFinal(Base64.decode(blob, Base64.NO_WRAP)), java.nio.charset.StandardCharsets.UTF_8);
    }
    void save(String endpoint, String model, String key) throws Exception {
        save(endpoint, model, key, outputTokens(), disableDeepSeekThinking());
    }
    void save(String endpoint, String model, String key, int outputTokens, boolean disableThinking) throws Exception {
        save(endpoint, model, key, outputTokens, disableThinking, lightGlmThinking());
    }
    void save(String endpoint, String model, String key, int outputTokens, boolean disableThinking, boolean lightGlmThinking) throws Exception {
        Http.endpoint(endpoint); if (model.trim().isEmpty()) throw new IllegalArgumentException("请填写模型名称");
        Http.validateOutputTokens(outputTokens);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secret());
        byte[] encrypted = c.doFinal(key.trim().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        prefs.edit().putString("endpoint", endpoint.trim()).putString("model", model.trim())
                .putInt("output_tokens", outputTokens).putBoolean("disable_deepseek_thinking", disableThinking).putBoolean("light_glm_thinking", lightGlmThinking)
                .putString("key_blob", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("key_iv", Base64.encodeToString(c.getIV(), Base64.NO_WRAP)).commit();
    }
}
