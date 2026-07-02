package br.com.droidboaoferta;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class TdlibDatabaseKey {
    private static final String ANDROID_KEY_STORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "boa_oferta_tdlib_key";
    private static final String PREFS = "telegram_secure_storage";
    private static final String PREF_ENCRYPTED_KEY = "database_key";
    private static final String PREF_IV = "database_key_iv";

    private TdlibDatabaseKey() {
    }

    static String getOrCreateBase64(Context context) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SecretKey wrappingKey = getOrCreateWrappingKey();
        String encryptedValue = preferences.getString(PREF_ENCRYPTED_KEY, null);
        String ivValue = preferences.getString(PREF_IV, null);

        byte[] databaseKey;
        if (encryptedValue != null && ivValue != null) {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    wrappingKey,
                    new GCMParameterSpec(128, Base64.decode(ivValue, Base64.NO_WRAP))
            );
            databaseKey = cipher.doFinal(Base64.decode(encryptedValue, Base64.NO_WRAP));
        } else {
            databaseKey = new byte[32];
            new SecureRandom().nextBytes(databaseKey);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey);
            byte[] encryptedKey = cipher.doFinal(databaseKey);
            preferences.edit()
                    .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encryptedKey, Base64.NO_WRAP))
                    .putString(PREF_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .apply();
        }

        return Base64.encodeToString(databaseKey, Base64.NO_WRAP);
    }

    private static SecretKey getOrCreateWrappingKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEY_STORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }

        KeyGenerator keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEY_STORE
        );
        keyGenerator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return keyGenerator.generateKey();
    }
}
