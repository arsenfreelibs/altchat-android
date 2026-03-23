package org.thoughtcrime.securesms.altplatform.storage;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

public class AltPrefs {

    private static final String PREFS_NAME = "alt_platform_prefs";
    private static final String KEY_USERNAME = "alt_username";
    private static final String KEY_EMAIL = "alt_email";
    private static final String KEY_ONBOARDING_SNACKBAR_SHOWN = "alt_onboarding_snackbar_shown";
    private static final String KEY_PENDING_USERNAME = "alt_pending_username";
    private static final String KEY_PENDING_DISPLAY_NAME = "alt_pending_display_name";
    private static final String KEY_PENDING_EMAIL = "alt_pending_email";

    public static void setRegistered(Context context, String username, String email) {
        getPrefs(context).edit()
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

    // --- Pending registration (started but not yet email-verified) ---

    public static boolean hasPendingRegistration(Context context) {
        return getPrefs(context).getString(KEY_PENDING_EMAIL, null) != null;
    }

    public static void setPendingRegistration(Context context, String username, String displayName, String email) {
        getPrefs(context).edit()
                .putString(KEY_PENDING_USERNAME, username)
                .putString(KEY_PENDING_DISPLAY_NAME, displayName)
                .putString(KEY_PENDING_EMAIL, email)
                .apply();
    }

    @Nullable
    public static String getPendingUsername(Context context) {
        return getPrefs(context).getString(KEY_PENDING_USERNAME, null);
    }

    @Nullable
    public static String getPendingDisplayName(Context context) {
        return getPrefs(context).getString(KEY_PENDING_DISPLAY_NAME, null);
    }

    @Nullable
    public static String getPendingEmail(Context context) {
        return getPrefs(context).getString(KEY_PENDING_EMAIL, null);
    }

    public static void clearPendingRegistration(Context context) {
        getPrefs(context).edit()
                .remove(KEY_PENDING_USERNAME)
                .remove(KEY_PENDING_DISPLAY_NAME)
                .remove(KEY_PENDING_EMAIL)
                .apply();
    }

    private static final String KEY_RECOVERY_PASSWORD = "alt_recovery_password";

    public static void saveRecoveryPassword(Context context, String password) {
        getPrefs(context).edit().putString(KEY_RECOVERY_PASSWORD, password).apply();
    }

    @Nullable
    public static String getRecoveryPassword(Context context) {
        return getPrefs(context).getString(KEY_RECOVERY_PASSWORD, null);
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
