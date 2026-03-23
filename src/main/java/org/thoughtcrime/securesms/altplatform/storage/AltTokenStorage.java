package org.thoughtcrime.securesms.altplatform.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

public class AltTokenStorage {

    private static final String TAG = AltTokenStorage.class.getSimpleName();
    private static final String PREFS_NAME = "alt_token_prefs";

    private static String key(int accountId) {
        return "jwt_token_" + accountId;
    }

    public static void saveToken(Context context, int accountId, String token) {
        getPrefs(context).edit().putString(key(accountId), token).apply();
    }

    @Nullable
    public static String getToken(Context context, int accountId) {
        return getPrefs(context).getString(key(accountId), null);
    }

    public static void clearToken(Context context, int accountId) {
        getPrefs(context).edit().remove(key(accountId)).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                MasterKey masterKey = new MasterKey.Builder(context)
                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                        .build();
                return EncryptedSharedPreferences.create(
                        context,
                        PREFS_NAME,
                        masterKey,
                        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                );
            } catch (Exception e) {
                Log.e(TAG, "EncryptedSharedPreferences failed, falling back to plain", e);
            }
        }
        return context.getSharedPreferences(PREFS_NAME + "_plain", Context.MODE_PRIVATE);
    }
}
