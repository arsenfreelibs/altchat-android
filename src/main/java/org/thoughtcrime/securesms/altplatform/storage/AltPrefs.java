package org.thoughtcrime.securesms.altplatform.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

public class AltPrefs {

    private static final String PREFS_NAME = "alt_platform_prefs";
    private static final String KEY_REGISTERED = "alt_registered";
    private static final String KEY_USERNAME = "alt_username";
    private static final String KEY_EMAIL = "alt_email";
    private static final String KEY_ONBOARDING_SNACKBAR_SHOWN = "alt_onboarding_snackbar_shown";

    public static boolean isRegistered(Context context) {
        return getPrefs(context).getBoolean(KEY_REGISTERED, false);
    }

    public static void setRegistered(Context context, String username, String email) {
        getPrefs(context).edit()
                .putBoolean(KEY_REGISTERED, true)
                .putString(KEY_USERNAME, username)
                .putString(KEY_EMAIL, email)
                .apply();
    }

    @Nullable
    public static String getUsername(Context context) {
        return getPrefs(context).getString(KEY_USERNAME, null);
    }

    @Nullable
    public static String getEmail(Context context) {
        return getPrefs(context).getString(KEY_EMAIL, null);
    }

    public static boolean wasOnboardingSnackbarShown(Context context) {
        return getPrefs(context).getBoolean(KEY_ONBOARDING_SNACKBAR_SHOWN, false);
    }

    public static void markOnboardingSnackbarShown(Context context) {
        getPrefs(context).edit().putBoolean(KEY_ONBOARDING_SNACKBAR_SHOWN, true).apply();
    }

    public static void clear(Context context) {
        getPrefs(context).edit().clear().apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
